package com.example.fitnotes.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
internal fun HealthConnectionSection(model: FitNotesViewModel, access: HealthAccess?, onPermissions: () -> Unit, onBackgroundPermission: () -> Unit) {
    val connection by model.healthConnectionState.collectAsState()
    val settings = connection.settings
    var filters by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Garmin via Health Connect", style = MaterialTheme.typography.titleLarge)
        Text("Enable Garmin sharing in Health Connect, then choose which activities to import.")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Automatically import activities", Modifier.weight(1f))
            Switch(settings.enabled, model::setAutomaticHealth)
        }
        Text(if (connection.working) "Syncing. Workout logging remains available." else connection.message, style = MaterialTheme.typography.bodyMedium)
        Text("Last attempt: " + (connection.lastAttempt?.let(::timestampText) ?: "Not yet"))
        Text("Last completed: " + (connection.lastSuccess?.let(::timestampText) ?: "Not yet"))
        Text("${connection.imported} imported · ${connection.refreshed} refreshed · ${connection.reviewKeys.size} need review · ${connection.errors} errors")
        if (connection.missing > 0) Text("${connection.missing} source records were unavailable. Local records were retained.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::syncHealthNow, enabled = !connection.working && settings.origins.isNotEmpty() && settings.activities.isNotEmpty()) { Text("Sync now") }
            TextButton(onClick = onPermissions) { Text("Connect / review permissions") }
            TextButton(onClick = { filters = !filters }) { Text(if (filters) "Hide import choices" else "Choose sources and activities") }
        }
        if (filters) {
            Text("Sources", style = MaterialTheme.typography.titleMedium)
            connection.availableOrigins.toSortedMap().forEach { (origin, name) ->
                HandoffCheck(name, origin in settings.origins) { checked -> model.setHealthOrigins(if (checked) settings.origins + origin else settings.origins - origin) }
            }
            Text("Activities", style = MaterialTheme.typography.titleMedium)
            ActivityType.entries.forEach { type ->
                HandoffCheck(type.label, type.key in settings.activities) { checked -> model.setHealthActivities(if (checked) settings.activities + type.key else settings.activities - type.key) }
            }
        }
        if (access?.backgroundSupported == true) {
            if (access.background) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Allow scheduled background import", Modifier.weight(1f))
                Switch(settings.background, model::setHealthBackground)
            } else TextButton(onClick = onBackgroundPermission) { Text("Grant optional background access") }
        }
        if (access?.background != true || access.backgroundSupported != true || !settings.background)
            Text("Foreground and manual import are available. Background import needs separate supported permission and is optional.", style = MaterialTheme.typography.bodySmall)
        else Text("Android schedules background checks about hourly; timing is controlled by Android.", style = MaterialTheme.typography.bodySmall)
        if (settings.origins.isEmpty() || settings.activities.isEmpty()) Text("Choose at least one source and activity to import.")
        Text("Possible duplicates are kept for review below. Ignored or locally deleted source records stay excluded. Saved sets, notes and local workout times are preserved.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}
