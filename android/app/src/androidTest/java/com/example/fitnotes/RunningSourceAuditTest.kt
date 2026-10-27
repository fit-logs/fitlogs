package com.example.fitnotes

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.*
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Instant

/** Explicit opt-in, read-only device audit. No database writes or imports. */
class RunningSourceAuditTest {
    @Test fun availableGarminRuns() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val output = JSONObject().put("permissions", JSONArray(granted.toList())).put("historyFeature", client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY))
        val runs = JSONArray(); var token: String? = null; val seen = mutableSetOf<String>()
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) do {
            val page = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.before(Instant.now()), pageSize = 500, pageToken = token))
            page.records.filter { it.metadata.dataOrigin.packageName == "com.garmin.android.apps.connectmobile" && it.exerciseType in setOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) }.forEach { r ->
                val distances = JSONArray(); var dt: String? = null
                if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) do {
                    val p = client.readRecords(ReadRecordsRequest(DistanceRecord::class, TimeRangeFilter.between(r.startTime, r.endTime), pageSize = 500, pageToken = dt))
                    p.records.forEach { d -> distances.put(JSONObject().put("id", d.metadata.id).put("origin", d.metadata.dataOrigin.packageName).put("start", d.startTime.toString()).put("end", d.endTime.toString()).put("metres", d.distance.inMeters)) }; dt = p.pageToken?.takeIf { it.isNotEmpty() }
                } while(dt != null)
                runs.put(JSONObject().put("id", r.metadata.id).put("clientId", r.metadata.clientRecordId).put("title", r.title).put("start", r.startTime.toString()).put("end", r.endTime.toString()).put("offset", r.startZoneOffset?.totalSeconds).put("type", r.exerciseType)
                    .put("segments", JSONArray().also { a -> r.segments.forEach { a.put(JSONObject().put("type",it.segmentType).put("start",it.startTime.toString()).put("end",it.endTime.toString())) } }).put("distanceRecords", distances))
            }; token = page.pageToken?.takeIf { it.isNotEmpty() }; require(token == null || seen.add(token!!))
        } while(token != null)
        output.put("runs", runs)
        File(context.filesDir, "running-source-audit.json").writeText(output.toString(2))
    }
}
