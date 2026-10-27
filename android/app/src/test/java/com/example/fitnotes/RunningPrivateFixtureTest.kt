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
import java.security.MessageDigest

/** Optional verification on an authorized local COPY. Never changes or packages the source archive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RunningPrivateFixtureTest {
    @Test fun existingFitnotesRunRowsAreReusedAndTheirOriginalFieldsAreUnchanged()=runBlocking {
        val path=System.getenv("FITNOTES_RUNNING_FIXTURE");assumeTrue(path!=null)
        val file=File(path!!);fun digest()=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        val original=digest();val context=ApplicationProvider.getApplicationContext<Context>();val name="running-private-${System.nanoTime()}.db"
        val db=Room.databaseBuilder(context,FitDatabase::class.java,name).createFromFile(file).addMigrations(EXERCISE_MERGE_MIGRATION_9_10,BODY_PROFILE_MIGRATION_10_11,RUNNING_MIGRATION_11_12).build()
        try {
            val snapshot=runningSnapshot(db);val rows=db.importedDao().sets().filter{isRunningName(it.exercise)}
            assertTrue("Fixture must contain the existing running records",rows.isNotEmpty())
            val runs=runningRecords(snapshot);rows.forEach { row -> val run=runs.single{it.importedSetId==row.id}
                assertEquals(distanceInMetres(row.distance,row.distanceUnit),run.distanceMetres);assertEquals(row.durationSeconds,run.times.recorded)
                val parent=snapshot.imported.workouts.single{it.workout.id==row.workoutId};if(parent.sets.size!=1 || parent.workout.recordedStart==null)assertNull(run.start);assertNull(run.times.timer);assertNull(run.times.moving)
            }
            assertEquals(snapshot.imported.workouts.flatMap{it.sets}.sortedBy{it.id},db.importedDao().sets().sortedBy{it.id})
            assertEquals(original,digest())
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun availableCsvExportCanBeImportedTwiceWithoutChangingTheOriginal()=runBlocking {
        val source=File("../../data/fitnotes_export.csv");assumeTrue(source.exists());val bytes=source.readBytes()
        val context=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build()
        try {
            val document=HistoryImportReader.workoutCsv(bytes.toString(Charsets.UTF_8),source.name)
            val plan=HistoryImportReader.plan(document,ImportOptions("kg",mapOf("CSV" to "km"),"h:m:s"));val repo=HistoryImportRepository(db)
            assertTrue(repo.importHistory(plan).changed);assertFalse(repo.importHistory(plan).changed)
            assertEquals(plan.sets.size,db.importedDao().sets().size);assertArrayEquals(bytes,source.readBytes())
        }finally{db.close()}
    }
}
