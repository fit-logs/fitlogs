package com.example.fitnotes.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ImportIssue(val row: String, val reason: String)
data class WorkoutSourceRow(
    val recordId: String, val order: Int, val date: String, val exercise: String, val category: String,
    val weight: String, val reps: String, val distance: String, val time: String,
    val distanceCode: String, val comment: String, val rawValues: String, val native: Boolean,
)
data class BodySourceRow(
    val recordId: String, val date: String, val time: String, val name: String, val value: Double,
    val unit: String, val unitName: String, val comment: String, val rawValues: String,
)
data class HistoryDocument(
    val id: String, val source: String, val fileName: String,
    val rows: List<WorkoutSourceRow>, val body: List<BodySourceRow>,
    val timers: Map<String, Pair<String, String>> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val issues: List<ImportIssue> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val metadataJson: String = "{}",
) {
    val distanceCodes get() = rows.filter { it.distance.isNotBlank() }.map { it.distanceCode }.distinct()
    val hasWeight get() = rows.any { it.weight.isNotBlank() }
    val hasAmbiguousWeight get() = rows.any { !it.native && it.weight.isNotBlank() }
    val ambiguousDistanceCodes get() = distanceCodes.filter { code ->
        rows.any { !it.native && it.distanceCode == code } || code !in HistoryImportReader.nativeDistanceUnits
    }
    val hasCsvTime get() = rows.any { !it.native && it.time.isNotBlank() }
}
data class ImportOptions(
    val weightUnit: String? = null,
    val distanceUnits: Map<String, String> = emptyMap(),
    val csvTimeFormat: String? = null,
)
data class ImportPlan(
    val document: HistoryDocument,
    val options: ImportOptions,
    val workouts: List<ImportedWorkout>,
    val sets: List<ImportedSet>,
    val body: List<BodyMeasurement>,
    val issues: List<ImportIssue>,
    val requiredChoices: List<String>,
) {
    val dates get() = (workouts.map { it.date } + body.map { it.date }).sorted()
    val cardioCount get() = sets.count { it.distance != null || it.durationSeconds != null }
    val zeroRepCount get() = sets.count { it.reps == 0 }
    val units get() = buildList {
        if (document.hasWeight) add("Weight: " + if (document.hasAmbiguousWeight) (options.weightUnit ?: "unconfirmed") else "kg (native metric_weight)")
        sets.mapNotNull { it.distanceUnit }.distinct().forEach { add("Distance: " + it) }
        if (document.hasCsvTime) add("Time: " + options.csvTimeFormat)
        if (sets.any { it.durationSeconds != null && document.source.contains("backup") }) add("Native duration_seconds: seconds")
        body.map { it.unitName.ifBlank { "unitless" } }.distinct().forEach { add("Body: " + it) }
    }.joinToString("; ")
}

object HistoryImportReader {
    const val MAX_BYTES = 20 * 1024 * 1024
    // Verified from the installed original app's DistanceUnit enum and TrainingLog:
    // persistent IDs differ from Body Tracker's MeasurementUnit IDs.
    val nativeDistanceUnits = mapOf("0" to "m", "2" to "m", "3" to "km", "4" to "ft", "5" to "mi")
    private val unsignedDecimal = Regex("[0-9]+(\\.[0-9]+)?")
    private val wholeNumber = Regex("[0-9]+")
    private val expectedWorkoutHeaders = listOf("Date", "Exercise", "Category", "Weight", "Reps", "Distance", "Time", "Comment")

    fun workoutCsv(text: String, fileName: String): HistoryDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "CSV exceeds 20 MB." }
        val table = CsvTable.read(text.removePrefix("\uFEFF"))
        require(table.isNotEmpty()) { "CSV is empty." }
        require(table.first().fields == expectedWorkoutHeaders) {
            "Unsupported Workout CSV columns. Expected: " + expectedWorkoutHeaders.joinToString(", ")
        }
        val issues = mutableListOf<ImportIssue>()
        val records = table.drop(1).mapIndexedNotNull { index, row ->
            if (row.fields.size != 8) {
                issues.add(ImportIssue("CSV line " + row.line, "Expected 8 columns; found " + row.fields.size + ". Check quoting around comments."))
                null
            } else if (row.fields.all { it.isBlank() }) {
                issues.add(ImportIssue("CSV line " + row.line, "Empty row."))
                null
            } else {
                val f = row.fields
                WorkoutSourceRow(row.line.toString(), index, f[0], f[1], f[2], f[3], f[4], f[5], f[6], "CSV",
                    f[7], JSONObject().also { json -> expectedWorkoutHeaders.forEachIndexed { i, key -> json.put(key, f[i]) } }.toString(), false)
            }
        }
        // Hash parsed fields, including all repeated occurrences. Renaming the file,
        // BOM, and record line endings do not change its import identity.
        val canonical = table.joinToString("") { row -> row.fields.joinToString("") { it.length.toString() + ":" + it } + ";" }
        return HistoryDocument(digest("workout-csv\n" + canonical), "Original FitNotes Workout CSV", fileName, records, emptyList(), issues = issues)
    }

    fun nativeBackup(file: File, fileName: String): HistoryDocument {
        require(file.length() <= MAX_BYTES) { "Backup exceeds 20 MB." }
        require(file.inputStream().use { input -> ByteArray(16).also { require(input.read(it) == 16) } }
            .contentEquals("SQLite format 3\u0000".toByteArray(Charsets.UTF_8))) { "Not a native FitNotes SQLite backup." }
        val id = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            require(db.version == 22) { "Unsupported native backup version " + db.version + "; verified format is 22." }
            db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "SQLite backup failed its integrity check." } }
            val required = setOf("exercise", "training_log", "Category", "Measurement", "MeasurementUnit", "MeasurementRecord", "Comment", "WorkoutComment", "WorkoutTime")
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            require(tables.containsAll(required)) { "Native backup is missing required history tables." }
            val metadata = JSONObject()
            fun read(table: String): List<JSONObject> {
                require(table in tables)
                return db.rawQuery("SELECT * FROM \"" + table + "\" ORDER BY _id", null).use { cursor ->
                    val values = mutableListOf<JSONObject>()
                    while (cursor.moveToNext()) {
                        require(values.size < 200000) { "Too many records in " + table + "." }
                        values.add(cursor.json())
                    }
                    values
                }
            }
            val exercises = read("exercise").associateBy { it.getLong("_id") }
            val categories = read("Category").associateBy { it.getLong("_id") }
            val comments = read("Comment")
            val workoutComments = read("WorkoutComment")
            val measurements = read("Measurement").associateBy { it.getLong("_id") }
            val units = read("MeasurementUnit").associateBy { it.getLong("_id") }
            val timerRows = read("WorkoutTime")
            metadata.put("exerciseDefinitions", JSONArray(exercises.values.toList()))
            metadata.put("measurementDefinitions", JSONArray(measurements.values.toList()))
            metadata.put("measurementUnits", JSONArray(units.values.toList()))
            metadata.put("sourceComments", JSONArray(comments))
            metadata.put("workoutTimers", JSONArray(timerRows))
            if ("WorkoutGroup" in tables) metadata.put("workoutGroups", JSONArray(read("WorkoutGroup")))
            val unsupported = mutableListOf<String>()
            val issues = mutableListOf<ImportIssue>()
            val matchedComments = mutableSetOf<Long>()
            val rows = read("training_log").mapIndexedNotNull { index, row ->
                try {
                    val recordId = row.getLong("_id")
                    val exercise = requireNotNull(exercises[row.getLong("exercise_id")]) { "Unknown exercise ID." }
                    val type = exercise.getInt("exercise_type_id")
                    require(type == 0 || type == 1) { "Unsupported exercise type " + type + "." }
                    val date = row.getString("date")
                    val attached = comments.filter {
                        it.getInt("owner_type_id") == 1 && it.getLong("owner_id") == recordId && it.getString("date").take(10) == date
                    }
                    attached.forEach { matchedComments.add(it.getLong("_id")) }
                    val raw = row.put("comments", JSONArray(attached))
                    val distance = row.getDouble("distance")
                    val seconds = row.getDouble("duration_seconds")
                    WorkoutSourceRow(recordId.toString(), index, date, exercise.getString("name"),
                        categories[exercise.getLong("category_id")]?.getString("name") ?: "",
                        if (type == 0) row.get("metric_weight").toString() else "",
                        if (type == 0) row.get("reps").toString() else "",
                        if (distance != 0.0) row.get("distance").toString() else "",
                        if (seconds != 0.0) row.get("duration_seconds").toString() else "",
                        row.get("unit").toString(), attached.joinToString("\n") { it.getString("comment") }, raw.toString(), true)
                } catch (error: Exception) {
                    issues.add(ImportIssue("training_log " + row.opt("_id"), error.message ?: "Invalid source record."))
                    null
                }
            }
            val unmatched = comments.count { it.getLong("_id") !in matchedComments }
            if (unmatched > 0) unsupported.add(unmatched.toString() + " unlinked comments retained in source metadata.")
            val body = read("MeasurementRecord").mapNotNull { row ->
                try {
                    val measurement = requireNotNull(measurements[row.getLong("measurement_id")]) { "Unknown measurement definition." }
                    val unit = requireNotNull(units[measurement.getLong("unit_id")]) { "Unknown measurement unit." }
                    BodySourceRow(row.get("_id").toString(), row.getString("date"), row.getString("time"),
                        measurement.getString("name"), row.getDouble("value"), unit.getString("short_name"),
                        unit.getString("long_name"), if (row.isNull("comment")) "" else row.getString("comment"), row.toString())
                } catch (error: Exception) {
                    issues.add(ImportIssue("MeasurementRecord " + row.opt("_id"), error.message ?: "Invalid measurement."))
                    null
                }
            }
            val timers = mutableMapOf<String, Pair<String, String>>()
            timerRows.forEach { row ->
                val date = row.getString("workout_date")
                val start = row.getString("start_date_time")
                val end = row.getString("end_date_time")
                if (validHistoryDate(date) && validRecordedTimestamp(start) && validRecordedTimestamp(end) && date !in timers) timers[date] = start to end
                else issues.add(ImportIssue("WorkoutTime " + row.getLong("_id"), "Invalid or duplicate timer; date is retained without inventing a timer."))
            }
            val notes = workoutComments.groupBy { it.getString("date") }.mapValues { (_, values) ->
                values.joinToString("\n") { it.getString("comment") }
            }
            metadata.put("workoutComments", JSONArray(workoutComments))
            listOf("Routine", "RoutineSection", "RoutineSectionExercise", "RoutineSectionExerciseSet", "Goal", "Barbell", "Plate", "ExerciseGraphFavourite", "RepMaxGridFavourite").forEach { table ->
                if (table in tables) db.rawQuery("SELECT COUNT(*) FROM \"" + table + "\"", null).use { cursor ->
                    cursor.moveToFirst()
                    if (cursor.getInt(0) > 0) unsupported.add(table + ": " + cursor.getInt(0) + " configuration records not imported.")
                }
            }
            return HistoryDocument(id, "Original FitNotes native backup", fileName, rows, body, timers, notes, issues, unsupported, metadata.toString())
        }
    }

    fun plan(document: HistoryDocument, options: ImportOptions): ImportPlan {
        val issues = document.issues.toMutableList()
        val required = mutableListOf<String>()
        if (document.hasAmbiguousWeight && options.weightUnit !in setOf("kg", "lb")) required.add("Confirm workout weight units.")
        document.ambiguousDistanceCodes.forEach { code ->
            if (options.distanceUnits[code] !in setOf("m", "km", "ft", "mi")) required.add("Confirm distance unit for " + code + ".")
        }
        if (document.hasCsvTime && options.csvTimeFormat !in setOf("seconds", "minutes", "h:m:s", "m:s", "h:m")) required.add("Confirm CSV time format.")
        val sets = document.rows.mapNotNull { row ->
            try {
                require(validHistoryDate(row.date)) { "Invalid date; expected YYYY-MM-DD." }
                require(row.exercise.isNotBlank() && row.exercise.length <= 500) { "Missing or overlong exercise name." }
                require(row.weight.isNotBlank() == row.reps.isNotBlank()) { "Weight and reps must both be present for a strength record." }
                val weight = row.weight.takeIf { it.isNotBlank() }?.let { number(it, "weight") }
                val reps = row.reps.takeIf { it.isNotBlank() }?.let {
                    require(wholeNumber.matches(it.trim())) { "Reps must be a whole number." }
                    val value = requireNotNull(it.trim().toIntOrNull()) { "Reps are out of range." }
                    require(value in (if (row.native) 0 else 1)..10000) { "Invalid reps." }
                    value
                }
                val distance = row.distance.takeIf { it.isNotBlank() }?.let { number(it, "distance") }
                val seconds = row.time.takeIf { it.isNotBlank() }?.let {
                    if (row.native) number(it, "duration_seconds") else parseCsvTime(it, options.csvTimeFormat)
                }
                require(weight != null || distance != null || seconds != null) { "Record has no weight/reps, distance, or time." }
                val workoutId = document.id + ":" + row.date
                ImportedSet(document.id + ":set:" + row.recordId, workoutId, row.recordId, row.order, row.exercise, row.category,
                    weight?.times(if (!row.native && options.weightUnit == "lb") 0.45359237 else 1.0), reps, distance,
                    if (distance == null) null else (if (row.native) nativeDistanceUnits[row.distanceCode] else null)
                        ?: options.distanceUnits[row.distanceCode] ?: "unconfirmed",
                    seconds, row.comment, row.rawValues)
            } catch (error: Exception) {
                issues.add(ImportIssue(if (row.native) "training_log " + row.recordId else "CSV line " + row.recordId, error.message ?: "Invalid row."))
                null
            }
        }
        val dates = sets.map { it.workoutId.substringAfterLast(':') }.distinct()
        val workouts = dates.map { date ->
            val timer = document.timers[date]
            ImportedWorkout(document.id + ":" + date, document.id, date, timer?.first, timer?.second, document.notes[date] ?: "")
        }
        val body = document.body.mapNotNull { row ->
            try {
                require(validHistoryDate(row.date) && validHistoryTime(row.time)) { "Invalid measurement date or time." }
                require(row.name.isNotBlank() && row.name.length <= 500 && row.value.isFinite()) { "Invalid measurement name or value." }
                BodyMeasurement(document.id + ":body:" + row.recordId, document.id, row.recordId, row.date, row.time,
                    row.name, row.value, row.unit, row.unitName, row.comment, row.rawValues)
            } catch (error: Exception) {
                issues.add(ImportIssue("MeasurementRecord " + row.recordId, error.message ?: "Invalid measurement."))
                null
            }
        }
        return ImportPlan(document, options, workouts, sets, body, issues, required)
    }

    private fun number(raw: String, label: String): Double {
        require(unsignedDecimal.matches(raw.trim())) { "Invalid numeric " + label + "." }
        val value = requireNotNull(raw.trim().toDoubleOrNull()) { "Invalid " + label + "." }
        require(value.isFinite() && value >= 0) { "Invalid " + label + "." }
        return value
    }
    private fun parseCsvTime(raw: String, format: String?): Double {
        // Counts are available before a unit choice; final import remains disabled.
        if (format == null) return 0.0
        if (format == "seconds") return number(raw, "time")
        if (format == "minutes") return number(raw, "time") * 60
        val pieces = raw.split(':')
        val count = if (format == "h:m:s") 3 else 2
        require(pieces.size == count) { "Time does not match selected format " + format + "." }
        val values = pieces.map { number(it, "time") }
        require(values.drop(1).all { it < 60 }) { "Clock minutes/seconds must be below 60." }
        require(values.dropLast(1).all { it % 1 == 0.0 }) { "Clock hours/minutes must be whole numbers." }
        return when (format) {
            "h:m:s" -> values[0] * 3600 + values[1] * 60 + values[2]
            "m:s" -> values[0] * 60 + values[1]
            else -> values[0] * 3600 + values[1] * 60
        }
    }
    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun Cursor.json(): JSONObject = JSONObject().also { json ->
    columnNames.forEachIndexed { index, name ->
        json.put(name, when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_STRING -> getString(index)
            else -> throw IllegalArgumentException("Unexpected binary field in source history.")
        })
    }
}

internal data class CsvRow(val line: Int, val fields: List<String>)
internal object CsvTable {
    fun read(text: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var line = 1
        var rowLine = 1
        var i = 0
        fun endField() { fields.add(field.toString()); field.clear(); closedQuote = false }
        fun endRow() { endField(); rows.add(CsvRow(rowLine, fields.toList())); fields.clear(); require(rows.size <= 200000) { "Too many CSV rows." } }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; closedQuote = true }
                } else { field.append(c); if (c == '\n') line++ }
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closedQuote) { "Malformed quote on CSV line " + line + "." }; quoted = true }
                ',' -> endField()
                '\r', '\n' -> {
                    if (c == '\r' && i < text.length && text[i] == '\n') i++
                    endRow(); line++; rowLine = line
                }
                else -> { require(!closedQuote) { "Unexpected text after quote on CSV line " + line + "." }; field.append(c) }
            }
        }
        require(!quoted) { "Unclosed quoted CSV field on line " + rowLine + "." }
        if (field.isNotEmpty() || fields.isNotEmpty() || closedQuote) endRow()
        return rows
    }
}
