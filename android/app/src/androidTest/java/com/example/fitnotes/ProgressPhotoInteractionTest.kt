package com.example.fitnotes

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

// These fixtures and screenshots can run only in the isolated preview package.
class ProgressPhotoInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        assumeTrue(it.packageName == "com.example.fitnotes.preview.photos")
    }
    private fun image(context: Context, name: String, width: Int, height: Int, variant: Int): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (variant % 2 == 0) Color.rgb(226, 234, 237) else Color.rgb(240, 228, 216))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val sx = width / 400f; val sy = height / 700f
        canvas.save(); canvas.scale(sx, sy)
        paint.color = Color.rgb(198, 210, 212); paint.strokeWidth = 1f
        for (y in 0..700 step 50) canvas.drawLine(0f, y.toFloat(), 400f, y.toFloat(), paint)
        for (x in 0..400 step 50) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 700f, paint)
        paint.color = if (variant % 2 == 0) Color.rgb(48, 100, 122) else Color.rgb(169, 90, 51)
        canvas.drawCircle(200f, 125f, 43f, paint)
        canvas.drawRoundRect(142f - variant * 3, 185f, 258f + variant * 3, 405f, 28f, 28f, paint)
        paint.strokeWidth = 28f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(145f, 215f, 100f, 392f, paint); canvas.drawLine(255f, 215f, 300f, 392f, paint)
        canvas.drawLine(170f, 390f, 150f, 600f, paint); canvas.drawLine(230f, 390f, 250f, 600f, paint)
        paint.strokeWidth = 0f; paint.textSize = 20f
        canvas.drawText("SYNTHETIC SAMPLE ${variant + 1}", 75f, 660f, paint)
        canvas.restore()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }; bitmap.recycle()
        return file
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(500) // Let Android window/ripple animation settle before capture.
        val context = context()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(context.filesDir, "synthetic-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun seed() {
        val context = context()
        val model = androidx.lifecycle.ViewModelProvider(ui.activity)[FitNotesViewModel::class.java]
        val repo = model.photoRepository
        val db = repo.database
        runBlocking {
            if (db.photoDao().all().isEmpty()) {
                repeat(4) { index ->
                    val file = image(context, "synthetic-$index.jpg", if (index == 2) 1000 else 700, if (index == 2) 700 else 1200, index)
                    val draft = repo.prepare(Uri.fromFile(file)); repo.save(draft, "2026-0${index + 1}-12", "Front", "Synthetic illustration; no personal photo.")
                    file.delete()
                }
            }
            // A readable gallery source lets manual verification exercise the real picker.
            val source = image(context, "synthetic-picker.jpg", 700, 1200, 5)
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic-progress-picker.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyntheticProgress")
            })!!
            context.contentResolver.openOutputStream(uri)!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            source.delete()
        }
    }
    private fun photosTab() {
        ui.waitUntil(30000) { ui.onAllNodesWithText("Body").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Body").performClick()
        ui.onNodeWithText("Photos").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithText("12 Apr 2026").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun galleryDetailsCompareEndpointsDraggingAndModeRetention() {
        seed(); photosTab()
        capture("photo-grid")
        ui.onNodeWithText("12 Apr 2026").performClick()
        ui.onNodeWithText("Edit details").performClick()
        capture("edit-photo-details")
        ui.onNodeWithText("Note (optional)").performScrollTo().performTextReplacement("Edited synthetic note")
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Edit details").performClick()
        ui.onNodeWithText("Note (optional)").performScrollTo().performTextReplacement("Saved synthetic note")
        ui.onNodeWithText("Save photo").performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Saved synthetic note").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Close").performClick()
        ui.onNodeWithContentDescription("Select Front photo 2026-04-12").performClick()
        ui.onNodeWithContentDescription("Select Front photo 2026-03-12").performClick()
        ui.onNodeWithText("Compare (2/2)").performClick()
        ui.onNodeWithContentDescription("Comparison divider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        ui.onNodeWithText("Reveal earlier/later · 0%").assertExists()
        capture("slider-endpoint-later")
        ui.onNodeWithContentDescription("Comparison divider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
        ui.onNodeWithText("Reveal earlier/later · 100%").assertExists()
        capture("slider-endpoint-earlier")
        ui.onNodeWithText("Reset").performClick()
        ui.onNodeWithText("Reveal earlier/later · 50%").assertExists()
        capture("slider-comparison")
        ui.onNodeWithContentDescription("Drag comparison divider").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-120f, 0f), 500) }
        ui.onNodeWithText("Reveal earlier/later · 50%").assertDoesNotExist()
        ui.onNodeWithText("Reset").performClick()
        ui.onNodeWithText("Side by side").performClick()
        ui.onNodeWithText("Complete photos · original aspect ratios").assertExists()
        capture("side-by-side-comparison")
        ui.onNodeWithText("Slider", substring = false).performClick()
        ui.onNodeWithText("Earlier\n12 Mar 2026").assertExists()
        ui.onNodeWithText("Later\n12 Apr 2026").assertExists()
        ui.onNodeWithText("Close").performClick()
        ui.onNodeWithText("Compare (2/2)").assertIsEnabled()
        ui.onNodeWithText("Side", substring = false).performClick()
        ui.onNodeWithText("No side photos yet").assertExists()
        capture("empty-photos")
    }
    @Test fun importCancelAndConfirmedDeleteUseOnlyAppCopy() {
        seed(); photosTab()
        val context = context()
        val model = androidx.lifecycle.ViewModelProvider(ui.activity)[FitNotesViewModel::class.java]
        val db = model.photoRepository.database
        val before = runBlocking { db.photoDao().all().size }
        val original = image(context, "ui-import-synthetic.jpg", 700, 1200, 6)
        fun prepare() {
            ui.runOnIdle { model.preparePhotos(listOf(Uri.fromFile(original))) }
            ui.waitUntil(15000) { model.photoDrafts.value.isNotEmpty() && !model.busy.value }
            ui.onNodeWithText("Add photo (1 remaining)").assertExists()
        }
        prepare()
        ui.onNodeWithText("Cancel import").performClick()
        ui.waitUntil(15000) { model.photoDrafts.value.isEmpty() && !model.busy.value }
        assertEquals(before, runBlocking { db.photoDao().all().size })
        prepare()
        ui.onAllNodesWithText("Back", substring = false).onLast().performClick()
        ui.onNodeWithText("Note (optional)").performScrollTo().performTextReplacement("Synthetic import note")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save photo").performClick()
        ui.waitUntil(15000) { runBlocking { db.photoDao().all().size } == before + 1 && model.photoDrafts.value.isEmpty() }
        val saved = runBlocking { db.photoDao().all().single { it.note == "Synthetic import note" } }
        ui.onNodeWithText("Back", substring = false).performClick()
        ui.onNodeWithText(com.example.fitnotes.ui.prettyDay(saved.date, "d MMM yyyy")).performClick()
        ui.onNodeWithText("Edit details").performClick()
        ui.onNodeWithText("Note (optional)").performScrollTo().performTextReplacement("Synthetic edited import")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("Save photo").performClick()
        ui.waitUntil(15000) { runBlocking { db.photoDao().find(saved.id)?.note } == "Synthetic edited import" }
        ui.onNodeWithText("Delete", substring = false).performClick()
        ui.onNodeWithText("Cancel", substring = false).performClick()
        assertNotNull(runBlocking { db.photoDao().find(saved.id) })
        ui.onNodeWithText("Delete", substring = false).performClick()
        ui.onAllNodesWithText("Delete", substring = false).onLast().performClick()
        ui.waitUntil(15000) { runBlocking { db.photoDao().find(saved.id) } == null }
        assertFalse(model.photoRepository.file(saved).exists()); assertTrue(original.exists())
        original.delete()
        assertEquals(before, runBlocking { db.photoDao().all().size })
    }
    @Test fun nativeExifOrientationLargeImagesPrivateCopiesAndDeletion() {
        val context = context(); val db = FitDatabase.open(context)
        try { runBlocking {
            val repo = ProgressPhotoRepository(context, db)
            val source = image(context, "large-synthetic.jpg", 6000, 4000, 2)
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:07:08 13:00:00"); saveAttributes()
            }
            val before = db.photoDao().all().size
            val cancelled = repo.prepare(Uri.fromFile(source)); repo.discard(listOf(cancelled))
            assertEquals(before, db.photoDao().all().size)
            val draft = repo.prepare(Uri.fromFile(source))
            assertEquals("2024-07-08", draft.photo.date)
            repo.save(draft, draft.photo.date, "Other", "Synthetic large rotated image")
            source.delete()
            val bitmap = repo.load(draft.photo)!!
            assertTrue(bitmap.width < bitmap.height); assertTrue(bitmap.height <= 2048); bitmap.recycle()
            assertTrue(repo.file(draft.photo).isFile)
            repo.delete(draft.photo.id); assertNull(db.photoDao().find(draft.photo.id)); assertFalse(repo.file(draft.photo).exists())
            val invalid = File(context.cacheDir, "synthetic-corrupt.jpg").apply { writeText("not an image") }
            try { repo.prepare(Uri.fromFile(invalid)); fail() } catch (_: IllegalArgumentException) { }
            invalid.delete(); assertEquals(before, db.photoDao().all().size)
        } } finally { db.close() }
    }
}
