package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import kotlinx.coroutines.delay

@Composable
fun WorkoutTimeFields(input: WorkoutTimeInput, enabled: Boolean, change: (WorkoutTimeInput) -> Unit) {
    OutlinedTextField(input.start, { change(input.copy(start = it)) }, label = { Text("Start time (HH:mm, optional)") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("workout-start-time"))
    OutlinedTextField(input.endDate, { change(input.copy(endDate = it)) }, label = { Text("End date (YYYY-MM-DD)") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("workout-end-date"))
    OutlinedTextField(input.end, { change(input.copy(end = it)) }, label = { Text("End time (HH:mm, optional)") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("workout-end-time"))
    OutlinedTextField(input.minutes, { change(input.copy(minutes = it)) }, label = { Text("Duration (minutes, optional)") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("workout-duration"))
    Text("Leave unknown times/duration blank. End-only or duration-only is valid. Start and duration can determine an end; no midnight start is invented.", style = MaterialTheme.typography.bodySmall)
}
@Composable
fun PastWorkoutSetupDialog(data: AppData, model: FitNotesViewModel, busy: Boolean, onClose: () -> Unit, onReady: (Long, String, Boolean) -> Unit) {
    var date by rememberSaveable { mutableStateOf(moveDay(todayDate(), -1)) }
    var source by rememberSaveable { mutableStateOf("") }
    var times by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf("") }; var end by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf(date) }; var minutes by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val allPlans by model.plans.collectAsState(); val programs by model.programs.collectAsState()
    val owned = programs.flatMap { it.days }.map { it.day.planId }.toSet()
    val plans = allPlans.filter { !it.plan.archived && it.plan.id !in owned }; val error by model.workoutTimeError.collectAsState()
    val recent = notebookSessions(data).filter { it.completed && it.activity == "strength" && it.exercises.isNotEmpty() }.take(12)
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Log past workout") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enter what you actually did. This creates a draft for a completed past session and leaves the live workout alone.")
            TextButton(onClick = { picker = true }, enabled = !busy) { Text("Date · " + prettyDay(date, "EEE d MMM yyyy")) }
            RadioChoice("Start empty", source.isBlank(), !busy) { source = "" }
            if (recent.isNotEmpty()) Text("Copy a recent workout", style = MaterialTheme.typography.titleSmall)
            recent.forEach { session -> RadioChoice("${session.date} · ${session.title.ifBlank { session.exercises.take(3).joinToString(", ") { it.name } }} · ${session.timeLabel}", source == session.key, !busy) { source = session.key } }
            if (plans.isNotEmpty()) Text("Copy a saved routine", style = MaterialTheme.typography.titleSmall)
            plans.take(20).forEach { plan -> RadioChoice(plan.plan.title, source == "plan:${plan.plan.id}", !busy) { source = "plan:${plan.plan.id}" } }
            TextButton(onClick = { times = !times }) { Text(if (times) "Hide optional timing" else "Add optional timing") }
            if (times) WorkoutTimeFields(WorkoutTimeInput(date, start, endDate, end, minutes), !busy) { start = it.start; endDate = it.endDate; end = it.end; minutes = it.minutes }
            Text("Copied exercises and values are suggestions. No performed sets are copied.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val input = WorkoutTimeInput(date, if (times) start else "", if (times) endDate else date, if (times) end else "", if (times) minutes else "")
        model.beginPastWorkout(input, source) { id -> onReady(id, date, source.isBlank()) }
    }, enabled = !busy) { Text("Enter performed sets") } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } })
    if (picker) DayDialog(date, { picker = false }) { chosen -> date = chosen; endDate = chosen; picker = false }
}
@Composable
private fun RadioChoice(label: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    TextButton(onClick = choose, enabled = enabled, modifier = Modifier.fillMaxWidth()) { RadioButton(selected, onClick = choose, enabled = enabled); Text(label, Modifier.weight(1f).padding(start = 6.dp)) }
}
@Composable
fun WorkoutTimeDialog(workout: Workout, model: FitNotesViewModel, busy: Boolean, close: () -> Unit) {
    var date by rememberSaveable(workout.id) { mutableStateOf(localWorkoutDate(workout)) }
    var start by rememberSaveable(workout.id) { mutableStateOf(workout.knownStart?.let { sourceTime(it, null, "HH:mm") }.orEmpty()) }
    var endDate by rememberSaveable(workout.id) { mutableStateOf(workout.completedAt?.let { sourceTime(it, null, "yyyy-MM-dd") } ?: date) }
    var end by rememberSaveable(workout.id) { mutableStateOf(workout.completedAt?.let { sourceTime(it, null, "HH:mm") }.orEmpty()) }
    var minutes by rememberSaveable(workout.id) { mutableStateOf(localDuration(workout)?.let { weightText(it / 60) }.orEmpty()) }
    val error by model.workoutTimeError.collectAsState()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Edit workout time / duration") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { if(endDate==date)endDate=it;date = it;minutes="" }, label = { Text("Workout date (YYYY-MM-DD)") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("workout-date"))
            WorkoutTimeFields(WorkoutTimeInput(date, start, endDate, end, minutes), !busy) { changed ->
                minutes = if(changed.start != start || changed.end != end || changed.endDate != endDate) "" else changed.minutes
                start = changed.start; endDate = changed.endDate; end = changed.end
            }
            if (workout.timingEstimated) Text("Automatic timing is estimated. Confirmed edits replace it and persist.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { model.editWorkoutTime(workout.id, WorkoutTimeInput(date, start, endDate, end, minutes), close) }, enabled = !busy) { Text("Save time") } },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Cancel") } })
}
@Composable
fun WorkoutTimingPanel(workout: Workout, model: FitNotesViewModel, busy: Boolean, onEdit: () -> Unit) {
    val currentData by model.data.collectAsState()
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout.id, workout.isComplete, workout.historicalDraft) { while (!workout.isComplete && !workout.historicalDraft) { time = System.currentTimeMillis(); delay(1000) } }
    OutlinedCard(Modifier.fillMaxWidth().testTag("workout-timing")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local workout time", style = MaterialTheme.typography.titleSmall)
            when {
                workout.historicalDraft -> {
                    Text("Past workout draft", style = MaterialTheme.typography.titleMedium)
                    Text("Saved draft sets stay out of training history and progress until Save completed.")
                    Text(localWorkoutDate(workout) + " · " + durationText(localDuration(workout)))
                }
                workout.isComplete -> {
                    Text(durationText(localDuration(workout)), style = MaterialTheme.typography.titleMedium)
                    if (workout.timingEstimated) Text(if (workout.durationUnknown) "Auto-finished · duration unknown" else "Auto-finished · estimated timing", color = MaterialTheme.colorScheme.primary)
                    else Text(if (workout.durationUnknown) "Duration left unknown" else if (workout.resumeStartedAt != null) "Recorded segments · unlogged gap excluded" else "Recorded / reported timing", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val anchor = workout.resumeStartedAt ?: workout.startedAt
                    val futureAction = workout.lastActionAt?.let { it > time } == true || currentData.workouts.firstOrNull { it.workout.id == workout.id }?.entries?.any { entry -> entry.sets.any { it.completedAt?.let { action -> action > time } == true } } == true
                    if (futureAction || time < anchor || time - anchor > STALE_MAX_MILLIS && workout.staleSuppressedUntil?.let { it > time } != true) Text("Workout time needs review", style = MaterialTheme.typography.titleMedium)
                    else Text(activeWorkoutSeconds(workout, time)?.let(::durationText) ?: "Earlier duration unknown · current segment " + durationText((time - anchor).coerceAtLeast(0) / 1000.0), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(workout.knownStart?.let { "Start · " + sourceTime(it, null) } ?: "Start time unknown", style = MaterialTheme.typography.bodySmall)
            workout.completedAt?.let { Text("End · " + sourceTime(it, null), style = MaterialTheme.typography.bodySmall) }
            if (workout.timingNote.isNotBlank()) Text(workout.timingNote, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit time / duration") }
                if (!workout.isComplete && !workout.historicalDraft) TextButton(onClick = { model.allowLongWorkout(workout.id) }, enabled = !busy) { Text("Keep running for 6 hours") }
                if (workout.isComplete && workout.knownStart != null) TextButton(onClick = { model.resumeFinishedWorkout(workout.id) }, enabled = !busy && currentData.active == null) { Text("Resume workout") }
            }
        }
    }
}
