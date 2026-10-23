package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
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
class ExerciseOrderTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private val name = "exercise-order-test.db"
    private fun open() { db = Room.databaseBuilder(context, FitDatabase::class.java, name).build() }
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); open()
        db.dao().insertExercises(listOf(Exercise(1, "Synthetic press", "synthetic press"), Exercise(2, "Synthetic row", "synthetic row"), Exercise(3, "Synthetic curl", "synthetic curl"), Exercise(4, "Synthetic squat", "synthetic squat")))
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun seed(): Pair<String, Long> {
        val targets = listOf(PlanSetTarget(8, 10, PlanWeight("40.5", "kg"), 90), PlanSetTarget(6, 8))
        val exercises = (1L..2L).map { id ->
            ResolvedPlanExercise(PlanPrescription(db.dao().exercise(id)!!.name, targets, "Guidance $id"), id, supersetGroup = "A")
        }
        val plans = WorkoutPlanRepository(db)
        val id = (plans.save(PlanPreview(PlanSpec("Synthetic workout", "", 45, "Session note", exercises.map { it.prescription }, "a".repeat(64)), exercises)) as PlanSaveResult.Saved).id
        val workout = plans.start(id)
        // Reordering uses entry IDs and retains the existing target/result associations.
        db.dao().insertEntry(WorkoutExercise(workoutId = workout, exerciseId = 3, position = 7, planNote = "Another occurrence"))
        val first = db.planDao().runTargets().first()
        TrainingRepository(db).complete(first.id, "41", "9", "Actual", LoadMode.EXTERNAL)
        db.trainingDao().putTimer(RestTimerState(workoutId = workout, token = "existing-rest", sourceTargetId = first.id,
            remainingMillis = 60000, anchorElapsed = 1000, bootCount = 1, running = false))
        return id to workout
    }
    @Test fun reorderingSurvivesRestartAndBackupWithoutChangingTrainingOrTheSourcePlan() = runBlocking {
        val (plan, workout) = seed()
        val before = WorkoutRepository(db).backup()
        val entries = db.dao().workoutEntries(workout)
        val order = entries.reversed().map { it.id }
        WorkoutRepository(db).reorderExercises(workout, order)
        val after = WorkoutRepository(db).backup()
        assertEquals(order, db.dao().workoutEntries(workout).map { it.id })
        assertEquals(order.mapIndexed { position, id -> entries.single { it.id == id }.copy(position = position) }, db.dao().workoutEntries(workout))
        assertEquals(listOf(0, 1, 2), db.dao().workoutEntries(workout).map { it.position })
        assertEquals(before.workouts.map { it.copy(lastActionAt = null) }, after.workouts.map { it.copy(lastActionAt = null) })
        assertEquals(before.copy(exportedAt = after.exportedAt, entries = after.entries, workouts = after.workouts), after)
        val firstPending = nextPlannedSet(db.planDao().runDetail(workout)!!, db.dao().sets())!!
        assertEquals(entries[1].id, firstPending.entryId)
        db.close(); open()
        assertEquals(order, db.dao().workoutEntries(workout).map { it.id })
        val decoded = BackupCodec.decode(BackupCodec.encode(WorkoutRepository(db).backup()))
        WorkoutRepository(db).restore(decoded)
        assertEquals(order, db.dao().workoutEntries(workout).map { it.id })
        assertEquals(before.sets, db.dao().sets())
        assertEquals(before.runTargets, db.planDao().runTargets())
        assertEquals(listOf(1L, 2L), db.planDao().detail(plan)!!.ordered.map { it.exercise.id })
    }
    @Test fun malformedOrStaleOrdersCannotPartiallyChangeTheWorkout() = runBlocking {
        val (_, workout) = seed()
        val repository = WorkoutRepository(db)
        val before = db.dao().workoutEntries(workout)
        val ids = before.map { it.id }
        for (invalid in listOf(ids.dropLast(1), listOf(ids[0], ids[0], ids[2]), ids.dropLast(1) + Long.MAX_VALUE, ids + Long.MAX_VALUE)) {
            try { repository.reorderExercises(workout, invalid); fail("Expected invalid order to be rejected") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, db.dao().workoutEntries(workout))
        }
        val extra = db.dao().insertEntry(WorkoutExercise(workoutId = workout, exerciseId = 4, position = 8))
        try { repository.reorderExercises(workout, ids.reversed()); fail("Expected stale order to be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before + db.dao().entry(extra)!!, db.dao().workoutEntries(workout))
    }
}
