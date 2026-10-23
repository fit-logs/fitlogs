package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
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

@OptIn(ExperimentalTestApi::class)
class ProgramInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val db get()=model.photoRepository.database
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val templateTitle="Synthetic upper body template with a deliberately long readable title"
    private lateinit var template:String
    @Before fun seed() {
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        val ready=java.util.concurrent.atomic.AtomicBoolean(false);val old=System.currentTimeMillis()-86400000
        ui.activityRule.scenario.onActivity { model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Synthetic press","synthetic press")),workouts=listOf(Workout(10,old,old+3600000,"Keep actual history")),entries=listOf(WorkoutExercise(10,10,1,0)),sets=listOf(WorkoutSet(10,10,20.0,8,0,loadMode=LoadMode.EXTERNAL)));model.confirmRestore{ready.set(true)} }
        ui.waitUntil(30000){ready.get() && !model.busy.value && !model.data.value.imported.loading}
        template=runBlocking { val target=PlanPrescription("Synthetic press",listOf(PlanSetTarget(8,10,PlanWeight("25","kg"),loadMode=LoadMode.EXTERNAL)))
            (WorkoutPlanRepository(db).save(PlanPreview(PlanSpec(templateTitle,"",30,"Keep guidance",listOf(target),planFingerprint(templateTitle)),listOf(ResolvedPlanExercise(target,1)))) as PlanSaveResult.Saved).id }
        ui.waitUntil(15000){model.plans.value.size==1};ui.mainClock.advanceTimeBy(10000)
    }
    private fun capture(name:String){ui.waitForIdle();instrument.waitForIdleSync();android.os.SystemClock.sleep(400);val b=instrument.uiAutomation.takeScreenshot()!!;val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty();File(instrument.targetContext.filesDir,"$name$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun train(text:String){ui.onNodeWithTag("train-screen").performScrollToNode(hasText(text,substring=false));ui.onNodeWithText(text,substring=false).performClick()}
    private fun settle(test:()->Boolean){ui.waitUntil(20000){!model.busy.value && test()};ui.mainClock.advanceTimeBy(10000)}
    private fun templateMenu(title:String=templateTitle){ui.onNodeWithTag("template-list").performScrollToNode(hasContentDescription("Template actions: $title"));ui.onNodeWithContentDescription("Template actions: $title").performClick()}
    @Test fun manageTemplatesWithSearchKeyboardArchiveAndConfirmedDeletionWithoutTouchingHistory() {
        val actual=runBlocking{db.dao().sets()};train("My templates")
        ui.onNodeWithTag("template-search").performTextInput("upper")
        ui.onNodeWithTag("template-search").performKeyInput{pressKey(Key.Tab)};Espresso.closeSoftKeyboard();capture("templates-search")
        templateMenu();ui.onNodeWithText("Rename",substring=false).performClick();ui.onNodeWithTag("template-title").performTextReplacement("Renamed synthetic template");Espresso.closeSoftKeyboard();ui.onNodeWithText("Save name",substring=false).performClick();settle{runBlocking{db.planDao().detail(template)!!.plan.title}=="Renamed synthetic template"}
        ui.onNodeWithTag("template-search").performTextClearance();Espresso.closeSoftKeyboard()
        templateMenu("Renamed synthetic template");ui.onNodeWithText("Pin",substring=false).performClick();settle{runBlocking{db.planDao().detail(template)!!.plan.pinned}}
        templateMenu("Renamed synthetic template");ui.onNodeWithText("Archive",substring=false).performClick();settle{runBlocking{db.planDao().detail(template)!!.plan.archived}}
        ui.onNodeWithText("Available",substring=false).performClick();templateMenu("Renamed synthetic template");ui.onNodeWithText("Unarchive",substring=false).performClick();settle{!runBlocking{db.planDao().detail(template)!!.plan.archived}}
        ui.onNodeWithText("Archived",substring=false).performClick();templateMenu("Renamed synthetic template");ui.onNodeWithText("Duplicate",substring=false).performClick();settle{runBlocking{db.planDao().plans().size}==2}
        templateMenu("Renamed synthetic template");ui.onNodeWithText("Delete",substring=false).performClick();ui.onNodeWithText("Delete template?",substring=false).assertExists();ui.onNodeWithText("Cancel",substring=false).performClick();assertNotNull(runBlocking{db.planDao().detail(template)})
        templateMenu("Renamed synthetic template");ui.onNodeWithText("Delete",substring=false).performClick();ui.onNodeWithText("Delete",substring=false).performClick();settle{runBlocking{db.planDao().detail(template)}==null}
        assertEquals(actual,runBlocking{db.dao().sets()});assertEquals(1,runBlocking{db.dao().workouts().size});capture("templates-managed")
    }
    @Test fun buildFourDayProgramLogRealSetAndExplicitlyAdvancePauseResumeAndSkip() {
        train("My programs");ui.onNodeWithText("Build from templates",substring=false).performClick()
        ui.onNodeWithTag("program-title").performTextInput("Synthetic four day program with a long title")
        ui.onNodeWithTag("program-weeks").performTextReplacement("2");Espresso.closeSoftKeyboard()
        repeat(4){ui.onNodeWithTag("program-builder").performScrollToNode(hasTestTag("add-program-day:$template"));ui.onNodeWithTag("add-program-day:$template").performClick()}
        capture("program-ordered-days");ui.onNodeWithText("Save program",substring=false).performClick();settle{model.programs.value.size==1}
        val id=model.programs.value.single().program.id
        ui.onNodeWithTag("program-list").performScrollToNode(hasTestTag("program:$id"));ui.onNodeWithTag("program:$id").performClick()
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Set active / Resume program",substring=false));ui.onNodeWithText("Set active / Resume program",substring=false).performClick();settle{model.programs.value.single().program.status=="active"}
        ui.onNodeWithText("Close",substring=false).performClick();ui.onNodeWithTag("train-screen").performScrollToNode(hasTestTag("next-program-workout"));capture("program-next-workout")
        assertEquals(5,runBlocking{db.planDao().plans().size});assertEquals(1,runBlocking{db.dao().sets().size});assertEquals(0,model.programs.value.single().program.nextIndex)
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Start next workout",substring=false));ui.onNodeWithText("Start next workout",substring=false).performClick();settle{model.data.value.active!=null}
        val workout=model.data.value.active!!.workout.id
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Synthetic press",substring=false));ui.onNodeWithText("Synthetic press",substring=false).performClick()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Weight (kg)"));ui.onNodeWithTag("field:Weight (kg)").performTextReplacement("27.5")
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Reps"));ui.onNodeWithTag("field:Reps").performTextReplacement("9");Espresso.closeSoftKeyboard()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Save set",substring=false));ui.onNodeWithText("Save set",substring=false).performClick();settle{runBlocking{db.dao().sets().size}==2}
        ui.onNodeWithContentDescription("Back to workout").performClick();ui.onNodeWithText("Finish workout",substring=false).performClick();ui.onNodeWithText("Finish",substring=false).performClick();settle{runBlocking{db.dao().workout(workout)!!.isComplete}}
        ui.onNodeWithText("Done",substring=false).performClick();ui.mainClock.advanceTimeBy(10000)
        ui.onNode(hasText("Train") and hasClickAction()).performClick();ui.onNodeWithTag("train-screen").assertExists()
        assertEquals(0,model.programs.value.single().program.nextIndex)
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Complete program day",substring=false));capture("program-results-ready")
        ui.onNodeWithText("Complete program day",substring=false).performClick();settle{model.programs.value.single().program.nextIndex==1}
        train("My programs");ui.onNodeWithTag("program-list").performScrollToNode(hasTestTag("program:$id"));ui.onNodeWithTag("program:$id").performClick()
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Pause program",substring=false));ui.onNodeWithText("Pause program",substring=false).performClick();settle{model.programs.value.single().program.status=="paused"}
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Set active / Resume program",substring=false));ui.onNodeWithText("Set active / Resume program",substring=false).performClick();settle{model.programs.value.single().program.status=="active"}
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Skip day",substring=false));ui.onNodeWithText("Skip day",substring=false).performClick();ui.onNode(hasText("Skip day",substring=false) and hasAnyAncestor(isDialog()) and !hasAnyAncestor(hasTestTag("program-list"))).performClick();settle{model.programs.value.single().program.nextIndex==2}
        assertEquals(listOf("completed","skipped"),runBlocking{db.programDao().steps().map{it.action}});assertEquals(2,runBlocking{db.dao().sets().size});capture("program-progress")
        ui.activityRule.scenario.recreate();assertEquals(2,runBlocking{db.programDao().detail(id)!!.program.nextIndex})
        File(instrument.targetContext.filesDir,"program-restart.json").writeText(runBlocking{BackupCodec.encode(WorkoutRepository(db).backup())})
    }
}
class ProgramRestartTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun programProgressTemplatesAndPerformedHistorySurviveForceStop(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;assumeTrue(context.packageName=="com.example.fitnotes.preview.photos")
        lateinit var model:FitNotesViewModel;ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]}
        ui.waitUntil(30000){!model.data.value.loading && model.programs.value.isNotEmpty()}
        val expected=BackupCodec.decode(File(context.filesDir,"program-restart.json").readText());val actual=runBlocking{WorkoutRepository(model.photoRepository.database).backup()}
        assertEquals(expected.programs,actual.programs);assertEquals(expected.programDays,actual.programDays);assertEquals(expected.programSteps,actual.programSteps);assertEquals(expected.programSessions,actual.programSessions);assertEquals(expected.plannedWorkouts,actual.plannedWorkouts);assertEquals(expected.workouts,actual.workouts);assertEquals(expected.sets,actual.sets)
        ui.onNodeWithTag("next-program-workout").assertExists()
    }
}
