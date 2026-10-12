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

// All mutations require both the isolated package and the synthetic source marker.
class PolishInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun fixture(): FitDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "com.example.fitnotes.preview.polish")
        return FitDatabase.open(context).also { db ->
            assumeTrue(runBlocking { db.importedDao().imported("synthetic-ui-fixture")?.source } == "Synthetic sample history")
        }
    }
    @Test fun preparePickerFixture() {
        val db = fixture()
        try { runBlocking {
            val names = listOf("Demo calf raise", "Demo chest-supported row with a deliberately long exercise name for layout checks", "Demo walking", "Demo press")
            names.forEach { name ->
                val key = name.lowercase(java.util.Locale.ROOT)
                if (db.dao().exerciseByKey(key) == null) db.dao().insertExercise(Exercise(name = name, nameKey = key))
            }
            val prior = db.dao().workouts().first { it.completedAt != null }
            WorkoutRepository(db).addExercise(prior.id, db.dao().exerciseByKey("demo calf raise")!!.id)
        } } finally { db.close() }
    }
    @Test fun selectionSurvivesFiltersAndSearchThenAddsTogether() {
        val db = fixture()
        try {
            val active = runBlocking { db.dao().active()!! }
            val recentId = runBlocking { db.dao().exerciseByKey("demo calf raise")!!.id }
            val favourite = "Demo chest-supported row with a deliberately long exercise name for layout checks"
            val favouriteId = runBlocking { db.dao().exerciseByKey(favourite.lowercase())!!.id }
            val before = runBlocking { db.dao().workoutEntries(active.id).size }
            ui.waitUntil(30000) { ui.onAllNodesWithText("Add exercise").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Add exercise").performScrollTo().performClick()
            ui.onNodeWithText("Demo calf raise").performClick()
            ui.onNodeWithText("Add 1 exercise").assertIsDisplayed()
            ui.onNodeWithText("All", useUnmergedTree = true).performClick()
            ui.onNodeWithContentDescription("Favourite $favourite").performScrollTo().performClick()
            ui.onNodeWithText("Favourites", useUnmergedTree = true).performClick()
            ui.onNodeWithText(favourite).performClick()
            ui.onNodeWithText("Add 2 exercises").assertIsDisplayed()
            ui.onNode(hasSetTextAction()).performTextReplacement("no such exercise")
            ui.onNodeWithText("No matching exercises").assertIsDisplayed()
            ui.onNodeWithText("Add 2 exercises").assertIsDisplayed()
            ui.onNode(hasSetTextAction()).performTextClearance()
            ui.onNodeWithText("Recent", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Add 2 exercises").performClick()
            ui.waitUntil(30000) { runBlocking { db.dao().workoutEntries(active.id).size } == before + 2 }
            assertEquals(listOf(recentId, favouriteId), runBlocking { db.dao().workoutEntries(active.id).takeLast(2).map { it.exerciseId } })
        } finally { db.close() }
    }
}
