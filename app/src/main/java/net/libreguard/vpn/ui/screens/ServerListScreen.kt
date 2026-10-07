package net.libreguard.vpn.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.network.RemoteVpnServer
import net.libreguard.vpn.ui.components.ProBadge
import net.libreguard.vpn.ui.components.ScreenHeader
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.getFlagEmoji
import net.libreguard.vpn.util.getPrimaryServerLabel
import net.libreguard.vpn.util.getSecondaryServerLabel
import net.libreguard.vpn.viewmodel.VpnProtocol
import net.libreguard.vpn.viewmodel.VpnViewModel
import net.libreguard.vpn.viewmodel.ServerLatencyStatus
import kotlin.math.PI
import kotlin.math.sin

/**
 * Server List Screen - Select server and protocol
 * EXACTLY matching ServerList.tsx design
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerListScreen(
    authToken: String,
    vpnViewModel: VpnViewModel? = null,
    onServerSelected: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onNavigateToUpgrade: (() -> Unit)? = null
) {
    val viewModel: VpnViewModel = vpnViewModel ?: viewModel()

    val servers by viewModel.servers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val selectedProtocol by viewModel.selectedProtocol.collectAsState()
    val isLoadingServers by viewModel.isLoadingServers.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val serverLatencies by viewModel.serverLatencies.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var favoriteServers by remember { mutableStateOf(setOf<String>()) }
    val isRefreshing by viewModel.isRefreshingServers.collectAsState()
    val latencyStatuses by viewModel.serverLatencyStatuses.collectAsState()

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
                server.city.contains(searchQuery, ignoreCase = true) ||
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
        // Header with title and protocol selector
        Column {
            ScreenHeader(
                title = "Server Locations",
                subtitle = "Connection Protocol",
                onBack = onNavigateBack,
                backLabel = "Back"
            )

            Column(
                modifier = Modifier.padding(horizontal = LibreGuardDimens.screenHorizontalPadding)
            ) {
//                Text(
//                    text = "Connection Protocol",
//                    style = MaterialTheme.typography.labelMedium,
//                    color = MutedForeground
//                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        // IKEv2/IPSec Button
                        ProtocolToggleButton(
                            text = "IKEv2/IPSec",
                            selected = selectedProtocol == VpnProtocol.IKEV2_IPSEC,
                            onClick = { viewModel.saveDefaultProtocol(VpnProtocol.IKEV2_IPSEC) },
                            modifier = Modifier.weight(1f)
                        )

                        // OpenVPN Button with PRO badge
                        Box(modifier = Modifier.weight(1f)) {
                            ProtocolToggleButton(
                                text = "OpenVPN",
                                selected = selectedProtocol == VpnProtocol.OPENVPN,
                                onClick = {
                                    if (!isPro) {
                                        viewModel.rememberPendingUpgradeProtocolSelection(VpnProtocol.OPENVPN)
                                        onNavigateToUpgrade?.invoke()
                                    } else {
                                        viewModel.saveDefaultProtocol(VpnProtocol.OPENVPN)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            // PRO badge
                            if (!isPro) {
                                ProBadge(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Search Bar with Refresh Button
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LibreGuardDimens.screenHorizontalPadding),
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

            ServerRefreshButton(isRefreshing = isRefreshing, onRefresh = viewModel::refreshServers)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Server List
        if (isLoadingServers && servers.isEmpty()) {
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
                    .padding(horizontal = LibreGuardDimens.screenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Favorites Section
                if (favoriteServersList.isNotEmpty() && searchQuery.isBlank()) {
                    item(key = "favorites-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = spring(
                                        dampingRatio = 0.86f,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                                .padding(vertical = 8.dp)
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
                    items(favoriteServersList, key = { "server-" + it.id }) { server ->
                        ServerCard(
                            server = server,
                            latency = serverLatencies[server.id],
                            latencyStatus = latencyStatuses[server.id] ?: ServerLatencyStatus.UNAVAILABLE,
                            isSelected = selectedServer == server,
                            isFavorite = true,
                            modifier = Modifier.animateItem(
                                placementSpec = spring(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
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
                    item(key = "country-" + country) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = spring(
                                        dampingRatio = 0.86f,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                                .padding(top = 16.dp, bottom = 8.dp)
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
                    items(countryServers, key = { "server-" + it.id }) { server ->
                        ServerCard(
                            server = server,
                            latency = serverLatencies[server.id],
                            latencyStatus = latencyStatuses[server.id] ?: ServerLatencyStatus.UNAVAILABLE,
                            isSelected = selectedServer == server,
                            isFavorite = favoriteServers.contains(server.id.toString()),
                            modifier = Modifier.animateItem(
                                placementSpec = spring(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
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
                    item(key = "empty-state") {
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
private fun ProtocolToggleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Primary else Color.Transparent,
            contentColor = if (selected) PrimaryForeground else MutedForeground
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ServerCard(
    server: RemoteVpnServer,
    latency: Int?,
    latencyStatus: ServerLatencyStatus,
    isSelected: Boolean,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val load = server.load ?: 0  // Use real load from API, default to 0 if unavailable
    val isPremiumServer = server.pricingTier.equals("Premium", ignoreCase = true)

    Surface(
        modifier = modifier
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
                // Flag with PRO label
                Box(
                    modifier = Modifier.width(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // PRO label above flag
                        if (isPremiumServer) {
                            ProBadge()
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        // Flag emoji
                        Text(
                            text = getFlagEmoji(server.country),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Server info - city and server name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getPrimaryServerLabel(server),
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
                            text = getSecondaryServerLabel(server),
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
                    modifier = Modifier.width(68.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ServerLatencyReading(latency = latency, status = latencyStatus)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // Only show load if we have real data from API
                    if (server.load != null) {
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
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FavoriteStarButton(
                        isFavorite = isFavorite,
                        onToggleFavorite = onToggleFavorite
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isSelected) Primary else MutedForeground,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Load bar - only show if we have real load data
            if (server.load != null) {
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
}

@Composable
private fun getPingColor(ping: Int): Color {
    return when {
        ping <= 100 -> StatusConnected
        ping <= 200 -> Primary
        else -> MutedForeground
    }
}

@Composable
private fun getLoadColor(load: Int): Color {
    return when {
        load < 40 -> StatusConnected
        load < 70 -> StatusConnecting
        else -> Destructive
    }
}


@Composable
internal fun ServerRefreshButton(isRefreshing: Boolean, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.size(56.dp).semantics {
            contentDescription = if (isRefreshing) "Refreshing servers" else "Refresh servers"
        },
        shape = RoundedCornerShape(12.dp),
        color = Primary,
        enabled = !isRefreshing,
        onClick = onRefresh
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = PrimaryForeground,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = PrimaryForeground,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun ServerLatencyReading(latency: Int?, status: ServerLatencyStatus) {
    Column(horizontalAlignment = Alignment.End) {
        if (status == ServerLatencyStatus.REFRESHING) {
            Text("Updating", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
        } else if (latency != null) {
            val latencyColor = if (status == ServerLatencyStatus.FRESH) getPingColor(latency) else MutedForeground
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LatencySignalBars(latency = latency, status = status)
                Text(
                    text = "${latency}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = latencyColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        when (status) {
            ServerLatencyStatus.REFRESHING, ServerLatencyStatus.FRESH -> Unit
            ServerLatencyStatus.STALE -> Text("Previous", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
            ServerLatencyStatus.UNAVAILABLE -> Text("--", style = MaterialTheme.typography.labelSmall, color = MutedForeground)
        }
    }
}

@Composable
internal fun LatencySignalBars(latency: Int, status: ServerLatencyStatus) {
    val activeBars = when {
        latency <= 100 -> 4
        latency <= 200 -> 3
        else -> 1
    }
    val activeColor = if (status == ServerLatencyStatus.FRESH) getPingColor(latency) else MutedForeground
    val inactiveColor = MutedForeground.copy(alpha = 0.25f)
    val signalDescription = when {
        latency <= 100 -> "Low latency"
        latency <= 200 -> "Medium latency"
        else -> "High latency"
    }
    Canvas(
        modifier = Modifier
            .size(width = 16.dp, height = 12.dp)
            .semantics { contentDescription = signalDescription }
    ) {
        val gap = size.width * 0.1f
        val barWidth = (size.width - gap * 3) / 4
        val heights = listOf(0.34f, 0.55f, 0.76f, 1f)
        heights.forEachIndexed { index, fraction ->
            val barHeight = size.height * fraction
            drawRoundRect(
                color = if (index < activeBars) activeColor else inactiveColor,
                topLeft = Offset(x = index * (barWidth + gap), y = size.height - barHeight),
                size = Size(width = barWidth, height = barHeight),
                cornerRadius = CornerRadius(x = barWidth / 2, y = barWidth / 2)
            )
        }
    }
}


@Composable
private fun FavoriteStarButton(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    val bounceProgress = remember { Animatable(0f) }
    var hasObservedInitialValue by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        targetValue = if (isFavorite) Primary else MutedForeground,
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessLow
        ),
        label = "favorite-star-tint"
    )
    val bounceDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }

    LaunchedEffect(isFavorite) {
        if (hasObservedInitialValue) {
            bounceProgress.snapTo(0f)
            bounceProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.52f,
                    stiffness = 650f
                )
            )
        }
        hasObservedInitialValue = true
    }

    IconButton(
        onClick = onToggleFavorite,
        modifier = Modifier.size(32.dp).semantics {
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites"
        }
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    val bounce = sin(bounceProgress.value * PI).toFloat()
                    val scale = 1f + 0.2f * bounce
                    scaleX = scale
                    scaleY = scale
                    translationY = -bounceDistance * bounce
                },
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = isFavorite,
                animationSpec = tween(durationMillis = 150),
                label = "favorite-star-symbol"
            ) { favorite ->
                Icon(
                    imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
