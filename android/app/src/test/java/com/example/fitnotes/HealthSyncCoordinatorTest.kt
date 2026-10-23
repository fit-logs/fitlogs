package com.example.fitnotes

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.*
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
class HealthSyncCoordinatorTest {
    private lateinit var db: FitDatabase
    private lateinit var store: MemoryHealthSyncStore
    private lateinit var source: StreamSource
    private val lock = Mutex()
    private val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FitDatabase::class.java).build()
        store = MemoryHealthSyncStore(HealthSyncState(settings = HealthSyncSettings(enabled = true)))
        source = StreamSource()
    }
    @After fun close() { db.close() }
    private fun coordinator(s: HealthSyncSource = source, st: HealthSyncStore = store) = HealthSyncCoordinator(db, st, s, lock) { now }
    private fun candidate(id: String, slot: Int = 0, type: String = "running", origin: String = GARMIN_PACKAGE, client: String = id) =
        HealthCandidate(HealthSession(healthKey(origin, id), null, id, origin, "Synthetic source", client, 1, now, now,
            now - 3600000 + slot * 120000, now - 3540000 + slot * 120000, 0, 0, type,
            if (type == "walking") ExerciseSessionRecord.EXERCISE_TYPE_HIKING else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "Synthetic $id", "Source note", null, "", "Synthetic HR", "Synthetic distance"))
    private inner class StreamSource : HealthSyncSource {
        var access = HealthAccess(true, true, true, history = true, background = true, historySupported = true, backgroundSupported = true)
        val records = linkedMapOf<String, HealthCandidate>()
        val events = HealthFeed.entries.associateWith { mutableListOf<HealthDeltaPage>() }
        var afterSnapshot: (suspend () -> Unit)? = null
        var beforeSnapshot: (suspend () -> Unit)? = null
        var expired = false
        var failEnrichmentAt: Int? = null
        var incomplete = false
        private var enriched = 0
        val operations = mutableListOf<String>()
        fun upsert(c: HealthCandidate) { records[c.session.key] = c; events.getValue(HealthFeed.EXERCISE).add(HealthDeltaPage(listOf(c), next = "unused")) }
        fun metric(c: HealthCandidate, feed: HealthFeed = HealthFeed.HEART_RATE) { records[c.session.key] = c; events.getValue(feed).add(HealthDeltaPage(metricsChanged = true, next = "unused")) }
        fun delete(c: HealthCandidate) { records.remove(c.session.key); events.getValue(HealthFeed.EXERCISE).add(HealthDeltaPage(deletedIds = setOf(c.session.recordId), next = "unused")) }
        override suspend fun access() = access
        override suspend fun token(feed: HealthFeed): String { operations.add("token:$feed"); return "${feed.name}:${events.getValue(feed).size}" }
        override suspend fun sessions(start: Instant, end: Instant): List<HealthCandidate> {
            operations.add("snapshot"); beforeSnapshot?.invoke()
            val snapshot = records.values.filter { it.session.start in start.toEpochMilli() until end.toEpochMilli() }.toList()
            afterSnapshot?.invoke(); afterSnapshot = null
            return snapshot
        }
        override suspend fun changes(feed: HealthFeed, token: String): HealthDeltaPage {
            operations.add("changes:$feed")
            if (expired) { expired = false; throw HealthTokenExpired() }
            val index = token.substringAfter(':').toInt(); val all = events.getValue(feed); val page = all.drop(index).take(2)
            val next = index + page.size
            return HealthDeltaPage(page.flatMap { it.sessions }, page.flatMap { it.deletedIds }.toSet(), page.any { it.metricsChanged }, "${feed.name}:$next", next < all.size)
        }
        override suspend fun enrich(candidate: HealthCandidate): HealthCandidate {
            enriched++
            if (failEnrichmentAt == enriched) throw java.io.IOException("Synthetic SDK interruption")
            val fresh = records[candidate.session.key] ?: candidate
            return fresh.copy(heartRateRead = access.heartRate && !incomplete, distanceRead = access.distance && !incomplete)
        }
    }
    @Test fun importsNewGarminRunAndHikeOnceWhileOtherOriginsNeedExplicitSelection() = runBlocking {
        source.upsert(candidate("run")); source.upsert(candidate("hike", 1, "walking"))
        source.upsert(candidate("unselected", 2, origin = "org.synthetic.other"))
        assertTrue(coordinator().sync(HealthSyncReason.FOREGROUND))
        assertEquals(2, db.dao().workouts().size); assertEquals(2, store.state.value.imported)
        assertTrue(db.dao().workouts().all { it.isComplete }); assertTrue(db.dao().sets().isEmpty())
        assertTrue(db.healthDao().sessions().all { it.isSourceCreated })
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(2, db.dao().workouts().size)
        store.configure(store.state.value.settings.copy(origins = setOf("org.synthetic.other")))
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(3, db.dao().workouts().size)
    }
    @Test fun tokensPrecedeSnapshotAndConcurrentArrivalsDrainAcrossChangesPages() = runBlocking {
        source.upsert(candidate("before"))
        source.afterSnapshot = { (1..5).forEach { source.upsert(candidate("race-$it", it)) } }
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL))
        assertEquals(6, db.dao().workouts().size)
        assertEquals(3, source.operations.takeWhile { it != "snapshot" }.count { it.startsWith("token:") })
        assertTrue(source.operations.count { it == "changes:EXERCISE" } >= 3)
    }
    @Test fun sdkReadsDoNotHoldAWriteTransactionOrDisableGymSetSaves() = runBlocking {
        val workouts = WorkoutRepository(db); db.dao().insertExercise(Exercise(1, "Synthetic press", "synthetic press"))
        val active = workouts.start(); workouts.addExercise(active, 1)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        source.upsert(candidate("run")); source.beforeSnapshot = { started.complete(Unit); release.await() }
        coroutineScope {
            val sync = async { coordinator().sync(HealthSyncReason.MANUAL) }; started.await()
            withTimeout(5000) { workouts.saveSet(db.dao().workoutEntries(active).single().id, "40", "8") }
            assertEquals(active, db.dao().active()!!.id); assertEquals(1, db.dao().sets().size); assertTrue(store.state.value.working)
            release.complete(Unit); assertTrue(sync.await())
        }
        assertEquals(active, db.dao().active()!!.id); assertEquals(2, db.dao().workouts().size)
    }
    @Test fun partialBootstrapRetriesStableIdsWithoutDuplicatingAlreadyCommittedBatches() = runBlocking {
        (0..20).forEach { source.upsert(candidate("batch-$it", it)) }; source.failEnrichmentAt = 21
        assertFalse(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(20, db.dao().workouts().size)
        assertFalse(store.state.value.bootstrapped); assertTrue(store.state.value.tokens.isEmpty())
        source.failEnrichmentAt = null
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(21, db.dao().workouts().size)
    }
    @Test fun aFailedCursorCheckpointReplaysTheCommittedSnapshotWithoutNewWorkouts() = runBlocking {
        source.upsert(candidate("run"))
        var fail = true
        val failing = object : HealthSyncStore {
            override val state get() = store.state
            override fun update(expectedGeneration: Long?, transform: (HealthSyncState) -> HealthSyncState) {
                val next = transform(state.value)
                if (fail && next.bootstrapped) { fail = false; throw java.io.IOException("Synthetic checkpoint failure") }
                store.update(expectedGeneration) { next }
            }
        }
        assertFalse(coordinator(st = failing).sync(HealthSyncReason.MANUAL)); assertEquals(1, db.dao().workouts().size)
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(1, db.dao().workouts().size)
    }
    @Test fun expiredTokensReconcileWithoutResurrectingLocallyDeletedActivities() = runBlocking {
        val c = candidate("run"); source.upsert(c); assertTrue(coordinator().sync(HealthSyncReason.MANUAL))
        WorkoutRepository(db).deleteWorkout(db.dao().workouts().single().id)
        source.expired = true
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertTrue(db.dao().workouts().isEmpty())
        assertNull(db.healthDao().sessions().single().workoutId)
    }
    @Test fun lateHeartRateDistanceCorrectionsAndMetricDeletionRefreshWithoutAnExerciseChange() = runBlocking {
        val c = candidate("run"); source.upsert(c); assertTrue(coordinator().sync(HealthSyncReason.MANUAL))
        val workout = db.dao().workouts().single()
        val sample = HealthSample(sampleKey(c.session.key, GARMIN_PACKAGE, "hr", c.session.start + 1000), c.session.key, "hr", GARMIN_PACKAGE, c.session.start + 1000, 123)
        source.metric(c.copy(samples = listOf(sample), session = c.session.copy(distanceMeters = 5000.0)))
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(listOf(sample), db.healthDao().samples())
        assertEquals(5000.0, db.healthDao().sessions().single().distanceMeters!!, 0.0)
        source.metric(c.copy(samples = emptyList(), session = c.session.copy(distanceMeters = null)), HealthFeed.DISTANCE)
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertTrue(db.healthDao().samples().isEmpty())
        assertNull(db.healthDao().sessions().single().distanceMeters); assertEquals(listOf(workout), db.dao().workouts())
    }
    @Test fun sourceDeletionIsDistinctFromNoChangesAndPreservesLocalTrainingAndSamples() = runBlocking {
        val c = candidate("run"); source.upsert(c); coordinator().sync(HealthSyncReason.MANUAL)
        val before = WorkoutRepository(db).backup(); source.delete(c)
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL))
        assertEquals(before.workouts, db.dao().workouts())
        assertTrue(db.healthDao().sessions().single().sourceStatus.contains("Deleted at source"))
        assertTrue(db.healthDao().sessions().single().isSourceCreated)
        source.metric(candidate("other", 2)); coordinator().sync(HealthSyncReason.MANUAL)
        assertTrue(db.healthDao().sessions().single().sourceStatus.contains("Deleted at source"))
    }
    @Test fun ambiguousLocalAndDateOnlyMatchesGoToReviewUntilExplicitAttachment() = runBlocking {
        val c = candidate("run"); source.upsert(c)
        val local = Workout(id = 1, startedAt = c.session.start, completedAt = c.session.end, activityType = "running", title = "Local title", notes = "Local note")
        db.dao().insertWorkout(local); assertTrue(coordinator().sync(HealthSyncReason.MANUAL))
        assertEquals(setOf(c.session.key), store.state.value.reviewKeys); assertTrue(db.healthDao().sessions().isEmpty())
        HealthImportRepository(db).import(source.enrich(c), attachTo = 1)
        assertEquals(local, db.dao().workout(1)); assertFalse(db.healthDao().sessions().single().isSourceCreated)
        val other = candidate("date-only", 2, "walking"); source.upsert(other)
        db.dao().insertWorkout(Workout(id = 2, startedAt = 0, sessionDate = healthSessionDate(other.session), finished = true, activityType = "walking"))
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertTrue(other.session.key in store.state.value.reviewKeys)
    }
    @Test fun explicitIgnoreSurvivesClientRecordIdReplacementAndBackupRestore() = runBlocking {
        val original = candidate("old", client = "stable"); source.upsert(original)
        HealthImportRepository(db).ignore(original)
        val replacement = candidate("new", client = "stable").let { it.copy(session = it.session.copy(clientRecordVersion = 2)) }
        source.records.clear(); source.upsert(replacement)
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertTrue(db.dao().workouts().isEmpty())
        val backup = BackupCodec.decode(BackupCodec.encode(WorkoutRepository(db).backup()))
        WorkoutRepository(db).restore(backup); store.resetAfterRestore()
        assertFalse(store.state.value.settings.enabled); assertTrue(store.state.value.tokens.isEmpty())
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertTrue(db.dao().workouts().isEmpty())
    }
    @Test fun replacementSourceIdsRefreshTheExistingWorkoutAndRetainSamplesWhenPermissionsChange() = runBlocking {
        val original = candidate("old", client = "stable")
        val sample = HealthSample(sampleKey(original.session.key, GARMIN_PACKAGE, "hr", original.session.start + 1000), original.session.key, "hr", GARMIN_PACKAGE, original.session.start + 1000, 120)
        source.upsert(original.copy(samples = listOf(sample))); coordinator().sync(HealthSyncReason.MANUAL)
        val workout = db.dao().workouts().single()
        source.access = source.access.copy(heartRate = false, distance = false)
        val replacement = candidate("new", client = "stable").let { it.copy(session = it.session.copy(clientRecordVersion = 2)) }
        source.records.clear(); source.upsert(replacement)
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(listOf(workout), db.dao().workouts())
        val current = db.healthDao().sessions().single { it.workoutId != null }
        assertEquals("new", current.recordId); assertEquals(current.key, db.healthDao().samples().single().sessionKey)
        source.delete(replacement); coordinator().sync(HealthSyncReason.MANUAL)
        assertEquals(listOf(workout), db.dao().workouts())
    }
    @Test fun revokedOptionalPermissionRetainsSamplesWhileExerciseReadsContinue() = runBlocking {
        val c = candidate("run")
        val sample = HealthSample(sampleKey(c.session.key, GARMIN_PACKAGE, "hr", c.session.start + 1000), c.session.key, "hr", GARMIN_PACKAGE, c.session.start + 1000, 120)
        source.upsert(c.copy(samples = listOf(sample))); coordinator().sync(HealthSyncReason.MANUAL)
        source.access = source.access.copy(heartRate = false, distance = false)
        source.records[c.session.key] = c; source.upsert(candidate("hike", 1, "walking"))
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(2, db.dao().workouts().size)
        assertEquals(listOf(sample), db.healthDao().samples())
        source.access = source.access.copy(exercise = false)
        assertFalse(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(2, db.dao().workouts().size)
        assertNotNull(store.state.value.lastSuccess); assertEquals(1, store.state.value.errors)
    }
    @Test fun metricReadFailureRetainsBaseActivitiesAndTheUnconsumedCursorForRetry() = runBlocking {
        source.upsert(candidate("run")); source.incomplete = true
        assertFalse(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(1, db.dao().workouts().size)
        assertTrue(store.state.value.tokens.isEmpty())
        source.incomplete = false
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(1, db.dao().workouts().size)
    }
    @Test fun concurrentSyncsSerializeAndDisablingDuringReadPreventsLateWrites() = runBlocking {
        source.upsert(candidate("run")); val start = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        source.beforeSnapshot = { if (!start.isCompleted) start.complete(Unit); release.await() }
        coroutineScope {
            val a = async { coordinator().sync(HealthSyncReason.FOREGROUND) }; start.await()
            store.configure(store.state.value.settings.copy(enabled = false)); release.complete(Unit)
            try { a.await(); fail("Expected configuration cancellation") } catch (_: HealthSyncConfigurationChanged) { }
        }
        assertTrue(db.dao().workouts().isEmpty())
        source.beforeSnapshot = null; store.configure(store.state.value.settings.copy(enabled = true))
        coroutineScope { (1..4).map { async { coordinator().sync(HealthSyncReason.MANUAL) } }.awaitAll().forEach { assertTrue(it) } }
        assertEquals(1, db.dao().workouts().size)
    }
    @Test fun deniedBackgroundPermissionFallsBackWithoutReadingOrLosingManualSync() = runBlocking {
        source.upsert(candidate("run")); source.access = source.access.copy(background = false)
        assertTrue(coordinator().sync(HealthSyncReason.BACKGROUND)); assertTrue(source.operations.isEmpty())
        assertTrue(db.dao().workouts().isEmpty()); assertTrue(coordinator().sync(HealthSyncReason.MANUAL))
        assertEquals(1, db.dao().workouts().size)
    }
    @Test fun deviceStateReopensWithDurableChoicesAndCursorsButNeverAStuckWorkingFlag() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("health-connection-device", Context.MODE_PRIVATE).edit().clear().commit()
        val device = DeviceHealthSyncStore(context)
        device.configure(HealthSyncSettings(enabled = true, background = true, activities = setOf("running")))
        device.update { it.copy(tokens = mapOf(HealthFeed.EXERCISE to "synthetic-cursor"), working = true, lastSuccess = now) }
        val reopened = DeviceHealthSyncStore(context).state.value
        assertEquals(device.state.value.copy(working = false), reopened)
        assertSame(HealthSyncRuntime.connection(context).second, HealthSyncRuntime.connection(context).second)
    }
    @Test fun invalidChangesCursorFailsWithoutCheckpointingAndRetriesThePage() = runBlocking {
        coordinator().sync(HealthSyncReason.MANUAL)
        val previous = store.state.value.tokens
        val c = candidate("delta"); source.upsert(c)
        val broken = object : HealthSyncSource by source {
            override suspend fun changes(feed: HealthFeed, token: String) = HealthDeltaPage(listOf(c), next = token, hasMore = true)
        }
        assertFalse(coordinator(broken).sync(HealthSyncReason.MANUAL))
        assertEquals(previous, store.state.value.tokens); assertTrue(db.dao().workouts().isEmpty())
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(1, db.dao().workouts().size)
    }
    @Test fun failedSnapshotDoesNotMarkExistingDataMissingAndTypeFiltersExcludeNewActivities() = runBlocking {
        source.upsert(candidate("run")); coordinator().sync(HealthSyncReason.MANUAL)
        val saved = db.healthDao().sessions().single()
        store.configure(store.state.value.settings.copy(activities = setOf("running")))
        source.beforeSnapshot = { throw java.io.IOException("Synthetic unavailable provider") }
        assertFalse(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(saved, db.healthDao().sessions().single())
        assertTrue(store.state.value.tokens.isEmpty())
        source.beforeSnapshot = null; source.upsert(candidate("hike", 1, "walking"))
        assertTrue(coordinator().sync(HealthSyncReason.MANUAL)); assertEquals(1, db.dao().workouts().size)
    }
    @Test fun emptyStringIsATerminalReadPageRatherThanAnEndlessExtraRequest() = runBlocking {
        var calls = 0
        assertEquals(listOf(1), collectHealthPages { token -> assertNull(token); calls++; HealthPage(listOf(1), "") })
        assertEquals(1, calls)
    }
}
