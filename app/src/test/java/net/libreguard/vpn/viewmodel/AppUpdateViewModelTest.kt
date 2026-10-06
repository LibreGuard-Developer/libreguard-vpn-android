package net.libreguard.vpn.viewmodel

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import net.libreguard.vpn.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val client = FakeClient()
    private val snooze = MemorySnooze()
    private var clock = 1_000L

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel(): AppUpdateViewModel = AppUpdateViewModel(
        client, snooze, "2.0.3", now = { clock }
    ).also { store.put("updates", it) }

    @Test fun overlapping_checks_are_coalesced_and_cannot_replace_a_newer_download_event() = runTest(dispatcher) {
        val vm = viewModel()
        val delayed = CompletableDeferred<AppUpdateSnapshot>()
        client.checkAnswer = { delayed.await() }
        vm.checkForUpdates()
        vm.checkForUpdates()
        runCurrent()
        assertEquals(1, client.checkCalls)
        client.events.emit(available.copy(phase = AppUpdatePhase.READY_TO_INSTALL))
        runCurrent()
        delayed.complete(AppUpdateSnapshot(AppUpdatePhase.CURRENT))
        advanceUntilIdle()
        assertEquals(AppUpdatePhase.READY_TO_INSTALL, vm.state.value.snapshot.phase)
        assertFalse(vm.state.value.isChecking)
    }

    @Test fun cancellation_allows_retry_with_a_fresh_launch_request_and_double_taps_do_not_duplicate_it() = runTest(dispatcher) {
        val vm = viewModel()
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.primaryAction()
        vm.primaryAction()
        advanceUntilIdle()
        val first = vm.effects.first() as AppUpdateEffect.Launch
        assertEquals(1, client.prepareCalls)
        vm.onConsentResult(AppUpdateConsentResult.CANCELED)
        assertEquals(AppUpdatePhase.AVAILABLE, vm.state.value.snapshot.phase)
        assertNull(vm.state.value.snapshot.problem)
        vm.primaryAction()
        advanceUntilIdle()
        val second = vm.effects.first() as AppUpdateEffect.Launch
        assertEquals(2, client.prepareCalls)
        assertNotSame(first.starter, second.starter)
    }

    @Test fun consent_result_does_not_downgrade_progress_received_before_the_activity_result() = runTest(dispatcher) {
        val vm = viewModel()
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.primaryAction()
        advanceUntilIdle()
        vm.effects.first()
        client.events.emit(available.copy(phase = AppUpdatePhase.DOWNLOADING, bytesDownloaded = 50, totalBytes = 100))
        runCurrent()
        vm.onConsentResult(AppUpdateConsentResult.ACCEPTED)
        assertEquals(AppUpdatePhase.DOWNLOADING, vm.state.value.snapshot.phase)
        assertEquals(0.5f, vm.state.value.snapshot.progress!!, 0.001f)
    }

    @Test fun a_failed_check_is_never_presented_as_up_to_date() = runTest(dispatcher) {
        val vm = viewModel()
        client.checkAnswer = { throw IllegalStateException("unavailable") }
        vm.checkForUpdates()
        advanceUntilIdle()
        assertEquals(AppUpdatePhase.FAILED, vm.state.value.snapshot.phase)
        assertEquals(AppUpdateProblem.CHECK_FAILED, vm.state.value.snapshot.problem)
        assertFalse(vm.state.value.showHomeBanner)
    }

    @Test fun a_check_timeout_leaves_an_actionable_error() = runTest(dispatcher) {
        val vm = viewModel()
        client.checkAnswer = { CompletableDeferred<AppUpdateSnapshot>().await() }
        vm.checkForUpdates()
        advanceUntilIdle()
        assertEquals(AppUpdateProblem.CHECK_FAILED, vm.state.value.snapshot.problem)
        assertFalse(vm.state.value.isChecking)
    }

    @Test fun foreground_recovers_a_downloaded_update_and_reoffers_the_restart_notice() = runTest(dispatcher) {
        client.checkAnswer = { available.copy(phase = AppUpdatePhase.READY_TO_INSTALL) }
        val vm = viewModel()
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(AppUpdatePhase.READY_TO_INSTALL, vm.state.value.snapshot.phase)
        assertEquals("2.0.3", vm.state.value.installedVersion)
        assertEquals(1, vm.state.value.foregroundSession)
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.foregroundSession)
        assertTrue(vm.state.value.showHomeBanner)
    }

    @Test fun snooze_survives_recreation_expires_after_24_hours_and_does_not_hide_new_versions_or_progress() = runTest(dispatcher) {
        val vm = viewModel()
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.dismissBanner()
        assertFalse(vm.state.value.showHomeBanner)
        assertEquals(20040, snooze.value!!.versionCode)
        store.clear()
        val restored = viewModel()
        restored.checkForUpdates()
        advanceUntilIdle()
        assertFalse(restored.state.value.showHomeBanner)
        clock += AppUpdateViewModel.BANNER_SNOOZE_MILLIS
        restored.onForeground()
        advanceUntilIdle()
        assertTrue(restored.state.value.showHomeBanner)
        restored.dismissBanner()
        client.events.emit(available.copy(phase = AppUpdatePhase.DOWNLOADING))
        runCurrent()
        assertTrue(restored.state.value.showHomeBanner)
        client.checkAnswer = { available.copy(versionCode = 20050) }
        restored.checkForUpdates()
        advanceUntilIdle()
        assertTrue(restored.state.value.showHomeBanner)
        assertFalse(restored.state.value.bannerSnoozed)
    }

    @Test fun restarting_is_explicit_and_a_failed_install_can_be_retried() = runTest(dispatcher) {
        val vm = viewModel()
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.completeUpdate()
        assertEquals(0, client.completeCalls)
        client.events.emit(available.copy(phase = AppUpdatePhase.READY_TO_INSTALL))
        runCurrent()
        assertEquals(0, client.completeCalls)
        client.completeAnswer = { throw IllegalStateException("install failed") }
        vm.completeUpdate()
        advanceUntilIdle()
        assertEquals(AppUpdatePhase.READY_TO_INSTALL, vm.state.value.snapshot.phase)
        assertEquals(AppUpdateProblem.INSTALL_FAILED, vm.state.value.snapshot.problem)
        client.completeAnswer = {}
        vm.completeUpdate()
        advanceUntilIdle()
        assertEquals(2, client.completeCalls)
        assertEquals(AppUpdatePhase.INSTALLING, vm.state.value.snapshot.phase)
    }

    @Test fun a_flexible_update_that_becomes_disallowed_uses_the_store_fallback() = runTest(dispatcher) {
        val vm = viewModel()
        vm.checkForUpdates()
        advanceUntilIdle()
        client.prepareAnswer = { PreparedAppUpdate(available.copy(flexibleAllowed = false)) }
        vm.primaryAction()
        advanceUntilIdle()
        assertEquals(AppUpdateEffect.OpenStore, vm.effects.first())
        assertEquals(AppUpdatePhase.AVAILABLE, vm.state.value.snapshot.phase)
        assertFalse(vm.state.value.snapshot.flexibleAllowed)
    }

    @Test fun unsupported_devices_offer_the_store_without_claiming_the_current_version_is_latest() = runTest(dispatcher) {
        client.checkAnswer = { AppUpdateSnapshot(AppUpdatePhase.UNAVAILABLE) }
        val vm = viewModel()
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.primaryAction()
        assertEquals(AppUpdateEffect.OpenStore, vm.effects.first())
        assertEquals(AppUpdatePhase.UNAVAILABLE, vm.state.value.snapshot.phase)
        assertEquals(0, client.prepareCalls)
    }

    @Test fun a_listener_failure_can_be_retried_and_clearing_the_view_model_releases_the_client() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        client.events.emit(available.copy(phase = AppUpdatePhase.FAILED, problem = AppUpdateProblem.DOWNLOAD_FAILED))
        runCurrent()
        vm.primaryAction()
        advanceUntilIdle()
        assertTrue(vm.effects.first() is AppUpdateEffect.Launch)
        store.clear()
        assertTrue(client.closed)
    }

    private class MemorySnooze : AppUpdateSnoozeStore {
        var value: AppUpdateSnooze? = null
        override fun read() = value
        override fun write(snooze: AppUpdateSnooze) { value = snooze }
    }

    private class FakeClient : AppUpdateClient {
        val events = MutableSharedFlow<AppUpdateSnapshot>(extraBufferCapacity = 16)
        override val installUpdates = events
        var checkCalls = 0
        var prepareCalls = 0
        var completeCalls = 0
        var closed = false
        var checkAnswer: suspend () -> AppUpdateSnapshot = { available }
        var prepareAnswer: suspend () -> PreparedAppUpdate = {
            PreparedAppUpdate(available, object : AppUpdateFlowStarter {
                override fun launch(launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) = true
            })
        }
        var completeAnswer: suspend () -> Unit = {}
        override suspend fun check(): AppUpdateSnapshot { checkCalls++; return checkAnswer() }
        override suspend fun prepareUpdate(): PreparedAppUpdate { prepareCalls++; return prepareAnswer() }
        override suspend fun completeUpdate() { completeCalls++; completeAnswer() }
        override fun close() { closed = true }
    }

    private companion object {
        val available = AppUpdateSnapshot(AppUpdatePhase.AVAILABLE, versionCode = 20040, flexibleAllowed = true)
    }
}