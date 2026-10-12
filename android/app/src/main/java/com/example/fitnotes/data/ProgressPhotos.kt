package com.example.fitnotes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

val PHOTO_ANGLES = listOf("Front", "Side", "Back", "Other")

@Entity(tableName = "progress_photos", indices = [Index("date")])
data class ProgressPhoto(
    @PrimaryKey val id: String,
    val date: String,
    val angle: String,
    val note: String,
    val imagePath: String,
)

data class PhotoDraft(val photo: ProgressPhoto, val captureDateFound: Boolean)

@Dao
interface ProgressPhotoDao {
    @Query("SELECT * FROM progress_photos ORDER BY date DESC, id DESC") fun observe(): Flow<List<ProgressPhoto>>
    @Query("SELECT * FROM progress_photos ORDER BY date DESC, id DESC") suspend fun all(): List<ProgressPhoto>
    @Query("SELECT * FROM progress_photos WHERE id = :id") suspend fun find(id: String): ProgressPhoto?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(photo: ProgressPhoto)
    @Query("UPDATE progress_photos SET date = :date, angle = :angle, note = :note WHERE id = :id") suspend fun edit(id: String, date: String, angle: String, note: String)
    @Query("DELETE FROM progress_photos WHERE id = :id") suspend fun delete(id: String)
}

val PHOTO_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS progress_photos (id TEXT NOT NULL, date TEXT NOT NULL, angle TEXT NOT NULL, note TEXT NOT NULL, imagePath TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_progress_photos_date ON progress_photos(date)")
    }
}

fun photoIdValid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
fun photoPath(id: String) = "progress-photos/$id/image"
fun validatePhoto(photo: ProgressPhoto) {
    require(photoIdValid(photo.id) && Regex("progress-photos/[a-f0-9-]{36}/image").matches(photo.imagePath) && photoIdValid(photo.imagePath.split('/')[1])) { "Invalid photo reference." }
    require(validHistoryDate(photo.date) && photo.angle in PHOTO_ANGLES && photo.note.length <= 10000) { "Invalid photo details." }
}

// All public file operations run on IO and share the same lock, including backups.
class ProgressPhotoRepository(val context: Context, val database: FitDatabase) {
    internal val mutex = Mutex()
    internal val root = File(context.filesDir, "progress-photos").apply { mkdirs() }
    internal val staging = File(root, "pending").apply { mkdirs() }
    val photos = database.photoDao().observe()

    suspend fun recover() = withContext(Dispatchers.IO) { mutex.withLock {
        // Pending imports and unreferenced files are safe to discard after a process restart.
        staging.listFiles()?.forEach { it.deleteRecursively() }
        val known = database.photoDao().all().map { it.imagePath.split('/')[1] }.toSet()
        root.listFiles()?.filter { it.name != "pending" && it.name !in known }?.forEach { it.deleteRecursively() }
    } }

    suspend fun prepare(uri: Uri): PhotoDraft = withContext(Dispatchers.IO) { mutex.withLock {
        val id = UUID.randomUUID().toString()
        val directory = File(staging, id).apply { mkdirs() }
        try {
            val image = File(directory, "image")
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read the selected image." }.use { input ->
                image.outputStream().use { output -> copyPhoto(input, output) }
            }
            currentCoroutineContext().ensureActive()
            val date = PhotoImages.captureDate(image)
            PhotoImages.thumbnail(image, File(directory, "thumb.jpg"))
            PhotoDraft(ProgressPhoto(id, date ?: SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()), "Front", "", photoPath(id)), date != null)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            if (error is CancellationException) throw error
            throw IllegalArgumentException("Unable to import this image. Choose a readable image under 60 MB.")
        }
    } }

    suspend fun save(draft: PhotoDraft, date: String, angle: String, note: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val photo = draft.photo.copy(date = date, angle = angle, note = note)
        validatePhoto(photo)
        // Once publication starts, complete the file + row pair even if the screen closes.
        withContext(NonCancellable) {
            val directory = File(root, photo.id)
            require(File(staging, photo.id).renameTo(directory)) { "Could not save the image. Please try again." }
            try { database.photoDao().put(photo) }
            catch (error: Throwable) { directory.deleteRecursively(); throw error }
        }
    } }

    suspend fun discard(drafts: List<PhotoDraft>) = withContext(Dispatchers.IO) { mutex.withLock {
        drafts.forEach { File(staging, it.photo.id).deleteRecursively() }
    } }
    suspend fun edit(id: String, date: String, angle: String, note: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = requireNotNull(database.photoDao().find(id)) { "This photo is no longer available." }
        validatePhoto(current.copy(date = date, angle = angle, note = note))
        database.photoDao().edit(id, date, angle, note)
    } }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(photoIdValid(id))
        withContext(NonCancellable) {
            val photo = database.photoDao().find(id)
            database.photoDao().delete(id)
            photo?.let { file(it).parentFile?.deleteRecursively() }
        }
    } }
    fun file(photo: ProgressPhoto, draft: Boolean = false): File {
        validatePhoto(photo)
        return if (draft) File(staging, "${photo.id}/image") else File(context.filesDir, photo.imagePath)
    }
    suspend fun load(photo: ProgressPhoto, thumbnail: Boolean = false, draft: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val image = file(photo, draft)
            if (!image.isFile) return@withContext null
            if (thumbnail) {
                val thumb = File(image.parentFile, "thumb.jpg")
                PhotoImages.decode(thumb, 384) ?: PhotoImages.decode(image, 384)
            } else PhotoImages.decode(image, 2048)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}

internal fun copyPhoto(input: InputStream, output: OutputStream, limit: Long = 60L * 1024 * 1024): Long {
    val buffer = ByteArray(32 * 1024)
    var size = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        size += count
        require(size <= limit) { "Photo exceeds the supported size limit." }
        output.write(buffer, 0, count)
    }
    require(size > 0) { "Image is empty." }
    return size
}

object PhotoImages {
    fun captureDate(file: File): String? = runCatching {
        val raw = ExifInterface(file).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        if (!Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(raw)) return null
        val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply { isLenient = false }
        val parsed = parser.parse(raw) ?: return null
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(parsed).takeIf(::validHistoryDate)
    }.getOrNull()

    fun decode(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 400_000_000L) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val matrix = Matrix()
        if (exif?.isFlipped == true) matrix.postScale(-1f, 1f)
        if (exif != null) matrix.postRotate(exif.rotationDegrees.toFloat())
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
    fun thumbnail(image: File, target: File) {
        val bitmap = requireNotNull(decode(image, 384)) { "Unreadable image." }
        try { target.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
        finally { bitmap.recycle() }
    }
}
