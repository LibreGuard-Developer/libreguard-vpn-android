package com.example.shadowlinkvpn.ui.screens

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shadowlinkvpn.R
import com.example.shadowlinkvpn.ui.theme.ShadowLinkVPNTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

// Expanded data class for server information
data class VpnServer(
    val name: String,
    val ip: String,
    val hostname: String,
    val country: String,
    val linkSpeed: Int, // in Gbps
    val pricingTier: String // e.g., "Free", "Premium"
)

// Helper function to load servers from the raw resource file
private fun loadServersFromRaw(context: Context): List<VpnServer> {
    return try {
        val inputStream = context.resources.openRawResource(R.raw.servers)
        val reader = InputStreamReader(inputStream)
        val serverListType = object : TypeToken<List<VpnServer>>() {}.type
        Gson().fromJson(reader, serverListType) ?: emptyList()
    } catch (e: Exception) {
        // Log the exception or handle it as needed
        e.printStackTrace()
        emptyList()
    }
}


// Helper to get a flag emoji for a country
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
fun MainScreen() {
    var isConnected by remember { mutableStateOf(false) }
    val connectionStatus = if (isConnected) "Connected" else "Disconnected"
    val statusColor = if (isConnected) Color(0xFF4CAF50) else Color.Red

    val context = LocalContext.current
    var serverList by remember { mutableStateOf<List<VpnServer>>(emptyList()) }

    LaunchedEffect(Unit) {
        serverList = loadServersFromRaw(context)
    }

    val groupedServers = serverList.groupBy { it.country }

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
            Text(connectionStatus, color = statusColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { isConnected = !isConnected },
                modifier = Modifier.size(150.dp),
                shape = RoundedCornerShape(75.dp)
            ) {
                Text(if (isConnected) "DISCONNECT" else "CONNECT", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))

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
                        ServerListItem(server = server)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // Protocol Selector
            ProtocolSelector()
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
fun ServerListItem(server: VpnServer) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                Text(server.name, fontWeight = FontWeight.Bold)
                Text(server.hostname, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("${server.linkSpeed} Gbps", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ProtocolSelector() {
    val protocols = listOf("IKEV2/IPSec", "OpenVPN", "WireGuard")
    var selectedProtocol by remember { mutableStateOf(protocols.first()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Protocol", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            protocols.forEach { protocol ->
                OutlinedButton(
                    onClick = { selectedProtocol = protocol },
                    colors = if (protocol == selectedProtocol) {
                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(protocol)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ShadowLinkVPNTheme {
        MainScreen()
    }
}