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
import net.libreguard.vpn.ui.components.ScreenHeader
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
        ScreenHeader(
            title = "Terms of Service",
            onBack = onBack,
            backLabel = "Back to Settings"
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = LibreGuardDimens.screenHorizontalPadding,
                    end = LibreGuardDimens.screenHorizontalPadding,
                    bottom = LibreGuardDimens.screenBottomPadding
                ),
            verticalArrangement = Arrangement.spacedBy(LibreGuardDimens.sectionSpacing)
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

            // Redirect Link Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://libreguard.net/Terms"))
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
                            text = "Read Full Terms of Service",
                            style = MaterialTheme.typography.titleSmall,
                            color = Foreground
                        )
                        Text(
                            text = "View the complete agreement on our website",
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
