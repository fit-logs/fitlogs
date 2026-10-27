package com.example.fitnotes.dashboard

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class DashboardConnection(val key: String = UUID.randomUUID().toString(), val accountId: String = "", val email: String = "",
    val folderId: String = "", val fileId: String = "", val lastSuccessAt: Long = 0, val hash: String = "", val pendingRows: Map<Int, Int> = emptyMap())
interface DashboardConnectionStore {
    fun read(): DashboardConnection
    fun write(connection: DashboardConnection)
}
data class DashboardPublishResult(val connection: DashboardConnection, val unchanged: Boolean)

/** One account-bound, private, replaceable projection. No permissions/sharing API is used. */
class DashboardPublisher(private val transport: DashboardTransport, private val store: DashboardConnectionStore,
    private val clock: () -> Long = System::currentTimeMillis) {
    companion object { val mutex = Mutex() }
    private val fileFields = "id,mimeType,trashed,parents,ownedByMe,owners(permissionId,me),permissions(type,role),appProperties,capabilities(canEdit)"

    suspend fun publish(snapshot: DashboardSnapshotV1, token: String, repairCharts: Boolean = false,
        expectedConnectionKey: String = store.read().key): DashboardPublishResult = mutex.withLock {
        // Cancellation can arrive after authorization or while waiting for this
        // lock. An old token must never re-bind a disconnected/switched account.
        if (store.read().key != expectedConnectionKey) throw CancellationException("Google dashboard connection changed.")
        suspend fun call(service: GoogleService, method: String, path: String, body: JSONObject? = null): JSONObject {
            coroutineContext.ensureActive()
            return transport.request(service, method, path, token, body)
        }
        val account = call(GoogleService.DRIVE, "GET", "about?fields=user(permissionId,emailAddress)").getJSONObject("user")
        val owner = account.optString("permissionId")
        if (owner.isBlank()) throw DashboardSyncException(DashboardFailure.AUTH)
        var connection = store.read()
        if (!Regex("[a-f0-9-]{36}").matches(connection.key)) throw DashboardSyncException(DashboardFailure.LAYOUT)
        if (connection.accountId.isNotBlank() && connection.accountId != owner) throw DashboardSyncException(DashboardFailure.ACCOUNT)
        connection = connection.copy(accountId = owner, email = account.optString("emailAddress"))
        store.write(connection)
        suspend fun file(id: String) = call(GoogleService.DRIVE, "GET", "files/${googleFileId(id)}?fields=${queryValue(fileFields)}")
        fun privateOwned(value: JSONObject, kind: String) {
            val owners = value.optJSONArray("owners") ?: JSONArray()
            val permissions = value.optJSONArray("permissions") ?: JSONArray()
            if (value.optBoolean("trashed") || !value.optBoolean("ownedByMe") || value.optJSONObject("capabilities")?.optBoolean("canEdit") != true ||
                (0 until owners.length()).none { owners.getJSONObject(it).optBoolean("me") && owners.getJSONObject(it).optString("permissionId") == owner } ||
                permissions.length() == 0 || (0 until permissions.length()).any { permissions.getJSONObject(it).optString("role") != "owner" } ||
                value.optJSONObject("appProperties")?.optString("fitlogsKind") != kind) throw DashboardSyncException(DashboardFailure.PRIVATE_FILE)
        }
        suspend fun find(kind: String, key: String? = null): String? {
            val q = "trashed = false and 'me' in owners and appProperties has { key='fitlogsKind' and value='$kind' }" +
                (key?.let { " and appProperties has { key='fitlogsKey' and value='$it' }" } ?: "")
            val files = call(GoogleService.DRIVE, "GET", "files?q=${queryValue(q)}&fields=files(id),nextPageToken&pageSize=100&orderBy=createdTime")
            val rows = files.getJSONArray("files")
            if (kind != "folder-v1" && (rows.length() > 1 || files.has("nextPageToken"))) throw DashboardSyncException(DashboardFailure.LAYOUT)
            return if (rows.length() == 0) null else googleFileId(rows.getJSONObject(0).getString("id"))
        }
        if (connection.folderId.isBlank()) {
            val candidate = find("folder-v1")
            val reusable = candidate?.takeIf { folderId ->
                try { privateOwned(file(folderId), "folder-v1"); true }
                catch (error: DashboardSyncException) {
                    if (error.reason !in setOf(DashboardFailure.PRIVATE_FILE, DashboardFailure.FILE_MISSING)) throw error
                    false
                }
            }
            val folderId = reusable ?: call(GoogleService.DRIVE, "POST", "files?fields=id", JSONObject()
                .put("name", "FitLogs").put("mimeType", "application/vnd.google-apps.folder")
                .put("appProperties", JSONObject().put("fitlogsKind", "folder-v1"))).getString("id")
            connection = connection.copy(folderId = googleFileId(folderId)); store.write(connection)
        }
        val folder = file(connection.folderId)
        privateOwned(folder, "folder-v1")
        if (folder.optString("mimeType") != "application/vnd.google-apps.folder") throw DashboardSyncException(DashboardFailure.PRIVATE_FILE)
        var created = false
        if (connection.fileId.isBlank()) {
            val recovered = find("dashboard-v1", connection.key)
            val id = recovered ?: call(GoogleService.DRIVE, "POST", "files?fields=id", JSONObject()
                .put("name", "FitLogs training dashboard").put("mimeType", "application/vnd.google-apps.spreadsheet")
                .put("parents", JSONArray().put(connection.folderId)).put("appProperties", JSONObject()
                    .put("fitlogsKind", "dashboard-v1").put("fitlogsKey", connection.key).put("fitlogsOwner", owner))).getString("id").also { created = true }
            connection = connection.copy(fileId = googleFileId(id)); store.write(connection)
        }
        val destination = file(connection.fileId)
        privateOwned(destination, "dashboard-v1")
        val parents = destination.optJSONArray("parents") ?: JSONArray()
        val properties = destination.getJSONObject("appProperties")
        if (destination.optString("mimeType") != "application/vnd.google-apps.spreadsheet" || properties.optString("fitlogsKey") != connection.key ||
            properties.optString("fitlogsOwner") != owner || (0 until parents.length()).none { parents.getString(it) == connection.folderId })
            throw DashboardSyncException(DashboardFailure.PRIVATE_FILE)
        val id = connection.fileId
        val sheets = DashboardWorkbook.sheets(snapshot)
        if (sheets.sumOf { maxOf(100, it.table.values.size + 20).toLong() * maxOf(18, it.table.columns.size) } > 9_500_000)
            throw DashboardSyncException(DashboardFailure.TOO_LARGE)
        suspend fun batch(requests: List<JSONObject>) = call(GoogleService.SHEETS, "POST", "$id:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
        var metadata = call(GoogleService.SHEETS, "GET", "$id?fields=sheets(properties,charts(chartId,position))").getJSONArray("sheets")
        val expected = sheets.associateBy { it.id }
        if (created || (0 until metadata.length()).none { metadata.getJSONObject(it).getJSONObject("properties").getInt("sheetId") in expected }) {
            if (connection.lastSuccessAt != 0L) throw DashboardSyncException(DashboardFailure.LAYOUT)
            val initialIds = (0 until metadata.length()).map { metadata.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }.toSet()
            val requests = sheets.filter { !created || it.id !in initialIds }.map { JSONObject().put("addSheet", JSONObject().put("properties", DashboardWorkbook.properties(it))) }.toMutableList()
            // Only the fresh creation response authorizes removal of its empty
            // default tab. A recovered file may contain user work; preserve it.
            if (created) (0 until metadata.length()).forEach { n -> requests += JSONObject().put("deleteSheet", JSONObject().put("sheetId", metadata.getJSONObject(n).getJSONObject("properties").getInt("sheetId"))) }
            if (created) sheets.filter { it.id in initialIds }.forEach { requests += JSONObject().put("addSheet", JSONObject().put("properties", DashboardWorkbook.properties(it))) }
            val initialMeta = sheets.single { it.id == DashboardWorkbook.META_ID }.table.values.map { row -> when {
                row[0] == "captured_at" -> listOf("captured_at", "0")
                row[0].toString().startsWith("sheet:") -> listOf(row[0], row[1].toString().split('|').let { "${it[0]}|1|${it[2]}" })
                else -> row
            } }
            requests += JSONObject().put("updateCells", JSONObject().put("start", JSONObject().put("sheetId", DashboardWorkbook.META_ID).put("rowIndex", 0).put("columnIndex", 0))
                .put("rows", DashboardWorkbook.rows(initialMeta)).put("fields", "userEnteredValue"))
            batch(requests)
            metadata = call(GoogleService.SHEETS, "GET", "$id?fields=sheets(properties,charts(chartId,position))").getJSONArray("sheets")
        }
        val existing = (0 until metadata.length()).map { metadata.getJSONObject(it) }.associateBy { it.getJSONObject("properties").getInt("sheetId") }
        if (sheets.any { sheet -> existing[sheet.id]?.getJSONObject("properties")?.optString("title") != sheet.table.name }) throw DashboardSyncException(DashboardFailure.LAYOUT)
        val meta = call(GoogleService.SHEETS, "GET", "$id/values/${queryValue("'_meta'!A1:B40")}").optJSONArray("values") ?: JSONArray()
        val manifest = (0 until meta.length()).mapNotNull { n -> meta.getJSONArray(n).takeIf { it.length() >= 2 }?.let { it.getString(0) to it.get(1).toString() } }.toMap()
        if (manifest["format"] != DashboardWorkbook.FORMAT || manifest["schema_version"] != "1") throw DashboardSyncException(DashboardFailure.LAYOUT)
        val oldRows = sheets.filter { it.id != DashboardWorkbook.META_ID }.associate { sheet ->
            val info = manifest["sheet:${sheet.id}"]?.split('|') ?: throw DashboardSyncException(DashboardFailure.LAYOUT)
            val count = info.getOrNull(1)?.toIntOrNull() ?: throw DashboardSyncException(DashboardFailure.LAYOUT)
            if (info.size != 3 || info[0] != sheet.table.name || info[2].toIntOrNull() != sheet.table.columns.size || count !in 1..500000) throw DashboardSyncException(DashboardFailure.LAYOUT)
            sheet.id to maxOf(count, connection.pendingRows[sheet.id] ?: 1)
        }
        val hash = snapshotHash(snapshot)
        val desiredCharts = DashboardWorkbook.charts(sheets).associateBy { it.getInt("chartId") }
        val existingCharts = existing.flatMap { (sheetId, sheet) ->
            val charts = sheet.optJSONArray("charts") ?: JSONArray()
            (0 until charts.length()).map { charts.getJSONObject(it).getInt("chartId") to sheetId }
        }.toMap()
        if (existingCharts.any { (chart, parent) -> chart in DashboardWorkbook.chartIds && parent !in setOf(100, 101, 104) }) throw DashboardSyncException(DashboardFailure.LAYOUT)
        if (!repairCharts && connection.hash == hash && connection.pendingRows.isEmpty()) return@withLock DashboardPublishResult(connection, true)
        val pending = sheets.associate { it.id to maxOf(oldRows[it.id] ?: 1, it.table.values.size) }
        // Persist high-water marks before writes. If a later request fails, a
        // smaller retry still clears rows from the partially uploaded snapshot.
        connection = connection.copy(pendingRows = pending); store.write(connection)
        val grow = sheets.mapNotNull { sheet ->
            val grid = existing.getValue(sheet.id).getJSONObject("properties").getJSONObject("gridProperties")
            if (grid.getInt("rowCount") >= pending.getValue(sheet.id) && grid.getInt("columnCount") >= sheet.table.columns.size) null
            else JSONObject().put("updateSheetProperties", JSONObject().put("properties", JSONObject().put("sheetId", sheet.id).put("gridProperties", JSONObject()
                .put("rowCount", maxOf(grid.getInt("rowCount"), pending.getValue(sheet.id) + 20)).put("columnCount", maxOf(grid.getInt("columnCount"), sheet.table.columns.size))))
                .put("fields", "gridProperties.rowCount,gridProperties.columnCount"))
        }
        if (grow.isNotEmpty()) batch(grow)
        suspend fun write(sheet: DashboardSheet) {
            val chunks = mutableListOf<List<List<Any?>>>()
            var rows = mutableListOf<List<Any?>>()
            var bytes = 0
            sheet.table.values.forEach { row ->
                val size = JSONArray(row.map { it ?: "" }).toString().toByteArray(Charsets.UTF_8).size
                if (rows.isNotEmpty() && (rows.size >= 200 || bytes + size > 1_000_000)) { chunks += rows; rows = mutableListOf(); bytes = 0 }
                rows += row; bytes += size
            }
            if (rows.isNotEmpty()) chunks += rows
            var offset = 0
            chunks.forEach { rows ->
                call(GoogleService.SHEETS, "POST", "$id/values:batchUpdate", JSONObject().put("valueInputOption", "RAW").put("data", JSONArray().put(JSONObject()
                    .put("range", "'${sheet.table.name}'!A${offset + 1}").put("majorDimension", "ROWS")
                    .put("values", JSONArray(rows.map { row -> JSONArray(row.map { it ?: "" }) })))))
                offset += rows.size
            }
        }
        sheets.filter { it.id != DashboardWorkbook.META_ID }.forEach { write(it) }
        val clear = sheets.filter { it.id != DashboardWorkbook.META_ID && pending.getValue(it.id) > it.table.values.size }.map { sheet ->
            "'${sheet.table.name}'!A${sheet.table.values.size + 1}:${columnName(sheet.table.columns.size)}${pending.getValue(sheet.id)}"
        }
        if (clear.isNotEmpty()) call(GoogleService.SHEETS, "POST", "$id/values:batchClear", JSONObject().put("ranges", JSONArray(clear)))
        // Drive creates the container with account defaults. Keep its calendar
        // settings aligned with the snapshot, including after a time-zone change.
        val requests = mutableListOf(JSONObject().put("updateSpreadsheetProperties", JSONObject()
            .put("properties", JSONObject().put("locale", "en_GB").put("timeZone", snapshot.timeZone)).put("fields", "locale,timeZone")))
        requests += DashboardWorkbook.formatting(sheets)
        desiredCharts.forEach { (chartId, chart) ->
            if (chartId in existingCharts) {
                requests += JSONObject().put("updateChartSpec", JSONObject().put("chartId", chartId).put("spec", chart.getJSONObject("spec")))
                if (repairCharts) requests += JSONObject().put("updateEmbeddedObjectPosition", JSONObject().put("objectId", chartId)
                    .put("newPosition", chart.getJSONObject("position")).put("fields", "*"))
            } else requests += JSONObject().put("addChart", JSONObject().put("chart", chart))
        }
        (DashboardWorkbook.chartIds - desiredCharts.keys).filter { it in existingCharts }.forEach { chartId ->
            requests += JSONObject().put("deleteEmbeddedObject", JSONObject().put("objectId", chartId))
        }
        batch(requests)
        write(sheets.single { it.id == DashboardWorkbook.META_ID })
        coroutineContext.ensureActive()
        connection = connection.copy(lastSuccessAt = clock(), hash = hash, pendingRows = emptyMap())
        store.write(connection)
        DashboardPublishResult(connection, false)
    }
}

fun snapshotHash(snapshot: DashboardSnapshotV1): String {
    val values = JSONArray().put(snapshot.schemaVersion).put(snapshot.timeZone)
    snapshot.tables.forEach { table -> values.put(JSONArray().put(table.name).put(JSONArray(table.values.map { JSONArray(it.map { value -> value ?: JSONObject.NULL }) }))) }
    return MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
private fun columnName(count: Int): String {
    var n = count; var name = ""
    while (n > 0) { n--; name = ('A'.code + n % 26).toChar() + name; n /= 26 }
    return name
}
