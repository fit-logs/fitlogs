package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class CatalogInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val db get()=model.photoRepository.database
    private val manifest get()=CatalogValidator.manifest(instrument.targetContext.assets.open("catalog/manifest.json").bufferedReader().use{it.readText()})
    @Before fun seed(){
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        File(instrument.targetContext.filesDir,"program-catalog").deleteRecursively()
        val ready=java.util.concurrent.atomic.AtomicBoolean(false);val old=System.currentTimeMillis()-86400000
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=manifest.exercises.values.mapIndexed{i,name->Exercise(i+1L,name,normaliseExerciseName(name))},workouts=listOf(Workout(1,old,old+3600000,"Keep actual history",finished=true)),entries=listOf(WorkoutExercise(1,1,1,0)),sets=listOf(WorkoutSet(1,1,40.0,8,0)));model.confirmRestore{ready.set(true)}}
        ui.waitUntil(30000){ready.get() && !model.busy.value && !model.data.value.loading && !model.data.value.imported.loading}
        // Restore feedback overlays the bottom Train action on compact screens.
        ui.mainClock.advanceTimeBy(10000)
    }
    private fun settle(test:()->Boolean={true}){ui.waitUntil(30000){!model.busy.value && !model.catalogBusy.value && test()};ui.waitForIdle()}
    private fun scroll(tag:String,text:String){ui.onNodeWithTag(tag).performScrollToNode(hasText(text,substring=false))}
    private fun click(text:String){scroll("catalog-list",text);ui.onNodeWithText(text,substring=false).performClick()}
    private fun choice(label:String,current:String,next:String){click("$label: $current ▾");ui.onNodeWithText(next,substring=false).performClick()}
    private fun open(){scroll("train-screen","Explore programs / Import");ui.onNodeWithText("Explore programs / Import",substring=false).performClick();ui.onNodeWithText("Workout Library",substring=false).performClick();settle{model.catalog.value!=null}}
    private fun search(text:String){ui.onNodeWithTag("catalog-list").performScrollToNode(hasTestTag("catalog-search"));ui.onNodeWithTag("catalog-search").performTextReplacement(text);Espresso.closeSoftKeyboard()}
    private fun preview(id:String){ui.onNodeWithTag("catalog-list").performScrollToNode(hasTestTag("catalog-open:$id"));ui.onNodeWithTag("catalog-open:$id").performClick();settle{model.catalogProgram.value!=null}}
    private fun capture(name:String){ui.waitForIdle();instrument.waitForIdleSync();android.os.SystemClock.sleep(400);val b=instrument.uiAutomation.takeScreenshot()!!;val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty();File(instrument.targetContext.filesDir,"$name$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun offlineStarterFiltersPreviewInstallActivateAndStartPreserveActualHistory(){
        val original=runBlocking{db.dao().sets()};open();assertEquals(3,model.catalog.value!!.available.size);assertNull(model.catalog.value!!.refreshedAt)
        search("DUMBBELL");click("Filters");choice("Experience","Any","beginner");choice("Days per week","Any","3");choice("Maximum minutes","Any","45")
        click("dumbbells");scroll("catalog-list","No programs match. Clear or broaden the filters.");capture("catalog-filter-empty")
        click("bench");scroll("catalog-list","1 program");click("Hide filters");preview("dumbbell-home-3day")
        scroll("catalog-list","3. Home C");capture("catalog-offline-preview")
        assertEquals(3,model.catalogProgram.value!!.document.getJSONArray("days").length());assertEquals(original,runBlocking{db.dao().sets()})
        ui.onNodeWithTag("review-catalog-install").performClick();settle{model.programImportDraft.value!=null}
        assertTrue(model.programImportDraft.value!!.days.flatMap{it.plan.exercises}.all{it.exerciseId!=null});capture("catalog-install-review")
        ui.onNodeWithTag("save-imported-program").performClick();settle{model.programs.value.size==1 && model.programImportDraft.value==null}
        val id=model.programs.value.single().program.id;assertEquals(3,model.programs.value.single().days.size);assertEquals(original,runBlocking{db.dao().sets()})
        ui.onNodeWithTag("program-list").performScrollToNode(hasTestTag("program:$id"));ui.onNodeWithTag("program:$id").performClick();scroll("program-list","Set active / Resume program");ui.onNodeWithText("Set active / Resume program",substring=false).performClick();settle{model.programs.value.single().program.status=="active"};capture("catalog-installed-copy")
        ui.onNodeWithText("Close",substring=false).performClick();ui.mainClock.advanceTimeBy(10000);scroll("train-screen","Start next workout");ui.onNodeWithText("Start next workout",substring=false).performClick();settle{model.data.value.active!=null}
        assertEquals(original,runBlocking{db.dao().sets()});assertTrue(model.data.value.active!!.entries.all{it.sets.isEmpty()});assertTrue(runBlocking{db.planDao().runTargets().all{it.weightValue==null && it.rir==3.0}});capture("catalog-started-empty-targets")
        File(instrument.targetContext.filesDir,"catalog-restart.json").writeText(runBlocking{BackupCodec.encode(WorkoutRepository(db).backup())})
    }
    @Test fun offlineRefreshAndDuplicateReviewNeverReplaceInstalledEditsOrHistory(){
        val e=manifest.entries.single{it.id=="full-body-express-2day"};val p=CatalogValidator.program(instrument.targetContext.assets.open("catalog/"+e.path).bufferedReader().use{it.readText()},e,manifest)
        runBlocking{val draft=PortableProgramParser.draft(p,db.dao().exercises(),emptyList(),emptySet());val id=(ProgramImportRepository(db).save(draft) as ProgramImportResult.Saved).id;ProgramImportRepository(db).editIdentity(id,"My edited express","Keep my notes")}
        settle{model.programs.value.size==1};val before=runBlocking{WorkoutRepository(db).backup()}
        open();ui.onNodeWithText("Refresh catalog",substring=false).performClick();settle{model.catalogError.value!=null};assertEquals(3,model.catalog.value!!.available.size);assertNull(model.catalog.value!!.refreshedAt);capture("catalog-offline-refresh")
        preview(e.id);ui.onNodeWithTag("review-catalog-install").performClick();settle{model.programImportDraft.value!=null};ui.onNodeWithTag("save-imported-program").performClick();settle{model.duplicateProgramId.value!=null}
        ui.onNodeWithText("Program already installed",substring=false).assertExists();capture("catalog-duplicate-keeps-edits")
        ui.onNode(hasText("Cancel",substring=false) and hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("Program already installed",substring=false)))).performClick()
        val after=runBlocking{WorkoutRepository(db).backup()};assertEquals(before.copy(exportedAt=after.exportedAt),after);assertEquals("My edited express",model.programs.value.single().program.title)
    }
}
class CatalogRestartTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun localProgramAndUntouchedHistorySurviveOfflineForceStop(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;assumeTrue(context.packageName=="com.example.fitnotes.preview.photos")
        lateinit var model:FitNotesViewModel;ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]};ui.waitUntil(30000){!model.data.value.loading && model.programs.value.size==1}
        val expected=BackupCodec.decode(File(context.filesDir,"catalog-restart.json").readText());val actual=runBlocking{WorkoutRepository(model.photoRepository.database).backup()}
        assertEquals(expected.programs,actual.programs);assertEquals(expected.programDays,actual.programDays);assertEquals(expected.plannedSets,actual.plannedSets);assertEquals(expected.runTargets,actual.runTargets);assertEquals(expected.sets,actual.sets);assertEquals(expected.workouts,actual.workouts)
        assertEquals(3,runBlocking{ProgramCatalogRepository(context,CatalogTransport{_,_->error("Offline restart must not fetch")}).load()}.available.size)
    }
}
