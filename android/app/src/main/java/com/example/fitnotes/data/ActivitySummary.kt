package com.example.fitnotes.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Creator link, then Garmin, then lexical provider/key: never relation order. */
fun preferredActivitySource(details: List<HealthDetail>): HealthDetail? = details.sortedWith(
    compareByDescending<HealthDetail> { it.session.isSourceCreated }
        .thenByDescending { isGarminOrigin(it.session.originPackage) }
        .thenBy { it.session.originPackage }.thenBy { it.session.key }).firstOrNull()

fun sourceSport(s: HealthSession): String = if (s.provider == STRAVA_EXPORT_PROVIDER) when {
    s.activityType == "running" -> "Running"
    s.sportLabel.replace(" ", "").equals("Hike", true) -> "Hiking"
    s.activityType == "walking" -> "Walking"
    s.sportLabel.replace(" ", "").lowercase() in setOf("ride", "virtualride", "ebikeride", "mountainbikeride", "gravelride") -> "Cycling"
    else -> s.sportLabel.ifBlank { ActivityType.from(s.activityType).label }
} else when (s.exerciseType) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Running"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
    else -> if (s.activityType == "other") "Other sport (Health Connect ${s.exerciseType})" else ActivityType.from(s.activityType).label
}
fun sourceTime(millis: Long, offset: Int?, pattern: String = "yyyy-MM-dd HH:mm XXX"): String =
    Instant.ofEpochMilli(millis).atZone(offset?.let(ZoneOffset::ofTotalSeconds) ?: ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
fun distanceInMeters(value: Double?, unit: String?): Double? = value?.let {
    when (unit) { "m" -> it; "km" -> it * 1000; "ft" -> it * 0.3048; "mi" -> it * 1609.344; else -> null }
}?.takeIf { it.isFinite() && it >= 0 }
fun elapsedPace(seconds: Double?, meters: Double?, sport: String): String? {
    if (sport !in setOf("Running", "Walking", "Hiking") || seconds == null || meters == null || !seconds.isFinite() || !meters.isFinite() || seconds <= 0 || meters <= 0) return null
    val pace = (seconds / (meters / 1000)).takeIf { it.isFinite() && it < Long.MAX_VALUE }?.roundToLong() ?: return null
    return "%d:%02d min/km elapsed pace".format(Locale.ROOT, pace / 60, pace % 60)
}
data class ActivitySummary(val title: String, val sport: String, val date: String, val start: Long?, val end: Long?,
    val timeLabel: String, val sourceTiming: Boolean, val seconds: Double?, val durationBasis: String,
    val distanceMeters: Double?, val distanceLabel: String?, val distanceOrigin: String?, val preferred: HealthDetail?) {
    val pace get() = elapsedPace(seconds, distanceMeters, sport)
    val metricText get() = listOfNotNull(durationText(seconds), distanceLabel, pace).joinToString(" · ")
}
fun activitySummary(detail: WorkoutDetail): ActivitySummary {
    val w = detail.workout; val primary = preferredActivitySource(detail.health); val source = primary?.session
    // Explicit local timing always wins. Missing local time alone never makes a
    // legacy/attached workout source-owned; only persisted provenance can do so.
    val useSource = source?.isSourceCreated == true && w.knownStart == null
    val localSeconds = localDuration(w)
    val seconds = localSeconds ?: source?.let { (it.end - it.start) / 1000.0 }
    val localMeters = distanceInMeters(w.distance, w.distanceUnit)
    val meters = localMeters ?: source?.distanceMeters
    val sport = if (w.activityType == "other" && w.activityLabel.isNotBlank() && source?.isSourceCreated != true) w.activityLabel
        else if (source != null && (source.activityType == w.activityType || useSource)) sourceSport(source) else activityName(w)
    val date = if (useSource) healthSessionDate(source!!) else localWorkoutDate(w)
    val start = w.knownStart ?: if (useSource) source?.start else null
    val end = w.completedAt ?: if (useSource) source?.end else null
    val time = when { useSource -> sourceTime(source!!.start, source.startOffsetSeconds, "HH:mm XXX") + " · source"
        start != null -> sourceTime(start, null, "HH:mm")
        else -> "Date-only" }
    return ActivitySummary(w.title.ifBlank { source?.title.orEmpty() }, sport, date, start, end, time, useSource, seconds,
        if (localSeconds == null && source != null) "Source elapsed interval" else if (w.sessionDate.isNotBlank()) "Reported duration" else "Local elapsed time",
        meters, if (localMeters != null) weightText(w.distance!!) + " " + w.distanceUnit else source?.distanceMeters?.let { weightText(it / 1000) + " km" },
        if (localMeters != null) "Local distance" else source?.distanceOrigins?.takeIf { it.isNotBlank() }, primary)
}
fun activitySummaryContext(detail: WorkoutDetail): String = activitySummary(detail).let { summary -> buildString {
    appendLine(summary.title.ifBlank { summary.sport } + " · " + summary.date + " · " + summary.timeLabel)
    if (summary.start == null) appendLine("Local start time unknown; source timing, if attached, remains separate.")
    appendLine("Activity: " + summary.sport)
    appendLine(summary.durationBasis + ": " + durationText(summary.seconds))
    summary.distanceLabel?.let { appendLine("Distance: $it · " + summary.distanceOrigin.orEmpty()) }
    summary.pace?.let(::appendLine)
    summary.preferred?.let { chosen ->
        val s = chosen.session
        appendLine("Summary source: ${s.sourceName} (${s.originPackage}); one provider, no duplicate totals")
        s.elevationMeters?.let { appendLine("Elevation gain: ${weightText(it)} m · ${s.elevationOrigins}") }
        s.movingSeconds?.let { appendLine("Source-reported moving time: ${durationText(it)} · ${s.movingOrigin}") }
    }
} }.trim()
