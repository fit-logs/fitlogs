package com.example.fitnotes.ui

import android.app.backup.BackupManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.SystemBackupStore
import java.text.DateFormat
import java.util.Date

@Composable
fun SystemBackupDialog(onClose: () -> Unit, onExport: () -> Unit, onReview: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SystemBackupStore(context.applicationContext) }
    var state by remember { mutableStateOf(store.state()) }
    var launchError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state = store.state() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Automatic backup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Use Android backup", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(state.enabled, onCheckedChange = { enabled ->
                    store.enable(enabled)
                    if (enabled) BackupManager(context).dataChanged()
                }, enabled = Build.VERSION.SDK_INT >= 28, modifier = Modifier.semantics { contentDescription = "Use Android backup" })
            }
            Text("Uses your phone's Google backup account. Enable Google backup and a screen lock in Android Settings.")
            Text("Includes workouts, measurements, plans and training preferences. Progress photos are excluded.")
            Text("Android schedules backups, usually daily on Wi-Fi. Check completion in Android Settings.", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT < 28) Text("Automatic encrypted backup requires Android 9 or newer. You can still export a backup file.")
            if (state.enabled && state.preparedAt == 0L) Text("Waiting for Android's first backup.", style = MaterialTheme.typography.bodySmall)
            if (state.preparedAt > 0) Text("Last prepared for Android: " + DateFormat.getDateTimeInstance().format(Date(state.preparedAt)) + ". Check Android Settings to confirm cloud upload.", style = MaterialTheme.typography.bodySmall)
            (launchError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (store.hasRecovery()) {
                Text("Android recovered a backup. Review it before replacing any saved training.")
                TextButton(onClick = onReview) { Text("Review recovered backup") }
            }
            TextButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                catch (_: Exception) { launchError = "Open Android Settings and search for Backup." }
            }) { Text("Open Android Settings") }
            TextButton(onClick = onExport) { Text("Export a copy to Drive or a file") }
            CollapsedGuidance("How backups work", "Android waits until the phone is idle and at least 24 hours have elapsed. Only the latest copy is kept in device backup, not My Drive. Search Android Settings for Backup to check your account and status.\n\nFor a separate file now, choose Drive in the export picker. Photos require the manual ZIP backup. Recovered training always needs your confirmation before replacing saved records.")
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
