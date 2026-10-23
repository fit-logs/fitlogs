package com.example.fitnotes.data

import androidx.room.withTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class HealthCandidate(val session: HealthSession, val samples: List<HealthSample> = emptyList(),
    val heartRateRead: Boolean = false, val distanceRead: Boolean = false, val elevationRead: Boolean = false, val summaryHeartRead: Boolean = false, val movingRead: Boolean = false)
const val SOURCE_CREATED_STATUS_PREFIX = "Created from source activity. "
val HealthSession.isSourceCreated: Boolean get() = sourceCreated || sourceStatus.startsWith(SOURCE_CREATED_STATUS_PREFIX)
fun HealthSession.withSourceStatus(message: String) = copy(sourceStatus = (if (isSourceCreated) SOURCE_CREATED_STATUS_PREFIX else "") + message)

data class HealthPage<T>(val records: List<T>, val next: String?)
suspend fun <T> collectHealthPages(read: suspend (String?) -> HealthPage<T>): List<T> {
    val records = mutableListOf<T>(); val seen = mutableSetOf<String>(); var token: String? = null
    do {
        val page = read(token); records.addAll(page.records); token = page.next?.takeIf { it.isNotBlank() }
        require(token == null || seen.add(token)) { "Health Connect repeated a page token. Retry the import." }
    } while (token != null)
    return records
}
fun matchingWorkouts(source: HealthSession, workouts: List<Workout>): List<Workout> = workouts.filter {
    it.activityType == source.activityType && it.knownStart != null && it.startedAt < source.end &&
        (it.completedAt ?: System.currentTimeMillis()) > source.start
}
class HealthImportRepository(private val database: FitDatabase) {
    private val dao = database.healthDao()
    suspend fun known() = dao.sessions()

    suspend fun import(candidate: HealthCandidate, attachTo: Long? = null, attachImported: String? = null, automatic: Boolean = false): Long? = database.withTransaction {
        val s = candidate.session
        validateHealthBackup(listOf(s.copy(workoutId = null, importedWorkoutId = null)), candidate.samples, emptySet())
        val exact = dao.session(s.key)
        val existing = exact ?: dao.sessions().filter { it.provider == s.provider && it.accountId == s.accountId && it.originPackage == s.originPackage && !s.clientRecordId.isNullOrBlank() && it.clientRecordId == s.clientRecordId }
            .maxWithOrNull(compareBy<HealthSession> { it.clientRecordVersion }.thenBy { it.modifiedAt }.thenBy { if (it.workoutId != null || it.importedWorkoutId != null) 1 else 0 })
        // A tombstone survives local deletion: repeat imports never re-create it.
        if (existing != null && existing.workoutId == null && existing.importedWorkoutId == null) {
            if (exact == null) dao.putSession(s.copy(workoutId = null, importedWorkoutId = null, sourceStatus = "Previously deleted or ignored."))
            return@withTransaction null
        }
        if (existing != null && (s.clientRecordVersion < existing.clientRecordVersion || (s.clientRecordVersion == existing.clientRecordVersion && s.modifiedAt < existing.modifiedAt))) return@withTransaction existing.workoutId ?: 0L
        require(existing == null || attachTo == null || existing.workoutId == attachTo) { "This source record is already attached to another session." }
        require(attachTo == null || attachImported == null) { "Choose one session." }
        require(existing == null || attachImported == null || existing.importedWorkoutId == attachImported) { "Source is already attached elsewhere." }
        val importedId = existing?.importedWorkoutId ?: attachImported
        if (importedId != null) requireNotNull(database.importedDao().workout(importedId)) { "Selected history session no longer exists." }
        val workoutId = if (importedId != null) null else existing?.workoutId ?: attachTo ?: run {
            val zone = s.startOffsetSeconds?.let { TimeZone.getTimeZone("GMT" + offsetText(it)) } ?: TimeZone.getDefault()
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(s.start))
            database.dao().insertWorkout(Workout(startedAt = 0, sessionDate = day, finished = true,
                activityType = s.activityType, activityLabel = if (s.activityType == "other") s.title.ifBlank { "Other (Health Connect type ${s.exerciseType})" }.take(100) else ""))
        }
        if (workoutId != null) requireNotNull(database.dao().workout(workoutId)) { "The selected local session no longer exists." }
        val changed = existing != null && (existing.modifiedAt != s.modifiedAt || existing.start != s.start || existing.end != s.end ||
            existing.title != s.title || existing.notes != s.notes || existing.exerciseType != s.exerciseType)
        val intervalChanged = existing != null && (existing.start != s.start || existing.end != s.end)
        val enriched = s.copy(workoutId = workoutId, importedWorkoutId = importedId,
            sourceCreated = existing?.isSourceCreated == true || (existing == null && attachTo == null && attachImported == null),
            elevationMeters = if (candidate.elevationRead) s.elevationMeters else if (intervalChanged) null else existing?.elevationMeters,
            elevationOrigins = if (candidate.elevationRead) s.elevationOrigins else if (intervalChanged) "" else existing?.elevationOrigins.orEmpty(),
            movingSeconds = if (candidate.movingRead) s.movingSeconds else if (intervalChanged) null else existing?.movingSeconds,
            movingOrigin = if (candidate.movingRead) s.movingOrigin else if (intervalChanged) "" else existing?.movingOrigin.orEmpty(),
            averageBpm = if (candidate.summaryHeartRead) s.averageBpm else if (intervalChanged) null else existing?.averageBpm,
            maximumBpm = if (candidate.summaryHeartRead) s.maximumBpm else if (intervalChanged) null else existing?.maximumBpm,
            heartOrigin = if (candidate.summaryHeartRead) s.heartOrigin else if (intervalChanged) "" else existing?.heartOrigin.orEmpty(),
            distanceMeters = if (candidate.distanceRead) s.distanceMeters else if (intervalChanged) null else existing?.distanceMeters,
            distanceOrigins = if (candidate.distanceRead) s.distanceOrigins else if (intervalChanged) "" else existing?.distanceOrigins.orEmpty(),
            distanceStatus = if (!candidate.distanceRead && intervalChanged) s.distanceStatus + " Source interval changed; prior imported distance cannot be attributed to the new interval." else s.distanceStatus,
            sourceStatus = (if (existing?.isSourceCreated == true || (existing == null && attachTo == null && attachImported == null)) SOURCE_CREATED_STATUS_PREFIX else "") + when {
                automatic && changed -> "Source changed; automatically refreshed. Local session preserved."
                automatic && existing == null && (attachTo != null || attachImported != null) -> "Automatically linked by activity and overlapping recorded times. Local session preserved."
                automatic && existing == null -> "Automatically imported from the selected source. Source timing is retained separately."
                changed -> "Source changed; reviewed and refreshed. Local session preserved."
                else -> "Available at last import; sync completion unknown."
            })
        val retained = existing?.let { old -> dao.samples().filter { it.sessionKey == old.key && it.time in s.start until s.end } }.orEmpty()
        if (existing != null && existing.key != s.key) {
            dao.updateSession(existing.copy(workoutId = null, importedWorkoutId = null, sourceStatus = "Superseded source record; identity decision retained."))
            dao.clearSamples(existing.key)
        }
        if (exact == null) dao.putSession(enriched) else dao.updateSession(enriched)
        if (candidate.heartRateRead) {
            // Replace the complete sample snapshot in the same transaction: later data,
            // corrections and removals cannot create duplicated samples.
            dao.clearSamples(s.key)
            dao.putSamples(candidate.samples.distinctBy { it.key })
        } else if (existing != null) {
            // Even when permission is lost, remove any old sample outside changed bounds.
            dao.clearSamples(s.key)
            dao.putSamples(retained.map { it.copy(sessionKey = s.key, key = sampleKey(s.key, it.originPackage, it.recordId, it.time)) })
        }
        workoutId ?: 0L
    }

    suspend fun ignore(candidate: HealthCandidate) = database.withTransaction {
        val s = candidate.session
        if (dao.session(s.key) == null) {
            require(dao.sessions().none { it.provider == s.provider && it.accountId == s.accountId && it.originPackage == s.originPackage && !s.clientRecordId.isNullOrBlank() && it.clientRecordId == s.clientRecordId && (it.workoutId != null || it.importedWorkoutId != null) }) { "This activity is already attached under a previous source ID. Refresh its imported data instead." }
            validateHealthBackup(listOf(s.copy(workoutId = null, importedWorkoutId = null)), emptyList(), emptySet())
            dao.putSession(s.copy(workoutId = null, importedWorkoutId = null, sourceStatus = "Ignored by user; automatic import will not recreate it."))
        }
    }

    suspend fun automaticTargets(candidates: List<HealthCandidate>) =
        automaticHealthTargets(candidates, database.dao().workouts(), dao.sessions())

    // Recheck all matches inside the write transaction: a local edit, explicit
    // attachment or deletion made while Health Connect was reading takes priority.
    // Only enriched records are written; unmatched records never create workouts.
    suspend fun applyAutomaticSync(candidates: List<HealthCandidate>, enriched: List<HealthCandidate>, start: Long, end: Long): HealthSyncResult = database.withTransaction {
        val before = dao.sessions().associateBy { it.key }
        val targets = automaticHealthTargets(candidates, database.dao().workouts(), before.values.toList())
        var refreshed = 0; var linked = 0
        enriched.distinctBy { it.session.key }.forEach { candidate ->
            val prior = before[candidate.session.key]
            if (prior != null && (prior.workoutId != null || prior.importedWorkoutId != null)) {
                import(candidate, automatic = true); refreshed++
            } else if (prior == null) {
                targets[candidate.session.key]?.let { target -> import(candidate, attachTo = target, automatic = true); linked++ }
            }
        }
        val knownKeys = dao.sessions().map { it.key }.toSet()
        val review = candidates.distinctBy { it.session.key }.count { isGarminOrigin(it.session.originPackage) && it.session.key !in knownKeys }
        val missing = noteMissing(start, end, candidates.map { it.session.key }.toSet())
        HealthSyncResult(refreshed, linked, review, missing)
    }

    suspend fun noteMissing(start: Long, end: Long, visible: Set<String>): Int = database.withTransaction {
        val missing = dao.sessions().filter { it.start in start until end && it.key !in visible && (it.workoutId != null || it.importedWorkoutId != null) }
        missing.forEach { dao.updateSession(it.withSourceStatus("Not returned in the last selected window. Source may have changed, moved or become unavailable. Local session retained.")) }
        missing.size
    }
}
private fun offsetText(seconds: Int): String {
    val absolute = kotlin.math.abs(seconds)
    return (if (seconds < 0) "-" else "+") + "%02d:%02d".format(Locale.ROOT, absolute / 3600, absolute % 3600 / 60)
}
