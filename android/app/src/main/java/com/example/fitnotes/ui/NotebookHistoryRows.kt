package com.example.fitnotes.ui

import com.example.fitnotes.data.*

/** Display projection only. Canonical run identities and stored workout contents are unchanged. */
data class NotebookHistoryRow(
    val key: String, val date: String, val start: Long?, val title: String,
    val activity: ActivityType, val activities: Set<ActivityType>, val subtitle: String,
    val summary: String, val sessionKey: String, val runKey: String? = null,
)

fun historyRows(sessions: List<NotebookSession>, runs: List<RunRecord>): List<NotebookHistoryRow> {
    val byParent = runs.groupBy { it.parentKey }
    fun running(exercise: NotebookExercise) = isRunningName(exercise.name) || exercise.sourceName?.let(::isRunningName) == true
    return sessions.filter { it.completed }.flatMap { session ->
        val components = byParent[session.key].orEmpty()
        val onlyRunning = session.activity == "running" ||
            session.exercises.isNotEmpty() && session.exercises.all(::running)
        if (onlyRunning) {
            // Separate source rows can be legitimate same-day runs. Never group them by date/title.
            // Retired or ignored run components have no canonical row and stay hidden here too.
            components.map { run -> NotebookHistoryRow(run.key, run.date, run.start, run.title,
                ActivityType.RUNNING, setOf(ActivityType.RUNNING),
                if (run.pace != null) run.preferredTime?.first?.paceLabel.orEmpty() else "",
                runSummary(run), session.key, run.key) }
        } else {
            val activity = ActivityType.from(session.activity)
            val nonRunning = session.exercises.filterNot(::running)
            val exerciseSummary = if (activity == ActivityType.STRENGTH && nonRunning.isNotEmpty()) {
                val count = nonRunning.sumOf { it.sets.size }
                "${nonRunning.size} ${if (nonRunning.size == 1) "exercise" else "exercises"} · $count ${if (count == 1) "set" else "sets"}"
            } else ""
            val activitySummary = if (activity == ActivityType.STRENGTH) exerciseSummary else listOfNotNull(
                session.duration?.let(::durationText),
                session.local?.workout?.distance?.let { weightText(it) + " " + session.local.workout.distanceUnit }
                    ?: session.local?.health?.firstOrNull()?.session?.distanceMeters?.let { weightText(it / 1000) + " km" }
            ).joinToString(" · ")
            listOf(NotebookHistoryRow(session.key, session.date, session.startedMillis,
                session.title.ifBlank { if (activity == ActivityType.STRENGTH) "Workout" else session.activityTitle },
                activity, setOf(activity) + if (components.isNotEmpty()) setOf(ActivityType.RUNNING) else emptySet(),
                if (activity == ActivityType.STRENGTH) session.exercises.joinToString(", ") { it.name }
                    else session.notes.ifBlank { session.local?.health?.firstOrNull()?.session?.title.orEmpty() },
                (listOf(activitySummary) + components.map { run ->
                    listOf("Running", runSummary(run)).filter { it.isNotBlank() }.joinToString(" · ")
                }).filter { it.isNotBlank() }.joinToString("\n"), session.key))
        }
    }.sortedWith(compareByDescending<NotebookHistoryRow> { it.date }.thenByDescending { it.start }.thenBy { it.key })
}

fun filterHistoryRows(rows: List<NotebookHistoryRow>, activity: String): List<NotebookHistoryRow> =
    if (activity == "all") rows else rows.filter { row -> row.activities.any { it.key == activity } }

fun historyFilterLabel(activity: String) = when (activity) {
    "all" -> "All activities"
    "strength" -> "Gym"
    else -> ActivityType.from(activity).label
}
