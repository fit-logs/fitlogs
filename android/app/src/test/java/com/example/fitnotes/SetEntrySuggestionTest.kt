package com.example.fitnotes

import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import org.junit.Assert.*
import org.junit.Test

class SetEntrySuggestionTest {
    private fun exercise(vararg sets:NotebookSet)=NotebookExercise("e","Press",sets=sets.toList())
    private fun set(weight:Double?,reps:Int,mode:String=LoadMode.EXTERNAL)=NotebookSet("s",weight,reps,null,null,null,"Do not repeat note",local=WorkoutSet(1,1,weight ?: 0.0,reps,0,loadMode=mode))
    private val target=WorkoutSetTarget(1,1,0,10,12,"40","kg",90)
    @Test fun latestCurrentActualOverridesPlanAndHistory() {
        assertEquals(SetEntrySuggestion("32.5","7",LoadMode.EXTERNAL),setEntrySuggestion(exercise(set(30.0,8),set(32.5,7)),listOf(target),exercise(set(25.0,12))))
    }
    @Test fun planThenPreviousThenEmptyAreDraftFallbacks() {
        assertEquals("40",setEntrySuggestion(exercise(),listOf(target),exercise(set(25.0,12))).weight)
        assertEquals(SetEntrySuggestion("25","12"),setEntrySuggestion(exercise(),emptyList(),exercise(set(25.0,12))))
        assertEquals(SetEntrySuggestion(),setEntrySuggestion(exercise(),emptyList(),null))
    }
    @Test fun repsOnlyAndUnspecifiedWeightStayUnspecified() {
        assertEquals(SetEntrySuggestion("","8",LoadMode.REPS_ONLY),setEntrySuggestion(exercise(set(null,8,LoadMode.REPS_ONLY)),listOf(target),null))
        assertEquals("",setEntrySuggestion(exercise(set(null,8)),listOf(target),null).weight)
        assertEquals(LoadMode.ASSISTANCE,setEntrySuggestion(exercise(set(15.0,9,LoadMode.ASSISTANCE)),emptyList(),null).mode)
    }
}
