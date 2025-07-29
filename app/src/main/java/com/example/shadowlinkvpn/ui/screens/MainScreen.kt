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
    val selectedServer by viewModel.selectedServer.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Set auth token and load servers
    LaunchedEffect(authToken) {
        viewModel.setAuthToken(authToken)
        viewModel.loadServers(context)
    }

    val connectionStatus = when {
        isConnecting -> "Connecting to ${selectedServer?.name}..."
        isConnected -> "Connected to ${selectedServer?.name}"
        else -> "Disconnected"
    }

    val statusColor = when {
        isConnecting -> Color(0xFFFFA726)
        isConnected -> Color(0xFF4CAF50)
        else -> Color.Red
    }

    val groupedServers = servers.groupBy { it.country }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ShadowLink VPN") },
                actions = {
                    IconButton(onClick = { /* TODO: Handle settings click */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection Status and Button
            Spacer(modifier = Modifier.height(24.dp))
            Text(connectionStatus, color = statusColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            // Selected server info
            selectedServer?.let { server ->
                Text(
                    text = "${server.country} - ${selectedProtocol.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else {
                        viewModel.connectToVpn()
                    }
                },
                enabled = !isConnecting && (selectedServer != null || isConnected),
                modifier = Modifier.size(150.dp),
                shape = RoundedCornerShape(75.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        if (isConnected) "DISCONNECT" else "CONNECT",
                        fontSize = 16.sp
                    )
                }
            }

            // Error message
            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { viewModel.clearError() },
                    colors = CardDefaults.cardColors(
                        containerColor = if (error.contains("Connected"))
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = if (error.contains("Connected"))
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Protocol Selector
            ProtocolSelector(
                selectedProtocol = selectedProtocol,
                onProtocolSelected = { viewModel.selectProtocol(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Server List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                groupedServers.forEach { (country, serversInCountry) ->
                    stickyHeader {
                        CountryHeader(country = country)
                    }
                    items(serversInCountry) { server ->
                        ServerListItem(
                            server = server,
                            isSelected = selectedServer?.name == server.name,
                            onServerClick = { viewModel.selectServer(server) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CountryHeader(country: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(getFlagEmoji(country), fontSize = 20.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = country,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ServerListItem(
    server: VpnServer,
    isSelected: Boolean,
    onServerClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onServerClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (server.pricingTier == "Premium") {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Premium Server",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    server.hostname,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "${server.linkSpeed} Gbps",
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ProtocolSelector(
    selectedProtocol: VpnProtocol,
    onProtocolSelected: (VpnProtocol) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Protocol", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            VpnProtocol.values().forEach { protocol ->
                FilterChip(
                    onClick = { onProtocolSelected(protocol) },
                    label = { Text(protocol.displayName) },
                    selected = selectedProtocol == protocol
                )
            }
        }
    }
}