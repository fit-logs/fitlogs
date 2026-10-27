package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class MeasurementInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database

    @Before fun setup() {
        assumeTrue("Fixtures require the separate preview app", InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.example.fitnotes.preview.photos")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList())
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
        ui.onNode(hasText("Body", substring = false) and hasClickAction()).performClick()
        if (ui.onAllNodesWithTag("body-overview").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithTag("body-overview").performScrollToNode(hasText("All body measurements ›"))
            ui.onNodeWithText("All body measurements ›").performClick()
        }
    }

    private fun enterMeasurement(time: String) {
        ui.onNodeWithText("Add measurement", substring = false).performClick()
        ui.onNodeWithText("Measurement name", substring = false).performTextReplacement("Test weight")
        ui.onNodeWithText("Unit (blank for unitless)").performTextReplacement("kgs")
        ui.onNodeWithText("Value", substring = false).performTextReplacement("72.625")
        ui.onNodeWithText("Time (optional)").performScrollTo().performTextReplacement(time)
        ui.onNodeWithText("Comment", substring = false).performScrollTo().performTextReplacement("Synthetic note")
        Espresso.closeSoftKeyboard()
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(instrumentation.targetContext.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun minuteTimeCanBeSavedReopenedAndEditedWithoutCreatingTraining() {
        enterMeasurement("20:12")
        ui.onNodeWithText("Save", substring = false).assertIsEnabled()
        capture("measurement-minutes-save")
        ui.onNodeWithText("Save", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.imported.body.size == 1 && !model.busy.value }
        val saved = runBlocking { db.importedDao().body().single() }
        assertEquals("20:12:00", saved.time); assertEquals("kgs", saved.unit)
        assertEquals(72.625, saved.value, 0.0); assertEquals("Synthetic note", saved.comment)
        ui.activityRule.scenario.recreate()
        ui.onNode(hasText("Body", substring = false) and hasClickAction()).performClick()
        if (ui.onAllNodesWithTag("body-overview").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithTag("body-overview").performScrollToNode(hasText("All body measurements ›"))
            ui.onNodeWithText("All body measurements ›").performClick()
        }
        ui.onNodeWithTag("body-measurements").performScrollToNode(hasText("72.625 kg") and hasClickAction())
        ui.onNode(hasText("72.625 kg") and hasClickAction()).performClick()
        ui.onNodeWithText("Edit", substring = false).performClick()
        ui.onNodeWithText("Time (optional)").performScrollTo().assertTextContains("20:12:00")
        ui.onNodeWithText("Time (optional)").performTextReplacement("21:05:09")
        Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save", substring = false).assertIsEnabled().performClick()
        ui.waitUntil(15000) { model.data.value.imported.body.singleOrNull()?.time == "21:05:09" && !model.busy.value }
        assertEquals(saved.id, runBlocking { db.importedDao().body().single().id })
        assertTrue(runBlocking { db.dao().workouts().isEmpty() && db.dao().sets().isEmpty() })
        capture("measurement-saved")
    }

    @Test fun invalidTimeShowsAnErrorAndCancelLeavesTheDatabaseUnchanged() {
        val before = runBlocking { WorkoutRepository(db).backup() }
        enterMeasurement("24:12")
        ui.onNodeWithText("Time (optional)").performScrollTo()
        ui.onNodeWithText("Enter a valid time, e.g. 20:12 or 20:12:03.").assertIsDisplayed()
        ui.onNodeWithText("Save", substring = false).assertIsNotEnabled()
        capture("measurement-invalid-time")
        ui.onNodeWithText("Time (optional)").performTextReplacement("")
        Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save", substring = false).assertIsEnabled()
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(before, runBlocking { WorkoutRepository(db).backup() }.copy(exportedAt = before.exportedAt))
    }
}
