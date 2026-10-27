package com.example.fitnotes.dashboard

import androidx.room.withTransaction
import com.example.fitnotes.data.*

/** One lean transaction: no photo files, raw heart-rate samples or plan tables. */
suspend fun dashboardBackup(database: FitDatabase): Backup = database.withTransaction {
    val dao = database.dao(); val imported = database.importedDao(); val identities = database.trainingDao()
    Backup(exercises = dao.exercises(), workouts = dao.workouts(), entries = dao.entries(), sets = dao.sets(),
        importedWorkouts = imported.workouts(), importedSets = imported.sets(), bodyMeasurements = imported.body(),
        healthSessions = database.healthDao().sessions(), runLinks = database.runDao().all(),
        exerciseAliases = identities.aliases(), exerciseMappings = identities.mappings(), exerciseRedirects = identities.redirects())
}
