package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
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
class ActivityRepositoryTest {
    private lateinit var db: FitDatabase
    private lateinit var context: Context
    private val name = "synthetic-activity-test.db"
    private fun open() { db = Room.databaseBuilder(context, FitDatabase::class.java, name).build() }
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun candidate(id: String = "demo-1", start: Long = 1000, end: Long = 10000, type: String = "running") =
        HealthCandidate(HealthSession(healthKey(GARMIN_PACKAGE, id), null, id, GARMIN_PACKAGE, "Synthetic source", "client-$id", 1, 1000,
            1000, start, end, 0, 0, type, 56, "Synthetic session", "Source notes", null, "", "No samples", "No distance"), heartRateRead = true, distanceRead = true)
    private fun withSample(c: HealthCandidate, time: Long, bpm: Long) = c.copy(samples = c.samples + HealthSample(
        sampleKey(c.session.key, GARMIN_PACKAGE, "hr", time), c.session.key, "hr", GARMIN_PACKAGE, time, bpm))

    @Test fun timerSurvivesDatabaseRestartAndRejectsReplacingUnfinishedWorkout() = runBlocking {
        val id = WorkoutRepository(db).startActivity("climbing", "", "Demo", "Keep notes", "Hard")
        val start = db.dao().workout(id)!!.knownStart!!
        db.close(); open()
        assertEquals(start, db.dao().active()!!.knownStart)
        assertEquals(id, WorkoutRepository(db).start())
        try { WorkoutRepository(db).startActivity("running", "", "", "", null); fail() } catch (_: IllegalArgumentException) {}
        WorkoutRepository(db).finish(id)
        assertTrue(db.dao().workout(id)!!.isComplete)
        assertNotNull(db.dao().workout(id)!!.completedAt)
        assertTrue(db.dao().workoutEntries(id).isEmpty())
    }
    @Test fun manualDurationHasNoInventedTimeAndSharesWithEffortDistanceAndNotes() = runBlocking {
        val id = WorkoutRepository(db).logPast("walking", "", "2026-10-11", null, 3600.0, "Demo hike", "Windy", "Okay", 5.25, "km")
        val w = db.dao().workout(id)!!
        assertNull(w.knownStart); assertNull(w.completedAt); assertTrue(w.isComplete); assertNull(db.dao().active())
        val snapshot = WorkoutRepository(db).backup(); WorkoutRepository(db).restore(BackupCodec.decode(BackupCodec.encode(snapshot)))
        assertEquals(w, db.dao().workout(id))
        val text = rangeContext(AppData(workouts = listOf(WorkoutDetail(w, emptyList()))), "2026-10-11", "2026-10-11")!!
        assertTrue(text.contains("start time unknown")); assertTrue(text.contains("1h 0m 0s")); assertTrue(text.contains("5.25 km")); assertTrue(text.contains("Windy")); assertTrue(text.contains("Okay"))
    }
    @Test fun repeatedImportAndLateHeartRateAreIdempotentAndDifferentIdsStayDistinct() = runBlocking {
        val repo = HealthImportRepository(db); val c = candidate()
        val first = repo.import(c)
        assertEquals(first, repo.import(c)); assertEquals(1, db.dao().workouts().size)
        val later = withSample(withSample(c, 2000, 110), 5000, 130)
        repo.import(later); repo.import(later)
        assertEquals(2, db.healthDao().samples().size)
        repo.import(candidate("demo-2"))
        assertEquals(2, db.dao().workouts().size)
        assertEquals(2, db.healthDao().sessions().size)
    }
    @Test fun attachPreservesStrengthSetsNotesTitleTimingAndIdentityAndBackup() = runBlocking {
        val local = WorkoutRepository(db); val id = local.start(); local.createExercise(id, "Synthetic press")
        local.saveSet(db.dao().workoutEntries(id).single().id, "42.5", "8"); local.saveDetails(id, "Keep title", "Keep notes")
        val before = db.dao().workout(id)!!; val sets = db.dao().sets()
        val c = withSample(candidate(type = "strength"), 3000, 100)
        assertEquals(id, HealthImportRepository(db).import(c, id))
        assertEquals(before, db.dao().workout(id)); assertEquals(sets, db.dao().sets()); assertEquals(1, db.dao().workouts().size)
        val snapshot = local.backup(); val decoded = BackupCodec.decode(BackupCodec.encode(snapshot)); assertEquals(snapshot, decoded)
        local.restore(decoded); assertEquals(snapshot.copy(exportedAt = 1), local.backup().copy(exportedAt = 1))
        val text = sessionText(listOf(WorkoutDetail(before, emptyList(), listOf(HealthDetail(c.session.copy(workoutId = id), c.samples)))))
        assertTrue(text.contains("Keep notes")); assertTrue(text.contains("Source timing")); assertTrue(text.contains("Sample summary"))
    }
    @Test fun revokedSupplementalAccessRetainsDataAndChangedSourceDoesNotChangeLocalSession() = runBlocking {
        val repo = HealthImportRepository(db); val c = withSample(candidate(), 2000, 120)
        val id = repo.import(c)!!; val before = db.dao().workout(id)
        repo.import(c.copy(session = c.session.copy(title = "Changed source", modifiedAt = 2000, hrStatus = "Permission revoked"), samples = emptyList(), heartRateRead = false))
        assertEquals(1, db.healthDao().samples().size); assertEquals(before, db.dao().workout(id))
        assertTrue(db.healthDao().sessions().single().sourceStatus.contains("Source changed"))
        assertEquals(1, repo.noteMissing(1, 20000, emptySet())); assertNotNull(db.dao().workout(id))
        WorkoutRepository(db).deleteWorkout(id); assertNull(repo.import(c)); assertTrue(db.dao().workouts().isEmpty())
        assertNull(db.healthDao().sessions().single().workoutId)
        val b = WorkoutRepository(db).backup(); WorkoutRepository(db).restore(BackupCodec.decode(BackupCodec.encode(b))); assertNull(repo.import(c))
    }
    @Test fun invalidAttachAndInvalidSampleRollBackAndDateOnlyRecordsAreNotSuggested() = runBlocking {
        val c = candidate(); val repo = HealthImportRepository(db)
        try { repo.import(c, 9999); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(db.healthDao().sessions().isEmpty()); assertTrue(db.dao().workouts().isEmpty())
        try { repo.import(withSample(c, 10001, 120)); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(db.healthDao().sessions().isEmpty())
        val manual = Workout(id = 1, startedAt = 0, finished = true, sessionDate = "2026-10-12", activityType = "running", durationSeconds = 600.0)
        val timed = Workout(id = 2, startedAt = 2000, completedAt = 6000, activityType = "running")
        assertEquals(listOf(timed), matchingWorkouts(c.session, listOf(manual, timed)))
        assertFalse(isGarminOrigin("org.fake.GarminConnect")); assertTrue(isGarminOrigin(GARMIN_PACKAGE))
    }
    @Test fun paginationKeepsSeparateRecordsAndUsefulPermissionStates() = runBlocking {
        val seen = mutableListOf<String?>()
        val results = collectHealthPages { token -> seen.add(token); if (token == null) HealthPage(listOf("one"), "next") else HealthPage(listOf("two"), null) }
        assertEquals(listOf("one", "two"), results); assertEquals(listOf(null, "next"), seen)
        assertTrue(HealthAccess(false, false, false).message.contains("revoked"))
        assertTrue(HealthAccess(true, false, true).message.contains("Heart-rate permission"))
        try { collectHealthPages { _: String? -> HealthPage(emptyList<String>(), "repeat") }; fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun attachingToOriginalDateOnlyHistoryPreservesItsRecordsAndDoesNotAddWorkout() = runBlocking {
        db.importedDao().insertImport(HistoryImport("synthetic", "Synthetic", "fixture", 1000, "kg", 0, "", "{}"))
        db.importedDao().insertWorkouts(listOf(ImportedWorkout("original", "synthetic", "2026-10-12", notes = "Keep original note")))
        db.importedDao().insertSets(listOf(ImportedSet("set", "original", "record", 0, "Synthetic press", "", 50.0, 8, null, null, null, "Keep set note", "{}")))
        val before = db.importedDao().workout("original")!!
        val c = withSample(candidate(type = "strength"), 3000, 100)
        HealthImportRepository(db).import(c, attachImported = "original")
        HealthImportRepository(db).import(c)
        assertTrue(db.dao().workouts().isEmpty())
        val after = db.importedDao().workout("original")!!
        assertEquals(before.workout, after.workout); assertEquals(before.sets, after.sets); assertEquals(1, after.health.size)
        assertNull(after.workout.recordedStart); assertNull(after.workout.recordedEnd)
        val b = WorkoutRepository(db).backup(); assertEquals(b, BackupCodec.decode(BackupCodec.encode(b)))
        WorkoutRepository(db).restore(b); assertEquals(after, db.importedDao().workout("original"))
    }
    @Test fun sampleWriteFailureRollsBackSourceAndNewSessionTogether() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_failure BEFORE INSERT ON health_samples BEGIN SELECT RAISE(ABORT, 'synthetic test'); END")
        try { HealthImportRepository(db).import(withSample(candidate(), 2000, 120)); fail() } catch (_: Exception) {}
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(db.healthDao().sessions().isEmpty()); assertTrue(db.healthDao().samples().isEmpty())
    }
    @Test @Config(sdk = [24]) fun offlineLoggingStillWorksOnAndroidSeven() = runBlocking {
        val id = WorkoutRepository(db).startActivity("soccer", "", "", "", null)
        WorkoutRepository(db).finish(id); assertTrue(db.dao().workout(id)!!.isComplete)
    }
}
