package net.libreguard.vpn.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.ConnectionIssue
import net.libreguard.vpn.viewmodel.ConnectionIssueSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectionIssueNoticeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun networkFailureStaysVisibleUntilHandledAndSettingsRequireATap() {
        var settings = 0
        composeRule.setContent {
            var issue by remember { mutableStateOf<ConnectionIssue?>(ConnectionIssue("Couldn't reach LibreGuard. Check the app's network access.")) }
            LibreGuardVPNTheme {
                issue?.let { ConnectionIssueCard(it, onSettings = { settings++ }, onDismiss = { issue = null }) }
            }
        }
        composeRule.onNodeWithText("Connection needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't reach LibreGuard. Check the app's network access.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, settings) }
        composeRule.onNodeWithText("App settings").performClick()
        composeRule.runOnIdle { assertEquals(1, settings) }
        composeRule.onNodeWithTag("connection_issue").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.onNodeWithTag("connection_issue").assertDoesNotExist()
    }

    @Test fun activeVpnIssueOffersVpnSettings() {
        var settings = 0
        composeRule.setContent {
            LibreGuardVPNTheme {
                ConnectionIssueCard(ConnectionIssue("Disconnect the other VPN to measure latency.", ConnectionIssueSettings.VPN,
                    title = "Another VPN is active"),
                    onSettings = { settings++ }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Another VPN is active").assertIsDisplayed()
        composeRule.onNodeWithText("Connection needs attention").assertDoesNotExist()
        val settingsBounds = composeRule.onNodeWithTag("connection_issue_settings").fetchSemanticsNode().boundsInRoot
        val dismissBounds = composeRule.onNodeWithTag("connection_issue_dismiss").fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onNodeWithTag("connection_issue").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(settingsBounds.left < cardBounds.left + cardBounds.width * .2f)
        org.junit.Assert.assertTrue(dismissBounds.right > cardBounds.left + cardBounds.width * .8f)
        org.junit.Assert.assertEquals(settingsBounds.center.y, dismissBounds.center.y, 1f)
        composeRule.onNodeWithTag("connection_issue_disconnect").assertDoesNotExist()
        composeRule.onNodeWithText("VPN settings").performClick()
        composeRule.onNodeWithText("App settings").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun protocolFailureDoesNotSuggestAnUnrelatedPermissionChange() {
        composeRule.setContent {
            LibreGuardVPNTheme {
                ConnectionIssueCard(ConnectionIssue("Connection failed. Please choose another server.", ConnectionIssueSettings.NONE),
                    onSettings = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Connection failed. Please choose another server.").assertIsDisplayed()
        composeRule.onNodeWithTag("connection_issue_settings").assertDoesNotExist()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }
}
