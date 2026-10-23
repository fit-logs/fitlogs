package com.example.fitnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.dayMillis
import com.example.fitnotes.ui.todayDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

// Every row/image is synthetic. The package guard runs before fixture writes.
class HandoffInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val simple = """WORKOUT_PLAN_V2
{"title":"Synthetic saved plan","durationMinutes":45,"note":"Synthetic session guidance.","exercises":[
{"name":"Synthetic press","sets":[{"repsMin":8,"repsMax":10,"weight":"100.25","unit":"lb"},{"repsMin":8,"repsMax":10,"weight":"100.25","unit":"lb"},{"repsMin":8,"repsMax":10,"weight":"100.25","unit":"lb"}]}]}
END_WORKOUT_PLAN"""
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Run fixtures only in the isolated preview", context.packageName in setOf("com.example.fitnotes.preview.activities", "com.example.fitnotes.preview.photos"))
        val restored = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = dayMillis(todayDate()) + 3600000
        val source = HealthSession(healthKey("org.synthetic", "source"), 10, "source", "org.synthetic", "Synthetic watch", null,
            1, start, start, start, start + 3600000, null, null, "strength", 70, "Synthetic source", "", null, "", "Synthetic samples", "No synthetic distance")
        val samples = listOf(HealthSample(sampleKey(source.key, "org.synthetic", "hr", start + 1000), source.key, "hr", "org.synthetic", start + 1000, 123))
        ui.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press"), Exercise(2, "Synthetic shoulder press", "synthetic shoulder press")),
                workouts = listOf(Workout(10, start, start + 3600000, notes = "Synthetic completed workout"),
                    Workout(11, start + 7200000, start + 9000000, activityType = "climbing", effort = "Okay", notes = "Synthetic climbing")),
                entries = listOf(WorkoutExercise(20, 10, 1, 0)), sets = listOf(WorkoutSet(30, 20, 50.0, 8, 0)),
                imports = listOf(HistoryImport("fixture", "Synthetic fixture", "synthetic.fixture", start, "kg", 0, "", "{}")),
                bodyMeasurements = listOf(BodyMeasurement("body", "fixture", "body", todayDate(), "", "Synthetic body weight", 70.0, "kg", "kg", "", "{}")),
                healthSessions = listOf(source), healthSamples = samples)
            model.confirmRestore { restored.set(true) }
        }
        ui.waitUntil(30000) { restored.get() && !model.busy.value && !model.data.value.imported.loading && model.data.value.workouts.size == 2 && model.handoffPreferences.value.value.lastTargetDate.isBlank() }
        waitFor("Backup restored.")
        ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
        runBlocking { db.photoDao().all().forEach { model.photoRepository.delete(it.id) } }
        val image = File(context.cacheDir, "synthetic-handoff-image.jpg")
        Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 130, 200)) }.let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        }
        runBlocking {
            val photo = model.photoRepository.prepare(Uri.fromFile(image))
            model.photoRepository.save(photo, todayDate(), "Front", "Synthetic colour fixture")
        }
        ui.waitUntil(15000) { model.photos.value.size == 1 }
    }
    private fun waitFor(text: String) { ui.waitUntil(30000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(600)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun paste(text: String) {
        if (ui.onAllNodesWithTag("train-screen").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithTag("train-screen").performScrollToNode(hasText("My templates", substring = false))
            ui.onNodeWithText("My templates", substring = false).performClick()
            ui.onNodeWithText("Import template", substring = false).performClick()
        } else ui.onAllNodes(hasText("Paste plan") and hasClickAction()).onLast().performClick()
        waitFor("Preview plan")
        val clipboard = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic plan", text)) }
        assertEquals("", model.planImportText.value)
        ui.onNodeWithText("Paste", substring = false).performScrollTo().performClick()
        ui.onNodeWithText("Preview plan").performClick()
        waitFor("Edit planned workout")
        // Let Android's transient clipboard preview disappear before screenshots/actions.
        android.os.SystemClock.sleep(5000)
    }
    @Test fun preparePromptCopyOptionalDataAndAndroidShareSheet() {
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("My templates", substring = false))
        ui.onNodeWithText("My templates", substring = false).performClick()
        ui.onNodeWithText("Ask ChatGPT").performScrollTo().performClick()
        waitFor("History: 30 days ▾")
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Available minutes (optional)"))
        ui.onNode(hasSetTextAction() and hasText("Available minutes (optional)")).performScrollTo().performTextInput("45")
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Goals"))
        ui.onNode(hasSetTextAction() and hasText("Goals")).performScrollTo().performTextInput("Synthetic goal")
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Equipment"))
        ui.onNode(hasSetTextAction() and hasText("Equipment")).performScrollTo().performTextInput("Synthetic equipment")
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Exercise constraints"))
        ui.onNode(hasSetTextAction() and hasText("Exercise constraints")).performScrollTo().performTextInput("Synthetic constraint")
        Espresso.closeSoftKeyboard()
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("History: 30 days ▾"))
        ui.onNodeWithText("History: 30 days ▾").performScrollTo()
        capture("ask-preparation")
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Select body measurements"))
        ui.onNodeWithText("Select body measurements").performScrollTo().performClick()
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Synthetic body weight (kg)"))
        ui.onNodeWithText("Synthetic body weight (kg)").performScrollTo().performClick()
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Select photos"))
        ui.onNodeWithText("Select photos").performScrollTo().performClick()
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("${todayDate()} · Front"))
        ui.onNodeWithText("${todayDate()} · Front").performScrollTo().performClick()
        ui.onNodeWithTag("handoff-list").performScrollToNode(hasText("Include raw heart-rate samples"))
        ui.onNodeWithText("Include raw heart-rate samples").performScrollTo().performClick()
        capture("ask-data-selection")
        ui.onNodeWithText("Preview prompt").performClick()
        waitFor("Review prompt")
        val prepared = model.preparedHandoff.value!!
        assertTrue(prepared.fullText.contains("50 kg × 8 reps")); assertTrue(prepared.fullText.contains("Synthetic climbing"))
        assertTrue(prepared.fullText.contains("Synthetic body weight")); assertEquals(1, prepared.sampleCount); assertEquals(1, prepared.photos.size)
        capture("ask-preview")
        ui.onNodeWithText("Copy prompt").performClick()
        val clipboard = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.runOnIdle { assertEquals(prepared.fullText, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        ui.onNodeWithText("Share", substring = false).performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitUntil(15000) { automation.rootInActiveWindow?.packageName?.toString() in setOf("android", "com.android.intentresolver") }
        capture("android-share-sheet")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        assertEquals(2, runBlocking { db.dao().workouts().size }); assertEquals(1, runBlocking { db.dao().sets().size })
        assertEquals("Synthetic goal", runBlocking { db.planDao().preferences()!!.goals })
    }
    @Test fun exactAndUnknownMatchesSavePlanThenLogActualResultsSeparately() {
        val block = "A short explanation.\n```text\n" + simple.replace("]}\nEND_WORKOUT_PLAN", """,{"name":"DB press","sets":[{"repsMin":12,"repsMax":12,"unit":"bodyweight"},{"repsMin":12,"repsMax":12,"unit":"bodyweight"}]}]}
END_WORKOUT_PLAN""") + "\n```"
        paste(block)
        ui.onNodeWithText("Save plan").assertIsNotEnabled()
        val key = model.planEdit.value!!.exercises.last().key
        ui.onNodeWithTag("plan-import-list").performScrollToNode(hasTestTag("plan-row:$key"))
        capture("plan-match-unresolved")
        ui.onAllNodesWithText("Resolve exercise").onLast().performClick()
        waitFor("Use for strength plan")
        ui.onNodeWithText("Synthetic shoulder press").performScrollTo().performClick()
        ui.onNodeWithText("Use for strength plan").performClick()
        ui.onNodeWithText("Matched: Synthetic shoulder press").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { db.planDao().plans().isEmpty() })
        capture("plan-match-resolved")
        ui.activityRule.scenario.recreate()
        waitFor("Edit planned workout")
        ui.onNodeWithText("Save plan").performClick()
        waitFor("Saved template")
        ui.waitUntil(15000) { runBlocking { db.planDao().plans().size } == 1 }
        assertEquals(2, runBlocking { db.dao().workouts().size }); assertEquals(1, runBlocking { db.dao().sets().size })
        ui.onNodeWithText("Set 1: 8-10 reps · 100.25 lb").performScrollTo().assertIsDisplayed()
        capture("saved-plan-targets")
        val saved = runBlocking { db.planDao().plans().single() }
        assertEquals("", saved.scheduledDate)
        ui.activityRule.scenario.recreate()
        waitFor("Saved template")
        ui.waitUntil(10000) { ui.onAllNodesWithTag("start-plan").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        ui.onNodeWithTag("start-plan").performClick()
        ui.waitUntil(15000) { runBlocking { db.dao().active() } != null }
        assertEquals(1, runBlocking { db.dao().sets().size })
        waitFor("Workout started."); ui.waitUntil(10000) { ui.onAllNodesWithText("Workout started.").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("Synthetic press").performScrollTo().performClick()
        waitFor("Save set")
        ui.onAllNodesWithText("8-10 reps · 100.25 lb").onFirst().performScrollTo().assertIsDisplayed()
        capture("logger-plan-targets")
        ui.onNodeWithTag("field:Weight (kg)").performScrollTo().performTextReplacement("45.5")
        ui.onNodeWithTag("field:Reps").performScrollTo().performTextReplacement("7")
        ui.onNodeWithText("Save set").performScrollTo().performClick()
        ui.waitUntil(15000) { runBlocking { db.dao().sets().size } == 2 }
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Recorded sets · 1"));ui.onNodeWithText("Recorded sets · 1").performScrollTo().assertIsDisplayed()
        capture("logger-actual-results")
        val actual = runBlocking { db.dao().sets().last() }
        assertEquals(45.5, actual.weightKg, 0.0); assertEquals(7, actual.reps)
        assertEquals("100.25", runBlocking { db.planDao().detail(saved.id)!!.ordered.first().target.weightValue })
        assertEquals("lb", runBlocking { db.planDao().detail(saved.id)!!.ordered.first().target.weightUnit })
    }
    @Test fun cancelInvalidPlanAndDuplicateWarningPreserveRecordedData() {
        val before = runBlocking { WorkoutRepository(db).backup() }
        paste(simple)
        ui.onNodeWithText("Cancel", substring = false).performClick()
        val after = runBlocking { WorkoutRepository(db).backup() }
        assertEquals(before.copy(exportedAt = after.exportedAt), after)
        if (ui.onAllNodesWithTag("train-screen").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithTag("train-screen").performScrollToNode(hasText("My templates", substring = false))
            ui.onNodeWithText("My templates", substring = false).performClick()
            ui.onNodeWithText("Import template", substring = false).performClick()
        } else ui.onAllNodes(hasText("Paste plan") and hasClickAction()).onLast().performClick()
        ui.onNode(hasSetTextAction() and hasText("Plan reply")).performScrollTo().performTextInput(simple.replace("\"repsMin\":8", "\"repsMin\":0"))
        ui.onNodeWithText("Preview plan").performClick()
        ui.waitUntil(10000) { runCatching { ui.onNode(hasText("Check rep minimum/maximum", substring = true)).assertIsDisplayed() }.isSuccess }
        assertTrue(runBlocking { db.planDao().plans().isEmpty() })
        ui.onNode(hasSetTextAction() and hasText("Plan reply")).performScrollTo().performTextReplacement(simple)
        Espresso.closeSoftKeyboard()
        ui.waitUntil(10000) { model.planImportText.value == simple && !model.busy.value }
        ui.onNodeWithText("Preview plan").performClick(); waitFor("Edit planned workout")
        ui.onNodeWithText("Save plan").performClick(); waitFor("Saved template")
        val id = runBlocking { db.planDao().plans().single().id }
        paste(simple)
        ui.onNodeWithText("Save plan").performClick(); waitFor("Plan already saved")
        capture("plan-duplicate-warning")
        ui.onNodeWithText("Open existing plan").performClick(); waitFor("Saved template")
        assertEquals(id, runBlocking { db.planDao().plans().single().id })
        paste(simple)
        ui.onNodeWithText("Save plan").performClick(); waitFor("Plan already saved")
        ui.onNodeWithText("Save another copy").performClick(); waitFor("Saved template")
        assertEquals(2, runBlocking { db.planDao().plans().size })
        assertEquals(before.workouts, runBlocking { db.dao().workouts() }); assertEquals(before.sets, runBlocking { db.dao().sets() })
    }
}
