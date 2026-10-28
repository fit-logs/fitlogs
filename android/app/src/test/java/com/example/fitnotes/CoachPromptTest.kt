package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CoachPromptTest {
    private fun start(date:String)=LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val press=Exercise(1,"Synthetic Press","synthetic press")
    private fun data():AppData {
        fun workout(id:Long,date:String,completed:Boolean,kg:Double):WorkoutDetail {
            val time=start(date)
            return WorkoutDetail(Workout(id,time,if(completed)time+3600000 else null,notes="PRIVATE SESSION NOTE"),
                listOf(EntryDetail(WorkoutExercise(id,id,1,0,planNote="PRIVATE EXERCISE NOTE"),press,listOf(WorkoutSet(id,id,kg,8,0,comment="PRIVATE SET NOTE",loadMode=LoadMode.EXTERNAL)))))
        }
        return AppData(exercises=listOf(press),workouts=listOf(workout(1,"2026-10-20",true,40.0),workout(2,"2026-10-25",false,99.0),workout(3,"2026-09-01",true,50.0)),loading=false,
            imported=ImportedData(body=listOf(BodyMeasurement("b","i","b","2026-10-20","","PRIVATE BODY",70.0,"kg","kg","","{}"))))
    }
    private fun request()=CoachRequest(CoachQuestion.WEEK,"Both","2026-10-01","2026-10-28",HandoffPreferences(goals="Synthetic goal",equipment="Synthetic equipment",constraints="Synthetic constraints",availableMinutes=45))
    @Test fun selectedCompletedHistoryAndPreferencesAreSharedWithPrivacyAndNoMarkers() {
        val text=CoachPrompt.prepare(data(),request())
        assertTrue(text.contains("2026-10-01 through 2026-10-28"));assertTrue(text.contains("Synthetic goal"));assertTrue(text.contains("Synthetic equipment"))
        assertTrue(text.contains("Synthetic Press: 1 performed set(s) × 8 reps, 40 kg"));assertFalse(text.contains("99 kg"));assertFalse(text.contains("50 kg"))
        assertFalse(text.contains("PRIVATE"));assertTrue(text.contains("No history is truncated"));assertTrue(text.contains("\"sessions\""));assertFalse(text.contains("WORKOUT_PLAN_V2"))
    }
    @Test fun ninetyDayContextContainsEveryCompletedSessionAndNoUnfinishedSets() {
        val text=CoachPrompt.prepare(data(),request().copy(first="2026-08-01"))
        assertTrue(text.contains("2026-09-01"));assertTrue(text.contains("50 kg"));assertTrue(text.contains("40 kg"));assertFalse(text.contains("99 kg"))
    }
    @Test fun adviceRequestDoesNotDemandAPlanAndPartialHistoryIsRejected() {
        val text=CoachPrompt.prepare(data(),request().copy(question=CoachQuestion.REVIEW))
        assertFalse(text.contains("\"sessions\""));assertTrue(text.contains("ordinary coaching advice"))
        try{CoachPrompt.prepare(data().copy(imported=ImportedData(loading=true)),request());fail()}catch(_:IllegalArgumentException){}
        try{CoachPrompt.prepare(data(),request().copy(first="bad"));fail()}catch(_:IllegalArgumentException){}
    }
}
