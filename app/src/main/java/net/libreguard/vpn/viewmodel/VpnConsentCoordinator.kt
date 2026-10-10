package net.libreguard.vpn.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.libreguard.vpn.service.vpn.VpnPermissionFailure
import java.util.UUID

enum class VpnConsentStage { IDLE, REQUEST_PENDING, AWAITING_RESULT, RECOVERY }

data class VpnConsentUiState<I>(
    val stage: VpnConsentStage = VpnConsentStage.IDLE,
    val attemptId: String? = null,
    val intent: I? = null,
    val failure: VpnPermissionFailure? = null,
    val showRecoveryDialog: Boolean = false
) {
    val awaitingConsent: Boolean
        get() = stage == VpnConsentStage.REQUEST_PENDING || stage == VpnConsentStage.AWAITING_RESULT
}

/** Main-thread owned. Only a connection request may call prepare, never a status check. */
internal class VpnConsentCoordinator<R, I>(
    private val prepare: () -> I?,
    private val needsGuidance: () -> Boolean = { false },
    private val saveGuidance: (Boolean) -> Unit = {},
    private val onOutcome: (String) -> Unit = {},
    private val onCheckFailure: (Exception) -> Unit = {}
) {
    private val _state = MutableStateFlow(VpnConsentUiState<I>())
    val state: StateFlow<VpnConsentUiState<I>> = _state
    private val lifetimeId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var pending: R? = null
    val hasPendingRequest: Boolean get() = pending != null

    fun request(request: R, automatic: Boolean = false, retry: Boolean = false): R? {
        if (_state.value.awaitingConsent) return null
        val showGuidance = !retry && (needsGuidance() || _state.value.failure != null)
        pending = request
        return check("$lifetimeId-${++sequence}", automatic, showGuidance)
    }

    private fun check(id: String, automatic: Boolean, showGuidance: Boolean): R? {
        val intent = try {
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCheckFailure(e)
            recover(VpnPermissionFailure.CHECK_FAILED, showDialog = !automatic)
            if (automatic) pending = null
            return null
        }
        if (intent == null) {
            val ready = pending
            pending = null
            saveGuidance(false)
            _state.value = VpnConsentUiState()
            onOutcome("prepared")
            return ready
        }
        if (automatic || showGuidance) {
            recover(VpnPermissionFailure.CONSENT_REQUIRED, showDialog = !automatic)
            if (automatic) pending = null
        } else {
            _state.value = VpnConsentUiState(VpnConsentStage.REQUEST_PENDING, id, intent)
            onOutcome("requested")
        }
        return null
    }

    fun claimLaunch(id: String): I? {
        val state = _state.value
        if (state.stage != VpnConsentStage.REQUEST_PENDING || state.attemptId != id) return null
        _state.value = state.copy(stage = VpnConsentStage.AWAITING_RESULT)
        return state.intent
    }

    fun result(id: String?, approved: Boolean): R? {
        val state = _state.value
        if (id == null || state.attemptId != id || state.stage != VpnConsentStage.AWAITING_RESULT) return null
        if (!approved) {
            saveGuidance(true)
            recover(VpnPermissionFailure.DENIED, showDialog = true)
            return null
        }
        // RESULT_OK is not a permanent grant: recheck without opening a second prompt.
        return check(id, automatic = false, showGuidance = true)
    }

    fun launchFailed(id: String?) {
        if (id == null || _state.value.attemptId != id || !_state.value.awaitingConsent) return
        recover(VpnPermissionFailure.REQUEST_UNAVAILABLE, showDialog = true)
    }

    fun retry(): R? {
        val request = pending ?: return null
        return this.request(request, retry = true)
    }

    fun reportFailure(failure: VpnPermissionFailure, showDialog: Boolean) {
        pending = null
        recover(failure, showDialog)
    }

    private fun recover(failure: VpnPermissionFailure, showDialog: Boolean) {
        _state.value = VpnConsentUiState(
            stage = VpnConsentStage.RECOVERY, failure = failure, showRecoveryDialog = showDialog
        )
        onOutcome(failure.name.lowercase())
    }

    fun dismissRecovery() {
        pending = null
        _state.value = _state.value.copy(showRecoveryDialog = false)
    }

    fun cancel() {
        pending = null
        _state.value = VpnConsentUiState()
        onOutcome("cancelled")
    }
}
