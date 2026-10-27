package com.example.fitnotes

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.ui.*
import com.example.fitnotes.ui.theme.FitNotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

class NotebookChartInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun emptySingleConstantZeroAndOrdinarySeriesRenderAndAllowPointInspection() {
        val i=InstrumentationRegistry.getInstrumentation()
        assumeTrue(i.targetContext.packageName=="com.example.fitnotes.preview.photos")
        listOf("empty" to emptyList(),"single" to listOf(80.0),"constant" to listOf(80.0,80.0,80.0),"zero" to listOf(0.0,0.0),"ordinary" to listOf(70.0,80.0,75.0)).forEach{(name,values)->
            ui.activityRule.scenario.onActivity{activity->activity.setContent{FitNotesTheme{Surface(Modifier.fillMaxSize().systemBarsPadding()){
                Column(Modifier.padding(16.dp)) { Text(name,style=MaterialTheme.typography.titleLarge)
                    if(values.isEmpty())Text("No records in this period") else NotebookLineChart(values.mapIndexed{index,value->ChartValue(index.toDouble(),value,"Recorded session ${index+1}")},"kg","1 Oct","3 Oct",220.dp,"chart-case")
                }
            }}}}
            if(values.isEmpty())ui.onNodeWithText("No records in this period").assertIsDisplayed()
            else { ui.onNodeWithTag("chart-case").assertHeightIsAtLeast(220.dp).performTouchInput{click(center)};ui.onNode(hasText("Recorded session",substring=true)).assertIsDisplayed() }
            i.waitForIdleSync();val bitmap=i.uiAutomation.takeScreenshot()!!
            val phase=InstrumentationRegistry.getArguments().getString("phase") ?: "after"
            File(i.targetContext.filesDir,"$phase-chart-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
