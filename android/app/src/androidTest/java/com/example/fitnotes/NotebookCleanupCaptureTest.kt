package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Only the opt-in, separately installed preview may replace these synthetic fixtures. */
class NotebookCleanupCaptureTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val db get() = model.photoRepository.database
    private val longName = "Leg Press with a controlled lowering phase and a comfortable range of motion"
    private fun waitText(text: String) = ui.waitUntil(30000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        i.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = i.uiAutomation.takeScreenshot()!!
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "after"
        File(i.targetContext.filesDir, "$phase-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Before fun seed() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.example.fitnotes.preview.photos")
        val now = System.currentTimeMillis()
        val day = com.example.fitnotes.ui.todayDate()
        val previous = now - 86400000L
        val source = HealthSession(healthKey(GARMIN_PACKAGE,"source-activity"), 10, "source-activity", GARMIN_PACKAGE, "Garmin", null, 1, now, now,
            previous, previous + 3600000, 46800, 46800, "strength", 80, "Legs", "", null, "", "read", "read")
        val samples = listOf(0L to 90L, 60000L to 110L, 120000L to 38L, 2400000L to 125L, 2460000L to 118L).mapIndexed { index, (time, bpm) ->
            HealthSample(sampleKey(source.key,GARMIN_PACKAGE,"hr-record",previous+time), source.key, "hr-record", GARMIN_PACKAGE, previous + time, bpm)
        }
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, longName, longName.lowercase()), Exercise(2, "Bodyweight Squat", "bodyweight squat")),
                workouts = listOf(Workout(10, previous, previous + 3600000, "Legs", "Controlled warm-up", finished = true)),
                entries = listOf(WorkoutExercise(20, 10, 1, 0)), sets = listOf(WorkoutSet(30, 20, 80.0, 8, 0, loadMode = LoadMode.EXTERNAL)),
                imports = listOf(HistoryImport("fixture-import", "Original FitNotes Workout CSV", "fixture.csv", now, "kg", 0, "", "{}")),
                importedWorkouts = listOf(ImportedWorkout("date-only", "fixture-import", day)),
                importedSets = listOf(ImportedSet("original-set", "date-only", "2", 0, longName, "Legs", 75.0, 10, null, null, null, "", "{}")),
                healthSessions = listOf(source), healthSamples = samples,
                plannedWorkouts=listOf(PlannedWorkout("saved-plan","Legs",day,60,"General guidance",now,planFingerprint("fixture-plan"),warmUp="Easy warm-up")),
                plannedExercises=listOf(PlannedExercise(100,"saved-plan",1,0,longName,3,8,12,"80","kg","Slow lowering"),PlannedExercise(101,"saved-plan",2,1,"Bodyweight Squat",3,8,12,null,"bodyweight","")),
                plannedSets=listOf(PlannedSet(200,100,0,8,12,"80","kg",90),PlannedSet(201,100,1,6,8,"85","kg",90),PlannedSet(202,100,2,8,12,null,null,90))+(0..2).map{PlannedSet((203+it).toLong(),101,it,8,12,null,"bodyweight",90)})
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        val workout = runBlocking { val repo = WorkoutRepository(db); val id = repo.start(); repo.addExercise(id, 1); id }
        runBlocking {
            val entry = db.dao().entries().first { it.workoutId == workout }
            db.planDao().insertRun(WorkoutPlanRun(workout, null, "Legs", "Easy warm-up", ""))
            db.planDao().insertRunTargets(listOf(
                WorkoutSetTarget(entryId = entry.id, position = 0, repsMin = 8, repsMax = 12, weightValue = "80", weightUnit = "kg", restSeconds = 90),
                WorkoutSetTarget(entryId = entry.id, position = 1, repsMin = 6, repsMax = 8, weightValue = "85", weightUnit = "kg", restSeconds = 90),
                WorkoutSetTarget(entryId = entry.id, position = 2, repsMin = 8, repsMax = 12, weightValue = null, weightUnit = null, restSeconds = 90)))
            TrainingRepository(db).complete(db.planDao().runTargets().first().id, "80", "10", "", LoadMode.EXTERNAL)
        }
        ui.waitUntil(30000) { model.data.value.active?.entries?.firstOrNull()?.sets?.size == 1 && model.planRuns.value.isNotEmpty() }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Backup restored.").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun freeformCompletesRepeatedSetsWithoutPlanAndKeepsEdits() {
        runBlocking { db.dao().active()?.let{WorkoutRepository(db).deleteWorkout(it.id)} }
        ui.waitUntil(15000){model.data.value.active==null}
        ui.onNodeWithTag("start-workout").performClick(); ui.onNodeWithTag("confirm-workout-setup").performClick()
        ui.waitUntil(15000){model.data.value.active!=null}
        val id=model.data.value.active!!.workout.id
        runBlocking{WorkoutRepository(db).addExercise(id,1)}
        ui.waitUntil(15000){model.data.value.active?.entries?.size==1}
        ui.onNodeWithText(longName).performClick()
        repeat(2) {
            ui.onNodeWithText("Add set").performClick()
            ui.onNodeWithTag("edit-weight").performTextReplacement("25.5")
            ui.onNodeWithTag("edit-reps").performTextReplacement("8")
            Espresso.closeSoftKeyboard();ui.onNodeWithText("Complete set").performClick()
            ui.waitUntil(15000){model.data.value.active!!.entries.single().sets.size==it+1&&!model.busy.value}
        }
        capture("freeform")
        assertTrue(runBlocking{db.planDao().runTargets().isEmpty()})
        assertNull(runBlocking{db.trainingDao().timer()})
        val set=model.data.value.active!!.entries.single().sets.first()
        ui.onNodeWithTag("saved-set:set:${set.id}").performClick();ui.onNodeWithTag("edit-reps").performTextReplacement("9")
        Espresso.closeSoftKeyboard();ui.onNodeWithText("Save changes").performClick()
        ui.waitUntil(15000){runBlocking{db.dao().set(set.id)?.reps}==9&&!model.busy.value}
        assertEquals(25.5,runBlocking{db.dao().set(set.id)!!.weightKg},0.0)
    }
    @Test fun loggerHistoryShowsActualProgressAlongsideSessionTables() {
        val before=runBlocking{db.dao().sets()}
        val targetsBefore=runBlocking{db.planDao().runTargets()}
        runBlocking {
            val imported=db.importedDao().workouts().single()
            db.importedDao().updateWorkout(imported.copy(date=com.example.fitnotes.ui.moveDay(com.example.fitnotes.ui.todayDate(),-7)))
        }
        ui.waitUntil(15000){model.data.value.imported.workouts.single().workout.date<com.example.fitnotes.ui.todayDate()}
        ui.onNodeWithText("Resume workout").performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText(longName))
        ui.onNodeWithText(longName).performClick()
        ui.onNode(hasText("History",substring=false) and hasClickAction()).performClick()
        ui.onNodeWithTag("logger-history-chart").assertIsDisplayed()
        ui.onNodeWithText("Heaviest set ▾").assertIsDisplayed()
        capture("logger-history-graph")
        ui.onNodeWithTag("logger-history-chart").performTouchInput{click(androidx.compose.ui.geometry.Offset(width-15f,height*.4f))}
        ui.onNodeWithText("Heaviest set: 80 kg × 8").assertIsDisplayed()
        ui.onNodeWithText("View workout").assertIsDisplayed()
        ui.onNodeWithText("Close",substring=false).performClick()
        ui.onNodeWithTag("logger-history-list").performScrollToNode(hasText("80",substring=false))
        ui.onNodeWithText("80",substring=false).assertIsDisplayed()
        assertTrue(runBlocking{db.dao().active()!=null})
        assertEquals(before,runBlocking{db.dao().sets()})
        assertEquals(targetsBefore,runBlocking{db.planDao().runTargets()})
    }
    @Test fun captureNotebookScreens() {
        if(InstrumentationRegistry.getArguments().getString("phase")!="before") { capture("train");ui.onNodeWithText("Resume workout").performClick() }
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText(longName)); capture("active")
        ui.onNodeWithText(longName).performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("logger-list").fetchSemanticsNodes().isNotEmpty() }
        capture("logger")
        if (InstrumentationRegistry.getArguments().getString("phase") == "before") {
            ui.onNodeWithContentDescription("Back to workout").performClick()
            ui.onNodeWithText("History", substring = false).performClick()
            ui.onNodeWithText("◆ Legs").performClick(); capture("completed")
            ui.onNodeWithContentDescription("More actions").performClick()
            ui.onNodeWithText("Strength progress").performClick(); waitText("Strength progress"); capture("progress")
        } else {
            val targets=runBlocking{db.planDao().runTargets()}
            val third=targets[2]
            ui.onNodeWithTag("weight:${third.id}").assertTextEquals("")
            ui.onNodeWithTag("reps:${third.id}").performClick().performTextReplacement("11")
            ui.onNodeWithTag("reps:${third.id}").assertIsDisplayed()
            ui.onNodeWithTag("complete-target:${third.id}").assertIsDisplayed()
            capture("logger-keyboard")
            if(InstrumentationRegistry.getArguments().getString("captureOnly")=="true") {
                Espresso.closeSoftKeyboard()
                ui.onNodeWithContentDescription("Back to workout").performClick()
                ui.onNodeWithContentDescription("Back").performClick()
                ui.onNodeWithText("Legs",substring=false).performClick();waitText("Start workout");capture("saved-plan")
                ui.onNodeWithText(longName).performClick();waitText("Edit planned workout")
                ui.onNodeWithTag("plan-import-list").performScrollToNode(hasText("Target set 2"));capture("plan-editor")
                assertEquals(2,runBlocking{db.dao().workouts().size})
                return
            }
            Espresso.closeSoftKeyboard()
            ui.onNodeWithTag("complete-target:${third.id}").performClick()
            ui.waitUntil(15000){runBlocking{db.dao().sets().size}==3&&!model.busy.value} // includes the previous completed session
            val actual=runBlocking{db.trainingDao().result(third.id)!!};assertFalse(actual.weightSpecified);assertEquals(11,actual.reps)
            val timer=runBlocking{db.trainingDao().timer()!!};capture("logger-completed-rest")
            ui.onNodeWithTag("saved-set:set:${actual.id}").performClick()
            ui.onNodeWithTag("edit-reps").performTextReplacement("10")
            Espresso.closeSoftKeyboard();ui.onNodeWithText("Save changes").performClick()
            ui.waitUntil(15000){runBlocking{db.dao().set(actual.id)?.reps}==10&&!model.busy.value}
            assertEquals(timer.token,runBlocking{db.trainingDao().timer()!!.token})
            ui.activityRule.scenario.recreate()
            ui.waitUntil(15000){ui.onAllNodesWithTag("logger-list").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("Back to workout").performClick()
            ui.onNodeWithText("Finish",substring=false).performClick();waitText("Finish workout?")
            ui.onAllNodesWithText("Finish",substring=false).onLast().performClick()
            ui.waitUntil(15000){runBlocking{db.dao().active()}==null&&!model.busy.value}
            assertNull(runBlocking{db.trainingDao().timer()})
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithText("◆ Legs").performClick()
            ui.onNodeWithTag("workout-overview").performScrollToNode(hasTestTag("heart-rate-chart"));capture("completed")
            ui.onNodeWithTag("heart-rate-chart").performTouchInput{click(center)};capture("heart-rate-selected")
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithText("Progress",substring=false).performClick()
            ui.onNodeWithTag("strength-progress").performScrollToNode(hasTestTag("volume-chart"));capture("progress")
            ui.onNodeWithText("Train",substring=false).performClick()
            ui.onNodeWithText("Legs",substring=false).performClick()
            waitText("Start workout");capture("saved-plan")
            val count=runBlocking{db.dao().workouts().size}
            assertEquals(2, count)
            ui.onNodeWithText(longName).performClick();waitText("Edit planned workout")
            ui.onNodeWithTag("plan-import-list").performScrollToNode(hasText("Target set 2"));capture("plan-editor")
            ui.onNodeWithTag("save-plan").performClick();waitText("Start workout")
            assertEquals(count,runBlocking{db.dao().workouts().size})
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithText("History",substring=false).performClick()
            ui.onNodeWithTag("history-session:import:date-only").performClick();capture("date-only")
        }
    }
}
