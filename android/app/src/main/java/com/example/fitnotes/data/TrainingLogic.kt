package com.example.fitnotes.data

import java.math.BigDecimal

fun targetWeightKg(target: PlanSetTarget): String = when (target.weight.unit) {
    "bodyweight" -> "0"
    "kg" -> target.weight.value.orEmpty()
    "lb" -> target.weight.value?.let { BigDecimal(it).multiply(BigDecimal("0.45359237")).stripTrailingZeros().toPlainString() }.orEmpty()
    else -> ""
}

data class SetStep(val entryId: Long, val targetId: Long)
fun plannedSequence(run: PlanRunDetail): List<WorkoutSetTarget> {
    val entries = run.exercises.sortedBy { it.entry.position }
    val seen = mutableSetOf<String>()
    return entries.flatMap { exercise ->
        val group = exercise.entry.supersetGroup
        if (group.isBlank()) exercise.ordered
        else if (!seen.add(group)) emptyList()
        else {
            val peers = entries.filter { it.entry.supersetGroup == group }
            (0 until (peers.maxOfOrNull { it.ordered.size } ?: 0)).flatMap { round -> peers.mapNotNull { it.ordered.getOrNull(round) } }
        }
    }
}
fun nextPlannedSet(run: PlanRunDetail, results: List<WorkoutSet>): SetStep? {
    val done = results.mapNotNull { it.targetId }.toSet()
    return plannedSequence(run).firstOrNull { !it.skipped && it.id !in done }?.let { SetStep(it.entryId, it.id) }
}
fun pendingTargets(exercise: RunExerciseDetail, results: List<WorkoutSet>) = exercise.ordered.filter { target -> !target.skipped && results.none { it.targetId == target.id } }
fun shouldRestAfter(run: PlanRunDetail, target: WorkoutSetTarget, results: List<WorkoutSet>): Boolean {
    val done = results.mapNotNull { it.targetId }.toSet()
    if (plannedSequence(run).none { !it.skipped && !it.isWarmUp && it.id !in done }) return false
    val exercise = run.exercises.firstOrNull { it.entry.id == target.entryId } ?: return false
    val group = exercise.entry.supersetGroup
    return group.isBlank() || run.exercises.filter { it.entry.supersetGroup == group }.all { peer ->
        peer.ordered.getOrNull(target.position)?.let { it.skipped || it.id in done } ?: true
    }
}

interface RestClock { fun elapsed(): Long; fun bootCount(): Int }
fun RestTimerState.remaining(clock: RestClock): Long = if (!running || clock.bootCount() != bootCount) remainingMillis
    else (remainingMillis - (clock.elapsed() - anchorElapsed).coerceAtLeast(0)).coerceAtLeast(0)
fun RestTimerState.recovered(clock: RestClock): RestTimerState = if (running && (clock.bootCount() != bootCount || clock.elapsed() < anchorElapsed))
    copy(running = false, anchorElapsed = clock.elapsed(), bootCount = clock.bootCount()) else this
fun RestTimerState.pause(clock: RestClock) = recovered(clock).let { it.copy(remainingMillis = it.remaining(clock), anchorElapsed = clock.elapsed(), running = false) }
fun RestTimerState.resume(clock: RestClock) = recovered(clock).copy(anchorElapsed = clock.elapsed(), bootCount = clock.bootCount(), running = remainingMillis > 0, alerted = false)
fun RestTimerState.extend(clock: RestClock) = recovered(clock).let { it.copy(remainingMillis = (it.remaining(clock) + 30000).coerceAtMost(7200000), anchorElapsed = clock.elapsed(), running = it.running || it.alerted, alerted = false) }
