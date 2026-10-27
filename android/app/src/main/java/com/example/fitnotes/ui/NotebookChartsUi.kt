package com.example.fitnotes.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.fitnotes.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChartValue(val x: Double, val value: Double, val label: String, val segment: Int = 0)

@Composable
fun NotebookLineChart(points: List<ChartValue>, unit: String, startLabel: String, endLabel: String, height: Dp, tag: String, onPoint: ((Int)->Unit)? = null,
    description: String? = null) {
    if (points.isEmpty()) return
    val density = LocalDensity.current
    val bounds = remember(points) { plotBounds(points.map { it.value }) }
    val primary = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    var selected by remember(points) { mutableStateOf<ChartValue?>(null) }
    var positions by remember(points) { mutableStateOf<List<Offset>>(emptyList()) }
    Column(Modifier.fillMaxWidth()) {
        Text(unit, style = MaterialTheme.typography.labelMedium, color = textColor)
        Canvas(Modifier.fillMaxWidth().height(height).testTag(tag).semantics { if(description!=null) contentDescription = description }.pointerInput(points) {
            detectTapGestures { touch ->
                positions.indices.minByOrNull { (positions[it] - touch).getDistance() }?.let { selected = points[it]; onPoint?.invoke(it) }
            }
        }) {
            val labelSize = with(density) { 12.dp.toPx() * density.fontScale }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor.toArgb(); textSize = labelSize }
            val ticks = (0..kotlin.math.round((bounds.high-bounds.low)/bounds.step).toInt()).map { bounds.low+bounds.step*it }
            val labels = ticks.map { plotAxisText(it, bounds.step) }
            val left = maxOf(48.dp.toPx(), labels.maxOf { paint.measureText(it) } + 12.dp.toPx())
            val right = size.width - 10.dp.toPx()
            val top = 10.dp.toPx(); val bottom = size.height - 34.dp.toPx() * density.fontScale
            val minX = points.minOf { it.x }; val span = points.maxOf { it.x } - minX
            val mapped = points.map { p -> Offset(if (span == 0.0) (left+right)/2 else (left + (p.x-minX)/span*(right-left)).toFloat(),
                (bottom - (p.value-bounds.low)/(bounds.high-bounds.low)*(bottom-top)).toFloat()) }
            positions = mapped
            drawLine(axis, Offset(left,top), Offset(left,bottom), 1.dp.toPx())
            drawLine(axis, Offset(left,bottom), Offset(right,bottom), 1.dp.toPx())
            ticks.forEachIndexed { index, value ->
                val y = (bottom - (value-bounds.low)/(bounds.high-bounds.low)*(bottom-top)).toFloat()
                drawLine(axis.copy(alpha=.2f),Offset(left,y),Offset(right,y),1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(labels[index], 0f, y+labelSize/3, paint)
            }
            points.indices.drop(1).forEach { index -> if (points[index].segment == points[index-1].segment && points[index].x != points[index-1].x) drawLine(primary,mapped[index-1],mapped[index],2.dp.toPx()) }
            mapped.forEachIndexed { index, point -> drawCircle(primary, if (points[index] == selected) 6.dp.toPx() else 4.dp.toPx(),point) }
            val y = size.height - 4.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(startLabel,left,y,paint)
            if (endLabel != startLabel && paint.measureText(startLabel)+paint.measureText(endLabel) < right-left-12.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(endLabel,right-paint.measureText(endLabel),y,paint)
        }
        if(onPoint==null) selected?.let { Text(it.label + " · " + estimateText(it.value) + " " + unit, Modifier.padding(vertical=4.dp), style=MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun HeartRateChart(details: List<HealthDetail>) {
    val samples = remember(details) { details.flatMap { health -> associatedHeartSamples(health.session, health.samples) }.distinctBy { Triple(it.originPackage,it.recordId,it.time) } }
    if (samples.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val clock = remember(details.firstOrNull()?.session?.startOffsetSeconds) { SimpleDateFormat("HH:mm:ss",Locale.getDefault()).apply {
        details.firstOrNull()?.session?.startOffsetSeconds?.let{timeZone=java.util.SimpleTimeZone(it*1000,"Session")}
    } }
    val points = remember(samples) { heartRateSegments(samples).flatMapIndexed { segment, values -> values.map { ChartValue(it.time.toDouble(),it.bpm.toDouble(),clock.format(Date(it.time)),segment) } } }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick={expanded=true}, contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp)) { Text("Heart rate",style=MaterialTheme.typography.titleMedium) }
        NotebookLineChart(points,"BPM",clock.format(Date(samples.minOf{it.time})),clock.format(Date(samples.maxOf{it.time})),160.dp,"heart-rate-chart")
    }
    if(expanded) androidx.compose.ui.window.Dialog(onDismissRequest={expanded=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp),shape=MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) { Text("Heart rate",style=MaterialTheme.typography.titleLarge);NotebookLineChart(points,"BPM",clock.format(Date(samples.minOf{it.time})),clock.format(Date(samples.maxOf{it.time})),280.dp,"expanded-heart-rate");TextButton(onClick={expanded=false}){Text("Close")} }
        }
    }
}
