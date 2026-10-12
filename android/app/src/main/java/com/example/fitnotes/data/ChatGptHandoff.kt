package com.example.fitnotes.data

import com.example.fitnotes.AppData
import com.example.fitnotes.ui.notebookSessions
import com.example.fitnotes.ui.rangeContext
import com.example.fitnotes.ui.sessionContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class HandoffRequestKind(val label: String) { NEXT_WORKOUT("Next workout"), REVIEW_PROGRESS("Review progress"), CUSTOM("Custom question") }
fun tomorrowDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
fun measurementSelectionKey(value: BodyMeasurement) = value.name + "\u001f" + value.unit
data class HandoffRequest(val first: String, val last: String, val kind: HandoffRequestKind, val targetDate: String,
    val preferences: HandoffPreferences, val customQuestion: String = "", val measurementTypes: Set<String> = emptySet(),
    val photoIds: Set<String> = emptySet(), val includeHeartRate: Boolean = false)
data class PreparedHandoff(val request: HandoffRequest, val fullText: String, val requestText: String,
    val photos: List<ProgressPhoto>, val sampleCount: Int) {
    val bytes get() = fullText.toByteArray(Charsets.UTF_8).size
    val canCopy get() = bytes <= MAX_INLINE_BYTES
    companion object { const val MAX_INLINE_BYTES = 200000; const val MAX_SHARED_PHOTOS = 8 }
}

object ChatGptHandoff {
    fun prepare(data: AppData, photos: List<ProgressPhoto>, request: HandoffRequest): PreparedHandoff {
        require(!data.loading && !data.imported.loading && data.storageError == null) { "Wait for the full training history to load before preparing a prompt." }
        require(validHistoryDate(request.first) && validHistoryDate(request.last) && request.first <= request.last) { "Choose a valid history date range." }
        require(validHistoryDate(request.targetDate)) { "Choose a valid target date as YYYY-MM-DD." }
        validateHandoffPreferences(request.preferences)
        require(request.kind != HandoffRequestKind.CUSTOM || (request.customQuestion.length <= 4000 && request.customQuestion.isNotBlank())) { "Enter your custom question, up to 4,000 characters." }
        require(request.kind != HandoffRequestKind.NEXT_WORKOUT || request.preferences.activityPreference in setOf("", "strength")) {
            "V1 imports strength plans only. Choose No preference / Strength, or use an advice request for other activities."
        }
        require(request.photoIds.size <= PreparedHandoff.MAX_SHARED_PHOTOS) { "Select at most 8 photos per handoff." }
        val selectedPhotos = photos.filter { it.id in request.photoIds && it.date in request.first..request.last }.sortedWith(compareBy({ it.date }, { it.id }))
        require(selectedPhotos.size == request.photoIds.size) { "A selected photo is unavailable or outside this date range. Review your selection." }
        val selectedBody = data.imported.body.filter { it.date in request.first..request.last && measurementSelectionKey(it) in request.measurementTypes }
        val sessions = notebookSessions(data)
        val actual = sessions.filter { it.date in request.first..request.last }
        val allHealth = (data.workouts.flatMap { it.health } + data.imported.workouts.flatMap { it.health }).distinctBy { it.session.key }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val samples = if (request.includeHeartRate) allHealth.flatMap { it.samples }.filter { day.format(Date(it.time)) in request.first..request.last }
            .distinctBy { Triple(it.originPackage, it.recordId, it.time) }.sortedWith(compareBy({ it.time }, { it.originPackage })) else emptyList()
        val incompatible = incompatiblePlanExercises(data.exercises, data.workouts.flatMap { it.entries.map { e -> e.entry } },
            data.workouts.flatMap { it.entries.flatMap { e -> e.sets } }, data.imported.workouts.flatMap { it.sets })
        val requestText = buildString {
            appendLine("FitNotes · ${request.kind.label}")
            appendLine("Covered history: ${request.first} through ${request.last}, inclusive, in device timezone ${TimeZone.getDefault().id}.")
            appendLine("Target date: ${request.targetDate}")
            appendLine("Time available: ${request.preferences.availableMinutes?.let { "$it min" } ?: "not specified"}")
            appendLine("Activity preference: ${request.preferences.activityPreference.takeIf { it.isNotBlank() }?.let { ActivityType.from(it).label } ?: "not specified"}")
            appendLine("Goals: ${request.preferences.goals.ifBlank { "not specified" }}")
            appendLine("Equipment: ${request.preferences.equipment.ifBlank { "not specified" }}")
            appendLine("Exercise constraints: ${request.preferences.constraints.ifBlank { "not specified" }}")
            appendLine()
            when (request.kind) {
                HandoffRequestKind.NEXT_WORKOUT -> appendLine("Suggest my next strength workout using the recorded history and preferences. Account for other activities and recovery. Explain your choices briefly outside the plan block.")
                HandoffRequestKind.REVIEW_PROGRESS -> appendLine("Review progress across the recorded sessions. Explain trends, limitations and possible next steps in ordinary readable advice. No workout-plan block is needed.")
                HandoffRequestKind.CUSTOM -> appendLine("My question: ${request.customQuestion}\nAnswer in ordinary readable advice. No workout-plan block is required.")
            }
            appendLine("Use only recorded results as performed training. Targets and guidance in notes are not completed sets. Missing timings, measurements and samples are unknown; do not invent them. Imported heart-rate samples may be sparse and are not official workout statistics or continuous coverage.")
            if (request.kind == HandoffRequestKind.NEXT_WORKOUT) {
                appendLine()
                appendLine(nextWorkoutInstructions(request.targetDate))
                appendLine("Available catalogue names (no exercise identifiers):")
                if (data.exercises.isEmpty()) appendLine("No catalogue names available. Use clear full exercise names; the app will require explicit matching or creation.")
                else data.exercises.filter { it.id !in incompatible }.sortedBy { it.nameKey }.forEach { appendLine("- ${it.name}") }
            }
        }.trimEnd()
        val history = rangeContext(data, request.first, request.last, includeBody = false)
        val fullText = buildString {
            appendLine(requestText)
            appendLine("\nRECORDED TRAINING")
            appendLine("${actual.count { it.completed }} completed sessions in this range. Planned workouts are excluded.")
            appendLine(history ?: "No completed workouts are recorded in this range.")
            val unfinished = actual.filter { !it.completed }
            if (unfinished.isNotEmpty()) {
                appendLine("\nIN-PROGRESS SESSIONS · saved results only; no completion assumed")
                unfinished.forEach { appendLine(sessionContext(it, data)); appendLine() }
            }
            appendLine("\nBODY MEASUREMENTS")
            if (request.measurementTypes.isEmpty()) appendLine("Not selected; omitted.")
            else if (selectedBody.isEmpty()) appendLine("No records for the selected measurement types in this range.")
            else appendLine(bodyContext(selectedBody))
            appendLine("\nPHOTOS")
            if (selectedPhotos.isEmpty()) appendLine("Not selected; omitted.")
            else {
                appendLine("${selectedPhotos.size} explicitly selected photos. Copy is text only; attach these photos manually, or use Share to include JPEG copies.")
                selectedPhotos.forEachIndexed { index, photo -> appendLine("Photo ${index + 1}: ${photo.date} · ${photo.angle}${photo.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}") }
                appendLine("Photo files are shared as orientation-correct JPEG copies up to 2048 pixels along the longest edge. Originals remain in the app.")
            }
            appendLine("\nRAW HEART-RATE SERIES")
            if (!request.includeHeartRate) appendLine("Not selected; omitted. Imported sample summaries above are not raw series.")
            else if (samples.isEmpty()) appendLine("No imported samples available in this range. This does not establish that no heart-rate data exists in the source app.")
            else {
                appendLine("${samples.size} available imported samples, filtered by actual sample time. These are cached samples from imported exercise sessions, not a complete day-by-day series.")
                appendLine("timestamp_local,epoch_millis,bpm,source_package")
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
                samples.forEach { appendLine("${timestamp.format(Date(it.time))},${it.time},${it.bpm},${it.originPackage}") }
            }
        }.trimEnd()
        return PreparedHandoff(request, fullText, requestText, selectedPhotos, samples.size)
    }
}

fun nextWorkoutInstructions(date: String): String = """
    Return exactly one fenced code block containing WORKOUT_PLAN_V2 through END_WORKOUT_PLAN, with strict JSON between those markers. Put a brief explanation outside the block. Strength plans only; no running/climbing prescriptions or invented identifiers.
    Use existing catalogue names verbatim when appropriate. Every exercise has its own note and individual sets. Each set requires integer repsMin/repsMax (equal for a fixed count), optional decimal weight and unit (kg/lb, or unit bodyweight with no numeric weight), and optional restSeconds. Omitted weight is unspecified, never zero. Suggested targets are not performed results.
    Put general warm-up instructions in warmUp and other session guidance in note. Keep exercise-specific guidance in that exercise's note. Do not add unknown fields. Date is optional: omit it for an unscheduled plan, or use ${date.ifBlank { "an explicit YYYY-MM-DD date" }}. durationMinutes is an optional time budget, not recorded activity.
    Example structure (replace example names and suggestions using my context):
    ```text
    WORKOUT_PLAN_V2
    {
      "title": "Session title",
      "date": "$date",
      "durationMinutes": 45,
      "warmUp": "General warm-up guidance.",
      "note": "Session-level guidance.",
      "exercises": [
        {
          "name": "Existing exercise name",
          "note": "Exercise-level guidance.",
          "sets": [
            {"repsMin": 8, "repsMax": 10, "weight": "40.5", "unit": "kg", "restSeconds": 90},
            {"repsMin": 8, "repsMax": 10, "restSeconds": 90},
            {"repsMin": 12, "repsMax": 12, "unit": "bodyweight", "restSeconds": 60}
          ]
        }
      ]
    }
    END_WORKOUT_PLAN
    ```
""".trimIndent()
