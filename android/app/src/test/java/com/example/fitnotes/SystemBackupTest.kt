package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SystemBackupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun snapshotKeepsTrainingPlansMeasurementsAndPreferencesAndExcludesPhotos() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        val file = File(context.cacheDir, "system-backup-test.gz")
        try {
            db.dao().insertExercises(listOf(Exercise(1, "Synthetic press", "synthetic press")))
            val prescription = PlanPrescription("Synthetic press", listOf(PlanSetTarget(8, 10, PlanWeight("42.5", "kg"), 90)), "Guidance")
            val plans = WorkoutPlanRepository(db)
            val id = (plans.save(PlanPreview(PlanSpec("Synthetic plan", "", 40, "Warm-up", listOf(prescription), "c".repeat(64)), listOf(ResolvedPlanExercise(prescription, 1)))) as PlanSaveResult.Saved).id
            plans.savePreferences(HandoffPreferences(goals = "Synthetic goal", equipment = "Synthetic equipment"))
            val workout = plans.start(id)
            TrainingRepository(db).complete(db.planDao().runTargets().single().id, "40", "9", "Actual", LoadMode.EXTERNAL)
            HistoryImportRepository(db).saveMeasurement(null, "Synthetic waist", "70.125", "cm", "Centimetres", "2026-10-15", "20:12", "Measurement note")
            val health = HealthSession(healthKey("example.synthetic", "session"), workout, "session", "example.synthetic", "Synthetic source", null,
                1, 1000, 2000, 1000, 3000, null, null, "strength", 0, "Synthetic activity", "Source note", null, "[]", "available", "none")
            db.healthDao().putSession(health)
            db.healthDao().putSamples(listOf(HealthSample(sampleKey(health.key, "example.synthetic", "sample", 2000), health.key, "sample", "example.synthetic", 2000, 90)))
            val photoId = "11111111-1111-4111-8111-111111111111"
            db.photoDao().put(ProgressPhoto(photoId, "2026-10-15", "Front", "Private photo note", photoPath(photoId)))
            context.getSharedPreferences("appearance", 0).edit().putStringSet("favourite_exercises", setOf("synthetic press")).commit()
            db.bodyProfileDao().put(BodyProfile(heightCm = 180.0, birthDate = "2000-01-01", weightUnit = "lb", heightUnit = "ft-in", chartRange = "1Y"))
            val before = WorkoutRepository(db).backup()
            val snapshot = SystemBackupSnapshot.capture(context, db)
            assertEquals(before.copy(exportedAt = snapshot.exportedAt, trainingPreferences = snapshot.trainingPreferences), snapshot)
            SystemBackupSnapshot.write(snapshot, file)
            val decoded = SystemBackupSnapshot.read(file)
            assertEquals(snapshot, decoded)
            assertFalse(BackupCodec.encode(decoded).contains("Private photo note"))
            assertFalse(BackupCodec.encode(decoded).contains(photoId))
            WorkoutRepository(restored).restore(decoded)
            val after = WorkoutRepository(restored).backup()
            assertEquals(snapshot.copy(exportedAt = after.exportedAt), after)
            assertEquals(1, restored.dao().entrySets(restored.dao().workoutEntries(workout).single().id).size)
            assertEquals(1, restored.planDao().sets().size)
            assertEquals("[\"synthetic press\"]", restored.trainingDao().preferences()!!.favouriteKeys)
            assertTrue(restored.photoDao().all().isEmpty())
            assertEquals(1, db.photoDao().all().size)
        } finally { db.close(); restored.close(); file.delete() }
    }

    @Test fun corruptUnsupportedAndOversizedExpandedBackupsAreRejected() {
        val file = File(context.cacheDir, "invalid-system-backup.gz")
        try {
            val empty = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList())
            SystemBackupSnapshot.write(empty, file)
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertThrows(Exception::class.java) { SystemBackupSnapshot.read(file) }
            GZIPOutputStream(file.outputStream()).use { it.write(BackupCodec.encode(empty).replace("\"version\": 14", "\"version\": 1499").toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { SystemBackupSnapshot.read(file) }
            GZIPOutputStream(file.outputStream()).use { output ->
                val block = ByteArray(1024 * 1024) { 32 }
                repeat(51) { output.write(block) }
            }
            assertThrows(IllegalArgumentException::class.java) { SystemBackupSnapshot.read(file) }
        } finally { file.delete() }
    }

    @Test fun automaticBackupRulesOnlyIncludeTheSnapshotNotPhotoFilesOrTheRawDatabase() {
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name"))
            val includes = xml.getElementsByTagName("include")
            assertTrue(includes.length > 0)
            for (i in 0 until includes.length) {
                val attributes = includes.item(i).attributes
                assertEquals("file", attributes.getNamedItem("domain").nodeValue)
                assertEquals(SYSTEM_BACKUP_PATH, attributes.getNamedItem("path").nodeValue)
            }
        }
        val store = SystemBackupStore(context)
        store.preferences.edit().clear().commit()
        assertFalse(store.state().enabled)
        store.enable(true)
        assertTrue(SystemBackupStore(context).state().enabled)
        store.enable(false)
        assertFalse(SystemBackupStore(context).state().enabled)
    }
}
