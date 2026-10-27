package com.example.fitnotes

import com.example.fitnotes.dashboard.*
import com.example.fitnotes.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.ZoneId

class DashboardPublisherTest {
    private class Store : DashboardConnectionStore {
        var value = DashboardConnection(key = "11111111-1111-1111-1111-111111111111")
        override fun read() = value
        override fun write(connection: DashboardConnection) { value = connection }
    }
    private data class Call(val service: GoogleService, val method: String, val path: String, val body: JSONObject?)
    private class Google : DashboardTransport {
        val folderId = "folderABCDEFGHIJ"; val fileId = "sheetABCDEFGHIJK"
        var account = "owner-one"
        var folderCreates = 0; var fileCreates = 0
        var lostCreateResponse = false
        var failAfterTable: String? = null
        var cancelAfterTable: String? = null
        val files = mutableMapOf<String, JSONObject>()
        val shared = mutableSetOf<String>()
        val sheets = linkedMapOf<Int, JSONObject>()
        val cells = mutableMapOf<Int, MutableList<MutableList<Any?>>>()
        val charts = mutableMapOf<Int, JSONObject>()
        val properties = JSONObject().put("locale", "en_US").put("timeZone", "America/Los_Angeles")
        val calls = mutableListOf<Call>()
        private fun copy(value: JSONObject) = JSONObject(value.toString())
        private fun value(cell: JSONObject): Any? = cell.optJSONObject("userEnteredValue")?.let { entered -> entered.keys().asSequence().firstOrNull()?.let { entered.get(it) } }
        fun put(id: Int, row: Int, column: Int, value: Any?) {
            val data = cells.getOrPut(id) { mutableListOf() }
            while (data.size <= row) data += mutableListOf<Any?>()
            while (data[row].size <= column) data[row] += null
            data[row][column] = value
        }
        fun at(id: Int, row: Int, column: Int): Any? = cells[id]?.getOrNull(row)?.getOrNull(column)
        fun extraSheet() {
            sheets[999] = JSONObject().put("sheetId", 999).put("title", "My notes").put("gridProperties", JSONObject().put("rowCount", 100).put("columnCount", 30))
            put(999, 0, 0, "Keep my own tab")
            charts[900] = JSONObject().put("chartId", 900).put("position", JSONObject().put("overlayPosition", JSONObject().put("anchorCell", JSONObject().put("sheetId", 999))))
            sheets.getValue(200).getJSONObject("gridProperties").put("columnCount", 30)
            put(200, 2, 25, "Keep my extra column")
        }
        override suspend fun request(service: GoogleService, method: String, path: String, token: String, body: JSONObject?): JSONObject {
            yield()
            calls += Call(service, method, path, body?.let(::copy))
            assertFalse("No sharing or permissions writes", method != "GET" && path.contains("permissions"))
            if (service == GoogleService.DRIVE) {
                if (path.startsWith("about?")) return JSONObject().put("user", JSONObject().put("permissionId", account).put("emailAddress", "synthetic@example.invalid"))
                if (path.startsWith("files?q=")) {
                    val query = URLDecoder.decode(path.substringAfter("q=").substringBefore('&'), "UTF-8")
                    val kind = if (query.contains("folder-v1")) "folder-v1" else "dashboard-v1"
                    val matches = files.filter { (_, f) -> f.getJSONObject("appProperties").optString("fitlogsKind") == kind }
                    return JSONObject().put("files", JSONArray(matches.keys.map { JSONObject().put("id", it) }))
                }
                if (method == "POST" && path.startsWith("files?")) {
                    val b = body!!; val folder = b.getString("mimeType").endsWith("folder")
                    if (folder) folderCreates++ else fileCreates++
                    val id = if (folder) folderId else fileId
                    files[id] = copy(b).put("id", id).put("trashed", false).put("ownedByMe", true)
                        .put("owners", JSONArray().put(JSONObject().put("me", true).put("permissionId", account)))
                        .put("permissions", JSONArray().put(JSONObject().put("type", "user").put("role", "owner")))
                        .put("capabilities", JSONObject().put("canEdit", true))
                    if (!folder) {
                        sheets[0] = JSONObject().put("sheetId", 0).put("title", "Sheet1").put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", 26))
                        if (lostCreateResponse) { lostCreateResponse = false; throw DashboardSyncException(DashboardFailure.RETRY) }
                    }
                    return JSONObject().put("id", id)
                }
                val id = path.substringAfter("files/").substringBefore('?')
                return copy(files.getValue(id)).also { if (id in shared) it.getJSONArray("permissions").put(JSONObject().put("type", "user").put("role", "reader")) }
            }
            if (method == "GET" && path.contains("/values/")) {
                val data = cells[299].orEmpty()
                return JSONObject().put("values", JSONArray(data.map { JSONArray(it.map { value -> value ?: "" }) }))
            }
            if (method == "GET") return JSONObject().put("sheets", JSONArray(sheets.map { (id, props) -> JSONObject().put("properties", copy(props))
                .put("charts", JSONArray(charts.values.filter { it.getJSONObject("position").getJSONObject("overlayPosition").getJSONObject("anchorCell").getInt("sheetId") == id })) }))
            if (path.endsWith(":batchUpdate") && !path.contains("/values:")) {
                val requests = body!!.getJSONArray("requests")
                for (n in 0 until requests.length()) {
                    val r = requests.getJSONObject(n)
                    when {
                        r.has("updateSpreadsheetProperties") -> {
                            val p = r.getJSONObject("updateSpreadsheetProperties").getJSONObject("properties")
                            p.keys().asSequence().forEach { key -> properties.put(key, p.get(key)) }
                        }
                        r.has("addSheet") -> { val p = r.getJSONObject("addSheet").getJSONObject("properties"); check(p.getInt("sheetId") !in sheets); sheets[p.getInt("sheetId")] = copy(p) }
                        r.has("deleteSheet") -> sheets.remove(r.getJSONObject("deleteSheet").getInt("sheetId"))
                        r.has("updateCells") -> { val u = r.getJSONObject("updateCells"); val id = u.getJSONObject("start").getInt("sheetId"); val rows = u.getJSONArray("rows")
                            for (row in 0 until rows.length()) { val values = rows.getJSONObject(row).getJSONArray("values"); for (col in 0 until values.length()) put(id, row, col, value(values.getJSONObject(col))) } }
                        r.has("updateSheetProperties") -> { val p = r.getJSONObject("updateSheetProperties").getJSONObject("properties"); val grid = p.optJSONObject("gridProperties")
                            if (p.has("hidden")) sheets.getValue(p.getInt("sheetId")).put("hidden", p.getBoolean("hidden"))
                            grid?.keys()?.asSequence()?.forEach { key -> sheets.getValue(p.getInt("sheetId")).getJSONObject("gridProperties").put(key, grid.get(key)) } }
                        r.has("addChart") -> { val c = r.getJSONObject("addChart").getJSONObject("chart"); check(c.getInt("chartId") !in charts); charts[c.getInt("chartId")] = copy(c) }
                        r.has("updateChartSpec") -> { val c = r.getJSONObject("updateChartSpec"); charts.getValue(c.getInt("chartId")).put("spec", c.getJSONObject("spec")) }
                        r.has("deleteEmbeddedObject") -> charts.remove(r.getJSONObject("deleteEmbeddedObject").getInt("objectId"))
                    }
                }
                return JSONObject()
            }
            if (path.endsWith("/values:batchUpdate")) {
                assertEquals("RAW", body!!.getString("valueInputOption"))
                val data = body.getJSONArray("data")
                for (n in 0 until data.length()) {
                    val item = data.getJSONObject(n); val range = item.getString("range")
                    val name = range.substringAfter('\'').substringBefore('\''); val start = range.substringAfter("!A").toInt() - 1
                    val id = sheets.values.single { it.getString("title") == name }.getInt("sheetId")
                    val rows = item.getJSONArray("values")
                    assertTrue(start + rows.length() <= sheets.getValue(id).getJSONObject("gridProperties").getInt("rowCount"))
                    for (row in 0 until rows.length()) { val values = rows.getJSONArray(row); for (col in 0 until values.length()) put(id, start + row, col, values.get(col)) }
                    if (failAfterTable == name) { failAfterTable = null; throw DashboardSyncException(DashboardFailure.RETRY) }
                    if (cancelAfterTable == name) { cancelAfterTable = null; throw CancellationException("Synthetic cancellation") }
                }
                return JSONObject()
            }
            if (path.endsWith("/values:batchClear")) {
                val ranges = body!!.getJSONArray("ranges")
                for (n in 0 until ranges.length()) {
                    val range = ranges.getString(n); val name = range.substringAfter('\'').substringBefore('\'')
                    val match = Regex("!A([0-9]+):([A-Z]+)([0-9]+)").find(range)!!
                    val columns = match.groupValues[2].fold(0) { total, letter -> total * 26 + letter.code - 'A'.code + 1 }
                    val id = sheets.values.single { it.getString("title") == name }.getInt("sheetId")
                    for (row in match.groupValues[1].toInt() - 1 until match.groupValues[3].toInt()) for (column in 0 until columns) put(id, row, column, "")
                }
                return JSONObject()
            }
            error("Unexpected synthetic request: $method $path")
        }
    }
    private fun snapshot(count: Int = 1, notes: Boolean = false, body: Boolean = false, note: String = "private note"): DashboardSnapshotV1 = DashboardSnapshot.project(
        Backup(exportedAt = 100, exercises = listOf(Exercise(1, "=Synthetic squat", "synthetic squat")),
            workouts = (1..count).map { Workout(it.toLong(), 0, title = "Synthetic", notes = if (notes) note else "", finished = true, sessionDate = "2026-10-01") },
            entries = (1..count).map { WorkoutExercise(it.toLong(), it.toLong(), 1, 0) },
            sets = (1..count).map { WorkoutSet(it.toLong(), it.toLong(), 40.0, 8, 0, comment = if (notes) note else "", loadMode = LoadMode.EXTERNAL) },
            bodyMeasurements = if (body) listOf(BodyMeasurement("reading", "fixture", "reading", "2026-10-01", "", "Weight", 70.0, "kg", "Kilograms", if (notes) note else "", "RAW SECRET")) else emptyList()),
        ZoneId.of("UTC"))
    private suspend fun reject(reason: DashboardFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (error: DashboardSyncException) { assertEquals(reason, error.reason) }
    }

    @Test fun repeatAndConcurrentSyncUseOnePrivateFileAndFixedCharts() = runBlocking {
        val google = Google(); val store = Store(); val publisher = DashboardPublisher(google, store) { 500 }
        val results = coroutineScope { listOf(async { publisher.publish(snapshot(), "secret-token") }, async { publisher.publish(snapshot().copy(capturedAt = 999), "secret-token") }).awaitAll() }
        assertEquals(1, google.folderCreates); assertEquals(1, google.fileCreates); assertEquals(14, google.sheets.size)
        assertEquals(1, results.count { it.unchanged }); assertEquals(500L, store.value.lastSuccessAt)
        assertEquals(setOf(501, 503), google.charts.keys)
        assertEquals("=Synthetic squat", google.at(201, 1, 4))
        assertEquals("en_GB", google.properties.getString("locale")); assertEquals("UTC", google.properties.getString("timeZone"))
        publisher.publish(snapshot().copy(timeZone = "Pacific/Auckland"), "secret-token")
        assertEquals("Pacific/Auckland", google.properties.getString("timeZone")); assertEquals(1, google.fileCreates)
        assertTrue(google.calls.none { it.body?.toString()?.contains("secret-token") == true })
        assertFalse(store.value.toString().contains("secret-token"))
    }

    @Test fun changedAccountSharedFolderAndForeignOwnerAreRejectedBeforeTrainingWrites() = runBlocking {
        val google = Google(); val store = Store(); val publisher = DashboardPublisher(google, store)
        publisher.publish(snapshot(), "token"); val saved = store.value
        google.account = "owner-two"; google.calls.clear()
        reject(DashboardFailure.ACCOUNT) { publisher.publish(snapshot(2), "token") }; assertEquals(1, google.calls.size)
        google.account = "owner-one"; google.shared += google.folderId; google.calls.clear()
        reject(DashboardFailure.PRIVATE_FILE) { publisher.publish(snapshot(2), "token") }; assertTrue(google.calls.none { it.method == "POST" })
        google.shared.clear(); google.files.getValue(google.fileId).getJSONArray("owners").getJSONObject(0).put("permissionId", "someone-else")
        reject(DashboardFailure.PRIVATE_FILE) { publisher.publish(snapshot(2), "token") }
        assertEquals(saved.lastSuccessAt, store.value.lastSuccessAt)
    }

    @Test fun queuedOldAuthorizationCannotRebindAfterDisconnectOrAccountSwitch() = runBlocking {
        val google = Google(); val store = Store(); val publisher = DashboardPublisher(google, store)
        val oldKey = store.read().key
        DashboardPublisher.mutex.lock()
        val pending = async { publisher.publish(snapshot(), "old-account-token", expectedConnectionKey = oldKey) }
        try {
            yield() // The old authorization is waiting for the publish lock.
            store.write(DashboardConnection(key = "22222222-2222-2222-2222-222222222222"))
        } finally { DashboardPublisher.mutex.unlock() }
        try { pending.await(); fail("A disconnected authorization must be cancelled") } catch (_: CancellationException) { }
        assertTrue(google.calls.isEmpty()); assertEquals("", store.value.accountId)
        publisher.publish(snapshot(), "current-account-token")
        assertEquals(1, google.fileCreates)
    }

    @Test fun partialUploadThenSmallerRetryClearsStaleRowsAndKeepsUserTabsColumnsAndCharts() = runBlocking {
        val google = Google(); val store = Store(); var time = 100L; val publisher = DashboardPublisher(google, store) { time }
        publisher.publish(snapshot(2, notes = true, body = true), "token"); google.extraSheet()
        google.sheets.getValue(104).put("hidden", true) // Recover a previously hidden body tab.
        val userChart = google.charts.getValue(900).toString(); google.failAfterTable = "_sets"
        reject(DashboardFailure.RETRY) { publisher.publish(snapshot(20, notes = true, body = true), "token") }
        assertEquals(100L, store.value.lastSuccessAt); assertEquals(21, store.value.pendingRows[201])
        assertEquals("private note", google.at(201, 20, 12))
        time = 200; publisher.publish(snapshot(1), "token")
        assertEquals(200L, store.value.lastSuccessAt); assertTrue(store.value.pendingRows.isEmpty())
        assertEquals("", google.at(201, 20, 12)); assertEquals("", google.at(201, 1, 12)); assertEquals("", google.at(203, 1, 4))
        assertEquals("Keep my own tab", google.at(999, 0, 0)); assertEquals("Keep my extra column", google.at(200, 2, 25))
        assertEquals(userChart, google.charts.getValue(900).toString()); assertEquals(1, google.fileCreates)
        assertFalse(google.sheets.getValue(104).getBoolean("hidden"))
    }

    @Test fun lostCreationResponseRecoversTheMarkedFileWithoutDuplicatingManagedTabs() = runBlocking {
        val google = Google(); val store = Store(); val publisher = DashboardPublisher(google, store)
        google.lostCreateResponse = true
        reject(DashboardFailure.RETRY) { publisher.publish(snapshot(), "token") }
        assertEquals("", store.value.fileId)
        publisher.publish(snapshot(), "token")
        assertEquals(1, google.fileCreates); assertEquals(1, google.folderCreates)
        assertEquals(14, google.sheets.keys.count { it in (100..104).toSet() + (200..207).toSet() + 299 })
        assertTrue(google.sheets.containsKey(0)) // The recovered pre-existing tab is preserved.
    }

    @Test fun cancelledSyncKeepsHighWaterMarksAndNeverAdvancesSuccess() = runBlocking {
        val google = Google(); val store = Store(); val publisher = DashboardPublisher(google, store) { 100 }
        publisher.publish(snapshot(), "token"); google.cancelAfterTable = "_sets"
        try { publisher.publish(snapshot(5), "token"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(100L, store.value.lastSuccessAt); assertEquals(6, store.value.pendingRows[201])
        publisher.publish(snapshot(), "token"); assertTrue(store.value.pendingRows.isEmpty()); assertEquals("", google.at(201, 5, 0))
    }

    @Test fun changedManagedLayoutFailsBeforeDataWritesAndLargeNotesUseBoundedRequests() = runBlocking {
        val google = Google(); val store = Store(); val publisher = DashboardPublisher(google, store)
        publisher.publish(snapshot(), "token")
        google.sheets.getValue(201).put("title", "My rewritten data"); google.calls.clear()
        reject(DashboardFailure.LAYOUT) { publisher.publish(snapshot(2), "token") }
        assertTrue(google.calls.none { it.method == "POST" })
        google.sheets.getValue(201).put("title", "_sets"); google.calls.clear()
        publisher.publish(snapshot(40, notes = true, note = "测".repeat(10000)), "token")
        val values = google.calls.filter { it.path.endsWith("/values:batchUpdate") }
        assertTrue(values.all { it.body!!.toString().toByteArray().size < 1_100_000 })
        assertTrue(values.count { it.body!!.toString().contains("'_sets'!A") } > 1)
    }
}
