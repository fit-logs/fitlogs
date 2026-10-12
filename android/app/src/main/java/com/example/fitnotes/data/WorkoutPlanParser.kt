package com.example.fitnotes.data

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale

data class PlanWeight(val value: String?, val unit: String?) {
    val text: String get() = when (unit) { null -> "Unspecified"; "bodyweight" -> "Bodyweight"; else -> "$value $unit" }
}
data class PlanSetTarget(val repsMin: Int?, val repsMax: Int?, val weight: PlanWeight = PlanWeight(null, null), val restSeconds: Int? = null) {
    val repsText get() = when { repsMin == null -> "Unspecified"; repsMin == repsMax -> repsMin.toString(); else -> "$repsMin-$repsMax" }
    val text get() = "$repsText reps · ${weight.text}" + (restSeconds?.let { " · rest $it s" } ?: "")
}
data class PlanPrescription(val name: String, val sets: Int, val repsMin: Int, val repsMax: Int,
    val weight: PlanWeight = PlanWeight(null, null), val note: String = "", val targets: List<PlanSetTarget>? = null) {
    val setTargets get() = targets ?: List(sets) { PlanSetTarget(repsMin, repsMax, weight) }
    val repsText get() = if (repsMin == repsMax) repsMin.toString() else "$repsMin-$repsMax"
    val targetText get() = "$sets sets · $repsText reps · ${weight.text}"
}
data class PlanSpec(val title: String, val date: String, val durationMinutes: Int, val note: String,
    val exercises: List<PlanPrescription>, val fingerprint: String, val warmUp: String = "", val legacyGuidance: String = "")

fun planFingerprint(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

class PlanParseException(val line: Int?, val field: String?, val exercise: String?, reason: String) : IllegalArgumentException(
    listOfNotNull(line?.let { "Line $it" }, exercise?.let { "Exercise $it" }, field).joinToString(" · ").let {
        if (it.isBlank()) reason else "$it: $reason"
    })

object WorkoutPlanParser {
    const val MAX_INPUT = 64000
    const val MAX_EXERCISES = 50
    const val MAX_NOTE = 2000
    const val MAX_SETS = 100
    const val MAX_REPS = 1000
    const val MAX_MINUTES = 720
    private val marker = Regex("WORKOUT_PLAN_V([0-9]+)")

    fun parse(raw: String, fallbackDate: String): PlanSpec {
        require(raw.length <= MAX_INPUT) { "Paste one plan of at most 64,000 characters." }
        require(fallbackDate.isBlank() || validHistoryDate(fallbackDate)) { "Choose a valid scheduled date as YYYY-MM-DD." }
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
        val starts = lines.indices.filter { marker.matches(lines[it].trim()) }
        val ends = lines.indices.filter { lines[it].trim() == "END_WORKOUT_PLAN" }
        require(starts.size <= 1 && ends.size <= 1) { "Multiple plan blocks found. Paste exactly one workout plan." }
        require(starts.size == 1) { "Missing WORKOUT_PLAN_V2 (or legacy V1) start marker." }
        val start = starts.single()
        val version = lines[start].trim()
        require(version in setOf("WORKOUT_PLAN_V1", "WORKOUT_PLAN_V2")) { "Unsupported workout plan version. Use WORKOUT_PLAN_V2 or legacy V1." }
        require(ends.size == 1 && ends.single() > start) { "Missing END_WORKOUT_PLAN after the plan." }
        val end = ends.single()
        if (version == "WORKOUT_PLAN_V2") return StructuredPlanV2.parse(lines.subList(start + 1, end).joinToString("\n"), fallbackDate)
        val header = linkedMapOf<String, Pair<String, Int>>()
        val sections = mutableListOf<LinkedHashMap<String, Pair<String, Int>>>()
        var current: LinkedHashMap<String, Pair<String, Int>>? = null
        fun error(index: Int, field: String?, reason: String): Nothing = throw PlanParseException(index + 1, field, current?.get("exercise")?.first, reason)
        for (index in start + 1 until end) {
            val line = lines[index].trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) error(index, null, "Expected a Field: value line. Put additional guidance in Note.")
            val key = line.take(colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (key == "exercise") {
                if (sections.size >= MAX_EXERCISES) error(index, "Exercise", "Use at most $MAX_EXERCISES exercises.")
                current = linkedMapOf("exercise" to (value to index))
                sections.add(current)
                continue
            }
            val fields = if (current == null) setOf("title", "date", "duration", "note") else setOf("sets", "reps", "weight", "note")
            if (key !in fields) error(index, line.take(colon).trim(), "Unknown or misplaced field. Keep V1 limited to strength plans and use Note for extra instructions.")
            val destination = current ?: header
            val previous = destination[key]
            if (previous != null && previous.first != value) error(index, key, "Conflicting duplicate field; keep one value.")
            destination[key] = value to index
        }
        fun headerValue(key: String, required: Boolean = false): String {
            val value = header[key]?.first
            if (required && value.isNullOrBlank()) throw PlanParseException(null, key, null, "This field is required.")
            return value.orEmpty()
        }
        val title = headerValue("title", true)
        val date = if (header.containsKey("date")) headerValue("date", true) else fallbackDate
        if (date.isNotBlank() && !validHistoryDate(date)) throw PlanParseException(header["date"]?.second?.plus(1), "Date", null, "Use a valid date as YYYY-MM-DD.")
        val duration = headerValue("duration", true)
        val minutes = Regex("([0-9]+)\\s*min", RegexOption.IGNORE_CASE).matchEntire(duration)?.groupValues?.get(1)?.toIntOrNull()
        if (minutes == null || minutes !in 1..MAX_MINUTES) throw PlanParseException(header["duration"]?.second?.plus(1), "Duration", null, "Use 1-$MAX_MINUTES whole minutes, for example 45 min.")
        val exercises = sections.map { section ->
            val name = section.getValue("exercise").first
            fun value(key: String, required: Boolean = false): String {
                val result = section[key]?.first
                if (required && result.isNullOrBlank()) throw PlanParseException(section["exercise"]!!.second + 1, key, name, "This field is required.")
                return result.orEmpty()
            }
            fun <T> parseField(key: String, block: () -> T): T = try { block() } catch (error: IllegalArgumentException) {
                throw PlanParseException(section[key]?.second?.plus(1), key, name, error.message ?: "Check this field.")
            }
            val sets = parseField("sets") { positiveInteger(value("sets", true), MAX_SETS, "Sets") }
            val reps = parseField("reps") { parseReps(value("reps", true)) }
            val weight = parseField("weight") { parseWeight(value("weight"), present = section.containsKey("weight")) }
            PlanPrescription(name, sets, reps.first, reps.second, weight, value("note"))
        }
        val fingerprint = planFingerprint(lines.subList(start, end + 1).joinToString("\n") { it.trim() })
        return PlanSpec(title, date, minutes, headerValue("note"), exercises, fingerprint).also(::validate)
    }

    fun positiveInteger(raw: String, maximum: Int, label: String): Int {
        val count = raw.trim().takeIf { Regex("[0-9]+").matches(it) }?.toIntOrNull()
        require(count != null && count in 1..maximum) { "$label must be a whole number from 1 to $maximum." }
        return count
    }
    fun parseReps(raw: String): Pair<Int, Int> {
        val parts = raw.trim().split(Regex("\\s*[-–]\\s*"))
        require(parts.size in 1..2) { "Use whole reps or an ascending range such as 8-10." }
        val low = positiveInteger(parts.first(), MAX_REPS, "Reps")
        val high = if (parts.size == 1) low else positiveInteger(parts.last(), MAX_REPS, "Reps")
        require(parts.size == 1 || high > low) { "Rep ranges must ascend, for example 8-10." }
        return low to high
    }
    fun parseWeight(raw: String, present: Boolean = raw.isNotBlank()): PlanWeight {
        if (!present) return PlanWeight(null, null)
        val text = raw.trim()
        if (text.equals("bodyweight", true)) return PlanWeight(null, "bodyweight")
        val match = Regex("([0-9]+(?:\\.[0-9]{1,6})?|\\.[0-9]{1,6})\\s*(kg|lb)", RegexOption.IGNORE_CASE).matchEntire(text)
        require(match != null) { "Use a decimal weight with kg or lb, explicit bodyweight, or omit Weight." }
        val value = match.groupValues[1].let { if (it.startsWith('.')) "0$it" else it }
        require(value.length <= 16 && BigDecimal(value) in BigDecimal.ZERO..BigDecimal("10000")) { "Suggested weight must be between 0 and 10,000, with at most 6 decimal places." }
        return PlanWeight(value, match.groupValues[2].lowercase(Locale.ROOT))
    }
    fun validate(spec: PlanSpec) {
        fun text(value: String, max: Int, label: String, required: Boolean = false) {
            require(value.length <= max && (!required || value.isNotBlank()) && value.none { it.isISOControl() && it !in "\n\r\t" }) { "$label must be readable text of at most $max characters." }
        }
        text(spec.title, 200, "Title", true)
        require(spec.date.isBlank() || validHistoryDate(spec.date)) { "Date must be a valid YYYY-MM-DD date." }
        require(spec.durationMinutes in 0..MAX_MINUTES) { "Duration must be 1-$MAX_MINUTES minutes." }
        text(spec.note, MAX_NOTE, "Session note")
        text(spec.warmUp, MAX_NOTE, "Warm-up")
        text(spec.legacyGuidance, 10000, "Legacy guidance")
        require(spec.exercises.size <= MAX_EXERCISES && (spec.exercises.isNotEmpty() || spec.legacyGuidance.isNotBlank())) { "Include 1-$MAX_EXERCISES strength exercises." }
        require(Regex("[a-f0-9]{64}").matches(spec.fingerprint)) { "Invalid plan fingerprint." }
        spec.exercises.forEach { exercise ->
            require(runCatching { InputRules.exerciseName(exercise.name) }.isSuccess) { "Check exercise name ${exercise.name.take(100)}." }
            require(exercise.sets in (if (exercise.targets == null) 1 else 0)..MAX_SETS && exercise.setTargets.size <= MAX_SETS) { "Use at most $MAX_SETS target sets for ${exercise.name}." }
            exercise.setTargets.forEach { validateSet(it, exercise.name) }
            text(exercise.note, MAX_NOTE, "Note for ${exercise.name}")
        }
    }
    fun validateSet(target: PlanSetTarget, name: String = "exercise") {
        require((target.repsMin == null && target.repsMax == null) ||
            (target.repsMin != null && target.repsMax != null && target.repsMin in 1..MAX_REPS && target.repsMax in target.repsMin..MAX_REPS)) { "Check rep minimum/maximum for $name." }
        require(target.weight == parseWeight(when (target.weight.unit) { null -> ""; "bodyweight" -> "bodyweight"; else -> "${target.weight.value} ${target.weight.unit}" })) { "Check Weight for $name." }
        require(target.restSeconds == null || target.restSeconds in 0..3600) { "Rest must be 0–3,600 whole seconds for $name." }
    }

}

fun normaliseExerciseName(raw: String) = raw.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
enum class ExerciseMatchKind { EXACT, ALIAS, AMBIGUOUS, UNKNOWN, INCOMPATIBLE }
data class PlanExerciseMatch(val kind: ExerciseMatchKind, val candidates: List<Exercise>)

// The existing catalogue has no saved alias table. Only explicit aliases passed
// by a caller are eligible; abbreviations and movement variants are never guessed.
fun matchPlanExercise(name: String, catalogue: List<Exercise>, incompatible: Set<Long> = emptySet(), aliases: Map<String, Set<Long>> = emptyMap()): PlanExerciseMatch {
    val key = normaliseExerciseName(name)
    val exact = catalogue.filter { normaliseExerciseName(it.name) == key }
    val candidates = if (exact.isNotEmpty()) exact else catalogue.filter { it.id in aliases.filterKeys { normaliseExerciseName(it) == key }.values.flatten() }
    val kind = when {
        candidates.isEmpty() -> ExerciseMatchKind.UNKNOWN
        candidates.size > 1 -> ExerciseMatchKind.AMBIGUOUS
        candidates.single().id in incompatible -> ExerciseMatchKind.INCOMPATIBLE
        exact.isNotEmpty() -> ExerciseMatchKind.EXACT
        else -> ExerciseMatchKind.ALIAS
    }
    return PlanExerciseMatch(kind, candidates)
}
