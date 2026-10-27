package com.example.fitnotes.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fitnotes.data.FitDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock

class GoogleDashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val store = GoogleDashboardStore(application)
    val connection = MutableStateFlow(store.read())
    val options = MutableStateFlow(store.options())
    val automatic = MutableStateFlow(store.automatic())
    val message = MutableStateFlow(store.status())
    val working = MutableStateFlow(false)
    private var job: Job? = null
    fun refresh() { connection.value = store.read(); options.value = store.options(); automatic.value = store.automatic(); message.value = store.status() }
    fun options(value: DashboardOptions) {
        store.options(value)
        if (store.read().fileId.isNotBlank()) store.status(if (store.automatic()) "Options changed. Automatic sync will update your dashboard." else "Options changed. Sync now to update your dashboard.")
        refresh(); GoogleDashboardWork.request(getApplication())
    }
    fun automatic(value: Boolean) {
        store.automatic(value); refresh()
        if (value) GoogleDashboardWork.request(getApplication()) else GoogleDashboardWork.cancel(getApplication())
    }
    fun status(value: String) { store.status(value); message.value = value }
    fun sync(token: String, switchAccount: Boolean = false, newDashboard: Boolean = false, repairCharts: Boolean = false) {
        if (working.value) return
        working.value = true; status("Syncing to your Google Drive…")
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GoogleDashboardWork.cancel(getApplication())
                    val connectionKey = DashboardPublisher.mutex.withLock {
                        if (switchAccount) store.disconnect()
                        else if (newDashboard) store.newDashboard()
                        store.read().key
                    }
                    val database = FitDatabase.open(getApplication())
                    val snapshot = try { DashboardSnapshot.project(dashboardBackup(database), store.options()) } finally { database.close() }
                    val result = DashboardPublisher(GoogleDashboardTransport(), store).publish(snapshot, token, repairCharts, connectionKey)
                    store.status(if (result.unchanged) "Dashboard is up to date." else "Dashboard synced to your Google Drive.")
                }
            } catch (error: CancellationException) { store.status("Sync cancelled. Your local records are unchanged."); throw error }
            catch (error: DashboardSyncException) {
                if (error.reason == DashboardFailure.AUTH) runCatching { clearDashboardToken(getApplication(), token) }
                store.status(error.reason.explanation)
            } catch (_: Exception) { store.status("Unable to sync. Your local records are unchanged. Check your connection and Google setup, then retry.") }
            finally { working.value = false; refresh() }
        }
    }
    fun cancel() { job?.cancel(); GoogleDashboardWork.cancel(getApplication()) }
    fun disconnect() {
        if (working.value) return
        working.value = true
        job = viewModelScope.launch {
            var revoked = false
            try {
                GoogleDashboardWork.cancel(getApplication())
                val email = store.read().email
                if (email.isNotBlank()) { revokeDashboardAccess(getApplication(), email); revoked = true }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Local disconnect still succeeds without a network connection. */ }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { DashboardPublisher.mutex.withLock { store.disconnect() } }
                store.status(if (revoked) "Disconnected from Google. Your Drive files and local records are kept."
                    else "Disconnected on this device. You can also remove FitLogs access in your Google account settings. Your files are kept.")
                working.value = false; refresh()
            }
        }
    }
}
