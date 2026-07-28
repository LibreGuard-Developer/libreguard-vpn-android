package net.libreguard.vpn.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.service.data.DataUsageInfo
import net.libreguard.vpn.ui.components.VpnConnectionStatus
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardAdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun freeConnectedLayoutKeepsRemainingAndBothSpeedsVisible() {
        show(height = 720.dp, isUnlimited = false)

        composeRule.onNodeWithText("Remaining").assertIsDisplayed()
        composeRule.onNodeWithTag("bandwidth_download_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("bandwidth_upload_speed").assertIsDisplayed()

        val cardBottom = composeRule
            .onNodeWithTag("bandwidth_usage_card")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val viewportBottom = composeRule
            .onNodeWithTag("dashboard_connection_viewport")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom

        assertTrue("Bandwidth card must retain a bottom gap", viewportBottom - cardBottom >= 7f)
    }

    @Test
    fun proConnectedLayoutOmitsRemainingButKeepsRealtimeSpeeds() {
        show(height = 720.dp, isUnlimited = true)

        composeRule.onAllNodesWithTag("bandwidth_remaining").assertCountEquals(0)
        composeRule.onNodeWithTag("bandwidth_download_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("bandwidth_upload_speed").assertIsDisplayed()
    }

    @Test
    fun compactConnectedLayoutScrollsToTheBottomOfBandwidthCard() {
        show(height = 420.dp, isUnlimited = false)

        composeRule
            .onNodeWithTag("dashboard_connection_scroll")
            .performScrollToNode(hasTestTag("bandwidth_upload_speed"))

        composeRule.onNodeWithTag("bandwidth_remaining").assertIsDisplayed()
        composeRule.onNodeWithTag("bandwidth_download_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("bandwidth_upload_speed").assertIsDisplayed()
    }

    @Test
    fun connectedBlocksEnterSmoothlyWhenStateChanges() {
        var isConnected by mutableStateOf(false)

        composeRule.setContent {
            LibreGuardVPNTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    AdaptiveConnectionViewport(
                        modifier = Modifier.fillMaxSize(),
                        status = if (isConnected) {
                            VpnConnectionStatus.CONNECTED
                        } else {
                            VpnConnectionStatus.DISCONNECTED
                        },
                        statusButtonText = if (isConnected) "Disconnect" else "Connect",
                        onConnectionToggle = {},
                        isConnected = isConnected,
                        isConnecting = false,
                        isQuickConnectMode = true,
                        hasSelectedServer = true,
                        connectionTime = "00:01:00",
                        downloadSpeed = 12.3,
                        uploadSpeed = 4.5,
                        location = "Serbia",
                        dataUsageInfo = freeUsage,
                        isUnlimited = false,
                        isOverLimit = false,
                        sessionData = 12.0,
                        monthlyLimit = 5120.0,
                        monthlyPercentage = 35f,
                        totalPercentage = 35f
                    )
                }
            }
        }

        isConnected = true
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connection_stats").assertIsDisplayed()
        composeRule.onNodeWithTag("bandwidth_usage_card").assertIsDisplayed()
    }

    private fun show(height: androidx.compose.ui.unit.Dp, isUnlimited: Boolean) {
        composeRule.setContent {
            LibreGuardVPNTheme {
                Box(Modifier.size(360.dp, height)) {
                    AdaptiveConnectionViewport(
                        modifier = Modifier.fillMaxSize(),
                        status = VpnConnectionStatus.CONNECTED,
                        statusButtonText = "Disconnect",
                        onConnectionToggle = {},
                        isConnected = true,
                        isConnecting = false,
                        isQuickConnectMode = true,
                        hasSelectedServer = true,
                        connectionTime = "00:01:00",
                        downloadSpeed = 12.3,
                        uploadSpeed = 4.5,
                        location = "Serbia",
                        dataUsageInfo = freeUsage.copy(isUnlimited = isUnlimited),
                        isUnlimited = isUnlimited,
                        isOverLimit = false,
                        sessionData = 12.0,
                        monthlyLimit = if (isUnlimited) Double.MAX_VALUE else 5120.0,
                        monthlyPercentage = 35f,
                        totalPercentage = 35f
                    )
                }
            }
        }
    }

    private companion object {
        val freeUsage = DataUsageInfo(
            totalBytesUsed = 1792L * 1024L * 1024L,
            sessionBytesUsed = 12L * 1024L * 1024L,
            limitBytes = 5120L * 1024L * 1024L,
            formattedTotal = "1.75 GB",
            formattedSession = "12.0 MB",
            formattedLimit = "5.0 GB",
            formattedRemaining = "3.25 GB",
            downloadSpeedMbps = 12.3,
            uploadSpeedMbps = 4.5
        )
    }
}
