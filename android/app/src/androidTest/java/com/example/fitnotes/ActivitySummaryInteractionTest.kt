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
import java.time.Instant

class ActivitySummaryInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val start = Instant.parse("2026-10-22T11:30:00Z").toEpochMilli()
    private fun source(id: String, workout: Long, slot: Int, sport: Int, type: String, title: String) = HealthSession(
        healthKey(GARMIN_PACKAGE, id), workout, id, GARMIN_PACKAGE, "Synthetic Garmin", id, 1, start, start,
        start + slot * 3600000, start + slot * 3600000 + 1800000, 46800, 46800, type, sport, title, "Synthetic source note",
        5000.0, GARMIN_PACKAGE, "Synthetic sample snapshot", "Synthetic distance", sourceCreated = workout != 4L,
        elevationMeters = if (type == "walking") 123.5 else null, elevationOrigins = if (type == "walking") GARMIN_PACKAGE else "")
    @Before fun seed() {
        assumeTrue(i.targetContext.packageName == "com.example.fitnotes.preview.photos")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            val sources = listOf(source("run", 1, 0, 56, "running", "Synthetic morning run"),
                source("hike", 2, 1, 37, "walking", "Synthetic morning hike"),
                source("ride", 3, 2, 8, "other", "Synthetic morning ride"),
                source("attached", 4, 3, 70, "strength", "Source gym title"))
            val sample = HealthSample(sampleKey(sources[0].key, GARMIN_PACKAGE, "hr", start + 1000), sources[0].key, "hr", GARMIN_PACKAGE, start + 1000, 125)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = listOf(Workout(1, 0, sessionDate = "2026-10-22", finished = true, activityType = "running"),
                    Workout(2, 0, sessionDate = "2026-10-22", finished = true, activityType = "walking"),
                    Workout(3, 0, sessionDate = "2026-10-22", finished = true, activityType = "other", activityLabel = "Cycling"),
                    Workout(4, 0, title = "Local date-only gym", notes = "Keep local note", sessionDate = "2026-10-23", finished = true)),
                entries = listOf(WorkoutExercise(1, 4, 1, 0)), sets = listOf(WorkoutSet(1, 1, 40.0, 8, 0)), healthSessions = sources, healthSamples = listOf(sample))
            BackupCodec.validate(model.pendingRestore.value!!)
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        ui.mainClock.advanceTimeBy(10000)
    }
    private fun capture(name: String) {
        ui.waitForIdle(); i.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = i.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(i.targetContext.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun sourceActivitiesHaveTimesSportsPaceAndSharedContextWhileAttachedGymStaysDateOnly() {
        ui.onNode(hasText("History") and hasClickAction()).performClick()
        // Scroll the real History surface to each activity, then inspect the same
        // resolved summary in details and in the user-controlled share preview.
        ui.onNodeWithTag("history-list").performScrollToNode(hasText("↗ Synthetic morning run", substring = false))
        ui.onNodeWithText("↗ Synthetic morning run").performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Source start · 2026-10-23 00:30 +13:00"))
        ui.onNodeWithText("Source start · 2026-10-23 00:30 +13:00").assertIsDisplayed()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("6:00 min/km elapsed pace"))
        ui.onNodeWithText("6:00 min/km elapsed pace").assertIsDisplayed(); capture("activity-summary-run")
        ui.onNodeWithContentDescription("Copy or share workout").performClick()
        ui.onNodeWithText("Training context", substring = false).assertExists()
        ui.onNode(hasText("6:00 min/km elapsed pace", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        capture("activity-summary-share")
        ui.onNodeWithText("Close", substring = false).performClick()
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithTag("history-list").performScrollToNode(hasText("↟ Synthetic morning hike", substring = false))
        ui.onNodeWithText("↟ Synthetic morning hike").performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Hiking", substring = false))
        ui.onNodeWithText("Hiking", substring = false).assertIsDisplayed()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Elevation gain · 123.5 m"))
        ui.onNodeWithText("Elevation gain · 123.5 m").assertIsDisplayed(); capture("activity-summary-hike")
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithTag("history-list").performScrollToNode(hasText("● Synthetic morning ride", substring = false))
        ui.onNodeWithText("● Synthetic morning ride").performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Cycling", substring = false))
        ui.onNodeWithText("Cycling", substring = false).assertIsDisplayed()
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithTag("history-list").performScrollToNode(hasText("◆ Local date-only gym", substring = false))
        ui.onNodeWithText("◆ Local date-only gym").performClick()
        ui.onNodeWithContentDescription("More actions").performClick()
        ui.onNodeWithText("Workout information", substring = false).performClick()
        ui.onNodeWithText("Date-only · no session time was recorded", substring = true).assertIsDisplayed()
        capture("activity-summary-date-only")
        val saved = runBlocking { WorkoutRepository(model.photoRepository.database).backup() }
        assertNull(saved.workouts.single { it.id == 4L }.knownStart); assertEquals("Keep local note", saved.workouts.single { it.id == 4L }.notes)
        assertEquals(1, saved.sets.size); assertEquals(4, saved.workouts.size)
    }
}
