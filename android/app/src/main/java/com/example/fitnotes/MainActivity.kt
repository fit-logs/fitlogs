package com.example.fitnotes

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fitnotes.ui.FitNotesApp
import com.example.fitnotes.ui.theme.FitNotesTheme

class MainActivity : ComponentActivity() {
    private fun receiveSharedProgram(intent: Intent?) {
        if(intent?.action==Intent.ACTION_SEND) {
            val model=androidx.lifecycle.ViewModelProvider(this)[FitNotesViewModel::class.java]
            val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if(text!=null)model.receiveProgramShare(text)
            else IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,Uri::class.java)?.let(model::receiveProgramFileShare)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);receiveSharedProgram(intent) }

    override fun onResume() {
        super.onResume()
        androidx.lifecycle.ViewModelProvider(this)[FitNotesViewModel::class.java].healthForeground()
    }

    override fun onPause() {
        androidx.lifecycle.ViewModelProvider(this)[FitNotesViewModel::class.java].healthBackground()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null)receiveSharedProgram(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            FitNotesTheme {
                val model: FitNotesViewModel = viewModel()
                FitNotesApp(model)
            }
        }
    }
}
