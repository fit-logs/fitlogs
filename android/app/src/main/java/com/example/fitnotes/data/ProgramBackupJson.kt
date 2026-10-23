package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

internal object ProgramBackupJson {
    val keys = arrayOf("programs", "programDays", "programSessions", "programSteps")
    fun add(root: JSONObject, b: Backup) {
        fun rows(key: String, values: List<JSONObject>) { root.put(key, JSONArray(values)) }
        rows("programs", b.programs.map { JSONObject().put("id", it.id).put("title", it.title).put("weeks", it.weeks).put("status", it.status).put("nextIndex", it.nextIndex).put("notBefore", it.notBefore).put("createdAt", it.createdAt).also { row -> if(b.version>=12)row.put("definitionId",it.definitionId).put("definitionVersion",it.definitionVersion).put("definitionJson",it.definitionJson).put("sourceFingerprint",it.sourceFingerprint) } })
        rows("programDays", b.programDays.map { JSONObject().put("id", it.id).put("programId", it.programId).put("position", it.position).put("planId", it.planId) })
        rows("programSessions", b.programSessions.map { JSONObject().put("workoutId", it.workoutId).put("programId", it.programId).put("dayIndex", it.dayIndex) })
        rows("programSteps", b.programSteps.map { JSONObject().put("id", it.id).put("programId", it.programId).put("dayIndex", it.dayIndex).put("action", it.action).put("workoutId", it.workoutId ?: JSONObject.NULL).put("recordedAt", it.recordedAt) })
    }
    private fun rows(root: JSONObject, key: String, fields: Set<String>): List<JSONObject> {
        val array = root.getJSONArray(key); require(array.length() <= 100000) { "Too many program records." }
        return (0 until array.length()).map { array.getJSONObject(it).also { o -> require(o.keys().asSequence().toSet() == fields) { "Invalid program backup fields." } } }
    }
    private fun text(o: JSONObject, k: String) = (o.get(k) as? String) ?: throw IllegalArgumentException("Expected text for $k.")
    private fun long(o: JSONObject, k: String): Long { val v = o.get(k); require(v is Int || v is Long); return (v as Number).toLong() }
    private fun int(o: JSONObject, k: String): Int = long(o,k).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    fun programs(r: JSONObject,version:Int) = rows(r,"programs",setOf("id","title","weeks","status","nextIndex","notBefore","createdAt") + if(version>=12)setOf("definitionId","definitionVersion","definitionJson","sourceFingerprint") else emptySet()).map { TrainingProgram(text(it,"id"),text(it,"title"),int(it,"weeks"),text(it,"status"),int(it,"nextIndex"),text(it,"notBefore"),long(it,"createdAt"),if(version<12)"" else text(it,"definitionId"),if(version<12)"" else text(it,"definitionVersion"),if(version<12)"" else text(it,"definitionJson"),if(version<12)"" else text(it,"sourceFingerprint")) }
    fun days(r: JSONObject) = rows(r,"programDays",setOf("id","programId","position","planId")).map { ProgramDay(text(it,"id"),text(it,"programId"),int(it,"position"),text(it,"planId")) }
    fun sessions(r: JSONObject) = rows(r,"programSessions",setOf("workoutId","programId","dayIndex")).map { ProgramSession(long(it,"workoutId"),text(it,"programId"),int(it,"dayIndex")) }
    fun steps(r: JSONObject) = rows(r,"programSteps",setOf("id","programId","dayIndex","action","workoutId","recordedAt")).map { ProgramStep(text(it,"id"),text(it,"programId"),int(it,"dayIndex"),text(it,"action"),if(it.isNull("workoutId"))null else long(it,"workoutId"),long(it,"recordedAt")) }
    fun validate(b: Backup) {
        if (b.version < 11) {
            require(b.programs.isEmpty() && b.programDays.isEmpty() && b.programSessions.isEmpty() && b.programSteps.isEmpty() && b.plannedWorkouts.none { it.archived || it.pinned }) { "Programs and template metadata require backup version 11." }; return
        }
        fun ids(values: List<String>) { require(values.all { it.isNotBlank() && it.length <= 128 } && values.distinct().size == values.size) { "Invalid or duplicate program IDs." } }
        ids(b.programs.map { it.id }); ids(b.programDays.map { it.id }); ids(b.programSteps.map { it.id })
        b.programs.forEach { p ->
            if(b.version<12)require(p.definitionId.isEmpty() && p.definitionVersion.isEmpty() && p.definitionJson.isEmpty() && p.sourceFingerprint.isEmpty()) { "Portable program metadata needs backup version 12." }
            if(p.definitionJson.isNotEmpty()) {
                val d=PortableProgramParser.parse(p.definitionJson)
                require(d.id==p.definitionId && d.version==p.definitionVersion && d.name==p.title && Regex("[a-f0-9]{64}").matches(p.sourceFingerprint)) { "Invalid portable program metadata." }
            } else require(p.definitionId.isEmpty() && p.definitionVersion.isEmpty() && p.sourceFingerprint.isEmpty()) { "Missing portable program definition." }
        }
        val programs = b.programs.associateBy { it.id }; val plans = b.plannedWorkouts.associateBy { it.id }; val workouts = b.workouts.associateBy { it.id }
        require(b.programs.count { it.status == "active" } <= 1) { "Only one primary program can be active." }
        require(b.programDays.map { it.planId }.distinct().size == b.programDays.size) { "Each program day owns its prescription." }
        b.programDays.forEach { require(it.programId in programs && it.planId in plans && !plans[it.planId]!!.archived) { "Invalid program day reference." } }
        b.programs.forEach { p ->
            val days = b.programDays.filter { it.programId == p.id }.sortedBy { it.position }
            val steps = b.programSteps.filter { it.programId == p.id }.sortedBy { it.dayIndex }
            require(p.title.isNotBlank() && p.title.length <= 200 && p.title.none(Char::isISOControl) && p.weeks in 1..52 && p.status in setOf("active","paused","completed") && p.createdAt > 0) { "Invalid program." }
            require(days.size in 1..14 && days.map { it.position } == days.indices.toList()) { "Invalid program day order." }
            require(p.nextIndex in 0..p.weeks * days.size && (p.status == "completed") == (p.nextIndex == p.weeks * days.size)) { "Invalid program progress." }
            require(p.notBefore.isBlank() || validHistoryDate(p.notBefore)) { "Invalid postponement date." }
            require(steps.map { it.dayIndex } == (0 until p.nextIndex).toList()) { "Program progress must retain every completion or skip." }
        }
        require(b.programSessions.map { it.workoutId }.distinct().size == b.programSessions.size) { "Duplicate program session." }
        b.programSessions.forEach { s ->
            val p = requireNotNull(programs[s.programId]); val count = b.programDays.count { it.programId == p.id }
            require(s.dayIndex in 0 until p.weeks * count && workouts[s.workoutId]?.let { it.activityType == "strength" && !it.historicalDraft } == true) { "Invalid program session reference." }
        }
        require(b.programSteps.mapNotNull { it.workoutId }.distinct().size == b.programSteps.count { it.workoutId != null }) { "A workout cannot complete more than one program day." }
        b.programSteps.forEach { step ->
            require(step.programId in programs && step.action in setOf("completed","skipped") && step.recordedAt > 0 && (step.action != "skipped" || step.workoutId == null)) { "Invalid program step." }
            step.workoutId?.let { id -> require(workouts[id]?.let { it.activityType == "strength" && !it.historicalDraft } == true && b.programSessions.any { it.workoutId == id && it.programId == step.programId && it.dayIndex == step.dayIndex }) { "Invalid completed program session." } }
        }
    }
}
