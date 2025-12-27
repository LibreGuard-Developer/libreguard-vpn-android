package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.Verify2faRequest
import net.libreguard.vpn.network.VerifyRecoveryRequest
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorVerificationScreen(
    email: String,
    onVerificationSuccess: (String) -> Unit,
    onBackToLogin: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRecoveryCodeInput by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf("") }
    var showRecoveryWarning by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo
            LogoWithGradient(size = 80.dp)

            Spacer(modifier = Modifier.height(24.dp))

            // Lock icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (showRecoveryCodeInput) Icons.Default.Key else Icons.Default.Lock,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = if (showRecoveryCodeInput) "Recovery Code" else "Two-Factor Authentication",
                style = MaterialTheme.typography.headlineMedium,
                color = Foreground
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
                text = if (showRecoveryCodeInput)
                    "Enter one of your saved recovery codes"
                else
                    "Enter the 6-digit code from your authenticator app",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Input field
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (showRecoveryCodeInput) "Recovery Code" else "Verification Code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (showRecoveryCodeInput) {
                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = {
                            recoveryCode = it
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter recovery code", color = MutedForeground) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = CardBackground,
                            unfocusedContainerColor = CardBackground,
                            cursorColor = Primary
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            if (it.length <= 6) {
                                code = it.filter { char -> char.isDigit() }
                                errorMessage = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("000000", color = MutedForeground) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border,
                            focusedContainerColor = CardBackground,
                            unfocusedContainerColor = CardBackground,
                            cursorColor = Primary
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Verify button
            Button(
                onClick = {
                    coroutineScope.launch {
                        isLoading = true
                        errorMessage = null
                        try {
                            if (showRecoveryCodeInput) {
                                val response = RetrofitClient.instance.verifyRecoveryCode(
                                    VerifyRecoveryRequest(email = email, recoveryCode = recoveryCode)
                                )
                                if (response.isSuccessful) {
                                    val tokenResponse = response.body()
                                    if (tokenResponse != null) {
                                        if (tokenResponse.warningRecoveryCodes == true) {
                                            showRecoveryWarning = true
                                        }
                                        onVerificationSuccess(tokenResponse.token)
                                    } else {
                                        errorMessage = "Invalid response from server"
                                    }
                                } else {
                                    errorMessage = "Invalid recovery code"
                                }
                            } else {
                                val response = RetrofitClient.instance.verify2fa(
                                    Verify2faRequest(email = email, twoFactorCode = code)
                                )
                                if (response.isSuccessful) {
                                    val tokenResponse = response.body()
                                    if (tokenResponse != null) {
                                        onVerificationSuccess(tokenResponse.token)
                                    } else {
                                        errorMessage = "Invalid response from server"
                                    }
                                } else {
                                    errorMessage = "Invalid verification code"
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
                    .height(56.dp),
                enabled = !isLoading && (if (showRecoveryCodeInput) recoveryCode.isNotBlank() else code.length == 6),
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
                        text = "Verify",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Toggle between 2FA and recovery code
            TextButton(
                onClick = {
                    showRecoveryCodeInput = !showRecoveryCodeInput
                    errorMessage = null
                    code = ""
                    recoveryCode = ""
                }
            ) {
                Icon(
                    imageVector = if (showRecoveryCodeInput) Icons.Default.Lock else Icons.Default.Key,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (showRecoveryCodeInput) "Use authenticator code" else "Use recovery code",
                    color = Primary
                )
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

            Spacer(modifier = Modifier.height(24.dp))

            // Back to login button
            TextButton(onClick = onBackToLogin) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = MutedForeground,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Back to Login",
                    color = MutedForeground
                )
            }

            // Recovery warning dialog
            if (showRecoveryWarning) {
                AlertDialog(
                    onDismissRequest = { showRecoveryWarning = false },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(StatusConnecting.copy(alpha = 0.1f), RoundedCornerShape(28.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusConnecting,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Recovery Code Used",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Foreground
                        )
                    },
                    text = {
                        Text(
                            text = "You've used a recovery code to sign in. Please generate new recovery codes in your account settings to ensure you can always access your account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedForeground
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { showRecoveryWarning = false },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("I Understand")
                        }
                    },
                    containerColor = Background,
                    shape = RoundedCornerShape(24.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTwoFactorVerificationScreenNew() {
    TwoFactorVerificationScreen(
        email = "test@example.com",
        onVerificationSuccess = { },
        onBackToLogin = { }
    )
}

