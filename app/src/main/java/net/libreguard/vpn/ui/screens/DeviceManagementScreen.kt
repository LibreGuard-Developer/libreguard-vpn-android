package net.libreguard.vpn.ui.screens

import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.libreguard.vpn.network.DeviceDto
import net.libreguard.vpn.network.ApiError
import net.libreguard.vpn.ui.components.ScreenHeader
import net.libreguard.vpn.ui.theme.*
import net.libreguard.vpn.util.DeviceIdManager
import net.libreguard.vpn.util.DeviceNameGenerator
import net.libreguard.vpn.viewmodel.DeviceManagementViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Device Management Screen - View and manage registered devices
 * Allows users to remove devices when hitting device limits
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagementScreen(
    onNavigateBack: () -> Unit,
    onDeviceRemoved: () -> Unit = {},
    preLoadedDevices: List<DeviceDto>? = null,
    viewModel: DeviceManagementViewModel = viewModel()
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val rateLimitCountdown by viewModel.rateLimitCountdown.collectAsState()
    val successMessage by viewModel.successMessage.collectAsState()

    val currentDeviceId = remember { DeviceIdManager(context).getDeviceId() }
    fun DeviceDto.remoteId(): String = (deviceIdHash?.takeIf { it.isNotBlank() } ?: deviceId).orEmpty()

    val snackbarHostState = remember { SnackbarHostState() }

    var showRemoveAllOthersDialog by remember { mutableStateOf(false) }
    var showRemoveAllInactiveDialog by remember { mutableStateOf(false) }
    var deviceToDelete by remember { mutableStateOf<DeviceDto?>(null) }

    // Load pre-loaded devices if provided (from 409 error response)
    LaunchedEffect(preLoadedDevices) {
        Log.d("DeviceManagementScreen", "========== SCREEN LAUNCHED ==========")
        Log.d("DeviceManagementScreen", "PreLoadedDevices: ${preLoadedDevices?.size ?: "null"}")

        if (preLoadedDevices != null) {
            Log.d("DeviceManagementScreen", "Loading pre-loaded devices from 409 response")
            viewModel.loadDevicesFromErrorResponse(preLoadedDevices)
        } else {
            Log.d("DeviceManagementScreen", "Fetching devices from API (no pre-loaded devices)")
            viewModel.fetchDevices()
        }
    }

    // Show snackbar for success messages
    LaunchedEffect(successMessage) {
        successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccessMessage()
            // Notify parent that device was removed
            if (it.contains("logged out", ignoreCase = true) ||
                it.contains("deleted", ignoreCase = true)) {
                onDeviceRemoved()
            }
        }
    }

    // Show rate limit countdown dialog
    if (rateLimitCountdown != null && rateLimitCountdown!! > 0) {
        RateLimitDialog(secondsRemaining = rateLimitCountdown!!)
    }

    // Confirmation dialogs
    if (showRemoveAllOthersDialog) {
        ConfirmBulkActionDialog(
            title = "Remove All Other Devices?",
            message = "This will log out all devices except this one. You'll need to log in again on those devices.",
            onConfirm = {
                viewModel.removeAllOtherDevices()
                showRemoveAllOthersDialog = false
            },
            onDismiss = { showRemoveAllOthersDialog = false }
        )
    }

    if (showRemoveAllInactiveDialog) {
        val inactiveCount = devices.count { !it.isActive }
        ConfirmBulkActionDialog(
            title = "Remove All Inactive Devices?",
            message = "This will clean up $inactiveCount inactive device(s) from your account.",
            onConfirm = {
                viewModel.removeAllInactiveDevices()
                showRemoveAllInactiveDialog = false
            },
            onDismiss = { showRemoveAllInactiveDialog = false }
        )
    }

    if (deviceToDelete != null) {
        ConfirmDeleteDialog(
            device = deviceToDelete!!,
            onConfirm = {
                viewModel.deleteDevice(deviceToDelete!!.id)
                deviceToDelete = null
            },
            onDismiss = { deviceToDelete = null }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.fetchDevices(isRefresh = true) },
            modifier = Modifier.padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                ScreenHeader(
                    title = "Manage Devices",
                    subtitle = "Review active devices and clean up access when needed",
                    onBack = onNavigateBack,
                    backLabel = "Back",
                    actions = {
                        IconButton(onClick = { viewModel.fetchDevices(isRefresh = true) }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MutedForeground
                            )
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = LibreGuardDimens.screenHorizontalPadding)
                        .padding(bottom = LibreGuardDimens.screenBottomPadding)
                ) {
                // Error message
                error?.let { apiError ->
                    ErrorBanner(
                        error = apiError,
                        onRetry = { viewModel.fetchDevices() },
                        onDismiss = { viewModel.clearError() }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (isLoading && devices.isEmpty()) {
                    // Initial loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (devices.isEmpty()) {
                    // Empty state
                    EmptyDeviceListState()
                } else {
                    // Device list
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->
                            DeviceCard(
                                device = device,
                                isCurrentDevice = device.remoteId() == currentDeviceId,
                                onRemove = { viewModel.removeDevice(device.id) },
                                onDelete = { deviceToDelete = device }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Bulk action buttons
                    BulkActionButtons(
                        devices = devices,
                        onRemoveAllOthers = { showRemoveAllOthersDialog = true },
                        onRemoveAllInactive = { showRemoveAllInactiveDialog = true },
                        currentDeviceId = currentDeviceId
                    )
                }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceDto,
    isCurrentDevice: Boolean,
    onRemove: () -> Unit,
    onDelete: () -> Unit
) {
    val deviceDisplayName = device.deviceName ?: "Unknown Device"
    val deviceIdentifier = DeviceNameGenerator.formatDeviceIdentifier(device.deviceId ?: device.deviceIdHash ?: "")
    val lastActiveText = formatLastActive(device.lastSeenAt)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = deviceDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isCurrentDevice) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Primary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "This Device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = deviceIdentifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground
                    )

                    device.osVersion?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedForeground
                        )
                    }
                }

                // Status badge
                StatusBadge(isActive = device.isActive)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Last active
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MutedForeground,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Last active: $lastActiveText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }

            // Action buttons (only for other devices)
            if (!isCurrentDevice) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                        enabled = device.isActive
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Logout")
                    }

                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        enabled = !device.isActive,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (device.isActive) MutedForeground else MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(isActive: Boolean) {
    val (color, text) = if (isActive) {
        Pair(Primary, "Active")
    } else {
        Pair(MutedForeground, "Inactive")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun BulkActionButtons(
    devices: List<DeviceDto>,
    onRemoveAllOthers: () -> Unit,
    onRemoveAllInactive: () -> Unit,
    currentDeviceId: String
) {
    val otherDevicesCount = devices.count { (it.deviceIdHash ?: it.deviceId) != currentDeviceId }
    val inactiveDevicesCount = devices.count { !it.isActive }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onRemoveAllOthers,
            modifier = Modifier.fillMaxWidth(),
            enabled = otherDevicesCount > 0,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Logout All Other Devices ($otherDevicesCount)")
        }

        OutlinedButton(
            onClick = onRemoveAllInactive,
            modifier = Modifier.fillMaxWidth(),
            enabled = inactiveDevicesCount > 0,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CleaningServices, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clean Up Inactive Devices ($inactiveDevicesCount)")
        }
    }
}

@Composable
fun ErrorBanner(
    error: ApiError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val errorMessage = when (error) {
        is ApiError.RateLimited -> "Too many requests. Please wait ${error.retryAfterSeconds} seconds."
        is ApiError.Unauthorized -> error.message
        is ApiError.DeviceProtected -> error.message
        is ApiError.ServerError -> error.message
        is ApiError.NetworkError -> "Network error: ${error.throwable.message}"
        is ApiError.Unknown -> error.message ?: "Unknown error occurred"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Error",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Row {
                if (error !is ApiError.RateLimited) {
                    TextButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }
        }
    }
}

@Composable
fun EmptyDeviceListState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MutedForeground
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No other devices registered",
                style = MaterialTheme.typography.titleMedium,
                color = Foreground
            )
            Text(
                text = "This is the only device on your account",
                style = MaterialTheme.typography.bodySmall,
                color = MutedForeground
            )
        }
    }
}

@Composable
fun RateLimitDialog(secondsRemaining: Int) {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss */ },
        title = { Text("Too Many Requests") },
        text = {
            Column {
                Text("Please wait $secondsRemaining seconds before trying again.")
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {},
        containerColor = CardBackground
    )
}

@Composable
fun ConfirmBulkActionDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = CardBackground
    )
}

@Composable
fun ConfirmDeleteDialog(
    device: DeviceDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Device?") },
        text = {
            Column {
                Text("This will permanently delete the device record:")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = device.deviceName ?: "Unknown Device",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Note: You can only delete inactive devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedForeground
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = CardBackground
    )
}

/**
 * Format last active timestamp to relative time
 */
fun formatLastActive(lastActive: String?): String {
    if (lastActive.isNullOrBlank()) return "Never"

    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val date = format.parse(lastActive)

        if (date != null) {
            DateUtils.getRelativeTimeSpanString(
                date.time,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        } else {
            "Unknown"
        }
    } catch (e: Exception) {
        "Unknown"
    }
}
