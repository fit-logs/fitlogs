package com.example.fitnotes.data

import com.example.fitnotes.ui.NotebookSession

/** Actual results become targets only after preview/save; no activity is created. */
fun planFromCompletedSession(session:NotebookSession, prescribed:List<WorkoutSetTarget>):PlanPreview {
    require(session.completed){"Finish this workout before using it as a plan."}
    val exercises=session.exercises.map { exercise ->
        val entry=session.local?.entries?.firstOrNull{it.entry.id==exercise.entryId}?.entry
        val sets=exercise.sets.filter{(it.reps?:0)>0}.map { set ->
            val weight=if(set.local?.loadMode==LoadMode.BODYWEIGHT&&(set.weight==null||set.weight==0.0)) PlanWeight(null,"bodyweight")
                else set.weight?.let{PlanWeight(weightText(it),"kg")}?:PlanWeight(null,null)
            val rest=set.local?.targetId?.let{id->prescribed.firstOrNull{it.id==id&&it.entryId==set.local.entryId}?.restSeconds}
            PlanSetTarget(set.reps,set.reps,weight,rest,set.local?.loadMode?:LoadMode.UNKNOWN,set.local?.isWarmUp?:false)
        }
        ResolvedPlanExercise(PlanPrescription(exercise.name,sets,entry?.planNote.orEmpty()),exercise.canonicalId,supersetGroup=entry?.supersetGroup.orEmpty())
    }
    val spec=PlanSpec(session.title.ifBlank{"Workout"},"",0,session.notes,exercises.map{it.prescription},planFingerprint(java.util.UUID.randomUUID().toString()))
    return PlanPreview(spec,exercises)
}
