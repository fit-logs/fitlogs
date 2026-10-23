package com.example.fitnotes.data

import androidx.room.withTransaction

enum class ActiveWorkoutAction { FINISH, DISCARD }

class WorkoutStartRepository(private val database: FitDatabase) {
    suspend fun startEmpty(): Long = database.withTransaction {
        require(database.dao().active() == null) { "A workout is already in progress. Resume it or choose how to finish it." }
        WorkoutRepository(database).start()
    }

    suspend fun resolveAndStart(expectedActiveId: Long, action: ActiveWorkoutAction, planId: String? = null): Long = database.withTransaction {
        // A confirmation belongs to one active session; never act on its replacement.
        require(database.dao().active()?.id == expectedActiveId) { "The active workout changed. Return to Train and try again." }
        val workouts = WorkoutRepository(database)
        when (action) {
            ActiveWorkoutAction.FINISH -> workouts.finish(expectedActiveId)
            ActiveWorkoutAction.DISCARD -> workouts.deleteWorkout(expectedActiveId)
        }
        if (planId == null) workouts.start() else WorkoutPlanRepository(database).start(planId)
    }
}
