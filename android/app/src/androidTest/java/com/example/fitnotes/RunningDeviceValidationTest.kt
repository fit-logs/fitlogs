package com.example.fitnotes

import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Explicit opt-in integration check. Imports/refreshes ONE real Garmin run, never clears/restores/deletes data. */
class RunningDeviceValidationTest {
    @Test fun importOneRealRunTwiceWithoutDuplicatingOrChangingLocalTraining()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        assumeTrue("Real run validation must be explicitly selected",args.getString("validateRealRun")=="true" && i.targetContext.packageName=="com.example.fitnotes")
        val context=i.targetContext;val db=FitDatabase.open(context)
        try {
            val repository=WorkoutRepository(db);val before=repository.backup()
            File(context.filesDir,"repair-backups/running-validation-before.json").apply { parentFile!!.mkdirs();if(!exists())writeText(BackupCodec.encode(before)) }
            val reader=HealthConnectReader(context);val access=reader.access()
            val sources=collectHealthPages { reader.runningPage(null,Instant.now(),it) }
            val output=JSONObject().put("accessibleRuns",sources.size).put("historicalPermission",access.history)
                .put("earliest",sources.minOfOrNull{runLocalDate(it.session.start,it.session.startOffsetSeconds)})
            val selected=sources.maxByOrNull{it.session.start}
            if(selected!=null) {
                val enriched=reader.enrich(selected);val runs=RunningRepository(db)
                val sourceRun=healthRun(HealthDetail(enriched.session,enriched.samples),"health:${enriched.session.key}","")
                output.put("sourceDistanceMetres",sourceRun.distanceMetres ?: JSONObject.NULL).put("sourceElapsedSeconds",sourceRun.times.elapsed ?: JSONObject.NULL)
                    .put("sourceTimerSeconds",sourceRun.times.timer ?: JSONObject.NULL).put("sourceMovingSeconds",sourceRun.times.moving ?: JSONObject.NULL)
                    .put("sourcePace",paceText(sourceRun.pace) ?: JSONObject.NULL).put("sourceTimeBasis",sourceRun.preferredTime?.first?.name)
                    .put("source",HealthBackupJson.encode(enriched.session))
                val known=db.healthDao().session(selected.session.key)
                val matches=potentialRunMatches(healthRun(HealthDetail(enriched.session,enriched.samples),"health:${enriched.session.key}",""),runningRecords(runningSnapshot(db)))
                output.put("potentialMatches",matches.size).put("matchSummaries",org.json.JSONArray(matches.map { r -> JSONObject().put("date",r.date).put("source",r.sourceLabel).put("distanceMetres",r.distanceMetres ?: JSONObject.NULL).put("duration",r.preferredTime?.second ?: JSONObject.NULL) }))
                if(known==null && matches.isNotEmpty()) {
                    repeat(2) { assertThrows(IllegalArgumentException::class.java) { runBlocking { runs.import(enriched) } } }
                    val unchanged=repository.backup();assertEquals(before.workouts,unchanged.workouts);assertEquals(before.importedSets,unchanged.importedSets);assertEquals(before.healthSessions,unchanged.healthSessions)
                    output.put("result","Needs user review; both attempts preserved existing activities and created no duplicates")
                }
                else {
                    val first=runs.import(enriched);val afterFirst=repository.backup();val second=runs.import(enriched);val afterSecond=repository.backup()
                    assertEquals(afterFirst.workouts,afterSecond.workouts);assertEquals(afterFirst.importedSets,afterSecond.importedSets)
                    assertEquals(before.entries,afterSecond.entries);assertEquals(before.sets,afterSecond.sets);assertEquals(before.bodyMeasurements,afterSecond.bodyMeasurements)
                    assertEquals(before.plannedWorkouts,afterSecond.plannedWorkouts);assertEquals(before.plannedSets,afterSecond.plannedSets)
                    before.workouts.forEach { old->assertEquals(old,afterSecond.workouts.single{it.id==old.id}) }
                    val run=runningRecords(runningSnapshot(db)).single{r->r.sources.any{it.session.key==selected.session.key}}
                    assertEquals(1,runningRecords(runningSnapshot(db)).count{r->r.sources.any{it.session.key==selected.session.key}})
                    output.put("first",first).put("second",second).put("distanceMetres",run.distanceMetres).put("elapsedSeconds",run.times.elapsed)
                        .put("timerSeconds",run.times.timer).put("movingSeconds",run.times.moving).put("pace",paceText(run.pace)).put("timeBasis",run.preferredTime?.first?.name)
                        .put("source",HealthBackupJson.encode(enriched.session)).put("localWorkoutsBefore",before.workouts.size).put("localWorkoutsAfter",afterSecond.workouts.size)
                }
            }
            File(context.filesDir,"running-device-validation.json").writeText(output.toString(2))
        } finally { db.close() }
    }
}
