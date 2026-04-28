package net.libreguard.vpn.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.gson.Gson
import kotlinx.coroutines.delay
import net.libreguard.vpn.util.DeviceKeyManager
import net.libreguard.vpn.network.AuthRequest
import net.libreguard.vpn.network.GoogleLoginRequest
import net.libreguard.vpn.network.PreAuthDeviceRemovalRequest
import net.libreguard.vpn.network.ResendConfirmationRequest
import net.libreguard.vpn.network.Verify2faRequest
import net.libreguard.vpn.network.VerifyRecoveryRequest
import org.json.JSONObject
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.DeviceIdManager
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.ui.components.LogoWithGradient
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import kotlin.reflect.KClass

/**
 * Login Screen - User authentication
 * Based on design from Login.tsx
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    forcedLogoutReasonJson: String? = null,
    onDismissForcedLogoutReason: () -> Unit = {},
    onNavigateToUpgrade: () -> Unit = {},
    onNavigateToDeviceManagement: () -> Unit = {},
    onLoginSuccess: (String) -> Unit,
    onRequires2FA: (String) -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToEmailVerification: (email: String, userId: String?) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var deviceLimitError by remember { mutableStateOf<DeviceLimitErrorResponse?>(null) }
    var showDevicePickerDialog by remember { mutableStateOf(false) }
    var isRemovingDevice by remember { mutableStateOf(false) }
    var showPasswordDialogForDeviceManagement by remember { mutableStateOf(false) }
    var passwordForDeviceManagement by remember { mutableStateOf("") }
    var emailForDeviceManagement by remember { mutableStateOf("") }
    var googleUserEmail by remember { mutableStateOf<String?>(null) }
    var lastKnownEmail by remember { mutableStateOf("") }
    var googleIdToken by remember { mutableStateOf<String?>(null) }
    var isFetchingDevicesForManagement by remember { mutableStateOf(false) }
    var fetchDevicesError by remember { mutableStateOf<String?>(null) }

    // Parse forced logout reason (best-effort)
    val forcedLogoutReasonParsed = remember(forcedLogoutReasonJson) {
        if (forcedLogoutReasonJson.isNullOrBlank()) {
            null
        } else {
            try {
                JSONObject(forcedLogoutReasonJson)
            } catch (_: Exception) {
                null
            }
        }
    }

    // Extract email from forced logout reason and set as lastKnownEmail AND pre-fill email field
    LaunchedEffect(forcedLogoutReasonParsed) {
        forcedLogoutReasonParsed?.let { jo ->
            val type = jo.optString("type", "")
            if (type.equals("DEVICE_LIMIT_EXCEEDED", ignoreCase = true)) {
                val logoutEmail = jo.optString("email", "")
                android.util.Log.d("LoginScreen", "Forced logout detected, email from JSON: '$logoutEmail'")
                if (logoutEmail.isNotBlank()) {
                    // Set lastKnownEmail for device management
                    if (lastKnownEmail.isBlank()) {
                        lastKnownEmail = logoutEmail
                        android.util.Log.d("LoginScreen", "Set lastKnownEmail from forced logout: $logoutEmail")
                    }
                    // Pre-fill the email text field so user only needs to enter password
                    if (email.isBlank()) {
                        email = logoutEmail
                        android.util.Log.d("LoginScreen", "Pre-filled email field from forced logout: $logoutEmail")
                    }
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val deviceIdManager = remember { DeviceIdManager(context) }
    val deviceId = remember { deviceIdManager.getDeviceId() }
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("1.0")
    }
    val gson = remember { Gson() }

    val tokenManager = RetrofitClient.getTokenManager()

    // Helpers for device identity (prefer hashed IDs)
    fun DeviceDto.remoteId(): String = (deviceIdHash?.takeIf { it.isNotBlank() } ?: deviceId).orEmpty()
    fun DeviceDto.displayTail(length: Int = 8): String = remoteId().takeLast(length)

    // Check for pending device limit exceeded (from AuthInterceptor broadcast - user NOT logged out)
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE)
        val pendingDeviceLimit = prefs.getString("pending_device_limit_exceeded", null)
        if (!pendingDeviceLimit.isNullOrBlank()) {
            android.util.Log.d("LoginScreen", "Found pending device limit exceeded payload")
            try {
                val jo = JSONObject(pendingDeviceLimit)
                val deviceEmail = jo.optString("email", "")
                if (deviceEmail.isNotBlank()) {
                    email = deviceEmail
                    lastKnownEmail = deviceEmail
                    android.util.Log.d("LoginScreen", "Pre-filled email from device limit: $deviceEmail")
                }

                // Parse devices array if available
                val devicesJson = jo.optJSONArray("devices")
                val devices = mutableListOf<DeviceDto>()
                if (devicesJson != null && devicesJson.length() > 0) {
                    android.util.Log.d("LoginScreen", "Found ${devicesJson.length()} devices in pending payload")
                    for (i in 0 until devicesJson.length()) {
                        val dj = devicesJson.getJSONObject(i)
                        devices.add(DeviceDto(
                            id = dj.optInt("id", 0),
                            deviceId = dj.optString("deviceId", ""),
                            deviceIdHash = dj.optString("deviceIdHash", null),
                            appVersion = dj.optString("appVersion"),
                            lastSeenAt = dj.optString("lastSeenAt"),
                            daysSinceLastSeen = dj.optInt("daysSinceLastSeen", 0),
                            isActive = dj.optBoolean("isActive", false)
                        ))
                    }
                } else {
                    android.util.Log.d("LoginScreen", "No devices array in payload (403 response) - will need to fetch via login")
                }

                // ALWAYS set deviceLimitError so the "Manage Devices" UI is shown
                // Even without devices, user needs to see the button to fetch them
                deviceLimitError = DeviceLimitErrorResponse(
                    message = jo.optString("message", "Device limit exceeded"),
                    errorCode = jo.optString("errorCode", "DEVICE_LIMIT_EXCEEDED"),
                    currentDevices = jo.optInt("currentDevices", 0),
                    maxDevices = jo.optInt("maxDevices", 0),
                    planType = jo.optString("planType"),
                    devices = devices.ifEmpty { null }, // null if empty so UI shows "Manage Devices" button
                    email = deviceEmail
                )
                errorMessage = jo.optString("message", "Device limit exceeded. Please remove a device.")

                // If we have devices, show password dialog for removal
                // If no devices, the UI will show "Manage Devices" button which calls fetchDevicesForManagement()
                if (devices.isNotEmpty()) {
                    emailForDeviceManagement = deviceEmail
                    showPasswordDialogForDeviceManagement = true
                    android.util.Log.d("LoginScreen", "Showing password dialog for device management (have ${devices.size} devices)")
                } else {
                    android.util.Log.d("LoginScreen", "No devices yet - user needs to click 'Manage Devices' and enter password")
                }
            } catch (e: Exception) {
                android.util.Log.e("LoginScreen", "Failed to parse pending device limit payload", e)
            } finally {
                // Clear the pending payload
                prefs.edit().remove("pending_device_limit_exceeded").apply()
            }
        }
    }

    fun persistAuthResponse(authResponse: AuthResponse?): Boolean {
        val auth = authResponse ?: return false
        val token = auth.token
        val refreshToken = auth.refreshToken
        if (token.isNullOrBlank()) return false
        tokenManager.saveTokens(token, refreshToken ?: "")
        auth.deviceId?.let { tokenManager.saveDeviceId(it) }
        tokenManager.saveCurrentDeviceKeyId()
        if (auth.activeDevices != null && auth.maxDevices != null) {
            tokenManager.saveDeviceMetadata(auth.activeDevices, auth.maxDevices)
        }
        return true
    }

    fun handleDeviceLimitError(errorBody: String?, emailFromLogin: String? = null) {
        android.util.Log.d("LoginScreen", "========== HANDLE DEVICE LIMIT ERROR ==========")
        android.util.Log.d("LoginScreen", "Error body: $errorBody")
        android.util.Log.d("LoginScreen", "Email from login: $emailFromLogin")

        if (errorBody.isNullOrBlank()) {
            android.util.Log.w("LoginScreen", "Error body is null or blank")
            deviceLimitError = null
            return
        }

        deviceLimitError = try {
            val parsed = gson.fromJson(errorBody, DeviceLimitErrorResponse::class.java)
            android.util.Log.d("LoginScreen", "Parsed device limit error:")
            android.util.Log.d("LoginScreen", "  - Message: ${parsed.message}")
            android.util.Log.d("LoginScreen", "  - Error Code: ${parsed.errorCode}")
            android.util.Log.d("LoginScreen", "  - Current Devices: ${parsed.currentDevices}")
            android.util.Log.d("LoginScreen", "  - Max Devices: ${parsed.maxDevices}")
            android.util.Log.d("LoginScreen", "  - Plan Type: ${parsed.planType}")
            android.util.Log.d("LoginScreen", "  - Email from response: ${parsed.email}")
            val devSize = parsed.devices?.size ?: 0
            val devicesMsg = if (devSize == 0) "NULL/EMPTY" else "EXISTS ($devSize devices)"
            android.util.Log.d("LoginScreen", "  - Devices array: $devicesMsg")

            if (!parsed.devices.isNullOrEmpty()) {
                parsed.devices.forEachIndexed { index, device ->
                    android.util.Log.d("LoginScreen", "    Device $index: id=${device.id}, deviceId=${device.displayTail(8)}, isActive=${device.isActive}")
                }
            } else {
                android.util.Log.w("LoginScreen", "WARNING: No devices array in 409 response - will show web dashboard link")
            }

            // Store the email from response or login attempt for later use
            val emailToStore = parsed.email?.takeIf { it.isNotBlank() } ?: emailFromLogin
            if (!emailToStore.isNullOrBlank() && lastKnownEmail.isBlank()) {
                android.util.Log.d("LoginScreen", "Storing email for device management: $emailToStore")
                lastKnownEmail = emailToStore
            }

            parsed
        } catch (e: Exception) {
            android.util.Log.e("LoginScreen", "Failed to parse device limit error", e)
            null
        }

        errorMessage = deviceLimitError?.message ?: "Device limit reached."
        android.util.Log.d("LoginScreen", "===============================================")
    }

    fun removeDeviceAndRetryLogin(deviceIdToRemove: Int, attemptEmail: String, attemptPassword: String) {
        android.util.Log.d("LoginScreen", "========== PRE-AUTH DEVICE REMOVAL START ==========")
        android.util.Log.d("LoginScreen", "Device ID to remove: $deviceIdToRemove")
        android.util.Log.d("LoginScreen", "Email: $attemptEmail")
        android.util.Log.d("LoginScreen", "Password length: ${attemptPassword.length}")

        coroutineScope.launch {
            isRemovingDevice = true
            errorMessage = null
            try {
                android.util.Log.d("LoginScreen", "Calling POST /api/devices/pre-auth/remove")
                val response = RetrofitClient.instance.removeDevicePreAuth(
                    PreAuthDeviceRemovalRequest(
                        email = attemptEmail,
                        password = attemptPassword,
                        deviceIdToRemove = deviceIdToRemove
                    )
                )

                android.util.Log.d("LoginScreen", "Pre-auth response code: ${response.code()}")
                android.util.Log.d("LoginScreen", "Pre-auth response successful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    val body = response.body()
                    android.util.Log.d("LoginScreen", "Device removed successfully: ${body?.message}")
                    android.util.Log.d("LoginScreen", "Removed count: ${body?.removedDeviceCount}")

                    // Clear device limit error state
                    deviceLimitError = null
                    errorMessage = null

                    // Show success message
                    android.util.Log.d("LoginScreen", "Device removed. Retrying login...")

                    // Wait a moment for UI feedback
                    kotlinx.coroutines.delay(500)

                    // Automatically retry login with same credentials
                    android.util.Log.d("LoginScreen", "Retrying login with same credentials...")
                    isLoading = true
                    val loginResp = RetrofitClient.instance.login(
                        AuthRequest(
                            email = attemptEmail,
                            password = attemptPassword,
                            deviceId = deviceId,
                            appVersion = appVersion,
                            devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                            devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                            devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                        )
                    )

                    if (loginResp.isSuccessful) {
                        val authBody = loginResp.body()
                        if (authBody != null && persistAuthResponse(authBody)) {
                            android.util.Log.d("LoginScreen", "Login successful after device removal")
                            onLoginSuccess(authBody.token ?: "")
                        } else {
                            errorMessage = "Login successful but token error"
                        }
                    } else {
                        // Login still failed after device removal
                        val newErrorBody = loginResp.errorBody()?.string()
                        when (loginResp.code()) {
                            409 -> handleDeviceLimitError(newErrorBody, attemptEmail)
                            else -> errorMessage = "Login failed: ${loginResp.code()}"
                        }
                    }
                } else {
                    // Device removal failed
                    val errorBody = response.errorBody()?.string()
                    android.util.Log.e("LoginScreen", "Pre-auth removal failed!")
                    android.util.Log.e("LoginScreen", "Error code: ${response.code()}")
                    android.util.Log.e("LoginScreen", "Error body: $errorBody")

                    when (response.code()) {
                        401 -> errorMessage = "Invalid credentials"
                        404 -> errorMessage = "Device not found"
                        429 -> {
                            val rateLimitError = try {
                                gson.fromJson(errorBody, DeviceRemovalResponse::class.java)
                            } catch (_: Exception) {
                                null
                            }
                            errorMessage = "Too many requests. Please wait and try again."
                        }
                        else -> errorMessage = "Failed to remove device: ${response.code()}"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LoginScreen", "Pre-auth removal exception!", e)
                errorMessage = "Network error: ${e.localizedMessage}"
            } finally {
                isRemovingDevice = false
                isLoading = false
                android.util.Log.d("LoginScreen", "========== PRE-AUTH DEVICE REMOVAL END ==========")
            }
        }
    }

    fun fetchDevicesForManagement() {
        if (isFetchingDevicesForManagement) return

        // Resolve email from multiple sources
        val emailToUse = when {
            email.isNotBlank() -> email
            lastKnownEmail.isNotBlank() -> lastKnownEmail
            deviceLimitError?.email?.isNotBlank() == true -> deviceLimitError?.email!!
            else -> null
        }

        android.util.Log.d("LoginScreen", "fetchDevicesForManagement: emailToUse=$emailToUse, email=$email, lastKnownEmail=$lastKnownEmail, deviceLimitError.email=${deviceLimitError?.email}, password.length=${password.length}")

        if (emailToUse.isNullOrBlank()) {
            android.util.Log.e("LoginScreen", "fetchDevicesForManagement: EMAIL IS BLANK - aborting")
            fetchDevicesError = "Enter email above first"
            return
        }
        if (password.isBlank()) {
            android.util.Log.d("LoginScreen", "fetchDevicesForManagement: Password blank, showing PasswordDialogForDeviceManagement")
            emailForDeviceManagement = emailToUse
            passwordForDeviceManagement = ""
            showPasswordDialogForDeviceManagement = true
            return
        }
        fetchDevicesError = null
        coroutineScope.launch {
            isFetchingDevicesForManagement = true
            try {
                // Use a PROBE device ID to force 409 response with devices list
                // The real deviceId might already be registered, causing 200 response without devices
                val probeDeviceId = "probe_${System.currentTimeMillis()}"
                android.util.Log.d("LoginScreen", "fetchDevicesForManagement: Using probe deviceId=$probeDeviceId to get 409 with devices list")

                val resp = RetrofitClient.instance.login(
                    AuthRequest(
                        email = emailToUse,
                        password = password,
                        deviceId = probeDeviceId,
                        appVersion = appVersion,
                        devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                        devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                        devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                    )
                )
                val errorBody = resp.errorBody()?.string()
                android.util.Log.d("LoginScreen", "fetchDevicesForManagement response: code=${resp.code()}, errorBody.length=${errorBody?.length ?: 0}")

                when (resp.code()) {
                    409 -> {
                        lastKnownEmail = emailToUse
                        android.util.Log.d("LoginScreen", "Got 409 with devices list - parsing...")
                        handleDeviceLimitError(errorBody, emailToUse)
                        android.util.Log.d("LoginScreen", "After handleDeviceLimitError: deviceLimitError=${deviceLimitError != null}, devices.size=${deviceLimitError?.devices?.size ?: -1}")
                        if (!deviceLimitError?.devices.isNullOrEmpty()) {
                            android.util.Log.d("LoginScreen", "SHOWING DEVICE PICKER DIALOG - ${deviceLimitError?.devices?.size} devices found!")
                            showDevicePickerDialog = true
                        } else {
                            android.util.Log.e("LoginScreen", "DEVICES ARRAY IS NULL/EMPTY - trying manual JSON parse")
                            // Fallback: try to parse devices manually using JSONObject
                            try {
                                val jo = org.json.JSONObject(errorBody ?: "{}")
                                val devicesJson = jo.optJSONArray("devices")
                                if (devicesJson != null && devicesJson.length() > 0) {
                                    android.util.Log.d("LoginScreen", "Manual parse found ${devicesJson.length()} devices")
                                    val manualDevices = mutableListOf<DeviceDto>()
                                    for (i in 0 until devicesJson.length()) {
                                        val dj = devicesJson.getJSONObject(i)
                                        manualDevices.add(DeviceDto(
                                            id = dj.optInt("id", 0),
                                            deviceId = dj.optString("deviceId", ""),
                                            deviceIdHash = dj.optString("deviceIdHash", null),
                                            appVersion = dj.optString("appVersion"),
                                            lastSeenAt = dj.optString("lastSeenAt"),
                                            daysSinceLastSeen = dj.optInt("daysSinceLastSeen", 0),
                                            isActive = dj.optBoolean("isActive", false)
                                        ))
                                    }
                                    deviceLimitError = DeviceLimitErrorResponse(
                                        message = jo.optString("message", "Device limit reached"),
                                        errorCode = jo.optString("errorCode", "DEVICE_LIMIT_EXCEEDED"),
                                        currentDevices = jo.optInt("currentDevices", 0),
                                        maxDevices = jo.optInt("maxDevices", 0),
                                        planType = jo.optString("planType"),
                                        devices = manualDevices,
                                        email = emailToUse
                                    )
                                    android.util.Log.d("LoginScreen", "Manual parse successful, showing dialog")
                                    showDevicePickerDialog = true
                                } else {
                                    fetchDevicesError = "Could not load device list"
                                }
                            } catch (parseEx: Exception) {
                                android.util.Log.e("LoginScreen", "Manual JSON parse failed", parseEx)
                                fetchDevicesError = "Failed to parse device list"
                            }
                        }
                    }
                    200 -> {
                        // Probe succeeded unexpectedly (user has free slot now?)
                        // Don't persist this token (wrong device ID), instead show success
                        android.util.Log.w("LoginScreen", "Probe login succeeded - user may have free slots now. Try logging in normally.")
                        val auth = resp.body()
                        val activeDevices = auth?.activeDevices ?: 0
                        val maxDevices = auth?.maxDevices ?: 1
                        if (activeDevices <= maxDevices) {
                            // User has space, they can login normally
                            fetchDevicesError = null
                            deviceLimitError = null
                            errorMessage = null
                            // Show toast and let user login normally
                            android.widget.Toast.makeText(context, "Device limit cleared! Please sign in.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            // Still over limit but got 200 (unexpected)
                            fetchDevicesError = "Please try signing in again"
                        }
                    }
                    401 -> {
                        fetchDevicesError = "Invalid email or password"
                    }
                    else -> {
                        fetchDevicesError = "Failed to fetch devices (${resp.code()})"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LoginScreen", "fetchDevicesForManagement error", e)
                fetchDevicesError = "Network error: ${e.localizedMessage}"
            } finally {
                isFetchingDevicesForManagement = false
            }
        }
    }

    val webClientId = net.libreguard.vpn.BuildConfig.GOOGLE_WEB_CLIENT_ID
    var googleLoading by remember { mutableStateOf(false) }

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        coroutineScope.launch {
            googleLoading = true
            val account = runCatching {
                GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(Exception::class.java)
            }.getOrElse {
                googleLoading = false
                errorMessage = context.getString(R.string.google_sign_in_error, it.localizedMessage ?: "Google sign-in failed")
                return@launch
            }

            val idToken = account?.idToken
            if (idToken.isNullOrBlank()) {
                googleLoading = false
                errorMessage = context.getString(R.string.google_sign_in_error, "Missing ID token")
                return@launch
            }

            isLoading = true
            try {
                val resp = RetrofitClient.instance.loginWithGoogle(
                    GoogleLoginRequest(
                        idToken = idToken,
                        deviceId = deviceId,
                        appVersion = appVersion,
                        devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                        devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                        devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                    )
                )
                val errorBody = resp.errorBody()?.string()
                if (resp.isSuccessful) {
                    val body: GoogleLoginResponse? = resp.body()
                    if (body != null && persistAuthResponse(
                            AuthResponse(
                                token = body.token,
                                refreshToken = body.refreshToken,
                                message = null,
                                requiresTwoFactor = false,
                                email = body.email,
                                userId = body.userId,
                                deviceId = body.deviceId,
                                activeDevices = body.activeDevices,
                                maxDevices = body.maxDevices,
                                planType = body.planType
                            )
                        )) {
                        deviceLimitError = null
                        onLoginSuccess(body.token)
                    } else {
                        errorMessage = context.getString(R.string.google_sign_in_error, "No token returned")
                    }
                } else {
                    when (resp.code()) {
                        409 -> {
                            // Store Google user's email and idToken for device management
                            googleUserEmail = account?.email
                            googleIdToken = idToken
                            handleDeviceLimitError(errorBody, account?.email)
                            // DO NOT auto-show device picker - let user click "Manage Devices" button
                            // This gives user control over when to see the device list
                            android.util.Log.d("LoginScreen", "Google Sign In got 409 with ${deviceLimitError?.devices?.size ?: 0} devices - user will click Manage Devices to see list")
                        }
                        400 -> {
                            handleDeviceLimitError(errorBody, account?.email)
                            if (errorMessage.isNullOrBlank()) {
                                errorMessage = context.getString(R.string.google_sign_in_error, "Missing device information")
                            }
                        }
                        else -> errorMessage = context.getString(R.string.google_sign_in_error, "${resp.code()}")
                    }
                }
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.google_sign_in_error, e.localizedMessage ?: "Unknown error")
            } finally {
                isLoading = false
                googleLoading = false
            }
        }
    }

    // Button animation
    val buttonScale by animateFloatAsState(
        targetValue = if (isLoading) 1f else 1f,
        animationSpec = tween(100),
        label = "buttonScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Logo with animation
            LogoWithGradient(size = 96.dp)

            Spacer(modifier = Modifier.height(24.dp))

            // Header
            Text(
                text = "Welcome Back",
                style = MaterialTheme.typography.headlineMedium,
                color = Foreground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Sign in to your LibreGuard account",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground
            )


            Spacer(modifier = Modifier.height(32.dp))

            // Email Field
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Email",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("you@example.com", color = MutedForeground) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = MutedForeground
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        cursorColor = Primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Password Field
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Password",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("••••••••", color = MutedForeground) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MutedForeground
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                                tint = MutedForeground
                            )
                        }
                    },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        cursorColor = Primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Forgot Password Link
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "Forgot password?",
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary,
                    modifier = Modifier.clickable { /* TODO: Navigate to forgot password */ }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sign In Button
            Button(
                onClick = {
                    keyboardController?.hide()
                    coroutineScope.launch {
                        isLoading = true
                        errorMessage = null
                        try {
                            // Detailed logging for debugging
                            android.util.Log.d("LoginScreen", "=== LOGIN ATTEMPT ===")
                            android.util.Log.d("LoginScreen", "Email: $email")
                            android.util.Log.d("LoginScreen", "Password length: ${password.length}")
                            android.util.Log.d("LoginScreen", "DeviceId: $deviceId")
                            android.util.Log.d("LoginScreen", "AppVersion: $appVersion")

                            val authRequest = AuthRequest(
                                email = email,
                                password = password,
                                deviceId = deviceId,
                                appVersion = appVersion,
                                devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                            )
                            android.util.Log.d("LoginScreen", "Request JSON: ${Gson().toJson(authRequest)}")

                            val response = RetrofitClient.instance.login(authRequest)
                            val errorBody = response.errorBody()?.string()

                            android.util.Log.d("LoginScreen", "Response code: ${response.code()}")
                            android.util.Log.d("LoginScreen", "Response successful: ${response.isSuccessful}")
                            android.util.Log.d("LoginScreen", "Error body: $errorBody")
                            if (response.isSuccessful) {
                                val authResponse = response.body()
                                if (authResponse?.requiresTwoFactor == true) {
                                    onRequires2FA(email)
                                } else {
                                    // CHECK FOR DEVICE LIMIT EXCEEDED IN SUCCESSFUL RESPONSE
                                    // Backend returns 200 with activeDevices > maxDevices
                                    val activeDevicesCount = authResponse?.activeDevices ?: 0
                                    val maxDevicesCount = authResponse?.maxDevices ?: 1

                                    if (activeDevicesCount > maxDevicesCount) {
                                        android.util.Log.w("LoginScreen", "Device limit exceeded in 200 response: $activeDevicesCount/$maxDevicesCount")
                                        android.util.Log.d("LoginScreen", "User logged in but has too many devices - triggering device management flow")

                                        // Do NOT persist auth or navigate to dashboard
                                        // Instead, call login with a FAKE device ID to get 409 with devices list
                                        lastKnownEmail = email

                                        // Make a login call with a fake deviceId to get the 409 response with devices list
                                        android.util.Log.d("LoginScreen", "Calling login with temp deviceId to get devices list...")
                                        val tempLoginResp = RetrofitClient.instance.login(
                                            AuthRequest(
                                                email = email,
                                                password = password,
                                                deviceId = "temp_device_for_list",
                                                appVersion = appVersion,
                                                devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                            )
                                        )
                                        val tempErrorBody = tempLoginResp.errorBody()?.string()
                                        android.util.Log.d("LoginScreen", "Temp login response: ${tempLoginResp.code()}, body length=${tempErrorBody?.length ?: 0}")

                                        when (tempLoginResp.code()) {
                                            409 -> {
                                                android.util.Log.d("LoginScreen", "Got 409 with devices list - parsing...")
                                                handleDeviceLimitError(tempErrorBody, email)
                                                if (!deviceLimitError?.devices.isNullOrEmpty()) {
                                                    android.util.Log.d("LoginScreen", "Found ${deviceLimitError?.devices?.size} devices - showing picker")
                                                    showDevicePickerDialog = true
                                                } else {
                                                    android.util.Log.e("LoginScreen", "409 but no devices in response - manual parse fallback")
                                                    // Try manual JSON parse
                                                    try {
                                                        val jo = org.json.JSONObject(tempErrorBody ?: "{}")
                                                        val devicesJson = jo.optJSONArray("devices")
                                                        if (devicesJson != null && devicesJson.length() > 0) {
                                                            val manualDevices = mutableListOf<DeviceDto>()
                                                            for (i in 0 until devicesJson.length()) {
                                                                val dj = devicesJson.getJSONObject(i)
                                                                manualDevices.add(DeviceDto(
                                                                    id = dj.optInt("id", 0),
                                                                    deviceId = dj.optString("deviceId", ""),
                                                                    deviceIdHash = dj.optString("deviceIdHash", null),
                                                                    appVersion = dj.optString("appVersion"),
                                                                    lastSeenAt = dj.optString("lastSeenAt"),
                                                                    daysSinceLastSeen = dj.optInt("daysSinceLastSeen", 0),
                                                                    isActive = dj.optBoolean("isActive", false)
                                                                ))
                                                            }
                                                            deviceLimitError = DeviceLimitErrorResponse(
                                                                message = jo.optString("message", "Device limit reached"),
                                                                errorCode = jo.optString("errorCode", "DEVICE_LIMIT_EXCEEDED"),
                                                                currentDevices = jo.optInt("currentDevices", 0),
                                                                maxDevices = jo.optInt("maxDevices", 0),
                                                                planType = jo.optString("planType"),
                                                                devices = manualDevices,
                                                                email = email
                                                            )
                                                            showDevicePickerDialog = true
                                                        } else {
                                                            errorMessage = "Device limit exceeded. Please manage your devices."
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("LoginScreen", "Manual parse failed", e)
                                                        errorMessage = "Device limit exceeded. Please remove a device via web dashboard."
                                                    }
                                                }
                                            }
                                            200 -> {
                                                // Even temp login succeeded - very unusual, just proceed
                                                android.util.Log.w("LoginScreen", "Temp login also returned 200 - proceeding with original token")
                                                if (persistAuthResponse(authResponse)) {
                                                    deviceLimitError = null
                                                    lastKnownEmail = email
                                                    onLoginSuccess(authResponse!!.token!!)
                                                } else {
                                                    errorMessage = "Failed to save login state"
                                                }
                                            }
                                            else -> {
                                                // Create device limit error from original response data
                                                deviceLimitError = DeviceLimitErrorResponse(
                                                    message = "Device limit exceeded. You have $activeDevicesCount active devices but your plan allows only $maxDevicesCount.",
                                                    errorCode = "DEVICE_LIMIT_EXCEEDED",
                                                    currentDevices = activeDevicesCount,
                                                    maxDevices = maxDevicesCount,
                                                    planType = authResponse?.planType,
                                                    devices = null,
                                                    email = email
                                                )
                                                errorMessage = deviceLimitError?.message
                                            }
                                        }
                                    } else if (persistAuthResponse(authResponse)) {
                                        deviceLimitError = null
                                        lastKnownEmail = email
                                        onLoginSuccess(authResponse!!.token!!)
                                    } else {
                                        errorMessage = authResponse?.message ?: "Login failed"
                                    }
                                }
                            } else {
                                when (response.code()) {
                                    401 -> {
                                        // Parse structured error response to determine if email is unverified
                                        val requiresVerification = errorBody?.let { body ->
                                            try {
                                                val errorResponse = Gson().fromJson(body, ApiErrorResponse::class.java)
                                                errorResponse?.requiresEmailVerification == true ||
                                                errorResponse?.code == "EMAIL_NOT_VERIFIED"
                                            } catch (e: Exception) {
                                                android.util.Log.w("LoginScreen", "Failed to parse error response: ${e.message}")
                                                false
                                            }
                                        } ?: false

                                        if (requiresVerification) {
                                            // Only redirect to email verification if explicitly required
                                            runCatching {
                                                RetrofitClient.instance.resendConfirmation(ResendConfirmationRequest(email))
                                            }
                                            onNavigateToEmailVerification(email, null)
                                        } else {
                                            // Other 401 errors (invalid credentials, expired tokens, etc.)
                                            errorMessage = "Invalid email or password"
                                        }
                                    }
                                    409 -> {
                                        lastKnownEmail = email
                                        handleDeviceLimitError(errorBody, email)
                                        // Automatically show device picker if devices are available
                                        if (!deviceLimitError?.devices.isNullOrEmpty()) {
                                            android.util.Log.d("LoginScreen", "Sign In got 409 with ${deviceLimitError?.devices?.size} devices - showing picker")
                                            showDevicePickerDialog = true
                                        }
                                    }
                                    400 -> {
                                        handleDeviceLimitError(errorBody, email)
                                        if (errorMessage.isNullOrBlank()) {
                                            errorMessage = "Device identifier missing. Please reinstall the app."
                                        }
                                    }
                                    else -> {
                                        errorMessage = "Login failed: ${response.code()}"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            errorMessage = "Network error: ${e.localizedMessage}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .scale(buttonScale),
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground,
                    disabledContainerColor = Primary.copy(alpha = 0.5f),
                    disabledContentColor = PrimaryForeground.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isLoading && !googleLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PrimaryForeground,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = Border
                )
                Text(
                    text = "Or continue with",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = Border
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Google Sign In Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx: android.content.Context ->
                            SignInButton(ctx).apply {
                                setSize(SignInButton.SIZE_WIDE)
                                setColorScheme(SignInButton.COLOR_LIGHT)
                                setOnClickListener {
                                    errorMessage = null
                                    googleLoading = true
                                    googleLauncher.launch(googleSignInClient.signInIntent)
                                }
                            }
                        },
                        update = { btn: SignInButton ->
                            btn.isEnabled = !isLoading && !googleLoading
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (googleLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CardBackground.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Error message - Enhanced for device limit errors
            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))

                // Check if this is a device limit error
                if (deviceLimitError != null) {
                    // Show enhanced device limit error UI with Manage Devices button
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.08f),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Primary.copy(alpha = 0.4f))
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Device limit reached",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Foreground,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = {
                                    errorMessage = null
                                    deviceLimitError = null
                                }) {
                                    Text("Dismiss")
                                }
                            }

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )

                            val detailLine = buildString {
                                if (deviceLimitError?.planType != null) append("Plan: ${deviceLimitError?.planType}")
                                if (deviceLimitError?.currentDevices != null && deviceLimitError?.maxDevices != null) {
                                    if (isNotEmpty()) append(" • ")
                                    append("Devices: ${deviceLimitError?.currentDevices}/${deviceLimitError?.maxDevices}")
                                }
                            }
                            if (detailLine.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = detailLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedForeground
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = onNavigateToUpgrade,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Upgrade to Pro")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Show different button based on whether devices are available
                            if (!deviceLimitError?.devices.isNullOrEmpty()) {
                                // Devices available from 409 response - show device picker on click
                                OutlinedButton(
                                    onClick = { showDevicePickerDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isRemovingDevice
                                ) {
                                    if (isRemovingDevice) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Removing device...")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Devices,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Manage Devices")
                                    }
                                }
                            } else {
                                // No devices in response - user needs to click to fetch via login API
                                // Error messages handled by fetchDevicesForManagement()
                                OutlinedButton(
                                    onClick = {
                                        fetchDevicesForManagement()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isFetchingDevicesForManagement
                                ) {
                                    if (isFetchingDevicesForManagement) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Fetching devices...")
                                    } else {
                                        Icon(imageVector = Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Manage Devices")
                                    }
                                }
                                fetchDevicesError?.let {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = it, style = MaterialTheme.typography.labelSmall, color = Destructive)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = if (!deviceLimitError?.devices.isNullOrEmpty()) {
                                    "Click Manage Devices to select a device to remove."
                                } else {
                                    "Enter your password to manage devices and remove one to free up space."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                } else {
                    // Show regular error message
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Destructive.copy(alpha = 0.1f),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                        )
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            color = Destructive,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Forced logout banner (shown if user was logged out due to device limit)
            // Identical to device limit error card for consistency
            forcedLogoutReasonParsed?.let { jo ->
                val type = jo.optString("type", "")
                if (type.equals("DEVICE_LIMIT_EXCEEDED", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(16.dp))

                    val msg = jo.optString("message", "Device limit exceeded.")
                    val currentDevices = jo.optInt("currentDevices", -1).takeIf { it >= 0 }
                    val maxDevices = jo.optInt("maxDevices", -1).takeIf { it >= 0 }
                    val planType = jo.optString("planType", "").takeIf { it.isNotBlank() }

                    // Extract email from forced logout JSON if available
                    val logoutEmail = jo.optString("email", "")
                    if (logoutEmail.isNotBlank() && lastKnownEmail.isBlank()) {
                        lastKnownEmail = logoutEmail
                    }

                    // Parse devices array from forced logout reason if available
                    val devicesArray = try {
                        val devicesJson = jo.optJSONArray("devices")
                        if (devicesJson != null && devicesJson.length() > 0) {
                            val devicesList = mutableListOf<DeviceDto>()
                            for (i in 0 until devicesJson.length()) {
                                val deviceJson = devicesJson.getJSONObject(i)
                                devicesList.add(
                                    DeviceDto(
                                        id = deviceJson.optInt("id", 0),
                                        deviceId = deviceJson.optString("deviceId", ""),
                                        deviceIdHash = deviceJson.optString("deviceIdHash", null),
                                        appVersion = deviceJson.optString("appVersion", ""),
                                        lastSeenAt = deviceJson.optString("lastSeenAt", ""),
                                        daysSinceLastSeen = deviceJson.optInt("daysSinceLastSeen", 0),
                                        isActive = deviceJson.optBoolean("isActive", false)
                                    )
                                )
                            }
                            devicesList
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.08f),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Primary.copy(alpha = 0.4f))
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Signed out: device limit reached",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Foreground,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = onDismissForcedLogoutReason) {
                                    Text("Dismiss")
                                }
                            }

                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )

                            val detailLine = buildString {
                                if (planType != null) append("Plan: $planType")
                                if (currentDevices != null && maxDevices != null) {
                                    if (isNotEmpty()) append(" • ")
                                    append("Devices: $currentDevices/$maxDevices")
                                }
                            }
                            if (detailLine.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = detailLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedForeground
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = onNavigateToUpgrade,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Upgrade to Pro")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Show device selection button if devices available
                            if (!devicesArray.isNullOrEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        // Ensure we have an email to use from logout context if variable is empty
                                        if (email.isBlank() && lastKnownEmail.isBlank() && logoutEmail.isNotBlank()) {
                                            lastKnownEmail = logoutEmail
                                        }

                                        // Show device picker dialog with devices from forced logout
                                        deviceLimitError = DeviceLimitErrorResponse(
                                            message = msg,
                                            errorCode = "DEVICE_LIMIT_EXCEEDED",
                                            currentDevices = currentDevices ?: 0,
                                            maxDevices = maxDevices ?: 0,
                                            planType = planType,
                                            devices = devicesArray
                                        )
                                        showDevicePickerDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Devices,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Manage Devices")
                                }
                            } else {
                                // No devices in response - user needs to click to fetch via login API
                                // Error messages handled by fetchDevicesForManagement()
                                OutlinedButton(
                                    onClick = {
                                        fetchDevicesForManagement()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isFetchingDevicesForManagement
                                ) {
                                    if (isFetchingDevicesForManagement) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Fetching devices...")
                                    } else {
                                        Icon(imageVector = Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Manage Devices")
                                    }
                                }
                                fetchDevicesError?.let {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = it, style = MaterialTheme.typography.labelSmall, color = Destructive)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = if (!devicesArray.isNullOrEmpty()) {
                                    "Click Manage Devices to select a device to remove."
                                } else {
                                    "Enter your password to manage devices and remove one to free up space."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Register Link
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New here? ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedForeground
                )
                Text(
                    text = "Create an account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary,
                    modifier = Modifier.clickable { onNavigateToRegister() }
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    // Device Picker Dialog
    // Debug: log state for dialog condition
    if (showDevicePickerDialog) {
        android.util.Log.d("LoginScreen", "Dialog condition check: showDevicePickerDialog=true, deviceLimitError=${deviceLimitError != null}, devices.size=${deviceLimitError?.devices?.size ?: -1}")
    }

    if (showDevicePickerDialog && deviceLimitError != null && !deviceLimitError?.devices.isNullOrEmpty()) {
        // Capture variables for use in lambda - resolve email from multiple sources
        val capturedGoogleIdToken = googleIdToken
        val capturedEmail = when {
            email.isNotBlank() -> email
            lastKnownEmail.isNotBlank() -> lastKnownEmail
            deviceLimitError?.email?.isNotBlank() == true -> deviceLimitError?.email!!
            else -> ""
        }
        val capturedPassword = password

        android.util.Log.d("LoginScreen", "Device picker dialog opened: capturedEmail=$capturedEmail, password.length=${capturedPassword.length}")

        // Filter out the current device - users can only remove OTHER devices
        // Removing the current device is useless as it will be re-registered on login
        val otherDevices = deviceLimitError?.devices?.filter { it.remoteId() != deviceId }
        val hasOnlyCurrentDevice = otherDevices.isNullOrEmpty()

        android.util.Log.d("LoginScreen", "Current deviceId=$deviceId, total devices=${deviceLimitError?.devices?.size}, other devices=${otherDevices?.size}")

        AlertDialog(
            onDismissRequest = { showDevicePickerDialog = false },
            title = { Text(if (hasOnlyCurrentDevice) "No Other Devices" else "Select Device to Remove") },
            text = {
                Column {
                    if (hasOnlyCurrentDevice) {
                        // All devices in the list are the current device (duplicates from multiple logins)
                        Text(
                            text = "All registered devices belong to this phone. This can happen if the device was registered multiple times.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Text(
                            text = "The server shows ${deviceLimitError?.currentDevices ?: 0} device(s) registered, but they all have the same device ID as your current phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Text(
                            text = "Please contact support or try logging in again - this issue may resolve itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary
                        )
                    } else {
                        Text(
                            text = "Remove one of these devices to free up space:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Text(
                            text = "Note: Your current device is not shown (removing it wouldn't help).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Show warning if email or password is missing for password-based auth
                        if (capturedGoogleIdToken.isNullOrBlank() && (capturedEmail.isBlank() || capturedPassword.isBlank())) {
                            Text(
                                text = if (capturedEmail.isBlank()) "⚠️ Please enter your email above first" else "⚠️ Please enter your password above first",
                                style = MaterialTheme.typography.bodySmall,
                                color = Destructive,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }

                    otherDevices?.forEachIndexed { index, device ->
                        val deviceDbId = device.id
                        val deviceStringId = device.deviceId

                        androidx.compose.runtime.key(deviceDbId) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(enabled = true) {
                                        android.util.Log.d("LoginScreen", "========== DEVICE PICKER CLICK ==========")
                                        android.util.Log.d("LoginScreen", "Device index: $index")
                                        android.util.Log.d("LoginScreen", "Device DB ID: $deviceDbId")
                                        android.util.Log.d("LoginScreen", "Device String ID: $deviceStringId")
                                        android.util.Log.d("LoginScreen", "Google token present: ${!capturedGoogleIdToken.isNullOrBlank()}")
                                        android.util.Log.d("LoginScreen", "Email: $capturedEmail")
                                        android.util.Log.d("LoginScreen", "Password length: ${capturedPassword.length}")

                                        showDevicePickerDialog = false

                                        // Check if OAuth user (has googleIdToken) or password user
                                        if (!capturedGoogleIdToken.isNullOrBlank()) {
                                            // OAuth user - use OAuth pre-auth endpoint
                                            android.util.Log.d("LoginScreen", "Using OAuth pre-auth to remove device $deviceDbId")
                                            isRemovingDevice = true

                                            coroutineScope.launch {
                                                try {
                                                    val response = RetrofitClient.instance.removeDevicePreAuthOAuth(
                                                        PreAuthOAuthDeviceRemovalRequest(
                                                            idToken = capturedGoogleIdToken,
                                                            provider = "Google",
                                                            deviceIdToRemove = deviceDbId
                                                        )
                                                    )

                                                    if (response.isSuccessful) {
                                                        android.util.Log.d("LoginScreen", "OAuth device removed successfully")
                                                        deviceLimitError = null
                                                        errorMessage = "Device removed! Please sign in again with Google."
                                                    } else {
                                                        val errorBody = response.errorBody()?.string()
                                                        android.util.Log.e("LoginScreen", "OAuth device removal failed: $errorBody")
                                                        errorMessage = when (response.code()) {
                                                            401 -> "Google token expired. Please sign in again."
                                                            404 -> "Device not found"
                                                            429 -> "Too many requests. Please wait."
                                                            else -> "Failed to remove device: ${response.code()}"
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("LoginScreen", "OAuth device removal exception", e)
                                                    errorMessage = "Network error: ${e.localizedMessage}"
                                                } finally {
                                                    isRemovingDevice = false
                                                }
                                            }
                                        } else if (capturedEmail.isNotBlank() && capturedPassword.isNotBlank()) {
                                            // Password user - use password pre-auth endpoint
                                            android.util.Log.d("LoginScreen", "Using password pre-auth to remove device $deviceDbId, email=$capturedEmail")
                                            removeDeviceAndRetryLogin(deviceDbId, capturedEmail, capturedPassword)
                                        } else {
                                            // No credentials available - show error
                                            android.util.Log.e("LoginScreen", "No credentials available for device removal!")
                                            errorMessage = "Please enter your email and password first"
                                        }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = CardBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = device.displayTail(12),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Foreground
                                    )
                                    device.appVersion?.let {
                                        Text(
                                            text = "Version: $it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedForeground
                                        )
                                    }
                                    device.lastSeenAt?.let {
                                        Text(
                                            text = "Last seen: ${device.daysSinceLastSeen} days ago",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedForeground
                                        )
                                    }
                                    if (device.isActive) {
                                        Text(
                                            text = "Active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDevicePickerDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = CardBackground
        )
    }

    // Password Dialog for Device Management (for Google users)
    if (showPasswordDialogForDeviceManagement) {
        var isPasswordDialogLoading by remember { mutableStateOf(false) }
        var passwordDialogError by remember { mutableStateOf<String?>(null) }
        // Initialize with devices from deviceLimitError if we already have them from the broadcast
        var devicesFromPasswordLogin by remember { mutableStateOf<List<DeviceDto>?>(deviceLimitError?.devices) }
        // Track if password has been validated (needed for pre-loaded devices case)
        var passwordValidated by remember { mutableStateOf(false) }
        var passwordDialogVisible by remember { mutableStateOf(true) }

        // Show devices only if we have them AND password is validated (or we got them from API)
        val showDevices = devicesFromPasswordLogin != null && (passwordValidated || passwordForDeviceManagement.length >= 8)

        // Filter out the current device - users can only remove OTHER devices
        val otherDevicesFromPasswordLogin = devicesFromPasswordLogin?.filter { it.remoteId() != deviceId }
        val hasOnlyCurrentDeviceInPasswordDialog = showDevices && otherDevicesFromPasswordLogin.isNullOrEmpty()

        AlertDialog(
            onDismissRequest = {
                if (!isPasswordDialogLoading) {
                    showPasswordDialogForDeviceManagement = false
                    passwordDialogError = null
                    devicesFromPasswordLogin = null
                    emailForDeviceManagement = ""
                }
            },
            title = {
                Text(
                    if (hasOnlyCurrentDeviceInPasswordDialog) "No Other Devices"
                    else if (showDevices) "Select Device to Remove"
                    else "Enter Password"
                )
            },
            text = {
                Column {
                    if (hasOnlyCurrentDeviceInPasswordDialog) {
                        // All devices in the list are the current device
                        Text(
                            text = "All registered devices belong to this phone. This can happen if the device was registered multiple times.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Text(
                            text = "Please contact support or try logging in again - this issue may resolve itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary
                        )
                    } else if (showDevices) {
                        // Show device picker
                        Text(
                            text = "Remove one of these devices to free up space:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Text(
                            text = "Note: Your current device is not shown.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        otherDevicesFromPasswordLogin?.forEachIndexed { index, device ->
                            // Capture values in local scope to avoid closure issues
                            val capturedEmail = when {
                                emailForDeviceManagement.isNotBlank() -> emailForDeviceManagement
                                email.isNotBlank() -> email
                                lastKnownEmail.isNotBlank() -> lastKnownEmail
                                deviceLimitError?.email?.isNotBlank() == true -> deviceLimitError?.email!!
                                googleUserEmail?.isNotBlank() == true -> googleUserEmail!!
                                else -> ""
                            }
                            val capturedPassword = passwordForDeviceManagement
                            val capturedDeviceId = device.id
                            val capturedDeviceIdString = device.deviceId

                            androidx.compose.runtime.key(device.id) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable(enabled = !isPasswordDialogLoading) {
                                            // Remove this device using pre-auth API
                                            android.util.Log.d("LoginScreen", "========== DEVICE CLICKED ==========")
                                            android.util.Log.d("LoginScreen", "Device index: $index")
                                            android.util.Log.d("LoginScreen", "Device ID (int): $capturedDeviceId")
                                            android.util.Log.d("LoginScreen", "Device ID (string): $capturedDeviceIdString")
                                            android.util.Log.d("LoginScreen", "Email to use: $capturedEmail")
                                            android.util.Log.d("LoginScreen", "Password length: ${capturedPassword.length}")

                                            if (capturedEmail.isBlank()) {
                                                passwordDialogError = "Email is missing"
                                                return@clickable
                                            }
                                            if (capturedPassword.isBlank()) {
                                                passwordDialogError = "Password is missing"
                                                return@clickable
                                            }

                                            isPasswordDialogLoading = true
                                            coroutineScope.launch {
                                                try {
                                                    val response = RetrofitClient.instance.removeDevicePreAuth(
                                                        PreAuthDeviceRemovalRequest(
                                                            email = capturedEmail,
                                                            password = capturedPassword,
                                                            deviceIdToRemove = capturedDeviceId
                                                        )
                                                    )

                                                    android.util.Log.d("LoginScreen", "Pre-auth device removal response: ${response.code()}")

                                                    if (response.isSuccessful) {
                                                        android.util.Log.d("LoginScreen", "Device removed successfully via password dialog")
                                                        // After removal, try to login with real device ID
                                                        android.util.Log.d("LoginScreen", "Attempting login after device removal...")

                                                        val loginResp = RetrofitClient.instance.login(
                                                            AuthRequest(
                                                                email = capturedEmail,
                                                                password = capturedPassword,
                                                                deviceId = deviceId,
                                                                appVersion = appVersion,
                                                                devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                                devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                                devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                                            )
                                                        )

                                                        if (loginResp.isSuccessful) {
                                                            val authBody = loginResp.body()
                                                            if (authBody != null && persistAuthResponse(authBody)) {
                                                                android.util.Log.d("LoginScreen", "Login successful after device removal!")
                                                                showPasswordDialogForDeviceManagement = false
                                                                deviceLimitError = null
                                                                errorMessage = null
                                                                onLoginSuccess(authBody.token ?: "")
                                                            } else {
                                                                showPasswordDialogForDeviceManagement = false
                                                                deviceLimitError = null
                                                                errorMessage = "Device removed! Please sign in again."
                                                            }
                                                        } else {
                                                            showPasswordDialogForDeviceManagement = false
                                                            deviceLimitError = null
                                                            errorMessage = "Device removed! Please sign in again."
                                                            passwordForDeviceManagement = ""
                                                        }
                                                    } else {
                                                        val errorBody = response.errorBody()?.string()
                                                        android.util.Log.e("LoginScreen", "Device removal failed: $errorBody")
                                                        passwordDialogError = when (response.code()) {
                                                            401 -> "Invalid password"
                                                            404 -> "Device not found"
                                                            429 -> "Too many requests. Please wait."
                                                            else -> "Failed to remove device: ${response.code()}"
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("LoginScreen", "Device removal exception", e)
                                                    passwordDialogError = "Network error: ${e.localizedMessage}"
                                                } finally {
                                                    isPasswordDialogLoading = false
                                                }
                                            }
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = CardBackground,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = device.displayTail(12),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Foreground
                                        )
                                        device.appVersion?.let {
                                            Text(
                                                text = "Version: $it",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MutedForeground
                                            )
                                        }
                                        if (device.isActive) {
                                            Text(
                                                text = "Active",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Show password input
                        Text(
                            text = "Enter your account password to view and manage devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        OutlinedTextField(
                            value = passwordForDeviceManagement,
                            onValueChange = {
                                passwordForDeviceManagement = it
                                passwordDialogError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("••••••••", color = MutedForeground) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MutedForeground
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !isPasswordDialogLoading,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = Border,
                                focusedContainerColor = CardBackground,
                                unfocusedContainerColor = CardBackground,
                                cursorColor = Primary
                            )
                        )

                        passwordDialogError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Destructive
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                isPasswordDialogLoading = true
                                passwordDialogError = null
                                coroutineScope.launch {
                                    try {
                                        val emailToUse = when {
                                            emailForDeviceManagement.isNotBlank() -> emailForDeviceManagement
                                            email.isNotBlank() -> email
                                            lastKnownEmail.isNotBlank() -> lastKnownEmail
                                            deviceLimitError?.email?.isNotBlank() == true -> deviceLimitError?.email!!
                                            else -> {
                                                passwordDialogError = "Please enter your email above first"
                                                isPasswordDialogLoading = false
                                                return@launch
                                            }
                                        }

                                        // If we already have devices from the broadcast, just validate password
                                        // by attempting a login - we don't need the devices list from response
                                        if (devicesFromPasswordLogin != null) {
                                            // We already have devices - use probe to validate password and check if still over limit
                                            val probeDeviceId = "probe_${System.currentTimeMillis()}"
                                            android.util.Log.d("LoginScreen", "Validating password with probe deviceId for pre-loaded devices (email: $emailToUse)")
                                            val response = RetrofitClient.instance.login(
                                                AuthRequest(
                                                    email = emailToUse,
                                                    password = passwordForDeviceManagement,
                                                    deviceId = probeDeviceId,
                                                    appVersion = appVersion,
                                                    devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                    devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                    devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                                )
                                            )

                                            when {
                                                response.code() == 409 -> {
                                                    // Password is valid, device limit still exceeded - show devices
                                                    android.util.Log.d("LoginScreen", "Password validated (got 409), showing pre-loaded devices")
                                                    passwordValidated = true
                                                }
                                                response.isSuccessful -> {
                                                    // Probe succeeded - user has free slot now, login with real device ID
                                                    android.util.Log.d("LoginScreen", "Device limit cleared! Logging in with real device ID...")
                                                    val realLoginResp = RetrofitClient.instance.login(
                                                        AuthRequest(
                                                            email = emailToUse,
                                                            password = passwordForDeviceManagement,
                                                            deviceId = deviceId,
                                                            appVersion = appVersion,
                                                            devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                            devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                            devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                                        )
                                                    )
                                                    if (realLoginResp.isSuccessful) {
                                                        val authBody = realLoginResp.body()
                                                        if (authBody != null && persistAuthResponse(authBody)) {
                                                            showPasswordDialogForDeviceManagement = false
                                                            deviceLimitError = null
                                                            errorMessage = null
                                                            onLoginSuccess(authBody.token ?: "")
                                                        }
                                                    } else {
                                                        passwordDialogError = "Login failed: ${realLoginResp.code()}"
                                                    }
                                                }
                                                response.code() == 401 -> {
                                                    passwordDialogError = "Invalid password"
                                                }
                                                else -> {
                                                    passwordDialogError = "Login failed: ${response.code()}"
                                                }
                                            }
                                        } else {
                                            // No pre-loaded devices, need to fetch from API using probe device ID
                                            val probeDeviceId = "probe_${System.currentTimeMillis()}"
                                            android.util.Log.d("LoginScreen", "Attempting login with probe deviceId to get devices list: $emailToUse")

                                            val response = RetrofitClient.instance.login(
                                                AuthRequest(
                                                    email = emailToUse,
                                                    password = passwordForDeviceManagement,
                                                    deviceId = probeDeviceId,
                                                    appVersion = appVersion,
                                                    devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                    devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                    devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                                )
                                            )

                                            if (response.code() == 409) {
                                                // Expected! Parse the devices from this response
                                                val errorBody = response.errorBody()?.string()
                                                val parsed = gson.fromJson(errorBody, DeviceLimitErrorResponse::class.java)
                                                if (!parsed.devices.isNullOrEmpty()) {
                                                    android.util.Log.d("LoginScreen", "Got ${parsed.devices.size} devices from login response")
                                                    devicesFromPasswordLogin = parsed.devices
                                                    passwordValidated = true
                                                } else {
                                                    passwordDialogError = "Could not retrieve device list"
                                                }
                                            } else if (response.isSuccessful) {
                                                // Probe succeeded - user has free slot now, login with real device ID
                                                android.util.Log.d("LoginScreen", "Device limit cleared! Logging in with real device ID...")
                                                val realLoginResp = RetrofitClient.instance.login(
                                                    AuthRequest(
                                                        email = emailToUse,
                                                        password = passwordForDeviceManagement,
                                                        deviceId = deviceId,
                                                        appVersion = appVersion,
                                                        devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                        devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                        devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                                    )
                                                )
                                                if (realLoginResp.isSuccessful) {
                                                    val authBody = realLoginResp.body()
                                                    if (authBody != null && persistAuthResponse(authBody)) {
                                                        showPasswordDialogForDeviceManagement = false
                                                        deviceLimitError = null
                                                        errorMessage = null
                                                        onLoginSuccess(authBody.token ?: "")
                                                    }
                                                } else {
                                                    passwordDialogError = "Login failed: ${realLoginResp.code()}"
                                                }
                                            } else if (response.code() == 401) {
                                                passwordDialogError = "Invalid password"
                                            } else {
                                                passwordDialogError = "Login failed: ${response.code()}"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        passwordDialogError = "Network error: ${e.localizedMessage}"
                                    } finally {
                                        isPasswordDialogLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            enabled = passwordForDeviceManagement.isNotBlank() && !isPasswordDialogLoading
                        ) {
                            if (isPasswordDialogLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = PrimaryForeground
                                )
                            } else {
                                Text("Get Devices")
                            }
                        }
                    }

                    if (isPasswordDialogLoading && devicesFromPasswordLogin != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Primary
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialogForDeviceManagement = false
                        passwordDialogError = null
                        devicesFromPasswordLogin = null
                        passwordForDeviceManagement = ""
                        emailForDeviceManagement = ""
                    },
                    enabled = !isPasswordDialogLoading
                ) {
                    Text("Cancel")
                }
            },
            containerColor = CardBackground
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewLoginScreenUI() {
    LoginScreen(
        forcedLogoutReasonJson = null,
        onDismissForcedLogoutReason = { },
        onNavigateToUpgrade = { },
        onLoginSuccess = { },
        onRequires2FA = { },
        onNavigateToRegister = { },
        onNavigateToEmailVerification = { _: String, _: String? -> }
    )
}

private fun mapGoogleSignInFailure(ex: Throwable): String {
    return if (ex is ApiException) {
        val code = ex.statusCode
        val name = GoogleSignInStatusCodes.getStatusCodeString(code)
        val base = "${'$'}name (${'$'}code)"
        when (code) {
            10 -> "${'$'}base - Configuration error. Check your OAuth setup."
            8 -> "${'$'}base - Internal error. Retry."
            7 -> "${'$'}base - Network error. Check connectivity."
            12501 -> "${'$'}base - User cancelled."
            12500 -> "${'$'}base - Sign-in failed. Retry."
            else -> base
        }
    } else {
        ex.localizedMessage ?: "Unknown error"
    }
}


