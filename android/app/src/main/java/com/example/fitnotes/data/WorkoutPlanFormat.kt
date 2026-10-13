package com.example.fitnotes.data

// Single schema/example source for the strict V2 parser and outgoing instructions.
object WorkoutPlanFormat {
    val rootFields=setOf("title","date","durationMinutes","note","warmUp","exercises")
    val exerciseFields=setOf("name","note","sets")
    val setFields=setOf("repsMin","repsMax","weight","unit","restSeconds","loadMode","isWarmUp")
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
