package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.ui.components.CenteredScreenHeader
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.ClientLoginViewModel

@Composable
fun TwoFactorVerificationScreen(viewModel: ClientLoginViewModel, busy: Boolean) {
    var recovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(LibreGuardDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LogoWithGradient(size = 80.dp)
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(64.dp).background(Primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (recovery) Icons.Default.Key else Icons.Default.Lock,
                    contentDescription = null, tint = Primary, modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            CenteredScreenHeader(
                title = if (recovery) "Recovery Code" else "Two-Factor Authentication",
                subtitle = if (recovery) "Enter one of your saved recovery codes"
                    else "Enter the 6-digit code from your authenticator app"
            )
            Spacer(Modifier.height(32.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (recovery) "Recovery Code" else "Verification Code",
                    style = MaterialTheme.typography.bodySmall, color = MutedForeground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = if (recovery) recoveryCode else code,
                    onValueChange = { value ->
                        if (recovery) recoveryCode = value
                        else if (value.length <= 6) code = value.filter { it.isDigit() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(if (recovery) "Enter recovery code" else "000000", color = MutedForeground)
                    },
                    singleLine = true, enabled = !busy, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary, unfocusedBorderColor = Border,
                        focusedContainerColor = CardBackground, unfocusedContainerColor = CardBackground,
                        cursorColor = Primary
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (recovery) KeyboardType.Text else KeyboardType.Number
                    )
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { viewModel.factor(if (recovery) recoveryCode else code, recovery) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !busy && (if (recovery) recoveryCode.isNotBlank() else code.length == 6),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary, contentColor = PrimaryForeground,
                    disabledContainerColor = Primary.copy(alpha = 0.5f),
                    disabledContentColor = PrimaryForeground.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (busy) CircularProgressIndicator(
                    Modifier.size(24.dp), color = PrimaryForeground, strokeWidth = 2.dp
                ) else Text("Verify", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { recovery = !recovery; code = ""; recoveryCode = "" },
                enabled = !busy
            ) {
                Icon(
                    if (recovery) Icons.Default.Lock else Icons.Default.Key,
                    contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (recovery) "Use authenticator code" else "Use recovery code", color = Primary)
            }
            state.error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    color = Destructive.copy(alpha = 0.1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
                    )
                ) {
                    Text(message, Modifier.padding(16.dp), color = Destructive,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = viewModel::cancel, enabled = !busy) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = MutedForeground,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back to Login", color = MutedForeground)
            }
        }
    }
}