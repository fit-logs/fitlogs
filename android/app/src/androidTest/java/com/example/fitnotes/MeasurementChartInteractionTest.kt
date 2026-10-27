package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class MeasurementChartInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun seed() {
        assumeTrue("Synthetic measurements require the separate preview", context.packageName == "com.example.fitnotes.preview.photos")
        SystemBackupStore(context).enable(false)
        val today = todayDate()
        fun record(id: String, offset: Int, value: Double, name: String = "Test weight", unit: String = "kgs", time: String = "") =
            BodyMeasurement(id, "chart-source", id, moveDay(today, offset), time, name, value, unit, unit, "Synthetic note $id", "{}")
        val records = listOf(record("old", -420, 70.0), record("middle", -80, 72.0), record("weight-a", -10, 73.125), record("weight-b", -10, 73.125),
            record("latest", 0, 74.25, time = "20:12:03"), record("pounds", 0, 160.0, unit = "lbs"),
            record("waist", -420, 66.625, "Waist circumference", "cm"), record("score-a", -1, -1.25, "Z score", ""), record("score-b", 0, .5, "Z score", ""))
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList(),
                imports = listOf(HistoryImport("chart-source", "Synthetic measurements", "fixture", 1000, "Mixed", 0, "", "{}")), bodyMeasurements = records)
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && model.data.value.imported.body.size == records.size }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
        ui.onNode(hasText("Body", substring = false) and hasClickAction()).performClick()
        ui.onNodeWithTag("body-overview").performScrollToNode(hasText("All body measurements ›"))
        ui.onNodeWithText("All body measurements ›").performClick()
    }

    private fun showChart() = ui.onNodeWithTag("body-measurements").performScrollToNode(hasTestTag("measurement-chart"))
    private fun chooseType(current: String, next: String) {
        ui.onNodeWithTag("body-measurements").performScrollToIndex(0)
        ui.onNodeWithText("$current ▾", substring = false).performClick()
        ui.onNodeWithText(next, substring = false).performClick()
    }
    private fun capture(name: String) {
        ui.waitForIdle(); val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(context.filesDir, "measurement-trend-$name$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }

    @Test fun rangesRepeatedPointsAndEditingSurviveReopenWithoutChangingOtherRecords() {
        val before = runBlocking { WorkoutRepository(db).backup() }
        showChart()
        ui.onNodeWithText("View 5 plotted measurements").assertExists()
        capture("all")
        ui.onNodeWithTag("measurement-range:1M").performClick()
        ui.onNodeWithText("View 3 plotted measurements").assertExists()
        ui.onNodeWithTag("measurement-chart").performTouchInput { click(Offset(1f, height * .75f)) }
        ui.onNodeWithTag("plot-record:weight-a").assertExists()
        ui.onNodeWithTag("plot-record:weight-b").assertExists()
        assertEquals(2, ui.onAllNodesWithText("Date only", substring = true).fetchSemanticsNodes().size)
        capture("repeated-point")
        ui.onNodeWithTag("plot-record:weight-a").performClick()
        ui.onNodeWithText("Edit", substring = false).performClick()
        ui.onNodeWithText("Value", substring = false).performTextReplacement("73.5")
        Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.imported.body.any { it.id == "weight-a" && it.value == 73.5 } }
        showChart(); capture("edited")
        ui.activityRule.scenario.recreate()
        showChart()
        ui.onNodeWithText("View 3 plotted measurements").assertExists()
        ui.onNodeWithTag("measurement-range:All").performClick()
        ui.onNodeWithText("View 5 plotted measurements").assertExists()
        val after = runBlocking { WorkoutRepository(db).backup() }
        assertEquals(before.copy(exportedAt = after.exportedAt, bodyMeasurements = before.bodyMeasurements.map { if (it.id == "weight-a") it.copy(value = 73.5) else it }), after)
        assertEquals(73.125, after.bodyMeasurements.single { it.id == "weight-b" }.value, 0.0)
        assertEquals("", after.bodyMeasurements.single { it.id == "weight-a" }.time)
        assertTrue(after.workouts.isEmpty() && after.sets.isEmpty())
    }

    @Test fun unitsStaySeparateAndSingleEmptyAndNegativeUnitlessSeriesRemainUsable() {
        chooseType("Test weight", "Test weight · lbs")
        showChart()
        ui.onNodeWithText("View 1 plotted measurement").assertExists()
        ui.onNodeWithText("lb", substring = false).assertIsDisplayed()
        chooseType("Test weight", "Waist circumference · cm")
        showChart()
        ui.onNodeWithText("cm", substring = false).assertIsDisplayed()
        capture("single")
        ui.onNodeWithTag("measurement-range:1M").performClick()
        ui.onNodeWithText("No measurements in this period").assertExists()
        ui.onNodeWithTag("measurement-chart").assertDoesNotExist()
        capture("empty-period")
        ui.onNodeWithTag("measurement-range:All").performClick()
        showChart()
        ui.onNodeWithTag("measurement-chart").performTouchInput { click(center) }
        ui.onNodeWithText("Edit", substring = false).assertIsDisplayed()
        ui.onNodeWithText("Close", substring = false).performClick()
        chooseType("Waist circumference", "Z score · ")
        showChart()
        ui.onNodeWithText("Value", substring = false).assertIsDisplayed()
        ui.onNodeWithText("View 2 plotted measurements").assertExists()
        capture("negative-unitless")
        assertEquals(9, runBlocking { db.importedDao().body().size })
    }
}
