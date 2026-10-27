package com.example.fitnotes

import android.app.Application
import com.example.fitnotes.dashboard.GoogleDashboardChanges

class FitLogsApplication : Application() {
    override fun onCreate() { super.onCreate(); GoogleDashboardChanges.start(this) }
}
