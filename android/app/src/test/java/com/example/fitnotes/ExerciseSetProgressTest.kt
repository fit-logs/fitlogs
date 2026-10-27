package com.example.fitnotes

import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import org.junit.Assert.*
import org.junit.Test

class ExerciseSetProgressTest {
    private val entry = WorkoutExercise(10, 1, 1, 0)
    private val exercise = Exercise(1, "Synthetic hammer curl", "synthetic hammer curl")
    private val targets = (1L..3L).map { WorkoutSetTarget(it, entry.id, it.toInt() - 1, 8, 10, "20", "kg", 90) }
    private fun actual(id: Long, target: Long? = null) =
        WorkoutSet(id, entry.id, 20.0, 8, id.toInt(), targetId = target).let {
            NotebookSet("set:$id", it.weightKg, it.reps, null, null, null, "", local = it)
        }
    private fun summary(sets: List<NotebookSet>, planned: List<WorkoutSetTarget>? = targets) =
        exerciseSetProgress(NotebookExercise("entry:10", exercise.name, entryId = entry.id, sets = sets),
            planned?.let { RunExerciseDetail(entry, exercise, it) })

    @Test fun anExerciseAddedToAPlanCountsAllThreeActualSetsEvenWithAnEmptyTargetRelation() {
        val sets = (1L..3L).map { actual(it) }
        assertEquals(ExerciseSetProgress("3 sets logged", false), summary(sets, emptyList()))
        assertEquals(summary(sets, null), summary(sets, emptyList()))
    }
    @Test fun unprescribedExercisesNeverInventATotalOrCompletionStatus() {
        assertEquals(ExerciseSetProgress("No sets logged", false), summary(emptyList(), emptyList()))
        assertEquals(ExerciseSetProgress("1 set logged", false), summary(listOf(actual(1)), emptyList()))
    }
    @Test fun extrasRemainVisibleButDoNotSatisfyUnfinishedTargets() {
        assertEquals(ExerciseSetProgress("0 of 3 sets", false), summary(emptyList()))
        assertEquals(ExerciseSetProgress("2 of 3 sets · 1 extra", false), summary(listOf(actual(1, 1), actual(2, 2), actual(3))))
        assertEquals(ExerciseSetProgress("0 of 3 sets · 3 extra", false), summary((1L..3L).map { actual(it) }))
    }
    @Test fun completingTargetsAndLoggingExtrasRetainsTheCompletionCheck() {
        val linked = (1L..3L).map { actual(it, it) }
        assertEquals(ExerciseSetProgress("3 of 3 sets", true), summary(linked))
        assertEquals(ExerciseSetProgress("3 of 3 sets · 2 extra", true), summary(linked + actual(4) + actual(5)))
    }
    @Test fun skipsAndUnrelatedTargetLinksAreNotCountedAsCompletedTargets() {
        assertEquals(ExerciseSetProgress("1 of 3 sets · 1 extra", false),
            summary(listOf(actual(1, 1), actual(2, 99)), targets.map { it.copy(skipped = it.id == 2L) }))
    }
}
