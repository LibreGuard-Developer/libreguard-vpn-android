package net.libreguard.vpn.ui.screens

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import kotlinx.coroutines.launch
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
    onLogout: () -> Unit,
    vpnViewModel: net.libreguard.vpn.viewmodel.VpnViewModel? = null
) {
    // Get or create ViewModel
    val viewModel: net.libreguard.vpn.viewmodel.VpnViewModel = vpnViewModel ?: viewModel()

    // Observe auto-connect state
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsState()

    // Observe kill switch state
    val killSwitchEnabled by viewModel.killSwitchEnabled.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDisable2faDialog by remember { mutableStateOf(false) }
    var is2faEnabled by remember { mutableStateOf(false) }
    var isLoading2fa by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("vpn_state_prefs", android.content.Context.MODE_PRIVATE) }
    val token = remember { sharedPrefs.getString("auth_token", null) }

    val coroutineScope = rememberCoroutineScope()

    val subscriptionViewModel: SubscriptionViewModel = viewModel()
    val tokenManager = remember { RetrofitClient.getTokenManager() }
    val deviceMetadata by tokenManager.deviceMetadataFlow.collectAsState()

    // Fetch 2FA status
    LaunchedEffect(token) {
        token?.let {
            subscriptionViewModel.setAuthToken(it)
            // Fetch 2FA status
            coroutineScope.launch {
                isLoading2fa = true
                try {
                    val response = RetrofitClient.instance.get2faStatus("Bearer $it")
                    if (response.isSuccessful) {
                        response.body()?.let { status ->
                            is2faEnabled = status.is2faEnabled
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SettingsScreen", "Error fetching 2FA status: ${e.message}")
                } finally {
                    isLoading2fa = false
                }
            }
        }
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

            // Account Info Card
            if (!userEmail.isNullOrBlank()) {
                SettingsCard(modifier = Modifier.padding(horizontal = 24.dp)) {
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
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Account",
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground
                            )
                            Text(
                                text = userEmail ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            android.util.Log.d("SettingsScreen", "2FA row clicked, current is2faEnabled=$is2faEnabled")
                            if (!is2faEnabled) {
                                // Navigate to setup screen when disabled
                                android.util.Log.d("SettingsScreen", "Navigating to TwoFactorSettings for setup")
                                onNavigateToTwoFactor()
                            } else {
                                // Show confirmation dialog to disable when enabled
                                android.util.Log.d("SettingsScreen", "Showing disable 2FA dialog")
                                showDisable2faDialog = true
                            }
                        }
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
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Two-Factor Authentication",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                        Text(
                            text = "Add extra layer of security",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                    Switch(
                        checked = is2faEnabled,
                        onCheckedChange = null, // Disable direct switch interaction
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PrimaryForeground,
                            checkedTrackColor = Primary,
                            uncheckedThumbColor = PrimaryForeground,
                            uncheckedTrackColor = SwitchBackground
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connection Section
            SectionHeader(title = "Connection", modifier = Modifier.padding(horizontal = 24.dp))

            SettingsCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsToggleRow(
                    icon = Icons.Default.Power,
                    title = "Auto-Connect",
                    subtitle = "Connect on app launch",
                    checked = autoConnectEnabled,
                    onCheckedChange = { isChecked ->
                        viewModel.setAutoConnect(isChecked)
                    }
                )
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))
                SettingsToggleRow(
                    icon = Icons.Default.Shield,
                    title = "Kill Switch",
                    subtitle = "Block internet if VPN drops",
                    checked = killSwitchEnabled,
                    onCheckedChange = { isChecked ->
                        viewModel.setKillSwitch(isChecked)
                    }
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

    // Disable 2FA Confirmation Dialog
    if (showDisable2faDialog) {
        AlertDialog(
            onDismissRequest = { showDisable2faDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Destructive.copy(alpha = 0.1f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Destructive,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    if (is2faEnabled) "Disable Two-Factor Authentication" else "Enable Two-Factor Authentication",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Foreground
                )
            },
            text = {
                Text(
                    if (is2faEnabled) "Are you sure you want to disable 2FA? You will lose an extra layer of security." else "Enable 2FA to add an extra layer of security to your account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedForeground
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisable2faDialog = false
                        // Disable 2FA via API
                        coroutineScope.launch {
                            try {
                                token?.let {
                                    val response = RetrofitClient.instance.disable2fa("Bearer $it")
                                    if (response.isSuccessful) {
                                        is2faEnabled = false
                                        android.util.Log.d("SettingsScreen", "2FA disabled successfully")
                                    } else {
                                        android.util.Log.e("SettingsScreen", "Failed to disable 2FA: ${response.code()}")
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsScreen", "Error disabling 2FA: ${e.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Destructive),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Disable 2FA")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisable2faDialog = false },
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
            .clickable(onClick = onClick)
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
