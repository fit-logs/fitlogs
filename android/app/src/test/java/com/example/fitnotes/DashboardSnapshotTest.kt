package com.example.fitnotes

import com.example.fitnotes.dashboard.*
import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DashboardSnapshotTest {
    private val start = Instant.parse("2026-10-04T23:30:00Z").toEpochMilli()
    private fun fixture() = Backup(exportedAt = start,
        exercises = listOf(Exercise(1, "Squat", "squat")),
        workouts = listOf(Workout(1, start, start + 3600000, "Completed", "private workout note"),
            Workout(2, start, title = "Unfinished"), Workout(3, 0, title = "Draft", finished = true, historicalDraft = true, sessionDate = "2026-10-01")),
        entries = (1L..3L).map { WorkoutExercise(it, it, 1, 0) },
        sets = listOf(WorkoutSet(1, 1, 80.0, 8, 0, comment = "private set note", loadMode = LoadMode.EXTERNAL),
            WorkoutSet(2, 1, 200.0, 3, 1, loadMode = LoadMode.EXTERNAL, isWarmUp = true),
            WorkoutSet(3, 1, 999.0, 8, 2, weightSpecified = false),
            WorkoutSet(4, 1, 300.0, 5, 3, loadMode = LoadMode.ASSISTANCE),
            WorkoutSet(5, 2, 999.0, 8, 0), WorkoutSet(6, 3, 999.0, 8, 0)),
        bodyMeasurements = listOf(BodyMeasurement("body", "fixture", "body", "2026-10-01", "", "Weight", 70.0, "kg", "Kilograms", "private body note", "RAW BODY SECRET")),
        bodyProfile = BodyProfile(heightCm = 180.0, birthDate = "1990-01-01"))

    @Test fun completedActualsUseExistingMetricsAndExcludeUnfinishedDraftsAndPlans() {
        val snapshot = DashboardSnapshot.project(fixture(), zone = ZoneId.of("Pacific/Auckland"))
        assertEquals(1, snapshot.table("_sessions").rows.size)
        assertEquals("2026-10-05", snapshot.table("_sessions").rows.single()[1])
        assertEquals(4, snapshot.table("_sets").rows.size)
        assertNull(snapshot.table("_sets").rows.single { it[0] == "set:3" }[6])
        val strength = snapshot.table("Strength progress").rows.single()
        assertEquals(600.0 + 640.0, strength[4]) // Existing statistics include actual warm-up volume.
        assertEquals(0.0, strength[5]) // Assistance never enters added-bodyweight volume.
        assertEquals(0.0, strength[6]) // Unspecified load contributes no invented volume.
    }

    @Test fun notesAndBodyAreOptInAndRawPayloadsAndProfilesNeverAppear() {
        val backup = fixture()
        val ordinary = DashboardSnapshot.project(backup)
        assertTrue(ordinary.table("_measurements").rows.isEmpty())
        assertFalse(ordinary.toString().contains("private"))
        assertFalse(ordinary.toString().contains("1990-01-01"))
        val opted = DashboardSnapshot.project(backup, DashboardOptions(includeNotes = true, includeBody = true))
        assertEquals(1, opted.table("_measurements").rows.size)
        assertTrue(opted.toString().contains("private workout note"))
        assertFalse(opted.toString().contains("RAW BODY SECRET"))
        assertEquals(80.0 * (1 + 8 / 30.0), opted.table("Strength progress").rows.single()[8])
    }

    @Test fun projectionIsStableAndDoesNotInventDateOnlyTimesOrUnknownLoads() {
        val backup = fixture().copy(importedWorkouts = listOf(ImportedWorkout("old", "fixture", "2026-09-30")),
            importedSets = listOf(ImportedSet("old-set", "old", "row", 0, "Squat", "Legs", 0.0, 10, null, null, null, "", "RAW SOURCE SECRET")))
        val first = DashboardSnapshot.project(backup, zone = ZoneId.of("UTC"))
        assertEquals(first, DashboardSnapshot.project(backup.copy(workouts = backup.workouts.reversed(), sets = backup.sets.reversed()), zone = ZoneId.of("UTC")))
        val imported = first.table("_sessions").rows.single { it[0] == "import:old" }
        assertNull(imported[4]); assertNull(imported[5]); assertNull(imported[6])
        val importedSet = first.table("_sets").rows.single { it[0] == "import:old-set" }
        assertEquals(0.0, importedSet[6]); assertEquals(LoadMode.UNKNOWN, importedSet[8])
        assertFalse(first.toString().contains("RAW SOURCE SECRET"))
    }

    @Test fun mergedRunsHaveOneActivityAndUnknownDistanceStaysBlank() {
        val backup = Backup(exportedAt = start, exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList(),
            importedWorkouts = listOf(ImportedWorkout("one", "fixture", "2026-10-01"), ImportedWorkout("two", "fixture", "2026-10-01")),
            importedSets = listOf("one", "two").map { id -> ImportedSet(id, id, id, 0, "Running", "Cardio", null, null, null, null, 1800.0, "", "{}") },
            runLinks = listOf(RunLink("merge:two", "fitnotes:one", retiredKey = "fitnotes:two")))
        val snapshot = DashboardSnapshot.project(backup)
        assertEquals(1, snapshot.table("_activities").rows.size)
        assertEquals("fitnotes:one", snapshot.table("_activities").rows.single()[0])
        assertNull(snapshot.table("_activities").rows.single()[8])
        assertNull(snapshot.table("_weekly").rows.single()[4])
        assertEquals(1, snapshot.table("_weekly").rows.single()[5])
    }
}
