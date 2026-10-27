package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Test
import org.junit.Assert.*

class ProgressPerformanceTest {
    private fun set(kg:Double?,reps:Int,mode:String=LoadMode.UNKNOWN)=StrengthResult("exercise:1","Bench Press",kg,reps,mode)
    private fun session(id:String,date:String,vararg sets:StrengthResult,complete:Boolean=true)=TrainingObservation(id,date,complete,null,null,sets.toList())
    @Test fun aliasesRankExactThenPrefixThenTokensAndReturnOneCanonicalRow() {
        val rows=listOf(ProgressExercise("a","Chin Up",listOf("Chip Up","Chin Up")),ProgressExercise("b","Chin Up Neutral"),ProgressExercise("c","Wide Chin Up"))
        assertEquals(listOf("a"),searchProgressExercises(rows,"  CHIP   UP ").map{it.key})
        assertEquals(listOf("a","b","c"),searchProgressExercises(rows,"chin up").map{it.key})
        assertTrue(searchProgressExercises(rows,"squat").isEmpty())
    }
    @Test fun maxLoadKeepsEveryAssociatedRepCountAndSameDaySessionIdentity() {
        val rows=listOf(session("a","2026-10-10",set(90.0,3),set(90.0,8),set(80.0,10)),session("b","2026-10-10",set(90.0,6)))
        val points=strengthPoints(rows,"exercise:1",StrengthMetric.WEIGHT)
        assertEquals(listOf("a","b"),points.map{it.sessionId});assertEquals(listOf(3,8),points[0].relevantSets.map{it.reps})
        assertEquals("90 kg × 3, 8",strengthPointSummary(points[0],StrengthMetric.WEIGHT));assertTrue(points.all{it.start==null})
        assertEquals(progressDateCoordinate(points[0].date),progressDateCoordinate(points[1].date),0.0)
    }
    @Test fun incompleteLoadSessionBreaksLineAndPartialVolumeIsNotPublished() {
        val rows=listOf(session("a","2026-10-01",set(80.0,8,LoadMode.EXTERNAL)),session("b","2026-10-02",set(80.0,8,LoadMode.EXTERNAL),set(null,8)),session("c","2026-10-10",set(80.0,10,LoadMode.EXTERNAL)))
        val points=strengthPoints(rows,"exercise:1",StrengthMetric.VOLUME)
        assertEquals(listOf(640.0,800.0),points.map{it.value});assertNotEquals(points[0].segment,points[1].segment)
        assertEquals(9.0,progressDateCoordinate(points[1].date)-progressDateCoordinate(points[0].date),0.0)
    }
    @Test fun assistedLoadsHaveNoWeightMetricOrOneRepMaxAndUseRepsDefault() {
        val rows=listOf(session("a","2026-10-01",set(40.0,8,LoadMode.ASSISTANCE)))
        assertEquals(listOf(StrengthMetric.REPS),strengthMetrics(rows,"exercise:1"));assertEquals(StrengthMetric.REPS,defaultStrengthMetric(rows,"exercise:1"))
        assertEquals("40 kg assistance × 8",strengthPointSummary(strengthPoints(rows,"exercise:1",StrengthMetric.REPS).single(),StrengthMetric.REPS))
    }
    @Test fun actualZeroIsRetainedUnknownLoadAndUnfinishedWorkoutAreOmitted() {
        val rows=listOf(session("a","2026-10-01",set(0.0,8)),session("b","2026-10-02",set(null,12)),session("c","2026-10-03",set(99.0,20),complete=false))
        val points=strengthPoints(rows,"exercise:1",StrengthMetric.WEIGHT);assertEquals(0.0,points.single().value,0.0)
        assertEquals(StrengthMetric.REPS,defaultStrengthMetric(rows,"exercise:1"));assertFalse(strengthMetrics(rows,"exercise:1").contains(StrengthMetric.VOLUME))
        assertTrue(strengthPoints(rows,"exercise:1",StrengthMetric.REPS,"2026-11-01").isEmpty())
    }
    @Test fun groupingCombinesOnlyConsecutiveEquivalentLoads() {
        assertEquals("70 kg × 10 · 80 kg × 8, 8 · 70 kg × 7",compactStrengthResults(listOf(set(70.0,10),set(80.0,8),set(80.0,8),set(70.0,7))))
    }
    @Test fun singletonConstantZeroAndOrdinaryBoundsHaveRoundedTicks() {
        for(values in listOf(listOf(0.0),listOf(80.0),listOf(80.0,80.0),listOf(51.0,75.0,99.0))) {
            val bounds=plotBounds(values);assertTrue(bounds.low<bounds.high);assertTrue(bounds.low<=values.min());assertTrue(bounds.high>=values.max())
            assertEquals(Math.rint(bounds.low/bounds.step),bounds.low/bounds.step,1e-8)
        }
    }
    @Test fun currentPlanRoundTripPreservesVaryingRangesUnknownWeightBodyweightAssistanceAndRest() {
        val sets=listOf(PlanSetTarget(8,12,PlanWeight("80","kg"),90,LoadMode.EXTERNAL),PlanSetTarget(5,5,PlanWeight(null,null),null,LoadMode.UNKNOWN),PlanSetTarget(10,10,PlanWeight(null,"bodyweight"),60,LoadMode.BODYWEIGHT),PlanSetTarget(6,8,PlanWeight("20","kg"),120,LoadMode.ASSISTANCE),PlanSetTarget(null,null))
        val spec=PlanSpec("Fixture","",45,"General note",listOf(PlanPrescription("Chin Up",sets,"Exercise note")),planFingerprint("fixture"),"Warm up")
        val decoded=WorkoutPlanParser.parse(WorkoutPlanFormat.encode(spec),"")
        assertEquals(spec.copy(fingerprint=decoded.fingerprint),decoded)
    }
    @Test fun incompatibleAddedLoadAndTotalLoadRemainInspectableThroughRepsOnly() {
        val records=listOf(session("a","2026-10-01",set(20.0,8,LoadMode.BODYWEIGHT)),session("b","2026-10-02",set(80.0,8,LoadMode.EXTERNAL)))
        assertFalse(strengthMetrics(records,"exercise:1").contains(StrengthMetric.WEIGHT))
        assertEquals(StrengthMetric.REPS,defaultStrengthMetric(records,"exercise:1"))
        assertEquals(2,strengthPoints(records,"exercise:1",StrengthMetric.REPS).size)
    }

}
