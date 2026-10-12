package com.example.fitnotes.ui

import com.example.fitnotes.AppData
import com.example.fitnotes.data.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
fun dayMillis(day: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(day)!!.time
fun moveDay(day: String, amount: Int): String = Calendar.getInstance().apply { timeInMillis = dayMillis(day); add(Calendar.DAY_OF_YEAR, amount) }
    .let { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(it.time) }
fun prettyDay(day: String, pattern: String = if (day.take(4) == todayDate().take(4)) "EEE d MMM" else "EEE d MMM yyyy"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(dayMillis(day)))
fun pickerMillis(day: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(day)!!.time
fun pickerDay(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(millis))

data class NotebookSet(val key: String, val weight: Double?, val reps: Int?, val distance: Double?, val unit: String?,
    val seconds: Double?, val comment: String, val local: WorkoutSet? = null, val imported: ImportedSet? = null) {
    val cardio get() = weight == null && (distance != null || seconds != null)
    val text get() = buildList {
        if (weight != null && reps != null) add(weightText(weight) + " kg × " + reps + " reps")
        if (distance != null) add(weightText(distance) + " " + unit)
        if (seconds != null) add(weightText(seconds) + " seconds")
    }.joinToString(" · ")
}
data class NotebookExercise(val key: String, val name: String, val category: String = "", val entryId: Long? = null, val sets: List<NotebookSet>)
data class NotebookSession(val key: String, val date: String, val title: String, val notes: String, val completed: Boolean,
    val local: WorkoutDetail? = null, val imported: ImportedWorkoutDetail? = null, val exercises: List<NotebookExercise>) {
    val activity get() = local?.workout?.activityType ?: "strength"
    val activityTitle get() = local?.workout?.let(::activityName) ?: "Strength training"
    val duration get() = local?.let { localDuration(it.workout) ?: it.health.firstOrNull()?.session?.let { source -> (source.end - source.start) / 1000.0 } }
    val count get() = exercises.sumOf { it.sets.size }
    val dateOnly get() = if (local != null) local.workout.knownStart == null else imported != null && imported.workout.recordedStart == null
    val startedMillis get() = local?.workout?.knownStart ?: recordedMillis(imported?.workout?.recordedStart)
    val completedMillis get() = local?.workout?.completedAt ?: recordedMillis(imported?.workout?.recordedEnd)
    val timeLabel get() = if (dateOnly) "Date-only" else if (local != null) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(local.workout.startedAt))
        else imported?.workout?.recordedStart?.substringAfter('T')?.substringBefore('+')?.take(5) ?: "Date-only"
}
private fun recordedMillis(raw: String?): Long? {
    if (raw == null || !validRecordedTimestamp(raw)) return null
    val offset = raw.length - 6
    val normalized = raw.take(19) + "." + (if (offset == 19) "000" else raw.substring(20, offset).padEnd(3, '0')) + raw.takeLast(6)
    return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { isLenient = false }.parse(normalized)?.time }.getOrNull()
}
fun notebookSessions(data: AppData): List<NotebookSession> {
    val locals = data.workouts.map { d ->
        NotebookSession("local:" + d.workout.id, localWorkoutDate(d.workout), d.workout.title, d.workout.notes, d.workout.isComplete,
            local = d, exercises = orderedEntries(d).map { e -> NotebookExercise("entry:" + e.entry.id, e.exercise.name, entryId = e.entry.id,
                sets = orderedSets(e).map { s -> NotebookSet("set:" + s.id, if (s.reps > 0) s.weightKg else null, if (s.reps > 0) s.reps else null,
                    s.distance, s.distanceUnit, s.durationSeconds, s.comment, local = s) }) })
    }
    val imported = data.imported.workouts.map { d ->
        NotebookSession("import:" + d.workout.id, d.workout.date, "", d.workout.notes, true, imported = d,
            exercises = d.sets.sortedBy { it.sourceOrder }.groupBy { it.exercise to it.category }.map { (group, records) ->
                NotebookExercise("original:" + group.first + "\u001f" + group.second, group.first, group.second,
                    sets = records.map { s -> NotebookSet(s.id, s.weightKg, s.reps, s.distance, s.distanceUnit, s.durationSeconds, s.comment, imported = s) }) })
    }
    return (locals + imported).sortedWith(compareByDescending<NotebookSession> { it.date }.thenByDescending { it.local?.workout?.startedAt ?: 0L }.thenBy { it.key })
}

// Date-only imports have no ordering within a day. Never manufacture a time or
// use an ambiguous same-day date-only record as a previous session.
fun previousExerciseSessions(all: List<NotebookSession>, viewed: NotebookSession, exercise: NotebookExercise): List<Pair<NotebookSession, NotebookExercise>> =
    all.filter { candidate ->
        if (!candidate.completed || candidate.key == viewed.key) false
        else {
            val before = candidate.completedMillis
            val current = viewed.startedMillis
            if (before != null && current != null) before < current else candidate.date < viewed.date
        }
    }.mapNotNull { session ->
        val matches = session.exercises.filter { it.name.equals(exercise.name, ignoreCase = true) &&
            (exercise.category.isBlank() || it.category.isBlank() || it.category == exercise.category) && it.sets.isNotEmpty() }
        // An original name reused across categories is ambiguous for a local name.
        matches.singleOrNull()?.let { session to it }
    }.sortedWith(compareByDescending<Pair<NotebookSession, NotebookExercise>> { it.first.date }
        .thenByDescending { it.first.completedMillis ?: 0L })

fun compactResults(sets: List<NotebookSet>): String {
    if (sets.isEmpty()) return "No sets yet."
    if (sets.all { it.weight != null && it.reps != null && it.distance == null && it.seconds == null } && sets.map { it.weight }.distinct().size == 1)
        return weightText(sets.first().weight!!) + " kg × " + sets.joinToString(", ") { it.reps.toString() }
    return sets.joinToString("  ·  ") { it.text }
}
fun sessionContext(session: NotebookSession, data: AppData): String = session.local?.let { sessionText(listOf(it)) }
    ?: importedSessionText(session.imported!!, data.imported.imports.firstOrNull { it.id == session.imported.workout.importId })
fun rangeContext(data: AppData, first: String, last: String): String? {
    require(validHistoryDate(first) && validHistoryDate(last) && first <= last) { "Choose a valid date range." }
    val workouts = notebookSessions(data).filter { it.completed && it.date in first..last }
    val body = data.imported.body.filter { it.date in first..last }
    if (workouts.isEmpty() && body.isEmpty()) return null
    return workouts.joinToString("\n\n") { sessionContext(it, data) } + bodyContext(body)
}
