package com.example.fitnotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fitnotes.dashboard.*
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.text.DateFormat
import java.util.Date

@Composable
fun GoogleDashboardDialog(onClose: () -> Unit, model: GoogleDashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val connection by model.connection.collectAsState()
    val options by model.options.collectAsState()
    val automatic by model.automatic.collectAsState()
    val message by model.message.collectAsState()
    val working by model.working.collectAsState()
    var authorizing by remember { mutableStateOf(false) }
    var action by rememberSaveable { mutableStateOf("sync") }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun accept(result: AuthorizationResult) {
        if (!alive.get()) return
        authorizing = false
        try { model.sync(dashboardAccessToken(result), switchAccount = action == "switch", newDashboard = action == "new", repairCharts = action == "repair") }
        catch (_: Exception) { model.status("Google permission was not granted. Your local records are unchanged.") }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        authorizing = false
        if (result.resultCode != Activity.RESULT_OK || result.data == null) model.status("Google connection cancelled. Your local records are unchanged.")
        else try { accept(client.getAuthorizationResultFromIntent(result.data!!)) }
        catch (_: Exception) { model.status("Google could not connect to FitLogs. Retry or check for an app update.") }
    }
    fun connect(next: String) {
        if (working || authorizing) return
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            model.status("Google connection needs Google Play services on this device."); return
        }
        action = next; authorizing = true
        client.authorize(dashboardAuthorization(connection.email, chooseAccount = next == "switch"))
            .addOnSuccessListener { result ->
                if (!alive.get()) return@addOnSuccessListener
                if (result.hasResolution()) {
                    val intent = result.pendingIntent
                    if (intent == null) { authorizing = false; model.status("Google could not start account selection. Please retry.") }
                    else try { launcher.launch(IntentSenderRequest.Builder(intent).build()) }
                    catch (_: Exception) { authorizing = false; model.status("Google could not open account selection. Please retry.") }
                } else accept(result)
            }.addOnFailureListener { if (alive.get()) { authorizing = false; model.status("Google could not connect to FitLogs. Retry or check for an app update.") } }
    }
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { model.status("No browser is available to open this link.") } }
    val busy = working || authorizing
    AlertDialog(onDismissRequest = onClose, title = { Text("Google training dashboard") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("google-dashboard"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Create a spreadsheet in your own Google Drive. Your phone syncs directly to Google; the developer and other FitLogs users receive no copy.")
            Text("Completed training records only. Your local database stays the source of truth. This dashboard is not a full backup.", style = MaterialTheme.typography.bodySmall)
            if (connection.email.isNotBlank()) Text(connection.email, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(options.includeNotes, { model.options(options.copy(includeNotes = it)) }, enabled = !busy, modifier = Modifier.testTag("google-include-notes"))
                Text("Include training notes")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(options.includeBody, { model.options(options.copy(includeBody = it)) }, enabled = !busy, modifier = Modifier.testTag("google-include-body"))
                Text("Include body measurements")
            }
            Text("Photos, body profiles and raw heart-rate samples stay out of the dashboard.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { connect("sync") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("google-sync")) {
                Text(if (authorizing) "Connecting…" else if (working) "Syncing…" else if (connection.accountId.isBlank()) "Connect and create dashboard" else "Sync now")
            }
            if (message.isNotBlank()) Text(message, modifier = Modifier.testTag("google-sync-status"))
            if (connection.lastSuccessAt > 0) Text("Last successful sync: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(connection.lastSuccessAt)), style = MaterialTheme.typography.bodySmall)
            if (working) TextButton(onClick = model::cancel) { Text("Cancel sync") }
            if (connection.fileId.isNotBlank()) {
                TextButton(onClick = { open("https://docs.google.com/spreadsheets/d/${connection.fileId}/edit#gid=100") }) { Text("Open dashboard") }
                TextButton(onClick = { open("https://drive.google.com/drive/folders/${connection.folderId}") }) { Text("Open Drive folder") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(automatic, model::automatic, enabled = !busy && connection.lastSuccessAt > 0, modifier = Modifier.testTag("google-auto-sync"))
                    Text("Automatic sync", Modifier.padding(start = 12.dp))
                }
                Text("Automatic sync waits until your workout is finished and a connection is available.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirm = "repair" }, enabled = !busy) { Text("Rebuild dashboard charts") }
                TextButton(onClick = { confirm = "new" }, enabled = !busy) { Text("Create a new dashboard") }
            }
            if (connection.accountId.isNotBlank()) {
                TextButton(onClick = { confirm = "switch" }, enabled = !busy) { Text("Switch Google account") }
                TextButton(onClick = { confirm = "disconnect" }, enabled = !busy) { Text("Disconnect Google") }
            }
            TextButton(onClick = { open("https://fit-logs.github.io/fitlogs/privacy/") }) { Text("Privacy policy") }
            TextButton(onClick = { open("https://myaccount.google.com/connections") }) { Text("Google account permissions") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
    confirm?.let { next -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(when (next) {
        "switch" -> "Switch Google account?"; "new" -> "Create a new dashboard?"; "disconnect" -> "Disconnect Google?"; else -> "Rebuild dashboard charts?"
    }) }, text = { Text(when (next) {
        "switch" -> "Choose the Google account that will receive your completed training records. A new private dashboard will be created. Existing Drive files and local records are kept. Notes and body measurements will be excluded until you opt in again."
        "new" -> "Create a new private dashboard. Existing Drive files and local training records are kept."
        "disconnect" -> "Stop sync and remove FitLogs Google access. Existing Drive files and local training records are kept."
        else -> "Refresh FitLogs data, charts and formatting in the managed tabs. Your additional spreadsheet tabs are kept."
    }) }, confirmButton = { TextButton(onClick = { confirm = null; if (next == "disconnect") model.disconnect() else connect(next) }) { Text(if (next == "disconnect") "Disconnect" else "Continue") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
}
