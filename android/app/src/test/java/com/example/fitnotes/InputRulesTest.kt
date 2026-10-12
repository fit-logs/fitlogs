package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class InputRulesTest {
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
        listOf(json + " trailing", json.replace("\"version\": 4", "\"version\": 4, \"version\": 4"),
            json.replace("\"version\"", "'version'"), json.replace("\"version\": 4", "\"version\": 04"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(it) } }
    }
}
