package com.example.fitnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import org.json.JSONObject
import org.json.JSONArray

const val PROGRAM_CONVERSION_FORMAT="""Convert the routine I provide into one FitNotes program JSON object, schemaVersion 1. Preserve all ordered days/exercises, original rep ranges, set counts, rest, RIR/RPE, optional substitutions, progression and source attribution. Do not invent weights, results or exercise equivalence. Use stable program id/programVersion and per-day ids. Example:
{"schemaVersion":1,"id":"private-four-day","programVersion":"1","name":"My program","weeks":4,"description":"","goals":["strength"],"level":"","equipment":[],"progression":"","source":{"title":"","url":"","author":"","attribution":""},"days":[{"id":"upper-a","name":"Upper A","exercises":[{"canonicalId":"bench-press","name":"Bench Press","sets":[{"repsMin":8,"repsMax":12,"restSeconds":90,"rir":2,"loadMode":"external"}]}]}]}
Return all workout days in one days array. Omit unknown weights and timing. Do not copy long instructional articles. The app will ask me to resolve exercise identities before saving."""

@Composable
fun ProgramImportDialog(model:FitNotesViewModel,data:AppData,busy:Boolean,close:()->Unit,saved:(String)->Unit) {
    val raw by model.programImportText.collectAsState();val draft by model.programImportDraft.collectAsState();val error by model.programImportError.collectAsState();val help by model.programImportHelp.collectAsState();val duplicate by model.duplicateProgramId.collectAsState();val sourceUrl by model.programImportSourceUrl.collectAsState()
    val context=LocalContext.current;val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var urlDialog by rememberSaveable{mutableStateOf(false)};var url by rememberSaveable{mutableStateOf("")};var urlError by remember{mutableStateOf<String?>(null)};var copied by remember{mutableStateOf(false)}
    var resolvingDay by rememberSaveable{mutableStateOf<String?>(null)};var resolvingExercise by rememberSaveable{mutableStateOf<String?>(null)}
    val incompatible=remember(data){incompatiblePlanExercises(data.exercises,data.workouts.flatMap{it.entries.map{e->e.entry}},data.workouts.flatMap{it.entries.flatMap{e->e.sets}},data.imported.workouts.flatMap{it.sets},canonicalImportedSetIds(data))}
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(model::readProgramFile)}
    fun edit(change:(ProgramImportDraft)->ProgramImportDraft){model.programImportDraft.value?.let{model.programImportDraft.value=change(it)};model.programImportError.value=null}
    fun day(key:String,change:(ProgramImportDay)->ProgramImportDay)=edit{d->d.copy(days=d.days.map{if(it.key==key)change(it) else it})}
    fun exercise(dayKey:String,key:String,change:(EditablePlanExercise)->EditablePlanExercise)=day(dayKey){d->d.copy(plan=d.plan.copy(exercises=d.plan.exercises.map{if(it.key==key)change(it) else it}))}
    fun dismiss(){model.cancelProgramImport();close()}
    val ready=draft?.let{it.days.isNotEmpty() && it.days.all{d->d.plan.exercises.isNotEmpty() && d.plan.exercises.all{e->(e.exerciseId?.let{id->id !in incompatible && data.exercises.any{it.id==id}}==true) xor (e.createName!=null)}}}==true
    HandoffScreen(if(draft==null)"Import program" else "Preview program",busy,::dismiss,"program-import-list",attention=error,footer={
        if(draft==null)Button(onClick={model.previewProgram();focus.clearFocus();keyboard?.hide()},enabled=!busy && raw.isNotBlank() && !data.loading && !data.imported.loading){Text("Preview program")}
        else {Button(onClick={model.saveImportedProgram(done=saved)},enabled=!busy && ready,modifier=Modifier.testTag("save-imported-program")){Text("Save one program")};TextButton(onClick={model.programImportDraft.value=null;model.programImportError.value=null},enabled=!busy){Text("Edit pasted text")}}
        TextButton(onClick=::dismiss,enabled=!busy){Text("Cancel")}
    }) {
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        help?.let{item{Text(it,color=MaterialTheme.colorScheme.primary)}}
        val p=draft
        if(p==null) {
            item{Text("Paste a complete program JSON or supported day/exercise text. Preview is read-only; saving installs one editable program with no performed sets.")}
            item{FlowRow{
                OutlinedButton(onClick={val text=clipboard.primaryClip?.takeIf{it.itemCount>0}?.getItemAt(0)?.text?.toString();if(text.isNullOrBlank())model.programImportError.value="Copy a program reply first." else if(text.toByteArray().size>PortableProgramParser.MAX_INPUT)model.programImportError.value="Program exceeds 256 KB." else {model.programImportText.value=text;model.programImportError.value=null}},enabled=!busy){Text("Paste")}
                TextButton(onClick={file.launch(arrayOf("application/json","text/plain","application/pdf"))},enabled=!busy){Text("Choose PDF / file")}
                TextButton(onClick={url=sourceUrl;urlError=null;urlDialog=true},enabled=!busy){Text("From URL")}
            }}
            item{TextButton(onClick={clipboard.setPrimaryClip(ClipData.newPlainText("Program conversion format",PROGRAM_CONVERSION_FORMAT+(sourceUrl.takeIf{it.isNotBlank()}?.let{"\nSource URL: $it"}.orEmpty())));copied=true},enabled=!busy){Text("Copy conversion format")};if(copied)Text("Format copied. Nothing has been sent.")}
            if(sourceUrl.isNotBlank())item{Text("Source link: $sourceUrl");TextButton(onClick={model.programImportSourceUrl.value=""},enabled=!busy){Text("Clear source link")}}
            item{OutlinedTextField(raw,{if(it.toByteArray().size<=PortableProgramParser.MAX_INPUT){model.programImportText.value=it;model.programImportError.value=null}else model.programImportError.value="Program exceeds 256 KB."},label={Text("Program JSON / text")},enabled=!busy,minLines=6,maxLines=14,modifier=Modifier.fillMaxWidth().testTag("program-import-text"))}
            item{Text("Text format: Program: Name\nWeeks: 4\nDay 1: Upper\nBench Press | 3 x 8-12 | rest 90s | RIR 2\n\nPDF/webpage extraction falls back to conversion and Paste. No remote parser, background download or AI subscription is used.",style=MaterialTheme.typography.bodySmall)}
        } else {
            item{Text("Prescriptions only · ${p.days.size} ordered days",style=MaterialTheme.typography.titleMedium);OutlinedTextField(p.name,{v->edit{it.copy(name=v)}},label={Text("Program name")},modifier=Modifier.fillMaxWidth().testTag("import-program-name"));OutlinedTextField(p.description,{v->edit{it.copy(description=v)}},label={Text("Description")},modifier=Modifier.fillMaxWidth());OutlinedTextField(p.weeks,{v->edit{it.copy(weeks=v)}},label={Text("Weeks (1–52)")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item{val root=p.source.document;val source=root.getJSONObject("source");Text("Identity: ${p.source.id} · version ${p.source.version}");for(key in listOf("goals","level","equipment","progression","schedule"))if(root.has(key) && programGuidanceText(root.get(key)).isNotBlank())Text("${key.replaceFirstChar{it.uppercase()}}: ${programGuidanceText(root.get(key))}");for(key in listOf("title","url","author","attribution"))source.optString(key).takeIf{it.isNotBlank()}?.let{Text("Source $key: $it")}}
            items(p.days,key={it.key}){d->
                val index=p.days.indexOf(d)
                OutlinedCard(Modifier.fillMaxWidth().testTag("import-day:${d.key}")){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Day ${index+1}",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(d.plan.title,{v->day(d.key){it.copy(plan=it.plan.copy(title=v))}},label={Text("Day name")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(d.plan.note,{v->day(d.key){it.copy(plan=it.plan.copy(note=v))}},label={Text("Day notes")},modifier=Modifier.fillMaxWidth())
                    FlowRow{TextButton(onClick={edit{it.copy(days=it.days.toMutableList().apply{add(index-1,removeAt(index))})}},enabled=!busy && index>0){Text("Move day up")};TextButton(onClick={edit{it.copy(days=it.days.toMutableList().apply{add(index+1,removeAt(index))})}},enabled=!busy && index<p.days.lastIndex){Text("Move day down")};TextButton(onClick={edit{it.copy(days=it.days.filter{row->row.key!=d.key})}},enabled=!busy){Text("Remove day")}}
                    d.plan.exercises.forEachIndexed { eIndex,e ->
                        var expanded by rememberSaveable(e.key){mutableStateOf(false)}
                        val selected=data.exercises.firstOrNull{it.id==e.exerciseId};val match=matchPlanExercise(e.sourceName,data.exercises,incompatible,aliasMap(data.exerciseAliases))
                        val metadata=JSONObject(p.exerciseMetadata[e.key] ?: "{}")
                        Text("${eIndex+1}. ${e.sourceName}",style=MaterialTheme.typography.titleMedium)
                        Text(when{e.createName!=null->"Create on Save: ${e.createName}";selected!=null->"Matched: ${selected.name}";match.kind==ExerciseMatchKind.AMBIGUOUS->"Ambiguous · choose the intended exercise";else->"Unmatched · select or explicitly create an exercise"},color=if(e.exerciseId!=null || e.createName!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        metadata.optString("canonicalId").takeIf{it.isNotBlank()}?.let{Text("Source exercise ID: $it",style=MaterialTheme.typography.bodySmall)}
                        Text(e.targets.joinToString("\n"){runCatching{it.validated(e.sourceName).text}.getOrDefault("Edited target · check values")}.ifBlank{"Target sets unspecified"})
                        ProgramImportRepository.guidance(JSONObject(metadata.toString()).put("sets",JSONArray(e.targets.map{JSONObject(p.setMetadata[it.key] ?: "{}")}))).takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                        TextButton(onClick={resolvingDay=d.key;resolvingExercise=e.key},enabled=!busy,modifier=Modifier.testTag("resolve-program-exercise:${d.key}:$eIndex")){Text("Resolve exercise")}
                        TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide exercise targets" else "Edit exercise targets")}
                        if(expanded){
                            OutlinedTextField(e.note,{v->exercise(d.key,e.key){it.copy(note=v)}},label={Text("Exercise notes")},modifier=Modifier.fillMaxWidth())
                            e.targets.forEachIndexed { position,target -> SetTargetEditor(target,position+1,busy,{changed->exercise(d.key,e.key){it.copy(targets=it.targets.map{row->if(row.key==target.key)changed else row})}},onRemove={exercise(d.key,e.key){it.copy(targets=it.targets.filter{row->row.key!=target.key})}}) }
                            TextButton(onClick={exercise(d.key,e.key){it.copy(targets=it.targets+EditableTarget())}},enabled=!busy && e.targets.size<100){Text("Add target set")}
                        }
                        FlowRow{TextButton(onClick={day(d.key){it.copy(plan=it.plan.copy(exercises=it.plan.exercises.toMutableList().apply{add(eIndex-1,removeAt(eIndex))}))}},enabled=!busy && eIndex>0){Text("Move exercise up")};TextButton(onClick={day(d.key){it.copy(plan=it.plan.copy(exercises=it.plan.exercises.toMutableList().apply{add(eIndex+1,removeAt(eIndex))}))}},enabled=!busy && eIndex<d.plan.exercises.lastIndex){Text("Move exercise down")};TextButton(onClick={day(d.key){it.copy(plan=it.plan.copy(exercises=it.plan.exercises.filter{row->row.key!=e.key}))}},enabled=!busy){Text("Remove exercise")}}
                        HorizontalDivider()
                    }
                }}
            }
            if(!ready)item{Text("Resolve every exercise and keep at least one exercise per day before saving.",color=MaterialTheme.colorScheme.error)}
        }
    }
    if(urlDialog)AlertDialog(onDismissRequest={urlDialog=false},title={Text("From URL")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("Direct webpage fetching is not supported. Keep the source link, convert the routine with your chosen tool, then Paste JSON/text. No page is fetched or uploaded.");OutlinedTextField(url,{url=it;urlError=null},label={Text("Source URL")},modifier=Modifier.testTag("program-source-url"));urlError?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={if(url.isBlank() || !PortableProgramParser.validUrl(url.trim()))urlError="Enter an http/https URL without embedded credentials." else {model.programImportSourceUrl.value=url.trim();model.programImportHelp.value="Source link retained. Convert the routine, then Paste its complete program JSON. No webpage was fetched.";urlDialog=false}},enabled=!busy){Text("Use link / Paste")}},dismissButton={TextButton(onClick={urlDialog=false}){Text("Cancel")}})
    val d=draft?.days?.firstOrNull{it.key==resolvingDay};val e=d?.plan?.exercises?.firstOrNull{it.key==resolvingExercise}
    if(d!=null && e!=null)Dialog(onDismissRequest={if(!busy){resolvingDay=null;resolvingExercise=null}},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(),color=MaterialTheme.colorScheme.background){ExercisePicker(data.exercises.filter{it.id !in incompatible},d.plan.exercises.filter{it.key!=e.key}.mapNotNull{it.exerciseId}.toSet(),emptyList(),emptyMap(),busy,{resolvingDay=null;resolvingExercise=null},{ids->ids.singleOrNull()?.let{id->exercise(d.key,e.key){it.copy(exerciseId=id,createName=null)}};resolvingDay=null;resolvingExercise=null},{name->runCatching{InputRules.exerciseName(name)}.onSuccess{valid->exercise(d.key,e.key){it.copy(exerciseId=null,createName=valid)}}.onFailure{model.programImportError.value=it.message};resolvingDay=null;resolvingExercise=null},singleSelection=true,aliases=data.exerciseAliases)}
    }
    duplicate?.let{id->AlertDialog(onDismissRequest={if(!busy)model.duplicateProgramId.value=null},title={Text("Program already installed")},text={Text("This identity/version or definition is already installed. Existing local edits and history will not be overwritten.")},confirmButton={TextButton(onClick={model.cancelProgramImport();saved(id)},enabled=!busy){Text("Open installed program")}},dismissButton={FlowRow{TextButton(onClick={model.saveImportedProgram(true,saved)},enabled=!busy){Text("Install another copy")};TextButton(onClick={model.duplicateProgramId.value=null}){Text("Cancel")}}})}
}
