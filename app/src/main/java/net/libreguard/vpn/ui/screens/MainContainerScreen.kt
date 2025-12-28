package net.libreguard.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.ui.navigation.BottomNavScaffold
import net.libreguard.vpn.ui.navigation.MainTab
import net.libreguard.vpn.ui.theme.Background
import net.libreguard.vpn.viewmodel.VpnViewModel

/**
 * Legal/Support screen overlay types
 */
enum class LegalScreen {
    NONE, HELP, PRIVACY, TERMS
}

/**
 * Main Container Screen with Bottom Navigation
 * Manages navigation between Dashboard, Servers, Statistics, and Settings tabs
 */
@Composable
fun MainContainerScreen(
    authToken: String,
    onLogout: () -> Unit,
    onNavigateToUpgrade: () -> Unit,
    onNavigateToTwoFactor: () -> Unit
) {
    val vpnViewModel: VpnViewModel = viewModel()
    var currentTab by remember { mutableStateOf(MainTab.DASHBOARD) }
    var legalScreen by remember { mutableStateOf(LegalScreen.NONE) }

    // Set auth token
    LaunchedEffect(authToken) {
        vpnViewModel.setAuthToken(authToken)
        vpnViewModel.loadRemoteServers()
    }

    // Listen for upgrade events
    LaunchedEffect(Unit) {
        vpnViewModel.upgradeEvents.collect {
            onNavigateToUpgrade()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomNavScaffold(
            currentTab = currentTab,
            onTabSelected = { currentTab = it }
        ) { paddingValues ->
            // Apply bottom padding so content doesn't disappear under the bottom nav bar.
            // We only apply bottom padding (not top) to avoid extra gaps at the top.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            ) {
                when (currentTab) {
                    MainTab.DASHBOARD -> {
                        DashboardScreen(
                            authToken = authToken,
                            vpnViewModel = vpnViewModel,
                            onNavigateToServers = { currentTab = MainTab.SERVERS },
                            onNavigateToUpgrade = onNavigateToUpgrade
                        )
                    }
                    MainTab.SERVERS -> {
                        ServerListScreen(
                            authToken = authToken,
                            vpnViewModel = vpnViewModel,
                            onServerSelected = { currentTab = MainTab.DASHBOARD },
                            onNavigateToUpgrade = onNavigateToUpgrade
                        )
                    }
                    MainTab.STATISTICS -> {
                        StatisticsScreen(viewModel = vpnViewModel)
                    }
                    MainTab.SETTINGS -> {
                        SettingsScreen(
                            onNavigateBack = { currentTab = MainTab.DASHBOARD },
                            onNavigateToTwoFactor = onNavigateToTwoFactor,
                            onNavigateToUpgrade = onNavigateToUpgrade,
                            onNavigateToHelp = { legalScreen = LegalScreen.HELP },
                            onNavigateToPrivacy = { legalScreen = LegalScreen.PRIVACY },
                            onNavigateToTerms = { legalScreen = LegalScreen.TERMS },
                            onLogout = onLogout
                        )
                    }
                }
            }
        }

        // Legal/Support Screens Overlay
        AnimatedVisibility(
            visible = legalScreen != LegalScreen.NONE,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background)
            ) {
                when (legalScreen) {
                    LegalScreen.HELP -> {
                        HelpSupportScreen(
                            onBack = { legalScreen = LegalScreen.NONE }
                        )
                    }
                    LegalScreen.PRIVACY -> {
                        PrivacyPolicyScreen(
                            onBack = { legalScreen = LegalScreen.NONE }
                        )
                    }
                    LegalScreen.TERMS -> {
                        TermsOfServiceScreen(
                            onBack = { legalScreen = LegalScreen.NONE }
                        )
                    }
                    LegalScreen.NONE -> { /* Do nothing */ }
                }
            }
        }
    }
}

/**
 * Legacy MainScreen wrapper for backward compatibility
 * This delegates to the main container screen
 */
@Composable
fun MainScreen(
    authToken: String,
    vpnViewModel: VpnViewModel? = null,
    onLogout: (() -> Unit)? = null,
    onNavigateToSettings: (() -> Unit)? = null,
    onNavigateToUpgrade: (() -> Unit)? = null
) {
    // Use MainContainerScreen for full navigation experience
    MainContainerScreen(
        authToken = authToken,
        onLogout = { onLogout?.invoke() },
        onNavigateToUpgrade = { onNavigateToUpgrade?.invoke() },
        onNavigateToTwoFactor = { /* Navigate to 2FA settings */ }
    )
}
