package net.libreguard.vpn.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.R
import net.libreguard.vpn.core.*
import net.libreguard.vpn.ui.theme.*
import java.text.NumberFormat

data class AppUpdateActions(
    val onAction: () -> Unit = {},
    val onCheck: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onOpenStore: () -> Unit = {}
)

@Composable
fun AppUpdateSettingsCard(
    state: AppUpdateUiState,
    actions: AppUpdateActions,
    modifier: Modifier = Modifier
) {
    UpdateCard(state, actions, compact = false, modifier = modifier.testTag("app_update_settings_card"))
}

@Composable
fun AppUpdateHomeBanner(
    state: AppUpdateUiState,
    actions: AppUpdateActions,
    modifier: Modifier = Modifier
) {
    if (state.showHomeBanner) {
        UpdateCard(state, actions, compact = true, modifier = modifier.testTag("app_update_home_banner"))
    }
}

@Composable
private fun UpdateCard(
    state: AppUpdateUiState,
    actions: AppUpdateActions,
    compact: Boolean,
    modifier: Modifier
) {
    val snapshot = state.snapshot
    val highlighted = snapshot.phase == AppUpdatePhase.AVAILABLE || snapshot.isInProgress
    val iconTint = when (snapshot.phase) {
        AppUpdatePhase.CURRENT -> StatusConnected
        AppUpdatePhase.FAILED -> Destructive
        else -> Primary
    }
    val icon = when (snapshot.phase) {
        AppUpdatePhase.CURRENT -> Icons.Default.CheckCircle
        AppUpdatePhase.READY_TO_INSTALL -> Icons.Default.RestartAlt
        AppUpdatePhase.FAILED -> Icons.Default.Info
        else -> Icons.Default.SystemUpdate
    }
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) Primary.copy(alpha = 0.06f).compositeOver(CardBackground) else CardBackground,
        border = BorderStroke(1.dp, if (highlighted) Primary.copy(alpha = 0.3f) else Border)
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                        .background(iconTint.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (snapshot.phase == AppUpdatePhase.CHECKING) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Primary, strokeWidth = 2.dp)
                    } else {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = updateTitle(snapshot),
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (!compact) {
                        Text(
                            stringResource(R.string.app_update_installed_version, state.installedVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                }
                if (compact && snapshot.phase == AppUpdatePhase.AVAILABLE && snapshot.versionCode != null) {
                    IconButton(onClick = actions.onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.app_update_dismiss), tint = MutedForeground)
                    }
                }
            }
            Text(
                text = updateDescription(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = if (snapshot.phase == AppUpdatePhase.FAILED) Destructive else MutedForeground
            )
            if (snapshot.problem == AppUpdateProblem.INSTALL_FAILED) {
                Text(
                    stringResource(R.string.app_update_install_error),
                    style = MaterialTheme.typography.bodySmall, color = Destructive
                )
            }
            if (snapshot.phase in setOf(
                    AppUpdatePhase.STARTING, AppUpdatePhase.PENDING,
                    AppUpdatePhase.DOWNLOADING, AppUpdatePhase.INSTALLING
                )) {
                val progress = snapshot.progress
                if (snapshot.phase == AppUpdatePhase.DOWNLOADING && progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().testTag("app_update_progress"),
                        color = Primary, trackColor = Primary.copy(alpha = 0.12f)
                    )
                    Text(
                        stringResource(R.string.app_update_progress, NumberFormat.getPercentInstance().format(progress)),
                        style = MaterialTheme.typography.labelSmall, color = MutedForeground
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag("app_update_progress"),
                        color = Primary, trackColor = Primary.copy(alpha = 0.12f)
                    )
                }
            }
            val label = actionLabel(snapshot)
            if (label != null) {
                val onClick = if (snapshot.phase == AppUpdatePhase.READY_TO_INSTALL) actions.onRestart else actions.onAction
                val checking = state.isChecking && snapshot.phase == AppUpdatePhase.CURRENT
                val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("app_update_primary_action")
                if (highlighted || snapshot.phase == AppUpdatePhase.FAILED) {
                    Button(
                        onClick = onClick, enabled = !state.isChecking,
                        modifier = buttonModifier, shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = PrimaryForeground)
                    ) {
                        Text(stringResource(label))
                    }
                } else {
                    OutlinedButton(
                        onClick = onClick, enabled = !state.isChecking,
                        modifier = buttonModifier, shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(if (checking) R.string.app_update_checking else label))
                    }
                }
            }
            if (!compact && (snapshot.phase == AppUpdatePhase.FAILED || snapshot.problem == AppUpdateProblem.INSTALL_FAILED)) {
                TextButton(
                    onClick = actions.onOpenStore,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.app_update_open_store), color = Primary)
                }
            }
        }
    }
}

@Composable
private fun updateTitle(snapshot: AppUpdateSnapshot): String = stringResource(when (snapshot.phase) {
    AppUpdatePhase.CHECKING -> R.string.app_update_checking
    AppUpdatePhase.CURRENT -> R.string.app_update_current
    AppUpdatePhase.AVAILABLE -> R.string.app_update_available
    AppUpdatePhase.STARTING -> R.string.app_update_preparing
    AppUpdatePhase.PENDING -> R.string.app_update_waiting
    AppUpdatePhase.DOWNLOADING -> R.string.app_update_downloading
    AppUpdatePhase.READY_TO_INSTALL -> R.string.app_update_ready
    AppUpdatePhase.INSTALLING -> R.string.app_update_installing
    AppUpdatePhase.FAILED -> when (snapshot.problem) {
        AppUpdateProblem.CHECK_FAILED -> R.string.app_update_check_failed_title
        AppUpdateProblem.INSTALL_FAILED -> R.string.app_update_install_failed_title
        else -> R.string.app_update_failed
    }
    AppUpdatePhase.UNAVAILABLE -> R.string.app_update_unavailable
})

@Composable
private fun updateDescription(snapshot: AppUpdateSnapshot): String = stringResource(when (snapshot.phase) {
    AppUpdatePhase.CURRENT -> R.string.app_update_current_description
    AppUpdatePhase.AVAILABLE -> if (snapshot.flexibleAllowed) {
        R.string.app_update_available_description
    } else R.string.app_update_store_description
    AppUpdatePhase.READY_TO_INSTALL -> R.string.app_update_ready_description
    AppUpdatePhase.INSTALLING -> R.string.app_update_installing_description
    AppUpdatePhase.UNAVAILABLE -> R.string.app_update_store_description
    AppUpdatePhase.CHECKING -> R.string.app_update_checking_description
    AppUpdatePhase.FAILED -> when (snapshot.problem) {
        AppUpdateProblem.CHECK_FAILED -> R.string.app_update_check_error
        AppUpdateProblem.DOWNLOAD_FAILED -> R.string.app_update_download_error
        AppUpdateProblem.INSTALL_FAILED -> R.string.app_update_install_failed_description
        else -> R.string.app_update_start_error
    }
    else -> R.string.app_update_download_description
})

private fun actionLabel(snapshot: AppUpdateSnapshot): Int? = when (snapshot.phase) {
    AppUpdatePhase.CURRENT -> R.string.app_update_check_again
    AppUpdatePhase.AVAILABLE -> if (snapshot.flexibleAllowed) R.string.app_update_action else R.string.app_update_store_action
    AppUpdatePhase.READY_TO_INSTALL -> R.string.app_update_restart
    AppUpdatePhase.FAILED -> R.string.app_update_retry
    AppUpdatePhase.UNAVAILABLE -> R.string.app_update_open_store
    else -> null
}

@Composable
internal fun AppUpdateRestartDialog(onConfirm: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Primary) },
        title = { Text(stringResource(R.string.app_update_restart_title)) },
        text = { Text(stringResource(R.string.app_update_restart_warning), color = MutedForeground) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("app_update_confirm_restart")) {
                Text(stringResource(R.string.app_update_restart))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.app_update_later)) }
        },
        containerColor = CardBackground,
        shape = RoundedCornerShape(24.dp)
    )
}