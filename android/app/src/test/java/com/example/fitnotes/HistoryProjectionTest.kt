package com.example.fitnotes

import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class HistoryProjectionTest {
    private fun history(): AppData {
        val workouts = (1..1600).map { n ->
            val id = "workout:$n"
            ImportedWorkoutDetail(ImportedWorkout(id, "fixture", "2026-10-01",
                recordedStart = "2026-10-01T12:00:00+13:00", recordedEnd = "2026-10-01T13:00:00+13:00"),
                (0..5).map { s -> ImportedSet("$n:$s", id, "$n:$s", s,
                    if (s == 0) "Running" else "Squat", if (s == 0) "Cardio" else "Legs",
                    if (s == 0) null else 80.0, if (s == 0) null else 8,
                    if (s == 0) 5.0 else null, if (s == 0) "km" else null,
                    if (s == 0) 1800.0 else null, "", "{}") })
        }
        return AppData(imported = ImportedData(workouts = workouts), loading = false)
    }

    @Test fun largeHistoryPreservesSessionsAndMixedRunIdentity() {
        val data = history()
        var sessions = emptyList<NotebookSession>()
        var runs = emptyList<RunRecord>()
        repeat(2) { notebookSessions(data); runningRecords(data) }
        val times = (1..5).map { measureNanoTime { sessions = notebookSessions(data); runs = runningRecords(data) } / 1_000_000.0 }.sorted()
        println("Synthetic 9600-set history projection median: ${times[2]} ms")
        assertEquals(1600, sessions.size)
        assertEquals(9600, sessions.sumOf { it.count })
        assertEquals(1600, runs.size)
        assertTrue(runs.all { it.start == null && it.distanceMetres == 5000.0 })
        assertEquals(3_600_000L, sessions.first().completedMillis!! - sessions.first().startedMillis!!)
    }

    @Test fun copyingSessionRecomputesCachedTiming() {
        val session = notebookSessions(history()).first()
        assertEquals(3600.0, session.duration!!, 0.0)
        val changed = session.copy(imported = session.imported!!.copy(workout = session.imported.workout.copy(recordedEnd = "2026-10-01T14:00:00+13:00")))
        assertEquals(7200.0, changed.duration!!, 0.0)
        assertNotEquals(session.completedMillis, changed.completedMillis)
    }

    @Test fun localEditsReuseImportedRowsButIdentityChangesRebuildThem() = runBlocking {
        val initial = history()
        val snapshots = MutableStateFlow(initial)
        val results = Channel<NotebookProjection>(Channel.UNLIMITED)
        val job = launch { notebookProjections(snapshots, flowOf(emptyList())).collect { results.send(it) } }
        try {
            val first = withTimeout(10000) { results.receive() }
            snapshots.value = initial.copy(workouts = listOf(WorkoutDetail(Workout(id = 1, startedAt = 1000), emptyList())))
            val second = withTimeout(10000) { results.receive() }
            val imported = first.sessions.first { it.imported != null }
            assertSame(imported, second.sessions.first { it.key == imported.key })
            assertEquals(snapshots.value, second.data)
            snapshots.value = snapshots.value.copy(exercises = listOf(Exercise(42, "Squat", "squat")))
            val third = withTimeout(10000) { results.receive() }
            val rebuilt = third.sessions.first { it.key == imported.key }
            assertNotSame(imported, rebuilt)
            assertEquals(42L, rebuilt.exercises.first { it.name == "Squat" }.canonicalId)
        } finally { job.cancelAndJoin(); results.close() }
    }
}
