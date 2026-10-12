package com.example.fitnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProgressPhotoTest {
    private lateinit var context: Context
    private lateinit var db: FitDatabase
    private lateinit var photos: ProgressPhotoRepository
    private lateinit var workouts: WorkoutRepository
    private val name = "synthetic-progress-photos.db"
    private lateinit var temporary: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        File(context.filesDir, "progress-photos").deleteRecursively()
        temporary = File(context.cacheDir, "synthetic-photo-tests").apply { mkdirs() }
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, FitDatabase::class.java, name).build()
        photos = ProgressPhotoRepository(context, db)
        workouts = WorkoutRepository(db)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name); temporary.deleteRecursively(); File(context.filesDir, "progress-photos").deleteRecursively() }
    private fun image(filename: String = "synthetic.jpg", width: Int = 120, height: Int = 240): File {
        val file = File(temporary, filename)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        return file
    }
    private suspend fun import(file: File, date: String = "2026-01-01", angle: String = "Front"): ProgressPhoto {
        val draft = photos.prepare(Uri.fromFile(file)); photos.save(draft, date, angle, "Synthetic note")
        return db.photoDao().find(draft.photo.id)!!
    }
    @Test fun importEditCancelDeleteAndPersistenceKeepOriginalIndependent() = runBlocking {
        val original = image()
        val cancelled = photos.prepare(Uri.fromFile(original)); photos.discard(listOf(cancelled))
        assertTrue(db.photoDao().all().isEmpty())
        assertFalse(photos.file(cancelled.photo, true).exists())
        val first = import(original); val second = import(original)
        assertEquals(2, db.photoDao().all().size)
        photos.edit(first.id, "2026-02-03", "Side", "Changed synthetic note")
        db.close(); reopen()
        assertEquals("Side", db.photoDao().find(first.id)!!.angle)
        assertEquals("2026-02-03", db.photoDao().all().first().date)
        assertTrue(original.delete())
        assertNotNull(photos.load(first)); assertNotNull(photos.load(first, true))
        photos.delete(first.id)
        assertFalse(photos.file(first).exists()); assertEquals(second.id, db.photoDao().all().single().id)
        val anotherOriginal = image("original-remains.jpg")
        val another = import(anotherOriginal); photos.delete(another.id); assertTrue(anotherOriginal.exists())
    }
    @Test fun invalidCopiesMissingAndCorruptFilesNeverCreateRowsAndRecoverOrphans() = runBlocking {
        val corrupt = File(temporary, "corrupt.jpg").apply { writeText("synthetic invalid image") }
        try { photos.prepare(Uri.fromFile(corrupt)); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(db.photoDao().all().isEmpty())
        assertTrue(File(context.filesDir, "progress-photos/pending").listFiles()!!.isEmpty())
        val pending = photos.prepare(Uri.fromFile(image()))
        val orphan = File(context.filesDir, "progress-photos/${UUID.randomUUID()}").apply { mkdirs() }
        photos.recover(); assertFalse(photos.file(pending.photo, true).exists()); assertFalse(orphan.exists())
        val saved = import(image())
        photos.file(saved).writeText("synthetic corrupted copy")
        assertNull(photos.load(saved))
        photos.file(saved).delete(); assertNull(photos.load(saved)); assertNull(photos.load(saved, true))
        photos.delete(saved.id); assertTrue(db.photoDao().all().isEmpty())
    }
    @Test fun captureDateUsesOriginalExifAndNeverModificationDate() = runBlocking {
        val original = image()
        original.setLastModified(946684800000)
        assertNull(PhotoImages.captureDate(original))
        ExifInterface(original).apply { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2022:07:08 12:13:14"); saveAttributes() }
        assertEquals("2022-07-08", PhotoImages.captureDate(original))
        assertTrue(photos.prepare(Uri.fromFile(original)).captureDateFound)
        ExifInterface(original).apply { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2022:02:31 12:13:14"); saveAttributes() }
        assertNull(PhotoImages.captureDate(original))
    }
    @Test fun allExifRotationsAndLargeLandscapeDecodeAreBounded() {
        val original = image(width = 240, height = 120)
        for (orientation in 1..8) {
            ExifInterface(original).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val decoded = PhotoImages.decode(original, 256)!!
            assertEquals(if (orientation >= 5) 120 else 240, decoded.width)
            assertEquals(if (orientation >= 5) 240 else 120, decoded.height)
            decoded.recycle()
        }
        val large = image("large-synthetic.jpg", 6000, 4000)
        val decoded = PhotoImages.decode(large, 2048)!!
        assertTrue(decoded.width <= 2048 && decoded.height <= 2048)
        assertEquals(1.5, decoded.width.toDouble() / decoded.height, 0.01); decoded.recycle()
    }
    @Test fun optInArchiveRoundTripRepeatedRestoreAndLegacyRestorePreservePhotos() = runBlocking {
        val original = image(); val first = import(original); import(original, "2026-02-02")
        val archive = File(temporary, "synthetic-photo-backup.zip")
        val backup = PhotoBackup(photos, workouts)
        backup.export(Uri.fromFile(archive))
        val jsonOnly = BackupCodec.encode(workouts.backup())
        assertFalse(jsonOnly.contains(first.id)); assertFalse(BackupCodec.analysis(workouts.backup()).contains(first.id))
        photos.delete(first.id)
        repeat(2) {
            val pending = backup.inspect(Uri.fromFile(archive)); assertEquals(2, pending.photos.size)
            backup.restore(pending)
            assertEquals(2, db.photoDao().all().size)
            assertTrue(db.photoDao().all().all { photos.file(it).isFile })
        }
        workouts.restore(BackupCodec.decode(jsonOnly))
        assertEquals(2, db.photoDao().all().size)
        val cancelled = backup.inspect(Uri.fromFile(archive)); backup.discard(cancelled)
        assertFalse(cancelled.directory.exists()); assertEquals(2, db.photoDao().all().size)
        photos.recover()
        assertEquals(3, File(context.filesDir, "progress-photos").listFiles()!!.size) // 2 saved directories + pending
    }
    @Test fun archiveRejectsTraversalAndMissingPhotoWithoutChangingData() = runBlocking {
        val photo = import(image())
        val archive = File(temporary, "synthetic-invalid.zip")
        ZipOutputStream(archive.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../outside")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        try { PhotoBackup(photos, workouts).inspect(Uri.fromFile(archive)); fail() } catch (_: IllegalArgumentException) { }
        assertNotNull(db.photoDao().find(photo.id)); assertTrue(photos.file(photo).exists())
        photos.file(photo).delete()
        try { PhotoBackup(photos, workouts).export(Uri.fromFile(archive)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, db.photoDao().all().size)
    }
    @Test fun failedPublicationAndRestoreRollBackRowsAndKeepExistingFiles() = runBlocking {
        val original = image()
        val saved = import(original)
        val savedFile = photos.file(saved)
        val originalDigest = PhotoBackup.digest(savedFile)
        val archive = File(temporary, "rollback-synthetic.zip")
        val backup = PhotoBackup(photos, workouts)
        backup.export(Uri.fromFile(archive))
        val pending = backup.inspect(Uri.fromFile(archive))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_photos BEFORE INSERT ON progress_photos BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END")
        val draft = photos.prepare(Uri.fromFile(original))
        try { photos.save(draft, "2026-01-01", "Front", ""); fail() } catch (_: Exception) { }
        assertNull(db.photoDao().find(draft.photo.id)); assertFalse(photos.file(draft.photo).exists())
        try { backup.restore(pending); fail() } catch (_: Exception) { }
        assertEquals(saved, db.photoDao().find(saved.id))
        assertTrue(savedFile.isFile); assertEquals(originalDigest, PhotoBackup.digest(savedFile))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_photos")
        backup.discard(pending)
    }
    @Test fun mirroredExifTransformsMoveQuadrantsCorrectly() {
        val file = File(temporary, "quadrants-synthetic.jpg")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        colors.forEachIndexed { index, color ->
            paint.color = color
            val x = (index % 2) * 60f; val y = (index / 2) * 40f
            canvas.drawRect(x, y, x + 60, y + 40, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        val expectedTopLeft = listOf(Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN)
        for (orientation in 1..8) {
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val decoded = PhotoImages.decode(file, 256)!!
            val actual = decoded.getPixel(decoded.width / 4, decoded.height / 4)
            val expected = expectedTopLeft[orientation - 1]
            assertTrue("EXIF orientation $orientation", kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 10 &&
                kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 10 && kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 10)
            decoded.recycle()
        }
    }

}
