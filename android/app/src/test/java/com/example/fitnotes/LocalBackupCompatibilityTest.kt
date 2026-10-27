package com.example.fitnotes

import com.example.fitnotes.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalBackupCompatibilityTest {
    @Test fun exportedLocalPlanRetainsReleasedEffortTargets() {
        val target = PlanSetTarget(8, 10, PlanWeight("42.5", "kg"), rir = 2.0, rpe = 8.0)
        val plan = PlanSpec("Synthetic plan", "", 40, "Keep note", listOf(PlanPrescription("Press", listOf(target))), "a".repeat(64))
        val parsed = WorkoutPlanParser.parse(WorkoutPlanFormat.encode(plan), "")
        assertEquals(target, parsed.exercises.single().setTargets.single())
    }

    @Test fun developmentVersionNineBackupRetainsProfileRunningLinksAndRecordedSets() {
        val backup = Backup(exercises = listOf(Exercise(1, "Synthetic press", "synthetic press")),
            workouts = listOf(Workout(1, 1000, 2000, notes = "Keep notes")),
            entries = listOf(WorkoutExercise(1, 1, 1, 0)),
            sets = listOf(WorkoutSet(1, 1, 42.5, 8, 0, comment = "Keep actual")),
            bodyProfile = BodyProfile(heightCm = 180.0), runLinks = listOf(RunLink("source", "local:1")))
        val legacy = JSONObject(BackupCodec.encode(backup)).put("version", 9)
        for (key in listOf("programs", "programDays", "programSessions", "programSteps", "loadProfiles", "setLoadContexts", "sessionLoadLocks")) legacy.remove(key)
        val workout = legacy.getJSONArray("workouts").getJSONObject(0)
        for (key in listOf("lastActionAt", "autoFinishedAt", "resumeStartedAt", "resumeBaseSeconds", "staleSuppressedUntil",
            "timingEdited", "timingEstimated", "durationUnknown", "historicalDraft", "autoFinishAcknowledged", "timingNote", "draftSourceKey")) workout.remove(key)
        assertEquals(backup, BackupCodec.decode(legacy.toString()))
    }
}
