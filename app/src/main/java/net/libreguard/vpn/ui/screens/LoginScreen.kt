package net.libreguard.vpn.ui.screens

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
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import net.libreguard.vpn.network.*
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.DeviceIdManager

/**
 * Login Screen - User authentication
 * Based on design from Login.tsx
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
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

    fun persistAuthResponse(authResponse: AuthResponse?): Boolean {
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

    fun handleDeviceLimitError(errorBody: String?) {
        if (errorBody.isNullOrBlank()) {
            deviceLimitError = null
            return
        }
        deviceLimitError = try {
            gson.fromJson(errorBody, DeviceLimitErrorResponse::class.java)
        } catch (_: Exception) {
            null
        }
        errorMessage = deviceLimitError?.message ?: "Device limit reached."
    }

    val webClientId = stringResource(id = R.string.google_web_client_id)
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
                        appVersion = appVersion
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
                        409 -> handleDeviceLimitError(errorBody)
                        400 -> {
                            handleDeviceLimitError(errorBody)
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
                                appVersion = appVersion
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
                                } else if (persistAuthResponse(authResponse)) {
                                    deviceLimitError = null
                                    onLoginSuccess(authResponse!!.token!!)
                                } else {
                                    errorMessage = authResponse?.message ?: "Login failed"
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
                                    409 -> handleDeviceLimitError(errorBody)
                                    400 -> {
                                        handleDeviceLimitError(errorBody)
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

            // Error message
            errorMessage?.let { message ->
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
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewLoginScreenUI() {
    LoginScreen(
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
        val base = "$name ($code)"
        when (code) {
            10 -> "$base - Configuration error. Check your OAuth setup."
            8 -> "$base - Internal error. Retry."
            7 -> "$base - Network error. Check connectivity."
            12501 -> "$base - User cancelled."
            12500 -> "$base - Sign-in failed. Retry."
            else -> base
        }
    } else {
        ex.localizedMessage ?: "Unknown error"
    }
}

