package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import java.io.File

/** Every record in these captures is synthetic, in a separately identified preview app. */
class BodyOverviewInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun seed(profile: BodyProfile? = BodyProfile(heightCm = 180.0, birthDate = "2000-01-01"), sparse: Boolean = false) {
        assumeTrue("Synthetic fixtures must never touch the personal app", context.packageName == "com.example.fitnotes.preview.photos")
        val today = bodyLocalDate()
        val offsets = if (sparse) listOf(-9) else listOf(-150, -55, -48, -28, -27, -14, -7, 0)
        val records = offsets.mapIndexed { i, day -> BodyMeasurement("test-$i", "synthetic-body", "$i", shiftBodyDay(today, day), "", "Bodyweight", if (day < -27) 80.6 else 80.0, "kgs", "Kilograms", "Synthetic test measurement", "{}") }
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            SystemBackupStore(context).enable(false)
            model.pendingRestore.value = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList(),
                imports = listOf(HistoryImport("synthetic-body", "Synthetic test data", "fixture", 1000, "kg", 0, "", "{}")), bodyMeasurements = records, bodyProfile = profile)
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && model.data.value.imported.body.size == records.size && model.bodyProfile.value == profile }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
        ui.onNode(hasText("Body", substring = false) and hasClickAction()).performClick()
    }
    private fun scroll(text: String) = ui.onNodeWithTag("body-overview").performScrollToNode(hasText(text, substring = false))
    private fun capture(name: String) {
        ui.waitForIdle(); val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(context.filesDir, "synthetic-body-$name$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun overviewRangeAndProfileSurviveReopenAndExportsExcludeProfile() {
        seed()
        ui.onNodeWithTag("latest-weight").assertTextEquals("80.0 kg")
        ui.onNodeWithTag("measurement-range:3M").assertIsSelected()
        capture("overview")
        scroll("All body measurements ›"); ui.onNodeWithText("Healthy weight range").assertIsDisplayed(); capture("bmi-and-trend")
        scroll("Weight history"); ui.onNodeWithTag("measurement-range:1M").performClick()
        ui.waitUntil(15000) { model.bodyProfile.value?.chartRange == "1M" && !model.busy.value }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("measurement-range:1M").assertIsSelected()
        val backup = runBlocking { WorkoutRepository(db).backup() }
        assertFalse(BackupCodec.analysis(backup).contains("2000-01-01")); assertTrue(backup.workouts.isEmpty()); assertTrue(backup.sets.isEmpty())
        ui.onNodeWithText("Body profile", substring = false).performClick(); capture("profile")
        ui.onNodeWithText("Height (cm)").performTextReplacement("200")
        ui.onNodeWithText("Save profile").assertIsDisplayed(); capture("profile-keyboard")
        Espresso.closeSoftKeyboard(); ui.onNodeWithText("Save profile").performClick()
        ui.waitUntil(15000) { model.bodyProfile.value?.heightCm == 200.0 && !model.busy.value }
        scroll("BMI 20.0"); ui.onNodeWithTag("current-bmi").assertTextEquals("BMI 20.0")
    }
    @Test fun missingHeightAndSparseHistoryKeepLoggingAvailable() {
        seed(profile = null, sparse = true)
        capture("sparse")
        scroll("Add height to calculate BMI"); capture("missing-height")
        ui.onNodeWithText("Average weight", substring = false).assertDoesNotExist()
        ui.onNodeWithText("Add height to calculate BMI").performClick()
        ui.onNodeWithText("Birth date (optional)").performScrollTo().performTextReplacement("2999-01-01")
        Espresso.closeSoftKeyboard(); ui.onNodeWithText("Save profile").assertIsNotEnabled()
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertNull(runBlocking { db.bodyProfileDao().get() })
        scroll("Log weight"); ui.onNodeWithText("Log weight", substring = false).performClick()
        ui.onNodeWithText("Value", substring = false).performTextReplacement("81.2")
        ui.onNodeWithText("Save", substring = false).assertIsDisplayed(); capture("weight-keyboard")
        Espresso.closeSoftKeyboard(); ui.onNodeWithText("Save", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.imported.body.size == 2 && !model.busy.value }
        ui.onNodeWithTag("latest-weight").assertTextEquals("81.2 kg")
        assertTrue(runBlocking { db.importedDao().body().all { it.time.isEmpty() } && db.dao().workouts().isEmpty() })
    }
    @Test fun latestEditingDeletingAndUndoUpdateBmiAndPreserveTheOtherReadings() {
        seed()
        fun chooseLatest() {
            scroll("View 7 plotted measurements"); ui.onNodeWithText("View 7 plotted measurements").performClick()
            ui.onNodeWithTag("plot-record:test-7").performClick()
        }
        chooseLatest(); ui.onNodeWithText("Edit", substring = false).performClick()
        ui.onNodeWithText("Value", substring = false).performTextReplacement("82")
        Espresso.closeSoftKeyboard(); ui.onNodeWithText("Save", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.imported.body.any { it.id == "test-7" && it.value == 82.0 } && !model.busy.value }
        scroll("BMI 25.3"); ui.onNodeWithText("Overweight range").assertIsDisplayed()
        chooseLatest(); ui.onNodeWithText("Delete", substring = false).performClick()
        ui.onNodeWithText("Delete", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.imported.body.size == 7 && !model.busy.value }
        ui.onNodeWithText("Undo", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.imported.body.size == 8 && !model.busy.value }
        scroll("BMI 25.3"); ui.onNodeWithTag("current-bmi").assertTextEquals("BMI 25.3")
    }
    @Test fun extremeHeightRequiresConfirmationAndUnknownAgeHasNoAdultCategory() {
        seed(profile = null, sparse = true)
        ui.onNodeWithText("Body profile", substring = false).performClick()
        ui.onNodeWithText("Height (cm)").performTextReplacement("18")
        Espresso.closeSoftKeyboard(); ui.onNodeWithText("Save profile").performClick()
        ui.onNodeWithText("Check height").assertIsDisplayed(); assertNull(runBlocking { db.bodyProfileDao().get() })
        ui.onNodeWithText("Use this height").performClick()
        ui.waitUntil(15000) { model.bodyProfile.value?.heightCm == 18.0 && !model.busy.value }
        scroll("Add birth date for age-appropriate interpretation")
        ui.onNodeWithText("Obesity range").assertDoesNotExist(); ui.onNodeWithText("Healthy weight range").assertDoesNotExist()
    }
}
