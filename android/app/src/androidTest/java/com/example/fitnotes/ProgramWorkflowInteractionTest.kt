package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException

class ProgramWorkflowInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val db get()=model.photoRepository.database
    @Before fun seed(){
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        val ready=java.util.concurrent.atomic.AtomicBoolean(false);val old=System.currentTimeMillis()-86400000
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Bench Press","bench press")),workouts=listOf(Workout(1,old,old+3600000,"Keep actual history",finished=true)),entries=listOf(WorkoutExercise(1,1,1,0)),sets=listOf(WorkoutSet(1,1,40.0,8,0)));model.confirmRestore{ready.set(true)}}
        ui.waitUntil(30000){ready.get() && !model.busy.value && !model.data.value.loading && !model.data.value.imported.loading};ui.mainClock.advanceTimeBy(10000)
    }
    private fun text(id:String)="""{"id":"$id","name":"Converted $id","weeks":3,"days":[{"id":"a","name":"Bench A","exercises":[{"name":"Bench Press","sets":3,"reps":"8-10","rir":2}]}]}"""
    private fun capture(name:String){ui.waitForIdle();instrument.waitForIdleSync();android.os.SystemClock.sleep(400);val b=instrument.uiAutomation.takeScreenshot()!!;val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty();File(instrument.targetContext.filesDir,"$name$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun shown(){ui.waitUntil(10000){ui.onAllNodesWithTag("program-import-text").fetchSemanticsNodes().isNotEmpty()}}
    @Test fun newConversionOpensAfterRecreationWithAFreshModelAndRetainedUiState(){
        val before=runBlocking{WorkoutRepository(db).backup()}
        ui.runOnIdle{model.receiveProgramShare(text("first"))};shown();ui.onNodeWithText("Cancel",substring=false).performClick()
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("program-import-text").assertDoesNotExist()
        val old=model
        // Simulate the process-recreation boundary: persistent Room + saved
        // Compose UI state, with a new ViewModel and request sequence. Ordinary
        // rotation alone retains the old model and must not replay a closed import.
        ui.activityRule.scenario.onActivity{it.viewModelStore.clear()};ui.activityRule.scenario.recreate()
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]}
        assertNotSame(old,model);ui.waitUntil(30000){!model.data.value.loading && !model.data.value.imported.loading}
        assertEquals(0L,model.programImportRequestVersion.value)
        ui.runOnIdle{model.receiveProgramShare(text("second"))};assertEquals(text("second"),model.programImportText.value);shown()
        ui.onNodeWithTag("program-import-text").assertTextContains(text("second"));capture("program-workflow-restored-conversion")
        ui.onNodeWithText("Cancel",substring=false).performClick();ui.runOnIdle{model.receiveProgramShare(text("third"))};shown();ui.onNodeWithTag("program-import-text").assertTextContains(text("third"))
        val after=runBlocking{WorkoutRepository(db).backup()};assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun liveCatalogRefreshAndDownloadCachePublicDataWithoutChangingTraining(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowCatalogNetwork")=="true")
        File(instrument.targetContext.filesDir,"program-catalog").deleteRecursively()
        val before=runBlocking{WorkoutRepository(db).backup()}
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Explore programs / Import",substring=false));ui.onNodeWithText("Explore programs / Import",substring=false).performClick();ui.onNodeWithText("Workout Library",substring=false).performClick()
        ui.waitUntil(30000){model.catalog.value!=null && !model.catalogBusy.value}
        ui.onNodeWithText("Refresh catalog",substring=false).performClick();ui.waitUntil(60000){!model.catalogBusy.value}
        assertNull(model.catalogError.value,model.catalogError.value);assertNotNull(model.catalog.value!!.refreshedAt)
        val e=model.catalog.value!!.manifest.entries.single{it.id=="strength-3day"};assertFalse(e.id in model.catalog.value!!.available)
        ui.onNodeWithTag("catalog-list").performScrollToNode(hasTestTag("catalog-open:${e.id}"));ui.onNodeWithTag("catalog-open:${e.id}").performClick();ui.waitUntil(60000){!model.catalogBusy.value}
        assertNull(model.catalogError.value,model.catalogError.value);assertEquals(e.id,model.catalogProgram.value!!.id);capture("catalog-live-public-download")
        val offline=ProgramCatalogRepository(instrument.targetContext,CatalogTransport{_,_->throw IOException("No network permitted for cache proof")})
        val cached=runBlocking{offline.load()};assertTrue(e.id in cached.available);assertEquals(model.catalogProgram.value!!.json,runBlocking{offline.program(e,cached.manifest)}.json)
        val after=runBlocking{WorkoutRepository(db).backup()};assertEquals(before.copy(exportedAt=after.exportedAt),after)
        File(instrument.targetContext.filesDir,"catalog-live-restart.json").writeText(BackupCodec.encode(after))
    }
}
class CatalogLiveRestartTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun liveDownloadedVersionRemainsAvailableWithTheNetworkOffAfterForceStop(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyDownloadedCatalogRestart")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext;assumeTrue(context.packageName=="com.example.fitnotes.preview.photos")
        lateinit var model:FitNotesViewModel;ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]};ui.waitUntil(30000){!model.data.value.loading}
        val repo=ProgramCatalogRepository(context,CatalogTransport{_,_->error("Offline restart must use cached bytes")})
        val snapshot=runBlocking{repo.load()};assertNotNull(snapshot.refreshedAt);assertEquals(4,snapshot.available.size)
        val entry=snapshot.manifest.entries.single{it.id=="strength-3day"};assertEquals(entry.id,runBlocking{repo.program(entry,snapshot.manifest)}.id)
        val expected=BackupCodec.decode(File(context.filesDir,"catalog-live-restart.json").readText());val actual=runBlocking{WorkoutRepository(model.photoRepository.database).backup()};assertEquals(expected.copy(exportedAt=actual.exportedAt),actual)
    }
}
