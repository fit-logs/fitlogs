package com.example.fitnotes.ui

import com.example.fitnotes.AppData
import com.example.fitnotes.ImportedData
import com.example.fitnotes.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

data class NotebookProjection(
    val data: AppData = AppData(),
    val pastDrafts: List<WorkoutDetail> = emptyList(),
    val sessions: List<NotebookSession> = emptyList(),
    val editingSessions: List<NotebookSession> = emptyList(),
    val runs: List<RunRecord> = emptyList(),
    val historyRows: List<NotebookHistoryRow> = emptyList(),
)

// One serial worker owns the cache. Emit data and its derived lists together so
// Compose never pairs a fresh database snapshot with stale navigation rows.
fun notebookProjections(data: Flow<AppData>, drafts: Flow<List<WorkoutDetail>>): Flow<NotebookProjection> {
    var importedInput: AppData? = null
    var importedSessions = emptyList<NotebookSession>()
    return combine(data, drafts) { snapshot, pastDrafts ->
        val input = AppData(exercises = snapshot.exercises,
            imported = ImportedData(workouts = snapshot.imported.workouts), runLinks = snapshot.runLinks,
            exerciseAliases = snapshot.exerciseAliases, exerciseMappings = snapshot.exerciseMappings,
            exerciseRedirects = snapshot.exerciseRedirects)
        // Local edits do not change the imported history. Reuse its parsed rows
        // until the import, exercise identities or explicit run links change.
        if (input != importedInput) {
            importedSessions = notebookSessions(input)
            importedInput = input
        }
        val local = notebookSessions(snapshot.copy(imported = ImportedData()))
        val sessions = sortNotebookSessions(local + importedSessions)
        val draftSessions = notebookSessions(snapshot.copy(workouts = pastDrafts, imported = ImportedData()), includeDrafts = true)
        val runs = runningRecords(snapshot)
        NotebookProjection(snapshot, pastDrafts, sessions, (sessions + draftSessions).distinctBy { it.key }, runs, historyRows(sessions, runs))
    }.flowOn(Dispatchers.Default)
}
