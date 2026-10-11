package net.libreguard.vpn

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import net.libreguard.vpn.ui.screens.LoginScreen
import net.libreguard.vpn.ui.screens.MainScreen
import net.libreguard.vpn.ui.screens.OpenSourceLicensesScreen
import net.libreguard.vpn.ui.screens.SettingsScreen
import net.libreguard.vpn.ui.screens.TwoFactorSettingsScreen
import net.libreguard.vpn.viewmodel.ClientLoginViewModel
import net.libreguard.vpn.viewmodel.NewsletterViewModel
import net.libreguard.vpn.ui.components.NewsletterConsentHost
import net.libreguard.vpn.ui.components.GoogleCredentialActivityHost
import net.libreguard.vpn.ui.screens.RegisterScreen
import net.libreguard.vpn.ui.screens.ConfirmEmailScreen
import net.libreguard.vpn.ui.screens.PrivacyPolicyScreen
import net.libreguard.vpn.ui.screens.TermsOfServiceScreen
import net.libreguard.vpn.ui.screens.CardPaymentScreen
import net.libreguard.vpn.ui.screens.MoneroPaymentScreen
import net.libreguard.vpn.ui.screens.GooglePlayPaymentScreen
import net.libreguard.vpn.ui.screens.IntegrityBlockScreen
import net.libreguard.vpn.ui.screens.DeviceManagementScreen
import net.libreguard.vpn.ui.screens.ForgotPasswordScreen
import net.libreguard.vpn.ui.screens.ResetPasswordScreen
import net.libreguard.vpn.security.AppIntegrityChecker
import net.libreguard.vpn.ui.theme.ThemePreferences
import net.libreguard.vpn.ui.theme.ThemeMode
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.VpnViewModel
import net.libreguard.vpn.viewmodel.AppUpdateViewModel
import net.libreguard.vpn.ui.components.AppUpdateHost
import net.libreguard.vpn.ui.components.AppUpdateActions
import net.libreguard.vpn.util.TokenManager
import net.libreguard.vpn.util.TokenValidationManager
import net.libreguard.vpn.util.TokenValidationResult
import net.libreguard.vpn.util.restoreSavedSession
import net.libreguard.vpn.viewmodel.SubscriptionViewModel


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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    var pendingDeepLinkUri by mutableStateOf<Uri?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkUri = intent?.data
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val systemIsDark = isSystemInDarkTheme()
            var themeMode by rememberSaveable {
                mutableStateOf(ThemePreferences.getThemeMode(context))
            }
            val effectiveDarkMode = when (themeMode) {
                ThemeMode.SYSTEM -> systemIsDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LibreGuardVPNTheme(darkTheme = effectiveDarkMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        modifier = Modifier.padding(innerPadding),
                        themeMode = themeMode,
                        effectiveDarkMode = effectiveDarkMode,
                        onThemeModeChange = { selectedMode ->
                            themeMode = selectedMode
                            ThemePreferences.setThemeMode(context, selectedMode)
                            ThemePreferences.applyThemeMode(selectedMode)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkUri = intent.data
    }

    fun consumePendingDeepLinkUri() {
        pendingDeepLinkUri = null
        intent?.data = null
    }
}

@Composable
@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun AppNavigation(
    modifier: Modifier = Modifier,
    themeMode: ThemeMode,
    effectiveDarkMode: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val startupIntegrityReport = remember {
        AppIntegrityChecker.getLastStartupReport()
            ?: AppIntegrityChecker.runStartupChecks(context.applicationContext)
    }

    if (startupIntegrityReport.enforcementDecision.shouldBlock) {
        IntegrityBlockScreen(
            modifier = modifier,
            onCloseApp = { (context as? ComponentActivity)?.finishAffinity() }
        )
        return
    }

    val clientLoginViewModel: ClientLoginViewModel = viewModel(factory = ClientLoginViewModel.Factory(context))
    GoogleCredentialActivityHost(clientLoginViewModel.googleCredentials)
    val newsletterViewModel: NewsletterViewModel = viewModel(factory = NewsletterViewModel.Factory(context))
    var authToken by remember { mutableStateOf<String?>(null) }
    var isCheckingToken by remember { mutableStateOf(true) }
    LaunchedEffect(authToken, isCheckingToken) {
        if (!isCheckingToken) newsletterViewModel.bindSession(authToken != null)
    }
    var loginPrefilledEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var resetEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var resetToken by rememberSaveable { mutableStateOf<String?>(null) }

    // Forced logout reason shown on login screen (e.g., device limit exceeded)
    var forcedLogoutReasonJson by remember { mutableStateOf<String?>(null) }

    fun consumeForcedLogoutReasonFromPrefs() {
        try {
            val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
            val value = prefs.getString("pending_forced_logout_reason", null)
            if (!value.isNullOrBlank()) {
                forcedLogoutReasonJson = value
                prefs.edit().remove("pending_forced_logout_reason").apply()
                android.util.Log.d("MainActivity", "Consumed forced logout reason")
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    // SharedPreferences for persisting registration flow state across process death
    val regPrefs = remember { context.getSharedPreferences("registration_flow_prefs", android.content.Context.MODE_PRIVATE) }

    // Notification permission prompt state (ask once on Android 13+)
    val notifPrefs = remember { context.getSharedPreferences("notification_permission_prefs", Context.MODE_PRIVATE) }
    var notificationPermissionAsked by rememberSaveable { mutableStateOf(notifPrefs.getBoolean("asked_notification_permission", false)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPrefs.edit().putBoolean("asked_notification_permission", true).apply()
        notificationPermissionAsked = true
        if (!granted) {
            Toast.makeText(context, "Enable notifications to get VPN alerts", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(notificationPermissionAsked) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted && !notificationPermissionAsked) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
    val updateViewModel: AppUpdateViewModel = viewModel(factory = AppUpdateViewModel.Factory(context))
    val updateBackStackEntry by navController.currentBackStackEntryAsState()
    val updateState by updateViewModel.state.collectAsState()
    val updateActions = AppUpdateActions(
        onAction = updateViewModel::primaryAction,
        onCheck = updateViewModel::checkForUpdates,
        onRestart = updateViewModel::requestRestart,
        onDismiss = updateViewModel::dismissBanner,
        onOpenStore = updateViewModel::openStore
    )

    // Keep account-wide DNS eligibility/effectiveness current after returning to
    // the app. The ViewModel ignores this until an authenticated account exists.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vpnViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vpnViewModel.refreshDnsPreference()
                newsletterViewModel.refresh()
                vpnViewModel.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    net.libreguard.vpn.ui.components.VpnConsentHost(vpnViewModel)
    if (authToken != null && !isCheckingToken) NewsletterConsentHost(newsletterViewModel)

    // Coroutine scope for async logout operations
    val coroutineScope = rememberCoroutineScope()

    // Helper: perform full logout (API + Google + local state)
    // This runs sequentially to guarantee API call occurs after VPN disconnect
    suspend fun performLogoutSequential() {
        newsletterViewModel.reset()
        clientLoginViewModel.cancel()
        android.util.Log.d("MainActivity", "Starting logout process")
        try {
            LogoutManager.logout()
            android.util.Log.i("MainActivity", "Logout completed")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Logout failed")
            // Continue anyway - user is already navigating to login
        }

        // Clear in-memory auth token immediately for UI responsiveness
        clientLoginViewModel.cancel()
        authToken = null


    }

    suspend fun disconnectThenLogout(navigate: () -> Unit) {
        try {
            vpnViewModel.forceDisconnectVpn(context)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error disconnecting VPN on logout")
        }

        performLogoutSequential()
        navigate()
    }

    // Handle Logout Broadcast
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "net.libreguard.vpn.ACTION_LOGOUT") {
                    val revokedEpoch = intent.getLongExtra("account_session_epoch", -1L)
                    val activeSession = RetrofitClient.getTokenManager().sessionSnapshot()?.context
                    if (revokedEpoch >= 0L && activeSession != null && activeSession.epoch != revokedEpoch) return
                    android.util.Log.d("MainActivity", "Received ACTION_LOGOUT broadcast")
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
                    navController.navigate("payment/googleplay")
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

    // Handle auth-related deep links on cold start and when MainActivity receives new intents.
    val mainActivity = context as? MainActivity
    val pendingDeepLinkUri = mainActivity?.pendingDeepLinkUri
    LaunchedEffect(pendingDeepLinkUri?.toString()) {
        val uri = pendingDeepLinkUri ?: return@LaunchedEffect

        when {
            uri.scheme == "libreguardvpn" && uri.host == "email" && uri.path == "/confirmed" -> {
                val uid = uri.getQueryParameter("userId")
                val confirmToken = uri.getQueryParameter("token")
                android.util.Log.d("MainActivity", "Email confirmed deep link received")

                if (!uid.isNullOrBlank()) {
                    if (!confirmToken.isNullOrBlank()) {
                        regToken = confirmToken
                    }
                    regUserId = uid

                    if (regEmail.isNullOrBlank() || regPassword.isNullOrBlank()) {
                        android.util.Log.e("MainActivity", "Missing credentials for auto-login")
                    } else {
                        android.util.Log.d("MainActivity", "Credentials available for auto-login")
                    }

                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute != "confirmEmail") {
                        android.util.Log.d("MainActivity", "Deep link: Navigating to confirmEmail")
                        navController.navigate("confirmEmail") {
                            popUpTo("login") { inclusive = false }
                            launchSingleTop = true
                        }
                    } else {
                        android.util.Log.d("MainActivity", "Deep link: Already on confirmEmail")
                    }
                }
            }

            uri.scheme == "libreguardvpn" && uri.host == "account" && uri.path == "/reset-password" -> {
                resetToken = uri.getQueryParameter("code")
                resetEmail = uri.getQueryParameter("email")
                if (!resetEmail.isNullOrBlank()) {
                    loginPrefilledEmail = resetEmail
                }
                android.util.Log.d("MainActivity", "Password reset deep link received")
                navController.navigate("resetPassword") {
                    launchSingleTop = true
                }
            }
        }

        mainActivity?.consumePendingDeepLinkUri()
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
            clientLoginViewModel.cancel()
            authToken = null
            isCheckingToken = false
            return@LaunchedEffect
        }

        val tokenManager = RetrofitClient.getTokenManager()
        val restored = restoreSavedSession(
            readToken = tokenManager::getAccessToken,
            validate = { TokenValidationManager(context, tokenManager).validateTokenBeforeConnection() },
            onRevoked = {
                Log.w("MainActivity", "Saved session rejected - login required")
                tokenManager.clearTokens()
                context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
                    .edit().remove("auth_token").apply()
                vpnViewModel.clearCachedAuthToken()
                clientLoginViewModel.cancel()
                authToken = null
            },
            onFailure = { Log.w("MainActivity", "Session validation unavailable", it) }
        )
        if (restored.validation == TokenValidationResult.UNAVAILABLE) {
            Log.w("MainActivity", "Session validation unavailable - preserving saved sign-in")
            vpnViewModel.onSessionValidationUnavailable()
        }
        if (!restored.token.isNullOrBlank()) {
            authToken = restored.token
            navController.navigate("main") {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            if (restored.validation == TokenValidationResult.VALID) coroutineScope.launch {
                delay(3000)
                Log.d("MainActivity", "Attempting Auto-Connect...")
                val autoConnected = vpnViewModel.attemptAutoConnect()
                Log.d("MainActivity", if (autoConnected) "Auto-Connect initiated" else "Auto-Connect skipped")
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

    // Global back handler - intercept ALL back presses based on current route
    // Removed global handler in favor of per-screen handling to ensure correct interception order

    NavHost(
        navController = navController,
        startDestination = "login",
        modifier = modifier
    ) {
        composable("login") {
            if (authToken != null) {
                // Already authenticated - redirect immediately without rendering
                LaunchedEffect(Unit) {
                    navController.navigate("main") {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                // Show empty box while redirecting
                Box(modifier = Modifier.fillMaxSize())
            } else if (isCheckingToken) {
                // Show loading while checking token
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Not authenticated - show login screen
                LoginScreen(
                    forcedLogoutReasonJson = forcedLogoutReasonJson,
                    prefilledEmail = loginPrefilledEmail,
                    onDismissForcedLogoutReason = { forcedLogoutReasonJson = null },
                    onNavigateToUpgrade = {
                        navController.navigate("upgrade")
                    },
                    onLoginSuccess = { token ->
                        authToken = token
                        loginPrefilledEmail = null
                        resetEmail = null
                        resetToken = null
                        // Clear registration state on successful login
                        regUserId = null
                        regEmail = null
                        regToken = null
                        regPassword = null
                        regPrefs.edit().clear().apply()

                        navController.navigate("main") {
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    loginViewModel = clientLoginViewModel,
                    onNavigateToForgotPassword = {
                        navController.navigate("forgotPassword") {
                            launchSingleTop = true
                        }
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

        composable("forgotPassword") {
            ForgotPasswordScreen(
                initialEmail = loginPrefilledEmail.orEmpty(),
                onBackToLogin = {
                    navController.popBackStack()
                }
            )
        }

        composable("resetPassword") {
            ResetPasswordScreen(
                initialEmail = resetEmail,
                initialToken = resetToken,
                onBackToLogin = {
                    resetEmail = null
                    resetToken = null
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onResetSuccess = { email ->
                    loginPrefilledEmail = email
                    resetEmail = null
                    resetToken = null
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("register") {
            if (authToken != null) {
                // Already authenticated - redirect immediately without rendering
                LaunchedEffect(Unit) {
                    navController.navigate("main") {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                // Show empty box while redirecting
                Box(modifier = Modifier.fillMaxSize())
            } else {
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
        }

        composable("confirmEmail") {
            if (authToken != null) {
                // Already authenticated - redirect immediately without rendering
                LaunchedEffect(Unit) {
                    // Clear registration state
                    regUserId = null
                    regEmail = null
                    regToken = null
                    regPassword = null
                    regPrefs.edit().clear().apply()

                    navController.navigate("main") {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                // Show empty box while redirecting
                Box(modifier = Modifier.fillMaxSize())
            } else {
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
                            android.util.Log.d("MainActivity", "Email confirmation session accepted")
                            // Persist full auth response like LoginScreen does
                            val tokenManager = TokenManager(context)
                            if (authResponse.token != null) {
                                tokenManager.beginSession(authResponse.token, authResponse.refreshToken ?: "")
                                authResponse.deviceId?.let { tokenManager.saveDeviceId(it) }
                                tokenManager.saveCurrentDeviceKeyId()
                                if (authResponse.activeDevices != null && authResponse.maxDevices != null) {
                                    tokenManager.saveDeviceMetadata(authResponse.activeDevices, authResponse.maxDevices)
                                }
                                authToken = authResponse.token
                                android.util.Log.d("MainActivity", "Token saved and authToken set, navigating to main")

                                // Clear registration state BEFORE navigation
                                regUserId = null
                                regEmail = null
                                regToken = null
                                regPassword = null
                                regPrefs.edit().clear().apply()

                                // Navigate with complete back stack clearing
                                navController.navigate("main") {
                                    popUpTo(0) { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                android.util.Log.e("MainActivity", "onConfirmed called but token is null")
                            }
                        },
                        onBackToLogin = {
                            // Clear registration flow state
                            regUserId = null
                            regEmail = null
                            regToken = null
                            regPassword = null
                            regPrefs.edit().clear().apply()

                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
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
                            regUserId = null
                            regEmail = null
                            regToken = null
                            regPassword = null
                            regPrefs.edit().clear().apply()

                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        // Already logged in (authToken is set), just show nothing
                        // This prevents redirect loop when state is cleared after successful login
                        android.util.Log.d("MainActivity", "confirmEmail: Email is missing but already logged in (authToken present), doing nothing")
                    }
                }
            }
        }

        // Old saved navigation entries restart authentication without restoring capabilities.
        composable("twoFactor") {
            LaunchedEffect(Unit) { navController.navigate("login") { popUpTo(0); launchSingleTop = true } }
        }
        composable("main") {
            authToken?.let { token ->
                // Force BackHandler here inside the composable
                // This ensures it registers AFTER NavHost's internal handler and actually intercepts the event
                BackHandler(enabled = true) {
                    // Exit app when pressing back on main screen
                    (context as? ComponentActivity)?.finish()
                }

                MainScreen(
                    authToken = token,
                    themeMode = themeMode,
                    effectiveDarkMode = effectiveDarkMode,
                    onThemeModeChange = onThemeModeChange,
                    vpnViewModel = vpnViewModel,
                    newsletterViewModel = newsletterViewModel,
                    updateState = updateState,
                    updateActions = updateActions,
                    onLogout = {
                        // Disconnect VPN first, then logout
                        coroutineScope.launch {
                            disconnectThenLogout {
                                navController.navigate("login") {
                                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    },
                    onNavigateToUpgrade = {
                        navController.navigate("payment/googleplay")
                    },
                    onNavigateToTwoFactor = {
                        navController.navigate("twoFactorSettings")
                    }
                )
            } ?: LaunchedEffect(Unit) {
                navController.navigate("login") {
                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        composable("settings") {
            authToken?.let { _ ->
                SettingsScreen(
                    newsletterViewModel = newsletterViewModel,
                    updateState = updateState,
                    updateActions = updateActions,
                    vpnViewModel = vpnViewModel,
                    themeMode = themeMode,
                    effectiveDarkMode = effectiveDarkMode,
                    onThemeModeChange = onThemeModeChange,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToTwoFactor = { navController.navigate("twoFactorSettings") },
                    onNavigateToUpgrade = { navController.navigate("payment/googleplay") },
                    onNavigateToOpenSource = { navController.navigate("openSourceLicenses") },
                    onNavigateToPrivacy = { navController.navigate("privacyPolicy") },
                    onNavigateToTerms = { navController.navigate("termsOfService") },
                    onLogout = {
                        // Disconnect VPN first, then logout
                        coroutineScope.launch {
                            disconnectThenLogout {
                                navController.navigate("login") {
                                    popUpTo(0) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                )
            } ?: LaunchedEffect(Unit) {
                navController.navigate("login") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        composable("twoFactorSettings") {
            authToken?.let { token ->
                TwoFactorSettingsScreen(
                    authToken = token,
                    onNavigateBack = { navController.popBackStack() }
                )
            } ?: LaunchedEffect(Unit) {
                navController.navigate("login") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        composable("deviceManagement") {
            if (authToken == null) {
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                Box(modifier = Modifier.fillMaxSize())
            } else {
                DeviceManagementScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onDeviceRemoved = { }
                )
            }
        }

        composable("payment/googleplay") {
            authToken?.let { token ->
                BackHandler(enabled = true) {
                    navController.popBackStack()
                }

                val subscriptionViewModel: SubscriptionViewModel = viewModel()

                // Connect billing and set auth on entry
                LaunchedEffect(token) {
                    subscriptionViewModel.setAuthToken(token)
                    subscriptionViewModel.billingManager.connect()
                }


                GooglePlayPaymentScreen(
                    billingManager = subscriptionViewModel.billingManager,
                    onClose = { navController.popBackStack() },
                    onSuccess = {
                        vpnViewModel.updateSubscriptionStatus()
                        Toast.makeText(context, "Pro upgrade successful!", Toast.LENGTH_LONG).show()
                        navController.navigate("main") {
                            popUpTo("main") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            } ?: run {
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
                Box(modifier = Modifier.fillMaxSize())
            }
        }

        composable("privacyPolicy") {
            PrivacyPolicyScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("openSourceLicenses") {
            OpenSourceLicensesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("termsOfService") {
            TermsOfServiceScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
    AppUpdateHost(
        viewModel = updateViewModel,
        isVpnActive = { vpnViewModel.isConnected.value || vpnViewModel.isConnecting.value },
        hasBottomNavigation = updateBackStackEntry?.destination?.route == "main",
        modifier = modifier
    )
}
