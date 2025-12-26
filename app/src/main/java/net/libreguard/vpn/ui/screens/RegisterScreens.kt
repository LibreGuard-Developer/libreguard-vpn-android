@file:OptIn(ExperimentalMaterial3Api::class)

package net.libreguard.vpn.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.DeviceIdManager
import org.json.JSONObject

private fun isValidEmail(email: String): Boolean =
    android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

private fun isValidPassword(pw: String): Boolean {
    if (pw.length < 8) return false
    val hasDigit = pw.any { it.isDigit() }
    val hasSpecial = pw.any { !it.isLetterOrDigit() }
    return hasDigit && hasSpecial
}

@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onRegistrationNeedsConfirmation: (userId: String?, email: String, password: String, token: String?) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.register_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text(stringResource(id = R.string.email_label)) },
                singleLine = true,
                isError = email.isNotBlank() && !isValidEmail(email),
                supportingText = {
                    if (email.isNotBlank() && !isValidEmail(email)) {
                        Text(stringResource(id = R.string.error_invalid_email))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(id = R.string.password_label)) },
                singleLine = true,
                isError = password.isNotBlank() && !isValidPassword(password),
                supportingText = {
                    if (password.isNotBlank() && !isValidPassword(password)) {
                        Text(stringResource(id = R.string.error_password_requirements))
                    }
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        try {
                            if (email.isBlank()) {
                                error = context.getString(R.string.error_empty_email)
                                return@launch
                            }
                            if (!isValidEmail(email)) {
                                error = context.getString(R.string.error_invalid_email)
                                return@launch
                            }
                            if (password.isBlank()) {
                                error = context.getString(R.string.error_empty_password)
                                return@launch
                            }
                            if (!isValidPassword(password)) {
                                error = context.getString(R.string.error_password_requirements)
                                return@launch
                            }
                            val resp = RetrofitClient.instance.register(
                                RegisterRequest(
                                    email = email,
                                    password = password
                                )
                            )
                            if (resp.isSuccessful) {
                                val body = resp.body()
                                if (body != null) {
                                    onRegistrationNeedsConfirmation(
                                        body.userId,
                                        body.email ?: email,
                                        password,
                                        body.emailConfirmationToken
                                    )
                                } else {
                                    error = "Empty response from server"
                                }
                            } else if (resp.code() == 409) {
                                val raw = resp.errorBody()?.string()
                                val obj = try { if (raw.isNullOrBlank()) null else JSONObject(raw) } catch (_: Throwable) { null }
                                val status = obj?.optString("accountStatus")?.lowercase()
                                val errEmail = obj?.optString("email").takeUnless { it.isNullOrBlank() } ?: email
                                val errUserId = obj?.optString("userId").takeUnless { it.isNullOrBlank() }
                                val errToken = obj?.optString("emailConfirmationToken").takeUnless { it.isNullOrBlank() }
                                val msg = obj?.optString("message")

                                when (status) {
                                    // Unverified + CORRECT password: backend has already (re)sent email. Just navigate.
                                    "unverified" -> {
                                        onRegistrationNeedsConfirmation(errUserId, errEmail, password, errToken)
                                    }
                                    // Unverified + WRONG password: do not send email, show generic low-information message
                                    "unknown" -> {
                                        error = msg ?: context.getString(R.string.registration_generic_error)
                                    }
                                    // Verified account: only show login suggestion if password is correct
                                    "exists" -> {
                                        // Verified account: only show login suggestion if password is correct
                                        val loginResponse = runCatching {
                                            RetrofitClient.instance.login(net.libreguard.vpn.network.AuthRequest(email = errEmail, password = password))
                                        }.getOrNull()
                                        if (loginResponse?.isSuccessful == true) {
                                            val auth = loginResponse.body()
                                            val looksValid = (auth?.token?.isNotBlank() == true) || (auth?.requiresTwoFactor == true)
                                            if (looksValid) {
                                                error = msg ?: context.getString(R.string.registration_account_exists_login)
                                            } else {
                                                error = context.getString(R.string.registration_generic_error)
                                            }
                                        } else {
                                            error = context.getString(R.string.registration_generic_error)
                                        }
                                    }
                                    else -> {
                                        // Generic fallback to avoid info leakage
                                        error = msg ?: context.getString(R.string.registration_generic_error)
                                    }
                                }
                            } else {
                                error = "Registration failed: ${resp.code()} ${resp.message()}"
                            }
                        } catch (t: Throwable) {
                            error = t.localizedMessage
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && isValidEmail(email) && isValidPassword(password),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(id = R.string.create_account), fontWeight = FontWeight.SemiBold)
                }
            }

            error?.let { msg ->
                Text(
                    text = msg,
                    color = Color(0xFFEF5350),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = stringResource(id = R.string.register_terms),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888888)
            )
        }
    }
}

@Composable
fun ConfirmEmailScreen(
    userId: String?,
    email: String,
    password: String,
    initialToken: String?,
    onConfirmed: (authResponse: net.libreguard.vpn.network.AuthResponse) -> Unit,
    onBackToLogin: () -> Unit
) {
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var remainingMillis by remember { mutableStateOf(120_000L) }
    var expired by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Get deviceId and appVersion for login
    val deviceIdManager = remember { DeviceIdManager(context) }
    val deviceId = remember { deviceIdManager.getDeviceId() }
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
    }
    val tokenManager = remember { RetrofitClient.getTokenManager() }

    // CRITICAL: Clear any existing tokens when entering this screen
    // This prevents the app from using an old/invalid token when resuming
    // The only valid token will come from the auto-login after email confirmation
    LaunchedEffect(Unit) {
        Log.d("ConfirmEmail", "Clearing any existing tokens to ensure clean state for auto-login")
        tokenManager.clearTokens()
        // Also clear from regular shared prefs used by ViewModel
        context.getSharedPreferences("vpn_state_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("auth_token")
            .apply()
    }

    fun persistAuthResponse(authResponse: net.libreguard.vpn.network.AuthResponse?): Boolean {
        val auth = authResponse ?: return false
        val token = auth.token
        val refreshToken = auth.refreshToken
        if (token.isNullOrBlank()) return false
        tokenManager.saveTokens(token, refreshToken ?: "")
        auth.deviceId?.let { tokenManager.saveDeviceId(it) }
        if (auth.activeDevices != null && auth.maxDevices != null) {
            tokenManager.saveDeviceMetadata(auth.activeDevices, auth.maxDevices)
        }
        return true
    }

    // Countdown timer
    LaunchedEffect(Unit) {
        val tick = 1000L
        while (isActive && remainingMillis > 0L) {
            delay(tick)
            remainingMillis -= tick
        }
        if (remainingMillis <= 0L) {
            expired = true
            info = context.getString(R.string.verification_link_expired)
        }
    }

    // Poll only if userId and token are present and not expired
    LaunchedEffect(userId, initialToken) {
        android.util.Log.d("MainActivity", "ConfirmEmail: LaunchedEffect triggered")
        Log.d("ConfirmEmail", "=== LaunchedEffect triggered ===")
        Log.d("ConfirmEmail", "userId=$userId, initialToken=${initialToken?.take(20)}, email=$email, password=${if (password.isNotBlank()) "[present]" else "[MISSING]"}, deviceId=$deviceId")

        info = context.getString(R.string.polling_waiting_confirmation)
        val uid = userId
        val token = initialToken
        Log.d("ConfirmEmail", "Starting poll: uid=$uid email=$email deviceId=$deviceId appVersion=$appVersion")

        if (!uid.isNullOrBlank() && !token.isNullOrBlank()) {
            Log.d("ConfirmEmail", "Both uid and token present - proceeding with confirm/login flow")
            try {
                // Call confirmEmail endpoint with userId and token
                val confirmResp = runCatching {
                    RetrofitClient.instance.confirmEmail(
                        net.libreguard.vpn.network.ConfirmEmailRequest(
                            userId = uid,
                            token = token
                        )
                    )
                }.getOrNull()

                Log.d("ConfirmEmail", "confirmEmail status=${confirmResp?.code()} body=${confirmResp?.body()} error=${confirmResp?.errorBody()?.string()}")

                if (confirmResp?.isSuccessful == true || confirmResp?.code() == 200 || confirmResp?.code() == 409) {
                    // Email confirmed (or already was confirmed), now perform automatic login with deviceId and appVersion
                    val loginReq = AuthRequest(
                        email = email,
                        password = password,
                        deviceId = deviceId,
                        appVersion = appVersion
                    )
                    Log.d("ConfirmEmail", "auto-login request: email=$email deviceId=$deviceId appVersion=$appVersion")
                    val loginResp = runCatching {
                        RetrofitClient.instance.login(loginReq)
                    }.getOrNull()

                    Log.d("ConfirmEmail", "auto-login status=${loginResp?.code()} body=${loginResp?.body()} error=${loginResp?.errorBody()?.string()}")

                    if (loginResp?.isSuccessful == true) {
                        val authResponse = loginResp.body()
                        if (authResponse != null && !authResponse.token.isNullOrBlank()) {
                            Log.d("ConfirmEmail", "auto-login SUCCESS: token=${authResponse.token?.take(30)}... refreshToken=${authResponse.refreshToken?.take(30)}... deviceId=${authResponse.deviceId}")

                            // Decode JWT to check for device_id claim
                            try {
                                val parts = authResponse.token!!.split(".")
                                if (parts.size >= 2) {
                                    val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                                    Log.d("ConfirmEmail", "JWT payload: $payload")
                                    if (payload.contains("device_id")) {
                                        Log.d("ConfirmEmail", "✓ JWT contains device_id claim")
                                    } else {
                                        Log.e("ConfirmEmail", "✗ JWT MISSING device_id claim! This will cause 401 on protected endpoints!")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ConfirmEmail", "Failed to decode JWT: ${e.message}")
                            }

                            val persisted = persistAuthResponse(authResponse)
                            Log.d("ConfirmEmail", "Token persisted=$persisted, now verifying token in TokenManager...")
                            val savedToken = tokenManager.getAccessToken()
                            val savedRefresh = tokenManager.getRefreshToken()
                            Log.d("ConfirmEmail", "TokenManager verification: accessToken=${savedToken?.take(30)}... refreshToken=${savedRefresh?.take(30)}...")
                            if (persisted && savedToken != null) {
                                onConfirmed(authResponse)
                            } else {
                                Log.e("ConfirmEmail", "Failed to persist token properly!")
                                onBackToLogin()
                            }
                            return@LaunchedEffect
                        }
                    } else {
                        Log.w("ConfirmEmail", "auto-login FAILED: code=${loginResp?.code()} error=${loginResp?.errorBody()?.string()}")
                    }
                    // If confirmEmail is successful but auto-login fails, navigate to login
                    onBackToLogin()
                } else {
                    error = confirmResp?.body()?.message ?: "Failed to confirm email"
                    onBackToLogin()
                }
            } catch (e: Throwable) {
                Log.e("ConfirmEmail", "Error during confirm/login", e)
                error = e.localizedMessage
                onBackToLogin()
            }
        } else if (!uid.isNullOrBlank() || email.isNotBlank()) {
            // We have userId or email but no confirmation token
            // This happens when user clicks email link in browser (email already confirmed)
            // Just try to login directly
            Log.d("ConfirmEmail", "No confirmation token but have user info - attempting direct login")
            Log.d("ConfirmEmail", "uid=$uid, email=$email, hasPassword=${password.isNotBlank()}")

            if (email.isNotBlank() && password.isNotBlank()) {
                val loginReq = AuthRequest(
                    email = email,
                    password = password,
                    deviceId = deviceId,
                    appVersion = appVersion
                )
                Log.d("ConfirmEmail", "auto-login request: email=$email deviceId=$deviceId appVersion=$appVersion")
                val loginResp = runCatching {
                    RetrofitClient.instance.login(loginReq)
                }.getOrNull()

                Log.d("ConfirmEmail", "auto-login status=${loginResp?.code()} body=${loginResp?.body()} error=${loginResp?.errorBody()?.string()}")

                if (loginResp?.isSuccessful == true) {
                    val authResponse = loginResp.body()
                    if (authResponse != null && !authResponse.token.isNullOrBlank()) {
                        Log.d("ConfirmEmail", "auto-login SUCCESS: token=${authResponse.token?.take(30)}... refreshToken=${authResponse.refreshToken?.take(30)}... deviceId=${authResponse.deviceId}")

                        // Decode JWT to check for device_id claim
                        try {
                            val parts = authResponse.token!!.split(".")
                            if (parts.size >= 2) {
                                val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                                Log.d("ConfirmEmail", "JWT payload: $payload")
                                if (payload.contains("device_id")) {
                                    Log.d("ConfirmEmail", "✓ JWT contains device_id claim")
                                } else {
                                    Log.e("ConfirmEmail", "✗ JWT MISSING device_id claim! This will cause 401 on protected endpoints!")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ConfirmEmail", "Failed to decode JWT: ${e.message}")
                        }

                        val persisted = persistAuthResponse(authResponse)
                        Log.d("ConfirmEmail", "Token persisted=$persisted, now verifying token in TokenManager...")
                        val savedToken = tokenManager.getAccessToken()
                        val savedRefresh = tokenManager.getRefreshToken()
                        Log.d("ConfirmEmail", "TokenManager verification: accessToken=${savedToken?.take(30)}... refreshToken=${savedRefresh?.take(30)}...")
                        if (persisted && savedToken != null) {
                            onConfirmed(authResponse)
                        } else {
                            Log.e("ConfirmEmail", "Failed to persist token properly!")
                            onBackToLogin()
                        }
                    }
                } else {
                    Log.w("ConfirmEmail", "auto-login FAILED: code=${loginResp?.code()} error=${loginResp?.errorBody()?.string()}")
                }
            } else {
                Log.w("ConfirmEmail", "Missing email or password for login - email=${email.isNotBlank()}, password=${password.isNotBlank()}")
                // Can't login without credentials, user needs to login manually
                onBackToLogin()
            }
        } else {
            Log.w("ConfirmEmail", "Missing uid and token - cannot proceed with confirm flow.")
            Log.w("ConfirmEmail", "Will wait for user to return to app after clicking email link...")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.confirm_email_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackToLogin) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Use Material Text instead of HtmlText for normal, readable layout
            Text(
                text = stringResource(id = R.string.confirm_email_description, email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Time remaining label
            val minutes = (remainingMillis / 1000L) / 60
            val seconds = (remainingMillis / 1000L) % 60
            val timeText = String.format("%02d:%02d", minutes, seconds)
            if (!expired) {
                Text(stringResource(id = R.string.time_remaining_label, timeText), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(text = context.getString(R.string.verification_link_expired), color = Color(0xFFEF5350), style = MaterialTheme.typography.bodySmall)
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x2222AA22)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = info ?: context.getString(R.string.polling_waiting_confirmation),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        try {
                            val resp = RetrofitClient.instance.resendConfirmation(ResendConfirmationRequest(email))
                            if (resp.isSuccessful) {
                                // Reset timer on resend
                                remainingMillis = 120_000L
                                expired = false
                                info = context.getString(R.string.confirmation_email_sent_again)
                            } else {
                                error = "Resend failed: ${resp.code()} ${resp.message()}"
                            }
                        } catch (t: Throwable) {
                            error = t.localizedMessage
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp) else Text(stringResource(id = R.string.resend_confirmation_email))
            }

            error?.let { Text(it, color = Color(0xFFEF5350)) }
        }
    }
}
