package com.example.fitnotes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.fitnotes.FitNotesViewModel
import com.example.fitnotes.data.*
import kotlin.math.roundToInt

@Composable
fun PhotosScreen(model: FitNotesViewModel, busy: Boolean) {
    val photos by model.photos.collectAsState()
    val drafts by model.photoDrafts.collectAsState()
    var angle by rememberSaveable { mutableStateOf("Front") }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    val filtered = photos.filter { it.angle == angle }
    val pair = selected.mapNotNull { id -> photos.firstOrNull { it.id == id } }.sortedWith(compareBy<ProgressPhoto> { it.date }.thenBy { it.id })
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) model.preparePhotos(uris)
    }
    fun add() { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(photos) { selected = selected.filter { id -> photos.any { it.id == id } } }
    Column(Modifier.fillMaxSize()) {
        AngleChoices(angle, { angle = it; selected = emptyList() }, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::add, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Add photos") }
            OutlinedButton(onClick = { comparing = true }, enabled = pair.size == 2 && pair.map { it.angle }.distinct().size == 1, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Compare (${pair.size}/2)") }
        }
        if (filtered.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No ${angle.lowercase()} photos yet", style = MaterialTheme.typography.titleLarge)
            Text("Keep dated photos to compare your progress. Photos stay on this device and are independent of measurements.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = ::add, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add photos") }
        } else {
            Text("Newest first · select two of the same angle", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            if (selected.isNotEmpty()) TextButton(onClick = { selected = emptyList() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear selection") }
            LazyVerticalGrid(GridCells.Adaptive(145.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.id }) { photo ->
                    Surface(onClick = { viewing = photo.id }, shape = MaterialTheme.shapes.small, tonalElevation = 1.dp) {
                        Column {
                            PhotoImage(model.photoRepository, photo, Modifier.fillMaxWidth().aspectRatio(0.78f), thumbnail = true)
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(prettyDay(photo.date, "d MMM yyyy"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Checkbox(checked = photo.id in selected, enabled = photo.id in selected || selected.size < 2,
                                    onCheckedChange = { checked -> selected = if (checked) selected + photo.id else selected - photo.id },
                                    modifier = Modifier.semantics { contentDescription = "Select ${photo.angle} photo ${photo.date}" })
                            }
                        }
                    }
                }
            }
        }
    }
    drafts.firstOrNull()?.let { draft ->
        PhotoDetails(model, draft.photo, busy, draft = true, title = "Add photo (${drafts.size} remaining)",
            dateHint = if (draft.captureDateFound) "Capture date from image metadata. Check it before saving." else "No reliable capture date found. Defaults to today; tap the date to change it.",
            onCancel = model::cancelPhotos, onSave = { date, chosen, note -> model.savePhotoDraft(date, chosen, note) })
    }
    photos.firstOrNull { it.id == viewing }?.let { photo ->
        PhotoViewer(model, photo, busy) { viewing = null }
    }
    if (comparing && pair.size == 2) PhotoComparison(model.photoRepository, pair[0], pair[1]) { comparing = false }
}

@Composable
private fun AngleChoices(angle: String, onAngle: (String) -> Unit, modifier: Modifier = Modifier) {
    // Two rows stay readable with large text rather than squeezing four chips.
    Column(modifier) {
        PHOTO_ANGLES.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value -> FilterChip(selected = angle == value, onClick = { onAngle(value) }, label = { Text(value) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
        } }
    }
}

@Composable
fun PhotoImage(repository: ProgressPhotoRepository, photo: ProgressPhoto, modifier: Modifier = Modifier, thumbnail: Boolean = false, draft: Boolean = false) {
    val loaded by produceState<Pair<android.graphics.Bitmap?, Boolean>>(null to false, photo.imagePath, thumbnail, draft) {
        value = repository.load(photo, thumbnail, draft) to true
    }
    val bitmap = loaded.first
    val loading = !loaded.second
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer).clipToBounds(), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "${photo.angle} photo from ${photo.date}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(if (loading) "Loading photo…" else "Photo unavailable\nRe-import or delete the app copy.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhotoViewer(model: FitNotesViewModel, photo: ProgressPhoto, busy: Boolean, close: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    if (edit) PhotoDetails(model, photo, busy, onCancel = { edit = false }, onSave = { date, angle, note -> model.editPhoto(photo.id, date, angle, note) { edit = false } })
    else Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                NotebookBar("${photo.angle} · ${prettyDay(photo.date)}") { TextButton(onClick = close) { Text("Close") } }
                PhotoImage(model.photoRepository, photo, modifier = Modifier.fillMaxWidth().weight(1f))
                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (photo.note.isNotBlank()) Text(photo.note)
                    Text("Only the app copy is managed here.", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { edit = true }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit details") }
                    OutlinedButton(onClick = { delete = true }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Delete") }
                }
            }
        }
    }
    if (delete) NotebookConfirm("Delete app photo?", "This deletes only FitNotes’ copy. The original phone photo is untouched.", "Delete", busy, { delete = false }) {
        model.deletePhoto(photo.id) { delete = false; close() }
    }
}

@Composable
private fun PhotoDetails(model: FitNotesViewModel, photo: ProgressPhoto, busy: Boolean, draft: Boolean = false,
    title: String = "Edit photo details", dateHint: String = "Tap the date to change it.", onCancel: () -> Unit, onSave: (String, String, String) -> Unit) {
    var date by rememberSaveable(photo.id) { mutableStateOf(photo.date) }
    var angle by rememberSaveable(photo.id) { mutableStateOf(photo.angle) }
    var note by rememberSaveable(photo.id) { mutableStateOf(photo.note) }
    var picker by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PhotoImage(model.photoRepository, photo, Modifier.fillMaxWidth().height(180.dp), thumbnail = true, draft = draft) }
            item {
                TextButton(onClick = { picker = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Date taken" }) { Text(prettyDay(date, "EEE d MMM yyyy")) }
                Text(dateHint, style = MaterialTheme.typography.bodySmall)
            }
            item { Text("Angle", style = MaterialTheme.typography.labelLarge); AngleChoices(angle, { angle = it }) }
            item { OutlinedTextField(note, { note = it.take(10000) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !busy) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(date, angle, note) }, enabled = !busy && validHistoryDate(date)) { Text("Save photo") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text(if (draft) "Cancel import" else "Cancel") } })
    if (picker) DayDialog(date, { picker = false }) { date = it; picker = false }
}

@Composable
fun PhotoComparison(repository: ProgressPhotoRepository, earlier: ProgressPhoto, later: ProgressPhoto, close: () -> Unit) {
    var sideBySide by rememberSaveable { mutableStateOf(false) }
    var divider by rememberSaveable { mutableFloatStateOf(0.5f) }
    var adjusting by rememberSaveable { mutableStateOf("Neither") }
    var firstScale by rememberSaveable { mutableFloatStateOf(1f) }
    var secondScale by rememberSaveable { mutableFloatStateOf(1f) }
    var firstX by rememberSaveable { mutableFloatStateOf(0f) }
    var firstY by rememberSaveable { mutableFloatStateOf(0f) }
    var secondX by rememberSaveable { mutableFloatStateOf(0f) }
    var secondY by rememberSaveable { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                NotebookBar("Compare · ${earlier.angle}") { TextButton(onClick = close) { Text("Close") } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!sideBySide, { sideBySide = false }, label = { Text("Slider") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    FilterChip(sideBySide, { sideBySide = true }, label = { Text("Side by side") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${if (earlier.date == later.date) "Photo 1" else "Earlier"}\n${prettyDay(earlier.date, "d MMM yyyy")}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("${if (earlier.date == later.date) "Photo 2" else "Later"}\n${prettyDay(later.date, "d MMM yyyy")}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { width = it.width.coerceAtLeast(1); height = it.height.coerceAtLeast(1) }
                    .pointerInput(adjusting, sideBySide) {
                        if (!sideBySide && adjusting != "Neither") detectTransformGestures { _, pan, zoom, _ ->
                            if (adjusting == "Earlier") {
                                firstScale = (firstScale * zoom).coerceIn(1f, 4f)
                                firstX = (firstX + pan.x / width).coerceIn(-1f, 1f); firstY = (firstY + pan.y / height).coerceIn(-1f, 1f)
                            } else {
                                secondScale = (secondScale * zoom).coerceIn(1f, 4f)
                                secondX = (secondX + pan.x / width).coerceIn(-1f, 1f); secondY = (secondY + pan.y / height).coerceIn(-1f, 1f)
                            }
                        }
                    }) {
                    if (sideBySide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoImage(repository, earlier, modifier = Modifier.weight(1f).fillMaxHeight())
                        PhotoImage(repository, later, modifier = Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Box(Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * divider) { this@drawWithContent.drawContent() } }) {
                            PhotoImage(repository, earlier, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = firstScale; scaleY = firstScale; translationX = firstX * width; translationY = firstY * height })
                        }
                        Box(Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * divider) { this@drawWithContent.drawContent() } }) {
                            PhotoImage(repository, later, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = secondScale; scaleY = secondScale; translationX = secondX * width; translationY = secondY * height })
                        }
                        Box(Modifier.offset { IntOffset((width * divider).roundToInt() - with(density) { 24.dp.roundToPx() }, 0) }
                            .width(48.dp).fillMaxHeight().semantics { contentDescription = "Drag comparison divider" }.pointerInput(width) {
                                detectDragGestures(onDragStart = { adjusting = "Neither" }) { change, drag -> change.consume(); divider = (divider + drag.x / width).coerceIn(0f, 1f) }
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) { Text("↔", Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onPrimary) }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (!sideBySide) {
                        Text("Reveal earlier/later · ${(divider * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(divider, { divider = it }, modifier = Modifier.semantics { contentDescription = "Comparison divider" })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Neither", "Earlier", "Later").forEach { choice -> FilterChip(adjusting == choice, { adjusting = choice }, label = { Text(if (choice == "Neither") "Divider" else choice) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
                        }
                        Text("Select a photo, then pinch to zoom or drag to reposition.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { firstScale = 1f; secondScale = 1f; firstX = 0f; firstY = 0f; secondX = 0f; secondY = 0f; divider = 0.5f; adjusting = "Neither" }) { Text("Reset") }
                    } else Text("Complete photos · original aspect ratios", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    BackHandler { close() }
}
