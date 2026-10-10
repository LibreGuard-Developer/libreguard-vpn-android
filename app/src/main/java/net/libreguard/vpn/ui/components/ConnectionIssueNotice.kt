package net.libreguard.vpn.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.ui.theme.CardBackground
import net.libreguard.vpn.util.BackgroundConnectionSupport
import net.libreguard.vpn.util.openVpnSettings
import net.libreguard.vpn.viewmodel.ConnectionIssue
import net.libreguard.vpn.viewmodel.ConnectionIssueSettings

@Composable
internal fun ConnectionIssueNotice(issue: ConnectionIssue?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (issue == null) return
    val context = LocalContext.current
    ConnectionIssueCard(issue, onDismiss = onDismiss, modifier = modifier, onSettings = {
        val opened = when (issue.settings) {
            ConnectionIssueSettings.APP -> BackgroundConnectionSupport(context).openAppDetails()
            ConnectionIssueSettings.VPN -> openVpnSettings { context.startActivity(Intent(it)) }
            ConnectionIssueSettings.NONE -> false
        }
        if (!opened) Toast.makeText(context,
            "Open Android Settings and check LibreGuard VPN's network access or VPN settings.", Toast.LENGTH_LONG).show()
    })
}

@Composable
internal fun ConnectionIssueCard(
    issue: ConnectionIssue,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("connection_issue"),
        shape = RoundedCornerShape(12.dp), color = CardBackground) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(issue.title ?: "Connection needs attention", style = MaterialTheme.typography.titleSmall)
            Text(issue.message, style = MaterialTheme.typography.bodySmall)
            RecoveryActionRow(buildList {
                if (issue.settings != ConnectionIssueSettings.NONE) {
                    add(RecoveryAction(if (issue.settings == ConnectionIssueSettings.VPN) "VPN settings" else "App settings",
                        "connection_issue_settings", onSettings))
                }
                add(RecoveryAction("Dismiss", "connection_issue_dismiss", onDismiss))
            })
        }
    }
}
