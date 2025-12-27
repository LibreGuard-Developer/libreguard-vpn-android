package net.libreguard.vpn.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.network.RemoteVpnServer
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.VpnProtocol
import net.libreguard.vpn.viewmodel.VpnViewModel

/**
 * Server List Screen - Select server and protocol
 * Based on design from ServerList.tsx
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    authToken: String,
    vpnViewModel: VpnViewModel? = null,
    onServerSelected: () -> Unit = {},
    onNavigateToUpgrade: (() -> Unit)? = null
) {
    val viewModel: VpnViewModel = vpnViewModel ?: viewModel()

    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val isLoadingServers by viewModel.isLoadingServers.collectAsState()
    val isPro by viewModel.isPro.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var favoriteServers by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(authToken) {
        viewModel.setAuthToken(authToken)
        viewModel.loadRemoteServers()
    }

    // Filter servers based on search
    val filteredServers = remember(servers, searchQuery) {
        if (searchQuery.isBlank()) {
            servers
        } else {
            servers.filter { server ->
                server.serverName.contains(searchQuery, ignoreCase = true) ||
                server.country.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Group by favorites and then by country
    val favoriteServersList = remember(filteredServers, favoriteServers) {
        filteredServers.filter { favoriteServers.contains(it.id.toString()) }
    }

    val regularServersByCountry = remember(filteredServers, favoriteServers) {
        filteredServers
            .filter { !favoriteServers.contains(it.id.toString()) }
            .groupBy { it.country }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // Header - reduced padding
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Server Locations",
                style = MaterialTheme.typography.headlineSmall,
                color = Foreground
            )
            Text(
                text = "Select a server to connect",
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }

        // Search Bar + Refresh Button in same row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search locations...", color = MutedForeground) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MutedForeground,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Border,
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground
                )
            )

            // Refresh Button inline
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                color = Primary,
                onClick = { viewModel.refreshServers() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh servers",
                        tint = PrimaryForeground,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Protocol Selector
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = CardBackground,
            border = ButtonDefaults.outlinedButtonBorder(enabled = true)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connection Protocol",
                    style = MaterialTheme.typography.titleSmall,
                    color = Foreground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VpnProtocol.entries
                        .filter { !it.displayName.equals("WireGuard", ignoreCase = true) }
                        .forEach { protocol ->
                            val isOpenVpn = protocol.displayName.equals("OpenVPN", ignoreCase = true)

                            FilterChip(
                                onClick = {
                                    if (isOpenVpn && !isPro) {
                                        // Navigate to upgrade screen for non-Pro users
                                        onNavigateToUpgrade?.invoke()
                                    } else {
                                        viewModel.selectProtocol(protocol)
                                    }
                                },
                                label = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = protocol.displayName,
                                            color = if (selectedProtocol == protocol) PrimaryForeground else Foreground
                                        )
                                        if (isOpenVpn && !isPro) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Pro",
                                                modifier = Modifier.size(12.dp),
                                                tint = if (selectedProtocol == protocol) PrimaryForeground else Primary
                                            )
                                        }
                                    }
                                },
                                selected = selectedProtocol == protocol,
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Primary,
                                    selectedLabelColor = PrimaryForeground,
                                    containerColor = Secondary,
                                    labelColor = Foreground
                                )
                            )
                        }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Server List
        if (isLoadingServers) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // Favorites Section
                if (favoriteServersList.isNotEmpty() && searchQuery.isBlank()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.labelMedium,
                                color = MutedForeground
                            )
                        }
                    }
                    items(favoriteServersList) { server ->
                        ServerCard(
                            server = server,
                            isSelected = selectedServer == server,
                            isFavorite = true,
                            onSelect = {
                                viewModel.selectServer(server)
                                onServerSelected()
                            },
                            onToggleFavorite = {
                                favoriteServers = if (favoriteServers.contains(server.id.toString())) {
                                    favoriteServers - server.id.toString()
                                } else {
                                    favoriteServers + server.id.toString()
                                }
                            }
                        )
                    }
                }

                // All Servers by Country
                regularServersByCountry.forEach { (country, countryServers) ->
                    item {
                        Text(
                            text = "${getFlagEmoji(country)} $country",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedForeground,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(countryServers) { server ->
                        ServerCard(
                            server = server,
                            isSelected = selectedServer == server,
                            isFavorite = favoriteServers.contains(server.id.toString()),
                            onSelect = {
                                viewModel.selectServer(server)
                                onServerSelected()
                            },
                            onToggleFavorite = {
                                favoriteServers = if (favoriteServers.contains(server.id.toString())) {
                                    favoriteServers - server.id.toString()
                                } else {
                                    favoriteServers + server.id.toString()
                                }
                            }
                        )
                    }
                }

                // Empty state
                if (filteredServers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank())
                                    "No servers found matching \"$searchQuery\""
                                else
                                    "No servers available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedForeground
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: RemoteVpnServer,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Primary.copy(alpha = 0.1f) else CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isSelected) Primary else Border
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flag
                Text(
                    text = getFlagEmoji(server.country),
                    style = MaterialTheme.typography.headlineMedium
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Server info
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (server.pricingTier == "Premium") {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Premium",
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = server.serverName,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) Primary else Foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = server.country,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )
                }

                // Stats
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ping (simulated)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SignalCellularAlt,
                            contentDescription = null,
                            tint = StatusConnected,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${(10..100).random()}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }

                    // Load
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val load = (20..80).random()
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = when {
                                load < 40 -> StatusConnected
                                load < 70 -> StatusConnecting
                                else -> Destructive
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "$load%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }

                    // Favorite button
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (isFavorite) Primary else MutedForeground,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Chevron
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isSelected) Primary else MutedForeground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Load bar
            Spacer(modifier = Modifier.height(12.dp))
            val load = (20..80).random()
            LinearProgressIndicator(
                progress = { load / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = when {
                    load < 40 -> StatusConnected
                    load < 70 -> StatusConnecting
                    else -> Destructive
                },
                trackColor = Secondary,
            )
        }
    }
}

private fun getFlagEmoji(country: String): String {
    return when (country) {
        "USA", "United States" -> "🇺🇸"
        "UK", "United Kingdom" -> "🇬🇧"
        "Japan" -> "🇯🇵"
        "Germany" -> "🇩🇪"
        "Netherlands" -> "🇳🇱"
        "Canada" -> "🇨🇦"
        "France" -> "🇫🇷"
        "Australia" -> "🇦🇺"
        "Singapore" -> "🇸🇬"
        else -> "🏳️"
    }
}
