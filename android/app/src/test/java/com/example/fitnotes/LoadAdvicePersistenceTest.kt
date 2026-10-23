package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LoadAdvicePersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:FitDatabase
    private val workouts get()=WorkoutRepository(db)
    private val advice get()=LoadAdviceRepository(db){LocalDate.parse("2026-10-23")}
    private val config get()=LoadProfile("setup",1,"Gym A","Barbell","total",LoadMode.EXTERNAL)
    @Before fun setup()=runBlocking {db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build();db.dao().insertExercise(Exercise(1,"Bench Press","bench press"));advice.saveProfile(config);Unit}
    @After fun close(){db.close()}
    private suspend fun reject(block:suspend()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    private suspend fun history(day:Int,kg:Double=80.0,reps:Int=6,rir:Double?=2.0):Long {
        val start=Instant.parse("2026-10-${day}T05:00:00Z").toEpochMilli();val w=db.dao().insertWorkout(Workout(startedAt=start,completedAt=start+3600000,title="Actual $day",finished=true));val e=db.dao().insertEntry(WorkoutExercise(workoutId=w,exerciseId=1,position=0))
        repeat(3){i->val id=db.dao().insertSet(WorkoutSet(entryId=e,weightKg=kg,reps=reps,position=i,loadMode=LoadMode.EXTERNAL));db.loadDao().putContext(SetLoadContext(id,"Gym A","Barbell","total",rir))};return w
    }
    private suspend fun plan():String {
        val row=PlanPrescription("Bench Press",List(3){PlanSetTarget(8,10,loadMode=LoadMode.EXTERNAL,rir=2.0)})
        return (WorkoutPlanRepository(db).save(PlanPreview(PlanSpec("Imported-style targets","",30,"",listOf(row),"a".repeat(64)),listOf(ResolvedPlanExercise(row,1)))) as PlanSaveResult.Saved).id
    }
    @Test fun previewAndCacheDoNotWriteWhileExplicitUseChangesOnlyFuturePrescriptions()=runBlocking {
        history(20);history(22);val id=plan();val before=workouts.backup();val row=advice.plan(id).single();assertEquals("75",row.advice.loads.first().value);assertEquals(row,advice.plan(id).single())
        val afterPreview=workouts.backup();assertEquals(before.copy(exportedAt=afterPreview.exportedAt),afterPreview)
        advice.use(row);assertTrue(db.planDao().sets().all{it.weightValue=="75" && it.rir==2.0});assertEquals(before.sets,db.dao().sets());assertEquals(before.setLoadContexts,db.loadDao().contexts());assertEquals(2,db.dao().workouts().size)
    }
    @Test fun acceptedSessionLoadsLockAndActualOverridesDoNotRewriteAnyTarget()=runBlocking {
        history(20);history(22);val id=plan();val active=WorkoutPlanRepository(db).start(id);val entry=db.dao().workoutEntries(active).single()
        val row=advice.entry(entry.id);advice.use(row,true);val targets=db.planDao().runTargets();assertTrue(targets.all{it.weightValue=="75"});assertEquals(listOf(SessionLoadLock(entry.id)),db.loadDao().locks())
        reject{advice.use(advice.entry(entry.id))}
        val result=workouts.saveSet(entry.id,"72.5","8",targetId=targets.first().id,loadMode=LoadMode.EXTERNAL,actualRir="1",actualRpe="")
        assertEquals(targets,db.planDao().runTargets());assertEquals(72.5,db.dao().set(result)!!.weightKg,0.0);val actual=db.loadDao().context(result)!!;assertEquals(1.0,actual.rir!!,0.0);assertEquals("Gym A",actual.gym)
        advice.lock(entry.id,false);reject{advice.use(advice.entry(entry.id))};assertEquals(targets,db.planDao().runTargets());assertTrue(advice.entry(entry.id).hasActual)
    }
    @Test fun changedHistorySetupOrTargetsInvalidatesAnOldReviewBeforeAnyWrite()=runBlocking {
        history(20);history(22);val id=plan();val old=advice.plan(id).single();val set=db.dao().sets().last();db.dao().updateSet(set.copy(reps=5))
        reject{advice.use(old)};assertTrue(db.planDao().sets().all{it.weightValue==null})
        val fresh=advice.plan(id).single();advice.saveProfile(config.copy(increment="1.25"));reject{advice.use(fresh)};assertTrue(db.planDao().sets().all{it.weightValue==null})
    }
    @Test fun nextRelevantSessionRecomputesAfterRealResultsAndNeverUsesTheActiveWorkout()=runBlocking {
        history(20);history(22);val id=plan();val row=advice.plan(id).single();assertEquals("75",row.advice.loads.first().value)
        val w=WorkoutPlanRepository(db).start(id);val entry=db.dao().workoutEntries(w).single();repeat(3){workouts.saveSet(entry.id,"72.5","8",loadMode=LoadMode.EXTERNAL,actualRir="1")}
        assertEquals("75",advice.plan(id).single().advice.loads.first().value);workouts.finish(w)
        val next=advice.plan(id).single();assertEquals("70",next.advice.loads.first().value);assertTrue(next.advice.explanation.contains("72.5 kg × 8"));assertEquals(9,db.dao().sets().size)
    }
    @Test fun multipleGymAndPerHandProfilesStayDistinctAndDoNotRelabelPastSetContexts()=runBlocking {
        history(20);history(22);val id=plan();advice.saveProfile(config.copy(gym="Gym B",equipment="Dumbbells",basis="per_hand",unit="lb",increment="5"))
        assertEquals(2,db.loadDao().profiles().size);assertEquals(1,db.loadDao().profiles().count{it.selected});assertEquals("manual",advice.plan(id).single().advice.state)
        assertTrue(db.loadDao().contexts().all{it.gym=="Gym A" && it.basis=="total"});advice.selectProfile(db.loadDao().profiles().single{it.gym=="Gym A"}.id);assertEquals("75",advice.plan(id).single().advice.loads.first().value)
    }
    @Test fun manualTargetRetainsExplicitPoundsAndModeWithoutCreatingResults()=runBlocking {
        val id=plan();val initial=advice.plan(id).single();assertEquals("manual",initial.advice.state)
        advice.change(initial,"50 lb",LoadMode.EXTERNAL,false)
        assertTrue(db.planDao().sets().all{it.weightValue=="50" && it.weightUnit=="lb" && it.loadMode==LoadMode.EXTERNAL})
        assertTrue(db.dao().sets().isEmpty());val active=WorkoutPlanRepository(db).start(id)
        val entry=db.dao().workoutEntries(active).single();val review=advice.entry(entry.id)
        advice.change(review,"45 lb",LoadMode.EXTERNAL,true)
        assertTrue(db.planDao().runTargets().all{it.weightValue=="45" && it.weightUnit=="lb"})
        assertTrue(db.dao().sets().isEmpty());assertEquals(1,db.loadDao().locks().size)
    }
    @Test fun importedLegacyHistoryNeedsExplicitSetupReviewAndNeverInventsEffortOrProgression()=runBlocking {
        val csv="Date,Exercise,Category,Weight,Reps,Distance,Time,Comment\n"+listOf(20,22).flatMap{d->List(3){"2026-10-$d,Bench Press,Chest,80,6,,,"}}.joinToString("\n")+"\n"
        val document=HistoryImportReader.workoutCsv(csv,"synthetic.csv");val parsed=HistoryImportReader.plan(document,ImportOptions(weightUnit="kg"))
        HistoryImportRepository(db).importHistory(parsed);val id=plan();assertEquals("manual",advice.plan(id).single().advice.state)
        val before=db.importedDao().sets();advice.saveProfile(config.copy(reviewedLegacy=true));val row=advice.plan(id).single();assertEquals("Limited evidence",row.advice.confidence);assertNotEquals("progress",row.advice.state);assertTrue(row.advice.explanation.contains("legacy"));assertEquals(before,db.importedDao().sets());assertTrue(db.loadDao().contexts().isEmpty())
        reject{advice.saveProfile(config.copy(gym="Gym B",reviewedLegacy=true))}
    }
    @Test fun duplicateRecordsDoNotCopyReportedEffortAndEditsDoNotAssignTodaysSetupToLegacySets()=runBlocking {
        val w=workouts.start();workouts.addExercise(w,1);val entry=db.dao().workoutEntries(w).single();val id=workouts.saveSet(entry.id,"40","8",actualRir="2")
        val copy=workouts.duplicateSet(id);assertNull(db.loadDao().context(copy)!!.rir);assertEquals("Gym A",db.loadDao().context(copy)!!.gym)
        db.openHelper.writableDatabase.execSQL("DELETE FROM set_load_contexts WHERE setId=?",arrayOf(id));workouts.saveSet(entry.id,"42.5","8",setId=id,actualRir="1")
        assertEquals("",db.loadDao().context(id)!!.gym);assertEquals("unknown",db.loadDao().context(id)!!.basis)
        val before=workouts.backup();reject{workouts.saveSet(entry.id,"50","8",actualRir="2",actualRpe="10")};val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun fullBackupRetainsPrefsActualEffortAndLocksWhileAnalysisExcludesDraftContextsAndPreferences()=runBlocking {
        history(20);history(22);val id=plan();val w=WorkoutPlanRepository(db).start(id);val entry=db.dao().workoutEntries(w).single();advice.use(advice.entry(entry.id),true);workouts.saveSet(entry.id,"75","8",actualRir="2")
        val draft=WorkoutLifecycleRepository(db).beginPast(WorkoutTimeInput("2026-10-21"),"plan:$id");val de=db.dao().workoutEntries(draft).single();workouts.saveSet(de.id,"70","8",actualRir="1")
        val before=workouts.backup();assertEquals(13,before.version);val decoded=BackupCodec.decode(BackupCodec.encode(before));assertEquals(before,decoded);workouts.restore(decoded);val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
        val a=JSONObject(BackupCodec.analysis(after));assertEquals(6,a.getInt("version"));assertFalse(a.has("loadProfiles"));assertFalse(a.has("sessionLoadLocks"));assertEquals(7,a.getJSONArray("setLoadContexts").length());assertEquals(7,a.getJSONArray("sets").length())
        reject{BackupCodec.encode(before.copy(version=12))}
    }
    @Test fun mergingExerciseNamesPreservesContextAndBlocksConflictingSetupPreferences()=runBlocking {
        db.dao().insertExercise(Exercise(2,"Bench alias","bench alias"));advice.saveProfile(config.copy(exerciseId=2,unit="lb",increment="5"))
        val identity=ExerciseIdentityRepository(db);assertTrue(identity.preview(1,2).blockers.any{it.contains("Load setups")})
        advice.saveProfile(config.copy(reviewedLegacy=true));advice.saveProfile(config.copy(exerciseId=2,gym="Gym B",reviewedLegacy=true))
        assertTrue(identity.preview(1,2).blockers.any{it.contains("legacy history")})
        advice.saveProfile(config.copy(reviewedLegacy=false));advice.saveProfile(config.copy(exerciseId=2,gym="Gym B",reviewedLegacy=false))
        // Remove the extra setup through a backup round-trip to also exercise
        // preserving the remaining identical context during the merge.
        workouts.restore(workouts.backup().let{b->b.copy(loadProfiles=b.loadProfiles.filter{it.gym!="Gym B"})})
        advice.saveProfile(config.copy(exerciseId=2));val p=identity.preview(1,2);assertTrue(p.blockers.isEmpty());identity.merge(p);assertEquals(1,db.loadDao().profiles().size);assertEquals(1L,db.loadDao().profiles().single().exerciseId)
    }
    @Test fun versionFourteenMigrationPreservesTargetsAndNeverInventsHistoricalContextOrEffort()=runBlocking {
        db.close();val name="load-migration.db";context.deleteDatabase(name);val schema=JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/14.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{old->val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val e=entities.getJSONObject(i);old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",e.getString("tableName")));val idx=e.optJSONArray("indices")?:JSONArray();for(j in 0 until idx.length())old.execSQL(idx.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",e.getString("tableName")))};old.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT)");old.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")));old.execSQL("INSERT INTO exercises VALUES(1,'Keep movement','keep movement')");old.execSQL("INSERT INTO workouts(id,startedAt,completedAt,title,notes) VALUES(1,1000,2000,'Keep workout','Keep note')");old.execSQL("INSERT INTO workout_exercises(id,workoutId,exerciseId,position) VALUES(1,1,1,0)");old.execSQL("INSERT INTO workout_sets(id,entryId,weightKg,reps,position) VALUES(1,1,80,6,0)");old.version=14}
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).addMigrations(LOAD_ADVICE_MIGRATION_14_15).build();assertEquals("Keep note",db.dao().workout(1)!!.notes);assertEquals(80.0,db.dao().sets().single().weightKg,0.0);assertTrue(db.loadDao().contexts().isEmpty());assertTrue(db.loadDao().profiles().isEmpty());assertTrue(db.loadDao().locks().isEmpty());db.close();context.deleteDatabase(name);Unit
    }
}
