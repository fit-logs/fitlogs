package com.example.fitnotes

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.HealthConnectReader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant

/** Explicit opt-in read-only source audit. Never restores, clears, imports or edits training. */
class HealthSourceAuditTest {
    @Test fun rereadSourceRecordsWithoutChangingTraining()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        assumeTrue("Source audit needs explicit bounds",args.getString("sourceAudit")=="true")
        val start=args.getString("sourceStart")!!.toLong();val end=args.getString("sourceEnd")!!.toLong()
        val client=HealthConnectClient.getOrCreate(i.targetContext)
        val granted=client.permissionController.getGrantedPermissions()
        assertTrue("Existing heart-rate permission required",HealthConnectReader.heartPermission in granted)
        assertTrue("Existing exercise permission required",HealthConnectReader.exercisePermission in granted)
        val output=JSONObject();val sessions=JSONArray();val low=JSONArray();val duplicates=mutableMapOf<Long,MutableSet<Long>>()
        var token:String?=null
        do {
            val page=client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,TimeRangeFilter.between(Instant.ofEpochMilli(start-86400000),Instant.ofEpochMilli(end+86400000)),pageSize=500,pageToken=token))
            page.records.filter{it.startTime.toEpochMilli()==start && it.endTime.toEpochMilli()==end}.forEach{record->sessions.put(JSONObject().put("id",record.metadata.id).put("origin",record.metadata.dataOrigin.packageName).put("start",record.startTime.toEpochMilli()).put("end",record.endTime.toEpochMilli()).put("startOffset",record.startZoneOffset?.totalSeconds))}
            token=page.pageToken
        }while(token!=null)
        token=null
        var sampleCount=0
        do {
            val page=client.readRecords(ReadRecordsRequest(HeartRateRecord::class,TimeRangeFilter.between(Instant.ofEpochMilli(start-30*86400000L),Instant.ofEpochMilli(end+86400000)),pageSize=500,pageToken=token))
            page.records.forEach{record->record.samples.filter{it.time.toEpochMilli() in start until end}.forEach{sample->
                sampleCount++;duplicates.getOrPut(sample.time.toEpochMilli()){mutableSetOf()}.add(sample.beatsPerMinute)
                if(sample.beatsPerMinute<=45)low.put(JSONObject().put("id",record.metadata.id).put("origin",record.metadata.dataOrigin.packageName).put("time",sample.time.toEpochMilli()).put("bpm",sample.beatsPerMinute).put("recordStart",record.startTime.toEpochMilli()).put("recordEnd",record.endTime.toEpochMilli()).put("method",record.metadata.recordingMethod))
            }};token=page.pageToken
        }while(token!=null)
        output.put("sessions",sessions).put("lowSamples",low).put("sampleCountInBounds",sampleCount).put("conflictingTimestamps",duplicates.count{it.value.size>1})
        File(i.targetContext.filesDir,"health-source-audit.json").writeText(output.toString(2))
        assertTrue("Source exercise record no longer available",sessions.length()>0)
    }
}
