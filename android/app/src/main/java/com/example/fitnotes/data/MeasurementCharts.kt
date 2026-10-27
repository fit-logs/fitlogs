package com.example.fitnotes.data

data class MeasurementPoint(val record: BodyMeasurement, val segment: Int)

/** Calendar-date points, never invented recording times or daily averages. */
fun measurementPoints(records: List<BodyMeasurement>, name: String, unit: String, from: String? = null): List<MeasurementPoint> {
    val days = records.filter { it.name == name && it.unit == unit && (from == null || it.date >= from) }
        .groupBy { it.date }.toSortedMap()
    var segment = 0
    return days.flatMap { (_, values) ->
        // Different readings on one day have no unique daily value to connect.
        val differentValues = values.map { it.value }.distinct().size > 1
        if (differentValues) segment++
        val points = values.sortedWith(compareBy({ it.time }, { it.id })).map { MeasurementPoint(it, segment) }
        if (differentValues) segment++
        points
    }
}

/** Keep unusual recorded values in the list even when ordinary axes cannot represent them. */
fun measurementPlotSupported(points: List<MeasurementPoint>): Boolean {
    if (points.isEmpty()) return false
    return runCatching {
        val bounds = plotBounds(points.map { it.record.value })
        bounds.low.isFinite() && bounds.high.isFinite() && bounds.step.isFinite() && bounds.step > 0 &&
            (bounds.high - bounds.low).isFinite() && bounds.high > bounds.low &&
            listOf(bounds.low, bounds.high, bounds.low + bounds.step).all { plotAxisText(it, bounds.step).length <= 16 }
    }.getOrDefault(false)
}
