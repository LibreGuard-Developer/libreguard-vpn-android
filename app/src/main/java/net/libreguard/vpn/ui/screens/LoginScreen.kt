package net.libreguard.vpn.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.network.ClientLoginStep
import net.libreguard.vpn.ui.components.CenteredScreenHeader
import net.libreguard.vpn.ui.components.LogoWithEdgeFade
import net.libreguard.vpn.ui.components.LogoWordmark
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.ClientLoginViewModel
import org.json.JSONObject

@Composable
fun LoginScreen(
    forcedLogoutReasonJson: String? = null,
    prefilledEmail: String? = null,
    onDismissForcedLogoutReason: () -> Unit = {},
    onNavigateToUpgrade: () -> Unit = {},
    onLoginSuccess: (String) -> Unit,
    onNavigateToForgotPassword: () -> Unit = {},
    onNavigateToRegister: () -> Unit,
    onNavigateToEmailVerification: (email: String, userId: String?) -> Unit,
    loginViewModel: ClientLoginViewModel? = null
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val factory = remember(context.applicationContext) { ClientLoginViewModel.Factory(context) }
    val vm = loginViewModel ?: viewModel<ClientLoginViewModel>(factory = factory)
    val state by vm.state.collectAsState()
    var identifier by remember { mutableStateOf(prefilledEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val activity = context.findLoginActivity()

    DisposableEffect(activity, vm) {
        activity?.let(vm.googleCredentials::attach)
        onDispose { activity?.let(vm.googleCredentials::detach) }
    }
    LaunchedEffect(prefilledEmail) {
        if (identifier.isBlank()) identifier = prefilledEmail.orEmpty()
    }
    LaunchedEffect(state.step) {
        val signedIn = state.step as? ClientLoginStep.SignedIn ?: return@LaunchedEffect
        if (signedIn.response.warningRecoveryCodes) {
            android.widget.Toast.makeText(
                context,
                "Few recovery codes remain. Generate new codes in account settings.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        onLoginSuccess(requireNotNull(signedIn.response.token))
        vm.cancel()
    }
    BackHandler(enabled = state.step != ClientLoginStep.Start || state.busy) {
        password = ""
        vm.cancel()
    }

    if (state.step is ClientLoginStep.Factor) {
        TwoFactorVerificationScreen(viewModel = vm, busy = state.busy)
    } else {
        val (title, subtitle) = when (val step = state.step) {
            ClientLoginStep.Start -> "Welcome Back" to "Sign in to your LibreGuard account"
            is ClientLoginStep.Factor -> "Two-Factor Authentication" to "Enter the code from your authenticator app"
            is ClientLoginStep.Devices -> "Device limit reached" to "Remove an existing device to finish signing in"
            is ClientLoginStep.LinkPassword -> "Link your existing account" to "Verify your LibreGuard password to link Google"
            is ClientLoginStep.LinkConfirmation -> "Confirm Google account link" to "Review the accounts before continuing"
            is ClientLoginStep.SignedIn -> "Signing in" to "Your account is ready"
        }
        val forcedLogoutMessage = remember(forcedLogoutReasonJson) {
            if (forcedLogoutReasonJson.isNullOrBlank()) null
            else runCatching { JSONObject(forcedLogoutReasonJson).optString("message") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Sign in again to continue."
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
            val compact = maxHeight < 720.dp
            val veryCompact = maxHeight < 620.dp
            val topSpacing = when {
                veryCompact -> 4.dp
                compact -> 8.dp
                else -> 16.dp
            }
            val logoSize = when {
                veryCompact -> 48.dp
                compact -> 60.dp
                else -> 76.dp
            }
            val wordmarkWidth = when {
                veryCompact -> 118.dp
                compact -> 138.dp
                else -> 156.dp
            }
            val wordmarkHeight = when {
                veryCompact -> 17.dp
                compact -> 20.dp
                else -> 22.dp
            }
            val brandGap = if (veryCompact) 2.dp else 4.dp
            val headerGap = when {
                veryCompact -> 6.dp
                compact -> 10.dp
                else -> 14.dp
            }
            val sectionGap = when {
                veryCompact -> 8.dp
                compact -> 12.dp
                else -> 18.dp
            }
            val fieldGap = when {
                veryCompact -> 6.dp
                compact -> 10.dp
                else -> 16.dp
            }
            val labelGap = if (veryCompact) 4.dp else if (compact) 6.dp else 8.dp
            val buttonHeight = if (compact) 48.dp else 52.dp
            val horizontalPadding =
                if (maxWidth < 360.dp) 16.dp else LibreGuardDimens.screenHorizontalPadding

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.height(topSpacing))
                LogoWithEdgeFade(size = logoSize)
                Spacer(Modifier.height(brandGap))
                LogoWordmark(width = wordmarkWidth, height = wordmarkHeight)
                Spacer(Modifier.height(headerGap))
                CenteredScreenHeader(title = title, subtitle = subtitle)

                if (forcedLogoutMessage != null) {
                    Spacer(Modifier.height(fieldGap))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = CardBackground
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(forcedLogoutMessage, color = MutedForeground, textAlign = TextAlign.Center)
                            TextButton(onClick = onDismissForcedLogoutReason) {
                                Text("Dismiss", color = Primary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(sectionGap))

                when (val step = state.step) {
                    ClientLoginStep.Start -> {
                        Column(Modifier.fillMaxWidth()) {
                            Text("Email or username", style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground, modifier = Modifier.padding(bottom = labelGap))
                            OutlinedTextField(
                                value = identifier,
                                onValueChange = { identifier = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("you@example.com", color = MutedForeground) },
                                leadingIcon = {
                                    Icon(Icons.Default.Email, contentDescription = null, tint = MutedForeground)
                                },
                                singleLine = true,
                                enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary, unfocusedBorderColor = Border,
                                    focusedContainerColor = CardBackground, unfocusedContainerColor = CardBackground,
                                    cursorColor = Primary
                                )
                            )
                        }
                        Spacer(Modifier.height(fieldGap))
                        Column(Modifier.fillMaxWidth()) {
                            Text("Password", style = MaterialTheme.typography.bodySmall, color = MutedForeground,
                                modifier = Modifier.padding(bottom = labelGap))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("••••••••", color = MutedForeground) },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MutedForeground)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !state.busy) {
                                        Icon(
                                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                            tint = MutedForeground
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                enabled = !state.busy,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary, unfocusedBorderColor = Border,
                                    focusedContainerColor = CardBackground, unfocusedContainerColor = CardBackground,
                                    cursorColor = Primary
                                )
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                "Forgot password?",
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                modifier = Modifier.clickable(enabled = !state.busy) {
                                    vm.cancel()
                                    onNavigateToForgotPassword()
                                }
                            )
                        }
                        Spacer(Modifier.height(sectionGap))
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                vm.password(identifier, password)
                                password = ""
                            },
                            modifier = Modifier.fillMaxWidth().height(buttonHeight),
                            enabled = !state.busy && identifier.isNotBlank() && password.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary, contentColor = PrimaryForeground,
                                disabledContainerColor = Primary.copy(alpha = 0.5f),
                                disabledContentColor = PrimaryForeground.copy(alpha = 0.5f)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            if (state.busy) CircularProgressIndicator(
                                Modifier.size(24.dp), color = PrimaryForeground, strokeWidth = 2.dp
                            ) else Text("Sign In", style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(sectionGap))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f), color = Border)
                            Text("Or continue with", style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground, modifier = Modifier.padding(horizontal = 16.dp))
                            HorizontalDivider(Modifier.weight(1f), color = Border)
                        }
                        Spacer(Modifier.height(sectionGap))
                        Button(
                            onClick = {
                                password = ""
                                vm.google()
                            },
                            modifier = Modifier.fillMaxWidth().height(buttonHeight),
                            enabled = !state.busy,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardBackground, contentColor = Foreground,
                                disabledContainerColor = CardBackground.copy(alpha = 0.6f),
                                disabledContentColor = MutedForeground
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                        ) {
                            Box(
                                Modifier.size(26.dp).background(Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "G",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        brush = Brush.sweepGradient(
                                            listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05),
                                                Color(0xFF34A853), Color(0xFF4285F4))
                                        ),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                buildAnnotatedString {
                                    append("Sign in with ")
                                    withStyle(SpanStyle(color = Color(0xFF4285F4))) { append("G") }
                                    withStyle(SpanStyle(color = Color(0xFFEA4335))) { append("o") }
                                    withStyle(SpanStyle(color = Color(0xFFFBBC05))) { append("o") }
                                    withStyle(SpanStyle(color = Color(0xFF4285F4))) { append("g") }
                                    withStyle(SpanStyle(color = Color(0xFF34A853))) { append("l") }
                                    withStyle(SpanStyle(color = Color(0xFFEA4335))) { append("e") }
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(fieldGap))
                        TextButton(onClick = { vm.cancel(); onNavigateToRegister() }, enabled = !state.busy) {
                            Text("Don't have an account? Create one", color = MutedForeground)
                        }
                    }
                    is ClientLoginStep.Factor -> Unit
                    is ClientLoginStep.Devices -> {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp), color = CardBackground, tonalElevation = 2.dp
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(
                                    step.currentDevices.toString() + " active devices; limit " + step.maxDevices,
                                    color = MutedForeground, style = MaterialTheme.typography.bodyMedium
                                )
                                step.devices.forEach { device ->
                                    Card(
                                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Background)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(Icons.Default.Devices, contentDescription = null, tint = Primary)
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    device.deviceNickname?.takeIf { it.isNotBlank() }
                                                        ?: device.deviceName?.takeIf { it.isNotBlank() }
                                                        ?: "Device " + (device.deviceIdHash ?: device.deviceId ?: device.id.toString()).takeLast(8),
                                                    color = Foreground, style = MaterialTheme.typography.titleSmall
                                                )
                                                device.lastSeenAt?.let {
                                                    Text("Last seen: " + it, color = MutedForeground,
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = { vm.continueLogin(listOf(device.id)) },
                                                enabled = !state.busy
                                            ) { Text("Remove") }
                                        }
                                    }
                                }
                                Button(
                                    onClick = { vm.continueLogin() },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                ) { Text("Check available slots again", color = PrimaryForeground) }
                                TextButton(
                                    onClick = onNavigateToUpgrade, enabled = !state.busy,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) { Text("Upgrade plan", color = Primary) }
                            }
                        }
                    }
                    is ClientLoginStep.LinkPassword -> {
                        Text(
                            "Sign in with your LibreGuard password to link Google. Your account's two-factor authentication will still be required.",
                            color = MutedForeground, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = identifier, onValueChange = { identifier = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Email or username") },
                            leadingIcon = { Icon(Icons.Default.Email, null, tint = MutedForeground) },
                            singleLine = true, enabled = !state.busy, shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary, unfocusedBorderColor = Border,
                                focusedContainerColor = CardBackground, unfocusedContainerColor = CardBackground,
                                cursorColor = Primary
                            )
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("LibreGuard password") },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = MutedForeground) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !state.busy) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = MutedForeground
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true, enabled = !state.busy, shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary, unfocusedBorderColor = Border,
                                focusedContainerColor = CardBackground, unfocusedContainerColor = CardBackground,
                                cursorColor = Primary
                            )
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { vm.linkPassword(identifier, password); password = "" },
                            enabled = !state.busy && identifier.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = PrimaryForeground),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) { Text("Verify existing account", style = MaterialTheme.typography.labelLarge) }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "If your existing account only uses Apple, link Google on the website.",
                            color = MutedForeground, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = {
                                vm.cancel()
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://management.libreguard.net/Identity/Account/Manage/ExternalLogins")))
                            },
                            enabled = !state.busy
                        ) { Text("Link on the website", color = Primary) }
                    }
                    is ClientLoginStep.LinkConfirmation -> {
                        Surface(
                            Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = CardBackground
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Google account", color = MutedForeground, style = MaterialTheme.typography.bodySmall)
                                Text(step.googleEmail, color = Foreground, style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider(color = Border)
                                Text("LibreGuard account", color = MutedForeground, style = MaterialTheme.typography.bodySmall)
                                Text(step.accountIdentifier, color = Foreground, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = vm::confirmLink, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = PrimaryForeground),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) { Text("Confirm link and continue", style = MaterialTheme.typography.labelLarge) }
                    }
                    is ClientLoginStep.SignedIn -> CircularProgressIndicator(color = Primary)
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
                if (state.errorCode == "EMAIL_NOT_VERIFIED") {
                    TextButton(onClick = {
                        vm.cancel()
                        onNavigateToEmailVerification(state.verificationEmail ?: identifier, null)
                    }) { Text("Verify email", color = Primary) }
                }
                if (state.errorCode == "SUBSCRIPTION_REQUIRED") {
                    TextButton(onClick = { vm.cancel(); onNavigateToUpgrade() }) {
                        Text("Renew subscription", color = Primary)
                    }
                }
                if (state.busy && state.step != ClientLoginStep.Start) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(color = Primary)
                }
                if (state.step != ClientLoginStep.Start) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { password = ""; vm.cancel() }, enabled = !state.busy) {
                        Text("Cancel and start again", color = MutedForeground)
                    }
                }
                Spacer(Modifier.height(topSpacing))
            }
        }
    }


}

private tailrec fun Context.findLoginActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findLoginActivity()
    else -> null
}
