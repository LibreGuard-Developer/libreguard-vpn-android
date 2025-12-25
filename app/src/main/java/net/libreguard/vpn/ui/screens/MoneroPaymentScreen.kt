package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import net.libreguard.vpn.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneroPaymentScreen(
    paymentAddress: String,
    xmrAmount: Double,
    usdAmount: Double,
    xmrPrice: Double,
    confirmations: Int,
    requiredConfirmations: Int,
    isLoading: Boolean,
    isWaitingForPayment: Boolean,
    hoursRemaining: Int,
    minutesRemaining: Int,
    onClose: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monero Checkout") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Send Monero (XMR)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Amount (XMR)", fontWeight = FontWeight.SemiBold)
                        Text(text = String.format("%.6f XMR", xmrAmount), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = String.format("≈ $%.2f", usdAmount))
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Payment Address", fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = paymentAddress, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(paymentAddress))
                                ContextCompat.getMainExecutor(context).execute { }
                            }) {
                                Icon(Icons.Default.CopyAll, contentDescription = "Copy entire address")
                            }
                        }
                    }
                }

                val confirmationPercent = remember(confirmations, requiredConfirmations) {
                    (confirmations.toFloat() / requiredConfirmations.coerceAtLeast(1)).coerceIn(0f, 1f)
                }

                Column {
                    Text(text = "Blockchain status", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = confirmationPercent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "$confirmations / $requiredConfirmations confirmations")
                }

                if (isWaitingForPayment) {
                    Text(
                        text = "Expires in: ${hoursRemaining}h ${minutesRemaining}m",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Waiting for wallet...")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh payment status")
                        }
                    }
                    Text(text = String.format("1 XMR ≈ $%.2f", xmrPrice))
                }
            }
        }
    }
}

