package com.example.fitnotes.data

import org.json.JSONObject

// V2 carries individual targets. StrictJson also rejects duplicate JSON keys.
internal object StructuredPlanV2 {
    fun parse(json: String, fallbackDate: String): PlanSpec {
        try {
            StrictJson(json).check()
            val root = JSONObject(json)
            keys(root, WorkoutPlanFormat.rootFields)
            val title = text(root, "title", required = true)
            val date = if (!root.has("date")) fallbackDate else text(root, "date")
            val minutes = integer(root, "durationMinutes", optional = true) ?: 0
            val array = root.getJSONArray("exercises")
            require(array.length() in 1..WorkoutPlanParser.MAX_EXERCISES) { "Include 1–50 strength exercises." }
            val exercises = (0 until array.length()).map { index ->
                val exercise = array.getJSONObject(index)
                keys(exercise, WorkoutPlanFormat.exerciseFields)
                val name = text(exercise, "name", required = true)
                try {
                    val sets = exercise.getJSONArray("sets")
                    require(sets.length() in 1..WorkoutPlanParser.MAX_SETS) { "Include 1–100 target sets." }
                    val targets = (0 until sets.length()).map { position ->
                        try {
                            val set = sets.getJSONObject(position)
                            keys(set, WorkoutPlanFormat.setFields)
                            val low = integer(set, "repsMin")!!
                            val high = integer(set, "repsMax")!!
                            val weight = if (!set.has("weight") && !set.has("unit")) PlanWeight(null, null)
                                else if (text(set, "unit", required = true).equals("bodyweight", true)) {
                                    require(!set.has("weight")) { "Bodyweight must omit numeric weight." }; PlanWeight(null, "bodyweight")
                                } else {
                                    val raw = set.get("weight")
                                    require(raw is String || raw is Number) { "Weight must be a decimal string or number." }
                                    WorkoutPlanParser.parseWeight("$raw ${text(set, "unit", required = true)}")
                                }
                            PlanSetTarget(low, high, weight, integer(set, "restSeconds", optional = true),
                                if (set.has("loadMode")) text(set, "loadMode", true) else LoadMode.target(weight),
                                if (set.has("isWarmUp")) (set.get("isWarmUp") as? Boolean ?: throw IllegalArgumentException("isWarmUp must be boolean.")) else false,
                                if(set.has("rir"))set.get("rir").let{require(it is Number);it.toDouble()} else null,
                                if(set.has("rpe"))set.get("rpe").let{require(it is Number);it.toDouble()} else null).also { WorkoutPlanParser.validateSet(it, name) }
                        } catch (failure: Exception) { throw PlanParseException(null, "Set ${position + 1}", name, failure.message ?: "Check this target.") }
                    }
                    PlanPrescription(name, targets, text(exercise, "note"))
                } catch (failure: PlanParseException) { throw failure }
                catch (failure: Exception) { throw PlanParseException(null, "Exercise ${index + 1}", name, failure.message ?: "Check this exercise.") }
            }
            return PlanSpec(title, date, minutes, text(root, "note"), exercises, planFingerprint("WORKOUT_PLAN_V2\n" + root.toString()), text(root, "warmUp"))
                .also(WorkoutPlanParser::validate)
        } catch (failure: IllegalArgumentException) { throw failure }
        catch (failure: Exception) { throw IllegalArgumentException("Invalid V2 JSON: ${failure.message ?: "check the structured block"}") }
    }
    private fun keys(o: JSONObject, allowed: Set<String>) {
        val unknown = o.keys().asSequence().toSet() - allowed
        require(unknown.isEmpty()) { "Unknown fields: ${unknown.joinToString()}. Keep guidance in note/warmUp." }
    }
    private fun text(o: JSONObject, key: String, required: Boolean = false): String {
        if (!o.has(key)) { require(!required) { "Missing $key." }; return "" }
        val value = o.get(key); require(value is String) { "$key must be text." }
        require(!required || value.isNotBlank()) { "$key is required." }; return value
    }
    private fun integer(o: JSONObject, key: String, optional: Boolean = false): Int? {
        if (!o.has(key)) { require(optional) { "Missing $key." }; return null }
        val value = o.get(key)
        require(value is Int || (value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE)) { "$key must be a whole number." }
        return (value as Number).toInt()
    }
}
