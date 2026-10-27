package com.example.fitnotes.data

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

const val GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile"
const val HEALTH_ACCESS_CONTEXT = "Only data available in Health Connect can be read. Default history access begins 30 days before your first permission grant; extended history is optional and background access is not requested. Granting access does not make Garmin backfill older runs. An empty query does not prove no activity occurred or that Garmin has finished syncing."
fun isGarminOrigin(packageName: String) = packageName == GARMIN_PACKAGE
data class HealthAccess(val exercise: Boolean, val heartRate: Boolean, val distance: Boolean, val history: Boolean = false, val historyAvailable: Boolean = false) {
    val message get() = if (!exercise) "Exercise-session permission is missing or revoked. Grant it in Health Connect to preview activities."
        else buildList {
            if (!heartRate) add("Heart-rate permission is missing; activities can still be imported.")
            if (!distance) add("Distance permission is missing; activities can still be imported.")
        }.joinToString("\n").ifBlank { "Read permissions granted. Data can be read while FitNotes is open." }
}
@RequiresApi(28)
class HealthConnectReader(private val context: Context, private val clientOverride: HealthConnectClient? = null) {
    companion object {
        val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
        val distancePermission = HealthPermission.getReadPermission(DistanceRecord::class)
        val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        val runningPermissions = setOf(exercisePermission, distancePermission)
        val permissions = setOf(exercisePermission, heartPermission, distancePermission)
    }
    private var heartCache: List<HeartRateRecord>? = null
    private var distanceCache: List<DistanceRecord>? = null
    private val windowEnd = Instant.now()
    private val windowStart = windowEnd.minusSeconds(30 * 86400L)
    private fun client() = clientOverride ?: HealthConnectClient.getOrCreate(context)
    suspend fun access(): HealthAccess {
        val granted = client().permissionController.getGrantedPermissions()
        return HealthAccess(exercisePermission in granted, heartPermission in granted, distancePermission in granted,
            historyPermission in granted, client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE)
    }
    private suspend fun <T : Record> read(type: KClass<T>, start: Instant, end: Instant): List<T> = collectHealthPages { token ->
        val page = client().readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 500, pageToken = token))
        HealthPage(page.records, page.pageToken)
    }
    suspend fun sessions(start: Instant, end: Instant): List<HealthCandidate> {
        require(access().exercise) { HealthAccess(false, false, false).message }
        return read(ExerciseSessionRecord::class, start, end).distinctBy { healthKey(it.metadata.dataOrigin.packageName, it.metadata.id) }.map(::candidate).sortedByDescending { it.session.start }
    }
    private fun candidate(record: ExerciseSessionRecord): HealthCandidate {
        val origin = record.metadata.dataOrigin.packageName
        val source = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(origin, 0)).toString() }.getOrDefault(origin)
        val m = record.metadata
        val pauses = record.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
            .map { RunPause(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
        val facts = RunSourceData(timerSeconds = runTimerSeconds(record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), pauses),
            subtype = if (record.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) "Treadmill run" else "Running")
        return HealthCandidate(HealthSession(healthKey(origin, m.id), null, m.id, origin, source,
            m.clientRecordId, m.clientRecordVersion, m.lastModifiedTime.toEpochMilli(), System.currentTimeMillis(),
            record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), record.startZoneOffset?.totalSeconds,
            record.endZoneOffset?.totalSeconds, activityType(record.exerciseType), record.exerciseType,
            record.title.orEmpty(), record.notes.orEmpty(), null, "", "Not read yet", "Not read yet",
            device = listOfNotNull(m.device?.manufacturer, m.device?.model).joinToString(" "), runData = facts.json()))
    }
    /** An unbounded read is platform-filtered at the first-grant history boundary when extended access is absent. */
    suspend fun runningPage(start: Instant?, end: Instant, token: String?): HealthPage<HealthCandidate> {
        require(access().exercise) { "Allow exercise access to read runs." }
        val range = if (start == null) TimeRangeFilter.before(end) else TimeRangeFilter.between(start, end)
        val page = client().readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range,
            dataOriginFilter = setOf(DataOrigin(GARMIN_PACKAGE)), pageSize = 100, pageToken = token))
        return HealthPage(page.records.filter { activityType(it.exerciseType) == "running" && it.endTime <= end }.map(::candidate), page.pageToken?.takeIf { it.isNotEmpty() })
    }
    suspend fun enrich(candidate: HealthCandidate): HealthCandidate {
        val s = candidate.session; val access = access()
        require(access.exercise) { access.message }
        val end = Instant.ofEpochMilli(s.end)
        // Read from the accessible lookback, then filter individual sample times.
        // This also captures batched records that begin before this session.
        val lower = windowStart
        val sampleStart = minOf(Instant.ofEpochMilli(s.start), lower)
        var samples = emptyList<HealthSample>(); var hrRead = false; var distanceRead = false
        var hrStatus = "Heart-rate permission missing. Previously imported samples, if any, are retained."
        var distanceStatus = "Distance permission missing. Previously imported distance, if any, is retained."
        var distance: Double? = null; var distanceOrigins = ""
        if (access.heartRate) try {
            val all = (if (s.activityType == "running") read(HeartRateRecord::class, Instant.ofEpochMilli(s.start).minusSeconds(86400), end.plusSeconds(86400)) else (heartCache ?: read(HeartRateRecord::class, sampleStart, maxOf(end, windowEnd)).also { heartCache = it })).flatMap { record ->
                record.samples.filter { it.time.toEpochMilli() in s.start until s.end }.map {
                    val origin = record.metadata.dataOrigin.packageName; val time = it.time.toEpochMilli()
                    HealthSample(sampleKey(s.key, origin, record.metadata.id, time), s.key, record.metadata.id, origin, time, it.beatsPerMinute)
                }
            }.distinctBy { it.key }
            samples = associatedHeartSamples(s, all)
            hrRead = true
            hrStatus = if (samples.isEmpty()) "No samples from the exercise source within its recorded interval."
                else "Samples from the exercise source."
            // Diagnostics retain source record identities; low values are never filtered.
            samples.filter { it.bpm < 45 }.forEach {
                android.util.Log.d("HealthAssociation", "low=${it.bpm} time=${it.time} record=${it.recordId} origin=${it.originPackage} session=${s.recordId} bounds=${s.start}..${s.end}")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            hrStatus = if (e is SecurityException) "Heart-rate permission was revoked. Existing samples retained." else "Heart-rate read failed. Retry after sync; existing samples retained."
        }
        if (access.distance) try {
            if (s.activityType == "running") {
                val boundsStart = Instant.ofEpochMilli(s.start)
                val records = read(DistanceRecord::class, boundsStart.minusSeconds(86400), end.plusSeconds(86400)).map { RunDistanceRecord(it.metadata.id, it.metadata.dataOrigin.packageName,
                    it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.distance.inMeters) }
                val overlapping = sessions(boundsStart.minusSeconds(86400), end.plusSeconds(86400)).map { it.session }
                val selected = selectRunDistance(s, records, overlapping)
                distance = selected.metres; distanceStatus = selected.evidence
                distanceOrigins = if (distance != null) s.originPackage else ""
                distanceRead = true
            } else {
            val all = (distanceCache ?: read(DistanceRecord::class, sampleStart, maxOf(end, windowEnd)).also { distanceCache = it }).filter { it.startTime.toEpochMilli() < s.end && it.endTime.toEpochMilli() > s.start }
            val preferred = all.filter { it.metadata.dataOrigin.packageName == s.originPackage }
            val chosen = if (preferred.isNotEmpty()) preferred else all.filter { it.metadata.dataOrigin.packageName == all.map { r -> r.metadata.dataOrigin.packageName }.distinct().sorted().firstOrNull() }
            val contained = chosen.filter { it.startTime.toEpochMilli() >= s.start && it.endTime.toEpochMilli() <= s.end }
                .distinctBy { it.metadata.id }.sortedBy { it.startTime }
            val ambiguous = contained.zipWithNext().any { (a, b) -> a.endTime > b.startTime }
            distance = if (contained.isEmpty() || ambiguous) null else contained.sumOf { it.distance.inMeters }
            distanceOrigins = contained.map { it.metadata.dataOrigin.packageName }.distinct().joinToString()
            distanceRead = true
            distanceStatus = when {
                ambiguous -> "Overlapping distance records are ambiguous; no total inferred."
                chosen.size != contained.size -> "Only fully contained records counted; boundary-crossing distance was not apportioned."
                distance == null -> "No distance records found within the session."
                preferred.isEmpty() -> "Distance from another source: $distanceOrigins; interval total, not an official workout statistic."
                else -> "Sum of contained distance records from exercise source; not an official workout statistic."
            }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            distanceStatus = if (e is SecurityException) "Distance permission was revoked. Existing distance retained." else "Distance read failed. Retry after sync; existing distance retained."
        }
        return HealthCandidate(s.copy(importedAt = System.currentTimeMillis(), distanceMeters = distance, distanceOrigins = distanceOrigins,
            hrStatus = hrStatus, distanceStatus = distanceStatus,
            runData = if (s.activityType == "running") RunSourceData.read(s.runData).copy(distanceVerified = distanceRead && distance != null, distanceEvidence = distanceStatus).json() else s.runData), samples, hrRead, distanceRead)
    }
}
fun activityType(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING, ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "strength"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "walking"
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climbing"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS, ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "martial_arts"
    else -> "other"
}
