package com.example.fitnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.moveDay
import com.example.fitnotes.ui.prettyDay
import com.example.fitnotes.ui.todayDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PastWorkoutNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel

    private fun logForViewedDay() {
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasTestTag("log-workout-for-day"))
        ui.onNodeWithTag("log-workout-for-day").performClick()
    }

    @Before fun prepare() {
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
        }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading && !model.busy.value }
        val now = System.currentTimeMillis()
        val restored = AtomicBoolean(false)
        ui.runOnIdle {
            model.pendingRestore.value = Backup(
                exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = listOf(
                    Workout(10, now - 3 * 86400000L, now - 3 * 86400000L + 3600000L, "Earlier workout", sessionDate = moveDay(todayDate(), -3)),
                    Workout(20, now, title = "Active workout")
                ),
                entries = listOf(WorkoutExercise(1, 10, 1, 0)),
                sets = listOf(WorkoutSet(1, 1, 30.0, 8, 0))
            )
            model.confirmRestore { restored.set(true) }
        }
        ui.waitUntil(30000) { restored.get() && !model.busy.value && model.data.value.active?.workout?.id == 20L }
    }

    @Test fun emptyPreviousDayStaysOpenAndCreatesADraftOnThatDate() {
        val selectedDay = moveDay(todayDate(), -2)
        ui.onNodeWithText("Resume workout", substring = false).performClick()
        repeat(2) { ui.onNodeWithContentDescription("Previous day").performClick() }
        ui.onNodeWithTag("workout-overview").assertExists()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("No workouts on this day."))
        ui.onNodeWithText("No workouts on this day.").assertIsDisplayed()
        assertTrue(model.pastDrafts.value.isEmpty())
        assertEquals(2, model.data.value.workouts.size)

        logForViewedDay()
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(selectedDay, "EEE d MMM yyyy"))
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(selectedDay, "EEE d MMM yyyy"))
        ui.onNodeWithText("Enter performed sets", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.size == 1 }
        val draft = model.pastDrafts.value.single()
        assertEquals(selectedDay, draft.workout.sessionDate)
        assertNull(draft.workout.knownStart)
        assertTrue(draft.entries.isEmpty())
        assertEquals(20L, model.data.value.active!!.workout.id)
        assertEquals(1, runBlocking { model.photoRepository.database.dao().sets().size })
    }

    @Test fun existingHistoryDayAllowsAnAdditionalSessionAndCancelCreatesNothing() {
        ui.onNode(hasText("History") and hasClickAction()).performClick()
        ui.onNodeWithText("◆ Earlier workout", substring = false).performClick()
        logForViewedDay()
        val selectedDay = moveDay(todayDate(), -3)
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(selectedDay, "EEE d MMM yyyy"))
        ui.onNodeWithText("Cancel", substring = false).performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
        assertTrue(model.pastDrafts.value.isEmpty())

        logForViewedDay()
        ui.onNodeWithText("Enter performed sets", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.size == 1 }
        assertEquals(selectedDay, model.pastDrafts.value.single().workout.sessionDate)
        assertEquals(20L, model.data.value.active!!.workout.id)
        assertEquals("Earlier workout", model.data.value.workouts.first { it.workout.id == 10L }.workout.title)
    }
}
