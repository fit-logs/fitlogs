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

/** Synthetic fixtures only, with a separate app identity. Never restores the user's application. */
class RunningInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val today get()=LocalDate.now()
    private fun source(id:String,day:LocalDate,metres:Double?=5000.0):HealthCandidate {
        val start=day.atTime(18,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return HealthCandidate(HealthSession(healthKey(GARMIN_PACKAGE,id),null,id,GARMIN_PACKAGE,"Garmin Connect",id,1,start,start,start,start+2100000,null,null,
            "running",56,"Evening run","Easy lap of the park",metres,GARMIN_PACKAGE,"Read","Read",runData=RunSourceData(timerSeconds=1800.0,distanceVerified=metres!=null,distanceEvidence="Synthetic complete interval").json()),distanceRead=true)
    }
    private fun seed(empty:Boolean=false) {
        assumeTrue("Synthetic tests must use the separate preview app",context.packageName=="com.example.fitnotes.preview.photos")
        val s=source("existing",today.minusDays(3)).session.copy(workoutId=1)
        val backup=if(empty)Backup(exercises=emptyList(),workouts=emptyList(),entries=emptyList(),sets=emptyList())else Backup(exercises=emptyList(),
            workouts=listOf(Workout(1,0,sessionDate=runLocalDate(s.start),finished=true,activityType="running"),
                Workout(2,0,title="Weekend trail run",sessionDate=today.minusDays(10).toString(),finished=true,activityType="running",distance=7.4,distanceUnit="km",durationSeconds=2800.0),
                Workout(3,0,title="A relaxed treadmill run after work with a very long descriptive title",sessionDate=today.minusDays(1).toString(),finished=true,activityType="running",durationSeconds=1200.0),
                Workout(4,0,title="Climbing / bouldering",sessionDate=today.minusDays(2).toString(),finished=true,activityType="climbing",durationSeconds=2700.0),
                Workout(5,0,title="Gym session",sessionDate=today.toString(),finished=true)),entries=emptyList(),sets=emptyList(),healthSessions=listOf(s),
            imports=listOf(HistoryImport("fixture","Synthetic FitNotes CSV","fixture",1000,"kg",0,"","{}")),importedWorkouts=listOf(ImportedWorkout("mixed","fixture",today.minusDays(7).toString(),notes="Mixed workout")),
            importedSets=listOf(ImportedSet("run","mixed","source-run",1,"Running (Outdoor)","Cardio",null,null,3.0,"km",1100.0,"Park loop","{}"),
                ImportedSet("strength","mixed","source-strength",0,"Squat","Legs",80.0,8,null,null,null,"Keep strength","{}")))
        context.getSharedPreferences("running-progress",0).edit().clear().commit()
        context.getSharedPreferences("history-filter",0).edit().clear().commit()
        val ready=java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false);SystemBackupStore(context).enable(false)
            model.pendingRestore.value=backup;model.confirmRestore{ready.set(true)}
        }
        ui.waitUntil(30000){ready.get()&&!model.busy.value&&!model.data.value.imported.loading}
        ui.waitUntil(10000){ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty()}
    }
    private fun capture(name:String) {
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync();android.os.SystemClock.sleep(350)
        val image=instrumentation.uiAutomation.takeScreenshot()!!;val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(context.filesDir,"synthetic-running-$name$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun filterHistory(activity:String) {
        ui.onNodeWithTag("history-filter").performClick();ui.onNodeWithTag("history-filter:$activity").performClick()
    }
    private fun openRunningHistory(){ui.onNode(hasText("History")and hasClickAction()).performClick();filterHistory("running")}
    @Test fun allActivitiesUseOneChronologicalListAndMixedWorkoutsRemainTogether() {
        seed();ui.onNode(hasText("History")and hasClickAction()).performClick()
        ui.onNodeWithTag("history-session:local:5").assertIsDisplayed()
        ui.onNodeWithTag("history-session:local:3").assertIsDisplayed()
        ui.onNodeWithTag("history-session:local:4").assertIsDisplayed();capture("unified-history")
        filterHistory("climbing");ui.onNodeWithTag("history-session:local:4").assertIsDisplayed()
        ui.onNodeWithTag("history-session:local:3").assertDoesNotExist();capture("climbing-filter")
        filterHistory("strength");ui.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-session:import:mixed"))
        ui.onNodeWithTag("history-session:import:mixed").assertIsDisplayed();capture("gym-filter-mixed")
        filterHistory("running");ui.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-session:import:mixed"))
        ui.onNodeWithTag("history-session:import:mixed").assertIsDisplayed();capture("running-filter-mixed")
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("history-filter").assertTextContains("Running")
        ui.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-session:local:1"))
        ui.onNodeWithTag("history-session:local:1").performClick();ui.onNodeWithTag("run-details").assertExists()
        ui.onAllNodesWithContentDescription("Back").onLast().performClick();ui.onNodeWithTag("history-filter").assertTextContains("Running")
        assertEquals(4,runningRecords(model.data.value).size)
        assertEquals(2,runBlocking{model.photoRepository.database.importedDao().sets().size})
    }
    @Test fun historyDetailsEditingAndProgressPreserveMixedWorkoutsAndSelections() {
        seed();openRunningHistory();ui.onNodeWithTag("history-session:local:3").assertIsDisplayed();capture("history")
        ui.onNodeWithTag("history-session:local:1").performScrollTo().performClick()
        ui.onAllNodesWithText("Timer pace",substring=false).onLast().assertIsDisplayed();ui.onNodeWithText("6:00 /km",substring=false).assertIsDisplayed();capture("details")
        ui.onAllNodesWithContentDescription("More actions").onLast().performClick();ui.onNodeWithText("Edit run",substring=false).performClick()
        ui.onNodeWithText("Recorded distance (km)",substring=false).performClick().performTextReplacement("5.25")
        ui.waitUntil(10000) { ui.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) };capture("edit-keyboard")
        Espresso.closeSoftKeyboard();ui.onNodeWithText("Save changes",substring=false).performClick()
        ui.waitUntil(15000){!model.busy.value && runningRecords(model.data.value).any{it.key=="local:1"&&it.distanceMetres==5250.0}}
        ui.onAllNodesWithContentDescription("Back").onLast().performClick()
        ui.onNode(hasText("Progress")and hasClickAction()).performClick();ui.onNodeWithText("Running",substring=false).performClick()
        ui.onNodeWithTag("running-week-chart").assertIsDisplayed();capture("progress")
        ui.onNodeWithText("All",substring=false).performClick();ui.activityRule.scenario.recreate();ui.onNodeWithTag("running-progress").assertExists();ui.onNodeWithText("All",substring=false).assertIsSelected()
        ui.onNode(hasText("History")and hasClickAction()).performClick();filterHistory("running")
        ui.onNodeWithTag("history-session:local:3").performClick();capture("partial-details")
        assertEquals(2,runBlocking{model.photoRepository.database.importedDao().sets().size})
    }
    @Test fun importPreviewAndRetryUseActualCountsAndCreateNoDuplicates() {
        seed();val review=source("review",today.minusDays(7),3000.0).let{it.copy(session=it.session.copy(runData=RunSourceData(timerSeconds=1100.0,distanceVerified=true).json()))}
        val candidates=listOf(source("new-one",today.minusDays(20)),source("new-two",today.minusDays(24),null),source("existing",today.minusDays(3)),review)
        val preview=RunImportPreview(candidates,setOf(candidates[2].session.key),emptySet(),mapOf(review.session.key to listOf(runningRecords(model.data.value).single{it.importedSetId=="run"})))
        ui.onNodeWithContentDescription("Settings").performClick();ui.onNodeWithText("Health sync",substring=false).performClick();ui.onNodeWithText("Import running history",substring=false).performClick()
        ui.runOnIdle{model.runImport.value=RunImportState(preview=preview,message="Review the preview, then import.",coverage="Synthetic fixtures for layout and import verification.")}
        ui.onNodeWithTag("running-import-preview").performScrollToNode(hasText("4 runs found",substring=false));ui.onNodeWithText("4 runs found",substring=false).assertIsDisplayed();capture("import-preview")
        ui.onNodeWithTag("running-import-preview").performScrollToNode(hasText("Same run",substring=false));ui.onNodeWithText("Same run",substring=false).performClick();capture("import-review")
        ui.onNodeWithText("Import runs",substring=false).performClick()
        ui.waitUntil(30000){!model.busy.value&&model.runImport.value.message.startsWith("Imported 2 runs; 1 already present.")}
        ui.onNodeWithTag("running-import-preview").performScrollToNode(hasText("Imported 2 runs; 1 already present. 1 linked.",substring=false));ui.onNodeWithText("Imported 2 runs; 1 already present. 1 linked.",substring=false).assertIsDisplayed();capture("import-result")
        ui.runOnIdle{model.runImport.value=RunImportState(preview=preview.copy(knownKeys=candidates.map{it.session.key}.toSet(),matches=emptyMap()))};ui.onNodeWithText("Import runs",substring=false).performClick()
        ui.waitUntil(30000){!model.busy.value&&model.runImport.value.message.startsWith("Imported 0 runs; 4 already present.")}
        assertEquals(6,runningRecords(model.data.value).size)
    }
    @Test fun emptyRunningHistoryAndProgressHaveUsefulEmptyStates() {
        seed(true);openRunningHistory();ui.onNodeWithText("No recorded activities of this type yet.").assertIsDisplayed();capture("empty-history")
        ui.onNode(hasText("Progress")and hasClickAction()).performClick();ui.onNodeWithText("Running",substring=false).performClick()
        ui.onNodeWithText("No records in this period").assertIsDisplayed();ui.onNodeWithText("All time").assertIsDisplayed();capture("empty-progress")
    }
}
