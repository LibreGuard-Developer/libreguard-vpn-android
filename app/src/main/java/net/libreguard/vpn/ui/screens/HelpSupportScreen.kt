package net.libreguard.vpn.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
 * Help & Support Screen
 * Based on design from HelpSupport.tsx
 */
@Composable
fun HelpSupportScreen(
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
                    text = "Help & Support",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Foreground
                )
                Text(
                    text = "Get assistance with LibreGuard VPN",
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
            // Email Support Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Primary.copy(alpha = 0.05f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Primary),
                    width = 2.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Row(
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
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = PrimaryForeground,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Email Support",
                                style = MaterialTheme.typography.titleMedium,
                                color = Foreground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Currently, we offer email-only support. Our team typically responds within 24-48 hours.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { openEmail() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = PrimaryForeground
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("support@libreguard.net")
                    }
                }
            }

            // FAQ Section
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Frequently Asked Questions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedForeground
                )

                FAQItem(
                    question = "How do I connect to a VPN server?",
                    answer = "Navigate to the Server List, select your preferred location, then return to the Dashboard and tap \"Connect\". You can also use \"Quick Connect\" for the fastest server."
                )

                FAQItem(
                    question = "What's the difference between Free and Pro?",
                    answer = "Free plan includes 5GB monthly data with basic features. Pro plan offers unlimited bandwidth, faster servers, OpenVPN protocol, VPN usage outside this app, and much more!"
                )

                FAQItem(
                    question = "Is my data logged or stored?",
                    answer = "LibreGuard follows a strict no-logs policy. We don't track, collect, or store your browsing activity. See our Privacy Policy for details."
                )

                FAQItem(
                    question = "How do I enable Two-Factor Authentication?",
                    answer = "Go to Settings → Security → Two-Factor Authentication. Scan the QR code with your authenticator app and enter the verification code to enable 2FA."
                )

                FAQItem(
                    question = "What is the Kill Switch feature?",
                    answer = "Kill Switch blocks all internet traffic if your VPN connection drops unexpectedly, preventing data leaks and ensuring your privacy remains protected."
                )
            }

            // Need More Help Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Secondary.copy(alpha = 0.5f),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        tint = MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Need More Help?",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Send us an email with your questions, bug reports, or feature requests. Include your device model and app version for faster assistance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.clickable { openEmail() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "support@libreguard.net",
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary
                            )
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // App Version
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LibreGuard v${net.libreguard.vpn.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Text(
                    text = "Build ${net.libreguard.vpn.BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun FAQItem(
    question: String,
    answer: String
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Help,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = question,
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MutedForeground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

