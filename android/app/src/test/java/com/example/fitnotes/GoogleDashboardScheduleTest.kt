package com.example.fitnotes

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.fitnotes.dashboard.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GoogleDashboardScheduleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {
        GoogleDashboardStore(context).disconnect()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(androidx.work.impl.utils.SynchronousExecutor()).build())
    }
    @After fun close() { GoogleDashboardStore(context).disconnect(); WorkManagerTestInitHelper.closeWorkDatabase() }
    @Test fun automaticSyncRequiresOptInAndConnectionAndCoalescesPendingEdits() {
        val store = GoogleDashboardStore(context); val wm = WorkManager.getInstance(context)
        assertFalse(store.options().includeNotes); assertFalse(store.options().includeBody); assertFalse(store.automatic())
        GoogleDashboardWork.request(context); assertTrue(wm.getWorkInfosForUniqueWork(GoogleDashboardWork.NAME).get().isEmpty())
        store.automatic(true); GoogleDashboardWork.request(context); assertTrue(wm.getWorkInfosForUniqueWork(GoogleDashboardWork.NAME).get().isEmpty())
        store.write(store.read().copy(accountId = "synthetic-owner", email = "synthetic@example.invalid", fileId = "sheetABCDEFGHIJK", folderId = "folderABCDEFGHIJ"))
        repeat(3) { GoogleDashboardWork.request(context) }
        val work = wm.getWorkInfosForUniqueWork(GoogleDashboardWork.NAME).get()
        assertEquals(1, work.count { !it.state.isFinished })
        assertEquals(NetworkType.CONNECTED, work.single { !it.state.isFinished }.constraints.requiredNetworkType)
        GoogleDashboardWork.cancel(context)
        assertTrue(wm.getWorkInfosForUniqueWork(GoogleDashboardWork.NAME).get().all { it.state.isFinished })
    }
    @Test fun disabledWorkerDoesNotReadDatabaseOrAuthorizeGoogle() = runBlocking {
        val worker = TestListenableWorkerBuilder<GoogleDashboardWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("", GoogleDashboardStore(context).status())
        assertEquals(0L, GoogleDashboardStore(context).read().lastSuccessAt)
    }
    @Test fun quotaAuthAndStorageErrorsRemainSpecificWithoutEchoingPrivateServerText() {
        fun body(reason: String) = """{"error":{"message":"PRIVATE WORKOUT NAME","errors":[{"reason":"$reason"}]}}"""
        assertEquals(DashboardFailure.RETRY, dashboardHttpFailure(403, body("userRateLimitExceeded")))
        assertEquals(DashboardFailure.RETRY, dashboardHttpFailure(429))
        assertEquals(DashboardFailure.AUTH, dashboardHttpFailure(401))
        assertEquals(DashboardFailure.STORAGE, dashboardHttpFailure(403, body("storageQuotaExceeded")))
        assertEquals(DashboardFailure.SETUP, dashboardHttpFailure(403, body("accessNotConfigured")))
        assertFalse(DashboardSyncException(dashboardHttpFailure(403, body("insufficientFilePermissions"))).message!!.contains("PRIVATE"))
    }
}
