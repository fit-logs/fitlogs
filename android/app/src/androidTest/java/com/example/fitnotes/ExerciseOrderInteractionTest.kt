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

class ExerciseOrderInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val names = listOf("Synthetic shoulder press", "Synthetic lateral raise", "Synthetic curl", "Synthetic cable crunch with a deliberately long exercise name")
    private var workout = 0L
    private val addedName = "Synthetic hammer curl"
    @Before fun seed() {
        assumeTrue("Synthetic fixtures require the isolated preview", InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.example.fitnotes.preview.photos")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = (names + addedName).mapIndexed { index, name -> Exercise(index + 1L, name, name.lowercase()) }, workouts = emptyList(), entries = emptyList(), sets = emptyList())
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        workout = runBlocking {
            val targets = listOf(PlanSetTarget(8, 10, PlanWeight("20.5", "kg"), 90), PlanSetTarget(8, 10))
            val exercises = names.mapIndexed { index, name -> ResolvedPlanExercise(PlanPrescription(name, targets, "Exercise guidance"), index + 1L) }
            val plans = WorkoutPlanRepository(db)
            val id = (plans.save(PlanPreview(PlanSpec("Synthetic shoulders and core", "", 45, "Session notes", exercises.map { it.prescription }, "b".repeat(64)), exercises)) as PlanSaveResult.Saved).id
            val active = plans.start(id)
            val first = db.planDao().runTargets().first()
            TrainingRepository(db).complete(first.id, "21", "9", "", LoadMode.EXTERNAL)
            model.restController.afterCompletion(first.id)
            active
        }
        ui.waitUntil(30000) { model.data.value.active?.workout?.id == workout && model.data.value.active!!.entries.first().sets.size == 1 && model.planRuns.value.isNotEmpty() }
        ui.mainClock.advanceTimeBy(10000)
        ui.onNodeWithText("Resume workout").performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation(); i.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = i.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(i.targetContext.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun openOrder() {
        ui.onNodeWithContentDescription("More actions").performClick()
        ui.onNodeWithText("Reorder exercises").performClick()
        ui.onNodeWithTag("exercise-order-list").assertExists()
    }
    @Test fun cancelSaveRecreateAndLogRetainTheRightTargetsAndActuals() {
        val entries = runBlocking { db.dao().workoutEntries(workout) }
        val before = runBlocking { WorkoutRepository(db).backup() }
        val timer = runBlocking { db.trainingDao().timer()!! }
        capture("exercise-order-before")
        openOrder()
        ui.onNodeWithTag("exercise-up:${entries.first().id}").assertIsNotEnabled()
        ui.onNodeWithTag("exercise-order-list").performScrollToNode(hasTestTag("exercise-down:${entries.last().id}"))
        ui.onNodeWithTag("exercise-down:${entries.last().id}").assertIsNotEnabled()
        ui.onNodeWithTag("exercise-up:${entries.last().id}").performClick()
        ui.onNodeWithText("Save order").assertIsEnabled()
        capture("exercise-order-editor")
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(entries, runBlocking { db.dao().workoutEntries(workout) })
        openOrder()
        ui.onNodeWithTag("exercise-order-list").performScrollToNode(hasTestTag("exercise-up:${entries[1].id}"))
        ui.onNodeWithTag("exercise-up:${entries[1].id}").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("exercise-order-list").assertExists()
        ui.onNodeWithText("Save order").assertIsEnabled().performClick()
        val expected = listOf(entries[1].id, entries[0].id, entries[2].id, entries[3].id)
        ui.waitUntil(15000) { runBlocking { db.dao().workoutEntries(workout).map { it.id } } == expected && !model.busy.value }
        assertEquals(before.sets, runBlocking { db.dao().sets() })
        assertEquals(before.runTargets, runBlocking { db.planDao().runTargets() })
        assertEquals(before.workouts, runBlocking { db.dao().workouts() })
        assertEquals(before.plannedExercises, runBlocking { WorkoutRepository(db).backup().plannedExercises })
        assertEquals(timer.token, runBlocking { db.trainingDao().timer()!!.token })
        capture("exercise-order-after")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("workout-overview").assertExists()
        assertEquals(expected, runBlocking { db.dao().workoutEntries(workout).map { it.id } })
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText(names[1]))
        ui.onNodeWithText(names[1], substring = false).performClick()
        ui.onNodeWithTag("logger-list").assertExists()
        val pending = runBlocking { db.planDao().runTargets().first { it.entryId == entries[1].id } }
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("run-target:${pending.id}"))
        capture("exercise-order-logger")
        assertEquals(1, runBlocking { db.dao().sets().size })
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Save set"))
        ui.waitUntil(10000) { model.completionReady.value && !model.busy.value }
        ui.onNodeWithText("Save set").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(15000) { runBlocking { db.dao().sets().size } == 2 && !model.busy.value }
        val actual = runBlocking { db.dao().sets().single { it.targetId == pending.id } }
        assertEquals(entries[1].id, actual.entryId)
        assertEquals(20.5, actual.weightKg, 0.0)
        assertEquals(before.sets.single(), runBlocking { db.dao().sets().single { it.id == before.sets.single().id } })
        assertEquals(before.runTargets, runBlocking { db.planDao().runTargets() })
    }
}
