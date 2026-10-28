package com.example.fitnotes.data

import com.example.fitnotes.AppData
import com.example.fitnotes.ui.notebookSessions
import java.util.TimeZone

enum class CoachQuestion(val label: String) {
    NEXT("Plan my next workout"), WEEK("Plan my training week"), REVIEW("Review my progress"), CUSTOM("Ask a custom question")
}
data class CoachRequest(val question: CoachQuestion, val focus: String, val first: String, val last: String,
    val preferences: HandoffPreferences, val customQuestion: String = "")

/** Uses the same completed-session projection and statistics as History/Progress.
 * Private notes, body data, photos and samples are never traversed for Coach. */
object CoachPrompt {
    fun prepare(data: AppData, request: CoachRequest): String {
        require(!data.loading && !data.imported.loading && data.storageError == null) { "Wait for training history to finish loading." }
        require(validHistoryDate(request.first) && validHistoryDate(request.last) && request.first <= request.last) { "Choose a valid inclusive date range." }
        validateHandoffPreferences(request.preferences)
        require(request.focus in setOf("Gym", "Running", "Both")) { "Choose Gym, Running or Both." }
        require(request.question != CoachQuestion.CUSTOM || request.customQuestion.isNotBlank() && request.customQuestion.length <= 4000) { "Enter a custom question up to 4,000 characters." }
        val sessions=notebookSessions(data).filter { it.completed && it.date in request.first..request.last }
        val strength=observations(sessions).filter { it.activity == "strength" && it.results.isNotEmpty() }
        return buildString {
            appendLine("FitNotes · ${request.question.label}")
            appendLine("Training focus: ${when(request.focus){"Both"->"Gym and running";"Gym"->"Gym (strength)";else->"Running"}}")
            appendLine("History: ${request.first} through ${request.last}, inclusive; timezone ${TimeZone.getDefault().id}.")
            val p=request.preferences
            appendLine("Goals: ${p.goals.ifBlank { "not specified" }}")
            appendLine("Equipment: ${p.equipment.ifBlank { "not specified" }}")
            appendLine("Constraints: ${p.constraints.ifBlank { "not specified" }}")
            appendLine("Available minutes per session: ${p.availableMinutes ?: "not specified"}")
            appendLine("Activity preference: ${p.activityPreference.ifBlank { "not specified" }}")
            if(request.question==CoachQuestion.CUSTOM)appendLine("My question: ${request.customQuestion}")
            appendLine("Use only completed, actually performed exercises and activities as training history. Planned sets, skipped sessions and target weights are not results. Missing values are unknown. Do not infer readiness, soreness or recovery; ask me if needed.")
            appendLine("Shared data: all completed strength sets in this range grouped by exercise/load/reps per session, plus recorded activity summaries. No session/exercise/set notes, photos, body measurements or raw heart-rate samples are included. No history is truncated. Active and uncommitted workouts are excluded.")
            appendLine("\nCOMPLETED STRENGTH")
            appendLine(StrengthStatistics.context(strength))
            strength.forEach { s ->
                appendLine(s.date)
                s.results.groupBy { listOf(it.exerciseName, it.weightKg, it.reps, it.mode, it.warmUp) }.forEach { (_, rows) ->
                    val r=rows.first()
                    appendLine("${r.exerciseName}: ${rows.size} performed set(s) × ${r.reps} reps, ${r.weightKg?.let { "${weightText(it)} kg" } ?: "load unknown"}, ${r.mode}${if(r.warmUp) ", warm-up" else ""}")
                }
            }
            appendLine("\nCOMPLETED ACTIVITIES")
            runningRecords(data).filter { it.date in request.first..request.last }.forEach { run ->
                appendLine("${run.date} · Running${run.distanceMetres?.let { " · ${weightText(it/1000)} km" }.orEmpty()}${run.preferredTime?.let { (basis, seconds) -> " · ${basis.durationLabel}: ${weightText(seconds)} seconds${run.pace?.let { " · ${basis.paceLabel}: ${paceText(it)}" }.orEmpty()}" }.orEmpty()}")
            }
            sessions.filter { it.activity !in setOf("strength","running") }.forEach { s ->
                s.summary?.let { a -> appendLine("${a.date} · ${a.sport} · ${a.durationBasis}: ${a.seconds?.let { "${weightText(it)} seconds" } ?: "unknown"}${a.distanceLabel?.let { " · $it" }.orEmpty()}${a.pace?.let { " · $it" }.orEmpty()}") }
            }
            if(request.question in setOf(CoachQuestion.NEXT,CoachQuestion.WEEK))appendLine("\n${CoachResponse.CONTRACT}")
            else appendLine("\nReturn ordinary coaching advice. Include plan JSON only if I explicitly request a workout/program.")
        }.trim()
    }
}
