package com.example.fitnotes

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.FakePermissionController
import androidx.health.connect.client.units.Length
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HealthConnectSyncSourceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: FitDatabase
    private lateinit var fake: FakeHealthConnectClient
    private lateinit var permissions: FakePermissionController
    private lateinit var adapter: HealthConnectSyncSource
    private lateinit var store: MemoryHealthSyncStore
    private val lock = Mutex()
    private val start = Instant.now().minusSeconds(86400)
    private fun exercise(id: String, slot: Int = 0, hike: Boolean = false) = ExerciseSessionRecord(
        startTime = start.plusSeconds(slot * 120L), startZoneOffset = null, endTime = start.plusSeconds(slot * 120L + 60), endZoneOffset = null,
        exerciseType = if (hike) ExerciseSessionRecord.EXERCISE_TYPE_HIKING else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        title = "Synthetic $id", metadata = Metadata.manualEntry(clientRecordId = id))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        permissions = FakePermissionController()
        fake = FakeHealthConnectClient(packageName = GARMIN_PACKAGE, permissionController = permissions)
        adapter = HealthConnectSyncSource(context, fake)
        store = MemoryHealthSyncStore(HealthSyncState(settings = HealthSyncSettings(enabled = true)))
    }
    @After fun close() { db.close() }
    @Test fun snapshotAndPagedChangesUseRealSdkRecordsAndCatchWritesDuringBootstrap() = runBlocking {
        fake.pageSizeGetChanges = 2
        fake.insertRecords(listOf(exercise("before")))
        val source = object : HealthSyncSource by adapter {
            override suspend fun sessions(first: Instant, last: Instant): List<HealthCandidate> {
                val snapshot = adapter.sessions(first, last)
                fake.insertRecords((1..5).map { exercise("raced-$it", it, it % 2 == 0) })
                return snapshot
            }
        }
        assertTrue(HealthSyncCoordinator(db, store, source, lock).sync(HealthSyncReason.MANUAL))
        assertEquals(6, db.dao().workouts().size)
        assertTrue(db.dao().workouts().all { it.isComplete }); assertTrue(db.dao().sets().isEmpty())
        assertEquals(2, db.dao().workouts().count { it.activityType == "walking" })
    }
    @Test fun optionalMetricOnlyChangeRefreshesSourceWithoutAnExerciseUpsert() = runBlocking {
        val session = exercise("run"); fake.insertRecords(listOf(session))
        val coordinator = HealthSyncCoordinator(db, store, adapter, lock)
        assertTrue(coordinator.sync(HealthSyncReason.MANUAL))
        val original = db.dao().workouts().single()
        // connect-testing alpha02 advances an empty response's internal offset
        // incorrectly. Reserve fresh metric tokens before this delta fixture;
        // full cross-run cursor/replay behavior uses the deterministic stream fake.
        val hr = adapter.token(HealthFeed.HEART_RATE); val distance = adapter.token(HealthFeed.DISTANCE)
        store.update { it.copy(tokens = it.tokens + mapOf(HealthFeed.HEART_RATE to hr, HealthFeed.DISTANCE to distance)) }
        fake.insertRecords(listOf<Record>(
            HeartRateRecord(session.startTime, null, session.endTime, null, listOf(HeartRateRecord.Sample(session.startTime.plusSeconds(10), 125)), Metadata.manualEntry(clientRecordId = "late-hr")),
            DistanceRecord(session.startTime, null, session.endTime, null, Length.meters(5000.0), Metadata.manualEntry(clientRecordId = "late-distance"))))
        assertTrue(coordinator.sync(HealthSyncReason.MANUAL))
        assertEquals(125L, db.healthDao().samples().single().bpm)
        assertEquals(5000.0, db.healthDao().sessions().single().distanceMeters!!, 0.0)
        assertEquals(listOf(original), db.dao().workouts())
    }
    @Test fun expiredRealSdkTokenRebootstrapsAndPreservesDeletedLocalSourceDecisions() = runBlocking {
        fake.insertRecords(listOf(exercise("run"))); val coordinator = HealthSyncCoordinator(db, store, adapter, lock)
        assertTrue(coordinator.sync(HealthSyncReason.MANUAL))
        WorkoutRepository(db).deleteWorkout(db.dao().workouts().single().id)
        fake.expireToken(store.state.value.tokens.getValue(HealthFeed.EXERCISE))
        assertTrue(coordinator.sync(HealthSyncReason.MANUAL)); assertTrue(db.dao().workouts().isEmpty())
        assertNull(db.healthDao().sessions().single().workoutId)
    }
    @Test fun historyRangeExtendsEnrichmentCacheBeyondTheFirstRecentRead() = runBlocking {
        permissions.grantPermission(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        val oldStart = start.minusSeconds(120 * 86400L)
        val old = ExerciseSessionRecord(startTime = oldStart, startZoneOffset = null, endTime = oldStart.plusSeconds(600), endZoneOffset = null, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            metadata = Metadata.manualEntry(clientRecordId = "older-run"))
        fake.insertRecords(listOf<Record>(exercise("recent"), old,
            HeartRateRecord(oldStart.minusSeconds(60), null, old.endTime.plusSeconds(60), null,
                listOf(HeartRateRecord.Sample(oldStart.plusSeconds(100), 130)), Metadata.manualEntry(clientRecordId = "old-batched-hr"))))
        val reader = HealthConnectReader(context, fake)
        reader.enrich(reader.sessions(start.minusSeconds(1), start.plusSeconds(1000)).single())
        val candidate = reader.sessions(oldStart.minusSeconds(1), old.endTime.plusSeconds(1)).single()
        assertEquals(130L, reader.enrich(candidate).samples.single().bpm)
    }
    @Test fun rejectedExercisePermissionDoesNotAdvanceAnySourceCursor() = runBlocking {
        permissions.revokePermission(HealthConnectReader.exercisePermission)
        assertFalse(HealthSyncCoordinator(db, store, adapter, lock).sync(HealthSyncReason.MANUAL))
        assertTrue(store.state.value.tokens.isEmpty()); assertNull(store.state.value.lastSuccess)
        assertTrue(db.dao().workouts().isEmpty())
    }
}
