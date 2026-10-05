package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.viewmodel.ClientLoginViewModel

@Composable
fun TwoFactorVerificationScreen(viewModel: ClientLoginViewModel, busy: Boolean) {
    var recovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    Text("Verify your account", style = MaterialTheme.typography.headlineSmall)
    Text(if (recovery) "Enter one of your unused recovery codes." else "Enter the code from your authenticator app.")
    OutlinedTextField(
        value = code, onValueChange = { code = it }, enabled = !busy, singleLine = true,
        label = { Text(if (recovery) "Recovery code" else "Verification code") },
        keyboardOptions = KeyboardOptions(keyboardType = if (recovery) KeyboardType.Text else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { viewModel.factor(code, recovery); code = "" },
        enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    TextButton(onClick = { recovery = !recovery; code = "" }, enabled = !busy) {
        Text(if (recovery) "Use authenticator code" else "Use a recovery code")
    }
    Spacer(Modifier.height(8.dp))
}
