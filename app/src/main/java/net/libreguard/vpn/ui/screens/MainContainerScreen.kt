package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.ui.navigation.BottomNavScaffold
import net.libreguard.vpn.ui.navigation.MainTab
import net.libreguard.vpn.viewmodel.VpnViewModel

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

    BottomNavScaffold(
        currentTab = currentTab,
        onTabSelected = { currentTab = it }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
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
                    StatisticsScreen()
                }
                MainTab.SETTINGS -> {
                    SettingsScreen(
                        onNavigateBack = { currentTab = MainTab.DASHBOARD },
                        onNavigateToTwoFactor = onNavigateToTwoFactor,
                        onNavigateToUpgrade = onNavigateToUpgrade,
                        onLogout = onLogout
                    )
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

