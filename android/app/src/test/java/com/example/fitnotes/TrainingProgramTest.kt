package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TrainingProgramTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:FitDatabase
    private val plans get()=WorkoutPlanRepository(db)
    private val programs get()=TrainingProgramRepository(db)
    private val workouts get()=WorkoutRepository(db)
    @Before fun setup()=runBlocking { db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build();db.dao().insertExercise(Exercise(1,"Synthetic press","synthetic press"));Unit }
    @After fun close(){db.close()}
    private suspend fun template(title:String="Synthetic upper"):String {
        val target=PlanPrescription("Synthetic press",listOf(PlanSetTarget(8,10,PlanWeight("25","kg"),60,LoadMode.EXTERNAL)),"Keep target note")
        return (plans.save(PlanPreview(PlanSpec(title,"",30,"Keep plan note",listOf(target),planFingerprint(title)),listOf(ResolvedPlanExercise(target,1))),allowDuplicate=true) as PlanSaveResult.Saved).id
    }
    private suspend fun reject(block:suspend()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    private suspend fun perform(id:Long){val entry=db.dao().workoutEntries(id).single();workouts.saveSet(entry.id,"22.5","8");workouts.finish(id)}
    @Test fun fourDayProgramOwnsOrderedCopiesAndNeverCreatesPerformedSetsDuringPlanning()=runBlocking {
        val a=template();val b=template("Synthetic lower");val id=programs.create("Four days",4,listOf(a,b,a,b));val p=db.programDao().detail(id)!!
        assertEquals(listOf("Synthetic upper","Synthetic lower","Synthetic upper","Synthetic lower"),p.ordered.map{it.plan.plan.title})
        assertEquals(4,p.days.size);assertEquals(16,p.total);assertEquals(0,p.program.nextIndex);assertEquals("paused",p.program.status)
        assertTrue(p.days.none{it.day.planId in setOf(a,b)});assertEquals(6,db.planDao().plans().size)
        assertTrue(db.dao().workouts().isEmpty());assertTrue(db.dao().sets().isEmpty());assertTrue(db.programDao().sessions().isEmpty())
        plans.rename(a,"Changed template");plans.delete(b)
        assertEquals("Synthetic upper",db.programDao().detail(id)!!.ordered.first().plan.plan.title)
        assertEquals("Synthetic lower",db.programDao().detail(id)!!.ordered[1].plan.plan.title)
    }
    @Test fun templateRenamePinArchiveDuplicateAndDeleteRetainActualHistoryAndSnapshots()=runBlocking {
        val id=template();val actual=plans.start(id);perform(actual);val before=db.dao().sets();val run=db.planDao().runDetail(actual)!!
        plans.rename(id,"Renamed template");plans.pin(id,true);plans.archive(id,true)
        assertTrue(db.planDao().detail(id)!!.plan.pinned);assertTrue(db.planDao().detail(id)!!.plan.archived);reject{plans.start(id)}
        plans.archive(id,false);val copy=plans.duplicate(id)
        assertFalse(db.planDao().detail(copy)!!.plan.pinned);assertFalse(db.planDao().detail(copy)!!.plan.archived);assertNull(db.planDao().detail(copy)!!.plan.startedWorkoutId)
        assertEquals(db.planDao().detail(id)!!.ordered.map{it.prescription},db.planDao().detail(copy)!!.ordered.map{it.prescription})
        plans.delete(id);assertEquals(before,db.dao().sets());assertEquals(run.exercises,db.planDao().runDetail(actual)!!.exercises);assertNull(db.planDao().run(actual)!!.planId)
        assertTrue(db.dao().workout(actual)!!.isComplete)
    }
    @Test fun pauseSwitchAndResumeKeepOnePrimaryProgramAndEveryCursor()=runBlocking {
        val t=template();val a=programs.create("A",2,listOf(t));val b=programs.create("B",2,listOf(t))
        programs.activate(a);programs.skip(a);programs.activate(b)
        assertEquals("paused",db.programDao().detail(a)!!.program.status);assertEquals(1,db.programDao().detail(a)!!.program.nextIndex)
        programs.pause(b);assertTrue(db.programDao().programs().none{it.status=="active"});programs.activate(a)
        assertEquals(1,db.programDao().programs().count{it.status=="active"});assertEquals(1,db.programDao().detail(a)!!.program.nextIndex)
        assertTrue(db.dao().sets().isEmpty());assertTrue(db.dao().workouts().isEmpty())
    }
    @Test fun onlyExplicitMatchingFinishedProgramSessionAdvancesExactlyOnce()=runBlocking {
        val t=template();val id=programs.create("Program",2,listOf(t));programs.activate(id)
        val w=programs.start(id);assertEquals(w,programs.start(id));assertTrue(db.dao().sets().isEmpty());reject{programs.complete(id,w)}
        perform(w);assertEquals(0,db.programDao().detail(id)!!.program.nextIndex)
        programs.complete(id,w);programs.complete(id,w)
        assertEquals(1,db.programDao().detail(id)!!.program.nextIndex);assertEquals(1,db.programDao().steps().size)
        val w2=programs.start(id);perform(w2);programs.complete(id,w2)
        assertEquals("completed",db.programDao().detail(id)!!.program.status);reject{programs.activate(id)};reject{programs.start(id)}
        assertEquals(2,db.dao().sets().size)
    }
    @Test fun earlierFinishedAttemptCannotAdvanceWhileTheSameProgramDayIsRunningAgain()=runBlocking {
        val t=template();val id=programs.create("Program",2,listOf(t));programs.activate(id)
        val first=programs.start(id);perform(first);val retry=programs.start(id)
        reject{programs.complete(id,first)};assertEquals(0,db.programDao().detail(id)!!.program.nextIndex)
        workouts.deleteWorkout(retry);programs.complete(id,first);assertEquals(1,db.programDao().detail(id)!!.program.nextIndex)
    }
    @Test fun adHocBackdatedAndCancelledSessionsDoNotAdvancePrograms()=runBlocking {
        val t=template();val id=programs.create("Program",2,listOf(t));programs.activate(id)
        val adhoc=plans.start(t);perform(adhoc);reject{programs.complete(id,adhoc)}
        val past=WorkoutLifecycleRepository(db).beginPast(WorkoutTimeInput("2026-10-16"),"plan:$t")
        workouts.saveSet(db.dao().workoutEntries(past).single().id,"25","8");WorkoutLifecycleRepository(db).savePast(past);reject{programs.complete(id,past)}
        val started=programs.start(id);reject{programs.skip(id)};workouts.deleteWorkout(started)
        assertEquals(0,db.programDao().detail(id)!!.program.nextIndex);assertTrue(db.programDao().steps().isEmpty());assertEquals(2,db.dao().sets().size)
    }
    @Test fun skipAndPostponeAreExplicitAndNeverPretendTrainingHappened()=runBlocking {
        val t=template();val id=programs.create("Program",1,listOf(t,t));programs.activate(id)
        programs.postpone(id,"2099-01-01");reject{programs.start(id)};assertEquals(0,db.programDao().detail(id)!!.program.nextIndex)
        programs.postpone(id,"");programs.skip(id)
        assertEquals(1,db.programDao().detail(id)!!.program.nextIndex);assertEquals("skipped",db.programDao().steps().single().action);assertNull(db.programDao().steps().single().workoutId)
        assertTrue(db.dao().workouts().isEmpty());assertTrue(db.dao().sets().isEmpty())
    }
    @Test fun backupRestoreRetainsProgramsTemplatesCursorsAndOldVersionsRemainReadable()=runBlocking {
        val t=template();val id=programs.create("Program",2,listOf(t,t));programs.activate(id);programs.skip(id)
        val w=programs.start(id);perform(w);programs.complete(id,w);plans.archive(t,true);plans.pin(t,true)
        val before=workouts.backup();val decoded=BackupCodec.decode(BackupCodec.encode(before));assertEquals(before,decoded)
        val legacyProgram=before.copy(version=11);assertEquals(legacyProgram,BackupCodec.decode(BackupCodec.encode(legacyProgram)))
        workouts.restore(decoded);val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
        assertFalse(JSONObject(BackupCodec.analysis(after)).has("programs"));assertEquals(1,JSONObject(BackupCodec.analysis(after)).getJSONArray("sets").length())
        reject{BackupCodec.encode(before.copy(version=10))}
        val old=Backup(version=10,exercises=emptyList(),workouts=emptyList(),entries=emptyList(),sets=emptyList());assertEquals(old,BackupCodec.decode(BackupCodec.encode(old)))
        workouts.deleteWorkout(w);val retained=BackupCodec.decode(BackupCodec.encode(workouts.backup()));assertNull(retained.programSteps.single{it.action=="completed"}.workoutId);assertEquals(2,retained.programs.single().nextIndex)
    }
    @Test fun corruptReferencesDuplicateActiveAndInventedCursorAreRejectedBeforeRestore()=runBlocking {
        val t=template();val id=programs.create("Program",2,listOf(t));programs.activate(id);val good=workouts.backup()
        reject{workouts.restore(good.copy(programs=good.programs.map{it.copy(nextIndex=1)}))}
        reject{workouts.restore(good.copy(programDays=good.programDays.map{it.copy(planId="missing")}))}
        reject{workouts.restore(good.copy(programs=good.programs+good.programs.single().copy(id="other"),programDays=good.programDays+good.programDays.single().copy(id="otherday",programId="other",planId="missing")))}
        val after=workouts.backup();assertEquals(good.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun creationFailureRollsBackAllProgramCopies()=runBlocking {
        val t=template();val before=workouts.backup();reject{programs.create("Program",4,listOf(t,"missing"))};val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun versionTwelveMigrationRetainsTemplateTargetsAndAllActualRows()=runBlocking {
        db.close();val name="program-migration.db";context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/12.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){val e=entities.getJSONObject(i);old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",e.getString("tableName")));val indices=e.optJSONArray("indices")?:org.json.JSONArray();for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",e.getString("tableName")))}
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)");old.execSQL("INSERT INTO room_master_table VALUES (42, ?)",arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES (1,'Synthetic press','synthetic press')")
            old.execSQL("INSERT INTO workouts(id,startedAt,completedAt,title,notes) VALUES (1,1000,2000,'Keep actual','Keep note')")
            old.execSQL("INSERT INTO workout_exercises(id,workoutId,exerciseId,position) VALUES (1,1,1,0)")
            old.execSQL("INSERT INTO workout_sets(id,entryId,weightKg,reps,position) VALUES (1,1,25,8,0)")
            old.execSQL("INSERT INTO planned_workouts(id,title,scheduledDate,durationMinutes,notes,createdAt,sourceFingerprint) VALUES ('old','Keep template','',30,'Keep target',1000,'${"a".repeat(64)}')")
            old.version=12
        }
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).addMigrations(PROGRAM_MIGRATION_12_13, PROGRAM_IMPORT_MIGRATION_13_14).build()
        assertEquals("Keep note",db.dao().workout(1)!!.notes);assertEquals(25.0,db.dao().sets().single().weightKg,0.0);assertFalse(db.planDao().detail("old")!!.plan.archived);assertFalse(db.planDao().detail("old")!!.plan.pinned);assertTrue(db.programDao().programs().isEmpty())
        db.close();context.deleteDatabase(name);Unit
    }
}
