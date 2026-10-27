package com.example.fitnotes

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Fixtures may only replace data in the separately installed synthetic preview. */
class ProgressUsabilityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val longName="Chin Up with a controlled lowering phase and a comfortable range of motion"
    private fun waitText(value:String)=ui.waitUntil(30000){ui.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty()}
    private fun capture(name:String) {
        val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();android.os.SystemClock.sleep(350)
        val bitmap=i.uiAutomation.takeScreenshot()!!
        val phase=InstrumentationRegistry.getArguments().getString("phase")?:"after"
        File(i.targetContext.filesDir,"progress-$phase-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Before fun seed() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName=="com.example.fitnotes.preview.photos")
        context.getSharedPreferences("strength-progress",0).edit().clear().putString("exercise","exercise:1").putString("range","All").putString("metric","WEIGHT").commit()
        val today=todayDate();val names=listOf(longName,"Bodyweight Squat","Assisted Pull Up","Never performed catalogue entry")+(5..35).map{"Recorded exercise $it"}
        val exercises=names.mapIndexed{i,name->Exercise((i+1).toLong(),name,name.lowercase())}
        val workouts=(1..4).map { n -> Workout(n.toLong(),dayMillis(moveDay(today,-n))+12*3600000L,dayMillis(moveDay(today,-n))+13*3600000L,title="Results $n",finished=true) }
        val entries=workouts.map{WorkoutExercise(it.id,it.id,1,0)}
        val sets=entries.flatMap{entry->listOf(8,8,6).mapIndexed{index,reps->WorkoutSet(entry.id*10+index,entry.id,if(index==0)70.0 else 80.0,reps,index,loadMode=LoadMode.EXTERNAL)}}
        val date=moveDay(today,-1)
        val source=HistoryImport("fixture","Synthetic history","fixture.csv",1000,"kg",0,"","{}")
        val imports=listOf(ImportedWorkout("same-day","fixture",date,null,null,"Imported notes"))+(5..35).map{ImportedWorkout("record-$it","fixture",moveDay(today,-it),null,null,"")}
        val importedSets=listOf(ImportedSet("date-only-set","same-day","original-id",0,longName,"Strength",80.0,3,null,null,null,"", "{}"))+(5..35).map{ImportedSet("row-$it","record-$it","id-$it",0,names[it-1],"Strength",20.0,8,null,null,null,"","{}")}
        val ready=java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=exercises,workouts=workouts,entries=entries,sets=sets,imports=listOf(source),importedWorkouts=imports,importedSets=importedSets,exerciseAliases=listOf(ExerciseAlias(1,1,"Chip Up","chip up")))
            model.confirmRestore{ready.set(true)}
        }
        ui.waitUntil(30000){ready.get()};waitText("Progress")
    }
    @Test fun selectionSearchPointDetailsAndWorkoutReturnPreserveProgress() {
        ui.onNode(hasText("Progress",substring=false) and hasClickAction()).performClick();waitText("Heaviest set")
        ui.onNodeWithTag("strength-progress").performScrollToIndex(0);capture("screen")
        ui.onNodeWithText(longName+" ▾",substring=false).performClick();waitText("Choose exercise");capture("picker")
        ui.onNodeWithText("Never performed catalogue entry").assertDoesNotExist()
        ui.onNodeWithTag("progress-exercise-search").performTextInput("  CHIP   UP ")
        ui.onNodeWithText(longName,substring=false).assertIsDisplayed();capture("picker-keyboard")
        ui.onNodeWithText(longName,substring=false).performClick();ui.onNodeWithText("Choose exercise").assertDoesNotExist()
        ui.onNodeWithTag("volume-chart").performTouchInput{click(androidx.compose.ui.geometry.Offset(width-15f,height*.45f))}
        waitText("View workout");assertEquals(2,ui.onAllNodesWithText("View workout").fetchSemanticsNodes().size);capture("same-day-details")
        ui.onAllNodesWithText("View workout")[0].performClick();waitText("Workout")
        ui.onNodeWithContentDescription("Back").performClick();waitText("Heaviest set");ui.onNodeWithText(longName+" ▾").assertExists()
        ui.onNodeWithText("1M",substring=false).performClick()
        ui.activityRule.scenario.recreate();waitText("Progress");ui.onNode(hasText("Progress",substring=false) and hasClickAction()).performClick();waitText("Heaviest set")
        assertEquals("1M",InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("strength-progress",0).getString("range",null))
    }
    @Test fun openedCurrentPlanPreviewsOnceWithoutStartingAndPreservesTargets() {
        val spec=PlanSpec("Opened file","",45,"",listOf(PlanPrescription(longName,listOf(PlanSetTarget(8,12,PlanWeight("80","kg"),90),PlanSetTarget(6,8)))) ,planFingerprint("fixture"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(File(context.cacheDir,"handoff-exports").apply{mkdirs()},"fixture.fitnotesplan").apply{writeText(WorkoutPlanFormat.encode(spec))}
        val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".handoff-files",file)
        ui.activityRule.scenario.onActivity{model.openPlanFile(uri)}
        waitText("Save plan");capture("opened-plan")
        ui.runOnIdle{assertEquals(spec.exercises,model.planEdit.value!!.validated().spec.exercises)}
        val db=model.photoRepository.database;assertNull(runBlocking{db.dao().active()})
        ui.onNodeWithText("Save plan",substring=false).performClick();waitText("Start workout")
        val count=runBlocking{db.planDao().plans().size};assertEquals(1,count);assertNull(runBlocking{db.dao().active()})
        ui.activityRule.scenario.onActivity{model.openPlanFile(uri)}
        ui.waitForIdle();assertEquals(1,runBlocking{db.planDao().plans().size});assertNull(runBlocking{db.dao().active()})
    }
}
