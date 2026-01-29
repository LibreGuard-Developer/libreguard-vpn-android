package net.libreguard.vpn.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.ui.theme.*

/**
 * Privacy Policy Screen
 * Based on design from PrivacyPolicy.tsx
 */
@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    fun openEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:support@libreguard.net")
        }
        context.startActivity(Intent.createChooser(intent, "Send Email"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Background,
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Back to Settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedForeground
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Privacy Policy",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Foreground
                )
            }
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Introduction Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Primary.copy(alpha = 0.05f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Primary),
                    width = 2.dp
                )
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = PrimaryForeground,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Your Privacy is Our Priority",
                            style = MaterialTheme.typography.titleMedium,
                            color = Foreground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "At LibreGuard, we believe privacy is a fundamental right. This policy explains how we protect your data and what information we do and don't collect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                }
            }

            // Redirect Link Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://libreguard.net/Privacy"))
                    context.startActivity(intent)
                }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Read Full Privacy Policy",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                        Text(
                            text = "View the complete policy on our website",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Contact Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Questions About Privacy?",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If you have questions about this Privacy Policy or how we protect your data, contact us:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "support@libreguard.net",
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary,
                        modifier = Modifier.clickable { openEmail() }
                    )
                }
            }

            // Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "This policy may be updated periodically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedForeground
                )
                Text(
                    text = "We'll notify you of significant changes via email.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
