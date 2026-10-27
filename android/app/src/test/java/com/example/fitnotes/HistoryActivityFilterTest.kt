package com.example.fitnotes

import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoryActivityFilterTest {
    private fun fixture(): AppData {
        fun workout(id:Long,type:String,finished:Boolean=true) = WorkoutDetail(
            Workout(id,0,title=type,sessionDate="2026-10-12",finished=finished,activityType=type,durationSeconds=1800.0),emptyList())
        fun set(id:String,parent:String,order:Int,name:String,kg:Double?=null,reps:Int?=null) =
            ImportedSet(id,parent,"source-$id",order,name,"",kg,reps,
                if(kg==null)5.0 else null,if(kg==null)"km" else null,if(kg==null)1800.0 else null,"","{}")
        return AppData(workouts=listOf(workout(1,"running"),workout(2,"climbing"),workout(3,"strength"),workout(4,"running",false)),
            imported=ImportedData(workouts=listOf(
                ImportedWorkoutDetail(ImportedWorkout("runs","source","2026-10-11"),listOf(set("a","runs",0,"Running (Outdoor)"),set("b","runs",1,"Running (Outdoor)"))),
                ImportedWorkoutDetail(ImportedWorkout("mixed","source","2026-10-10"),listOf(set("c","mixed",0,"Squat",80.0,8),set("d","mixed",1,"Running (Treadmill)")))
            )))
    }
    @Test fun allActivitiesShareChronologyAndPureImportedRunsAreNotGymWorkouts() {
        val data=fixture();val rows=historyRows(notebookSessions(data),runningRecords(data))
        assertEquals(6,rows.size)
        assertFalse(rows.any { it.key=="local:4" })
        assertEquals(listOf("2026-10-12","2026-10-12","2026-10-12","2026-10-11","2026-10-11","2026-10-10"),rows.map { it.date })
        assertEquals(setOf("local:3","import:mixed"),filterHistoryRows(rows,"strength").map { it.key }.toSet())
        assertEquals(listOf("local:2"),filterHistoryRows(rows,"climbing").map { it.key })
        val imported=rows.single { it.key=="fitnotes:a" }
        assertEquals(ActivityType.RUNNING,imported.activity)
        assertEquals("5.00 km · 6:00 /km · 30:00",imported.summary)
        assertEquals("Average pace",imported.subtitle)
        assertNull(imported.start)
    }
    @Test fun distinctDateOnlyRunsInOneImportKeepTheirIdentitiesAndDetailsTargets() {
        val data=fixture();val rows=historyRows(notebookSessions(data),runningRecords(data))
        val sameDay=filterHistoryRows(rows,"running").filter { it.date=="2026-10-11" }
        assertEquals(listOf("fitnotes:a","fitnotes:b"),sameDay.map { it.runKey })
        assertTrue(sameDay.all { it.sessionKey=="import:runs" && it.start==null })
    }
    @Test fun mixedWorkoutAppearsOnceAndBelongsToBothFiltersWithoutChangingSets() {
        val data=fixture();val before=data.imported.workouts.flatMap { it.sets }
        val rows=historyRows(notebookSessions(data),runningRecords(data))
        assertEquals(1,rows.count { it.sessionKey=="import:mixed" })
        val mixed=rows.single { it.key=="import:mixed" }
        assertEquals(setOf(ActivityType.STRENGTH,ActivityType.RUNNING),mixed.activities)
        assertEquals("1 exercise · 1 set\nRunning · 5.00 km · 6:00 /km · 30:00",mixed.summary)
        assertNull(mixed.runKey)
        assertTrue(filterHistoryRows(rows,"running").contains(mixed))
        assertTrue(filterHistoryRows(rows,"strength").contains(mixed))
        assertEquals(before,data.imported.workouts.flatMap { it.sets })
    }
    @Test fun ignoredAndMergedRunRowsDoNotReappearAsStrengthTraining() {
        val data=fixture().copy(runLinks=listOf(
            RunLink("decision-a","local:1",retiredKey="fitnotes:a",reviewed=true),
            RunLink("fitnotes:b","fitnotes:b",ignored=true,reviewed=true)))
        val rows=historyRows(notebookSessions(data),runningRecords(data))
        assertFalse(rows.any { it.sessionKey=="import:runs" })
        assertEquals(1,rows.count { it.key=="local:1" })
        assertEquals(setOf("local:3","import:mixed"),filterHistoryRows(rows,"strength").map { it.key }.toSet())
    }
}
