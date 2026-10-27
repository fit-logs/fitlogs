package com.example.fitnotes.dashboard

import android.content.Context
import androidx.work.*
import com.example.fitnotes.data.FitDatabase
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object GoogleDashboardWork {
    const val NAME = "google-training-dashboard"
    fun request(context: Context) {
        val store = GoogleDashboardStore(context)
        if (!store.automatic() || store.read().fileId.isBlank()) return
        val work = OneTimeWorkRequestBuilder<GoogleDashboardWorker>().setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, work)
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
}

class GoogleDashboardWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = GoogleDashboardStore(applicationContext)
        val connection = store.read()
        if (!store.automatic() || connection.fileId.isBlank()) return Result.success()
        var token: String? = null
        return try {
            val database = FitDatabase.open(applicationContext)
            val snapshot = try {
                // Automatic exports must not compete with active set logging.
                if (database.dao().active() != null) return Result.retry()
                DashboardSnapshot.project(dashboardBackup(database), store.options())
            } finally { database.close() }
            val authorization = Identity.getAuthorizationClient(applicationContext).authorize(dashboardAuthorization(connection.email)).dashboardAwait()
            if (authorization.hasResolution()) { store.status("Reconnect Google to resume automatic sync."); return Result.failure() }
            token = dashboardAccessToken(authorization)
            if (!store.automatic() || store.read().key != connection.key) return Result.success()
            store.status("Syncing to your Google Drive…")
            val result = DashboardPublisher(GoogleDashboardTransport(), store).publish(snapshot, token, expectedConnectionKey = connection.key)
            store.status(if (result.unchanged) "Dashboard is up to date." else "Dashboard synced to your Google Drive.")
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: DashboardSyncException) {
            store.status(error.reason.explanation)
            if (error.reason == DashboardFailure.AUTH && token != null) runCatching { clearDashboardToken(applicationContext, token) }
            if (error.reason == DashboardFailure.RETRY || error.reason == DashboardFailure.AUTH && token != null && runAttemptCount == 0) Result.retry() else Result.failure()
        } catch (_: Exception) { store.status("Reconnect Google to resume automatic sync."); Result.failure() }
    }
}

/** Observe changes only while opted in; disabled users incur no extra Room reader. */
object GoogleDashboardChanges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var watching: Job? = null
    private var preferences: android.content.SharedPreferences? = null
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun start(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("google-dashboard", Context.MODE_PRIVATE)
        fun reload() { scope.launch { mutex.withLock {
            watching?.cancelAndJoin(); watching = null
            if (GoogleDashboardStore(app).automatic()) watching = scope.launch {
                val database = FitDatabase.open(app)
                try {
                    database.invalidationTracker.createFlow("workouts", "workout_exercises", "workout_sets", "exercises", "imported_workouts", "imported_sets",
                        "body_measurements", "exercise_aliases", "exercise_source_mappings", "exercise_redirects", "health_sessions", "run_links")
                        .collect { GoogleDashboardWork.request(app) }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { GoogleDashboardStore(app).status("Automatic sync could not read the history. Open the dashboard settings to retry. Your local records were not reset.")
                } finally { database.close() }
            }
        } } }
        listener?.let { preferences?.unregisterOnSharedPreferenceChangeListener(it) }
        listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "automatic" || key == null) reload() }
        preferences = prefs; prefs.registerOnSharedPreferenceChangeListener(listener); reload()
    }
}
