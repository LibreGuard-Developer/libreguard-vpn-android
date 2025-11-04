@file:OptIn(ExperimentalMaterial3Api::class)

package net.libreguard.vpn.ui.screens

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
    onRegistrationNeedsConfirmation: (userId: String?, email: String, token: String?) -> Unit,
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
                            val resp = RetrofitClient.instance.register(RegisterRequest(email, password))
                            if (resp.isSuccessful) {
                                val body = resp.body()
                                if (body != null) {
                                    onRegistrationNeedsConfirmation(
                                        body.userId,
                                        body.email ?: email,
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
                                        onRegistrationNeedsConfirmation(errUserId, errEmail, errToken)
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
    initialToken: String?,
    onConfirmed: (token: String) -> Unit,
    onBackToLogin: () -> Unit
) {
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var remainingMillis by remember { mutableStateOf(120_000L) }
    var expired by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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

    // Poll only if userId is present and not expired
    LaunchedEffect(userId) {
        info = context.getString(R.string.polling_waiting_confirmation)
        val uid = userId
        if (!uid.isNullOrBlank()) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 120_000 && !expired) { // 2 minutes
                try {
                    val resp = RetrofitClient.instance.checkConfirmation(uid)
                    if (resp.isSuccessful) {
                        val body = resp.body()
                        if (body?.emailConfirmed == true) {
                            val token = body.token
                            if (!token.isNullOrBlank()) {
                                onConfirmed(token)
                                break
                            } else {
                                info = body?.message ?: context.getString(R.string.email_confirmed_logged_in)
                                onBackToLogin()
                                break
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // ignore transient errors
                }
                delay(3000)
            }
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
