package com.example.fitnotes

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.moveDay
import com.example.fitnotes.ui.prettyDay
import com.example.fitnotes.ui.todayDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(androidx.room.ExperimentalRoomApi::class)
class PastWorkoutNavigationTest {
    private val activityModels = mutableSetOf<ViewModelStore>()
    // Robolectric's default bindService binder is not a Room service. Supply the
    // real service before MainActivity opens its production Room database.
    @get:Rule(order = 0) val appServices: ExternalResource = object : ExternalResource() {
        private var controller: ServiceController<MultiInstanceInvalidationService>? = null
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setExecutor(androidx.work.impl.utils.SynchronousExecutor()).build())
            val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
            controller = service
            shadowOf(context).setComponentNameAndServiceForBindService(
                ComponentName(context, MultiInstanceInvalidationService::class.java), service.get().onBind(Intent())
            )
        }
        override fun after() {
            // Explicitly release the store retained by Robolectric recreation.
            activityModels.forEach { it.clear() }
            controller?.destroy()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel

    private fun awaitModel(condition: () -> Boolean) {
        ui.waitUntil(30000) {
            // Room updates also use Android's Looper, outside Compose's clock.
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun logForViewedDay() {
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasTestTag("log-workout-for-day"))
        ui.onNodeWithTag("log-workout-for-day").performClick()
    }

    @Before fun prepare() {
        ui.activityRule.scenario.onActivity {
            activityModels += it.viewModelStore
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
        }
        awaitModel { !model.data.value.loading && !model.data.value.imported.loading && !model.busy.value }
        assertNull(model.data.value.storageError)
        val now = System.currentTimeMillis()
        runBlocking { WorkoutRepository(model.photoRepository.database).restore(Backup(
            exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
            workouts = listOf(
                Workout(10, now - 3 * 86400000L, now - 3 * 86400000L + 3600000L, "Earlier workout", sessionDate = moveDay(todayDate(), -3)),
                Workout(20, now, title = "Active workout")
            ),
            entries = listOf(WorkoutExercise(1, 10, 1, 0)),
            sets = listOf(WorkoutSet(1, 1, 30.0, 8, 0))
        )) }
        awaitModel { model.data.value.active?.workout?.id == 20L && model.data.value.workouts.size == 2 && model.pastDrafts.value.isEmpty() }
        assertEquals(20L, model.data.value.active?.workout?.id)
        assertTrue("Fresh fixture drafts: ${model.pastDrafts.value.map { it.workout }}", model.pastDrafts.value.isEmpty())
    }

    @Test fun emptyPreviousDayStaysOpenAndCreatesADraftOnThatDate() {
        val selectedDay = moveDay(todayDate(), -2)
        ui.onNodeWithText("Resume workout", substring = false).performClick()
        repeat(2) { ui.onNodeWithContentDescription("Previous day").performClick() }
        ui.onNodeWithTag("workout-overview").assertExists()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("No workouts on this day."))
        ui.onNodeWithText("No workouts on this day.").assertIsDisplayed()
        assertTrue("Unexpected drafts: ${model.pastDrafts.value.map { it.workout }}", model.pastDrafts.value.isEmpty())
        assertEquals(2, model.data.value.workouts.size)

        logForViewedDay()
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(selectedDay, "EEE d MMM yyyy"), substring = true)
        ui.activityRule.scenario.recreate()
        ui.activityRule.scenario.onActivity { activityModels += it.viewModelStore; model = ViewModelProvider(it)[FitNotesViewModel::class.java] }
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(selectedDay, "EEE d MMM yyyy"), substring = true)
        ui.onNodeWithText("Enter performed sets", substring = false).performClick()
        awaitModel { !model.busy.value && model.pastDrafts.value.size == 1 }
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
        ui.onNodeWithTag("past-workout-date").assertTextContains(prettyDay(selectedDay, "EEE d MMM yyyy"), substring = true)
        ui.onNodeWithText("Cancel", substring = false).performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
        assertTrue("Cancel changed drafts: ${model.pastDrafts.value.map { it.workout }}", model.pastDrafts.value.isEmpty())

        logForViewedDay()
        ui.onNodeWithText("Enter performed sets", substring = false).performClick()
        awaitModel { !model.busy.value && model.pastDrafts.value.size == 1 }
        assertEquals(selectedDay, model.pastDrafts.value.single().workout.sessionDate)
        assertEquals(20L, model.data.value.active!!.workout.id)
        assertEquals("Earlier workout", model.data.value.workouts.first { it.workout.id == 10L }.workout.title)
    }
}
