package com.example.fitnotes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HistoryImportTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private val options = ImportOptions("kg", mapOf("CSV" to "km", "2" to "km", "3" to "mi"), "h:m:s")
    private val header = "Date,Exercise,Category,Weight,Reps,Distance,Time,Comment\n"
    private val csv = header +
        "2025-02-03,Squat,Legs,40,8,,,\"same, comment\"\n" +
        "2025-02-03,Squat,Legs,40,8,,,\"same, comment\"\n" +
        "2025-02-03,Run,Cardio,,,5,00:25:30,\"steady\npace\"\n" +
        "2025-02-04,Squat,Legs,42.5,6,,,next\n"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
    }
    @After fun cleanup() { db.close() }

    @Test fun csvPreservesDuplicateSetsOrderCommentsCardioAndDates() {
        val document = HistoryImportReader.workoutCsv(csv, "first.csv")
        val plan = HistoryImportReader.plan(document, options)
        assertEquals(2, plan.workouts.size)
        assertEquals(4, plan.sets.size)
        assertTrue(plan.issues.isEmpty())
        assertEquals(listOf("Squat", "Squat", "Run", "Squat"), plan.sets.map { it.exercise })
        assertEquals("same, comment", plan.sets.first().comment)
        assertEquals("steady\npace", plan.sets[2].comment)
        assertEquals(1530.0, plan.sets[2].durationSeconds!!, 0.0)
        assertEquals(5.0, plan.sets[2].distance!!, 0.0)
        assertEquals("km", plan.sets[2].distanceUnit)
        assertTrue(plan.workouts.all { it.recordedStart == null && it.recordedEnd == null })
        assertEquals(document.id, HistoryImportReader.workoutCsv("\uFEFF" + csv.replace("\n", "\r\n")
            .replace("steady\r\npace", "steady\npace"), "renamed.csv").id)
        assertNotEquals(plan.sets[0].id, plan.sets[1].id)
    }

    @Test fun ambiguousUnitsBlockImportAndPoundsConvertOnlyWhenChosen() = runBlocking {
        val document = HistoryImportReader.workoutCsv(header + "2025-01-01,Press,Upper,100,5,,,\n", "units.csv")
        val pending = HistoryImportReader.plan(document, ImportOptions())
        assertFalse(pending.requiredChoices.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { HistoryImportRepository(db).importHistory(pending) } }
        assertTrue(db.importedDao().imports().isEmpty())
        val pounds = HistoryImportReader.plan(document, ImportOptions("lb"))
        assertEquals(45.359237, pounds.sets.single().weightKg!!, 0.0000001)
        assertTrue(pounds.requiredChoices.isEmpty())
    }

    @Test fun rejectsInvalidRowsAndReportsPhysicalLineNumbers() {
        val document = HistoryImportReader.workoutCsv(header +
            "2025-02-30,Squat,Legs,20,8,,,\n" +
            "2025-02-03,Squat,Legs,-1,8,,,\n" +
            "2025-02-03,Squat,Legs,20,8,,,unquoted,comma\n" +
            "2025-02-03,Squat,Legs,20,8,,,good\n", "invalid.csv")
        val plan = HistoryImportReader.plan(document, options)
        assertEquals(1, plan.sets.size)
        assertEquals(3, plan.issues.size)
        assertTrue(plan.issues.any { it.row == "CSV line 4" && it.reason.contains("9") })
        assertTrue(plan.issues.any { it.reason.contains("date") })
        assertThrows(IllegalArgumentException::class.java) { HistoryImportReader.workoutCsv(header + "\"unclosed", "bad.csv") }
    }

    @Test fun validatesCalendarDatesAndExplicitTimeFormats() {
        assertTrue(validHistoryDate("2024-02-29"))
        assertFalse(validHistoryDate("2025-02-29"))
        assertFalse(validHistoryDate("02/03/2025"))
        assertFalse(validHistoryTime("24:00:00"))
        assertTrue(validRecordedTimestamp("2025-02-03T15:20:11.12+13:00"))
        val document = HistoryImportReader.workoutCsv(header + "2025-02-03,Run,Cardio,,,1,12:30,\n", "time.csv")
        assertEquals(750.0, HistoryImportReader.plan(document, options.copy(csvTimeFormat = "m:s")).sets.single().durationSeconds!!, 0.0)
        assertEquals(45000.0, HistoryImportReader.plan(document, options.copy(csvTimeFormat = "h:m")).sets.single().durationSeconds!!, 0.0)
        assertEquals(1, HistoryImportReader.plan(document, options.copy(csvTimeFormat = "seconds")).issues.size)
    }

    @Test fun repeatImportIsNoOpWhileIdenticalRowsRemainAndLocalDataIsUntouched() = runBlocking {
        val local = WorkoutRepository(db)
        val localId = local.start()
        local.createExercise(localId, "Local exercise")
        val entry = db.dao().workoutEntries(localId).single()
        local.saveSet(entry.id, "15", "10")
        val importer = HistoryImportRepository(db)
        val plan = HistoryImportReader.plan(HistoryImportReader.workoutCsv(csv, "one.csv"), options)
        assertTrue(importer.importHistory(plan))
        assertFalse(importer.importHistory(plan.copy(document = plan.document.copy(fileName = "renamed.csv"))))
        assertEquals(4, db.importedDao().sets().size)
        assertEquals(1, db.importedDao().imports().size)
        assertEquals(1, db.dao().entrySets(entry.id).size)
        assertEquals(localId, local.start())
        local.finish(localId)
        val repeated = importer.repeatWorkout(plan.workouts.first().id)
        assertEquals(listOf("Squat", "Run"), db.dao().workoutEntries(repeated).map { db.dao().exercise(it.exerciseId)!!.name })
        assertTrue(db.dao().workoutEntries(repeated).all { db.dao().entrySets(it.id).isEmpty() })
    }

    @Test fun bodyAndSourceProvenanceSurviveBackupAndAnalysisAndReimport() = runBlocking {
        val document = HistoryImportReader.workoutCsv(csv, "source.csv").copy(body = listOf(
            BodySourceRow("1", "2025-02-03", "07:30:00", "Bodyweight", 72.5, "kgs", "Kilograms", "morning", "{}"),
            BodySourceRow("2", "2025-02-03", "07:31:00", "Body Fat", 15.0, "%", "Percent", "", "{}"),
            BodySourceRow("3", "2025-02-04", "08:00:00", "Custom circumference", 31.5, "cm", "Centimetres", "", "{}"),
        ))
        val plan = HistoryImportReader.plan(document, options)
        val importer = HistoryImportRepository(db)
        importer.importHistory(plan)
        val sourceSets = db.importedDao().sets()
        val squatId = db.dao().exercises().single { it.name == "Squat" }.id
        val mapping = ExerciseSourceMapping(document.id, "Squat", "Legs", squatId)
        ExerciseIdentityRepository(db).mapSource(mapping)
        val original = WorkoutRepository(db).backup()
        val json = BackupCodec.encode(original)
        assertEquals(original, BackupCodec.decode(json))
        val analysis = JSONObject(BackupCodec.analysis(original))
        assertEquals(3, analysis.getJSONArray("bodyMeasurements").length())
        assertEquals(2, analysis.getInt("version"))
        assertEquals(7, analysis.getInt("backupSchemaVersion"))
        db.importedDao().clear()
        WorkoutRepository(db).restore(BackupCodec.decode(json))
        assertEquals(3, db.importedDao().body().size)
        assertFalse(importer.importHistory(plan))
        assertEquals(sourceSets, db.importedDao().sets())
        assertEquals(listOf(mapping), db.trainingDao().mappings())
        val legacy = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList(), version = 1)
        assertEquals(legacy, BackupCodec.decode(BackupCodec.encode(legacy)))
        val before = db.importedDao().body()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { WorkoutRepository(db).restore(original.copy(bodyMeasurements = original.bodyMeasurements.map { it.copy(value = Double.NaN) })) } }
        assertEquals(before, db.importedDao().body())
    }

    @Test fun importerInsertionFailureRollsBackProvenanceAndHistory() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON imported_sets BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val plan = HistoryImportReader.plan(HistoryImportReader.workoutCsv(csv, "atomic.csv"), options)
        try { HistoryImportRepository(db).importHistory(plan); fail("Expected insertion failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(db.importedDao().imports().isEmpty())
        assertTrue(db.importedDao().workouts().isEmpty())
        assertTrue(db.importedDao().sets().isEmpty())
        assertTrue(db.dao().exercises().isEmpty())
    }

    @Test fun editingAndDuplicatingImportedCardioPreservesOriginalFieldsAndImportIdentity() = runBlocking {
        val plan = HistoryImportReader.plan(HistoryImportReader.workoutCsv(csv, "editing.csv"), options)
        val importer = HistoryImportRepository(db)
        importer.importHistory(plan)
        val cardio = db.importedDao().sets().first { it.exercise == "Run" }
        importer.updateRecord(cardio.id, "", "", "6.25", "km", "1600", "edited")
        val updated = db.importedDao().set(cardio.id)!!
        assertEquals(cardio.rawValues, updated.rawValues)
        assertEquals(6.25, updated.distance!!, 0.0)
        assertEquals(1600.0, updated.durationSeconds!!, 0.0)
        importer.duplicateRecord(cardio.id)
        assertEquals(5, db.importedDao().sets().size)
        assertEquals(2, db.importedDao().sets().count { it.exercise == "Run" })
        importer.saveNotes(cardio.workoutId, "Edited session notes")
        assertEquals("Edited session notes", db.importedDao().workout(cardio.workoutId)!!.workout.notes)
        importer.deleteRecord(cardio.id)
        assertEquals(4, db.importedDao().sets().size)
        assertFalse(importer.importHistory(plan))
        assertEquals(4, db.importedDao().sets().size)
        val snapshot = WorkoutRepository(db).backup()
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
    }

    @Test fun migrationFromVersionOnePreservesExistingSessionAndSets() = runBlocking<Unit> {
        val name = "import-migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schemaFile = File("schemas/com.example.fitnotes.data.FitDatabase/1.json").takeIf { it.exists() }
            ?: File("app/schemas/com.example.fitnotes.data.FitDatabase/1.json")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
                val indexes = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES(1,'Squat','squat')")
            old.execSQL("INSERT INTO workouts VALUES(1,1000,NULL,'Existing workout','Unsaved session remains unfinished')")
            old.execSQL("INSERT INTO workout_exercises VALUES(1,1,1,0)")
            old.execSQL("INSERT INTO workout_sets VALUES(1,1,50,8,0)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(IMPORT_MIGRATION_1_2, NOTEBOOK_MIGRATION_2_3, ACTIVITY_MIGRATION_3_4, ACTIVITY_MIGRATION_4_5, PHOTO_MIGRATION_5_6, PLAN_MIGRATION_6_7, PLAN_MIGRATION_7_8, TRAINING_MIGRATION_8_9).build()
        try {
            assertEquals(50.0, migrated.dao().entrySets(1).single().weightKg, 0.0)
            assertNull(migrated.dao().workout(1)!!.completedAt)
            assertEquals("Existing workout", migrated.dao().workout(1)!!.title)
            assertTrue(migrated.importedDao().body().isEmpty())
        } finally { migrated.close() }
        context.deleteDatabase(name)
    }

    @Test fun suppliedNativeBackupCountsUnitsCommentsOrderAndRoundTrip() = runBlocking {
        val path = System.getenv("FITNOTES_EXAMPLE_BACKUP")
        Assume.assumeTrue("Native example supplied through environment, never bundled", path != null)
        val source = File(path!!)
        val before = source.readBytes()
        val document = HistoryImportReader.nativeBackup(source, source.name)
        val plan = HistoryImportReader.plan(document, options)
        assertEquals(562, plan.workouts.size)
        assertEquals(10564, plan.sets.size)
        assertEquals(35, plan.cardioCount)
        assertTrue(plan.sets.filter { it.distance != null && JSONObject(it.rawValues).getInt("unit") == 2 }.all { it.distanceUnit == "m" })
        assertTrue(plan.sets.filter { it.distance != null && JSONObject(it.rawValues).getInt("unit") == 3 }.all { it.distanceUnit == "km" })
        assertEquals(25, plan.zeroRepCount)
        assertEquals(29, plan.body.size)
        assertEquals(28, plan.body.count { it.name == "Bodyweight" && it.unit == "kgs" })
        assertEquals(1, plan.body.count { it.name == "Body Fat" && it.unit == "%" })
        assertEquals(50, plan.workouts.count { it.recordedStart != null })
        assertEquals(16, plan.sets.count { it.comment.isNotBlank() })
        assertTrue(plan.issues.toString(), plan.issues.isEmpty())
        assertTrue(plan.sets.zipWithNext().all { it.first.sourceOrder < it.second.sourceOrder })
        val importer = HistoryImportRepository(db)
        assertTrue(importer.importHistory(plan))
        assertFalse(importer.importHistory(plan))
        val backup = WorkoutRepository(db).backup()
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        assertEquals(10564, db.importedDao().sets().size)
        assertEquals(29, db.importedDao().body().size)
        assertArrayEquals(before, source.readBytes())
    }
}
