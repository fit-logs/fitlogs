package com.example.fitnotes.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Created only for an explicit Share action. Cache paths are narrowly granted;
// no private database or original photo directory is exposed by the provider.
class HandoffShare(private val context: Context, private val photos: ProgressPhotoRepository) {
    suspend fun intent(prepared: PreparedHandoff): Intent = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "handoff-exports").apply { mkdirs() }
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.deleteRecursively() }
        val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val files = mutableListOf<File>()
            if (!prepared.canCopy) files.add(File(directory, "training-context.txt").apply { writeText(prepared.fullText, Charsets.UTF_8) })
            prepared.photos.forEachIndexed { index, photo ->
                require(photos.database.photoDao().find(photo.id) == photo) { "A selected photo changed. Review the selection before sharing." }
                val bitmap = photos.load(photo) ?: throw IllegalArgumentException("A selected photo is missing or corrupted. Deselect it or re-import it before sharing.")
                try {
                    val file = File(directory, "photo-${index + 1}-${photo.date}-${photo.angle.lowercase()}.jpg")
                    file.outputStream().use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) { "Could not prepare a selected photo." } }
                    files.add(file)
                } finally { bitmap.recycle() }
            }
            val text = if (prepared.canCopy) prepared.fullText else prepared.requestText + "\n\nThe complete recorded training context and any selected raw heart-rate series are attached in training-context.txt. No history has been truncated."
            if (files.isEmpty()) {
                directory.deleteRecursively()
                return@withContext Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            }
            val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.handoff-files", it) })
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (prepared.canCopy) "image/jpeg" else "*/*"
                putExtra(Intent.EXTRA_TEXT, text)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(context.contentResolver, "Selected FitNotes export", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Throwable) { directory.deleteRecursively(); throw error }
    }
}
