package com.example.fitnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressExercisePicker(exercises: List<ProgressExercise>, recentKeys: List<String>, selectedKey: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(exercises, query) { searchProgressExercises(exercises, query) }
    val recent = if (query.isBlank()) recentKeys.distinct().take(5).mapNotNull { key -> matches.firstOrNull { it.key == key } } else emptyList()
    val all = matches.filter { candidate -> recent.none { it.key == candidate.key } }
    ModalBottomSheet(onDismissRequest=onDismiss, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true), dragHandle=null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.96f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Choose exercise",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)) { Text("Close") }
            }
            OutlinedTextField(query,{query=it},label={Text("Search exercises")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("progress-exercise-search"))
            LazyColumn(Modifier.weight(1f).testTag("progress-exercise-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=24.dp)) {
                if(matches.isEmpty()) item { Text("No matching exercises.",Modifier.padding(vertical=16.dp)) }
                if(recent.isNotEmpty()) {
                    item { Text("Recent",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=8.dp)) }
                    items(recent,key={it.key}) { exercise -> ProgressExerciseRow(exercise,selectedKey,onSelect) }
                }
                if(all.isNotEmpty()) {
                    if(query.isBlank()) item { Text("All exercises",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=8.dp)) }
                    items(all,key={it.key}) { exercise -> ProgressExerciseRow(exercise,selectedKey,onSelect) }
                }
            }
        }
    }
}
@Composable
private fun ProgressExerciseRow(exercise: ProgressExercise, selected: String?, onSelect: (String)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable{onSelect(exercise.key)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(exercise.name,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurface)
        if(exercise.key==selected) Text("✓",Modifier.padding(start=12.dp),color=MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun ProgressSymbol() {
    val color=LocalContentColor.current
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        drawLine(color,androidx.compose.ui.geometry.Offset(size.width*.12f,size.height*.1f),androidx.compose.ui.geometry.Offset(size.width*.12f,size.height*.88f),2.dp.toPx())
        drawLine(color,androidx.compose.ui.geometry.Offset(size.width*.12f,size.height*.88f),androidx.compose.ui.geometry.Offset(size.width*.92f,size.height*.88f),2.dp.toPx())
        val points=listOf(.2f to .7f,.43f to .48f,.64f to .58f,.89f to .2f).map{androidx.compose.ui.geometry.Offset(size.width*it.first,size.height*it.second)}
        points.zipWithNext().forEach{(a,b)->drawLine(color,a,b,2.dp.toPx())}
    }
}
