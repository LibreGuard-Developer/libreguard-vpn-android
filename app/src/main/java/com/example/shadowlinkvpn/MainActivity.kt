package com.example.shadowlinkvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.shadowlinkvpn.ui.screens.LoginScreen
import com.example.shadowlinkvpn.ui.screens.MainScreen
import com.example.shadowlinkvpn.ui.screens.TwoFactorSettingsScreen
import com.example.shadowlinkvpn.ui.screens.TwoFactorVerificationScreen
import com.example.shadowlinkvpn.ui.theme.ShadowLinkVPNTheme
import com.example.shadowlinkvpn.viewmodel.VpnViewModel

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShadowLinkVPNTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var authToken by remember { mutableStateOf<String?>(null) }
    var isCheckingToken by remember { mutableStateOf(true) }
    var pendingEmail by remember { mutableStateOf<String?>(null) }

    // Check for persisted auth token on startup
    LaunchedEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("vpn_state_prefs", android.content.Context.MODE_PRIVATE)
        val savedToken = sharedPrefs.getString("auth_token", null)

        if (!savedToken.isNullOrBlank()) {
            authToken = savedToken
            // Navigate to main if we have a valid token
            navController.navigate("main") {
                popUpTo("login") { inclusive = true }
            }
        }
        isCheckingToken = false
    }

    NavHost(
        navController = navController,
        startDestination = "login",
        modifier = modifier
    ) {
        composable("login") {
            if (!isCheckingToken) { // Only show login screen after checking token
                LoginScreen(
                    onLoginSuccess = { token ->
                        authToken = token
                        navController.navigate("main") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onRequires2FA = { email ->
                        pendingEmail = email
                        navController.navigate("twoFactor")
                    }
                )
            }
        }

        composable("twoFactor") {
            pendingEmail?.let { email ->
                TwoFactorVerificationScreen(
                    email = email,
                    onVerificationSuccess = { token ->
                        authToken = token
                        pendingEmail = null
                        navController.navigate("main") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onBackToLogin = {
                        pendingEmail = null
                        navController.navigate("login") {
                            popUpTo("twoFactor") { inclusive = true }
                        }
                    }
                )
            }
        }

        composable("main") {
            authToken?.let { token ->
                val viewModel: VpnViewModel = viewModel()

                MainScreen(
                    authToken = token,
                    vpnViewModel = viewModel,
                    onLogout = {
                        authToken = null
                        navController.navigate("login") {
                            popUpTo("main") { inclusive = true }
                        }
                    },
                    onNavigateToTwoFactorSettings = {
                        navController.navigate("twoFactorSettings")
                    }
                )
            } ?: run {
                // If token is null, navigate back to login
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo("main") { inclusive = true }
                    }
                }
            }
        }

        composable("twoFactorSettings") {
            authToken?.let { token ->
                TwoFactorSettingsScreen(
                    authToken = token,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}