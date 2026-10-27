package com.example.fitnotes.update

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, NO_RELEASE, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
data class UpdateUiState(val installedName: String, val installedCode: Long, val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateRelease? = null, val file: File? = null, val downloaded: Long = 0,
    val error: String? = null, val mayInstall: Boolean = false) {
    val busy get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updater = GitHubUpdater(application)
    private var job: Job? = null
    val state = MutableStateFlow(updater.installed.let { UpdateUiState(it.name, it.code, mayInstall = updater.mayInstall()) })
    fun check() = action(UpdatePhase.CHECKING) {
        state.value = state.value.copy(release = null, file = null, downloaded = 0)
        val release = updater.check()
        require(release == null || release.minSdk <= Build.VERSION.SDK_INT) { "This update requires a newer Android version (API ${release?.minSdk})." }
        state.value = state.value.copy(release = release, phase = when {
            release == null -> UpdatePhase.NO_RELEASE
            release.versionCode <= state.value.installedCode -> UpdatePhase.CURRENT
            else -> UpdatePhase.AVAILABLE
        })
    }
    fun download() = action(UpdatePhase.DOWNLOADING) {
        val release = requireNotNull(state.value.release)
        val file = updater.download(release) { bytes, _ -> state.value = state.value.copy(downloaded = bytes) }
        state.value = state.value.copy(phase = UpdatePhase.READY, file = file, mayInstall = updater.mayInstall())
    }
    fun install(onIntent: (Intent) -> Unit) {
        if (!updater.mayInstall()) { refreshPermission(); return }
        action(UpdatePhase.VERIFYING) {
            val current = state.value
            val intent = updater.installIntent(requireNotNull(current.file), requireNotNull(current.release))
            state.value = state.value.copy(phase = UpdatePhase.READY)
            onIntent(intent)
        }
    }
    fun refreshPermission() { state.value = state.value.copy(mayInstall = updater.mayInstall()) }
    fun cancel() { job?.cancel() }
    private fun action(phase: UpdatePhase, block: suspend () -> Unit) {
        if (job?.isCompleted == false) return
        state.value = state.value.copy(phase = phase, error = null)
        job = viewModelScope.launch {
            try { block() }
            catch (_: CancellationException) {
                state.value = state.value.copy(phase = if (state.value.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
                    file = null, downloaded = 0)
            } catch (error: Exception) {
                val message = when (error) {
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException, is javax.net.ssl.SSLException -> "Could not reach GitHub securely. Check your connection and try again."
                    is IllegalArgumentException, is IllegalStateException -> error.message ?: "The update could not be verified."
                    is java.io.IOException -> "The update request failed. Check your connection and try again."
                    else -> "Could not open the update. Please check again or use the GitHub releases page."
                }
                state.value = state.value.copy(phase = UpdatePhase.ERROR, error = message, file = null)
            }
        }
    }
}
