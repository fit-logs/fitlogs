package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

internal object PlanBackupJson {
    fun add(root: JSONObject, backup: Backup) {
        root.put("plannedWorkouts", JSONArray().also { array -> backup.plannedWorkouts.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("title", p.title).put("scheduledDate", p.scheduledDate)
                .put("durationMinutes", p.durationMinutes).put("notes", p.notes).put("createdAt", p.createdAt)
                .put("sourceFingerprint", p.sourceFingerprint).put("startedWorkoutId", p.startedWorkoutId ?: JSONObject.NULL).also { row ->
                    if (backup.version >= 6) row.put("warmUp", p.warmUp).put("legacyGuidance", p.legacyGuidance)
                })
        } })
        root.put("plannedExercises", JSONArray().also { array -> backup.plannedExercises.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("planId", p.planId).put("exerciseId", p.exerciseId).put("position", p.position)
                .put("sourceName", p.sourceName).put("sets", p.sets).put("repsMin", p.repsMin).put("repsMax", p.repsMax)
                .put("weightValue", p.weightValue ?: JSONObject.NULL).put("weightUnit", p.weightUnit ?: JSONObject.NULL).put("note", p.note))
        } })
        root.put("handoffPreferences", backup.handoffPreferences?.let { p ->
            JSONObject().put("id", p.id).put("goals", p.goals).put("equipment", p.equipment).put("constraints", p.constraints)
                .put("activityPreference", p.activityPreference).put("availableMinutes", p.availableMinutes ?: JSONObject.NULL).put("lastTargetDate", p.lastTargetDate)
        } ?: JSONObject.NULL)
    }
    fun plans(root: JSONObject, version: Int): List<PlannedWorkout> = objects(root, "plannedWorkouts", 100000).map { o ->
        keys(o, setOf("id", "title", "scheduledDate", "durationMinutes", "notes", "createdAt", "sourceFingerprint", "startedWorkoutId") + if (version >= 6) setOf("warmUp", "legacyGuidance") else emptySet())
        PlannedWorkout(text(o, "id"), text(o, "title"), text(o, "scheduledDate"), int(o, "durationMinutes"), text(o, "notes"),
            long(o, "createdAt"), text(o, "sourceFingerprint"), if (o.isNull("startedWorkoutId")) null else long(o, "startedWorkoutId"), if (version < 6) "" else text(o, "warmUp"), if (version < 6) "" else text(o, "legacyGuidance"))
    }
    fun targets(root: JSONObject): List<PlannedExercise> = objects(root, "plannedExercises", 1000000).map { o ->
        keys(o, setOf("id", "planId", "exerciseId", "position", "sourceName", "sets", "repsMin", "repsMax", "weightValue", "weightUnit", "note"))
        PlannedExercise(long(o, "id"), text(o, "planId"), long(o, "exerciseId"), int(o, "position"), text(o, "sourceName"),
            int(o, "sets"), int(o, "repsMin"), int(o, "repsMax"), if (o.isNull("weightValue")) null else text(o, "weightValue"),
            if (o.isNull("weightUnit")) null else text(o, "weightUnit"), text(o, "note"))
    }
    fun preferences(root: JSONObject): HandoffPreferences? {
        if (root.isNull("handoffPreferences")) return null
        val o = root.getJSONObject("handoffPreferences")
        keys(o, setOf("id", "goals", "equipment", "constraints", "activityPreference", "availableMinutes", "lastTargetDate"))
        return HandoffPreferences(int(o, "id"), text(o, "goals"), text(o, "equipment"), text(o, "constraints"), text(o, "activityPreference"),
            if (o.isNull("availableMinutes")) null else int(o, "availableMinutes"), text(o, "lastTargetDate"))
    }
    private fun keys(o: JSONObject, fields: Set<String>) { require(o.keys().asSequence().toSet() == fields) { "Invalid planned-workout backup fields." } }
    private fun text(o: JSONObject, field: String) = (o.get(field) as? String) ?: throw IllegalArgumentException("Expected text for $field.")
    private fun long(o: JSONObject, field: String): Long { val v = o.get(field); require(v is Int || v is Long) { "Expected an integer for $field." }; return (v as Number).toLong() }
    private fun int(o: JSONObject, field: String): Int { val v = long(o, field); require(v in Int.MIN_VALUE..Int.MAX_VALUE); return v.toInt() }
    private fun objects(o: JSONObject, field: String, max: Int): List<JSONObject> { val v = o.getJSONArray(field); require(v.length() <= max); return (0 until v.length()).map { v.getJSONObject(it) } }
}

fun validatePlanBackup(backup: Backup) {
    if (backup.version < 5) {
        require(backup.plannedWorkouts.isEmpty() && backup.plannedExercises.isEmpty() && backup.handoffPreferences == null) { "Plans and handoff preferences require backup version 5." }
        return
    }
    val plans = backup.plannedWorkouts
    val targets = backup.plannedExercises
    require(plans.map { it.id }.distinct().size == plans.size && plans.all { it.id.isNotBlank() && it.id.length <= 128 && it.createdAt > 0 }) { "Invalid or duplicate plan IDs." }
    require(targets.map { it.id }.distinct().size == targets.size && targets.all { it.id in 1 until Long.MAX_VALUE }) { "Invalid or duplicate planned exercise IDs." }
    val planIds = plans.map { it.id }.toSet()
    val exerciseIds = backup.exercises.map { it.id }.toSet()
    val workouts = backup.workouts.associateBy { it.id }
    require(plans.mapNotNull { it.startedWorkoutId }.distinct().size == plans.count { it.startedWorkoutId != null }) { "A recorded workout is linked to multiple plans." }
    require(targets.map { it.planId to it.position }.distinct().size == targets.size && targets.map { it.planId to it.exerciseId }.distinct().size == targets.size) { "Duplicate plan positions or exercises." }
    targets.forEach { require(it.planId in planIds && it.exerciseId in exerciseIds && it.position >= 0) { "Invalid planned exercise reference." } }
    plans.forEach { plan ->
        require(plan.startedWorkoutId == null || workouts[plan.startedWorkoutId]?.activityType == "strength") { "Invalid started-plan workout reference." }
        val exercises = targets.filter { it.planId == plan.id }.sortedBy { it.position }
        require(exercises.map { it.position } == exercises.indices.toList()) { "Plan exercise positions must be contiguous." }
        WorkoutPlanParser.validate(PlanSpec(plan.title, plan.scheduledDate, plan.durationMinutes, plan.notes, exercises.map {
            val sets = if (backup.version < 6) {
                require(it.sets in 1..WorkoutPlanParser.MAX_SETS) { "Invalid planned set count." }
                List(it.sets) { _ -> PlanSetTarget(it.repsMin, it.repsMax, PlanWeight(it.weightValue, it.weightUnit)) }
            } else backup.plannedSets.filter { set -> set.plannedExerciseId == it.id }.sortedBy { set -> set.position }.map { set -> set.target }
            PlanPrescription(it.sourceName, sets, it.note)
        }, plan.sourceFingerprint, plan.warmUp, plan.legacyGuidance))
    }
    backup.handoffPreferences?.let(::validateHandoffPreferences)
}
