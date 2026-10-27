package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementChartTest {
    private fun record(id: String, date: String, value: Double, unit: String = "kgs", name: String = "Test weight", time: String = "") =
        BodyMeasurement(id, "source", id, date, time, name, value, unit, unit, "Original note", "{}")

    @Test fun exactTypeAndUnitsStaySeparateAndDuplicateRecordsAreRetained() {
        val records = listOf(record("later", "2026-10-15", 74.125, time = "20:12:03"),
            record("a", "2026-09-15", 73.0), record("b", "2026-09-15", 73.0),
            record("pounds", "2026-10-15", 160.0, "lb"), record("other", "2026-10-15", 60.0, "cm", "Waist"),
            record("other spelling", "2026-10-15", 74.125, "kg"))
        val plotted = measurementPoints(records, "Test weight", "kgs")
        assertEquals(listOf("a", "b", "later"), plotted.map { it.record.id })
        assertSame(records[1], plotted.first().record)
        assertEquals("", plotted.first().record.time)
        assertEquals("20:12:03", plotted.last().record.time)
        assertEquals(listOf(73.0, 73.0, 74.125), plotted.map { it.record.value })
        assertTrue(plotted.all { it.segment == 0 })
        assertEquals(6, records.size)
    }

    @Test fun dateRangesAreInclusiveAndUseCalendarSpacingWithoutFillingMissingDays() {
        val records = listOf(record("old", "2025-10-01", 70.0), record("boundary", "2026-09-15", 72.0), record("latest", "2026-10-15", 74.0))
        val plotted = measurementPoints(records, "Test weight", "kgs", progressFrom("1M", "2026-10-15"))
        assertEquals(listOf("boundary", "latest"), plotted.map { it.record.id })
        assertEquals(30.0, progressDateCoordinate(plotted.last().record.date) - progressDateCoordinate(plotted.first().record.date), 0.0)
        assertTrue(measurementPoints(records, "Test weight", "kgs", "2026-10-16").isEmpty())
        assertEquals(3, measurementPoints(records, "Test weight", "kgs", progressFrom("All", "2026-10-15")).size)
    }

    @Test fun disagreeingSameDayReadingsNeverBecomeAnAverageOrAnArbitraryConnectedValue() {
        val points = measurementPoints(listOf(record("before", "2026-10-01", 72.0),
            record("a", "2026-10-02", 73.0), record("b", "2026-10-02", 74.0), record("after", "2026-10-03", 75.0)), "Test weight", "kgs")
        assertEquals(listOf(72.0, 73.0, 74.0, 75.0), points.map { it.record.value })
        assertNotEquals(points[0].segment, points[1].segment)
        assertNotEquals(points[2].segment, points[3].segment)
    }

    @Test fun negativeUnitlessZeroConstantAndSmallChangesHaveUsableAxesAndExtremeValuesDoNotCrash() {
        val negative = listOf(record("a", "2026-10-01", -1.25, "", "Change score"), record("b", "2026-10-02", .5, "", "Change score"))
        val points = measurementPoints(negative, "Change score", "")
        val bounds = plotBounds(points.map { it.record.value })
        assertTrue(bounds.low < -1.25 && bounds.high > .5)
        assertTrue(measurementPlotSupported(points))
        assertEquals("70.125", plotAxisText(70.125, .025))
        assertNotEquals(plotAxisText(70.125, .025), plotAxisText(70.15, .025))
        listOf(listOf(0.0), listOf(72.625, 72.625), listOf(70.125, 70.15)).forEach { values ->
            assertTrue(measurementPlotSupported(measurementPoints(values.mapIndexed { i, v -> record("$i", "2026-10-01", v) }, "Test weight", "kgs")))
        }
        assertFalse(measurementPlotSupported(emptyList()))
        assertFalse(measurementPlotSupported(measurementPoints(listOf(record("large", "2026-10-01", Double.MAX_VALUE)), "Test weight", "kgs")))
    }
}
