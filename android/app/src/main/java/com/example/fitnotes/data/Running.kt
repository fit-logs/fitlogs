package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Associations/decisions only. Workouts and FitNotes rows remain the activity store. */
@Entity(tableName = "run_links")
data class RunLink(@PrimaryKey val sourceKey: String, val canonicalKey: String, val retiredKey: String = "",
    val ignored: Boolean = false, val reviewed: Boolean = true)
@Dao interface RunDao {
    @Query("SELECT * FROM run_links") fun observe(): Flow<List<RunLink>>
    @Query("SELECT * FROM run_links") suspend fun all(): List<RunLink>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(link: RunLink)
    @Query("DELETE FROM run_links") suspend fun clear()
}
val RUNNING_MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN runData TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("CREATE TABLE IF NOT EXISTS run_links (sourceKey TEXT NOT NULL PRIMARY KEY, canonicalKey TEXT NOT NULL, retiredKey TEXT NOT NULL, ignored INTEGER NOT NULL, reviewed INTEGER NOT NULL)")
    }
}

enum class RunTimeBasis(val durationLabel: String, val paceLabel: String) {
    TIMER("Timer time", "Timer pace"), MOVING("Moving time", "Moving pace"), ELAPSED("Elapsed time", "Elapsed pace"),
    RECORDED("Recorded duration", "Average pace")
}
data class RunTimes(val elapsed: Double? = null, val timer: Double? = null, val moving: Double? = null, val recorded: Double? = null) {
    fun preferred(): Pair<RunTimeBasis, Double>? = listOf(RunTimeBasis.TIMER to timer, RunTimeBasis.MOVING to moving,
        RunTimeBasis.ELAPSED to elapsed, RunTimeBasis.RECORDED to recorded).firstNotNullOfOrNull { (basis, seconds) ->
        seconds?.takeIf { it.isFinite() && it > 0 }?.let { basis to it }
    }
}
data class RunRecord(val key: String, val parentKey: String, val title: String, val date: String,
    val start: Long? = null, val end: Long? = null, val offsetSeconds: Int? = null,
    val distanceMetres: Double? = null, val times: RunTimes = RunTimes(), val notes: String = "",
    val sources: List<HealthDetail> = emptyList(), val sourceLabel: String = "FitNotes", val importedSetId: String? = null,
    val subtype: String = "Running", val mergedKeys: Set<String> = emptySet()) {
    val preferredTime get() = times.preferred()
    val pace get() = preferredTime?.let { runPace(distanceMetres, it.second) }
}
fun runPace(metres: Double?, seconds: Double?, miles: Boolean = false): Double? {
    if (metres == null || seconds == null || !metres.isFinite() || !seconds.isFinite() || metres <= 0 || seconds <= 0) return null
    return (seconds / (metres / if (miles) 1609.344 else 1000.0)).takeIf { it.isFinite() }
}
fun paceText(seconds: Double?, miles: Boolean = false): String? = seconds?.takeIf { it.isFinite() && it > 0 }?.let {
    val rounded = it.roundToLong(); "${rounded / 60}:${(rounded % 60).toString().padStart(2, '0')} /${if (miles) "mi" else "km"}"
}
fun runDurationText(seconds: Double): String {
    val value = seconds.roundToLong().coerceAtLeast(0)
    return if (value >= 3600) "%d:%02d:%02d".format(Locale.ROOT, value / 3600, value % 3600 / 60, value % 60)
        else "%d:%02d".format(Locale.ROOT, value / 60, value % 60)
}
fun runDistanceText(metres: Double) = String.format(Locale.getDefault(), "%.2f km", metres / 1000)
fun runSummary(run: RunRecord): String = listOfNotNull(run.distanceMetres?.let(::runDistanceText), paceText(run.pace),
    run.preferredTime?.second?.let(::runDurationText)).joinToString(" · ")
fun distanceInMetres(value: Double?, unit: String?): Double? {
    if (value == null || !value.isFinite() || value < 0) return null
    val multiplier = when (unit?.trim()?.lowercase(Locale.ROOT)) { "m", "metres", "meters" -> 1.0; "km" -> 1000.0; "mi", "miles" -> 1609.344; "ft" -> 0.3048; else -> return null }
    return (value * multiplier).takeIf { it.isFinite() }
}
fun isRunningName(name: String): Boolean {
    val normalized = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return normalized in setOf("run", "running", "jog", "jogging", "outdoor run", "outdoor running", "running (outdoor)", "treadmill", "treadmill run", "treadmill running", "running (treadmill)", "trail run", "trail running") ||
        Regex("\\d+(?:\\.\\d+)?\\s*(?:km|k|m|mi) run(?:ning)?").matches(normalized)
}

fun runLocalDate(start: Long, offset: Int? = null): String = Instant.ofEpochMilli(start).atZone(offset?.let(ZoneOffset::ofTotalSeconds) ?: ZoneId.systemDefault()).toLocalDate().toString()

/** Source-only facts, kept separately from local edits and from recorded FitNotes duration. */
data class RunSourceData(val timerSeconds: Double? = null, val movingSeconds: Double? = null,
    val averageSpeedMps: Double? = null, val averageSpeedMeaning: String? = null,
    val distanceVerified: Boolean = false, val distanceEvidence: String = "", val subtype: String = "Running") {
    fun json(): String = JSONObject().put("timerSeconds", timerSeconds ?: JSONObject.NULL).put("movingSeconds", movingSeconds ?: JSONObject.NULL)
        .put("averageSpeedMps", averageSpeedMps ?: JSONObject.NULL).put("averageSpeedMeaning", averageSpeedMeaning ?: JSONObject.NULL)
        .put("distanceVerified", distanceVerified).put("distanceEvidence", distanceEvidence).put("subtype", subtype).toString()
    companion object {
        fun read(raw: String): RunSourceData { val j = JSONObject(raw)
            fun number(key: String) = if (j.isNull(key) || !j.has(key)) null else j.getDouble(key).also { require(it.isFinite() && it >= 0) }
            return RunSourceData(number("timerSeconds"), number("movingSeconds"), number("averageSpeedMps"),
                if(j.isNull("averageSpeedMeaning")) null else j.optString("averageSpeedMeaning").ifBlank { null }, j.optBoolean("distanceVerified"), j.optString("distanceEvidence"), j.optString("subtype", "Running"))
        }
    }
}
fun healthRun(source: HealthDetail, key: String, parent: String): RunRecord {
    val s = source.session; val facts = RunSourceData.read(s.runData)
    return RunRecord(key, parent, s.title.ifBlank { facts.subtype }, runLocalDate(s.start, s.startOffsetSeconds), s.start, s.end, s.startOffsetSeconds,
        s.distanceMeters.takeIf { facts.distanceVerified }, RunTimes(elapsed = (s.end - s.start) / 1000.0, timer = facts.timerSeconds, moving = facts.movingSeconds),
        s.notes, listOf(source), s.sourceName, subtype = facts.subtype)
}

/** Incoming similarity is a REVIEW candidate, never an identity or automatic merge. */
fun potentialRunMatches(incoming: RunRecord, existing: List<RunRecord>): List<RunRecord> = existing.filter { old ->
    old.key != incoming.key && old.sources.none { a -> incoming.sources.any { it.session.key == a.session.key || it.session.originPackage == a.session.originPackage } } &&
        old.date == incoming.date && if (old.start != null && incoming.start != null) abs(old.start - incoming.start) <= 5 * 60_000L
        else {
            val distance = old.distanceMetres; val otherDistance = incoming.distanceMetres
            val duration = old.preferredTime?.second; val otherDuration = incoming.preferredTime?.second
            val distanceClose = distance != null && otherDistance != null && distance > 0 && otherDistance > 0 && abs(distance - otherDistance) <= maxOf(100.0, distance * 0.03)
            val durationClose = duration != null && otherDuration != null && duration > 0 && otherDuration > 0 && abs(duration - otherDuration) <= maxOf(60.0, duration * 0.05)
            distanceClose || durationClose
        }
}

data class RunDistanceRecord(val id: String, val origin: String, val start: Long, val end: Long, val metres: Double)
data class RunDistanceSelection(val metres: Double?, val evidence: String)
/** HC has no session foreign key for DistanceRecord. Require a unique same-origin session
 * and exact session boundary coverage. Intervals must tile the session without overlaps/gaps.
 * A total plus components is conflicting evidence, even if their values happen to agree. */
fun selectRunDistance(session: HealthSession, records: List<RunDistanceRecord>, sessions: List<HealthSession>): RunDistanceSelection {
    val same = records.filter { it.origin == session.originPackage && it.start < session.end && it.end > session.start }.distinctBy { it.id }
    fun reject(reason: String) = RunDistanceSelection(null, "$reason; records=" + same.joinToString { it.id })
    if (same.isEmpty()) return reject("No same-origin run distance")
    if (sessions.any { it.key != session.key && it.originPackage == session.originPackage && it.start < session.end && it.end > session.start }) return reject("Overlapping same-origin sessions")
    if (same.any { !it.metres.isFinite() || it.metres < 0 || it.start < session.start || it.end > session.end || it.end <= it.start }) return reject("Invalid or boundary-crossing distance")
    val sorted = same.sortedBy { it.start }
    if (sorted.first().start != session.start || sorted.last().end != session.end || sorted.zipWithNext().any { (a,b) -> a.end != b.start }) return reject("Distance intervals do not form one unambiguous complete session")
    val total = sorted.sumOf { it.metres }
    if (!total.isFinite()) return reject("Invalid total")
    return RunDistanceSelection(total, "Complete same-origin session interval(s): " + sorted.joinToString { it.id })
}

data class RunPause(val start: Long, val end: Long)
fun runTimerSeconds(start: Long, end: Long, pauses: List<RunPause>): Double? {
    // No segments is insufficient evidence that an exported session's elapsed time is timer time.
    if (pauses.isEmpty() || end <= start) return null
    val sorted = pauses.sortedBy { it.start }
    if (sorted.any { it.start < start || it.end > end || it.end <= it.start } || sorted.zipWithNext().any { (a,b) -> a.end > b.start }) return null
    return ((end - start) - sorted.sumOf { it.end - it.start }).div(1000.0).takeIf { it > 0 }
}

data class RunWeek(val monday: String, val metres: Double, val runs: Int, val current: Boolean)
data class RunningStats(val runs: List<RunRecord>, val weeks: List<RunWeek>, val totalMetres: Double, val longest: Double?, val missingDistances: Int)
fun runningStats(records: List<RunRecord>, first: String?, today: String = LocalDate.now().toString()): RunningStats {
    val runs = records.distinctBy { it.key }.filter { it.date <= today && (first == null || it.date >= first) }
        .sortedWith(compareByDescending<RunRecord> { it.date }.thenByDescending { it.start }.thenBy { it.key })
    fun monday(day: String) = LocalDate.parse(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val groups = runs.groupBy { monday(it.date) }
    val end = monday(today); var week = monday(first ?: runs.minOfOrNull { it.date } ?: today)
    val weeks = mutableListOf<RunWeek>()
    while (week <= end) { val grouped = groups[week].orEmpty(); weeks += RunWeek(week.toString(), grouped.sumOf { it.distanceMetres ?: 0.0 }, grouped.size, week == end); week = week.plusWeeks(1) }
    return RunningStats(runs, weeks, runs.sumOf { it.distanceMetres ?: 0.0 }, runs.mapNotNull { it.distanceMetres }.maxOrNull(), runs.count { it.distanceMetres == null })
}

fun runPairDecisionKey(a:String,b:String) = "separate:" + org.json.JSONArray(listOf(a,b).sorted()).toString()
