package com.example.fitnotes.data

import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

const val STALE_IDLE_MILLIS = 2 * 3600000L
const val STALE_MAX_MILLIS = 6 * 3600000L
const val STALE_GRACE_MILLIS = 15 * 60000L
val WORKOUT_LIFECYCLE_MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("lastActionAt", "autoFinishedAt", "resumeStartedAt", "staleSuppressedUntil")) db.execSQL("ALTER TABLE workouts ADD COLUMN $column INTEGER")
        db.execSQL("ALTER TABLE workouts ADD COLUMN resumeBaseSeconds REAL")
        for (column in listOf("timingEdited", "timingEstimated", "durationUnknown", "historicalDraft")) db.execSQL("ALTER TABLE workouts ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE workouts ADD COLUMN autoFinishAcknowledged INTEGER NOT NULL DEFAULT 1")
        for (column in listOf("timingNote", "draftSourceKey")) db.execSQL("ALTER TABLE workouts ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
    }
}
data class WorkoutTimeInput(val date: String, val start: String = "", val endDate: String = date, val end: String = "", val minutes: String = "")
data class WorkoutTiming(val date: String, val start: Long?, val end: Long?, val seconds: Double?)
fun parseWorkoutTiming(input: WorkoutTimeInput, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): WorkoutTiming {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    require(validHistoryDate(input.date) && LocalDate.parse(input.date) <= today) { "Choose today or a past date." }
    fun time(date: String, raw: String): Long? {
        if (raw.isBlank()) return null
        require(validHistoryDate(date)) { "Check the end date." }
        val explicit = runCatching { OffsetTime.parse(raw.trim()) }.getOrNull()
        if (explicit != null) return LocalDate.parse(date).atTime(explicit).toInstant().toEpochMilli().also { require(it in 1..now) { "Time must be in the past." } }
        val local = runCatching { LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(raw.trim())) }.getOrElse { throw IllegalArgumentException("Enter time as HH:mm; an explicit offset can be added.") }
        val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: throw IllegalArgumentException("This local time is ambiguous or nonexistent. Add its explicit offset, e.g. 02:30+13:00.")
        return local.atOffset(offset).toInstant().toEpochMilli().also { require(it in 1..now) { "Time must be in the past." } }
    }
    val start = time(input.date, input.start); var end = time(input.endDate, input.end)
    val reported = optionalNonNegative(input.minutes)?.also { require(it > 0 && it <= 366 * 1440.0) { "Duration must be positive and no longer than one year." } }?.times(60)
    require(end == null || input.endDate >= input.date) { "End date must be the workout date or later." }
    require(start == null || end == null || end >= start) { "End must follow start; choose the next end date for an overnight workout." }
    val interval = if (start != null && end != null) (end - start) / 1000.0 else null
    require(interval == null || reported == null || abs(interval - reported) <= 60) { "The duration disagrees with the start/end interval. Correct one or leave duration blank." }
    if (end == null && start != null && reported != null) end = Math.addExact(start, (reported * 1000).roundToLong()).also { require(it <= now) { "End time must be in the past." } }
    return WorkoutTiming(input.date, start, end, interval ?: reported)
}
fun activeWorkoutSeconds(w: Workout, now: Long): Double? {
    val anchor = w.resumeStartedAt ?: w.knownStart ?: return null
    if (now < anchor || w.resumeStartedAt != null && w.resumeBaseSeconds == null) return null
    val elapsed = (now - anchor) / 1000.0
    return (w.resumeBaseSeconds ?: 0.0) + elapsed
}

/** Wall clocks can change. Automatic ends are always estimated/unknown, reversible,
 * and bounded by saved actions; legacy sets never gain invented timestamps. */
class WorkoutLifecycleRepository(private val db: FitDatabase, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun reconcile(): Long? = db.withTransaction {
        val w = db.dao().active() ?: return@withTransaction null
        if (w.activityType != "strength" || w.historicalDraft) return@withTransaction null
        val anchor = w.resumeStartedAt ?: w.knownStart ?: return@withTransaction null
        val time = now(); if (time < anchor || w.staleSuppressedUntil?.let { it > time } == true) return@withTransaction null
        val sets = db.dao().workoutEntries(w.id).flatMap { db.dao().entrySets(it.id) }
        // A future saved action indicates a backwards wall-clock change. Hold
        // automatic repair until the clock/timing is reviewed; never end before it.
        if (w.lastActionAt?.let { it > time } == true || sets.any { it.completedAt?.let { action -> action > time } == true }) return@withTransaction null
        val actions = (sets.mapNotNull { it.completedAt } + listOfNotNull(w.lastActionAt?.takeIf { it > anchor })).filter { it in anchor..time }
        val last = actions.maxOrNull() ?: anchor
        val hardEnd = maxOf(anchor + STALE_MAX_MILLIS, w.staleSuppressedUntil ?: 0)
        if (time - last < STALE_IDLE_MILLIS && time < hardEnd) return@withTransaction null
        db.trainingDao().timer()?.takeIf { it.workoutId == w.id }?.let { db.trainingDao().clearTimer() }
        if (sets.isEmpty()) {
            db.dao().updateWorkout(w.copy(finished = true, historicalDraft = true, completedAt = null, durationSeconds = null,
                durationUnknown = true, autoFinishedAt = time, autoFinishAcknowledged = false,
                timingNote = "Abandoned empty session kept as an unsaved draft. No performed workout was invented."))
        } else {
            val end = actions.maxOrNull()?.let { action -> maxOf(action, minOf(time, hardEnd, action + STALE_GRACE_MILLIS)) }
            val duration = end?.let { if (w.resumeStartedAt != null) w.resumeBaseSeconds?.plus((it - anchor) / 1000.0) else (it - anchor) / 1000.0 }
            db.dao().updateWorkout(w.copy(finished = true, completedAt = end, durationSeconds = duration, durationUnknown = duration == null,
                timingEstimated = true, autoFinishedAt = time, autoFinishAcknowledged = false,
                timingNote = if (end == null) "Auto-finished after inactivity. No reliable last-action timestamp exists; end and duration remain unknown."
                    else "Auto-finished after inactivity/elapsed limit. End estimated from the last saved action plus up to 15 minutes; saved sets are unchanged."))
        }
        w.id
    }
    suspend fun acknowledge(id: Long) = db.withTransaction { db.dao().workout(id)?.let { db.dao().updateWorkout(it.copy(autoFinishAcknowledged = true)) } }
    suspend fun allowLongWorkout(id: Long) = db.withTransaction {
        val w = requireNotNull(db.dao().workout(id)); require(db.dao().active()?.id == id) { "Resume this workout first." }
        db.dao().updateWorkout(w.copy(staleSuppressedUntil = now() + STALE_MAX_MILLIS, lastActionAt = now()))
    }
    suspend fun resume(id: Long) = db.withTransaction {
        val w = requireNotNull(db.dao().workout(id)) { "Workout no longer exists." }
        require(db.dao().active()?.id.let { it == null || it == id }) { "Finish or discard the current workout before resuming this one." }
        require(w.knownStart != null) { "Set a known start time before resuming a live workout." }
        val base = if (w.historicalDraft && db.dao().workoutEntries(id).all { db.dao().entrySets(it.id).isEmpty() }) 0.0 else localDuration(w)
        db.dao().updateWorkout(w.copy(finished = false, historicalDraft = false, completedAt = null, durationSeconds = null,
            resumeStartedAt = now(), resumeBaseSeconds = base, durationUnknown = base == null, lastActionAt = now(),
            staleSuppressedUntil = now() + STALE_MAX_MILLIS, autoFinishAcknowledged = true,
            timingNote = "Resumed explicitly. The unlogged gap is excluded; prior unknown/estimated duration remains marked."))
    }
    suspend fun editTime(id: Long, input: WorkoutTimeInput) = db.withTransaction {
        val w = requireNotNull(db.dao().workout(id)); val t = parseWorkoutTiming(input, now())
        val finished = w.isComplete || w.historicalDraft || t.end != null || t.seconds != null
        require(finished || t.start != null) { "A running workout needs a known start. Save a completed dated entry for unknown timing." }
        db.dao().updateWorkout(w.copy(startedAt = t.start ?: 0, completedAt = t.end, sessionDate = t.date, finished = finished,
            durationSeconds = t.seconds, durationUnknown = finished && t.seconds == null, timingEdited = true, timingEstimated = false,
            timingNote = "User-corrected timing. Unspecified timestamps/duration remain unknown.", autoFinishAcknowledged = true,
            resumeStartedAt = null, resumeBaseSeconds = null, staleSuppressedUntil = if (finished) null else now() + STALE_MAX_MILLIS, lastActionAt = if (finished) w.lastActionAt else now()))
        if (finished) db.trainingDao().timer()?.takeIf { it.workoutId == id }?.let { db.trainingDao().clearTimer() }
    }
    suspend fun beginPast(input: WorkoutTimeInput, sourceKey: String = ""): Long = db.withTransaction {
        val t = parseWorkoutTiming(input, now())
        val id = db.dao().insertWorkout(Workout(startedAt = t.start ?: 0, completedAt = t.end, sessionDate = t.date, finished = true,
            durationSeconds = t.seconds, durationUnknown = t.seconds == null, timingEdited = true, historicalDraft = true, draftSourceKey = sourceKey))
        val local = db.dao()
        when {
            sourceKey.startsWith("local:") -> {
                val source = requireNotNull(local.workout(sourceKey.substringAfter(':').toLong()))
                require(source.isComplete && source.activityType == "strength") { "Choose a completed strength workout." }
                local.workoutEntries(source.id).forEachIndexed { position, entry -> local.insertEntry(entry.copy(id = 0, workoutId = id, position = position)) }
                local.updateWorkout(requireNotNull(local.workout(id)).copy(title = source.title))
            }
            sourceKey.startsWith("import:") -> {
                val source = requireNotNull(db.importedDao().workout(sourceKey.substringAfter(':')))
                val mappings = db.trainingDao().mappings(); val ids = linkedSetOf<Long>()
                source.sets.sortedBy { it.sourceOrder }.groupBy { it.exercise to it.category }.forEach { (group, _) ->
                    val name = InputRules.exerciseName(group.first)
                    val exercise = mappings.firstOrNull { it.importId == source.workout.importId && it.sourceName == group.first && it.category == group.second }?.exerciseId
                        ?: local.exerciseByKey(name.lowercase(Locale.ROOT))?.id
                        ?: matchPlanExercise(name, local.exercises(), aliases = aliasMap(db.trainingDao().aliases())).candidates.singleOrNull()?.id
                        ?: local.insertExercise(Exercise(name = name, nameKey = name.lowercase(Locale.ROOT)))
                    ids.add(exercise)
                }
                ids.forEachIndexed { position, exercise -> local.insertEntry(WorkoutExercise(workoutId = id, exerciseId = exercise, position = position)) }
            }
            sourceKey.startsWith("plan:") -> {
                val plan = requireNotNull(db.planDao().detail(sourceKey.substringAfter(':'))) { "Plan no longer exists." }
                plan.ordered.forEachIndexed { position, e -> local.insertEntry(WorkoutExercise(workoutId = id, exerciseId = e.exercise.id, position = position, planNote = e.target.note, supersetGroup = e.target.supersetGroup)) }
                local.updateWorkout(requireNotNull(local.workout(id)).copy(title = plan.plan.title))
            }
            else -> require(sourceKey.isBlank()) { "Choose a saved workout or routine." }
        }
        // Copy structure/reference only. No result or target is marked performed.
        id
    }
    suspend fun savePast(id: Long) = db.withTransaction {
        val w = requireNotNull(db.dao().workout(id)); require(w.historicalDraft) { "This draft was already saved or removed." }
        require(db.dao().workoutEntries(id).any { db.dao().entrySets(it.id).isNotEmpty() }) { "Save at least one performed set, or discard the empty draft." }
        db.dao().updateWorkout(w.copy(historicalDraft = false, finished = true, draftSourceKey = "", timingEdited = true, autoFinishAcknowledged = true,
            timingNote = if (w.autoFinishedAt != null && !w.timingEdited) "Completed from a recovered draft. Unspecified timing remains unknown." else w.timingNote))
    }
}
