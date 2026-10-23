package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProgramCatalogDialog(model:FitNotesViewModel,busy:Boolean,close:()->Unit,import:()->Unit) {
    val snapshot by model.catalog.collectAsState();val loading by model.catalogBusy.collectAsState();val error by model.catalogError.collectAsState()
    val entry by model.catalogEntry.collectAsState();val program by model.catalogProgram.collectAsState();val installed by model.programs.collectAsState()
    var query by rememberSaveable{mutableStateOf("")};var goal by rememberSaveable{mutableStateOf("")};var level by rememberSaveable{mutableStateOf("")}
    var days by rememberSaveable{mutableStateOf("")};var minutes by rememberSaveable{mutableStateOf("")};var gear by rememberSaveable{mutableStateOf("")};var filters by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(Unit){model.loadCatalog()}
    val filter=CatalogFilter(query,goal,level,days.toIntOrNull(),minutes.toIntOrNull(),gear.split('|').filter{it.isNotBlank()}.toSet())
    HandoffScreen(entry?.title ?: "Workout Library",false,close,"catalog-list",attention=error,footer={
        if(entry==null) {
            TextButton(onClick=model::refreshCatalog,enabled=!loading){Text("Refresh catalog")}
            TextButton(onClick=import,enabled=!busy){Text("Import my program")}
        } else {
            Button(onClick={model.reviewCatalogProgram();close()},enabled=!busy && !loading && program!=null,modifier=Modifier.testTag("review-catalog-install")){Text("Review installation")}
            TextButton(onClick=model::catalogBack){Text("Back to library")}
        }
        TextButton(onClick=close){Text("Close")}
    }) {
        item {Text("Original generic prescriptions. Personal weights stay on this device; installation creates an editable local copy.")}
        if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading public program data…")}
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error);Text("Installed programs and cached starters remain usable.")}}
        if(entry==null) {
            snapshot?.let{s->
                item{Text(s.status);Text("Catalog ${s.manifest.version}",style=MaterialTheme.typography.bodySmall)
                    s.refreshedAt?.let{Text("Last successful refresh: "+DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)),style=MaterialTheme.typography.bodySmall)}
                    Text("Opening an uncached entry downloads its public JSON from GitHub Pages. Refresh fetches the manifest only; no workout data is sent.",style=MaterialTheme.typography.bodySmall)}
                item{OutlinedTextField(query,{query=it},label={Text("Search programs")},modifier=Modifier.fillMaxWidth().testTag("catalog-search"));TextButton(onClick={filters=!filters}){Text(if(filters)"Hide filters" else "Filters")}}
                if(filters) {
                    item{CatalogChoice("Goal",goal,s.manifest.entries.map{it.goal}.distinct().sorted()){goal=it};CatalogChoice("Experience",level,s.manifest.entries.map{it.level}.distinct().sorted()){level=it}}
                    item{CatalogChoice("Days per week",days,s.manifest.entries.map{it.days.toString()}.distinct().sorted()){days=it};CatalogChoice("Maximum minutes",minutes,listOf("35","45","60")){minutes=it}}
                    item{Text("Available equipment · select everything you have. No selection means any equipment.");s.manifest.entries.flatMap{it.equipment}.distinct().sorted().forEach{item->HandoffCheck(item,item in filter.equipment,true){checked->gear=(if(checked)filter.equipment+item else filter.equipment-item).sorted().joinToString("|")}}}
                    item{TextButton(onClick={goal="";level="";days="";minutes="";gear="";query=""}){Text("Clear filters")}}
                }
                val visible=s.manifest.entries.filter(filter::matches)
                item{Text("${visible.size} ${if(visible.size==1)"program" else "programs"}",style=MaterialTheme.typography.titleMedium)}
                if(visible.isEmpty())item{Text("No programs match. Clear or broaden the filters.")}
                visible.forEach{e->item(key=e.id){OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(e.title,style=MaterialTheme.typography.titleLarge);Text("${e.days} days/week · about ${e.minutes} min · ${e.level}");Text(e.description)
                    Text("${e.goal} · ${e.equipment.joinToString()}",style=MaterialTheme.typography.bodySmall)
                    Text(if(e.id in s.available)"Available offline" else "Download to preview / cache",style=MaterialTheme.typography.labelLarge)
                    if(installed.any{it.program.definitionId==e.id})Text("A local copy is installed; your edits are kept.")
                    Button(onClick={model.openCatalogEntry(e)},enabled=!loading,modifier=Modifier.testTag("catalog-open:${e.id}")){Text("Preview")}
                }}}}
                item{Text("Definitions: CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/) · FitNotes contributors. Original text and generic routines; no third-party articles or images. Versioned JSON: $CATALOG_URL",style=MaterialTheme.typography.bodySmall)}
            }
        } else {
            val e=entry!!
            item{Text("${e.days} ordered days/week · about ${e.minutes} min/day · version ${e.version}",style=MaterialTheme.typography.titleMedium);Text(e.description);Text("Equipment: ${e.equipment.joinToString()}")}
            if(installed.any{it.program.definitionId==e.id})item{Text("A local copy is installed. Installation creates a separate editable copy; it never replaces your existing version or history.")}
            if(program==null && !loading)item{Button(onClick={model.openCatalogEntry(e)}){Text("Retry download / preview")}}
            program?.let{p->val root=p.document
                item{Text("${p.weeks} weeks · ${root.getString("level")}");Text(root.optString("schedule"));Text(root.optString("notes"));Text(root.optString("progression"))}
                val ordered=root.getJSONArray("days")
                for(i in 0 until ordered.length()){val d=ordered.getJSONObject(i)
                    item(key="day:$i"){OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("${i+1}. ${d.getString("name")}",style=MaterialTheme.typography.titleLarge);Text(d.optString("notes"),style=MaterialTheme.typography.bodySmall)
                        val moves=d.getJSONArray("exercises")
                        for(j in 0 until moves.length()){val m=moves.getJSONObject(j);Text(m.getString("name"),style=MaterialTheme.typography.titleMedium);val targets=m.getJSONArray("sets")
                            for(k in 0 until targets.length()){val t=targets.getJSONObject(k);Text("Set ${k+1}: ${t.getInt("repsMin")}–${t.getInt("repsMax")} reps · ${t.getInt("restSeconds")}s rest · RIR ${t.get("rir")}")}
                            Text(m.optString("notes"),style=MaterialTheme.typography.bodySmall)
                            m.optJSONArray("substitutions")?.let{a->Text("Optional alternative (resolve explicitly): "+(0 until a.length()).joinToString{a.getString(it)},style=MaterialTheme.typography.bodySmall)}
                        }
                    }}}
                }
                item{Text("CC BY 4.0 · "+root.getJSONObject("license").getString("attribution"));Text("Review installation to resolve exercises and edit targets before saving. Existing installed versions are never replaced.")}
            }
        }
    }
}
@Composable private fun CatalogChoice(label:String,current:String,values:List<String>,change:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    Box{OutlinedButton(onClick={open=true},shape=MaterialTheme.shapes.small){Text("$label: ${current.ifBlank{"Any"}} ▾")};DropdownMenu(open,{open=false}){(listOf("")+values).forEach{value->DropdownMenuItem(text={Text(value.ifBlank{"Any"})},onClick={change(value);open=false})}}}
}
