package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic private-conversion fixture, never a copied third-party routine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProgramWorkflowTest {
    @Test fun convertedProgramConfirmationActualLoggingAndNextSessionProgressionStaySeparate()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,FitDatabase::class.java).build()
        try {
            val today=LocalDate.now()
            val workouts=WorkoutRepository(db)
            val programs=TrainingProgramRepository(db)
            val advice=LoadAdviceRepository(db){today}
            db.dao().insertExercise(Exercise(1,"Bench Press","bench press"))
            db.dao().insertExercise(Exercise(2,"Incline Bench Press","incline bench press"))
            db.trainingDao().putAlias(ExerciseAlias(1,1,"Press","press"))
            db.trainingDao().putAlias(ExerciseAlias(2,2,"Press","press"))
            advice.saveProfile(LoadProfile("bench-setup",1,"Gym A","Barbell","total",LoadMode.EXTERNAL))
            // Actual saved history on distinct dates, with explicitly known setup
            // and effort. No target or catalog load supplies these observations.
            for(offset in listOf(10L,8L)) {
                val start=today.minusDays(offset).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val w=db.dao().insertWorkout(Workout(startedAt=start,completedAt=start+3600000,title="Existing actual bench",finished=true))
                val e=db.dao().insertEntry(WorkoutExercise(workoutId=w,exerciseId=1,position=0))
                repeat(3){position->val id=db.dao().insertSet(WorkoutSet(entryId=e,weightKg=80.0,reps=6,position=position,loadMode=LoadMode.EXTERNAL));db.loadDao().putContext(SetLoadContext(id,"Gym A","Barbell","total",rir=2.0))}
            }
            val original=db.dao().sets()
            val source=PortableProgramParser.parse("""{
              "schemaVersion":1,"id":"private-converted-fixture","programVersion":"1",
              "name":"My converted routine","weeks":3,
              "source":{"title":"User-provided conversion (synthetic fixture)","url":"https://example.org/owner-routine"},
              "days":[
                {"id":"a","name":"Bench A","exercises":[{"name":"Press","sets":3,"reps":"8-10","rir":2}]},
                {"id":"b","name":"Bench B","exercises":[{"name":"Press","sets":3,"reps":"6-8","rir":2}]}
              ]} """)
            val unresolved=PortableProgramParser.draft(source,db.dao().exercises(),db.trainingDao().aliases(),emptySet())
            assertTrue(unresolved.days.flatMap{it.plan.exercises}.all{it.exerciseId==null})
            try {ProgramImportRepository(db).save(unresolved);fail("Ambiguous exercise must be confirmed")}catch(_:IllegalArgumentException){}
            assertTrue(db.programDao().programs().isEmpty());assertEquals(original,db.dao().sets())
            // This is the explicit exercise choice made in the import preview.
            val resolved=unresolved.copy(days=unresolved.days.map{day->day.copy(plan=day.plan.copy(exercises=day.plan.exercises.map{it.copy(exerciseId=1)}))})
            val programId=(ProgramImportRepository(db).save(resolved) as ProgramImportResult.Saved).id
            programs.activate(programId)
            assertEquals(1,db.programDao().programs().size);assertEquals(2,db.programDao().days().size)
            assertEquals(original,db.dao().sets());assertTrue(db.planDao().sets().all{it.weightValue==null})

            suspend fun followNext(date:LocalDate):Long {
                val next=db.programDao().detail(programId)!!.next!!
                val review=advice.plan(next.day.planId).single()
                assertEquals("75",review.advice.loads.first().value)
                val actualBefore=db.dao().sets()
                advice.use(review)
                assertEquals(actualBefore,db.dao().sets())
                val w=programs.start(programId);val entry=db.dao().workoutEntries(w).single()
                assertTrue(db.dao().entrySets(entry.id).isEmpty())
                advice.use(advice.entry(entry.id),true)
                val targets=db.planDao().runTargets().filter{it.entryId==entry.id}
                assertTrue(targets.all{it.weightValue=="75" && it.rir==2.0})
                val index=db.programDao().detail(programId)!!.program.nextIndex
                targets.forEach{assertTrue(TrainingRepository(db).complete(it.id,"75","10","Performed",LoadMode.EXTERNAL,actualRir="3"))}
                assertFalse(TrainingRepository(db).complete(targets.first().id,"75","10","retry",LoadMode.EXTERNAL,actualRir="3"))
                assertEquals(index,db.programDao().detail(programId)!!.program.nextIndex)
                assertEquals(targets,db.planDao().runTargets().filter{it.entryId==entry.id})
                workouts.finish(w)
                // Exercise the supported retrospective timing correction to keep
                // fixture training dates distinct without waiting several days.
                WorkoutLifecycleRepository(db).editTime(w,WorkoutTimeInput(date.toString(),"12:00",date.toString(),"13:00"))
                programs.complete(programId,w)
                assertEquals(index+1,db.programDao().detail(programId)!!.program.nextIndex)
                return w
            }
            followNext(today.minusDays(5));followNext(today.minusDays(2))
            val actual=db.dao().sets();val snapshots=db.planDao().runTargets()
            assertEquals(12,actual.size);assertEquals(original,actual.filter{it.id in original.map{row->row.id}})
            val nextPlan=db.programDao().detail(programId)!!.next!!.day.planId
            val progressed=advice.plan(nextPlan).single()
            assertEquals("progress",progressed.advice.state);assertEquals("77.5",progressed.advice.loads.first().value)
            assertTrue(progressed.advice.explanation.contains("reported headroom"))
            advice.use(progressed)
            assertEquals(actual,db.dao().sets());assertEquals(snapshots,db.planDao().runTargets())
            assertEquals(2,db.loadDao().locks().size)

            val saved=workouts.backup();workouts.restore(BackupCodec.decode(BackupCodec.encode(saved)))
            val restored=workouts.backup();assertEquals(saved.copy(exportedAt=restored.exportedAt),restored)
            assertEquals("77.5",advice.plan(nextPlan).single().advice.loads.first().value)
            val requests=mutableListOf<String>()
            ProgramCatalogRepository(context,CatalogTransport{path,_->requests+=path;File("../../web/public/catalog",path).readText()}).refresh()
            assertEquals(listOf("manifest.json"),requests)
            val afterRefresh=workouts.backup();assertEquals(restored.copy(exportedAt=afterRefresh.exportedAt),afterRefresh)
        } finally {db.close()}
    }
}
