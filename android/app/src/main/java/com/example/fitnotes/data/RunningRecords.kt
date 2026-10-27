package com.example.fitnotes.data

import com.example.fitnotes.AppData

fun runningRecords(data: AppData): List<RunRecord> {
    val index = ExerciseIdentityIndex(data)
    val exercisesById = data.exercises.associateBy { it.id }
    val raw = mutableListOf<RunRecord>()
    data.workouts.filter { it.workout.isComplete && it.workout.activityType == "running" }.forEach { detail ->
        val w = detail.workout; val key = "local:${w.id}"
        val health = detail.health.filter { it.session.activityType == "running" }
        val source = health.sortedWith(compareByDescending<HealthDetail> { it.session.clientRecordVersion }.thenByDescending { it.session.modifiedAt }.thenByDescending { it.session.importedAt }).firstOrNull()?.let { healthRun(it, key, key) }
        val hasLocalValues = w.distance != null || w.durationSeconds != null || w.knownStart != null
        val local = RunRecord(key, key, w.title.ifBlank { source?.title ?: "Running" }, w.sessionDate.ifBlank { w.knownStart?.let { runLocalDate(it) } ?: source?.date.orEmpty() },
            w.knownStart, w.completedAt, distanceMetres = distanceInMetres(w.distance, w.distanceUnit),
            times = RunTimes(elapsed = w.knownStart?.let { start -> w.completedAt?.takeIf { it > start }?.let { (it - start) / 1000.0 } }, recorded = w.durationSeconds), notes = w.notes,
            sources = health, sourceLabel = if (health.isEmpty()) "FitNotes" else health.map { it.session.sourceName }.distinct().joinToString(), subtype = source?.subtype ?: "Running")
        raw += if (!hasLocalValues && source != null) source.copy(date = w.sessionDate.ifBlank { source.date }, title = w.title.ifBlank { source.title }, notes = listOf(w.notes, source.notes).filter { it.isNotBlank() }.distinct().joinToString("\n"), sources = health) else local
    }
    data.workouts.filter { it.workout.isComplete && it.workout.activityType != "running" }.forEach { detail ->
        detail.entries.filter { isRunningName(it.exercise.name) }.forEach { entry ->
            entry.sets.sortedWith(compareBy<WorkoutSet>{it.position}.thenBy{it.id}).forEach { set ->
                if (set.distance != null || set.durationSeconds != null) raw += RunRecord("set:${set.id}", "local:${detail.workout.id}", entry.exercise.name,
                    localWorkoutDate(detail.workout), distanceMetres = distanceInMetres(set.distance,set.distanceUnit), times = RunTimes(recorded=set.durationSeconds), notes=set.comment)
            }
        }
    }
    data.imported.workouts.forEach { detail ->
        val firstComponent = raw.size
        detail.sets.sortedBy { it.sourceOrder }.filter { s ->
            val canonical = index.source(detail.workout.importId, s.exercise, s.category)
            isRunningName(s.exercise) || exercisesById[canonical]?.name?.let(::isRunningName) == true
        }.forEach { s ->
            val key = "fitnotes:${s.id}"
            val exactStart = if(detail.sets.size==1) detail.workout.recordedStart?.let { runCatching { java.time.OffsetDateTime.parse(it) }.getOrNull() } else null
            val exactEnd = if(detail.sets.size==1) detail.workout.recordedEnd?.let { runCatching { java.time.OffsetDateTime.parse(it) }.getOrNull() } else null
            val elapsed = if(exactStart!=null && exactEnd!=null && exactEnd>exactStart) java.time.Duration.between(exactStart,exactEnd).toMillis()/1000.0 else null
            // An aggregate workout timestamp does not timestamp a cardio component in a mixed session.
            raw += RunRecord(key, "import:${detail.workout.id}", s.exercise, detail.workout.date,
                start=exactStart?.toInstant()?.toEpochMilli(),end=exactEnd?.toInstant()?.toEpochMilli(),offsetSeconds=exactStart?.offset?.totalSeconds, distanceMetres = distanceInMetres(s.distance, s.distanceUnit), times = RunTimes(elapsed=elapsed,recorded = s.durationSeconds),
                notes = listOf(detail.workout.notes, s.comment).filter { it.isNotBlank() }.distinct().joinToString("\n"), importedSetId = s.id,
                subtype = if (s.exercise.contains("trail", true)) "Trail run" else if (s.exercise.contains("treadmill", true)) "Treadmill run" else "Running")
        }
        // Existing explicit association predates component links. Only a sole running component is unambiguous.
        if (raw.size - firstComponent == 1) {
            raw[firstComponent] = raw[firstComponent].copy(sources = detail.health.filter { it.session.activityType == "running" })
        }
    }
    val links = data.runLinks
    val retired = links.filter { it.retiredKey.isNotBlank() }.groupBy { it.retiredKey }
    val ignored = links.filter { it.ignored }.flatMap { listOf(it.canonicalKey, it.sourceKey) }.toSet()
    fun resolved(key: String): String {
        var current = key; val seen = mutableSetOf<String>()
        while (seen.add(current)) { current = retired[current]?.first()?.canonicalKey ?: return current }
        error("Cyclic running associations")
    }
    val health = (data.workouts.flatMap { it.health } + data.imported.workouts.flatMap { it.health }).distinctBy { it.session.key }
    val linksBySource = links.filter { !it.ignored }.groupBy { it.sourceKey }
    val attachedHealth = health.flatMap { source ->
        linksBySource[source.session.key].orEmpty().map { resolved(it.canonicalKey) to source }
    }.groupBy({ it.first }, { it.second })
    val grouped = raw.groupBy { resolved(it.key) }
    return grouped.mapNotNull { (key, entries) ->
        if (key in ignored) return@mapNotNull null
        val base = entries.firstOrNull { it.key == key } ?: return@mapNotNull null
        val extra = attachedHealth[key].orEmpty()
        val sources = (entries.flatMap { it.sources } + extra).distinctBy { it.session.key }
        base.copy(sources = sources, notes = entries.map { it.notes }.filter { it.isNotBlank() }.distinct().joinToString("\n"),
            sourceLabel = (listOf(base.sourceLabel) + sources.map { it.session.sourceName }).distinct().joinToString(" · "), mergedKeys = entries.map { it.key }.toSet())
    }.filter { validHistoryDate(it.date) }.sortedWith(compareByDescending<RunRecord> { it.date }.thenByDescending { it.start }.thenBy { it.key })
}

fun hiddenRunParents(data: AppData): Set<String> = data.runLinks.filter { it.retiredKey.startsWith("local:") || it.ignored && it.canonicalKey.startsWith("local:") }
    .map { if (it.retiredKey.isNotBlank()) it.retiredKey else it.canonicalKey }.filter { key ->
        data.workouts.firstOrNull { "local:${it.workout.id}" == key }?.let { it.workout.activityType == "running" && it.entries.isEmpty() } == true
    }.toSet()

fun validateRunLinks(backup: Backup) {
    require(backup.runLinks.map { it.sourceKey }.distinct().size == backup.runLinks.size) { "Duplicate running source identities." }
    // Logical identities deliberately outlive removed rows (including a set awaiting Undo).
    // They are tombstones/aliases, not foreign keys into the activity tables.
    fun activityKey(key:String)=Regex("(?:local:[1-9][0-9]*|set:[1-9][0-9]*|fitnotes:.+)").matches(key)
    backup.runLinks.forEach { link ->
        require(link.sourceKey.isNotBlank() && link.canonicalKey.isNotBlank()) { "Invalid running association." }
        require(link.ignored || activityKey(link.canonicalKey)) { "Invalid canonical run identity." }
        require(link.retiredKey.isEmpty() || activityKey(link.retiredKey)) { "Invalid merged run identity." }
        var next = link.canonicalKey; val seen = mutableSetOf<String>()
        while (seen.add(next)) { next = backup.runLinks.firstOrNull { it.retiredKey == next && it.retiredKey.isNotBlank() }?.canonicalKey ?: break }
        require(next !in seen || backup.runLinks.none { it.retiredKey == next && it.retiredKey.isNotBlank() }) { "Cyclic running associations." }
    }
}
