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
                Text(
                    text = "Last updated: December 27, 2024",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
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

            // No-Logs Policy Section
            PolicySection(
                icon = Icons.Default.VisibilityOff,
                title = "No-Logs Policy",
                subtitle = "We Don't Track Your Activity",
                description = "LibreGuard operates under a strict no-logs policy. We do not monitor, record, log, or store:",
                items = listOf(
                    "Browsing history or DNS queries",
                    "Connection timestamps or duration",
                    "Bandwidth usage or traffic data",
                    "Original IP addresses while connected",
                    "Server connections or location information"
                )
            )

            // Data We Collect Section
            PolicySection(
                icon = Icons.Default.Storage,
                title = "Information We Collect",
                subtitle = "Account Information (Minimal)",
                description = "To provide our service, we collect only essential information:",
                items = listOf(
                    "Email address (for account recovery and support)",
                    "Payment information (processed by third-party providers, never stored on our servers)",
                    "Subscription status (Free or Pro plan)"
                ),
                additionalText = "We collect aggregate server load statistics (anonymized) to optimize performance, but this data cannot be linked to individual users."
            )

            // Encryption Section
            PolicySection(
                icon = Icons.Default.Lock,
                title = "Encryption & Security",
                subtitle = null,
                description = "All VPN connections use military-grade encryption:",
                items = listOf(
                    "IKEv2/IPSec: AES-256-GCM encryption with perfect forward secrecy",
                    "OpenVPN (Pro): AES-256-CBC with SHA-512 authentication",
                    "DNS leak protection and IPv6 blocking enabled by default",
                    "Kill Switch feature prevents IP address exposure if VPN drops"
                )
            )

            // Third Parties Section
            PolicySection(
                icon = Icons.Default.Cloud,
                title = "Third-Party Services",
                subtitle = null,
                description = "We work with select third-party providers who are bound by strict privacy agreements:",
                items = listOf(
                    "Payment Processors: Card payments and Monero transactions are handled by certified payment gateways. We never see or store your full payment details.",
                    "Server Infrastructure: Our VPN servers are located in secure data centers worldwide. No third party has access to user traffic or connection logs."
                )
            )

            // Data Retention Section
            PolicySection(
                icon = Icons.Default.Language,
                title = "Data Retention & Your Rights",
                subtitle = null,
                description = "You have complete control over your data:",
                items = listOf(
                    "Access: Request a copy of your account information at any time",
                    "Deletion: Delete your account and all associated data permanently",
                    "Portability: Export your data in a machine-readable format"
                ),
                additionalText = "Account information is retained only while your account is active. Upon deletion, all data is permanently removed within 30 days."
            )

            // Jurisdiction Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Secondary.copy(alpha = 0.5f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Jurisdiction & Legal Requests",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "LibreGuard operates under privacy-friendly jurisdiction. We cannot comply with data requests for information we don't collect or store. Our no-logs policy means there is no user activity data to provide, even if legally compelled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
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

@Composable
private fun PolicySection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    description: String,
    items: List<String>,
    additionalText: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Foreground
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CardBackground,
            border = ButtonDefaults.outlinedButtonBorder(enabled = true)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                }
                if (additionalText != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = additionalText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
            }
        }
    }
}

