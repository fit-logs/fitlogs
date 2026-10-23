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
import java.time.LocalDate
import java.time.ZoneId

class LoadAdviceInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val db get()=model.photoRepository.database
    @Before fun seed(){
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        val ready=java.util.concurrent.atomic.AtomicBoolean(false)
        val days=listOf(3L,1L).map{LocalDate.now().minusDays(it).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Bench Press","bench press")),
                workouts=days.mapIndexed{i,t->Workout((i+1).toLong(),t,t+3600000,"Actual bench",finished=true)},
                entries=days.mapIndexed{i,_->WorkoutExercise((i+1).toLong(),(i+1).toLong(),1,0)},
                sets=(1L..6L).map{WorkoutSet(it,(it-1)/3+1,80.0,6,((it-1)%3).toInt(),loadMode=LoadMode.EXTERNAL)},
                setLoadContexts=(1L..6L).map{SetLoadContext(it,"Gym A","Barbell","total",rir=2.0)})
            model.confirmRestore{ready.set(true)}}
        ui.waitUntil(30000){ready.get() && !model.busy.value && !model.data.value.loading && !model.data.value.imported.loading}
        // Exercise the same portable definition path used by pasted programs.
        val source=PortableProgramParser.parse("""{"id":"local-bench","name":"Two-week bench","weeks":2,"days":[{"id":"bench","name":"Bench day","exercises":[{"name":"Bench Press","sets":[{"repsMin":8,"repsMax":10,"rir":2},{"repsMin":8,"repsMax":10,"rir":2},{"repsMin":8,"repsMax":10,"rir":2}]}]}]}""")
        runBlocking{val draft=PortableProgramParser.draft(source,db.dao().exercises(),emptyList(),emptySet());val id=(ProgramImportRepository(db).save(draft) as ProgramImportResult.Saved).id;TrainingProgramRepository(db).activate(id)}
        settle{model.programs.value.size==1 && model.programs.value.single().program.status=="active"}
    }
    private fun settle(test:()->Boolean={true}){ui.waitUntil(20000){!model.busy.value && test()};ui.mainClock.advanceTimeBy(10000)}
    private fun scroll(tag:String,text:String){ui.onNodeWithTag(tag).performScrollToNode(hasText(text,substring=false))}
    private fun advice(text:String){scroll("load-advice-list",text);ui.onNodeWithText(text,substring=false).performClick();settle()}
    private fun capture(name:String){ui.waitForIdle();instrument.waitForIdleSync();android.os.SystemClock.sleep(400);val b=instrument.uiAutomation.takeScreenshot()!!;val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty();File(instrument.targetContext.filesDir,"$name$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun reviewSetupAcceptThenLockAndOverrideActualWithoutRewritingTargets(){
        val original=runBlocking{db.dao().sets()}
        scroll("train-screen","Suggested weights · Review / Change");ui.onNodeWithText("Suggested weights · Review / Change",substring=false).performClick();settle{model.loadAdviceRows.value.size==1}
        ui.onNodeWithTag("use-load:1").assertIsNotEnabled();capture("load-advice-calibration")
        advice("Load setup")
        ui.onNodeWithTag("load-setup-list").performScrollToNode(hasTestTag("load-gym"));ui.onNodeWithTag("load-gym").performTextReplacement("Gym A");ui.onNodeWithTag("load-equipment").performTextReplacement("Barbell");Espresso.closeSoftKeyboard()
        scroll("load-setup-list","Load mode unknown ▾");ui.onNodeWithText("Load mode unknown ▾",substring=false).performClick();ui.onNodeWithText("External load",substring=false).performClick()
        scroll("load-setup-list","Weight basis: Confirm basis ▾");ui.onNodeWithText("Weight basis: Confirm basis ▾",substring=false).performClick();ui.onNodeWithText("Total barbell / implement load",substring=false).performClick()
        ui.onNodeWithText("Save setup",substring=false).performClick();settle{model.loadAdviceRows.value.single().advice.loads.isNotEmpty()}
        assertEquals("75",model.loadAdviceRows.value.single().advice.loads.first().value);capture("load-advice-comparable")
        ui.onNodeWithTag("load-advice-list").performScrollToNode(hasTestTag("use-load:1"));ui.onNodeWithTag("use-load:1").performClick();settle{runBlocking{db.planDao().sets().all{it.weightValue=="75"}}};assertEquals(original,runBlocking{db.dao().sets()})
        ui.onNodeWithText("Close",substring=false).performClick();scroll("train-screen","Start next workout");ui.onNodeWithText("Start next workout",substring=false).performClick();settle{model.data.value.active!=null}
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Bench Press",substring=false));ui.onNodeWithText("Bench Press",substring=false).performClick()
        scroll("logger-list","Suggested weight · Use / Change");ui.onNodeWithText("Suggested weight · Use / Change",substring=false).performClick();settle{model.loadAdviceRows.value.singleOrNull()?.subject?.kind=="entry"}
        ui.onNodeWithTag("load-advice-list").performScrollToNode(hasTestTag("use-load:1"));ui.onNodeWithTag("use-load:1").performClick();settle{model.loadAdviceRows.value.single().locked};capture("load-advice-locked")
        ui.onNodeWithText("Close",substring=false).performClick();scroll("logger-list","Record RIR / RPE");ui.onNodeWithText("Record RIR / RPE",substring=false).performClick()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("actual-rir"));ui.onNodeWithTag("actual-rir").assertTextEquals("Actual RIR (optional)","");ui.onNodeWithTag("actual-rir").performTextReplacement("1");Espresso.closeSoftKeyboard()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Weight (kg)"));ui.onNodeWithTag("field:Weight (kg)").performTextReplacement("72.5");Espresso.closeSoftKeyboard()
        scroll("logger-list","Save set");ui.onNodeWithText("Save set",substring=false).performClick();settle{runBlocking{db.dao().sets().size==7}}
        val saved=runBlocking{db.dao().sets().single{it.id !in original.map{row->row.id}}};assertEquals(72.5,saved.weightKg,0.0);assertEquals(1.0,runBlocking{db.loadDao().context(saved.id)!!.rir!!},0.0);assertTrue(runBlocking{db.planDao().runTargets().all{it.weightValue=="75" && it.rir==2.0}})
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("actual-rir"));ui.onNodeWithTag("actual-rir").assertTextEquals("Actual RIR (optional)","");capture("load-advice-actual-override")
        scroll("logger-list","Suggested weight · Use / Change");ui.onNodeWithText("Suggested weight · Use / Change",substring=false).performClick();settle{model.loadAdviceRows.value.single().hasActual}
        ui.onNodeWithTag("load-advice-list").performScrollToNode(hasTestTag("use-load:1"));ui.onNodeWithTag("use-load:1").assertIsNotEnabled();capture("load-advice-after-logging")
        ui.onNodeWithText("Close",substring=false).performClick()
        File(instrument.targetContext.filesDir,"load-advice-restart.json").writeText(runBlocking{BackupCodec.encode(WorkoutRepository(db).backup())})
    }
}
class LoadAdviceRestartTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun preferencesTargetsActualEffortAndSessionLockSurviveForceStop(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;assumeTrue(context.packageName=="com.example.fitnotes.preview.photos")
        lateinit var model:FitNotesViewModel;ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]};ui.waitUntil(30000){!model.data.value.loading && model.loadProfiles.value.isNotEmpty()}
        val expected=BackupCodec.decode(File(context.filesDir,"load-advice-restart.json").readText());val actual=runBlocking{WorkoutRepository(model.photoRepository.database).backup()}
        assertEquals(expected.loadProfiles,actual.loadProfiles);assertEquals(expected.setLoadContexts,actual.setLoadContexts);assertEquals(expected.sessionLoadLocks,actual.sessionLoadLocks);assertEquals(expected.runTargets,actual.runTargets);assertEquals(expected.sets,actual.sets);assertEquals(expected.programs,actual.programs)
    }
}
