package com.example.fitnotes.data

import androidx.room.withTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SetValues(val weightKg: Double, val reps: Int)

object InputRules {
    const val MAX_WEIGHT = 10000.0
    const val MAX_REPS = 10000

    fun parseSet(weight: String, reps: String): SetValues {
        val normalized = weight.trim().replace(',', '.')
        require(Regex("[0-9]+(\\.[0-9]+)?").matches(normalized)) { "Enter a valid weight in kg (zero is allowed)." }
        require(Regex("[0-9]+").matches(reps.trim())) { "Enter a whole number of reps." }
        val kg = normalized.toDoubleOrNull()
        val count = reps.trim().toIntOrNull()
        require(kg != null && kg.isFinite() && kg in 0.0..MAX_WEIGHT) { "Weight must be between 0 and 10,000 kg." }
        require(count != null && count in 1..MAX_REPS) { "Reps must be between 1 and 10,000." }
        return SetValues(kg, count)
    }

    fun exerciseName(raw: String): String {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        require(name.isNotEmpty() && name.length <= 100 && name.none { it.isISOControl() }) {
            "Exercise names must contain 1–100 readable characters."
        }
        return name
    }
}

fun weightText(weight: Double): String =
    java.math.BigDecimal.valueOf(weight).stripTrailingZeros().toPlainString()

fun optionalNonNegative(raw: String): Double? {
    if (raw.isBlank()) return null
    val text = raw.trim().replace(',', '.')
    require(Regex("[0-9]+(\\.[0-9]+)?").matches(text)) { "Enter a non-negative number." }
    return requireNotNull(text.toDoubleOrNull()).also { require(it.isFinite() && it >= 0) { "Value is out of range." } }
}

fun localSetText(set: WorkoutSet): String = buildList {
    if (set.reps > 0) add(when (set.loadMode) {
        LoadMode.BODYWEIGHT -> "Bodyweight + ${weightText(set.weightKg)} kg × ${set.reps} reps"
        LoadMode.ASSISTANCE -> "${weightText(set.weightKg)} kg assistance × ${set.reps} reps"
        LoadMode.REPS_ONLY -> "${set.reps} reps (reps only)"
        LoadMode.UNKNOWN -> if(set.weightSpecified) "${weightText(set.weightKg)} kg × ${set.reps} reps (load mode unknown)" else "${set.reps} reps · load unspecified"
        else -> "${weightText(set.weightKg)} kg × ${set.reps} reps"
    })
    set.distance?.let { add(weightText(it) + " " + set.distanceUnit) }
    set.durationSeconds?.let { add(weightText(it) + " seconds") }
}.joinToString(" · ")

fun orderedEntries(workout: WorkoutDetail) = workout.entries.sortedWith(compareBy({ it.entry.position }, { it.entry.id }))
fun orderedSets(entry: EntryDetail) = entry.sets.sortedWith(compareBy({ it.position }, { it.id }))

fun lastCompleted(
    workouts: List<WorkoutDetail>, exerciseId: Long, excludingWorkoutId: Long,
): Pair<WorkoutDetail, EntryDetail>? {
    val previous = workouts.filter { it.workout.id != excludingWorkoutId && it.workout.isComplete }
        .sortedWith(compareByDescending<WorkoutDetail> { it.workout.completedAt }.thenByDescending { it.workout.id })
    for (workout in previous) {
        val entry = workout.entries.firstOrNull { it.exercise.id == exerciseId && it.sets.isNotEmpty() }
        if (entry != null) return workout to entry
    }
    return null
}

fun sessionText(workouts: List<WorkoutDetail>): String {
    val date = SimpleDateFormat("HH:mm:ss XXX", Locale.getDefault())
    return buildString {
        appendLine("FitNotes training context")
        if (workouts.isEmpty()) appendLine("No completed sessions in this period.")
        workouts.forEach { detail ->
            val workout = detail.workout
            appendLine()
            appendLine(workout.title.ifBlank { activityName(workout) } + " · " + localWorkoutDate(workout) +
                (workout.knownStart?.let { " · " + date.format(Date(it)) } ?: " · start time unknown"))
            appendLine("Activity: " + activityName(workout))
            appendLine((if (workout.sessionDate.isNotBlank() && localDuration(workout) != null) "Reported duration: " else "Local elapsed wall time: ") + durationText(localDuration(workout)))
            if (!workout.isComplete) appendLine("In progress · saved data only")
            workout.effort?.let { appendLine("Effort: " + it) }
            workout.distance?.let { appendLine("Local distance: " + weightText(it) + " " + workout.distanceUnit) }
            orderedEntries(detail).forEach { entry ->
                appendLine(entry.exercise.name)
                orderedSets(entry).forEachIndexed { index, set ->
                    appendLine("  " + (index + 1) + ". " + localSetText(set))
                    if (set.comment.isNotBlank()) appendLine("    Comment: " + set.comment)
                }
                if (entry.sets.isEmpty()) appendLine("  No saved sets")
            }
            if (workout.notes.isNotBlank()) appendLine("Notes: " + workout.notes)
            appendLine(healthContext(detail.health))
        }
    }.trim()
}

class WorkoutRepository(val database: FitDatabase, private val restClock: RestClock? = null) {
    private val dao = database.dao()
    val exercises = dao.observeExercises()
    val workouts = dao.observeWorkouts()

    suspend fun start(): Long = database.withTransaction {
        dao.active()?.id ?: dao.insertWorkout(Workout(startedAt = System.currentTimeMillis()))
    }

    suspend fun startActivity(type: String, label: String, title: String, notes: String, effort: String?): Long = database.withTransaction {
        require(dao.active() == null) { "Resume, finish or delete the unfinished session before starting another." }
        validateActivity(type, label, title, notes, effort, null, null)
        dao.insertWorkout(Workout(startedAt = System.currentTimeMillis(), activityType = type,
            activityLabel = label.trim(), title = title.trim(), notes = notes.trim(), effort = effort))
    }

    suspend fun logPast(type: String, label: String, date: String, start: Long?, seconds: Double,
        title: String, notes: String, effort: String?, distance: Double?, unit: String?): Long = database.withTransaction {
        require(dao.active() == null) { "Finish or delete the unfinished session before logging a past activity." }
        require(validHistoryDate(date) && seconds.isFinite() && seconds > 0 && seconds <= 366 * 86400.0) { "Enter a valid date and positive duration." }
        require(start == null || (start > 0 && localWorkoutDate(Workout(startedAt = start)) == date)) { "Start time does not match the activity date." }
        validateActivity(type, label, title, notes, effort, distance, unit)
        dao.insertWorkout(Workout(startedAt = start ?: 0L, completedAt = start?.let { it + (seconds * 1000).toLong() },
            title = title.trim(), notes = notes.trim(), activityType = type, activityLabel = label.trim(), sessionDate = date,
            finished = true, durationSeconds = seconds, effort = effort, distance = distance, distanceUnit = if (distance == null) null else unit))
    }

    suspend fun saveActivityDetails(id: Long, title: String, notes: String, effort: String?, distance: Double?, unit: String?) = database.withTransaction {
        val old = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        validateActivity(old.activityType, old.activityLabel, title, notes, effort, distance, unit)
        dao.updateWorkout(old.copy(title = title.trim(), notes = notes.trim(), effort = effort,
            distance = distance, distanceUnit = if (distance == null) null else unit))
    }

    private fun validateActivity(type: String, label: String, title: String, notes: String, effort: String?, distance: Double?, unit: String?) {
        require(ActivityType.entries.any { it.key == type }) { "Choose an activity." }
        require(type != "other" || (label.trim().isNotEmpty() && label.length <= 100)) { "Enter a custom activity label." }
        require(title.length <= 200 && notes.length <= 10000 && effort in listOf(null, "Easy", "Okay", "Hard")) { "Check the title, notes and effort." }
        require(distance == null || (type in listOf("running", "walking") && distance.isFinite() && distance >= 0 && unit in listOf("m", "km", "ft", "mi"))) { "Check distance and unit." }
    }

    suspend fun addExercise(workoutId: Long, exerciseId: Long) = database.withTransaction {
        requireNotNull(dao.workout(workoutId)) { "Session no longer exists." }
        requireNotNull(dao.exercise(exerciseId)) { "Exercise no longer exists." }
        val entries = dao.workoutEntries(workoutId)
        if (entries.none { it.exerciseId == exerciseId }) {
            dao.insertEntry(WorkoutExercise(workoutId = workoutId, exerciseId = exerciseId, position = (entries.maxOfOrNull { it.position } ?: -1) + 1))
        }
    }

    suspend fun addExercises(workoutId: Long, exerciseIds: List<Long>) = database.withTransaction {
        requireNotNull(dao.workout(workoutId)) { "Session no longer exists." }
        // Validate all selections before changing the workout; retain selection order.
        exerciseIds.distinct().forEach { requireNotNull(dao.exercise(it)) { "Exercise no longer exists." } }
        exerciseIds.distinct().forEach { addExercise(workoutId, it) }
    }

    suspend fun createExercise(workoutId: Long, rawName: String) = database.withTransaction {
        val name = InputRules.exerciseName(rawName)
        val key = name.lowercase(Locale.ROOT)
        val id = dao.exerciseByKey(key)?.id ?: dao.insertExercise(Exercise(name = name, nameKey = key))
        addExercise(workoutId, id)
    }

    suspend fun saveSet(entryId: Long, weight: String, reps: String, setId: Long? = null, comment: String = "",
        distance: String? = null, distanceUnit: String? = null, seconds: String? = null,
        targetId: Long? = null, loadMode: String? = null, isWarmUp: Boolean? = null): Long = database.withTransaction {
        val entry = requireNotNull(dao.entry(entryId)) { "Exercise no longer belongs to this session." }
        val old = setId?.let { requireNotNull(dao.set(it)) { "Set no longer exists." } }
        require(old == null || old.entryId == entryId) { "Set belongs to another exercise." }
        require(old==null || targetId==null || old.targetId==targetId) { "Editing a result cannot assign it to a different target." }
        val mode = loadMode ?: old?.loadMode ?: LoadMode.EXTERNAL
        require(mode in LoadMode.values) { "Choose a valid load mode." }
        val values = InputRules.parseSet(if (mode == LoadMode.REPS_ONLY || (mode == LoadMode.UNKNOWN && weight.isBlank())) "0" else weight, reps)
        require(mode != LoadMode.REPS_ONLY || values.weightKg == 0.0) { "Reps-only sets have no external load." }
        require(comment.length <= 10000) { "Comment is too long." }
        val link = old?.targetId ?: targetId
        val target = link?.let { requireNotNull(database.trainingDao().target(it)) { "Target no longer exists." } }
        require(target == null || (target.entryId == entryId && !target.skipped)) { "Choose an unskipped target in this exercise." }
        if (old == null && link != null) database.trainingDao().result(link)?.let { return@withTransaction it.id }
        val dist = distance?.let(::optionalNonNegative); val duration = seconds?.let(::optionalNonNegative)
        if (dist != null) require(distanceUnit in listOf("m", "km", "ft", "mi")) { "Choose a distance unit." }
        if (old == null) {
            val workout = requireNotNull(dao.workout(entry.workoutId))
            dao.insertSet(WorkoutSet(entryId = entryId, weightKg = values.weightKg, reps = values.reps,
                position = (dao.entrySets(entryId).maxOfOrNull { it.position } ?: -1) + 1, comment = comment,
                distance = dist, distanceUnit = if (dist == null) null else distanceUnit, durationSeconds = duration,
                targetId = link, completedAt = if (!workout.isComplete && workout.knownStart != null) System.currentTimeMillis() else null,
                loadMode = mode, isWarmUp = target?.isWarmUp ?: isWarmUp ?: false, weightSpecified = mode != LoadMode.REPS_ONLY && weight.isNotBlank()))
        } else {
            dao.updateSet(old.copy(weightKg = values.weightKg, reps = values.reps, comment = comment, loadMode = mode, isWarmUp = isWarmUp ?: old.isWarmUp, weightSpecified = mode != LoadMode.REPS_ONLY && weight.isNotBlank(),
                distance = if (distance == null) old.distance else dist,
                distanceUnit = if (distance == null) old.distanceUnit else if (dist == null) null else distanceUnit,
                durationSeconds = if (seconds == null) old.durationSeconds else duration))
            old.id
        }
    }

    suspend fun duplicateSet(id: Long) = database.withTransaction {
        val old = requireNotNull(dao.set(id)) { "Set no longer exists." }
        val next = (dao.entrySets(old.entryId).maxOfOrNull { it.position } ?: -1) + 1
        dao.insertSet(old.copy(id = 0, position = next, targetId = null, completedAt =
            if (dao.workout(dao.entry(old.entryId)!!.workoutId)?.isComplete == false) System.currentTimeMillis() else null))
    }

    suspend fun saveCardio(entryId: Long, distance: String, unit: String, seconds: String, comment: String, setId: Long? = null) = database.withTransaction {
        requireNotNull(dao.entry(entryId)) { "Exercise no longer belongs to this session." }
        val dist = optionalNonNegative(distance)
        val duration = optionalNonNegative(seconds)
        require(dist != null || duration != null) { "Enter a distance or duration." }
        require(unit in listOf("m", "km", "ft", "mi") && comment.length <= 10000) { "Check the distance unit and comment." }
        val old = setId?.let { requireNotNull(dao.set(it)) { "Set no longer exists." } }
        require(old == null || old.entryId == entryId) { "Set belongs to another exercise." }
        val position = old?.position ?: ((dao.entrySets(entryId).maxOfOrNull { it.position } ?: -1) + 1)
        dao.insertOrUpdateCardio(old, WorkoutSet(id = old?.id ?: 0, entryId = entryId, weightKg = 0.0, reps = 0,
            position = position, distance = dist, distanceUnit = if (dist == null) null else unit, durationSeconds = duration, comment = comment))
    }

    private suspend fun FitDao.insertOrUpdateCardio(old: WorkoutSet?, value: WorkoutSet) {
        if (old == null) insertSet(value) else updateSet(value)
    }

    suspend fun deleteSet(id: Long) = database.withTransaction {
        val target = dao.set(id)?.targetId
        dao.deleteSet(id)
        if (target != null && database.trainingDao().timer()?.sourceTargetId == target) database.trainingDao().clearTimer()
    }
    suspend fun removeExercise(id: Long) = dao.deleteEntry(id)
    suspend fun reorderExercises(workoutId: Long, entryIds: List<Long>) = database.withTransaction {
        requireNotNull(dao.workout(workoutId)) { "Session no longer exists." }
        val entries = dao.workoutEntries(workoutId)
        require(entryIds.size == entries.size && entryIds.toSet().size == entryIds.size &&
            entryIds.toSet() == entries.map { it.id }.toSet()) {
            "The workout exercises changed. Close and reopen Reorder exercises to try again."
        }
        val byId = entries.associateBy { it.id }
        entryIds.forEachIndexed { position, id ->
            val entry = byId.getValue(id)
            if (entry.position != position) dao.updateEntry(entry.copy(position = position))
        }
    }
    suspend fun deleteWorkout(id: Long) = dao.deleteWorkout(id)

    suspend fun saveDetails(id: Long, title: String, notes: String) = database.withTransaction {
        require(title.length <= 200 && notes.length <= 10000) { "Title or notes are too long." }
        val workout = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        dao.updateWorkout(workout.copy(title = title.trim(), notes = notes.trim()))
    }

    suspend fun finish(id: Long) = database.withTransaction {
        val workout = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        if (workout.activityType == "strength") require(dao.workoutEntries(id).any { dao.entrySets(it.id).isNotEmpty() }) { "Save at least one set before finishing." }
        database.trainingDao().timer()?.takeIf { it.workoutId == id }?.let { database.trainingDao().clearTimer() }
        if (!workout.isComplete) {
            val end = maxOf(workout.startedAt, System.currentTimeMillis())
            dao.updateWorkout(workout.copy(completedAt = end, finished = true, durationSeconds = (end - workout.startedAt) / 1000.0))
        }
    }

    suspend fun repeat(id: Long): Long = database.withTransaction {
        require(dao.active() == null) { "Finish or delete the unfinished workout before repeating a session." }
        val source = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        require(source.isComplete) { "Only completed sessions can be repeated." }
        val newId = dao.insertWorkout(Workout(startedAt = System.currentTimeMillis(), title = source.title))
        dao.workoutEntries(id).forEachIndexed { position, entry ->
            dao.insertEntry(WorkoutExercise(workoutId = newId, exerciseId = entry.exerciseId, position = position))
        }
        newId
    }

    suspend fun backup(): Backup = database.withTransaction {
        val imported = database.importedDao()
        Backup(exercises = dao.exercises(), workouts = dao.workouts(), entries = dao.entries(), sets = dao.sets(),
            imports = imported.imports(), importedWorkouts = imported.workouts(), importedSets = imported.sets(), bodyMeasurements = imported.body(),
            healthSessions = database.healthDao().sessions(), healthSamples = database.healthDao().samples(),
            plannedWorkouts = database.planDao().plans(), plannedExercises = database.planDao().exercises(), handoffPreferences = database.planDao().preferences(),
            plannedSets = database.planDao().sets(), planRuns = database.planDao().runs(), runTargets = database.planDao().runTargets(), exerciseAliases = database.trainingDao().aliases(), exerciseMappings = database.trainingDao().mappings(),
            exerciseRedirects = database.trainingDao().redirects(), exerciseProgression = database.trainingDao().progression(), trainingPreferences = database.trainingDao().preferences(),
            restTimer = database.trainingDao().timer()?.let { timer -> timer.copy(running = false, remainingMillis = restClock?.let { timer.recovered(it).remaining(it) } ?: timer.remainingMillis, anchorElapsed = 0) })
    }

    suspend fun restore(backup: Backup) {
        // Validate again at the mutation boundary, including programmatic callers.
        BackupCodec.validate(backup)
        database.withTransaction {
            database.planDao().clearPlans()
            if (backup.version >= 5) database.planDao().clearPreferences()
            database.healthDao().clear()
            database.importedDao().clear()
            dao.clearSets()
            dao.clearEntries()
            dao.clearWorkouts()
            dao.clearExercises()
            dao.insertExercises(backup.exercises)
            dao.insertWorkouts(backup.workouts)
            dao.insertEntries(backup.entries)
            val imported = database.importedDao()
            imported.insertImports(backup.imports)
            imported.insertWorkouts(backup.importedWorkouts)
            imported.insertSets(backup.importedSets)
            imported.insertBody(backup.bodyMeasurements)
            backup.healthSessions.forEach { database.healthDao().putSession(it) }
            database.healthDao().putSamples(backup.healthSamples)
            backup.plannedWorkouts.forEach { database.planDao().insert(it) }
            database.planDao().insertTargets(backup.plannedExercises)
            if (backup.version >= 6) {
                database.planDao().insertSets(backup.plannedSets)
                backup.planRuns.forEach { database.planDao().insertRun(it) }
                database.planDao().insertRunTargets(backup.runTargets)
            } else {
                database.planDao().insertSets(backup.plannedExercises.flatMap { exercise -> List(exercise.sets) { position ->
                    PlannedSet(plannedExerciseId = exercise.id, position = position, repsMin = exercise.repsMin, repsMax = exercise.repsMax,
                        weightValue = exercise.weightValue, weightUnit = exercise.weightUnit, restSeconds = null)
                } })
                // Earlier plans already linked to an actual workout keep their targets as a snapshot.
                backup.plannedWorkouts.filter { it.startedWorkoutId != null }.forEach { plan ->
                    database.planDao().insertRun(WorkoutPlanRun(plan.startedWorkoutId!!, plan.id, plan.title, plan.warmUp, plan.legacyGuidance))
                    backup.entries.filter { it.workoutId == plan.startedWorkoutId }.forEach { entry ->
                        val target = backup.plannedExercises.firstOrNull { it.planId == plan.id && it.exerciseId == entry.exerciseId }
                        if (target != null) {
                            dao.updateEntry(entry.copy(planNote = target.note))
                            database.planDao().insertRunTargets(List(target.sets) { position ->
                            WorkoutSetTarget(entryId = entry.id, position = position, repsMin = target.repsMin, repsMax = target.repsMax,
                                weightValue = target.weightValue, weightUnit = target.weightUnit, restSeconds = null)
                        })
                        }
                    }
                }
            }
            dao.insertSets(backup.sets)
            val training = database.trainingDao()
            backup.exerciseAliases.forEach { training.putAlias(it) }; backup.exerciseMappings.forEach { training.putMapping(it) }
            backup.exerciseRedirects.forEach { training.putRedirect(it) }; backup.exerciseProgression.forEach { training.putProgression(it) }
            // A fresh database must not reuse an ID retired by a merge. The
            // restored survivors alone may have a lower AUTOINCREMENT sequence.
            backup.exerciseRedirects.maxOfOrNull { it.oldId }?.let { retiredId ->
                database.openHelper.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq = max(seq, ?) WHERE name = 'exercises'", arrayOf(retiredId))
            }
            if (backup.version >= 7) training.clearPreferences()
            backup.trainingPreferences?.let { training.putPreferences(it) }
            backup.restTimer?.let { training.putTimer(it.copy(token=java.util.UUID.randomUUID().toString(),running = false, bootCount = -1, anchorElapsed = 0)) }
            backup.handoffPreferences?.let { database.planDao().putPreferences(it) }
        }
    }
}
