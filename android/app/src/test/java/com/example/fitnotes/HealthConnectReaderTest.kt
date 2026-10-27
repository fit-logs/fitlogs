package com.example.fitnotes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.health.connect.client.units.Length
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HealthConnectReaderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val start = Instant.now().minusSeconds(86400)
    private val end = start.plusSeconds(600)
    private fun exercise(id: String) = ExerciseSessionRecord(startTime = start, startZoneOffset = null,
        endTime = end, endZoneOffset = null, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        title = "Synthetic $id", metadata = Metadata.manualEntry(clientRecordId = id))

    @Test fun deniedAndRevokedExercisePermissionStopsReadsButSupplementalDenialKeepsPreview() = runBlocking {
        val permissions = FakePermissionController().apply { replaceGrantedPermissions(emptySet()) }
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE, permissionController = permissions)
        val reader = HealthConnectReader(context, fake)
        assertFalse(reader.access().exercise)
        try { reader.sessions(start.minusSeconds(1), end.plusSeconds(1)); fail() } catch (_: IllegalArgumentException) {}
        fake.insertRecords(listOf(exercise("one")))
        permissions.grantPermission(HealthConnectReader.exercisePermission)
        val c = reader.enrich(reader.sessions(start.minusSeconds(1), end.plusSeconds(1)).single())
        assertTrue(c.session.hrStatus.contains("permission missing")); assertFalse(c.heartRateRead)
        assertFalse(c.distanceRead); assertNull(c.session.distanceMeters)
        permissions.revokePermission(HealthConnectReader.exercisePermission)
        try { reader.enrich(c); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun batchedHeartRateFiltersBoundariesPrefersSameSourceAndDistanceNeverApportions() = runBlocking {
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE)
        fake.insertRecords(listOf<Record>(exercise("one"), HeartRateRecord(start.minusSeconds(60), null, end.plusSeconds(60), null,
            listOf(HeartRateRecord.Sample(start.minusSeconds(1), 90), HeartRateRecord.Sample(start, 110),
                HeartRateRecord.Sample(start.plusSeconds(300), 130), HeartRateRecord.Sample(end, 150)), Metadata.manualEntry(clientRecordId = "hr")),
            DistanceRecord(start.minusSeconds(10), null, end.plusSeconds(10), null, Length.meters(2000.0), Metadata.manualEntry(clientRecordId = "boundary"))))
        fake.setPackageName("org.synthetic.other")
        fake.insertRecords(listOf(HeartRateRecord(start, null, end, null,
            listOf(HeartRateRecord.Sample(start.plusSeconds(200), 180)), Metadata.manualEntry(clientRecordId = "other-hr"))))
        val reader = HealthConnectReader(context, fake)
        val c = reader.enrich(reader.sessions(start.minusSeconds(1), end.plusSeconds(1)).single())
        assertTrue(c.heartRateRead); assertEquals(listOf(110L, 130L), c.samples.sortedBy { it.time }.map { it.bpm })
        assertTrue(c.samples.all { it.originPackage == GARMIN_PACKAGE })
        assertNull(c.session.distanceMeters); assertTrue(c.session.distanceStatus.contains("boundary-crossing"))
    }
    @Test fun elevationUsesContainedNonoverlappingRecordsFromOneSourceAndRetainsDeniedSnapshots() = runBlocking {
        val permissions = FakePermissionController()
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE, permissionController = permissions)
        permissions.grantPermission(HealthConnectReader.elevationPermission)
        fake.insertRecords(listOf<Record>(exercise("elevation"), ElevationGainedRecord(start, null, end, null, Length.meters(123.5), Metadata.manualEntry(clientRecordId = "gain"))))
        fake.setPackageName("org.synthetic.other")
        fake.insertRecords(listOf(ElevationGainedRecord(start, null, end, null, Length.meters(999.0), Metadata.manualEntry(clientRecordId = "duplicate-gain"))))
        val reader = HealthConnectReader(context, fake)
        val enriched = reader.enrich(reader.sessions(start.minusSeconds(1), end.plusSeconds(1)).single())
        assertTrue(enriched.elevationRead); assertEquals(123.5, enriched.session.elevationMeters!!, 0.0)
        assertEquals(GARMIN_PACKAGE, enriched.session.elevationOrigins); assertNull(enriched.session.movingSeconds)
        permissions.revokePermission(HealthConnectReader.elevationPermission)
        assertFalse(reader.enrich(enriched).elevationRead)
        permissions.grantPermission(HealthConnectReader.elevationPermission); fake.setPackageName(GARMIN_PACKAGE)
        fake.insertRecords(listOf(ElevationGainedRecord(start.plusSeconds(100), null, end, null, Length.meters(10.0), Metadata.manualEntry(clientRecordId = "overlap"))))
        reader.invalidateMetrics(); assertNull(reader.enrich(enriched).session.elevationMeters)
    }
    @Test fun actualSdkPagingReadsMoreThanOnePageWithoutNameTimeDeduplication() = runBlocking {
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE)
        fake.insertRecords((1..501).map { exercise("session-$it") })
        val result = HealthConnectReader(context, fake).sessions(start.minusSeconds(1), end.plusSeconds(1))
        assertEquals(501, result.size); assertEquals(501, result.map { it.session.key }.distinct().size)
    }

    @Test fun runningHistoryPagesAndDefaultHistoryPermissionRemainIndependent() = runBlocking {
        val permissions = FakePermissionController().apply { replaceGrantedPermissions(HealthConnectReader.runningPermissions) }
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE, permissionController = permissions)
        fake.insertRecords((1..105).map { exercise("history-$it") })
        fake.setPackageName("com.strava"); fake.insertRecords(listOf(exercise("other-origin")))
        val reader = HealthConnectReader(context, fake)
        assertFalse(reader.access().history)
        val records = readRunningHistory({ reader.runningPage(null, end.plusSeconds(1), it) }, { it }, {})
        assertEquals(105, records.size); assertTrue(records.all { it.session.originPackage == GARMIN_PACKAGE })
        assertNull(reader.runningPage(end.plusSeconds(1), end.plusSeconds(100), null).records.firstOrNull())
        permissions.revokePermission(HealthConnectReader.exercisePermission)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { reader.runningPage(null, end, null) } }; Unit
    }
    @Test fun realSourceShapedGarminDistanceIsKeptWithoutMixingTheOverlappingStravaInterval() = runBlocking {
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE)
        val finish = start.plusMillis(4495308)
        fake.insertRecords(listOf<Record>(ExerciseSessionRecord(startTime=start, startZoneOffset=java.time.ZoneOffset.ofHours(13), endTime=finish, endZoneOffset=java.time.ZoneOffset.ofHours(13),
            exerciseType=ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, metadata = Metadata.manualEntry(clientRecordId = "garmin-run")),
            DistanceRecord(start,null,finish,null,Length.meters(10190.16015625),Metadata.manualEntry(clientRecordId="garmin-distance"))))
        fake.setPackageName("com.strava")
        fake.insertRecords(listOf(DistanceRecord(start,null,finish.plusSeconds(41),null,Length.meters(10190.2),Metadata.manualEntry(clientRecordId="strava-distance"))))
        val reader=HealthConnectReader(context,fake);val run=reader.enrich(reader.runningPage(null,finish.plusSeconds(60),null).records.single())
        assertEquals(10190.16015625,run.session.distanceMeters!!,0.0)
        val facts=RunSourceData.read(run.session.runData);assertTrue(facts.distanceVerified);assertNull(facts.timerSeconds);assertNull(facts.movingSeconds)
        val value=healthRun(HealthDetail(run.session,run.samples),"one","one")
        assertEquals(RunTimeBasis.ELAPSED,value.preferredTime!!.first);assertEquals("7:21 /km",paceText(value.pace));assertEquals(GARMIN_PACKAGE,run.session.distanceOrigins)
    }
}
