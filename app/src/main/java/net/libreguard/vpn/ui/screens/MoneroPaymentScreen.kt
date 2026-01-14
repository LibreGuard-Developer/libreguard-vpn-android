package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*

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
    secondsRemaining: Int = 0,
    onClose: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onSuccess: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var copied by remember { mutableStateOf(false) }

    val confirmationPercent = remember(confirmations, requiredConfirmations) {
        (confirmations.toFloat() / requiredConfirmations.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // CRITICAL FIX: Trigger onSuccess callback when payment is fully confirmed
    LaunchedEffect(confirmations, requiredConfirmations) {
        if (confirmations >= requiredConfirmations && confirmations > 0 && requiredConfirmations > 0) {
            onSuccess?.invoke()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp)
        ) {
            // Back Button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LogoWithGradient(size = 40.dp)
                Text(
                    text = "Monero Payment",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Foreground
                )
            }

            Text(
                text = "Send XMR to complete your Pro subscription",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Timer Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Primary)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Payment expires in:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Foreground
                        )
                    }
                    Text(
                        text = String.format("%02d:%02d:%02d", hoursRemaining, minutesRemaining, secondsRemaining),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Amount Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Amount to send",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("₿", style = MaterialTheme.typography.headlineMedium, color = Primary)
                        Text(
                            text = String.format("%.6f", xmrAmount),
                            style = MaterialTheme.typography.headlineLarge,
                            color = Foreground
                        )
                        Text(
                            text = "XMR",
                            style = MaterialTheme.typography.titleLarge,
                            color = MutedForeground
                        )
                    }
                    Text(
                        text = String.format("≈ $%.2f USD", usdAmount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Address Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Monero Address",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Secondary
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = paymentAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = Foreground,
                                modifier = Modifier.weight(1f),
                                softWrap = true,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Visible
                            )
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(paymentAddress))
                                    copied = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy address",
                                    tint = if (copied) Primary else MutedForeground,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Confirmation Status
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Blockchain Confirmations",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                        Text(
                            text = "$confirmations / $requiredConfirmations",
                            style = MaterialTheme.typography.titleSmall,
                            color = Primary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { confirmationPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Primary,
                        trackColor = Secondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Check Payment Button
            Button(
                onClick = { onRefresh?.invoke() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = PrimaryForeground
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PrimaryForeground,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking payment...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check Payment Status")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Info Cards
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Transaction Verification Time",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monero transactions typically require around 20 minutes for blockchain confirmation. Your subscription will be activated automatically once the payment is confirmed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("₿", style = MaterialTheme.typography.titleSmall, color = Primary)
                        Text(
                            text = "Important",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "• Send exactly ${String.format("%.6f", xmrAmount)} XMR to the address above",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                        Text(
                            text = "• Do not close this page until payment is confirmed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                        Text(
                            text = "• Network fees are included in the amount shown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                        Text(
                            text = "• This address is valid for this transaction only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Exchange rate
            Text(
                text = String.format("Current rate: 1 XMR ≈ $%.2f", xmrPrice),
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

