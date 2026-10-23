package com.example.fitnotes.data

import androidx.room.withTransaction

class TrainingRepository(val database: FitDatabase) {
    private val dao = database.trainingDao()
    suspend fun complete(targetId: Long, weight: String, reps: String, note: String, mode: String, actualRir:String?=null, actualRpe:String?=null): Boolean = database.withTransaction {
        val target = requireNotNull(dao.target(targetId)) { "Target no longer exists." }
        if (dao.result(targetId) != null) return@withTransaction false
        WorkoutRepository(database).saveSet(target.entryId, weight, reps, comment = note, targetId = target.id, loadMode = mode, isWarmUp = target.isWarmUp,actualRir=actualRir,actualRpe=actualRpe)
        true
    }
    suspend fun skip(targetId: Long, skipped: Boolean) = database.withTransaction {
        val target = requireNotNull(dao.target(targetId)) { "Target no longer exists." }
        require(dao.result(targetId) == null) { "Undo completion before skipping a set." }
        dao.updateTarget(target.copy(skipped = skipped))
    }
    suspend fun undo(targetId: Long) = database.withTransaction {
        dao.result(targetId)?.let { database.dao().deleteSet(it.id) }
        if (dao.timer()?.sourceTargetId == targetId) dao.clearTimer()
    }
    suspend fun applyWeight(entryId: Long, weight: PlanWeight) = database.withTransaction {
        database.planDao().runTargets().filter { it.entryId == entryId && !it.skipped && dao.result(it.id) == null }.forEach { target ->
            val next = target.target.copy(weight = weight)
            WorkoutPlanParser.validateSet(next)
            dao.updateTarget(target.copy(weightValue = weight.value, weightUnit = weight.unit))
        }
    }
    suspend fun addTarget(entryId: Long) = database.withTransaction {
        require(database.planDao().run(database.dao().entry(entryId)?.workoutId ?: -1) != null) { "This exercise has no plan snapshot." }
        val targets = database.planDao().runTargets().filter { it.entryId == entryId }
        require(targets.size < WorkoutPlanParser.MAX_SETS) { "At most 100 target sets." }
        database.planDao().insertRunTargets(listOf(WorkoutSetTarget(entryId = entryId, position = targets.size, repsMin = null, repsMax = null, weightValue = null, weightUnit = null, restSeconds = null)))
    }
    suspend fun moveTarget(targetId: Long, direction: Int) = database.withTransaction {
        val target = requireNotNull(dao.target(targetId))
        val targets = database.planDao().runTargets().filter { it.entryId == target.entryId }.sortedBy { it.position }.toMutableList()
        val from = targets.indexOfFirst { it.id == targetId }; val to = from + direction
        require(to in targets.indices) { "Cannot move this set further." }
        targets.add(to, targets.removeAt(from))
        targets.forEachIndexed { index, row -> dao.updateTarget(row.copy(position = -index - 1)) }
        targets.forEachIndexed { index, row -> dao.updateTarget(row.copy(position = index)) }
    }
    suspend fun preferences(value: TrainingPreferences) { require(value.id == 1); dao.putPreferences(value) }
    suspend fun progression(value: ExerciseProgression) {
        require(database.dao().exercise(value.exerciseId) != null)
        val increment = WorkoutPlanParser.parseWeight("${value.increment} ${value.unit}")
        require(java.math.BigDecimal(increment.value) > java.math.BigDecimal.ZERO) { "Choose a positive load increase." }
        dao.putProgression(value)
    }
}
