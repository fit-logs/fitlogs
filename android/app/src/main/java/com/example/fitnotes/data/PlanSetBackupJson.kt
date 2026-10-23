package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

internal object PlanSetBackupJson {
    private fun row(id: Long, parent: Long, parentName: String, position: Int, min: Int?, max: Int?, weight: String?, unit: String?, rest: Int?) =
        JSONObject().put("id", id).put(parentName, parent).put("position", position).put("repsMin", min ?: JSONObject.NULL).put("repsMax", max ?: JSONObject.NULL)
            .put("weightValue", weight ?: JSONObject.NULL).put("weightUnit", unit ?: JSONObject.NULL).put("restSeconds", rest ?: JSONObject.NULL)
    fun add(root: JSONObject, backup: Backup) {
        root.put("plannedSets", JSONArray().also { rows -> backup.plannedSets.forEach { rows.put(row(it.id, it.plannedExerciseId, "plannedExerciseId", it.position, it.repsMin, it.repsMax, it.weightValue, it.weightUnit, it.restSeconds).also { row -> if (backup.version >= 7) row.put("loadMode", it.loadMode).put("isWarmUp", it.isWarmUp) }) } })
        root.put("runTargets", JSONArray().also { rows -> backup.runTargets.forEach { rows.put(row(it.id, it.entryId, "entryId", it.position, it.repsMin, it.repsMax, it.weightValue, it.weightUnit, it.restSeconds).also { row -> if (backup.version >= 7) row.put("loadMode", it.loadMode).put("isWarmUp", it.isWarmUp).put("skipped", it.skipped) }) } })
        root.put("planRuns", JSONArray().also { rows -> backup.planRuns.forEach { rows.put(JSONObject().put("workoutId", it.workoutId).put("planId", it.planId ?: JSONObject.NULL).put("title", it.title).put("warmUp", it.warmUp).put("legacyGuidance", it.legacyGuidance)) } })
    }
    fun sets(root: JSONObject, version: Int) = objects(root, "plannedSets").map { o ->
        keys(o, targetKeys + "plannedExerciseId" + if (version >= 7) setOf("loadMode", "isWarmUp") else emptySet())
        PlannedSet(long(o, "id"), long(o, "plannedExerciseId"), int(o, "position"), optionalInt(o, "repsMin"), optionalInt(o, "repsMax"), optionalText(o, "weightValue"), optionalText(o, "weightUnit"), optionalInt(o, "restSeconds"), if (version >= 7) text(o, "loadMode") else LoadMode.target(PlanWeight(optionalText(o, "weightValue"), optionalText(o, "weightUnit"))), if (version >= 7) boolean(o, "isWarmUp") else false)
    }
    fun targets(root: JSONObject, version: Int) = objects(root, "runTargets").map { o ->
        keys(o, targetKeys + "entryId" + if (version >= 7) setOf("loadMode", "isWarmUp", "skipped") else emptySet())
        WorkoutSetTarget(long(o, "id"), long(o, "entryId"), int(o, "position"), optionalInt(o, "repsMin"), optionalInt(o, "repsMax"), optionalText(o, "weightValue"), optionalText(o, "weightUnit"), optionalInt(o, "restSeconds"), if (version >= 7) text(o, "loadMode") else LoadMode.target(PlanWeight(optionalText(o, "weightValue"), optionalText(o, "weightUnit"))), if (version >= 7) boolean(o, "isWarmUp") else false, if (version >= 7) boolean(o, "skipped") else false)
    }
    fun runs(root: JSONObject) = objects(root, "planRuns").map { o ->
        keys(o, setOf("workoutId", "planId", "title", "warmUp", "legacyGuidance"))
        WorkoutPlanRun(long(o, "workoutId"), optionalText(o, "planId"), text(o, "title"), text(o, "warmUp"), text(o, "legacyGuidance"))
    }
    private val targetKeys = setOf("id", "position", "repsMin", "repsMax", "weightValue", "weightUnit", "restSeconds")
    private fun keys(o: JSONObject, fields: Set<String>) { require(o.keys().asSequence().toSet() == fields) { "Invalid per-set target backup fields." } }
    private fun long(o: JSONObject, field: String): Long { val v = o.get(field); require(v is Int || v is Long) { "$field must be a whole number." }; return (v as Number).toLong() }
    private fun int(o: JSONObject, field: String): Int { val v = long(o, field); require(v in Int.MIN_VALUE..Int.MAX_VALUE); return v.toInt() }
    private fun optionalInt(o: JSONObject, field: String) = if (o.isNull(field)) null else int(o, field)
    private fun boolean(o: JSONObject, field: String) = (o.get(field) as? Boolean) ?: throw IllegalArgumentException("$field must be boolean.")
    private fun text(o: JSONObject, field: String) = (o.get(field) as? String) ?: throw IllegalArgumentException("$field must be text.")
    private fun optionalText(o: JSONObject, field: String) = if (o.isNull(field)) null else text(o, field)
    private fun objects(o: JSONObject, field: String): List<JSONObject> { val rows = o.getJSONArray(field); require(rows.length() <= 1000000); return (0 until rows.length()).map { rows.getJSONObject(it) } }
}

fun validatePlanSetBackup(backup: Backup) {
    if (backup.version < 6) {
        require(backup.plannedSets.isEmpty() && backup.planRuns.isEmpty() && backup.runTargets.isEmpty() && backup.entries.all { it.planNote.isEmpty() } &&
            backup.plannedWorkouts.all { it.warmUp.isEmpty() && it.legacyGuidance.isEmpty() }) { "Per-set targets require backup version 6." }
        return
    }
    val planned = backup.plannedExercises.associateBy { it.id }
    val entries = backup.entries.associateBy { it.id }
    val workouts = backup.workouts.associateBy { it.id }
    val plans = backup.plannedWorkouts.map { it.id }.toSet()
    require(backup.planRuns.map { it.workoutId }.distinct().size == backup.planRuns.size && backup.planRuns.all { workouts[it.workoutId]?.activityType == "strength" && (it.planId == null || it.planId in plans) }) { "Invalid workout-plan snapshot references." }
    backup.planRuns.forEach { require(it.title.length <= 200 && it.warmUp.length <= WorkoutPlanParser.MAX_NOTE && it.legacyGuidance.length <= 10000) { "Invalid workout snapshot guidance." } }
    val runIds = backup.planRuns.map { it.workoutId }.toSet()
    require(backup.plannedSets.map { it.id }.distinct().size == backup.plannedSets.size && backup.plannedSets.all { it.id > 0 && it.plannedExerciseId in planned }) { "Invalid planned set IDs or references." }
    require(backup.runTargets.map { it.id }.distinct().size == backup.runTargets.size && backup.runTargets.all { it.id > 0 && entries[it.entryId]?.workoutId in runIds }) { "Invalid workout target IDs or references." }
    fun positions(values: List<Int>) { require(values.sorted() == values.indices.toList() && values.size <= WorkoutPlanParser.MAX_SETS) { "Target set positions must be unique and contiguous." } }
    backup.plannedSets.groupBy { it.plannedExerciseId }.values.forEach { positions(it.map { it.position }); it.forEach { WorkoutPlanParser.validateSet(it.target) } }
    backup.runTargets.groupBy { it.entryId }.values.forEach { positions(it.map { it.position }); it.forEach { WorkoutPlanParser.validateSet(it.target) } }
    backup.plannedExercises.forEach { exercise -> require(exercise.sets == backup.plannedSets.count { it.plannedExerciseId == exercise.id }) { "Plan set count does not match individual targets." } }
    backup.entries.forEach { require(it.planNote.length <= WorkoutPlanParser.MAX_NOTE) { "Exercise guidance is too long." } }
}
