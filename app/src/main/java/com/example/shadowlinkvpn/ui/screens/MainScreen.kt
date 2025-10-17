package com.example.shadowlinkvpn.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shadowlinkvpn.viewmodel.VpnViewModel
import com.example.shadowlinkvpn.viewmodel.VpnProtocol
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
// Add data usage imports
import com.example.shadowlinkvpn.ui.components.DataUsageProgressBar
import com.example.shadowlinkvpn.ui.components.CompactDataUsageIndicator

// Keep your existing VpnServer data class
data class VpnServer(
    val name: String,
    val ip: String,
    val hostname: String,
    val country: String,
    val linkSpeed: Int,
    val pricingTier: String
)

// Keep your existing helper function
fun getFlagEmoji(country: String): String {
    return when (country) {
        "USA" -> "🇺🇸"
        "UK" -> "🇬🇧"
        "Japan" -> "🇯🇵"
        "Germany" -> "🇩🇪"
        else -> "🏳️"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    authToken: String,
    vpnViewModel: VpnViewModel? = null,
    onLogout: (() -> Unit)? = null,
    onNavigateToTwoFactorSettings: (() -> Unit)? = null
) {
    val viewModel: VpnViewModel = vpnViewModel ?: viewModel()
    val context = LocalContext.current

    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isLoadingServers by viewModel.isLoadingServers.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val pendingKeyChainImport by viewModel.pendingKeyChainImport.collectAsState()
    val showCertPicker by viewModel.showCertPicker.collectAsState()
    val availableCertificates by viewModel.availableCertificates.collectAsState()
    val showCertSelectionDialog by viewModel.showCertSelectionDialog.collectAsState()
    val showImportCertDialog by viewModel.showImportCertDialog.collectAsState()
    val isInstallingCertificate by viewModel.isInstallingCertificate.collectAsState()

    // Data usage state
    val dataUsageInfo by viewModel.dataUsageInfo.collectAsState()

    // State for logout confirmation dialog
    var showLogoutDialog by remember { mutableStateOf(false) }

    // State for account settings menu
    var showAccountMenu by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, try connecting again
            viewModel.connectToVpn()
        }
    }

    // Launcher for KeyChain certificate installation
    val keyChainInstallLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.completeCertificateInstallation()
        } else {
            viewModel.cancelCertificateInstallation()
        }
    }

    // Set auth token and load servers
    LaunchedEffect(authToken) {
        viewModel.setAuthToken(authToken)
        viewModel.loadLocalServers(context)
    }

    // Handle certificate import UI feedback
    LaunchedEffect(pendingKeyChainImport) {
        pendingKeyChainImport?.let { intent ->
            // Automatically launch the KeyChain install intent
            keyChainInstallLauncher.launch(intent)
        }
    }
    // Automatically launch certificate picker when requested
    LaunchedEffect(showCertPicker) {
        if (showCertPicker) {
            (context as? Activity)?.let { act ->
                viewModel.launchCertPicker(act)
            }
        }
    }

    // Certificate selection dialog
    if (showCertSelectionDialog) {
        CertificateSelectionDialog(
            certificates = availableCertificates,
            onCertificateSelected = { alias ->
                viewModel.selectCertificate(alias)
            },
            onImportNewCertificate = {
                viewModel.showImportCertificateDialog()
            },
            onDismiss = {
                viewModel.dismissCertSelectionDialog()
            }
        )
    }

    // Import certificate dialog
    if (showImportCertDialog) {
        ImportCertificateDialog(
            onImport = { alias ->
                viewModel.importCertificateWithAlias(alias)
            },
            onDismiss = {
                viewModel.dismissImportCertDialog()
            }
        )
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = {
                viewModel.logout()
                showLogoutDialog = false
                // Navigate back to login screen using the callback
                onLogout?.invoke()
            },
            onDismiss = {
                showLogoutDialog = false
            }
        )
    }

    val connectionStatus = when {
        isConnecting -> "Connecting..."
        isConnected -> "Connected"
        else -> "Disconnected"
    }

    val statusColor = when {
        isConnecting -> Color(0xFFFFA726)
        isConnected -> Color(0xFF4CAF50)
        else -> Color.Red
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side - Connection status
                    Column(
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = connectionStatus,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )

                        if (selectedServer != null) {
                            Text(
                                text = "${getFlagEmoji(selectedServer!!.country)} ${selectedServer!!.name}",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Compact data usage indicator placed immediately to the right of the status to reduce vertical space and move it left
                    CompactDataUsageIndicator(
                        dataUsage = dataUsageInfo,
                        modifier = Modifier
                            .padding(start = 4.dp)
                    )

                    // Fill the rest so the logout button (anchored in box) stays at the top-right
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Protocol Selector
            ProtocolSelector(
                selectedProtocol = selectedProtocol,
                onProtocolSelected = { viewModel.selectProtocol(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connect/Disconnect Button
            Button(
                onClick = {
                    viewModel.requestVpnPermission(context) { vpnIntent ->
                        if (vpnIntent != null) {
                            vpnPermissionLauncher.launch(vpnIntent)
                        } else {
                            if (isConnected) viewModel.disconnect() else viewModel.connectToVpn()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting && !isInstallingCertificate && selectedServer != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isConnected -> MaterialTheme.colorScheme.error
                        isConnecting || isInstallingCertificate -> Color(0xFF1976D2)
                        else -> Color(0xFF2196F3)
                    },
                    contentColor = Color.White
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isConnecting || isInstallingCertificate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = when {
                            isInstallingCertificate -> "Installing Certificate..."
                            isConnecting -> "Connecting..."
                            isConnected -> "Disconnect"
                            else -> "Connect"
                        },
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.getConnectionLogs() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Show Logs") }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Servers",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isLoadingServers) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            TextButton(onClick = { viewModel.refreshServers() }) { Text("Refresh") }
                        }
                    }
                    LazyColumn {
                        val serverGroups = servers.groupBy { it.country }
                        serverGroups.forEach { (country, list) ->
                            item { CountryHeader(country) }
                            items(list) { server ->
                                ServerListItem(
                                    server = server,
                                    isSelected = selectedServer == server,
                                    onServerSelected = { viewModel.selectServer(it) }
                                )
                            }
                        }
                    }
                }
            }

            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.contains("Connected") || message.contains("Config received"))
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = if (message.contains("Connected") || message.contains("Config received"))
                            MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Certificate installation status
            if (isInstallingCertificate) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = "Installing certificate... Please check your device's certificate manager.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Settings and Logout Buttons - positioned at top right
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Account Settings Button
            IconButton(
                onClick = { showAccountMenu = true },
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Account Settings",
                    tint = Color.White
                )
            }

            // Logout Button
            LogoutButton(onClick = { showLogoutDialog = true })
        }

        // Account Settings Dropdown Menu
        DropdownMenu(
            expanded = showAccountMenu,
            onDismissRequest = { showAccountMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "2FA",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Two-Factor Authentication")
                    }
                },
                onClick = {
                    showAccountMenu = false
                    onNavigateToTwoFactorSettings?.invoke()
                }
            )
        }
    }
}

@Composable
fun ProtocolSelector(selectedProtocol: VpnProtocol, onProtocolSelected: (VpnProtocol) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Protocol",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VpnProtocol.entries.forEach { proto ->
                    FilterChip(
                        onClick = { onProtocolSelected(proto) },
                        label = { Text(proto.displayName) },
                        selected = selectedProtocol == proto,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun CountryHeader(country: String) {
    Text(
        text = "${getFlagEmoji(country)} $country",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun ServerListItem(server: VpnServer, isSelected: Boolean, onServerSelected: (VpnServer) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onServerSelected(server) }
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = server.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${server.linkSpeed} Mbps • ${server.pricingTier}",
                fontSize = 12.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificateSelectionDialog(
    certificates: List<String>,
    onCertificateSelected: (String) -> Unit,
    onImportNewCertificate: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Select Certificate",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (certificates.isEmpty()) {
                    Text(
                        text = "No certificates found. Import a certificate first.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Text(
                        text = "Available Certificates:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, false)
                            .heightIn(max = 200.dp)
                    ) {
                        items(certificates) { cert ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { onCertificateSelected(cert) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Select",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = cert,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onImportNewCertificate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Import",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import New")
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCertificateDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var certificateAlias by remember { mutableStateOf(TextFieldValue("")) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Import Certificate",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Enter a name for your certificate:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = certificateAlias,
                    onValueChange = { certificateAlias = it },
                    label = { Text("Certificate Name") },
                    placeholder = { Text("e.g., My VPN Certificate") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Note: After clicking Import, you'll be prompted to install the certificate through Android's security system.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { onImport(certificateAlias.text.trim()) },
                        enabled = certificateAlias.text.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import")
                    }
                }
            }
        }
    }
}

@Composable
fun LogoutConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.ExitToApp,
                contentDescription = "Logout",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                "Confirm Logout",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "Are you sure you want to logout? This will disconnect your VPN and clear all saved data.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53E3E), // Beautiful red
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ExitToApp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Yes, Logout",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, Color(0xFF2196F3)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF2196F3)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "No, Cancel",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2196F3)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun LogoutButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Icon(
            imageVector = Icons.Default.ExitToApp,
            contentDescription = "Logout",
            tint = Color.White
        )
    }
}
