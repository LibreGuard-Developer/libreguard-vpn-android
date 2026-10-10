package net.libreguard.vpn.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import net.libreguard.vpn.service.vpn.VpnPermissionFailure
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.VpnConsentCoordinator
import net.libreguard.vpn.viewmodel.VpnConsentStage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VpnConsentUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun recoveryProvidesRetrySettingsAndDismissActions() {
        var retries = 0; var settings = 0; var dismissals = 0
        composeRule.setContent {
            LibreGuardVPNTheme {
                VpnConsentRecoveryDialog(VpnPermissionFailure.DENIED,
                    onRetry = { retries++ }, onSettings = { settings++ }, onDismiss = { dismissals++ })
            }
        }
        composeRule.onNodeWithText("VPN permission needed").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, settings); assertEquals(0, retries); assertEquals(0, dismissals) }
        assertRecoveryButtonsAligned()
        composeRule.onNodeWithTag("vpn_consent_retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(0, settings) }
        composeRule.onNodeWithTag("vpn_open_settings").performClick()
        composeRule.onNodeWithTag("vpn_consent_dismiss").performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(1, settings); assertEquals(1, dismissals) }
    }

    @Test fun failedCheckAndMissingSettingsHaveAccurateRecoveryText() {
        composeRule.setContent {
            LibreGuardVPNTheme {
                VpnConsentRecoveryDialog(VpnPermissionFailure.CHECK_FAILED, onePlus = true, settingsUnavailable = true,
                    onRetry = {}, onSettings = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("VPN access could not be checked").assertIsDisplayed()
        composeRule.onNodeWithText("On OxygenOS 14:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Settings could not be opened.", substring = true).assertIsDisplayed()
    }

    @Test fun dismissingDenialLeavesAnActionableDisconnectedNotice() {
        val coordinator = VpnConsentCoordinator<String, String>(prepare = { "system" })
        coordinator.request("server")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        coordinator.result(id, false)
        composeRule.setContent {
            val state by coordinator.state.collectAsState()
            LibreGuardVPNTheme {
                VpnConsentNotice(state, onRetry = {}, onCancel = {})
                if (state.showRecoveryDialog) VpnConsentRecoveryDialog(state.failure!!,
                    onRetry = {}, onSettings = {}, onDismiss = coordinator::dismissRecovery)
            }
        }
        composeRule.onNodeWithTag("vpn_consent_dismiss").performClick()
        composeRule.onNodeWithTag("vpn_consent_recovery").assertDoesNotExist()
        composeRule.onNodeWithText("Not connected — VPN permission needed").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }
    @Test fun backgroundRequestWaitsForResumeAndRotationDoesNotRelaunchIt() {
        val owner = mutableStateOf(TestOwner(Lifecycle.State.STARTED))
        val coordinator = VpnConsentCoordinator<String, String>(prepare = { "system" })
        var launches = 0
        coordinator.request("server-a")
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner.value) {
                VpnConsentLaunchEffect(coordinator.state, true, coordinator::claimLaunch) { _, _ -> launches++ }
            }
        }
        composeRule.runOnIdle { assertEquals(0, launches); owner.value.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitUntil { launches == 1 }
        composeRule.runOnIdle { owner.value = TestOwner(Lifecycle.State.RESUMED) }
        composeRule.runOnIdle { assertEquals(1, launches) }
        composeRule.runOnIdle {
            owner.value.registry.currentState = Lifecycle.State.STARTED
            coordinator.cancel()
            coordinator.request("server-b")
        }
        composeRule.runOnIdle { assertEquals(1, launches); owner.value.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitUntil { launches == 2 }
    }

    @Test fun pendingNewAttemptWaitsUntilTheOldActivityResultHasReturned() {
        val coordinator = VpnConsentCoordinator<String, String>(prepare = { "system" })
        val available = mutableStateOf(false)
        var launches = 0
        coordinator.request("server")
        composeRule.setContent {
            VpnConsentLaunchEffect(coordinator.state, available.value, coordinator::claimLaunch) { _, _ -> launches++ }
        }
        composeRule.runOnIdle { assertEquals(0, launches); available.value = true }
        composeRule.waitUntil { launches == 1 }
    }

    @Test fun denyingConsentDoesNotNavigateToSettingsAndRetryRequestsFreshConsent() {
        var checks = 0
        var settings = 0
        val coordinator = VpnConsentCoordinator<String, String>(prepare = { checks++; "system" })
        coordinator.request("server")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        composeRule.setContent {
            val state by coordinator.state.collectAsState()
            LibreGuardVPNTheme {
                if (state.showRecoveryDialog) VpnConsentRecoveryDialog(state.failure!!,
                    onRetry = { coordinator.retry() }, onSettings = { settings++ }, onDismiss = coordinator::dismissRecovery)
            }
        }
        composeRule.runOnIdle { coordinator.result(id, false) }
        composeRule.onNodeWithTag("vpn_consent_recovery").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, settings) }
        composeRule.onNodeWithTag("vpn_consent_retry").performClick()
        composeRule.onNodeWithTag("vpn_consent_recovery").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, settings)
            assertEquals(2, checks)
            assertEquals(VpnConsentStage.REQUEST_PENDING, coordinator.state.value.stage)
            assertNotEquals(id, coordinator.state.value.attemptId)
        }
    }

    @Test fun largeTextKeepsAllRecoveryActionsVisibleAndAligned() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                LibreGuardVPNTheme {
                    VpnConsentRecoveryDialog(VpnPermissionFailure.DENIED, onePlus = true,
                        onRetry = {}, onSettings = {}, onDismiss = {})
                }
            }
        }
        assertRecoveryButtonsAligned()
    }

    private fun assertRecoveryButtonsAligned() {
        val settings = composeRule.onNodeWithTag("vpn_open_settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dismiss = composeRule.onNodeWithTag("vpn_consent_dismiss").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val retry = composeRule.onNodeWithTag("vpn_consent_retry").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(settings.center.x < dismiss.center.x)
        assertTrue(dismiss.center.x < retry.center.x)
        assertEquals(settings.center.y, dismiss.center.y, 1f)
        assertEquals(dismiss.center.y, retry.center.y, 1f)
        assertEquals(settings.height, dismiss.height, 1f)
        assertEquals(dismiss.height, retry.height, 1f)
    }

    private class TestOwner(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

}
