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
    if (set.reps > 0) add(weightText(set.weightKg) + " kg × " + set.reps + " reps")
    set.distance?.let { add(weightText(it) + " " + set.distanceUnit) }
    set.durationSeconds?.let { add(weightText(it) + " seconds") }
}.joinToString(" · ")

fun orderedEntries(workout: WorkoutDetail) = workout.entries.sortedWith(compareBy({ it.entry.position }, { it.entry.id }))
fun orderedSets(entry: EntryDetail) = entry.sets.sortedWith(compareBy({ it.position }, { it.id }))

fun lastCompleted(
    workouts: List<WorkoutDetail>, exerciseId: Long, excludingWorkoutId: Long,
): Pair<WorkoutDetail, EntryDetail>? {
    val previous = workouts.filter { it.workout.id != excludingWorkoutId && it.workout.completedAt != null }
        .sortedWith(compareByDescending<WorkoutDetail> { it.workout.completedAt }.thenByDescending { it.workout.id })
    for (workout in previous) {
        val entry = workout.entries.firstOrNull { it.exercise.id == exerciseId && it.sets.isNotEmpty() }
        if (entry != null) return workout to entry
    }
    return null
}

fun sessionText(workouts: List<WorkoutDetail>): String {
    val date = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())
    return buildString {
        appendLine("FitNotes training context · kg")
        if (workouts.isEmpty()) appendLine("No completed sessions in this period.")
        workouts.forEach { detail ->
            val workout = detail.workout
            appendLine()
            appendLine(workout.title.ifBlank { "Workout" } + " · " + date.format(Date(workout.startedAt)))
            if (workout.completedAt == null) appendLine("In progress · saved sets only")
            orderedEntries(detail).forEach { entry ->
                appendLine(entry.exercise.name)
                orderedSets(entry).forEachIndexed { index, set ->
                    appendLine("  " + (index + 1) + ". " + localSetText(set))
                    if (set.comment.isNotBlank()) appendLine("    Comment: " + set.comment)
                }
                if (entry.sets.isEmpty()) appendLine("  No saved sets")
            }
            if (workout.notes.isNotBlank()) appendLine("Notes: " + workout.notes)
        }
    }.trim()
}

class WorkoutRepository(val database: FitDatabase) {
    private val dao = database.dao()
    val exercises = dao.observeExercises()
    val workouts = dao.observeWorkouts()

    suspend fun start(): Long = database.withTransaction {
        dao.active()?.id ?: dao.insertWorkout(Workout(startedAt = System.currentTimeMillis()))
    }

    suspend fun addExercise(workoutId: Long, exerciseId: Long) = database.withTransaction {
        requireNotNull(dao.workout(workoutId)) { "Session no longer exists." }
        requireNotNull(dao.exercise(exerciseId)) { "Exercise no longer exists." }
        val entries = dao.workoutEntries(workoutId)
        if (entries.none { it.exerciseId == exerciseId }) {
            dao.insertEntry(WorkoutExercise(workoutId = workoutId, exerciseId = exerciseId, position = (entries.maxOfOrNull { it.position } ?: -1) + 1))
        }
    }

    suspend fun createExercise(workoutId: Long, rawName: String) = database.withTransaction {
        val name = InputRules.exerciseName(rawName)
        val key = name.lowercase(Locale.ROOT)
        val id = dao.exerciseByKey(key)?.id ?: dao.insertExercise(Exercise(name = name, nameKey = key))
        addExercise(workoutId, id)
    }

    suspend fun saveSet(entryId: Long, weight: String, reps: String, setId: Long? = null, comment: String = "",
        distance: String? = null, distanceUnit: String? = null, seconds: String? = null) = database.withTransaction {
        requireNotNull(dao.entry(entryId)) { "Exercise no longer belongs to this session." }
        val values = InputRules.parseSet(weight, reps)
        require(comment.length <= 10000) { "Comment is too long." }
        val dist = distance?.let(::optionalNonNegative)
        val duration = seconds?.let(::optionalNonNegative)
        if (dist != null) require(distanceUnit in listOf("m", "km", "ft", "mi")) { "Choose a distance unit." }
        if (setId == null) {
            val next = (dao.entrySets(entryId).maxOfOrNull { it.position } ?: -1) + 1
            dao.insertSet(WorkoutSet(entryId = entryId, weightKg = values.weightKg, reps = values.reps, position = next, comment = comment,
                distance = dist, distanceUnit = if (dist == null) null else distanceUnit, durationSeconds = duration))
        } else {
            val old = requireNotNull(dao.set(setId)) { "Set no longer exists." }
            require(old.entryId == entryId) { "Set belongs to another exercise." }
            dao.updateSet(old.copy(weightKg = values.weightKg, reps = values.reps, comment = comment,
                distance = if (distance == null) old.distance else dist,
                distanceUnit = if (distance == null) old.distanceUnit else if (dist == null) null else distanceUnit,
                durationSeconds = if (seconds == null) old.durationSeconds else duration))
        }
    }

    suspend fun duplicateSet(id: Long) = database.withTransaction {
        val old = requireNotNull(dao.set(id)) { "Set no longer exists." }
        val next = (dao.entrySets(old.entryId).maxOfOrNull { it.position } ?: -1) + 1
        dao.insertSet(old.copy(id = 0, position = next))
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

    suspend fun deleteSet(id: Long) = dao.deleteSet(id)
    suspend fun removeExercise(id: Long) = dao.deleteEntry(id)
    suspend fun deleteWorkout(id: Long) = dao.deleteWorkout(id)

    suspend fun saveDetails(id: Long, title: String, notes: String) = database.withTransaction {
        require(title.length <= 200 && notes.length <= 10000) { "Title or notes are too long." }
        val workout = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        dao.updateWorkout(workout.copy(title = title.trim(), notes = notes.trim()))
    }

    suspend fun finish(id: Long) = database.withTransaction {
        val workout = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        require(dao.workoutEntries(id).any { dao.entrySets(it.id).isNotEmpty() }) { "Save at least one set before finishing." }
        if (workout.completedAt == null) {
            dao.updateWorkout(workout.copy(completedAt = maxOf(workout.startedAt, System.currentTimeMillis())))
        }
    }

    suspend fun repeat(id: Long): Long = database.withTransaction {
        require(dao.active() == null) { "Finish or delete the unfinished workout before repeating a session." }
        val source = requireNotNull(dao.workout(id)) { "Session no longer exists." }
        require(source.completedAt != null) { "Only completed sessions can be repeated." }
        val newId = dao.insertWorkout(Workout(startedAt = System.currentTimeMillis(), title = source.title))
        dao.workoutEntries(id).forEachIndexed { position, entry ->
            dao.insertEntry(WorkoutExercise(workoutId = newId, exerciseId = entry.exerciseId, position = position))
        }
        newId
    }

    suspend fun backup(): Backup = database.withTransaction {
        val imported = database.importedDao()
        Backup(exercises = dao.exercises(), workouts = dao.workouts(), entries = dao.entries(), sets = dao.sets(),
            imports = imported.imports(), importedWorkouts = imported.workouts(), importedSets = imported.sets(), bodyMeasurements = imported.body())
    }

    suspend fun restore(backup: Backup) {
        // Validate again at the mutation boundary, including programmatic callers.
        BackupCodec.validate(backup)
        database.withTransaction {
            database.importedDao().clear()
            dao.clearSets()
            dao.clearEntries()
            dao.clearWorkouts()
            dao.clearExercises()
            dao.insertExercises(backup.exercises)
            dao.insertWorkouts(backup.workouts)
            dao.insertEntries(backup.entries)
            dao.insertSets(backup.sets)
            val imported = database.importedDao()
            imported.insertImports(backup.imports)
            imported.insertWorkouts(backup.importedWorkouts)
            imported.insertSets(backup.importedSets)
            imported.insertBody(backup.bodyMeasurements)
        }
    }
}
