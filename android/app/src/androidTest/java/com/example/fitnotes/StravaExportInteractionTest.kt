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
import java.time.Instant

class StravaExportInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val db get() = model.photoRepository.database
    private val start = Instant.parse("2026-10-22T11:30:00Z").toEpochMilli()
    private val csv = """Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Distance,Moving Time,Elevation Gain,Average Heart Rate,Max Heart Rate
1,2026-10-23T00:30:00+13:00,Synthetic shared run,Run,1800,5,1500,123.5,125,150
2,2026-10-23T02:30:00+13:00,Synthetic separate ride,Ride,3600,10,3300,321,140,160"""
    private fun capture(name: String) {
        ui.waitForIdle(); i.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = i.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(i.targetContext.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Before fun seed() {
        assumeTrue(i.targetContext.packageName == "com.example.fitnotes.preview.photos")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = listOf(Workout(10, 0, sessionDate = "2026-10-23", finished = true, activityType = "running"), Workout(20, System.currentTimeMillis(), title = "Active synthetic gym", notes = "Keep gym notes")),
                entries = listOf(WorkoutExercise(1, 20, 1, 0)), sets = listOf(WorkoutSet(1, 1, 40.0, 8, 0)),
                healthSessions = listOf(HealthSession(healthKey(GARMIN_PACKAGE, "garmin-run"), 10, "garmin-run", GARMIN_PACKAGE, "Synthetic Garmin", null, 0, start, start,
                    start, start + 1800000, 46800, 46800, "running", 56, "Synthetic Garmin run", "Keep source note", 5000.0, GARMIN_PACKAGE, "", "", sourceCreated = true)))
            BackupCodec.validate(model.pendingRestore.value!!); model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        ui.mainClock.advanceTimeBy(10000)
        model.inspectStravaText(csv)
        ui.waitUntil(15000) { !model.stravaExportWorking.value && model.stravaExportHeaders.value.isNotEmpty() }
    }
    private fun scroll(text: String) { ui.onNodeWithTag("strava-export-list").performScrollToNode(hasText(text, substring = false)) }
    private fun choice(label: String, value: String) { scroll(label); ui.onNodeWithText(label, substring = false).performClick(); ui.onNodeWithText(value, substring = false).performClick() }
    @Test fun reviewGarminAttachmentImportBatchAndKeepActiveGymLoggingAvailable() {
        ui.onNodeWithContentDescription("Settings").performClick(); ui.onNodeWithText("Strava activity files", substring = false).performClick()
        ui.onNodeWithTag("strava-export-list").performScrollToNode(hasTestTag("strava-owner"))
        ui.onNodeWithTag("strava-owner").performTextReplacement("synthetic-owner"); Espresso.closeSoftKeyboard()
        choice("Time units: Confirm units ▾", "seconds")
        choice("Distance units: Confirm units ▾", "km")
        choice("Elevation units: Confirm units ▾", "m")
        scroll("Load activity preview"); ui.onNodeWithText("Load activity preview", substring = false).performClick()
        ui.waitUntil(15000) { !model.stravaExportWorking.value && model.stravaExportPreview.value?.candidates?.size == 2 }
        scroll("Import 1 selected activities"); ui.onNodeWithText("Import 1 selected activities", substring = false).assertIsEnabled()
        ui.onNodeWithTag("strava-export-list").performScrollToNode(hasTestTag("strava-row:1"))
        ui.onNode(hasText("Attach:", substring = true) and hasClickAction()).performScrollTo().performClick()
        capture("strava-export-review")
        scroll("Import 2 selected activities"); ui.onNodeWithText("Import 2 selected activities", substring = false).performClick()
        ui.onNodeWithText("Import", substring = false).performClick()
        ui.waitUntil(15000) { !model.stravaExportWorking.value && runBlocking { db.healthDao().sessions().size == 3 } }
        assertFalse(model.busy.value); assertEquals(3, runBlocking { db.dao().workouts().size })
        assertEquals(20L, runBlocking { db.dao().active()!!.id }); assertEquals(1, runBlocking { db.dao().sets().size })
        assertEquals(10L, runBlocking { db.healthDao().session("strava_owner_export:synthetic-owner:1")!!.workoutId })
        ui.onNodeWithText("2 selected activities processed. Existing workouts and saved sets were kept.", substring = false).assertIsDisplayed()
        capture("strava-export-complete")
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.mainClock.advanceTimeBy(10000)
        ui.onNodeWithText("Resume workout", substring = false).performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Synthetic press", substring = false))
        ui.onNodeWithText("Synthetic press", substring = false).performClick()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Weight (kg)"))
        ui.onNodeWithTag("field:Weight (kg)").performTextReplacement("42.5")
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Reps")); ui.onNodeWithTag("field:Reps").performTextReplacement("9")
        Espresso.closeSoftKeyboard(); ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Save set", substring = false))
        ui.onNodeWithText("Save set", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().sets().size == 2 } }
        assertEquals("Keep gym notes", runBlocking { db.dao().workout(20)!!.notes }); capture("strava-export-gym")
    }
}
