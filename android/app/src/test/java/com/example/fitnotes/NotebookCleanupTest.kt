package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NotebookCleanupTest {
    private lateinit var db:FitDatabase
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FitDatabase::class.java).build()}
    @After fun close(){db.close()}
    private fun native(id:String,records:List<Pair<Int,String>>):ImportPlan {
        val rows=records.mapIndexed{index,(sourceId,date)->WorkoutSourceRow(sourceId.toString(),index,date,"Leg Press","Legs","80","8","","","0","",
            JSONObject().put("_id",sourceId).put("date",date).put("exercise_id",1).put("metric_weight",80).put("reps",8).toString(),true)}
        return HistoryImportReader.plan(HistoryDocument(id,"Original FitNotes native backup","fixture",rows,emptyList()),ImportOptions())
    }
    @Test fun changedNativeBackupReusesSourceRecordsPreservesUserEditsAndAddsNewRecords()=runBlocking {
        val repo=HistoryImportRepository(db);val first=native("first",listOf(1 to "2026-10-10",2 to "2026-10-10"));repo.importHistory(first)
        repo.updateRecord(first.sets[0].id,"82.5","9","",null,"","User note")
        val later=native("later",listOf(1 to "2026-10-10",2 to "2026-10-10",3 to "2026-10-10",4 to "2026-10-11"))
        repo.importHistory(later);assertFalse(repo.importHistory(later).changed)
        assertEquals(2,db.importedDao().workouts().size);assertEquals(4,db.importedDao().sets().size)
        val edited=db.importedDao().set(first.sets[0].id)!!;assertEquals(82.5,edited.weightKg!!,0.0);assertEquals("User note",edited.comment)
        assertEquals(3,db.importedDao().workout(first.workouts.single().id)!!.sets.size)
        assertEquals(2,db.importedDao().imports().size)
    }
    @Test fun conflictDoesNotSilentlyOverwriteOrCreateAnotherWorkout()=runBlocking {
        val repo=HistoryImportRepository(db);repo.importHistory(native("one",listOf(1 to "2026-10-10")))
        val later=native("two",listOf(1 to "2026-10-10"));val changed=later.copy(sets=later.sets.map{it.copy(rawValues=JSONObject(it.rawValues).put("reps",10).toString())})
        assertThrows(IllegalArgumentException::class.java){runBlocking{repo.importHistory(changed)}}
        assertEquals(1,db.importedDao().workouts().size);assertEquals(1,db.importedDao().imports().size);assertEquals(8,db.importedDao().sets().single().reps)
    }
    @Test fun mergeRetainsUniqueSetsNotesHealthAndProvenanceThenReimportIsNoOp()=runBlocking {
        val first=native("a",listOf(1 to "2026-10-10",2 to "2026-10-10"));val second=native("b",listOf(1 to "2026-10-10",3 to "2026-10-10"))
        listOf(first,second).forEach{p->db.importedDao().insertImport(HistoryImport(p.document.id,p.document.source,"fixture",1000,"kg",0,"","{}"));db.importedDao().insertWorkouts(p.workouts.map{it.copy(notes=p.document.id)});db.importedDao().insertSets(p.sets)}
        val source=health("record",null).copy(importedWorkoutId=second.workouts.single().id)
        db.healthDao().putSession(source);db.healthDao().putSamples(listOf(sample(source,source.start+1000,38)))
        val a=db.importedDao().workout(first.workouts.single().id)!!;val b=db.importedDao().workout(second.workouts.single().id)!!
        val backup=WorkoutRepository(db).backup();assertEquals(4,backup.importedSets.size)
        HistoryImportRepository(db).merge(historyMergePreview(a,b))
        assertEquals(1,db.importedDao().workouts().size);assertEquals(3,db.importedDao().sets().size)
        assertEquals("a\n\nb",db.importedDao().workouts().single().notes);assertEquals(a.workout.id,db.healthDao().sessions().single().importedWorkoutId)
        assertEquals(38,db.healthDao().samples().single().bpm.toInt());assertFalse(HistoryImportRepository(db).importHistory(second).changed)
        WorkoutRepository(db).restore(backup);assertEquals(4,db.importedDao().sets().size)
    }
    @Test fun staleMergePreviewCannotDeleteConcurrentEdits()=runBlocking {
        val a=native("a",listOf(1 to "2026-10-10"));val b=native("b",listOf(1 to "2026-10-10"))
        listOf(a,b).forEach{p->db.importedDao().insertImport(HistoryImport(p.document.id,p.document.source,"fixture",1000,"kg",0,"","{}"));db.importedDao().insertWorkouts(p.workouts);db.importedDao().insertSets(p.sets)}
        val preview=historyMergePreview(db.importedDao().workout(a.workouts.single().id)!!,db.importedDao().workout(b.workouts.single().id)!!)
        db.importedDao().updateSet(b.sets.single().copy(comment="Concurrent edit"))
        assertThrows(IllegalArgumentException::class.java){runBlocking{HistoryImportRepository(db).merge(preview)}}
        assertEquals(2,db.importedDao().workouts().size);assertEquals("Concurrent edit",db.importedDao().set(b.sets.single().id)!!.comment)
    }
    private fun health(id:String,workout:Long?)=HealthSession(healthKey(GARMIN_PACKAGE,id),workout,id,GARMIN_PACKAGE,"Garmin",null,1,1000,1000,1000,10000,46800,46800,"strength",80,"Legs","",null,"","read","read")
    private fun sample(s:HealthSession,time:Long,bpm:Long,origin:String=GARMIN_PACKAGE,record:String="hr")=HealthSample(sampleKey(s.key,origin,record,time),s.key,record,origin,time,bpm)
    @Test fun correctLowReadingSurvivesWhileWrongOriginAndBoundariesDoNot() {
        val source=health("one",null)
        val samples=listOf(sample(source,999,38),sample(source,1000,38),sample(source,4000,38,"other"),sample(source,5000,100),sample(source,10000,38))
        assertEquals(listOf(38L,100L),associatedHeartSamples(source,samples).map{it.bpm})
        assertEquals(listOf(1000L,5000L),associatedHeartSamples(source,samples).map{it.time})
    }
    @Test fun gapsUseMedianPositiveIntervalsAndKeepSparseSingleAndDuplicateTimes() {
        val s=health("one",null);val points=listOf(1000L,61000L,121000L,181000L,2000000L,2060000L).map{sample(s,it,38)}
        val segments=heartRateSegments(points);assertEquals(listOf(4,2),segments.map{it.size})
        assertEquals(1,heartRateSegments(points.take(1)).single().size)
        assertEquals(2,heartRateSegments(listOf(points[0],points[0].copy(key="distinct",recordId="another",bpm=110))).single().size)
    }
    @Test fun associatingAlreadyImportedGarminPlaceholderCreatesOneWorkoutAndRequiresBackup()=runBlocking {
        val local=WorkoutRepository(db);val localId=local.start();local.createExercise(localId,"Leg Press");local.saveSet(db.dao().workoutEntries(localId).single().id,"80","8")
        val repo=HealthImportRepository(db);val candidate=HealthCandidate(health("source",null),listOf(sample(health("source",null),2000,38)),true)
        val placeholder=repo.import(candidate)!!;assertEquals(2,db.dao().workouts().size)
        assertThrows(IllegalArgumentException::class.java){runBlocking{repo.import(candidate,localId)}}
        var backup:Backup?=null
        repo.import(candidate,localId,beforeRepair={backup=local.backup()})
        assertEquals(2,backup!!.workouts.size);assertNull(db.dao().workout(placeholder));assertEquals(1,db.dao().workouts().size);assertEquals(1,db.dao().sets().size)
        assertEquals(localId,db.healthDao().sessions().single().workoutId);assertEquals(38,db.healthDao().samples().single().bpm.toInt())
        repo.import(candidate);assertEquals(1,db.dao().workouts().size)
    }
    @Test fun realSameDaySessionsRemainSeparateAndPlansNeverEnterObservations() {
        val a=WorkoutDetail(Workout(1,1000,2000,"Legs",finished=true,sessionDate="2026-10-10"),emptyList())
        val b=WorkoutDetail(Workout(2,3000,4000,"Legs",finished=true,sessionDate="2026-10-10"),emptyList())
        assertEquals(listOf("local:2","local:1"),notebookSessions(AppData(workouts=listOf(a,b))).map{it.key})
    }
    private fun records()=listOf(
        TrainingObservation("a","2026-10-10",true,null,null,listOf(StrengthResult("e","Leg Press",80.0,8,LoadMode.UNKNOWN))),
        TrainingObservation("b","2026-10-10",true,null,null,listOf(StrengthResult("e","Leg Press",90.0,9,LoadMode.UNKNOWN))),
        TrainingObservation("active","2026-10-11",false,null,null,listOf(StrengthResult("e","Leg Press",100.0,10,LoadMode.EXTERNAL))))
    @Test fun recordedUnknownLoadsStillHaveWeightAndRepsWithoutUnsupportedEstimates() {
        assertEquals(listOf(StrengthMetric.WEIGHT,StrengthMetric.REPS),strengthMetrics(records(),"e"))
        assertEquals(listOf(80.0,90.0),strengthPoints(records(),"e",StrengthMetric.WEIGHT).map{it.value})
        assertTrue(strengthPoints(records(),"e",StrengthMetric.WEIGHT).all{it.start==null})
        assertEquals(listOf(8.0,9.0),strengthPoints(records(),"e",StrengthMetric.REPS).map{it.value})
        assertTrue(strengthPoints(records(),"e",StrengthMetric.WEIGHT,"2026-10-11").isEmpty())
    }
    @Test fun volumeAndBestSetRepsArePerSessionAndMissingLoadsNeverBecomeZeroObservations() {
        val r=listOf(TrainingObservation("a","2026-10-10",true,null,null,listOf(StrengthResult("e","Press",40.0,8,LoadMode.EXTERNAL),StrengthResult("e","Press",50.0,6,LoadMode.EXTERNAL))))
        assertEquals(620.0,strengthPoints(r,"e",StrengthMetric.VOLUME).single().value,0.0)
        assertEquals(8.0,strengthPoints(r,"e",StrengthMetric.REPS).single().value,0.0)
        val missing=r.map{it.copy(results=listOf(StrengthResult("e","Press",null,8,LoadMode.EXTERNAL)))}
        assertTrue(strengthPoints(missing,"e",StrengthMetric.WEIGHT).isEmpty());assertTrue(strengthPoints(missing,"e",StrengthMetric.VOLUME).isEmpty())
    }
    @Test fun zeroAndConstantSeriesHaveReadableBoundsAndMonthRangesUseCalendarDates() {
        assertTrue(plotBounds(listOf(0.0)).high>plotBounds(listOf(0.0)).low)
        assertEquals(0.0,plotBounds(listOf(0.0)).low,0.0)
        val bound=plotBounds(listOf(80.0,80.0));assertTrue(bound.low<80&&bound.high>80)
        assertEquals("2026-07-13",progressFrom("3M","2026-10-13"));assertNull(progressFrom("All","2026-10-13"))
    }
    @Test fun completingAnUnspecifiedWeightKeepsItUnspecifiedAndEditingDoesNotRestartRest()=runBlocking {
        val repo=WorkoutRepository(db);val id=repo.start();repo.createExercise(id,"Press");val entry=db.dao().workoutEntries(id).single()
        val resultId=repo.saveSet(entry.id,"","8",loadMode=LoadMode.EXTERNAL);val saved=db.dao().set(resultId)!!
        assertFalse(saved.weightSpecified)
        val clock=object:RestClock{override fun elapsed()=1000L;override fun bootCount()=1}
        val controller=RestTimerController(ApplicationProvider.getApplicationContext(),db,clock);controller.start(id,90)
        val timer=db.trainingDao().timer()!!;repo.saveSet(entry.id,"40","9",resultId);assertEquals(timer,db.trainingDao().timer())
        repo.finish(id);controller.sync();assertNull(db.trainingDao().timer())
    }
    @Test fun importedTimersHaveDurationButDateOnlyAndUnknownStartsDoNot()=runBlocking {
        val source=ImportedWorkout("timed","source","2026-10-10","2026-10-10T12:00:00+13:00","2026-10-10T13:00:00+13:00")
        val session=NotebookSession("import:timed",source.date,"","",true,imported=ImportedWorkoutDetail(source,emptyList()),exercises=emptyList())
        assertEquals(3600.0,session.duration!!,0.0)
        assertNull(session.copy(imported=ImportedWorkoutDetail(source.copy(recordedStart=null,recordedEnd=null),emptyList())).duration)
        val repo=WorkoutRepository(db);val id=db.dao().insertWorkout(Workout(startedAt=0,sessionDate="2026-10-10"))
        repo.createExercise(id,"Press");repo.saveSet(db.dao().workoutEntries(id).single().id,"40","8");repo.finish(id)
        assertNull(db.dao().workout(id)!!.durationSeconds)
    }

}
