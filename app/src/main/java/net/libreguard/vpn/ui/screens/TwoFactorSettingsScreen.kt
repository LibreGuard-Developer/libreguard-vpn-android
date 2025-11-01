package net.libreguard.vpn.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import net.libreguard.vpn.network.*
import net.libreguard.vpn.ui.components.CodeInputField
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorSettingsScreen(
    authToken: String,
    onNavigateBack: () -> Unit
) {
    var is2faEnabled by remember { mutableStateOf(false) }
    var hasAuthenticator by remember { mutableStateOf(false) }
    var recoveryCodesLeft by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showSetupDialog by remember { mutableStateOf(false) }
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sharedKey by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var showRecoveryCodes by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null

            try {
                // Use Retrofit which already handles coroutines properly
                val response = RetrofitClient.instance.get2faStatus("Bearer $authToken")

                if (response.isSuccessful) {
                    response.body()?.let { status ->
                        is2faEnabled = status.is2faEnabled
                        hasAuthenticator = status.hasAuthenticator
                        recoveryCodesLeft = status.recoveryCodesLeft
                        android.util.Log.d("2FA_DEBUG", "Successfully loaded 2FA status: is2faEnabled=$is2faEnabled, hasAuthenticator=$hasAuthenticator, recoveryCodesLeft=$recoveryCodesLeft")
                    } ?: run {
                        errorMessage = "Received empty response from server"
                        android.util.Log.e("2FA_DEBUG", "Response body was null")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    errorMessage = "Failed to load 2FA status (${response.code()}): ${errorBody ?: response.message()}"
                    android.util.Log.e("2FA_DEBUG", "API Error - Code: ${response.code()}, Body: $errorBody")
                }
            } catch (e: com.google.gson.JsonSyntaxException) {
                errorMessage = "Server returned invalid JSON. The API might be returning HTML or plain text instead of JSON. Check your backend endpoint."
                android.util.Log.e("2FA_DEBUG", "JsonSyntaxException", e)
            } catch (e: com.google.gson.stream.MalformedJsonException) {
                errorMessage = "Server returned malformed response. Expected JSON but got something else. This usually means the API endpoint doesn't exist or is returning an error page."
                android.util.Log.e("2FA_DEBUG", "MalformedJsonException", e)
            } catch (e: Exception) {
                errorMessage = "Failed to load 2FA status: ${e.javaClass.simpleName} - ${e.message}"
                android.util.Log.e("2FA_DEBUG", "Exception loading 2FA status", e)
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-Factor Authentication", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF00FF88))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (is2faEnabled) Color(0xFF00FF88) else Color(0xFFAAAAAA)
                            )
                            Text(
                                text = "Status: ${if (is2faEnabled) "Enabled" else "Disabled"}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        if (is2faEnabled) {
                            Text(
                                text = "Recovery codes remaining: $recoveryCodesLeft",
                                color = if (recoveryCodesLeft < 3) Color(0xFFFF6666) else Color(0xFFAAAAAA),
                                fontSize = 14.sp
                            )

                            if (recoveryCodesLeft < 3) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFFF6666),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Consider generating new recovery codes",
                                        color = Color(0xFFFF6666),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (!is2faEnabled) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.setup2fa("Bearer $authToken")
                                    if (response.isSuccessful) {
                                        response.body()?.let { setup ->
                                            sharedKey = setup.sharedKey
                                            qrCodeBitmap = generateQRCode(setup.authenticatorUri)
                                            showSetupDialog = true
                                        }
                                    } else {
                                        errorMessage = "Failed to setup 2FA"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            text = "Enable Two-Factor Authentication",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.disable2fa("Bearer $authToken")
                                    if (response.isSuccessful) {
                                        is2faEnabled = false
                                        hasAuthenticator = false
                                        recoveryCodesLeft = 0
                                    } else {
                                        errorMessage = "Failed to disable 2FA"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(
                            text = "Disable Two-Factor Authentication",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.generateRecoveryCodes("Bearer $authToken")
                                    if (response.isSuccessful) {
                                        response.body()?.let { codes ->
                                            recoveryCodes = codes.recoveryCodes
                                            recoveryCodesLeft = codes.recoveryCodes.size
                                            showRecoveryCodes = true
                                        }
                                    } else {
                                        errorMessage = "Failed to generate recovery codes"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00CCFF)),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(text = "Generate New Recovery Codes", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.reset2fa("Bearer $authToken")
                                    if (response.isSuccessful) {
                                        is2faEnabled = false
                                        hasAuthenticator = false
                                        recoveryCodesLeft = 0
                                    } else {
                                        errorMessage = "Failed to reset authenticator"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6666)),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text(text = "Reset Authenticator", fontWeight = FontWeight.Bold)
                    }
                }

                errorMessage?.let { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0x33FF4444)),
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

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "About Two-Factor Authentication",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Two-factor authentication adds an extra layer of security to your account. " +
                                    "You'll need both your password and a verification code from your authenticator app to sign in.",
                            color = Color(0xFFAAAAAA),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        if (showSetupDialog) {
            SetupTwoFactorDialog(
                qrCodeBitmap = qrCodeBitmap,
                sharedKey = sharedKey,
                verificationCode = verificationCode,
                onVerificationCodeChange = { verificationCode = it },
                onConfirm = {
                    coroutineScope.launch {
                        isLoading = true
                        errorMessage = null
                        try {
                            val response = RetrofitClient.instance.enable2fa(
                                "Bearer $authToken",
                                EnableRequest(code = verificationCode)
                            )
                            if (response.isSuccessful) {
                                response.body()?.let { result ->
                                    is2faEnabled = true
                                    hasAuthenticator = true
                                    result.recoveryCodes?.let { codes ->
                                        recoveryCodes = codes
                                        recoveryCodesLeft = codes.size
                                    }
                                    showSetupDialog = false
                                    showRecoveryCodes = true
                                    verificationCode = ""
                                }
                            } else {
                                errorMessage = "Invalid verification code"
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error: ${e.localizedMessage}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                onDismiss = {
                    showSetupDialog = false
                    verificationCode = ""
                }
            )
        }

        if (showRecoveryCodes) {
            RecoveryCodesDialog(
                recoveryCodes = recoveryCodes,
                onDismiss = { showRecoveryCodes = false }
            )
        }
    }
}

@Composable
fun SetupTwoFactorDialog(
    qrCodeBitmap: Bitmap?,
    sharedKey: String,
    verificationCode: String,
    onVerificationCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Setup Authenticator",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Scan this QR code with your authenticator app:",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                qrCodeBitmap?.let { bitmap ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(200.dp)
                        )
                    }
                }

                Text(
                    "Or enter this key manually:",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = sharedKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        color = Color(0xFF6366F1),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 2
                    )
                }

                Text(
                    "Enter the 6-digit code from your app:",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )

                CodeInputField(
                    code = verificationCode,
                    onCodeChange = onVerificationCodeChange,
                    length = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = verificationCode.length == 6,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6366F1),
                            disabledContainerColor = Color(0xFF475569)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Verify & Enable", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun RecoveryCodesDialog(
    recoveryCodes: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A2A2A),
        title = {
            Text("Recovery Codes", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Save these recovery codes in a safe place. You can use them to access your account if you lose access to your authenticator app.",
                    color = Color(0xFFFF6666),
                    fontSize = 14.sp
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        recoveryCodes.forEach { code ->
                            Text(
                                text = code,
                                color = Color(0xFF00FF88),
                                fontSize = 14.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }

                Text(
                    "Each code can only be used once.",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88))
            ) {
                Text("I've Saved These Codes", color = Color.Black)
            }
        }
    )
}

fun generateQRCode(text: String, size: Int = 512): Bitmap {
    val hints = hashMapOf<EncodeHintType, Any>()
    hints[EncodeHintType.MARGIN] = 0

    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)

    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }

    return bitmap
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewTwoFactorSettingsScreen() {
    TwoFactorSettingsScreen(
        authToken = "test_token",
        onNavigateBack = { }
    )
}
