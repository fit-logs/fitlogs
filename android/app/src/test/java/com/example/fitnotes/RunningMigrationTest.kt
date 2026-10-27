package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RunningMigrationTest {
    @Test fun installedVersionElevenGetsOnlyAdditiveChangesAndCurrentBackupsRestoreAssociations()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="running-migration.db";context.deleteDatabase(name)
        val schema=JSONObject(File("src/test/resources/local-schemas/11.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) { val e=entities.getJSONObject(i);val table=e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=e.optJSONArray("indices");for(j in 0 until (indices?.length() ?: 0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)");old.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO workouts(id,startedAt,sessionDate,finished,activityType,title,notes,distance,distanceUnit,durationSeconds) VALUES(1,0,'2026-10-05',1,'running','Keep title','Keep notes',5,'km',1800)")
            old.execSQL("INSERT INTO history_imports VALUES('source','FitNotes','fixture',1000,'kg',0,'','{}')")
            old.execSQL("INSERT INTO imported_workouts VALUES('mixed','source','2026-10-05',NULL,NULL,'Mixed note')")
            old.execSQL("INSERT INTO imported_sets VALUES('row','mixed','original-id',0,'Squat','Legs',80,8,NULL,NULL,NULL,'Keep set','{}')")
            old.execSQL("INSERT INTO body_profile VALUES(1,180,NULL,'kg','cm','3M')")
            old.execSQL("INSERT INTO progress_photos VALUES('photo','2026-10-05','Other','Keep photo','progress-photos/photo/image')")
            old.version=11
        }
        val db=Room.databaseBuilder(context,FitDatabase::class.java,name).addMigrations(RUNNING_MIGRATION_11_12, *UNIFIED_MIGRATIONS_TO_16).build()
        try {
            assertEquals(16,db.openHelper.readableDatabase.version);assertEquals("Keep notes",db.dao().workouts().single().notes)
            assertEquals("original-id",db.importedDao().sets().single().sourceRecordId);assertEquals("Keep photo",db.photoDao().all().single().note)
            assertEquals(180.0,db.bodyProfileDao().get()!!.heightCm!!,0.0)
            db.runDao().put(RunLink("deleted-source","local:1",ignored=true))
            val backup=WorkoutRepository(db).backup();val copy=BackupCodec.decode(BackupCodec.encode(backup));assertEquals(backup,copy)
            WorkoutRepository(db).restore(copy);assertEquals(backup.runLinks,db.runDao().all());assertEquals("Keep notes",db.dao().workouts().single().notes)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
