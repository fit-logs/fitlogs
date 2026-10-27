package com.example.fitnotes.data

import androidx.room.withTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class HealthCandidate(val session: HealthSession, val samples: List<HealthSample> = emptyList(),
    val heartRateRead: Boolean = false, val distanceRead: Boolean = false)
data class HealthPage<T>(val records: List<T>, val next: String?)
suspend fun <T> collectHealthPages(read: suspend (String?) -> HealthPage<T>): List<T> {
    val records = mutableListOf<T>(); val seen = mutableSetOf<String>(); var token: String? = null
    do {
        val page = read(token); records.addAll(page.records); token = page.next?.takeIf { it.isNotEmpty() }
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

    suspend fun import(candidate: HealthCandidate, attachTo: Long? = null, attachImported: String? = null, automatic: Boolean = false, beforeRepair: (suspend () -> Unit)? = null): Long? = database.withTransaction {
        val s = candidate.session
        validateHealthBackup(listOf(s.copy(workoutId = null, importedWorkoutId = null)), candidate.samples, emptySet())
        val existing = dao.session(s.key)
        // A tombstone survives local deletion: repeat imports never re-create it.
        if (existing != null && existing.workoutId == null && existing.importedWorkoutId == null) return@withTransaction null
        require(attachTo == null || attachImported == null) { "Choose one session." }
        val reattach=existing!=null && (attachTo!=null && existing.workoutId!=attachTo || attachImported!=null && existing.importedWorkoutId!=attachImported)
        val oldParent=if(reattach)existing?.workoutId?.let{database.dao().workout(it)} else null
        if(reattach) {
            require(!automatic && oldParent!=null && oldParent.knownStart==null && oldParent.finished && oldParent.sessionDate.isNotBlank() &&
                oldParent.title.isBlank() && oldParent.notes.isBlank() && oldParent.durationSeconds==null && oldParent.effort==null && oldParent.distance==null &&
                database.dao().workoutEntries(oldParent.id).isEmpty() && database.planDao().run(oldParent.id)==null) {
                "This activity is attached to a workout with its own data. Keep both workouts and review their association."
            }
            requireNotNull(beforeRepair){"A recovery backup is required before changing this association."}.invoke()
        }
        val importedId = if(reattach)attachImported else existing?.importedWorkoutId ?: attachImported
        if (importedId != null) requireNotNull(database.importedDao().workout(importedId)) { "Selected history session no longer exists." }
        val workoutId = if (importedId != null) null else (if(reattach)attachTo else existing?.workoutId ?: attachTo) ?: run {
            val zone = s.startOffsetSeconds?.let { TimeZone.getTimeZone("GMT" + offsetText(it)) } ?: TimeZone.getDefault()
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(s.start))
            database.dao().insertWorkout(Workout(startedAt = 0, sessionDate = day, finished = true,
                activityType = s.activityType, activityLabel = if (s.activityType == "other") s.title.ifBlank { "Other (Health Connect type ${s.exerciseType})" }.take(100) else ""))
        }
        if (workoutId != null) requireNotNull(database.dao().workout(workoutId)) { "The selected local session no longer exists." }
        val changed = existing != null && (existing.modifiedAt != s.modifiedAt || existing.start != s.start || existing.end != s.end ||
            existing.title != s.title || existing.notes != s.notes || existing.exerciseType != s.exerciseType)
        val intervalChanged = existing != null && (existing.start != s.start || existing.end != s.end)
        val sourceFacts = if (s.activityType == "running" && !candidate.distanceRead && !intervalChanged && existing != null) {
            val old = RunSourceData.read(existing.runData)
            RunSourceData.read(s.runData).copy(distanceVerified = old.distanceVerified, distanceEvidence = old.distanceEvidence).json()
        } else s.runData
        val enriched = s.copy(runData = sourceFacts, workoutId = workoutId, importedWorkoutId = importedId,
            distanceMeters = if (candidate.distanceRead) s.distanceMeters else if (intervalChanged) null else existing?.distanceMeters,
            distanceOrigins = if (candidate.distanceRead) s.distanceOrigins else if (intervalChanged) "" else existing?.distanceOrigins.orEmpty(),
            distanceStatus = if (!candidate.distanceRead && intervalChanged) s.distanceStatus + " Source interval changed; prior imported distance cannot be attributed to the new interval." else s.distanceStatus,
            sourceStatus = when {
                automatic && changed -> "Source changed; automatically refreshed. Local session preserved."
                automatic && existing == null -> "Automatically linked by activity and overlapping recorded times. Local session preserved."
                changed -> "Source changed; reviewed and refreshed. Local session preserved."
                else -> "Available at last import; sync completion unknown."
            })
        if (existing == null) dao.putSession(enriched) else dao.updateSession(enriched)
        if (candidate.heartRateRead) {
            // Replace the complete sample snapshot in the same transaction: later data,
            // corrections and removals cannot create duplicated samples.
            dao.clearSamples(s.key)
            dao.putSamples(candidate.samples.distinctBy { it.key })
        } else if (existing != null) {
            // Even when permission is lost, remove any old sample outside changed bounds.
            val retained = dao.samples().filter { it.sessionKey == s.key && it.time in s.start until s.end }
            dao.clearSamples(s.key); dao.putSamples(retained)
        }
        if(reattach && oldParent!=null && dao.sessions().none{it.workoutId==oldParent.id})database.dao().deleteWorkout(oldParent.id)
        workoutId ?: 0L
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
        missing.forEach { dao.updateSession(it.copy(sourceStatus = "Not returned in the last selected window. Source may have changed, moved or become unavailable. Local session retained.")) }
        missing.size
    }
}
private fun offsetText(seconds: Int): String {
    val absolute = kotlin.math.abs(seconds)
    return (if (seconds < 0) "-" else "+") + "%02d:%02d".format(Locale.ROOT, absolute / 3600, absolute % 3600 / 60)
}
