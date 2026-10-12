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
    val active get() = workouts.firstOrNull {  !it.workout.isComplete }
    val history get() = workouts.filter { it.workout.isComplete }
        .sortedWith(compareByDescending<WorkoutDetail> { it.workout.completedAt }.thenByDescending { it.workout.id })
}

class FitNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = FitDatabase.open(application)
    private val repository = WorkoutRepository(database)
    private val importer = HistoryImportRepository(database)
    private val healthImporter = HealthImportRepository(database)
    private var healthReadJob: kotlinx.coroutines.Job? = null
    val healthCandidates = MutableStateFlow<List<HealthCandidate>>(emptyList())
    val knownHealth = MutableStateFlow<List<HealthSession>>(emptyList())
    val healthMessage = MutableStateFlow("Choose a period, grant read permissions, then load a preview.")
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

    private fun change(success: String?, done: () -> Unit = {}, register: ((kotlinx.coroutines.Job) -> Unit)? = null, block: suspend () -> Unit) {
        if (busy.value) return
        // Set synchronously so repeated taps cannot enqueue duplicate writes.
        busy.value = true
        val job = viewModelScope.launch {
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
        register?.invoke(job)
    }

    fun start() = change("Session ready.") { repository.start() }
    fun addExercise(workoutId: Long, exerciseId: Long, done: () -> Unit) =
        change("Exercise added.", done) { repository.addExercise(workoutId, exerciseId) }
    fun addExercises(workoutId: Long, ids: List<Long>, done: () -> Unit) =
        change("Exercises added.", done) { repository.addExercises(workoutId, ids) }
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
    fun startActivity(type: String, label: String, title: String, notes: String, effort: String?, onReady: (Long) -> Unit) =
        change(null) { onReady(repository.startActivity(type, label, title, notes, effort)) }
    fun logPastActivity(type: String, label: String, date: String, start: Long?, seconds: Double, title: String, notes: String,
        effort: String?, distance: Double?, unit: String?, onReady: (Long) -> Unit) =
        change(null) { onReady(repository.logPast(type, label, date, start, seconds, title, notes, effort, distance, unit)) }
    fun saveActivityDetails(id: Long, title: String, notes: String, effort: String?, distance: Double?, unit: String?, done: () -> Unit) =
        change(null, done) { repository.saveActivityDetails(id, title, notes, effort, distance, unit) }

    fun cancelHealthRead() {
        if (healthReadJob?.isActive == true) {
            healthReadJob?.cancel()
            healthMessage.value = "Read stopped after leaving the foreground. Return and load the preview again; saved sessions were retained."
        }
    }
    fun inspectHealth(days: Int) = change(null, register = { healthReadJob = it }) {
        healthCandidates.value = emptyList()
        if (android.os.Build.VERSION.SDK_INT < 28) { healthMessage.value = "Health Connect requires Android 9 or later."; return@change }
        require(days == 7 || days == 30)
        val context = getApplication<Application>()
        if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
            healthMessage.value = "Health Connect is unavailable. Install or update it, then retry."; return@change
        }
        try {
            val reader = HealthConnectReader(context)
            val access = reader.access()
            if (!access.exercise) { healthMessage.value = access.message; return@change }
            healthMessage.value = "Reading activities and available samples…"
            val end = java.time.Instant.now(); val start = end.minusSeconds(days * 86400L)
            val candidates = reader.sessions(start, end)
            val enriched = candidates.map { reader.enrich(it) }
            // Only a fully successful exercise scan may mark an old source as missing.
            val missing = healthImporter.noteMissing(start.toEpochMilli(), end.toEpochMilli(), candidates.map { it.session.key }.toSet())
            knownHealth.value = healthImporter.known()
            healthCandidates.value = enriched
            healthMessage.value = access.message + "\n${enriched.size} available activities in the selected window." +
                if (missing > 0) "\n$missing previously imported records were not returned; local sessions retained. See session information." else ""
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            healthCandidates.value = emptyList()
            healthMessage.value = if (error is SecurityException) "Health Connect permission was denied or revoked. Grant exercise access, then retry." else
                "Health Connect could not complete the read. Check permissions, availability and sync, then retry. The result is incomplete; saved sessions were retained."
        }
    }
    fun importHealth(candidate: HealthCandidate, attachTo: Long? = null, attachImported: String? = null, done: () -> Unit) = change(null, done) {
        val id = healthImporter.import(candidate, attachTo, attachImported)
        knownHealth.value = healthImporter.known()
        notices.send(if (id == null) "Already imported; the deleted local session was not recreated." else "Activity saved. Local notes, sets and timing preserved.")
    }
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
