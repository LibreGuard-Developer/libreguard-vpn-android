package net.libreguard.vpn.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.util.*
import net.libreguard.vpn.viewmodel.VpnProtocol
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackgroundConnectionUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun settingsRequestsAreExplicitAndDenialLeavesTheCardUsable() {
        var batteryRequests = 0
        var timingRequests = 0
        composeRule.setContent {
            LibreGuardVPNTheme {
                BackgroundConnectionSettingsCard(
                    BackgroundConnectionStatus(false, false, false), true,
                    onBatterySettings = { batteryRequests++; true },
                    onTimingSettings = { timingRequests++; true },
                    onAppDetails = { true }
                )
            }
        }
        composeRule.runOnIdle {
            assertEquals(0, batteryRequests)
            assertEquals(0, timingRequests)
        }
        composeRule.onNodeWithText("Allow background access").performClick()
        composeRule.onNodeWithText("Allow connection timing").performClick()
        composeRule.onNodeWithText("Android battery access: Restricted").assertIsDisplayed()
        composeRule.onNodeWithText("IKEv2 connection timing: Limited").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, batteryRequests)
            assertEquals(1, timingRequests)
        }
    }

    @Test fun batteryStatusDoesNotClaimXiaomiSettingsAreVerified() {
        composeRule.setContent {
            LibreGuardVPNTheme {
                BackgroundConnectionSettingsCard(
                    BackgroundConnectionStatus(true, true, true), false,
                    onBatterySettings = { true }, onTimingSettings = { true }, onAppDetails = { true }
                )
            }
        }
        composeRule.onNodeWithText("Android battery access: Unrestricted").assertIsDisplayed()
        composeRule.onNodeWithText("Xiaomi, Redmi and POCO").assertIsDisplayed()
        composeRule.onNodeWithText("Review these phone settings manually.", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Allow connection timing").assertCountEquals(0)
    }

    @Test fun returningToForegroundRefreshesPlatformStatus() {
        val owner = TestOwner()
        val platform = FakePlatform()
        val support = BackgroundConnectionSupport(platform)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val status = rememberBackgroundConnectionStatus(support, VpnProtocol.IKEV2_IPSEC)
                Text("battery=${status.batteryExempt}, exact=${status.exactAlarmAvailable}")
            }
        }
        composeRule.onNodeWithText("battery=false, exact=false").assertExists()
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            platform.allowed = true
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.onNodeWithText("battery=true, exact=true").assertExists()
    }

    @Test fun dismissedTipDoesNotReturnAfterAnotherConnection() {
        val owner = TestOwner()
        val connected = mutableStateOf(false)
        val store = MemoryStore()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LibreGuardVPNTheme {
                    val visible = rememberBackgroundTipVisible(
                        BackgroundConnectionStatus(false, false, false),
                        VpnProtocol.IKEV2_IPSEC, connected.value, store
                    )
                    if (visible.value) BackgroundConnectionTip({}, { visible.value = false })
                }
            }
        }
        composeRule.onAllNodesWithTag("background_connection_tip").assertCountEquals(0)
        composeRule.runOnIdle { connected.value = true }
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.runOnIdle { assertTrue(store.shown); connected.value = false }
        composeRule.runOnIdle { connected.value = true }
        composeRule.onAllNodesWithTag("background_connection_tip").assertCountEquals(0)
    }

    @Test fun backgroundConnectionWaitsForForegroundBeforeShowingTip() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.STARTED }
        val store = MemoryStore()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LibreGuardVPNTheme {
                    val visible = rememberBackgroundTipVisible(
                        BackgroundConnectionStatus(false, false, false), VpnProtocol.IKEV2_IPSEC, true, store
                    )
                    if (visible.value) BackgroundConnectionTip({}, { visible.value = false })
                }
            }
        }
        composeRule.runOnIdle { assertFalse(store.shown) }
        composeRule.onAllNodesWithTag("background_connection_tip").assertCountEquals(0)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.onNodeWithText("Review settings").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(store.shown) }
    }

    @Test fun tipPreferenceSurvivesCreatingANewStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("background_connection_prefs", Context.MODE_PRIVATE)
        val hadTipState = prefs.contains("tip_shown")
        val previousTipState = prefs.getBoolean("tip_shown", false)
        prefs.edit().remove("tip_shown").commit()
        try {
            val first = BackgroundConnectionTipPreferences(context)
            assertFalse(first.hasBeenShown())
            first.markShown()
            assertTrue(BackgroundConnectionTipPreferences(context).hasBeenShown())
        } finally {
            val editor = prefs.edit()
            if (hadTipState) editor.putBoolean("tip_shown", previousTipState) else editor.remove("tip_shown")
            editor.commit()
        }
    }

    @Test fun compactLargeFontTipKeepsBothActionsUsable() {
        var reviewed = false
        var dismissed = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                LibreGuardVPNTheme {
                    Box(Modifier.size(320.dp, 350.dp)) {
                        BackgroundConnectionTip({ reviewed = true }, { dismissed = true })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Review settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(reviewed); assertTrue(dismissed) }
    }

    private class MemoryStore : BackgroundTipStore {
        var shown = false
        override fun hasBeenShown() = shown
        override fun markShown() { shown = true }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class FakePlatform : BackgroundConnectionPlatform {
        var allowed = false
        override val sdkInt = 34
        override val manufacturer = "Google"
        override val brand = "google"
        override fun isBatteryExempt() = allowed
        override fun canScheduleExactAlarms() = allowed
        override fun openSettings(destination: BackgroundSettingsDestination) = true
    }
}
