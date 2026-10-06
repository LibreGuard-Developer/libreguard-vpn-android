package net.libreguard.vpn.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.libreguard.vpn.core.*
import net.libreguard.vpn.util.CrashlyticsReporter
import net.libreguard.vpn.util.getInstalledAppVersionName

sealed interface AppUpdateEffect {
    data class Launch(val starter: AppUpdateFlowStarter) : AppUpdateEffect
    data object OpenStore : AppUpdateEffect
    data object RequestRestart : AppUpdateEffect
}

enum class AppUpdateConsentResult { ACCEPTED, CANCELED, FAILED }

class AppUpdateViewModel(
    private val client: AppUpdateClient,
    private val snoozeStore: AppUpdateSnoozeStore,
    installedVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val reportError: (Throwable) -> Unit = {}
) : ViewModel() {
    private val _state = MutableStateFlow(AppUpdateUiState(installedVersion = installedVersion))
    val state = _state.asStateFlow()
    private val effectChannel = Channel<AppUpdateEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var checkJob: Job? = null
    private var operationJob: Job? = null
    private var revision = 0L

    init {
        viewModelScope.launch {
            client.installUpdates.collect { snapshot ->
                revision++
                applySnapshot(snapshot)
            }
        }
    }

    fun onForeground() {
        _state.update { it.copy(foregroundSession = it.foregroundSession + 1) }
        refreshSnooze()
        checkForUpdates()
    }

    fun checkForUpdates() {
        if (checkJob?.isActive == true || operationJob?.isActive == true ||
            state.value.snapshot.phase == AppUpdatePhase.STARTING) return
        val requestRevision = revision
        _state.update { it.copy(isChecking = true) }
        checkJob = viewModelScope.launch {
            try {
                val snapshot = withTimeout(REQUEST_TIMEOUT) { client.check() }
                // A listener event or user operation is newer than this request.
                if (revision == requestRevision) applySnapshot(snapshot)
            } catch (error: Exception) {
                rethrowCancellation(error)
                reportError(error)
                if (revision == requestRevision && !state.value.snapshot.isInProgress) {
                    applySnapshot(state.value.snapshot.copy(
                        phase = AppUpdatePhase.FAILED, problem = AppUpdateProblem.CHECK_FAILED
                    ))
                }
            } finally {
                _state.update { it.copy(isChecking = false) }
            }
        }
    }

    fun primaryAction() {
        when (state.value.snapshot.phase) {
            AppUpdatePhase.CURRENT, AppUpdatePhase.CHECKING -> checkForUpdates()
            AppUpdatePhase.UNAVAILABLE -> openStore()
            AppUpdatePhase.AVAILABLE -> if (state.value.snapshot.flexibleAllowed) startUpdate() else openStore()
            AppUpdatePhase.FAILED -> if (state.value.snapshot.problem == AppUpdateProblem.CHECK_FAILED) {
                checkForUpdates()
            } else startUpdate()
            // The host handles the explicit restart action and VPN warning.
            else -> Unit
        }
    }

    fun openStore() { effectChannel.trySend(AppUpdateEffect.OpenStore) }

    fun requestRestart() {
        if (state.value.snapshot.phase == AppUpdatePhase.READY_TO_INSTALL) {
            effectChannel.trySend(AppUpdateEffect.RequestRestart)
        }
    }

    private fun startUpdate() {
        if (operationJob?.isActive == true || state.value.snapshot.isInProgress) return
        checkJob?.cancel()
        revision++
        val requestRevision = revision
        applySnapshot(state.value.snapshot.copy(phase = AppUpdatePhase.STARTING, problem = null))
        operationJob = viewModelScope.launch {
            try {
                val prepared = withTimeout(REQUEST_TIMEOUT) { client.prepareUpdate() }
                if (requestRevision != revision) return@launch
                if (prepared.starter != null) {
                    applySnapshot(prepared.snapshot.copy(phase = AppUpdatePhase.STARTING))
                    effectChannel.send(AppUpdateEffect.Launch(prepared.starter))
                } else {
                    applySnapshot(prepared.snapshot)
                    if (prepared.snapshot.phase == AppUpdatePhase.AVAILABLE) {
                        effectChannel.send(AppUpdateEffect.OpenStore)
                    }
                }
            } catch (error: Exception) {
                rethrowCancellation(error)
                reportError(error)
                if (revision == requestRevision) fail(AppUpdateProblem.START_FAILED)
            }
        }
    }

    fun onConsentResult(result: AppUpdateConsentResult) {
        if (state.value.snapshot.phase != AppUpdatePhase.STARTING) return
        revision++
        when (result) {
            AppUpdateConsentResult.ACCEPTED -> applySnapshot(state.value.snapshot.copy(phase = AppUpdatePhase.PENDING))
            AppUpdateConsentResult.CANCELED -> applySnapshot(state.value.snapshot.copy(phase = AppUpdatePhase.AVAILABLE))
            AppUpdateConsentResult.FAILED -> fail(AppUpdateProblem.START_FAILED)
        }
    }

    fun onLaunchFailed(error: Throwable? = null) {
        error?.let(reportError)
        onConsentResult(AppUpdateConsentResult.FAILED)
    }

    fun completeUpdate() {
        if (state.value.snapshot.phase != AppUpdatePhase.READY_TO_INSTALL || operationJob?.isActive == true) return
        revision++
        checkJob?.cancel()
        applySnapshot(state.value.snapshot.copy(phase = AppUpdatePhase.INSTALLING, problem = null))
        operationJob = viewModelScope.launch {
            try {
                withTimeout(REQUEST_TIMEOUT) { client.completeUpdate() }
            } catch (error: Exception) {
                rethrowCancellation(error)
                reportError(error)
                applySnapshot(state.value.snapshot.copy(
                    phase = AppUpdatePhase.READY_TO_INSTALL, problem = AppUpdateProblem.INSTALL_FAILED
                ))
            }
        }
    }

    fun dismissBanner() {
        val snapshot = state.value.snapshot
        if (snapshot.phase != AppUpdatePhase.AVAILABLE) return
        val version = snapshot.versionCode ?: return
        snoozeStore.write(AppUpdateSnooze(version, now() + BANNER_SNOOZE_MILLIS))
        refreshSnooze()
    }

    private fun fail(problem: AppUpdateProblem) {
        applySnapshot(state.value.snapshot.copy(phase = AppUpdatePhase.FAILED, problem = problem))
    }

    private fun applySnapshot(snapshot: AppUpdateSnapshot) {
        _state.update { it.copy(snapshot = snapshot, bannerSnoozed = isSnoozed(snapshot.versionCode)) }
    }

    private fun refreshSnooze() {
        _state.update { it.copy(bannerSnoozed = isSnoozed(it.snapshot.versionCode)) }
    }

    private fun isSnoozed(version: Int?): Boolean = snoozeStore.read()?.let {
        version == it.versionCode && now() < it.untilMillis
    } ?: false

    private fun rethrowCancellation(error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
    }

    override fun onCleared() {
        client.close()
        effectChannel.close()
        super.onCleared()
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppUpdateViewModel::class.java))
            return AppUpdateViewModel(
                GooglePlayUpdateClient(appContext), AppUpdatePreferences(appContext),
                appContext.getInstalledAppVersionName(),
                reportError = { CrashlyticsReporter.recordHandledException(it, "Google Play update failed") }
            ) as T
        }
    }

    companion object {
        const val BANNER_SNOOZE_MILLIS = 24 * 60 * 60 * 1000L
        private const val REQUEST_TIMEOUT = 15_000L
    }
}