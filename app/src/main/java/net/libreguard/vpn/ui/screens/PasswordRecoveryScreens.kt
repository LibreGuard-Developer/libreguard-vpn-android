@file:OptIn(ExperimentalMaterial3Api::class)

package net.libreguard.vpn.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import net.libreguard.vpn.network.ForgotPasswordResponse
import net.libreguard.vpn.network.ForgotPasswordRequest
import net.libreguard.vpn.network.ResetPasswordRequest
import net.libreguard.vpn.network.ResetPasswordResponse
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.ui.components.CenteredScreenHeader
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.Background
import net.libreguard.vpn.ui.theme.Border
import net.libreguard.vpn.ui.theme.CardBackground
import net.libreguard.vpn.ui.theme.Destructive
import net.libreguard.vpn.ui.theme.Foreground
import net.libreguard.vpn.ui.theme.LibreGuardDimens
import net.libreguard.vpn.ui.theme.MutedForeground
import net.libreguard.vpn.ui.theme.Primary
import net.libreguard.vpn.ui.theme.PrimaryForeground

private fun isValidRecoveryEmail(email: String): Boolean =
    android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

private fun isLocallyAcceptableResetPassword(password: String): Boolean = password.length >= 8

@Composable
fun ForgotPasswordScreen(
    initialEmail: String = "",
    onBackToLogin: () -> Unit
) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var isLoading by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val gson = remember { Gson() }
    val buttonScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(100),
        label = "forgotPasswordButtonScale"
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
                .padding(LibreGuardDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = onBackToLogin) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_to_login),
                        tint = MutedForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LogoWithGradient(size = 96.dp)

            Spacer(modifier = Modifier.height(16.dp))

            CenteredScreenHeader(
                title = stringResource(R.string.forgot_password_title),
                subtitle = stringResource(R.string.forgot_password_subtitle)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.email_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it.trim()
                        errorMessage = null
                    },
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
                    isError = email.isNotBlank() && !isValidRecoveryEmail(email),
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
                if (email.isNotBlank() && !isValidRecoveryEmail(email)) {
                    Text(
                        text = stringResource(R.string.error_invalid_email),
                        style = MaterialTheme.typography.bodySmall,
                        color = Destructive,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.forgot_password_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground,
                textAlign = TextAlign.Center
            )

            infoMessage?.let { info ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.08f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Primary.copy(alpha = 0.4f))
                    )
                ) {
                    Text(
                        text = info,
                        modifier = Modifier.padding(16.dp),
                        color = Foreground,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            errorMessage?.let { error ->
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
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = Destructive,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    keyboardController?.hide()
                    coroutineScope.launch {
                        errorMessage = null
                        infoMessage = null

                        when {
                            email.isBlank() -> {
                                errorMessage = context.getString(R.string.error_empty_email)
                                return@launch
                            }
                            !isValidRecoveryEmail(email) -> {
                                errorMessage = context.getString(R.string.error_invalid_email)
                                return@launch
                            }
                        }

                        isLoading = true
                        try {
                            val response = RetrofitClient.instance.forgotPassword(
                                ForgotPasswordRequest(email = email)
                            )
                            if (response.isSuccessful) {
                                infoMessage = response.body()?.message
                                    ?: context.getString(R.string.forgot_password_success_default)
                            } else {
                                val fallbackMessage = when (response.code()) {
                                    400 -> context.getString(R.string.error_empty_email)
                                    else -> context.getString(R.string.forgot_password_error_default)
                                }
                                val responseBody = response.errorBody()?.string().orEmpty()
                                val parsedError = responseBody.takeIf { it.isNotBlank() }?.let { body ->
                                    runCatching { gson.fromJson(body, ForgotPasswordResponse::class.java) }.getOrNull()
                                }
                                errorMessage = parsedError?.message ?: fallbackMessage
                            }
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage?.let {
                                context.getString(R.string.network_error_template, it)
                            } ?: context.getString(R.string.forgot_password_error_default)
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .scale(buttonScale),
                enabled = !isLoading && email.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground,
                    disabledContainerColor = Primary.copy(alpha = 0.5f),
                    disabledContentColor = PrimaryForeground.copy(alpha = 0.5f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = PrimaryForeground
                    )
                } else {
                    Text(text = stringResource(R.string.send_reset_link))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onBackToLogin) {
                Text(text = stringResource(R.string.back_to_login))
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun ResetPasswordScreen(
    initialEmail: String?,
    initialToken: String?,
    onBackToLogin: () -> Unit,
    onResetSuccess: (String) -> Unit
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var backendErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    val email = initialEmail.orEmpty()
    val token = initialToken.orEmpty()
    val hasValidLink = email.isNotBlank() && token.isNotBlank()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val gson = remember { Gson() }
    val buttonScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(100),
        label = "resetPasswordButtonScale"
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
                .padding(LibreGuardDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = onBackToLogin) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_to_login),
                        tint = MutedForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LogoWithGradient(size = 96.dp)

            Spacer(modifier = Modifier.height(16.dp))

            CenteredScreenHeader(
                title = stringResource(R.string.reset_password_title),
                subtitle = stringResource(R.string.reset_password_subtitle)
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (!hasValidLink) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Destructive.copy(alpha = 0.1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                    )
                ) {
                    Text(
                        text = stringResource(R.string.reset_password_invalid_link),
                        modifier = Modifier.padding(16.dp),
                        color = Destructive,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onBackToLogin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = stringResource(R.string.back_to_login))
                }

                Spacer(modifier = Modifier.height(48.dp))
                return@Column
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.email_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
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
                        disabledBorderColor = Border,
                        disabledContainerColor = CardBackground,
                        disabledTextColor = Foreground,
                        disabledLeadingIconColor = MutedForeground
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.new_password_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        errorMessage = null
                        backendErrors = emptyList()
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
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password),
                                tint = MutedForeground
                            )
                        }
                    },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    isError = errorMessage != null || backendErrors.isNotEmpty(),
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.confirm_new_password_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
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
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    isError = errorMessage != null,
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
                    text = stringResource(R.string.reset_password_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            infoMessage?.let { info ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Primary.copy(alpha = 0.08f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Primary.copy(alpha = 0.4f))
                    )
                ) {
                    Text(
                        text = info,
                        modifier = Modifier.padding(16.dp),
                        color = Foreground,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Destructive.copy(alpha = 0.1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = error,
                            color = Destructive,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (backendErrors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            backendErrors.forEach { backendError ->
                                Text(
                                    text = "• $backendError",
                                    color = Destructive,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (infoMessage != null) {
                Button(
                    onClick = { onResetSuccess(email) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = stringResource(R.string.back_to_login))
                }
            } else {
                Button(
                    onClick = {
                        keyboardController?.hide()
                        coroutineScope.launch {
                            errorMessage = null
                            infoMessage = null
                            backendErrors = emptyList()

                            when {
                                newPassword.isBlank() -> {
                                    errorMessage = context.getString(R.string.error_empty_new_password)
                                    return@launch
                                }
                                confirmPassword.isBlank() -> {
                                    errorMessage = context.getString(R.string.error_empty_confirm_password)
                                    return@launch
                                }
                                newPassword != confirmPassword -> {
                                    errorMessage = context.getString(R.string.error_passwords_do_not_match)
                                    return@launch
                                }
                                !isLocallyAcceptableResetPassword(newPassword) -> {
                                    errorMessage = context.getString(R.string.error_reset_password_too_short)
                                    return@launch
                                }
                            }

                            isLoading = true
                            try {
                                val response = RetrofitClient.instance.resetPassword(
                                    ResetPasswordRequest(
                                        email = email,
                                        token = token,
                                        newPassword = newPassword
                                    )
                                )

                                if (response.isSuccessful) {
                                    infoMessage = response.body()?.message
                                        ?: context.getString(R.string.reset_password_success_default)
                                } else {
                                    val responseBody = response.errorBody()?.string().orEmpty()
                                    val parsedError = responseBody.takeIf { it.isNotBlank() }?.let { body ->
                                        runCatching { gson.fromJson(body, ResetPasswordResponse::class.java) }.getOrNull()
                                    }
                                    backendErrors = parsedError?.errors.orEmpty()
                                    errorMessage = parsedError?.message
                                        ?: context.getString(R.string.reset_password_error_default)
                                }
                            } catch (e: Exception) {
                                errorMessage = e.localizedMessage?.let {
                                    context.getString(R.string.network_error_template, it)
                                } ?: context.getString(R.string.reset_password_error_default)
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .scale(buttonScale),
                    enabled = !isLoading && newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = PrimaryForeground,
                        disabledContainerColor = Primary.copy(alpha = 0.5f),
                        disabledContentColor = PrimaryForeground.copy(alpha = 0.5f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryForeground
                        )
                    } else {
                        Text(text = stringResource(R.string.reset_password_action))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (infoMessage == null) {
                TextButton(onClick = onBackToLogin, enabled = !isLoading) {
                    Text(text = stringResource(R.string.back_to_login))
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}



