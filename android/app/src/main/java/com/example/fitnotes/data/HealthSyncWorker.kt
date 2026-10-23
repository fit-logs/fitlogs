package com.example.fitnotes.data

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object HealthSyncSchedule {
    const val NAME = "health-activity-import"
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    suspend fun reconcile(context: Context, settings: HealthSyncSettings, access: HealthAccess?) {
        if (!settings.enabled || !settings.background || access?.exercise != true || !access.background || !access.backgroundSupported || Build.VERSION.SDK_INT < 28) {
            cancel(context); return
        }
        val work = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
class HealthSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        runSync()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        val (store, _) = HealthSyncRuntime.connection(applicationContext)
        store.update { it.copy(working = false, errors = it.errors + 1, message = "Background read failed. Android will retry; open FitNotes to review Health Connect access.") }
        Result.retry()
    }
    private suspend fun runSync(): Result {
        val (store, lock) = HealthSyncRuntime.connection(applicationContext)
        val settings = store.state.value.settings
        if (!settings.enabled || !settings.background || Build.VERSION.SDK_INT < 28) return Result.success()
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) return Result.retry()
        val source = HealthConnectSyncSource(applicationContext)
        val access = source.access()
        HealthSyncSchedule.reconcile(applicationContext, settings, access)
        if (!access.exercise || !access.background || !access.backgroundSupported) {
            store.update { it.copy(message = "Background permission unavailable. Open FitNotes or use Sync now; saved activities are retained.") }
            return Result.success()
        }
        val db = FitDatabase.open(applicationContext)
        return try {
            if (HealthSyncCoordinator(db, store, source, lock).sync(HealthSyncReason.BACKGROUND)) Result.success() else Result.retry()
        } finally { db.close() }
    }
}
