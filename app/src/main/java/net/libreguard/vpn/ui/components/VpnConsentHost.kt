package net.libreguard.vpn.ui.components

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import net.libreguard.vpn.service.vpn.VpnPermissionFailure
import net.libreguard.vpn.util.openVpnSettings
import net.libreguard.vpn.viewmodel.VpnConsentStage
import net.libreguard.vpn.viewmodel.VpnConsentUiState
import net.libreguard.vpn.viewmodel.VpnViewModel

@Composable
fun VpnConsentHost(viewModel: VpnViewModel) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val state by viewModel.vpnConsentState.collectAsState()
    val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
    var launchedAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsUnavailable by remember(state.showRecoveryDialog) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val attempt = launchedAttempt
        launchedAttempt = null
        viewModel.onVpnPermissionResult(attempt, result.resultCode == Activity.RESULT_OK)
    }
    VpnConsentLaunchEffect(viewModel.vpnConsentState, launchedAttempt == null, viewModel::claimVpnConsentRequest) { id, intent ->
        launchedAttempt = id
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
            launchedAttempt = null
            viewModel.onVpnConsentLaunchFailed(id)
        }
    }
    if (state.showRecoveryDialog && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        VpnConsentRecoveryDialog(
            failure = state.failure ?: VpnPermissionFailure.CONSENT_REQUIRED,
            onePlus = Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) && Build.VERSION.SDK_INT == 34,
            settingsUnavailable = settingsUnavailable,
            onRetry = viewModel::retryVpnConsent,
            onDismiss = viewModel::dismissVpnConsentRecovery,
            onSettings = {
                if (openVpnSettings { action -> context.startActivity(Intent(action)) }) {
                    viewModel.dismissVpnConsentRecovery()
                } else {
                    settingsUnavailable = true
                }
            }
        )
    }
}

/** Keep pending requests while backgrounded, and claim each launch exactly once. */
@Composable
internal fun <I> VpnConsentLaunchEffect(
    state: StateFlow<VpnConsentUiState<I>>,
    launchAllowed: Boolean,
    claim: (String) -> I?,
    launch: (String, I) -> Unit
) {
    val owner = LocalLifecycleOwner.current
    val currentClaim by rememberUpdatedState(claim)
    val currentLaunch by rememberUpdatedState(launch)
    LaunchedEffect(state, owner, launchAllowed) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.collect { request ->
                val id = request.attemptId
                if (launchAllowed && request.stage == VpnConsentStage.REQUEST_PENDING && id != null) {
                    currentClaim(id)?.let { currentLaunch(id, it) }
                }
            }
        }
    }
}

@Composable
fun VpnConsentRecoveryDialog(
    failure: VpnPermissionFailure,
    onePlus: Boolean = false,
    settingsUnavailable: Boolean = false,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.testTag("vpn_consent_recovery"),
        onDismissRequest = onDismiss,
        title = { Text(if (failure == VpnPermissionFailure.CHECK_FAILED) "VPN access could not be checked" else "VPN permission needed") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (failure) {
                    VpnPermissionFailure.CHECK_FAILED -> "Android could not check VPN access. Try again or check VPN settings."
                    VpnPermissionFailure.REQUEST_UNAVAILABLE -> "Android could not open the VPN approval screen. Try again or check VPN settings."
                    VpnPermissionFailure.CONSENT_REVOKED -> "Android stopped LibreGuard's VPN access. Try again to request access before reconnecting."
                    VpnPermissionFailure.DENIED -> "VPN permission was declined. Tap Try again to reopen Android's request, then choose Allow. If the request does not appear, check VPN settings for restrictions."
                    else -> "Android has not allowed LibreGuard to create a VPN connection. Tap Try again and choose Allow. If the prompt does not appear, check VPN settings for restrictions or another always-on VPN."
                })
                if (onePlus) Text("On OxygenOS 14: Settings → Connection & sharing → VPN. Approval is granted through Android's VPN prompt.")
                if (settingsUnavailable) Text("Settings could not be opened. Open your device's Settings and search for VPN.")
            }
        },
        confirmButton = {
            RecoveryActionRow(listOf(
                RecoveryAction("VPN settings", "vpn_open_settings", onSettings),
                RecoveryAction("Not now", "vpn_consent_dismiss", onDismiss),
                RecoveryAction("Try again", "vpn_consent_retry", onRetry)
            ))
        }
    )
}

@Composable
fun VpnConsentNotice(state: VpnConsentUiState<*>, onRetry: () -> Unit, onCancel: () -> Unit) {
    if (state.stage == VpnConsentStage.IDLE) return
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("vpn_consent_notice"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (state.awaitingConsent) "Waiting for VPN approval"
                else if (state.failure == VpnPermissionFailure.CHECK_FAILED) "VPN access could not be checked"
                else "Not connected — VPN permission needed",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (state.awaitingConsent) "Choose Allow in Android's VPN prompt to continue this connection."
                else "Tap Try again to request access before connecting.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = if (state.awaitingConsent) onCancel else onRetry) {
                Text(if (state.awaitingConsent) "Cancel" else "Try again")
            }
        }
    }
}
