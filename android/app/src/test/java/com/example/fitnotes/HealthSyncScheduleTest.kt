package com.example.fitnotes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.fitnotes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HealthSyncScheduleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(androidx.work.impl.utils.SynchronousExecutor()).build()) }
    @After fun close() { WorkManagerTestInitHelper.closeWorkDatabase() }
    @Test fun backgroundRequiresOptInAndGrantsKeepsOneJobAndCancelsWhenDisabled() = runBlocking {
        val wm = WorkManager.getInstance(context)
        val settings = HealthSyncSettings(enabled = true, background = true)
        val access = HealthAccess(true, true, true, background = true, backgroundSupported = true)
        HealthSyncSchedule.reconcile(context, settings, access)
        val id = wm.getWorkInfosForUniqueWork(HealthSyncSchedule.NAME).get().single().id
        HealthSyncSchedule.reconcile(context, settings, access)
        assertEquals(id, wm.getWorkInfosForUniqueWork(HealthSyncSchedule.NAME).get().single().id)
        HealthSyncSchedule.reconcile(context, settings, access.copy(background = false))
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(id).get()!!.state)
        HealthSyncSchedule.reconcile(context, settings.copy(enabled = false), access)
        assertTrue(wm.getWorkInfosForUniqueWork(HealthSyncSchedule.NAME).get().none { !it.state.isFinished })
        HealthSyncSchedule.reconcile(context, settings, access.copy(backgroundSupported = false))
        assertTrue(wm.getWorkInfosForUniqueWork(HealthSyncSchedule.NAME).get().none { !it.state.isFinished })
    }
    @Test fun disabledWorkerFinishesWithoutReadingHealthConnect() = runBlocking {
        HealthSyncRuntime.connection(context).first.resetAfterRestore()
        val worker = androidx.work.testing.TestListenableWorkerBuilder<HealthSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertNull(HealthSyncRuntime.connection(context).first.state.value.lastAttempt)
    }
}
