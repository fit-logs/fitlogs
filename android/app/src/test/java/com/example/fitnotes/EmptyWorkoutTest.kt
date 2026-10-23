package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EmptyWorkoutTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private val name = "empty-workout-test.db"
    private fun open() { db = Room.databaseBuilder(context, FitDatabase::class.java, name).build() }
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); open()
        db.dao().insertExercises(listOf(Exercise(1, "Synthetic press", "synthetic press"), Exercise(2, "Synthetic row", "synthetic row")))
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun plan(): String {
        val prescription = PlanPrescription("Synthetic press", listOf(PlanSetTarget(8, 10, PlanWeight("40", "kg"))))
        val spec = PlanSpec("Synthetic plan", "", 30, "Plan note", listOf(prescription), "c".repeat(64))
        return (WorkoutPlanRepository(db).save(PlanPreview(spec, listOf(ResolvedPlanExercise(prescription, 1)))) as PlanSaveResult.Saved).id
    }
    private suspend fun expectRejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun emptyStartLogEditReorderRestartFinishAndSecondSessionKeepOnlyActualResults() = runBlocking {
        val sourcePlan = plan(); val saved = db.planDao().detail(sourcePlan)!!
        val starts = WorkoutStartRepository(db); var workouts = WorkoutRepository(db)
        val first = starts.startEmpty(); val start = db.dao().workout(first)!!.startedAt
        assertEquals("", db.dao().workout(first)!!.title); assertTrue(start > 0)
        assertTrue(db.dao().workoutEntries(first).isEmpty()); assertTrue(db.dao().sets().isEmpty())
        workouts.addExercises(first, listOf(1, 2, 1))
        val entries = db.dao().workoutEntries(first); assertEquals(2, entries.size)
        val result = workouts.saveSet(entries[0].id, "40", "8")
        workouts.saveSet(entries[1].id, "25", "10")
        workouts.saveSet(entries[0].id, "42.5", "9", result)
        workouts.reorderExercises(first, entries.reversed().map { it.id })
        val actuals = db.dao().sets(); db.close(); open(); workouts = WorkoutRepository(db)
        assertEquals(first, db.dao().active()!!.id); assertEquals(start, db.dao().active()!!.startedAt)
        assertEquals(entries.reversed().map { it.id }, db.dao().workoutEntries(first).map { it.id })
        assertEquals(actuals, db.dao().sets()); assertEquals(42.5, db.dao().set(result)!!.weightKg, 0.0)
        workouts.finish(first)
        val next = WorkoutStartRepository(db).startEmpty()
        assertNotEquals(first, next); assertTrue(db.dao().workout(first)!!.isComplete)
        assertEquals(localWorkoutDate(db.dao().workout(first)!!), localWorkoutDate(db.dao().workout(next)!!))
        assertTrue(db.dao().workoutEntries(next).isEmpty()); assertEquals(actuals, db.dao().sets())
        assertEquals(saved, db.planDao().detail(sourcePlan)); assertTrue(db.planDao().runTargets().isEmpty())
        val backup = BackupCodec.decode(BackupCodec.encode(workouts.backup())); workouts.restore(backup)
        assertEquals(next, db.dao().active()!!.id); assertEquals(actuals, db.dao().sets())
    }
    @Test fun emptyOrTargetOnlySessionsCannotBeFinishedToStartAnother() = runBlocking {
        val starts = WorkoutStartRepository(db); val empty = starts.startEmpty()
        expectRejected { starts.resolveAndStart(empty, ActiveWorkoutAction.FINISH) }
        assertEquals(empty, db.dao().active()!!.id); assertEquals(1, db.dao().workouts().size)
        val p = plan(); val planned = starts.resolveAndStart(empty, ActiveWorkoutAction.DISCARD, p)
        assertNull(db.dao().workout(empty)); assertTrue(db.planDao().runTargets().isNotEmpty())
        assertTrue(db.dao().sets().isEmpty())
        expectRejected { starts.resolveAndStart(planned, ActiveWorkoutAction.FINISH) }
        assertEquals(planned, db.dao().active()!!.id); assertFalse(db.dao().workout(planned)!!.isComplete)
    }
    @Test fun finishAndStartRetainsResultsAndClearsOnlyTheOldRestTimer() = runBlocking {
        val starts = WorkoutStartRepository(db); val workouts = WorkoutRepository(db)
        val first = starts.startEmpty(); workouts.addExercise(first, 1)
        workouts.saveSet(db.dao().workoutEntries(first).single().id, "40", "8")
        db.trainingDao().putTimer(RestTimerState(workoutId=first, token="old", sourceTargetId=null, remainingMillis=60000, anchorElapsed=0, bootCount=1, running=false))
        val actuals = db.dao().sets()
        val next = starts.resolveAndStart(first, ActiveWorkoutAction.FINISH)
        assertEquals(next, db.dao().active()!!.id); assertTrue(db.dao().workout(first)!!.isComplete)
        assertEquals(actuals, db.dao().sets()); assertNull(db.trainingDao().timer())
    }
    @Test fun invalidPlanRollsBackBothFinishAndDiscardAndStaleDecisionsCannotAffectTheNextWorkout() = runBlocking {
        val starts = WorkoutStartRepository(db); val workouts = WorkoutRepository(db)
        val first = starts.startEmpty(); workouts.addExercise(first, 1)
        workouts.saveSet(db.dao().workoutEntries(first).single().id, "40", "8")
        val before = workouts.backup()
        for (action in ActiveWorkoutAction.entries) {
            expectRejected { starts.resolveAndStart(first, action, "missing-plan") }
            val after = workouts.backup(); assertEquals(before.copy(exportedAt=after.exportedAt), after)
        }
        val next = starts.resolveAndStart(first, ActiveWorkoutAction.DISCARD)
        expectRejected { starts.resolveAndStart(first, ActiveWorkoutAction.DISCARD) }
        assertEquals(next, db.dao().active()!!.id); assertEquals(1, db.dao().workouts().size)
        assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun resolvingIntoAPlanCopiesTargetsWithoutManufacturingPerformedSets() = runBlocking {
        val p = plan(); val spec = db.planDao().detail(p)!!.spec
        val starts = WorkoutStartRepository(db); val first = starts.startEmpty()
        val next = starts.resolveAndStart(first, ActiveWorkoutAction.DISCARD, p)
        assertEquals(p, db.planDao().run(next)!!.planId); assertEquals(spec, db.planDao().detail(p)!!.spec)
        assertEquals(1, db.dao().workoutEntries(next).size); assertTrue(db.dao().sets().isEmpty())
        assertEquals(1, db.planDao().runTargets().size)
    }
    @Test fun usingACompletedWorkoutAsAPlanOnlyCreatesADraftUntilExplicitlySaved() = runBlocking {
        val workouts = WorkoutRepository(db)
        val id = WorkoutStartRepository(db).startEmpty(); workouts.addExercises(id, listOf(2, 1))
        val entries = db.dao().workoutEntries(id)
        workouts.saveSet(entries[0].id, "25", "10")
        workouts.saveSet(entries[1].id, "40", "8")
        workouts.finish(id)
        val before = workouts.backup()
        val detail = WorkoutDetail(db.dao().workout(id)!!, entries.map { EntryDetail(it, db.dao().exercise(it.exerciseId)!!, db.dao().entrySets(it.id)) }, emptyList())
        val session = com.example.fitnotes.ui.notebookSessions(AppData(exercises = db.dao().exercises(), workouts = listOf(detail))).single()
        val draft = planFromCompletedSession(session, emptyList())
        assertEquals(listOf(2L, 1L), draft.resolutions.map { it.exerciseId })
        assertEquals(listOf(10, 8), draft.spec.exercises.map { it.setTargets.single().repsMin })
        val after = workouts.backup(); assertEquals(before.copy(exportedAt = after.exportedAt), after)
        WorkoutPlanRepository(db).save(draft)
        assertEquals(before.workouts, db.dao().workouts()); assertEquals(before.sets, db.dao().sets())
        assertEquals(1, db.planDao().plans().size)
    }
    @Test fun concurrentStartsKeepExactlyOneActiveWorkout() = runBlocking {
        val outcomes = coroutineScope { (1..8).map { async { runCatching { WorkoutStartRepository(db).startEmpty() } } }.awaitAll() }
        assertEquals(1, outcomes.count { it.isSuccess }); assertEquals(1, db.dao().workouts().size)
        assertEquals(outcomes.single { it.isSuccess }.getOrThrow(), db.dao().active()!!.id)
        assertTrue(db.dao().sets().isEmpty())
    }
}
