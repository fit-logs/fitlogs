package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class InputRulesTest {
    @Test fun measurementTimesAcceptMinutesSecondsAndDateOnlyWithoutWeakeningImportValidation() {
        assertEquals("20:12:00", normalizeMeasurementTime("20:12"))
        assertEquals("20:12:03", normalizeMeasurementTime("20:12:03"))
        assertEquals("00:00:00", normalizeMeasurementTime(" 00:00 "))
        assertEquals("23:59:59", normalizeMeasurementTime(" 23:59:59 "))
        assertEquals("", normalizeMeasurementTime(""))
        assertEquals("", normalizeMeasurementTime("  "))
        assertFalse(validHistoryTime("20:12"))
        assertTrue(validHistoryTime(normalizeMeasurementTime("20:12")!!))
    }
    @Test fun invalidMeasurementTimesAreRejectedRatherThanAdjusted() {
        listOf("24:00", "20:60", "20:12:60", "-1:12", "2:12", "20:1", "20.12", "20:12 PM", "20:12:00:00", "٢٠:١٢")
            .forEach { assertNull("Unexpectedly accepted $it", normalizeMeasurementTime(it)) }
    }
    @Test fun acceptsBodyweightAndDecimalKilograms() {
        assertEquals(SetValues(0.0, 12), InputRules.parseSet("0", "12"))
        assertEquals(SetValues(2.5, 8), InputRules.parseSet("2,5", "8"))
        assertEquals("2.5", weightText(2.5))
    }
    @Test fun rejectsNonFiniteNegativeFractionalAndMissingValues() {
        listOf("NaN", "Infinity", "-1", "1e5", "", "10001").forEach {
            assertThrows(IllegalArgumentException::class.java) { InputRules.parseSet(it, "8") }
        }
        listOf("0", "-1", "1.5", "", "10001", "999999999999999").forEach {
            assertThrows(IllegalArgumentException::class.java) { InputRules.parseSet("0", it) }
        }
    }
    @Test fun emptyBackupRoundTripsAndRejectsMissingFields() {
        val backup = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList())
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("{}") }
        val json = BackupCodec.encode(backup)
        listOf(json + " trailing", json.replace("\"version\": ${backup.version}", "\"version\": ${backup.version}, \"version\": ${backup.version}"),
            json.replace("\"version\"", "'version'"), json.replace("\"version\": ${backup.version}", "\"version\": 0${backup.version}"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(it) } }
    }
}
