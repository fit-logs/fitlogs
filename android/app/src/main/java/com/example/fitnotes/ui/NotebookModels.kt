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
        if (weight != null && reps != null) add(local?.let(::localSetText) ?: (weightText(weight) + " kg × " + reps + " reps (load mode unknown)"))
        if (weight == null && reps != null) add(local?.let(::localSetText) ?: "$reps reps · load unspecified")
        if (distance != null) add(weightText(distance) + " " + unit)
        if (seconds != null) add(weightText(seconds) + " seconds")
    }.joinToString(" · ")
}
data class NotebookExercise(val key: String, val name: String, val category: String = "", val entryId: Long? = null, val sets: List<NotebookSet>, val canonicalId: Long? = null, val sourceName: String? = null)
data class NotebookSession(val key: String, val date: String, val title: String, val notes: String, val completed: Boolean,
    val local: WorkoutDetail? = null, val imported: ImportedWorkoutDetail? = null, val exercises: List<NotebookExercise>) {
    val summary get() = local?.let(::activitySummary)
    val activity get() = local?.workout?.activityType ?: "strength"
    val activityTitle get() = summary?.sport ?: "Strength training"
    val duration get() = summary?.seconds
    val count get() = exercises.sumOf { it.sets.size }
    val dateOnly get() = if (local != null) summary?.start == null else imported != null && imported.workout.recordedStart == null
    val startedMillis get() = summary?.start ?: recordedMillis(imported?.workout?.recordedStart)
    val completedMillis get() = summary?.end ?: recordedMillis(imported?.workout?.recordedEnd)
    val timeLabel get() = if (dateOnly) "Date-only" else if (local != null) summary!!.timeLabel
        else imported?.workout?.recordedStart?.substringAfter('T')?.substringBefore('+')?.take(5) ?: "Date-only"
}
private fun recordedMillis(raw: String?): Long? {
    if (raw == null || !validRecordedTimestamp(raw)) return null
    val offset = raw.length - 6
    val normalized = raw.take(19) + "." + (if (offset == 19) "000" else raw.substring(20, offset).padEnd(3, '0')) + raw.takeLast(6)
    return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { isLenient = false }.parse(normalized)?.time }.getOrNull()
}
// Use a Long fallback so a date-only import and a timed session compare safely.
// This ordering is for display only; unknown within-day times remain unknown.
fun sortNotebookSessions(sessions: List<NotebookSession>): List<NotebookSession> =
    sessions.sortedWith(compareByDescending<NotebookSession> { it.date }
        .thenByDescending { it.startedMillis ?: 0L }.thenBy { it.key })

fun notebookSessions(data: AppData): List<NotebookSession> {
    val identity = ExerciseIdentityIndex(data)
    val locals = data.workouts.map { d ->
        NotebookSession("local:" + d.workout.id, activitySummary(d).date, activitySummary(d).title, d.workout.notes, d.workout.isComplete,
            local = d, exercises = orderedEntries(d).map { e -> NotebookExercise("entry:" + e.entry.id, e.exercise.name, entryId = e.entry.id,
                sets = orderedSets(e).map { s -> NotebookSet("set:" + s.id, if (s.reps > 0 && s.weightSpecified) s.weightKg else null, if (s.reps > 0) s.reps else null,
                    s.distance, s.distanceUnit, s.durationSeconds, s.comment, local = s) }, canonicalId = e.exercise.id) })
    }
    val imported = data.imported.workouts.map { d ->
        NotebookSession("import:" + d.workout.id, d.workout.date, "", d.workout.notes, true, imported = d,
            exercises = d.sets.sortedBy { it.sourceOrder }.groupBy { it.exercise to it.category }.map { (group, records) ->
                val canonical = identity.source(d.workout.importId, group.first, group.second)
                NotebookExercise("original:" + group.first + "\u001f" + group.second, data.exercises.firstOrNull { it.id == canonical }?.name ?: group.first, group.second,
                    sets = records.map { s -> NotebookSet(s.id, s.weightKg, s.reps, s.distance, s.distanceUnit, s.durationSeconds, s.comment, imported = s) }, canonicalId = canonical, sourceName = group.first) })
    }
    return sortNotebookSessions(locals + imported)
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
        val matches = session.exercises.filter { (if (exercise.canonicalId != null && it.canonicalId != null) it.canonicalId == exercise.canonicalId else it.name.equals(exercise.name, ignoreCase = true)) &&
            (exercise.category.isBlank() || it.category.isBlank() || it.category == exercise.category) && it.sets.isNotEmpty() }
        // An original name reused across categories is ambiguous for a local name.
        val matched=matches.singleOrNull() ?: if(exercise.canonicalId!=null && matches.isNotEmpty() && matches.all{it.canonicalId==exercise.canonicalId})
            matches.first().copy(key="canonical:${exercise.canonicalId}",sets=matches.flatMap{it.sets}.distinctBy{it.key}.sortedBy{it.imported?.sourceOrder ?: it.local?.position ?: 0},sourceName=matches.mapNotNull{it.sourceName}.distinct().joinToString("; ")) else null
        matched?.let { session to it }
    }.sortedWith(compareByDescending<Pair<NotebookSession, NotebookExercise>> { it.first.date }
        .thenByDescending { it.first.completedMillis ?: 0L })

fun compactResults(sets: List<NotebookSet>): String {
    if (sets.isEmpty()) return "No sets yet."
    if (sets.all { it.weight != null && it.reps != null && it.distance == null && it.seconds == null } && sets.map { it.weight }.distinct().size == 1 && sets.all { it.local?.loadMode == LoadMode.EXTERNAL })
        return weightText(sets.first().weight!!) + " kg × " + sets.joinToString(", ") { it.reps.toString() }
    return sets.joinToString("  ·  ") { it.text }
}
fun sessionContext(session: NotebookSession, data: AppData): String = session.local?.let { sessionText(listOf(it)) }
    ?: importedSessionText(session.imported!!, data.imported.imports.firstOrNull { it.id == session.imported.workout.importId })
fun rangeContext(data: AppData, first: String, last: String, includeBody: Boolean = true): String? {
    require(validHistoryDate(first) && validHistoryDate(last) && first <= last) { "Choose a valid date range." }
    val workouts = notebookSessions(data).filter { it.completed && it.date in first..last }
    val body = if (includeBody) data.imported.body.filter { it.date in first..last } else emptyList()
    if (workouts.isEmpty() && body.isEmpty()) return null
    return workouts.joinToString("\n\n") { sessionContext(it, data) } + bodyContext(body)
}
