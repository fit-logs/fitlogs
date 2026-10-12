package com.example.fitnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

// Run only against the separate preview application and a clearly synthetic
// fixture. This test never clears data or modifies a normal installation.
class NotebookInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun deliberateSavesEditCancelSwitchFinishRepeatAndDateNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Use the isolated preview application", context.packageName == "com.example.fitnotes.preview")
        val db = FitDatabase.open(context)
        try {
            val repo = WorkoutRepository(db)
            val active = runBlocking { db.dao().active() }
            assumeTrue("Requires the synthetic comparison fixture", active?.title == "Demo — Pull")
            assumeTrue(runBlocking { db.importedDao().imported("synthetic-ui-fixture")?.source } == "Synthetic sample history")
            val entry = runBlocking { db.dao().workoutEntries(active!!.id).first() }
            val second = runBlocking { db.dao().workoutEntries(active!!.id)[1] }
            val secondCount = runBlocking { db.dao().entrySets(second.id).size }
            val initial = runBlocking { db.dao().entrySets(entry.id).size }
            ui.waitUntil(30000) { ui.onAllNodesWithText("Demo lat pulldown").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Demo lat pulldown").performClick()
            ui.waitUntil(30000) { ui.onAllNodesWithText("Save set").fetchSemanticsNodes().size == 1 }
            ui.onNodeWithText("Workout").assertDoesNotExist()
            repeat(3) { index ->
                ui.onNodeWithText("Save set").performClick()
                ui.waitUntil(30000) { ui.onAllNodesWithText("Recorded sets · ${initial + index + 1}").fetchSemanticsNodes().size == 1 }
            }
            val saved = runBlocking { db.dao().entrySets(entry.id) }
            assertEquals(initial + 3, saved.size)
            assertEquals(1, saved.takeLast(3).map { it.weightKg to it.reps }.distinct().size)
            val first = saved.first()
            ui.onNodeWithTag("saved-set:set:${first.id}").performScrollTo().performClick()
            ui.onNodeWithTag("field:Reps").performScrollTo().performTextReplacement("11")
            ui.onNodeWithText("Update set").performScrollTo().performClick()
            ui.waitUntil(30000) { runBlocking { db.dao().set(first.id)?.reps } == 11 }
            assertEquals(initial + 3, runBlocking { db.dao().entrySets(entry.id).size })
            ui.onNodeWithTag("saved-set:set:${first.id}").performScrollTo().performClick()
            ui.onNodeWithTag("field:Reps").performScrollTo().performTextReplacement("99")
            ui.onNodeWithText("Cancel edit").performScrollTo().performClick()
            assertEquals(11, runBlocking { db.dao().set(first.id)!!.reps })
            ui.onNodeWithText("Switch").performClick()
            ui.onNodeWithText("Demo seated row · $secondCount sets").performClick()
            ui.waitUntil(30000) { ui.onAllNodesWithText("Demo seated row").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("Back to workout").performClick()
            ui.waitUntil(30000) { ui.onAllNodesWithText("Finish").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(initial + 3, runBlocking { db.dao().entrySets(entry.id).size })
            val countBeforeDateNavigation = runBlocking { db.dao().workouts().size }
            ui.onNodeWithContentDescription("Previous day").performClick()
            ui.onNodeWithContentDescription("Next day").performClick()
            assertEquals(countBeforeDateNavigation, runBlocking { db.dao().workouts().size })
            ui.onNodeWithText("Finish").performClick()
            ui.onAllNodesWithText("Finish").onLast().performClick()
            ui.waitUntil(30000) { runBlocking { db.dao().active() } == null }
            ui.onNodeWithText("Demo lat pulldown").performClick()
            ui.waitUntil(30000) { ui.onAllNodesWithText("Recorded sets · ${initial + 3}").fetchSemanticsNodes().size == 1 }
            ui.onNodeWithText("Save set").assertDoesNotExist()
            ui.onNodeWithContentDescription("Back to workout").performClick()
            ui.onNodeWithContentDescription("More actions").performClick()
            ui.onNodeWithText("Repeat exercises").performClick()
            ui.waitUntil(30000) { runBlocking { db.dao().active() } != null }
            val repeated = runBlocking { db.dao().active()!! }
            assertTrue(runBlocking { db.dao().workoutEntries(repeated.id).all { db.dao().entrySets(it.id).isEmpty() } })
            assertEquals(3, runBlocking { db.dao().workoutEntries(repeated.id).size })
            assertEquals(3, runBlocking { db.importedDao().body().size })
            assertEquals(1, runBlocking { db.importedDao().workouts().size })
            val backup = runBlocking { repo.backup() }
            assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        } finally { db.close() }
    }
}
