package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import org.json.JSONObject

@Composable
fun RunningTargetFields(value: RunningPrescription, change: (RunningPrescription) -> Unit) {
    var km by rememberSaveable { mutableStateOf(value.distanceKm?.let(::weightText).orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(value.durationMinutes?.toString().orEmpty()) }
    OutlinedTextField(km,{km=it;change(value.copy(distanceKm=if(it.isBlank())null else it.toDoubleOrNull()?.takeIf{number->number.isFinite()} ?: -1.0))},label={Text("Distance target (km, optional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(minutes,{minutes=it;change(value.copy(durationMinutes=if(it.isBlank())null else it.toIntOrNull() ?: -1))},label={Text("Duration target (minutes, optional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value.intensity,{change(value.copy(intensity=it))},label={Text("Intensity / effort guidance")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value.pace,{change(value.copy(pace=it))},label={Text("Pace guidance (optional)")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value.warmUp,{change(value.copy(warmUp=it))},label={Text("Warm-up")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value.intervals,{change(value.copy(intervals=it))},label={Text("Intervals and recovery")},modifier=Modifier.fillMaxWidth(),maxLines=5)
    OutlinedTextField(value.coolDown,{change(value.copy(coolDown=it))},label={Text("Cool-down")},modifier=Modifier.fillMaxWidth())
}

@Composable
fun RunningPlanEditor(plan: PlannedWorkout, model: FitNotesViewModel, busy: Boolean, close: () -> Unit) {
    var title by rememberSaveable(plan.id) { mutableStateOf(plan.title) }
    var notes by rememberSaveable(plan.id) { mutableStateOf(plan.notes) }
    var json by rememberSaveable(plan.id) { mutableStateOf(plan.runningJson) }
    val run=JSONObject(json).let { o -> RunningPrescription(if(o.isNull("distanceKm"))null else o.getDouble("distanceKm"),if(o.isNull("durationMinutes"))null else o.getInt("durationMinutes"),o.optString("intensity"),o.optString("pace"),o.optString("intervals"),o.optString("warmUp"),o.optString("coolDown")) }
    val error by model.programError.collectAsState()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun dismiss(){focus.clearFocus();keyboard?.hide();close()}
    HandoffScreen("Edit running session",busy,::dismiss,"running-plan-editor",attention=error,footer={
        Button(onClick={model.saveRunningPlan(plan.id,title,notes,plan.scheduledDate,run,::dismiss)},enabled=!busy) {Text("Save session")}
        TextButton(onClick=::dismiss,enabled=!busy) {Text("Cancel")}
    }) {
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        item{Text("Targets only. Link an actual completed run separately.");OutlinedTextField(title,{title=it},label={Text("Session title")},modifier=Modifier.fillMaxWidth())}
        item{RunningTargetFields(run){ json=it.json().toString() }}
        item{OutlinedTextField(notes,{notes=it},label={Text("Session notes")},modifier=Modifier.fillMaxWidth(),maxLines=8)}
    }
}
