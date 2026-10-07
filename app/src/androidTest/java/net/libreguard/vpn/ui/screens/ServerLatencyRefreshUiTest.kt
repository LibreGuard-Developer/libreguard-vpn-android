package net.libreguard.vpn.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.libreguard.vpn.viewmodel.ServerLatencyStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ServerLatencyRefreshUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun showTestActivityOverKeyguard() {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_WAKEUP")
        ).use { it.readBytes() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
        }
    }

    @Test fun previousReadingStaysVisibleWhileUpdatingThenBecomesFresh() {
        val status = mutableStateOf(ServerLatencyStatus.REFRESHING)
        val latency = mutableStateOf<Int?>(80)
        compose.setContent { MaterialTheme { ServerLatencyReading(latency.value, status.value) } }
        compose.onNodeWithText("80ms").assertExists()
        compose.onNodeWithText("Updating").assertExists()
        compose.runOnIdle { latency.value = 35; status.value = ServerLatencyStatus.FRESH }
        compose.onNodeWithText("35ms").assertExists()
        compose.onNodeWithText("Updating").assertDoesNotExist()
        compose.runOnIdle { status.value = ServerLatencyStatus.STALE }
        compose.onNodeWithText("35ms").assertExists()
        compose.onNodeWithText("Previous").assertExists()
    }

    @Test fun unavailableServerShowsPlaceholder() {
        compose.setContent { MaterialTheme { ServerLatencyReading(null, ServerLatencyStatus.UNAVAILABLE) } }
        compose.onNodeWithText("--").assertExists()
    }

    @Test fun refreshControlWaitsForCompletionAndPreventsDuplicateTaps() {
        val refreshing = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme { ServerRefreshButton(refreshing.value) { clicks++; refreshing.value = true } }
        }
        compose.onNodeWithContentDescription("Refresh servers").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithContentDescription("Refreshing servers").assertIsNotEnabled()
        compose.runOnIdle { refreshing.value = false }
        compose.onNodeWithContentDescription("Refresh servers").assertExists().performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }
}

