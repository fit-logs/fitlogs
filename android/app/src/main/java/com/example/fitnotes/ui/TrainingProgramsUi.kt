package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import org.json.JSONObject
import org.json.JSONArray

@Composable
fun ProgramNextCard(p: ProgramDetail?, data: AppData, sessions: List<ProgramSession>, busy: Boolean, start: () -> Unit, manage: (() -> Unit)?, complete: ((Long) -> Unit)? = null, later: (() -> Unit)? = null, resume: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("next-program-workout")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Up Next",style=MaterialTheme.typography.titleMedium)
        if(p==null) {
            Text("Choose an active program to see what to train next. You can always log an ad-hoc workout below.")
            if(manage!=null)TextButton(onClick=manage,enabled=!busy){Text("Choose program")}
        } else {
            Text(p.program.title,style=MaterialTheme.typography.titleLarge)
            if(p.program.flexibleQueue) {
                val occurrence=p.inProgress ?: p.nextOccurrence
                val next=occurrence?.plan
                if(data.active!=null) {
                    Text("Workout in progress · ${data.active?.workout?.title.orEmpty()}")
                    Button(onClick=resume ?: start,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Resume workout")}
                } else if(next!=null) {
                    Text(next.plan.title,style=MaterialTheme.typography.titleMedium)
                    Text(if(next.plan.activityType=="running")RunningPrescription.parse(JSONObject(next.plan.runningJson)).summary.ifBlank{"Running guidance"} else planSubtitle(next.exercises.size,next.plan.durationMinutes))
                    occurrence.step.scheduledDate.takeIf{it.isNotBlank()}?.let {Text("Scheduled $it · date guidance; queue order chooses Up Next",style=MaterialTheme.typography.bodySmall)}
                    val finished=occurrence.step.workoutId?.let { id -> data.workouts.firstOrNull { it.workout.id==id && it.workout.isComplete && !it.workout.historicalDraft } }
                    Button(onClick=if(finished!=null && complete!=null)({complete(finished.workout.id)}) else if(next.plan.activityType=="running")manage ?: start else start,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(finished!=null)"Confirm finished workout" else if(next.plan.activityType=="running")"View / Link recorded run" else "Start workout")}
                    if(finished!=null)Text("Review the auto-finished session or resume it from the notice above. Confirming links its saved results to this occurrence.",style=MaterialTheme.typography.bodySmall)
                    FlowRow { if(later!=null && occurrence.step.action=="pending")TextButton(onClick=later,enabled=!busy){Text("Do later")}; if(manage!=null)TextButton(onClick=manage,enabled=!busy){Text("Choose another")} }
                } else Text("Program complete · ${p.progress}")
                if(manage!=null)TextButton(onClick=manage,enabled=!busy){Text("View / Edit Program")}
                return@Column
            }
            val next=p.next
            Text("Week ${p.week} of ${p.program.weeks} · Day ${(p.program.nextIndex % p.days.size)+1} of ${p.days.size}")
            next?.let { Text(it.plan.plan.title,style=MaterialTheme.typography.titleMedium);Text(planSubtitle(it.plan.exercises.size,it.plan.plan.durationMinutes)) }
            Text(p.progress,style=MaterialTheme.typography.bodySmall)
            if(p.program.notBefore.isNotBlank())Text("Postponed until ${p.program.notBefore}")
            val activeMatches=data.active?.workout?.id?.let { id -> sessions.any { it.workoutId==id && it.programId==p.program.id && it.dayIndex==p.program.nextIndex } }==true
            val pending = if(complete==null || activeMatches) null else sessions.filter { it.programId==p.program.id && it.dayIndex==p.program.nextIndex }.mapNotNull { s -> data.workouts.firstOrNull { it.workout.id==s.workoutId && it.workout.isComplete && it.entries.any { e -> e.sets.isNotEmpty() } } }.maxByOrNull { it.workout.id }
            Button(onClick={ if(pending!=null)complete?.invoke(pending.workout.id) else start() },enabled=!busy && next!=null && (pending!=null || p.program.status=="active" && (data.active==null || activeMatches) && (p.program.notBefore.isBlank() || todayDate()>=p.program.notBefore)),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(pending!=null)"Complete program day" else if(activeMatches)"Continue program workout" else "Start next workout") }
            if(pending!=null)Text("Saved results from ${localWorkoutDate(pending.workout)} are ready. Confirm to advance the program.",style=MaterialTheme.typography.bodySmall)
            if(data.active!=null && !activeMatches)Text("An ad-hoc or other workout is active. Finish or discard it before starting this day.",style=MaterialTheme.typography.bodySmall)
            if(manage!=null)TextButton(onClick=manage,enabled=!busy){Text("Manage program / Complete day")}
        }
    } }
}

@Composable
fun TemplatesDialog(model: FitNotesViewModel,busy: Boolean,close: ()->Unit,open:(String)->Unit,new:()->Unit,ask:()->Unit) {
    val all by model.plans.collectAsState();val programs by model.programs.collectAsState();val error by model.programError.collectAsState()
    val owned=programs.flatMap{it.days.map{d->d.day.planId}+it.steps.mapNotNull{step->step.planId}}.toSet()
    var query by rememberSaveable{mutableStateOf("")};var archived by rememberSaveable{mutableStateOf(false)};var alphabetical by rememberSaveable{mutableStateOf(false)}
    var rename by rememberSaveable{mutableStateOf<String?>(null)};var title by rememberSaveable{mutableStateOf("")};var deleting by rememberSaveable{mutableStateOf<String?>(null)}
    val templates=all.filter{it.plan.id !in owned && it.plan.archived==archived && it.plan.title.contains(query,true)}.let { list -> if(alphabetical)list.sortedWith(compareByDescending<PlannedWorkoutDetail>{it.plan.pinned}.thenBy{it.plan.title.lowercase()}) else list.sortedWith(compareByDescending<PlannedWorkoutDetail>{it.plan.pinned}.thenByDescending{it.plan.createdAt}.thenBy{it.plan.id}) }
    HandoffScreen("My templates",busy,close,"template-list",attention=error,footer={
        TextButton(onClick={close();new()},enabled=!busy){Text("New template")}
        TextButton(onClick={close();ask()},enabled=!busy){Text("Coach")}
        TextButton(onClick=close,enabled=!busy){Text("Close")}
    }) {
        item { Text("Reusable recipes. Starting a template creates a new session; targets never count as performed sets.");OutlinedTextField(query,{query=it},label={Text("Search templates")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("template-search")) }
        item { FlowRow { FilterChip(archived,{archived=!archived},label={Text(if(archived)"Archived" else "Available")});TextButton(onClick={alphabetical=!alphabetical}){Text(if(alphabetical)"Sort: A–Z" else "Sort: recent")}} }
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(templates.isEmpty())item{Text(if(archived)"No archived templates match." else "No templates match. Create or import a template.")}
        items(templates,key={it.plan.id}){ p ->
            var menu by remember{mutableStateOf(false)}
            OutlinedCard(Modifier.fillMaxWidth().testTag("template:${p.plan.id}")){Row(Modifier.padding(12.dp)){
                Column(Modifier.weight(1f)) {
                    // A multiline title needs a rectangular touch surface: the
                    // default capsule button clips its corners at large text sizes.
                    Surface(onClick={close();open(p.plan.id)},enabled=!busy,shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text((if(p.plan.pinned)"★ " else "")+p.plan.title,Modifier.padding(8.dp),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                    }
                    Text(planSubtitle(p.exercises.size,p.plan.durationMinutes),style=MaterialTheme.typography.bodySmall)
                }
                Box { IconButton(onClick={menu=true},enabled=!busy){Icon(Icons.Default.MoreVert,"Template actions: ${p.plan.title}")}
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("Rename")},onClick={menu=false;title=p.plan.title;rename=p.plan.id})
                        DropdownMenuItem(text={Text("Duplicate")},onClick={menu=false;model.duplicateTemplate(p.plan.id)})
                        DropdownMenuItem(text={Text(if(p.plan.pinned)"Unpin" else "Pin")},onClick={menu=false;model.pinTemplate(p.plan.id,!p.plan.pinned)})
                        DropdownMenuItem(text={Text(if(archived)"Unarchive" else "Archive")},onClick={menu=false;model.archiveTemplate(p.plan.id,!archived)})
                        DropdownMenuItem(text={Text("Delete")},onClick={menu=false;deleting=p.plan.id})
                    }
                }
            }}
        }
    }
    rename?.let{id->AlertDialog(onDismissRequest={if(!busy)rename=null},title={Text("Rename template")},text={Column{OutlinedTextField(title,{title=it},label={Text("Template title")},modifier=Modifier.testTag("template-title"));error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={model.renameTemplate(id,title){rename=null}},enabled=!busy){Text("Save name")}},dismissButton={TextButton(onClick={rename=null},enabled=!busy){Text("Cancel")}})}
    deleting?.let{id->NotebookConfirm("Delete template?","Only this recipe is removed. Recorded workouts, sets and program copies remain.","Delete",busy,{deleting=null}){model.deleteTemplate(id){deleting=null}}}
}

@Composable
fun ProgramsDialog(model: FitNotesViewModel,data:AppData,busy:Boolean,close:()->Unit,start:(String)->Unit,edit:(String)->Unit,importProgram:(()->Unit)?=null, initialProgramId:String?=null, startSession:(String,String)->Unit={_,_->}, openEditor:()->Unit={}) {
    val programs by model.programs.collectAsState();val sessions by model.programSessions.collectAsState();val plans by model.plans.collectAsState();val error by model.programError.collectAsState()
    val notebook by model.notebook.collectAsState()
    val linkableRuns=remember(notebook.runs,sessions,data.workouts) {
        val visible=notebook.runs.map{it.parentKey}.toSet()
        val linked=sessions.map{it.workoutId}.toSet()
        data.workouts.filter{it.workout.activityType=="running" && it.workout.isComplete && !it.workout.historicalDraft && it.workout.id !in linked && "local:${it.workout.id}" in visible}
    }
    var creating by rememberSaveable{mutableStateOf(false)};var selected by rememberSaveable{mutableStateOf<String?>(initialProgramId)};var skipping by rememberSaveable{mutableStateOf(false)};var date by rememberSaveable{mutableStateOf("")};var postponing by rememberSaveable{mutableStateOf(false)}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    var programSettings by rememberSaveable{mutableStateOf(false)}
    var editingIdentity by rememberSaveable{mutableStateOf(false)};var programTitle by rememberSaveable{mutableStateOf("")};var description by rememberSaveable{mutableStateOf("")}
    var changingDate by rememberSaveable{mutableStateOf<String?>(null)}
    var runningEditor by rememberSaveable{mutableStateOf<String?>(null)}
    var linkRun by rememberSaveable{mutableStateOf<String?>(null)}
    var editChoice by rememberSaveable{mutableStateOf<String?>(null)}
    fun editOccurrence(program:ProgramDetail,id:String,future:Boolean=false) {
        model.editProgramSession(program.program.id,id,future){planId->
            val occurrence=program.queue.firstOrNull{it.step.id==id}
            if(occurrence?.plan?.plan?.activityType=="running")runningEditor=planId else {close();openEditor()}
        }
    }
    val p=programs.firstOrNull{it.program.id==selected}
    HandoffScreen(if(p==null)"My programs" else p.program.title,busy,{if(selected==null)close() else selected=null},"program-list",attention=error,footer={
        if(p==null) { TextButton(onClick={creating=true},enabled=!busy){Text("Build from templates")};if(importProgram!=null)TextButton(onClick=importProgram,enabled=!busy){Text("Import program")} }
        TextButton(onClick=close,enabled=!busy){Text("Close")}
    }) {
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(p==null) {
            item{Text("One primary active program. Ad-hoc workouts and past logs do not advance it.")}
            if(programs.isEmpty())item{Text("No programs yet. Arrange templates into workout days, then set the program active.")}
            items(programs,key={it.program.id}){program->OutlinedCard(onClick={selected=program.program.id},modifier=Modifier.fillMaxWidth().testTag("program:${program.program.id}")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(program.program.title,style=MaterialTheme.typography.titleMedium);Text("${program.program.status.replaceFirstChar{it.uppercase()}} · ${program.days.size} days · ${program.program.weeks} weeks");Text(program.progress)
                program.next?.let{Text("Next: Week ${program.week} · ${it.plan.plan.title}")}
            }}}
        } else {
            item { Text("${p.program.status.replaceFirstChar{it.uppercase()}} · ${p.progress}",style=MaterialTheme.typography.bodyMedium) }
            item {
                TextButton(onClick={programSettings=!programSettings}){Text(if(programSettings)"Hide program settings" else "Program settings")}
                if(programSettings) {
                val metadata=p.program.definitionJson.takeIf{it.isNotBlank()}?.let(::JSONObject)
                metadata?.optString("description")?.takeIf{it.isNotBlank()}?.let{Text(it)}
                metadata?.let{root->for(key in listOf("goals","level","equipment","progression","schedule"))if(root.has(key) && programGuidanceText(root.get(key)).isNotBlank())Text("${key.replaceFirstChar{it.uppercase()}}: ${programGuidanceText(root.get(key))}")
                    root.optJSONObject("source")?.let{source->for(key in listOf("title","url","author","attribution"))source.optString(key).takeIf{it.isNotBlank()}?.let{Text("Source $key: $it")}}
                    Text("Editable local program. Changes are saved on this device.",style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={programTitle=p.program.title;description=metadata?.optString("description").orEmpty();editingIdentity=true},enabled=!busy){Text("Edit program details")}
                }
            }
            if(p.program.flexibleQueue) {
                item { FlowRow {
                    if(p.program.status=="active")TextButton(onClick={model.pauseProgram(p.program.id)},enabled=!busy){Text("Pause program")}
                    else if(p.next!=null)TextButton(onClick={model.activateProgram(p.program.id)},enabled=!busy){Text("Set active / Resume program")}
                };Text("Queue order chooses Up Next. Dates are optional guidance and stay unchanged when moving a session.",style=MaterialTheme.typography.bodySmall) }
                items(p.queue,key={it.step.id}) { occurrence ->
                    val step=occurrence.step;val plan=occurrence.plan
                    var menu by remember { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth().testTag("queue-session:${step.id}")) { Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${step.queuePosition+1}. ${plan?.plan?.title.orEmpty()}",style=MaterialTheme.typography.titleMedium)
                            Text(step.action.replace('_',' ').replaceFirstChar{it.uppercase()})
                            if(plan!=null)Text(if(plan.plan.activityType=="running")RunningPrescription.parse(JSONObject(plan.plan.runningJson)).summary else planSubtitle(plan.exercises.size,plan.plan.durationMinutes),style=MaterialTheme.typography.bodySmall)
                            if(step.scheduledDate.isNotBlank())Text("Scheduled ${step.scheduledDate}")
                            if(step.action=="skipped")TextButton(onClick={model.restoreProgramSession(p.program.id,step.id)},enabled=!busy){Text("Restore")}
                            if(step.action=="in_progress")TextButton(onClick={startSession(p.program.id,step.id)},enabled=!busy){Text("Resume workout")}
                        }
                        Box { IconButton(onClick={menu=true},enabled=!busy){Icon(Icons.Default.MoreVert,"Session actions: ${plan?.plan?.title.orEmpty()}")}
                            DropdownMenu(menu,{menu=false}) {
                                if(step.action=="pending") {
                                    DropdownMenuItem(text={Text(if(plan?.plan?.activityType=="running")"Link recorded run" else "Start now")},enabled=p.program.status=="active" && data.active==null,onClick={menu=false;if(plan?.plan?.activityType=="running")linkRun=step.id else startSession(p.program.id,step.id)})
                                    DropdownMenuItem(text={Text("Move earlier")},enabled=step.queuePosition>0,onClick={menu=false;model.moveProgramSession(p.program.id,step.id,step.queuePosition-1)})
                                    DropdownMenuItem(text={Text("Move later")},enabled=step.queuePosition<p.queue.lastIndex,onClick={menu=false;model.moveProgramSession(p.program.id,step.id,step.queuePosition+1)})
                                    DropdownMenuItem(text={Text("Move to end")},enabled=step.queuePosition<p.queue.lastIndex,onClick={menu=false;model.doProgramLater(p.program.id,step.id)})
                                    DropdownMenuItem(text={Text("Skip session")},onClick={menu=false;model.skipProgramSession(p.program.id,step.id)})
                                }
                                if(step.action in setOf("pending","skipped")) {
                                    DropdownMenuItem(text={Text("Edit session")},onClick={menu=false;if(p.program.weeks>1)editChoice=step.id else editOccurrence(p,step.id)})
                                    DropdownMenuItem(text={Text("Change scheduled date")},onClick={menu=false;date=step.scheduledDate;changingDate=step.id})
                                }
                            }
                        }
                    } }
                }
            } else {
                item { TextButton(onClick={model.enableProgramQueue(p.program.id)},enabled=!busy){Text("Enable flexible queue")};Text("Keeps recorded completions and skips. Pending sessions become reorderable; finishing their linked workout completes them.",style=MaterialTheme.typography.bodySmall) }
            if(p.next!=null)item {
                ProgramNextCard(p,data,sessions,busy,{close();start(p.program.id)},null)
                FlowRow {
                    if(p.program.status=="active")TextButton(onClick={model.pauseProgram(p.program.id)},enabled=!busy){Text("Pause program")}
                    else TextButton(onClick={model.activateProgram(p.program.id)},enabled=!busy){Text("Set active / Resume program")}
                    TextButton(onClick={date=p.program.notBefore;postponing=true},enabled=!busy){Text("Postpone day")}
                    TextButton(onClick={skipping=true},enabled=!busy){Text("Skip day")}
                }
                val eligible=sessions.filter{it.programId==p.program.id && it.dayIndex==p.program.nextIndex}.mapNotNull{s->data.workouts.firstOrNull{it.workout.id==s.workoutId}}.filter{it.workout.isComplete && it.entries.any{e->e.sets.isNotEmpty()}}.sortedByDescending{it.workout.id}
                eligible.forEach{w->Button(onClick={model.completeProgramDay(p.program.id,w.workout.id)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("complete-program-day:${w.workout.id}")){Text("Complete program day · ${localWorkoutDate(w.workout)}")}}
                Text("Finish a workout with actual sets, then confirm Complete program day here. Skipping is recorded separately.",style=MaterialTheme.typography.bodySmall)
            }
            items(p.ordered,key={it.day.id}){day->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text("Day ${day.day.position+1} · ${day.plan.plan.title}",style=MaterialTheme.typography.titleMedium);Text(planSubtitle(day.plan.exercises.size,day.plan.plan.durationMinutes))
                TextButton(onClick={close();edit(day.day.planId)},enabled=!busy){Text("Review / edit day")}
            }}}
            }
        }
    }
    if(changingDate!=null && p!=null)AlertDialog(onDismissRequest={focus.clearFocus();keyboard?.hide();changingDate=null;model.programError.value=null},title={Text("Change scheduled date")},text={Column{OutlinedTextField(date,{date=it},label={Text("YYYY-MM-DD (optional)")},singleLine=true);Text("Leave blank for an undated session. Queue order stays unchanged.");error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={model.scheduleProgramSession(p.program.id,changingDate!!,date){focus.clearFocus();keyboard?.hide();changingDate=null}},enabled=!busy){Text("Save date")}},dismissButton={TextButton(onClick={focus.clearFocus();keyboard?.hide();changingDate=null;model.programError.value=null}){Text("Cancel")}})
    if(editChoice!=null && p!=null)AlertDialog(onDismissRequest={editChoice=null},title={Text("Edit repeating session")},text={Text("This occurrence gets its own copy. Editing the reusable day updates future occurrences that still use it, while active and completed workouts keep their snapshots.")},confirmButton={TextButton(onClick={val id=editChoice!!;editChoice=null;editOccurrence(p,id)}){Text("This occurrence only")}},dismissButton={TextButton(onClick={val id=editChoice!!;editChoice=null;editOccurrence(p,id,true)}){Text("Reusable day / future")}})
    runningEditor?.let { id -> plans.firstOrNull { it.plan.id==id }?.let { RunningPlanEditor(it.plan,model,busy){runningEditor=null} } }
    if(linkRun!=null && p!=null)HandoffScreen("Link recorded run",busy,{linkRun=null},"link-program-run",attention=error,footer={TextButton(onClick={linkRun=null}){Text("Close")}}) {
        item{Text("Choose the actual completed local or imported run. Distance and duration targets will never fill actual results.");error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}
        val runs=linkableRuns
        if(runs.isEmpty())item{Text("No unlinked recorded runs. Import or log your actual run through the existing activity workflow, then return here.")}
        items(runs,key={it.workout.id}){ run->
            val record=notebook.runs.firstOrNull{it.parentKey=="local:${run.workout.id}"}
            val metrics=record?.let{runSummary(it)+it.preferredTime?.let{time->" · ${time.first.durationLabel}"}.orEmpty()}.orEmpty()
            OutlinedButton(onClick={model.linkProgramRun(p.program.id,linkRun!!,run.workout.id){linkRun=null}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("${localWorkoutDate(run.workout)} · ${run.workout.title.ifBlank{"Running"}} · $metrics")}
        }
    }
    if(editingIdentity && p!=null)AlertDialog(onDismissRequest={if(!busy)editingIdentity=false},title={Text("Edit program details")},text={Column(Modifier.verticalScroll(rememberScrollState())){OutlinedTextField(programTitle,{programTitle=it},label={Text("Program title")},modifier=Modifier.testTag("edit-program-title"));if(p.program.definitionJson.isNotBlank())OutlinedTextField(description,{description=it},label={Text("Description")});error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={model.editProgramIdentity(p.program.id,programTitle,description){editingIdentity=false}},enabled=!busy){Text("Save details")}},dismissButton={TextButton(onClick={editingIdentity=false},enabled=!busy){Text("Cancel")}})
    if(creating) ProgramBuilderDialog(model,plans.filter{plan->!plan.plan.archived && programs.none{p->p.days.any{it.day.planId==plan.plan.id} || p.steps.any{it.planId==plan.plan.id}}},busy){creating=false}
    if(skipping && p!=null)NotebookConfirm("Skip this program day?","The next day advances, with this day recorded as skipped. No performed workout is created or marked completed.","Skip day",busy,{skipping=false}){model.skipProgramDay(p.program.id);skipping=false}
    if(postponing && p!=null)AlertDialog(onDismissRequest={postponing=false},title={Text("Postpone next day")},text={Column{OutlinedTextField(date,{date=it},label={Text("Not before (YYYY-MM-DD)")},singleLine=true);Text("Leave blank to clear. This changes no completions or day order.");error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={model.postponeProgram(p.program.id,date);postponing=false},enabled=!busy){Text("Save date")}},dismissButton={TextButton(onClick={postponing=false}){Text("Cancel")}})
}
@Composable
private fun ProgramBuilderDialog(model:FitNotesViewModel,templates:List<PlannedWorkoutDetail>,busy:Boolean,close:()->Unit) {
    var title by rememberSaveable{mutableStateOf("")};var weeks by rememberSaveable{mutableStateOf("4")};var days by rememberSaveable{mutableStateOf(listOf<String>())};val error by model.programError.collectAsState()
    HandoffScreen("Build program",busy,close,"program-builder",attention=error,footer={Button(onClick={model.createProgram(title,weeks.toIntOrNull()?:0,days,close)},enabled=!busy && days.isNotEmpty()){Text("Save program")};TextButton(onClick=close,enabled=!busy){Text("Cancel")}}){
        item{OutlinedTextField(title,{title=it},label={Text("Program title")},modifier=Modifier.fillMaxWidth().testTag("program-title"));OutlinedTextField(weeks,{weeks=it},label={Text("Weeks (1–52)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth().testTag("program-weeks"));Text("Add templates in workout order. Copies belong to this program; the originals stay available.")}
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        items(days.indices.toList(),key={it}){index->Column{Text("Day ${index+1} · "+templates.firstOrNull{it.plan.id==days[index]}?.plan?.title.orEmpty());FlowRow{TextButton(onClick={days=days.toMutableList().apply{add(index-1,removeAt(index))}},enabled=index>0 && !busy){Text("Move up")};TextButton(onClick={days=days.filterIndexed{i,_->i!=index}},enabled=!busy){Text("Remove day")}}}}
        if(templates.isEmpty())item{Text("Create or import a template first.")}
        items(templates,key={it.plan.id}){t->Surface(onClick={days=days+t.plan.id},enabled=!busy && days.size<14 && t.exercises.isNotEmpty(),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("add-program-day:${t.plan.id}")){Text("Add day · ${t.plan.title}",Modifier.padding(12.dp),color=MaterialTheme.colorScheme.primary)}}
    }
}

// Imported structured guidance is displayed as readable text, never executed.
internal fun programGuidanceText(value:Any?):String=when(value){
    null,JSONObject.NULL -> ""
    is JSONArray -> (0 until value.length()).joinToString(", "){programGuidanceText(value.get(it))}
    is JSONObject -> value.keys().asSequence().toList().joinToString("\n"){key->key.replaceFirstChar{it.uppercase()}+": "+programGuidanceText(value.get(key))}
    else -> value.toString()
}
