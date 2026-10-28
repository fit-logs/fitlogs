package com.example.fitnotes.dashboard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** Device-local connection metadata only. Whitelisted backup rules exclude these preferences. */
class GoogleDashboardStore(context: Context) : DashboardConnectionStore {
    private val preferences = context.getSharedPreferences("google-dashboard", Context.MODE_PRIVATE)
    override fun read(): DashboardConnection {
        val rows = runCatching { JSONObject(preferences.getString("pending_rows", "{}")!!) }.getOrDefault(JSONObject())
        return DashboardConnection(key = preferences.getString("dashboard_key", null) ?: newKey(),
            accountId = preferences.getString("account_id", "").orEmpty(), email = preferences.getString("account_email", "").orEmpty(),
            folderId = preferences.getString("folder_id", "").orEmpty(), fileId = preferences.getString("file_id", "").orEmpty(),
            lastSuccessAt = preferences.getLong("last_success", 0), hash = preferences.getString("snapshot_hash", "").orEmpty(),
            pendingRows = rows.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { id -> rows.optInt(key).takeIf { it in 1..500000 }?.let { id to it } } }.toMap())
    }
    private fun newKey(): String = java.util.UUID.randomUUID().toString().also { value -> preferences.edit(commit = true) { putString("dashboard_key", value) } }
    override fun write(connection: DashboardConnection) {
        val rows = JSONObject(); connection.pendingRows.forEach { (id, count) -> rows.put(id.toString(), count) }
        check(preferences.edit().putString("dashboard_key", connection.key).putString("account_id", connection.accountId)
            .putString("account_email", connection.email).putString("folder_id", connection.folderId).putString("file_id", connection.fileId)
            .putLong("last_success", connection.lastSuccessAt).putString("snapshot_hash", connection.hash).putString("pending_rows", rows.toString()).commit()) {
            "Unable to save the Google connection on this device."
        }
    }
    fun automatic() = preferences.getBoolean("automatic", false)
    fun automatic(value: Boolean) { preferences.edit(commit = true) { putBoolean("automatic", value) } }
    fun status() = preferences.getString("status", "").orEmpty()
    fun status(message: String) { preferences.edit { putString("status", message) } }
    fun disconnect() { preferences.edit(commit = true) { clear() } }
    fun newDashboard() { write(read().copy(key = java.util.UUID.randomUUID().toString(), folderId = "", fileId = "", lastSuccessAt = 0, hash = "", pendingRows = emptyMap())) }
}
