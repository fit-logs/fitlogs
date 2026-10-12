package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

// Explicitly isolated package: these fixtures never touch the personal installation.
class ActivityInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var db: FitDatabase
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Run only in the isolated activities preview", context.packageName == "com.example.fitnotes.preview.activities")
        db = FitDatabase.open(context)
        runBlocking { WorkoutRepository(db).restore(Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList())) }
    }
    @After fun cleanup() { if (::db.isInitialized) db.close() }
    private fun waitFor(text: String) { ui.waitUntil(30000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun startRotateNotesAndFinishNonStrengthActivity() {
        waitFor("Start workout"); ui.onNodeWithText("Start workout").performClick()
        ui.onNodeWithText("Activity: Strength training ▾").performClick()
        ui.onNodeWithText("Climbing / bouldering").performClick()
        capture("activity-form")
        ui.onNode(hasSetTextAction() and hasText("Notes")).performScrollTo().performTextInput("Synthetic climbing note")
        ui.onNodeWithText("Start session").performClick()
        ui.waitUntil(30000) { runBlocking { db.dao().active()?.activityType } == "climbing" }
        val before = runBlocking { db.dao().active()!! }
        ui.activityRule.scenario.recreate()
        waitFor("Finish session")
        assertEquals(before.startedAt, runBlocking { db.dao().active()!!.startedAt })
        ui.onNodeWithText("Add exercise").assertDoesNotExist()
        ui.onNodeWithText("Edit activity details").performScrollTo().assertIsDisplayed()
        capture("active-climbing")
        ui.onNodeWithText("Finish session").performClick(); ui.onNodeWithText("Finish", useUnmergedTree = true).performClick()
        ui.waitUntil(30000) { runBlocking { db.dao().active() } == null }
        val done = runBlocking { db.dao().workout(before.id)!! }
        assertTrue(done.isComplete); assertEquals("Synthetic climbing note", done.notes)
        assertTrue(runBlocking { db.dao().workoutEntries(done.id).isEmpty() })
    }
    @Test fun manualWithoutTimeAndCombinedTextPreview() {
        waitFor("Start workout"); ui.onNodeWithText("Start workout").performClick()
        ui.onNodeWithText("Activity: Strength training ▾").performClick(); ui.onNodeWithText("Running").performClick()
        ui.onNodeWithText("Entry: Start session ▾").performClick(); ui.onNodeWithText("Log past activity").performClick()
        ui.onNode(hasSetTextAction() and hasText("Duration (minutes)")).performScrollTo().performTextInput("25")
        ui.onNode(hasSetTextAction() and hasText("Notes")).performScrollTo().performTextInput("Synthetic easy run")
        ui.onNodeWithText("Save activity").performClick()
        ui.waitUntil(30000) { runBlocking { db.dao().workouts().any { it.activityType == "running" } } }
        val w = runBlocking { db.dao().workouts().single() }
        assertNull(w.knownStart); assertNull(w.completedAt); assertEquals(1500.0, w.durationSeconds!!, 0.0)
        waitFor("Local start time unknown"); ui.onNodeWithText("Local start time unknown").performScrollTo().assertIsDisplayed()
        capture("manual-running")
        ui.onNodeWithText("History", useUnmergedTree = true).performClick()
        waitFor("↗ Running"); ui.onNodeWithText("↗ Running").assertIsDisplayed()
        capture("combined-history")
        ui.onNodeWithContentDescription("Share").performClick()
        ui.onNodeWithText("Last 14 days").performClick()
        waitFor("Training context")
        ui.onNodeWithText("Training context").assertIsDisplayed()
        capture("sharing-preview")
    }
    @Test fun importPermissionStateAndSyntheticSourceDetailsAreReadable() {
        val id = runBlocking {
            val start = System.currentTimeMillis() - 3600000; val end = start + 1800000
            val s = HealthSession(healthKey("org.synthetic.source", "exercise"), null, "exercise", "org.synthetic.source", "Synthetic source", "fixture", 1,
                start, end, start, end, null, null, "running", 56, "Synthetic imported run", "Synthetic source note", 2500.0,
                "org.synthetic.source", "Synthetic fixture; no samples", "Synthetic distance fixture")
            HealthImportRepository(db).import(HealthCandidate(s, heartRateRead = true, distanceRead = true))!!
        }
        // Fixture writes use a separate Room instance. Trigger the UI instance's
        // invalidation through an unchanged local details write before inspecting it.
        ui.activityRule.scenario.onActivity { activity ->
            androidx.lifecycle.ViewModelProvider(activity)[FitNotesViewModel::class.java].saveDetails(id, "", "")
        }
        ui.onNodeWithText("History", useUnmergedTree = true).performClick()
        waitFor("↗ Running"); ui.onNodeWithText("↗ Running").performClick()
        waitFor("Imported from Synthetic source")
        ui.onNodeWithText("Imported from Synthetic source").performScrollTo().assertIsDisplayed()
        capture("imported-session")
        ui.onNodeWithText("No heart-rate samples found for this session\nSynthetic fixture; no samples").performScrollTo().assertIsDisplayed()
        capture("imported-missing-heart-rate")
        ui.onNodeWithContentDescription("More actions").performClick(); ui.onNodeWithText("Import history").performClick()
        ui.onNodeWithText("Import activities (Health Connect)").performClick()
        waitFor("Load preview"); ui.onNodeWithText("Load preview").performScrollTo().performClick()
        waitFor("Exercise-session permission is missing or revoked. Grant it in Health Connect to preview activities.")
        ui.onNodeWithText("Exercise-session permission is missing or revoked. Grant it in Health Connect to preview activities.").performScrollTo().assertIsDisplayed()
        capture("health-permission-missing")
    }
    @Test fun syntheticPreviewRequiresAttachConfirmationAndRefreshesWithoutDuplicating() {
        val w = runBlocking {
            val repo = WorkoutRepository(db); val id = repo.start(); repo.createExercise(id, "Synthetic press")
            repo.saveSet(db.dao().workoutEntries(id).single().id, "50", "8")
            repo.finish(id); db.dao().workout(id)!!
        }
        val model = androidx.lifecycle.ViewModelProvider(ui.activity)[FitNotesViewModel::class.java]
        ui.activityRule.scenario.onActivity { model.saveDetails(w.id, "Synthetic lifting fixture", "Preserve local notes") }
        ui.waitUntil(30000) { runBlocking { db.dao().workout(w.id)?.notes } == "Preserve local notes" }
        ui.onNodeWithContentDescription("More actions").performClick(); ui.onNodeWithText("Import history").performClick()
        ui.onNodeWithText("Import activities (Health Connect)").performClick()
        val start = w.startedAt - 1000; val end = w.completedAt!! + 1000
        val s = HealthSession(healthKey(GARMIN_PACKAGE, "synthetic-ui-exercise"), null, "synthetic-ui-exercise", GARMIN_PACKAGE,
            "Synthetic watch source", "synthetic-ui", 1, start, end, start, end, null, null, "strength", 70,
            "Synthetic watch workout", "Source note", null, "", "Synthetic samples", "No synthetic distance")
        fun sample(t: Long, bpm: Long) = HealthSample(sampleKey(s.key, GARMIN_PACKAGE, "synthetic-hr", t), s.key, "synthetic-hr", GARMIN_PACKAGE, t, bpm)
        val c = HealthCandidate(s, listOf(sample(w.startedAt, 110)), heartRateRead = true, distanceRead = true)
        ui.runOnIdle { model.healthCandidates.value = listOf(c); model.healthMessage.value = "Synthetic preview fixture" }
        ui.onNodeWithTag("health-preview-list").performScrollToNode(hasText("Synthetic watch workout"))
        ui.onNodeWithText("Possible match · 1 local sessions").performScrollTo().assertIsDisplayed()
        capture("health-synthetic-preview")
        ui.onNodeWithText("Attach to local session…").performScrollTo().performClick()
        ui.onNodeWithText("Confirm attach").assertIsNotEnabled()
        ui.onNode(hasText("Synthetic lifting fixture", substring = true)).performScrollTo().performClick()
        ui.onNodeWithText("Confirm attach").performClick()
        waitFor("Already imported")
        assertEquals(1, runBlocking { db.dao().workouts().size }); assertEquals(1, runBlocking { db.dao().sets().size })
        assertEquals("Preserve local notes", runBlocking { db.dao().workout(w.id)!!.notes })
        ui.runOnIdle { model.healthCandidates.value = listOf(c.copy(samples = c.samples + sample(w.completedAt, 130))) }
        ui.onNodeWithText("Refresh imported data").performScrollTo().performClick()
        ui.waitUntil(30000) { runBlocking { db.healthDao().samples().size } == 2 }
        assertEquals(1, runBlocking { db.healthDao().sessions().size }); assertEquals(w.startedAt, runBlocking { db.dao().workout(w.id)!!.startedAt })
    }
}
