package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutPlanParserTest {
    private val text = """
        A brief explanation goes here.
        ```text
        WORKOUT_PLAN_V1
        Title: Synthetic upper body
        Date: 2026-10-14
        Duration: 45 min
        Note: Warm up gradually.

        Exercise: Flat Barbell Bench Press
        Sets: 3
        Reps: 8
        Weight: 80.25 kg
        Note: Rest two minutes.

        Exercise: Lat Pulldown
        Sets: 3
        Reps: 8-10
        Weight: 70.50 lb

        Exercise: Bodyweight Squat
        Sets: 2
        Reps: 12
        Weight: bodyweight

        Exercise: Lunge
        Sets: 2
        Reps: 10
        END_WORKOUT_PLAN
        ```
        Additional explanation.
    """.trimIndent()
    private fun parse(value: String = text) = WorkoutPlanParser.parse(value, "2026-10-20")
    private fun reject(value: String, fragment: String = "") {
        try { parse(value); fail("Expected rejection") } catch (error: IllegalArgumentException) {
            assertTrue("Unexpected message: ${error.message}", error.message.orEmpty().contains(fragment, true))
        }
    }
    @Test fun fencedLfCrLfDecimalUnitsRangesAndOmittedWeightsAreLossless() {
        val plan = parse()
        assertEquals(plan, parse(text.replace("\n", "\r\n")))
        assertEquals("2026-10-14", plan.date)
        assertEquals(45, plan.durationMinutes)
        assertEquals(PlanWeight("80.25", "kg"), plan.exercises[0].weight)
        assertEquals(PlanWeight("70.50", "lb"), plan.exercises[1].weight)
        assertEquals(8, plan.exercises[1].repsMin); assertEquals(10, plan.exercises[1].repsMax)
        assertEquals(PlanWeight(null, "bodyweight"), plan.exercises[2].weight)
        assertEquals(PlanWeight(null, null), plan.exercises[3].weight)
        assertEquals("Rest two minutes.", plan.exercises[0].note)
        assertEquals("Warm up gradually.", plan.note)
    }
    @Test fun dateFallbackWhitespaceAndExplicitZeroRemainDistinct() {
        val plan = parse(text.replace("Date: 2026-10-14\n", "").replace("Weight: 80.25 kg", " Weight : 0 kg  ").replace("Reps: 8-10", "Reps : 8 – 10"))
        assertEquals("2026-10-20", plan.date)
        assertEquals(PlanWeight("0", "kg"), plan.exercises.first().weight)
        assertEquals(PlanWeight(null, null), plan.exercises.last().weight)
        assertEquals(PlanWeight("0.5", "lb"), WorkoutPlanParser.parseWeight(".5 LB"))
    }
    @Test fun missingUnsupportedAndMultipleBlocksAreRejected() {
        reject(text.replace("WORKOUT_PLAN_V1\n", ""), "start marker")
        reject(text.replace("END_WORKOUT_PLAN", ""), "END_WORKOUT_PLAN")
        reject(text.replace("WORKOUT_PLAN_V1", "WORKOUT_PLAN_V2"), "Unsupported")
        reject(text + "\n" + text, "Multiple")
        reject("END_WORKOUT_PLAN\n" + text, "Multiple")
        reject(text + "x".repeat(64001), "64,000")
    }
    @Test fun invalidNumbersDatesFieldsAndConflictingDuplicatesIdentifyTheField() {
        listOf("0", "-1", "3.5", "101").forEach { reject(text.replaceFirst("Sets: 3", "Sets: $it"), "sets") }
        listOf("0", "-2", "10-8", "8-8", "8-10-12", "1.5").forEach { reject(text.replaceFirst("Reps: 8\n", "Reps: $it\n"), "reps") }
        listOf("-1 kg", "80", "eight kg", "5 stone", "80.1234567 kg", "").forEach { reject(text.replace("Weight: 80.25 kg", "Weight: $it"), "weight") }
        reject(text.replace("Date: 2026-10-14", "Date: 2025-02-29"), "date")
        reject(text.replace("Duration: 45 min", "Duration: 0 min"), "duration")
        reject(text.replace("Duration: 45 min", "Duration: 1 hour"), "duration")
        reject(text.replace("Title: Synthetic upper body", "Title: Synthetic upper body\nActivity: running"), "Unknown")
        reject(text.replace("Sets: 3", "Sets: 3\nExerciseId: 123"), "ExerciseId")
        reject(text.replaceFirst("Reps: 8\n", "Reps: 8\nReps: 10\n"), "Conflicting")
        assertEquals(8, parse(text.replaceFirst("Reps: 8\n", "Reps: 8\nReps: 8\n")).exercises.first().repsMin)
        try { parse(text.replace("Weight: 80.25 kg", "Weight: -1 kg")); fail() } catch (error: PlanParseException) {
            assertEquals("Flat Barbell Bench Press", error.exercise); assertNotNull(error.line)
        }
    }
    @Test fun catalogueMatchingUsesOnlyExactNormalisationAndExplicitAliases() {
        val catalogue = listOf(Exercise(1, "Flat Barbell Bench Press", "flat barbell bench press"), Exercise(2, "Dumbbell Shoulder Press", "dumbbell shoulder press"))
        assertEquals(ExerciseMatchKind.EXACT, matchPlanExercise("  FLAT   Barbell Bench Press ", catalogue).kind)
        assertEquals(ExerciseMatchKind.UNKNOWN, matchPlanExercise("DB press", catalogue).kind)
        assertEquals(ExerciseMatchKind.UNKNOWN, matchPlanExercise("Incline Barbell Bench Press", catalogue).kind)
        assertEquals(ExerciseMatchKind.ALIAS, matchPlanExercise("Bench", catalogue, aliases = mapOf("Bench" to setOf(1L))).kind)
        assertEquals(ExerciseMatchKind.AMBIGUOUS, matchPlanExercise("press", catalogue, aliases = mapOf("press" to setOf(1L, 2L))).kind)
        assertEquals(ExerciseMatchKind.INCOMPATIBLE, matchPlanExercise("Flat Barbell Bench Press", catalogue, setOf(1L)).kind)
        val ambiguous = catalogue + Exercise(3, "Flat  Barbell Bench Press", "flat  barbell bench press")
        assertEquals(ExerciseMatchKind.AMBIGUOUS, matchPlanExercise("Flat Barbell Bench Press", ambiguous).kind)
    }
}
