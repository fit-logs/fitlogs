package com.example.fitnotes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
class BodyPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun additiveMigrationPreservesAllTablesPhotosAndSettingsAndProfileSurvivesRestart() = runBlocking {
        val name = "body-profile-migration.db"; context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, FitDatabase::class.java, name).build()
        val imports = HistoryImportRepository(db)
        imports.saveMeasurement(null, "Bodyweight", "80", "kgs", "Kilograms", "2026-10-01", "", "Synthetic")
        imports.saveMeasurement(null, "Waist", "85", "cm", "Centimetres", "2026-10-01", "", "Synthetic")
        val photo = ProgressPhoto("synthetic-photo", "2026-10-01", "Other", "Synthetic", "progress-photos/synthetic-photo/image")
        val image = File(context.filesDir, photo.imagePath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        db.photoDao().put(photo)
        WorkoutRepository(db).start()
        db.trainingDao().putPreferences(TrainingPreferences())
        db.openHelper.writableDatabase.execSQL("UPDATE body_measurements SET createdAt = 0")
        val before = WorkoutRepository(db).backup()
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old -> old.execSQL("DROP TABLE body_profile"); old.execSQL("DROP TABLE run_links")
            old.execSQL("ALTER TABLE health_sessions RENAME TO staged_health")
            val healthSchema = JSONObject(File("src/test/resources/local-schemas/11.json").readText()).getJSONObject("database").getJSONArray("entities")
            val healthTable = (0 until healthSchema.length()).map { healthSchema.getJSONObject(it) }.single { it.getString("tableName") == "health_sessions" }
            old.execSQL(healthTable.getString("createSql").replace("\${TABLE_NAME}", "health_sessions"))
            val healthColumns = healthTable.getJSONArray("fields").let { fields -> (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") } }
            old.execSQL("INSERT INTO health_sessions ($healthColumns) SELECT $healthColumns FROM staged_health")
            old.execSQL("DROP TABLE staged_health")
            old.execSQL("CREATE INDEX index_health_sessions_workoutId ON health_sessions(workoutId)")
            old.execSQL("CREATE INDEX index_health_sessions_importedWorkoutId ON health_sessions(importedWorkoutId)")
            // Recreate the verified v10 body table; API 28 SQLite has no DROP COLUMN.
            val entities = JSONObject(File("src/test/resources/local-schemas/10.json").readText()).getJSONObject("database").getJSONArray("entities")
            val body = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == "body_measurements" }
            old.execSQL("ALTER TABLE body_measurements RENAME TO staged_body")
            old.execSQL(body.getString("createSql").replace("\${TABLE_NAME}", "body_measurements"))
            val columns = "id,importId,sourceRecordId,date,time,name,value,unit,unitName,comment,rawValues"
            old.execSQL("INSERT INTO body_measurements ($columns) SELECT $columns FROM staged_body")
            old.execSQL("DROP TABLE staged_body")
            old.execSQL("CREATE INDEX index_body_measurements_importId ON body_measurements(importId)")
            old.version = 10 }
        db = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(BODY_PROFILE_MIGRATION_10_11, RUNNING_MIGRATION_11_12, *UNIFIED_MIGRATIONS_TO_16).build()
        assertEquals(16, db.openHelper.readableDatabase.version)
        assertEquals(before, WorkoutRepository(db).backup().copy(exportedAt = before.exportedAt))
        assertEquals(listOf(photo), db.photoDao().all()); assertArrayEquals(byteArrayOf(1, 2, 3), image.readBytes())
        val profile = BodyProfile(heightCm = 180.0, birthDate = "2000-10-15", weightUnit = "lb", heightUnit = "ft-in", chartRange = "All")
        db.bodyProfileDao().put(profile); db.close()
        db = Room.databaseBuilder(context, FitDatabase::class.java, name).build()
        assertEquals(profile, db.bodyProfileDao().get())
        val backup = WorkoutRepository(db).backup(); val encoded = BackupCodec.encode(backup)
        assertEquals(backup, BackupCodec.decode(encoded))
        val analysis = BackupCodec.analysis(backup)
        assertFalse(JSONObject(analysis).has("bodyProfile")); assertFalse(analysis.contains("2000-10-15"))
        db.bodyProfileDao().clear(); WorkoutRepository(db).restore(BackupCodec.decode(encoded))
        assertEquals(profile, db.bodyProfileDao().get()); assertEquals(listOf(photo), db.photoDao().all()); assertArrayEquals(byteArrayOf(1, 2, 3), image.readBytes())
        db.close(); context.deleteDatabase(name); image.parentFile!!.deleteRecursively(); Unit
    }
    @Test fun editsAndDeletionRecalculateLatestBmiWithoutChangingOtherMeasurements() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        try {
            val repo = HistoryImportRepository(db)
            repo.saveMeasurement(null, "Bodyweight", "80", "kg", "kg", "2025-01-01", "", "Old date stays visible")
            repo.saveMeasurement(null, "Bodyweight", "82", "kg", "kg", "2026-10-15", "20:12", "")
            repo.saveMeasurement(null, "Body Fat", "18", "%", "%", "2026-10-15", "", "")
            val original = db.importedDao().body(); val other = original.single { it.name == "Body Fat" }
            val latest = weightReadings(original).last().record
            repo.saveMeasurement(latest.id, latest.name, "176.3698097479", "lb", "Pounds", latest.date, latest.time, latest.comment)
            assertEquals(24.691358, bmi(weightReadings(db.importedDao().body()).last().kg, 180.0)!!, 1e-6)
            assertEquals("20:12:00", db.importedDao().measurement(latest.id)!!.time)
            repo.deleteMeasurement(latest.id)
            val now = weightReadings(db.importedDao().body()).last()
            assertEquals("2025-01-01", now.record.date); assertEquals("", now.record.time)
            assertEquals(other, db.importedDao().measurement(other.id)); assertTrue(db.dao().workouts().isEmpty())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveMeasurement(null, "Bodyweight", "0", "kg", "kg", "2026-10-15", "", "") } }; Unit
        } finally { db.close() }
    }
    @Test fun consecutiveDateOnlyLogsUseCreationOrderWithoutInventingMeasurementTimes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        try {
            val repo = HistoryImportRepository(db)
            repo.saveMeasurement(null, "Bodyweight", "80", "kg", "kg", "2026-10-15", "", "First")
            repo.saveMeasurement(null, "Bodyweight", "81", "kg", "kg", "2026-10-15", "", "Second")
            val records = db.importedDao().body()
            val latest = latestWeight(weightReadings(records))!!
            assertEquals(81.0, latest.kg, 0.0); assertTrue(records.all { it.time.isEmpty() })
            assertEquals(2, records.size)
            val backup = WorkoutRepository(db).backup(); WorkoutRepository(db).restore(BackupCodec.decode(BackupCodec.encode(backup)))
            assertEquals(latest.record, latestWeight(weightReadings(db.importedDao().body()))!!.record)
            repo.deleteMeasurement(latest.record.id)
            assertEquals(80.0, latestWeight(weightReadings(db.importedDao().body()))!!.kg, 0.0)
        } finally { db.close() }
    }
    @Test fun sourceIdentitiesMakeReimportsIdempotentWithoutDeduplicatingEqualReadings() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        try {
            fun row(id: String, date: String, unit: String = "kgs", value: Double = 80.0) = BodySourceRow(id, date, "", "Bodyweight", value, unit, unit, "", JSONObject().put("_id", id).put("measurement_id", 1).put("value", value).put("date", date).toString())
            val rows = listOf(row("1", "2026-10-01"), row("2", "2026-10-01"), row("3", "2026-10-02", "lbs", weightInUnit(80.0, "lb")))
            val doc = HistoryDocument("body-source", "Original FitNotes native backup", "synthetic.fitnotes", emptyList(), rows)
            val repo = HistoryImportRepository(db)
            val plan = HistoryImportReader.plan(doc, ImportOptions())
            assertTrue(repo.importHistory(plan).changed); assertFalse(repo.importHistory(plan).changed)
            val later = HistoryImportReader.plan(doc.copy(id = "later-backup", body = rows + row("4", "2026-10-03")), ImportOptions())
            repo.importHistory(later)
            assertEquals(4, db.importedDao().body().size); assertEquals(2, db.importedDao().body().count { it.date == "2026-10-01" })
            assertTrue(weightReadings(db.importedDao().body()).all { kotlin.math.abs(it.kg - 80.0) < 1e-8 })
            assertTrue(db.importedDao().body().all { it.time.isEmpty() })
        } finally { db.close() }
    }
}
