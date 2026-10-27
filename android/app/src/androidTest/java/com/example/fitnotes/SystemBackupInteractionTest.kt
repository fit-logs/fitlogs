package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class SystemBackupInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = SystemBackupStore(context)

    @Before fun seed() {
        assumeTrue("Backup fixtures require the separate preview package", context.packageName == "com.example.fitnotes.preview.photos")
        store.discardRecovery(); store.preferences.edit().clear().commit()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")), workouts = emptyList(), entries = emptyList(), sets = emptyList())
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        runBlocking {
            val prescription = PlanPrescription("Synthetic press", listOf(PlanSetTarget(8, 10, PlanWeight("42.5", "kg"), 90)), "Exercise guidance")
            val plans = WorkoutPlanRepository(db)
            val id = (plans.save(PlanPreview(PlanSpec("Synthetic backup plan", "", 40, "Warm-up", listOf(prescription), "d".repeat(64)), listOf(ResolvedPlanExercise(prescription, 1)))) as PlanSaveResult.Saved).id
            plans.start(id)
            TrainingRepository(db).complete(db.planDao().runTargets().single().id, "40", "9", "Actual", LoadMode.EXTERNAL)
            HistoryImportRepository(db).saveMeasurement(null, "Test waist", "70.125", "cm", "Centimetres", "2026-10-15", "20:12", "Synthetic note")
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
    }

    private fun openAutomatic() {
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Backup and restore", substring = false).performClick()
        ui.onNodeWithText("Automatic backup", substring = false).performClick()
    }
    private fun capture(name: String) {
        ui.waitForIdle(); val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(context.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun optInPersistsAndRecoveredDataNeedsReviewBeforeReplacingTraining() {
        val before = runBlocking { WorkoutRepository(db).backup() }
        openAutomatic()
        ui.onNodeWithContentDescription("Use Android backup").assertIsOff().performClick().assertIsOn()
        capture("automatic-backup-settings")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithContentDescription("Use Android backup").assertIsOn()
        assertEquals(before, runBlocking { WorkoutRepository(db).backup() }.copy(exportedAt = before.exportedAt))
        // A recovered system snapshot must follow the same preview/confirmation as a manual backup.
        SystemBackupSnapshot.write(before, store.file); store.recovered()
        ui.onNodeWithText("Review recovered backup").performScrollTo().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pendingRestore.value != null }
        ui.onNodeWithText("Replace all training data?", substring = false).assertIsDisplayed()
        capture("automatic-backup-restore-review")
        assertEquals(before, runBlocking { WorkoutRepository(db).backup() }.copy(exportedAt = before.exportedAt))
        ui.onNodeWithText("Cancel", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pendingRestore.value == null }
        assertTrue(store.hasRecovery())
        assertEquals(before, runBlocking { WorkoutRepository(db).backup() }.copy(exportedAt = before.exportedAt))
        val original = runBlocking { db.importedDao().body().single() }
        runBlocking { HistoryImportRepository(db).saveMeasurement(original.id, original.name, "71", original.unit, original.unitName, original.date, original.time, "Later edit") }
        openAutomatic()
        ui.onNodeWithText("Review recovered backup").performScrollTo().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pendingRestore.value != null }
        ui.onNodeWithText("Replace & restore", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pendingRestore.value == null }
        assertFalse(store.hasRecovery()); assertFalse(store.file.exists())
        assertEquals(listOf(original), runBlocking { db.importedDao().body() })
        assertEquals(before.sets, runBlocking { db.dao().sets() })
        assertEquals(before.plannedSets, runBlocking { db.planDao().sets() })
        assertEquals(before.runTargets, runBlocking { db.planDao().runTargets() })
        assertTrue(store.state().enabled)
    }
}
