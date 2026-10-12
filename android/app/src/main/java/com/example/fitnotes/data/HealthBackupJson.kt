package com.example.fitnotes.data

import org.json.JSONObject

object HealthBackupJson {
    fun encode(s: HealthSession) = JSONObject().put("key", s.key).put("workoutId", s.workoutId ?: JSONObject.NULL)
        .put("recordId", s.recordId).put("originPackage", s.originPackage).put("sourceName", s.sourceName)
        .put("clientRecordId", s.clientRecordId ?: JSONObject.NULL).put("clientRecordVersion", s.clientRecordVersion)
        .put("modifiedAt", s.modifiedAt).put("importedAt", s.importedAt).put("start", s.start).put("end", s.end)
        .put("startOffsetSeconds", s.startOffsetSeconds ?: JSONObject.NULL).put("endOffsetSeconds", s.endOffsetSeconds ?: JSONObject.NULL)
        .put("activityType", s.activityType).put("exerciseType", s.exerciseType).put("title", s.title).put("notes", s.notes)
        .put("distanceMeters", s.distanceMeters ?: JSONObject.NULL).put("distanceOrigins", s.distanceOrigins)
        .put("hrStatus", s.hrStatus).put("distanceStatus", s.distanceStatus).put("sourceStatus", s.sourceStatus).put("device", s.device).put("importedWorkoutId", s.importedWorkoutId ?: JSONObject.NULL)
    fun encode(s: HealthSample) = JSONObject().put("key", s.key).put("sessionKey", s.sessionKey).put("recordId", s.recordId)
        .put("originPackage", s.originPackage).put("time", s.time).put("bpm", s.bpm)
    private fun JSONObject.string(k: String) = get(k).also { require(it is String) { "Invalid health field: $k" } } as String
    private fun JSONObject.long(k: String): Long = get(k).let {
        require(it is Number && it !is Double && it !is Float) { "Invalid health integer: $k" }; it.toLong()
    }
    private fun JSONObject.optionalLong(k: String) = if (isNull(k)) null else long(k)
    private fun JSONObject.optionalString(k: String) = if (isNull(k)) null else string(k)
    private fun JSONObject.fields(vararg names: String) { require(keys().asSequence().toSet() == names.toSet()) { "Invalid health backup fields." } }
    fun session(j: JSONObject): HealthSession {
        j.fields("key", "workoutId", "recordId", "originPackage", "sourceName", "clientRecordId", "clientRecordVersion", "modifiedAt", "importedAt",
            "start", "end", "startOffsetSeconds", "endOffsetSeconds", "activityType", "exerciseType", "title", "notes", "distanceMeters",
            "distanceOrigins", "hrStatus", "distanceStatus", "sourceStatus", "device", *if (j.has("importedWorkoutId")) arrayOf("importedWorkoutId") else emptyArray())
        return HealthSession(j.string("key"), j.optionalLong("workoutId"), j.string("recordId"), j.string("originPackage"), j.string("sourceName"),
            j.optionalString("clientRecordId"), j.long("clientRecordVersion"), j.long("modifiedAt"), j.long("importedAt"), j.long("start"), j.long("end"),
            j.optionalLong("startOffsetSeconds")?.also { require(it in -64800..64800) }?.toInt(),
            j.optionalLong("endOffsetSeconds")?.also { require(it in -64800..64800) }?.toInt(),
            j.string("activityType"), j.long("exerciseType").also { require(it in 0..Int.MAX_VALUE) }.toInt(), j.string("title"), j.string("notes"),
            if (j.isNull("distanceMeters")) null else (j.get("distanceMeters") as? Number)?.toDouble() ?: error("Invalid distance"),
            j.string("distanceOrigins"), j.string("hrStatus"), j.string("distanceStatus"), j.string("sourceStatus"), j.string("device"), if (j.has("importedWorkoutId")) j.optionalString("importedWorkoutId") else null)
    }
    fun sample(j: JSONObject): HealthSample {
        j.fields("key", "sessionKey", "recordId", "originPackage", "time", "bpm")
        return HealthSample(j.string("key"), j.string("sessionKey"), j.string("recordId"), j.string("originPackage"), j.long("time"), j.long("bpm"))
    }
}
fun healthKey(origin: String, id: String) = "$origin:$id"
fun sampleKey(session: String, origin: String, record: String, time: Long) = "$session|$origin|$record|$time"
fun validateHealthBackup(sessions: List<HealthSession>, samples: List<HealthSample>, workouts: Set<Long>, importedWorkouts: Set<String> = emptySet()) {
    require(sessions.map { it.key }.distinct().size == sessions.size && samples.map { it.key }.distinct().size == samples.size) { "Duplicate health IDs." }
    val parents = sessions.associateBy { it.key }
    sessions.forEach {
        require(it.key == healthKey(it.originPackage, it.recordId) && it.recordId.isNotBlank() && it.originPackage.isNotBlank() &&
            it.start > 0 && it.end > it.start && it.importedAt > 0 && (it.workoutId == null || it.workoutId in workouts) &&
            (it.importedWorkoutId == null || it.importedWorkoutId in importedWorkouts) && (it.workoutId == null || it.importedWorkoutId == null)) { "Invalid health session." }
        require(ActivityType.entries.any { type -> type.key == it.activityType } && it.modifiedAt >= 0 && it.clientRecordVersion >= 0) { "Invalid health provenance." }
        require(it.distanceMeters == null || (it.distanceMeters.isFinite() && it.distanceMeters >= 0)) { "Invalid imported distance." }
        require(it.startOffsetSeconds == null || it.startOffsetSeconds in -64800..64800) { "Invalid source offset." }
        require(it.endOffsetSeconds == null || it.endOffsetSeconds in -64800..64800) { "Invalid source offset." }
    }
    samples.forEach { sample ->
        val parent = requireNotNull(parents[sample.sessionKey]) { "Missing health session." }
        require(sample.key == sampleKey(sample.sessionKey, sample.originPackage, sample.recordId, sample.time) && sample.recordId.isNotBlank() &&
            sample.originPackage.isNotBlank() && sample.time in parent.start until parent.end && sample.bpm in 1..300) { "Invalid heart-rate sample." }
    }
}
