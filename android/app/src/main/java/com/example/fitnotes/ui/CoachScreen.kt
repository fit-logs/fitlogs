package com.example.fitnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.fitnotes.AppData
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*

@Composable
fun CoachScreen(data: AppData, model: FitNotesViewModel, busy: Boolean, import: () -> Unit, clipboardReader: (() -> String?)? = null) {
    val prefs by model.handoffPreferences.collectAsState()
    val error by model.coachError.collectAsState()
    val advice by model.coachAdvice.collectAsState()
    val shared by model.coachPrompt.collectAsState()
    var initialized by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(CoachQuestion.NEXT.name) }
    var focus by rememberSaveable { mutableStateOf("Both") }
    var range by rememberSaveable { mutableStateOf("Last 30 days") }
    var first by rememberSaveable { mutableStateOf(moveDay(todayDate(),-29)) }
    var last by rememberSaveable { mutableStateOf(todayDate()) }
    var activityPreference by rememberSaveable { mutableStateOf("") }
    var goals by rememberSaveable { mutableStateOf("") }
    var equipment by rememberSaveable { mutableStateOf("") }
    var constraints by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var preferences by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val raw by model.programImportText.collectAsState()
    var showShared by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    val keyboard=LocalFocusManager.current
    val ime=LocalSoftwareKeyboardController.current
    val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    LaunchedEffect(prefs.loading) {
        if(!prefs.loading && !initialized) {
            activityPreference=prefs.value.activityPreference
            goals=prefs.value.goals;equipment=prefs.value.equipment;constraints=prefs.value.constraints
            minutes=prefs.value.availableMinutes?.toString().orEmpty()
            focus=when(prefs.value.activityPreference) { "strength"->"Gym";"running"->"Running";else->"Both" }
            initialized=true
        }
    }
    fun prepare(copy: Boolean) {
        copied=false;keyboard.clearFocus();ime?.hide()
        val request=CoachRequest(CoachQuestion.valueOf(kind),focus,first,last,
            prefs.value.copy(goals=goals,equipment=equipment,constraints=constraints,
                availableMinutes=if(minutes.isBlank())null else minutes.toIntOrNull() ?: 0,
                activityPreference=activityPreference),question)
        model.prepareCoach(request) { text ->
            if(copy) {
                if(text.toByteArray().size<=PreparedHandoff.MAX_INLINE_BYTES) { clipboard.setPrimaryClip(ClipData.newPlainText("Coach prompt",text));copied=true }
                else model.coachError.value="This complete prompt exceeds the clipboard limit. Choose a shorter range; nothing was truncated."
            } else showShared=true
        }
    }
    Column(Modifier.fillMaxSize()) {
        NotebookBar("Coach") { SettingsButton() }
        LazyColumn(Modifier.fillMaxSize().imePadding().testTag("coach-screen"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("Ask your coach",style=MaterialTheme.typography.titleLarge); Text("Copy a prompt, ask your preferred AI app, then paste its response here.") }
            error?.let { item { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("coach-error")) } }
            item { ChoiceField("Request",CoachQuestion.entries.map { it.label },CoachQuestion.valueOf(kind).label) { label -> kind=CoachQuestion.entries.first { it.label==label }.name;copied=false } }
            if(kind==CoachQuestion.CUSTOM.name)item { OutlinedTextField(question,{question=it},label={Text("Your question")},modifier=Modifier.fillMaxWidth(),maxLines=5) }
            item { ChoiceField("Training focus",listOf("Gym","Running","Both"),focus) {focus=it;activityPreference=when(it){"Gym"->"strength";"Running"->"running";else->""};copied=false} }
            item { ChoiceField("History context",listOf("Last 7 days","Last 30 days","Last 90 days","Custom range"),range) {
                range=it;copied=false
                if(it!="Custom range") {first=moveDay(todayDate(),-when(it){"Last 7 days"->6;"Last 90 days"->89;else->29});last=todayDate()}
            };Text("$first through $last · completed training only",style=MaterialTheme.typography.bodySmall) }
            if(range=="Custom range")item {
                OutlinedTextField(first,{first=it},label={Text("From (YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                OutlinedTextField(last,{last=it},label={Text("Through (YYYY-MM-DD)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            }
            item { TextButton(onClick={preferences=!preferences}) {Text(if(preferences)"Hide preferences" else "Goals, equipment and constraints")} }
            if(preferences)item {
                ChoiceField("Activity preference",listOf("No preference")+ActivityType.entries.map{it.label},if(activityPreference.isBlank())"No preference" else ActivityType.from(activityPreference).label) {label->activityPreference=ActivityType.entries.firstOrNull{it.label==label}?.key.orEmpty()}
                OutlinedTextField(goals,{goals=it},label={Text("Goals")},modifier=Modifier.fillMaxWidth(),maxLines=4)
                OutlinedTextField(equipment,{equipment=it},label={Text("Equipment")},modifier=Modifier.fillMaxWidth(),maxLines=4)
                OutlinedTextField(constraints,{constraints=it},label={Text("Constraints")},modifier=Modifier.fillMaxWidth(),maxLines=4)
                OutlinedTextField(minutes,{minutes=it},label={Text("Available minutes (optional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),singleLine=true)
            }
            item {
                Button(onClick={prepare(true)},enabled=!busy && initialized && !data.imported.loading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("copy-coach-prompt")) {Text("Copy coach prompt")}
                if(copied)Text("Prompt copied",color=MaterialTheme.colorScheme.primary)
                TextButton(onClick={prepare(false)},enabled=!busy && initialized && !data.imported.loading) {Text("View shared data")}
                Text("Compact completed training summary. Notes, photos, body measurements and raw heart-rate samples are omitted.",style=MaterialTheme.typography.bodySmall)
            }
            item { HorizontalDivider();Text("Import AI response",style=MaterialTheme.typography.titleLarge) }
            item {
                Button(onClick={
                    keyboard.clearFocus();ime?.hide()
                    // Deliberate user gesture is the only clipboard read on Coach.
                    val text=if(clipboardReader!=null)clipboardReader() else clipboard.primaryClip?.takeIf {it.itemCount>0}?.getItemAt(0)?.text?.toString()
                    if(text.isNullOrBlank()) {manual=true;model.coachError.value="Clipboard has no text. Enter the response below."}
                    else model.previewCoach(text,import)
                },enabled=!busy && !data.imported.loading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("paste-ai-response")) {Text("Paste AI response")}
                TextButton(onClick={manual=!manual}) {Text(if(manual)"Hide text entry" else "Enter response manually")}
            }
            if(manual)item {
                OutlinedTextField(raw,{if(it.toByteArray().size<=PortableProgramParser.MAX_INPUT)model.programImportText.value=it else model.coachError.value="Response exceeds 256 KB."},label={Text("AI response")},minLines=4,maxLines=10,modifier=Modifier.fillMaxWidth().testTag("coach-response-text"))
                OutlinedButton(onClick={keyboard.clearFocus();ime?.hide();model.previewCoach(raw,import)},enabled=!busy && raw.isNotBlank()) {Text("Preview response")}
            }
            advice?.let { text -> item { OutlinedCard(Modifier.fillMaxWidth().testTag("coach-advice")) {Column(Modifier.padding(16.dp)) {Text("Coach advice",style=MaterialTheme.typography.titleMedium);Text(text);TextButton(onClick={model.coachAdvice.value=null}) {Text("Dismiss advice")}}} } }
        }
    }
    if(showShared && shared!=null)HandoffScreen("Shared data",busy,{showShared=false},"coach-shared-data",footer={TextButton(onClick={showShared=false}){Text("Close")}}) {item{Text(shared!!)}}
}
