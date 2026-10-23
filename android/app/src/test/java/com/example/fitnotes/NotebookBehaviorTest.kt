package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotebookBehaviorTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FitDatabase::class.java).build()
    @Test fun mixedTimedAndDateOnlySessionsSortWithoutInventingImportTimes() {
        val day = "2026-10-12"
        val timed = WorkoutDetail(Workout(id = 1, sessionDate = day, startedAt = pickerMillis(day) + 7 * 60 * 60 * 1000L, completedAt = pickerMillis(day) + 8 * 60 * 60 * 1000L), emptyList())
        fun imported(id: String, date: String = day, start: String? = null) =
            ImportedWorkoutDetail(ImportedWorkout(id, "source", date, recordedStart = start), emptyList())
        val data = AppData(workouts = listOf(timed), imported = ImportedData(workouts = listOf(
            imported("date-only-b"), imported("timed", start = "2026-10-12T22:00:00+13:00"),
            imported("date-only-a"), imported("newer", "2026-10-13"), imported("older", "2026-10-11")
        )))
        val sessions = notebookSessions(data)
        assertEquals(listOf("import:newer", "import:timed", "local:1", "import:date-only-a", "import:date-only-b", "import:older"), sessions.map { it.key })
        // The main screen joins separately loaded local and imported histories.
        val joined = notebookSessions(data.copy(imported = ImportedData())) + notebookSessions(data.copy(workouts = emptyList()))
        assertEquals(sessions, sortNotebookSessions(joined.reversed()))
        assertNull(sessions.first { it.key == "import:date-only-a" }.startedMillis)
    }
    @Test fun cardioCommentsAndEditingRoundTripWithoutAppending() = runBlocking {
        val db = database()
        try {
            val repo = WorkoutRepository(db); val id = repo.start(); repo.createExercise(id, "Treadmill")
            val entry = db.dao().workoutEntries(id).single().id
            repeat(3) { repo.saveCardio(entry, "1.25", "mi", "600.5", "Keep this note") }
            val original = db.dao().entrySets(entry).first()
            repo.saveCardio(entry, "2.5", "mi", "700.25", "Edited note", original.id)
            assertEquals(3, db.dao().entrySets(entry).size)
            assertEquals(original.id, db.dao().entrySets(entry).first().id)
            repo.duplicateSet(original.id)
            assertEquals("Edited note", db.dao().entrySets(entry).last().comment)
            val backup = repo.backup(); assertEquals(13, backup.version)
            val decoded = BackupCodec.decode(BackupCodec.encode(backup))
            assertEquals(backup, decoded)
            repo.restore(decoded)
            assertEquals(4, db.dao().entrySets(entry).size)
            assertTrue(sessionText(listOf(WorkoutDetail(db.dao().workout(id)!!, listOf(EntryDetail(db.dao().entry(entry)!!, db.dao().exercises().single(), db.dao().entrySets(entry)))))).contains("2.5 mi"))
            repo.finish(id); val next = repo.repeat(id)
            assertTrue(db.dao().entrySets(db.dao().workoutEntries(next).single().id).isEmpty())
            val before = repo.backup()
            try { repo.saveCardio(entry, "NaN", "km", "1", ""); fail() } catch (_: IllegalArgumentException) {}
            assertEquals(before.sets, repo.backup().sets)
        } finally { db.close() }
    }
    @Test fun legacyBackupsKeepTheirStrengthSetsAndIds() = runBlocking {
        val db = database()
        try {
            val repo = WorkoutRepository(db); val id = repo.start(); repo.createExercise(id, "Squat")
            repo.saveSet(db.dao().workoutEntries(id).single().id, "0", "12")
            for (version in 1..3) {
                val current=repo.backup()
                val old = current.copy(version = version, workouts = current.workouts.map { it.copy(lastActionAt = null) },sets=current.sets.map{it.copy(targetId=null,completedAt=null,loadMode=LoadMode.UNKNOWN)})
                val restored = BackupCodec.decode(BackupCodec.encode(old))
                assertEquals(old, restored); repo.restore(restored)
                assertEquals(0.0, repo.backup().sets.single().weightKg, 0.0)
            }
        } finally { db.close() }
    }
    @Test fun manualMeasurementsKeepPrecisionUnitsCommentsAndDateOnlyAcrossBackup() = runBlocking {
        val db = database()
        try {
            val repo = HistoryImportRepository(db)
            repo.saveMeasurement(null, "Waist", "80.125", "cm", "Centimetres", "2026-10-12", "", "Measured twice")
            val first = db.importedDao().body().single()
            repo.saveMeasurement(first.id, "Ignored rename", "80.0625", "in", "Ignored unit", first.date, "17:42:03", "Updated")
            val updated = db.importedDao().body().single()
            assertEquals(first.id, updated.id); assertEquals("Waist", updated.name); assertEquals("cm", updated.unit)
            assertEquals(80.0625, updated.value, 0.0)
            repo.saveMeasurement(null, "Waist", "81.333", "cm", "Centimetres", "2026-10-11", "", "Date-only")
            val backup = WorkoutRepository(db).backup()
            assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
            assertEquals(13, JSONObject(BackupCodec.analysis(backup)).getInt("backupSchemaVersion"))
            repo.deleteMeasurement(first.id); assertEquals(1, db.importedDao().body().size)
        } finally { db.close() }
    }
    @Test fun previousReferenceExcludesSelfFutureDateOnlySameDayAndExerciseVariations() {
        val set = NotebookSet("s", 60.0, 10, null, null, null, "")
        val exercise = NotebookExercise("e", "Lat pulldown", sets = listOf(set))
        fun session(key: String, day: String, start: Long, end: Long?, name: String = exercise.name) = NotebookSession(key, day, "", "", end != null,
            local = WorkoutDetail(Workout(id = start, startedAt = start, completedAt = end), emptyList()), exercises = listOf(exercise.copy(name = name)))
        val current = session("current", "2026-10-12", 2000, 3000)
        val earlier = session("earlier", "2026-10-12", 500, 1000)
        val finishesLater = session("later", "2026-10-11", 100, 4000)
        val future = session("future", "2026-10-13", 5000, 6000)
        val variation = session("variation", "2026-10-11", 200, 900, "Wide-grip lat pulldown")
        val dateOnly = NotebookSession("date-only", current.date, "", "", true, exercises = listOf(exercise))
        assertEquals(listOf("earlier"), previousExerciseSessions(listOf(current, earlier, finishesLater, future, variation, dateOnly), current, exercise).map { it.first.key })
        assertEquals("1 km  ·  100 m", compactResults(listOf(NotebookSet("a", null, null, 1.0, "km", null, ""), NotebookSet("b", null, null, 100.0, "m", null, ""))))
    }
    @Test fun additiveTwoToThreeMigrationPreservesImportedAndLocalHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "notebook-migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/2.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i); val table = e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indexes = e.optJSONArray("indices")
                if (indexes != null) for (j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES(9,'Squat','squat')")
            old.execSQL("INSERT INTO workouts VALUES(7,1000,NULL,'Preserve','Note')")
            old.execSQL("INSERT INTO workout_exercises VALUES(8,7,9,0)")
            old.execSQL("INSERT INTO workout_sets VALUES(6,8,42.5,10,0)")
            old.execSQL("INSERT INTO history_imports VALUES('source','Original FitNotes','private',1000,'kg',0,'','{}')")
            old.execSQL("INSERT INTO imported_workouts VALUES('original','source','2026-10-11',NULL,NULL,'Imported note')")
            old.version = 2
        }
        val migrated = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(NOTEBOOK_MIGRATION_2_3, ACTIVITY_MIGRATION_3_4, ACTIVITY_MIGRATION_4_5, PHOTO_MIGRATION_5_6, PLAN_MIGRATION_6_7, PLAN_MIGRATION_7_8, TRAINING_MIGRATION_8_9, ACTIVITY_SUMMARY_MIGRATION_9_10, ACTIVITY_PROVIDER_MIGRATION_10_11, WORKOUT_LIFECYCLE_MIGRATION_11_12, PROGRAM_MIGRATION_12_13, PROGRAM_IMPORT_MIGRATION_13_14, LOAD_ADVICE_MIGRATION_14_15).build()
        try {
            assertEquals(6L, migrated.dao().sets().single().id); assertEquals(42.5, migrated.dao().sets().single().weightKg, 0.0)
            assertEquals("", migrated.dao().sets().single().comment); assertEquals("original", migrated.importedDao().workouts().single().id)
            assertNull(migrated.dao().workout(7)!!.completedAt)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
