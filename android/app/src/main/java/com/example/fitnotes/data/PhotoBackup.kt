package com.example.fitnotes.data

import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PhotoRestore(val backup: Backup, val photos: List<ProgressPhoto>, val directory: File)

// Existing v1–v6 JSON backups stay unchanged. The opt-in archive is a separate,
// versioned envelope containing that JSON, metadata and actual original files.
class PhotoBackup(private val repository: ProgressPhotoRepository, private val workouts: WorkoutRepository) {
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) { repository.mutex.withLock {
        val (backup, photos) = repository.database.withTransaction { workouts.backup() to repository.database.photoDao().all() }
        require(photos.size <= MAX_PHOTOS) { "Photo backups support up to 1000 photos." }
        val manifest = JSONObject().put("format", "fitnotes-photo-backup").put("version", 1)
        val records = JSONArray()
        var total = 0L
        photos.forEach { photo ->
            validatePhoto(photo)
            val file = repository.file(photo)
            require(file.isFile && file.length() in 1..MAX_PHOTO_BYTES) { "A photo is missing or unreadable. Remove or re-import it before backing up photos." }
            total += file.length()
            require(total <= MAX_ARCHIVE_BYTES - BackupCodec.MAX_BYTES) { "Photo backup exceeds 512 MB. Export training without photos instead." }
            val decoded = PhotoImages.decode(file, 64)
            require(decoded != null) { "A photo is corrupted. Remove or re-import it before backing up photos." }
            decoded.recycle()
            records.put(JSONObject().put("id", photo.id).put("date", photo.date).put("angle", photo.angle).put("note", photo.note)
                .put("image", "images/${photo.id}").put("sha256", digest(file)))
        }
        manifest.put("photos", records)
        requireNotNull(repository.context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open the selected backup destination." }.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                fun text(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                text("backup.json", BackupCodec.encode(backup))
                text("manifest.json", manifest.toString())
                photos.forEach { photo ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("images/${photo.id}"))
                    repository.file(photo).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    } }

    suspend fun inspect(uri: Uri): PhotoRestore = withContext(Dispatchers.IO) { repository.mutex.withLock {
        val directory = File(repository.staging, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var expanded = 0L
            var entries = 0
            val names = mutableSetOf<String>()
            requireNotNull(repository.context.contentResolver.openInputStream(uri)) { "Cannot read selected backup." }.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        require(++entries <= MAX_PHOTOS + 2 && names.add(name) && !entry.isDirectory) { "Invalid photo archive entries." }
                        require(name in listOf("backup.json", "manifest.json") ||
                            (name.startsWith("images/") && photoIdValid(name.removePrefix("images/")))) { "Invalid photo archive path." }
                        val file = File(directory, name)
                        file.parentFile!!.mkdirs()
                        val limit = when (name) { "backup.json" -> BackupCodec.MAX_BYTES.toLong(); "manifest.json" -> 12L * 1024 * 1024; else -> MAX_PHOTO_BYTES }
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var size = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                size += count; expanded += count
                                require(size <= limit && expanded <= MAX_ARCHIVE_BYTES) { "Photo archive exceeds its supported size limit." }
                                output.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val backup = BackupCodec.decode(File(directory, "backup.json").readText())
            val text = File(directory, "manifest.json").readText()
            StrictJson(text).check()
            val manifest = JSONObject(text)
            require(manifest.get("format") == "fitnotes-photo-backup" && manifest.get("version") == 1 && manifest.length() == 3) { "Unsupported photo archive." }
            val records = manifest.getJSONArray("photos")
            require(records.length() <= MAX_PHOTOS)
            val photos = (0 until records.length()).map { index ->
                val record = records.getJSONObject(index)
                require(record.keys().asSequence().toSet() == setOf("id", "date", "angle", "note", "image", "sha256")) { "Invalid photo metadata." }
                fun string(key: String) = (record.get(key) as? String) ?: error("Invalid photo metadata.")
                val id = string("id")
                val photo = ProgressPhoto(id, string("date"), string("angle"), string("note"), photoPath(id))
                validatePhoto(photo)
                require(string("image") == "images/$id" && Regex("[a-f0-9]{64}").matches(string("sha256"))) { "Invalid photo reference." }
                val image = File(directory, "images/$id")
                require(image.isFile && digest(image) == string("sha256")) { "A photo is missing or damaged in this backup." }
                val ready = File(directory, "ready/$id").apply { mkdirs() }
                require(image.renameTo(File(ready, "image"))) { "Cannot prepare restored photo." }
                PhotoImages.thumbnail(File(ready, "image"), File(ready, "thumb.jpg"))
                photo
            }
            require(photos.map { it.id }.toSet().size == photos.size && names == setOf("backup.json", "manifest.json") + photos.map { "images/${it.id}" }) { "Duplicate or unexpected photos." }
            PhotoRestore(backup, photos, directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            if (error is CancellationException) throw error
            throw IllegalArgumentException("Cannot restore this photo archive. It is incomplete, damaged or unsupported.")
        }
    } }

    suspend fun restore(pending: PhotoRestore) = withContext(Dispatchers.IO) { repository.mutex.withLock {
        val installed = mutableListOf<File>()
        val obsolete = mutableListOf<File>()
        withContext(NonCancellable) {
            try {
                val restored = pending.photos.map { photo ->
                    validatePhoto(photo)
                    val ready = File(pending.directory, "ready/${photo.id}")
                    require(File(ready, "image").isFile) { "Restore preview expired. Choose the backup again." }
                    // Immutable new file locations keep existing rows/files valid if the
                    // process dies at any point before the database transaction commits.
                    val storageId = UUID.randomUUID().toString()
                    val target = File(repository.root, storageId)
                    require(ready.renameTo(target)) { "Unable to save restored photo." }
                    installed.add(target)
                    repository.database.photoDao().find(photo.id)?.let { obsolete.add(repository.file(it).parentFile!!) }
                    photo.copy(imagePath = photoPath(storageId))
                }
                repository.database.withTransaction {
                    workouts.restore(pending.backup)
                    restored.forEach { repository.database.photoDao().put(it) }
                }
            } catch (error: Throwable) {
                installed.forEach { it.deleteRecursively() }
                throw error
            }
            obsolete.forEach { it.deleteRecursively() }
            pending.directory.deleteRecursively()
        }
    } }
    suspend fun discard(pending: PhotoRestore?) = withContext(Dispatchers.IO) { repository.mutex.withLock { pending?.directory?.deleteRecursively(); Unit } }

    companion object {
        const val MAX_PHOTOS = 1000
        const val MAX_PHOTO_BYTES = 60L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
