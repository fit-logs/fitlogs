package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkoutLifecycleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: FitDatabase
    private var now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    private val old = Instant.parse("2026-10-16T05:00:00Z").toEpochMilli()
    private fun lifecycle() = WorkoutLifecycleRepository(db) { now }
    private fun repo() = WorkoutRepository(db, wallClock = { now })
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun seed(start: Long = old, actualAt: Long? = null, empty: Boolean = false, type: String = "strength"): Long {
        db.dao().insertExercise(Exercise(1, "Synthetic press", "synthetic press"))
        val id = db.dao().insertWorkout(Workout(1, start, title = "Keep title", notes = "Keep notes", activityType = type))
        db.dao().insertEntry(WorkoutExercise(1, id, 1, 0))
        if (!empty) db.dao().insertSet(WorkoutSet(1, 1, 42.5, 8, 0, comment = "Keep actual", completedAt = actualAt))
        return id
    }
    @Test fun weekOldLegacyStrengthHasUnknownEstimatedEndAndNeverGainsInventedSetTimes() = runBlocking {
        val id = seed(); val actual = db.dao().sets()
        assertEquals(id, lifecycle().reconcile()); val w = db.dao().workout(id)!!
        assertTrue(w.isComplete); assertNull(db.dao().active()); assertTrue(w.timingEstimated); assertTrue(w.durationUnknown)
        assertNull(w.completedAt); assertNull(localDuration(w)); assertEquals(actual, db.dao().sets())
        assertEquals("Keep title", w.title); assertEquals("Keep notes", w.notes)
        repeat(3) { now += 86400000; assertNull(lifecycle().reconcile()); assertEquals(w, db.dao().workout(id)) }
    }
    @Test fun recordedLastActionGivesBoundedEstimatedEndWithoutChangingPerformedSets() = runBlocking {
        val id = seed(actualAt = old + 3600000); val actual = db.dao().sets()
        lifecycle().reconcile(); val w = db.dao().workout(id)!!
        assertEquals(old + 4500000, w.completedAt); assertEquals(4500.0, localDuration(w)!!, 0.0)
        assertTrue(w.timingEstimated); assertFalse(w.autoFinishAcknowledged); assertEquals(actual, db.dao().sets())
        val backup = repo().backup(); assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        repo().restore(backup); assertNull(lifecycle().reconcile()); assertEquals(w, db.dao().workout(id))
    }
    @Test fun recentSavedActionPreventsIdleTimeoutButHardLimitRespectsRecordedFacts() = runBlocking {
        val start = now - 3 * 3600000; val id = seed(start, now - 30 * 60000)
        assertNull(lifecycle().reconcile()); assertEquals(id, db.dao().active()!!.id)
        db.dao().updateWorkout(db.dao().workout(id)!!.copy(startedAt = now - 7 * 3600000))
        lifecycle().reconcile(); val w = db.dao().workout(id)!!
        assertTrue(w.isComplete); assertTrue(w.completedAt!! >= db.dao().sets().single().completedAt!!)
        assertTrue(w.completedAt!! <= now); assertTrue(w.timingEstimated)
    }
    @Test fun emptyAbandonedWorkoutBecomesRecoverableDraftAndNeverAnActualTrainingRecord() = runBlocking {
        val id = seed(empty = true); lifecycle().reconcile(); val w = db.dao().workout(id)!!
        assertTrue(w.historicalDraft); assertFalse(w.isComplete); assertNull(db.dao().active()); assertEquals("Keep notes", w.notes)
        val data = AppData(workouts = listOf(WorkoutDetail(w, listOf(EntryDetail(db.dao().workoutEntries(id).single(), db.dao().exercise(1)!!, emptyList())))))
        assertTrue(notebookSessions(data).isEmpty()); assertNull(rangeContext(data, "2026-10-01", "2026-10-31"))
        assertEquals(0, JSONObject(BackupCodec.analysis(repo().backup())).getJSONArray("workouts").length())
        lifecycle().resume(id); assertEquals(id, db.dao().active()!!.id); assertTrue(db.dao().sets().isEmpty())
        assertEquals(0.0, activeWorkoutSeconds(db.dao().workout(id)!!, now)!!, 0.0)
    }
    @Test fun nonStrengthSessionsKeepTheirLongIntervalsAndBackwardsClockDoesNotAutoFinish() = runBlocking {
        val id = seed(type = "walking"); assertNull(lifecycle().reconcile()); assertEquals(id, db.dao().active()!!.id)
        db.dao().updateWorkout(db.dao().workout(id)!!.copy(activityType = "strength", startedAt = now - 3 * 3600000, lastActionAt = now + 10000))
        assertNull(lifecycle().reconcile())
        db.dao().updateWorkout(db.dao().workout(id)!!.copy(startedAt = now + 10000))
        assertNull(lifecycle().reconcile()); repo().finish(id)
        assertTrue(db.dao().workout(id)!!.durationUnknown); assertNull(localDuration(db.dao().workout(id)!!))
        assertEquals("Keep actual", db.dao().sets().single().comment)
    }
    @Test fun explicitResumeExcludesTheWeekLongGapAndKeepsTheOriginalWorkoutAndSets() = runBlocking {
        val id = seed(actualAt = old + 3600000); lifecycle().reconcile(); lifecycle().resume(id)
        val before = db.dao().sets(); val resumed = db.dao().workout(id)!!
        val snapshot = repo().backup(); repo().restore(BackupCodec.decode(BackupCodec.encode(snapshot)))
        assertEquals(resumed, db.dao().workout(id))
        assertEquals(old, resumed.startedAt); assertEquals(now, resumed.resumeStartedAt)
        now += 600000; repo().finish(id)
        assertEquals(5100.0, localDuration(db.dao().workout(id)!!)!!, 0.0); assertEquals(before, db.dao().sets())
        assertEquals(1, db.dao().workouts().size)
    }
    @Test fun unknownPriorDurationStaysUnknownAcrossResumeAndFinish() = runBlocking {
        val id = seed(); lifecycle().reconcile(); lifecycle().resume(id)
        now += 600000; assertNull(activeWorkoutSeconds(db.dao().workout(id)!!, now)); repo().finish(id)
        assertNull(localDuration(db.dao().workout(id)!!)); assertTrue(db.dao().workout(id)!!.durationUnknown)
    }
    @Test fun correctedHistoricalEndPersistsAndCannotBeOverwrittenByAnotherStaleCheck() = runBlocking {
        val id = seed(); lifecycle().reconcile()
        val oldZone = java.util.TimeZone.getDefault(); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
        try {
            lifecycle().editTime(id, WorkoutTimeInput("2026-10-16", "18:00+13:00", "2026-10-16", "19:15+13:00"))
            val corrected = db.dao().workout(id)!!; assertFalse(corrected.timingEstimated); assertTrue(corrected.autoFinishAcknowledged)
            assertEquals(4500.0, localDuration(corrected)!!, 0.0)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York")); now += 86400000
            assertNull(lifecycle().reconcile()); assertEquals(corrected, db.dao().workout(id))
            val backup = repo().backup(); repo().restore(BackupCodec.decode(BackupCodec.encode(backup))); assertEquals(corrected, db.dao().workout(id))
        } finally { java.util.TimeZone.setDefault(oldZone) }
    }
    @Test fun pastDraftCopiesStructureOnlyAllowsAnotherActiveWorkoutAndExportsOnlyAfterExplicitSave() = runBlocking {
        val source = seed(start = now - 86400000, actualAt = now - 85000000)
        db.dao().updateWorkout(db.dao().workout(source)!!.copy(finished = true, completedAt = now - 83000000))
        val active = repo().start(); val draft = lifecycle().beginPast(WorkoutTimeInput("2026-10-22"), "local:$source")
        assertEquals(active, db.dao().active()!!.id); assertTrue(db.dao().workout(draft)!!.historicalDraft)
        assertTrue(db.dao().entrySets(db.dao().workoutEntries(draft).single().id).isEmpty())
        try { lifecycle().savePast(draft); fail() } catch (_: IllegalArgumentException) { }
        val entry = db.dao().workoutEntries(draft).single().id
        repo().saveSet(entry, "45", "9"); assertNull(db.dao().entrySets(entry).single().completedAt)
        val before = repo().backup(); val analysis = JSONObject(BackupCodec.analysis(before))
        assertFalse((0 until analysis.getJSONArray("workouts").length()).any { analysis.getJSONArray("workouts").getJSONObject(it).getLong("id") == draft })
        repo().restore(BackupCodec.decode(BackupCodec.encode(before)))
        assertEquals(1, db.dao().entrySets(entry).size); lifecycle().savePast(draft)
        val saved = db.dao().workout(draft)!!; assertTrue(saved.isComplete); assertNull(saved.knownStart); assertNull(saved.completedAt); assertNull(localDuration(saved))
        assertEquals("2026-10-22", saved.sessionDate); assertEquals(active, db.dao().active()!!.id)
        assertEquals(2, JSONObject(BackupCodec.analysis(repo().backup())).getJSONArray("sets").length())
    }
    @Test fun savedRoutineAndOriginalHistoryCopyAsReferencesWithoutPerformedTargets() = runBlocking {
        db.dao().insertExercise(Exercise(1, "Synthetic press", "synthetic press"))
        val target = PlanPrescription("Synthetic press", listOf(PlanSetTarget(8, 10, PlanWeight("40", "kg"))))
        val plan = (WorkoutPlanRepository(db).save(PlanPreview(PlanSpec("Routine", "", 30, "", listOf(target), "c".repeat(64)), listOf(ResolvedPlanExercise(target, 1)))) as PlanSaveResult.Saved).id
        val draft = lifecycle().beginPast(WorkoutTimeInput("2026-10-22"), "plan:$plan")
        assertEquals(1, db.dao().workoutEntries(draft).size); assertTrue(db.dao().sets().isEmpty()); assertTrue(db.planDao().runs().isEmpty())
        assertNull(db.planDao().detail(plan)!!.plan.startedWorkoutId)
        val base = repo().backup().copy(imports = listOf(HistoryImport("source", "Synthetic", "fixture", 1000, "kg", 0, "", "{}")),
            importedWorkouts = listOf(ImportedWorkout("dated", "source", "2026-10-20")),
            importedSets = listOf(ImportedSet("original-set", "dated", "1", 0, "Synthetic press", "Strength", 40.0, 8, null, null, null, "", "{}")))
        repo().restore(base)
        val copied = lifecycle().beginPast(WorkoutTimeInput("2026-10-22"), "import:dated")
        assertEquals(1, db.dao().workoutEntries(copied).size); assertTrue(db.dao().sets().isEmpty()); assertEquals(base.importedSets, db.importedDao().sets())
    }
    @Test fun explicitLongWorkoutOverrideAndMeaningfulSetSavesKeepTheSessionOpen() = runBlocking {
        val id = seed(start = now - 3600000); lifecycle().allowLongWorkout(id)
        val allowed = db.dao().workout(id)!!.staleSuppressedUntil!!
        now += 5 * 3600000; assertNull(lifecycle().reconcile()); assertEquals(id, db.dao().active()!!.id)
        repo().saveSet(1, "45", "9"); assertEquals(now, db.dao().workout(id)!!.lastActionAt)
        now = allowed + 1; lifecycle().reconcile(); assertTrue(db.dao().workout(id)!!.isComplete)
    }
    @Test fun timingInputsSupportUnknownOrEndOnlyOvernightAndRejectAmbiguousDstAndConflicts() {
        val zone = ZoneId.of("Pacific/Auckland")
        val unknown = parseWorkoutTiming(WorkoutTimeInput("2026-10-22"), now, zone); assertNull(unknown.start); assertNull(unknown.end); assertNull(unknown.seconds)
        val endOnly = parseWorkoutTiming(WorkoutTimeInput("2026-10-22", end = "19:00", minutes = "60"), now, zone)
        assertNull(endOnly.start); assertNotNull(endOnly.end); assertEquals(3600.0, endOnly.seconds!!, 0.0)
        val overnight = parseWorkoutTiming(WorkoutTimeInput("2026-10-21", "23:30", "2026-10-22", "00:30"), now, zone)
        assertEquals(3600.0, overnight.seconds!!, 0.0)
        for (input in listOf(WorkoutTimeInput("2026-09-27", "02:30"), WorkoutTimeInput("2026-04-05", "02:30"), WorkoutTimeInput("2026-10-22", "18:00", end = "19:00", minutes = "20"))) {
            try { parseWorkoutTiming(input, now, zone); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun migrationElevenToTwelveKeepsOldWorkoutSetsAndUnknownMetadata() = runBlocking {
        val name = "synthetic-lifecycle-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/11.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i); val table = e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("$" + "{TABLE_NAME}", table)); val indices = e.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO workouts(id,startedAt,title,notes) VALUES(7,1000,'Keep title','Keep note')")
            old.execSQL("INSERT INTO exercises(id,name,nameKey) VALUES(1,'Synthetic press','synthetic press')")
            old.execSQL("INSERT INTO workout_exercises(id,workoutId,exerciseId,position) VALUES(1,7,1,0)")
            old.execSQL("INSERT INTO workout_sets(id,entryId,weightKg,reps,position) VALUES(1,1,42.5,8,0)"); old.version = 11
        }
        val migrated = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(WORKOUT_LIFECYCLE_MIGRATION_11_12, PROGRAM_MIGRATION_12_13, PROGRAM_IMPORT_MIGRATION_13_14).build()
        try { assertEquals("Keep note", migrated.dao().workout(7)!!.notes); assertNull(migrated.dao().workout(7)!!.lastActionAt); assertNull(migrated.dao().sets().single().completedAt) }
        finally { migrated.close(); context.deleteDatabase(name) }
    }
}
