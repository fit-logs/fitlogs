package com.example.fitnotes

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
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

class ProgramImportInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val db get()=model.photoRepository.database
    private val fixture get()=instrument.targetContext.assets.open("examples/four-day.json").bufferedReader().use{it.readText()}
    @Before fun seed() {
        assumeTrue(instrument.targetContext.packageName=="com.example.fitnotes.preview.photos")
        val ready=java.util.concurrent.atomic.AtomicBoolean(false);val old=System.currentTimeMillis()-86400000
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false)
            model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Bench Press","bench press"),Exercise(2,"Squat","squat"),Exercise(3,"Barbell Row","barbell row"),Exercise(4,"Seated Cable Row","seated cable row")),workouts=listOf(Workout(10,old,old+3600000,"Keep actual history")),entries=listOf(WorkoutExercise(10,10,1,0)),sets=listOf(WorkoutSet(10,10,30.0,8,0)),exerciseAliases=listOf(ExerciseAlias(1,1,"Flat bench","flat bench"),ExerciseAlias(2,3,"Row","row"),ExerciseAlias(3,4,"Row","row")));model.confirmRestore{ready.set(true)}}
        ui.waitUntil(30000){ready.get() && !model.busy.value && !model.data.value.loading && !model.data.value.imported.loading};ui.mainClock.advanceTimeBy(10000)
    }
    private fun capture(name:String){ui.waitForIdle();instrument.waitForIdleSync();android.os.SystemClock.sleep(400);val b=instrument.uiAutomation.takeScreenshot()!!;val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty();File(instrument.targetContext.filesDir,"$name$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun settle(test:()->Boolean={true}){ui.waitUntil(20000){!model.busy.value && test()};ui.mainClock.advanceTimeBy(10000)}
    private fun open(){ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Explore programs / Import",substring=false));ui.onNodeWithText("Explore programs / Import",substring=false).performClick();ui.onNodeWithText("Import program",substring=false).performClick()}
    private fun paste(text:String=fixture){ui.onNodeWithTag("program-import-list").performScrollToNode(hasTestTag("program-import-text"));ui.onNodeWithTag("program-import-text").performTextReplacement(text);Espresso.closeSoftKeyboard();ui.onNodeWithText("Preview program",substring=false).performClick();settle{model.programImportDraft.value!=null || model.programImportError.value!=null}}
    private fun resolveRow(){
        ui.onNodeWithTag("program-import-list").performScrollToNode(hasTestTag("resolve-program-exercise:upper-a:1"));ui.onNodeWithTag("resolve-program-exercise:upper-a:1").performClick()
        ui.onNodeWithTag("exercise-search").performTextReplacement("Barbell Row");Espresso.closeSoftKeyboard();ui.onNode(hasText("Barbell Row",substring=false) and !hasSetTextAction()).performClick();ui.onNodeWithText("Use for strength plan",substring=false).performClick()
    }
    @Test fun previewFourDaysResolveAliasesAndUnknownsEditThenSaveOneProgramAndStartOffline() {
        val actual=runBlocking{db.dao().sets()};open();paste();assertEquals(4,model.programImportDraft.value!!.days.size)
        assertEquals(1L,model.programImportDraft.value!!.days[0].plan.exercises[0].exerciseId);ui.onNodeWithTag("save-imported-program").assertIsNotEnabled();capture("program-import-unresolved")
        resolveRow()
        ui.onNodeWithTag("program-import-list").performScrollToNode(hasTestTag("resolve-program-exercise:lower-b:0"));ui.onNodeWithTag("resolve-program-exercise:lower-b:0").performClick()
        ui.onNodeWithTag("exercise-search").performTextReplacement("Custom carry");Espresso.closeSoftKeyboard();ui.onNodeWithText("Create custom exercise",substring=false).performClick();ui.onNodeWithText("Use new exercise",substring=false).performClick()
        assertEquals(4,runBlocking{db.dao().exercises().size});assertEquals(actual,runBlocking{db.dao().sets()})
        ui.onNodeWithTag("program-import-list").performScrollToNode(hasTestTag("import-program-name"));ui.onNodeWithTag("import-program-name").performTextReplacement("My four-day program");Espresso.closeSoftKeyboard()
        val move=hasText("Move day down",substring=false) and hasAnyAncestor(hasTestTag("import-day:upper-a"));ui.onNodeWithTag("program-import-list").performScrollToNode(move);ui.onNode(move).performClick()
        assertEquals(listOf("lower-a","upper-a","upper-b","lower-b"),model.programImportDraft.value!!.days.map{it.key});capture("program-import-four-days")
        ui.onNodeWithTag("save-imported-program").assertIsEnabled().performClick();settle{model.programs.value.size==1 && model.programImportDraft.value==null}
        val p=model.programs.value.single();assertEquals(4,p.days.size);assertEquals(4,runBlocking{db.planDao().plans().size});assertEquals(1,runBlocking{db.dao().workouts().size});assertEquals(actual,runBlocking{db.dao().sets()});assertEquals(5,runBlocking{db.dao().exercises().size})
        ui.onNodeWithTag("program-list").performScrollToNode(hasTestTag("program:${p.program.id}"));ui.onNodeWithTag("program:${p.program.id}").performClick()
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Edit program details",substring=false));ui.onNodeWithText("Edit program details",substring=false).performClick();ui.onNodeWithTag("edit-program-title").performTextReplacement("Edited local program");Espresso.closeSoftKeyboard();ui.onNodeWithText("Save details",substring=false).performClick();settle{model.programs.value.single().program.title=="Edited local program"}
        ui.onNodeWithTag("program-list").performScrollToNode(hasText("Set active / Resume program",substring=false));ui.onNodeWithText("Set active / Resume program",substring=false).performClick();settle{model.programs.value.single().program.status=="active"};capture("program-import-installed")
        ui.onNodeWithText("Close",substring=false).performClick();ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Start next workout",substring=false));ui.onNodeWithText("Start next workout",substring=false).performClick();settle{model.data.value.active!=null}
        val active=model.data.value.active!!;assertEquals("Lower A",active.workout.title);assertEquals(actual,runBlocking{db.dao().sets()});assertTrue(active.entries.all{it.sets.isEmpty()})
        val targets=runBlocking{db.planDao().runTargets()};assertTrue(targets.all{it.weightValue==null});assertEquals(0,model.programs.value.single().program.nextIndex);capture("program-import-started-targets")
        File(instrument.targetContext.filesDir,"program-import-restart.json").writeText(runBlocking{BackupCodec.encode(WorkoutRepository(db).backup())})
    }
    @Test fun duplicateAndMalformedInputDoNotOverwriteWhilePdfFileAndUrlClearlyFallBackToPaste() {
        val source=PortableProgramParser.parse(fixture);val initial=PortableProgramParser.draft(source,runBlocking{db.dao().exercises()},runBlocking{db.trainingDao().aliases()},emptySet())
        val resolved=initial.copy(days=initial.days.map{day->day.copy(plan=day.plan.copy(exercises=day.plan.exercises.map{e->when(e.sourceName){"Row"->e.copy(exerciseId=3);"Custom carry"->e.copy(createName="Custom carry");else->e}}))})
        runBlocking{ProgramImportRepository(db).save(resolved)};settle{model.programs.value.size==1 && model.data.value.exercises.size==5};val before=runBlocking{WorkoutRepository(db).backup()}
        open();paste();resolveRow();ui.onNodeWithTag("save-imported-program").performClick();settle{model.duplicateProgramId.value!=null};ui.onNodeWithText("Program already installed",substring=false).assertExists();capture("program-import-duplicate")
        ui.onNode(hasText("Cancel",substring=false) and hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("Program already installed",substring=false)))).performClick()
        ui.onNodeWithText("Edit pasted text",substring=false).performClick();paste("{\n\"schemaVersion\":1,\n\"days\": [");assertNotNull(model.programImportError.value);assertNull(model.programImportDraft.value)
        ui.onNodeWithTag("program-import-list").performScrollToNode(hasText("From URL",substring=false));ui.onNodeWithText("From URL",substring=false).performClick();ui.onNodeWithTag("program-source-url").performTextReplacement("javascript:bad");Espresso.closeSoftKeyboard();ui.onNodeWithText("Use link / Paste",substring=false).performClick();ui.onNodeWithText("Enter an http/https URL without embedded credentials.").assertExists()
        ui.onNodeWithTag("program-source-url").performTextReplacement("https://example.org/program");Espresso.closeSoftKeyboard();ui.onNodeWithText("Use link / Paste",substring=false).performClick();assertEquals("https://example.org/program",model.programImportSourceUrl.value)
        val directory=File(instrument.targetContext.cacheDir,"handoff-exports").apply{mkdirs()};val pdf=File(directory,"program-fallback.pdf").apply{writeText("%PDF-1.4\nSynthetic bounded fixture")}
        val pdfUri=FileProvider.getUriForFile(instrument.targetContext,instrument.targetContext.packageName+".handoff-files",pdf)
        ui.runOnIdle{model.readProgramFile(pdfUri)};settle{model.programImportHelp.value?.contains("PDF text extraction") == true};capture("program-import-pdf-fallback")
        val json=File(directory,"program-example.json").apply{writeText(fixture)};val uri=FileProvider.getUriForFile(instrument.targetContext,instrument.targetContext.packageName+".handoff-files",json)
        ui.runOnIdle{model.readProgramFile(uri)};settle{model.programImportText.value==fixture};assertNull(model.programImportDraft.value)
        val after=runBlocking{WorkoutRepository(db).backup()};assertEquals(before.copy(exportedAt=after.exportedAt),after)
        ui.onNodeWithText("Cancel",substring=false).performClick();assertTrue(model.programImportText.value.isBlank());pdf.delete();json.delete()
    }
    @Test fun androidTextAndUrlSharesOpenOnlyAReviewableImportWithoutSavingAnything() {
        val launchIntent=Intent(ui.activity.intent)
        val before=runBlocking{WorkoutRepository(db).backup()}
        ui.activityRule.scenario.onActivity{it.startActivity(Intent(Intent.ACTION_SEND).setClass(it,MainActivity::class.java).setType("text/plain").putExtra(Intent.EXTRA_TEXT,fixture))}
        ui.waitUntil(15000){model.programImportRequestVersion.value>0 && model.programImportText.value==fixture};ui.onNodeWithTag("program-import-list").assertExists();assertNull(model.programImportDraft.value);ui.onNodeWithText("Cancel",substring=false).performClick()
        ui.activityRule.scenario.onActivity{it.startActivity(Intent(Intent.ACTION_SEND).setClass(it,MainActivity::class.java).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://example.org/program"))}
        ui.waitUntil(15000){model.programImportSourceUrl.value=="https://example.org/program"};assertTrue(model.programImportText.value.isBlank());capture("program-import-shared-url")
        val after=runBlocking{WorkoutRepository(db).backup()};assertEquals(before.copy(exportedAt=after.exportedAt),after)
        ui.onNodeWithText("Cancel",substring=false).performClick()
        val sharedFile=File(instrument.targetContext.cacheDir,"handoff-exports/shared-program.json").apply{parentFile!!.mkdirs();writeText(fixture)}
        val sharedUri=FileProvider.getUriForFile(instrument.targetContext,instrument.targetContext.packageName+".handoff-files",sharedFile)
        ui.activityRule.scenario.onActivity{it.startActivity(Intent(Intent.ACTION_SEND).setClass(it,MainActivity::class.java).setType("application/json").putExtra(Intent.EXTRA_STREAM,sharedUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
        settle{model.programImportText.value==fixture};ui.onNodeWithTag("program-import-list").assertExists();assertNull(model.programImportDraft.value)
        ui.onNodeWithText("Cancel",substring=false).performClick();sharedFile.delete()
        // ActivityScenario identifies its launch by intent. The real share flow
        // updates Activity.intent, so restore only the harness identity to close.
        ui.activityRule.scenario.onActivity{it.intent=launchIntent}
    }
}
class ProgramImportRestartTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun importedDefinitionEditableProgramAndActiveEmptyTargetsSurviveForceStop(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;assumeTrue(context.packageName=="com.example.fitnotes.preview.photos")
        lateinit var model:FitNotesViewModel;ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java]};ui.waitUntil(30000){!model.data.value.loading && model.programs.value.size==1}
        val expected=BackupCodec.decode(File(context.filesDir,"program-import-restart.json").readText());val actual=runBlocking{WorkoutRepository(model.photoRepository.database).backup()}
        assertEquals(expected.programs,actual.programs);assertEquals(expected.programDays,actual.programDays);assertEquals(expected.programSessions,actual.programSessions);assertEquals(expected.plannedWorkouts,actual.plannedWorkouts);assertEquals(expected.workouts,actual.workouts);assertEquals(expected.sets,actual.sets);assertEquals(4,model.programs.value.single().days.size)
    }
}
