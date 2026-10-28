package com.example.fitnotes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
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
class UnifiedMigrationTest {
    @Test fun releasedAndLocalSchemaFamiliesKeepRecordedRowsAndConverge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sources = (9..15).map { "released-$it" to File("schemas/com.example.fitnotes.data.FitDatabase/$it.json") } +
            (10..12).map { "local-$it" to File("src/test/resources/local-schemas/$it.json") }
        for ((label, file) in sources) {
            val schema = JSONObject(file.readText()).getJSONObject("database")
            val name = "migration-$label.db"
            context.deleteDatabase(name)
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
                old.execSQL("INSERT INTO exercises(id,name,nameKey) VALUES(1,'Keep exercise','keep exercise')")
                old.execSQL("INSERT INTO workouts(id,startedAt,completedAt,title,notes) VALUES(1,1000,2000,'Keep title','Keep notes')")
                old.execSQL("INSERT INTO workout_exercises(id,workoutId,exerciseId,position) VALUES(1,1,1,0)")
                old.execSQL("INSERT INTO workout_sets(id,entryId,weightKg,reps,position,comment) VALUES(1,1,42.5,8,0,'Keep actual')")
                if (label.startsWith("local-") && schema.getInt("version") >= 11)
                    old.execSQL("INSERT INTO body_profile VALUES(1,180,NULL,'kg','cm','3M')")
                if (label == "local-12") old.execSQL("INSERT INTO run_links VALUES('source','local:1','',0,1)")
                old.version = schema.getInt("version")
            }
            val db = FitDatabase.open(context)
            // open() owns the production filename; move the synthetic fixture there.
            db.close()
            context.deleteDatabase("fitnotes.db")
            assertTrue(context.getDatabasePath(name).renameTo(context.getDatabasePath("fitnotes.db")))
            val migrated = FitDatabase.open(context)
            try {
                assertEquals(label, 17, migrated.openHelper.readableDatabase.version)
                assertEquals(label, "Keep notes", migrated.dao().workouts().single().notes)
                assertEquals(label, "Keep actual", migrated.dao().sets().single().comment)
                assertEquals(42.5, migrated.dao().sets().single().weightKg, 0.0)
                if (label.startsWith("local-") && schema.getInt("version") >= 11)
                    assertEquals(180.0, migrated.bodyProfileDao().get()!!.heightCm!!, 0.0)
                if (label == "local-12") assertEquals("source", migrated.runDao().all().single().sourceKey)
                migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(label, it.moveToFirst()) }
                val backup = WorkoutRepository(migrated).backup()
                assertEquals(label, backup, BackupCodec.decode(BackupCodec.encode(backup)))
            } finally {
                migrated.close()
                context.deleteDatabase("fitnotes.db")
            }
        }
    }
}
