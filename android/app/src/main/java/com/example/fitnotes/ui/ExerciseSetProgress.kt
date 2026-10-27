package com.example.fitnotes.ui

import com.example.fitnotes.data.RunExerciseDetail

data class ExerciseSetProgress(val label: String, val allTargetsCompleted: Boolean)

fun exerciseSetProgress(exercise: NotebookExercise, planned: RunExerciseDetail?): ExerciseSetProgress {
    val targetIds = planned?.targets?.map { it.id }?.toSet().orEmpty()
    // A run includes newly added exercises even when they have no prescription.
    // Their actual sets count normally; zero targets is not a planned total.
    if (targetIds.isEmpty()) {
        val count = exercise.sets.size
        return ExerciseSetProgress(when (count) {
            0 -> "No sets logged"
            1 -> "1 set logged"
            else -> "$count sets logged"
        }, false)
    }
    val loggedTargets = exercise.sets.mapNotNull { it.local?.targetId }.toSet()
    val completed = targetIds.count { it in loggedTargets }
    val extras = exercise.sets.count { it.local?.targetId !in targetIds }
    return ExerciseSetProgress("$completed of ${targetIds.size} sets" + if (extras > 0) " · $extras extra" else "",
        completed == targetIds.size)
}
