package com.example.shadowlinkvpn.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shadowlinkvpn.viewmodel.VpnViewModel
import com.example.shadowlinkvpn.viewmodel.VpnProtocol
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity

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
fun MainScreen(authToken: String) {
    val viewModel: VpnViewModel = viewModel()
    val context = LocalContext.current

    val servers by viewModel.servers.collectAsState()
    val remoteServers by viewModel.remoteServers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isLoadingServers by viewModel.isLoadingServers.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted, try connecting again
            viewModel.connectToVpn()
        }
    }

    // Set auth token and load servers
    LaunchedEffect(authToken) {
        viewModel.setAuthToken(authToken)
        viewModel.loadLocalServers(context)
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
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
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
                // Check VPN permission before connecting
                viewModel.requestVpnPermission(context) { vpnIntent ->
                    if (vpnIntent != null) {
                        // Launch permission request
                        vpnPermissionLauncher.launch(vpnIntent)
                    } else {
                        // Permission already granted, connect/disconnect
                        if (isConnected) {
                            viewModel.disconnect()
                        } else {
                            viewModel.connectToVpn()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting && selectedServer != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        )


        {
            Text(
                text = if (isConnecting) "Connecting..." else if (isConnected) "Disconnect" else "Connect",
                fontSize = 18.sp
            )
        }
// Add this button after the Connect/Disconnect button
        Button(
            onClick = { viewModel.getConnectionLogs() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show Logs")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Server List
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
                        TextButton(onClick = { viewModel.refreshServers() }) {
                            Text("Refresh")
                        }
                    }
                }

                LazyColumn {
                    val serverGroups = servers.groupBy { it.country }

                    serverGroups.forEach { (country, countryServers) ->
                        item {
                            CountryHeader(country = country)
                        }

                        items(countryServers) { server ->
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

        // Error Message
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (message.contains("Connected") || message.contains("Config received"))
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    color = if (message.contains("Connected") || message.contains("Config received"))
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun ProtocolSelector(
    selectedProtocol: VpnProtocol,
    onProtocolSelected: (VpnProtocol) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
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
                VpnProtocol.values().forEach { protocol ->
                    FilterChip(
                        onClick = { onProtocolSelected(protocol) },
                        label = { Text(protocol.displayName) },
                        selected = selectedProtocol == protocol,
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
fun ServerListItem(
    server: VpnServer,
    isSelected: Boolean,
    onServerSelected: (VpnServer) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onServerSelected(server) }
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${server.linkSpeed} Mbps • ${server.pricingTier}",
                fontSize = 12.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
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