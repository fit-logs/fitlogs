package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.moveDay
import com.example.fitnotes.ui.todayDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant

class WorkoutLifecycleInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val db get() = model.photoRepository.database
    private val old = Instant.parse("2026-10-16T05:00:00Z").toEpochMilli()
    @Before fun initialize() {
        assumeTrue(i.targetContext.packageName == "com.example.fitnotes.preview.photos")
        ui.activityRule.scenario.onActivity { model = ViewModelProvider(it)[FitNotesViewModel::class.java]; model.setAutomaticHealth(false) }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading && !model.busy.value }
    }
    private fun restore(backup: Backup, ready: () -> Boolean) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.runOnIdle { model.pendingRestore.value = backup; model.confirmRestore { done.set(true) } }
        ui.waitUntil(30000) { done.get() && !model.busy.value && ready() }
        ui.mainClock.advanceTimeBy(10000)
    }
    private fun capture(name: String) {
        ui.waitForIdle(); i.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = i.uiAutomation.takeScreenshot()!!; val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(i.targetContext.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun field(label: String): SemanticsNodeInteraction {
        ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:$label")); return ui.onNodeWithTag("field:$label")
    }
    @Test fun forgottenLegacyStrengthBecomesUnknownThenCanBeCorrectedFromTheNoticeAndHistory() {
        val backup = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")), workouts = listOf(Workout(16, old, title = "Forgotten strength 16 Oct", notes = "Keep note")),
            entries = listOf(WorkoutExercise(1, 16, 1, 0)), sets = listOf(WorkoutSet(1, 1, 40.0, 8, 0)))
        restore(backup) { model.data.value.workouts.singleOrNull()?.workout?.timingEstimated == true }
        ui.onNodeWithTag("auto-finish-notice").assertExists()
        assertNull(runBlocking { db.dao().active() }); assertNull(runBlocking { localDuration(db.dao().workout(16)!!) })
        assertEquals(backup.sets, runBlocking { db.dao().sets() }); capture("stale-workout-notice")
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Edit time / duration", substring = false))
        ui.onNodeWithText("Edit time / duration", substring = false).performClick()
        ui.onNodeWithTag("workout-start-time").performScrollTo().performTextReplacement("18:00+13:00")
        ui.onNodeWithTag("workout-end-date").performScrollTo().performTextReplacement("2026-10-16")
        ui.onNodeWithTag("workout-end-time").performScrollTo().performTextReplacement("19:15+13:00")
        Espresso.closeSoftKeyboard(); ui.onNodeWithText("Save time", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().workout(16)?.timingEdited == true } }
        assertEquals(4500.0, runBlocking { localDuration(db.dao().workout(16)!!) }!!, 0.0)
        assertFalse(runBlocking { db.dao().workout(16)!!.timingEstimated }); assertEquals(backup.sets, runBlocking { db.dao().sets() })
        ui.mainClock.advanceTimeBy(10000); ui.onNode(hasText("History") and hasClickAction()).performClick()
        ui.onNodeWithTag("history-list").performScrollToNode(hasText("◆ Forgotten strength 16 Oct", substring = false))
        ui.onNodeWithText("◆ Forgotten strength 16 Oct", substring = false).performClick()
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("1h 15m 0s", substring = false))
        ui.onNodeWithText("1h 15m 0s", substring = false).assertIsDisplayed(); capture("stale-workout-corrected")
        ui.activityRule.scenario.recreate(); assertEquals(4500.0, runBlocking { localDuration(db.dao().workout(16)!!) }!!, 0.0)
    }
    @Test fun resumeUsesAFreshSegmentWithoutDuplicatingTheWeekOldWorkoutOrItsSets() {
        val backup = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")), workouts = listOf(Workout(16, old, title = "Forgotten with last set")),
            entries = listOf(WorkoutExercise(1, 16, 1, 0)), sets = listOf(WorkoutSet(1, 1, 40.0, 8, 0, completedAt = old + 3600000)))
        restore(backup) { model.data.value.workouts.singleOrNull()?.workout?.timingEstimated == true }
        ui.onNodeWithText("Undo / Resume", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.data.value.active?.workout?.id == 16L }
        val resumed = runBlocking { db.dao().workout(16)!! }
        assertEquals(old, resumed.startedAt); assertNotNull(resumed.resumeStartedAt); assertEquals(4500.0, resumed.resumeBaseSeconds!!, 0.0)
        assertEquals(backup.sets, runBlocking { db.dao().sets() }); assertEquals(1, runBlocking { db.dao().workouts().size })
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Resumed explicitly.", substring = true))
        capture("stale-workout-resumed")
    }
    @Test fun copyYesterdaysDraftFromHistoryLogOnlyPerformedSetsAndSaveCompletedWithoutALiveTimer() {
        val now = System.currentTimeMillis(); val sourceStart = now - 2 * 86400000L
        val backup = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
            workouts = listOf(Workout(10, sourceStart, sourceStart + 3600000, "Previous routine"), Workout(20, now, title = "Active gym", notes = "Keep active")),
            entries = listOf(WorkoutExercise(1, 10, 1, 0), WorkoutExercise(2, 20, 1, 0)),
            sets = listOf(WorkoutSet(1, 1, 30.0, 6, 0), WorkoutSet(2, 2, 40.0, 8, 0)))
        restore(backup) { model.data.value.active?.workout?.id == 20L && model.data.value.workouts.size == 2 }
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Log past workout", substring = false))
        ui.onNodeWithText("Log past workout", substring = false).assertIsDisplayed()
        ui.onNode(hasText("History") and hasClickAction()).performClick()
        ui.onNodeWithText("Log past workout", substring = false).performClick()
        ui.onNode(hasText("Previous routine", substring = true) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        capture("past-workout-copy")
        ui.onNodeWithText("Enter performed sets", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.size == 1 }
        val draft = model.pastDrafts.value.single(); val id = draft.workout.id
        assertEquals(moveDay(todayDate(), -1), draft.workout.sessionDate); assertNull(draft.workout.knownStart)
        assertEquals(20L, runBlocking { db.dao().active()!!.id }); assertEquals(2, runBlocking { db.dao().sets().size })
        ui.onNodeWithTag("workout-overview").performScrollToNode(hasText("Synthetic press", substring = false))
        ui.onNodeWithText("Synthetic press", substring = false).performClick()
        field("Weight (kg)").assertTextContains("30"); field("Reps").assertTextContains("6")
        assertEquals(2, runBlocking { db.dao().sets().size })
        field("Weight (kg)").performTextReplacement("32.5"); field("Reps").performTextReplacement("7")
        Espresso.closeSoftKeyboard(); ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Save set", substring = false))
        ui.onNodeWithText("Save set", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().sets().size == 3 } }
        assertEquals(2, org.json.JSONObject(runBlocking { BackupCodec.analysis(WorkoutRepository(db).backup()) }).getJSONArray("sets").length())
        assertEquals(20L, runBlocking { db.dao().active()!!.id }); capture("past-workout-draft-sets")
        ui.activityRule.scenario.recreate(); ui.onNodeWithTag("logger-list").assertExists()
        ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Save completed", substring = false))
        ui.onNodeWithText("Save completed", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.isEmpty() && runBlocking { db.dao().workout(id)!!.isComplete } }
        assertEquals(20L, runBlocking { db.dao().active()!!.id }); assertNull(runBlocking { db.dao().workout(id)!!.knownStart })
        assertNull(runBlocking { localDuration(db.dao().workout(id)!!) }); assertEquals(3, runBlocking { db.dao().sets().size })
        assertEquals(backup.sets, runBlocking { db.dao().sets().filter { it.entryId in setOf(1L, 2L) } })
        ui.onNodeWithTag("workout-overview").assertExists(); capture("past-workout-saved")
        File(i.targetContext.filesDir, "past-workout-restart.json").writeText(runBlocking { BackupCodec.encode(WorkoutRepository(db).backup()) })
    }
    @Test fun abandonedEmptySessionStaysADraftUntilTheUserDiscardsIt() {
        val backup = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")), workouts = listOf(Workout(16, old, notes = "Keep empty note")),
            entries = listOf(WorkoutExercise(1, 16, 1, 0)), sets = emptyList())
        restore(backup) { model.data.value.workouts.isEmpty() && model.pastDrafts.value.size == 1 }
        ui.onNodeWithText("Empty session kept as a draft", substring = false).assertIsDisplayed()
        assertTrue(runBlocking { db.dao().sets().isEmpty() }); assertNull(runBlocking { db.dao().active() })
        ui.onNodeWithTag("train-screen").performScrollToNode(hasText("Discard draft", substring = false))
        ui.onNodeWithText("Discard draft", substring = false).performClick(); ui.onNodeWithText("Discard", substring = false).performClick()
        ui.waitUntil(15000) { !model.busy.value && model.pastDrafts.value.isEmpty() }
        assertTrue(runBlocking { db.dao().workouts().isEmpty() }); assertTrue(runBlocking { db.dao().sets().isEmpty() })
    }
}

class PastWorkoutRestartTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun completedPastSessionAndConcurrentActiveWorkoutSurviveForceStop() {
        val i = InstrumentationRegistry.getInstrumentation(); assumeTrue(i.targetContext.packageName == "com.example.fitnotes.preview.photos")
        lateinit var model: FitNotesViewModel
        ui.activityRule.scenario.onActivity { model = ViewModelProvider(it)[FitNotesViewModel::class.java] }
        ui.waitUntil(30000) { !model.data.value.loading && !model.data.value.imported.loading }
        val expected = BackupCodec.decode(File(i.targetContext.filesDir, "past-workout-restart.json").readText())
        val actual = runBlocking { WorkoutRepository(model.photoRepository.database).backup() }
        assertEquals(expected.workouts, actual.workouts); assertEquals(expected.entries, actual.entries); assertEquals(expected.sets, actual.sets)
        assertEquals(20L, model.data.value.active!!.workout.id)
    }
}
