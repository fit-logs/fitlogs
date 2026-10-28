package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in evidence check on a copy. Private archives are never test resources or Git content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PrivateArchiveRepairTest {
    @Test fun exactRepairPreservesActualRecordsAndHealthAndIsRecoverableAndIdempotent()=runBlocking {
        val path=System.getenv("FITNOTES_AUDIT_DATABASE")
        assumeTrue(path!=null)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="private-repair-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(context,FitDatabase::class.java,name).createFromFile(File(path!!)).addMigrations(EXERCISE_MERGE_MIGRATION_9_10, BODY_PROFILE_MIGRATION_10_11, RUNNING_MIGRATION_11_12, *UNIFIED_MIGRATIONS_TO_16, COACH_MIGRATION_16_17).build()
        try {
            val repo=WorkoutRepository(db);val original=repo.backup()
            var recovery:Backup?=null
            val count=HistoryImportRepository(db).repairExactDuplicates{recovery=repo.backup()}
            assertTrue(count>0);assertEquals(original.copy(exportedAt=1),recovery!!.copy(exportedAt=1))
            val repaired=repo.backup()
            assertEquals(repaired,BackupCodec.decode(BackupCodec.encode(repaired)))
            assertEquals(original.importedWorkouts.size-count,repaired.importedWorkouts.size)
            assertEquals(original.workouts,repaired.workouts);assertEquals(original.sets,repaired.sets)
            assertEquals(original.healthSessions.map{it.key}.toSet(),repaired.healthSessions.map{it.key}.toSet());assertEquals(original.healthSamples,repaired.healthSamples)
            assertEquals(original.imports,repaired.imports);assertEquals(original.plannedWorkouts,repaired.plannedWorkouts)
            val oldIdentity=original.importedSets.map{nativeSetIdentity(it)}.filterNotNull().toSet()
            assertEquals(oldIdentity,repaired.importedSets.map{nativeSetIdentity(it)}.filterNotNull().toSet())
            assertEquals(0,HistoryImportRepository(db).repairExactDuplicates{fail("Second repair must be a no-op")})
            File(File(path).parentFile,"verified-repair-summary.txt").writeText("Merged $count source-identical workouts; ${original.importedWorkouts.size} -> ${repaired.importedWorkouts.size} imported workouts; ${original.importedSets.size} -> ${repaired.importedSets.size} imported sets. Local sets, health samples, plans and import provenance preserved. Second repair made no change.\n")
            repo.restore(recovery!!);assertEquals(original.copy(exportedAt=1),repo.backup().copy(exportedAt=1))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun installedVersionNineMigrationLeavesEveryStoredRowUnchanged()=runBlocking {
        val path=System.getenv("FITNOTES_AUDIT_DATABASE");assumeTrue(path!=null)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val original=android.database.sqlite.SQLiteDatabase.openDatabase(path!!,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        fun rows(db:androidx.sqlite.db.SupportSQLiteDatabase,table:String):List<List<String?>> = db.query("SELECT * FROM $table ORDER BY rowid").use { cursor->buildList { while(cursor.moveToNext())add((0 until cursor.columnCount).map{if(cursor.isNull(it))null else cursor.getString(it)}) } }
        val tables=original.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' AND name != 'room_master_table'",null).use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
        val before=tables.associateWith{table->original.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use{cursor->buildList{while(cursor.moveToNext())add((0 until cursor.columnCount).map{if(cursor.isNull(it))null else cursor.getString(it)})}}}
        original.close()
        val name="private-migration-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(context,FitDatabase::class.java,name).createFromFile(File(path)).addMigrations(EXERCISE_MERGE_MIGRATION_9_10, BODY_PROFILE_MIGRATION_10_11, RUNNING_MIGRATION_11_12, *UNIFIED_MIGRATIONS_TO_16, COACH_MIGRATION_16_17).build()
        try {
            val connection=db.openHelper.writableDatabase
            assertEquals(10,connection.version)
            tables.forEach{table->assertEquals("Preserved table $table",before.getValue(table),rows(connection,table))}
            assertEquals(WorkoutRepository(db).backup().copy(exportedAt=1),BackupCodec.decode(BackupCodec.encode(WorkoutRepository(db).backup())).copy(exportedAt=1))
        }finally{db.close();context.deleteDatabase(name)}
    }

}
