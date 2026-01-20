package net.libreguard.vpn

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
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
import net.libreguard.vpn.ui.screens.DeviceManagementScreen
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.VpnViewModel
import net.libreguard.vpn.util.TokenManager
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import org.json.JSONObject

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
@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var authToken by remember { mutableStateOf<String?>(null) }
    var isCheckingToken by remember { mutableStateOf(true) }
    var pendingEmail by remember { mutableStateOf<String?>(null) }

    // Forced logout reason shown on login screen (e.g., device limit exceeded)
    var forcedLogoutReasonJson by remember { mutableStateOf<String?>(null) }

    fun consumeForcedLogoutReasonFromPrefs() {
        try {
            val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
            val value = prefs.getString("pending_forced_logout_reason", null)
            if (!value.isNullOrBlank()) {
                forcedLogoutReasonJson = value
                prefs.edit().remove("pending_forced_logout_reason").apply()
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    // SharedPreferences for persisting registration flow state across process death
    val regPrefs = remember { context.getSharedPreferences("registration_flow_prefs", android.content.Context.MODE_PRIVATE) }

    // Load initial values from SharedPreferences (handles process death)
    val initialRegEmail = remember { regPrefs.getString("reg_email", null) }
    val initialRegPassword = remember { regPrefs.getString("reg_password", null) }
    val initialRegUserId = remember { regPrefs.getString("reg_user_id", null) }
    val initialRegToken = remember { regPrefs.getString("reg_token", null) }

    // New state for registration confirmation flow
    // Using rememberSaveable to persist across Activity recreation (e.g., when user returns from browser)
    var regUserId by rememberSaveable { mutableStateOf(initialRegUserId) }
    var regEmail by rememberSaveable { mutableStateOf(initialRegEmail) }
    var regPassword by rememberSaveable { mutableStateOf(initialRegPassword) }
    var regToken by rememberSaveable { mutableStateOf(initialRegToken) }

    // Persist registration flow state to SharedPreferences whenever it changes
    LaunchedEffect(regEmail, regPassword, regUserId, regToken) {
        regPrefs.edit()
            .putString("reg_email", regEmail)
            .putString("reg_password", regPassword)
            .putString("reg_user_id", regUserId)
            .putString("reg_token", regToken)
            .apply()
    }

    // ViewModel reference for VPN disconnect on logout - shared across all composables
    val vpnViewModel: VpnViewModel = viewModel()

    // Launcher for the system VPN consent dialog (VpnService.prepare intent)
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // RESULT_OK means permission granted; RESULT_CANCELED means user denied/backed out
        vpnViewModel.onVpnPermissionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

    // Coroutine scope for async logout operations
    val coroutineScope = rememberCoroutineScope()

    // Helper: perform full logout (API + Google + local state)
    // This runs sequentially to guarantee API call occurs after VPN disconnect
    suspend fun performLogoutSequential() {
        Log.d("MainActivity", "Starting logout via LogoutManager")
        try {
            LogoutManager.logout()
            Log.i("MainActivity", "Logout completed successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "LogoutManager.logout() failed: ${e.message}", e)
            // Continue anyway - user is already navigating to login
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

    suspend fun disconnectThenLogout(navigate: () -> Unit) {
        try {
            vpnViewModel.forceDisconnectVpn(context)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error disconnecting VPN on logout: ${e.message}")
        }

        performLogoutSequential()
        navigate()
    }

    // Handle Logout Broadcast
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "net.libreguard.vpn.ACTION_LOGOUT") {
                    // Capture logout reason (if any) before we navigate back to login
                    consumeForcedLogoutReasonFromPrefs()

                    // CRITICAL: Disconnect VPN first before clearing tokens
                    coroutineScope.launch {
                        disconnectThenLogout {
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
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
            context.registerReceiver(receiver, filter)
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
            context.registerReceiver(upgradeReceiver, upgradeFilter)
        }
        onDispose {
            context.unregisterReceiver(upgradeReceiver)
        }
    }

    // State for device limit exceeded dialog
    var pendingDeviceLimitPayload by remember { mutableStateOf<String?>(null) }

    // Handle Device Limit Exceeded Broadcast - shows device picker WITHOUT logging out
    DisposableEffect(Unit) {
        val deviceLimitReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "net.libreguard.vpn.ACTION_DEVICE_LIMIT_EXCEEDED") {
                    android.util.Log.d("MainActivity", "Received ACTION_DEVICE_LIMIT_EXCEEDED broadcast")
                    val payload = intent.getStringExtra("payload")
                    if (!payload.isNullOrBlank()) {
                        android.util.Log.d("MainActivity", "Device limit payload received, navigating to login for device management")
                        // Store payload in prefs for LoginScreen to pick up
                        context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("pending_device_limit_exceeded", payload)
                            .apply()
                        // Navigate to login screen with device limit flag
                        // The token is still valid, user just needs to remove a device
                        pendingDeviceLimitPayload = payload
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
        }
        val deviceLimitFilter = IntentFilter("net.libreguard.vpn.ACTION_DEVICE_LIMIT_EXCEEDED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(deviceLimitReceiver, deviceLimitFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(deviceLimitReceiver, deviceLimitFilter)
        }
        onDispose {
            context.unregisterReceiver(deviceLimitReceiver)
        }
    }

    // Handle deep links that bring the app to foreground after email confirmation
    LaunchedEffect(Unit) {
        val data: Uri? = (context as? MainActivity)?.intent?.data
        data?.let { uri ->
            // We expect libreguardvpn://email/confirmed?userId=...&token=...
            // The token here is a CONFIRMATION token, NOT an auth token
            // We must go through proper login to get an auth token with device_id claim
            if (uri.scheme == "libreguardvpn" && uri.host == "email" && uri.path == "/confirmed") {
                val uid = uri.getQueryParameter("userId")
                val confirmToken = uri.getQueryParameter("token")
                android.util.Log.d("MainActivity", "Deep link received: userId=$uid, hasToken=${!confirmToken.isNullOrBlank()}")
                android.util.Log.d("MainActivity", "Current registration state: regEmail=${regEmail}, regPassword=${if (!regPassword.isNullOrBlank()) "[set]" else "[EMPTY]"}, regUserId=$regUserId, regToken=${regToken?.take(10)}")

                // IMPORTANT: Do NOT use the token directly - it's a confirmation token, not an auth token
                // The auth token needs to be obtained via /api/login with DeviceId to get device_id claim
                if (!uid.isNullOrBlank()) {
                    // Store the confirmation token if provided, so ConfirmEmailScreen can use it
                    if (!confirmToken.isNullOrBlank()) {
                        regToken = confirmToken
                    }
                    regUserId = uid

                    // Verify we have email and password for auto-login
                    if (regEmail.isNullOrBlank() || regPassword.isNullOrBlank()) {
                        android.util.Log.e("MainActivity", "CRITICAL: Missing email or password for auto-login! email=${regEmail.isNullOrBlank()}, password=${regPassword.isNullOrBlank()}")
                        android.util.Log.e("MainActivity", "User will need to login manually because credentials are not cached")
                    } else {
                        android.util.Log.d("MainActivity", "Credentials available for auto-login: email=$regEmail")
                    }

                    // Navigate to confirmEmail screen which will handle the proper login flow
                    navController.navigate("confirmEmail")
                }
            }
        }
    }

    // Check for persisted auth token on startup
    LaunchedEffect(Unit) {
        // Skip auto-navigation if we're in the middle of email confirmation flow
        // The user should complete the confirmation process to get a valid token
        if (!regEmail.isNullOrBlank()) {
            android.util.Log.d("MainActivity", "Skipping auto-navigation: email confirmation in progress for $regEmail")
            // Clear any old tokens that might be invalid - from TokenManager
            RetrofitClient.getTokenManager().clearTokens()
            // Clear from SharedPreferences
            context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("auth_token")
                .apply()
            // CRITICAL: Also clear the cached token from VpnViewModel
            // Otherwise it will use the old token loaded during init
            vpnViewModel.clearCachedAuthToken()
            // Clear the local authToken variable too
            authToken = null
            isCheckingToken = false
            return@LaunchedEffect
        }

        val tokenManager = RetrofitClient.getTokenManager()
        var savedToken = tokenManager.getAccessToken()

        if (!savedToken.isNullOrBlank()) {
            // CRITICAL FIX: Check if token is expired and attempt REFRESH first before clearing
            // This ensures users stay logged in even after app was closed for hours
            if (tokenManager.isTokenExpired() || tokenManager.isTokenExpiringWithin(300)) {
                Log.d("MainActivity", "Token is expired or expiring soon - attempting proactive refresh...")

                // Check if refresh token is available and not expired
                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    Log.w("MainActivity", "No refresh token available - must re-login")
                    tokenManager.clearTokens()
                    context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("auth_token")
                        .apply()
                    vpnViewModel.clearCachedAuthToken()
                    authToken = null
                    isCheckingToken = false
                    return@LaunchedEffect
                }

                // Check if refresh token is expired (for JWT-format refresh tokens)
                if (tokenManager.isRefreshTokenExpired()) {
                    Log.w("MainActivity", "Refresh token is also expired - must re-login")
                    tokenManager.clearTokens()
                    context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("auth_token")
                        .apply()
                    vpnViewModel.clearCachedAuthToken()
                    authToken = null
                    isCheckingToken = false
                    return@LaunchedEffect
                }

                // Attempt to refresh the token BEFORE any API calls
                try {
                    val refreshSuccess = tokenManager.refreshTokenIfNeeded(RetrofitClient.authApiService)
                    if (refreshSuccess) {
                        Log.d("MainActivity", "Token refresh successful on startup")
                        // Update savedToken with the new refreshed token
                        savedToken = tokenManager.getAccessToken()
                    } else {
                        Log.w("MainActivity", "Token refresh failed on startup - must re-login")
                        tokenManager.clearTokens()
                        context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .remove("auth_token")
                            .apply()
                        vpnViewModel.clearCachedAuthToken()
                        authToken = null
                        isCheckingToken = false
                        return@LaunchedEffect
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Token refresh exception on startup: ${e.message}")
                    tokenManager.clearTokens()
                    context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("auth_token")
                        .apply()
                    vpnViewModel.clearCachedAuthToken()
                    authToken = null
                    isCheckingToken = false
                    return@LaunchedEffect
                }
            }

            // Token is valid (either was already valid or we just refreshed it)
            // Now validate with server to ensure it's not revoked
            try {
                Log.d("MainActivity", "Validating token with server before auto-login...")
                val currentToken = savedToken ?: tokenManager.getAccessToken()
                if (currentToken.isNullOrBlank()) {
                    Log.w("MainActivity", "No token after refresh attempt")
                    isCheckingToken = false
                    return@LaunchedEffect
                }

                val response = RetrofitClient.instance.checkTokenValidity("Bearer $currentToken")

                if (!response.isSuccessful || response.body()?.isValid != true) {
                    Log.w("MainActivity", "Token validation failed: ${response.code()} - clearing token")
                    tokenManager.clearTokens()
                    context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .remove("auth_token")
                        .apply()
                    vpnViewModel.clearCachedAuthToken()
                    authToken = null
                    isCheckingToken = false
                    return@LaunchedEffect
                }

                Log.d("MainActivity", "Token validation successful - proceeding with auto-login")
            } catch (e: Exception) {
                // Network error during validation - allow proceeding but log warning
                Log.w("MainActivity", "Token validation network error: ${e.message} - proceeding anyway")
            }

            authToken = savedToken ?: tokenManager.getAccessToken()

            // Navigate to main if we have a valid token
            navController.navigate("main") {
                popUpTo("login") { inclusive = true }
            }

            // IMPORTANT: Trigger auto-connect AFTER navigation and delay
            // This ensures the UI is ready and ViewModel is properly initialized
            coroutineScope.launch {
                // Wait longer to ensure:
                // 1. UI composables are fully initialized
                // 2. ViewModel state observers are active
                // 3. Servers are loaded
                delay(3000) // 3 seconds for full UI initialization

                Log.d("MainActivity", "Attempting Auto-Connect...")

                // Attempt auto-connect
                val autoConnected = vpnViewModel.attemptAutoConnect()
                if (autoConnected) {
                    Log.d("MainActivity", "Auto-Connect initiated successfully - UI should reflect connection status")
                } else {
                    Log.d("MainActivity", "Auto-Connect skipped (disabled, already connected, or failed validation)")
                }
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
                    forcedLogoutReasonJson = forcedLogoutReasonJson,
                    onDismissForcedLogoutReason = { forcedLogoutReasonJson = null },
                    onNavigateToUpgrade = {
                        navController.navigate("upgrade")
                    },
                    onNavigateToDeviceManagement = {
                        navController.navigate("deviceManagement")
                    },
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
                        // Preserve email for email verification flow - do NOT clear state
                        // State will be cleared only on successful login or explicit "Back to Login"
                        regEmail = email
                        navController.navigate("confirmEmail")
                    }
                )
            }
        }

        composable("register") {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onRegistrationNeedsConfirmation = { userId, email, password, token ->
                    regUserId = userId
                    regEmail = email
                    regPassword = password
                    regToken = token
                    navController.navigate("confirmEmail")
                }
            )
        }

        composable("confirmEmail") {
            val uid = regUserId
            val emailParam = regEmail
            val tokenParam = regToken
            val passwordParam = regPassword

            android.util.Log.d("MainActivity", "confirmEmail composable: uid=$uid, email=$emailParam, password=${if (!passwordParam.isNullOrBlank()) "[set]" else "[EMPTY]"}, token=${tokenParam?.take(10)}")

            if (!emailParam.isNullOrBlank()) {
                val emailNonNull: String = emailParam
                android.util.Log.d("MainActivity", "Rendering ConfirmEmailScreen with email=$emailNonNull, hasPassword=${!passwordParam.isNullOrBlank()}")
                ConfirmEmailScreen(
                    userId = uid ?: "",
                    email = emailNonNull,
                    password = passwordParam ?: "",
                    initialToken = tokenParam,
                    onConfirmed = { authResponse ->
                        android.util.Log.d("MainActivity", "onConfirmed called with token=${authResponse.token?.take(30)}...")
                        // Persist full auth response like LoginScreen does
                        val tokenManager = TokenManager(context)
                        if (authResponse.token != null) {
                            tokenManager.saveTokens(authResponse.token, authResponse.refreshToken ?: "")
                            authResponse.deviceId?.let { tokenManager.saveDeviceId(it) }
                            if (authResponse.activeDevices != null && authResponse.maxDevices != null) {
                                tokenManager.saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                            }
                            authToken = authResponse.token
                            android.util.Log.d("MainActivity", "Token saved and authToken set, navigating to main")

                            // Navigate and clear state only after successful login
                            navController.navigate("main") {
                                popUpTo("login") { inclusive = true }
                            }
                            // Clear registration state only after successful navigation
                            regUserId = null; regEmail = null; regToken = null; regPassword = null
                        } else {
                            android.util.Log.e("MainActivity", "onConfirmed called but token is null")
                        }
                    },
                    onBackToLogin = {
                        // Clear registration flow state
                        regUserId = null; regEmail = null; regToken = null; regPassword = null
                        navController.navigate("login") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                )
            } else {
                // Email is missing - can't do auto-login without credentials
                // But only redirect if we haven't successfully logged in yet
                if (authToken == null) {
                    // This can happen if app was killed and credentials weren't persisted
                    android.util.Log.e("MainActivity", "confirmEmail: Email is missing and not logged in! Redirecting to login.")
                    LaunchedEffect(Unit) {
                        regUserId = null; regEmail = null; regToken = null; regPassword = null
                        navController.navigate("login") {
                            popUpTo("confirmEmail") { inclusive = true }
                        }
                    }
                } else {
                    // Already logged in (authToken is set), just show nothing
                    // This prevents redirect loop when state is cleared after successful login
                    android.util.Log.d("MainActivity", "confirmEmail: Email is missing but already logged in (authToken present), doing nothing")
                }
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
                            disconnectThenLogout {
                                navController.navigate("login") {
                                    popUpTo("main") { inclusive = true }
                                }
                            }
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    },
                    onNavigateToUpgrade = {
                        navController.navigate("upgrade")
                    },
                    onNavigateToTwoFactor = {
                        android.util.Log.d("MainActivity", "onNavigateToTwoFactor (MainScreen) called, authToken is ${if (authToken == null) "NULL" else "present"}")
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

        composable("settings") {
            authToken?.let { token ->
                SettingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToTwoFactor = {
                        android.util.Log.d("MainActivity", "onNavigateToTwoFactor called, authToken is ${if (authToken == null) "NULL" else "present"}")
                        navController.navigate("twoFactorSettings")
                    },
                    onNavigateToUpgrade = {
                        navController.navigate("upgrade")
                    },
                    onLogout = {
                        // Disconnect VPN first, then logout
                        coroutineScope.launch {
                            disconnectThenLogout {
                                navController.navigate("login") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    }
                )
            }
        }

        composable("twoFactorSettings") {
            android.util.Log.d("MainActivity", "twoFactorSettings composable called, authToken is ${if (authToken == null) "NULL" else "present"}")
            authToken?.let { token ->
                android.util.Log.d("MainActivity", "Rendering TwoFactorSettingsScreen with token: ${token.take(20)}...")
                TwoFactorSettingsScreen(
                    authToken = token,
                    onNavigateBack = {
                        android.util.Log.d("MainActivity", "TwoFactorSettings onNavigateBack called")
                        navController.popBackStack()
                    }
                )
            } ?: run {
                // If token is null, this shouldn't happen, but log it
                android.util.Log.e("MainActivity", "twoFactorSettings: authToken is NULL! Redirecting to login")
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo("twoFactorSettings") { inclusive = true }
                    }
                }
            }
        }

        // ===== SUBSCRIPTION ROUTES =====
        composable("upgrade") {
            // Allow access to upgrade even when logged out (so forced-logout flows can upgrade).
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

        composable("deviceManagement") {
            // Device management screen for handling device limit scenarios
            Log.d("MainActivity", "========== NAVIGATED TO DEVICE MANAGEMENT ==========")
            Log.d("MainActivity", "Auth token: ${if (authToken.isNullOrBlank()) "NULL/BLANK" else "EXISTS"}")

            DeviceManagementScreen(
                onNavigateBack = {
                    Log.d("MainActivity", "Device management: Back pressed")
                    navController.popBackStack()
                },
                onDeviceRemoved = {
                    Log.d("MainActivity", "Device management: Device removed callback")
                    // Optionally navigate back to login after successful device removal
                    // User can then retry login
                }
            )
        }

        composable("payment/card") {
            authToken?.let { token ->
                Log.d("PaymentCard", "payment/card route composable called with token: ${token.take(20)}...")

                // Create stable viewModel instance that persists across recompositions
                val subscriptionViewModel: SubscriptionViewModel = viewModel()
                val checkoutUrl by subscriptionViewModel.checkoutUrl.collectAsState()
                val isLoading by subscriptionViewModel.isLoading.collectAsState()
                val errorMessage by subscriptionViewModel.errorMessage.collectAsState()
                // Observe payment verification result
                val paymentVerificationResult by subscriptionViewModel.paymentVerificationResult.collectAsState()
                // Observe subscription status for polling success
                val isPro by subscriptionViewModel.isPro.collectAsState()

                Log.d("PaymentCard", "Current checkoutUrl state: ${checkoutUrl?.take(50) ?: "null"}")
                Log.d("PaymentCard", "isLoading: $isLoading, errorMessage: $errorMessage, paymentVerified: $paymentVerificationResult, isPro: $isPro")

                // Start polling for subscription status
                DisposableEffect(Unit) {
                    subscriptionViewModel.startPollingSubscriptionStatus()
                    onDispose {
                        subscriptionViewModel.stopPollingSubscriptionStatus()
                    }
                }

                // Handle payment verification success or polling success
                LaunchedEffect(paymentVerificationResult, isPro) {
                    if (paymentVerificationResult == true || isPro) {
                        Log.d("PaymentCard", "Payment verified or Pro status detected! Navigating to main.")
                        vpnViewModel.updateSubscriptionStatus()
                        Toast.makeText(context, "Pro upgrade successful", Toast.LENGTH_LONG).show()
                        navController.navigate("main") {
                            popUpTo("upgrade") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

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
                    },
                    onCheckPayment = { orderId ->
                        Log.d("PaymentCard", "Order ID detected: $orderId - verifying...")
                        subscriptionViewModel.verifyPayment(orderId)
                    },
                    onSuccess = {
                        Log.d("PaymentCard", "Success URL detected, forcing immediate subscription update")
                        subscriptionViewModel.subscriptionUpdated()
                    }
                )
            } ?: run {
                // If logged out, redirect to login; payment requires auth.
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo("payment/card") { inclusive = true }
                    }
                }
            }
        }

        composable("payment/monero") {
            authToken?.let { token ->
                val subscriptionViewModel: SubscriptionViewModel = viewModel()
                val moneroInvoice by subscriptionViewModel.moneroInvoice.collectAsState()
                val moneroPaymentStatus by subscriptionViewModel.moneroPaymentStatus.collectAsState()
                val moneroPrice by subscriptionViewModel.moneroPrice.collectAsState()
                val isLoading by subscriptionViewModel.isLoading.collectAsState()
                val hoursRemaining by subscriptionViewModel.hoursRemaining.collectAsState()
                val minutesRemaining by subscriptionViewModel.minutesRemaining.collectAsState()
                val secondsRemaining by subscriptionViewModel.secondsRemaining.collectAsState()

                // Initialize Monero payment flow
                LaunchedEffect(Unit) {
                    subscriptionViewModel.setAuthToken(token)
                    // Fetch price first
                    subscriptionViewModel.fetchMoneroPrice()
                    // Try to fetch latest pending invoice, or create new one if none exists
                    subscriptionViewModel.fetchLatestMoneroInvoice()
                }

                // Display the payment screen once we have invoice data AND price data
                if (moneroInvoice != null && moneroPrice != null) {
                    val invoice = moneroInvoice!!
                    val price = moneroPrice!!
                    val status = moneroPaymentStatus

                    MoneroPaymentScreen(
                        paymentAddress = invoice.paymentAddress,
                        xmrAmount = invoice.amount,
                        usdAmount = invoice.amount * price.xmrPriceUsd,
                        xmrPrice = price.xmrPriceUsd,
                        confirmations = status?.confirmations ?: 0,
                        requiredConfirmations = status?.requiredConfirmations ?: 10,
                        isLoading = isLoading,
                        isWaitingForPayment = status?.status == "Pending",
                        hoursRemaining = hoursRemaining,
                        minutesRemaining = minutesRemaining,
                        secondsRemaining = secondsRemaining,
                        onClose = {
                            subscriptionViewModel.stopMoneroPolling()
                            navController.popBackStack()
                        },
                        onRefresh = {
                            subscriptionViewModel.checkMoneroPaymentStatus(invoice.invoiceId)
                        },
                        onSuccess = {
                            // CRITICAL FIX: After confirmed payment, refresh status and return to dashboard
                            vpnViewModel.updateSubscriptionStatus()
                            subscriptionViewModel.stopMoneroPolling()
                            Toast.makeText(context, "Pro upgrade successful", Toast.LENGTH_LONG).show()
                            navController.navigate("main") {
                                popUpTo("upgrade") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                } else {
                    // Show loading while fetching price and creating/loading invoice
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } ?: run {
                // If logged out, redirect to login; payment requires auth.
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo("payment/monero") { inclusive = true }
                    }
                }
            }
        }
    }
}
