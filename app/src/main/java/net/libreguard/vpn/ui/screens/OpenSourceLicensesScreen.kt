package net.libreguard.vpn.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.BuildConfig
import net.libreguard.vpn.ui.components.ScreenHeader
import net.libreguard.vpn.ui.theme.Background
import net.libreguard.vpn.ui.theme.Border
import net.libreguard.vpn.ui.theme.CardBackground
import net.libreguard.vpn.ui.theme.Foreground
import net.libreguard.vpn.ui.theme.LibreGuardDimens
import net.libreguard.vpn.ui.theme.MutedForeground
import net.libreguard.vpn.ui.theme.Primary
import net.libreguard.vpn.ui.theme.PrimaryForeground

private const val SOURCE_CODE_URL = "https://github.com/LibreGuard-Developer/libreguard-vpn-android"
private const val GPL_V2_URL = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html"
private const val ICS_OPENVPN_URL = "https://github.com/schwabe/ics-openvpn"
private const val STRONGSWAN_URL = "https://github.com/strongswan/strongswan"

@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        ScreenHeader(
            title = "Open Source Licenses",
            subtitle = "GPLv2 licensing, source code, and bundled project notices",
            onBack = onBack,
            backLabel = "Back to Settings"
        )

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
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                    imageVector = Icons.Default.Gavel,
                                    contentDescription = null,
                                    tint = PrimaryForeground,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LibreGuard VPN is distributed under GPL v2.0.",
                                style = MaterialTheme.typography.titleMedium,
                                color = Foreground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "If you redistribute modified versions, provide the corresponding source code and keep the GPL terms and upstream notices with your distribution.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedForeground
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { openUrl(SOURCE_CODE_URL) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = PrimaryForeground
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Source code")
                        }

                        OutlinedButton(
                            onClick = { openUrl(GPL_V2_URL) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Read GPLv2")
                        }
                    }
                }
            }

            Text(
                text = "Bundled and key third-party components",
                style = MaterialTheme.typography.labelMedium,
                color = MutedForeground
            )

            LicenseNoticeCard(
                name = "LibreGuard VPN",
                license = "GNU General Public License v2.0",
                description = "Primary Android application code in this repository.",
                linkLabel = "Open repository",
                onOpenLink = { openUrl(SOURCE_CODE_URL) }
            )

            LicenseNoticeCard(
                name = "ics-openvpn",
                license = "GPLv2 with upstream linking exceptions",
                description = "Bundled OpenVPN for Android code used for the OpenVPN protocol integration.",
                linkLabel = "View upstream project",
                onOpenLink = { openUrl(ICS_OPENVPN_URL) }
            )

            LicenseNoticeCard(
                name = "strongSwan",
                license = "GPLv2-or-later with upstream exceptions",
                description = "Bundled strongSwan Android/frontend code used for IKEv2/IPsec support.",
                linkLabel = "View upstream project",
                onOpenLink = { openUrl(STRONGSWAN_URL) }
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CardBackground,
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Other libraries used by the app",
                        style = MaterialTheme.typography.titleSmall,
                        color = Foreground
                    )
                    Text(
                        text = "LibreGuard VPN also uses Kotlin, AndroidX/Jetpack Compose, Material, Retrofit/OkHttp, Gson, ZXing, Bouncy Castle, Firebase, Google Sign-In, and Google Play Billing under their respective upstream licenses and terms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                    HorizontalDivider(color = Border)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openUrl(SOURCE_CODE_URL) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Review the repository notices and dependency inventory",
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LibreGuard v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
                Text(
                    text = "Open-source notices available in this screen and in the repository root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun LicenseNoticeCard(
    name: String,
    license: String,
    description: String,
    linkLabel: String,
    onOpenLink: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = Foreground
            )
            Text(
                text = license,
                style = MaterialTheme.typography.labelMedium,
                color = Primary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLink),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = linkLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Primary
                )
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}



