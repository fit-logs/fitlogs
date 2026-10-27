package com.example.fitnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun HistoryRepairDialog(data:AppData,model:FitNotesViewModel,busy:Boolean,onClose:()->Unit) {
    val candidates=remember(data.imported){historyMergeCandidates(data.imported.workouts,data.imported.imports)}
    val preview by model.historyMergeReview.collectAsState()
    val error by model.historyMergeError.collectAsState()
    if(preview==null)AlertDialog(onDismissRequest=onClose,title={Text("Review repeated imports")},text={LazyColumn(Modifier.heightIn(max=400.dp)){
        if(candidates.isEmpty())item{Text("No repeated source records found. Sessions with the same name or date are kept separate.")}
        items(candidates,key={it.first.workout.id+it.second.workout.id}){candidate->TextButton(onClick={model.historyMergeReview.value=candidate},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(prettyDay(candidate.first.workout.date)+" · ${candidate.repeated} repeated source records")}}
    }},confirmButton={TextButton(onClick=onClose){Text("Close")}})
    else AlertDialog(onDismissRequest={if(!busy)model.historyMergeReview.value=null},title={Text("Merge these imports?")},text={Column {
        Text("${preview!!.repeated} identical source records appear in both imports. Keep one copy of those records and ${preview!!.retained} sets in the combined workout. Distinct results, notes and health data are retained.")
        Text("A recovery backup will be saved before the merge.",Modifier.padding(top=8.dp))
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick=model::mergeHistory,enabled=!busy){Text("Back up and merge")}},dismissButton={TextButton(onClick={model.historyMergeReview.value=null},enabled=!busy){Text("Keep both")}})
}
