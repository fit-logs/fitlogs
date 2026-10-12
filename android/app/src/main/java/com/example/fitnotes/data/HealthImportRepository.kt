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
        val page = read(token); records.addAll(page.records); token = page.next
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

    suspend fun import(candidate: HealthCandidate, attachTo: Long? = null, attachImported: String? = null): Long? = database.withTransaction {
        val s = candidate.session
        validateHealthBackup(listOf(s.copy(workoutId = null, importedWorkoutId = null)), candidate.samples, emptySet())
        val existing = dao.session(s.key)
        // A tombstone survives local deletion: repeat imports never re-create it.
        if (existing != null && existing.workoutId == null && existing.importedWorkoutId == null) return@withTransaction null
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
            distanceMeters = if (candidate.distanceRead) s.distanceMeters else if (intervalChanged) null else existing?.distanceMeters,
            distanceOrigins = if (candidate.distanceRead) s.distanceOrigins else if (intervalChanged) "" else existing?.distanceOrigins.orEmpty(),
            distanceStatus = if (!candidate.distanceRead && intervalChanged) s.distanceStatus + " Source interval changed; prior imported distance cannot be attributed to the new interval." else s.distanceStatus,
            sourceStatus = if (changed) "Source changed; reviewed and refreshed. Local session preserved." else "Available at last import; sync completion unknown.")
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
        workoutId ?: 0L
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
