package com.example.fitnotes.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Backup(
    val version: Int = 4,
    val exportedAt: Long = System.currentTimeMillis(),
    val exercises: List<Exercise>,
    val workouts: List<Workout>,
    val entries: List<WorkoutExercise>,
    val sets: List<WorkoutSet>,
    val imports: List<HistoryImport> = emptyList(),
    val importedWorkouts: List<ImportedWorkout> = emptyList(),
    val importedSets: List<ImportedSet> = emptyList(),
    val bodyMeasurements: List<BodyMeasurement> = emptyList(),
    val healthSessions: List<HealthSession> = emptyList(),
    val healthSamples: List<HealthSample> = emptyList(),
)

object BackupCodec {
    const val MAX_BYTES = 50 * 1024 * 1024

    fun encode(backup: Backup): String {
        validate(backup)
        val root = JSONObject().put("format", "fitnotes").put("version", backup.version).put("exportedAt", backup.exportedAt)
        root.put("exercises", JSONArray().also { array ->
            backup.exercises.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("nameKey", it.nameKey)) }
        })
        root.put("workouts", JSONArray().also { array ->
            backup.workouts.forEach {
                array.put(JSONObject().put("id", it.id).put("startedAt", it.startedAt)
                    .put("completedAt", it.completedAt ?: JSONObject.NULL).put("title", it.title).put("notes", it.notes).also { record ->
                        if (backup.version >= 4) record.put("activityType", it.activityType).put("activityLabel", it.activityLabel)
                            .put("sessionDate", it.sessionDate).put("finished", it.finished).put("durationSeconds", it.durationSeconds ?: JSONObject.NULL)
                            .put("effort", it.effort ?: JSONObject.NULL).put("distance", it.distance ?: JSONObject.NULL).put("distanceUnit", it.distanceUnit ?: JSONObject.NULL)
                    })
            }
        })
        root.put("entries", JSONArray().also { array ->
            backup.entries.forEach {
                array.put(JSONObject().put("id", it.id).put("workoutId", it.workoutId).put("exerciseId", it.exerciseId).put("position", it.position))
            }
        })
        root.put("sets", JSONArray().also { array ->
            backup.sets.forEach {
                val record = JSONObject().put("id", it.id).put("entryId", it.entryId).put("weightKg", it.weightKg).put("reps", it.reps).put("position", it.position)
                if (backup.version >= 3) record.put("distance", it.distance ?: JSONObject.NULL).put("distanceUnit", it.distanceUnit ?: JSONObject.NULL)
                    .put("durationSeconds", it.durationSeconds ?: JSONObject.NULL).put("comment", it.comment)
                array.put(record)
            }
        })
        if (backup.version >= 2) {
            root.put("imports", JSONArray().also { array -> backup.imports.forEach {
                array.put(JSONObject().put("id", it.id).put("source", it.source).put("fileName", it.fileName)
                    .put("importedAt", it.importedAt).put("units", it.units).put("skippedRows", it.skippedRows)
                    .put("unsupported", it.unsupported).put("metadataJson", it.metadataJson))
            } })
            root.put("importedWorkouts", JSONArray().also { array -> backup.importedWorkouts.forEach {
                array.put(JSONObject().put("id", it.id).put("importId", it.importId).put("date", it.date)
                    .put("recordedStart", it.recordedStart ?: JSONObject.NULL).put("recordedEnd", it.recordedEnd ?: JSONObject.NULL).put("notes", it.notes))
            } })
            root.put("importedSets", JSONArray().also { array -> backup.importedSets.forEach {
                array.put(JSONObject().put("id", it.id).put("workoutId", it.workoutId).put("sourceRecordId", it.sourceRecordId)
                    .put("sourceOrder", it.sourceOrder).put("exercise", it.exercise).put("category", it.category)
                    .put("weightKg", it.weightKg ?: JSONObject.NULL).put("reps", it.reps ?: JSONObject.NULL)
                    .put("distance", it.distance ?: JSONObject.NULL).put("distanceUnit", it.distanceUnit ?: JSONObject.NULL)
                    .put("durationSeconds", it.durationSeconds ?: JSONObject.NULL).put("comment", it.comment).put("rawValues", it.rawValues))
            } })
            root.put("bodyMeasurements", JSONArray().also { array -> backup.bodyMeasurements.forEach {
                array.put(JSONObject().put("id", it.id).put("importId", it.importId).put("sourceRecordId", it.sourceRecordId)
                    .put("date", it.date).put("time", it.time).put("name", it.name).put("value", it.value)
                    .put("unit", it.unit).put("unitName", it.unitName).put("comment", it.comment).put("rawValues", it.rawValues))
            } })
        }
        if (backup.version >= 4) {
            root.put("healthSessions", JSONArray().also { array -> backup.healthSessions.forEach { array.put(HealthBackupJson.encode(it)) } })
            root.put("healthSamples", JSONArray().also { array -> backup.healthSamples.forEach { array.put(HealthBackupJson.encode(it)) } })
        }
        return root.toString(2).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 50 MB limit." } }
    }

    fun decode(text: String): Backup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 50 MB limit." }
        try {
            StrictJson(text).check()
            val root = JSONObject(text)
            require(root.string("format") == "fitnotes") { "This is not a FitNotes backup." }
            val version = root.integer("version")
            require(version in 1..4) { "Unsupported backup version." }
            val keys = arrayOf("format", "version", "exportedAt", "exercises", "workouts", "entries", "sets")
            if (version == 1) root.keysExactly(*keys)
            else if (version < 4) root.keysExactly(*keys, "imports", "importedWorkouts", "importedSets", "bodyMeasurements")
            else root.keysExactly(*keys, "imports", "importedWorkouts", "importedSets", "bodyMeasurements", "healthSessions", "healthSamples")
            val backup = Backup(
                version = root.integer("version"),
                exportedAt = root.long("exportedAt"),
                exercises = root.objects("exercises", 20000).map {
                    it.keysExactly("id", "name", "nameKey")
                    Exercise(it.long("id"), it.string("name"), it.string("nameKey"))
                },
                workouts = root.objects("workouts", 100000).map {
                    if (version < 4) it.keysExactly("id", "startedAt", "completedAt", "title", "notes")
                    else it.keysExactly("id", "startedAt", "completedAt", "title", "notes", "activityType", "activityLabel", "sessionDate", "finished", "durationSeconds", "effort", "distance", "distanceUnit")
                    Workout(it.long("id"), it.long("startedAt"), if (it.isNull("completedAt")) null else it.long("completedAt"), it.string("title"), it.string("notes"),
                        if (version < 4) "strength" else it.string("activityType"), if (version < 4) "" else it.string("activityLabel"),
                        if (version < 4) "" else it.string("sessionDate"), if (version < 4) false else it.get("finished").also { value -> require(value is Boolean) }.let { value -> value as Boolean },
                        if (version < 4) null else it.optionalNumber("durationSeconds"), if (version < 4 || it.isNull("effort")) null else it.string("effort"),
                        if (version < 4) null else it.optionalNumber("distance"), if (version < 4 || it.isNull("distanceUnit")) null else it.string("distanceUnit"))
                },
                entries = root.objects("entries", 200000).map {
                    it.keysExactly("id", "workoutId", "exerciseId", "position")
                    WorkoutExercise(it.long("id"), it.long("workoutId"), it.long("exerciseId"), it.integer("position"))
                },
                sets = root.objects("sets", 1000000).map {
                    if (version < 3) it.keysExactly("id", "entryId", "weightKg", "reps", "position")
                    else it.keysExactly("id", "entryId", "weightKg", "reps", "position", "distance", "distanceUnit", "durationSeconds", "comment")
                    val number = it.get("weightKg")
                    require(number is Number) { "Weight must be numeric." }
                    WorkoutSet(it.long("id"), it.long("entryId"), number.toDouble(), it.integer("reps"), it.integer("position"),
                        if (version < 3) null else it.optionalNumber("distance"),
                        if (version < 3 || it.isNull("distanceUnit")) null else it.string("distanceUnit"),
                        if (version < 3) null else it.optionalNumber("durationSeconds"), if (version < 3) "" else it.string("comment"))
                },
                imports = if (version == 1) emptyList() else root.objects("imports", 10000).map {
                    it.keysExactly("id", "source", "fileName", "importedAt", "units", "skippedRows", "unsupported", "metadataJson")
                    HistoryImport(it.string("id"), it.string("source"), it.string("fileName"), it.long("importedAt"),
                        it.string("units"), it.integer("skippedRows"), it.string("unsupported"), it.string("metadataJson"))
                },
                importedWorkouts = if (version == 1) emptyList() else root.objects("importedWorkouts", 100000).map {
                    it.keysExactly("id", "importId", "date", "recordedStart", "recordedEnd", "notes")
                    ImportedWorkout(it.string("id"), it.string("importId"), it.string("date"),
                        if (it.isNull("recordedStart")) null else it.string("recordedStart"),
                        if (it.isNull("recordedEnd")) null else it.string("recordedEnd"), it.string("notes"))
                },
                importedSets = if (version == 1) emptyList() else root.objects("importedSets", 1000000).map {
                    it.keysExactly("id", "workoutId", "sourceRecordId", "sourceOrder", "exercise", "category", "weightKg", "reps",
                        "distance", "distanceUnit", "durationSeconds", "comment", "rawValues")
                    ImportedSet(it.string("id"), it.string("workoutId"), it.string("sourceRecordId"), it.integer("sourceOrder"),
                        it.string("exercise"), it.string("category"), it.optionalNumber("weightKg"),
                        if (it.isNull("reps")) null else it.integer("reps"), it.optionalNumber("distance"),
                        if (it.isNull("distanceUnit")) null else it.string("distanceUnit"), it.optionalNumber("durationSeconds"),
                        it.string("comment"), it.string("rawValues"))
                },
                bodyMeasurements = if (version == 1) emptyList() else root.objects("bodyMeasurements", 1000000).map {
                    it.keysExactly("id", "importId", "sourceRecordId", "date", "time", "name", "value", "unit", "unitName", "comment", "rawValues")
                    BodyMeasurement(it.string("id"), it.string("importId"), it.string("sourceRecordId"), it.string("date"), it.string("time"),
                        it.string("name"), requireNotNull(it.optionalNumber("value")), it.string("unit"), it.string("unitName"), it.string("comment"), it.string("rawValues"))
                },
                healthSessions = if (version < 4) emptyList() else root.objects("healthSessions", 100000).map(HealthBackupJson::session),
                healthSamples = if (version < 4) emptyList() else root.objects("healthSamples", 1000000).map(HealthBackupJson::sample),
            )
            validate(backup)
            return backup
        } catch (error: Exception) {
            throw IllegalArgumentException(error.message ?: "Malformed backup.", error)
        }
    }

    fun validate(backup: Backup) {
        require(backup.version in 1..4) { "Unsupported backup version." }
        if (backup.version < 3) require(backup.sets.all { it.distance == null && it.durationSeconds == null && it.comment.isEmpty() } &&
            backup.bodyMeasurements.none { it.time.isEmpty() }) { "These records require backup version 3." }
        if (backup.version == 1) require(backup.imports.isEmpty() && backup.importedWorkouts.isEmpty() &&
            backup.importedSets.isEmpty() && backup.bodyMeasurements.isEmpty()) { "Version 1 cannot contain imported history." }
        if (backup.version < 4) require(backup.healthSessions.isEmpty() && backup.healthSamples.isEmpty() && backup.workouts.all {
            it.activityType == "strength" && it.activityLabel.isEmpty() && it.sessionDate.isEmpty() && !it.finished && it.durationSeconds == null && it.effort == null && it.distance == null
        }) { "Activity records require backup version 4." }
        require(backup.exportedAt > 0) { "Invalid export date." }
        fun ids(values: List<Long>): Set<Long> {
            require(values.all { it in 1 until Long.MAX_VALUE } && values.toSet().size == values.size) { "Duplicate or invalid IDs." }
            return values.toSet()
        }
        val exercises = ids(backup.exercises.map { it.id })
        val workouts = ids(backup.workouts.map { it.id })
        val entries = ids(backup.entries.map { it.id })
        ids(backup.sets.map { it.id })
        require(backup.exercises.map { it.nameKey }.toSet().size == backup.exercises.size) { "Duplicate exercise names." }
        backup.exercises.forEach {
            require(InputRules.exerciseName(it.name) == it.name && it.nameKey == it.name.lowercase(Locale.ROOT)) { "Invalid exercise name." }
        }
        require(backup.workouts.count { !it.isComplete } <= 1) { "Backup contains multiple unfinished sessions." }
        backup.workouts.forEach {
            require((it.startedAt > 0 || (it.startedAt == 0L && it.finished && validHistoryDate(it.sessionDate))) && (it.completedAt == null || (it.startedAt > 0 && it.completedAt >= it.startedAt))) { "Invalid session dates." }
            require(ActivityType.entries.any { type -> type.key == it.activityType } && it.activityLabel.length <= 100 &&
                (it.activityType != "other" || it.activityLabel.isNotBlank())) { "Invalid activity type or label." }
            require(it.sessionDate.isEmpty() || validHistoryDate(it.sessionDate)) { "Invalid session date." }
            require(it.durationSeconds == null || (it.durationSeconds.isFinite() && it.durationSeconds >= 0)) { "Invalid duration." }
            require(it.effort in listOf(null, "Easy", "Okay", "Hard")) { "Invalid effort." }
            require((it.distance == null) == (it.distanceUnit == null)) { "Incomplete distance." }
            require(it.distance == null || (it.distance.isFinite() && it.distance >= 0 && it.distanceUnit in listOf("m", "km", "ft", "mi"))) { "Invalid distance." }
            require(it.title.length <= 200 && it.notes.length <= 10000) { "Title or notes are too long." }
        }
        require(backup.entries.map { it.workoutId to it.exerciseId }.toSet().size == backup.entries.size) { "An exercise appears twice in a session." }
        require(backup.entries.map { it.workoutId to it.position }.toSet().size == backup.entries.size) { "Duplicate exercise positions." }
        backup.entries.forEach {
            require(it.workoutId in workouts && it.exerciseId in exercises && it.position in 0 until Int.MAX_VALUE) { "Invalid exercise reference or position." }
        }
        require(backup.sets.map { it.entryId to it.position }.toSet().size == backup.sets.size) { "Duplicate set positions." }
        backup.sets.forEach {
            require(it.entryId in entries && it.position in 0 until Int.MAX_VALUE) { "Invalid set reference or position." }
            require(it.weightKg.isFinite() && it.weightKg in 0.0..InputRules.MAX_WEIGHT) { "Invalid weight." }
            val cardio = it.distance != null || it.durationSeconds != null
            require(it.reps in 1..InputRules.MAX_REPS || (cardio && it.reps == 0 && it.weightKg == 0.0)) { "Invalid reps." }
            require((it.distance == null) == (it.distanceUnit == null)) { "Incomplete distance record." }
            if (it.distance != null) require(it.distance.isFinite() && it.distance >= 0 && it.distanceUnit in listOf("m", "km", "ft", "mi")) { "Invalid distance." }
            if (it.durationSeconds != null) require(it.durationSeconds.isFinite() && it.durationSeconds >= 0) { "Invalid duration." }
            require(it.comment.length <= 10000) { "Set comment is too long." }
        }
        validateHealthBackup(backup.healthSessions, backup.healthSamples, workouts, backup.importedWorkouts.map { it.id }.toSet())
        validateImported(backup.imports, backup.importedWorkouts, backup.importedSets, backup.bodyMeasurements)
    }

    fun validateImported(imports: List<HistoryImport>, workouts: List<ImportedWorkout>, sets: List<ImportedSet>, body: List<BodyMeasurement>) {
        fun ids(values: List<String>): Set<String> {
            require(values.all { it.isNotBlank() && it.length <= 256 } && values.toSet().size == values.size) { "Duplicate or invalid imported IDs." }
            return values.toSet()
        }
        val importIds = ids(imports.map { it.id })
        val workoutIds = ids(workouts.map { it.id })
        ids(sets.map { it.id }); ids(body.map { it.id })
        imports.forEach {
            require(it.importedAt > 0 && it.skippedRows >= 0 && it.source.isNotBlank()) { "Invalid import provenance." }
            StrictJson(it.metadataJson).check()
            require(it.metadataJson.trimStart().startsWith("{")) { "Import metadata must be an object." }
        }
        workouts.forEach {
            require(it.importId in importIds && validHistoryDate(it.date)) { "Invalid imported workout date or source." }
            require((it.recordedStart == null) == (it.recordedEnd == null)) { "Incomplete recorded timer." }
            if (it.recordedStart != null) require(validRecordedTimestamp(it.recordedStart) && validRecordedTimestamp(it.recordedEnd!!)) { "Invalid recorded timer." }
        }
        require(sets.map { it.workoutId to it.sourceOrder }.toSet().size == sets.size) { "Duplicate source positions." }
        sets.forEach {
            require(it.workoutId in workoutIds && it.sourceOrder >= 0 && it.exercise.isNotBlank() && it.exercise.length <= 500) { "Invalid imported record reference or name." }
            require((it.weightKg == null) == (it.reps == null)) { "Incomplete strength record." }
            if (it.weightKg != null) require(it.weightKg.isFinite() && it.weightKg >= 0 && it.reps!! in 0..10000) { "Invalid imported weight/reps." }
            require((it.distance == null) == (it.distanceUnit == null)) { "Incomplete distance record." }
            if (it.distance != null) require(it.distance.isFinite() && it.distance >= 0 && it.distanceUnit in setOf("m", "km", "ft", "mi")) { "Invalid imported distance." }
            if (it.durationSeconds != null) require(it.durationSeconds.isFinite() && it.durationSeconds >= 0) { "Invalid imported duration." }
            require(it.weightKg != null || it.distance != null || it.durationSeconds != null) { "Empty imported record." }
            StrictJson(it.rawValues).check()
        }
        body.forEach {
            require(it.importId in importIds && validHistoryDate(it.date) && (it.time.isEmpty() || validHistoryTime(it.time)) &&
                it.name.isNotBlank() && it.name.length <= 500 && it.value.isFinite()) { "Invalid body measurement." }
            StrictJson(it.rawValues).check()
        }
    }

    fun analysis(backup: Backup): String = JSONObject(encode(backup.copy(version = 4)))
        .put("format", "fitnotes-analysis").put("version", 1).put("backupSchemaVersion", 4).toString(2)

    private fun JSONObject.optionalNumber(key: String): Double? {
        if (isNull(key)) return null
        val value = get(key)
        require(value is Number) { "Expected numeric " + key + "." }
        return value.toDouble()
    }

    private fun JSONObject.keysExactly(vararg names: String) {
        require(keys().asSequence().toSet() == names.toSet()) { "Missing or unexpected backup fields." }
    }
    private fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String) { "Expected text for " + key + "." }
        return value
    }
    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        // Floating-point numbers and numeric strings must never be accepted as IDs.
        require(value is Int || value is Long) { "Expected an integer for " + key + "." }
        return (value as Number).toLong()
    }
    private fun JSONObject.integer(key: String): Int {
        val value = long(key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer out of range." }
        return value.toInt()
    }
    private fun JSONObject.objects(key: String, max: Int): List<JSONObject> {
        val array = getJSONArray(key)
        require(array.length() <= max) { "Too many records in backup." }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}

// Android's JSONObject parser accepts non-JSON syntax. Validate strictly first so
// malformed documents and duplicate keys cannot silently change training data.
private val jsonNumberSyntax = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
private class StrictJson(private val text: String) {
    private var offset = 0
    fun check() {
        value(0)
        whitespace()
        require(offset == text.length) { "Unexpected content after JSON." }
    }
    private fun whitespace() {
        while (offset < text.length && text[offset] in " \n\r\t") offset++
    }
    private fun take(char: Char): Boolean {
        whitespace()
        if (offset < text.length && text[offset] == char) { offset++; return true }
        return false
    }
    private fun expect(char: Char) { require(take(char)) { "Malformed JSON." } }
    private fun value(depth: Int) {
        require(depth <= 32) { "Backup nesting is too deep." }
        whitespace()
        require(offset < text.length) { "Incomplete JSON." }
        when (text[offset]) {
            '{' -> {
                offset++
                val names = mutableSetOf<String>()
                if (take('}')) return
                do {
                    val name = string()
                    require(names.add(name)) { "Duplicate JSON field." }
                    expect(':')
                    value(depth + 1)
                } while (take(','))
                expect('}')
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                expect(']')
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val start = offset
                while (offset < text.length && text[offset] in "-+0123456789.eE") offset++
                require(jsonNumberSyntax.matches(text.substring(start, offset))) {
                    "Invalid JSON number."
                }
            }
        }
    }
    private fun literal(word: String) {
        require(text.startsWith(word, offset)) { "Malformed JSON." }
        offset += word.length
    }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            if (char == '"') return result.toString()
            require(char >= ' ') { "Unescaped control character in JSON." }
            if (char != '\\') result.append(char)
            else {
                require(offset < text.length) { "Incomplete JSON escape." }
                when (val escaped = text[offset++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(offset + 4 <= text.length) { "Incomplete Unicode escape." }
                        val code = text.substring(offset, offset + 4)
                        require(code.all { it in "0123456789abcdefABCDEF" }) { "Invalid Unicode escape." }
                        result.append(code.toInt(16).toChar())
                        offset += 4
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape.")
                }
            }
        }
        throw IllegalArgumentException("Incomplete JSON string.")
    }
}
