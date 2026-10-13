package com.example.fitnotes

import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

// Run the two methods separately, killing only the isolated preview between them.
class RestProcessInteractionTest {
    private fun context()=InstrumentationRegistry.getInstrumentation().targetContext.also{assumeTrue("Synthetic timer requires isolated preview",it.packageName=="com.example.fitnotes.preview.activities")}
    @Test fun seedBackgroundTimer()=runBlocking {
        val context=context();val db=FitDatabase.open(context)
        try {
            val repo=WorkoutRepository(db);repo.restore(Backup(exercises=emptyList(),workouts=emptyList(),entries=emptyList(),sets=emptyList()))
            val workout=repo.start();db.trainingDao().putPreferences(TrainingPreferences(restAlerts=true))
            RestTimerController(context,db).start(workout,20)
            assertTrue(db.trainingDao().timer()!!.running)
        } finally { db.close() }
    }
    @Test fun verifyExpiredTimerAfterProcessDeath()=runBlocking {
        val context=context();val db=FitDatabase.open(context)
        try {
            val timer=db.trainingDao().timer()!!;assertTrue(timer.alerted);assertFalse(timer.running);assertEquals(0,timer.remainingMillis)
            assertNotNull(db.dao().active());assertTrue(db.dao().sets().isEmpty())
            RestTimerController(context,db).action("stale-token","skip");assertEquals(timer,db.trainingDao().timer())
        } finally { db.close() }
    }
}
