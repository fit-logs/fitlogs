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

class PlanLifecycleInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val block = """WORKOUT_PLAN_V2
{"title":"Synthetic editable plan","warmUp":"Five minutes of gentle movement. Warm-up sets are suggestions, not completed training.","note":"Use controlled movement.","exercises":[
{"name":"Synthetic press","note":"Keep shoulders steady and leave two reps in reserve.","sets":[
{"repsMin":8,"repsMax":10,"weight":"40.5","unit":"kg","restSeconds":90},
{"repsMin":6,"repsMax":8,"weight":"42.75","unit":"kg","restSeconds":120}]}]}
END_WORKOUT_PLAN"""
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Synthetic fixtures require the isolated preview", context.packageName == "com.example.fitnotes.preview.activities")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")), workouts = emptyList(), entries = emptyList(), sets = emptyList())
            model.confirmRestore { done.set(true) }
        }
        ui.waitUntil(30000) { done.get() && !model.busy.value && !model.data.value.imported.loading && model.handoffPreferences.value.value.lastTargetDate.isBlank() }
        waitFor("Backup restored."); ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
    }
    private fun waitFor(text: String) { ui.waitUntil(30000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(700)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun pasteAndPreview() {
        ui.onAllNodes(hasText("Paste plan") and hasClickAction()).onLast().performClick()
        waitFor("Preview plan")
        ui.onNode(hasSetTextAction() and hasText("Plan reply")).performScrollTo().performTextInput(block)
        ui.onNodeWithText("Preview plan").performClick(); waitFor("Edit planned workout")
    }
    private fun scroll(matcher: SemanticsMatcher) { ui.onNodeWithTag("plan-import-list").performScrollToNode(matcher) }
    @Test fun saveCloseReopenEditAndStartOnlyLogsPerformedSets() {
        pasteAndPreview()
        ui.onNodeWithTag("editor-start-plan").assertIsNotEnabled()
        val exercise = model.planEdit.value!!.exercises.single()
        scroll(hasTestTag("target-row:${exercise.targets.first().key}"))
        capture("after-import-editable-set-rows")
        ui.onNodeWithTag("save-plan").performTouchInput { doubleClick() }
        waitFor("Saved plan")
        val saved = runBlocking { db.planDao().plans().single() }
        assertEquals("", saved.scheduledDate); assertEquals(0, saved.durationMinutes)
        assertTrue(runBlocking { db.dao().workouts().isEmpty() }); assertTrue(runBlocking { db.dao().sets().isEmpty() }); assertNull(runBlocking { db.dao().active() })
        capture("after-saved-plan-no-training")
        ui.onNodeWithText("Close", substring = false).performClick()
        ui.activityRule.scenario.recreate()
        ui.onAllNodesWithText("Plans (1)").onFirst().performClick(); waitFor("Planned workouts")
        ui.onNodeWithText("Synthetic editable plan").performClick(); waitFor("Saved plan")
        ui.onNodeWithText("Edit plan").performClick(); waitFor("Edit planned workout")
        val set = model.planEdit.value!!.exercises.single().targets.first()
        scroll(hasTestTag("target-row:${set.key}"))
        ui.onNodeWithTag("target-row:${set.key}").onChildren().fetchSemanticsNodes() // material fields remain editable
        ui.onAllNodes(hasSetTextAction() and hasText("Weight + unit (optional)")).onFirst().performTextReplacement("41.25 kg")
        ui.onNodeWithTag("editor-start-plan").assertIsNotEnabled()
        Espresso.closeSoftKeyboard(); ui.onNodeWithTag("save-plan").performClick(); waitFor("Saved plan")
        assertTrue(runBlocking { db.dao().workouts().isEmpty() }); assertTrue(runBlocking { db.dao().sets().isEmpty() })
        ui.waitUntil(10000) { ui.onAllNodesWithTag("start-plan").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        val before = System.currentTimeMillis()
        ui.onNodeWithTag("start-plan").performTouchInput { doubleClick() }
        ui.waitUntil(15000) { runBlocking { db.dao().active() } != null }
        assertEquals(1, runBlocking { db.dao().workouts().size }); assertTrue(runBlocking { db.dao().active()!!.startedAt } >= before)
        assertTrue(runBlocking { db.dao().sets().isEmpty() })
        waitFor("Workout started."); ui.waitUntil(10000) { ui.onAllNodesWithText("Workout started.").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("Synthetic press").performScrollTo().performClick(); waitFor("Save set")
        ui.onNodeWithText("Workout suggestions · not completed").performScrollTo()
        capture("after-active-logger-suggestions")
        ui.onNodeWithTag("field:Weight (kg)").performScrollTo().assertTextContains("41.25")
        ui.onNodeWithTag("field:Reps").performScrollTo().assertTextContains("8")
        ui.onNodeWithTag("field:Weight (kg)").performTextReplacement("43.5")
        ui.onNodeWithTag("field:Reps").performTextReplacement("9")
        ui.onNodeWithText("Save set").performScrollTo().performClick()
        ui.waitUntil(15000) { runBlocking { db.dao().sets().size } == 1 }
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Recorded sets · 1"));ui.onNodeWithText("Recorded sets · 1").performScrollTo(); capture("after-actual-set-logged")
        assertEquals(43.5 * 9, runBlocking { db.dao().sets().sumOf { it.weightKg * it.reps } }, 0.0)
        assertEquals("41.25", runBlocking { db.planDao().detail(saved.id)!!.ordered.single().sets.first().weightValue })
    }
    @Test fun strengthPlanDoesNotReuseCardioInputModeFromPreviousMixedHistory() {
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val now = System.currentTimeMillis()
        ui.activityRule.scenario.onActivity {
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = listOf(Workout(10, now - 172800000, now - 172000000), Workout(11, now - 86400000, now - 86000000)),
                entries = listOf(WorkoutExercise(20, 10, 1, 0), WorkoutExercise(21, 11, 1, 0)),
                sets = listOf(WorkoutSet(30, 20, 20.0, 8, 0), WorkoutSet(31, 21, 0.0, 0, 0, distance = 1.0, distanceUnit = "km", durationSeconds = 600.0)))
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(15000) { ready.get() && !model.busy.value && model.data.value.workouts.size == 2 }
        waitFor("Backup restored."); ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
        pasteAndPreview(); ui.onNodeWithTag("save-plan").performClick(); waitFor("Saved plan")
        assertEquals(2, runBlocking { db.dao().sets().size })
        ui.onNodeWithText("Edit plan").performClick(); waitFor("Edit planned workout")
        ui.onNodeWithTag("editor-start-plan").assertIsEnabled().performClick(); waitFor("Workout started.")
        assertNull(model.planEdit.value)
        ui.waitUntil(10000) { ui.onAllNodesWithText("Workout started.").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("Synthetic press").performScrollTo().performClick(); waitFor("Save set")
        ui.onNodeWithTag("field:Weight (kg)").performScrollTo().assertTextContains("40.5")
        ui.onNodeWithTag("field:Reps").performScrollTo().assertTextContains("8")
        ui.onNodeWithTag("field:Distance (km)").assertDoesNotExist()
        assertEquals(2, runBlocking { db.dao().sets().size }); assertEquals(3, runBlocking { db.dao().workouts().size })
    }
    @Test fun emptyLegacyImportCanBeReviewedConvertedAndReopenedWithoutGuessing() {
        val raw = SESSION_PLAN_PREFIX + "Warm up gradually. DB press: maybe 3 sets? Weight unclear. Choose based on recovery."
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
                workouts = listOf(Workout(20, System.currentTimeMillis(), title = "Synthetic mistaken import", notes = raw)),
                entries = listOf(WorkoutExercise(30, 20, 1, 0)), sets = emptyList())
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(15000) { ready.get() && model.data.value.workouts.size == 1 }
        waitFor("Convert empty import to plan")
        capture("before-empty-legacy-import")
        ui.onAllNodesWithText("Convert empty import to plan").onLast().performScrollTo().performClick(); waitFor("Edit planned workout")
        assertEquals(raw, model.planEdit.value!!.legacyGuidance)
        assertTrue(model.planEdit.value!!.exercises.single().targets.isEmpty())
        assertNotNull(runBlocking { db.dao().workout(20) })
        capture("after-legacy-conversion-preview")
        ui.onNodeWithTag("save-plan").performClick(); waitFor("Saved plan")
        assertNull(runBlocking { db.dao().workout(20) }); assertNull(runBlocking { db.dao().active() }); assertTrue(runBlocking { db.dao().sets().isEmpty() })
        assertEquals(raw, runBlocking { db.planDao().plans().single().legacyGuidance })
        ui.activityRule.scenario.recreate(); waitFor("Saved plan")
        assertTrue(runBlocking { db.dao().workouts().isEmpty() })
    }
}
