package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*

private val freeFeatures = listOf(
    "Access on 1 device only" to true,
    "Free servers" to true,
    "No speed throttling" to true,
    "5GB data per month" to true,
    "Kill Switch" to true,
    "Pro servers" to false,
    "Unlimited data" to false,
    "Custom VPN configuration" to false,
    "Ad Blocking" to false,
    "Split Tunneling" to false
)

private val proFeatures = listOf(
    "Access on unlimited devices" to true,
    "Pro servers (faster)" to true,
    "Unlimited data" to true,
    "No speed throttling" to true,
    "Kill Switch" to true,
    "Custom VPN configuration" to true,
    "Ad Blocking" to true,
    "Split Tunneling" to true,
    "Custom DNS servers" to true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(
    onNavigateBack: () -> Unit,
    onChooseGooglePlay: () -> Unit
) {
    val scrollState = rememberScrollState()

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
                onClick = onNavigateBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                    text = "Upgrade to Pro",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Foreground
                )
            }

            Text(
                text = "Unlock premium features for maximum privacy and performance",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedForeground,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Free Plan Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Free Plan",
                                style = MaterialTheme.typography.titleMedium,
                                color = Foreground
                            )
                            Text(
                                text = "$0/month",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Foreground
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Secondary
                        ) {
                            Text(
                                text = "Current Plan",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedForeground,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    freeFeatures.forEach { (feature, included) ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (included) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (included) Primary else MutedForeground,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (included) Foreground else MutedForeground
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pro Plan Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Primary.copy(alpha = 0.05f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Primary)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Popular Badge
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Primary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = PrimaryForeground,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Popular",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PrimaryForeground
                                )
                            }
                        }
                    }

                    Column {
                        Text(
                            text = "Pro Plan",
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary
                        )
                        Text(
                            text = "$4/month",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    proFeatures.forEach { (feature, _) ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodySmall,
                                color = Foreground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onChooseGooglePlay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
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
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

