@file:OptIn(ExperimentalMaterial3Api::class)

package net.libreguard.vpn.ui.screens

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import net.libreguard.vpn.network.*
import net.libreguard.vpn.ui.components.CenteredScreenHeader
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.components.NewsletterConsentCheckbox
import net.libreguard.vpn.ui.components.REGISTRATION_NEWSLETTER_CONSENT_TEST_TAG
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.DeviceIdManager
import net.libreguard.vpn.util.DeviceKeyManager
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
    var confirmPassword by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isConfirmPasswordVisible by remember { mutableStateOf(false) }
    var newsletterConsent by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Button press animation
    val buttonScale by animateFloatAsState(
        targetValue = if (isLoading) 1f else 1f,
        animationSpec = tween(100),
        label = "buttonScale"
    )

    val passwordsMatch = password == confirmPassword
    val canSubmit = isValidEmail(email) && isValidPassword(password) && passwordsMatch && confirmPassword.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(LibreGuardDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Back button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MutedForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Logo & Header
            LogoWithGradient(size = 96.dp)

            Spacer(modifier = Modifier.height(16.dp))

            CenteredScreenHeader(
                title = "Create Account",
                subtitle = "Join LibreGuard for secure browsing"
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
                    onValueChange = { email = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("you@example.com", color = MutedForeground) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = MutedForeground
                        )
                    },
                    isError = email.isNotBlank() && !isValidEmail(email),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                        errorBorderColor = Destructive,
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        cursorColor = Primary
                    )
                )
                if (email.isNotBlank() && !isValidEmail(email)) {
                    Text(
                        text = stringResource(id = R.string.error_invalid_email),
                        style = MaterialTheme.typography.bodySmall,
                        color = Destructive,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
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
                    isError = password.isNotBlank() && !isValidPassword(password),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                        errorBorderColor = Destructive,
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        cursorColor = Primary
                    )
                )
                Text(
                    text = "Must be at least 8 characters with a number and special character",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (password.isNotBlank() && !isValidPassword(password)) Destructive else MutedForeground,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Confirm Password Field
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Confirm Password",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
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
                        IconButton(onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible }) {
                            Icon(
                                imageVector = if (isConfirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isConfirmPasswordVisible) "Hide password" else "Show password",
                                tint = MutedForeground
                            )
                        }
                    },
                    visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = confirmPassword.isNotBlank() && !passwordsMatch,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                        errorBorderColor = Destructive,
                        focusedContainerColor = CardBackground,
                        unfocusedContainerColor = CardBackground,
                        cursorColor = Primary
                    )
                )
                if (confirmPassword.isNotBlank() && !passwordsMatch) {
                    Text(
                        text = "Passwords do not match",
                        style = MaterialTheme.typography.bodySmall,
                        color = Destructive,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Optional newsletter consent. This is intentionally independent from legal acceptance.
            NewsletterConsentCheckbox(
                checked = newsletterConsent,
                onCheckedChange = { newsletterConsent = it },
                testTag = REGISTRATION_NEWSLETTER_CONSENT_TEST_TAG
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Terms Acceptance
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground.copy(alpha = 0.5f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Border.copy(alpha = 0.5f))
                )
            ) {
                Text(
                    text = "By creating an account, you agree to our Terms of Service and Privacy Policy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Create Account Button
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
                            if (!passwordsMatch) {
                                error = "Passwords do not match"
                                return@launch
                            }
                            val resp = RetrofitClient.instance.register(
                                RegisterRequest(
                                    email = email,
                                    password = password,
                                    newsletterConsent = newsletterConsent.takeIf { it }
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
                                    "unverified" -> {
                                        onRegistrationNeedsConfirmation(errUserId, errEmail, password, errToken)
                                    }
                                    "unknown" -> {
                                        error = msg ?: context.getString(R.string.registration_generic_error)
                                    }
                                    "exists" -> {
                                        val loginResponse = runCatching {
                                            RetrofitClient.instance.login(
                                                AuthRequest(
                                                    email = errEmail,
                                                    password = password,
                                                    deviceId = DeviceIdManager(context).getDeviceId(),
                                                    appVersion = runCatching {
                                                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
                                                    }.getOrDefault("1.0"),
                                                    devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                                    devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                                    devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                                                )
                                            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .scale(buttonScale),
                enabled = !isLoading && canSubmit,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground,
                    disabledContainerColor = Primary.copy(alpha = 0.5f),
                    disabledContentColor = PrimaryForeground.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PrimaryForeground,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Create Account",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Error message
            error?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
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

            Spacer(modifier = Modifier.height(24.dp))

            // Login Link
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Already have an account? ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedForeground
                )
                Text(
                    text = "Sign in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary,
                    modifier = Modifier.clickable { onBack() }
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun ConfirmEmailScreen(
    userId: String?,
    email: String,
    password: String,
    initialToken: String?,
    onConfirmed: (authResponse: AuthResponse) -> Unit,
    onBackToLogin: () -> Unit
) {
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isResendLoading by remember { mutableStateOf(false) }
    var isLoginInProgress by remember { mutableStateOf(false) }
    var remainingMillis by remember { mutableStateOf(120_000L) }
    var expired by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val deviceIdManager = remember { DeviceIdManager(context) }
    val deviceId = remember { deviceIdManager.getDeviceId() }
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        }.getOrDefault("1.0")
    }
    val tokenManager = remember { RetrofitClient.getTokenManager() }

    // Token clearing removed - should only occur on explicit logout to prevent interference
    // with legitimate login flows and token refresh attempts

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

    // Poll for confirmation only when a confirmation token is present; otherwise stay on waiting screen.
    LaunchedEffect(userId, initialToken) {
        info = context.getString(R.string.polling_waiting_confirmation)
        val uid = userId
        val token = initialToken

        if (!uid.isNullOrBlank() && !token.isNullOrBlank()) {
            try {
                val confirmResp = runCatching {
                    RetrofitClient.instance.confirmEmail(
                        ConfirmEmailRequest(userId = uid, token = token)
                    )
                }.getOrNull()

                if (confirmResp?.isSuccessful == true || confirmResp?.code() == 200 || confirmResp?.code() == 409) {
                    Log.d("ConfirmEmail", "Confirmation accepted (code=${confirmResp?.code()}) - attempting login")
                    val loginReq = AuthRequest(
                        email = email,
                        password = password,
                        deviceId = deviceId,
                        appVersion = appVersion,
                        devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                        devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                        devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                    )
                    val loginResp = runCatching { RetrofitClient.instance.login(loginReq) }.getOrNull()

                    if (loginResp?.isSuccessful == true) {
                        val authResponse = loginResp.body()
                        if (authResponse != null && !authResponse.token.isNullOrBlank()) {
                            val persisted = persistAuthResponse(authResponse)
                            val savedToken = tokenManager.getAccessToken()
                            if (persisted && savedToken != null) {
                                onConfirmed(authResponse)
                            } else {
                                Log.w("ConfirmEmail", "Failed to persist auth response or retrieve saved token after login. persisted=$persisted, savedToken=$savedToken")
                                error = context.getString(R.string.login_failed)
                            }
                            return@LaunchedEffect
                        } else {
                            Log.w("ConfirmEmail", "Login response missing token after confirmation")
                            error = context.getString(R.string.login_failed)
                        }
                    } else {
                        Log.w("ConfirmEmail", "Login failed after confirmation: code=${loginResp?.code()}, success=${loginResp?.isSuccessful}")
                        error = loginResp?.errorBody()?.string() ?: context.getString(R.string.login_failed)
                    }
                } else {
                    Log.w("ConfirmEmail", "Confirmation response not successful: code=${confirmResp?.code()}, message=${confirmResp?.body()?.message}")
                    error = confirmResp?.body()?.message ?: context.getString(R.string.verification_link_expired)
                }
            } catch (e: Throwable) {
                Log.e("ConfirmEmail", "Exception during email confirmation flow: ${e.message}", e)
                error = e.localizedMessage
            }
        } else if (email.isBlank()) {
            Log.w("ConfirmEmail", "No email provided, redirecting to login")
            onBackToLogin()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(LibreGuardDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Email icon with checkmark
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(40.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp)
                        .size(32.dp)
                        .background(Primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = PrimaryForeground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            CenteredScreenHeader(
                title = "Check Your Email",
                subtitle = "We've sent a confirmation link to"
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                color = Foreground
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Click the link in the email to verify your account and start using LibreGuard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Countdown Timer
                    val minutes = (remainingMillis / 1000L) / 60
                    val seconds = (remainingMillis / 1000L) % 60
                    val timeText = String.format("%02d:%02d", minutes, seconds)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (!expired) Primary.copy(alpha = 0.1f) else Secondary,
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⏱",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (!expired) Primary else MutedForeground
                            )
                            Text(
                                text = if (!expired) "Time remaining" else "Expired",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { (remainingMillis / 120_000f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Primary,
                        trackColor = Secondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Resend Button
            Button(
                onClick = {
                    scope.launch {
                        isResendLoading = true
                        error = null
                        try {
                            val resp = RetrofitClient.instance.resendConfirmation(ResendConfirmationRequest(email))
                            if (resp.isSuccessful) {
                                remainingMillis = 120_000L
                                expired = false
                                info = context.getString(R.string.confirmation_email_sent_again)
                            } else {
                                error = "Resend failed: ${resp.code()} ${resp.message()}"
                            }
                        } catch (t: Throwable) {
                            error = t.localizedMessage
                        } finally {
                            isResendLoading = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isResendLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (expired) Primary else Secondary,
                    contentColor = if (expired) PrimaryForeground else Foreground,
                    disabledContainerColor = Secondary.copy(alpha = 0.5f)
                )
            ) {
                if (isResendLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = if (expired) PrimaryForeground else Primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Resend Confirmation Email",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Back to login button
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (email.isBlank() || password.isBlank()) {
                            error = context.getString(R.string.login_failed)
                            return@launch
                        }
                        isLoginInProgress = true
                        error = null
                        try {
                            val loginReq = AuthRequest(
                                email = email,
                                password = password,
                                deviceId = deviceId,
                                appVersion = appVersion,
                                devicePublicKey = DeviceKeyManager.exportPublicKeyBase64(),
                                devicePublicKeyId = DeviceKeyManager.publicKeyId(),
                                devicePublicKeyAlgorithm = DeviceKeyManager.algorithm()
                            )
                            val loginResp = runCatching { RetrofitClient.instance.login(loginReq) }.getOrNull()
                            if (loginResp?.isSuccessful == true) {
                                val authResponse = loginResp.body()
                                if (authResponse != null && !authResponse.token.isNullOrBlank()) {
                                    val persisted = persistAuthResponse(authResponse)
                                    val savedToken = tokenManager.getAccessToken()
                                    if (persisted && savedToken != null) {
                                        onConfirmed(authResponse)
                                    } else {
                                        error = context.getString(R.string.login_failed)
                                    }
                                } else {
                                    error = context.getString(R.string.login_failed)
                                }
                            } else {
                                val code = loginResp?.code()
                                error = if (code == 401) {
                                    "Email not confirmed yet. Please tap the confirmation link, then try again."
                                } else {
                                    "Login failed: ${code ?: "unknown"}"
                                }
                            }
                        } catch (t: Throwable) {
                            error = t.localizedMessage
                        } finally {
                            isLoginInProgress = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoginInProgress,
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                if (isLoginInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "I've verified my email",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Error message
            error?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    color = Destructive,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Help text
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground.copy(alpha = 0.5f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Border.copy(alpha = 0.5f))
                )
            ) {
                Text(
                    text = "Didn't receive the email? Check your spam folder or contact support",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

