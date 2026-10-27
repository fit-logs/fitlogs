package com.example.fitnotes.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import com.example.fitnotes.update.*
import java.util.Locale

@Composable
fun AppUpdateDialog(onClose: () -> Unit, model: AppUpdateViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val install = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshPermission() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshPermission() }
    var launchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { if (model.state.value.phase == UpdatePhase.IDLE) model.check() }
    AppUpdateContent(state, launchError, onClose = { model.cancel(); onClose() }, onCheck = { launchError = null; model.check() },
        onDownload = { launchError = null; model.download() }, onInstall = { model.install { install.launch(it) } },
        onAllow = {
            try {
                permission.launch(if(Build.VERSION.SDK_INT>=26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    else Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            catch (_: Exception) { launchError = "Open Android Settings → Apps → Special app access → Install unknown apps, and allow FitNotes." }
        }, onReleases = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, (state.release?.listing?.page ?: UPDATE_RELEASES_URL).toUri())) }
            catch (_: Exception) { launchError = "No browser is available to open GitHub." }
        })
}

@Composable
internal fun AppUpdateContent(state: UpdateUiState, launchError: String? = null, onClose: () -> Unit,
    onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit, onAllow: () -> Unit, onReleases: () -> Unit) {
    val release = state.release
    val canDownload = !state.busy && release != null && release.versionCode > state.installedCode && state.file == null
    AlertDialog(onDismissRequest = onClose, title = { Text("Update FitNotes") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Installed: ${state.installedName} (${state.installedCode})")
            Text(when (state.phase) {
                UpdatePhase.IDLE -> "Check GitHub for the latest update."
                UpdatePhase.CHECKING -> "Checking GitHub…"
                UpdatePhase.NO_RELEASE -> "No Android update has been published yet."
                UpdatePhase.CURRENT -> "You have the latest published version."
                UpdatePhase.AVAILABLE -> "Version ${release?.versionName} is available."
                UpdatePhase.DOWNLOADING -> "Downloading update…"
                UpdatePhase.VERIFYING -> "Checking the downloaded update…"
                UpdatePhase.READY -> "Version ${release?.versionName} is downloaded and verified."
                UpdatePhase.ERROR -> "The update could not finish."
            })
            if (state.phase == UpdatePhase.DOWNLOADING && release != null) {
                LinearProgressIndicator(progress = { (state.downloaded.toFloat() / release.listing.apk.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${String.format(Locale.ROOT, "%.1f", state.downloaded / 1048576.0)} / ${String.format(Locale.ROOT, "%.1f", release.listing.apk.bytes / 1048576.0)} MB")
            } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (launchError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (release != null && release.versionCode > state.installedCode) {
                if (release.listing.notes.isNotBlank()) CollapsedGuidance("What's new", release.listing.notes)
                Text("Android will ask you to confirm installation. Your workouts and settings stay in place.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.file != null && !state.mayInstall) Text("Allow FitNotes to install updates in Android Settings, then return here and tap Install update.")
            if (release != null && !state.busy) TextButton(onClick = onCheck) { Text("Check again") }
            Text("Checks and downloads come from GitHub. Training data is not uploaded.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.file != null && !state.busy && !state.mayInstall -> Button(onClick = onAllow) { Text("Allow updates") }
                state.file != null && !state.busy -> Button(onClick = onInstall) { Text("Install update") }
                canDownload -> Button(onClick = onDownload) { Text("Download update") }
                !state.busy -> Button(onClick = onCheck) { Text("Check for updates") }
            }
        }
    }, dismissButton = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.busy) TextButton(onClick = onReleases) { Text("GitHub releases") }
            TextButton(onClick = onClose) { Text(if (state.busy) "Cancel" else "Close") }
        }
    })
}
