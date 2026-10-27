package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class BodyOverviewTest {
    private fun reading(date: String, value: Double = 80.0, unit: String = "kg", id: String = date, name: String = "Bodyweight", time: String = "") =
        BodyMeasurement(id, "source", id, date, time, name, value, unit, unit, "Synthetic", "{}")
    @Test fun canonicalConversionsAndBmi() {
        assertEquals(24.691358024691358, bmi(80.0, 180.0)!!, 1e-12)
        assertEquals("24.7", bodyDecimal(bmi(80.0, 180.0)!!))
        val cm = heightFromFeet(5.0, 10.866141732283467)!!
        assertEquals(180.0, cm, 1e-10)
        assertEquals(bmi(80.0, 180.0)!!, bmi(weightKg(weightInUnit(80.0, "lb"), "lbs"), cm)!!, 1e-10)
        assertEquals(80.0, weightKg(80.0, "kilograms")!!, 0.0)
        assertNull(weightKg(80.0, "unknown")); assertNull(heightFromFeet(5.0, 12.0)); assertNull(heightFromFeet(5.1, 0.0))
    }
    @Test fun boundariesUseUnroundedValues() {
        listOf(18.4999 to "Underweight range", 18.5 to "Healthy weight range", 24.9999 to "Healthy weight range", 25.0 to "Overweight range", 29.9999 to "Overweight range", 30.0 to "Obesity range").forEach { (bmi, category) -> assertEquals(category, adultBmiCategory(bmi)) }
        assertEquals("25.0", bodyDecimal(24.9999)); assertEquals("Healthy weight range", adultBmiCategory(24.9999))
    }
    @Test fun invalidMissingAndExtremeInputsAreExplicit() {
        for (invalid in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) { assertNull(bmi(invalid, 180.0)); assertNull(bmi(80.0, invalid)) }
        assertEquals("Unavailable", bodyDecimal(weightInUnit(Double.MAX_VALUE, "lb")))
        assertNull(bmi(Double.MAX_VALUE, Double.MIN_VALUE)); assertNull(adultBmiCategory(Double.NaN))
        assertTrue(unusualHeight(18.0)); assertTrue(unusualWeight(800.0)); assertFalse(unusualHeight(180.0))
        assertThrows(IllegalArgumentException::class.java) { validateBodyProfile(BodyProfile(heightCm = -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { validateBodyProfile(BodyProfile(birthDate = "2026-10-16"), "2026-10-15") }
        validateBodyProfile(BodyProfile(heightCm = 300.0)) // Confirmed extremes are stored, never clamped.
    }
    @Test fun adultEligibilityAndTwentiethBirthday() {
        assertNull(ageOn(null, "2026-10-15")); assertFalse(adultBmiEligible(null, "2026-10-15"))
        assertEquals(19, ageOn("2006-10-16", "2026-10-15")); assertFalse(adultBmiEligible("2006-10-16", "2026-10-15"))
        assertTrue(adultBmiEligible("2006-10-15", "2026-10-15")); assertNull(ageOn("2026-10-16", "2026-10-15"))
        assertEquals(19, ageOn("2004-02-29", "2024-02-28")); assertEquals(20, ageOn("2004-02-29", "2024-02-29"))
    }
    @Test fun weightSelectionPreservesOriginalUnitsTimesAndDistinctSameDayReadings() {
        val records = listOf(reading("2026-10-15", id = "a"), reading("2026-10-15", 176.36980974790205, "lb", "b", time = "20:12:00"), reading("2026-10-14", id = "c"),
            reading("2026-10-15", 22.0, name = "Body Fat"), reading("2026-10-15", 0.0), reading("2026-10-15", 2.0, "unknown"))
        val weights = weightReadings(records)
        assertEquals(listOf("c", "a", "b"), weights.map { it.record.id })
        assertEquals("lb", weights.last().record.unit); assertEquals("", weights[1].record.time)
        assertEquals(2, weights.count { it.record.date == "2026-10-15" })
        assertEquals(80.0, dailyWeightMeans(weights).getValue("2026-10-15"), 1e-8)
        val sameDay = listOf(reading("2026-10-15", 80.0, id = "z").copy(createdAt = 100), reading("2026-10-15", 81.0, id = "a").copy(createdAt = 200))
        assertEquals("a", latestWeight(weightReadings(sameDay))!!.record.id)
        val timed = sameDay.mapIndexed { index, r -> r.copy(time = if (index == 0) "22:00:00" else "07:00:00") }
        assertEquals("z", latestWeight(weightReadings(timed))!!.record.id)
        assertEquals("2026-10-14", weightReadings(records.filter { it.date != "2026-10-15" }).last().record.date)
    }
    private fun fullWindow(today: String, kg: Double, offsets: List<Int>) = offsets.map { reading(shiftBodyDay(today, it), kg) }
    @Test fun comparisonUsesDailyMeansAndExactInclusiveWindows() {
        val today = "2026-10-15"
        val records = fullWindow(today, 80.0, listOf(-55, -48, -28)) + fullWindow(today, 79.4, listOf(-27, -7, 0)) +
            listOf(reading(today, 79.0, id = "repeat1"), reading(today, 79.8, id = "repeat2"), reading(shiftBodyDay(today, -56), 500.0), reading(shiftBodyDay(today, 1), 500.0))
        val compared = fourWeekComparison(weightReadings(records), today)!!
        assertEquals("2026-09-18", compared.recent.from); assertEquals("2026-10-15", compared.recent.to)
        assertEquals("2026-08-21", compared.previous.from); assertEquals("2026-09-17", compared.previous.to)
        assertEquals(3, compared.recent.measuredDays); assertEquals(79.4, compared.recent.meanKg, 1e-10)
        assertEquals("0.6 kg lower than the previous four weeks", compared.text("kg"))
        assertEquals("1.3 lb lower than the previous four weeks", compared.text("lb"))
        assertEquals("0.6 kg higher than the previous four weeks", compared.copy(recent = compared.recent.copy(meanKg = 80.6)).text("kg"))
        assertEquals("0.1 kg higher than the previous four weeks", compared.copy(recent = compared.recent.copy(meanKg = 80.05)).text("kg"))
        assertEquals("0.1 kg lower than the previous four weeks", compared.copy(recent = compared.recent.copy(meanKg = 79.95)).text("kg"))
        assertEquals("About the same as the previous four weeks", compared.copy(recent = compared.recent.copy(meanKg = 80.04)).text("kg"))
    }
    @Test fun sparseOrNarrowWindowsOmitComparison() {
        val today = "2026-10-15"
        assertNull(fourWeekComparison(emptyList(), today))
        assertNull(fourWeekComparison(weightReadings(fullWindow(today, 80.0, listOf(-55, -48, -28, -27, 0))), today))
        assertNull(fourWeekComparison(weightReadings(fullWindow(today, 80.0, listOf(-55, -54, -53, -27, -7, 0))), today))
        assertNotNull(fourWeekComparison(weightReadings(fullWindow(today, 80.0, listOf(-55, -48, -28, -27, -20, 0))), today))
    }
    @Test fun deviceLocalCalendarControlsBoundaryIncludingDst() {
        val instant = 1791977400000L // Same instant, different local calendar dates.
        val nz = bodyLocalDate(instant, TimeZone.getTimeZone("Pacific/Auckland"))
        val la = bodyLocalDate(instant, TimeZone.getTimeZone("America/Los_Angeles"))
        assertNotEquals(nz, la)
        val original = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland")); assertEquals("2026-09-27", shiftBodyDay("2026-09-28", -1)); assertEquals(nz, bodyLocalDate(instant)) } finally { TimeZone.setDefault(original) }
    }
}
