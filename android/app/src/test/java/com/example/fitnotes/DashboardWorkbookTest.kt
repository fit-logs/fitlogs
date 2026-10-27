package com.example.fitnotes

import com.example.fitnotes.dashboard.*
import com.example.fitnotes.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class DashboardWorkbookTest {
    private fun sample(): DashboardSnapshotV1 {
        val backup = Backup(exportedAt = 1790000000000, exercises = listOf(Exercise(1, "=HYPERLINK(\"https://example.invalid\")", "synthetic")),
            workouts = (1L..4L).map { id -> Workout(id, 0, title = "Synthetic workout $id", sessionDate = "2026-10-0$id", finished = true) },
            entries = (1L..4L).map { WorkoutExercise(it, it, 1, 0) },
            sets = (1L..4L).map { WorkoutSet(it, it, 40.0 + it, 8, 0, loadMode = LoadMode.EXTERNAL) },
            importedWorkouts = listOf(ImportedWorkout("run", "fixture", "2026-10-01")),
            importedSets = listOf(ImportedSet("run", "run", "run", 0, "Running", "Cardio", null, null, 5.0, "km", 1800.0, "", "{}")),
            bodyMeasurements = listOf(BodyMeasurement("b", "fixture", "b", "2026-10-01", "", "Weight", 70.0, "kg", "Kilograms", "", "{}")))
        return DashboardSnapshot.project(backup, ZoneId.of("Pacific/Auckland"))
    }

    @Test fun nativeTemplateHasStableTabsChartsAndLiteralUserStrings() {
        val snapshot = sample()
        val sheets = DashboardWorkbook.sheets(snapshot)
        assertEquals(sheets.size, sheets.map { it.id }.distinct().size)
        assertEquals(sheets.size, sheets.map { it.table.name }.distinct().size)
        assertEquals(4, DashboardWorkbook.charts(sheets).size)
        assertEquals(listOf(501, 502, 503, 504), DashboardWorkbook.charts(sheets).map { it.getInt("chartId") })
        val payload = DashboardWorkbook.create(snapshot)
        assertFalse(payload.toString().contains("formulaValue"))
        assertTrue(payload.toString().contains("stringValue"))
        val strength = payload.getJSONArray("sheets").getJSONObject(1).getJSONArray("data").getJSONObject(0)
            .getJSONArray("rowData").getJSONObject(1).getJSONArray("values").getJSONObject(1).getJSONObject("userEnteredValue")
        assertTrue(strength.getString("stringValue").startsWith("=HYPERLINK"))
        assertEquals("fitlogs-dashboard", sheets.last().table.rows.first()[1])
        assertFalse(sheets.single { it.id == 104 }.hidden)
        assertTrue(DashboardWorkbook.formatting(sheets).isNotEmpty())
    }

    @Test fun syntheticExampleIncludesBodyChartAndNoMoreThanFourCharts() {
        val snapshot = sample()
        val sheets = DashboardWorkbook.sheets(snapshot)
        assertEquals(4, DashboardWorkbook.charts(sheets).size)
        assertFalse(sheets.single { it.id == 104 }.hidden)
        assertEquals(DashboardWorkbook.create(snapshot).toString(), DashboardWorkbook.create(snapshot).toString())
        // Reproducible payload for inspecting the template without personal data.
        File("build/dashboard-synthetic-create.json").apply { parentFile!!.mkdirs(); writeText(DashboardWorkbook.create(snapshot).toString(2)) }
        File("build/dashboard-synthetic-format.json").writeText(org.json.JSONArray(DashboardWorkbook.formatting(sheets)).toString())
    }
}
