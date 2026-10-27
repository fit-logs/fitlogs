package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.notebookSessions
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RunningTest {
    private lateinit var db: FitDatabase
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FitDatabase::class.java).build() }
    @After fun close() { db.close() }
    private val start=Instant.parse("2026-10-05T05:29:28Z").toEpochMilli()
    private fun source(id:String="one", origin:String=GARMIN_PACKAGE, at:Long=start, metres:Double?=5000.0, seconds:Long=1800)=HealthSession(healthKey(origin,id),null,id,origin,"Garmin",id,1,start,start,at,at+seconds*1000,46800,46800,"running",56,"Evening run","Source note",metres,origin,"","",runData=RunSourceData(distanceVerified=metres!=null).json())
    private fun candidate(s:HealthSession=source())=HealthCandidate(s,emptyList(),true,true)
    private fun legacy(id:String="run", metres:Double?=5.0, seconds:Double?=1800.0)=ImportedSet(id,"mixed",id,1,"Running (Outdoor)","Cardio",null,null,metres,"km",seconds,"Run note","{}")
    private suspend fun seedMixed(): ImportedSet {
        db.importedDao().insertImport(HistoryImport("fitnotes","Original FitNotes CSV","fixture",1000,"kg",0,"","{}"))
        db.importedDao().insertWorkouts(listOf(ImportedWorkout("mixed","fitnotes","2026-10-05",notes="Workout note")))
        val run=legacy(); db.importedDao().insertSets(listOf(run,run.copy(id="strength",sourceRecordId="strength",sourceOrder=0,exercise="Squat",weightKg=80.0,reps=8,distance=null,distanceUnit=null,durationSeconds=null)))
        return run
    }
    @Test fun paceConversionsRoundOnlyAtDisplayAndCarryMinutes() {
        assertEquals(360.0,runPace(5000.0,1800.0)!!,1e-12);assertEquals("6:00 /km",paceText(runPace(5000.0,1800.0)))
        assertEquals(579.36384,runPace(5000.0,1800.0,true)!!,1e-10);assertEquals("9:39 /mi",paceText(runPace(5000.0,1800.0,true),true))
        assertEquals("6:00 /km",paceText(359.6));assertEquals("5:59 /km",paceText(359.4));assertEquals(1609.344,distanceInMetres(1.0,"mi")!!,0.0)
        listOf(null,0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY).forEach { assertNull(runPace(it,1800.0));assertNull(runPace(5000.0,it)) }
    }
    @Test fun durationMeaningIsNotReclassifiedAndPreferenceIsExplicit() {
        val times=RunTimes(2000.0,1800.0,1700.0,1600.0)
        assertEquals(RunTimeBasis.TIMER,times.preferred()!!.first)
        assertEquals(RunTimeBasis.MOVING,times.copy(timer=null).preferred()!!.first)
        assertEquals(RunTimeBasis.ELAPSED,times.copy(timer=null,moving=null).preferred()!!.first)
        assertEquals(RunTimeBasis.RECORDED,times.copy(timer=null,moving=null,elapsed=null).preferred()!!.first)
        assertNull(runTimerSeconds(1000,2000,emptyList()));assertEquals(0.8,runTimerSeconds(1000,2000,listOf(RunPause(1500,1700)))!!,0.0)
        assertNull(runTimerSeconds(1000,2000,listOf(RunPause(900,1700))));assertNull(runTimerSeconds(1000,2000,listOf(RunPause(1200,1600),RunPause(1500,1700))))
    }
    @Test fun distanceRequiresCompleteSameOriginNonoverlappingCoverageAndUniqueSession() {
        val s=source(); fun interval(id:String,a:Long,b:Long,d:Double=2500.0,origin:String=GARMIN_PACKAGE)=RunDistanceRecord(id,origin,s.start+a,s.start+b,d)
        val total=interval("total",0,1800000,5000.0)
        assertEquals(5000.0,selectRunDistance(s,listOf(total),listOf(s)).metres!!,0.0)
        val components=listOf(interval("a",0,900000),interval("b",900000,1800000))
        assertEquals(5000.0,selectRunDistance(s,components,listOf(s)).metres!!,0.0)
        assertNull(selectRunDistance(s,components+total,listOf(s)).metres)
        assertNull(selectRunDistance(s,listOf(interval("daily",-1000,1800000)),listOf(s)).metres)
        assertNull(selectRunDistance(s,listOf(interval("partial",0,900000)),listOf(s)).metres)
        assertNull(selectRunDistance(s,listOf(total.copy(origin="com.strava")),listOf(s)).metres)
        assertEquals(5000.0,selectRunDistance(s,listOf(total,total.copy(id="strava",origin="com.strava")),listOf(s)).metres!!,0.0)
        assertNull(selectRunDistance(s,listOf(total),listOf(s,s.copy(key="other",start=s.start+1000))).metres)
        assertEquals(5000.0,selectRunDistance(s,listOf(total,total),listOf(s)).metres!!,0.0)
    }
    @Test fun datesKeepSourceOffsetAndCalendarWeeksAcrossDst() {
        val boundary=Instant.parse("2026-09-27T11:30:00Z").toEpochMilli()
        assertEquals("2026-09-28",runLocalDate(boundary,46800));assertEquals("2026-09-27",runLocalDate(boundary,43200))
        val runs=listOf(RunRecord("a","a","Run","2026-09-27",distanceMetres=5000.0),RunRecord("b","b","Run","2026-09-28",distanceMetres=3000.0),RunRecord("c","c","Run","2026-09-28"))
        val stats=runningStats(runs+ runs.first(),"2026-09-21","2026-10-01")
        assertEquals(listOf("2026-09-21","2026-09-28"),stats.weeks.map{it.monday});assertEquals(listOf(5000.0,3000.0),stats.weeks.map{it.metres})
        assertEquals(3,stats.runs.size);assertEquals(1,stats.missingDistances);assertTrue(stats.weeks.last().current)
    }
    @Test fun mixedFitnotesRunIsImmediatelyUsableAndRemainsDateOnly()=runBlocking {
        seedMixed();val snapshot=runningSnapshot(db);val run=runningRecords(snapshot).single()
        assertEquals("2026-10-05",run.date);assertNull(run.start);assertNull(run.end);assertEquals(5000.0,run.distanceMetres!!,0.0)
        assertEquals(RunTimeBasis.RECORDED,run.preferredTime!!.first);assertEquals("6:00 /km",paceText(run.pace));assertEquals(2,notebookSessions(snapshot).single().count)
        listOf("Running (Outdoor)","Running (Treadmill)","2Km Run","Jog","Trail run").forEach{assertTrue(it,isRunningName(it))}
        listOf("Walking","Hiking","Cycling","Runaway plank").forEach{assertFalse(isRunningName(it))}
    }
    @Test fun repeatedSourceUpdatesWithoutOverwritingEditsAndDeletedRunDoesNotReturn()=runBlocking {
        val repo=RunningRepository(db);assertEquals("added",repo.import(candidate()));val run=runningRecords(runningSnapshot(db)).single()
        assertEquals("present",repo.import(candidate()));repo.save(run.key,"My run","My note",5.2,1850.0)
        assertEquals("updated",repo.import(candidate(source().copy(modifiedAt=start+1,distanceMeters=5100.0))))
        val edited=runningRecords(runningSnapshot(db)).single();assertEquals(5200.0,edited.distanceMetres!!,0.0);assertEquals("My note",edited.notes);assertEquals("My run",edited.title)
        assertEquals(5100.0,edited.sources.single().session.distanceMeters!!,0.0)
        repo.delete(run.key);assertEquals("ignored",repo.import(candidate()));assertTrue(runningRecords(runningSnapshot(db)).isEmpty());assertTrue(notebookSessions(runningSnapshot(db)).isEmpty())
        assertEquals(1,db.dao().workouts().size);assertEquals(1,db.healthDao().sessions().size)
    }
    @Test fun reviewMatchesNeverAutoMergeAndSameDayLegitimateRunsSurvive()=runBlocking {
        seedMixed();val repo=RunningRepository(db)
        assertThrows(IllegalArgumentException::class.java){runBlocking{repo.import(candidate())}}
        assertEquals("linked",repo.import(candidate(),"fitnotes:run"));assertEquals("present",repo.import(candidate()))
        assertEquals(1,runningRecords(runningSnapshot(db)).size);assertEquals(2,db.importedDao().sets().size)
        assertEquals("added",repo.import(candidate(source("evening",at=start+6*3600000)),"separate"))
        assertEquals(2,runningRecords(runningSnapshot(db)).size);assertEquals(10000.0,runningStats(runningRecords(runningSnapshot(db)),null,"2026-10-10").totalMetres,0.0)
    }
    @Test fun linkingAlreadyImportedRunsRetainsEveryRecordAndCountsCanonicalOnly()=runBlocking {
        seedMixed();val repo=RunningRepository(db);repo.import(candidate(),"separate")
        val source=runningRecords(runningSnapshot(db)).first{it.key.startsWith("local:")}
        repo.save(source.key,"Edited Garmin run","Keep me",5.1,1820.0)
        val backup=WorkoutRepository(db).backup();repo.merge(source.key,"fitnotes:run")
        val merged=runningRecords(runningSnapshot(db)).single();assertEquals(5000.0,merged.distanceMetres!!,0.0);assertTrue(merged.notes.contains("Keep me"));assertEquals(1,merged.sources.size)
        assertEquals(1,db.dao().workouts().size);assertEquals(2,db.importedDao().sets().size)
        val current=WorkoutRepository(db).backup();assertEquals(current,BackupCodec.decode(BackupCodec.encode(current)))
        WorkoutRepository(db).restore(current);assertEquals(1,runningRecords(runningSnapshot(db)).size)
        WorkoutRepository(db).restore(backup);assertEquals(2,runningRecords(runningSnapshot(db)).size)
    }
    @Test fun pagingEmptyEndTokensRepeatedIdsAndCancellationAreHandled()=runBlocking {
        val c=candidate();var pageCalls=0
        val all=readRunningHistory({token->pageCalls++;if(token==null)HealthPage(listOf(c),"next")else HealthPage(listOf(c,candidate(source("two"))),"")},{it},{})
        assertEquals(2,pageCalls);assertEquals(2,all.size)
        assertTrue(readRunningHistory({HealthPage(emptyList(),null)},{it},{}).isEmpty())
        var reached=false
        val job=launch { readRunningHistory({token->if(token!=null)awaitCancellation();HealthPage(listOf(c),"next")},{it},{reached=true}) }
        yield();assertTrue(reached);job.cancelAndJoin();assertTrue(job.isCancelled)
        assertEquals(1,collectHealthPages { HealthPage(listOf(c),"") }.size)
    }
    @Test fun interruptedWritesAndRetryAreIdempotent()=runBlocking {
        val repo=RunningRepository(db);repo.import(candidate());assertEquals(1,db.dao().workouts().size)
        // A second foreground pass repeats already committed source IDs before continuing.
        val outcomes=listOf(candidate(),candidate(source("later",at=start+7*3600000))).map { repo.import(it,"separate") }
        assertEquals(listOf("present","added"),outcomes);assertEquals(2,db.dao().workouts().size)
    }
    @Test fun editingOnlyNotesRetainsSourceDurationAndDistanceBasis()=runBlocking {
        val repo=RunningRepository(db);repo.import(candidate());val run=runningRecords(runningSnapshot(db)).single()
        repo.save(run.key,"Morning","My note",run.distanceMetres!!/1000,null)
        val edited=runningRecords(runningSnapshot(db)).single();assertEquals(1800.0,edited.times.elapsed!!,0.0);assertEquals(RunTimeBasis.ELAPSED,edited.preferredTime!!.first);assertEquals("My note\nSource note",edited.notes)
    }

    @Test fun providerRecordReplacementKeepsClientIdentityAndDeletedAliasesStayDeleted()=runBlocking {
        val repo=RunningRepository(db);repo.import(candidate())
        val replacement=source("replacement").copy(clientRecordId="one",clientRecordVersion=2,modifiedAt=start+1000)
        assertEquals("updated",repo.import(candidate(replacement)))
        assertEquals(1,runningRecords(runningSnapshot(db)).size);assertEquals(1,db.dao().workouts().size);assertEquals(2,db.healthDao().sessions().size)
        val key=runningRecords(runningSnapshot(db)).single().key;repo.delete(key)
        assertEquals("ignored",repo.import(candidate(source("third").copy(clientRecordId="one"))))
        assertTrue(runningRecords(runningSnapshot(db)).isEmpty())
    }
    @Test fun deletedParentKeepsRestorableSourceTombstones()=runBlocking {
        val repo=RunningRepository(db);repo.import(candidate())
        db.dao().deleteWorkout(db.dao().workouts().single().id);retainDeletedRunDecisions(db)
        val backup=WorkoutRepository(db).backup();assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
        assertEquals("ignored",repo.import(candidate()));assertEquals(0,db.dao().workouts().size)
    }

    @Test fun explicitSingleRunTimingIsRetainedButMixedWorkoutTimingIsNotAssignedToItsComponent()=runBlocking {
        seedMixed();val old=db.importedDao().workout("mixed")!!.workout
        db.importedDao().updateWorkout(old.copy(recordedStart="2026-10-05T18:00:00+13:00",recordedEnd="2026-10-05T18:35:00+13:00"))
        val mixed=runningRecords(runningSnapshot(db)).single();assertNull(mixed.start);assertEquals(RunTimeBasis.RECORDED,mixed.preferredTime!!.first)
        db.importedDao().deleteSet("strength")
        val single=runningRecords(runningSnapshot(db)).single();assertNotNull(single.start);assertEquals(46800,single.offsetSeconds)
        assertEquals(2100.0,single.times.elapsed!!,0.0);assertEquals(1800.0,single.times.recorded!!,0.0)
        assertEquals(RunTimeBasis.ELAPSED,single.preferredTime!!.first)
    }

    @Test fun partialDateOnlySummariesStillRequireReviewRatherThanSilentlyDoubleCounting() {
        val incoming=RunRecord("new","new","Run","2026-10-05",distanceMetres=5000.0,times=RunTimes(elapsed=1800.0))
        val distanceOnly=RunRecord("distance","old","Run","2026-10-05",distanceMetres=5000.0)
        val timeOnly=RunRecord("time","old","Jog","2026-10-05",times=RunTimes(recorded=1800.0))
        val different=RunRecord("different","old","Run","2026-10-05",distanceMetres=1000.0,times=RunTimes(recorded=600.0))
        val missing=RunRecord("missing","old","Run","2026-10-05")
        assertEquals(setOf("distance","time"),potentialRunMatches(incoming,listOf(distanceOnly,timeOnly,different,missing)).map{it.key}.toSet())
    }

    @Test fun distinctSameProviderIdentitiesRemainSeparateEvenWhenTimesAreClose()=runBlocking {
        val repo=RunningRepository(db);repo.import(candidate())
        assertEquals("added",repo.import(candidate(source("second",at=start+60000))))
        assertEquals(2,runningRecords(runningSnapshot(db)).size)
        assertEquals(2,db.healthDao().sessions().size)
    }

    @Test fun editingImportedNotesDoesNotConvertOriginalDistanceUnits()=runBlocking {
        seedMixed();val original=db.importedDao().sets().single{it.id=="run"}.copy(distance=1.0,distanceUnit="mi")
        db.importedDao().updateSet(original)
        val run=runningRecords(runningSnapshot(db)).single();RunningRepository(db).save(run.key,run.title,"Updated note",run.distanceMetres!!/1000,run.times.recorded)
        val saved=db.importedDao().sets().single{it.id=="run"}
        assertEquals(1.0,saved.distance!!,0.0);assertEquals("mi",saved.distanceUnit);assertEquals(original.rawValues,saved.rawValues);assertEquals(original.sourceRecordId,saved.sourceRecordId)
    }

    @Test fun loggerDeletionAndUndoKeepRunAssociationRestorable()=runBlocking {
        seedMixed();val repo=RunningRepository(db);repo.import(candidate(),"fitnotes:run")
        val history=HistoryImportRepository(db);val deleted=history.deleteRecord("run")
        assertTrue(runningRecords(runningSnapshot(db)).isEmpty())
        val backup=WorkoutRepository(db).backup();assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
        assertEquals("present",repo.import(candidate()));assertTrue(runningRecords(runningSnapshot(db)).isEmpty())
        history.restoreDeletedRecord(deleted)
        val restored=runningRecords(runningSnapshot(db)).single();assertEquals("fitnotes:run",restored.key);assertEquals(1,restored.sources.size)
        assertEquals(2,db.importedDao().sets().size)
    }
    @Test fun establishedNativeDuplicateRepairRemapsRunSourceAssociations()=runBlocking {
        db.importedDao().insertImports(listOf(HistoryImport("a","Native","a",1000,"kg",0,"","{}"),HistoryImport("b","Native","b",1000,"kg",0,"","{}")))
        db.importedDao().insertWorkouts(listOf(ImportedWorkout("a","a","2026-10-05"),ImportedWorkout("b","b","2026-10-05")))
        val raw=org.json.JSONObject().put("_id",17).put("date","2026-10-05").put("exercise_id",1).put("distance",5).put("duration_seconds",1800).toString()
        val first=legacy("a-run").copy(workoutId="a",sourceRecordId="17",rawValues=raw)
        db.importedDao().insertSets(listOf(first,first.copy(id="b-run",workoutId="b")))
        RunningRepository(db).import(candidate(),"fitnotes:b-run")
        val a=db.importedDao().workout("a")!!;val b=db.importedDao().workout("b")!!
        HistoryImportRepository(db).merge(historyMergePreview(a,b))
        val run=runningRecords(runningSnapshot(db)).single();assertEquals("fitnotes:a-run",run.key);assertEquals(1,run.sources.size)
        assertEquals("fitnotes:a-run",db.runDao().all().single().canonicalKey)
        val backup=WorkoutRepository(db).backup();assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
    }
}
