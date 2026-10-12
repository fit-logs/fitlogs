package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.SESSION_PLAN_PREFIX

@Composable
fun SessionPlanCard(notes: String, completed: Boolean) {
    var expanded by rememberSaveable(notes) { mutableStateOf(!completed) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Session plan", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide plan" else "Show plan") }
            }
            Text("Legacy reference targets · recorded results are separate", style = MaterialTheme.typography.bodySmall)
            if (expanded) Text(notes.removePrefix(SESSION_PLAN_PREFIX), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
