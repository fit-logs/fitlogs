package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.notebookSessions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkoutPlanPersistenceTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private lateinit var repo: WorkoutRepository
    private lateinit var plans: WorkoutPlanRepository
    private val name = "synthetic-plans.db"
    private val block = """WORKOUT_PLAN_V2
{"title":"Synthetic planned session","durationMinutes":40,"note":"Keep targets separate.","exercises":[
{"name":"Synthetic press","sets":[{"repsMin":8,"repsMax":10,"weight":"100.25","unit":"lb"},{"repsMin":8,"repsMax":10,"weight":"100.25","unit":"lb"},{"repsMin":8,"repsMax":10,"weight":"100.25","unit":"lb"}]}]}
END_WORKOUT_PLAN"""
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); reopen()
        db.dao().insertExercise(Exercise(1, "Synthetic press", "synthetic press"))
        Unit
    }
    private fun reopen() { db = Room.databaseBuilder(context, FitDatabase::class.java, name).build(); repo = WorkoutRepository(db); plans = WorkoutPlanRepository(db) }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun preview(text: String = block): PlanPreview { val spec = WorkoutPlanParser.parse(text, "2026-10-14"); return PlanPreview(spec, spec.exercises.map { ResolvedPlanExercise(it, exerciseId = 1) }) }
    private suspend fun saved(preview: PlanPreview = preview()) = (plans.save(preview) as PlanSaveResult.Saved).id
    private suspend fun reject(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: android.database.sqlite.SQLiteException) {} }

    @Test fun previewCancelAndUnresolvedResolutionWriteNothing() = runBlocking {
        val before = repo.backup()
        val p = preview()
        val afterPreview = repo.backup()
        assertEquals(before.copy(exportedAt = afterPreview.exportedAt), afterPreview)
        reject { plans.save(p.copy(resolutions = p.resolutions.map { it.copy(exerciseId = null) })) }
        val after = repo.backup()
        assertEquals(before.copy(exportedAt = after.exportedAt), after)
    }
    @Test fun savedPlanSurvivesReopenWithoutCreatingPerformedRecords() = runBlocking {
        val id = saved()
        db.close(); reopen()
        val detail = db.planDao().detail(id)!!
        assertEquals("2026-10-14", detail.plan.scheduledDate)
        assertEquals(PlanWeight("100.25", "lb"), detail.ordered.single().prescription.setTargets.first().weight)
        assertEquals(8, detail.ordered.single().target.repsMin); assertEquals(10, detail.ordered.single().target.repsMax)
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(db.dao().sets().isEmpty()); assertNull(db.dao().active())
        assertTrue(notebookSessions(AppData(workouts = emptyList(), loading = false)).isEmpty())
    }
    @Test fun startingIsIdempotentAndActualResultsNeverAlterTargets() = runBlocking {
        val id = saved(); val actual = plans.start(id)
        assertEquals(actual, plans.start(id))
        assertEquals(1, db.dao().workouts().size); assertTrue(db.dao().sets().isEmpty())
        assertFalse(db.dao().workout(actual)!!.isComplete); assertNull(db.dao().workout(actual)!!.durationSeconds)
        reject { repo.finish(actual) }
        val entry = db.dao().workoutEntries(actual).single()
        repo.saveSet(entry.id, "45.5", "7"); repo.finish(actual)
        assertEquals(45.5, db.dao().sets().single().weightKg, 0.0)
        assertEquals(7, db.dao().sets().single().reps)
        assertEquals(PlanWeight("100.25", "lb"), db.planDao().detail(id)!!.ordered.single().prescription.setTargets.first().weight)
        assertEquals(3, db.planDao().detail(id)!!.ordered.single().target.sets)
        assertEquals(1, db.dao().sets().size)
    }
    @Test fun backupRestoresPlansPreferencesReferencesAndOldBackupsRemainReadable() = runBlocking {
        val id = saved(); val actual = plans.start(id)
        repo.saveSet(db.dao().workoutEntries(actual).single().id, "40", "8")
        plans.savePreferences(HandoffPreferences(goals = "Synthetic goals", equipment = "Synthetic equipment", constraints = "Synthetic constraints", activityPreference = "strength", availableMinutes = 40, lastTargetDate = "2026-10-14"))
        val backup = repo.backup()
        assertEquals(7, backup.version)
        val decoded = BackupCodec.decode(BackupCodec.encode(backup)); assertEquals(backup, decoded)
        db.planDao().delete(id); repo.restore(decoded)
        assertEquals(actual, db.planDao().detail(id)!!.plan.startedWorkoutId)
        assertEquals("Synthetic goals", db.planDao().preferences()!!.goals)
        assertEquals(backup.sets, db.dao().sets())
        assertFalse(JSONObject(BackupCodec.analysis(backup)).has("plannedWorkouts"))
        for (version in 1..4) {
            val old = Backup(version = version, exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList())
            assertEquals(old, BackupCodec.decode(BackupCodec.encode(old)))
        }
    }
    @Test fun duplicateWarningAndDeliberateCopyDoNotDuplicateActualSessions() = runBlocking {
        val first = saved()
        assertEquals(PlanSaveResult.Duplicate(first), plans.save(preview(block.replace("\n", "\r\n"))))
        assertEquals(1, db.planDao().plans().size)
        val second = plans.save(preview(), allowDuplicate = true) as PlanSaveResult.Saved
        assertNotEquals(first, second.id); assertEquals(2, db.planDao().plans().size)
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun concurrentSaveOfOneBlockCreatesOnePlanAndOneDuplicateWarning() = runBlocking {
        val results = List(2) { async(Dispatchers.Default) { plans.save(preview()) } }.awaitAll()
        assertEquals(1, results.count { it is PlanSaveResult.Saved })
        assertEquals(1, results.count { it is PlanSaveResult.Duplicate })
        assertEquals(1, db.planDao().plans().size)
        assertEquals(1, db.planDao().exercises().size)
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun explicitCreationAndWriteFailuresAreAtomic() = runBlocking {
        val spec = WorkoutPlanParser.parse(block.replace("Synthetic press", "New synthetic movement"), "2026-10-14")
        val pending = PlanPreview(spec, spec.exercises.map { ResolvedPlanExercise(it, createName = it.name) })
        assertEquals(1, db.dao().exercises().size)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_target BEFORE INSERT ON planned_exercises BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        reject { plans.save(pending) }
        assertEquals(1, db.dao().exercises().size); assertTrue(db.planDao().plans().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_target")
        val id = saved(pending)
        assertEquals("New synthetic movement", db.planDao().detail(id)!!.ordered.single().exercise.name)
        assertEquals(2, db.dao().exercises().size)
    }
    @Test fun cardioOnlyCatalogueEntriesCannotReceiveStrengthTargets() = runBlocking {
        val actual = repo.start(); repo.addExercise(actual, 1); repo.saveCardio(db.dao().workoutEntries(actual).single().id, "2", "km", "600", "Synthetic cardio")
        reject { plans.save(preview()) }
        assertTrue(db.planDao().plans().isEmpty()); assertEquals(1, db.dao().sets().size)
    }
    @Test fun versionSixMigrationRetainsExistingWorkoutAndPhotoRows() = runBlocking {
        db.close(); context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/6.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES (1, 'Synthetic press', 'synthetic press')")
            old.execSQL("INSERT INTO workouts(id, startedAt, completedAt, title, notes) VALUES (5, 1000, 2000, 'Preserve title', 'Preserve note')")
            old.execSQL("INSERT INTO workout_exercises VALUES (6, 5, 1, 0)")
            old.execSQL("INSERT INTO workout_sets(id, entryId, weightKg, reps, position, comment) VALUES (7, 6, 20, 8, 0, 'Preserve set')")
            old.execSQL("INSERT INTO progress_photos VALUES ('synthetic-photo', '2026-10-13', 'Front', 'Preserve photo', 'synthetic/reference')")
            old.version = 6
        }
        db = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(PLAN_MIGRATION_6_7, PLAN_MIGRATION_7_8, TRAINING_MIGRATION_8_9).build(); repo = WorkoutRepository(db); plans = WorkoutPlanRepository(db)
        assertEquals("Preserve note", db.dao().workout(5)!!.notes); assertEquals("Preserve set", db.dao().sets().single().comment)
        assertEquals("Preserve photo", db.photoDao().all().single().note); assertTrue(db.planDao().plans().isEmpty())
    }
}
