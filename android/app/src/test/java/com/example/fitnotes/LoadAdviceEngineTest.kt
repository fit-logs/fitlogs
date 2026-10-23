package com.example.fitnotes

import com.example.fitnotes.data.*
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class LoadAdviceEngineTest {
    private val today=LocalDate.parse("2026-10-23")
    private fun profile(unit:String="kg",increment:String="2.5",gym:String="Gym A",basis:String="total")=LoadProfile("p",1,gym,"Barbell",basis,LoadMode.EXTERNAL,unit,increment)
    private fun request(low:Int=8,high:Int=10,n:Int=3,rir:Double?=2.0)=LoadRequest(1,"Bench Press",List(n){LoadTarget(it+1L,it,low,high,LoadMode.EXTERNAL,rir=rir)})
    private fun session(day:Int,kg:Double=80.0,reps:Int=6,rir:Double?=2.0,p:LoadProfile=profile(),n:Int=3,skipped:Boolean=false)=LoadHistorySession("s$day","2026-10-$day",List(n){LoadHistorySet("$day:$it",kg,reps,LoadMode.EXTERNAL,false,p.gym,p.equipment,p.basis,rir)},expected=3,skipped=skipped)
    private fun suggest(r:LoadRequest=request(),h:List<LoadHistorySession> = listOf(session(20),session(22)),p:LoadProfile?=profile())=LoadAdviceEngine.suggest(r,h,p,today)
    @Test fun recordedEightyForSixWithHeadroomProposesSeventyFiveForEightAndLessForTen(){
        val a=suggest();assertEquals(listOf("75","75","75"),a.loads.map{it.value});assertTrue(a.explanation.contains("2026-10-22"));assertTrue(a.explanation.contains("80 kg × 6"))
        val six=suggest(request(6,6));val ten=suggest(request(10,10));assertTrue(six.loads.first().value.toDouble()>ten.loads.first().value.toDouble());assertEquals("80",six.loads.first().value)
    }
    @Test fun fullTopRangeOnTwoDatesWithReportedHeadroomAllowsOnlyOneIncrement(){
        val a=suggest(h=listOf(session(20,reps=10,rir=3.0),session(22,reps=10,rir=3.0)));assertEquals("progress",a.state);assertEquals("82.5",a.loads.first().value)
        val oneNewLoad=suggest(h=listOf(session(20,kg=70.0,reps=10,rir=3.0),session(22,reps=10,rir=3.0)));assertTrue(oneNewLoad.loads.all{it.value.toDouble()<=80})
    }
    @Test fun missingRirMissedSetsSkippedTargetsAndHighEffortNeverTriggerAnIncrease(){
        for(rows in listOf(listOf(session(20,reps=10,rir=null),session(22,reps=10,rir=null)),listOf(session(20,reps=10),session(22,reps=10,n=2)),listOf(session(20,reps=10),session(22,reps=10,skipped=true)),listOf(session(20,reps=10,rir=0.0),session(22,reps=10,rir=0.0)))){
            val a=suggest(h=rows);assertTrue(a.loads.all{it.value.toDouble()<=80});assertNotEquals("progress",a.state)
        }
        assertTrue(suggest(h=listOf(session(20,reps=10,rir=null),session(22,reps=10,rir=null))).explanation.contains("not recorded"))
    }
    @Test fun explicitWarmupsAndAnIsolatedHugeOutlierCannotDriveWorkingLoads(){
        val latest=session(22).let{s->s.copy(sets=s.sets+LoadHistorySet("warm",20.0,10,LoadMode.EXTERNAL,true,"Gym A","Barbell","total",4.0)+LoadHistorySet("outlier",1000.0,6,LoadMode.EXTERNAL,false,"Gym A","Barbell","total",2.0))}
        val a=suggest(h=listOf(session(20),latest));assertEquals("75",a.loads.first().value);assertEquals(2,a.excludedSets);assertEquals(6,a.retainedSets)
    }
    @Test fun noHistorySparseStaleConflictingOrWrongSetupRequireManualCalibration(){
        val wrong=session(22).copy(sets=session(22).sets.map{it.copy(gym="Gym B")})
        for(rows in listOf(emptyList(),listOf(session(22)),listOf(session(1).copy(date="2026-08-01"),session(2).copy(date="2026-08-03")),listOf(session(20),wrong),listOf(session(20),session(22,kg=200.0)),listOf(session(20),session(21),session(22,kg=200.0))))assertEquals("manual",suggest(h=rows).state)
        assertEquals("manual",suggest(p=null).state)
        val conflict=session(22).copy(sets=session(22).sets.map{it.copy(rir=2.0,rpe=10.0)})
        assertEquals("manual",suggest(h=listOf(session(20),conflict)).state)
        assertEquals("manual",suggest(r=request(rir=7.0)).state)
    }
    @Test fun perHandGymEquipmentAndAssistanceNeverShareExactExternalLoads(){
        val perHand=profile(basis="per_hand");assertEquals("manual",suggest(p=perHand).state)
        val matching=listOf(session(20,kg=20.0,reps=8,p=perHand),session(22,kg=20.0,reps=8,p=perHand));val a=suggest(h=matching,p=perHand);assertEquals("20",a.loads.first().value);assertTrue(a.explanation.contains("per hand"))
        assertEquals("manual",suggest(p=profile().copy(equipment="Cable stack")).state)
        assertEquals("manual",suggest(p=profile().copy(mode=LoadMode.ASSISTANCE)).state)
        assertEquals("manual",suggest(p=profile().copy(mode=LoadMode.BODYWEIGHT)).state)
    }
    @Test fun poundsAndMultiGymIncrementsRoundInTheSelectedEquipmentUnitWithoutDoubling(){
        val p=profile("lb","5");val a=suggest(p=p);val numeric=a.loads.first().value.toDouble();assertEquals(0.0,numeric%5,1e-9);assertTrue(numeric*0.45359237<=76.000001);assertEquals("lb",a.loads.first().unit)
        val q=profile(increment="1.25");val b=suggest(p=q);assertEquals("75",b.loads.first().value)
        val coarse=suggest(p=profile(increment="25"),h=listOf(session(20,reps=10,rir=3.0),session(22,reps=10,rir=3.0)));assertNotEquals("progress",coarse.state)
    }
    @Test fun plateauCanProposeAnOptionalDeloadButImprovingRepsAreNotAPlateau(){
        val plateau=suggest(h=listOf(session(18,reps=8),session(20,reps=8),session(22,reps=8)));assertEquals("deload",plateau.state);assertEquals("75",plateau.loads.first().value)
        val improving=suggest(h=listOf(session(18,reps=6),session(20,reps=7),session(22,reps=8)));assertFalse(improving.explanation.contains("plateau"));assertEquals("hold",improving.state)
    }
    @Test fun potentialDuplicateDateHistoryDoesNotCountAsIndependentProgressionEvidence(){
        val a=session(22,reps=10,rir=3.0);val b=a.copy(id="duplicate",sets=a.sets.map{it.copy(id="copy:${it.id}")})
        val result=suggest(h=listOf(session(20,reps=10,rir=3.0),a,b));assertEquals("manual",result.state);assertTrue(result.explanation.contains("duplicate"))
    }
    @Test fun deterministicPermutationAndRoundingPropertiesHoldAcrossRepGoalsAndIncrements(){
        for(rep in 3..10)for(step in listOf("0.5","1.25","2.5","5")){
            val p=profile(increment=step);val h=listOf(session(20),session(22));val r=request(rep,rep)
            val a=suggest(r,h,p);assertEquals(a,suggest(r,h.reversed(),p));assertEquals(a,suggest(r,h,p))
            a.loads.forEach{load->val value=java.math.BigDecimal(load.value);assertEquals(0,value.remainder(java.math.BigDecimal(step)).signum());assertTrue(value.signum()>0);assertTrue(value.toDouble()<=if(a.state=="progress")80+step.toDouble() else 80.0)}
        }
    }
}
