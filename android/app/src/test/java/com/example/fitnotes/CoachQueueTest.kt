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
class CoachQueueTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:FitDatabase
    private val queue get()=TrainingProgramRepository(db)
    private val workouts get()=WorkoutRepository(db)
    private val plans get()=WorkoutPlanRepository(db)
    private val five="""{"title":"Five sessions","sessions":[
      {"title":"Easy Run","type":"running","distanceKm":5},
      {"title":"Upper Body","type":"strength","durationMinutes":45,"exercises":[{"name":"Synthetic Press","sets":3,"reps":"8-10"}]},
      {"title":"Lower Body","type":"strength","exercises":[{"name":"Synthetic Squat","sets":[{"repsMin":8,"repsMax":12,"weight":0,"unit":"kg"}]}]},
      {"title":"Intervals","type":"running","warmUp":"10 min easy","intervals":"6 x 400 m, 200 m easy","coolDown":"5 min easy"},
      {"title":"Full Body","type":"strength","exercises":[{"name":"Synthetic Press","sets":2,"reps":"8-12"}]}]}"""
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build()
        db.dao().insertExercise(Exercise(1,"Synthetic Press","synthetic press"));db.dao().insertExercise(Exercise(2,"Synthetic Squat","synthetic squat"));Unit
    }
    @After fun close(){ db.close() }
    private fun draft(text:String=five):ProgramImportDraft {
        val definition=(CoachResponse.parse(text) as CoachResponse.Plan).definition
        return PortableProgramParser.draft(definition,listOf(Exercise(1,"Synthetic Press","synthetic press"),Exercise(2,"Synthetic Squat","synthetic squat")),emptyList(),emptySet())
    }
    private suspend fun install(text:String=five)= (ProgramImportRepository(db).save(draft(text),flexible=true) as ProgramImportResult.Saved).id
    private suspend fun reject(block:suspend()->Unit) { try {block();fail("Expected validation error")} catch(_:IllegalArgumentException){} }
    private suspend fun perform(id:Long) {workouts.saveSet(db.dao().workoutEntries(id).first().id,"20","9");workouts.finish(id)}
    @Test fun fullMixedQueuePostponeSkipRestoreOutOfOrderCompleteAndBackup()=runBlocking {
        val id=install();var p=db.programDao().detail(id)!!
        assertEquals(5,p.queue.size);assertEquals("Easy Run",p.next!!.plan.plan.title)
        assertTrue(db.dao().workouts().isEmpty());assertTrue(db.dao().sets().isEmpty())
        val run=p.nextOccurrence!!.step.id;queue.schedule(id,run,"2026-11-01");queue.doLater(id,run)
        p=db.programDao().detail(id)!!;assertEquals("Upper Body",p.next!!.plan.plan.title)
        assertEquals("2026-11-01",p.queue.last().step.scheduledDate)
        val upper=p.nextOccurrence!!.step.id;queue.skipOccurrence(id,upper)
        assertEquals("skipped",db.programDao().step(upper)!!.action);assertTrue(db.dao().workouts().isEmpty())
        queue.restoreOccurrence(id,upper);queue.move(id,run,0)
        p=db.programDao().detail(id)!!;assertEquals(run,p.nextOccurrence!!.step.id)
        // Starting a non-first strength session leaves the earlier run pending.
        val w=queue.startOccurrence(id,upper);assertEquals(w,queue.startOccurrence(id,upper))
        assertEquals("pending",db.programDao().step(run)!!.action);assertEquals("in_progress",db.programDao().step(upper)!!.action)
        assertEquals(1,db.dao().workouts().size);assertTrue(db.dao().sets().isEmpty())
        perform(w);assertEquals("completed",db.programDao().step(upper)!!.action)
        assertEquals(w,db.programDao().step(upper)!!.workoutId);assertEquals(run,db.programDao().detail(id)!!.nextOccurrence!!.step.id)
        val before=workouts.backup();val decoded=BackupCodec.decode(BackupCodec.encode(before));assertEquals(before,decoded)
        workouts.restore(decoded);val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
        assertEquals(listOf(run,upper),db.programDao().detail(id)!!.queue.take(2).map{it.step.id})
        assertEquals(1,JSONObject(BackupCodec.analysis(after)).getJSONArray("sets").length())
        assertFalse(JSONObject(BackupCodec.analysis(after)).has("programs"))
    }
    @Test fun editOccurrenceDoesNotRewriteOtherOccurrencesActualsOrActiveSnapshot()=runBlocking {
        val id=install();val p=db.programDao().detail(id)!!;val upper=p.queue[1]
        val beforeOthers=p.queue.filter{it.step.id!=upper.step.id}.map{it.plan}
        val edit=queue.editableOccurrence(id,upper.step.id);plans.rename(edit,"Changed Upper")
        assertEquals(beforeOthers,db.programDao().detail(id)!!.queue.filter{it.step.id!=upper.step.id}.map{it.plan})
        val w=queue.startOccurrence(id,upper.step.id);val snapshot=db.planDao().runDetail(w)!!
        reject { queue.editableOccurrence(id,upper.step.id) }
        plans.rename(edit,"Future name") // Low-level recipe changes must never alter actual snapshots.
        assertEquals(snapshot,db.planDao().runDetail(w));assertTrue(db.dao().sets().isEmpty())
        perform(w);val actual=db.dao().sets();workouts.saveActivityDetails(w,"Edited actual","",null,null,null)
        assertEquals("completed",db.programDao().step(upper.step.id)!!.action);assertEquals(actual,db.dao().sets())
        workouts.deleteWorkout(w);assertNull(db.programDao().step(upper.step.id)!!.workoutId)
        assertEquals("completed",db.programDao().step(upper.step.id)!!.action)
        BackupCodec.validate(workouts.backup())
    }
    @Test fun discardReturnsPendingAndUnrelatedWorkoutCannotCompleteAnOccurrence()=runBlocking {
        val id=install();val upper=db.programDao().detail(id)!!.queue[1].step.id
        val w=queue.startOccurrence(id,upper);workouts.deleteWorkout(w)
        assertEquals("pending",db.programDao().step(upper)!!.action);assertNull(db.programDao().step(upper)!!.workoutId)
        val unrelated=workouts.start();val e=db.dao().insertEntry(WorkoutExercise(workoutId=unrelated,exerciseId=1,position=0));workouts.saveSet(e,"20","8");workouts.finish(unrelated)
        assertEquals("pending",db.programDao().step(upper)!!.action)
        val again=queue.startOccurrence(id,upper);perform(again);queue.completeLinked(again)
        assertEquals(1,db.programDao().detail(id)!!.steps.count{it.action=="completed"})
    }
    @Test fun runningTargetsNeverCreateMetricsAndLinkRequiresExplicitCompletedRun()=runBlocking {
        val id=install();val run=db.programDao().detail(id)!!.nextOccurrence!!.step.id
        reject {queue.startOccurrence(id,run)};assertTrue(db.dao().workouts().isEmpty())
        val actual=workouts.logPast("running","","2026-10-20",null,1800.0,"Actual run","",null,4.8,"km")
        val before=db.dao().workout(actual)!!;queue.linkRun(id,run,actual)
        assertEquals(before,db.dao().workout(actual));assertEquals("completed",db.programDao().step(run)!!.action)
        val otherRun=db.programDao().detail(id)!!.queue[3].step.id;reject{queue.linkRun(id,otherRun,actual)}
        val removed=workouts.logPast("running","","2026-10-21",null,1800.0,"Removed run","",null,5.0,"km")
        RunningRepository(db).delete("local:$removed");reject{queue.linkRun(id,otherRun,removed)}
        assertTrue(db.dao().sets().isEmpty());BackupCodec.validate(workouts.backup())
    }
    @Test fun lastSkipCanBeRestoredAndNoDatePassingCountsAsCompletion()=runBlocking {
        val id=install("""{"title":"One run","type":"running","date":"2000-01-01","durationMinutes":30}""")
        val step=db.programDao().detail(id)!!.nextOccurrence!!.step.id
        assertEquals("pending",db.programDao().step(step)!!.action)
        queue.skipOccurrence(id,step);assertEquals("completed",db.programDao().detail(id)!!.program.status)
        assertEquals(0,db.programDao().detail(id)!!.steps.count{it.action=="completed"})
        queue.restoreOccurrence(id,step);assertEquals("paused",db.programDao().detail(id)!!.program.status)
        queue.activate(id);assertEquals(step,db.programDao().detail(id)!!.nextOccurrence!!.step.id)
    }
    @Test fun unresolvedExerciseInFinalSessionRollsBackEarlierPlansAndCatalogueCreations()=runBlocking {
        val d=draft().let{it.copy(days=it.days.mapIndexed{i,day->when(i){
            1->day.copy(plan=day.plan.copy(exercises=day.plan.exercises.map{e->e.copy(sourceName="New Synthetic Press",exerciseId=null,createName="New Synthetic Press")}))
            4->day.copy(plan=day.plan.copy(exercises=day.plan.exercises.map{e->e.copy(exerciseId=null)}))
            else->day
        }})}
        val before=workouts.backup();reject{ProgramImportRepository(db).save(d,flexible=true)}
        val after=workouts.backup();assertEquals(before.copy(exportedAt=after.exportedAt),after)
    }
    @Test fun fileDatabaseReopenRetainsQueueAndActiveWorkout()=runBlocking {
        db.close();val name="synthetic-coach-restart";context.deleteDatabase(name)
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).build()
        db.dao().insertExercise(Exercise(1,"Synthetic Press","synthetic press"));db.dao().insertExercise(Exercise(2,"Synthetic Squat","synthetic squat"))
        val id=install();val run=db.programDao().detail(id)!!.nextOccurrence!!.step.id;queue.doLater(id,run)
        val upper=db.programDao().detail(id)!!.nextOccurrence!!.step.id;val w=queue.startOccurrence(id,upper)
        val order=db.programDao().detail(id)!!.queue.map{it.step};db.close()
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).build()
        assertEquals(order,db.programDao().detail(id)!!.queue.map{it.step});assertEquals(w,queue.startOccurrence(id,upper));assertEquals(1,db.dao().workouts().size)
        db.close();context.deleteDatabase(name);Unit
    }
    @Test fun schedulingAndPlanDateEditingUseTheSameOccurrenceDate()=runBlocking {
        val id=install();val upper=db.programDao().detail(id)!!.queue[1].step.id
        queue.schedule(id,upper,"2026-11-02");val edit=queue.editableOccurrence(id,upper)
        val plan=db.planDao().detail(edit)!!;assertEquals("2026-11-02",plan.plan.scheduledDate)
        plans.save(PlanPreview(plan.spec.copy(date="2026-11-03"),plan.ordered.map{ResolvedPlanExercise(it.prescription,it.exercise.id)}),editingId=edit)
        assertEquals("2026-11-03",db.programDao().step(upper)!!.scheduledDate)
        queue.schedule(id,upper,"");assertEquals("",db.planDao().detail(edit)!!.plan.scheduledDate)
        val order=db.programDao().detail(id)!!.queue.map{it.step.id};queue.doLater(id,upper)
        assertEquals(order.filter{it!=upper}+upper,db.programDao().detail(id)!!.queue.map{it.step.id})
    }
    @Test fun inactivityNeverCompletesAProgramAndEmptyDraftReturnsToPending()=runBlocking {
        val id=install();val upper=db.programDao().detail(id)!!.queue[1].step.id
        val w=queue.startOccurrence(id,upper);val began=db.dao().workout(w)!!.startedAt
        WorkoutLifecycleRepository(db){began+STALE_MAX_MILLIS+1}.reconcile()
        assertEquals("pending",db.programDao().step(upper)!!.action);assertNull(db.programDao().step(upper)!!.workoutId)
        assertTrue(db.dao().workout(w)!!.historicalDraft);assertTrue(db.programDao().sessions().isEmpty());BackupCodec.validate(workouts.backup())
        val real=queue.startOccurrence(id,upper);workouts.saveSet(db.dao().workoutEntries(real).single().id,"20","8")
        val action=db.dao().workout(real)!!.lastActionAt!!;WorkoutLifecycleRepository(db){action+STALE_MAX_MILLIS+1}.reconcile()
        assertEquals("in_progress",db.programDao().step(upper)!!.action)
        queue.complete(id,real);assertEquals("completed",db.programDao().step(upper)!!.action)
        BackupCodec.validate(workouts.backup())
    }
    @Test fun explicitlyFinishingThroughTheTimingEditorCompletesOnlyTheLinkedOccurrence()=runBlocking {
        val id=install();val upper=db.programDao().detail(id)!!.queue[1].step.id
        val workout=queue.startOccurrence(id,upper)
        workouts.saveSet(db.dao().workoutEntries(workout).single().id,"20","8")
        WorkoutLifecycleRepository(db).editTime(workout,WorkoutTimeInput(localWorkoutDate(db.dao().workout(workout)!!),minutes="20"))
        assertTrue(db.dao().workout(workout)!!.isComplete);assertEquals("completed",db.programDao().step(upper)!!.action)
        assertEquals("Easy Run",db.programDao().detail(id)!!.next!!.plan.plan.title)
        BackupCodec.validate(workouts.backup())
    }
    @Test fun emptyLegacyAttemptCanBeRetainedAsDraftAndBackedUpWithoutAdvancing()=runBlocking {
        val target=PlanPrescription("Synthetic Press",listOf(PlanSetTarget(8,10)))
        val plan=(plans.save(PlanPreview(PlanSpec("Legacy recipe","",30,"",listOf(target),planFingerprint("legacy")),listOf(ResolvedPlanExercise(target,1)))) as PlanSaveResult.Saved).id
        val id=queue.create("Legacy",2,listOf(plan));queue.activate(id)
        val workout=queue.start(id);val began=db.dao().workout(workout)!!.startedAt
        WorkoutLifecycleRepository(db){began+STALE_MAX_MILLIS+1}.reconcile()
        assertTrue(db.programDao().sessions().isEmpty());assertEquals(0,db.programDao().detail(id)!!.program.nextIndex)
        assertTrue(db.dao().workout(workout)!!.historicalDraft);BackupCodec.validate(workouts.backup())
    }
    @Test fun version16MigrationRetainsActualsAndLegacyCompletionSemantics()=runBlocking {
        db.close();val name="synthetic-coach-migration";context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/16.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {val e=entities.getJSONObject(i);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",table));val indices=e.optJSONArray("indices") ?: org.json.JSONArray();for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",table))}
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)");old.execSQL("INSERT INTO room_master_table VALUES (42,?)",arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES (1,'Synthetic Press','synthetic press')")
            old.execSQL("INSERT INTO workouts(id,startedAt,completedAt,title,notes) VALUES(1,1000,2000,'Keep actual','Keep private')")
            old.execSQL("INSERT INTO workout_exercises(id,workoutId,exerciseId,position) VALUES(1,1,1,0)")
            old.execSQL("INSERT INTO workout_sets(id,entryId,weightKg,reps,position) VALUES(1,1,25,8,0)")
            old.execSQL("INSERT INTO planned_workouts(id,title,scheduledDate,durationMinutes,notes,createdAt,sourceFingerprint) VALUES('recipe','Legacy','',30,'',1000,'${"a".repeat(64)}')")
            old.execSQL("INSERT INTO training_programs(id,title,weeks,status,nextIndex,notBefore,createdAt) VALUES('old','Old program',2,'active',1,'',1000)")
            old.execSQL("INSERT INTO program_days VALUES('day','old',0,'recipe')")
            old.execSQL("INSERT INTO program_sessions VALUES(1,'old',0)")
            old.execSQL("INSERT INTO program_steps VALUES('step','old',0,'completed',1,2000)")
            old.version=16
        }
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).addMigrations(COACH_MIGRATION_16_17).build()
        assertEquals(17,db.openHelper.readableDatabase.version);assertEquals("Keep private",db.dao().workout(1)!!.notes);assertEquals(25.0,db.dao().sets().single().weightKg,0.0)
        assertFalse(db.programDao().detail("old")!!.program.flexibleQueue);assertEquals("completed",db.programDao().steps().single().action)
        queue.enableQueue("old");val p=db.programDao().detail("old")!!
        assertEquals(2,p.queue.size);assertEquals("completed",p.queue[0].step.action);assertEquals("pending",p.queue[1].step.action);assertEquals(1,p.program.nextIndex)
        db.close();context.deleteDatabase(name);Unit
    }
}
