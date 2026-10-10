package net.libreguard.vpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import net.libreguard.vpn.ui.theme.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.viewmodel.NewsletterUiState
import net.libreguard.vpn.viewmodel.NewsletterViewModel
import java.net.URI

@Composable
fun NewsletterConsentHost(viewModel: NewsletterViewModel) {
    val state by viewModel.state.collectAsState()
    NewsletterConsentDialog(
        state, viewModel::setConsentChecked,
        { viewModel.completeOnboarding(true) }, { viewModel.completeOnboarding(false) },
        viewModel::dismissPrompt, viewModel::refresh
    )
}

@Composable
fun NewsletterConsentDialog(
    state: NewsletterUiState,
    onConsentChange: (Boolean) -> Unit,
    onSubscribe: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    val settings = state.settings ?: return
    if (!state.promptVisible || !settings.promptPending) return
    val ready = !state.isLoading && !state.isSaving && !state.requiresRefresh
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text("LibreGuard email updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This is optional. You can change your choice in account settings.")
                Row {
                    Checkbox(
                        checked = state.consentChecked,
                        onCheckedChange = onConsentChange,
                        enabled = ready && settings.canSubscribe,
                        modifier = Modifier.testTag("newsletterOnboardingConsent")
                    )
                    Text(settings.consentText, modifier = Modifier.padding(top = 12.dp))
                }
                NewsletterPrivacyLink(settings.privacyUrl)
                if (!settings.canSubscribe) Text("Verify your account email before subscribing.")
                NewsletterRequestStatus(state, onRefresh)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubscribe,
                enabled = ready && state.consentChecked && settings.canSubscribe,
                modifier = Modifier.testTag("newsletterSubscribe")
            ) { Text("Subscribe") }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip,
                enabled = ready,
                modifier = Modifier.testTag("newsletterSkip")
            ) { Text("Skip") }
        }
    )
}

@Composable
fun NewsletterPreferencesCard(
    state: NewsletterUiState,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    var descriptionExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column {
            if (settings != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { descriptionExpanded = !descriptionExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Newsletter", style = MaterialTheme.typography.titleSmall, color = Foreground)
                        Text("Email updates from LibreGuard", style = MaterialTheme.typography.bodySmall, color = MutedForeground)
                    }
                    Switch(
                        checked = settings.subscribed,
                        onCheckedChange = onToggle,
                        enabled = !state.isLoading && !state.isSaving && !state.requiresRefresh &&
                            (settings.canSubscribe || settings.subscribed),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PrimaryForeground,
                            checkedTrackColor = Primary,
                            uncheckedThumbColor = PrimaryForeground,
                            uncheckedTrackColor = SwitchBackground
                        ),
                        modifier = Modifier.testTag("accountNewsletterToggle")
                    )
                }

                AnimatedVisibility(
                    visible = descriptionExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 68.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(settings.consentText, style = MaterialTheme.typography.bodySmall, color = MutedForeground)
                        Text(
                            "This preference applies to your LibreGuard account across devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                        if (!settings.canSubscribe && !settings.subscribed) {
                            Text(
                                "Verify your account email before subscribing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                        NewsletterPrivacyLink(settings.privacyUrl)
                    }
                }
            }

            if (state.isLoading || state.isSaving || state.error != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NewsletterRequestStatus(state, onRefresh)
                }
            }
            if (settings == null && !state.isLoading && state.error == null) {
                TextButton(onClick = onRefresh, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    Text("Load email preferences")
                }
            }
        }
    }
}
@Composable
private fun NewsletterRequestStatus(state: NewsletterUiState, onRefresh: () -> Unit) {
    if (state.isLoading || state.isSaving) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(if (state.isSaving) "Saving…" else "Loading…", style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!state.isLoading && !state.isSaving)
            TextButton(onClick = onRefresh) { Text("Refresh preferences") }
    }
}

@Composable
private fun NewsletterPrivacyLink(url: String) {
    val uriHandler = LocalUriHandler.current
    val allowed = runCatching { URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() } }.getOrDefault(false)
    if (allowed) TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("Privacy policy") }
}
