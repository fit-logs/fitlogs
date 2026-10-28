package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class CoachResponseTest {
    private val press=Exercise(1,"Synthetic Press","synthetic press")
    private val sample="""{"title":"Upper","type":"strength","durationMinutes":45,"exercises":[{"name":"Synthetic Press","sets":3,"reps":"8-10"}]}"""
    private fun draft(raw:String)=PortableProgramParser.draft((CoachResponse.parse(raw) as CoachResponse.Plan).definition,listOf(press),emptyList(),emptySet())
    private fun reject(raw:String,part:String) {try {CoachResponse.parse(raw);fail("Expected invalid response")}catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(part,true))}}
    @Test fun singleStrengthWithSurroundingExplanationUsesExistingSetModelAndIdentity() {
        val d=draft("Here is your session.\n```json\n$sample\n```\nEnjoy.")
        assertEquals("1",d.weeks);assertEquals(1,d.days.size)
        val e=d.days.single().plan.exercises.single();assertEquals(1L,e.exerciseId);assertEquals(3,e.targets.size)
        assertEquals(8,e.targets.first().validated(e.sourceName).repsMin);assertEquals(10,e.targets.first().validated(e.sourceName).repsMax)
        assertNull(e.targets.first().validated(e.sourceName).weight.value)
        d.validated()
        assertEquals("Upper",WorkoutPlanParser.parse(sample, "").title)
    }
    @Test fun individualSetsRetainZeroUnspecifiedLbRestWarmupAndNotes() {
        val d=draft("""{"title":"Upper","type":"strength","warmUp":"Gentle movement","notes":"Session guidance","exercises":[{"name":"Synthetic Press","notes":"Exercise guidance","sets":[{"repsMin":8,"repsMax":10,"weight":0,"unit":"kg","restSeconds":90,"isWarmUp":true},{"repsMin":6,"repsMax":8,"weight":"55.50","unit":"lb"},{"repsMin":null,"repsMax":null}]}]}""")
        val sets=d.validated().second.single().spec.exercises.single().setTargets
        assertEquals("0",sets[0].weight.value);assertTrue(sets[0].isWarmUp);assertEquals(90,sets[0].restSeconds)
        assertEquals(PlanWeight("55.50","lb"),sets[1].weight);assertEquals(PlanWeight(null,null),sets[2].weight)
        assertEquals("Gentle movement",d.days.single().plan.warmUp);assertEquals("Exercise guidance",d.days.single().plan.exercises.single().note)
    }
    @Test fun runningAndMixedTargetsHaveNoStrengthExercisesOrResults() {
        val d=draft("""{"title":"Run","type":"running","date":"2026-11-01","distanceKm":5,"durationMinutes":30,"intensity":"easy","pace":"conversational","warmUp":"5 min","intervals":"6 x 400 m","coolDown":"5 min"}""")
        val day=d.days.single();assertTrue(day.plan.exercises.isEmpty());assertEquals("2026-11-01",day.plan.date)
        assertEquals(5.0,day.running!!.distanceKm!!,0.0);assertEquals(30,day.running!!.durationMinutes)
        val result=d.validated().first.document.getJSONArray("days").getJSONObject(0)
        assertEquals("6 x 400 m",result.getJSONObject("running").getString("intervals"))
    }
    @Test fun malformedDuplicateUnknownAmbiguousAndIncompatibleFieldsGiveErrors() {
        reject("""{"title":"Broken","sessions":[""","incomplete")
        reject("""{"title":"A","title":"B","exercises":[]}""","Duplicate")
        reject(sample.replace("\"durationMinutes\":45","\"durationMinutes\":45.5"),"whole")
        reject(sample.replace("\"reps\":\"8-10\"","\"reps\":\"8-10\",\"repsMin\":8"),"not both")
        reject(sample.replace("\"sets\":3","\"sets\":3,\"weigth\":20"),"weigth")
        reject(sample.replace("\"sets\":3","\"sets\":3,\"weight\":20"),"units")
        reject(sample.replace("\"reps\":\"8-10\"","\"reps\":\"ten\""),"reps")
        reject("""{"title":"Run","type":"running","distanceKm":"5"}""","distanceKm")
        reject("""{"title":"Run","type":"running","intervals":[400,400]}""","text")
        reject("$sample\n$sample","one JSON")
    }
    @Test fun adviceIsDisplayedAndAmbiguousExerciseNeverGuessed() {
        assertEquals(CoachResponse.Advice("Take an easy day."),CoachResponse.parse("Take an easy day."))
        assertEquals(CoachResponse.Advice("Review your effort."),CoachResponse.parse("""{"advice":"Review your effort."}"""))
        val definition=(CoachResponse.parse(sample) as CoachResponse.Plan).definition
        val aliases=listOf(ExerciseAlias(1,1,"Synthetic Press","synthetic press"),ExerciseAlias(2,2,"Synthetic Press","synthetic press"))
        val d=PortableProgramParser.draft(definition,listOf(press,Exercise(2,"Other Press","other press")),aliases,emptySet())
        assertNull(d.days.single().plan.exercises.single().exerciseId)
    }
}
