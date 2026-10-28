package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic fixtures are restricted to the separately installed preview package. */
class UiAuditCaptureTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val db get() = model.photoRepository.database
    private val press = "Bench Press"
    @Before fun prepare() {
        assumeTrue(instrument.targetContext.packageName == "com.example.fitnotes.preview.photos")
        listOf("history-filter", "strength-progress", "running-progress").forEach {
            instrument.targetContext.getSharedPreferences(it,0).edit().clear().commit()
        }
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
        }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading }
    }
    private fun restore(populated: Boolean) {
        val ready = AtomicBoolean(false)
        val day = com.example.fitnotes.ui.dayMillis(com.example.fitnotes.ui.moveDay(com.example.fitnotes.ui.todayDate(),-1)) + 18*3600000L
        ui.runOnIdle {
            model.pendingRestore.value = if (!populated) Backup(exercises=emptyList(),workouts=emptyList(),entries=emptyList(),sets=emptyList()) else Backup(
                exercises=listOf(Exercise(1,press,"bench press"),Exercise(2,"Seated Cable Row","seated cable row"),Exercise(3,"Squat","squat")),
                workouts=listOf(Workout(10,day,day+2700000,"Upper Body",finished=true),Workout(11,day-604800000L,day-604800000L+2400000,"Upper Body",finished=true)),
                entries=listOf(WorkoutExercise(20,10,1,0),WorkoutExercise(21,10,2,1),WorkoutExercise(22,11,1,0)),
                sets=listOf(WorkoutSet(30,20,60.0,8,0),WorkoutSet(31,20,60.0,8,1),WorkoutSet(32,21,45.0,10,0),WorkoutSet(33,22,57.5,8,0)),
                plannedWorkouts=listOf(PlannedWorkout("upper","Upper Body","",45,"",day,"a".repeat(64))),
                plannedExercises=listOf(PlannedExercise(100,"upper",1,0,press,3,8,10,"60","kg","")),
                plannedSets=(0..2).map { PlannedSet((200+it).toLong(),100,it,8,10,"60","kg",90) })
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        ui.mainClock.advanceTimeBy(10000)
    }
    private fun capture(name: String) {
        ui.waitForIdle(); instrument.waitForIdleSync(); android.os.SystemClock.sleep(350)
        val phase=InstrumentationRegistry.getArguments().getString("auditPhase") ?: "after"
        val bitmap=instrument.uiAutomation.takeScreenshot()!!
        File(instrument.targetContext.filesDir,"audit-$phase-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun trainAction(text: String) {
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText(text,substring=false))
        ui.onNodeWithText(text,substring=false).performClick()
    }
    @Test fun captureEmptyAndPopulatedWorkflows() {
        restore(false)
        capture("train-empty")
        trainAction("My templates"); capture("plans-empty")
        ui.onNodeWithContentDescription("Back",substring=false).performClick()
        ui.onNodeWithText("History",substring=false).performClick(); capture("history-empty")
        ui.onNodeWithText("Progress",substring=false).performClick(); capture("progress-empty")
        ui.onNodeWithText("Train",substring=false).performClick()
        restore(true)
        capture("train-ready")
        trainAction("My templates"); capture("plans-list")
        ui.onNodeWithText("Upper Body",substring=false).performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("start-plan").fetchSemanticsNodes().isNotEmpty() }
        capture("plan-detail")
        ui.onNodeWithText("Close",substring=false).performClick()
        ui.onNodeWithText("History",substring=false).performClick(); capture("history")
        ui.onNodeWithText("Progress",substring=false).performClick(); capture("progress")
        ui.onNodeWithText("Train",substring=false).performClick()
        val active=runBlocking { val repo=WorkoutRepository(db); val id=repo.start(); repo.saveDetails(id,"Upper Body",""); repo.addExercise(id,1);repo.addExercise(id,2);id }
        ui.waitUntil(15000) { model.data.value.active?.entries?.size==2 }
        capture("train-active")
        ui.onNodeWithTag("resume-workout").performClick(); capture("workout")
        ui.onNodeWithText(press,substring=false).performClick(); capture("logger-empty")
        if(InstrumentationRegistry.getArguments().getString("auditPhase")=="before") {
            ui.onNodeWithText("Add set",substring=false).performClick()
            ui.onNodeWithTag("edit-weight").performTextReplacement("60")
            ui.onNodeWithTag("edit-reps").performTextReplacement("8")
            Espresso.closeSoftKeyboard();capture("set-editor")
            ui.onNodeWithText("Complete set",substring=false).performClick()
        } else {
            ui.onNodeWithTag("quick-weight").performTextReplacement("60")
            ui.onNodeWithTag("quick-reps").performClick().performTextReplacement("8")
            ui.onNodeWithTag("quick-complete").performScrollTo().assertIsDisplayed()
            capture("logger-keyboard")
            Espresso.closeSoftKeyboard()
            ui.onNodeWithText("Set options",substring=false).performClick();capture("set-editor")
            ui.onNodeWithText("Cancel",substring=false).performClick()
            ui.onNodeWithTag("quick-complete").performClick()
        }
        ui.waitUntil(15000) { model.data.value.active?.entries?.first()?.sets?.size==1 && !model.busy.value }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("saved-set:set:"+model.data.value.active!!.entries.first().sets.first().id).fetchSemanticsNodes().isNotEmpty() }
        capture("logger")
        Assert.assertEquals(active,runBlocking { db.dao().active()!!.id })
        Assert.assertEquals(5,runBlocking { db.dao().sets().size })
    }
    private fun openLogger() {
        ui.onNodeWithTag("start-workout").performClick()
        ui.onNodeWithTag("confirm-workout-setup").performClick()
        ui.onNodeWithTag("exercise-search").performTextReplacement(press)
        Espresso.closeSoftKeyboard()
        ui.onNode(hasText(press,substring=false) and !hasSetTextAction()).performClick()
        ui.onNodeWithText("Add 1 exercise",substring=false).performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("quick-set-entry").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun startLogThreeSetsEditAndRetainDraftAcrossRecreation() {
        restore(true);openLogger()
        ui.onNodeWithTag("quick-complete").assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        ui.onNodeWithTag("quick-weight").performTextReplacement("60")
        ui.onNodeWithTag("quick-reps").performTextReplacement("0")
        ui.onNodeWithTag("quick-complete").assertIsNotEnabled()
        ui.onNodeWithTag("quick-reps").performTextReplacement("8")
        Espresso.closeSoftKeyboard()
        repeat(3) { index ->
            ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("quick-complete"))
            // Repeated taps during a single pending write must not create another set.
            if(index==0) ui.onNodeWithTag("quick-complete").performTouchInput { doubleClick() }
            else ui.onNodeWithTag("quick-complete").performClick()
            ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.entries?.single()?.sets?.size==index+1 }
            ui.onNodeWithTag("quick-weight").assertTextContains("60",substring=false)
            ui.onNodeWithTag("quick-reps").assertTextContains("8",substring=false)
        }
        val active=model.data.value.active!!
        Assert.assertTrue(runBlocking { db.planDao().runTargets().isEmpty() })
        val id=active.entries.single().sets.first().id
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("saved-set:set:$id"))
        ui.onNodeWithTag("saved-set:set:$id").performClick()
        ui.onNodeWithTag("edit-reps").performTextReplacement("9")
        Espresso.closeSoftKeyboard();ui.onNodeWithText("Save changes",substring=false).performClick()
        ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().set(id)?.reps }==9 }
        Assert.assertEquals(7,runBlocking { db.dao().sets().size })
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("quick-reps"))
        ui.onNodeWithTag("quick-reps").assertTextContains("8",substring=false)
        ui.onNodeWithTag("quick-weight").assertTextContains("60",substring=false)
        ui.onNodeWithContentDescription("Back to workout").performClick()
        ui.onNodeWithText("Finish",substring=false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active==null }
        Assert.assertEquals(3,runBlocking { db.dao().workoutEntries(active.workout.id).flatMap { e -> db.dao().sets().filter { it.entryId==e.id } }.size })
    }
    @Test fun planStartsImmediatelyAndKeepsTargetsSeparateWithNamedControls() {
        restore(true)
        trainAction("My templates")
        ui.onNodeWithTag("template:upper").performClick()
        ui.onNodeWithTag("start-plan").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        ui.onNodeWithText("Plan actions ▾",substring=false).performClick()
        ui.onNodeWithText("Delete template",substring=false).performClick()
        ui.onNodeWithText("Cancel",substring=false).performClick()
        ui.onNodeWithTag("start-plan").performClick()
        ui.waitUntil(15000) { model.data.value.active!=null && !model.busy.value }
        ui.onNodeWithText(press,substring=false).performClick()
        val targets=runBlocking { db.planDao().runTargets() }
        ui.onNodeWithContentDescription("Set 1 weight in kilograms").assertIsDisplayed()
        ui.onNodeWithContentDescription("Set 1 reps").assertIsDisplayed()
        ui.onNodeWithContentDescription("Complete set 1").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().sets().size }==5 }
        Assert.assertEquals(targets,runBlocking { db.planDao().runTargets() })
        Assert.assertEquals(1,runBlocking { db.dao().sets().count { it.targetId!=null } })
    }
    @Test fun optionsRetainNotesAndCardioLogging() {
        restore(true);openLogger()
        ui.onNodeWithText("Set options",substring=false).performClick()
        ui.onNodeWithText("Add note",substring=false).performClick()
        ui.onNodeWithTag("edit-weight").performTextReplacement("0")
        ui.onNodeWithTag("edit-reps").performTextReplacement("12")
        ui.onNodeWithText("Set note",substring=false).performTextReplacement("Warm-up")
        Espresso.closeSoftKeyboard();ui.onAllNodesWithText("Set options",substring=false).onLast().performClick()
        ui.onNodeWithText("Distance / time",substring=false).performClick()
        ui.onNodeWithText("Distance",substring=false).performTextReplacement("1")
        ui.onNodeWithText("Seconds",substring=false).performTextReplacement("300")
        Espresso.closeSoftKeyboard();ui.onAllNodesWithText("Complete set",substring=false).onLast().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.entries?.single()?.sets?.size==1 }
        val saved=model.data.value.active!!.entries.single().sets.single()
        Assert.assertEquals("Warm-up",saved.comment)
        Assert.assertEquals(1.0,requireNotNull(saved.distance),0.0)
        Assert.assertEquals(300.0,requireNotNull(saved.durationSeconds),0.0)
    }

    @Test fun quickEntryKeepsBodyweightAndRepsOnlyLoadsSeparate() {
        restore(true);openLogger()
        ui.onNodeWithTag("quick-weight").performTextReplacement("5")
        ui.onNodeWithTag("quick-reps").performTextReplacement("8")
        Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Set options",substring=false).performClick()
        ui.onNodeWithTag("edit-weight").assertTextContains("5",substring=false)
        ui.onAllNodesWithText("Set options",substring=false).onLast().performClick()
        ui.onNodeWithText(LoadMode.label(LoadMode.EXTERNAL)+" ▾",substring=false).performClick()
        ui.onNodeWithText(LoadMode.label(LoadMode.BODYWEIGHT),substring=false).performClick()
        ui.onAllNodesWithText("Complete set",substring=false).onLast().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.entries?.single()?.sets?.size==1 }
        ui.onNodeWithTag("quick-weight").assertTextContains("Added kg",substring=false)
        ui.onNodeWithTag("quick-complete").performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.entries?.single()?.sets?.size==2 }
        Assert.assertTrue(model.data.value.active!!.entries.single().sets.all { it.loadMode==LoadMode.BODYWEIGHT && it.weightKg==5.0 })
        ui.onNodeWithTag("quick-weight").performTextReplacement("bad")
        Espresso.closeSoftKeyboard();ui.onNodeWithText("Set options",substring=false).performClick()
        ui.onNodeWithTag("edit-weight").performTextReplacement("")
        ui.onAllNodesWithText("Set options",substring=false).onLast().performClick()
        ui.onNodeWithText(LoadMode.label(LoadMode.BODYWEIGHT)+" ▾",substring=false).performClick()
        ui.onNodeWithText(LoadMode.label(LoadMode.REPS_ONLY),substring=false).performClick()
        ui.onAllNodesWithText("Complete set",substring=false).onLast().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.entries?.single()?.sets?.size==3 }
        ui.onNodeWithTag("quick-weight").assertDoesNotExist()
        ui.onNodeWithTag("quick-complete").assertIsEnabled().performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.entries?.single()?.sets?.size==4 }
        Assert.assertTrue(model.data.value.active!!.entries.single().sets.takeLast(2).all { it.loadMode==LoadMode.REPS_ONLY && it.weightKg==0.0 && !it.weightSpecified })
    }

}
