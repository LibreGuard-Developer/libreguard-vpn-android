package net.libreguard.vpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.*
import net.libreguard.vpn.ui.components.CodeInputField
import net.libreguard.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorSettingsScreen(
    authToken: String,
    onNavigateBack: () -> Unit
) {
    // TokenManager is the source of truth; keep the parameter for backward compatibility,
    // but always prefer the latest stored token.
    val tokenManager = remember { RetrofitClient.getTokenManager() }
    val currentAuthToken = remember(authToken) {
        tokenManager.getAccessToken() ?: authToken
    }

    fun latestBearer(): String {
        val t = tokenManager.getAccessToken() ?: currentAuthToken
        return "Bearer $t"
    }

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
    val scrollState = rememberScrollState()

    LaunchedEffect(currentAuthToken) {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val response = RetrofitClient.instance.get2faStatus(latestBearer())
                if (response.isSuccessful) {
                    response.body()?.let { status ->
                        is2faEnabled = status.is2faEnabled
                        hasAuthenticator = status.hasAuthenticator
                        recoveryCodesLeft = status.recoveryCodesLeft
                    }
                } else {
                    errorMessage = "Failed to load 2FA status"
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
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
                .verticalScroll(scrollState)
                .padding(24.dp)
        ) {
            // Back Button
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Text(
                text = "Two-Factor Authentication",
                style = MaterialTheme.typography.headlineMedium,
                color = Foreground
            )
            Text(
                text = "Add an extra layer of security to your account",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else {
                // Status Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (is2faEnabled) Primary.copy(alpha = 0.1f) else CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(if (is2faEnabled) Primary else Border)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (is2faEnabled) Primary else Secondary,
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (is2faEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (is2faEnabled) PrimaryForeground else MutedForeground,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (is2faEnabled) "2FA Enabled" else "2FA Disabled",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (is2faEnabled) Primary else Foreground
                            )
                            if (is2faEnabled) {
                                Text(
                                    text = "$recoveryCodesLeft recovery codes remaining",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (recoveryCodesLeft < 3) Destructive else MutedForeground
                                )
                            } else {
                                Text(
                                    text = "Your account is less secure",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedForeground
                                )
                            }
                        }
                    }
                }

                // Warning for low recovery codes
                if (is2faEnabled && recoveryCodesLeft < 3) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Destructive.copy(alpha = 0.1f),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Destructive,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Low recovery codes! Generate new ones to avoid being locked out.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Destructive
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                if (!is2faEnabled) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.setup2fa(latestBearer())
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = PrimaryForeground
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Two-Factor Authentication")
                    }
                } else {
                    // Generate Recovery Codes Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.generateRecoveryCodes(latestBearer())
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = PrimaryForeground
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate New Recovery Codes")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Reset Authenticator Button
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.reset2fa(latestBearer())
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
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Smartphone,
                            contentDescription = null,
                            tint = MutedForeground,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reset Authenticator", color = Foreground)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Disable 2FA Button
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.disable2fa(latestBearer())
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
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = Destructive,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disable Two-Factor Authentication", color = Destructive)
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
                            style = MaterialTheme.typography.bodySmall,
                            color = Destructive
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Info Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "About Two-Factor Authentication",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Two-factor authentication adds an extra layer of security to your account. You'll need both your password and a verification code from your authenticator app to sign in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // Setup Dialog
        if (showSetupDialog) {
            SetupTwoFactorDialogNew(
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
                                latestBearer(),
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

        // Recovery Codes Dialog
        if (showRecoveryCodes) {
            RecoveryCodesDialogNew(
                recoveryCodes = recoveryCodes,
                onDismiss = { showRecoveryCodes = false }
            )
        }
    }
}

@Composable
private fun SetupTwoFactorDialogNew(
    qrCodeBitmap: Bitmap?,
    sharedKey: String,
    verificationCode: String,
    onVerificationCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Setup Authenticator",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Foreground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Scan this QR code with your authenticator app:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // QR Code
                qrCodeBitmap?.let { bitmap ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = androidx.compose.ui.graphics.Color.White
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .padding(16.dp)
                                .size(200.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Or enter this key manually:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Secondary
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Shared Key", sharedKey)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Shared key copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = sharedKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Enter the 6-digit code from your app:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Foreground
                )

                Spacer(modifier = Modifier.height(12.dp))

                CodeInputField(
                    code = verificationCode,
                    onCodeChange = onVerificationCodeChange,
                    length = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel", color = MutedForeground)
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = verificationCode.length == 6,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("Verify & Enable")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryCodesDialogNew(
    recoveryCodes: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Background,
        shape = RoundedCornerShape(16.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Destructive.copy(alpha = 0.1f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = Destructive,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "Save Your Recovery Codes",
                style = MaterialTheme.typography.headlineSmall,
                color = Foreground
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Destructive.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "⚠️ Store these codes safely. You can use them to access your account if you lose your authenticator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Destructive,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        recoveryCodes.forEach { code ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Recovery Code", code)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Recovery code copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = code,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Primary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Each code can only be used once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    fontStyle = FontStyle.Italic
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("I've Saved These Codes")
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
fun PreviewTwoFactorSettingsScreenNew() {
    TwoFactorSettingsScreen(
        authToken = "test_token",
        onNavigateBack = { }
    )
}
