package net.libreguard.vpn.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.network.ClientLoginStep
import net.libreguard.vpn.ui.components.GOOGLE_NEWSLETTER_CONSENT_TEST_TAG
import net.libreguard.vpn.ui.components.NewsletterConsentCheckbox
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
    val factory = remember(context.applicationContext) { ClientLoginViewModel.Factory(context) }
    val vm = loginViewModel ?: viewModel<ClientLoginViewModel>(factory = factory)
    val state by vm.state.collectAsState()
    var identifier by remember { mutableStateOf(prefilledEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var consentSheet by remember { mutableStateOf(false) }
    var newsletterConsent by remember { mutableStateOf(false) }
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
            android.widget.Toast.makeText(context, "Few recovery codes remain. Generate new codes in account settings.",
                android.widget.Toast.LENGTH_LONG).show()
        }
        onLoginSuccess(requireNotNull(signedIn.response.token))
        vm.cancel()
    }
    BackHandler(enabled = state.step != ClientLoginStep.Start || state.busy) { password = ""; vm.cancel() }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("LibreGuard VPN", style = MaterialTheme.typography.headlineMedium, color = Foreground)
            if (!forcedLogoutReasonJson.isNullOrBlank()) {
                val message = remember(forcedLogoutReasonJson) {
                    runCatching { JSONObject(forcedLogoutReasonJson).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "Sign in again to continue."
                }
                Text(message, color = MutedForeground)
                TextButton(onClick = onDismissForcedLogoutReason) { Text("Dismiss") }
            }
            when (val step = state.step) {
                ClientLoginStep.Start -> {
                    Text("Sign in", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = identifier, onValueChange = { identifier = it },
                        label = { Text("Email or username") }, singleLine = true, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Password") }, singleLine = true, enabled = !state.busy,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password")
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.password(identifier, password); password = "" },
                        enabled = !state.busy && identifier.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                    OutlinedButton(onClick = { newsletterConsent = false; consentSheet = true },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                    TextButton(onClick = { vm.cancel(); onNavigateToForgotPassword() }, enabled = !state.busy) { Text("Forgot password?") }
                    TextButton(onClick = { vm.cancel(); onNavigateToRegister() }, enabled = !state.busy) { Text("Create an account") }
                }
                is ClientLoginStep.Factor -> TwoFactorVerificationScreen(vm, state.busy)
                is ClientLoginStep.Devices -> {
                    Text("Device limit reached", style = MaterialTheme.typography.titleLarge)
                    Text("Remove an existing device to finish signing in.")
                    Text(step.currentDevices.toString() + " active devices; limit " + step.maxDevices)
                    step.devices.forEach { device ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(device.deviceNickname?.takeIf { it.isNotBlank() }
                                    ?: device.deviceName?.takeIf { it.isNotBlank() }
                                    ?: "Device " + (device.deviceIdHash ?: device.deviceId ?: device.id.toString()).takeLast(8))
                                device.lastSeenAt?.let { Text("Last seen: " + it, style = MaterialTheme.typography.bodySmall) }
                                OutlinedButton(onClick = { vm.continueLogin(listOf(device.id)) }, enabled = !state.busy) {
                                    Text("Remove and sign in")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { vm.continueLogin() }, enabled = !state.busy) { Text("Check available slots again") }
                    TextButton(onClick = onNavigateToUpgrade, enabled = !state.busy) { Text("Upgrade plan") }
                }
                is ClientLoginStep.LinkPassword -> {
                    Text("Link your existing account", style = MaterialTheme.typography.titleLarge)
                    Text("Sign in with your LibreGuard password to link Google. Your account's two-factor authentication will still be required.")
                    OutlinedTextField(value = identifier, onValueChange = { identifier = it },
                        label = { Text("Email or username") }, singleLine = true, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("LibreGuard password") }, singleLine = true, enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.linkPassword(identifier, password); password = "" },
                        enabled = !state.busy && identifier.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) { Text("Verify existing account") }
                    Text("If your existing account only uses Apple, link Google on the website.")
                    TextButton(onClick = {
                        vm.cancel()
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://management.libreguard.net/Identity/Account/Manage/ExternalLogins")))
                    }, enabled = !state.busy) { Text("Link on the website") }
                }
                is ClientLoginStep.LinkConfirmation -> {
                    Text("Confirm Google account link", style = MaterialTheme.typography.titleLarge)
                    Text("Google account: " + step.googleEmail)
                    Text("LibreGuard account: " + step.accountIdentifier)
                    Button(onClick = vm::confirmLink, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Confirm link and continue") }
                }
                is ClientLoginStep.SignedIn -> Unit
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.errorCode == "EMAIL_NOT_VERIFIED") {
                TextButton(onClick = {
                    vm.cancel()
                    onNavigateToEmailVerification(state.verificationEmail ?: identifier, null)
                }) { Text("Verify email") }
            }
            if (state.errorCode == "SUBSCRIPTION_REQUIRED") {
                TextButton(onClick = { vm.cancel(); onNavigateToUpgrade() }) { Text("Renew subscription") }
            }
            if (state.busy) CircularProgressIndicator()
            if (state.step != ClientLoginStep.Start) {
                TextButton(onClick = { password = ""; vm.cancel() }) { Text("Cancel and start again") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (consentSheet) {
        GoogleNewsletterConsentSheet(newsletterConsent, { newsletterConsent = it }, { consentSheet = false }) {
            consentSheet = false
            password = ""
            vm.google(newsletterConsent)
        }
    }
}

private tailrec fun Context.findLoginActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findLoginActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleNewsletterConsentSheet(
    newsletterConsent: Boolean,
    onNewsletterConsentChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBackground) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Continue with Google", style = MaterialTheme.typography.titleLarge, color = Foreground)
            NewsletterConsentCheckbox(
                checked = newsletterConsent, onCheckedChange = onNewsletterConsentChange,
                testTag = GOOGLE_NEWSLETTER_CONSENT_TEST_TAG
            )
            Text("This choice applies only if Google creates a new LibreGuard account. Existing accounts will not be subscribed or have their preferences changed.",
                style = MaterialTheme.typography.bodySmall, color = MutedForeground)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onContinue, shape = RoundedCornerShape(12.dp)) { Text("Continue with Google") }
            }
        }
    }
}
