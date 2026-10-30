package com.example.fitnotes.ui

import com.example.fitnotes.data.*

/** Editable defaults only. Looking at a suggestion never records a performed set. */
data class SetEntrySuggestion(val weight:String="",val reps:String="",val mode:String=LoadMode.EXTERNAL)
fun setEntrySuggestion(exercise:NotebookExercise,targets:List<WorkoutSetTarget>,previous:NotebookExercise?):SetEntrySuggestion {
    val actual=exercise.sets.lastOrNull { !it.cardio }
    val target=targets.lastOrNull { !it.skipped }
    val source=actual ?: if(target==null)previous?.sets?.lastOrNull { !it.cardio } else null
    val mode=source?.local?.loadMode ?: target?.loadMode ?: LoadMode.EXTERNAL
    return SetEntrySuggestion(
        if(mode==LoadMode.REPS_ONLY) "" else if(source!=null)source.weight?.let(::weightText).orEmpty() else target?.let { suggestedActualWeight(it.target) }.orEmpty(),
        source?.reps?.toString() ?: target?.repsMin?.toString().orEmpty(),mode)
}
