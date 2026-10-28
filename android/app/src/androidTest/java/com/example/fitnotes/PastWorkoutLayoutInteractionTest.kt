package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.moveDay
import com.example.fitnotes.ui.todayDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PastWorkoutLayoutInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val db get() = model.photoRepository.database
    private val exerciseName = "Incline Barbell Bench Press with a deliberately long exercise name"

    @Before fun prepare() {
        assumeTrue("Synthetic fixtures require the isolated preview app",
            instrumentation.targetContext.packageName == "com.example.fitnotes.preview.photos")
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
        }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading && !model.busy.value }
        val restored = AtomicBoolean(false)
        val now = System.currentTimeMillis()
        val backup = Backup(
            exercises = listOf(Exercise(1, exerciseName, exerciseName.lowercase())),
            workouts = listOf(
                Workout(10, 0, title = "Past workout with a deliberately long title", historicalDraft = true, finished = true,
                    sessionDate = moveDay(todayDate(), -1)),
                Workout(20, now, title = "Live workout")
            ),
            entries = listOf(WorkoutExercise(1, 10, 1, 0)),
            sets = listOf(WorkoutSet(1, 1, 30.0, 8, 0))
        )
        ui.runOnIdle { model.pendingRestore.value = backup; model.confirmRestore { restored.set(true) } }
        ui.waitUntil(30000) { restored.get() && !model.busy.value && model.pastDrafts.value.size == 1 && model.data.value.active?.workout?.id == 20L }
        ui.mainClock.advanceTimeBy(10000)
    }

    private fun capture(name: String) {
        ui.waitForIdle(); instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(instrumentation.targetContext.filesDir, "$name$suffix.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertCompactTitle(title: String) {
        val bounds = ui.onNodeWithText(title, substring = false).getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("Title occupies $height instead of at most two lines", height <= 144.dp)
    }

    @Test fun longTitlesKeepPastWorkoutActionsAndSetEntryVisible() {
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Continue entry", substring = false))
        ui.onNodeWithText("Continue entry", substring = false).performClick()
        ui.onNodeWithText("Save past workout", substring = false).assertIsDisplayed().assertIsEnabled()
        capture("past-workout-overview")
        assertCompactTitle("Past workout with a deliberately long title")
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText(exerciseName, substring = false))
        ui.onNodeWithText(exerciseName, substring = false).performClick()
        capture("past-workout-logger")
        assertCompactTitle(exerciseName)
        ui.onNodeWithContentDescription("Back to workout").assertIsDisplayed()
        ui.onNodeWithContentDescription("Switch exercise").assertIsDisplayed()
        ui.onNodeWithContentDescription("More actions").assertIsDisplayed()
        ui.onNodeWithText("Save past workout", substring = false).assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithText("Suggested weights", substring = false).assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithText("Add set", substring = false).assertIsDisplayed()

        ui.onNodeWithText(exerciseName, substring = false).performClick()
        ui.onNode(hasText(exerciseName, substring = false) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText("Close", substring = false).performClick()
        ui.onNodeWithText("Suggested weights", substring = false).performClick()
        ui.waitUntil(15000) { model.loadAdviceOpen.value && !model.busy.value }
        ui.onNodeWithText("Close", substring = false).performClick()
        ui.onNodeWithText("Add set", substring = false).performClick()
        ui.onNodeWithTag("edit-weight").performScrollTo().performTextReplacement("32.5")
        ui.onNodeWithTag("edit-reps").performScrollTo().performTextReplacement("7")
        Espresso.closeSoftKeyboard()
        ui.onNode(hasText("Complete set", substring = false) and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().sets().size } == 2 }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Add set", substring = false).assertIsDisplayed()
        ui.onNodeWithText("Save past workout", substring = false).assertIsDisplayed().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.isEmpty() && runBlocking { db.dao().workout(10)!!.isComplete } }
        assertEquals(20L, runBlocking { db.dao().active()!!.id })
        assertNull(runBlocking { db.dao().workout(10)!!.knownStart })
        val sets = runBlocking { db.dao().sets() }
        assertEquals(2, sets.size)
        assertEquals(30.0, sets.first { it.id == 1L }.weightKg, 0.0)
        assertEquals(32.5, sets.first { it.id != 1L }.weightKg, 0.0)
        assertEquals(7, sets.first { it.id != 1L }.reps)
        capture("past-workout-saved")
    }
}
