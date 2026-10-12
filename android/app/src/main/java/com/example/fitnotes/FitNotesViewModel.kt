package com.example.fitnotes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fitnotes.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ImportedData(val imports: List<HistoryImport> = emptyList(), val workouts: List<ImportedWorkoutDetail> = emptyList(), val body: List<BodyMeasurement> = emptyList(), val loading: Boolean = false)
data class AppData(val exercises: List<Exercise> = emptyList(), val workouts: List<WorkoutDetail> = emptyList(), val loading: Boolean = true,
    val imported: ImportedData = ImportedData()) {
    val active get() = workouts.firstOrNull { it.workout.completedAt == null }
    val history get() = workouts.filter { it.workout.completedAt != null }
        .sortedWith(compareByDescending<WorkoutDetail> { it.workout.completedAt }.thenByDescending { it.workout.id })
}

class FitNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = FitDatabase.open(application)
    private val repository = WorkoutRepository(database)
    private val importer = HistoryImportRepository(database)
    private val notices = Channel<String>(Channel.BUFFERED)
    private val localData = combine(repository.exercises, repository.workouts) { exercises, workouts ->
        AppData(exercises, workouts, loading = false)
    }.catch {
        notices.send("Unable to read workouts. Restart the app to retry.")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppData())
    private val importedData = flow {
        emit(ImportedData(loading = true))
        // Load local sessions first: Room serializes relation read transactions.
        // A large original history must not get ahead of the resume query.
        localData.first { !it.loading }
        emitAll(combine(database.importedDao().observeImports(), database.importedDao().observeWorkouts(),
            database.importedDao().observeBody()) { sources, workouts, body -> ImportedData(sources, workouts, body) })
    }
    val data = combine(localData, importedData) { local, imported ->
        local.copy(imported = imported)
    }.catch {
        notices.send("Unable to read workouts. Restart the app to retry.")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppData())
    val busy = MutableStateFlow(false)
    val pendingRestore = MutableStateFlow<Backup?>(null)
    val pendingImport = MutableStateFlow<HistoryDocument?>(null)
    val previouslyImported = MutableStateFlow<HistoryImport?>(null)
    val messages = notices.receiveAsFlow()

    private fun change(success: String?, done: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy.value) return
        // Set synchronously so repeated taps cannot enqueue duplicate writes.
        busy.value = true
        viewModelScope.launch {
            try {
                block()
                done()
                if (success != null) notices.send(success)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                notices.send(error.message ?: "Could not save. Please try again.")
            } finally {
                busy.value = false
            }
        }
    }

    fun start() = change("Session ready.") { repository.start() }
    fun addExercise(workoutId: Long, exerciseId: Long, done: () -> Unit) =
        change("Exercise added.", done) { repository.addExercise(workoutId, exerciseId) }
    fun createExercise(workoutId: Long, name: String, done: () -> Unit) =
        change("Exercise added.", done) { repository.createExercise(workoutId, name) }
    fun saveSet(entryId: Long, weight: String, reps: String, setId: Long? = null, done: () -> Unit = {}) =
        change("Set saved.", done) { repository.saveSet(entryId, weight, reps, setId) }
    fun duplicateSet(id: Long, done: () -> Unit = {}) = change(null, done) { repository.duplicateSet(id) }
    fun deleteSet(id: Long, done: () -> Unit = {}) = change(null, done) { repository.deleteSet(id) }
    fun removeExercise(id: Long, done: () -> Unit) = change("Exercise removed.", done) { repository.removeExercise(id) }
    fun saveDetails(id: Long, title: String, notes: String) = change("Session details saved.") { repository.saveDetails(id, title, notes) }
    fun finish(id: Long, done: () -> Unit) = change("Session finished.", done) { repository.finish(id) }
    fun repeat(id: Long, done: () -> Unit) = change("Exercise list repeated. No sets copied.", done) { repository.repeat(id) }
    fun deleteWorkout(id: Long, done: () -> Unit) = change("Session deleted.", done) { repository.deleteWorkout(id) }
    fun repeatImported(id: String, done: () -> Unit) = change("Imported exercise list repeated. No sets copied.", done) { importer.repeatWorkout(id) }
    fun editImportedRecord(id: String, weight: String, reps: String, distance: String, unit: String?, seconds: String, comment: String, done: () -> Unit) =
        change("Record saved.", done) { importer.updateRecord(id, weight, reps, distance, unit, seconds, comment) }
    fun duplicateImportedRecord(id: String) = change("Record duplicated.") { importer.duplicateRecord(id) }
    fun deleteImportedRecord(id: String) = change("Record deleted.") { importer.deleteRecord(id) }
    fun deleteImportedWorkout(id: String, done: () -> Unit) = change("Imported workout deleted.", done) { importer.deleteImportedWorkout(id) }
    fun saveImportedNotes(id: String, notes: String) = change("Notes saved.") { importer.saveNotes(id, notes) }

    fun startOverview(onReady: (Long) -> Unit) = change(null) { onReady(repository.start()) }
    fun logStrength(entryId: Long, weight: String, reps: String, setId: Long?, comment: String, done: () -> Unit,
        distance: String? = null, unit: String? = null, seconds: String? = null) =
        change(null, done) { repository.saveSet(entryId, weight, reps, setId, comment, distance, unit, seconds) }
    fun logCardio(entryId: Long, distance: String, unit: String, seconds: String, comment: String, setId: Long?, done: () -> Unit) =
        change(null, done) { repository.saveCardio(entryId, distance, unit, seconds, comment, setId) }
    fun logImported(workoutId: String, exercise: String, category: String, recordId: String?, weight: String, reps: String,
        distance: String, unit: String?, seconds: String, comment: String, done: () -> Unit) = change(null, done) {
        if (recordId == null) importer.addRecord(workoutId, exercise, category, weight, reps, distance, unit, seconds, comment)
        else importer.updateRecord(recordId, weight, reps, distance, unit, seconds, comment)
    }
    fun saveMeasurement(id: String?, name: String, value: String, unit: String, unitName: String, date: String, time: String, comment: String, done: () -> Unit) =
        change(null, done) { importer.saveMeasurement(id, name, value, unit, unitName, date, time, comment) }
    fun deleteMeasurement(id: String, done: () -> Unit) = change(null, done) { importer.deleteMeasurement(id) }

    fun export(uri: Uri) = change("Backup exported.") {
        withContext(Dispatchers.IO) {
            val json = BackupCodec.encode(repository.backup())
            val resolver = getApplication<Application>().contentResolver
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "Unable to open the selected file." }
                .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
    }
    fun exportAnalysis(uri: Uri) = change("Analysis JSON exported.") {
        withContext(Dispatchers.IO) {
            val json = BackupCodec.analysis(repository.backup())
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "Unable to open selected file." }
                .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
    }

    fun inspectOriginal(uri: Uri, native: Boolean) = change("History parsed. Review units and counts before importing.") {
        val document = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Selected export"
            if (native) {
                val file = java.io.File.createTempFile("original-history-", ".fitnotes", getApplication<Application>().cacheDir)
                try {
                    requireNotNull(resolver.openInputStream(uri)) { "Cannot read selected backup." }.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytes = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= HistoryImportReader.MAX_BYTES) { "Original backup exceeds 20 MB." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    HistoryImportReader.nativeBackup(file, name)
                } finally {
                    file.delete()
                    java.io.File(file.path + "-wal").delete()
                    java.io.File(file.path + "-shm").delete()
                }
            } else HistoryImportReader.workoutCsv(readUtf8(uri, HistoryImportReader.MAX_BYTES), name)
        }
        previouslyImported.value = importer.alreadyImported(document.id)
        pendingImport.value = document
    }

    fun confirmImport(plan: ImportPlan, done: () -> Unit) {
        if (plan.document.id != pendingImport.value?.id) return
        change("Import complete. Existing workouts were preserved.", {
            pendingImport.value = null; previouslyImported.value = null; done()
        }) { importer.importHistory(plan) }
    }
    fun cancelImport() { if (!busy.value) { pendingImport.value = null; previouslyImported.value = null } }

    fun inspectBackup(uri: Uri) = change("Backup validated. Review before restoring.") {
        val backup = withContext(Dispatchers.IO) {
            BackupCodec.decode(readUtf8(uri, BackupCodec.MAX_BYTES))
        }
        pendingRestore.value = backup
    }

    fun confirmRestore(done: () -> Unit) {
        val backup = pendingRestore.value ?: return
        change("Backup restored.", {
            pendingRestore.value = null
            done()
        }) { repository.restore(backup) }
    }

    fun cancelRestore() { if (!busy.value) pendingRestore.value = null }

    private fun readUtf8(uri: Uri, maxBytes: Int): String {
        val bytes = ByteArrayOutputStream()
        requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "Unable to read selected file." }.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= maxBytes) { "Selected file exceeds the supported size limit." }
                bytes.write(buffer, 0, count)
            }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    override fun onCleared() {
        super.onCleared()
        database.close()
    }
}
