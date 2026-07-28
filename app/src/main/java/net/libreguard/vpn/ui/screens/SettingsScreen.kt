package net.libreguard.vpn.ui.screens

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import net.libreguard.vpn.BuildConfig
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.SubscriptionStatusResponse
import net.libreguard.vpn.ui.components.ProBadge
import net.libreguard.vpn.ui.components.ScreenHeader
import net.libreguard.vpn.ui.components.SectionHeader
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.CrashlyticsReporter
import net.libreguard.vpn.viewmodel.SubscriptionViewModel
import net.libreguard.vpn.viewmodel.DnsPreferenceUiState
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    effectiveDarkMode: Boolean = false,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onNavigateBack: () -> Unit,
    onNavigateToTwoFactor: () -> Unit,
    onNavigateToUpgrade: () -> Unit,
    onNavigateToHelp: () -> Unit = {},
    onNavigateToOpenSource: () -> Unit = {},
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

    // Observe selected protocol (default protocol)
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()

    val dnsPreference by viewModel.dnsPreference.collectAsState()

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
                    CrashlyticsReporter.recordHandledException(e, "Error fetching 2FA status")
                } finally {
                    isLoading2fa = false
                }
            }
        }
        subscriptionViewModel.fetchSubscriptionStatus()
        viewModel.refreshDnsPreference(force = true)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, token) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !token.isNullOrBlank()) {
                viewModel.refreshDnsPreference()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            ScreenHeader(
                title = "Settings",
                subtitle = "Configure your VPN preferences",
                onBack = onNavigateBack,
                backLabel = "Back"
            )

            // Account Info Card
            if (!userEmail.isNullOrBlank()) {
                SettingsCard(modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)) {
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
                Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))
            }

            // Plan Upgrade Card (only show if not Pro)
            if (!isPro && !isLoading) {
                UpgradeCard(
                    onUpgradeClick = onNavigateToUpgrade,
                    modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)
                )
                Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))
            }

            // Pro Plan Display (if Pro)
            if (isPro && subscriptionStatus != null) {
                ProPlanCard(
                    subscriptionStatus = subscriptionStatus!!,
                    modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)
                )
                Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))
            }

            // Security Section
            SectionHeader(title = "Security", modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding))

            SettingsCard(modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)) {
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

            Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))

            // Connection Section
            SectionHeader(title = "Connection", modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding))

            SettingsCard(modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)) {
                // Default Protocol Selection
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Default Protocol",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPro) Foreground else MutedForeground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // IKEv2/IPSec Button (always available)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedProtocol == net.libreguard.vpn.viewmodel.VpnProtocol.IKEV2_IPSEC) Primary else CardBackground,
                            border = if (selectedProtocol != net.libreguard.vpn.viewmodel.VpnProtocol.IKEV2_IPSEC)
                                ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                            onClick = { viewModel.saveDefaultProtocol(net.libreguard.vpn.viewmodel.VpnProtocol.IKEV2_IPSEC) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "IKEv2/IPSec",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedProtocol == net.libreguard.vpn.viewmodel.VpnProtocol.IKEV2_IPSEC) PrimaryForeground else Foreground
                                )
                            }
                        }

                        // OpenVPN Button with PRO badge
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isPro && selectedProtocol == net.libreguard.vpn.viewmodel.VpnProtocol.OPENVPN) Primary else CardBackground,
                                border = if (!isPro || selectedProtocol != net.libreguard.vpn.viewmodel.VpnProtocol.OPENVPN)
                                    ButtonDefaults.outlinedButtonBorder(enabled = isPro) else null,
                                onClick = {
                                    if (!isPro) {
                                        onNavigateToUpgrade()
                                    } else {
                                        viewModel.saveDefaultProtocol(net.libreguard.vpn.viewmodel.VpnProtocol.OPENVPN)
                                    }
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "OpenVPN",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isPro && selectedProtocol == net.libreguard.vpn.viewmodel.VpnProtocol.OPENVPN) PrimaryForeground else if (isPro) Foreground else MutedForeground
                                    )
                                }
                            }
                            // PRO badge for free users
                            if (!isPro) {
                                ProBadge(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 0.dp))

                // Auto-Connect Toggle with PRO badge for free users
                Box {
                    SettingsToggleRow(
                        icon = Icons.Default.Power,
                        title = "Auto-Connect",
                        subtitle = "Connect on app launch",
                        checked = autoConnectEnabled,
                        enabled = isPro,
                        onCheckedChange = { isChecked ->
                            viewModel.setAutoConnect(isChecked)
                        },
                        onRowClick = if (!isPro) onNavigateToUpgrade else null
                    )
                    // PRO badge for free users
                    if (!isPro) {
                        ProBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-16).dp, y = 16.dp)
                        )
                    }
                }
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))

                // Kill Switch Toggle with PRO badge for free users
                Box {
                    SettingsToggleRow(
                        icon = Icons.Default.Shield,
                        title = "Kill Switch",
                        subtitle = "Block internet if VPN drops",
                        checked = killSwitchEnabled,
                        enabled = isPro,
                        onCheckedChange = { isChecked ->
                            viewModel.setKillSwitch(isChecked)
                        },
                        onRowClick = if (!isPro) onNavigateToUpgrade else null
                    )
                    // PRO badge for free users
                    if (!isPro) {
                        ProBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-16).dp, y = 16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))

            // DNS & Privacy Section
            SectionHeader(title = "DNS & Privacy", modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding))

            DnsPrivacySettingsCard(
                state = dnsPreference,
                onToggleAdBlocking = viewModel::setDnsAdBlocking,
                onNavigateToUpgrade = onNavigateToUpgrade,
                modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)
            )

            Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))

            // Preferences Section
            SectionHeader(title = "Preferences", modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding))

            SettingsCard(modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)) {
                ThemeModeSelector(
                    selectedThemeMode = themeMode,
                    effectiveDarkMode = effectiveDarkMode,
                    onThemeModeChange = onThemeModeChange
                )
            }

            Spacer(modifier = Modifier.height(LibreGuardDimens.sectionSpacing))

            // Support Section
            SectionHeader(title = "Support", modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding))

            SettingsCard(modifier = Modifier.padding(horizontal = 24.dp)) {
                SettingsItemRow(
                    icon = Icons.Default.Help,
                    title = "Help & Support",
                    onClick = onNavigateToHelp
                )
                HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))
                SettingsItemRow(
                    icon = Icons.Default.Gavel,
                    title = "Open Source Licenses",
                    subtitle = "GPLv2, source code, and bundled notices",
                    onClick = onNavigateToOpenSource
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
                    text = "LibreGuard v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Text(
                    text = "Open-source VPN app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Text(
                    text = "Licensed under GPL v2.0",
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
                                CrashlyticsReporter.recordHandledException(e, "Error disabling 2FA")
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
internal fun DnsPrivacySettingsCard(
    state: DnsPreferenceUiState,
    onToggleAdBlocking: (Boolean) -> Unit,
    onNavigateToUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locked = state.isLoaded && !state.canUseAdBlocking && !state.requestedEnabled
    val canToggle = state.isLoaded && !state.isLoading && !state.isSaving &&
        (state.canUseAdBlocking || state.requestedEnabled)
    val subtitle = when {
        state.isSaving -> "Saving this account-wide preference…"
        state.isLoading || !state.isLoaded -> "Checking your account setting…"
        state.requestedEnabled && !state.canUseAdBlocking ->
            "Paused — your saved preference needs an active Pro plan"
        state.requestedEnabled && state.effectiveEnabled ->
            "Active for this account and all your devices"
        state.requestedEnabled ->
            "Saved, but filtering is currently unavailable; private DNS remains active"
        state.canUseAdBlocking ->
            "Filter many ad and tracker domains across all your devices"
        else -> "Available with Pro across all your devices"
    }

    SettingsCard(modifier = modifier) {
        SettingsInfoRow(
            icon = Icons.Default.Dns,
            title = "Private DNS",
            subtitle = "LibreGuard DNS is used automatically for every VPN connection"
        )
        HorizontalDivider(color = Border, modifier = Modifier.padding(start = 68.dp))
        Box {
            SettingsToggleRow(
                icon = Icons.Default.Block,
                title = "DNS Ad Blocking",
                subtitle = subtitle,
                checked = state.requestedEnabled,
                enabled = canToggle,
                onCheckedChange = onToggleAdBlocking,
                onRowClick = if (locked) onNavigateToUpgrade else null,
                modifier = Modifier.testTag("dns_ad_blocking_row"),
                switchModifier = Modifier.testTag("dns_ad_blocking_switch")
            )
            if (state.isLoaded && !state.canUseAdBlocking) {
                ProBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-16).dp, y = 16.dp)
                )
            }
        }
        state.confirmationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Primary,
                modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 16.dp)
            )
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String
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
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = Foreground)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MutedForeground)
        }
    }
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
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onRowClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onRowClick != null) {
                    Modifier.clickable { onRowClick() }
                } else if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Primary.copy(alpha = if (enabled) 0.1f else 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Primary else MutedForeground,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) Foreground else MutedForeground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
        Switch(
            modifier = switchModifier,
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PrimaryForeground,
                checkedTrackColor = Primary,
                uncheckedThumbColor = PrimaryForeground,
                uncheckedTrackColor = SwitchBackground,
                disabledCheckedThumbColor = MutedForeground,
                disabledCheckedTrackColor = MutedForeground.copy(alpha = 0.3f),
                disabledUncheckedThumbColor = MutedForeground,
                disabledUncheckedTrackColor = SwitchBackground.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selectedThemeMode: ThemeMode,
    effectiveDarkMode: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val currentAppearance = if (effectiveDarkMode) "Dark" else "Light"
    val subtitle = when (selectedThemeMode) {
        ThemeMode.SYSTEM -> "Following system theme • Currently $currentAppearance"
        ThemeMode.LIGHT -> "Manual theme override • Always Light"
        ThemeMode.DARK -> "Manual theme override • Always Dark"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
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
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleSmall,
                    color = Foreground
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeMode.entries.forEach { mode ->
                val chipIcon = when (mode) {
                    ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                    ThemeMode.LIGHT -> Icons.Default.LightMode
                    ThemeMode.DARK -> Icons.Default.DarkMode
                }

                Box(modifier = Modifier.weight(1f)) {
                    FilterChip(
                        selected = selectedThemeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = chipIcon,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        },
                        label = {
                            Text(
                                text = when (mode) {
                                    ThemeMode.SYSTEM -> if (selectedThemeMode == ThemeMode.SYSTEM) {
                                        "System • $currentAppearance"
                                    } else {
                                        "System"
                                    }
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                                maxLines = 1
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.12f),
                            selectedLabelColor = Primary,
                            containerColor = Background,
                            labelColor = MutedForeground
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedThemeMode == mode,
                            borderColor = Border,
                            selectedBorderColor = Primary.copy(alpha = 0.35f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 160.dp)
            .padding(4.dp)
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = Primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = PrimaryForeground,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MutedForeground
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

            // Responsive feature list: adapt between 1 and 2 columns depending on screen width
            val features = listOf(
                "Unlimited bandwidth",
                "Priority servers",
                "OpenVPN support",
                "DNS-based ad blocking",
                "Manual VPN configuration export",
                "Email support",
                "Auto-connect",
                "Kill switch",
                "Use on up to 3 devices simultaneously"
            )
            val columns = if (LocalConfiguration.current.screenWidthDp < 420) 1 else 2
            val rows = features.chunked(columns)
            Column {
                rows.forEachIndexed { idx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { feature ->
                            Box(modifier = Modifier.weight(1f)) {
                                FeatureItem(feature)
                            }
                        }
                        if (row.size < columns) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    if (idx < rows.size - 1) Spacer(modifier = Modifier.height(8.dp))
                }
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
        themeMode = ThemeMode.SYSTEM,
        effectiveDarkMode = false,
        onNavigateBack = { },
        onNavigateToTwoFactor = { },
        onNavigateToUpgrade = { },
        onLogout = { }
    )
}
