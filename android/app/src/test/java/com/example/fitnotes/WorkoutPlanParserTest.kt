package com.example.fitnotes

import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutPlanParserTest {
    private val text = """
        A brief explanation goes here.
        ```text
        WORKOUT_PLAN_V2
        {
          "title": "Synthetic upper body",
          "date": "2026-10-14",
          "durationMinutes": 45,
          "warmUp": "Warm up gradually.",
          "exercises": [
            {"name":"Flat Barbell Bench Press", "note":"Rest two minutes.", "sets":[
              {"repsMin":8,"repsMax":8,"weight":"80.25","unit":"kg"}]},
            {"name":"Lat Pulldown", "sets":[
              {"repsMin":8,"repsMax":10,"weight":"70.50","unit":"lb"}]},
            {"name":"Bodyweight Squat", "sets":[
              {"repsMin":12,"repsMax":12,"unit":"bodyweight"}]},
            {"name":"Lunge", "sets":[{"repsMin":10,"repsMax":10}]}
          ]
        }
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
        val targets = plan.exercises.map { it.setTargets.single() }
        assertEquals(PlanWeight("80.25", "kg"), targets[0].weight)
        assertEquals(PlanWeight("70.50", "lb"), targets[1].weight)
        assertEquals(8, targets[1].repsMin); assertEquals(10, targets[1].repsMax)
        assertEquals(PlanWeight(null, "bodyweight"), targets[2].weight)
        assertEquals(PlanWeight(null, null), targets[3].weight)
        assertEquals("Rest two minutes.", plan.exercises[0].note)
        assertEquals("Warm up gradually.", plan.warmUp)
    }
    @Test fun dateFallbackWhitespaceAndExplicitZeroRemainDistinct() {
        val plan = parse(text.replace("\"date\": \"2026-10-14\",", "").replace("\"weight\":\"80.25\"", "\"weight\" : \"0\""))
        assertEquals("2026-10-20", plan.date)
        assertEquals(PlanWeight("0", "kg"), plan.exercises.first().setTargets.single().weight)
        assertEquals(PlanWeight(null, null), plan.exercises.last().setTargets.single().weight)
        assertEquals(PlanWeight("0.5", "lb"), WorkoutPlanParser.parseWeight(".5 LB"))
    }
    @Test fun missingOldUnsupportedAndMultipleBlocksAreRejected() {
        reject(text.replace("WORKOUT_PLAN_V2\n", ""), "start marker")
        reject(text.replace("END_WORKOUT_PLAN", ""), "END_WORKOUT_PLAN")
        reject(text.replace("WORKOUT_PLAN_V2", "WORKOUT_PLAN_V1"), "Unsupported")
        reject(text.replace("WORKOUT_PLAN_V2", "WORKOUT_PLAN_V99"), "Unsupported")
        reject(text + "\n" + text, "Multiple")
        reject("END_WORKOUT_PLAN\n" + text, "Multiple")
        reject(text + "x".repeat(64001), "64,000")
    }
    @Test fun invalidNumbersDatesFieldsAndDuplicateKeysIdentifyTheField() {
        listOf("0", "-1", "3.5", "1001").forEach { reject(text.replaceFirst("\"repsMin\":8", "\"repsMin\":$it"), "Set 1") }
        listOf("-1", "eight", "80.1234567", "").forEach { reject(text.replace("\"weight\":\"80.25\"", "\"weight\":\"$it\""), "Weight") }
        reject(text.replace("2026-10-14", "2025-02-29"), "Date")
        reject(text.replace("\"durationMinutes\": 45", "\"durationMinutes\": 721"), "Duration")
        reject(text.replace("\"durationMinutes\": 45", "\"durationMinutes\": 1.5"), "durationMinutes")
        reject(text.replace("\"durationMinutes\": 45", "\"activity\":\"running\""), "Unknown")
        reject(text.replace("\"note\":\"Rest two minutes.\"", "\"exerciseId\":123"), "exerciseId")
        reject(text.replaceFirst("\"repsMin\":8", "\"repsMin\":8,\"repsMin\":10"), "Duplicate")
        reject(text.replaceFirst("\"repsMin\":8", "\"repsMin\":8,\"repsMin\":8"), "Duplicate")
        try { parse(text.replace("\"weight\":\"80.25\"", "\"weight\":\"-1\"")); fail() } catch (error: PlanParseException) {
            assertEquals("Flat Barbell Bench Press", error.exercise); assertEquals("Set 1", error.field)
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
