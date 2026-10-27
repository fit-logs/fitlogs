package com.example.fitnotes.data

import androidx.room.withTransaction
import com.example.fitnotes.AppData
import com.example.fitnotes.ImportedData
import kotlinx.coroutines.flow.first

suspend fun runningSnapshot(db: FitDatabase): AppData = AppData(exercises = db.dao().exercises(), workouts = db.dao().observeWorkouts().first(), loading = false,
    imported = ImportedData(workouts = db.importedDao().observeWorkouts().first()), runLinks = db.runDao().all())

class RunningRepository(private val db: FitDatabase) {
    private val health = HealthImportRepository(db)
    /** Every source has a durable identity. A separate decision is explicit; null requires no candidates. */
    suspend fun import(candidate: HealthCandidate, choice: String? = null): String = db.withTransaction {
        require(candidate.session.activityType == "running")
        val s = candidate.session
        require(s.end <= System.currentTimeMillis()) { "This run has not finished." }
        val links = db.runDao().all()
        if (links.any { it.sourceKey == s.key && it.ignored }) return@withTransaction "ignored"
        val known = db.healthDao().session(s.key)
        if (known != null) {
            if (known.workoutId == null && known.importedWorkoutId == null) return@withTransaction "ignored"
            health.import(candidate)
            return@withTransaction if (known.modifiedAt != s.modifiedAt || known.distanceMeters != s.distanceMeters || known.runData != s.runData) "updated" else "present"
        }
        // Client IDs are provider identities too. A provider can replace an HC record
        // with a new record ID; keep both IDs associated with the same canonical activity.
        val priorClient = s.clientRecordId?.takeIf { it.isNotBlank() }?.let { id ->
            db.healthDao().sessions().filter { it.originPackage == s.originPackage && it.clientRecordId == id }.maxWithOrNull(compareBy<HealthSession>{it.clientRecordVersion}.thenBy{it.modifiedAt})
        }
        if (priorClient != null) {
            val target = runningRecords(runningSnapshot(db)).firstOrNull { run -> run.sources.any { it.session.key == priorClient.key } }
            if (target == null) {
                db.runDao().put(RunLink(s.key, links.firstOrNull{it.sourceKey==priorClient.key}?.canonicalKey ?: "local:${priorClient.workoutId}", ignored=true))
                return@withTransaction "ignored"
            }
            health.import(candidate, attachTo=priorClient.workoutId, attachImported=priorClient.importedWorkoutId)
            db.runDao().put(RunLink(s.key,target.key,reviewed=false))
            return@withTransaction "updated"
        }
        val runs = runningRecords(runningSnapshot(db))
        val matches = potentialRunMatches(healthRun(HealthDetail(s, candidate.samples), "health:${s.key}", ""), runs)
        require(choice != null || matches.isEmpty()) { "Review this run before importing." }
        val target = choice?.takeUnless { it == "separate" }?.let { key -> requireNotNull(runs.firstOrNull { it.key == key }) { "Selected run no longer exists." } }
        val id = health.import(candidate, attachTo = target?.parentKey?.takeIf { it.startsWith("local:") }?.removePrefix("local:")?.toLong(),
            attachImported = target?.parentKey?.takeIf { it.startsWith("import:") }?.removePrefix("import:")) ?: return@withTransaction "ignored"
        val key = target?.key ?: "local:$id"
        db.runDao().put(RunLink(s.key, key, reviewed = choice != null))
        if (target == null) "added" else "linked"
    }
    suspend fun delete(key: String) = db.withTransaction {
        val snapshot = runningSnapshot(db)
        val run = requireNotNull(runningRecords(snapshot).firstOrNull { it.key == key })
        db.runDao().put(RunLink(key, key, ignored = true))
        run.sources.forEach { db.runDao().put(RunLink(it.session.key, key, ignored = true)) }
        // Retain original rows/source IDs as tombstones, including FitNotes components in mixed workouts.
        // They are hidden locally and excluded from progress, but reimport cannot recreate them.
    }
    suspend fun save(key: String, title: String, notes: String, distanceKm: Double?, recordedSeconds: Double?) = db.withTransaction {
        require(title.trim().isNotBlank() && title.length <= 200 && notes.length <= 10000)
        require(distanceKm == null || distanceKm.isFinite() && distanceKm >= 0)
        require(recordedSeconds == null || recordedSeconds.isFinite() && recordedSeconds > 0)
        if (key.startsWith("local:")) {
            val current = requireNotNull(runningRecords(runningSnapshot(db)).firstOrNull { it.key == key })
            val old = requireNotNull(db.dao().workout(key.removePrefix("local:").toLong()))
            db.dao().updateWorkout(old.copy(title = title.trim(), notes = notes.trim(), distance = if (distanceKm == current.distanceMetres?.div(1000)) old.distance else distanceKm, distanceUnit = if (distanceKm == current.distanceMetres?.div(1000)) old.distanceUnit else distanceKm?.let { "km" }, durationSeconds = recordedSeconds))
        } else if (key.startsWith("set:")) {
            val old = requireNotNull(db.dao().set(key.removePrefix("set:").toLong()))
            val unchanged = distanceKm == distanceInMetres(old.distance,old.distanceUnit)?.div(1000)
            db.dao().updateSet(old.copy(comment=notes.trim(),distance=if(unchanged)old.distance else distanceKm,distanceUnit=if(unchanged)old.distanceUnit else distanceKm?.let{"km"},durationSeconds=recordedSeconds))
        } else {
            val old = requireNotNull(db.importedDao().sets().firstOrNull { "fitnotes:${it.id}" == key })
            // Preserve original exercise/source identity: title is deliberately not renamed here.
            val unchanged = distanceKm == distanceInMetres(old.distance,old.distanceUnit)?.div(1000)
            db.importedDao().updateSet(old.copy(comment = notes.trim(), distance = if(unchanged)old.distance else distanceKm, distanceUnit = if(unchanged)old.distanceUnit else distanceKm?.let { "km" }, durationSeconds = recordedSeconds))
        }
    }
    suspend fun keepSeparate(a: String, b: String) = db.withTransaction {
        require(a != b)
        val keys = runningRecords(runningSnapshot(db)).map { it.key }.toSet()
        require(a in keys && b in keys)
        db.runDao().put(RunLink(runPairDecisionKey(a,b), a))
    }
    suspend fun merge(sourceKey: String, targetKey: String) = db.withTransaction {
        val snapshot = runningSnapshot(db); val runs = runningRecords(snapshot)
        val source = requireNotNull(runs.firstOrNull { it.key == sourceKey }); require(sourceKey != targetKey)
        requireNotNull(runs.firstOrNull { it.key == targetKey })
        // Preserve both activities and their edits. Only their canonical presentation changes.
        db.runDao().put(RunLink("merge:$sourceKey", targetKey, retiredKey = sourceKey))
        source.sources.forEach { db.runDao().put(RunLink(it.session.key, targetKey)) }
    }
}

/** Parent deletions keep decisions as source tombstones; no source refresh may resurrect them. */
suspend fun retainDeletedRunDecisions(db:FitDatabase) {
    val valid=db.dao().workouts().map{"local:${it.id}"}.toSet()+db.dao().sets().map{"set:${it.id}"}+db.importedDao().sets().map{"fitnotes:${it.id}"}
    db.runDao().all().forEach { link ->
        if(link.canonicalKey !in valid || link.retiredKey.isNotEmpty() && link.retiredKey !in valid)
            db.runDao().put(link.copy(ignored=link.ignored || link.canonicalKey !in valid,retiredKey=link.retiredKey.takeIf{it in valid}.orEmpty()))
    }
}
