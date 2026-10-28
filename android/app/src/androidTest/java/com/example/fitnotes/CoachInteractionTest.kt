package com.example.fitnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.CoachScreen
import com.example.fitnotes.ui.theme.FitNotesTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CoachInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val db get()=model.photoRepository.database
    private val clipboard get()=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val response="""{"title":"Strength + Running","sessions":[
        {"title":"Easy Run","type":"running","distanceKm":5},
        {"title":"Upper Body","type":"strength","durationMinutes":45,"exercises":[{"name":"Synthetic Press","sets":3,"reps":"8-10","weight":99,"unit":"kg"}]},
        {"title":"Lower Body","type":"strength","exercises":[{"name":"Synthetic Squat","sets":3,"reps":"8-12"}]},
        {"title":"Intervals","type":"running","intervals":"6 x 400 m, 200 m easy recovery"},
        {"title":"Full Body","type":"strength","exercises":[{"name":"Synthetic Press","sets":2,"reps":"8-12"}]}]}"""
    @Before fun seed() {
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        val ready=AtomicBoolean(false);val old=System.currentTimeMillis()-2*86400000L
        ui.activityRule.scenario.onActivity {
            model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Synthetic Press","synthetic press"),Exercise(2,"Synthetic Squat","synthetic squat")),
                workouts=listOf(Workout(1,old,old+3600000,"Kept history",notes="PRIVATE SYNTHETIC NOTE",finished=true)),
                entries=listOf(WorkoutExercise(1,1,1,0)),sets=listOf(WorkoutSet(1,1,20.0,8,0)))
            model.confirmRestore {ready.set(true)}
        }
        ui.waitUntil(30000){ready.get() && !model.busy.value && !model.data.value.loading && !model.data.value.imported.loading}
        ui.mainClock.advanceTimeBy(5000)
    }
    private fun capture(name:String) {
        ui.mainClock.advanceTimeBy(10000);ui.waitForIdle();instrument.waitForIdleSync()
        android.os.SystemClock.sleep(3000)
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        val bitmap=instrument.uiAutomation.takeScreenshot()!!
        File(instrument.targetContext.filesDir,"coach-$name$suffix.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun coachAction(text:String) {ui.onNodeWithTag("coach-screen").performScrollToNode(hasText(text,substring=false));ui.onNodeWithText(text,substring=false).performClick()}
    @Test fun clipboardIsOnlyReadAfterPasteAndAdviceCreatesNoTraining() {
        val reads=AtomicInteger();val before=runBlocking{WorkoutRepository(db).backup()}
        val snapshot=model.data.value
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {FitNotesTheme {Surface(Modifier.fillMaxSize()) {
            CoachScreen(snapshot,model,false,import={},clipboardReader={reads.incrementAndGet();"Take an easy day."})
        }}} }
        ui.waitForIdle();assertEquals(0,reads.get())
        ui.onNodeWithText("Training focus:",substring=true).performClick();ui.onNodeWithText("Running",substring=false).performClick()
        assertEquals(0,reads.get());coachAction("Paste AI response")
        ui.waitUntil(10000){model.coachAdvice.value!=null};assertEquals(1,reads.get())
        ui.onNodeWithTag("coach-screen").performScrollToNode(hasTestTag("coach-advice"));ui.onNodeWithText("Take an easy day.").assertIsDisplayed();capture("advice")
        val after=runBlocking{WorkoutRepository(db).backup()};assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    private fun trainAction(text:String) {ui.onNodeWithTag("train-screen").performScrollToNode(hasText(text,substring=false));ui.onNodeWithText(text,substring=false).performClick()}
    @Test fun queueMenusMoveSkipRestoreAndValidateDates() {
        val id=runBlocking {
            val definition=(CoachResponse.parse(response) as CoachResponse.Plan).definition
            val d=PortableProgramParser.draft(definition,db.dao().exercises(),emptyList(),emptySet())
            (ProgramImportRepository(db).save(d,flexible=true) as ProgramImportResult.Saved).id
        }
        ui.waitUntil(15000){model.programs.value.any{it.program.id==id}}
        trainAction("Choose another")
        ui.onNodeWithTag("program-list").performScrollToNode(hasContentDescription("Session actions: Easy Run"))
        ui.onNodeWithContentDescription("Session actions: Easy Run").performClick();ui.onNodeWithText("Skip session",substring=false).performClick()
        ui.waitUntil(10000){!model.busy.value && model.programs.value.single().queue.first().step.action=="skipped"}
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Restore",substring=false));ui.onNodeWithText("Restore",substring=false).performClick()
        ui.waitUntil(10000){!model.busy.value && model.programs.value.single().queue.first().step.action=="pending"}
        ui.onNodeWithContentDescription("Session actions: Easy Run").performClick();ui.onNodeWithText("Move to end",substring=false).performClick()
        ui.waitUntil(10000){!model.busy.value && model.programs.value.single().next!!.plan.plan.title=="Upper Body"}
        ui.onNodeWithTag("program-list").performScrollToNode(hasContentDescription("Session actions: Upper Body"))
        ui.onNodeWithContentDescription("Session actions: Upper Body").performClick();ui.onNodeWithText("Change scheduled date",substring=false).performClick()
        ui.onNodeWithText("YYYY-MM-DD (optional)",substring=false).performTextInput("2026-02-30")
        ui.onNodeWithText("Save date",substring=false).performClick();ui.waitUntil(10000){model.programError.value!=null && !model.busy.value}
        ui.onNodeWithText("Save date",substring=false).assertIsDisplayed();ui.onNodeWithText("Cancel",substring=false).performClick()
        capture("queue")
        assertEquals(1,runBlocking{db.dao().workouts().size});assertEquals(1,runBlocking{db.dao().sets().size})
        // Leave a stable out-of-order active session for an explicit force-stop verification.
        runBlocking {
            val p=db.programDao().detail(id)!!
            val lower=p.queue.first{it.plan!!.plan.title=="Lower Body"}
            TrainingProgramRepository(db).startOccurrence(id,lower.step.id)
            File(instrument.targetContext.filesDir,"coach-restart.json").writeText(BackupCodec.encode(WorkoutRepository(db).backup()))
        }
    }
    @Test fun editRunningTargetsRejectInvalidInputAndLinkOnlyRecordedMetrics() {
        val ids=runBlocking {
            val actual=WorkoutRepository(db).logPast("running","","2026-10-20",null,1800.0,"Actual synthetic run","",null,4.9,"km")
            val definition=(CoachResponse.parse("""{"title":"Easy Run","type":"running","distanceKm":5,"durationMinutes":30}""") as CoachResponse.Plan).definition
            val draft=PortableProgramParser.draft(definition,db.dao().exercises(),emptyList(),emptySet())
            (ProgramImportRepository(db).save(draft,flexible=true) as ProgramImportResult.Saved).id to actual
        }
        ui.waitUntil(15000){model.programs.value.any{it.program.id==ids.first} && model.notebook.value.runs.isNotEmpty()}
        val before=runBlocking{db.dao().workout(ids.second)!!}
        trainAction("View / Link recorded run")
        ui.onNodeWithContentDescription("Session actions: Easy Run").performClick();ui.onNodeWithText("Edit session",substring=false).performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("running-plan-editor").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("running-plan-editor").performScrollToNode(hasText("Distance target (km, optional)",substring=false))
        ui.onNodeWithText("Distance target (km, optional)",substring=false).performTextReplacement("NaN");Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save session",substring=false).performClick();ui.waitUntil(10000){model.programError.value!=null && !model.busy.value}
        assertEquals(5.0,model.programs.value.single().nextOccurrence!!.plan!!.plan.runningJson.let{org.json.JSONObject(it).getDouble("distanceKm")},0.0)
        ui.onNodeWithTag("running-plan-editor").performScrollToNode(hasText("Distance target (km, optional)",substring=false));ui.onNodeWithText("Distance target (km, optional)",substring=false).performTextReplacement("6.5")
        ui.onNodeWithTag("running-plan-editor").performScrollToNode(hasText("Duration target (minutes, optional)",substring=false));ui.onNodeWithText("Duration target (minutes, optional)",substring=false).performTextReplacement("35");Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save session",substring=false).performClick();ui.waitUntil(10000){!model.busy.value && ui.onAllNodesWithTag("running-plan-editor").fetchSemanticsNodes().isEmpty()}
        val target=runBlocking{db.programDao().detail(ids.first)!!.nextOccurrence!!.plan!!.plan}
        assertEquals(6.5,org.json.JSONObject(target.runningJson).getDouble("distanceKm"),0.0)
        assertEquals(35,org.json.JSONObject(target.runningJson).getInt("durationMinutes"))
        ui.onNodeWithContentDescription("Session actions: Easy Run").performClick();ui.onNodeWithText("Link recorded run",substring=false).performClick()
        ui.onNodeWithTag("link-program-run").performScrollToNode(hasText("Actual synthetic run",substring=true));ui.onNodeWithText("Actual synthetic run",substring=true).assertTextContains("Recorded duration",substring=true).performClick()
        ui.waitUntil(10000){!model.busy.value && model.programs.value.single().program.status=="completed"}
        assertEquals(before,runBlocking{db.dao().workout(ids.second)});assertEquals(2,runBlocking{db.dao().workouts().size});assertEquals(1,runBlocking{db.dao().sets().size})
    }
    @Test fun copyPasteFiveSessionsPostponeEditRotateStartAndFinish() {
        ui.onNodeWithText("Coach",substring=false).performClick()
        ui.onNodeWithText("Request:",substring=true).performClick();ui.onNodeWithText("Plan my training week",substring=false).performClick()
        capture("request")
        coachAction("Copy coach prompt");ui.waitUntil(10000){model.coachPrompt.value!=null && !model.busy.value}
        val text=ui.runOnIdle {clipboard.primaryClip!!.getItemAt(0).text.toString()}
        assertTrue(text.contains("20 kg"));assertTrue(text.contains("\"sessions\""));assertFalse(text.contains("PRIVATE SYNTHETIC NOTE"))
        ui.runOnIdle{clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic AI response",response))}
        coachAction("Paste AI response");ui.waitUntil(15000){model.programImportDraft.value!=null && !model.busy.value}
        ui.onNodeWithTag("program-import-list").assertIsDisplayed();capture("preview")
        ui.onNodeWithTag("save-imported-program").performClick()
        ui.waitUntil(15000){model.programs.value.any{it.program.flexibleQueue} && !model.busy.value}
        assertTrue(runBlocking{db.planDao().sets().all{it.weightValue==null}}) // AI weights need explicit acceptance.
        ui.onNodeWithTag("next-program-workout").assert(hasAnyDescendant(hasText("Easy Run",substring=true)))
        capture("up-next-run")
        trainAction("Do later")
        ui.waitUntil(10000){model.programs.value.single().next?.plan?.plan?.title=="Upper Body" && !model.busy.value}
        ui.activityRule.scenario.recreate();ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]}
        ui.onNodeWithTag("next-program-workout").assert(hasAnyDescendant(hasText("Upper Body",substring=true)));capture("up-next-upper")
        trainAction("Choose another")
        ui.onNodeWithContentDescription("Session actions: Upper Body").performClick();ui.onNodeWithText("Edit session",substring=false).performClick()
        ui.waitUntil(10000){model.planEdit.value!=null && !model.busy.value}
        ui.onNodeWithText("Plan details",substring=false).performClick();ui.onNodeWithText("Title",substring=false).performTextReplacement("Edited Upper")
        Espresso.closeSoftKeyboard()
        val exercise=model.planEdit.value!!.exercises.single()
        ui.onNodeWithTag("plan-import-list").performScrollToNode(hasTestTag("plan-row:${exercise.key}"));ui.onNodeWithTag("plan-row:${exercise.key}").performClick()
        ui.onNodeWithTag("plan-import-list").performScrollToNode(hasText("Resolve exercise",substring=false));ui.onNodeWithText("Resolve exercise",substring=false).performClick()
        ui.onNodeWithTag("exercise-search").performTextReplacement("Synthetic Squat");Espresso.closeSoftKeyboard()
        ui.onNode(hasText("Synthetic Squat",substring=false) and !hasSetTextAction()).performClick();ui.onNodeWithText("Use for strength plan",substring=false).performClick()
        assertEquals(2L,model.planEdit.value!!.exercises.single().exerciseId)
        ui.onNodeWithTag("save-plan").performClick();ui.waitUntil(10000){!model.busy.value && model.planEdit.value==null}
        ui.onNodeWithText("Close",substring=false).performClick()
        trainAction("Start workout")
        ui.waitUntil(15000){model.data.value.active!=null && !model.busy.value}
        val active=model.data.value.active!!;assertEquals("Edited Upper",active.workout.title);assertEquals(2L,active.entries.single().exercise.id)
        val done=AtomicBoolean(false);ui.runOnIdle{model.saveSet(active.entries.single().entry.id,"20","8"){done.set(true)}}
        ui.waitUntil(10000){done.get() && !model.busy.value};done.set(false)
        ui.runOnIdle{model.finish(active.workout.id){done.set(true)}};ui.waitUntil(15000){done.get() && !model.busy.value}
        val p=runBlocking{db.programDao().programs().single().let{db.programDao().detail(it.id)!!}}
        assertEquals("completed",p.queue.first().step.action);assertEquals(active.workout.id,p.queue.first().step.workoutId)
        assertEquals("Easy Run",p.queue.last().plan!!.plan.title);assertEquals("Lower Body",p.next!!.plan.plan.title)
        assertEquals(2,runBlocking{db.dao().sets().size});assertEquals(20.0,runBlocking{db.dao().sets().first().weightKg},0.0)
        val backup=runBlocking{WorkoutRepository(db).backup()};assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
    }
}

class CoachRestartTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun forceStopRetainsOrderActiveOccurrenceAndRealHistory() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyCoachRestart")=="true")
        lateinit var model:FitNotesViewModel
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]}
        ui.waitUntil(30000){!model.data.value.loading && !model.data.value.imported.loading && !model.notebook.value.data.loading && model.programs.value.isNotEmpty()}
        val db=model.photoRepository.database
        val expected=BackupCodec.decode(File(instrument.targetContext.filesDir,"coach-restart.json").readText())
        val actual=runBlocking{WorkoutRepository(db).backup()}
        assertEquals(expected.programs,actual.programs);assertEquals(expected.programSteps,actual.programSteps)
        assertEquals(expected.programSessions,actual.programSessions);assertEquals(expected.plannedWorkouts,actual.plannedWorkouts)
        assertEquals(expected.workouts,actual.workouts);assertEquals(expected.sets,actual.sets);assertEquals(expected.runTargets,actual.runTargets)
        val active=model.data.value.active!!;assertEquals("Lower Body",active.workout.title)
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Resume workout",substring=false));ui.onNodeWithText("Resume workout",substring=false).performClick()
        ui.onNodeWithTag("workout-overview").assertExists()
        val p=model.programs.value.single();val id=p.inProgress!!.step.id
        runBlocking{assertEquals(active.workout.id,TrainingProgramRepository(db).startOccurrence(p.program.id,id))}
        assertEquals(expected.workouts.size,runBlocking{db.dao().workouts().size})
    }
}
