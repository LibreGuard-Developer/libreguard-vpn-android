package net.libreguard.vpn.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.android.billingclient.api.ProductDetails
import net.libreguard.vpn.core.GooglePlayBillingManager
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePlayPaymentScreen(
    billingManager: GooglePlayBillingManager,
    onClose: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val billingState by billingManager.billingState.collectAsState()
    val subscriptionOptions by billingManager.subscriptionOptions.collectAsState()
    val scrollState = rememberScrollState()

    var selectedOption by remember(subscriptionOptions) {
        mutableStateOf(subscriptionOptions.firstOrNull())
    }

    // Navigate away as soon as purchase is verified by backend
    LaunchedEffect(billingState) {
        if (billingState is GooglePlayBillingManager.BillingState.PurchaseSuccess) {
            onSuccess()
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
            // Back button
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
                    text = "Subscribe to Pro",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Foreground
                )
            }

            Text(
                text = "Unlock all premium features via Google Play",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Subscription Options List
            subscriptionOptions.forEach { option ->
                val isSelected = selectedOption?.offerToken == option.offerToken

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { selectedOption = option }
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Primary else MutedForeground.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Primary.copy(alpha = 0.1f) else CardBackground
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Foreground
                                )
                                if (option.discountPercentage != null) {
                                    Surface(
                                        color = StatusConnected.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "-${option.discountPercentage}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusConnected,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = option.formattedPrice,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Primary
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Feature list
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Features:",
                        style = MaterialTheme.typography.titleMedium,
                        color = Foreground
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    listOf(
                        "Access on unlimited devices",
                        "Pro servers (faster)",
                        "Unlimited data",
                        "Kill Switch",
                        "Custom VPN configuration",
                        "Ad Blocking",
                        "Split Tunneling",
                        "Custom DNS servers"
                    ).forEach { feature ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodySmall,
                                color = Foreground
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Pending verification state
            if (billingState is GooglePlayBillingManager.BillingState.PurchasePending) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = (billingState as GooglePlayBillingManager.BillingState.PurchasePending).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Error state
            if (billingState is GooglePlayBillingManager.BillingState.Error) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Destructive.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = (billingState as GooglePlayBillingManager.BillingState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Destructive,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Loading state for product details
            if (subscriptionOptions.isEmpty() &&
                billingState !is GooglePlayBillingManager.BillingState.Error
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Loading subscription details…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Subscribe button
            val isProcessing = billingState is GooglePlayBillingManager.BillingState.Connecting ||
                    billingState is GooglePlayBillingManager.BillingState.PurchasePending

            Button(
                onClick = {
                    val activity = context as? Activity
                    val option = selectedOption
                    if (activity != null && option != null) {
                        billingManager.launchPurchaseFlow(activity, option.productDetails, option.offerToken)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                enabled = !isProcessing && selectedOption != null
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = PrimaryForeground,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart,
                        contentDescription = null,
                        tint = PrimaryForeground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Subscribe with Google Play",
                        style = MaterialTheme.typography.titleSmall,
                        color = PrimaryForeground
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Restore Purchases Button
            OutlinedButton(
                onClick = { billingManager.restorePurchases() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isProcessing
            ) {
                Text(
                    text = "Restore Purchases",
                    style = MaterialTheme.typography.titleSmall,
                    color = Foreground
                )
            }

            Text(
                text = "Restoring purchases will transfer any existing subscription to this account.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Policy notice
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Text(
                    text = "Payment is handled securely by Google Play. You can manage or cancel " +
                            "your subscription at any time in the Google Play Store app under " +
                            "Subscriptions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Transfer Confirmation Dialog
        if (billingState is GooglePlayBillingManager.BillingState.RequiresTransfer) {
            val transferState = billingState as GooglePlayBillingManager.BillingState.RequiresTransfer
            AlertDialog(
                onDismissRequest = { /* Require explicit action */ },
                title = { Text(text = "Subscription Found") },
                text = {
                    Text(text = "Your Google Play Pro subscription is currently linked to another LibreGuard account. Would you like to transfer it to this account?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        billingManager.transferSubscription(transferState.subscriptionId, transferState.purchaseToken)
                    }) {
                        Text("Transfer", color = Primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        billingManager.cancelTransfer()
                    }) {
                        Text("Cancel", color = MutedForeground)
                    }
                }
            )
        }
    }
}
