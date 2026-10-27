package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.dashboard.GoogleDashboardStore
import com.example.fitnotes.data.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class GoogleDashboardInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun allTrainingDetailsNeedNoInclusionOptionsAndKeepLocalTrainingUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Synthetic preview only", context.packageName == "com.example.fitnotes.preview.photos")
        val store = GoogleDashboardStore(context); store.disconnect()
        lateinit var model: FitNotesViewModel
        ui.activityRule.scenario.onActivity { model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false) }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading }
        val before = runBlocking { WorkoutRepository(model.photoRepository.database).backup() }
        fun open() {
            ui.onNodeWithContentDescription("Settings").performClick()
            ui.onNodeWithText("Google training dashboard", substring = false).performClick()
            ui.onNodeWithTag("google-dashboard").assertExists()
        }
        open()
        ui.onNodeWithTag("google-include-notes").assertDoesNotExist()
        ui.onNodeWithTag("google-include-body").assertDoesNotExist()
        ui.onNodeWithTag("google-training-details").assertTextEquals("Includes completed workouts, imported activities, training notes and body measurements.")
        ui.onNodeWithTag("google-sync").assertIsEnabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation(); instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "google-dashboard-details.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("google-dashboard").assertExists()
        ui.onNodeWithTag("google-include-body").assertDoesNotExist()
        ui.onNodeWithTag("google-training-details").assertExists()
        assertFalse(store.automatic()); assertEquals("", store.read().fileId); assertEquals(0L, store.read().lastSuccessAt)
        val after = runBlocking { WorkoutRepository(model.photoRepository.database).backup() }
        assertEquals(before.workouts, after.workouts); assertEquals(before.entries, after.entries); assertEquals(before.sets, after.sets)
        assertEquals(before.bodyMeasurements, after.bodyMeasurements); assertEquals(before.importedSets, after.importedSets)
        store.disconnect()
    }
}
