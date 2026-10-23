package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.notebookSessions
import com.example.fitnotes.ui.suggestedActualWeight
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PerSetPlanTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private lateinit var plans: WorkoutPlanRepository
    private val name = "synthetic-per-set-plans.db"
    private val text = """Explanation.
```text
WORKOUT_PLAN_V2
{"title":"Synthetic upper body","durationMinutes":45,"warmUp":"General warm-up.","note":"General guidance.","exercises":[
{"name":"Synthetic press","note":"Keep shoulder position steady.","sets":[
{"repsMin":8,"repsMax":10,"weight":"40.50","unit":"kg","restSeconds":90},
{"repsMin":6,"repsMax":8,"weight":"100.25","unit":"lb","restSeconds":120},
{"repsMin":12,"repsMax":12,"unit":"bodyweight"},
{"repsMin":10,"repsMax":10}]}]}
END_WORKOUT_PLAN
```"""
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(name); open()
        db.dao().insertExercise(Exercise(1, "Synthetic press", "synthetic press")); Unit
    }
    private fun open() { db = Room.databaseBuilder(context, FitDatabase::class.java, name).build(); plans = WorkoutPlanRepository(db) }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun parse(value: String = text) = WorkoutPlanParser.parse(value, "")
    private fun preview(value: String = text): PlanPreview { val spec = parse(value); return PlanPreview(spec, spec.exercises.map { ResolvedPlanExercise(it, 1) }) }
    private suspend fun save(value: PlanPreview = preview()) = (plans.save(value) as PlanSaveResult.Saved).id
    private suspend fun reject(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }

    @Test fun v2IndividualTargetsUnitsRestAndUnscheduledDateArePreserved() {
        val plan = parse()
        assertEquals(plan, parse(text.replace("\n", "\r\n")))
        assertEquals("", plan.date); assertEquals("General warm-up.", plan.warmUp)
        val targets = plan.exercises.single().setTargets
        assertEquals(4, targets.size); assertEquals(PlanWeight("40.50", "kg"), targets[0].weight)
        assertEquals(PlanWeight("100.25", "lb"), targets[1].weight); assertEquals(120, targets[1].restSeconds)
        assertEquals(PlanWeight(null, "bodyweight"), targets[2].weight); assertEquals(PlanWeight(null, null), targets[3].weight)
        assertEquals("45.4726350925", suggestedActualWeight(targets[1]))
    }
    @Test fun malformedVersionTwoTargetsAndUnknownFieldsFailClearly() = runBlocking {
        for (value in listOf(text.replace("\"repsMin\":8", "\"repsMin\":0"), text.replace("\"repsMax\":10", "\"repsMax\":2"),
            text.replace("\"restSeconds\":90", "\"restSeconds\":-1"), text.replace("\"restSeconds\":90", "\"restSeconds\":1.5"),
            text.replace("\"unit\":\"kg\"", "\"unit\":\"stone\""), text.replace("\"note\":\"General guidance.\"", "\"note\":\"General guidance.\",\"unknown\":true"),
            text.replace("\"repsMin\":8", "\"repsMin\":8,\"repsMin\":9"), text.replace("WORKOUT_PLAN_V2", "WORKOUT_PLAN_V99"), text + text)) reject { parse(value) }
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(db.planDao().plans().isEmpty())
    }
    @Test fun importEditSaveCloseAndReopenDoNotCreateTraining() = runBlocking {
        val baseline = WorkoutRepository(db).backup()
        val parsed = preview(); val editable = parsed.spec.editable(parsed.resolutions)
        assertTrue(db.planDao().plans().isEmpty()) // parse/cancel have no database effects
        val id = save(editable.validated())
        val saved = db.planDao().detail(id)!!
        val changed = saved.spec.editable(saved.ordered.map { ResolvedPlanExercise(it.prescription, it.exercise.id) }, editingId = id).copy(title = "Edited synthetic plan")
        plans.save(changed.validated(), editingId = id)
        db.close(); open()
        assertEquals("Edited synthetic plan", db.planDao().detail(id)!!.plan.title)
        assertEquals(baseline.workouts, db.dao().workouts()); assertEquals(baseline.sets, db.dao().sets()); assertNull(db.dao().active())
        assertTrue(notebookSessions(AppData(workouts = emptyList(), loading = false)).isEmpty())
        assertEquals(4, db.planDao().sets().size)
    }
    @Test fun explicitStartCreatesOneActiveSessionAndCopiedSuggestionsAreIndependent() = runBlocking {
        val id = save(); val before = System.currentTimeMillis(); val actual = plans.start(id)
        assertEquals(actual, plans.start(id)); assertTrue(db.dao().workout(actual)!!.startedAt >= before)
        assertEquals(1, db.dao().workouts().size); assertTrue(db.dao().sets().isEmpty()); assertEquals(4, db.planDao().runTargets().size)
        val original = db.planDao().detail(id)!!.spec
        val runTarget = db.planDao().runTargets().first()
        plans.updateRunTarget(runTarget.id, runTarget.target.copy(weight = PlanWeight("42.5", "kg")))
        assertEquals(original, db.planDao().detail(id)!!.spec); assertTrue(db.dao().sets().isEmpty())
        val repo = WorkoutRepository(db); repo.saveSet(db.dao().workoutEntries(actual).single().id, "44", "7"); repo.finish(actual)
        assertEquals(1, db.dao().sets().size); assertEquals(44.0 * 7, db.dao().sets().sumOf { it.weightKg * it.reps }, 0.0)
        val second = plans.start(id); assertNotEquals(actual, second)
        assertEquals(4, db.planDao().runTargets().count { it.entryId == db.dao().workoutEntries(second).single().id })
        assertEquals("40.50", db.planDao().runDetail(second)!!.exercises.single().ordered.first().weightValue)
        assertEquals(original, db.planDao().detail(id)!!.spec)
    }
    @Test fun editingOriginalPlanDoesNotRewriteAnExistingWorkoutSnapshot() = runBlocking {
        val id = save(); val actual = plans.start(id); val before = db.planDao().runDetail(actual)!!
        val detail = db.planDao().detail(id)!!
        val edited = detail.spec.copy(warmUp = "Edited warm-up", exercises = detail.spec.exercises.map { it.copy(note = "Edited guidance", setTargets = listOf(PlanSetTarget(5, 5, PlanWeight("99", "kg"), 60))) })
        plans.save(PlanPreview(edited, edited.exercises.map { ResolvedPlanExercise(it, 1) }), editingId = id)
        assertEquals(before, db.planDao().runDetail(actual)); assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun backupSixRetainsTargetsRunsGuidanceAndOldFiveBackupsExpandUniformSets() = runBlocking {
        val id = save(); plans.start(id)
        val repo = WorkoutRepository(db); val backup = repo.backup()
        assertEquals(13, backup.version); val decoded = BackupCodec.decode(BackupCodec.encode(backup)); assertEquals(backup, decoded)
        repo.restore(decoded); assertEquals(backup.plannedSets, db.planDao().sets()); assertEquals(backup.runTargets, db.planDao().runTargets())
        val plan = backup.plannedWorkouts.single().copy(warmUp = "", legacyGuidance = "")
        val exercise = backup.plannedExercises.single().copy(sets = 2)
        val old = backup.copy(version = 5, workouts = backup.workouts.map { it.copy(lastActionAt = null) }, plannedWorkouts = listOf(plan), plannedExercises = listOf(exercise), plannedSets = emptyList(), planRuns = emptyList(), runTargets = emptyList(), entries = backup.entries.map { it.copy(planNote = "") })
        repo.restore(BackupCodec.decode(BackupCodec.encode(old)))
        assertEquals(2, db.planDao().sets().size); assertEquals(2, db.planDao().runTargets().size)
        assertEquals("Keep shoulder position steady.", db.dao().entries().single().planNote)
    }
    @Test fun explicitRecoveryClearsAccidentalTimestampButKeepsUnparsedLegacyInstructions() = runBlocking {
        val raw = SESSION_PLAN_PREFIX + "Maybe 3 x 8? DB press 30? Warm up however feels right."
        val actual = WorkoutRepository(db).startActivity("strength", "", "Synthetic mistaken import", raw, null)
        WorkoutRepository(db).addExercise(actual, 1)
        val before = WorkoutRepository(db).backup(); val recovery = plans.recoveryPreview(actual)
        assertEquals(raw, recovery.spec.legacyGuidance); assertTrue(recovery.spec.exercises.single().setTargets.isEmpty())
        assertEquals(before.workouts, db.dao().workouts()) // preview/cancel retains timestamp
        val id = (plans.save(recovery, recoveryId = actual) as PlanSaveResult.Saved).id
        assertNull(db.dao().workout(actual)); assertNull(db.dao().active()); assertTrue(db.dao().sets().isEmpty())
        assertEquals(raw, db.planDao().detail(id)!!.plan.legacyGuidance); assertEquals("", db.planDao().detail(id)!!.plan.scheduledDate)
    }
    @Test fun legacyImportWithoutExercisesCanRemainAnEditableUnspecifiedPlan() = runBlocking {
        val actual = WorkoutRepository(db).startActivity("strength", "", "Synthetic empty import", SESSION_PLAN_PREFIX + "Choose exercises later.", null)
        val recovery = plans.recoveryPreview(actual)
        assertTrue(recovery.spec.exercises.isEmpty())
        val id = (plans.save(recovery, recoveryId = actual) as PlanSaveResult.Saved).id
        assertTrue(db.dao().workouts().isEmpty()); reject { plans.start(id) }
        val original = db.planDao().detail(id)!!.spec
        val explicit = original.copy(exercises = listOf(PlanPrescription("Synthetic press", emptyList())))
        plans.save(PlanPreview(explicit, explicit.exercises.map { ResolvedPlanExercise(it, 1) }), editingId = id)
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(db.planDao().sets().isEmpty())
    }
    @Test fun recoveryCannotDiscardTrainingLoggedAfterItsPreview() = runBlocking {
        val repo = WorkoutRepository(db); val actual = repo.startActivity("strength", "", "Synthetic import", SESSION_PLAN_PREFIX + "Unclear guidance.", null)
        repo.addExercise(actual, 1); val preview = plans.recoveryPreview(actual)
        repo.saveSet(db.dao().workoutEntries(actual).single().id, "20", "8")
        reject { plans.save(preview, recoveryId = actual) }
        assertNotNull(db.dao().workout(actual)); assertEquals(1, db.dao().sets().size); assertTrue(db.planDao().plans().isEmpty())
    }
    @Test fun returningAnEmptyStartedPlanKeepsTheOriginalAndEditedSuggestions() = runBlocking {
        val original = save(); val actual = plans.start(original)
        val row = db.planDao().runTargets().first()
        plans.updateRunTarget(row.id, row.target.copy(weight = PlanWeight("42.75", "kg")))
        val recovery = plans.recoveryPreview(actual)
        val recovered = (plans.save(recovery, recoveryId = actual) as PlanSaveResult.Saved).id
        assertNotEquals(original, recovered); assertNull(db.dao().workout(actual)); assertNull(db.dao().active())
        assertEquals("40.50", db.planDao().detail(original)!!.ordered.first().sets.first().weightValue)
        assertEquals("42.75", db.planDao().detail(recovered)!!.ordered.first().sets.first().weightValue)
        assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun recoveryRejectsRecordedActivityAndRealImports() = runBlocking {
        val repo = WorkoutRepository(db); val actual = repo.startActivity("strength", "", "Synthetic import", SESSION_PLAN_PREFIX + "Notes.", null)
        repo.addExercise(actual, 1)
        db.dao().updateWorkout(db.dao().workout(actual)!!.copy(durationSeconds = 900.0))
        reject { plans.recoveryPreview(actual) }
        db.dao().updateWorkout(db.dao().workout(actual)!!.copy(durationSeconds = null, effort = "Hard"))
        reject { plans.recoveryPreview(actual) }
        assertNotNull(db.dao().workout(actual)); assertTrue(db.planDao().plans().isEmpty())
    }
    @Test fun versionSevenMigrationExpandsTargetsAndPreservesPerformedRows() = runBlocking {
        db.close(); context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/7.json").readText()).getJSONObject("database")
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
            old.execSQL("INSERT INTO workouts(id, startedAt, title, notes) VALUES (5, 1000, 'Preserve title', 'Preserve note')")
            old.execSQL("INSERT INTO workout_exercises VALUES (6, 5, 1, 0)")
            old.execSQL("INSERT INTO workout_sets(id, entryId, weightKg, reps, position, comment) VALUES (7, 6, 20, 8, 0, 'Preserve actual')")
            old.execSQL("INSERT INTO planned_workouts VALUES ('plan', 'Synthetic plan', '2026-10-14', 45, 'Guidance', 1000, ?, 5)", arrayOf("a".repeat(64)))
            old.execSQL("INSERT INTO planned_exercises VALUES (8, 'plan', 1, 0, 'Synthetic press', 3, 8, 10, '40.50', 'lb', 'Exercise guidance')")
            old.version = 7
        }
        db = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(PLAN_MIGRATION_7_8, TRAINING_MIGRATION_8_9, ACTIVITY_SUMMARY_MIGRATION_9_10, ACTIVITY_PROVIDER_MIGRATION_10_11, WORKOUT_LIFECYCLE_MIGRATION_11_12, PROGRAM_MIGRATION_12_13, PROGRAM_IMPORT_MIGRATION_13_14, LOAD_ADVICE_MIGRATION_14_15).build(); plans = WorkoutPlanRepository(db)
        assertEquals(3, db.planDao().sets().size); assertEquals(3, db.planDao().runTargets().size)
        assertEquals("Preserve actual", db.dao().sets().single().comment); assertEquals("40.50", db.planDao().sets().first().weightValue)
        assertEquals(1000, db.dao().workout(5)!!.startedAt); assertEquals("Exercise guidance", db.dao().entries().single().planNote)
    }
}
