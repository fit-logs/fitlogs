package com.example.fitnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fitnotes.ui.FitNotesApp
import com.example.fitnotes.ui.theme.FitNotesTheme

class MainActivity : ComponentActivity() {
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
