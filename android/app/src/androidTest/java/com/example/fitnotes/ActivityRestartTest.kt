package com.example.fitnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

// Run prepare, force-stop the isolated package, then run verify in a new process.
class ActivityRestartTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        assumeTrue(it.packageName == "com.example.fitnotes.preview.activities")
    }
    @Test fun preparePersistedTimer() {
        val context = context(); val db = FitDatabase.open(context)
        try { runBlocking {
            val repo = WorkoutRepository(db)
            repo.restore(Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList()))
            val id = repo.startActivity("climbing", "", "Synthetic restart fixture", "Synthetic persisted note", "Hard")
            File(context.filesDir, "synthetic-restart.txt").writeText("$id\n${db.dao().active()!!.startedAt}")
        } } finally { db.close() }
    }
    @Test fun verifyAfterProcessRestart() {
        val context = context(); val marker = File(context.filesDir, "synthetic-restart.txt")
        assumeTrue(marker.exists())
        val saved = marker.readLines(); val db = FitDatabase.open(context)
        try {
            val active = runBlocking { db.dao().active()!! }
            assertEquals(saved[0].toLong(), active.id); assertEquals(saved[1].toLong(), active.startedAt)
            assertEquals("Synthetic persisted note", active.notes)
            ui.waitUntil(30000) { ui.onAllNodesWithText("Finish session").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Elapsed since " + timestampText(active.startedAt)).performScrollTo().assertIsDisplayed()
            assertTrue(System.currentTimeMillis() > active.startedAt)
        } finally { db.close() }
    }
}
