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
    @Test fun actualSdkPagingReadsMoreThanOnePageWithoutNameTimeDeduplication() = runBlocking {
        val fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE)
        fake.insertRecords((1..501).map { exercise("session-$it") })
        val result = HealthConnectReader(context, fake).sessions(start.minusSeconds(1), end.plusSeconds(1))
        assertEquals(501, result.size); assertEquals(501, result.map { it.session.key }.distinct().size)
    }
}
