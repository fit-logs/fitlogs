package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.moveDay
import com.example.fitnotes.ui.prettyDay
import com.example.fitnotes.ui.todayDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WorkoutEntryInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val db get() = model.photoRepository.database

    @Before fun prepare() {
        assumeTrue("Synthetic fixtures require the isolated preview app",
            instrumentation.targetContext.packageName == "com.example.fitnotes.preview.photos")
        instrumentation.targetContext.getSharedPreferences("history-filter",0).edit().clear().commit()
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
        }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading && !model.busy.value }
        val restored = AtomicBoolean(false)
        val start = System.currentTimeMillis() - 2 * 86400000L
        ui.runOnIdle {
            model.pendingRestore.value = Backup(
                exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = listOf(Workout(10, start, start + 1800000, "Earlier workout", finished = true)),
                entries = listOf(WorkoutExercise(1, 10, 1, 0)),
                sets = listOf(WorkoutSet(1, 1, 30.0, 8, 0))
            )
            model.confirmRestore { restored.set(true) }
        }
        ui.waitUntil(30000) { restored.get() && !model.busy.value && model.data.value.workouts.size == 1 && model.pastDrafts.value.isEmpty() }
        ui.mainClock.advanceTimeBy(10000)
    }

    private fun capture(name: String) {
        ui.waitForIdle(); instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(instrumentation.targetContext.filesDir, "$name$suffix.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun openStart() {
        ui.onNodeWithTag("train-screen").performScrollToNode(hasTestTag("start-workout"))
        ui.onNodeWithTag("start-workout").performClick()
    }

    private fun selectDay(day: String, initialDay: String) {
        ui.onNodeWithTag("past-workout-date").performScrollTo().performClick()
        val tomorrow = moveDay(todayDate(), 1)
        if(initialDay == todayDate() && tomorrow.take(7) == initialDay.take(7)) {
            ui.onNode(hasText(prettyDay(tomorrow, "EEEE, MMMM d, yyyy"), substring = true) and hasClickAction()).assertIsNotEnabled()
        }
        if(day.take(7) != initialDay.take(7)) {
            ui.onNodeWithContentDescription(if(day < initialDay) "Change to previous month" else "Change to next month").performClick()
        }
        ui.onNode(hasText(prettyDay(day, "EEEE, MMMM d, yyyy"), substring = true) and hasClickAction()).performClick()
        ui.onNodeWithText("Choose", substring = false).performClick()
    }

    @Test fun trainChoosesTodayBeforeStartingAndCancelCreatesNothing() {
        ui.onNodeWithText("Log past workout", substring = false).assertDoesNotExist()
        ui.onNodeWithTag("train-screen").performScrollToNode(hasTestTag("start-workout"))
        capture("workout-entry-train")
        openStart()
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(todayDate(), "EEE d MMM yyyy"), substring = true)
        ui.onNodeWithTag("confirm-workout-setup").assertTextContains("Start now")
        assertEquals(1, runBlocking { db.dao().workouts().size })
        assertNull(runBlocking { db.dao().active() })
        capture("workout-entry-today")
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(1, runBlocking { db.dao().workouts().size })
        openStart()
        ui.onNodeWithTag("confirm-workout-setup").performTouchInput { doubleClick() }
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active != null }
        val active = runBlocking { db.dao().active()!! }
        assertNotNull(active.knownStart)
        assertFalse(active.historicalDraft)
        assertEquals(2, runBlocking { db.dao().workouts().size })
        ui.onNodeWithTag("exercise-search").assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("exercise-search").assertExists()
        assertEquals(active.id, runBlocking { db.dao().active()!!.id })
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithTag("resume-workout").assertExists().performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
        assertEquals(2, runBlocking { db.dao().workouts().size })
    }

    @Test fun choosingAnEarlierDayCopiesOnlyExercisesAndKeepsTheLiveSession() {
        ui.runOnIdle { model.startEmpty {} }
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active != null }
        val active = runBlocking { db.dao().active()!! }
        openStart()
        val yesterday = moveDay(todayDate(), -1)
        selectDay(yesterday, todayDate())
        ui.onNodeWithTag("confirm-workout-setup").assertTextContains("Continue")
        assertTrue(model.pastDrafts.value.isEmpty())
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(yesterday, "EEE d MMM yyyy"), substring = true)
        ui.onNode(hasText("Earlier workout", substring = true) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        capture("workout-entry-past")
        ui.onNodeWithTag("confirm-workout-setup").performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.size == 1 }
        val draft = model.pastDrafts.value.single()
        assertEquals(yesterday, draft.workout.sessionDate)
        assertNull(draft.workout.knownStart)
        assertEquals(1, draft.entries.size)
        assertTrue(draft.entries.single().sets.isEmpty())
        assertEquals(active, runBlocking { db.dao().active()!! })
        assertEquals(listOf(WorkoutSet(1, 1, 30.0, 8, 0)), runBlocking { db.dao().sets() })
        ui.onNodeWithTag("workout-overview").assertExists()
    }

    @Test fun historyHasNoDuplicateEntryAndTrainCanLogACompletedSessionEarlierToday() {
        ui.onNode(hasText("History", substring = false) and hasClickAction()).performClick()
        ui.onNodeWithTag("history-log-past-workout").assertDoesNotExist()
        ui.onNodeWithText("Log past workout", substring = false).assertDoesNotExist()
        capture("workout-entry-history")
        ui.onNode(hasText("Train", substring = false) and hasClickAction()).performClick()
        openStart()
        ui.onNodeWithTag("workout-entry-completed").performClick()
        ui.onNodeWithTag("confirm-workout-setup").assertTextContains("Continue")
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(1, runBlocking { db.dao().workouts().size })
        openStart()
        ui.onNodeWithTag("workout-entry-completed").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("workout-entry-completed").assertIsSelected()
        capture("workout-entry-done")
        ui.onNodeWithTag("confirm-workout-setup").assertTextContains("Continue").performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.size == 1 }
        assertEquals(todayDate(), model.pastDrafts.value.single().workout.sessionDate)
        assertNull(model.pastDrafts.value.single().workout.knownStart)
        assertNull(runBlocking { db.dao().active() })
        assertEquals(1, runBlocking { db.dao().sets().size })
        ui.onNodeWithTag("exercise-search").assertExists()
    }

    @Test fun historyComposesVisibleRowsAndOpensAnOlderSession() {
        val restored = AtomicBoolean(false)
        ui.runOnIdle {
            model.pendingRestore.value = Backup(
                exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = (1L..120L).map { id -> Workout(id, 0, title="Session $id", sessionDate=moveDay(todayDate(), 1-id.toInt()), finished=true) },
                entries = (1L..120L).map { id -> WorkoutExercise(id, id, 1, 0) },
                sets = (1L..120L).map { id -> WorkoutSet(id, id, 30.0, 8, 0) }
            )
            model.confirmRestore { restored.set(true) }
        }
        ui.waitUntil(30000) { restored.get() && !model.busy.value && model.notebook.value.historyRows.size == 120 }
        ui.mainClock.advanceTimeBy(10000)
        ui.onNode(hasText("History", substring=false) and hasClickAction()).performClick()
        ui.onNodeWithTag("history-session:local:1").assertIsDisplayed()
        ui.onNodeWithTag("history-session:local:120").assertDoesNotExist()
        val visible = ui.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("history-list"))).fetchSemanticsNodes().size
        assertTrue("Expected only visible history rows to be composed, found $visible", visible in 1 until 120)
        ui.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-session:local:120"))
        ui.onNodeWithTag("history-session:local:120").assertIsDisplayed().performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
        ui.onNodeWithText("Session 120", substring=false).assertIsDisplayed()
        assertEquals(120, runBlocking { db.dao().sets().size })
    }
}
