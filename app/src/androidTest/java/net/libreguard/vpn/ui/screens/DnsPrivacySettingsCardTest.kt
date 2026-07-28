package net.libreguard.vpn.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.DnsPreferenceUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DnsPrivacySettingsCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingDisablesToggle() {
        show(DnsPreferenceUiState(isLoading = true))
        composeRule.onNodeWithText("Checking your account setting…").assertExists()
        composeRule.onNodeWithTag("dns_ad_blocking_switch").assertIsNotEnabled()
    }

    @Test
    fun freeLockedRowNavigatesToUpgrade() {
        var upgradeRequested = false
        show(DnsPreferenceUiState(isLoaded = true)) { upgradeRequested = true }

        composeRule.onNodeWithTag("dns_ad_blocking_switch").assertIsNotEnabled()
        composeRule.onNodeWithTag("dns_ad_blocking_row").performClick()
        assertTrue(upgradeRequested)
    }

    @Test
    fun activeProPreferenceIsOn() {
        show(
            DnsPreferenceUiState(
                isLoaded = true,
                requestedEnabled = true,
                canUseAdBlocking = true,
                effectiveEnabled = true,
                effectiveMode = "filtered"
            )
        )
        composeRule.onNodeWithText("Active for this account and all your devices").assertExists()
        composeRule.onNodeWithTag("dns_ad_blocking_switch").assertIsEnabled().assertIsOn()
    }

    @Test
    fun pausedSavedPreferenceCanBeTurnedOff() {
        var requestedValue = true
        composeRule.setContent {
            LibreGuardVPNTheme {
                DnsPrivacySettingsCard(
                    state = DnsPreferenceUiState(
                        isLoaded = true,
                        requestedEnabled = true,
                        canUseAdBlocking = false,
                        effectiveEnabled = false
                    ),
                    onToggleAdBlocking = { requestedValue = it },
                    onNavigateToUpgrade = {}
                )
            }
        }

        composeRule.onNodeWithText("Paused — your saved preference needs an active Pro plan").assertExists()
        composeRule.onNodeWithTag("dns_ad_blocking_switch").assertIsEnabled().performClick()
        assertFalse(requestedValue)
    }

    @Test
    fun globalUnavailableStateHasDistinctCopy() {
        show(
            DnsPreferenceUiState(
                isLoaded = true,
                requestedEnabled = true,
                canUseAdBlocking = true,
                effectiveEnabled = false
            )
        )
        composeRule.onNodeWithText(
            "Saved, but filtering is currently unavailable; private DNS remains active"
        ).assertExists()
    }

    private fun show(state: DnsPreferenceUiState, onUpgrade: () -> Unit = {}) {
        composeRule.setContent {
            LibreGuardVPNTheme {
                DnsPrivacySettingsCard(
                    state = state,
                    onToggleAdBlocking = {},
                    onNavigateToUpgrade = onUpgrade
                )
            }
        }
    }
}
