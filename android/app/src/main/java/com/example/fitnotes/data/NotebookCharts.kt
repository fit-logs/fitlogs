package com.example.fitnotes.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One observation per session. A recorded date never becomes a recorded start time. */
data class StrengthPoint(val sessionId: String, val date: String, val start: Long?, val value: Double,
    val sets: List<StrengthResult> = emptyList(), val relevantSets: List<StrengthResult> = emptyList(), val segment: Int = 0)
enum class StrengthMetric(val label: String, val unit: String, val definition: String) {
    WEIGHT("Heaviest set", "kg", "Highest comparable recorded load in a session, with the actual reps at that load. Assistance is excluded."),
    REPS("Best set reps", "reps", "Most completed reps in one set, shown with its recorded load."),
    VOLUME("Volume", "kg·reps", "Sum of external load × completed reps. A session is included only when every recorded set has supported load values."),
    ONE_RM("Estimated 1RM", "kg", "Epley: load × (1 + reps / 30), or the actual load for one rep. External working sets of 1–10 reps only.")
}
private fun metricValue(sets: List<StrengthResult>, metric: StrengthMetric): Pair<Double,List<StrengthResult>>? {
    if(sets.isEmpty()) return null
    return when(metric) {
        StrengthMetric.WEIGHT -> {
            val eligible=sets.filter { it.weightKg!=null && it.mode in setOf(LoadMode.UNKNOWN,LoadMode.EXTERNAL,LoadMode.BODYWEIGHT) }
            // Unknown legacy loads remain useful, but explicit added load is not
            // comparable to external/unknown total load within a session.
            if(eligible.any{it.mode==LoadMode.BODYWEIGHT} && eligible.any{it.mode!=LoadMode.BODYWEIGHT}) null
            else eligible.maxOfOrNull { it.weightKg!! }?.let { value -> value to eligible.filter{it.weightKg==value} }
        }
        StrengthMetric.REPS -> sets.maxOf{it.reps}.let { value -> value.toDouble() to sets.filter{it.reps==value} }
        StrengthMetric.VOLUME -> if(sets.all{it.mode==LoadMode.EXTERNAL && it.weightKg!=null}) sets.sumOf{it.weightKg!!*it.reps} to sets else null
        StrengthMetric.ONE_RM -> sets.mapNotNull{estimatedOneRepMax(it.weightKg,it.reps,it.mode,it.warmUp)}.maxOrNull()?.let { value ->
            value to sets.filter{estimatedOneRepMax(it.weightKg,it.reps,it.mode,it.warmUp)==value}
        }
    }
}
fun strengthMetrics(records: List<TrainingObservation>, key: String): List<StrengthMetric> =
    StrengthMetric.entries.filter { strengthPoints(records,key,it).isNotEmpty() }
fun defaultStrengthMetric(records: List<TrainingObservation>, key: String): StrengthMetric {
    val sets=records.filter{it.completed&&it.activity=="strength"}.flatMap{it.results}.filter{it.exerciseKey==key}
    return if(sets.none{it.mode==LoadMode.ASSISTANCE} && strengthPoints(records,key,StrengthMetric.WEIGHT).any{it.value>0}) StrengthMetric.WEIGHT else StrengthMetric.REPS
}
fun strengthSessions(records: List<TrainingObservation>, key: String, from: String? = null) = records
    .filter { it.completed && it.activity=="strength" && (from==null||it.date>=from) && it.results.any{set->set.exerciseKey==key} }
    .sortedWith(compareBy<TrainingObservation>{it.date}.thenBy{it.started?:Long.MIN_VALUE}.thenBy{it.id})
fun strengthPoints(records: List<TrainingObservation>, key: String, metric: StrengthMetric, from: String? = null): List<StrengthPoint> {
    if(metric==StrengthMetric.WEIGHT) {
        val loaded=records.filter{it.completed&&it.activity=="strength"}.flatMap{it.results}.filter{it.exerciseKey==key&&it.weightKg!=null}
        if(loaded.any{it.mode==LoadMode.BODYWEIGHT}&&loaded.any{it.mode in setOf(LoadMode.UNKNOWN,LoadMode.EXTERNAL)}) return emptyList()
    }
    var segment=0
    return strengthSessions(records,key,from).mapNotNull { session ->
        val sets=session.results.filter{it.exerciseKey==key}
        val result=metricValue(sets,metric)
        if(result==null) { segment++;null } else StrengthPoint(session.id,session.date,session.started,result.first,sets,result.second,segment)
    }
}
/** Consecutive equivalent loads only; original set order is significant. */
fun compactStrengthResults(sets: List<StrengthResult>): String {
    val groups=mutableListOf<MutableList<StrengthResult>>()
    sets.forEach { set ->
        if(groups.isEmpty() || groups.last().last().let{it.weightKg!=set.weightKg||it.mode!=set.mode}) groups.add(mutableListOf())
        groups.last().add(set)
    }
    return groups.joinToString(" · ") { group ->
        val first=group.first()
        val load=when(first.mode) {
            LoadMode.BODYWEIGHT -> "Bodyweight" + first.weightKg?.takeIf{it>0}?.let{" + ${estimateText(it)} kg"}.orEmpty()
            LoadMode.ASSISTANCE -> first.weightKg?.let{"${estimateText(it)} kg assistance"}.orEmpty()
            else -> first.weightKg?.let{"${estimateText(it)} kg"}.orEmpty()
        }
        (if(load.isBlank()) "" else "$load × ") + group.joinToString(", "){it.reps.toString()} + if(load.isBlank()) " reps" else ""
    }
}
fun strengthPointSummary(point: StrengthPoint, metric: StrengthMetric): String =
    if(metric in setOf(StrengthMetric.WEIGHT,StrengthMetric.REPS)) compactStrengthResults(point.relevantSets) else "${estimateText(point.value)} ${metric.unit}"
/** Calendar coordinate only: date-only records retain a null recorded start. */
fun progressDateCoordinate(date: String): Double = SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply {
    isLenient=false;timeZone=java.util.TimeZone.getTimeZone("UTC")
}.parse(date)!!.time / 86400000.0
fun progressFrom(range: String, today: String): String? {
    if(range=="All")return null
    val format=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).apply{isLenient=false}
    val day=Calendar.getInstance().apply{time=requireNotNull(format.parse(today))}
    when(range){"1M"->day.add(Calendar.MONTH,-1);"3M"->day.add(Calendar.MONTH,-3);"1Y"->day.add(Calendar.YEAR,-1);else->return null}
    return format.format(day.time)
}
data class PlotBounds(val low: Double, val high: Double, val step: Double = (high-low)/4)
fun plotBounds(values: List<Double>): PlotBounds {
    require(values.isNotEmpty() && values.all { it.isFinite() })
    val low = values.min(); val high = values.max()
    val padding = if (low == high) maxOf(kotlin.math.abs(low) * .1, 1.0) else (high - low) * .1
    val paddedLow=if(low>=0) (low-padding).coerceAtLeast(0.0) else low-padding
    val paddedHigh=high+padding
    val rawStep=(paddedHigh-paddedLow)/4
    val magnitude=Math.pow(10.0,kotlin.math.floor(kotlin.math.log10(rawStep)))
    val fraction=rawStep/magnitude
    val step=when { fraction<=1->1.0; fraction<=2->2.0; fraction<=2.5->2.5; fraction<=5->5.0; else->10.0 }*magnitude
    return PlotBounds(kotlin.math.floor(paddedLow/step)*step,kotlin.math.ceil(paddedHigh/step)*step,step)
}
/** Tick precision follows the scale, so small measurement changes get distinct labels. */
fun plotAxisText(value: Double, step: Double): String = java.math.BigDecimal.valueOf(value)
    .setScale(java.math.BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceAtLeast(0), java.math.RoundingMode.HALF_UP)
    .stripTrailingZeros().toPlainString()
/** Keep actual values, including low readings and duplicate-time disagreements. */
fun heartRateSegments(samples: List<HealthSample>): List<List<HealthSample>> {
    val ordered = samples.distinctBy { Triple(it.originPackage, it.recordId, it.time) }.sortedWith(compareBy({ it.time }, { it.key }))
    val intervals = ordered.zipWithNext().map { (a,b) -> b.time - a.time }.filter { it > 0 }.sorted()
    val median = if (intervals.isEmpty()) 0.0 else if (intervals.size % 2 == 0) (intervals[intervals.size/2-1].toDouble() + intervals[intervals.size/2])/2 else intervals[intervals.size/2].toDouble()
    val threshold = maxOf(300000.0, 3 * median)
    val groups = mutableListOf<MutableList<HealthSample>>()
    ordered.forEach { point ->
        if (groups.isEmpty() || point.time - groups.last().last().time > threshold) groups.add(mutableListOf())
        groups.last().add(point)
    }
    return groups
}
/** Only the exercise source is attributable automatically; never choose an arbitrary app. */
fun associatedHeartSamples(session: HealthSession, samples: List<HealthSample>): List<HealthSample> = samples
    .filter { it.originPackage == session.originPackage && it.time >= session.start && it.time < session.end }
    .distinctBy { Triple(it.originPackage, it.recordId, it.time) }.sortedWith(compareBy({ it.time }, { it.key }))
