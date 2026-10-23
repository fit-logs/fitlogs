package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TrainingWorkflowTest {
    private lateinit var context:Context;private lateinit var db:FitDatabase
    private val name="synthetic-adoption.db"
    private val repo get()=WorkoutRepository(db);private val training get()=TrainingRepository(db);private val plans get()=WorkoutPlanRepository(db)
    private class Clock(var time:Long=10000,var boot:Int=3):RestClock{override fun elapsed()=time;override fun bootCount()=boot}
    @Before fun setup()=runBlocking { context=ApplicationProvider.getApplicationContext();context.deleteDatabase(name);open();db.dao().insertExercise(Exercise(1,"Synthetic press","synthetic press"));db.dao().insertExercise(Exercise(2,"Synthetic press duplicate","synthetic press duplicate"));Unit }
    private fun open(){db=Room.databaseBuilder(context,FitDatabase::class.java,name).build()}
    @After fun cleanup(){db.close();context.deleteDatabase(name)}
    private suspend fun plan(two:Boolean=false,group:Boolean=false):String {
        val targets=listOf(PlanSetTarget(8,10,PlanWeight("40.5","kg"),90),PlanSetTarget(6,8,PlanWeight("45.25","kg"),120))
        val exercises=(if(two)listOf(1L,2L) else listOf(1L)).map{ResolvedPlanExercise(PlanPrescription(db.dao().exercise(it)!!.name,targets,"Guidance $it"),it,supersetGroup=if(group)"A" else "")}
        val spec=PlanSpec("Synthetic plan","",0,"",exercises.map{it.prescription},"a".repeat(64))
        return (plans.save(PlanPreview(spec,exercises)) as PlanSaveResult.Saved).id
    }
    private suspend fun reject(block:suspend()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun outOfOrderCompletionSkipUndoExtraAndRepeatedSaveKeepTargetsSeparate()=runBlocking {
        val id=plan();val workout=plans.start(id);val rows=db.planDao().runTargets()
        assertTrue(training.complete(rows[1].id,"46","7","Actual",LoadMode.EXTERNAL));assertFalse(training.complete(rows[1].id,"999","99","Duplicate",LoadMode.EXTERNAL))
        assertEquals(rows[0].id,nextPlannedSet(db.planDao().runDetail(workout)!!,db.dao().sets())!!.targetId)
        training.skip(rows[0].id,true);assertNull(nextPlannedSet(db.planDao().runDetail(workout)!!,db.dao().sets()))
        assertEquals(1,db.dao().sets().size);assertNotNull(db.dao().sets().single().completedAt)
        training.undo(rows[1].id);assertTrue(db.dao().sets().isEmpty());assertEquals(rows[1].id,nextPlannedSet(db.planDao().runDetail(workout)!!,db.dao().sets())!!.targetId)
        training.skip(rows[0].id,false);repo.saveSet(rows[0].entryId,"50","8")
        assertNull(db.dao().sets().single().targetId);assertEquals(2,db.planDao().detail(id)!!.ordered.single().sets.size)
    }
    @Test fun editingCompletionNeverCreatesAnotherResultAndApplyingWeightNeverChangesCompletedTargets()=runBlocking {
        val workout=plans.start(plan());val rows=db.planDao().runTargets()
        training.complete(rows[0].id,"41","9","",LoadMode.EXTERNAL);val actual=db.dao().sets().single();val completed=actual.completedAt
        repo.saveSet(actual.entryId,"42","10",actual.id,loadMode=LoadMode.EXTERNAL)
        training.applyWeight(actual.entryId,PlanWeight("47.5","kg"))
        assertEquals(completed,db.dao().sets().single().completedAt);assertEquals("40.5",db.trainingDao().target(rows[0].id)!!.weightValue)
        assertEquals("47.5",db.trainingDao().target(rows[1].id)!!.weightValue)
        repo.deleteSet(actual.id);assertEquals(rows[0].id,nextPlannedSet(db.planDao().runDetail(workout)!!,db.dao().sets())!!.targetId)
    }
    @Test fun reorderingPreservesTargetIdentityAndItsResultLink()=runBlocking {
        plans.start(plan());val rows=db.planDao().runTargets();training.complete(rows[0].id,"40.5","10","",LoadMode.EXTERNAL)
        training.moveTarget(rows[0].id,1)
        assertEquals(rows[0].id,db.dao().sets().single().targetId);assertEquals(1,db.trainingDao().target(rows[0].id)!!.position)
        assertEquals(rows[1].id,db.planDao().runTargets().minBy{it.position}.id)
    }
    @Test fun supersetSequenceAlternatesAndRestOnlyStartsAfterTheRound()=runBlocking {
        val workout=plans.start(plan(true,true));val run=db.planDao().runDetail(workout)!!
        val sequence=plannedSequence(run);assertEquals(listOf(run.exercises[0].entry.id,run.exercises[1].entry.id,run.exercises[0].entry.id,run.exercises[1].entry.id),sequence.map{it.entryId})
        training.complete(sequence[0].id,"40.5","10","",LoadMode.EXTERNAL)
        assertFalse(shouldRestAfter(run,sequence[0],db.dao().sets()))
        training.complete(sequence[1].id,"40.5","10","",LoadMode.EXTERNAL);assertTrue(shouldRestAfter(run,sequence[1],db.dao().sets()))
    }
    @Test fun restPersistsReopenPausesResumesExtendsIgnoresStaleActionsAndStopsAtFinish()=runBlocking {
        val workout=plans.start(plan());val target=db.planDao().runTargets().first();training.complete(target.id,"40.5","10","",LoadMode.EXTERNAL)
        val clock=Clock();var controller=RestTimerController(context,db,clock);controller.afterCompletion(target.id)
        val token=db.trainingDao().timer()!!.token;clock.time+=5000;controller.action(token,"pause")
        assertEquals(85000,db.trainingDao().timer()!!.remainingMillis)
        db.close();open();controller=RestTimerController(context,db,clock);clock.time+=100000
        controller.action("old-token","skip");assertEquals(token,db.trainingDao().timer()!!.token)
        controller.action(token,"resume");controller.action(token,"extend");assertEquals(115000,db.trainingDao().timer()!!.remaining(clock))
        repo.finish(workout);controller.sync();assertNull(db.trainingDao().timer())
    }
    @Test fun restSkipsEditsAndTheLastRequiredSetDoNotStartANewTimer()=runBlocking {
        plans.start(plan());val targets=db.planDao().runTargets();val clock=Clock();val controller=RestTimerController(context,db,clock)
        training.skip(targets[0].id,true);assertNull(db.trainingDao().timer())
        training.complete(targets[1].id,"45.25","8","",LoadMode.EXTERNAL);controller.afterCompletion(targets[1].id);assertNull(db.trainingDao().timer())
        val actual=db.dao().sets().single();repo.saveSet(actual.entryId,"47","8",actual.id);assertNull(db.trainingDao().timer())
        controller.start(db.dao().active()!!.id,90,targets[1].id);training.undo(targets[1].id);controller.sync();assertNull(db.trainingDao().timer())
    }
    @Test fun backupRoundTripRetainsLinksModesAliasesMappingsProgressionAndSupersets()=runBlocking {
        val id=plan(true,true);val workout=plans.start(id);val target=db.planDao().runTargets().first();training.complete(target.id,"40.5","10","",LoadMode.EXTERNAL)
        ExerciseIdentityRepository(db).addAlias(1,"Press shorthand");training.progression(ExerciseProgression(1,true,"2.5","kg"));training.preferences(TrainingPreferences(automaticRest=false,favouriteKeys="[\"synthetic press\"]"))
        val backup=repo.backup();val decoded=BackupCodec.decode(BackupCodec.encode(backup));assertEquals(backup,decoded)
        repo.restore(decoded);assertEquals(backup.sets,db.dao().sets());assertEquals(backup.runTargets,db.planDao().runTargets())
        assertEquals(backup.exerciseAliases,db.trainingDao().aliases());assertEquals(backup.exerciseProgression,db.trainingDao().progression());assertEquals(backup.trainingPreferences,db.trainingDao().preferences())
        assertEquals("A",db.dao().workoutEntries(workout).first().supersetGroup);assertEquals("A",db.planDao().detail(id)!!.ordered.first().target.supersetGroup)
    }
    @Test fun unknownActualLoadIsNotNumericZeroForDisplayStatisticsOrBackup()=runBlocking {
        val workout=plans.start(plan());repo.saveSet(db.dao().workoutEntries(workout).single().id,"","9",loadMode=LoadMode.UNKNOWN);repo.finish(workout)
        val actual=db.dao().sets().single();assertFalse(actual.weightSpecified);assertTrue(localSetText(actual).contains("unspecified"))
        val backup=repo.backup();repo.restore(BackupCodec.decode(BackupCodec.encode(backup)));assertFalse(db.dao().sets().single().weightSpecified)
        val record=WorkoutDetail(db.dao().workout(workout)!!,listOf(EntryDetail(db.dao().entries().single(),db.dao().exercise(1)!!,db.dao().sets())))
        val stats=StrengthStatistics.calculate(observations(AppData(exercises=db.dao().exercises(),workouts=listOf(record),loading=false)))
        assertEquals(0.0,stats.exercises.single().externalVolume,0.0);assertNull(stats.exercises.single().estimatedOneRepMax)
    }
    @Test fun aliasMatchingIsPersistedAmbiguousAndDoesNotGuessMovementVariants()=runBlocking {
        val identity=ExerciseIdentityRepository(db);identity.addAlias(1,"DB press");identity.addAlias(2,"DB press")
        assertEquals(ExerciseMatchKind.AMBIGUOUS,matchPlanExercise(" db  press ",db.dao().exercises(),aliases=aliasMap(db.trainingDao().aliases())).kind)
        identity.addAlias(1,"Known press");db.close();open()
        assertEquals(ExerciseMatchKind.ALIAS,matchPlanExercise("Known press",db.dao().exercises(),aliases=aliasMap(db.trainingDao().aliases())).kind)
        assertEquals(ExerciseMatchKind.UNKNOWN,matchPlanExercise("Incline synthetic press",db.dao().exercises(),aliases=aliasMap(db.trainingDao().aliases())).kind)
    }
    @Test fun mergeCombinesBothParentsWithoutLosingSetsTargetsNotesOrSourcePlanIdentity()=runBlocking {
        val plan=plan(true);val workout=plans.start(plan);val rows=db.planDao().runTargets();training.complete(rows[0].id,"40.5","10","First",LoadMode.EXTERNAL);training.complete(rows[2].id,"41","9","Second",LoadMode.EXTERNAL)
        val ids=db.dao().sets().map{it.id};val targetIds=rows.map{it.id};val identity=ExerciseIdentityRepository(db)
        val preview=identity.preview(1,2);assertEquals(1,preview.combinedWorkouts);assertEquals(1,preview.combinedPlans);assertTrue(preview.blockers.isEmpty())
        identity.merge(preview)
        assertEquals(ids.toSet(),db.dao().sets().map{it.id}.toSet());assertEquals(targetIds.toSet(),db.planDao().runTargets().map{it.id}.toSet())
        assertEquals(1,db.dao().workoutEntries(workout).size);assertEquals(1,db.planDao().detail(plan)!!.exercises.size)
        assertEquals(4,db.planDao().sets().size);assertTrue(db.dao().entries().single().planNote.contains("Guidance 2"))
        assertEquals(1,db.trainingDao().redirects().single().exerciseId);BackupCodec.validate(repo.backup())
    }
    @Test fun mergeRejectsChangedDataAndConflictingLoadModes()=runBlocking {
        val id=plans.start(plan(true));val identity=ExerciseIdentityRepository(db);val preview=identity.preview(1,2)
        val target=db.planDao().runTargets().first();training.skip(target.id,true);reject{identity.merge(preview)}
        val row=db.planDao().runTargets().first{it.entryId!=target.entryId};db.trainingDao().updateTarget(row.copy(loadMode=LoadMode.BODYWEIGHT))
        assertTrue(identity.preview(1,2).blockers.isNotEmpty());assertEquals(2,db.dao().workoutEntries(id).size)
    }
    @Test fun restoringIntoAFreshDatabaseNeverReusesMergedExerciseIds()=runBlocking {
        val identity=ExerciseIdentityRepository(db);identity.merge(identity.preview(1,2))
        val backup=repo.backup()
        val fresh=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build()
        try {
            val restored=WorkoutRepository(fresh);restored.restore(backup)
            val workout=restored.start();restored.createExercise(workout,"Synthetic new movement")
            val created=fresh.dao().exercises().single{it.name=="Synthetic new movement"}
            assertTrue(created.id>2);assertEquals(listOf(ExerciseRedirect(2,1)),fresh.trainingDao().redirects())
            val next=restored.backup();assertEquals(next,BackupCodec.decode(BackupCodec.encode(next)))
        } finally { fresh.close() }
    }
    @Test fun deletedSourcePlanLeavesRunTargetsAndCompletedResultsIntact()=runBlocking {
        val plan=plan();val workout=plans.start(plan);val target=db.planDao().runTargets().first();training.complete(target.id,"40.5","10","",LoadMode.EXTERNAL)
        plans.delete(plan);assertNull(db.planDao().run(workout)!!.planId);assertEquals(2,db.planDao().runTargets().size);assertEquals(target.id,db.dao().sets().single().targetId)
        BackupCodec.validate(repo.backup())
    }
    @Test fun originalCategoryMappingsRemainExplicitAndSurviveBackupWithoutRewritingRows()=runBlocking {
        val source=HistoryImport("source","Synthetic source","synthetic.csv",1000,"kg",0,"","{}")
        val workout=ImportedWorkout("original","source","2026-10-12",null,null,"")
        val rows=listOf(ImportedSet("one","original","one",0,"Old press","Chest",40.0,8,null,null,null,"Original note","{}"),
            ImportedSet("two","original","two",1,"Old press","Cardio",null,null,1.0,"km",60.0,"Cardio note","{}"))
        repo.restore(Backup(exercises=db.dao().exercises(),workouts=emptyList(),entries=emptyList(),sets=emptyList(),imports=listOf(source),importedWorkouts=listOf(workout),importedSets=rows))
        val identity=ExerciseIdentityRepository(db);identity.addAlias(1,"Old press")
        fun data()=AppData(exercises=runBlocking{db.dao().exercises()},loading=false,imported=ImportedData(imports=listOf(source),workouts=listOf(ImportedWorkoutDetail(workout,rows))),exerciseAliases=runBlocking{db.trainingDao().aliases()},exerciseMappings=runBlocking{db.trainingDao().mappings()})
        assertNull(ExerciseIdentityIndex(data()).source("source","Old press","Chest"))
        identity.mapSource(ExerciseSourceMapping("source","Old press","Chest",1));identity.mapSource(ExerciseSourceMapping("source","Old press","Cardio",2))
        assertEquals(1L,ExerciseIdentityIndex(data()).source("source","Old press","Chest"));assertEquals(2L,ExerciseIdentityIndex(data()).source("source","Old press","Cardio"))
        assertEquals(setOf(2L),db.incompatibleStrengthExercises());assertEquals(rows,db.importedDao().sets())
        val backup=repo.backup();repo.restore(BackupCodec.decode(BackupCodec.encode(backup)))
        assertEquals(backup.exerciseMappings.toSet(),db.trainingDao().mappings().toSet());assertEquals(rows,db.importedDao().sets())
    }
    @Test fun explicitProgressionChangesOnlyReusableTargetsAndRejectsAnAlreadyChangedPlan()=runBlocking {
        val id=plan();val workout=plans.start(id);val targets=db.planDao().runTargets()
        targets.forEach{training.complete(it.id,targetWeightKg(it.target),it.repsMax.toString(),"",it.loadMode)}
        repo.finish(workout);training.progression(ExerciseProgression(1,true,"2.5","kg"))
        val actual=db.dao().sets();plans.applyProgression(id)
        assertEquals(listOf("43","47.75"),db.planDao().detail(id)!!.ordered.single().sets.sortedBy{it.position}.map{it.weightValue})
        assertEquals(targets,db.planDao().runTargets());assertEquals(actual,db.dao().sets())
        assertTrue(db.planDao().detail(id)!!.ordered.single().target.note.contains("all 2"))
        reject{plans.applyProgression(id)};assertEquals(actual,db.dao().sets())
    }
    @Test fun portableTimerBackupRestoresPausedWithAFreshTokenAndNoInventedTraining()=runBlocking {
        val workout=repo.start();val clock=Clock();RestTimerController(context,db,clock).start(workout,90)
        clock.time+=5000;val backup=WorkoutRepository(db,clock).backup()
        assertEquals(85000L,backup.restTimer!!.remainingMillis);assertFalse(backup.restTimer.running)
        val old=backup.restTimer.token;repo.restore(BackupCodec.decode(BackupCodec.encode(backup)))
        val restored=db.trainingDao().timer()!!;assertFalse(restored.running);assertEquals(85000L,restored.remainingMillis);assertNotEquals(old,restored.token)
        RestTimerController(context,db,clock).action(old,"resume");assertFalse(db.trainingDao().timer()!!.running)
        assertTrue(db.dao().sets().isEmpty());assertEquals(1,db.dao().workouts().size)
    }
    @Test fun versionEightMigrationPreservesActualRecordsWithoutInventedLinksTimestampsOrModes()=runBlocking {
        db.close();context.deleteDatabase(name)
        val schema=JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/8.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old->
            val entities=schema.getJSONArray("entities")
            for(index in 0 until entities.length()) { val e=entities.getJSONObject(index);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",table));val indices=e.optJSONArray("indices")?:org.json.JSONArray();for(i in 0 until indices.length())old.execSQL(indices.getJSONObject(i).getString("createSql").replace("$"+"{TABLE_NAME}",table)) }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)");old.execSQL("INSERT INTO room_master_table VALUES (42, ?)",arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO exercises VALUES (1,'Synthetic press','synthetic press')")
            old.execSQL("INSERT INTO workouts(id,startedAt,completedAt,title,notes) VALUES (5,1000,2000,'Keep title','Keep note')")
            old.execSQL("INSERT INTO workout_exercises VALUES (6,5,1,0,'Keep guidance')")
            old.execSQL("INSERT INTO workout_sets(id,entryId,weightKg,reps,position,comment) VALUES (7,6,42.5,8,0,'Keep actual')")
            old.version=8
        }
        db=Room.databaseBuilder(context,FitDatabase::class.java,name).addMigrations(TRAINING_MIGRATION_8_9, ACTIVITY_SUMMARY_MIGRATION_9_10, ACTIVITY_PROVIDER_MIGRATION_10_11, WORKOUT_LIFECYCLE_MIGRATION_11_12, PROGRAM_MIGRATION_12_13, PROGRAM_IMPORT_MIGRATION_13_14).build()
        val actual=db.dao().sets().single();assertEquals(7,actual.id);assertEquals(42.5,actual.weightKg,0.0);assertEquals("Keep actual",actual.comment)
        assertNull(actual.targetId);assertNull(actual.completedAt);assertEquals(LoadMode.UNKNOWN,actual.loadMode);assertTrue(actual.weightSpecified)
        assertEquals("Keep note",db.dao().workout(5)!!.notes)
    }
}
