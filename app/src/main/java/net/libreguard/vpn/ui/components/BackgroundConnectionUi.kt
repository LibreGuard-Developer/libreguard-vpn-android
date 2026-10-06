package net.libreguard.vpn.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.*
import net.libreguard.vpn.viewmodel.VpnProtocol

@Composable
internal fun rememberBackgroundConnectionStatus(
    support: BackgroundConnectionSupport,
    protocol: VpnProtocol
): BackgroundConnectionStatus {
    var status by remember(support) { mutableStateOf(support.readStatus()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, support) {
        // Refresh on entry too, when the lifecycle is already resumed.
        status = support.readStatus()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = support.readStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status, protocol) {
        BackgroundConnectionDiagnostics.record(support.sdkInt, protocol.name, status)
    }
    return status
}

@Composable
internal fun rememberBackgroundTipVisible(
    status: BackgroundConnectionStatus,
    protocol: VpnProtocol,
    isConnected: Boolean,
    store: BackgroundTipStore
): MutableState<Boolean> {
    val visible = rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status, protocol, isConnected, isForeground, store) {
        if (shouldShowBackgroundTip(store, status, protocol == VpnProtocol.IKEV2_IPSEC, isForeground, isConnected)) {
            visible.value = true
        }
    }
    // Persist once the tip enters composition, not on a background state change.
    if (visible.value) SideEffect { if (!store.hasBeenShown()) store.markShown() }
    return visible
}

@Composable
internal fun BackgroundConnectionSettingsCard(
    status: BackgroundConnectionStatus,
    usesIkev2: Boolean,
    onBatterySettings: () -> Boolean,
    onTimingSettings: () -> Boolean,
    onAppDetails: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    fun openSettings(action: () -> Boolean) {
        if (!action()) Toast.makeText(context, "Open your phone's Settings and find LibreGuard under Apps.", Toast.LENGTH_LONG).show()
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("background_connection_settings"),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Background connection", style = MaterialTheme.typography.titleSmall, color = Foreground)
            Text("Allow LibreGuard to maintain your connection while the screen is off.",
                style = MaterialTheme.typography.bodySmall, color = MutedForeground)
            Text(when (status.batteryExempt) {
                true -> "Android battery access: Unrestricted"
                false -> "Android battery access: Restricted"
                null -> "Android battery access: Could not check"
            }, style = MaterialTheme.typography.bodyMedium, color = Foreground)
            if (status.batteryExempt != true) {
                TextButton(onClick = { openSettings(onBatterySettings) }) { Text("Allow background access") }
            }
            if (usesIkev2) {
                HorizontalDivider(color = Border)
                Text(when (status.exactAlarmAvailable) {
                    true -> "IKEv2 connection timing: Available"
                    false -> "IKEv2 connection timing: Limited"
                    null -> "IKEv2 connection timing: Could not check"
                }, style = MaterialTheme.typography.bodyMedium, color = Foreground)
                if (status.exactAlarmAvailable != true) {
                    Text("Android may delay connection maintenance. You can allow precise timing in system settings.",
                        style = MaterialTheme.typography.bodySmall, color = MutedForeground)
                    TextButton(onClick = { openSettings(onTimingSettings) }) { Text("Allow connection timing") }
                }
            }
            if (status.isXiaomiFamily) {
                HorizontalDivider(color = Border)
                Text("Xiaomi, Redmi and POCO", style = MaterialTheme.typography.titleSmall, color = Foreground)
                Text("Review these phone settings manually. Android battery access does not confirm them; names vary by MIUI or HyperOS version.",
                    style = MaterialTheme.typography.bodySmall, color = MutedForeground)
                Text("1. In LibreGuard's app battery settings, choose No restrictions.\n" +
                    "2. Enable Background autostart for LibreGuard in Settings.\n" +
                    "3. In recent apps, lock LibreGuard to keep it in the background.",
                    style = MaterialTheme.typography.bodySmall, color = Foreground)
                TextButton(onClick = { openSettings(onAppDetails) }) { Text("Open app settings") }
            }
        }
    }
}

@Composable
internal fun BackgroundConnectionTip(
    onReviewSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("background_connection_tip"),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Help your connection stay active", style = MaterialTheme.typography.titleSmall, color = Foreground)
            Text("Review your phone's background settings to reduce interruptions when the screen is off.",
                style = MaterialTheme.typography.bodySmall, color = MutedForeground)
            // Separate rows keep both actions readable at large font sizes.
            TextButton(onClick = onReviewSettings) { Text("Review settings") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
