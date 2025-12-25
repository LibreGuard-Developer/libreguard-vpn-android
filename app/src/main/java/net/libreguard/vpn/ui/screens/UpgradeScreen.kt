package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(
    onNavigateBack: () -> Unit,
    onChooseCard: () -> Unit,
    onChooseMonero: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upgrade to Pro") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current Plan
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Current Plan: Free", fontWeight = FontWeight.Bold)
                    Text("Devices: 1/1", fontSize = 14.sp)
                }
            }

            // Benefits
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pro Benefits:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf(
                        "✅ OpenVPN Protocol",
                        "✅ 3 Connected Devices",
                        "✅ Premium Servers (⭐)",
                        "✅ 24/7 Priority Support"
                    ).forEach { benefit ->
                        Text(benefit, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            // Pricing
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("\$4.00", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                    Text("per month", fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Payment Methods
            Text("Choose Payment Method:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

            Button(
                onClick = onChooseCard,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("💳 Pay with Card")
            }

            Button(
                onClick = onChooseMonero,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("₿ Pay with Monero (XMR)")
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                "🔒 Secure • AES-256 Encrypted",
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

