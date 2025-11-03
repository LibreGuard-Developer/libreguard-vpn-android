@file:OptIn(ExperimentalMaterial3Api::class)

package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import net.libreguard.vpn.network.*

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
    onRegistrationNeedsConfirmation: (userId: String, email: String, token: String?) -> Unit,
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
                                        body.userId ?: "",
                                        body.email ?: email,
                                        body.emailConfirmationToken
                                    )
                                } else {
                                    error = "Empty response from server"
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
    userId: String,
    email: String,
    initialToken: String?,
    onConfirmed: (token: String) -> Unit,
    onBackToLogin: () -> Unit
) {
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Polling state
    var isPolling by remember { mutableStateOf(true) }

    // Start polling as soon as we enter this screen
    LaunchedEffect(userId) {
        val start = System.currentTimeMillis()
        isPolling = true
        info = context.getString(R.string.polling_waiting_confirmation)
        while (isPolling && System.currentTimeMillis() - start < 120_000) { // 2 minutes
            try {
                val resp = RetrofitClient.instance.checkConfirmation(userId)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.emailConfirmed == true) {
                        val token = body.token
                        if (!token.isNullOrBlank()) {
                            onConfirmed(token)
                            break
                        } else {
                            info = body?.message ?: context.getString(R.string.email_confirmed_logged_in)
                            // If backend confirms without token (unlikely by contract), navigate user back to login
                            onBackToLogin()
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore transient network errors and keep polling
            }
            delay(3000)
        }
        isPolling = false
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
            Text(
                text = stringResource(id = R.string.confirm_email_description, email),
                style = MaterialTheme.typography.bodyMedium
            )

            // Show waiting status prominently
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x2222AA22)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = info ?: context.getString(R.string.polling_waiting_confirmation),
                    modifier = Modifier.padding(16.dp)
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
