package com.example.fitnotes.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class HealthTokenExpired : Exception("Health Connect cursor expired; a fresh bounded read is needed.")
data class HealthDeltaPage(val sessions: List<HealthCandidate> = emptyList(), val deletedIds: Set<String> = emptySet(),
    val metricsChanged: Boolean = false, val next: String, val hasMore: Boolean = false)
interface HealthSyncSource {
    suspend fun access(): HealthAccess
    suspend fun token(feed: HealthFeed): String
    suspend fun sessions(start: Instant, end: Instant): List<HealthCandidate>
    suspend fun changes(feed: HealthFeed, token: String): HealthDeltaPage
    suspend fun enrich(candidate: HealthCandidate): HealthCandidate
    fun invalidateMetrics() {}
}

class HealthSyncCoordinator(private val database: FitDatabase, private val store: HealthSyncStore,
    private val source: HealthSyncSource, private val lock: Mutex, private val now: () -> Long = System::currentTimeMillis) {
    private val importer = HealthImportRepository(database)
    private fun checkCurrent(generation: Long, reason: HealthSyncReason) {
        val s = store.state.value
        if (s.generation != generation || (reason != HealthSyncReason.MANUAL && !s.settings.enabled)) throw HealthSyncConfigurationChanged()
    }
    suspend fun sync(reason: HealthSyncReason): Boolean = lock.withLock {
        val initial = store.state.value
        if (reason != HealthSyncReason.MANUAL && !initial.settings.enabled) return@withLock true
        val generation = initial.generation
        store.update(generation) { it.copy(working = true, lastAttempt = now(), imported = 0, refreshed = 0, missing = 0, errors = 0, message = "Reading available health data…") }
        try {
            val access = source.access()
            require(access.exercise) { "Grant exercise read permission in Health Connect. Saved activities are retained." }
            if (reason == HealthSyncReason.BACKGROUND && !access.background) {
                store.update(generation) { it.copy(message = "Background access unavailable. Foreground import and Sync now remain available.") }
                return@withLock true
            }
            val feeds = buildSet { add(HealthFeed.EXERCISE); if (access.heartRate) add(HealthFeed.HEART_RATE); if (access.distance) add(HealthFeed.DISTANCE); if (access.elevation) add(HealthFeed.ELEVATION) }
            val signature = feeds.sortedBy { it.name }.joinToString() + ":history=" + access.history
            if (store.state.value.permissionSignature != signature) store.update(generation) {
                it.copy(tokens = emptyMap(), bootstrapped = false, permissionSignature = signature)
            }
            // Each permission has its own feed. A grant change starts a bounded
            // reconciliation and retains the previously saved metric snapshots.
            try { consume(feeds, access, generation, reason) }
            catch (_: HealthTokenExpired) {
                store.update(generation) { it.copy(tokens = emptyMap(), bootstrapped = false, message = "Cursor expired. Reconciling the last 30 days by source identity…") }
                consume(feeds, access, generation, reason)
            }
            checkCurrent(generation, reason)
            store.update(generation) { it.copy(lastSuccess = now(), message = "Sync complete. Garmin must publish records to Health Connect first; an empty result does not establish complete watch history.") }
            true
        } catch (error: CancellationException) {
            if (store.state.value.generation == generation) store.update(generation) { it.copy(message = "Sync paused; saved batches are retained and the next run resumes safely.") }
            throw error
        } catch (error: Exception) {
            if (store.state.value.generation == generation) store.update(generation) { it.copy(errors = it.errors + 1,
                message = if (error is SecurityException) "Read permissions changed. Review Health Connect access; saved activities are retained." else (error.message ?: "Sync did not complete. Retry safely; saved activities are retained.")) }
            false
        } finally {
            if (store.state.value.generation == generation) store.update(generation) { it.copy(working = false) }
        }
    }
    private suspend fun consume(feeds: Set<HealthFeed>, access: HealthAccess, generation: Long, reason: HealthSyncReason) {
        val state = store.state.value
        if (!state.bootstrapped || feeds.any { state.tokens[it].isNullOrBlank() }) {
            // Acquire tokens BEFORE the snapshot. Writes arriving during the read
            // are drained afterward, including late metric-only changes.
            val tokens = feeds.associateWith { feed -> source.token(feed).also { require(it.isNotBlank()) } }
            val end = Instant.ofEpochMilli(now()); val start = end.minusSeconds(30 * 86400L)
            val snapshot = source.sessions(start, end)
            applySessions(snapshot, access, generation, reason)
            checkCurrent(generation, reason)
            val settings = store.state.value.settings
            val missing = database.withTransaction {
                checkCurrent(generation, reason)
                val candidates = snapshot.filter { it.session.originPackage in settings.origins && it.session.activityType in settings.activities }
                val keys = candidates.map { it.session.key }.toSet()
                val missing = database.healthDao().sessions().filter { it.originPackage in settings.origins && it.activityType in settings.activities &&
                    it.start in start.toEpochMilli() until end.toEpochMilli() && it.key !in keys && (it.workoutId != null || it.importedWorkoutId != null) }
                missing.forEach { database.healthDao().updateSession(it.withSourceStatus("Not returned in the completed reconciliation window. Local activity retained; source availability is uncertain.")) }
                checkCurrent(generation, reason); missing.size
            }
            store.update(generation) { it.copy(tokens = tokens, bootstrapped = true, missing = it.missing + missing) }
        }
        for (feed in feeds) {
            val seen = mutableSetOf<String>()
            do {
                currentCoroutineContext().ensureActive(); checkCurrent(generation, reason)
                val token = store.state.value.tokens.getValue(feed)
                require(seen.add(token)) { "Health Connect repeated a Changes cursor. Retry safely." }
                val page = source.changes(feed, token)
                require(page.next.isNotBlank() && (!page.hasMore || page.next != token)) { "Invalid Health Connect Changes cursor. Retry safely." }
                source.invalidateMetrics()
                if (feed == HealthFeed.EXERCISE) {
                    applySessions(page.sessions, access, generation, reason)
                    database.withTransaction {
                        checkCurrent(generation, reason)
                        database.healthDao().sessions().filter { it.recordId in page.deletedIds }.forEach {
                            database.healthDao().updateSession(it.withSourceStatus("Deleted at source. Local activity and previously read data retained."))
                        }
                        checkCurrent(generation, reason)
                    }
                    if (page.deletedIds.isNotEmpty()) store.update(generation) { state -> state.copy(reviewKeys = state.reviewKeys.filterNot { key -> page.deletedIds.any { key.endsWith(":$it") } }.toSet()) }
                } else if (page.metricsChanged || page.deletedIds.isNotEmpty()) {
                    // Distance deletions contain only a record ID, not its old
                    // interval. Re-read all linked intervals conservatively; never
                    // guess which workout lost the deleted distance record.
                    val linked = importer.known().filter { (it.workoutId != null || it.importedWorkoutId != null) && !it.sourceStatus.removePrefix(SOURCE_CREATED_STATUS_PREFIX).startsWith("Deleted at source") }
                    applySessions(linked.map { HealthCandidate(it) }, access, generation, reason, metricsOnly = true)
                }
                checkCurrent(generation, reason)
                // Only checkpoint after every batch in this SDK page has applied.
                // Replaying a partially applied page is idempotent by stable IDs.
                store.update(generation) { it.copy(tokens = it.tokens + (feed to page.next)) }
            } while (page.hasMore)
        }
    }
    private suspend fun applySessions(candidates: List<HealthCandidate>, access: HealthAccess, generation: Long,
        reason: HealthSyncReason, metricsOnly: Boolean = false) {
        val settings = store.state.value.settings
        store.update(generation) { it.copy(availableOrigins = it.availableOrigins + candidates.associate { c -> c.session.originPackage to c.session.sourceName }) }
        var supplementalIncomplete = false
        for (batch in candidates.distinctBy { it.session.key }.filter { it.session.originPackage in settings.origins && it.session.activityType in settings.activities }.chunked(20)) {
            currentCoroutineContext().ensureActive(); checkCurrent(generation, reason)
            val enriched = batch.map { candidate -> source.enrich(candidate).also {
                if ((access.heartRate && !it.heartRateRead) || (access.distance && !it.distanceRead) || (access.elevation && !it.elevationRead)) supplementalIncomplete = true
            } }
            val counts = database.withTransaction {
                checkCurrent(generation, reason)
                var added = 0; var refreshed = 0
                val review = store.state.value.reviewKeys.toMutableSet()
                for (candidate in enriched) {
                    val s = candidate.session
                    val known = importer.known()
                    val sameClient = known.filter { it.originPackage == s.originPackage && !s.clientRecordId.isNullOrBlank() && it.clientRecordId == s.clientRecordId }
                    val prior = known.firstOrNull { it.key == s.key } ?: sameClient.maxWithOrNull(compareBy<HealthSession> { it.clientRecordVersion }.thenBy { it.modifiedAt }.thenBy { if (it.workoutId != null || it.importedWorkoutId != null) 1 else 0 })
                    if (prior != null && prior.workoutId == null && prior.importedWorkoutId == null) {
                        // Extend a durable ignore/deletion decision across a source
                        // record-ID replacement with the same client identity.
                        if (prior.key != s.key) database.healthDao().putSession(s.copy(workoutId = null, importedWorkoutId = null, sourceStatus = "Previously deleted or ignored."))
                        review.remove(s.key); continue
                    }
                    if (prior != null && (s.clientRecordVersion < prior.clientRecordVersion || (s.clientRecordVersion == prior.clientRecordVersion && s.modifiedAt < prior.modifiedAt))) continue
                    if (prior == null) {
                        val timed = matchingWorkouts(s, database.dao().workouts())
                        val day = healthSessionDate(s)
                        val dated = database.dao().workouts().any { workout -> workout.activityType == s.activityType && workout.knownStart == null && localWorkoutDate(workout) == day &&
                            known.none { it.workoutId == workout.id && it.isSourceCreated } }
                        val importedStrength = s.activityType == "strength" && database.importedDao().workouts().any { it.date == day }
                        val providerDuplicate = known.any { it.key != s.key && it.activityType == s.activityType && it.start < s.end && it.end > s.start && (it.workoutId != null || it.importedWorkoutId != null) }
                        if (timed.isNotEmpty() || dated || importedStrength || providerDuplicate) { review.add(s.key); continue }
                        if (metricsOnly) continue
                        importer.import(candidate, automatic = true); added++
                    } else {
                        importer.import(candidate, attachTo = prior.workoutId, attachImported = prior.importedWorkoutId, automatic = true)
                        if (metricsOnly && prior.sourceStatus.removePrefix(SOURCE_CREATED_STATUS_PREFIX).startsWith("Not returned")) database.healthDao().session(s.key)?.let { database.healthDao().updateSession(it.copy(sourceStatus = prior.sourceStatus)) }
                        refreshed++
                    }
                    review.remove(s.key)
                }
                checkCurrent(generation, reason)
                Triple(added, refreshed, review.toSet())
            }
            store.update(generation) { it.copy(imported = it.imported + counts.first, refreshed = it.refreshed + counts.second, reviewKeys = counts.third) }
        }
        // Base activities remain useful even if an optional SDK read fails. Keep
        // the page cursor behind those writes so the metric snapshot retries.
        require(!supplementalIncomplete) { "Activities were retained, but some metric reads were incomplete. Review permissions/history access and retry; source cursors were retained." }
    }
}
