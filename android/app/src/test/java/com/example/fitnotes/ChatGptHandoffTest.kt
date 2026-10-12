package com.example.fitnotes

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatGptHandoffTest {
    private fun stamp(date: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(date)!!.time
    private val from = "2026-10-07"
    private val through = "2026-10-13"
    private val press = Exercise(1, "Synthetic press", "synthetic press")
    private val start = stamp("2026-10-12 18:00")
    private val source = HealthSession("org.synthetic:one", 1, "synthetic", "org.synthetic", "Synthetic source", null,
        1, start, start, start, start + 3600000, null, null, "strength", 70, "Synthetic source title", "", null, "", "Synthetic samples", "No distance")
    private fun data(): AppData {
        val detail = WorkoutDetail(Workout(1, start, start + 3600000, notes = "Synthetic actual-session note"),
            listOf(EntryDetail(WorkoutExercise(1, 1, 1, 0), press, listOf(WorkoutSet(1, 1, 50.0, 8, 0)))),
            listOf(HealthDetail(source, listOf(HealthSample("sample", source.key, "hr", "org.synthetic", start + 1000, 123)))))
        val climbing = WorkoutDetail(Workout(2, start + 7200000, start + 9000000, activityType = "climbing", effort = "Okay", notes = "Synthetic climbing note"), emptyList())
        val body = BodyMeasurement("body", "synthetic", "body", "2026-10-12", "", "Synthetic body weight", 70.0, "kg", "kilograms", "", "{}")
        return AppData(listOf(press), listOf(detail, climbing), loading = false, imported = ImportedData(body = listOf(body)))
    }
    private fun request(kind: HandoffRequestKind = HandoffRequestKind.NEXT_WORKOUT) = HandoffRequest(from, through, kind, "2026-10-14",
        HandoffPreferences(goals = "Synthetic goals", equipment = "Synthetic equipment", constraints = "Synthetic constraints", availableMinutes = 45))
    @Test fun nextWorkoutUsesExactDatesActualSetsNonGymAndExplicitMissingPreferences() {
        val prepared = ChatGptHandoff.prepare(data(), emptyList(), request())
        val text = prepared.fullText
        assertTrue(text.contains("$from through $through, inclusive"))
        assertTrue(text.contains("50 kg × 8 reps")); assertTrue(text.contains("Climbing / bouldering")); assertTrue(text.contains("Effort: Okay"))
        assertTrue(text.contains("Synthetic actual-session note")); assertTrue(text.contains("Synthetic equipment"))
        assertTrue(text.contains("WORKOUT_PLAN_V1")); assertTrue(text.contains("no exercise identifiers"))
        assertTrue(text.contains("Activity preference: not specified"))
        assertFalse(text.contains("Synthetic body weight")); assertFalse(text.contains("timestamp_local,epoch_millis,bpm"))
    }
    @Test fun optionalMeasurementsRawSeriesAndPhotosRequireSpecificSelection() {
        val current = data()
        val photo = ProgressPhoto("00000000-0000-0000-0000-000000000001", "2026-10-12", "Front", "Synthetic photo note", "private/path/not/exported")
        val unselected = ChatGptHandoff.prepare(current, listOf(photo), request())
        assertFalse(unselected.fullText.contains("Synthetic photo note"))
        val selected = ChatGptHandoff.prepare(current, listOf(photo), request().copy(
            measurementTypes = setOf(measurementSelectionKey(current.imported.body.single())), photoIds = setOf(photo.id), includeHeartRate = true))
        assertTrue(selected.fullText.contains("Synthetic body weight")); assertTrue(selected.fullText.contains("Synthetic photo note"))
        assertTrue(selected.fullText.contains("timestamp_local,epoch_millis,bpm")); assertTrue(selected.fullText.contains(",123,org.synthetic"))
        assertFalse(selected.fullText.contains("private/path")); assertEquals(1, selected.sampleCount)
        assertTrue(selected.fullText.contains("not a complete day-by-day series"))
    }
    @Test fun adviceRequestsAvoidPlanInstructionsAndPartialHistoryIsRejected() {
        val review = ChatGptHandoff.prepare(data(), emptyList(), request(HandoffRequestKind.REVIEW_PROGRESS).copy(preferences = HandoffPreferences(activityPreference = "climbing")))
        assertFalse(review.fullText.contains("WORKOUT_PLAN_V1"))
        val custom = ChatGptHandoff.prepare(data(), emptyList(), request(HandoffRequestKind.CUSTOM).copy(customQuestion = "Synthetic question about recovery"))
        assertTrue(custom.fullText.contains("Synthetic question about recovery")); assertFalse(custom.fullText.contains("WORKOUT_PLAN_V1"))
        try { ChatGptHandoff.prepare(data().copy(imported = ImportedData(loading = true)), emptyList(), request()); fail() } catch (_: IllegalArgumentException) {}
        try { ChatGptHandoff.prepare(data(), emptyList(), request().copy(preferences = HandoffPreferences(activityPreference = "running"))); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun emptyRangeExplainsMissingDataAndOversizeExportKeepsEverySession() {
        val empty = ChatGptHandoff.prepare(data(), emptyList(), request().copy(first = "2020-01-01", last = "2020-01-07"))
        assertTrue(empty.fullText.contains("No completed workouts are recorded"))
        val many = data().copy(workouts = (1..24).map { index -> WorkoutDetail(Workout(index.toLong(), start, start + 1000, title = "Synthetic $index", notes = "x".repeat(10000)), emptyList()) })
        val full = ChatGptHandoff.prepare(many, emptyList(), request())
        assertFalse(full.canCopy); assertTrue(full.fullText.contains("Synthetic 1")); assertTrue(full.fullText.contains("Synthetic 24"))
        assertEquals(24, Regex("Notes: ").findAll(full.fullText).count())
    }
    @Test fun androidShareUsesTextOrAReadOnlyFullExportAttachment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        try {
            val share = HandoffShare(context, ProgressPhotoRepository(context, db))
            val prepared = ChatGptHandoff.prepare(data(), emptyList(), request())
            val inline = share.intent(prepared)
            assertEquals(Intent.ACTION_SEND, inline.action); assertEquals("text/plain", inline.type)
            assertEquals(prepared.fullText, inline.getStringExtra(Intent.EXTRA_TEXT))
            val larger = prepared.copy(fullText = prepared.fullText + "x".repeat(200001))
            val attached = share.intent(larger)
            assertNotNull(attached.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            assertTrue(attached.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = attached.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(larger.fullText, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally { db.close(); java.io.File(context.cacheDir, "handoff-exports").deleteRecursively() }
    }
}
