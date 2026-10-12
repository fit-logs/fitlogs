package com.example.fitnotes.data

import androidx.room.withTransaction
import java.util.Locale

class HistoryImportRepository(private val db: FitDatabase) {
    private val dao = db.importedDao()
    suspend fun alreadyImported(id: String) = dao.imported(id)

    suspend fun importHistory(plan: ImportPlan): Boolean = db.withTransaction {
        require(plan.requiredChoices.isEmpty()) { "Confirm ambiguous units before importing." }
        require(plan.sets.isNotEmpty() || plan.body.isNotEmpty()) { "No valid history records to import." }
        if (dao.imported(plan.document.id) != null) return@withTransaction false
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
                if (db.dao().exerciseByKey(key) == null) db.dao().insertExercise(Exercise(name = name, nameKey = key))
            }
        }
        dao.insertImport(source)
        dao.insertWorkouts(plan.workouts)
        dao.insertSets(plan.sets)
        dao.insertBody(plan.body)
        true
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
            val exerciseId = local.exerciseByKey(key)?.id ?: local.insertExercise(Exercise(name = name, nameKey = key))
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
    suspend fun deleteRecord(id: String) = dao.deleteSet(id)
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
        require(validHistoryDate(date) && (time.isEmpty() || validHistoryTime(time))) { "Use a valid date and HH:mm:ss time, or leave time blank for date-only." }
        val sourceId = old?.importId ?: "manual-body-measurements"
        if (dao.imported(sourceId) == null) dao.insertImport(HistoryImport(sourceId, "Manual measurements", "", System.currentTimeMillis(), "Units stored with each measurement", 0, "", "{}"))
        val id = old?.id ?: java.util.UUID.randomUUID().toString()
        val record = BodyMeasurement(id, sourceId, old?.sourceRecordId ?: "local:" + id, date, time,
            old?.name ?: name.trim(), number, old?.unit ?: unit.trim(), old?.unitName ?: unitName.trim(), comment, old?.rawValues ?: "{}")
        if (old == null) dao.insertMeasurement(record) else dao.updateBody(record)
    }

    suspend fun deleteMeasurement(id: String) = dao.deleteMeasurement(id)
}
