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

class EmptyWorkoutInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val press = "Synthetic press"
    private val row = "Synthetic row with a deliberately long exercise name"
    private lateinit var planId: String
    @Before fun seed() {
        assumeTrue("Synthetic fixtures require the isolated preview", InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.example.fitnotes.preview.photos")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = System.currentTimeMillis() - 86400000L
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, press, press.lowercase()), Exercise(2, row, row.lowercase())),
                workouts = listOf(Workout(10, start, start + 1800000, "Previous synthetic workout", finished = true)),
                entries = listOf(WorkoutExercise(10, 10, 1, 0)),
                sets = listOf(WorkoutSet(10, 10, 20.0, 6, 0, loadMode = LoadMode.EXTERNAL)))
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        planId = runBlocking {
            val prescription = PlanPrescription(press, listOf(PlanSetTarget(8, 10, PlanWeight("25", "kg"))))
            val spec = PlanSpec("Synthetic saved plan", "", 30, "", listOf(prescription), "d".repeat(64))
            (WorkoutPlanRepository(db).save(PlanPreview(spec, listOf(ResolvedPlanExercise(prescription, 1)))) as PlanSaveResult.Saved).id
        }
        ui.waitUntil(15000) { model.plans.value.isNotEmpty() }
        ui.mainClock.advanceTimeBy(10000)
        ui.onNodeWithTag("train-screen").assertExists()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        i.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val image = i.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(i.targetContext.filesDir, "$name$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun field(label: String): SemanticsNodeInteraction {
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:$label"))
        return ui.onNodeWithTag("field:$label").performScrollTo()
    }
    private fun choose(name: String) {
        ui.onNodeWithTag("exercise-search").performTextReplacement(name)
        Espresso.closeSoftKeyboard()
        ui.onNode(hasText(name, substring = false) and !hasSetTextAction()).performClick()
        ui.onNodeWithText("Add 1 exercise").performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("logger-list").fetchSemanticsNodes().isNotEmpty() && !model.busy.value }
    }
    private fun input(weight: String, reps: String) {
        field("Weight (kg)").performClick().performTextReplacement(weight)
        field("Reps").performClick().performTextReplacement(reps)
    }
    private fun save(text: String = "Save set", twice: Boolean = false) {
        Espresso.closeSoftKeyboard()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText(text))
        val button = ui.onNodeWithText(text).performScrollTo().assertIsDisplayed().assertIsEnabled()
        if (twice) button.performTouchInput { doubleClick() } else button.performClick()
        ui.waitUntil(15000) { !model.busy.value }
    }
    private fun backToTrain() {
        ui.onNodeWithContentDescription("Back to workout").performClick()
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithTag("train-screen").assertExists()
    }
    @Test fun startAddLogEditFinishAndStartAgainWithSuggestionsKeptAsDrafts() {
        val originalPlan = runBlocking { db.planDao().detail(planId)!! }
        capture("empty-train")
        ui.onNodeWithText("Start empty workout").performTouchInput { doubleClick() }
        ui.onNodeWithTag("exercise-search").assertExists()
        val first = runBlocking { db.dao().active()!!.id }
        assertEquals(2, runBlocking { db.dao().workouts().size })
        assertTrue(runBlocking { db.dao().workoutEntries(first).isEmpty() })
        // A row absent from Recent must still be found by searching.
        choose(row)
        input("40", "8"); capture("empty-keyboard")
        save(twice = true)
        ui.waitUntil(15000) { runBlocking { db.dao().sets().size } == 2 }
        ui.onNodeWithContentDescription("Back to workout").performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Add exercise"))
        ui.onNodeWithText("Add exercise").performClick(); choose(press)
        field("Weight (kg)").assertTextContains("20")
        field("Reps").assertTextContains("6")
        assertEquals(2, runBlocking { db.dao().sets().size })
        save(); ui.waitUntil(15000) { runBlocking { db.dao().sets().size } == 3 }
        ui.onNodeWithContentDescription("Back to workout").performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText(row))
        ui.onNodeWithText(row, substring = false).performClick()
        val rowSet = runBlocking { db.dao().sets().single { it.entryId == db.dao().workoutEntries(first).single { e -> e.exerciseId == 2L }.id } }
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("saved-set:set:${rowSet.id}"))
        ui.onNodeWithTag("saved-set:set:${rowSet.id}").performClick()
        input("42.5", "9"); save("Update set")
        ui.waitUntil(15000) { runBlocking { db.dao().set(rowSet.id)?.weightKg } == 42.5 }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("logger-list").assertExists()
        assertEquals(first, runBlocking { db.dao().active()!!.id })
        ui.onNodeWithContentDescription("Back to workout").performClick()
        ui.onNodeWithText("Finish workout").performClick()
        ui.onNodeWithText("Finish", substring = false).performClick()
        ui.waitUntil(15000) { runBlocking { db.dao().workout(first)!!.isComplete } && !model.busy.value && model.completionSummary.value != null }
        ui.onNodeWithText("Done", substring = false).performClick()
        ui.onNodeWithContentDescription("More actions").performClick()
        ui.onNodeWithText("Use as plan").performClick()
        ui.onNodeWithTag("plan-import-list").assertExists()
        assertEquals(2, model.planEdit.value!!.exercises.size)
        assertEquals(1, runBlocking { db.planDao().plans().size })
        ui.onNodeWithText("Cancel", substring = false).performClick()
        ui.mainClock.advanceTimeBy(10000) // Let the completion snackbar leave the bottom actions.
        ui.onNode(hasText("Train") and hasClickAction()).performClick()
        ui.onNodeWithText("Start empty workout").performClick()
        ui.waitUntil(15000) { model.data.value.active != null && !model.busy.value }
        ui.onNodeWithTag("exercise-search").assertExists()
        val second = runBlocking { db.dao().active()!!.id }
        assertNotEquals(first, second)
        ui.onNodeWithContentDescription("Back", substring = false).performClick() // Cancel retains the real session.
        ui.onNodeWithTag("workout-overview").assertExists()
        assertTrue(runBlocking { db.dao().workoutEntries(second).isEmpty() })
        assertEquals(3, runBlocking { db.dao().sets().size })
        assertEquals(originalPlan, runBlocking { db.planDao().detail(planId) })
        assertTrue(runBlocking { db.planDao().runs().isEmpty() })
        capture("empty-second-workout")
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Add exercise"))
        ui.onNodeWithText("Add exercise").performClick()
        for (name in listOf(press, row)) {
            ui.onNodeWithTag("exercise-search").performTextReplacement(name)
            Espresso.closeSoftKeyboard()
            ui.onNode(hasText(name, substring = false) and !hasSetTextAction()).performClick()
        }
        ui.onNodeWithText("Add 2 exercises").assertIsEnabled().performClick()
        try { ui.waitUntil(15000) { model.data.value.active?.entries?.size == 2 && !model.busy.value } }
        catch (error: Throwable) {
            capture("empty-multi-debug")
            throw AssertionError("Expected active $second with two entries; actual=" + runBlocking { db.dao().workouts().toString() + db.dao().entries().toString() }, error)
        }
        ui.onNodeWithTag("workout-overview").assertExists()
        assertEquals(3, runBlocking { db.dao().sets().size })
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText(press))
        ui.onNodeWithText(press, substring = false).performClick()
        input("35", "7"); save()
        ui.waitUntil(15000) { runBlocking { db.dao().sets().size } == 4 }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "empty-workout-restart.json")
            .writeText(runBlocking { BackupCodec.encode(WorkoutRepository(db).backup()) })
    }
    @Test fun activeSessionChoicesCancelResumeFinishAndDiscardWithoutChangingSavedPlans() {
        ui.onNodeWithText("Start empty workout").performClick(); choose(press)
        input("30", "8"); save()
        val first = runBlocking { db.dao().active()!!.id }
        backToTrain()
        val before = runBlocking { WorkoutRepository(db).backup() }
        ui.onNodeWithText("Start empty workout").performClick()
        ui.onNodeWithText("Workout already in progress").assertExists()
        ui.onNodeWithText("Cancel", substring = false).performClick()
        val after = runBlocking { WorkoutRepository(db).backup() }
        assertEquals(before.copy(exportedAt = after.exportedAt), after)
        ui.onNodeWithText("Start empty workout").performClick()
        ui.onNodeWithText("Resume", substring = false).performClick()
        assertEquals(first, runBlocking { db.dao().active()!!.id })
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Synthetic saved plan"))
        ui.onNodeWithText("Synthetic saved plan").performClick()
        ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("start-plan").assertIsEnabled() }.isSuccess }
        ui.onNodeWithTag("start-plan").performClick()
        ui.onNodeWithText("Finish and start").performClick()
        ui.waitUntil(15000) { model.data.value.active?.workout?.id != first && !model.busy.value }
        val planned = runBlocking { db.dao().active()!!.id }
        assertTrue(runBlocking { db.dao().workout(first)!!.isComplete })
        assertEquals(planId, runBlocking { db.planDao().run(planned)!!.planId })
        assertEquals(2, runBlocking { db.dao().sets().size })
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithText("Start empty workout").performClick()
        ui.onNodeWithText("Finish and start").assertIsNotEnabled()
        ui.onNodeWithText("Discard and start").performClick()
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(planned, runBlocking { db.dao().active()!!.id })
        ui.onNodeWithText("Start empty workout").performClick()
        ui.onNodeWithText("Discard and start").performClick()
        capture("empty-discard-confirmation")
        ui.onNodeWithText("Discard", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.active?.workout?.id != planned && !model.busy.value }
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithContentDescription("Back", substring = false).performClick()
        ui.onNodeWithText("Finish workout").assertIsNotEnabled()
        ui.onNodeWithText("Discard workout").performClick()
        ui.onNodeWithText("Discard", substring = false).performClick()
        ui.waitUntil(15000) { model.data.value.active == null && !model.busy.value }
        assertEquals(2, runBlocking { db.dao().sets().size })
        assertNotNull(runBlocking { db.planDao().detail(planId) })
    }
}

class EmptyWorkoutRestartTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun forceStoppedSessionRetainsItsTimestampOrderAndActualSets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires the isolated preview", context.packageName == "com.example.fitnotes.preview.photos")
        lateinit var model: FitNotesViewModel
        ui.activityRule.scenario.onActivity { model = ViewModelProvider(it)[FitNotesViewModel::class.java] }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading }
        val expected = BackupCodec.decode(File(context.filesDir, "empty-workout-restart.json").readText())
        val actual = runBlocking { WorkoutRepository(model.photoRepository.database).backup() }
        assertEquals(expected.workouts, actual.workouts); assertEquals(expected.entries, actual.entries)
        assertEquals(expected.sets, actual.sets); assertEquals(expected.plannedWorkouts, actual.plannedWorkouts)
        ui.onNodeWithText("Resume workout").performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
        assertEquals(expected.workouts.single { !it.isComplete }.id, model.data.value.active!!.workout.id)
    }
}
