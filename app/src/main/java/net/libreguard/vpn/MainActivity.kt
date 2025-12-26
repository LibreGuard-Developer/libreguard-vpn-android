package net.libreguard.vpn

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
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
import net.libreguard.vpn.ui.screens.UpgradeScreen
import net.libreguard.vpn.ui.screens.CardPaymentScreen
import net.libreguard.vpn.ui.screens.MoneroPaymentScreen
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.VpnViewModel
import net.libreguard.vpn.viewmodel.SubscriptionViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.util.LogoutManager

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

    // ViewModel reference for VPN disconnect on logout - shared across all composables
    val vpnViewModel: VpnViewModel = viewModel()

    // Coroutine scope for async logout operations
    val coroutineScope = rememberCoroutineScope()

    // Helper: perform full logout (API + Google + local state)
    // This runs in the calling coroutine scope (non-blocking, best-effort)
    fun performLogout() {
        // Call LogoutManager in background (non-blocking, best-effort)
        coroutineScope.launch {
            Log.d("MainActivity", "Starting async logout via LogoutManager")
            try {
                LogoutManager.logout()
                Log.i("MainActivity", "Logout completed successfully")
            } catch (e: Exception) {
                Log.e("MainActivity", "LogoutManager.logout() failed: ${e.message}", e)
                // Continue anyway - user is already navigating to login
            }
        }

        // Clear in-memory auth token immediately for UI responsiveness
        authToken = null

        // Google Sign-Out (best-effort)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.google_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso).signOut()
    }

    // Handle Logout Broadcast
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "net.libreguard.vpn.ACTION_LOGOUT") {
                    // CRITICAL: Disconnect VPN first before clearing tokens
                    coroutineScope.launch {
                        try {
                            vpnViewModel.forceDisconnectVpn(context)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error disconnecting VPN on logout: ${e.message}")
                        }
                        // Then perform logout
                        performLogout()
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("net.libreguard.vpn.ACTION_LOGOUT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Use the not-exported flag as well on older SDKs to satisfy lint/security checks
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Handle Upgrade Required Broadcast
    DisposableEffect(Unit) {
        val upgradeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "net.libreguard.vpn.ACTION_SHOW_UPGRADE") {
                    android.util.Log.d("MainActivity", "Received ACTION_SHOW_UPGRADE broadcast - navigating to upgrade screen")
                    // navigate to upgrade screen on main thread
                    navController.navigate("upgrade")
                }
            }
        }
        val upgradeFilter = IntentFilter("net.libreguard.vpn.ACTION_SHOW_UPGRADE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(upgradeReceiver, upgradeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(upgradeReceiver, upgradeFilter, Context.RECEIVER_NOT_EXPORTED)
        }
        onDispose {
            context.unregisterReceiver(upgradeReceiver)
        }
    }

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
                    RetrofitClient.getTokenManager().saveTokens(token, "") // No refresh token from deep link usually
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
        val savedToken = RetrofitClient.getTokenManager().getAccessToken()

        if (!savedToken.isNullOrBlank()) {
            authToken = savedToken
            // Navigate to main if we have a valid token
            navController.navigate("main") {
                popUpTo("login") { inclusive = true }
            }
        }
        isCheckingToken = false

        // Flush any pending upgrade payload persisted by AuthInterceptor (if app was backgrounded when 403 happened)
        try {
            val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
            val pending = prefs.getString("pending_upgrade_payload", null)
            if (!pending.isNullOrBlank()) {
                android.util.Log.d("MainActivity", "Found pending upgrade payload; navigating to upgrade")
                prefs.edit().remove("pending_upgrade_payload").apply()
                navController.navigate("upgrade")
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to flush pending upgrade payload: ${e.message}")
        }
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
                        // Token is already saved in LoginScreen
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
                MainScreen(
                    authToken = token,
                    vpnViewModel = vpnViewModel,
                    onLogout = {
                        // Disconnect VPN first, then logout
                        coroutineScope.launch {
                            try {
                                vpnViewModel.forceDisconnectVpn(context)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error disconnecting VPN on manual logout: ${e.message}")
                            }
                            performLogout()
                            navController.navigate("login") {
                                popUpTo("main") { inclusive = true }
                            }
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    },
                    onNavigateToUpgrade = {
                        navController.navigate("upgrade")
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
                    onNavigateToUpgrade = {
                        navController.navigate("upgrade")
                    },
                    onLogout = {
                        // Disconnect VPN first, then logout
                        coroutineScope.launch {
                            try {
                                vpnViewModel.forceDisconnectVpn(context)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error disconnecting VPN on settings logout: ${e.message}")
                            }
                            performLogout()
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
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

        // ===== SUBSCRIPTION ROUTES =====
        composable("upgrade") {
            authToken?.let { token ->
                UpgradeScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onChooseCard = {
                        navController.navigate("payment/card")
                    },
                    onChooseMonero = {
                        navController.navigate("payment/monero")
                    }
                )
            }
        }

        composable("payment/card") {
            authToken?.let { token ->
                Log.d("PaymentCard", "payment/card route composable called with token: ${token.take(20)}...")

                // Create stable viewModel instance that persists across recompositions
                val subscriptionViewModel: SubscriptionViewModel = viewModel()
                val checkoutUrl by subscriptionViewModel.checkoutUrl.collectAsState()
                val isLoading by subscriptionViewModel.isLoading.collectAsState()
                val errorMessage by subscriptionViewModel.errorMessage.collectAsState()

                Log.d("PaymentCard", "Current checkoutUrl state: ${checkoutUrl?.take(50) ?: "null"}")
                Log.d("PaymentCard", "isLoading: $isLoading, errorMessage: $errorMessage")

                // Fetch checkout URL once when entering this screen
                LaunchedEffect(token) {
                    Log.d("PaymentCard", "LaunchedEffect triggered with token: ${token.take(20)}...")
                    Log.d("PaymentCard", "Setting auth token in ViewModel...")

                    subscriptionViewModel.setAuthToken(token)
                    Log.d("PaymentCard", "Auth token set. Calling fetchCheckoutUrl()...")

                    subscriptionViewModel.fetchCheckoutUrl()
                    Log.d("PaymentCard", "fetchCheckoutUrl() call completed, waiting for response...")
                }

                CardPaymentScreen(
                    checkoutUrl = checkoutUrl ?: "",
                    isLoading = isLoading,
                    onClose = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("payment/monero") {
            authToken?.let { token ->
                MoneroPaymentScreen(
                    paymentAddress = "87E7Qw1j6VKNjNj4mK1F5...",
                    xmrAmount = 0.0234,
                    usdAmount = 4.00,
                    xmrPrice = 170.85,
                    confirmations = 0,
                    requiredConfirmations = 10,
                    isLoading = false,
                    isWaitingForPayment = false,
                    hoursRemaining = 23,
                    minutesRemaining = 59,
                    onClose = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
