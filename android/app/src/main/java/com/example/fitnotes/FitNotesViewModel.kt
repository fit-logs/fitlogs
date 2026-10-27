package com.example.fitnotes

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fitnotes.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import android.content.Intent

data class ImportedData(val imports: List<HistoryImport> = emptyList(), val workouts: List<ImportedWorkoutDetail> = emptyList(), val body: List<BodyMeasurement> = emptyList(), val loading: Boolean = false)
data class AppData(val exercises: List<Exercise> = emptyList(), val workouts: List<WorkoutDetail> = emptyList(), val loading: Boolean = true,
    val imported: ImportedData = ImportedData(), val runLinks: List<RunLink> = emptyList(), val storageError: String? = null,
    val exerciseAliases: List<ExerciseAlias> = emptyList(), val exerciseMappings: List<ExerciseSourceMapping> = emptyList(), val exerciseRedirects: List<ExerciseRedirect> = emptyList()) {
    val active get() = workouts.firstOrNull {  !it.workout.isComplete }
    val history get() = workouts.filter { it.workout.isComplete }
        .sortedWith(compareByDescending<WorkoutDetail> { it.workout.completedAt }.thenByDescending { it.workout.id })
}
data class HandoffPreferenceState(val loading: Boolean = true, val value: HandoffPreferences = HandoffPreferences())

class FitNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val notices = Channel<String>(Channel.BUFFERED)
    private val database = FitDatabase.open(application)
    private val repository = WorkoutRepository(database,AndroidRestClock(application))
    private val planRepository = WorkoutPlanRepository(database)
    private val training = TrainingRepository(database)
    private val identity = ExerciseIdentityRepository(database)
    val restController = RestTimerController(application, database)
    val restTimer = database.trainingDao().observeTimer().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val bodyProfile = database.bodyProfileDao().observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val trainingPreferences = database.trainingDao().observePreferences().map { it ?: TrainingPreferences() }.stateIn(viewModelScope, SharingStarted.Eagerly, TrainingPreferences())
    val progression = database.trainingDao().observeProgression().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val mergePreview = MutableStateFlow<ExerciseMergePreview?>(null)
    val trainingError = MutableStateFlow<String?>(null)
    val completionSummary = MutableStateFlow<String?>(null)
    val completionReady = MutableStateFlow(true)
    private val timerRecovery = viewModelScope.launch { try { database.trainingDao().observeTimer().collect { restController.sync() } } catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; notices.send("Unable to recover the rest timer. Saved training is retained.") } }
    val photoRepository = ProgressPhotoRepository(application, database)
    private val photoBackup = PhotoBackup(photoRepository, repository)
    val photos = photoRepository.photos.catch { notices.send("Unable to read progress photos. Restart the app to retry."); emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val photoDrafts = MutableStateFlow<List<PhotoDraft>>(emptyList())
    val pendingPhotoRestore = MutableStateFlow<PhotoRestore?>(null)
    val plans = planRepository.plans.catch { notices.send("Unable to read planned workouts. Restart the app to retry."); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val planRuns = database.planDao().observeRuns().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val handoffPreferences = planRepository.preferences.map { HandoffPreferenceState(false, it ?: HandoffPreferences()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HandoffPreferenceState())
    val preparedHandoff = MutableStateFlow<PreparedHandoff?>(null)
    val handoffError = MutableStateFlow<String?>(null)
    var planFocusPosition: Int? = null
    val planImportText = MutableStateFlow("")
    val planEdit = MutableStateFlow<EditablePlan?>(null)
    val planImportError = MutableStateFlow<String?>(null)
    val duplicatePlanId = MutableStateFlow<String?>(null)
    private val photoRecovery = viewModelScope.launch {
        try { photoRepository.recover() }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            notices.send("Unable to check progress photo storage. Restart the app to retry.")
        }
    }
    private val importer = HistoryImportRepository(database)
    private val runningRepository = RunningRepository(database)
    val runImport = MutableStateFlow(RunImportState())
    private var runningJob: kotlinx.coroutines.Job? = null
    fun cancelRunningImport() { runningJob?.cancel() }
    fun previewRunningHistory(months: Int?) {
        if (busy.value || runningJob?.isActive == true || android.os.Build.VERSION.SDK_INT < 28) return
        automaticHealthJob?.cancel()
        runImport.value = RunImportState(working = true, stage = "Reading runs…")
        runningJob = viewModelScope.launch {
            try {
                healthReadMutex.withLock {
                    val reader = HealthConnectReader(getApplication())
                    val access = reader.access()
                    require(access.exercise) { "Allow exercise access to preview runs." }
                    val coverage = if (access.history) "Extended history access is enabled. All available means records Garmin has written to Health Connect."
                        else "History is limited to 30 days before your first permission grant. Older Garmin runs may not have been written to Health Connect."
                    runImport.value = runImport.value.copy(coverage = coverage)
                    val end = java.time.Instant.now()
                    val start = months?.let { java.time.ZonedDateTime.now().minusMonths(it.toLong()).toInstant() }
                    val candidates = readRunningHistory({ reader.runningPage(start, end, it) }, { reader.enrich(it) }) { count ->
                        runImport.value = runImport.value.copy(stage = "Read $count runs…")
                    }
                    val snapshot = runningSnapshot(database); val existing = runningRecords(snapshot)
                    val known = healthImporter.known(); val knownKeys = known.filter { it.workoutId != null || it.importedWorkoutId != null }.map { it.key }.toSet()
                    val clientMatches = candidates.mapNotNull { c -> known.filter { it.originPackage==c.session.originPackage && !it.clientRecordId.isNullOrBlank() && it.clientRecordId==c.session.clientRecordId }.maxWithOrNull(compareBy<HealthSession>{it.clientRecordVersion}.thenBy{it.modifiedAt})?.let { c.session.key to it } }.toMap()
                    val presentRaw = knownKeys + clientMatches.filterValues { it.workoutId!=null || it.importedWorkoutId!=null }.keys
                    val ignored = known.filter { it.workoutId == null && it.importedWorkoutId == null }.map { it.key }.toSet() + snapshot.runLinks.filter { it.ignored }.map { it.sourceKey } + clientMatches.filterValues { source -> source.workoutId==null && source.importedWorkoutId==null || snapshot.runLinks.any { it.ignored && it.sourceKey==source.key } }.keys
                    val presentKeys = presentRaw - ignored
                    val matches = candidates.filter { it.session.key !in presentKeys && it.session.key !in ignored }.associate { c ->
                        c.session.key to potentialRunMatches(healthRun(HealthDetail(c.session, c.samples), "health:${c.session.key}", ""), existing)
                    }
                    runImport.value = RunImportState(preview = RunImportPreview(candidates, presentKeys, ignored, matches), coverage = coverage,
                        message = if (candidates.isEmpty()) "No accessible Garmin runs in this period." else "Review the preview, then import.")
                }
            } catch (error: Exception) {
                if (error !is kotlinx.coroutines.CancellationException) android.util.Log.w("RunningImport", "Foreground history read failed", error)
                val message = when(error) {
                    is kotlinx.coroutines.CancellationException -> "Import paused. Preview again to retry safely."
                    is SecurityException -> "Health Connect access changed. Review permissions, then try again."
                    is IllegalArgumentException -> "Allow exercise access and try the preview again."
                    else -> "Could not read runs. Wait a moment, then retry. Saved runs are unchanged."
                }
                runImport.value = runImport.value.copy(working = false, stage = "", message = message)
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }
    fun importRunningHistory(choices: Map<String, String>) {
        val preview = runImport.value.preview ?: return
        if (preview.matches.any { (key, values) -> values.isNotEmpty() && key !in choices }) return
        change(null, register = { runningJob = it }, failure = { runImport.value = runImport.value.copy(working = false, stage = "", message = it) }) {
            var added = 0; var present = 0; var linked = 0; var ignored = 0; var updated = 0
            runImport.value = runImport.value.copy(working = true, stage = "Importing runs…")
            try {
                if (choices.values.any { it != "separate" }) repairBackup()
                for (batch in preview.candidates.chunked(20)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val outcomes = database.withTransaction { batch.map { runningRepository.import(it, choices[it.session.key]) } }
                    added += outcomes.count { it == "added" }; present += outcomes.count { it == "present" }; linked += outcomes.count { it == "linked" }; ignored += outcomes.count { it == "ignored" }; updated += outcomes.count { it == "updated" }
                    runImport.value = runImport.value.copy(stage = "Imported ${added + present + linked + ignored + updated} of ${preview.candidates.size} runs…")
                }
                val result = "Imported $added runs; $present already present." + (if (linked > 0) " $linked linked." else "") + (if (updated > 0) " $updated updated." else "") + (if (ignored > 0) " $ignored previously deleted." else "")
                runImport.value = runImport.value.copy(working = false, stage = "", preview = null, message = result)
            } catch (error: kotlinx.coroutines.CancellationException) {
                runImport.value = runImport.value.copy(working = false, stage = "", preview = null, message = "Import paused after $added new runs. Preview again to continue safely."); throw error
            }
        }
    }
    fun deleteRun(key: String, done: () -> Unit) = change("Run deleted from FitNotes.", done) { runningRepository.delete(key) }
    fun saveRun(key: String, title: String, notes: String, km: Double?, seconds: Double?, done: () -> Unit) = change(null, done) { runningRepository.save(key, title, notes, km, seconds) }
    fun keepRunsSeparate(a:String,b:String,done:()->Unit) = change("Runs kept separate.",done) { runningRepository.keepSeparate(a,b) }
    fun mergeRuns(source: String, target: String, done: () -> Unit) = change("Runs linked. A recovery backup is saved on this device.", done) { repairBackup(); runningRepository.merge(source, target) }
    private val healthImporter = HealthImportRepository(database)
    private var healthReadJob: kotlinx.coroutines.Job? = null
    private var automaticHealthJob: kotlinx.coroutines.Job? = null
    private val healthReadMutex = Mutex()
    private val healthPreferences = application.getSharedPreferences("fitnotes-health-sync", android.content.Context.MODE_PRIVATE)
    private var healthForeground = false
    private var lastAutomaticRead = -60_000L
    val automaticHealthEnabled = MutableStateFlow(healthPreferences.getBoolean("on-open", true))
    val healthSyncing = MutableStateFlow(false)
    val healthSyncStatus = MutableStateFlow(healthPreferences.getString("last-result", null) ?: "Automatic sync is ready once read permissions are granted.")
    val healthCandidates = MutableStateFlow<List<HealthCandidate>>(emptyList())
    val knownHealth = MutableStateFlow<List<HealthSession>>(emptyList())
    val healthMessage = MutableStateFlow("Choose a period, grant read permissions, then load a preview.")
    private val localData = combine(repository.exercises, repository.workouts) { exercises, workouts ->
        AppData(exercises, workouts, loading = false)
    }.catch {
        notices.send("Unable to read workouts. Restart the app to retry.")
        emit(AppData(loading = false, storageError = "Unable to read workouts. Your database has not been reset. Close and reopen FitNotes after updating the app to retry."))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppData())
    private val importedData = flow {
        emit(ImportedData(loading = true))
        // Load local sessions first: Room serializes relation read transactions.
        // A large original history must not get ahead of the resume query.
        localData.first { !it.loading }
        emitAll(combine(database.importedDao().observeImports(), database.importedDao().observeWorkouts(),
            database.importedDao().observeBody()) { sources, workouts, body -> ImportedData(sources, workouts, body) })
    }
    private val identities = combine(database.trainingDao().observeAliases(),database.trainingDao().observeMappings(),database.trainingDao().observeRedirects()) { aliases,mappings,redirects -> Triple(aliases,mappings,redirects) }
    val data = combine(localData, importedData, identities, database.runDao().observe()) { local, imported, identity, runLinks ->
        local.copy(imported = imported,runLinks=runLinks,exerciseAliases=identity.first,exerciseMappings=identity.second,exerciseRedirects=identity.third)
    }.catch {
        notices.send("Unable to read workouts. Restart the app to retry.")
        emit(AppData(loading = false, storageError = "Unable to read workouts. Your database has not been reset. Close and reopen FitNotes after updating the app to retry."))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppData())
    val busy = MutableStateFlow(false)
    val pendingRestore = MutableStateFlow<Backup?>(null)
    private val systemBackup = SystemBackupStore(application)
    private var restoringSystemBackup = false
    private var offeredSystemBackup = false
    val pendingImport = MutableStateFlow<HistoryDocument?>(null)
    val previouslyImported = MutableStateFlow<HistoryImport?>(null)
    val messages = notices.receiveAsFlow()
    private val deletedNotices=Channel<DeletedSetUndo>(Channel.BUFFERED)
    val deletedSets=deletedNotices.receiveAsFlow()
    data class DeletedSetUndo(val local:WorkoutSet?=null,val imported:ImportedSet?=null)
    fun undoDeletedSet(value:DeletedSetUndo)=change(null){
        value.local?.let{repository.restoreDeletedSet(it)}
        value.imported?.let{importer.restoreDeletedRecord(it)}
    }

    private val historyRepair = viewModelScope.launch {
        data.first { !it.loading && !it.imported.loading && it.storageError==null }
        try {
            val count=importer.repairExactDuplicates { repairBackup() }
            if(count>0)notices.send("Repeated imports combined. A recovery backup is saved on this device.")
        } catch(error:Exception) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            notices.send("Repeated imports need review in Settings. Saved records were retained.")
        }
    }

    private fun change(success: String?, done: () -> Unit = {}, register: ((kotlinx.coroutines.Job) -> Unit)? = null,
        failure: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        if (busy.value) return
        // Foreground reads must never hold up logging, edits or an explicit import.
        automaticHealthJob?.cancel()
        // Set synchronously so repeated taps cannot enqueue duplicate writes.
        busy.value = true
        val job = viewModelScope.launch {
            try {
                block()
                done()
                if (success != null) notices.send(success)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = error.message ?: "Could not save. Please try again."
                if (failure != null) failure(message) else notices.send(message)
            } finally {
                busy.value = false
            }
        }
        register?.invoke(job)
    }

    fun applyPlanProgression(planId: String) = change("Progression suggestions applied.",failure={planImportError.value=it}) { planRepository.applyProgression(planId) }
    fun completeTarget(id: Long, weight: String, reps: String, note: String, mode: String, done: () -> Unit) {
        if(busy.value||!completionReady.value)return
        completionReady.value=false
        viewModelScope.launch { kotlinx.coroutines.delay(1000);completionReady.value=true }
        change(null, done) { if (training.complete(id,weight,reps,note,mode)) restController.afterCompletion(id) }
    }
    fun skipTarget(id: Long, skipped: Boolean) = change(null) { training.skip(id,skipped) }
    fun undoTarget(id: Long) = change(null) { training.undo(id);restController.sync() }
    fun applyRemainingWeight(entryId: Long, weight: String) = change(null) { training.applyWeight(entryId,WorkoutPlanParser.parseWeight(weight)) }
    fun addRunTarget(entryId: Long) = change(null) { training.addTarget(entryId) }
    fun moveRunTarget(id: Long, direction: Int) = change(null) { training.moveTarget(id,direction) }
    fun restAction(token: String, action: String) = change(null) { restController.action(token,action) }
    fun restFinished(token: String) { viewModelScope.launch { restController.action(token,"expire") } }
    fun startRest(workoutId: Long, seconds: Int, targetId: Long? = null) = change(null) { restController.start(workoutId,seconds,targetId) }
    fun saveTrainingPreferences(value: TrainingPreferences) = change(null) { training.preferences(value);restController.sync() }
    fun saveProgression(value: ExerciseProgression) = change(null,failure={trainingError.value=it}) { trainingError.value=null;training.progression(value) }
    fun addAlias(exerciseId: Long, name: String, done: () -> Unit = {}) = change(null,done,failure={trainingError.value=it}) { trainingError.value=null;identity.addAlias(exerciseId,name) }
    fun removeAlias(id: Long) = change(null) { identity.removeAlias(id) }
    fun mapExerciseSource(value: ExerciseSourceMapping) = change(null,failure={trainingError.value=it}) { trainingError.value=null;identity.mapSource(value) }
    fun previewExerciseMerge(survivor: Long, removed: Long) = change(null,failure={trainingError.value=it}) { trainingError.value=null;mergePreview.value=identity.preview(survivor,removed) }
    fun confirmExerciseMerge() = change("Exercises merged. A recovery backup is saved on this device.",failure={trainingError.value=it}) { repairBackup();identity.merge(requireNotNull(mergePreview.value));mergePreview.value=null }

    fun start() = change("Session ready.") { repository.start() }
    private suspend fun captureFavouritePreferences() {
        val favourites=getApplication<Application>().getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE).getStringSet("favourite_exercises",emptySet())!!.toList().sorted()
        val prefs=database.trainingDao().preferences() ?: TrainingPreferences()
        database.trainingDao().putPreferences(prefs.copy(favouriteKeys=org.json.JSONArray(favourites).toString()))
    }
    fun prepareHandoff(request: HandoffRequest) = change(null, failure = { handoffError.value = it }) {
        handoffError.value = null
        val prepared = withContext(Dispatchers.Default) { ChatGptHandoff.prepare(data.value, photos.value, request) }
        planRepository.savePreferences(request.preferences.copy(lastTargetDate = request.targetDate))
        preparedHandoff.value = prepared
    }
    fun cancelHandoff() { if (!busy.value) { preparedHandoff.value = null; handoffError.value = null } }
    fun shareHandoff(onIntent: (Intent) -> Unit) = change(null, failure = { handoffError.value = it }) {
        val prepared = preparedHandoff.value ?: return@change
        onIntent(HandoffShare(getApplication(), photoRepository).intent(prepared))
    }
    fun exportHandoff(uri: Uri) = change("Complete prompt exported.", failure = { handoffError.value = it }) {
        val prepared = preparedHandoff.value ?: return@change
        withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "Cannot open the selected export destination." }
                .bufferedWriter(Charsets.UTF_8).use { it.write(prepared.fullText) }
        }
    }
    fun planFromSession(session: com.example.fitnotes.ui.NotebookSession) {
        val preview=planFromCompletedSession(session,planRuns.value.flatMap{it.exercises}.flatMap{it.targets})
        planImportError.value = null
        planEdit.value=preview.spec.editable(preview.resolutions)
    }

    fun newPlan() {
        planImportError.value = null
        planEdit.value = com.example.fitnotes.data.PlanSpec("New workout", "", 0, "", emptyList(), planFingerprint(java.util.UUID.randomUUID().toString())).editable(emptyList())
    }
    val openedPlanReview=MutableStateFlow(false)
    private val openedPlanHashes=mutableSetOf<String>()
    fun openPlanFile(uri:Uri)=change(null,failure={planImportError.value="Cannot open this plan. Use a current FitNotes plan file.";openedPlanReview.value=true}) {
        require(uri.scheme in setOf("content","file")){"Unsupported plan location."}
        data.first{!it.loading&&!it.imported.loading}
        val text=withContext(Dispatchers.IO){readUtf8(uri,WorkoutPlanParser.MAX_INPUT)}
        val hash=planFingerprint(text)
        if(hash in openedPlanHashes)return@change
        preparePlan(text,"")
        openedPlanHashes.add(hash)
        planImportText.value=text
        openedPlanReview.value=true
    }
    fun exportPlan(uri:Uri,id:String)=change("Plan exported.") {
        val plan=requireNotNull(database.planDao().detail(id)){"Plan no longer exists."}
        val text=WorkoutPlanFormat.encode(plan.spec)
        withContext(Dispatchers.IO){requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{it.write(text)}}
    }
    fun previewPlan(fallbackDate:String)=change(null,failure={planImportError.value=it}){preparePlan(planImportText.value,fallbackDate)}
    private suspend fun preparePlan(raw:String,fallbackDate:String) {
        planImportError.value = null
        val data = data.value
        require(!data.loading && !data.imported.loading && data.storageError == null) { "Wait for the full exercise catalogue to load." }
        val spec = withContext(Dispatchers.Default) { WorkoutPlanParser.parse(raw, fallbackDate) }
        val incompatible = incompatiblePlanExercises(data.exercises, data.workouts.flatMap { it.entries.map { e -> e.entry } },
            data.workouts.flatMap { it.entries.flatMap { e -> e.sets } }, data.imported.workouts.flatMap { it.sets },canonicalImportedSetIds(data))
        planEdit.value = spec.editable(spec.exercises.map { p ->
            val match = matchPlanExercise(p.name, data.exercises, incompatible, aliasMap(data.exerciseAliases))
            ResolvedPlanExercise(p, match.candidates.singleOrNull()?.id?.takeIf { match.kind in setOf(ExerciseMatchKind.EXACT, ExerciseMatchKind.ALIAS) })
        })
    }
    fun savePlan(allowDuplicate: Boolean = false, onSaved: (String) -> Unit) = change(null, failure = { planImportError.value = it }) {
        planImportError.value = null
        val edit = planEdit.value ?: return@change
        val preview = edit.validated()
        when (val result = planRepository.save(preview, allowDuplicate, edit.editingId, edit.recoveryWorkoutId)) {
            is PlanSaveResult.Saved -> { planEdit.value = null; duplicatePlanId.value = null; planImportText.value = ""; onSaved(result.id) }
            is PlanSaveResult.Duplicate -> duplicatePlanId.value = result.id
        }
    }
    fun cancelPlanImport() { if (!busy.value) { planEdit.value = null; planImportText.value = ""; planImportError.value = null; duplicatePlanId.value = null } }
    fun editSavedPlan(id: String, done: () -> Unit) = change(null, failure = { planImportError.value = it }) {
        val detail = requireNotNull(database.planDao().detail(id)) { "Plan no longer exists." }
        planImportError.value = null
        planEdit.value = detail.spec.editable(detail.ordered.map { ResolvedPlanExercise(it.prescription, it.exercise.id, supersetGroup = it.target.supersetGroup) }, editingId = id)
        done()
    }
    fun recoverEmptySuggestion(id: Long, done: () -> Unit) = change(null, failure = { planImportError.value = it }) {
        val preview = planRepository.recoveryPreview(id)
        planImportError.value = null
        planEdit.value = preview.spec.editable(preview.resolutions, recoveryId = id)
        done()
    }
    fun saveRunSuggestion(id: Long, edit: EditableTarget, name: String, done: () -> Unit) = change(null, failure = { planImportError.value = it }) {
        planRepository.updateRunTarget(id, edit.validated(name)); done()
    }
    fun startPlan(id: String, onReady: (Long) -> Unit) = change("Workout started.", failure = { planImportError.value = it }) { planImportError.value = null; val workout = planRepository.start(id); planEdit.value = null; planImportText.value = ""; duplicatePlanId.value = null; onReady(workout) }
    fun duplicatePlan(id: String, done: (String) -> Unit) = change(null,failure={planImportError.value=it}) {
        val plan=requireNotNull(database.planDao().detail(id))
        val spec=plan.spec.copy(title=plan.plan.title+" copy",fingerprint=planFingerprint(java.util.UUID.randomUUID().toString()))
        val preview=PlanPreview(spec,plan.ordered.map{ResolvedPlanExercise(it.prescription,it.exercise.id,supersetGroup=it.target.supersetGroup)})
        val result=planRepository.save(preview,true,null,null)
        if(result is PlanSaveResult.Saved)done(result.id)
    }
    fun deletePlan(id: String, done: () -> Unit) = change("Plan deleted.", done, failure = { planImportError.value = it }) { planImportError.value = null; planRepository.delete(id) }
    fun addExercise(workoutId: Long, exerciseId: Long, done: () -> Unit) =
        change("Exercise added.", done) { repository.addExercise(workoutId, exerciseId) }
    fun addExercises(workoutId: Long, ids: List<Long>, done: () -> Unit) =
        change("Exercises added.", done) { repository.addExercises(workoutId, ids) }
    fun createExercise(workoutId: Long, name: String, done: () -> Unit) =
        change("Exercise added.", done) { repository.createExercise(workoutId, name) }
    fun saveSet(entryId: Long, weight: String, reps: String, setId: Long? = null, done: () -> Unit = {}) =
        change("Set saved.", done) { repository.saveSet(entryId, weight, reps, setId) }
    fun duplicateSet(id: Long, done: () -> Unit = {}) = change(null, done) { repository.duplicateSet(id) }
    fun deleteSet(id: Long, done: () -> Unit = {}) = change(null, done) { deletedNotices.send(DeletedSetUndo(local=repository.deleteSet(id))) }
    fun saveExerciseNote(entryId:Long,note:String,done:()->Unit)=change(null,done){repository.saveExerciseNote(entryId,note)}
    fun removeExercise(id: Long, done: () -> Unit) = change("Exercise removed.", done) { repository.removeExercise(id) }
    fun reorderExercises(workoutId: Long, entryIds: List<Long>, done: () -> Unit, failure: (String) -> Unit) =
        change("Exercise order saved.", done, failure = failure) { repository.reorderExercises(workoutId, entryIds) }
    fun saveDetails(id: Long, title: String, notes: String) = change("Session details saved.") { repository.saveDetails(id, title, notes) }
    fun finish(id: Long, done: () -> Unit) = change("Session finished.", done) {
        repository.finish(id)
        restController.sync()
    }
    val historyMergeReview = MutableStateFlow<HistoryMergePreview?>(null)
    val historyMergeError = MutableStateFlow<String?>(null)
    private suspend fun repairBackup(): java.io.File = withContext(Dispatchers.IO) {
        val folder=java.io.File(getApplication<Application>().filesDir,"repair-backups").apply{mkdirs()}
        val file=java.io.File(folder,"before-repair-${System.currentTimeMillis()}.json")
        file.writeText(BackupCodec.encode(repository.backup()))
        require(file.length()>0){"Unable to save a repair backup."}
        file
    }
    fun mergeHistory() = change("Workouts merged. A recovery backup is saved on this device.",failure={historyMergeError.value=it}) {
        val preview=historyMergeReview.value ?: return@change
        repairBackup();importer.merge(preview);historyMergeReview.value=null
    }
    fun repeat(id: Long, done: () -> Unit) = change("Exercise list repeated. No sets copied.", done) { repository.repeat(id) }
    fun deleteWorkout(id: Long, done: () -> Unit) = change("Session deleted.", done) { repository.deleteWorkout(id); retainDeletedRunDecisions(database) }
    fun repeatImported(id: String, done: () -> Unit) = change("Imported exercise list repeated. No sets copied.", done) { importer.repeatWorkout(id) }
    fun editImportedRecord(id: String, weight: String, reps: String, distance: String, unit: String?, seconds: String, comment: String, done: () -> Unit) =
        change("Record saved.", done) { importer.updateRecord(id, weight, reps, distance, unit, seconds, comment) }
    fun duplicateImportedRecord(id: String) = change("Record duplicated.") { importer.duplicateRecord(id) }
    fun deleteImportedRecord(id: String) = change(null) { deletedNotices.send(DeletedSetUndo(imported=importer.deleteRecord(id))) }
    fun deleteImportedWorkout(id: String, done: () -> Unit) = change("Imported workout deleted.", done) { importer.deleteImportedWorkout(id); retainDeletedRunDecisions(database) }
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
    fun healthForeground() {
        healthForeground = true
        syncHealthOnOpen()
    }
    fun healthBackground() {
        healthForeground = false
        cancelRunningImport()
        automaticHealthJob?.cancel()
        cancelHealthRead()
    }
    fun setAutomaticHealth(enabled: Boolean) {
        automaticHealthEnabled.value = enabled
        healthPreferences.edit().putBoolean("on-open", enabled).apply()
        if (enabled) syncHealthOnOpen(force = true) else automaticHealthJob?.cancel()
    }
    private fun syncHealthOnOpen(force: Boolean = false) {
        if (!healthForeground || !automaticHealthEnabled.value || android.os.Build.VERSION.SDK_INT < 28 ||
            busy.value || systemBackup.hasRecovery() || automaticHealthJob?.isActive == true) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        // Returning from a picker or rotating the device must not cause a burst of reads.
        if (!force && elapsed - lastAutomaticRead < 60_000) return
        automaticHealthJob = viewModelScope.launch {
            val loaded = localData.first { !it.loading }
            if (loaded.storageError != null || !healthForeground) return@launch
            healthReadMutex.withLock {
                if (!healthForeground || !automaticHealthEnabled.value) return@withLock
                val context = getApplication<Application>()
                if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                    healthSyncStatus.value = "Automatic sync needs Health Connect setup. Open Import activities."
                    return@withLock
                }
                healthSyncing.value = true
                try {
                    val reader = HealthConnectReader(context)
                    val access = reader.access()
                    if (!access.exercise) {
                        healthSyncStatus.value = "Automatic sync needs exercise read permission. Open Import activities to grant access."
                        return@withLock
                    }
                    healthSyncStatus.value = "Syncing available Garmin data…"
                    healthCandidates.value = emptyList()
                    val end = java.time.Instant.now(); val start = end.minusSeconds(30 * 86400L)
                    val candidates = reader.sessions(start, end)
                    val known = healthImporter.known()
                    val linkedKeys = known.filter { it.workoutId != null || it.importedWorkoutId != null }.map { it.key }.toSet()
                    val targets = healthImporter.automaticTargets(candidates)
                    val enriched = candidates.filter { it.session.key in linkedKeys || it.session.key in targets || it.session.activityType == "running" && isGarminOrigin(it.session.originPackage) }.map { reader.enrich(it) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!healthForeground) return@withLock
                    var importedRuns = 0
                    for (candidate in enriched.filter { it.session.activityType == "running" && isGarminOrigin(it.session.originPackage) && it.session.end <= System.currentTimeMillis() }) {
                        val matches = potentialRunMatches(healthRun(HealthDetail(candidate.session, candidate.samples), "health:${candidate.session.key}", ""), runningRecords(runningSnapshot(database)))
                        val sameClient = known.any { it.originPackage==candidate.session.originPackage && !it.clientRecordId.isNullOrBlank() && it.clientRecordId==candidate.session.clientRecordId }
                        if (candidate.session.key in linkedKeys || sameClient || matches.isEmpty()) {
                            if (runningRepository.import(candidate) == "added") importedRuns++
                        }
                    }
                    val result = healthImporter.applyAutomaticSync(candidates, enriched.filter { it.session.activityType != "running" || it.session.key in linkedKeys && !isGarminOrigin(it.session.originPackage) }, start.toEpochMilli(), end.toEpochMilli())
                    knownHealth.value = healthImporter.known()
                    val summary = "Last sync: ${timestampText(System.currentTimeMillis())}\n${result.refreshed} refreshed · ${result.linked} linked · $importedRuns runs imported · ${result.needsReview} need review" +
                        (if (result.missing > 0) "\n${result.missing} source records were not returned; local sessions retained." else "") +
                        (if (!access.heartRate || !access.distance) "\n" + access.message else "")
                    healthSyncStatus.value = summary
                    healthPreferences.edit().putString("last-result", summary).apply()
                    lastAutomaticRead = android.os.SystemClock.elapsedRealtime()
                    if (result.linked > 0) notices.send("Garmin data linked to ${result.linked} matching sessions.")
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) {
                        healthSyncStatus.value = if (automaticHealthEnabled.value) "Automatic sync paused. It will retry when you return to FitNotes." else "Automatic sync is off."
                        throw error
                    }
                    healthSyncStatus.value = if (error is SecurityException) "Automatic sync needs read permissions. Open Import activities to review access." else
                        "Automatic sync could not finish. Saved sessions were retained. Open Import activities to retry."
                    lastAutomaticRead = android.os.SystemClock.elapsedRealtime()
                } finally {
                    healthSyncing.value = false
                }
            }
        }
    }
    fun inspectHealth(days: Int) = change(null, register = { healthReadJob = it }) {
        healthReadMutex.withLock {
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
    }
    fun importHealth(candidate: HealthCandidate, attachTo: Long? = null, attachImported: String? = null, done: () -> Unit) = change(null, done) {
        val id = healthImporter.import(candidate, attachTo, attachImported, beforeRepair={repairBackup()})
        knownHealth.value = healthImporter.known()
        notices.send(if (id == null) "Already imported; the deleted local session was not recreated." else "Activity saved. Local notes, sets and timing preserved.")
    }
    fun logStrength(entryId: Long, weight: String, reps: String, setId: Long?, comment: String, done: () -> Unit,
        distance: String? = null, unit: String? = null, seconds: String? = null, loadMode: String? = null, isWarmUp: Boolean? = null) =
        change(null, done) { repository.saveSet(entryId, weight, reps, setId, comment, distance, unit, seconds, loadMode=loadMode, isWarmUp=isWarmUp) }
    fun logCardio(entryId: Long, distance: String, unit: String, seconds: String, comment: String, setId: Long?, done: () -> Unit) =
        change(null, done) { repository.saveCardio(entryId, distance, unit, seconds, comment, setId) }
    fun logImported(workoutId: String, exercise: String, category: String, recordId: String?, weight: String, reps: String,
        distance: String, unit: String?, seconds: String, comment: String, done: () -> Unit) = change(null, done) {
        if (recordId == null) importer.addRecord(workoutId, exercise, category, weight, reps, distance, unit, seconds, comment)
        else importer.updateRecord(recordId, weight, reps, distance, unit, seconds, comment)
    }
    fun saveMeasurement(id: String?, name: String, value: String, unit: String, unitName: String, date: String, time: String, comment: String, done: () -> Unit) =
        change(null, done) { importer.saveMeasurement(id, name, value, unit, unitName, date, time, comment) }
    private val deletedMeasurementsChannel = Channel<BodyMeasurement>(Channel.BUFFERED)
    val deletedMeasurements = deletedMeasurementsChannel.receiveAsFlow()
    fun deleteMeasurement(id: String, done: () -> Unit) = change(null, done) {
        val record = requireNotNull(database.importedDao().measurement(id)) { "Measurement no longer exists." }
        importer.deleteMeasurement(id); deletedMeasurementsChannel.send(record)
    }
    fun undoDeletedMeasurement(record: BodyMeasurement) = change(null) {
        database.withTransaction {
            require(database.importedDao().imported(record.importId) != null) { "The measurement source was removed." }
            require(database.importedDao().measurement(record.id) == null) { "This measurement is already present." }
            database.importedDao().insertMeasurement(record)
        }
    }
    fun saveBodyProfile(profile: BodyProfile, done: () -> Unit) = change(null, done) {
        validateBodyProfile(profile)
        database.bodyProfileDao().put(profile)
    }
    fun bodyChartRange(range: String, defaultUnit: String) = change(null) {
        database.withTransaction {
            val profile = database.bodyProfileDao().get() ?: BodyProfile(weightUnit = defaultUnit)
            database.bodyProfileDao().put(profile.copy(chartRange = range).also { validateBodyProfile(it) })
        }
    }

    fun recoveryBackups():List<java.io.File> = java.io.File(getApplication<Application>().filesDir,"repair-backups").listFiles().orEmpty().filter{it.extension=="json"}.sortedByDescending{it.lastModified()}
    fun exportRecovery(uri:Uri,name:String)=change("Recovery backup exported.") {
        val file=recoveryBackups().firstOrNull{it.name==name} ?: error("Recovery backup no longer exists.")
        withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).use{out->file.inputStream().use{it.copyTo(out)}}
        }
    }
    fun export(uri: Uri) = change("Backup exported. Progress photos are not included.") {
        withContext(Dispatchers.IO) {
            captureFavouritePreferences()
            val json = BackupCodec.encode(repository.backup())
            val resolver = getApplication<Application>().contentResolver
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "Unable to open the selected file." }
                .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
    }
    fun preparePhotos(uris: List<Uri>) = change(null) {
        photoRecovery.join()
        val drafts = mutableListOf<PhotoDraft>()
        try {
            for (uri in uris.take(20)) drafts.add(photoRepository.prepare(uri))
            photoDrafts.value = drafts
        } catch (error: Throwable) { photoRepository.discard(drafts); throw error }
    }
    fun savePhotoDraft(date: String, angle: String, note: String) {
        val draft = photoDrafts.value.firstOrNull() ?: return
        change("Photo saved.") {
            photoRepository.save(draft, date, angle, note)
            photoDrafts.value = photoDrafts.value.drop(1)
        }
    }
    fun cancelPhotos() = change(null) {
        photoRepository.discard(photoDrafts.value)
        photoDrafts.value = emptyList()
    }
    fun editPhoto(id: String, date: String, angle: String, note: String, done: () -> Unit) =
        change("Photo details saved.", done) { photoRepository.edit(id, date, angle, note) }
    fun deletePhoto(id: String, done: () -> Unit) = change("App copy deleted.", done) { photoRepository.delete(id) }
    fun exportPhotos(uri: Uri) = change("Backup exported with progress photos.") {
        captureFavouritePreferences(); photoRecovery.join(); photoBackup.export(uri)
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
        change(null, {
            pendingImport.value = null; previouslyImported.value = null; done()
        }) { notices.send(importer.importHistory(plan).message) }
    }
    fun cancelImport() { if (!busy.value) { pendingImport.value = null; previouslyImported.value = null } }

    fun offerRecoveredBackup() {
        if (!offeredSystemBackup && !busy.value) {
            offeredSystemBackup = true
            if (systemBackup.hasRecovery()) reviewSystemBackup()
        }
    }

    fun reviewSystemBackup() = change("Recovered backup validated. Review before restoring.") {
        restoringSystemBackup = false
        pendingRestore.value = null
        photoRecovery.join()
        photoBackup.discard(pendingPhotoRestore.value)
        pendingPhotoRestore.value = null
        pendingRestore.value = withContext(Dispatchers.IO) { SystemBackupSnapshot.read(systemBackup.file) }
        restoringSystemBackup = true
    }

    fun inspectBackup(uri: Uri) = change("Backup validated. Review before restoring.") {
        restoringSystemBackup = false
        photoRecovery.join()
        pendingRestore.value = null
        photoBackup.discard(pendingPhotoRestore.value)
        pendingPhotoRestore.value = null
        val isArchive = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.read() == 80 && it.read() == 75 } ?: false
        }
        if (isArchive) {
            val preview = photoBackup.inspect(uri)
            pendingPhotoRestore.value = preview
            pendingRestore.value = preview.backup
        } else pendingRestore.value = withContext(Dispatchers.IO) { BackupCodec.decode(readUtf8(uri, BackupCodec.MAX_BYTES)) }
    }

    fun confirmRestore(done: () -> Unit) {
        val backup = pendingRestore.value ?: return
        change("Backup restored.", {
            pendingRestore.value = null
            pendingPhotoRestore.value = null
            done()
        }) {
            val photos = pendingPhotoRestore.value
            if (photos == null) repository.restore(backup) else photoBackup.restore(photos)
            backup.trainingPreferences?.let { preferences->val values=org.json.JSONArray(preferences.favouriteKeys);val keys=(0 until values.length()).map{values.getString(it)}.toSet();getApplication<Application>().getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE).edit().putStringSet("favourite_exercises",keys).apply() }
            restController.sync()
            if (restoringSystemBackup) {
                withContext(Dispatchers.IO) {
                    runCatching { systemBackup.discardRecovery() }.onFailure {
                        systemBackup.failed("Training was restored, but the recovered backup file could not be removed.")
                    }
                }
                restoringSystemBackup = false
            }
        }
    }

    fun cancelRestore() = change(null) {
        restoringSystemBackup = false
        photoBackup.discard(pendingPhotoRestore.value)
        pendingRestore.value = null; pendingPhotoRestore.value = null
    }

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
