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
    val imported: ImportedData = ImportedData(), val storageError: String? = null,
    val exerciseAliases: List<ExerciseAlias> = emptyList(), val exerciseMappings: List<ExerciseSourceMapping> = emptyList(), val exerciseRedirects: List<ExerciseRedirect> = emptyList()) {
    val active get() = workouts.firstOrNull { !it.workout.isComplete && !it.workout.historicalDraft }
    val history get() = workouts.filter { it.workout.isComplete }
        .sortedWith(compareByDescending<WorkoutDetail> { it.workout.completedAt }.thenByDescending { it.workout.id })
}
data class HandoffPreferenceState(val loading: Boolean = true, val value: HandoffPreferences = HandoffPreferences())

class FitNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogRepository=ProgramCatalogRepository(application)
    val catalog=MutableStateFlow<CatalogSnapshot?>(null)
    val catalogBusy=MutableStateFlow(false)
    val catalogError=MutableStateFlow<String?>(null)
    val catalogEntry=MutableStateFlow<CatalogEntry?>(null)
    val catalogProgram=MutableStateFlow<PortableProgram?>(null)
    private fun catalogTask(block:suspend()->Unit) {
        if(catalogBusy.value)return
        catalogBusy.value=true;catalogError.value=null
        viewModelScope.launch {
            try {block()} catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;catalogError.value=if(e is java.io.IOException)"Can't reach the library. Check your connection and retry." else e.message ?: "Catalog unavailable. Try again later."}
            finally {catalogBusy.value=false}
        }
    }
    fun loadCatalog(){if(catalog.value==null)catalogTask{catalog.value=catalogRepository.load()}}
    fun refreshCatalog()=catalogTask{if(catalog.value==null)catalog.value=catalogRepository.load();catalog.value=catalogRepository.refresh()}
    fun openCatalogEntry(entry:CatalogEntry) {
        if(catalogBusy.value)return
        catalogEntry.value=entry;catalogProgram.value=null
        catalogTask{val snapshot=requireNotNull(catalog.value);catalogProgram.value=catalogRepository.program(entry,snapshot.manifest);catalog.value=snapshot.copy(available=snapshot.available+entry.id)}
    }
    fun catalogBack(){catalogEntry.value=null;catalogError.value=null}
    fun reviewCatalogProgram() {
        if(busy.value || catalogBusy.value)return
        val p=catalogProgram.value ?: return
        receiveProgramShare(p.json);previewProgram()
    }
    private val notices = Channel<String>(Channel.BUFFERED)
    private val database = FitDatabase.open(application)
    private val repository = WorkoutRepository(database,AndroidRestClock(application))
    private val workoutLifecycle = WorkoutLifecycleRepository(database)
    val pastDrafts = database.dao().observePastDrafts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workoutTimeError = MutableStateFlow<String?>(null)
    private var staleWorkoutJob: kotlinx.coroutines.Job? = null
    private val planRepository = WorkoutPlanRepository(database)
    private val programRepository = TrainingProgramRepository(database)
    private val programImporter = ProgramImportRepository(database)
    val programImportText = MutableStateFlow("")
    val programImportDraft = MutableStateFlow<ProgramImportDraft?>(null)
    val programImportError = MutableStateFlow<String?>(null)
    val programImportHelp = MutableStateFlow<String?>(null)
    val programImportSourceUrl = MutableStateFlow("")
    val duplicateProgramId = MutableStateFlow<String?>(null)
    val programImportRequestVersion = MutableStateFlow(0L)
    val programs = database.programDao().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val programSessions = database.programDao().observeSessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val programError = MutableStateFlow<String?>(null)
    private val training = TrainingRepository(database)
    private val loadAdviceRepository=LoadAdviceRepository(database)
    val loadProfiles=database.loadDao().observeProfiles().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val setLoadContexts=database.loadDao().observeContexts().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val sessionLoadLocks=database.loadDao().observeLocks().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val loadAdviceOpen=MutableStateFlow(false)
    val loadAdviceRows=MutableStateFlow<List<ReviewedLoadAdvice>>(emptyList())
    val loadAdviceError=MutableStateFlow<String?>(null)
    private var loadAdvicePlan:String?=null
    private var loadAdviceEntry:Long?=null
    private val identity = ExerciseIdentityRepository(database)
    val restController = RestTimerController(application, database)
    val restTimer = database.trainingDao().observeTimer().stateIn(viewModelScope, SharingStarted.Eagerly, null)
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
    private val healthImporter = HealthImportRepository(database)
    private var healthReadJob: kotlinx.coroutines.Job? = null
    private var automaticHealthJob: kotlinx.coroutines.Job? = null
    private val healthConnection = HealthSyncRuntime.connection(application)
    val healthConnectionState = healthConnection.first.state
    private val healthReadMutex = healthConnection.second
    private var healthForeground = false
    private var lastAutomaticRead = -60_000L
    val automaticHealthEnabled = healthConnectionState.map { it.settings.enabled }.stateIn(viewModelScope, SharingStarted.Eagerly, healthConnectionState.value.settings.enabled)
    val healthSyncing = healthConnectionState.map { it.working }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val healthSyncStatus = healthConnectionState.map { it.message }.stateIn(viewModelScope, SharingStarted.Eagerly, healthConnectionState.value.message)
    val healthReading = MutableStateFlow(false)
    val healthCandidates = MutableStateFlow<List<HealthCandidate>>(emptyList())
    val knownHealth = MutableStateFlow<List<HealthSession>>(emptyList())
    val healthMessage = MutableStateFlow("Choose a period, grant read permissions, then load a preview.")
    private val localData = combine(repository.exercises, repository.workouts) { exercises, workouts ->
        AppData(exercises, workouts, loading = false)
    }.transform { value ->
        // Reconcile before exposing a stale active timer, including after restore.
        if (workoutLifecycle.reconcile() == null) emit(value)
    }.flowOn(Dispatchers.IO).catch {
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
    val data = combine(localData, importedData, identities) { local, imported, identity ->
        local.copy(imported = imported,exerciseAliases=identity.first,exerciseMappings=identity.second,exerciseRedirects=identity.third)
    }.catch {
        notices.send("Unable to read workouts. Restart the app to retry.")
        emit(AppData(loading = false, storageError = "Unable to read workouts. Your database has not been reset. Close and reopen FitNotes after updating the app to retry."))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppData())
    val busy = MutableStateFlow(false)
    val pendingRestore = MutableStateFlow<Backup?>(null)
    val pendingImport = MutableStateFlow<HistoryDocument?>(null)
    val previouslyImported = MutableStateFlow<HistoryImport?>(null)
    val messages = notices.receiveAsFlow()

    private fun change(success: String?, done: () -> Unit = {}, register: ((kotlinx.coroutines.Job) -> Unit)? = null,
        failure: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        if (busy.value) return
        // SDK reads and background sync use a separate state/lock; a set save
        // must neither cancel those reads nor wait for them.
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

    fun reviewPlanWeights(id:String) { if(busy.value)return;loadAdvicePlan=id;loadAdviceEntry=null;loadAdviceRows.value=emptyList();loadAdviceOpen.value=true;refreshLoadAdvice() }
    fun reviewEntryWeights(id:Long) { if(busy.value)return;loadAdviceEntry=id;loadAdvicePlan=null;loadAdviceRows.value=emptyList();loadAdviceOpen.value=true;refreshLoadAdvice() }
    private suspend fun readLoadAdvice(){loadAdviceRows.value=withContext(Dispatchers.Default){loadAdvicePlan?.let{loadAdviceRepository.plan(it)} ?: loadAdviceEntry?.let{listOf(loadAdviceRepository.entry(it))}.orEmpty()}}
    fun refreshLoadAdvice()=change(null,failure={loadAdviceError.value=it}) {loadAdviceError.value=null;readLoadAdvice()}
    fun closeLoadAdvice(){if(!busy.value){loadAdviceOpen.value=false;loadAdviceError.value=null}}
    fun useSuggestedLoads(value:ReviewedLoadAdvice,lock:Boolean)=change("Target weights updated. No performed sets saved.",failure={loadAdviceError.value=it}) {loadAdviceError.value=null;withContext(Dispatchers.Default){loadAdviceRepository.use(value,lock)};readLoadAdvice()}
    fun changeTargetLoads(value:ReviewedLoadAdvice,weight:String,mode:String,lock:Boolean,done:()->Unit)=change("Target weights changed.",done,failure={loadAdviceError.value=it}) {loadAdviceError.value=null;withContext(Dispatchers.Default){loadAdviceRepository.change(value,weight,mode,lock)};readLoadAdvice()}
    fun saveLoadProfile(value:LoadProfile,done:()->Unit)=change(null,done,failure={loadAdviceError.value=it}) {loadAdviceError.value=null;loadAdviceRepository.saveProfile(value);readLoadAdvice()}
    fun selectLoadProfile(id:String,done:()->Unit={})=change(null,done,failure={loadAdviceError.value=it}) {loadAdviceError.value=null;loadAdviceRepository.selectProfile(id);readLoadAdvice()}
    fun lockSessionLoads(entryId:Long,locked:Boolean)=change(null,failure={loadAdviceError.value=it}) {loadAdviceError.value=null;loadAdviceRepository.lock(entryId,locked);readLoadAdvice()}
    fun applyPlanProgression(planId: String) = reviewPlanWeights(planId)
    fun completeTarget(id: Long, weight: String, reps: String, note: String, mode: String, done: () -> Unit,actualRir:String?=null,actualRpe:String?=null) {
        if(busy.value||!completionReady.value)return
        completionReady.value=false
        viewModelScope.launch { kotlinx.coroutines.delay(1000);completionReady.value=true }
        change(null, done) { if (training.complete(id,weight,reps,note,mode,actualRir,actualRpe)) restController.afterCompletion(id) }
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
    fun confirmExerciseMerge() = change("Exercises merged.",failure={trainingError.value=it}) { identity.merge(requireNotNull(mergePreview.value));mergePreview.value=null }

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
    fun previewPlan(fallbackDate: String) = change(null, failure = { planImportError.value = it }) {
        planImportError.value = null
        val data = data.value
        require(!data.loading && !data.imported.loading && data.storageError == null) { "Wait for the full exercise catalogue to load." }
        val spec = withContext(Dispatchers.Default) { WorkoutPlanParser.parse(planImportText.value, fallbackDate) }
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
    fun receiveProgramShare(text: String) {
        if(busy.value){notices.trySend("Finish the current save, then share the program again.");return}
        cancelProgramImportState()
        if(text.toByteArray(Charsets.UTF_8).size>PortableProgramParser.MAX_INPUT) { programImportError.value="Shared program exceeds 256 KB. Paste a shorter definition.";programImportRequestVersion.value+=1;return }
        programImportDraft.value=null;duplicateProgramId.value=null;programImportError.value=null
        if(text.trim().startsWith("http") && PortableProgramParser.validUrl(text.trim())) {
            programImportSourceUrl.value=text.trim();programImportText.value="";programImportHelp.value="This importer does not fetch webpages. Convert the linked routine to program JSON with your chosen tool, then paste it here. The link is retained as source; nothing is uploaded."
        } else { programImportText.value=text;programImportHelp.value=null;programImportSourceUrl.value="" }
        programImportRequestVersion.value+=1
    }
    fun receiveProgramFileShare(uri:Uri) {
        if(busy.value){notices.trySend("Finish the current save, then share the program again.");return}
        cancelProgramImportState()
        if(uri.scheme!="content")programImportError.value="Share a file through an Android content provider, or use Choose PDF / file."
        else readProgramFile(uri)
        programImportRequestVersion.value+=1
    }
    fun previewProgram() = change(null,failure={programImportError.value=it}) {
        programImportError.value=null;val current=data.value
        require(!current.loading && !current.imported.loading && current.storageError==null) { "Wait for the exercise catalogue to load." }
        val parsed=withContext(Dispatchers.Default) { PortableProgramParser.parse(programImportText.value) }
        val source=programImportSourceUrl.value
        val definition=if(source.isBlank())parsed else {
            require(PortableProgramParser.validUrl(source)) { "Check the source URL." }
            val root=parsed.document;val metadata=root.getJSONObject("source")
            require(metadata.optString("url").isBlank() || metadata.optString("url")==source) { "The pasted source URL differs from the selected link. Clear or correct the link before previewing." }
            metadata.put("url",source);PortableProgramParser.parse(root.toString())
        }
        val incompatible=incompatiblePlanExercises(current.exercises,current.workouts.flatMap{it.entries.map{e->e.entry}},current.workouts.flatMap{it.entries.flatMap{e->e.sets}},current.imported.workouts.flatMap{it.sets},canonicalImportedSetIds(current))
        programImportDraft.value=withContext(Dispatchers.Default) { PortableProgramParser.draft(definition,current.exercises,current.exerciseAliases,incompatible) }
    }
    fun readProgramFile(uri: Uri) = change(null,failure={programImportError.value=it}) {
        programImportError.value=null
        val resolver=getApplication<Application>().contentResolver
        val pdf=withContext(Dispatchers.IO) {
            resolver.getType(uri)=="application/pdf" || requireNotNull(resolver.openInputStream(uri)).use{input->
                val head=ByteArray(5);var length=0
                while(length<head.size){val count=input.read(head,length,head.size-length);if(count<=0)break;length+=count}
                length==5 && head.toString(Charsets.US_ASCII)=="%PDF-"
            }
        }
        if(pdf) programImportHelp.value="PDF text extraction is not supported by this offline importer. Convert this PDF to program JSON using your chosen tool, then Paste. The PDF is not uploaded or stored and training is unchanged."
        else {
            val text=withContext(Dispatchers.IO){readUtf8(uri,PortableProgramParser.MAX_INPUT)}
            programImportText.value=text;programImportDraft.value=null;duplicateProgramId.value=null;programImportHelp.value="Text/JSON file loaded. Preview and resolve exercises before saving."
        }
    }
    fun saveImportedProgram(allowDuplicate:Boolean=false,done:(String)->Unit)=change(null,failure={programImportError.value=it}) {
        programImportError.value=null
        when(val result=programImporter.save(requireNotNull(programImportDraft.value),allowDuplicate)) {
            is ProgramImportResult.Duplicate->duplicateProgramId.value=result.id
            is ProgramImportResult.Saved->{cancelProgramImportState();done(result.id);notices.send("Program saved. Targets are separate from performed training.")}
        }
    }
    private fun cancelProgramImportState() { programImportText.value="";programImportDraft.value=null;programImportError.value=null;programImportHelp.value=null;duplicateProgramId.value=null;programImportSourceUrl.value="" }
    fun cancelProgramImport() { if(!busy.value)cancelProgramImportState() }
    fun editProgramIdentity(id:String,title:String,description:String,done:()->Unit)=change(null,done,failure={programError.value=it}) { programError.value=null;programImporter.editIdentity(id,title,description) }
    fun deleteTemplate(id: String, done: () -> Unit) = change("Template deleted.", done, failure = { programError.value = it }) { programError.value = null; planRepository.delete(id) }
    fun renameTemplate(id: String, title: String, done: () -> Unit) = change(null, done, failure = { programError.value = it }) { programError.value = null; planRepository.rename(id, title) }
    fun archiveTemplate(id: String, archived: Boolean) = change(null, failure = { programError.value = it }) { programError.value = null; planRepository.archive(id, archived) }
    fun pinTemplate(id: String, pinned: Boolean) = change(null, failure = { programError.value = it }) { programError.value = null; planRepository.pin(id, pinned) }
    fun duplicateTemplate(id: String) = change("Template copied.", failure = { programError.value = it }) { programError.value = null; planRepository.duplicate(id) }
    fun createProgram(title: String, weeks: Int, days: List<String>, done: () -> Unit) = change("Program saved.", done, failure = { programError.value = it }) { programError.value = null; programRepository.create(title, weeks, days) }
    fun activateProgram(id: String) = change(null, failure = { programError.value = it }) { programError.value = null; programRepository.activate(id) }
    fun pauseProgram(id: String) = change(null, failure = { programError.value = it }) { programError.value = null; programRepository.pause(id) }
    fun postponeProgram(id: String, date: String) = change(null, failure = { programError.value = it }) { programError.value = null; programRepository.postpone(id, date) }
    fun skipProgramDay(id: String) = change("Day skipped; no workout marked completed.", failure = { programError.value = it }) { programError.value = null; programRepository.skip(id) }
    fun completeProgramDay(id: String, workoutId: Long) = change("Program day completed.", failure = { programError.value = it }) { programError.value = null; programRepository.complete(id, workoutId) }
    fun startProgramDay(id: String, done: (Long) -> Unit) = change(null, failure = { programError.value = it }) { programError.value = null; done(programRepository.start(id)) }
    fun startPlan(id: String, onReady: (Long) -> Unit) = change("Workout started.", failure = { planImportError.value = it }) { planImportError.value = null; val workout = planRepository.start(id); planEdit.value = null; planImportText.value = ""; duplicatePlanId.value = null; onReady(workout) }
    fun deletePlan(id: String, done: () -> Unit) = change("Plan deleted.", done, failure = { planImportError.value = it }) { planImportError.value = null; planRepository.delete(id) }
    fun addExercise(workoutId: Long, exerciseId: Long, done: () -> Unit) =
        change("Exercise added.", done) { repository.addExercise(workoutId, exerciseId) }
    fun reorderExercises(workoutId: Long, entryIds: List<Long>, done: () -> Unit, failure: (String) -> Unit) =
        change("Exercise order saved.", done, failure = failure) { repository.reorderExercises(workoutId, entryIds) }
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
    fun finish(id: Long, done: () -> Unit) = change("Session finished.", done) {
        repository.finish(id); restController.sync()
        val updated=repository.database.dao().workout(id)!!
        val entries=database.dao().workoutEntries(id).map{entry->EntryDetail(entry,database.dao().exercise(entry.exerciseId)!!,database.dao().entrySets(entry.id))}
        val fresh=WorkoutDetail(updated,entries,data.value.workouts.firstOrNull{it.workout.id==id}?.health.orEmpty())
        val all=withContext(Dispatchers.Default){observations(data.value.copy(workouts=data.value.workouts.filter{it.workout.id!=id}+fresh))}
        val current=all.firstOrNull{it.id=="local:$id"}
        if(current!=null) {
            completionSummary.value=(if(updated.activityType=="strength")StrengthStatistics.summary(current,all) else "${activityName(updated)} · ${localWorkoutDate(updated)}\nDuration: ${durationText(localDuration(updated))}${updated.effort?.let{"\nEffort: $it"}.orEmpty()}${updated.notes.takeIf{it.isNotBlank()}?.let{"\nNotes: $it"}.orEmpty()}")
        }
    }
    fun repeat(id: Long, done: () -> Unit) = change("Exercise list repeated. No sets copied.", done) { repository.repeat(id) }
    fun deleteWorkout(id: Long, done: () -> Unit) = change("Session deleted.", done) { repository.deleteWorkout(id) }
    fun repeatImported(id: String, done: () -> Unit) = change("Imported exercise list repeated. No sets copied.", done) { importer.repeatWorkout(id) }
    fun editImportedRecord(id: String, weight: String, reps: String, distance: String, unit: String?, seconds: String, comment: String, done: () -> Unit) =
        change("Record saved.", done) { importer.updateRecord(id, weight, reps, distance, unit, seconds, comment) }
    fun duplicateImportedRecord(id: String) = change("Record duplicated.") { importer.duplicateRecord(id) }
    fun deleteImportedRecord(id: String) = change("Record deleted.") { importer.deleteRecord(id) }
    fun deleteImportedWorkout(id: String, done: () -> Unit) = change("Imported workout deleted.", done) { importer.deleteImportedWorkout(id) }
    fun saveImportedNotes(id: String, notes: String) = change("Notes saved.") { importer.saveNotes(id, notes) }

    fun planFromSession(session: com.example.fitnotes.ui.NotebookSession) {
        val preview = planFromCompletedSession(session, planRuns.value.flatMap { it.exercises }.flatMap { it.targets })
        planImportError.value = null
        planEdit.value = preview.spec.editable(preview.resolutions)
    }
    fun newPlan() {
        cancelPlanImport()
        planEdit.value = PlanSpec("New workout", "", 0, "", emptyList(), planFingerprint(java.util.UUID.randomUUID().toString())).editable(emptyList())
    }
    fun startEmpty(onReady: (Long) -> Unit) = change(null) { onReady(WorkoutStartRepository(database).startEmpty()) }
    fun resolveActiveAndStart(id: Long, action: ActiveWorkoutAction, planId: String?, onReady: (Long) -> Unit) = change(null) {
        val next = WorkoutStartRepository(database).resolveAndStart(id, action, planId)
        restController.sync()
        if (planId != null) { planEdit.value = null; planImportText.value = ""; duplicatePlanId.value = null }
        onReady(next)
    }
    fun addExercisesAndOpen(workoutId: Long, ids: List<Long>, onReady: (Long?) -> Unit) = change(null) {
        repository.addExercises(workoutId, ids)
        val entry = ids.singleOrNull()?.let { id -> database.dao().workoutEntries(workoutId).firstOrNull { it.exerciseId == id }?.id }
        onReady(entry)
    }
    fun startOverview(onReady: (Long) -> Unit) = change(null) { onReady(repository.start()) }
    fun startActivity(type: String, label: String, title: String, notes: String, effort: String?, onReady: (Long) -> Unit) =
        change(null) { onReady(repository.startActivity(type, label, title, notes, effort)) }
    fun logPastActivity(type: String, label: String, date: String, start: Long?, seconds: Double, title: String, notes: String,
        effort: String?, distance: Double?, unit: String?, onReady: (Long) -> Unit) =
        change(null) { onReady(repository.logPast(type, label, date, start, seconds, title, notes, effort, distance, unit)) }
    fun saveActivityDetails(id: Long, title: String, notes: String, effort: String?, distance: Double?, unit: String?, done: () -> Unit) =
        change(null, done) { repository.saveActivityDetails(id, title, notes, effort, distance, unit) }

    fun beginPastWorkout(input: WorkoutTimeInput, source: String, done: (Long) -> Unit) = change(null, failure = { workoutTimeError.value = it }) {
        workoutTimeError.value = null; done(workoutLifecycle.beginPast(input, source))
    }
    fun savePastWorkout(id: Long, done: () -> Unit) = change("Past workout saved.", done, failure = { workoutTimeError.value = it }) {
        workoutTimeError.value = null; workoutLifecycle.savePast(id)
    }
    fun editWorkoutTime(id: Long, input: WorkoutTimeInput, done: () -> Unit) = change("Workout timing saved.", done, failure = { workoutTimeError.value = it }) {
        workoutTimeError.value = null; workoutLifecycle.editTime(id, input); restController.sync()
    }
    fun resumeFinishedWorkout(id: Long, done: () -> Unit = {}) = change("Workout resumed; the unlogged gap stays excluded.", done, failure = { workoutTimeError.value = it }) {
        workoutTimeError.value = null; workoutLifecycle.resume(id)
    }
    fun allowLongWorkout(id: Long) = change("Stale checks paused for six hours.") { workoutLifecycle.allowLongWorkout(id) }
    fun acknowledgeAutoFinish(id: Long) = change(null) { workoutLifecycle.acknowledge(id) }

    val stravaExportText = MutableStateFlow("")
    val stravaExportHeaders = MutableStateFlow<List<String>>(emptyList())
    val stravaExportPreview = MutableStateFlow<StravaExportPreview?>(null)
    val stravaExportKnown = MutableStateFlow<List<HealthSession>>(emptyList())
    val stravaExportWorking = MutableStateFlow(false)
    val stravaExportMessage = MutableStateFlow("Choose activities.csv from your own downloaded Strava account archive.")
    private var stravaExportJob: kotlinx.coroutines.Job? = null
    private var stravaExportGeneration = 0L
    private fun stravaFileWork(block: suspend () -> Unit) {
        if (stravaExportWorking.value) return
        val generation = ++stravaExportGeneration
        stravaExportWorking.value = true
        stravaExportJob = viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (generation == stravaExportGeneration) stravaExportMessage.value = error.message ?: "File import did not complete. Committed batches are retained; review and retry safely."
            } finally { if (generation == stravaExportGeneration) stravaExportWorking.value = false }
        }
    }
    fun readStravaFile(uri: Uri) = stravaFileWork { loadStravaText(readUtf8(uri, StravaOwnerExport.MAX_BYTES)) }
    fun inspectStravaText(text: String) = stravaFileWork { loadStravaText(text) }
    private suspend fun loadStravaText(text: String) {
        val headers = StravaOwnerExport.headers(text)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        stravaExportText.value = text; stravaExportHeaders.value = headers; stravaExportPreview.value = null
        stravaExportMessage.value = "Confirm owner key, timestamp timezone and column units, then load a preview."
    }
    fun previewStravaFile(options: StravaExportOptions) = stravaFileWork {
        val preview = StravaOwnerExport.preview(stravaExportText.value, options)
        val known = healthImporter.known()
        val index = SourceMatchIndex(database.dao().workouts(), known)
        val matches = preview.candidates.associate { it.session.key to index.matches(it.session) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        stravaExportKnown.value = known; stravaExportPreview.value = preview.copy(matches = matches)
        stravaExportMessage.value = "${preview.rows} file rows · ${preview.candidates.size} valid activities in range · ${preview.issues.size} row errors. Review possible matches before importing."
    }
    fun importStravaFile(choices: List<StravaImportChoice>) = stravaFileWork {
        val count = StravaExportImporter(database, healthReadMutex).apply(choices) { stravaExportMessage.value = "$it selected records processed. Workout logging remains available." }
        stravaExportKnown.value = healthImporter.known()
        stravaExportMessage.value = "$count selected activities processed. Existing workouts and saved sets were kept."
    }
    fun ignoreStravaFile(candidate: HealthCandidate) = stravaFileWork {
        healthImporter.ignore(candidate); stravaExportKnown.value = healthImporter.known()
        stravaExportMessage.value = "Activity ignored. The decision is included in training backups."
    }
    fun cancelStravaFile() {
        stravaExportJob?.cancel(); stravaExportGeneration++
        stravaExportWorking.value = false
        stravaExportMessage.value = "Stopped. Committed batches remain saved; the same owner key and activity IDs make a later retry safe."
    }

    fun cancelHealthRead() {
        if (healthReadJob?.isActive == true) {
            healthReadJob?.cancel()
            healthMessage.value = "Read stopped after leaving the foreground. Return and load the preview again; saved sessions were retained."
        }
    }
    fun healthForeground() {
        healthForeground = true
        if (staleWorkoutJob?.isActive != true) staleWorkoutJob = viewModelScope.launch(Dispatchers.IO) {
            localData.first { !it.loading }
            while (healthForeground) {
                try { workoutLifecycle.reconcile() }
                catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; notices.send("Workout time check failed. Saved sets are retained; review time manually.") }
                kotlinx.coroutines.delay(60_000)
            }
        }
        syncHealthOnOpen()
    }
    fun healthBackground() {
        healthForeground = false
        staleWorkoutJob?.cancel()
        automaticHealthJob?.cancel()
        cancelHealthRead()
    }
    fun setAutomaticHealth(enabled: Boolean) = configureHealth(healthConnectionState.value.settings.copy(enabled = enabled))
    fun setHealthBackground(enabled: Boolean) = configureHealth(healthConnectionState.value.settings.copy(background = enabled))
    fun setHealthOrigins(origins: Set<String>) = configureHealth(healthConnectionState.value.settings.copy(origins = origins))
    fun setHealthActivities(activities: Set<String>) = configureHealth(healthConnectionState.value.settings.copy(activities = activities))
    private fun configureHealth(settings: HealthSyncSettings) {
        automaticHealthJob?.cancel()
        healthConnection.first.configure(settings)
        if (!settings.enabled || !settings.background) HealthSyncSchedule.cancel(getApplication())
        if (settings.enabled) syncHealthOnOpen(force = true)
    }
    fun syncHealthNow() { syncHealthOnOpen(force = true, manual = true) }
    private fun syncHealthOnOpen(force: Boolean = false, manual: Boolean = false) {
        if ((!manual && (!healthForeground || !healthConnectionState.value.settings.enabled)) ||
            android.os.Build.VERSION.SDK_INT < 28 || automaticHealthJob?.isActive == true) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (!force && elapsed - lastAutomaticRead < 60_000) return
        automaticHealthJob = viewModelScope.launch(Dispatchers.IO) {
            localData.first { !it.loading }
            if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(getApplication()) != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                healthConnection.first.update { it.copy(message = "Health Connect setup is needed. Install/update it, then grant read access.") }
                HealthSyncSchedule.cancel(getApplication()); return@launch
            }
            try {
                val source = HealthConnectSyncSource(getApplication())
                val access = source.access()
                HealthSyncSchedule.reconcile(getApplication(), healthConnectionState.value.settings, access)
                HealthSyncCoordinator(database, healthConnection.first, source, healthReadMutex)
                    .sync(if (manual) HealthSyncReason.MANUAL else HealthSyncReason.FOREGROUND)
                knownHealth.value = healthImporter.known()
                lastAutomaticRead = android.os.SystemClock.elapsedRealtime()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                healthConnection.first.update { it.copy(working = false, errors = it.errors + 1, message = "Health Connect could not complete the sync. Review access and retry; saved activities are retained.") }
            }
        }
    }
    fun inspectHealth(days: Int) {
        require(days in 1..3650)
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val end = java.time.Instant.now()
        readHealthWindow(end.minusSeconds(days * 86400L), end)
    }
    fun inspectHealthRange(first: String, last: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        try {
            val startDay = java.time.LocalDate.parse(first); val endDay = java.time.LocalDate.parse(last)
            require(startDay <= endDay && endDay <= java.time.LocalDate.now() && java.time.temporal.ChronoUnit.DAYS.between(startDay, endDay) <= 3650) { "Choose a valid range of up to ten years ending today or earlier." }
            val zone = java.time.ZoneId.systemDefault()
            readHealthWindow(startDay.atStartOfDay(zone).toInstant(), endDay.plusDays(1).atStartOfDay(zone).toInstant())
        } catch (error: Exception) { healthMessage.value = error.message ?: "Choose dates as YYYY-MM-DD." }
    }
    private fun readHealthWindow(start: java.time.Instant, end: java.time.Instant) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        if (healthReadJob?.isActive == true) return
        healthReading.value = true
        healthReadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                healthReadMutex.withLock {
                    val context = getApplication<Application>()
                    require(androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) { "Install or update Health Connect, then retry." }
                    val reader = HealthConnectReader(context); val access = reader.access()
                    require(access.exercise) { access.message }
                    healthMessage.value = "Reading the requested interval; workout logging remains available…"
                    val candidates = reader.sessions(start, end)
                    val enriched = candidates.map { reader.enrich(it) }
                    knownHealth.value = healthImporter.known()
                    healthCandidates.value = enriched
                    healthConnection.first.update { it.copy(availableOrigins = it.availableOrigins + enriched.associate { c -> c.session.originPackage to c.session.sourceName }) }
                    val coverage = if (access.history) "Extended history read permission is granted. Records still depend on what Garmin published."
                        else "Health Connect may restrict reads to 30 days before the first permission grant. Older coverage is not confirmed."
                    healthMessage.value = "Requested ${start} to ${end}. ${enriched.size} returned activities.\n$coverage\n${access.message}"
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                healthMessage.value = if (error is SecurityException) "Read access changed. Review Health Connect permissions; saved activities are retained."
                    else error.message ?: "The read is incomplete. Retry safely; saved activities are retained."
            } finally { healthReading.value = false }
        }
    }
    fun ignoreHealth(candidate: HealthCandidate) = change(null) {
        healthImporter.ignore(candidate)
        healthConnection.first.update { it.copy(reviewKeys = it.reviewKeys - candidate.session.key) }
        knownHealth.value = healthImporter.known()
        healthCandidates.value = healthCandidates.value.filterNot { it.session.key == candidate.session.key }
    }
    fun importHealth(candidate: HealthCandidate, attachTo: Long? = null, attachImported: String? = null, done: () -> Unit) = change(null, done) {
        val id = healthImporter.import(candidate, attachTo, attachImported)
        healthConnection.first.update { it.copy(reviewKeys = it.reviewKeys - candidate.session.key) }
        knownHealth.value = healthImporter.known()
        notices.send(if (id == null) "Already imported; the deleted local session was not recreated." else "Activity saved. Local notes, sets and timing preserved.")
    }
    fun logStrength(entryId: Long, weight: String, reps: String, setId: Long?, comment: String, done: () -> Unit,
        distance: String? = null, unit: String? = null, seconds: String? = null, loadMode: String? = null, isWarmUp: Boolean? = null, actualRir:String?=null,actualRpe:String?=null) =
        change(null, done) { repository.saveSet(entryId, weight, reps, setId, comment, distance, unit, seconds, loadMode=loadMode, isWarmUp=isWarmUp,actualRir=actualRir,actualRpe=actualRpe) }
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
        change("Import complete. Existing workouts were preserved.", {
            pendingImport.value = null; previouslyImported.value = null; done()
        }) { importer.importHistory(plan) }
    }
    fun cancelImport() { if (!busy.value) { pendingImport.value = null; previouslyImported.value = null } }

    fun inspectBackup(uri: Uri) = change("Backup validated. Review before restoring.") {
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
            cancelProgramImportState()
            loadAdviceOpen.value=false;loadAdviceRows.value=emptyList()
            done()
        }) {
            automaticHealthJob?.cancel()
            cancelStravaFile(); stravaExportPreview.value = null
            healthConnection.first.resetAfterRestore()
            HealthSyncSchedule.cancel(getApplication())
            val photos = pendingPhotoRestore.value
            if (photos == null) repository.restore(backup) else photoBackup.restore(photos)
            backup.trainingPreferences?.let { preferences->val values=org.json.JSONArray(preferences.favouriteKeys);val keys=(0 until values.length()).map{values.getString(it)}.toSet();getApplication<Application>().getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE).edit().putStringSet("favourite_exercises",keys).apply() }
            restController.sync()
        }
    }

    fun cancelRestore() = change(null) {
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
