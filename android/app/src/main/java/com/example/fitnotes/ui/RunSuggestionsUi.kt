package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

fun suggestedActualWeight(target: PlanSetTarget): String = targetWeightKg(target)

@Composable
fun RestControls(model:FitNotesViewModel,workoutId:Long,busy:Boolean) {
    val timer by model.restTimer.collectAsState()
    val current=timer?.takeIf{it.workoutId==workoutId} ?: return
    var remaining by remember(current.token){mutableLongStateOf(current.remaining(model.restController.clock))}
    LaunchedEffect(current) {
        do {
            remaining=current.remaining(model.restController.clock)
            if(current.running && remaining==0L){model.restFinished(current.token);break}
            kotlinx.coroutines.delay(250)
        } while(current.running)
    }
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth().testTag("rest-controls")) {
        Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(current.alerted)"Rest finished" else "Rest ${(remaining+999)/1000/60}:${(((remaining+999)/1000)%60).toString().padStart(2,'0')}",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            if(!current.alerted)TextButton(onClick={model.restAction(current.token,if(current.running)"pause" else "resume")},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text(if(current.running)"Pause" else "Resume")}
            TextButton(onClick={model.restAction(current.token,"extend")},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("+30 s")}
            TextButton(onClick={model.restAction(current.token,"skip")},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Skip")}
        }
    }
}
