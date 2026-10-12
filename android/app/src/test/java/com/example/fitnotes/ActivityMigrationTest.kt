package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.flow.first
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
class ActivityMigrationTest {
    @Test fun earlyVersionFourGetsMissingLinkWithoutChangingRecords() = runBlocking { migrate(localOnly = true) }
    @Test fun laterVersionFourKeepsExistingLinksAndRecords() = runBlocking { migrate(localOnly = false) }

    private suspend fun migrate(localOnly: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-v4-migration-$localOnly.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/4.json").readText()).getJSONObject("database")
        val source = HealthSession("org.synthetic.source:record", 7, "record", "org.synthetic.source", "Synthetic", null,
            0, 1000, 2000, 1000, 2000, null, null, "strength", 70, "Source title", "Source note", null, "", "Synthetic sample", "No distance")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                var sql = entity.getString("createSql").replace("$" + "{TABLE_NAME}", table)
                if (localOnly && table == "health_sessions") sql = sql.replace(", `importedWorkoutId` TEXT", "")
                    .replace(", FOREIGN KEY(`importedWorkoutId`) REFERENCES `imported_workouts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL ", "")
                old.execSQL(sql)
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    if (!(localOnly && index.getString("name") == "index_health_sessions_importedWorkoutId"))
                        old.execSQL(index.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                }
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(if (localOnly) "dea4894b31ee3ab43e9d8bda5813b950" else schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES (9, 'Synthetic press', 'synthetic press')")
            old.execSQL("INSERT INTO workouts(id, startedAt, completedAt, title, notes) VALUES (7, 1000, 2000, 'Keep title', 'Keep note')")
            old.execSQL("INSERT INTO workout_exercises VALUES (8, 7, 9, 0)")
            old.execSQL("INSERT INTO workout_sets(id, entryId, weightKg, reps, position, comment) VALUES (6, 8, 42.5, 8, 0, 'Keep set note')")
            old.execSQL("INSERT INTO history_imports VALUES ('synthetic', 'Synthetic', 'fixture', 1000, 'kg', 0, '', '{}')")
            old.execSQL("INSERT INTO imported_workouts VALUES ('original', 'synthetic', '2026-10-12', NULL, NULL, 'Keep original note')")
            val json = HealthBackupJson.encode(source); json.remove("importedWorkoutId")
            val keys = json.keys().asSequence().toList()
            old.execSQL("INSERT INTO health_sessions(" + keys.joinToString(",") { "`$it`" } + ") VALUES (" + keys.joinToString(",") { "?" } + ")",
                keys.map { if (json.isNull(it)) null else json.get(it) }.toTypedArray())
            old.execSQL("INSERT INTO health_samples VALUES (?, ?, 'hr', 'org.synthetic.source', 1500, 120)", arrayOf(
                sampleKey(source.key, "org.synthetic.source", "hr", 1500), source.key))
            if (!localOnly) old.execSQL("UPDATE health_sessions SET workoutId = NULL, importedWorkoutId = 'original'")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(ACTIVITY_MIGRATION_4_5).build()
        try {
            assertEquals(7L, db.dao().workouts().single().id); assertEquals("Keep note", db.dao().workouts().single().notes)
            assertEquals(6L, db.dao().sets().single().id); assertEquals("Keep set note", db.dao().sets().single().comment)
            assertEquals(42.5, db.dao().sets().single().weightKg, 0.0)
            val original = db.importedDao().workout("original")!!
            assertNull(original.workout.recordedStart); assertNull(original.workout.recordedEnd)
            assertEquals("Keep original note", original.workout.notes)
            assertEquals(120L, db.healthDao().samples().single().bpm)
            assertEquals(source.key, db.healthDao().sessions().single().key)
            if (localOnly) {
                assertNull(db.healthDao().sessions().single().importedWorkoutId)
                assertEquals(1, db.dao().observeWorkouts().first().single().health.size)
            } else assertEquals("original", db.healthDao().sessions().single().importedWorkoutId)
            val backup = WorkoutRepository(db).backup(); assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun earlyVersionFourBackupsWithoutImportedLinkRemainReadable() {
        val source = HealthSession("org.synthetic.source:record", 7, "record", "org.synthetic.source", "Synthetic", null,
            0, 1000, 2000, 1000, 2000, null, null, "strength", 70, "", "", null, "", "No samples", "No distance")
        val backup = Backup(exercises = emptyList(), workouts = listOf(Workout(7, 1000, 2000)), entries = emptyList(), sets = emptyList(), healthSessions = listOf(source))
        val json = JSONObject(BackupCodec.encode(backup))
        json.getJSONArray("healthSessions").getJSONObject(0).remove("importedWorkoutId")
        assertEquals(backup, BackupCodec.decode(json.toString()))
    }
}
