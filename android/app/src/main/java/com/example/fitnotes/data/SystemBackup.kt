package com.example.fitnotes.data

import android.content.Context
import androidx.core.content.edit
import com.example.fitnotes.AndroidRestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

const val SYSTEM_BACKUP_PATH = "system-backup/training.json.gz"
private const val MAX_COMPRESSED_BYTES = 24L * 1024 * 1024 // Leave room in Android's 25 MB quota.

data class SystemBackupState(val enabled: Boolean = false, val preparedAt: Long = 0,
    val recoveredAt: Long = 0, val error: String? = null)

/** No account credentials or exported payloads in preferences. Consent is device-local. */
class SystemBackupStore(private val context: Context) {
    val preferences = context.getSharedPreferences("system-backup-status", Context.MODE_PRIVATE)
    val file get() = File(context.filesDir, SYSTEM_BACKUP_PATH)
    fun state() = SystemBackupState(preferences.getBoolean("enabled", false), preferences.getLong("prepared", 0),
        preferences.getLong("recovered", 0), preferences.getString("error", null))
    // Android may terminate the agent immediately after a callback, so persist synchronously.
    fun enable(value: Boolean) { preferences.edit(commit = true) { putBoolean("enabled", value); remove("error") } }
    fun prepared() { preferences.edit(commit = true) { putLong("prepared", System.currentTimeMillis()); remove("error") } }
    fun failed(message: String) { preferences.edit(commit = true) { putString("error", message) } }
    fun recovered() { preferences.edit(commit = true) { putLong("recovered", System.currentTimeMillis()); remove("error") } }
    fun hasRecovery() = state().recoveredAt > 0 && file.isFile
    fun discardRecovery() {
        if (file.exists() && !file.delete()) throw IOException("Unable to remove the recovered backup file.")
        preferences.edit(commit = true) { remove("recovered") }
    }
}

/** A regular versioned FitNotes backup, compressed for the Android backup quota. */
object SystemBackupSnapshot {
    fun write(backup: Backup, file: File) {
        val text = BackupCodec.encode(backup)
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { output -> GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
            require(file.length() in 1..MAX_COMPRESSED_BYTES) { "Backup is too large for Android backup. Export a manual backup instead." }
            require(read(file) == backup) { "Unable to validate the backup." }
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun read(file: File): Backup {
        require(file.length() in 1..MAX_COMPRESSED_BYTES) { "Recovered backup is missing or too large." }
        val bytes = ByteArrayOutputStream()
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size().toLong() + count <= BackupCodec.MAX_BYTES) { "Recovered backup exceeds the supported size limit." }
                bytes.write(buffer, 0, count)
            }
        }
        return BackupCodec.decode(bytes.toString(Charsets.UTF_8.name()))
    }

    suspend fun capture(context: Context, db: FitDatabase): Backup = withContext(Dispatchers.IO) {
        val backup = WorkoutRepository(db, AndroidRestClock(context)).backup()
        val favourites = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .getStringSet("favourite_exercises", emptySet()).orEmpty().sorted()
        backup.copy(trainingPreferences = (backup.trainingPreferences ?: TrainingPreferences()).copy(favouriteKeys = JSONArray(favourites).toString()))
    }
}
