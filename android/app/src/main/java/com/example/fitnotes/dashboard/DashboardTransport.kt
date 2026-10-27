package com.example.fitnotes.dashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
enum class GoogleService(val base: String) {
    DRIVE("https://www.googleapis.com/drive/v3/"), SHEETS("https://sheets.googleapis.com/v4/spreadsheets/")
}
enum class DashboardFailure(val explanation: String) {
    AUTH("Google access expired or was removed. Reconnect to sync."),
    ACCOUNT("This connection belongs to another Google account. Use Switch account."),
    PRIVATE_FILE("The dashboard and its folder must be private files owned by your selected Google account. Check sharing in Drive or create a new dashboard."),
    FILE_MISSING("The dashboard or folder is missing. Restore it in Drive or create a new dashboard."),
    STORAGE("Your Google Drive storage is full. Free some space in Drive and retry."),
    LAYOUT("The managed dashboard tabs were changed. Create a new dashboard to keep the existing file intact."),
    RETRY("Google is temporarily unavailable. Check your connection and retry."),
    SETUP("Google could not connect to FitLogs. Retry or check for an app update."),
    TOO_LARGE("This history exceeds the supported dashboard size. Your local records are unchanged.")
}
class DashboardSyncException(val reason: DashboardFailure) : IOException(reason.explanation)
fun dashboardHttpFailure(code: Int, errorBody: String = ""): DashboardFailure {
    // Inspect only machine-readable reasons. Google's error text can contain
    // file names or user data and must never reach logs or status messages.
    val reasons = runCatching {
        val error = JSONObject(errorBody).getJSONObject("error")
        val errors = error.optJSONArray("errors") ?: org.json.JSONArray()
        (0 until errors.length()).map { errors.getJSONObject(it).optString("reason") }.toSet() + error.optString("status")
    }.getOrDefault(emptySet())
    return when {
        code == 401 -> DashboardFailure.AUTH
        code == 403 && reasons.any { it in setOf("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "RESOURCE_EXHAUSTED") } -> DashboardFailure.RETRY
        code == 403 && "storageQuotaExceeded" in reasons -> DashboardFailure.STORAGE
        code == 403 && reasons.any { it in setOf("accessNotConfigured", "serviceDisabled") } -> DashboardFailure.SETUP
        code == 403 -> DashboardFailure.PRIVATE_FILE
        code == 404 -> DashboardFailure.FILE_MISSING
        code == 408 || code == 409 || code == 429 || code >= 500 -> DashboardFailure.RETRY
        else -> DashboardFailure.SETUP
    }
}
fun queryValue(value: String): String = URLEncoder.encode(value, "UTF-8")
fun googleFileId(value: String): String = value.also {
    if (!Regex("[A-Za-z0-9_-]{10,150}").matches(it)) throw DashboardSyncException(DashboardFailure.LAYOUT)
}

fun interface DashboardTransport {
    suspend fun request(service: GoogleService, method: String, path: String, token: String, body: JSONObject?): JSONObject
}

/** Requests go directly to Google. Tokens stay in headers and are never persisted or logged. */
class GoogleDashboardTransport : DashboardTransport {
    override suspend fun request(service: GoogleService, method: String, path: String, token: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        require(!path.startsWith("/") && !path.contains("://") && !path.contains(".."))
        require(method in setOf("GET", "POST", "PATCH"))
        val connection = URL(service.base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 2_000_000) throw DashboardSyncException(DashboardFailure.TOO_LARGE)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = connection.errorStream?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(2048)
                    while (buffer.size() < 16384) {
                        coroutineContext.ensureActive()
                        val count = input.read(chunk, 0, minOf(chunk.size, 16384 - buffer.size())); if (count < 0) break
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toString("UTF-8")
                }.orEmpty()
                throw DashboardSyncException(dashboardHttpFailure(code, errorBody))
            }
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16384)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(chunk); if (count < 0) break
                    if (buffer.size() + count > 4_000_000) throw DashboardSyncException(DashboardFailure.TOO_LARGE)
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            coroutineContext.ensureActive()
            if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (error: IOException) {
            if (error is DashboardSyncException) throw error
            throw DashboardSyncException(DashboardFailure.RETRY)
        } finally { connection.disconnect() }
    }
}
