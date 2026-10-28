package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject

/** Targets only. Intervals remain editable guidance rather than invented GPS samples. */
data class RunningPrescription(val distanceKm: Double? = null, val durationMinutes: Int? = null,
    val intensity: String = "", val pace: String = "", val intervals: String = "", val warmUp: String = "", val coolDown: String = "") {
    fun json(): JSONObject = JSONObject().put("distanceKm", distanceKm ?: JSONObject.NULL)
        .put("durationMinutes", durationMinutes ?: JSONObject.NULL).put("intensity", intensity).put("pace", pace)
        .put("intervals", intervals).put("warmUp", warmUp).put("coolDown", coolDown)
    val summary get() = listOfNotNull(distanceKm?.let { "${weightText(it)} km target" }, durationMinutes?.let { "$it min target" },
        intensity.takeIf { it.isNotBlank() }, pace.takeIf { it.isNotBlank() }?.let { "Pace guidance: $it" },
        warmUp.takeIf { it.isNotBlank() }?.let { "Warm-up: $it" }, intervals.takeIf { it.isNotBlank() },
        coolDown.takeIf { it.isNotBlank() }?.let { "Cool-down: $it" }).joinToString(" · ")
    companion object {
        val fields = setOf("distanceKm", "durationMinutes", "intensity", "pace", "intervals", "warmUp", "coolDown")
        fun parse(o: JSONObject): RunningPrescription {
            require(o.keys().asSequence().all { it in fields }) { "Use distanceKm, durationMinutes, intensity, pace, intervals, warmUp or coolDown for running guidance." }
            fun text(key: String): String {
                if (!o.has(key) || o.isNull(key)) return ""
                val v = o.get(key)
                require(v is String && v.length <= 2000 && v.none { it.isISOControl() && it !in "\n\r\t" }) { "$key must be readable text (up to 2,000 characters). Describe intervals as text." }
                return v.trim()
            }
            val distance = if (!o.has("distanceKm") || o.isNull("distanceKm")) null else o.get("distanceKm").also {
                require(it is Number && it.toDouble().isFinite() && it.toDouble() > 0 && it.toDouble() <= 1000) { "distanceKm must be a positive number up to 1,000." }
            }.let { (it as Number).toDouble() }
            val duration = if (!o.has("durationMinutes") || o.isNull("durationMinutes")) null else o.get("durationMinutes").also {
                require((it is Int || it is Long) && (it as Number).toLong() in 1..720) { "durationMinutes must be a whole number from 1 to 720." }
            }.let { (it as Number).toInt() }
            return RunningPrescription(distance, duration, text("intensity"), text("pace"), text("intervals"), text("warmUp"), text("coolDown"))
        }
    }
}

sealed interface CoachResponse {
    data class Advice(val text: String): CoachResponse
    data class Plan(val definition: PortableProgram): CoachResponse
    companion object {
        const val CONTRACT = """Return one JSON object in a json code fence, with a normal explanation outside it. For a program use {"title":"My training","sessions":[...]}; for one workout use a single session object. Keep sessions in intended queue order; dates are optional YYYY-MM-DD guidance, never fixed weekdays. Default to one non-repeating sequence.
Strength session: {"title":"Upper body","type":"strength","durationMinutes":45,"exercises":[{"name":"Bench Press","sets":3,"reps":"8-10","restSeconds":90}]}. Use familiar exercise names exactly; do not guess equivalents. sets can instead be an array of individual targets with repsMin/repsMax (integers or both null), optional weight and unit kg/lb, restSeconds, loadMode, isWarmUp, rir/rpe. Omitted weights are unspecified, never zero. Bodyweight unit has no numeric weight. Exercise and session notes are optional. Weights will be reviewed using local history; omit personalized weights when unsure.
Running session: {"title":"Easy Run","type":"running","distanceKm":5,"durationMinutes":30,"intensity":"easy","pace":"conversational effort","notes":"Optional guidance","warmUp":"5 min easy","intervals":"6 x 400 m, 200 m easy recovery","coolDown":"5 min easy"}. All running targets are optional; describe intervals and pace as text. Do not calculate pace from sparse or missing history. Distinguish moving and elapsed pace when discussing actual runs.
For a progress review or ordinary advice, return readable prose without plan JSON. Suggested targets never represent actual training."""

        fun parse(raw: String): CoachResponse {
            require(raw.toByteArray(Charsets.UTF_8).size <= PortableProgramParser.MAX_INPUT) { "Response exceeds 256 KB. Paste a smaller response." }
            require(raw.isNotBlank()) { "Paste a response or enter text first." }
            val bodies = objects(raw)
            if (bodies.isEmpty()) {
                require(!raw.contains("```json", true) && !raw.trimStart().startsWith("[")) { "Expected one complete JSON object. Check braces and the code fence." }
                return Advice(raw.trim())
            }
            require(bodies.size == 1) { "Return one JSON object with all sessions in its sessions array." }
            val body = bodies.single(); StrictJson(body).check()
            val root = JSONObject(body)
            if (root.keys().asSequence().toSet() == setOf("advice")) {
                require(root.get("advice") is String) { "advice must be text." }; return Advice(root.getString("advice"))
            }
            // Existing whole-program definitions still use the same parser/editor.
            if (root.has("days") || root.has("workouts")) return Plan(PortableProgramParser.parse(body))
            val sessions = if (root.has("sessions")) {
                keys(root, setOf("title", "sessions", "notes", "weeks"), "Program")
                root.optJSONArray("sessions") ?: throw IllegalArgumentException("sessions must be an array.")
            } else JSONArray().put(root)
            require(sessions.length() in 1..14) { "Include 1–14 sessions in one program." }
            val days = JSONArray()
            for (i in 0 until sessions.length()) try {
                val session = sessions.getJSONObject(i)
                keys(session, setOf("title", "type", "date", "durationMinutes", "notes", "note", "exercises") + RunningPrescription.fields, "Session")
                require(!(session.has("notes") && session.has("note"))) { "Use notes or note, not both." }
                if(session.has("durationMinutes")) {
                    val duration=session.get("durationMinutes")
                    require((duration is Int || duration is Long) && (duration as Number).toLong() in 1..720) { "durationMinutes must be a whole number from 1 to 720; omit unknown duration." }
                }
                val type = session.opt("type") ?: if (session.has("exercises")) "strength" else throw IllegalArgumentException("Specify type strength or running.")
                require(type in setOf("strength", "running")) { "type must be strength or running." }
                val d = JSONObject().put("name", session.get("title")).put("type", type).put("notes", session.opt("notes") ?: session.opt("note") ?: "")
                if (session.has("date")) d.put("date", session.get("date"))
                if (session.has("durationMinutes")) d.put("durationMinutes", session.get("durationMinutes"))
                if (type == "running") {
                    require(!session.has("exercises")) { "Running sessions cannot contain strength exercises. Split them into separate sessions." }
                    val run = JSONObject(); RunningPrescription.fields.forEach { if (session.has(it)) run.put(it, session.get(it)) }
                    d.put("running", RunningPrescription.parse(run).json()).put("exercises", JSONArray())
                } else {
                    require((session.keys().asSequence().toSet() intersect (RunningPrescription.fields - "durationMinutes" - "warmUp")).isEmpty()) { "Put running targets in a separate running session." }
                    if(session.has("warmUp")) { require(session.get("warmUp") is String); d.put("warmUp",session.get("warmUp")) }
                    val exercises = session.getJSONArray("exercises")
                    for (j in 0 until exercises.length()) {
                        val e = exercises.getJSONObject(j)
                        keys(e, setOf("name", "sets", "reps", "repsMin", "repsMax", "weight", "unit", "restSeconds", "loadMode", "isWarmUp", "notes", "note", "rir", "rpe"), "Exercise ${j+1}")
                        require(!(e.has("reps") && (e.has("repsMin") || e.has("repsMax")))) { "Use reps or repsMin/repsMax, not both." }
                        require(!(e.has("notes") && e.has("note"))) { "Exercise ${j+1}: use notes or note, not both." }
                        require(e.has("sets")) { "Exercise ${j+1}: supply sets as a count or target array." }
                        e.optJSONArray("sets")?.let { sets -> for(k in 0 until sets.length()) {
                            val set = sets.getJSONObject(k)
                            keys(set, setOf("reps", "repsMin", "repsMax", "weight", "unit", "restSeconds", "loadMode", "isWarmUp", "rir", "rpe"), "Set ${k+1}")
                            require(!(set.has("reps") && (set.has("repsMin") || set.has("repsMax")))) { "Set ${k+1}: use one rep format." }
                        } }
                    }
                    d.put("exercises", exercises)
                }
                days.put(d)
            } catch (e: Exception) { throw IllegalArgumentException("Session ${i+1}: ${e.message ?: "Check fields."}") }
            val definition = JSONObject().put("name", if(root.has("sessions"))root.get("title") else root.get("title"))
                .put("description", if(root.has("sessions"))root.opt("notes") ?: "" else "")
                .put("weeks", if(root.has("sessions"))root.opt("weeks") ?: 1 else 1).put("days", days)
            return Plan(PortableProgramParser.parse(definition.toString()))
        }
        private fun keys(o: JSONObject, allowed: Set<String>, label: String) {
            val unknown = o.keys().asSequence().toSet() - allowed
            require(unknown.isEmpty()) { "$label: unsupported fields ${unknown.joinToString()}. Check spelling; keep guidance in notes." }
        }
        private fun objects(raw: String): List<String> {
            val result=mutableListOf<String>();var start=-1;var depth=0;var quoted=false;var escape=false
            raw.forEachIndexed { i,c ->
                if(start<0) { if(c=='{') { start=i;depth=1;quoted=false;escape=false } }
                else if(quoted) { if(escape)escape=false else if(c=='\\')escape=true else if(c=='"')quoted=false }
                else when(c) { '"'->quoted=true; '{'->depth++; '}'->{depth--;if(depth==0){result.add(raw.substring(start,i+1));start=-1}} }
            }
            require(start<0) { "JSON object is incomplete. Check closing braces and quoted strings." }
            return result
        }
    }
}
