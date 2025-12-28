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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.RemoteVpnServer
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.viewmodel.VpnProtocol
import net.libreguard.vpn.viewmodel.VpnViewModel

/**
 * Server List Screen - Select server and protocol
 * EXACTLY matching ServerList.tsx design
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
    var isRefreshing by remember { mutableStateOf(false) }

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
            .padding(bottom = 80.dp)
    ) {
        // Header with title and protocol selector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Server Locations",
                style = MaterialTheme.typography.headlineSmall,
                color = Foreground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connection Protocol Toggle
            Column {
                Text(
                    text = "Connection Protocol",
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedForeground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // IKEv2/IPSec Button
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedProtocol == VpnProtocol.IKEV2_IPSEC) Primary else CardBackground,
                        border = if (selectedProtocol != VpnProtocol.IKEV2_IPSEC)
                            ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                        onClick = { viewModel.selectProtocol(VpnProtocol.IKEV2_IPSEC) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "IKEv2/IPSec",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedProtocol == VpnProtocol.IKEV2_IPSEC) PrimaryForeground else Foreground
                            )
                        }
                    }

                    // OpenVPN Button with PRO badge
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedProtocol == VpnProtocol.OPENVPN) Primary else CardBackground,
                            border = if (selectedProtocol != VpnProtocol.OPENVPN)
                                ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
                            onClick = {
                                if (!isPro) {
                                    onNavigateToUpgrade?.invoke()
                                } else {
                                    viewModel.selectProtocol(VpnProtocol.OPENVPN)
                                }
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "OpenVPN",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selectedProtocol == VpnProtocol.OPENVPN) PrimaryForeground else Foreground
                                )
                            }
                        }
                        // PRO badge
                        if (!isPro) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp),
                                shape = RoundedCornerShape(8.dp),
                                color = Primary
                            ) {
                                Text(
                                    text = "PRO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PrimaryForeground,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Search Bar with Refresh Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                placeholder = {
                    Text(
                        "Search locations...",
                        color = MutedForeground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
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
                    focusedTextColor = Foreground,
                    unfocusedTextColor = Foreground,
                    cursorColor = Primary,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Border,
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground
                )
            )

            // Refresh Button
            val coroutineScope = rememberCoroutineScope()
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp),
                color = Primary,
                onClick = {
                    isRefreshing = true
                    viewModel.refreshServers()
                    // Reset after animation
                    coroutineScope.launch {
                        delay(1000)
                        isRefreshing = false
                    }
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val rotation by rememberInfiniteTransition(label = "refresh").animateFloat(
                        initialValue = 0f,
                        targetValue = if (isRefreshing) 360f else 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rotation"
                    )
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh servers",
                        tint = PrimaryForeground,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Favorites Section
                if (favoriteServersList.isNotEmpty() && searchQuery.isBlank()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        ) {
                            Text(
                                text = getFlagEmoji(country),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = country,
                                style = MaterialTheme.typography.titleSmall,
                                color = Foreground
                            )
                            Text(
                                text = "(${countryServers.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedForeground
                            )
                        }
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
    val ping = remember { (10..150).random() }
    val load = remember { (20..80).random() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Primary.copy(alpha = 0.05f) else CardBackground,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isSelected) Primary else Border
            )
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flag
                Text(
                    text = getFlagEmoji(server.country),
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Server info - city and country + server name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.serverName,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) Primary else Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = server.country,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                // Stats - Vertical layout
                Column(
                    modifier = Modifier.width(58.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SignalCellularAlt,
                            contentDescription = null,
                            tint = getPingColor(ping),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${ping}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = getLoadColor(load),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "$load%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedForeground
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (isFavorite) Primary else MutedForeground,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isSelected) Primary else MutedForeground,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Load bar
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Secondary)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(load / 100f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(getLoadColor(load))
                )
            }
        }
    }
}

private fun getPingColor(ping: Int): androidx.compose.ui.graphics.Color {
    return when {
        ping < 50 -> StatusConnected
        ping < 100 -> StatusConnecting
        else -> MutedForeground
    }
}

private fun getLoadColor(load: Int): androidx.compose.ui.graphics.Color {
    return when {
        load < 40 -> StatusConnected
        load < 70 -> StatusConnecting
        else -> Destructive
    }
}

private fun getFlagEmoji(country: String): String {
    return when (country.lowercase()) {
        "usa", "united states" -> "🇺🇸"
        "uk", "united kingdom" -> "🇬🇧"
        "japan" -> "🇯🇵"
        "germany" -> "🇩🇪"
        "netherlands" -> "🇳🇱"
        "canada" -> "🇨🇦"
        "france" -> "🇫🇷"
        "australia" -> "🇦🇺"
        "singapore" -> "🇸🇬"
        "switzerland" -> "🇨🇭"
        "sweden" -> "🇸🇪"
        "norway" -> "🇳🇴"
        "italy" -> "🇮🇹"
        "spain" -> "🇪🇸"
        "brazil" -> "🇧🇷"
        "india" -> "🇮🇳"
        "south korea", "korea" -> "🇰🇷"
        "hong kong" -> "🇭🇰"
        "ireland" -> "🇮🇪"
        "poland" -> "🇵🇱"
        else -> "🏳️"
    }
}

