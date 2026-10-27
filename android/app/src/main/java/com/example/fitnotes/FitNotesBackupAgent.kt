package com.example.fitnotes

import android.app.backup.BackupAgentHelper
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.fitnotes.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** Android schedules and transports backups; only this one opt-in snapshot is eligible. */
class FitNotesBackupAgent : BackupAgentHelper() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        val store = SystemBackupStore(this)
        if (!store.state().enabled) return
        if (Build.VERSION.SDK_INT < 28 || data.transportFlags and
            (FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or FLAG_DEVICE_TO_DEVICE_TRANSFER) == 0) {
            store.failed("Automatic backup needs encrypted Android backup. Enable Google backup and a screen lock in Android Settings.")
            throw IOException("An encrypted backup transport is required.")
        }
        if (store.hasRecovery()) {
            store.failed("Review the recovered backup before creating a new automatic backup.")
            throw IOException("A recovered backup is awaiting review.")
        }
        try {
            runBlocking(Dispatchers.IO) {
                val db = FitDatabase.open(this@FitNotesBackupAgent)
                try { SystemBackupSnapshot.write(SystemBackupSnapshot.capture(this@FitNotesBackupAgent, db), store.file) }
                finally { db.close() }
            }
            fullBackupFile(store.file, data)
            store.prepared() // Preparation does not establish a successful cloud upload.
        } catch (_: Exception) {
            store.failed("Android backup could not prepare your data. Your saved training is unchanged. Try a manual export and check Android backup settings.")
            throw IOException("Unable to prepare the FitNotes backup.")
        } finally { store.file.delete() }
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        SystemBackupStore(this).failed("Android's backup size limit was exceeded. Export a manual backup; the previous Android backup is retained.")
    }

    override fun onRestoreFile(data: ParcelFileDescriptor, size: Long, destination: File?, type: Int, mode: Long, mtime: Long) {
        val file = SystemBackupStore(this).file
        val accepted = destination?.canonicalFile == file.canonicalFile && size in 1..(24L * 1024 * 1024) && type == TYPE_FILE
        super.onRestoreFile(data, size, if (accepted) destination else null, type, mode, mtime)
    }

    override fun onRestoreFinished() {
        val store = SystemBackupStore(this)
        if (!store.file.exists()) return
        try { SystemBackupSnapshot.read(store.file); store.recovered() }
        catch (_: Exception) { store.failed("The recovered Android backup is damaged or unsupported. Existing training has not been replaced.") }
        // Review and the existing transactional restore happen in the app, never here.
    }
}
