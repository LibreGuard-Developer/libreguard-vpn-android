package com.example.shadowlinkvpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A data class to hold server information
data class VpnServer(val id: Int, val name: String, val country: String)

@Composable
fun MainScreen() {
    var isConnected by remember { mutableStateOf(false) }
    val connectionStatus = if (isConnected) "Connected" else "Disconnected"
    val statusColor = if (isConnected) Color.Green else Color.Red

    // Dummy data for the server list
    val servers = listOf(
        VpnServer(1, "US-East-01", "USA"),
        VpnServer(2, "UK-London-05", "UK"),
        VpnServer(3, "JP-Tokyo-11", "Japan")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection Status and Button
        Spacer(modifier = Modifier.height(32.dp))
        Text(connectionStatus, color = statusColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { isConnected = !isConnected },
            modifier = Modifier.size(150.dp)
        ) {
            Text(if (isConnected) "DISCONNECT" else "CONNECT", fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.height(32.dp))

        // Bandwidth Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("Download: 0.0 MB")
            Text("Upload: 0.0 MB")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Divider()

        // Server List
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(servers) { server ->
                ServerListItem(server = server)
            }
        }
    }
}

@Composable
fun ServerListItem(server: VpnServer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.SignalCellular4Bar,
            contentDescription = "Latency",
            tint = Color.Green, // Placeholder color
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(server.name, fontWeight = FontWeight.Bold)
            Text(server.country, style = MaterialTheme.typography.bodySmall)
        }
    }
}