package com.example.fitnotes.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** User-selected account exports are distinct from API data and its retention rules. */
data class StravaExportOptions(val owner: String, val timezone: String = "", val elapsedColumn: Int? = null,
    val distanceColumn: Int? = null, val movingColumn: Int? = null, val elevationColumn: Int? = null,
    val averageHrColumn: Int? = null, val maximumHrColumn: Int? = null,
    val timeUnit: String = "", val distanceUnit: String = "", val elevationUnit: String = "",
    val firstDate: String = "", val lastDate: String = "")
data class StravaExportPreview(val candidates: List<HealthCandidate>, val issues: List<String>, val rows: Int, val matches: Map<String, List<SourceMatch>> = emptyMap())
data class SourceMatch(val workoutId: Long?, val label: String, val sourceKey: String? = null, val importedWorkoutId: String? = null)
data class StravaImportChoice(val candidate: HealthCandidate, val include: Boolean = true, val attachTo: Long? = null, val keepSeparate: Boolean = false, val attachImported: String? = null)

object StravaOwnerExport {
    const val MAX_BYTES = 20 * 1024 * 1024
    fun headers(text: String): List<String> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Activity CSV exceeds 20 MB." }
        return CsvTable.read(text.removePrefix("\uFEFF")).firstOrNull()?.fields?.map { it.trim() } ?: error("Choose a nonempty activities.csv export.")
    }
    fun columnChoices(headers: List<String>, name: String) = headers.indices.filter { headers[it].equals(name, true) }
    fun uniqueColumn(headers: List<String>, name: String) = columnChoices(headers, name).singleOrNull()
    fun preview(text: String, options: StravaExportOptions): StravaExportPreview {
        val headers = headers(text)
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(options.owner)) { "Enter a stable owner key using letters, digits, _ or -. This identifies the file owner; it is not authenticated by Strava." }
        fun required(name: String) = uniqueColumn(headers, name) ?: error("Expected exactly one '$name' column.")
        val id = required("Activity ID"); val date = required("Activity Date"); val name = required("Activity Name"); val sport = required("Activity Type")
        val notes = uniqueColumn(headers, "Activity Description")
        require(options.elapsedColumn in columnChoices(headers, "Elapsed Time") && options.timeUnit in setOf("seconds", "minutes", "h:m:s")) { "Choose an elapsed-time column and confirm its units." }
        fun optional(index: Int?, label: String) { require(index == null || index in columnChoices(headers, label)) { "Choose a valid $label column." } }
        optional(options.distanceColumn, "Distance"); optional(options.movingColumn, "Moving Time"); optional(options.elevationColumn, "Elevation Gain")
        optional(options.averageHrColumn, "Average Heart Rate"); optional(options.maximumHrColumn, "Max Heart Rate")
        require(options.distanceColumn == null || options.distanceUnit in setOf("m", "km", "ft", "mi")) { "Confirm distance units for the selected CSV column." }
        require(options.elevationColumn == null || options.elevationUnit in setOf("m", "ft")) { "Confirm elevation-gain units." }
        require((options.firstDate.isBlank() || validHistoryDate(options.firstDate)) && (options.lastDate.isBlank() || validHistoryDate(options.lastDate)) &&
            (options.firstDate.isBlank() || options.lastDate.isBlank() || options.firstDate <= options.lastDate)) { "Check the date range (YYYY-MM-DD)." }
        val zone = options.timezone.takeIf { it.isNotBlank() }?.let(ZoneId::of)
        val rows = CsvTable.read(text.removePrefix("\uFEFF")).drop(1).filter { it.fields.any(String::isNotBlank) }
        val issues = mutableListOf<String>(); val candidates = linkedMapOf<String, HealthCandidate>()
        for (row in rows) try {
            require(row.fields.size == headers.size) { "Wrong column count. Check CSV quoting." }
            fun value(index: Int?) = index?.let { row.fields[it].trim() }.orEmpty()
            fun number(index: Int?): Double? = value(index).takeIf { it.isNotBlank() }?.let {
                it.replace(',', '.').toDoubleOrNull()?.takeIf { n -> n.isFinite() && n >= 0 } ?: error("Invalid numeric value in column ${index!! + 1}.")
            }
            fun seconds(index: Int?): Double? = if (options.timeUnit != "h:m:s") number(index)?.times(if (options.timeUnit == "minutes") 60 else 1) else
                value(index).takeIf { it.isNotBlank() }?.let { raw ->
                    val parts = raw.split(':'); require(parts.size == 3) { "Expected h:m:s time." }
                    val h = parts[0].toLongOrNull(); val m = parts[1].toIntOrNull(); val sec = parts[2].toDoubleOrNull()
                    require(h != null && h >= 0 && m in 0..59 && sec != null && sec.isFinite() && sec >= 0 && sec < 60) { "Invalid h:m:s time." }
                    h * 3600 + m!! * 60 + sec
                }
            val record = value(id); require(Regex("[0-9]{1,30}").matches(record)) { "Activity ID must be the stable numeric Strava ID." }
            val explicitOffset = runCatching { OffsetDateTime.parse(value(date)) }.isSuccess
            val timestamp = timestamp(value(date), zone); val elapsed = seconds(options.elapsedColumn)
            require(elapsed != null && elapsed > 0 && elapsed <= 366 * 86400.0) { "Elapsed time must be positive and no longer than one year." }
            val moving = seconds(options.movingColumn); require(moving == null || moving >= 0 && moving <= elapsed) { "Moving time must be nonnegative and no longer than elapsed time." }
            val rawSport = value(sport); require(rawSport.isNotBlank() && rawSport.length <= 120) { "Invalid original activity type." }
            val type = sport(rawSport); val origin = "com.strava.owner-export"
            val key = activitySourceKey(STRAVA_EXPORT_PROVIDER, options.owner, origin, record)
            val title = value(name); val description = value(notes)
            require(title.length <= 200 && description.length <= 10000) { "Activity title or description is too long." }
            val meters = distanceInMeters(number(options.distanceColumn), options.distanceUnit)
            val elevation = distanceInMeters(number(options.elevationColumn), options.elevationUnit)
            val avg = number(options.averageHrColumn); val max = number(options.maximumHrColumn)
            val start = timestamp.toInstant().toEpochMilli(); val end = Math.addExact(start, (elapsed * 1000).roundToLong())
            val session = HealthSession(key, null, record, origin, "Strava owner export", null, 0, 0, System.currentTimeMillis(), start, end,
                timestamp.offset.totalSeconds, zone?.takeUnless { explicitOffset }?.rules?.getOffset(Instant.ofEpochMilli(end))?.totalSeconds ?: timestamp.offset.totalSeconds,
                type.first, type.second, title, description, meters, if (meters == null) "" else origin, "No per-second samples in this summary file.",
                if (meters == null) "No distance selected or provided." else "Owner-confirmed CSV column units; source-reported distance.",
                provider = STRAVA_EXPORT_PROVIDER, accountId = options.owner, sportLabel = rawSport,
                elevationMeters = elevation, elevationOrigins = if (elevation == null) "" else origin,
                elevationStatus = "Source-reported owner-export gain; no route/elevation reconstruction.",
                movingSeconds = moving, movingOrigin = if (moving == null) "" else origin,
                averageBpm = avg, maximumBpm = max, heartOrigin = if (avg == null && max == null) "" else origin)
            validateHealthBackup(listOf(session), emptyList(), emptySet())
            val day = healthSessionDate(session)
            if ((options.firstDate.isBlank() || day >= options.firstDate) && (options.lastDate.isBlank() || day <= options.lastDate)) {
                val previous = candidates[key]
                require(previous == null || previous.session.copy(importedAt = 0) == session.copy(importedAt = 0)) { "Conflicting rows repeat activity ID $record. Resolve the export before importing." }
                candidates[key] = HealthCandidate(session, distanceRead = options.distanceColumn != null, elevationRead = options.elevationColumn != null,
                    summaryHeartRead = options.averageHrColumn != null || options.maximumHrColumn != null, movingRead = options.movingColumn != null)
            }
        } catch (error: Exception) { issues.add("CSV line ${row.line}: ${error.message ?: "Invalid activity."}") }
        // Conflicting stable identities are never silently resolved by row order.
        val conflicts = issues.filter { it.contains("Conflicting rows repeat activity ID") }.mapNotNull { Regex("activity ID ([0-9]+)").find(it)?.groupValues?.get(1) }.toSet()
        return StravaExportPreview(candidates.values.filter { it.session.recordId !in conflicts }.sortedByDescending { it.session.start }, issues, rows.size)
    }
    private fun timestamp(value: String, zone: ZoneId?): OffsetDateTime {
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { return it }
        val formats = listOf(DateTimeFormatter.ISO_LOCAL_DATE_TIME, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, uuuu, h:mm:ss a").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT))
        val local = formats.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(value, it) }.getOrNull() } ?: error("Unsupported activity timestamp. Use ISO timestamps or the English Strava export date.")
        requireNotNull(zone) { "This timestamp has no offset. Confirm its timezone; no local/UTC time is guessed." }
        val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: error("Ambiguous/nonexistent local time. Provide a timestamp with its explicit offset.")
        return local.atOffset(offset)
    }
    private fun sport(raw: String): Pair<String, Int> = when (raw.lowercase().replace(" ", "")) {
        "run", "trailrun", "virtualrun", "treadmill" -> "running" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "walk" -> "walking" to ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "hike" -> "walking" to ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "ride", "virtualride", "ebikeride", "mountainbikeride", "gravelride" -> "other" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "weighttraining", "workout" -> "strength" to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "rockclimbing" -> "climbing" to ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
        "soccer" -> "soccer" to ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        else -> "other" to 0
    }
}

/** Bounded start-time lookup avoids comparing every archive row with all history. */
class SourceMatchIndex(workouts: List<Workout>, known: List<HealthSession>) {
    private data class Fact(val key: String, val start: Long, val end: Long, val type: String, val meters: Double?, val sport: String,
        val workout: Workout? = null, val source: HealthSession? = null)
    private val byStart = java.util.TreeMap<Long, MutableList<Fact>>()
    private val sources = mutableMapOf<String, Fact>()
    init {
        workouts.forEach { w -> if (w.knownStart != null && w.completedAt != null) {
            val fact = Fact("local:${w.id}", w.startedAt, w.completedAt, w.activityType, distanceInMeters(w.distance, w.distanceUnit), activityName(w), workout = w)
            byStart.getOrPut(fact.start) { mutableListOf() }.add(fact)
        } }
        known.forEach(::addSource)
    }
    fun addSource(source: HealthSession) {
        sources.remove(source.key)?.let { old -> byStart[old.start]?.removeAll { it.key == old.key } }
        val fact = Fact("source:${source.key}", source.start, source.end, source.activityType, source.distanceMeters, sourceSport(source), source = source)
        sources[source.key] = fact; byStart.getOrPut(fact.start) { mutableListOf() }.add(fact)
    }
    fun matches(candidate: HealthSession): List<SourceMatch> = byStart.subMap(candidate.start - 120000, true, candidate.start + 120000, true).values.flatten().mapNotNull { fact ->
        if (fact.source?.key == candidate.key || fact.type != candidate.activityType || fact.type == "other" && fact.sport != sourceSport(candidate)) return@mapNotNull null
        val overlap = (minOf(fact.end, candidate.end) - maxOf(fact.start, candidate.start)).coerceAtLeast(0)
        if (overlap < (fact.end - fact.start) * 0.6 || overlap < (candidate.end - candidate.start) * 0.6) return@mapNotNull null
        if (fact.meters != null && candidate.distanceMeters != null && abs(fact.meters - candidate.distanceMeters) > maxOf(100.0, candidate.distanceMeters * 0.1)) return@mapNotNull null
        val w = fact.workout; val source = fact.source
        if (w != null) SourceMatch(w.id, "${localWorkoutDate(w)} · ${w.title.ifBlank { activityName(w) }} · ${sourceTime(w.startedAt, null, "HH:mm")}")
        else if (source != null) SourceMatch(source.workoutId, when {
            source.workoutId != null -> "${healthSessionDate(source)} · ${source.title.ifBlank { sourceSport(source) }} · ${sourceTime(source.start, source.startOffsetSeconds, "HH:mm XXX")} · source"
            source.importedWorkoutId != null -> "Possible original-history attachment; review source ${source.sourceName}."
            else -> "Previously ignored/deleted source activity near this interval."
        }, source.key, source.importedWorkoutId) else null
    }.distinctBy { it.workoutId?.let { id -> "workout:$id" } ?: it.importedWorkoutId?.let { id -> "original:$id" } ?: it.sourceKey }
}
/** Suggestions use known local OR attached source intervals, never dates/titles. */
fun likelySourceMatches(candidate: HealthSession, workouts: List<Workout>, known: List<HealthSession>): List<SourceMatch> =
    SourceMatchIndex(workouts, known).matches(candidate)
class StravaExportImporter(private val db: FitDatabase, private val lock: Mutex) {
    suspend fun apply(choices: List<StravaImportChoice>, progress: (Int) -> Unit = {}): Int = lock.withLock {
        require(choices.map { it.candidate.session.key }.distinct().size == choices.size) { "Duplicate activity choices." }
        var applied = 0
        for (batch in choices.filter { it.include }.chunked(20)) {
            currentCoroutineContext().ensureActive()
            db.withTransaction {
                val index = SourceMatchIndex(db.dao().workouts(), db.healthDao().sessions())
                for (choice in batch) {
                    val source = choice.candidate.session
                    require(source.provider == STRAVA_EXPORT_PROVIDER) { "Expected an owner-export activity." }
                    val known = db.healthDao().sessions(); val prior = known.firstOrNull { it.key == source.key }
                    if (prior == null && choice.attachTo == null && choice.attachImported == null && !choice.keepSeparate)
                        require(index.matches(source).isEmpty()) { "A possible match appeared while this preview was open. Review the activity before importing it." }
                    HealthImportRepository(db).import(choice.candidate, attachTo = choice.attachTo, attachImported = choice.attachImported)
                    db.healthDao().session(source.key)?.let(index::addSource)
                }
            }
            applied += batch.size; progress(applied)
        }
        applied
    }
}
