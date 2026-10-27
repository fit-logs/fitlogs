package com.example.fitnotes.data

// Single schema/example source for the strict V2 parser and outgoing instructions.
object WorkoutPlanFormat {
    val rootFields=setOf("title","date","durationMinutes","note","warmUp","exercises")
    val exerciseFields=setOf("name","note","sets")
    val setFields=setOf("repsMin","repsMax","weight","unit","restSeconds","loadMode","isWarmUp","rir","rpe")
    fun encode(spec:PlanSpec):String {
        WorkoutPlanParser.validate(spec)
        require(spec.exercises.isNotEmpty()&&spec.exercises.all{it.setTargets.isNotEmpty()}){"Add target sets before exporting this plan."}
        val note=listOf(spec.note,spec.legacyGuidance).filter{it.isNotBlank()}.joinToString("\n\n")
        require(note.length<=WorkoutPlanParser.MAX_NOTE){"Shorten workout notes before exporting this plan."}
        val root=org.json.JSONObject().put("title",spec.title).put("date",spec.date).put("note",note).put("warmUp",spec.warmUp)
        if(spec.durationMinutes>0)root.put("durationMinutes",spec.durationMinutes)
        root.put("exercises",org.json.JSONArray().also { exercises->spec.exercises.forEach { exercise->
            exercises.put(org.json.JSONObject().put("name",exercise.name).put("note",exercise.note).put("sets",org.json.JSONArray().also { sets->exercise.setTargets.forEach { target->
                val row=org.json.JSONObject().put("repsMin",target.repsMin?:org.json.JSONObject.NULL).put("repsMax",target.repsMax?:org.json.JSONObject.NULL).put("loadMode",target.loadMode).put("isWarmUp",target.isWarmUp)
                target.weight.unit?.let{row.put("unit",it)};target.weight.value?.let{row.put("weight",it)};target.restSeconds?.let{row.put("restSeconds",it)}
                target.rir?.let { row.put("rir", it) }; target.rpe?.let { row.put("rpe", it) }
                sets.put(row)
            } }))
        } })
        return "WORKOUT_PLAN_V2\n"+root.toString(2)+"\nEND_WORKOUT_PLAN"
    }
    fun example(date:String)="""
        WORKOUT_PLAN_V2
        {
          "title": "Session title",
          "date": "$date",
          "durationMinutes": 45,
          "warmUp": "General warm-up guidance.",
          "note": "Session-level guidance.",
          "exercises": [{
            "name": "Familiar exercise name",
            "note": "Exercise-level guidance.",
            "sets": [
              {"repsMin": 8, "repsMax": 10, "weight": "40.5", "unit": "kg", "restSeconds": 90},
              {"repsMin": 8, "repsMax": 10, "restSeconds": 90},
              {"repsMin": 12, "repsMax": 12, "unit": "bodyweight", "restSeconds": 60}
            ]
          }]
        }
        END_WORKOUT_PLAN
    """.trimIndent()
}
