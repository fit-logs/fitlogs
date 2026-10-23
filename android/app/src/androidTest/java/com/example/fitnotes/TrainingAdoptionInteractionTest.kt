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

class TrainingAdoptionInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:FitNotesViewModel
    private val db get()=model.photoRepository.database
    private val block="""WORKOUT_PLAN_V2
{"title":"Synthetic table plan","warmUp":"Gentle movement and individual warm-up sets as needed.","exercises":[{"name":"Synthetic press","note":"Keep shoulders steady.","sets":[
{"repsMin":8,"repsMax":10,"weight":"40.5","unit":"kg","restSeconds":90},
{"repsMin":6,"repsMax":8,"weight":"45.25","unit":"kg","restSeconds":120},
{"repsMin":6,"repsMax":8,"weight":"47.5","unit":"kg","restSeconds":90}]}]}
END_WORKOUT_PLAN"""
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Fixtures require the isolated preview",context.packageName=="com.example.fitnotes.preview.activities")
        val ready=java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity{model=ViewModelProvider(it)[FitNotesViewModel::class.java];model.setAutomaticHealth(false);model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Synthetic press","synthetic press"),Exercise(2,"Synthetic press duplicate","synthetic press duplicate")),workouts=emptyList(),entries=emptyList(),sets=emptyList());model.confirmRestore{ready.set(true)}}
        ui.waitUntil(30000){ready.get()&&!model.busy.value&&!model.data.value.imported.loading}
        waitFor("Backup restored.");ui.waitUntil(10000){ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty()}
    }
    private fun waitFor(text:String){
        if(text=="Save set") { ui.waitUntil(30000){ui.onAllNodesWithTag("logger-list").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("logger-list").performScrollToNode(hasText(text)) }
        ui.waitUntil(30000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun capture(name:String) {
        val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();android.os.SystemClock.sleep(500)
        val image=i.uiAutomation.takeScreenshot() ?: return
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(i.targetContext.filesDir,"$name$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun rowButton(id:Long,text:String):SemanticsNodeInteraction {
        val matcher=hasText(text) and hasAnyAncestor(hasTestTag("run-target:$id"))
        val node=scrollLogger(matcher)
        ui.waitUntil(10000){runCatching{ui.onNode(matcher).assertIsEnabled()}.isSuccess}
        return node
    }
    private fun loggerNode(tag:String):SemanticsNodeInteraction {
        return scrollLogger(hasTestTag(tag))
    }
    // performScrollTo handles the nearest horizontal table scroller; also bring
    // its descendant into the vertical lazy-list viewport before a real tap.
    private fun scrollLogger(matcher:SemanticsMatcher):SemanticsNodeInteraction {
        val list=ui.onNodeWithTag("logger-list")
        list.performScrollToNode(matcher)
        val node=ui.onNode(matcher);node.performScrollTo()
        repeat(3) {
            val item=node.fetchSemanticsNode();val viewport=list.fetchSemanticsNode().boundsInRoot
            val delta=item.positionInRoot.y+item.size.height/2f-(viewport.top+viewport.height/2f)
            list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,delta)}
            ui.waitForIdle()
        }
        return node.assertIsDisplayed()
    }
    private fun field(label:String):SemanticsNodeInteraction {
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:$label"))
        return ui.onNodeWithTag("field:$label").performScrollTo()
    }
    private fun input(weight:String,reps:String) {
        field("Weight (kg)").performClick().performTextReplacement(weight)
        field("Reps").performClick().performTextReplacement(reps)
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("field:Weight (kg)").assertTextEquals(weight);ui.onNodeWithTag("field:Reps").assertTextEquals(reps)}.isSuccess}
    }
    private fun save() {
        Espresso.closeSoftKeyboard()
        ui.waitUntil(10000){!model.busy.value&&model.completionReady.value && runCatching{ui.onNodeWithText("Save set").assertIsEnabled()}.isSuccess}
        ui.onNodeWithText("Save set").performScrollTo().performClick()
    }
    @Test fun targetsAdvanceAndRemainEditableWhileSkipsUndoExtrasAndRestStaySeparate() {
        ui.onAllNodes(hasText("Paste plan") and hasClickAction()).onLast().performClick();waitFor("Preview plan")
        ui.onNode(hasSetTextAction() and hasText("Plan reply")).performScrollTo().performTextInput(block)
        ui.onNodeWithText("Preview plan").performClick();waitFor("Edit planned workout")
        val draft=model.planEdit.value!!.exercises.single()
        ui.onNodeWithTag("plan-import-list").performScrollToNode(hasTestTag("target-row:${draft.targets.first().key}"));capture("adoption-editable-plan")
        ui.onNodeWithTag("save-plan").performClick();waitFor("Saved plan")
        assertTrue(runBlocking{db.dao().workouts().isEmpty()});assertNull(runBlocking{db.trainingDao().timer()})
        ui.activityRule.scenario.recreate();waitFor("Saved plan")
        ui.waitUntil(10000){ui.onAllNodesWithTag("start-plan").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false}
        ui.onNodeWithTag("start-plan").performClick();waitFor("Workout started.")
        ui.waitUntil(10000){ui.onAllNodesWithText("Workout started.").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Synthetic press"));ui.onNodeWithText("Synthetic press").performScrollTo().performClick();waitFor("Save set")
        val targets=runBlocking{db.planDao().runTargets()};val original=runBlocking{db.planDao().sets()}
        ui.onNodeWithText("Previous",substring=false).performScrollTo();capture("adoption-logger-before-completion")
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("run-target:${targets[1].id}"));capture("adoption-set-table")
        input("43.5","10");capture("adoption-logger-keyboard")
        Espresso.closeSoftKeyboard();ui.onNodeWithText("Save set").performScrollTo().performTouchInput{doubleClick()}
        ui.waitUntil(15000){runBlocking{db.dao().sets().size}==1&&!model.busy.value}
        loggerNode("target-result:${targets[0].id}");capture("adoption-set-table-completed")
        assertEquals(43.5,runBlocking{db.dao().sets().single().weightKg},0.0);assertEquals(10,runBlocking{db.dao().sets().single().reps})
        field("Weight (kg)").assertTextContains("45.25")
        val timer=runBlocking{db.trainingDao().timer()!!};assertEquals(targets[0].id,timer.sourceTargetId)
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("rest-controls"));capture("adoption-completed-target-and-rest")
        ui.onNodeWithText("Pause rest").performClick();ui.waitUntil(10000){runBlocking{db.trainingDao().timer()?.running}==false}
        val paused=runBlocking{db.trainingDao().timer()!!.remainingMillis}
        ui.onNodeWithText("+30 seconds").performClick();ui.waitUntil(10000){runBlocking{db.trainingDao().timer()?.remainingMillis}==paused+30000}
        ui.activityRule.scenario.recreate();waitFor("Resume rest")
        ui.onNodeWithText("Resume rest").performScrollTo().performClick()
        ui.waitUntil(10000){runBlocking{db.trainingDao().timer()?.running}==true&&!model.busy.value}
        rowButton(targets[0].id,"Edit result").performScrollTo().performClick()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Editing set 1"));waitFor("Editing set 1")
        input("44","9");Espresso.closeSoftKeyboard();capture("adoption-editing-result")
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Update set"));ui.onNodeWithText("Update set").performScrollTo().performClick()
        ui.waitUntil(15000){runBlocking{db.dao().sets().single().weightKg}==44.0&&!model.busy.value}
        assertEquals(timer.token,runBlocking{db.trainingDao().timer()!!.token})
        field("Weight (kg)").assertTextContains("45.25")
        rowButton(targets[1].id,"Skip").performScrollTo().performClick()
        ui.waitUntil(10000){runBlocking{db.trainingDao().target(targets[1].id)!!.skipped}}
        field("Weight (kg)").assertTextContains("47.5")
        rowButton(targets[1].id,"Unskip").performScrollTo().performClick()
        ui.waitUntil(10000){!runBlocking{db.trainingDao().target(targets[1].id)!!.skipped}&&!model.busy.value}
        loggerNode("complete-target:${targets[1].id}").performClick()
        rowButton(targets[1].id,"Skip").performScrollTo().performClick()
        ui.waitUntil(10000){runBlocking{db.trainingDao().target(targets[1].id)!!.skipped}&&!model.busy.value}
        loggerNode("complete-target:${targets[0].id}").performClick();waitFor("Undo completion?")
        ui.onNodeWithText("Undo",substring=false).performClick();ui.waitUntil(10000){runBlocking{db.dao().sets().isEmpty()}&&!model.busy.value}
        assertNull(runBlocking{db.trainingDao().timer()})
        loggerNode("complete-target:${targets[0].id}").performClick();input("40.5","10");Espresso.closeSoftKeyboard()
        ui.waitUntil(10000){model.completionReady.value&&!model.busy.value}
        loggerNode("complete-target:${targets[0].id}").performClick()
        ui.waitUntil(15000){runBlocking{db.dao().sets().size}==1&&!model.busy.value}
        field("Weight (kg)").assertTextContains("47.5")
        ui.onNodeWithText("Add extra set").performScrollTo().performClick();input("50","7");save()
        ui.waitUntil(15000){runBlocking{db.dao().sets().size}==2}
        assertNull(runBlocking{db.dao().sets().last().targetId});assertEquals(original,runBlocking{db.planDao().sets()})
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Recorded sets · 2"));ui.onNodeWithText("Recorded sets · 2").performScrollTo();capture("adoption-extra-actual-set")
    }
    @Test fun aliasesMergePreviewAndChartsUseOneCanonicalHistoryWithoutLosingActualSets() {
        val ready=java.util.concurrent.atomic.AtomicBoolean(false);val now=System.currentTimeMillis()-86400000
        ui.activityRule.scenario.onActivity{
            model.pendingRestore.value=Backup(exercises=listOf(Exercise(1,"Synthetic press","synthetic press"),Exercise(2,"Synthetic press duplicate","synthetic press duplicate")),
                workouts=listOf(Workout(10,now,now+100000,finished=true)),entries=listOf(WorkoutExercise(20,10,1,0),WorkoutExercise(21,10,2,1)),
                sets=listOf(WorkoutSet(30,20,40.0,8,0,loadMode=LoadMode.EXTERNAL),WorkoutSet(31,21,42.5,8,0,loadMode=LoadMode.EXTERNAL)))
            model.confirmRestore{ready.set(true)}
        }
        ui.waitUntil(15000){ready.get()&&!model.busy.value&&model.data.value.workouts.size==1}
        waitFor("Backup restored.");ui.waitUntil(10000){ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithContentDescription("More actions").performClick();ui.onNodeWithText("Exercise library").performClick();waitFor("Exercise library")
        ui.onNode(hasSetTextAction() and hasText("New alias")).performScrollTo().performTextInput("Synthetic shorthand")
        Espresso.closeSoftKeyboard();ui.onNodeWithText("Add alias",substring=false).performScrollTo().performClick()
        ui.waitUntil(15000){model.data.value.exerciseAliases.any{it.name=="Synthetic shorthand"}}
        capture("adoption-alias-and-progression")
        ui.onNodeWithText("Preview merge into this exercise").performScrollTo().performClick();waitFor("Use for strength plan")
        ui.onNodeWithText("Synthetic press duplicate").performScrollTo().performClick();ui.onNodeWithText("Use for strength plan").performClick();waitFor("Review exercise merge")
        capture("adoption-merge-review")
        ui.onNodeWithTag("confirm-exercise-merge").performScrollTo().performClick();ui.onNodeWithText("Merge exercises").performClick()
        ui.waitUntil(15000){model.data.value.exercises.size==1&&!model.busy.value}
        assertEquals(2,runBlocking{db.dao().sets().size});assertEquals(1,runBlocking{db.dao().entries().size})
        ui.onNodeWithText("Close",substring=false).performClick()
        ui.onNodeWithContentDescription("More actions").performClick();ui.onNodeWithText("Strength progress").performClick();waitFor("Strength progress")
        ui.onNodeWithTag("strength-progress").performScrollToNode(hasText("External-load volume: 660 kg·reps"));ui.onNodeWithText("External-load volume: 660 kg·reps").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("strength-progress").performScrollToNode(hasTestTag("volume-chart"));capture("adoption-progress-after-merge")
    }
    @Test fun freeformLoggingStillSupportsRepeatedSavesEditCancelAndFinish() {
        val workout=runBlocking { val repo=WorkoutRepository(db);val id=repo.start();repo.addExercise(id,1);id }
        ui.waitUntil(15000){model.data.value.active?.workout?.id==workout}
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Synthetic press"));ui.onNodeWithText("Synthetic press").performScrollTo().performClick();waitFor("Save set")
        assertTrue(runBlocking { db.planDao().runTargets().isEmpty() })
        input("25.5","8");save();ui.waitUntil(15000){runBlocking{db.dao().sets().size}==1&&!model.busy.value}
        save();ui.waitUntil(15000){runBlocking{db.dao().sets().size}==2&&!model.busy.value}
        val first=runBlocking{db.dao().sets().first()}
        loggerNode("saved-set:set:${first.id}").performClick()
        input("26","9");Espresso.closeSoftKeyboard();ui.onNodeWithText("Update set").performScrollTo().performClick()
        ui.waitUntil(15000){runBlocking{db.dao().set(first.id)?.reps}==9&&!model.busy.value}
        loggerNode("saved-set:set:${first.id}").performClick()
        input("99","99");Espresso.closeSoftKeyboard();ui.onNodeWithText("Cancel edit").performScrollTo().performClick()
        assertEquals(26.0,runBlocking{db.dao().set(first.id)!!.weightKg},0.0)
        assertNull(runBlocking{db.trainingDao().timer()})
        ui.onNodeWithContentDescription("Back to workout").performClick();waitFor("Finish workout")
        ui.onNodeWithText("Finish workout").performClick();ui.onNodeWithText("Finish",useUnmergedTree=true).performClick()
        ui.waitUntil(15000){runBlocking{db.dao().active()}==null&&!model.busy.value}
        assertTrue(runBlocking{db.dao().workout(workout)!!.finished});assertEquals(2,runBlocking{db.dao().sets().size})
        assertTrue(runBlocking{db.dao().sets().all{it.targetId==null&&it.completedAt!=null}})
    }
}
