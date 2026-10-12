package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkoutRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private lateinit var repo: WorkoutRepository
    private val name = "repository-verification.db"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, FitDatabase::class.java, name).build()
        repo = WorkoutRepository(db)
    }
    @After fun cleanup() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun multiSelectIsAtomicOrderedAndIdempotent() = runBlocking {
        val workout = repo.start()
        val first = db.dao().insertExercise(Exercise(name = "One", nameKey = "one"))
        val second = db.dao().insertExercise(Exercise(name = "Two", nameKey = "two"))
        expectFailure { repo.addExercises(workout, listOf(first, Long.MAX_VALUE)) }
        assertTrue(db.dao().workoutEntries(workout).isEmpty())
        repo.addExercises(workout, listOf(second, first, second))
        repo.addExercises(workout, listOf(first))
        assertEquals(listOf(second, first), db.dao().workoutEntries(workout).map { it.exerciseId })
        assertTrue(db.dao().sets().isEmpty())
    }

    @Test fun threeSetsAndMetadataSurviveReopenAndResume() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        val entry = db.dao().workoutEntries(id).single()
        repeat(3) { repo.saveSet(entry.id, "60", "8") }
        repo.saveDetails(id, "Leg day", "Felt good")
        // An invalid draft must not become a saved set.
        expectFailure { repo.saveSet(entry.id, "-5", "0") }
        db.close()
        reopen()
        assertEquals(id, repo.start())
        assertEquals(3, db.dao().entrySets(entry.id).size)
        assertEquals("Leg day", db.dao().workout(id)!!.title)
        assertNull(db.dao().workout(id)!!.completedAt)
        repo.finish(id)
        assertNotNull(db.dao().workout(id)!!.completedAt)
        assertEquals("Felt good", db.dao().workout(id)!!.notes)
    }

    @Test fun zeroWeightDuplicateEditDeleteAndCustomNames() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, " Push-up ")
        repo.createExercise(id, "push-UP")
        assertEquals(1, db.dao().exercises().size)
        val entry = db.dao().workoutEntries(id).single()
        repo.saveSet(entry.id, "0", "12")
        repo.duplicateSet(db.dao().entrySets(entry.id).single().id)
        val duplicate = db.dao().entrySets(entry.id).last()
        repo.saveSet(entry.id, "2,5", "10", duplicate.id)
        assertEquals(2.5, db.dao().set(duplicate.id)!!.weightKg, 0.0)
        repo.deleteSet(duplicate.id)
        assertEquals(1, db.dao().entrySets(entry.id).size)
        assertEquals(0.0, db.dao().entrySets(entry.id).single().weightKg, 0.0)
    }

    @Test fun lastSessionSkipsActiveAndEmptyAndUsesCompletionOrder() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        val exercise = db.dao().exercises().single()
        val entry = db.dao().workoutEntries(id).single()
        repo.saveSet(entry.id, "60", "8")
        repo.finish(id)
        val second = repo.repeat(id)
        repo.saveSet(db.dao().workoutEntries(second).single().id, "65", "8")
        repo.finish(second)
        // Force deterministic completion order.
        db.dao().updateWorkout(db.dao().workout(id)!!.copy(completedAt = System.currentTimeMillis() + 10))
        db.dao().updateWorkout(db.dao().workout(second)!!.copy(completedAt = System.currentTimeMillis() + 20))
        val active = repo.repeat(id)
        val empty = db.dao().insertWorkout(Workout(startedAt = 1, completedAt = System.currentTimeMillis() + 30))
        db.dao().insertEntry(WorkoutExercise(workoutId = empty, exerciseId = exercise.id, position = 0))
        val result = lastCompleted(repo.workouts.first(), exercise.id, active)!!
        assertEquals(second, result.first.workout.id)
        assertEquals(65.0, result.second.sets.single().weightKg, 0.0)
        assertEquals(id, lastCompleted(repo.workouts.first(), exercise.id, second)!!.first.workout.id)
    }

    @Test fun repeatCopiesOrderedExercisesAndNoCompletedSets() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        repo.createExercise(id, "Bench Press")
        val source = db.dao().workoutEntries(id)
        repo.saveSet(source.first().id, "20", "10")
        repo.finish(id)
        val repeated = repo.repeat(id)
        assertNull(db.dao().workout(repeated)!!.completedAt)
        assertEquals(source.map { it.exerciseId }, db.dao().workoutEntries(repeated).map { it.exerciseId })
        assertTrue(db.dao().workoutEntries(repeated).all { db.dao().entrySets(it.id).isEmpty() })
        expectFailure { repo.repeat(id) }
    }

    @Test fun fullBackupRoundTripIncludesActiveAndPreservesIds() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        repo.saveSet(db.dao().workoutEntries(id).single().id, "80", "5")
        repo.saveDetails(id, "Strength", "Unicode notes: café 🏋")
        repo.finish(id)
        val active = repo.repeat(id)
        repo.saveSet(db.dao().workoutEntries(active).single().id, "0", "10")
        val backup = repo.backup()
        val decoded = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup, decoded)
        repo.deleteWorkout(id)
        repo.deleteWorkout(active)
        repo.restore(decoded)
        val restored = repo.backup()
        assertEquals(backup.copy(exportedAt = restored.exportedAt), restored)
        assertEquals(active, repo.start())
        val next = db.dao().insertExercise(Exercise(name = "Lunge", nameKey = "lunge"))
        assertTrue(next > backup.exercises.maxOf { it.id })
        assertTrue(sessionText(repo.workouts.first()).contains("80 kg × 5 reps"))
    }

    @Test fun invalidAndUnsupportedBackupsLeaveDatabaseUnchanged() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        repo.saveSet(db.dao().workoutEntries(id).single().id, "20", "5")
        val before = repo.backup()
        val json = BackupCodec.encode(before)
        listOf(
            "{",
            json.replace("\"version\": " + before.version, "\"version\": 99"),
            json.replace("\"reps\": 5", "\"reps\": 0"),
            json.replace("\"exerciseId\": 1", "\"exerciseId\": 9999"),
            json.replace("\"id\": 1", "\"id\": 1.5"),
            json.replace("\"id\": 1", "\"id\": \"1\""),
        ).forEach { bad -> expectFailure { repo.restore(BackupCodec.decode(bad)) } }
        expectFailure { repo.restore(before.copy(exercises = before.exercises + before.exercises.first())) }
        expectFailure { repo.restore(before.copy(workouts = before.workouts + Workout(id = 999, startedAt = 1))) }
        expectFailure { repo.restore(before.copy(sets = before.sets.map { it.copy(weightKg = Double.NaN) })) }
        val after = repo.backup()
        assertEquals(before.copy(exportedAt = after.exportedAt), after)
    }

    @Test fun restoreInsertionFailureRollsBackAllDeletes() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        repo.saveSet(db.dao().workoutEntries(id).single().id, "20", "5")
        val before = repo.backup()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_restore BEFORE INSERT ON exercises WHEN NEW.id = 999 BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        val validButRejected = before.copy(exercises = before.exercises + Exercise(999, "Lunge", "lunge"))
        expectFailure { repo.restore(validButRejected) }
        val after = repo.backup()
        assertEquals(before.copy(exportedAt = after.exportedAt), after)
    }

    @Test fun deletingSessionCascadesSetsButKeepsExerciseLibrary() = runBlocking {
        val id = repo.start()
        repo.createExercise(id, "Squat")
        repo.saveSet(db.dao().workoutEntries(id).single().id, "20", "5")
        repo.deleteWorkout(id)
        assertTrue(db.dao().entries().isEmpty())
        assertTrue(db.dao().sets().isEmpty())
        assertEquals(1, db.dao().exercises().size)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid input to fail") } catch (_: IllegalArgumentException) {
        } catch (_: android.database.sqlite.SQLiteException) {
        }
    }
}
