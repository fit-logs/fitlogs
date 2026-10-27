package com.example.fitnotes.data

import androidx.room.withTransaction
import java.util.Locale

data class HistoryImportOutcome(val added:Int,val updated:Int,val alreadyImported:Int,val measurements:Int=0) {
    val changed get()=added>0||updated>0||measurements>0
    val message get()=buildList {
        if(added>0)add("Added $added workouts")
        if(updated>0)add("Updated $updated workouts")
        if(alreadyImported>0)add("$alreadyImported already imported")
        if(measurements>0)add("Added $measurements measurements")
    }.joinToString("; ").ifBlank{"No new records"}+"."
}
class HistoryImportRepository(private val db: FitDatabase) {
    private val dao = db.importedDao()
    suspend fun alreadyImported(id: String) = dao.imported(id)

    suspend fun importHistory(plan: ImportPlan): HistoryImportOutcome = db.withTransaction {
        require(plan.requiredChoices.isEmpty()) { "Confirm ambiguous units before importing." }
        require(plan.sets.isNotEmpty() || plan.body.isNotEmpty()) { "No valid history records to import." }
        if (dao.imported(plan.document.id) != null) return@withTransaction HistoryImportOutcome(0,0,plan.workouts.size)
        val source = HistoryImport(plan.document.id, plan.document.source, plan.document.fileName, System.currentTimeMillis(),
            plan.units, plan.issues.size, plan.document.unsupported.joinToString("\n"), plan.document.metadataJson)
        // The preview and the mutation use the same validated immutable records.
        BackupCodec.validateImported(listOf(source), plan.workouts, plan.sets, plan.body)
        plan.sets.map { it.exercise }.distinct().forEach { rawName ->
            // Keep the verbatim name in imported records. The searchable library
            // follows the existing local name rules without renaming user entries.
            val name = runCatching { InputRules.exerciseName(rawName) }.getOrNull()
            if (name != null) {
                val key = name.lowercase(Locale.ROOT)
                if (db.dao().exerciseByKey(key) == null && matchPlanExercise(name,db.dao().exercises(),aliases=aliasMap(db.trainingDao().aliases())).candidates.singleOrNull()==null) db.dao().insertExercise(Exercise(name = name, nameKey = key))
            }
        }
        val delta=reconcileHistory(plan,dao.imports(),dao.workouts(),dao.sets(),dao.body())
        val changedParents=delta.updates.filter{update->dao.workouts().firstOrNull{it.id==update.id}!=update}.map{it.id}.toSet()
        dao.insertImport(source)
        dao.insertWorkouts(delta.workouts)
        delta.updates.forEach{dao.updateWorkout(it)}
        dao.insertSets(delta.sets)
        dao.insertBody(delta.body)
        val updatedIds=delta.sets.map{it.workoutId}.filter{it !in delta.workouts.map{w->w.id}}.toSet()+changedParents
        HistoryImportOutcome(delta.workouts.size,updatedIds.size,plan.workouts.size-delta.workouts.size-updatedIds.size,delta.body.size)
    }

    suspend fun repairExactDuplicates(beforeRepair:suspend()->Unit):Int = db.withTransaction {
        val candidates=historyMergeCandidates(dao.details(),dao.imports()).filter{safeExactMerge(it)}
        if(candidates.isEmpty())return@withTransaction 0
        beforeRepair()
        var merged=0
        for(candidate in candidates) {
            val first=dao.workout(candidate.first.workout.id) ?: continue
            val second=dao.workout(candidate.second.workout.id) ?: continue
            val fresh=runCatching{historyMergePreview(first,second)}.getOrNull() ?: continue
            if(safeExactMerge(fresh)) { merge(fresh);merged++ }
        }
        merged
    }
    private suspend fun safeExactMerge(preview:HistoryMergePreview):Boolean {
        val a=preview.first;val b=preview.second
        if(preview.repeated!=a.sets.size||preview.repeated!=b.sets.size||a.workout.date!=b.workout.date)return false
        if(a.workout.recordedStart!=null&&b.workout.recordedStart!=null&&a.workout.recordedStart!=b.workout.recordedStart)return false
        if(a.workout.recordedEnd!=null&&b.workout.recordedEnd!=null&&a.workout.recordedEnd!=b.workout.recordedEnd)return false
        val mappings=db.trainingDao().mappings()
        val first=mappings.filter{it.importId==a.workout.importId}.associateBy{it.sourceName to it.category}
        return mappings.filter{it.importId==b.workout.importId}.all{row->first[row.sourceName to row.category]?.exerciseId?.let{it==row.exerciseId}!=false}
    }

    /** Caller makes a recoverable backup before invoking this transaction. */
    suspend fun merge(preview:HistoryMergePreview) = db.withTransaction {
        val first=requireNotNull(dao.workout(preview.first.workout.id))
        val second=requireNotNull(dao.workout(preview.second.workout.id))
        val fresh=historyMergePreview(first,second)
        require(fresh.fingerprint==preview.fingerprint){"Workout data changed. Review the merge again."}
        val mappings=db.trainingDao().mappings()
        val destination=mappings.filter{it.importId==first.workout.importId}.associateBy{it.sourceName to it.category}
        mappings.filter{it.importId==second.workout.importId}.forEach{row->
            val existing=destination[row.sourceName to row.category]
            require(existing==null || existing.exerciseId==row.exerciseId){"These imports use different exercise mappings. Review the mappings before merging."}
            if(existing==null)db.trainingDao().putMapping(row.copy(importId=first.workout.importId))
        }
        val known=first.sets.mapNotNull{s->nativeSetIdentity(s)?.let{it to s}}.toMap()
        val distinct=second.sets.filter{set->nativeSetIdentity(set)?.let{key->known[key]?.let{sameActualSet(it,set)}}!=true}
        // Exact source-identical set removal must also preserve running associations.
        // Distinct rows keep their IDs when moved and need no remapping.
        val runKeys=second.sets.mapNotNull { set ->
            val kept=nativeSetIdentity(set)?.let { known[it] }
            if(kept!=null && sameActualSet(kept,set)) "fitnotes:${set.id}" to "fitnotes:${kept.id}" else null
        }.toMap()
        db.runDao().all().forEach { link ->
            val canonical=runKeys[link.canonicalKey] ?: link.canonicalKey
            val retired=runKeys[link.retiredKey] ?: link.retiredKey
            if(canonical!=link.canonicalKey || retired!=link.retiredKey)
                db.runDao().put(link.copy(canonicalKey=canonical,retiredKey=retired.takeUnless{it==canonical}.orEmpty()))
        }
        var order=(first.sets.maxOfOrNull{it.sourceOrder} ?: -1)+1
        distinct.sortedBy{it.sourceOrder}.forEach{dao.updateSet(it.copy(workoutId=first.workout.id,sourceOrder=order++))}
        val health=db.healthDao()
        second.health.forEach{health.updateSession(it.session.copy(importedWorkoutId=first.workout.id))}
        val start=first.workout.recordedStart ?: second.workout.recordedStart
        val end=first.workout.recordedEnd ?: second.workout.recordedEnd
        val notes=listOf(first.workout.notes,second.workout.notes).filter{it.isNotBlank()}.distinct().joinToString("\n\n")
        dao.updateWorkout(first.workout.copy(notes=notes,recordedStart=start,recordedEnd=end))
        // Import rows remain as provenance/tombstones. A repeat of either file is a no-op.
        dao.deleteWorkout(second.workout.id)
    }

    suspend fun repeatWorkout(id: String): Long = db.withTransaction {
        val local = db.dao()
        require(local.active() == null) { "Finish or delete the unfinished workout before repeating." }
        val source = requireNotNull(dao.workout(id)) { "Imported workout no longer exists." }
        val workoutId = local.insertWorkout(Workout(startedAt = System.currentTimeMillis()))
        val names = source.sets.sortedBy { it.sourceOrder }.map { it.exercise }.distinct()
        names.forEachIndexed { position, rawName ->
            val name = InputRules.exerciseName(rawName)
            val key = name.lowercase(Locale.ROOT)
            val exerciseId = db.trainingDao().mappings().firstOrNull { it.importId==source.workout.importId && it.sourceName==rawName && source.sets.filter { set->set.exercise==rawName }.map{set->set.category}.distinct().singleOrNull()==it.category }?.exerciseId ?: local.exerciseByKey(key)?.id ?: matchPlanExercise(name,local.exercises(),aliases=aliasMap(db.trainingDao().aliases())).candidates.singleOrNull()?.id ?: local.insertExercise(Exercise(name = name, nameKey = key))
            local.insertEntry(WorkoutExercise(workoutId = workoutId, exerciseId = exerciseId, position = position))
        }
        // No original records are copied into completed/local sets.
        workoutId
    }

    suspend fun updateRecord(id: String, weight: String, reps: String, distance: String, unit: String?, seconds: String, comment: String) = db.withTransaction {
        val old = requireNotNull(dao.set(id)) { "Record no longer exists." }
        fun numeric(raw: String): Double? {
            if (raw.isBlank()) return null
            require(Regex("[0-9]+(\\.[0-9]+)?").matches(raw.trim())) { "Enter a non-negative number." }
            return requireNotNull(raw.trim().toDoubleOrNull()).also { require(it.isFinite()) { "Value is out of range." } }
        }
        val kg = numeric(weight)
        val count = if (reps.isBlank()) null else {
            require(Regex("[0-9]+").matches(reps.trim())) { "Enter whole reps." }
            requireNotNull(reps.trim().toIntOrNull())
        }
        val dist = numeric(distance)
        require(comment.length <= 10000) { "Comment is too long." }
        val value = old.copy(weightKg = kg, reps = count, distance = dist, distanceUnit = if (dist == null) null else unit,
            durationSeconds = numeric(seconds), comment = comment)
        val workout = requireNotNull(dao.workout(old.workoutId)).workout
        val source = requireNotNull(dao.imported(workout.importId))
        BackupCodec.validateImported(listOf(source), listOf(workout), listOf(value), emptyList())
        dao.updateSet(value)
    }
    suspend fun duplicateRecord(id: String) = db.withTransaction {
        val old = requireNotNull(dao.set(id)) { "Record no longer exists." }
        val workout = requireNotNull(dao.workout(old.workoutId))
        val order = (workout.sets.maxOfOrNull { it.sourceOrder } ?: -1) + 1
        val newId = java.util.UUID.randomUUID().toString()
        dao.insertSet(old.copy(id = workout.workout.importId + ":local:" + newId, sourceRecordId = "local:" + newId, sourceOrder = order))
    }
    suspend fun deleteRecord(id: String) = db.withTransaction {
        val old=requireNotNull(dao.set(id)){"Set no longer exists."};dao.deleteSet(id);old
    }
    suspend fun restoreDeletedRecord(old: ImportedSet) = db.withTransaction {
        require(dao.workout(old.workoutId)!=null){"The workout was removed; the set cannot be restored."}
        require(dao.set(old.id)==null){"This set is already present."}
        if(dao.sets().any{it.workoutId==old.workoutId&&it.sourceOrder==old.sourceOrder}) dao.sets().filter{it.workoutId==old.workoutId&&it.sourceOrder>=old.sourceOrder}.sortedByDescending{it.sourceOrder}.forEach{dao.updateSet(it.copy(sourceOrder=it.sourceOrder+1))}
        dao.insertSet(old)
    }
    suspend fun deleteImportedWorkout(id: String) = dao.deleteWorkout(id)
    suspend fun saveNotes(id: String, notes: String) = db.withTransaction {
        require(notes.length <= 10000) { "Notes are too long." }
        val old = requireNotNull(dao.workout(id)).workout
        dao.updateWorkout(old.copy(notes = notes))
    }

    suspend fun addRecord(workoutId: String, exercise: String, category: String, weight: String, reps: String,
        distance: String, unit: String?, seconds: String, comment: String) = db.withTransaction {
        val workout = requireNotNull(dao.workout(workoutId)) { "Session no longer exists." }
        val newId = java.util.UUID.randomUUID().toString()
        val record = ImportedSet(workout.workout.importId + ":local:" + newId, workoutId, "local:" + newId,
            (workout.sets.maxOfOrNull { it.sourceOrder } ?: -1) + 1, exercise, category, optionalNonNegative(weight),
            if (reps.isBlank()) null else requireNotNull(reps.toIntOrNull()) { "Enter whole reps." },
            optionalNonNegative(distance), if (distance.isBlank()) null else unit, optionalNonNegative(seconds), comment, "{}")
        val source = requireNotNull(dao.imported(workout.workout.importId))
        require(comment.length <= 10000) { "Comment is too long." }
        BackupCodec.validateImported(listOf(source), listOf(workout.workout), listOf(record), emptyList())
        dao.insertSet(record)
    }

    suspend fun saveMeasurement(oldId: String?, name: String, value: String, unit: String, unitName: String,
        date: String, time: String, comment: String) = db.withTransaction {
        val old = oldId?.let { requireNotNull(dao.measurement(it)) { "Measurement no longer exists." } }
        val number = requireNotNull(value.trim().replace(',', '.').toDoubleOrNull()) { "Enter a numeric measurement." }
        require(number.isFinite() && name.isNotBlank() && name.length <= 500 && unit.length <= 50 && unitName.length <= 100 && comment.length <= 10000) { "Check the measurement name, value, unit and comment." }
        if (isWeightName(old?.name ?: name) && canonicalWeightUnit(unit) != null) require(weightKg(number, unit) != null) { "Weight must be positive and finite." }
        require(validHistoryDate(date)) { "Use a valid measurement date." }
        val savedTime = requireNotNull(normalizeMeasurementTime(time)) { "Use a valid HH:mm or HH:mm:ss time, or leave time blank for date-only." }
        val sourceId = old?.importId ?: "manual-body-measurements"
        if (dao.imported(sourceId) == null) dao.insertImport(HistoryImport(sourceId, "Manual measurements", "", System.currentTimeMillis(), "Units stored with each measurement", 0, "", "{}"))
        val id = old?.id ?: java.util.UUID.randomUUID().toString()
        val lastCreated = dao.body().maxOfOrNull { it.createdAt } ?: 0
        require(lastCreated < Long.MAX_VALUE - 1) { "Measurement creation order is out of range." }
        val creationOrder = old?.createdAt ?: maxOf(System.currentTimeMillis(), lastCreated + 1)
        val record = BodyMeasurement(id, sourceId, old?.sourceRecordId ?: "local:" + id, date, savedTime,
            old?.name ?: name.trim(), number, if (old == null || isWeightName(old.name)) unit.trim() else old.unit, if (old == null || isWeightName(old.name)) unitName.trim() else old.unitName, comment, old?.rawValues ?: "{}", creationOrder)
        if (old == null) dao.insertMeasurement(record) else dao.updateBody(record)
    }

    suspend fun deleteMeasurement(id: String) = dao.deleteMeasurement(id)
}
