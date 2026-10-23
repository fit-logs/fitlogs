package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import java.util.UUID

@Composable
fun LoadAdviceDialog(model:FitNotesViewModel,data:AppData,busy:Boolean) {
    val rows by model.loadAdviceRows.collectAsState();val error by model.loadAdviceError.collectAsState();val profiles by model.loadProfiles.collectAsState()
    var settings by rememberSaveable{mutableStateOf<Long?>(null)};var changing by rememberSaveable{mutableStateOf<Long?>(null)}
    var weight by rememberSaveable{mutableStateOf("")};var mode by rememberSaveable{mutableStateOf(LoadMode.UNKNOWN)};var lock by rememberSaveable{mutableStateOf(true)}
    HandoffScreen("Suggested weights",busy,model::closeLoadAdvice,"load-advice-list",attention=error,footer={TextButton(onClick=model::refreshLoadAdvice,enabled=!busy){Text("Refresh")};TextButton(onClick=model::closeLoadAdvice,enabled=!busy){Text("Close")}}){
        item{Text("Computed on this device from completed, comparable saved sets. Use changes target prescriptions only; Change lets you choose manually. Actual results are never pre-saved.")}
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(rows.isEmpty())item{Text(if(busy)"Reading comparable history…" else "No exercise targets available.")}
        rows.forEach{row->item(key=row.subject.exerciseId){
            OutlinedCard(Modifier.fillMaxWidth().testTag("load-advice:${row.subject.exerciseId}")){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(row.subject.name,style=MaterialTheme.typography.titleLarge)
                Text("Suggested weight",style=MaterialTheme.typography.titleMedium)
                if(row.advice.loads.isEmpty())Text("Manual start / calibration",color=MaterialTheme.colorScheme.primary)
                else {
                    val same=row.advice.loads.map{it.value to it.unit}.distinct().singleOrNull()
                    if(same!=null)Text("${same.first} ${same.second} · ${row.advice.loads.size} working sets",style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("suggested-load:${row.subject.exerciseId}"))
                    row.advice.loads.forEachIndexed{i,t->Text("Set ${i+1}: ${t.value} ${t.unit} · ${t.reps} reps · target ${weightText(t.rir)} RIR")}
                }
                Text(row.advice.confidence,style=MaterialTheme.typography.labelLarge)
                Text(row.advice.explanation,style=MaterialTheme.typography.bodySmall)
                if(row.hasActual)Text("Logging has started. Targets stay unchanged; override actual weights in the logger.")
                if(row.locked)Text("Advice locked for this session. Actual weight entry remains editable.")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={model.useSuggestedLoads(row,lock)},enabled=!busy && row.advice.loads.isNotEmpty() && !row.locked && !row.hasActual,modifier=Modifier.testTag("use-load:${row.subject.exerciseId}")){Text("Use")}
                    OutlinedButton(onClick={changing=row.subject.exerciseId;weight=row.advice.loads.firstOrNull()?.let{"${it.value} ${it.unit}"}.orEmpty();mode=row.profile?.mode ?: row.subject.request.targets.map{it.mode}.distinct().singleOrNull() ?: LoadMode.UNKNOWN},enabled=!busy && !row.locked && !row.hasActual){Text("Change")}
                    TextButton(onClick={settings=row.subject.exerciseId},enabled=!busy){Text("Load setup")}
                }
                if(row.subject.kind=="entry") {
                    if(row.locked)TextButton(onClick={model.lockSessionLoads(row.subject.key.toLong(),false)},enabled=!busy){Text("Unlock advice")}
                    else Row {Checkbox(lock,{lock=it},enabled=!busy);Text("Lock advice after Use",Modifier.padding(top=12.dp))}
                }
            }}
        }}
    }
    settings?.let{id->LoadSetupDialog(id,data.exercises.firstOrNull{it.id==id}?.name.orEmpty(),profiles.filter{it.exerciseId==id},model,busy){settings=null}}
    val row=rows.firstOrNull{it.subject.exerciseId==changing}
    if(row!=null)AlertDialog(onDismissRequest={if(!busy)changing=null},title={Text("Choose target weight")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("This is a target only. Existing results and warm-up targets stay unchanged.")
        OutlinedTextField(weight,{weight=it},label={Text("Weight with unit (kg / lb / bodyweight)")},modifier=Modifier.fillMaxWidth().testTag("manual-load"));LoadModePicker(mode,{mode=it},!busy)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={model.changeTargetLoads(row,weight,mode,lock){changing=null}},enabled=!busy && mode!=LoadMode.UNKNOWN){Text("Use manual target")}},dismissButton={TextButton(onClick={changing=null},enabled=!busy){Text("Cancel")}})
}

@Composable
private fun LoadSetupDialog(exerciseId:Long,name:String,profiles:List<LoadProfile>,model:FitNotesViewModel,busy:Boolean,close:()->Unit) {
    val selected=profiles.firstOrNull{it.selected};val error by model.loadAdviceError.collectAsState()
    var gym by rememberSaveable{mutableStateOf(selected?.gym.orEmpty())};var equipment by rememberSaveable{mutableStateOf(selected?.equipment.orEmpty())}
    var basis by rememberSaveable{mutableStateOf(selected?.basis ?: "unknown")};var mode by rememberSaveable{mutableStateOf(selected?.mode ?: LoadMode.UNKNOWN)}
    var unit by rememberSaveable{mutableStateOf(selected?.unit ?: "kg")};var increment by rememberSaveable{mutableStateOf(selected?.increment ?: "2.5")};var rir by rememberSaveable{mutableStateOf(selected?.targetRir?.let(::weightText) ?: "2")}
    var reps by rememberSaveable{mutableStateOf(selected?.repChoice ?: "lower")};var legacy by rememberSaveable{mutableStateOf(selected?.reviewedLegacy ?: false)}
    HandoffScreen("Load setup · $name",busy,close,"load-setup-list",attention=error,footer={Button(onClick={model.saveLoadProfile(LoadProfile(selected?.id ?: UUID.randomUUID().toString(),exerciseId,gym.trim(),equipment.trim(),basis,mode,unit,increment.trim(),rir.toDoubleOrNull() ?: Double.NaN,reps,true,legacy),close)},enabled=!busy){Text("Save setup")};TextButton(onClick=close,enabled=!busy){Text("Cancel")}}){
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        if(profiles.isNotEmpty())item{Text("Saved setups",style=MaterialTheme.typography.titleMedium);profiles.forEach{p->Surface(onClick={model.selectLoadProfile(p.id,close)},enabled=!busy,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("${if(p.selected)"✓ " else ""}${p.gym} · ${p.equipment} · ${p.basis.replace('_',' ')} · ${p.increment} ${p.unit}",Modifier.padding(12.dp))}}}
        item{Text("Only this exact exercise ID and selected setup are compared. Use distinct equipment labels for different machines. Changing a setup label creates a separate profile; old records keep their original context.")}
        item{OutlinedTextField(gym,{gym=it.take(100)},label={Text("Gym")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("load-gym"));OutlinedTextField(equipment,{equipment=it.take(100)},label={Text("Specific equipment / variant")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("load-equipment"))}
        item{LoadModePicker(mode,{mode=it},!busy);ChoiceField("Weight basis",basis,mapOf("unknown" to "Confirm basis","total" to "Total barbell / implement load","per_hand" to "One dumbbell / per hand","stack" to "This machine's stack marking"),!busy){basis=it}}
        item{Text("The actual logger records kg. Per-hand values stay per hand; no doubling or body mass is inferred. Suggested targets can use kg or lb.",style=MaterialTheme.typography.bodySmall)}
        item{ChoiceField("Suggestion unit",unit,mapOf("kg" to "kg","lb" to "lb"),!busy){unit=it};OutlinedTextField(increment,{increment=it},label={Text("Available increment")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("load-increment"))}
        item{ChoiceField("Rep goal within range",reps,mapOf("lower" to "Lower rep target","upper" to "Upper rep target"),!busy){reps=it};OutlinedTextField(rir,{rir=it},label={Text("Default target RIR (0–4)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())}
        item{HandoffCheck("Use reviewed older work sets from this same gym, equipment, load mode and weight basis",legacy,!busy){legacy=it};Text("Enable only after reviewing legacy/imported records. They remain labelled assumptions with lower confidence and cannot trigger progression. Raw history is unchanged.",style=MaterialTheme.typography.bodySmall)}
    }
}
@Composable
private fun ChoiceField(label:String,value:String,options:Map<String,String>,enabled:Boolean,change:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Box{OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.heightIn(min=48.dp)){Text("$label: ${options[value] ?: value} ▾")};DropdownMenu(expanded,{expanded=false}){options.forEach{(key,title)->DropdownMenuItem(text={Text(title)},onClick={change(key);expanded=false})}}}
}
