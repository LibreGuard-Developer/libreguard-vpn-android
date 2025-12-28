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
 * Terms of Service Screen
 * Based on design from TermsOfService.tsx
 */
@Composable
fun TermsOfServiceScreen(
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
                    text = "Terms of Service",
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
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = PrimaryForeground,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Agreement to Terms",
                            style = MaterialTheme.typography.titleMedium,
                            color = Foreground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "By using LibreGuard VPN services, you agree to these Terms of Service. Please read them carefully before using our application.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                }
            }

            // Account Registration Section
            TermsSection(
                icon = Icons.Default.PersonAdd,
                title = "Account Registration & Use",
                subtitle = "Your Responsibilities",
                description = "When you create a LibreGuard account, you agree to:",
                items = listOf(
                    "Provide accurate and complete registration information",
                    "Maintain the security of your account credentials",
                    "Be at least 18 years old or have parental/guardian consent",
                    "Notify us immediately of any unauthorized account access",
                    "Not share your account with others"
                )
            )

            // Acceptable Use Section
            TermsSection(
                icon = Icons.Default.Balance,
                title = "Acceptable Use Policy",
                subtitle = "Permitted Uses",
                description = "LibreGuard is designed to protect your privacy and security while browsing the internet. You may use our service to:",
                items = listOf(
                    "Protect your privacy on public WiFi networks",
                    "Access region-restricted content you have legitimate rights to",
                    "Secure your internet connection from surveillance",
                    "Bypass censorship in restrictive countries"
                )
            )

            // Prohibited Activities Section
            ProhibitedActivitiesSection()

            // Service Availability Section
            TermsSection(
                icon = Icons.Default.Shield,
                title = "Service Availability & Limitations",
                subtitle = "Service Level",
                description = "We strive to provide reliable VPN service, but please note:",
                items = listOf(
                    "Service may be temporarily unavailable due to maintenance or technical issues",
                    "Connection speeds may vary based on server load and your location",
                    "Free plan includes 10GB monthly bandwidth limit",
                    "We reserve the right to modify service features with notice"
                )
            )

            // Payment & Subscription Section
            TermsSection(
                icon = Icons.Default.Payment,
                title = "Payment & Subscription Terms",
                subtitle = "Free & Pro Plans",
                description = "LibreGuard offers both free and paid subscription options:",
                items = listOf(
                    "Free Plan: 10GB monthly data, IKEv2/IPSec protocol, standard servers",
                    "Pro Plan (\$4/month): Unlimited data, all protocols, priority servers",
                    "Subscriptions renew automatically unless cancelled",
                    "Refunds available within 30 days of initial purchase",
                    "You may cancel your subscription at any time"
                ),
                additionalText = "Payments are processed securely through third-party providers. We accept credit cards and Monero (XMR) cryptocurrency."
            )

            // Privacy Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Privacy & No-Logs Policy",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "LibreGuard operates under a strict no-logs policy. We do not monitor, record, or store your VPN activity, browsing history, or connection data. See our Privacy Policy for complete details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
            }

            // Limitation of Liability Section
            TermsSection(
                icon = Icons.Default.Warning,
                title = "Limitation of Liability",
                subtitle = null,
                description = "To the maximum extent permitted by law:",
                items = listOf(
                    "LibreGuard is provided \"as is\" without warranties of any kind",
                    "We are not liable for any indirect, incidental, or consequential damages",
                    "You are responsible for your use of the service and any consequences",
                    "Maximum liability is limited to the amount you paid in the last 12 months"
                )
            )

            // Termination Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Account Termination",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Either party may terminate this agreement:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "You may delete your account at any time from Settings",
                            "We may suspend or terminate accounts that violate these terms",
                            "Upon termination, your data will be deleted within 30 days"
                        ).forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", color = Primary, style = MaterialTheme.typography.bodySmall)
                                Text(item, color = MutedForeground, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Changes to Terms Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Changes to These Terms",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "We may update these Terms of Service periodically. Significant changes will be communicated via email. Continued use of LibreGuard after changes indicates acceptance of the updated terms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }
            }

            // Contact Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Secondary.copy(alpha = 0.5f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Questions About These Terms?",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If you have questions or concerns about these Terms of Service, please contact us:",
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
                    text = "By using LibreGuard, you acknowledge that you have read,",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedForeground
                )
                Text(
                    text = "understood, and agree to be bound by these Terms of Service.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun TermsSection(
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

@Composable
private fun ProhibitedActivitiesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = Destructive,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Prohibited Activities",
                style = MaterialTheme.typography.titleMedium,
                color = Destructive
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Destructive.copy(alpha = 0.1f),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(Destructive.copy(alpha = 0.5f))
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "You May Not Use LibreGuard To:",
                    style = MaterialTheme.typography.titleSmall,
                    color = Foreground
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Engage in illegal activities or violate any laws",
                        "Distribute malware, viruses, or harmful software",
                        "Conduct DDoS attacks or network interference",
                        "Spam, phishing, or fraudulent activities",
                        "Harvest or collect user data without consent",
                        "Infringe on intellectual property rights",
                        "Resell or redistribute VPN service without authorization"
                    ).forEach { item ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "✗",
                                style = MaterialTheme.typography.bodySmall,
                                color = Destructive
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Secondary.copy(alpha = 0.5f),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Note:",
                    style = MaterialTheme.typography.labelMedium,
                    color = Foreground
                )
                Text(
                    text = "Violation of these terms may result in immediate account termination without refund. We reserve the right to report illegal activities to appropriate authorities.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }
        }
    }
}

