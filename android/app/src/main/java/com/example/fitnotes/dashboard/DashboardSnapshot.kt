package com.example.fitnotes.dashboard

import com.example.fitnotes.AppData
import com.example.fitnotes.ImportedData
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.notebookSessions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

data class DashboardOptions(val includeNotes: Boolean = false, val includeBody: Boolean = false)
data class DashboardTable(val name: String, val columns: List<String>, val rows: List<List<Any?>>) {
    init {
        require(rows.all { it.size == columns.size })
        require(rows.flatten().all { it == null || it is String || it is Boolean || it is Number && it.toDouble().isFinite() })
    }
    val values get() = listOf(columns) + rows
}
data class DashboardSnapshotV1(val capturedAt: Long, val timeZone: String, val options: DashboardOptions,
    val tables: List<DashboardTable>) {
    val schemaVersion = 1
    fun table(name: String) = tables.single { it.name == name }
}

/** Projection only: no Room writes, credentials, photos, profiles or raw source payloads. */
object DashboardSnapshot {
    fun project(backup: Backup, options: DashboardOptions = DashboardOptions(),
        zone: ZoneId = ZoneId.systemDefault()): DashboardSnapshotV1 {
        val exercises = backup.exercises.associateBy { it.id }
        val sets = backup.sets.groupBy { it.entryId }
        val entries = backup.entries.groupBy { it.workoutId }
        // Source summaries need their session metadata, never heart-rate samples.
        val health = backup.healthSessions.map { HealthDetail(it, emptyList()) }
        val localHealth = health.groupBy { it.session.workoutId }
        val importedHealth = health.groupBy { it.session.importedWorkoutId }
        val importedSets = backup.importedSets.groupBy { it.workoutId }
        val data = AppData(exercises = backup.exercises,
            workouts = backup.workouts.filter { it.isComplete }.map { workout ->
                WorkoutDetail(workout, entries[workout.id].orEmpty().mapNotNull { entry ->
                    exercises[entry.exerciseId]?.let { EntryDetail(entry, it, sets[entry.id].orEmpty()) }
                }, localHealth[workout.id].orEmpty())
            }, loading = false,
            imported = ImportedData(workouts = backup.importedWorkouts.map { workout ->
                ImportedWorkoutDetail(workout, importedSets[workout.id].orEmpty(), importedHealth[workout.id].orEmpty())
            }), runLinks = backup.runLinks, exerciseAliases = backup.exerciseAliases,
            exerciseMappings = backup.exerciseMappings, exerciseRedirects = backup.exerciseRedirects)
        fun localDate(detail: WorkoutDetail): String {
            val workout = detail.workout
            val source = preferredActivitySource(detail.health)?.session
            val useSource = source?.isSourceCreated == true && workout.knownStart == null && !workout.timingEdited
            if (useSource) return Instant.ofEpochMilli(source!!.start)
                .atZone(source.startOffsetSeconds?.let(ZoneOffset::ofTotalSeconds) ?: zone).toLocalDate().toString()
            return workout.sessionDate.ifBlank {
                (workout.knownStart ?: workout.completedAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }.orEmpty()
            }
        }
        val sessions = notebookSessions(data).map { session -> session.local?.let { session.copy(date = localDate(it)) } ?: session }
            .filter { it.completed && validHistoryDate(it.date) }
            .sortedWith(compareBy({ it.date }, { it.key }))
        val observations = observations(sessions).filter { it.activity == "strength" }
        val statistics = StrengthStatistics.calculate(observations)
        val localDates = data.workouts.associate { "local:${it.workout.id}" to localDate(it) }
        val runs = runningRecords(data).map { run -> localDates[run.parentKey]?.let { run.copy(date = it) } ?: run }
            .sortedWith(compareBy({ it.date }, { it.key }))
        fun note(value: String) = value.takeIf { options.includeNotes }.orEmpty()
        fun utc(value: Long?) = value?.let { Instant.ofEpochMilli(it).toString() }
        fun week(date: String) = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        val sessionRows = sessions.map { session -> listOf(session.key, session.date, if (session.imported != null) "Imported workout" else session.activityTitle,
            session.title, utc(session.startedMillis), utc(session.completedMillis), session.duration,
            session.count, if (session.local == null) "Imported history" else session.local.health.map { it.session.sourceName }.distinct().sorted().joinToString().ifBlank { "FitLogs" }, note(session.notes)) }
        val setRows = sessions.flatMap { session -> session.exercises.flatMap { exercise -> exercise.sets.map { set ->
            val key = exercise.canonicalId?.let { "exercise:$it" } ?: "source:${session.imported?.workout?.importId}:${exercise.name}:${exercise.category}"
            listOf(if (set.local != null) set.key else "import:${set.key}", session.key, session.date,
                key, exercise.name, exercise.category, set.weight, set.reps,
                set.local?.loadMode ?: LoadMode.UNKNOWN, set.local?.isWarmUp ?: false,
                distanceInMeters(set.distance, set.unit), set.seconds, note(set.comment))
        } } }
        val activityRows = runs.map { run -> listOf(run.key, run.parentKey, run.date, run.subtype, run.title,
            utc(run.start), utc(run.end), run.offsetSeconds, run.distanceMetres, run.preferredTime?.second,
            run.preferredTime?.first?.name, run.pace, run.sourceLabel, note(run.notes)) } +
            sessions.filter { it.local != null && it.activity !in setOf("running", "strength") }.map { session ->
                val summary = session.summary!!
                listOf(session.key, session.key, session.date, summary.sport, session.title,
                    utc(summary.start), utc(summary.end), null, summary.distanceMeters, summary.seconds,
                    summary.durationBasis, null, summary.preferred?.session?.sourceName ?: "FitLogs", note(session.notes))
            }
        val bodyRows = if (options.includeBody) backup.bodyMeasurements.filter { validHistoryDate(it.date) }
            .sortedWith(compareBy({ it.date }, { it.time }, { it.id })).map { row ->
                listOf(row.id, row.date, row.time, row.name, row.value, row.unit, note(row.comment))
            } else emptyList()
        val strengthRows = statistics.exercises.sortedWith(compareBy({ it.name }, { it.key })).map { row -> listOf(row.key, row.name, row.setCount, row.totalReps,
            row.externalVolume, row.addedBodyweightVolume, row.unknownModeVolume, row.heaviestExternal, row.estimatedOneRepMax) }
        val strengthTrend = observations.flatMap { observation -> observation.results.groupBy { it.exerciseKey }.map { (key, results) ->
            listOf(observation.date, observation.id, key, results.first().exerciseName,
                results.filter { it.mode == LoadMode.EXTERNAL }.mapNotNull { it.weightKg }.maxOrNull(),
                results.mapNotNull { estimatedOneRepMax(it.weightKg, it.reps, it.mode, it.warmUp) }.maxOrNull(),
                results.sumOf { it.reps })
        } }.sortedWith(compareBy({ it[0].toString() }, { it[1].toString() }, { it[2].toString() }))
        val sessionsByWeek = sessions.groupBy { week(it.date) }
        val runsByWeek = runs.groupBy { week(it.date) }
        val weeklyRows = (sessionsByWeek.keys + runsByWeek.keys).sorted().map { monday ->
            listOf(monday, sessionsByWeek[monday].orEmpty().size,
                sessionsByWeek[monday].orEmpty().sumOf { it.count }, runsByWeek[monday].orEmpty().size,
                runsByWeek[monday].orEmpty().mapNotNull { it.distanceMetres }.takeIf { it.isNotEmpty() }?.sum()?.div(1000.0),
                runsByWeek[monday].orEmpty().count { it.distanceMetres == null })
        }
        return DashboardSnapshotV1(backup.exportedAt, zone.id, options, listOf(
            DashboardTable("_sessions", listOf("session_id", "date", "activity", "title", "start_utc", "end_utc", "duration_seconds", "actual_sets", "source", "notes"), sessionRows),
            DashboardTable("_sets", listOf("set_id", "session_id", "date", "exercise_key", "exercise", "category", "weight_kg", "reps", "load_mode", "warm_up", "distance_m", "recorded_seconds", "notes"), setRows),
            DashboardTable("_activities", listOf("activity_id", "session_id", "date", "sport", "title", "start_utc", "end_utc", "offset_seconds", "distance_m", "duration_seconds", "duration_basis", "pace_seconds_per_km", "source", "notes"), activityRows.sortedWith(compareBy({ it[2].toString() }, { it[0].toString() }))),
            DashboardTable("_measurements", listOf("measurement_id", "date", "time", "measurement", "value", "unit", "notes"), bodyRows),
            DashboardTable("Strength progress", listOf("exercise_key", "exercise", "actual_sets", "reps", "external_kg_reps", "added_bodyweight_kg_reps", "unknown_mode_kg_reps", "max_external_kg", "estimated_1rm_kg"), strengthRows),
            DashboardTable("_strength_trend", listOf("date", "session_id", "exercise_key", "exercise", "max_external_kg", "estimated_1rm_kg", "reps"), strengthTrend),
            DashboardTable("_weekly", listOf("week_monday", "completed_sessions", "actual_sets", "runs", "recorded_run_km", "runs_without_distance"), weeklyRows),
        ))
    }
}
