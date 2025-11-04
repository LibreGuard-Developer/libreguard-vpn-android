package net.libreguard.vpn

import android.net.Uri
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
import net.libreguard.vpn.ui.screens.LoginScreen
import net.libreguard.vpn.ui.screens.MainScreen
import net.libreguard.vpn.ui.screens.SettingsScreen
import net.libreguard.vpn.ui.screens.TwoFactorSettingsScreen
import net.libreguard.vpn.ui.screens.TwoFactorVerificationScreen
import net.libreguard.vpn.ui.screens.RegisterScreen
import net.libreguard.vpn.ui.screens.ConfirmEmailScreen
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.VpnViewModel

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibreGuardVPNTheme {
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

    // New state for registration confirmation flow
    var regUserId by remember { mutableStateOf<String?>(null) }
    var regEmail by remember { mutableStateOf<String?>(null) }
    var regToken by remember { mutableStateOf<String?>(null) }

    // Handle deep links that bring the app to foreground after email confirmation
    LaunchedEffect(Unit) {
        val data: Uri? = (context as? MainActivity)?.intent?.data
        data?.let { uri ->
            // We expect libreguardvpn://email/confirmed?userId=... optionally token
            if (uri.scheme == "libreguardvpn" && uri.host == "email" && uri.path == "/confirmed") {
                val uid = uri.getQueryParameter("userId")
                val token = uri.getQueryParameter("token")
                if (!token.isNullOrBlank()) {
                    // If token provided directly, persist and enter app
                    val sharedPrefs = context.getSharedPreferences("vpn_state_prefs", android.content.Context.MODE_PRIVATE)
                    sharedPrefs.edit().putString("auth_token", token).apply()
                    authToken = token
                    navController.navigate("main") { popUpTo("login") { inclusive = true } }
                } else if (!uid.isNullOrBlank()) {
                    // If only userId is present, navigate to confirmEmail and let polling finish
                    regUserId = uid
                    navController.navigate("confirmEmail")
                }
            }
        }
    }

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
                    },
                    onNavigateToRegister = {
                        navController.navigate("register")
                    },
                    onNavigateToEmailVerification = { email, _ ->
                        regEmail = email
                        navController.navigate("confirmEmail")
                    }
                )
            }
        }

        composable("register") {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onRegistrationNeedsConfirmation = { userId, email, token ->
                    regUserId = userId
                    regEmail = email
                    regToken = token
                    navController.navigate("confirmEmail")
                }
            )
        }

        composable("confirmEmail") {
            val uid = regUserId
            val emailParam = regEmail
            val tokenParam = regToken
            if (!emailParam.isNullOrBlank()) {
                val emailNonNull: String = emailParam
                ConfirmEmailScreen(
                    userId = uid ?: "",
                    email = emailNonNull,
                    initialToken = tokenParam,
                    onConfirmed = { token ->
                        // Persist token and go to main
                        val sharedPrefs = context.getSharedPreferences("vpn_state_prefs", android.content.Context.MODE_PRIVATE)
                        sharedPrefs.edit().putString("auth_token", token).apply()
                        authToken = token
                        regUserId = null; regEmail = null; regToken = null
                        navController.navigate("main") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onBackToLogin = {
                        navController.navigate("login") {
                            popUpTo("login") { inclusive = true }
                        }
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
                    onNavigateToSettings = {
                        navController.navigate("settings")
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

        composable("settings") {
            authToken?.let { token ->
                SettingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToTwoFactor = {
                        navController.navigate("twoFactorSettings")
                    },
                    onLogout = {
                        authToken = null
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
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
