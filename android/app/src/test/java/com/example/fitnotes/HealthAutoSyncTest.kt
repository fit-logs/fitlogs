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
class HealthAutoSyncTest {
    private lateinit var db: FitDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FitDatabase::class.java).build()
    }
    @After fun cleanup() { db.close() }
    private fun local(id: Long = 1, start: Long = 1_000, end: Long? = 11_000, type: String = "climbing") =
        Workout(id = id, startedAt = start, completedAt = end, activityType = type, title = "Local title", notes = "Local notes")
    private fun candidate(id: String = "one", start: Long = 2_000, end: Long = 12_000, type: String = "climbing", origin: String = GARMIN_PACKAGE) =
        HealthCandidate(HealthSession(healthKey(origin, id), null, id, origin, "Synthetic source", null, 1,
            1000, 1000, start, end, 0, 0, type, 70, "Watch title", "Watch note", null, "", "Synthetic samples", "No distance"),
            heartRateRead = true, distanceRead = true)
    private fun samples(c: HealthCandidate, vararg times: Long) = c.copy(samples = times.map { time ->
        HealthSample(sampleKey(c.session.key, c.session.originPackage, "synthetic-hr", time), c.session.key, "synthetic-hr", c.session.originPackage, time, 120)
    })

    @Test fun onlyMutuallyUniqueSubstantialOverlapCanLink() {
        val source = candidate(); val workout = local()
        assertEquals(mapOf(source.session.key to workout.id), automaticHealthTargets(listOf(source), listOf(workout), emptyList()))
        assertTrue(automaticHealthTargets(listOf(source), listOf(workout, workout.copy(id = 2)), emptyList()).isEmpty())
        assertTrue(automaticHealthTargets(listOf(source, candidate("two")), listOf(workout), emptyList()).isEmpty())
        assertTrue(automaticHealthTargets(listOf(source.copy(session = source.session.copy(start = 10_000, end = 20_000))), listOf(workout), emptyList()).isEmpty())
        // A weak competing overlap also needs review instead of selecting a winner.
        assertTrue(automaticHealthTargets(listOf(source, candidate("two", 10_000, 20_000)), listOf(workout), emptyList()).isEmpty())
    }

    @Test fun dateOnlyUnfinishedWrongTypeOtherAndUnverifiedOriginNeverLink() {
        val source = candidate(); val workout = local()
        listOf(workout.copy(startedAt = 0, completedAt = null, finished = true), workout.copy(completedAt = null),
            workout.copy(activityType = "strength")).forEach {
            assertTrue(automaticHealthTargets(listOf(source), listOf(it), emptyList()).isEmpty())
        }
        assertTrue(automaticHealthTargets(listOf(candidate(type = "other")), listOf(local(type = "other")), emptyList()).isEmpty())
        assertTrue(automaticHealthTargets(listOf(candidate(origin = "org.fake.Garmin")), listOf(workout), emptyList()).isEmpty())
    }

    @Test fun linksAndRefreshesWithoutChangingLocalRecordsOrCreatingUnmatchedWorkouts() = runBlocking {
        val workout = local(type = "strength"); db.dao().insertWorkout(workout)
        val exercise = db.dao().insertExercise(Exercise(name = "Synthetic press", nameKey = "synthetic press"))
        val entry = db.dao().insertEntry(WorkoutExercise(workoutId = workout.id, exerciseId = exercise, position = 0))
        db.dao().insertSet(WorkoutSet(entryId = entry, weightKg = 50.0, reps = 8, position = 0))
        val beforeSets = db.dao().sets()
        val source = samples(candidate(type = "strength"), 3_000)
        val unmatched = candidate("unmatched", 30_000, 40_000, "running")
        val repo = HealthImportRepository(db)
        assertEquals(HealthSyncResult(0, 1, 1, 0), repo.applyAutomaticSync(listOf(source, unmatched), listOf(source), 1, 50_000))
        val later = samples(source, 3_000, 5_000)
        repeat(2) {
            assertEquals(HealthSyncResult(1, 0, 1, 0), repo.applyAutomaticSync(listOf(later, unmatched), listOf(later), 1, 50_000))
        }
        assertEquals(listOf(workout), db.dao().workouts()); assertEquals(beforeSets, db.dao().sets())
        assertEquals(1, db.healthDao().sessions().size); assertEquals(2, db.healthDao().samples().size)
        val backup = WorkoutRepository(db).backup(); assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
    }

    @Test fun explicitDateOnlyLinksRefreshButDeletedLinksNeverReappear() = runBlocking {
        db.importedDao().insertImport(HistoryImport("synthetic", "Synthetic", "fixture", 1000, "kg", 0, "", "{}"))
        db.importedDao().insertWorkouts(listOf(ImportedWorkout("date-only", "synthetic", "2026-10-12", notes = "Keep note")))
        val repo = HealthImportRepository(db); val source = samples(candidate(), 3_000)
        repo.import(source, attachImported = "date-only")
        val original = db.importedDao().workout("date-only")!!.workout
        val later = samples(source, 3_000, 5_000)
        assertEquals(1, repo.applyAutomaticSync(listOf(later), listOf(later), 1, 20_000).refreshed)
        assertEquals(original, db.importedDao().workout("date-only")!!.workout)
        HistoryImportRepository(db).deleteImportedWorkout("date-only")
        assertEquals(HealthSyncResult(0, 0, 0, 0), repo.applyAutomaticSync(listOf(later), listOf(later), 1, 20_000))
        assertTrue(db.dao().workouts().isEmpty()); assertNull(db.healthDao().sessions().single().importedWorkoutId)
    }

    @Test fun editsAndCompetingAttachmentsDuringReadAreRecheckedBeforeWriting() = runBlocking {
        db.dao().insertWorkout(local()); val repo = HealthImportRepository(db); val source = candidate()
        assertEquals(mapOf(source.session.key to 1L), repo.automaticTargets(listOf(source)))
        db.dao().insertWorkout(local(id = 2))
        assertEquals(HealthSyncResult(0, 0, 1, 0), repo.applyAutomaticSync(listOf(source), listOf(source), 1, 20_000))
        assertTrue(db.healthDao().sessions().isEmpty())
        repo.import(candidate("explicit"), attachTo = 1)
        assertTrue(repo.automaticTargets(listOf(source)).isEmpty())
    }

    @Test fun revokedSupplementalPermissionAndMissingSourcePreserveSavedData() = runBlocking {
        db.dao().insertWorkout(local()); val repo = HealthImportRepository(db); val source = samples(candidate(), 3_000)
        repo.applyAutomaticSync(listOf(source), listOf(source), 1, 20_000)
        val denied = source.copy(samples = emptyList(), heartRateRead = false, session = source.session.copy(hrStatus = "Permission revoked"))
        repo.applyAutomaticSync(listOf(denied), listOf(denied), 1, 20_000)
        assertEquals(1, db.healthDao().samples().size)
        assertEquals(1, repo.applyAutomaticSync(emptyList(), emptyList(), 1, 20_000).missing)
        assertEquals(local(), db.dao().workout(1)); assertEquals(1, db.healthDao().samples().size)
    }

    @Test fun failedSampleWriteRollsBackTheEntireAutomaticBatch() = runBlocking {
        db.dao().insertWorkout(local()); val repo = HealthImportRepository(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_failure BEFORE INSERT ON health_samples BEGIN SELECT RAISE(ABORT, 'synthetic test'); END")
        val source = samples(candidate(), 3_000)
        try { repo.applyAutomaticSync(listOf(source), listOf(source), 1, 20_000); fail() } catch (_: Exception) {}
        assertTrue(db.healthDao().sessions().isEmpty()); assertTrue(db.healthDao().samples().isEmpty())
        assertEquals(listOf(local()), db.dao().workouts())
    }
}
