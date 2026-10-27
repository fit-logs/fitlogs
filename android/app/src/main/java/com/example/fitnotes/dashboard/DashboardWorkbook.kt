package com.example.fitnotes.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class DashboardSheet(val id: Int, val table: DashboardTable, val hidden: Boolean = false)

/** Native Sheets layout with fixed identities. No formulas or executable user input. */
object DashboardWorkbook {
    const val FORMAT = "fitlogs-dashboard"
    const val META_ID = 299
    val chartIds = setOf(501, 502, 503, 504)

    fun sheets(snapshot: DashboardSnapshotV1): List<DashboardSheet> {
        val sessions = snapshot.table("_sessions")
        val sets = snapshot.table("_sets")
        val activities = snapshot.table("_activities")
        val body = snapshot.table("_measurements")
        val strength = snapshot.table("Strength progress")
        val selected = strength.rows.sortedWith(compareByDescending<List<Any?>> { (it[2] as Number).toInt() }.thenBy { it[0].toString() }).firstOrNull()
        val strengthChart = DashboardTable("_chart_strength", listOf("Date", "Max external (kg)", "Estimated 1RM (kg)"),
            snapshot.table("_strength_trend").rows.filter { it[2] == selected?.get(0) && (it[4] != null || it[5] != null) }
                .map { listOf(it[0], it[4], it[5]) })
        val bodySeries = body.rows.firstOrNull()?.let { it[3] to it[5] }
        val bodyChart = DashboardTable("_chart_body", listOf("date", bodySeries?.let { "${it.first} (${it.second})" } ?: "No measurements selected"),
            body.rows.filter { it[3] to it[5] == bodySeries }.map { listOf(it[1], it[4]) })
        val distances = activities.rows.filter { it[3].toString().contains("run", ignoreCase = true) }.mapNotNull { it[8] as? Number }
        val dashboard = DashboardTable("Dashboard", listOf("FitLogs training dashboard", "Snapshot summary"), listOf(
            listOf("Updated (UTC)", Instant.ofEpochMilli(snapshot.capturedAt).toString()),
            listOf("Calendar time zone", snapshot.timeZone), listOf("Completed sessions", sessions.rows.size),
            listOf("Actual sets", sets.rows.size), listOf("Running activities", snapshot.table("_weekly").rows.sumOf { (it[3] as Number).toInt() }),
            listOf("Recorded run distance (km)", distances.takeIf { it.isNotEmpty() }?.sumOf { it.toDouble() }?.div(1000)),
            listOf("Strength chart", selected?.get(1) ?: "No actual strength records"),
            listOf("Body measurements", if (snapshot.options.includeBody) "Included" else "Excluded"),
            listOf("Notes", if (snapshot.options.includeNotes) "Included" else "Excluded"),
            listOf("About the data", "Completed actual records only. Blank means unknown. Room remains the source of truth."),
            listOf("Strength estimates", "Epley: external working sets, 1–10 reps. Body mass is never inferred."),
            listOf("Editing this workbook", "FitLogs replaces its managed tabs when syncing. Put your own work in new tabs."),
        ))
        val visible = listOf(DashboardSheet(100, dashboard), DashboardSheet(101, strength.copy(columns = listOf("Exercise ID", "Exercise", "Sets", "Reps", "External volume (kg·reps)", "Added-bodyweight volume (kg·reps)", "Unknown-mode volume (kg·reps)", "Max external (kg)", "Estimated 1RM (kg)"))),
            DashboardSheet(102, sessions.copy(name = "Workout history", columns = listOf("Session ID", "Date", "Activity", "Title", "Start (UTC)", "End (UTC)", "Duration (seconds)", "Actual sets", "Source", "Notes"))),
            DashboardSheet(103, activities.copy(name = "Running and activities", columns = listOf("Activity ID", "Session ID", "Date", "Sport", "Title", "Start (UTC)", "End (UTC)", "UTC offset (seconds)", "Distance (metres)", "Duration (seconds)", "Duration meaning", "Pace (seconds/km)", "Source", "Notes"))),
            DashboardSheet(104, body.copy(name = "Body measurements", columns = listOf("Reading ID", "Date", "Time", "Measurement", "Value", "Unit", "Notes")), hidden = !snapshot.options.includeBody))
        val raw = listOf("_sessions", "_sets", "_activities", "_measurements", "_strength_trend", "_weekly")
            .mapIndexed { index, name -> DashboardSheet(200 + index, snapshot.table(name), true) }
        val managed = visible + raw + listOf(DashboardSheet(206, strengthChart, true), DashboardSheet(207, bodyChart, true))
        val metadata = DashboardTable("_meta", listOf("key", "value"), listOf(
            listOf("format", FORMAT), listOf("schema_version", snapshot.schemaVersion),
            listOf("captured_at", snapshot.capturedAt.toString()), listOf("time_zone", snapshot.timeZone),
            listOf("include_notes", snapshot.options.includeNotes.toString()), listOf("include_body", snapshot.options.includeBody.toString()),
            listOf("managed_charts", chartIds.sorted().joinToString(",")),
        ) + managed.map { listOf("sheet:${it.id}", "${it.table.name}|${it.table.values.size}|${it.table.columns.size}") })
        return managed + DashboardSheet(META_ID, metadata, true)
    }

    fun create(snapshot: DashboardSnapshotV1): JSONObject {
        val sheets = sheets(snapshot)
        return JSONObject().put("properties", JSONObject().put("title", "FitLogs training dashboard")
            .put("locale", "en_GB").put("timeZone", snapshot.timeZone))
            .put("sheets", JSONArray(sheets.map { sheet ->
                JSONObject().put("properties", properties(sheet)).put("data", JSONArray().put(JSONObject()
                    .put("rowData", rows(sheet.table.values))))
                    .put("charts", JSONArray(charts(sheets).filter { it.getJSONObject("position").getJSONObject("overlayPosition").getJSONObject("anchorCell").getInt("sheetId") == sheet.id }))
            }))
    }

    fun properties(sheet: DashboardSheet): JSONObject = JSONObject().put("sheetId", sheet.id).put("title", sheet.table.name)
        .put("hidden", sheet.hidden).put("gridProperties", JSONObject().put("rowCount", maxOf(100, sheet.table.values.size + 20))
            .put("columnCount", maxOf(18, sheet.table.columns.size)).put("frozenRowCount", 1).put("hideGridlines", sheet.id == 100))

    // Explicit stringValue keeps '=...', '+...', '@...' and tab/newline-prefixed
    // names literal. The network sync also writes rows with RAW input mode.
    fun rows(values: List<List<Any?>>) = JSONArray(values.map { row -> JSONObject().put("values", JSONArray(row.map { value ->
        JSONObject().also { cell ->
            if (value != null) cell.put("userEnteredValue", JSONObject().put(when (value) {
                is Number -> "numberValue"; is Boolean -> "boolValue"; else -> "stringValue"
            }, value))
        }
    })) })

    fun formatting(sheets: List<DashboardSheet>): List<JSONObject> = sheets.flatMap { sheet ->
        val range = JSONObject().put("sheetId", sheet.id).put("startRowIndex", 0).put("endRowIndex", 1)
            .put("startColumnIndex", 0).put("endColumnIndex", sheet.table.columns.size)
        val header = JSONObject().put("backgroundColor", color(0.08, 0.20, 0.30)).put("textFormat",
            JSONObject().put("bold", true).put("fontFamily", "Arial").put("fontSize", 10).put("foregroundColor", color(1.0, 1.0, 1.0)))
            .put("wrapStrategy", "WRAP").put("verticalAlignment", "MIDDLE")
        buildList {
            add(JSONObject().put("repeatCell", JSONObject().put("range", JSONObject().put("sheetId", sheet.id)
                .put("startRowIndex", 0).put("endRowIndex", sheet.table.values.size).put("startColumnIndex", 0).put("endColumnIndex", sheet.table.columns.size))
                .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("textFormat", JSONObject().put("fontFamily", "Arial").put("fontSize", 10))))
                .put("fields", "userEnteredFormat.textFormat")))
            add(JSONObject().put("repeatCell", JSONObject().put("range", range).put("cell", JSONObject().put("userEnteredFormat", header)).put("fields", "userEnteredFormat")))
            add(
            JSONObject().put("updateDimensionProperties", JSONObject().put("range", JSONObject().put("sheetId", sheet.id)
                .put("dimension", "COLUMNS").put("startIndex", 0).put("endIndex", sheet.table.columns.size))
                .put("properties", JSONObject().put("pixelSize", if (sheet.id == 100) 270 else 170)).put("fields", "pixelSize")))
            if (!sheet.hidden && sheet.id != 100) {
                add(JSONObject().put("updateDimensionProperties", JSONObject().put("range", JSONObject().put("sheetId", sheet.id)
                    .put("dimension", "ROWS").put("startIndex", 0).put("endIndex", 1))
                    .put("properties", JSONObject().put("pixelSize", 60)).put("fields", "pixelSize")))
                add(JSONObject().put("setBasicFilter", JSONObject().put("filter", JSONObject().put("range", JSONObject().put("sheetId", sheet.id)
                    .put("startRowIndex", 0).put("endRowIndex", maxOf(2, sheet.table.values.size)).put("startColumnIndex", 0).put("endColumnIndex", sheet.table.columns.size)))))
                add(JSONObject().put("updateDimensionProperties", JSONObject().put("range", JSONObject().put("sheetId", sheet.id)
                    .put("dimension", "COLUMNS").put("startIndex", 0).put("endIndex", if (sheet.id == 103) 2 else 1))
                    .put("properties", JSONObject().put("hiddenByUser", true)).put("fields", "hiddenByUser")))
            }
            if (sheet.id == 100) {
                listOf(200, 300, 24).forEachIndexed { column, width ->
                    add(JSONObject().put("updateDimensionProperties", JSONObject().put("range", JSONObject().put("sheetId", 100)
                        .put("dimension", "COLUMNS").put("startIndex", column).put("endIndex", column + 1))
                        .put("properties", JSONObject().put("pixelSize", width)).put("fields", "pixelSize")))
                }
                add(JSONObject().put("repeatCell", JSONObject().put("range", JSONObject().put("sheetId", 100)
                    .put("startRowIndex", 1).put("endRowIndex", sheet.table.values.size).put("startColumnIndex", 0).put("endColumnIndex", 2))
                    .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("wrapStrategy", "WRAP").put("verticalAlignment", "MIDDLE")))
                    .put("fields", "userEnteredFormat.wrapStrategy,userEnteredFormat.verticalAlignment")))
                add(JSONObject().put("autoResizeDimensions", JSONObject().put("dimensions", JSONObject().put("sheetId", 100)
                    .put("dimension", "ROWS").put("startIndex", 0).put("endIndex", sheet.table.values.size))))
            }
            if (sheet.id == 101) add(JSONObject().put("repeatCell", JSONObject().put("range", JSONObject().put("sheetId", sheet.id)
                .put("startRowIndex", 1).put("endRowIndex", maxOf(2, sheet.table.values.size)).put("startColumnIndex", 4).put("endColumnIndex", 9))
                .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("numberFormat", JSONObject().put("type", "NUMBER").put("pattern", "0.0"))))
                .put("fields", "userEnteredFormat.numberFormat")))
        }
    }

    fun charts(sheets: List<DashboardSheet>): List<JSONObject> {
        val weekly = sheets.single { it.id == 205 }
        val strength = sheets.single { it.id == 206 }
        val body = sheets.single { it.id == 207 }
        return buildList {
            if (weekly.table.rows.isNotEmpty()) {
                add(chart(501, "Completed sessions by week", "COLUMN", weekly, listOf(1), 100, 1, "Sessions"))
                if (weekly.table.rows.any { it[4] != null }) add(chart(502, "Recorded running distance by week", "COLUMN", weekly, listOf(4), 100, 19, "Kilometres"))
            }
            if (strength.table.rows.isNotEmpty()) add(chart(503, "${sheets.single { it.id == 100 }.table.rows[6][1]} · strength", "LINE", strength, listOf(1, 2), 101, sheets.single { it.id == 101 }.table.values.size + 2, "Kilograms"))
            if (body.table.rows.isNotEmpty()) add(chart(504, body.table.columns[1], "LINE", body, listOf(1), 104, sheets.single { it.id == 104 }.table.values.size + 2, body.table.columns[1]))
        }
    }

    private fun chart(id: Int, title: String, type: String, data: DashboardSheet, columns: List<Int>, target: Int, row: Int, unit: String): JSONObject {
        fun range(column: Int) = JSONObject().put("sourceRange", JSONObject().put("sources", JSONArray().put(JSONObject()
            .put("sheetId", data.id).put("startRowIndex", 0).put("endRowIndex", data.table.values.size)
            .put("startColumnIndex", column).put("endColumnIndex", column + 1))))
        val basic = JSONObject().put("chartType", type).put("headerCount", 1).put("legendPosition", if (columns.size == 1) "NO_LEGEND" else "BOTTOM_LEGEND")
            .put("domains", JSONArray().put(JSONObject().put("domain", range(0))))
            .put("series", JSONArray(columns.mapIndexed { n, column -> JSONObject().put("series", range(column)).put("targetAxis", "LEFT_AXIS")
                .put("colorStyle", JSONObject().put("rgbColor", if (n == 0) color(0.05, 0.48, 0.58) else color(0.9, 0.5, 0.1)))
                .also { if (type == "LINE") it.put("pointStyle", JSONObject().put("shape", "CIRCLE").put("size", 5)) } }))
            .put("axis", JSONArray().put(JSONObject().put("position", "BOTTOM_AXIS").put("title", "Date"))
                .put(JSONObject().put("position", "LEFT_AXIS").put("title", unit)))
        return JSONObject().put("chartId", id).put("spec", JSONObject().put("title", title).put("fontName", "Arial").put("basicChart", basic))
            .put("position", JSONObject().put("overlayPosition", JSONObject().put("anchorCell", JSONObject().put("sheetId", target)
                .put("rowIndex", row).put("columnIndex", if (target == 100) 3 else 1)).put("widthPixels", if (target == 100) 580 else 720).put("heightPixels", 330)))
    }

    private fun color(r: Double, g: Double, b: Double) = JSONObject().put("red", r).put("green", g).put("blue", b)
}
