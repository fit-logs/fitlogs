package com.example.fitnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.ui.theme.FitNotesTheme

class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FitNotesTheme {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Health Connect & your training history", style = MaterialTheme.typography.titleLarge)
                Text("FitNotes reads exercise sessions, heart-rate samples and distance from your selected Health Connect sources. Automatic import runs when you open FitNotes after you enable it; Sync now and date-range previews are also available. Optional background permission allows Android-scheduled reads, and optional history permission allows older available records. Neither permission is required for manual workout logging. You can disable import and revoke access at any time.")
                Text("New selected-source activities can import automatically. Possible duplicates need review; a preview lets you attach to a local session or ignore a source record. Saved data remains on this device. FitNotes never writes to Health Connect and has no account or cloud upload. Backups and text leave the app only through your explicit export or sharing action.")
                Text("Record on your watch separately. Garmin data can be attached after sync.")
                Text("The local timer does not start your watch, record GPS or receive live heart rate.")
                TextButton(onClick = { finish() }) { Text("Close") }
            }
        } }
    }
}
