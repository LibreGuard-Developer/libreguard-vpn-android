package net.libreguard.vpn.core

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.InstallErrorCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GooglePlayUpdateClientTest {
    private lateinit var manager: FakeAppUpdateManager
    private lateinit var client: GooglePlayUpdateClient

    @Before fun setup() {
        manager = FakeAppUpdateManager(InstrumentationRegistry.getInstrumentation().targetContext)
        client = GooglePlayUpdateClient(manager)
    }

    @After fun teardown() { client.close() }

    @Test fun flexible_flow_reports_progress_then_requires_explicit_completion() = runBlocking {
        withTimeout(5_000) {
            manager.setUpdateAvailable(20040)
            assertEquals(AppUpdatePhase.AVAILABLE, client.check().phase)
            val prepared = client.prepareUpdate()
            assertNotNull(prepared.starter)
            assertTrue(prepared.starter!!.launch(noUiLauncher))
            assertTrue(manager.isConfirmationDialogVisible)
            manager.userAcceptsUpdate()
            manager.downloadStarts()
            manager.setTotalBytesToDownload(100)
            manager.setBytesDownloaded(25)
            val downloading = client.check()
            assertEquals(AppUpdatePhase.DOWNLOADING, downloading.phase)
            assertEquals(0.25f, downloading.progress!!, 0.001f)
            manager.downloadCompletes()
            assertEquals(AppUpdatePhase.READY_TO_INSTALL, client.check().phase)
            assertFalse(manager.isInstallSplashScreenVisible)
            client.completeUpdate()
            assertTrue(manager.isInstallSplashScreenVisible)
            manager.installFails()
            val failed = client.installUpdates.first { it.phase == AppUpdatePhase.FAILED }
            assertEquals(AppUpdateProblem.INSTALL_FAILED, failed.problem)
        }
    }

    @Test fun an_explicit_no_update_response_reports_current() = runBlocking {
        withTimeout(5_000) {
            manager.setUpdateNotAvailable()
            assertEquals(AppUpdatePhase.CURRENT, client.check().phase)
        }
    }

    @Test fun an_account_that_does_not_own_the_app_gets_a_store_fallback() = runBlocking {
        withTimeout(5_000) {
            manager.setInstallErrorCode(InstallErrorCode.ERROR_APP_NOT_OWNED)
            assertEquals(AppUpdatePhase.UNAVAILABLE, client.check().phase)
            assertNull(client.prepareUpdate().starter)
        }
    }

    private val noUiLauncher = object : ActivityResultLauncher<IntentSenderRequest>() {
        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {}
        override fun unregister() {}
        override val contract: ActivityResultContract<IntentSenderRequest, *>
            get() = ActivityResultContracts.StartIntentSenderForResult()
    }
}