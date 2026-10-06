package net.libreguard.vpn.ui.screens

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.emptyFlow
import net.libreguard.vpn.R
import net.libreguard.vpn.core.*
import net.libreguard.vpn.ui.components.*
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.viewmodel.AppUpdateViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppUpdateUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val store = ViewModelStore()
    @After fun teardown() { store.clear() }

    @Test fun dismissal_hides_only_the_home_banner_and_preserves_the_settings_action() {
        var state by mutableStateOf(AppUpdateUiState(available, installedVersion = "2.0.3"))
        composeRule.setContent {
            LibreGuardVPNTheme {
                Column {
                    AppUpdateHomeBanner(state, AppUpdateActions(onDismiss = { state = state.copy(bannerSnoozed = true) }))
                    AppUpdateSettingsCard(state, AppUpdateActions())
                }
            }
        }
        composeRule.onNodeWithContentDescription(text(R.string.app_update_dismiss)).performClick()
        composeRule.onNodeWithTag("app_update_home_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("app_update_settings_card").assertExists()
        composeRule.onNodeWithTag("app_update_primary_action").assertIsEnabled()
    }

    @Test fun update_action_remains_reachable_with_large_text_in_dark_theme() {
        var clicks = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LibreGuardVPNTheme(darkTheme = true) {
                    Box(Modifier.size(320.dp, 560.dp)) {
                        AppUpdateSettingsCard(
                            AppUpdateUiState(available, installedVersion = "2.0.3"),
                            AppUpdateActions(onAction = { clicks++ })
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("app_update_primary_action").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test fun available_card_light_and_dark_previews() {
        var dark by mutableStateOf(false)
        composeRule.setContent {
            LibreGuardVPNTheme(darkTheme = dark) {
                Box(Modifier.size(360.dp, 420.dp)) {
                    AppUpdateSettingsCard(
                        AppUpdateUiState(available, installedVersion = "2.0.3"),
                        AppUpdateActions()
                    )
                }
            }
        }
        saveCardPreview("app-update-light.png", dark = false)
        composeRule.runOnIdle { dark = true }
        saveCardPreview("app-update-dark.png", dark = true)
    }

    private fun saveCardPreview(name: String, dark: Boolean) {
        val captured = composeRule.onNodeWithTag("app_update_settings_card").captureToImage()
        val background = captured.toPixelMap()[captured.width - 12, captured.height / 2]
        assertTrue("Card background must follow its theme", if (dark) background.luminance() < 0.1f else background.luminance() > 0.8f)
        val image = captured.asAndroidBitmap()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun downloading_shows_progress_without_an_install_action() {
        composeRule.setContent {
            LibreGuardVPNTheme {
                AppUpdateSettingsCard(
                    AppUpdateUiState(available.copy(
                        phase = AppUpdatePhase.DOWNLOADING, bytesDownloaded = 25, totalBytes = 100
                    ), installedVersion = "2.0.3"),
                    AppUpdateActions()
                )
            }
        }
        composeRule.onNodeWithTag("app_update_progress").assertExists()
        composeRule.onNodeWithTag("app_update_primary_action").assertDoesNotExist()
    }

    @Test fun restarting_an_active_vpn_requires_confirmation_and_later_does_not_install() {
        val client = ReadyClient()
        showHost(client, vpnActive = true)
        composeRule.onNodeWithTag("app_update_primary_action").performClick()
        composeRule.onNodeWithText(text(R.string.app_update_restart_warning)).assertIsDisplayed()
        assertEquals(0, client.completeCalls)
        composeRule.onNodeWithText(text(R.string.app_update_later)).performClick()
        assertEquals(0, client.completeCalls)
        composeRule.onNodeWithTag("app_update_primary_action").performClick()
        composeRule.onNodeWithTag("app_update_confirm_restart").performClick()
        composeRule.runOnIdle { assertEquals(1, client.completeCalls) }
    }

    @Test fun restart_checks_the_live_vpn_state_even_before_the_next_recomposition() {
        val client = ReadyClient()
        var connected = false
        showHost(client, vpnActive = false, activeProvider = { connected })
        composeRule.runOnIdle { connected = true }
        composeRule.onNodeWithTag("app_update_primary_action").performClick()
        composeRule.onNodeWithTag("app_update_confirm_restart").assertExists()
        assertEquals(0, client.completeCalls)
    }

    @Test fun a_disconnected_vpn_installs_only_after_the_restart_button_is_pressed() {
        val client = ReadyClient()
        showHost(client, vpnActive = false)
        composeRule.runOnIdle { assertEquals(0, client.completeCalls) }
        composeRule.onNodeWithTag("app_update_primary_action").performClick()
        composeRule.runOnIdle { assertEquals(1, client.completeCalls) }
        composeRule.onNodeWithTag("app_update_confirm_restart").assertDoesNotExist()
    }

    private fun showHost(client: ReadyClient, vpnActive: Boolean, activeProvider: () -> Boolean = { vpnActive }) {
        val vm = AppUpdateViewModel(client, object : AppUpdateSnoozeStore {
            override fun read(): AppUpdateSnooze? = null
            override fun write(snooze: AppUpdateSnooze) {}
        }, "2.0.3")
        store.put("updates", vm)
        composeRule.setContent {
            LibreGuardVPNTheme {
                val state by vm.state.collectAsState()
                Box {
                    AppUpdateSettingsCard(state, AppUpdateActions(onRestart = vm::requestRestart))
                    AppUpdateHost(vm, isVpnActive = activeProvider, hasBottomNavigation = false)
                }
            }
        }
    }

    private class ReadyClient : AppUpdateClient {
        override val installUpdates = emptyFlow<AppUpdateSnapshot>()
        var completeCalls = 0
        override suspend fun check() = available.copy(phase = AppUpdatePhase.READY_TO_INSTALL)
        override suspend fun prepareUpdate() = PreparedAppUpdate(check())
        override suspend fun completeUpdate() { completeCalls++ }
        override fun close() {}
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private companion object {
        val available = AppUpdateSnapshot(AppUpdatePhase.AVAILABLE, versionCode = 20040, flexibleAllowed = true)
    }
}