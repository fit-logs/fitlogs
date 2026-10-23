package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class TrainingCalculationTest {
    private class Clock(var time:Long=10000,var boot:Int=3):RestClock { override fun elapsed()=time;override fun bootCount()=boot }
    private fun target(id:Long=1,position:Int=0,warmUp:Boolean=false,skipped:Boolean=false)=WorkoutSetTarget(id,1,position,8,10,"40.5","kg",90,isWarmUp=warmUp,skipped=skipped)
    private fun result(target:WorkoutSetTarget,reps:Int=10,weight:Double=40.5,mode:String=LoadMode.EXTERNAL)=WorkoutSet(target.id,1,weight,reps,target.position,targetId=target.id,loadMode=mode,isWarmUp=target.isWarmUp)
    @Test fun timerUsesElapsedTimeAndPreservesPausedTime() {
        val c=Clock();val timer=RestTimerState(workoutId=1,token="timer",sourceTargetId=1,remainingMillis=90000,anchorElapsed=c.time,bootCount=c.boot,running=true)
        c.time+=12000;assertEquals(78000,timer.remaining(c))
        val paused=timer.pause(c);c.time+=200000;assertEquals(78000,paused.remaining(c))
        val resumed=paused.resume(c);c.time+=5000;assertEquals(73000,resumed.remaining(c))
        assertEquals(103000,resumed.extend(c).remaining(c))
        assertFalse(paused.extend(c).running)
    }
    @Test fun rebootAndInvalidElapsedAnchorRecoverPausedWithoutGuessingElapsedRest() {
        val c=Clock();val timer=RestTimerState(workoutId=1,token="timer",sourceTargetId=null,remainingMillis=90000,anchorElapsed=10000,bootCount=3,running=true)
        c.boot=4;c.time=500;val recovered=timer.recovered(c)
        assertFalse(recovered.running);assertEquals(90000,recovered.remaining(c))
        c.boot=3;assertFalse(timer.recovered(c).running)
    }
    @Test fun elapsedExpirationNeverBecomesNegativeAndExplicitExtensionRestartsIt() {
        val c=Clock(time=100000);val timer=RestTimerState(workoutId=1,token="timer",sourceTargetId=1,remainingMillis=1000,anchorElapsed=10000,bootCount=3,running=true)
        assertEquals(0,timer.remaining(c))
        val finished=timer.copy(running=false,alerted=true,remainingMillis=0)
        assertTrue(finished.extend(c).running);assertEquals(30000,finished.extend(c).remaining(c))
    }
    @Test fun explicitPrescriptionIsAuthoritativeAndUnknownLoadStaysUnspecified() {
        assertEquals("40.50",targetWeightKg(PlanSetTarget(8,10,PlanWeight("40.50","kg"))))
        assertEquals("45.4726350925",targetWeightKg(PlanSetTarget(8,10,PlanWeight("100.25","lb"))))
        assertEquals("",targetWeightKg(PlanSetTarget(8,10)))
        assertEquals(LoadMode.BODYWEIGHT,PlanSetTarget(8,10,PlanWeight(null,"bodyweight")).loadMode)
    }
    @Test fun progressionRequiresEveryWorkingSetAndExcludesSkippedWarmupsAndUnplannedExtras() {
        val config=ExerciseProgression(1,true,"2.5","kg")
        val targets=listOf(target(1,0,true),target(2,1),target(3,2))
        assertFalse(doubleProgression(1,targets,listOf(result(targets[1])),config)!!.canIncrease)
        assertFalse(doubleProgression(1,targets.map{if(it.id==3L)it.copy(skipped=true) else it},listOf(result(targets[1]),result(targets[2])),config)!!.canIncrease)
        val actual=listOf(result(targets[1]),result(targets[2]),WorkoutSet(99,1,999.0,99,3,loadMode=LoadMode.EXTERNAL))
        val suggestion=doubleProgression(1,targets,actual,config)!!
        assertTrue(suggestion.canIncrease);assertEquals("40.5",suggestion.targets[0].weight.value);assertEquals("43",suggestion.targets[1].weight.value)
        assertTrue(suggestion.explanation.contains("all 2"));assertEquals(8,suggestion.targets[1].repsMin)
    }
    @Test fun progressionHoldsForLowRepsLowWeightsUnknownModesAndMixedUnits() {
        val t=target();val config=ExerciseProgression(1,true,"2.5","kg")
        listOf(result(t,9),result(t,10,39.0),result(t,mode=LoadMode.UNKNOWN)).forEach{assertFalse(doubleProgression(1,listOf(t),listOf(it),config)!!.canIncrease)}
        assertFalse(doubleProgression(1,listOf(t.copy(weightUnit="lb")),listOf(result(t)),config)!!.canIncrease)
        assertNull(doubleProgression(1,listOf(t),listOf(result(t)),config.copy(enabled=false)))
        val heavy=t.copy(weightValue="9999");assertFalse(doubleProgression(1,listOf(heavy),listOf(result(heavy,weight=9999.0)),config)!!.canIncrease)
    }
    @Test fun estimatesRequireExternalWorkingSetsAndSupportedReps() {
        assertEquals(100.0,estimatedOneRepMax(100.0,1,LoadMode.EXTERNAL,false)!!,0.0)
        assertEquals(120.0,estimatedOneRepMax(100.0,6,LoadMode.EXTERNAL,false)!!,1e-9)
        listOf(LoadMode.UNKNOWN,LoadMode.BODYWEIGHT,LoadMode.ASSISTANCE,LoadMode.REPS_ONLY).forEach{assertNull(estimatedOneRepMax(100.0,6,it,false))}
        assertNull(estimatedOneRepMax(100.0,11,LoadMode.EXTERNAL,false));assertNull(estimatedOneRepMax(100.0,6,LoadMode.EXTERNAL,true))
    }
    @Test fun statsIncludeMultipleSameDaySessionsAndNeverTreatUnknownBodyweightAsExternalVolume() {
        val r=StrengthResult("exercise:1","Synthetic press",40.0,10,LoadMode.EXTERNAL)
        val sessions=listOf(TrainingObservation("a","2026-10-12",true,1000,2000,listOf(r)),TrainingObservation("b","2026-10-12",true,3000,4000,listOf(r.copy(mode=LoadMode.UNKNOWN),r.copy(mode=LoadMode.BODYWEIGHT,weightKg=0.0),r.copy(mode=LoadMode.REPS_ONLY,weightKg=null))),TrainingObservation("pending","2026-10-13",false,5000,null,listOf(r)))
        val stats=StrengthStatistics.calculate(sessions)
        assertEquals(2,stats.completedWorkouts);assertEquals(4,stats.actualSets);assertEquals(2,stats.weeks.single().workouts)
        val exercise=stats.exercises.single();assertEquals(400.0,exercise.externalVolume,0.0);assertEquals(400.0,exercise.unknownModeVolume,0.0)
        assertEquals(setOf("2026-10-12"),exercise.volumeByDate.keys)
    }
    @Test fun firstObservationAndAmbiguousSameDayDateOnlyHistoryAreNotNewRecords() {
        val a=TrainingObservation("a","2026-10-12",true,null,null,listOf(StrengthResult("exercise:1","Synthetic",40.0,8,LoadMode.EXTERNAL)))
        assertFalse(StrengthStatistics.summary(a,listOf(a)).contains("New estimated"))
        val b=a.copy(id="b",results=a.results.map{it.copy(weightKg=50.0)})
        assertFalse(StrengthStatistics.summary(b,listOf(a,b)).contains("New estimated"))
        val later=b.copy(date="2026-10-13");assertTrue(StrengthStatistics.summary(later,listOf(a,later)).contains("New estimated"))
    }
    @Test fun repsOnlyAndAssistanceComparisonsDoNotInventWeightedTrainingVolume() {
        for(mode in listOf(LoadMode.REPS_ONLY,LoadMode.ASSISTANCE)) {
            val before=TrainingObservation("a","2026-10-12",true,null,null,listOf(StrengthResult("exercise:1","Synthetic",if(mode==LoadMode.ASSISTANCE)20.0 else null,8,mode)))
            val now=before.copy(id="b",date="2026-10-13",results=before.results.map{it.copy(reps=10)})
            val summary=StrengthStatistics.summary(now,listOf(before,now))
            assertTrue(summary.contains("actual rep change 2"));assertFalse(summary.contains("volume change"));assertFalse(summary.contains("Estimated 1RM"))
        }
    }
    @Test fun summaryHandlesMixedKnownAndUnknownCompletionTimesOnAnEarlierDay() {
        val result = StrengthResult("exercise:1", "Synthetic press", 40.0, 8, LoadMode.UNKNOWN)
        val dateOnly = TrainingObservation("date-only", "2026-10-12", true, null, null, listOf(result))
        val timed = dateOnly.copy(id = "timed", started = 1000L, finished = 2000L, results = listOf(result.copy(reps = 10)))
        val current = timed.copy(id = "current", date = "2026-10-13", started = 3000L, finished = 4000L, results = listOf(result.copy(reps = 12)))
        for (history in listOf(listOf(dateOnly, timed, current), listOf(timed, dateOnly, current))) {
            assertTrue(StrengthStatistics.summary(current, history).contains("recorded-load volume change 80 kg·reps"))
        }
        assertNull(dateOnly.finished)
    }
    @Test fun fuzzyRankingPrefersExactAndCompactMatchesWithoutEquatingVariants() {
        assertTrue(fuzzyMatchScore("bench","bench")!!>fuzzyMatchScore("bench","flat barbell bench press")!!)
        assertNotNull(fuzzyMatchScore("bp","Bench Press"));assertNull(fuzzyMatchScore("zz","Bench Press"))
        assertEquals(fuzzyMatchScore("e","é"),fuzzyMatchScore("e","e"))
        assertEquals(ExerciseMatchKind.UNKNOWN,matchPlanExercise("DB press",listOf(Exercise(1,"Dumbbell Shoulder Press","dumbbell shoulder press"))).kind)
    }
    @Test fun aliasCollisionWithAnExistingNameRequiresManualSelection() {
        val catalogue=listOf(Exercise(1,"Press","press"),Exercise(2,"Shoulder press","shoulder press"))
        assertEquals(ExerciseMatchKind.AMBIGUOUS,matchPlanExercise("Press",catalogue,aliases=mapOf("press" to setOf(2L))).kind)
    }
    @Test fun schemaExampleParsesAndRejectsUndeclaredFields() {
        val text=WorkoutPlanFormat.example("2026-10-14")
        assertEquals(3,WorkoutPlanParser.parse(text,"").exercises.single().setTargets.size)
        val extended=text.replace("\"restSeconds\": 90","\"restSeconds\": 90,\"isWarmUp\":true")
        assertTrue(WorkoutPlanParser.parse(extended,"").exercises.single().setTargets.first().isWarmUp)
        try { WorkoutPlanParser.parse(text.replace("\"restSeconds\": 90","\"restSeconds\": 90,\"superset\":\"A\""),"");fail() } catch(_:IllegalArgumentException){}
    }
}
