package com.example.fitnotes

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant

class HealthSyncInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var model: FitNotesViewModel
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val db get() = model.photoRepository.database
    private fun capture(name: String) {
        ui.waitForIdle(); instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(instrumentation.targetContext.filesDir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Before fun seed() {
        assumeTrue(instrumentation.targetContext.packageName == "com.example.fitnotes.preview.photos")
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[FitNotesViewModel::class.java]
            model.setAutomaticHealth(false)
            model.pendingRestore.value = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")), workouts = emptyList(), entries = emptyList(), sets = emptyList())
            model.confirmRestore { ready.set(true) }
        }
        ui.waitUntil(30000) { ready.get() && !model.busy.value && !model.data.value.imported.loading }
        ui.mainClock.advanceTimeBy(10000)
    }
    @Test fun healthConnectionStatusAndFiltersRemainUsableWhileGymSetsSaveDuringSourceRead() {
        val connection = HealthSyncRuntime.connection(instrumentation.targetContext)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val start = System.currentTimeMillis() - 86400000
        val candidates = listOf("running", "walking").mapIndexed { index, type ->
            HealthCandidate(HealthSession(healthKey(GARMIN_PACKAGE, "ui-$type"), null, "ui-$type", GARMIN_PACKAGE,
                "Synthetic Garmin", "ui-$type", 1, start, start, start + index * 3600000, start + index * 3600000 + 1800000,
                0, 0, type, if (type == "walking") 37 else 56, "Synthetic $type", "", null, "", "No HR", "No distance"))
        }
        val source = object : HealthSyncSource {
            override suspend fun access() = HealthAccess(true, false, false)
            override suspend fun token(feed: HealthFeed) = "synthetic-${feed.name}"
            override suspend fun sessions(start: Instant, end: Instant): List<HealthCandidate> { entered.complete(Unit); release.await(); return candidates }
            override suspend fun changes(feed: HealthFeed, token: String) = HealthDeltaPage(next = token)
            override suspend fun enrich(candidate: HealthCandidate) = candidate
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sync = scope.async { HealthSyncCoordinator(db, connection.first, source, connection.second).sync(HealthSyncReason.MANUAL) }
        try {
            runBlocking { withTimeout(10000) { entered.await() } }
            assertTrue(model.healthConnectionState.value.working); assertFalse(model.busy.value)
            // The same manual/foreground/worker coordinator is paused in an SDK
            // read while these are real UI actions against the normal logger.
            ui.onNodeWithContentDescription("Settings").performClick()
            ui.onNodeWithText("Garmin via Health Connect").performClick()
            ui.onNodeWithTag("health-preview-list").performScrollToNode(hasText("Syncing. Workout logging remains available."))
            ui.onNodeWithText("Syncing. Workout logging remains available.").assertIsDisplayed()
            capture("health-sync-working")
            ui.onNodeWithTag("health-preview-list").performScrollToNode(hasText("Choose sources and activities"))
            ui.onNodeWithText("Choose sources and activities").performClick()
            ui.onNodeWithTag("health-preview-list").performScrollToNode(hasText("Garmin Connect", substring = false))
            ui.onNodeWithText("Garmin Connect", substring = false).assertIsDisplayed()
            capture("health-sync-filters")
            ui.onNodeWithContentDescription("Back", substring = false).performClick()
            ui.onNodeWithText("Start empty workout").performClick()
            ui.onNodeWithTag("exercise-search").performTextReplacement("Synthetic press")
            Espresso.closeSoftKeyboard()
            ui.onNode(hasText("Synthetic press", substring = false) and !hasSetTextAction()).performClick()
            ui.onNodeWithText("Add 1 exercise").performClick()
            ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Weight (kg)"))
            ui.onNodeWithTag("field:Weight (kg)").performTextReplacement("40")
            ui.onNodeWithTag("logger-list").performScrollToNode(hasTestTag("field:Reps"))
            ui.onNodeWithTag("field:Reps").performTextReplacement("8")
            Espresso.closeSoftKeyboard()
            ui.onNodeWithTag("logger-list").performScrollToNode(hasText("Save set", substring = false))
            ui.onNodeWithText("Save set", substring = false).performClick()
            ui.waitUntil(15000) { !model.busy.value && runBlocking { db.dao().sets().size == 1 } }
            assertTrue(model.healthConnectionState.value.working)
            capture("health-sync-logging")
            release.complete(Unit); assertTrue(runBlocking { withTimeout(15000) { sync.await() } })
            ui.waitUntil(15000) { model.data.value.workouts.size == 3 }
            assertEquals(1, runBlocking { db.dao().sets().size }); assertNotNull(runBlocking { db.dao().active() })
            assertEquals(2, runBlocking { db.dao().workouts().count { it.isComplete } })
            ui.onNodeWithTag("logger-list").assertExists()
        } finally { release.complete(Unit); scope.cancel(); model.setAutomaticHealth(false) }
    }
}
