package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.Verify2faRequest
import net.libreguard.vpn.network.VerifyRecoveryRequest
import kotlinx.coroutines.launch

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
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0A0A),
                        Color(0xFF1A1A1A),
                        Color(0xFF0A0A0A)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "2FA",
                tint = Color(0xFF00FF88),
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = if (showRecoveryCodeInput) "Recovery Code" else "Two-Factor Authentication",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
                text = if (showRecoveryCodeInput)
                    "Enter one of your recovery codes"
                else
                    "Enter the 6-digit code from your authenticator app",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Input field
            if (showRecoveryCodeInput) {
                OutlinedTextField(
                    value = recoveryCode,
                    onValueChange = {
                        recoveryCode = it
                        errorMessage = null
                    },
                    label = { Text("Recovery Code", color = Color(0xFFAAAAAA)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color(0xFFCCCCCC),
                        focusedBorderColor = Color(0xFF00FF88),
                        unfocusedBorderColor = Color(0xFF333333),
                        cursorColor = Color(0xFF00FF88)
                    ),
                    singleLine = true,
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
                    label = { Text("6-digit code", color = Color(0xFFAAAAAA)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color(0xFFCCCCCC),
                        focusedBorderColor = Color(0xFF00FF88),
                        unfocusedBorderColor = Color(0xFF333333),
                        cursorColor = Color(0xFF00FF88)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
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
                                // Verify recovery code
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
                                // Verify 2FA code
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
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF00FF88),
                                    Color(0xFF00CCFF)
                                )
                            ),
                            shape = RoundedCornerShape(28.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(
                            text = "Verify",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                Text(
                    text = if (showRecoveryCodeInput) "Use authenticator code" else "Use recovery code",
                    color = Color(0xFF00CCFF)
                )
            }

            // Error message
            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x33FF4444)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFFFF6666),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Back to login button
            TextButton(onClick = onBackToLogin) {
                Text(
                    text = "← Back to Login",
                    color = Color(0xFFAAAAAA)
                )
            }

            // Recovery warning dialog
            if (showRecoveryWarning) {
                AlertDialog(
                    onDismissRequest = { showRecoveryWarning = false },
                    title = { Text("Warning", color = Color.White) },
                    text = {
                        Text(
                            "You've used a recovery code. Please generate new recovery codes in your account settings.",
                            color = Color(0xFFCCCCCC)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showRecoveryWarning = false }) {
                            Text("OK", color = Color(0xFF00FF88))
                        }
                    },
                    containerColor = Color(0xFF2A2A2A)
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTwoFactorVerificationScreen() {
    TwoFactorVerificationScreen(
        email = "test@example.com",
        onVerificationSuccess = { },
        onBackToLogin = { }
    )
}
