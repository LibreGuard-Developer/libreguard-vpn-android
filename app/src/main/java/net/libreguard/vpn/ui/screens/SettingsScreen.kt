package net.libreguard.vpn.ui.screens

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.SubscriptionStatusResponse
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.SubscriptionViewModel
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTwoFactor: () -> Unit,
    onNavigateToUpgrade: () -> Unit,
    onNavigateToHelp: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onLogout: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("vpn_state_prefs", android.content.Context.MODE_PRIVATE) }
    val token = remember { sharedPrefs.getString("auth_token", null) }

    val subscriptionViewModel: SubscriptionViewModel = viewModel()
    val tokenManager = remember { RetrofitClient.getTokenManager() }
    val deviceMetadata by tokenManager.deviceMetadataFlow.collectAsState()

    LaunchedEffect(token) {
        token?.let { subscriptionViewModel.setAuthToken(it) }
        subscriptionViewModel.fetchSubscriptionStatus()
    }

    val subscriptionStatus by subscriptionViewModel.subscriptionStatus.collectAsState()
    val isPro by subscriptionViewModel.isPro.collectAsState()
    val isLoading by subscriptionViewModel.isLoading.collectAsState()

    val userEmail by remember(token) {
        mutableStateOf(runCatching {
            if (token.isNullOrBlank()) return@runCatching null
            val parts = token.split(".")
            if (parts.size != 3) return@runCatching null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val json = JSONObject(payload)
            json.optString("email").ifBlank {
                json.optString("username").ifBlank {
                    json.optString("sub").ifBlank { null }
                }
            }
        }.getOrNull())
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Foreground
                )
                Text(
                    text = "Configure your VPN preferences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }

            // Plan Upgrade Card (only show if not Pro)
            if (!isPro && !isLoading) {
                UpgradeCard(
                    onUpgradeClick = onNavigateToUpgrade,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Pro Plan Display (if Pro)
            if (isPro && subscriptionStatus != null) {
                ProPlanCard(
                    subscriptionStatus = subscriptionStatus!!,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Security Section
            SectionHeader(title = "Security", modifier = Modifier.padding(horizontal = 24.dp))

            SettingsCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsItemRow(
                    icon = Icons.Default.Smartphone,
                    title = "Two-Factor Authentication",
                    subtitle = "Add extra layer of security",
                    onClick = onNavigateToTwoFactor
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connection Section
            SectionHeader(title = "Connection", modifier = Modifier.padding(horizontal = 24.dp))

            SettingsCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsToggleRow(
                    icon = Icons.Default.Power,
                    title = "Auto-Connect",
                    subtitle = "Connect on app launch",
                    checked = false, // TODO: Connect to actual setting
                    onCheckedChange = { }
                )
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))
                SettingsToggleRow(
                    icon = Icons.Default.Shield,
                    title = "Kill Switch",
                    subtitle = "Block internet if VPN drops",
                    checked = false, // TODO: Connect to actual setting
                    onCheckedChange = { }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Support Section
            SectionHeader(title = "Support", modifier = Modifier.padding(horizontal = 24.dp))

            SettingsCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsItemRow(
                    icon = Icons.Default.Help,
                    title = "Help & Support",
                    onClick = onNavigateToHelp
                )
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))
                SettingsItemRow(
                    icon = Icons.Default.Description,
                    title = "Privacy Policy",
                    onClick = onNavigateToPrivacy
                )
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))
                SettingsItemRow(
                    icon = Icons.Default.Description,
                    title = "Terms of Service",
                    onClick = onNavigateToTerms
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Logout Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable { showLogoutDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = Destructive.copy(alpha = 0.1f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        tint = Destructive,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Sign Out",
                        style = MaterialTheme.typography.titleSmall,
                        color = Destructive
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Destructive,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // App Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LibreGuard v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Text(
                    text = "Open-source privacy VPN",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }
        }
    }

    // Logout Confirmation Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Destructive.copy(alpha = 0.1f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        tint = Destructive,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    "Sign Out",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Foreground
                )
            },
            text = {
                Text(
                    "Are you sure you want to sign out? This will disconnect your VPN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedForeground
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Destructive),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = MutedForeground)
                }
            },
            containerColor = Background,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = MutedForeground,
        modifier = modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Foreground
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MutedForeground,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Foreground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PrimaryForeground,
                checkedTrackColor = Primary,
                uncheckedThumbColor = PrimaryForeground,
                uncheckedTrackColor = SwitchBackground
            )
        )
    }
}

@Composable
private fun UpgradeCard(
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Primary.copy(alpha = 0.05f),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(Primary)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = PrimaryForeground,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Upgrade to Pro",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Text(
                        text = "Unlock unlimited data, faster servers, and premium features",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "✓ Unlimited bandwidth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Text(
                    text = "✓ Priority servers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onUpgradeClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground
                )
            ) {
                Text("Upgrade Now", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ProPlanCard(
    subscriptionStatus: SubscriptionStatusResponse,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(Primary)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Primary, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = PrimaryForeground,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pro Plan",
                    style = MaterialTheme.typography.titleSmall,
                    color = Foreground
                )
                Text(
                    text = "Active subscription",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MutedForeground,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewSettingsScreenNew() {
    SettingsScreen(
        onNavigateBack = { },
        onNavigateToTwoFactor = { },
        onNavigateToUpgrade = { },
        onLogout = { }
    )
}

