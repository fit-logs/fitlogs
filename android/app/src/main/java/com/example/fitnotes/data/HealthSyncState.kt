package com.example.fitnotes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/** Connection choices survive restarts; cursors and read grants belong to this device. */
data class HealthSyncSettings(
    val enabled: Boolean = false,
    val background: Boolean = false,
    val origins: Set<String> = setOf(GARMIN_PACKAGE),
    val activities: Set<String> = ActivityType.entries.map { it.key }.toSet(),
)
enum class HealthFeed { EXERCISE, HEART_RATE, DISTANCE }
enum class HealthSyncReason { MANUAL, FOREGROUND, BACKGROUND }
data class HealthSyncState(
    val settings: HealthSyncSettings = HealthSyncSettings(), val generation: Long = 0,
    val tokens: Map<HealthFeed, String> = emptyMap(), val permissionSignature: String = "", val bootstrapped: Boolean = false,
    val working: Boolean = false, val lastAttempt: Long? = null, val lastSuccess: Long? = null,
    val imported: Int = 0, val refreshed: Int = 0, val missing: Int = 0, val errors: Int = 0,
    val reviewKeys: Set<String> = emptySet(), val availableOrigins: Map<String, String> = mapOf(GARMIN_PACKAGE to "Garmin Connect"),
    val message: String = "Connect Health Connect, choose sources, then enable automatic import or use Sync now.",
)
class HealthSyncConfigurationChanged : kotlinx.coroutines.CancellationException("Health sync choices changed.")
interface HealthSyncStore {
    val state: StateFlow<HealthSyncState>
    fun update(expectedGeneration: Long? = null, transform: (HealthSyncState) -> HealthSyncState)
    fun configure(settings: HealthSyncSettings) {
        require(settings.origins.all { it.isNotBlank() && it.length <= 256 })
        require(settings.activities.all { value -> ActivityType.entries.any { it.key == value } })
        update { it.copy(settings = settings, generation = it.generation + 1, tokens = emptyMap(), bootstrapped = false, working = false,
            message = if (settings.enabled) "Automatic import enabled." else "Automatic import off. Sync now remains available.") }
    }
    fun resetAfterRestore() {
        update { HealthSyncState(generation = it.generation + 1, message = "Backup restored. Reconnect and review sources on this device before importing.") }
    }
}
class MemoryHealthSyncStore(initial: HealthSyncState = HealthSyncState()) : HealthSyncStore {
    override val state = MutableStateFlow(initial)
    @Synchronized override fun update(expectedGeneration: Long?, transform: (HealthSyncState) -> HealthSyncState) {
        if (expectedGeneration != null && state.value.generation != expectedGeneration) throw HealthSyncConfigurationChanged()
        state.value = transform(state.value)
    }
}
class DeviceHealthSyncStore(context: Context) : HealthSyncStore {
    private val prefs = context.getSharedPreferences("health-connection-device", Context.MODE_PRIVATE)
    override val state = MutableStateFlow(runCatching { decode(JSONObject(prefs.getString("state", "{}")!!)) }.getOrDefault(HealthSyncState()))
    @Synchronized override fun update(expectedGeneration: Long?, transform: (HealthSyncState) -> HealthSyncState) {
        if (expectedGeneration != null && state.value.generation != expectedGeneration) throw HealthSyncConfigurationChanged()
        val next = transform(state.value)
        // Commit after the Room batch. A crash before this checkpoint replays the
        // batch by stable source IDs; it can never skip unapplied source changes.
        check(prefs.edit().putString("state", encode(next).toString()).commit()) { "Could not checkpoint health sync. Retry safely." }
        state.value = next
    }
    private fun encode(s: HealthSyncState) = JSONObject()
        .put("enabled", s.settings.enabled).put("background", s.settings.background)
        .put("origins", JSONArray(s.settings.origins.sorted())).put("activities", JSONArray(s.settings.activities.sorted()))
        .put("generation", s.generation).put("tokens", JSONObject().also { j -> s.tokens.forEach { (feed, token) -> j.put(feed.name, token) } })
        .put("permissions", s.permissionSignature).put("bootstrapped", s.bootstrapped)
        .put("attempt", s.lastAttempt ?: JSONObject.NULL).put("success", s.lastSuccess ?: JSONObject.NULL)
        .put("imported", s.imported).put("refreshed", s.refreshed).put("missing", s.missing).put("errors", s.errors)
        .put("review", JSONArray(s.reviewKeys.sorted())).put("sources", JSONObject(s.availableOrigins)).put("message", s.message)
    private fun decode(j: JSONObject): HealthSyncState {
        if (!j.has("enabled")) return HealthSyncState()
        fun strings(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val tokens = j.optJSONObject("tokens") ?: JSONObject()
        val origins = j.optJSONObject("sources") ?: JSONObject()
        return HealthSyncState(HealthSyncSettings(j.getBoolean("enabled"), j.optBoolean("background"), strings("origins"), strings("activities")),
            generation = j.optLong("generation"), tokens = HealthFeed.entries.mapNotNull { feed -> tokens.optString(feed.name).takeIf { it.isNotBlank() }?.let { feed to it } }.toMap(),
            permissionSignature = j.optString("permissions"), bootstrapped = j.optBoolean("bootstrapped"),
            lastAttempt = if (j.isNull("attempt")) null else j.optLong("attempt"), lastSuccess = if (j.isNull("success")) null else j.optLong("success"),
            imported = j.optInt("imported"), refreshed = j.optInt("refreshed"), missing = j.optInt("missing"), errors = j.optInt("errors"),
            reviewKeys = strings("review"), availableOrigins = origins.keys().asSequence().associateWith { origins.getString(it) },
            message = j.optString("message", "Ready to sync."))
    }
}
/** WorkManager and the foreground share one lock and observable store per app. */
object HealthSyncRuntime {
    private val instances = mutableMapOf<String, Pair<DeviceHealthSyncStore, Mutex>>()
    @Synchronized fun connection(context: Context): Pair<DeviceHealthSyncStore, Mutex> =
        instances.getOrPut(context.applicationContext.filesDir.absolutePath) { DeviceHealthSyncStore(context.applicationContext) to Mutex() }
}
