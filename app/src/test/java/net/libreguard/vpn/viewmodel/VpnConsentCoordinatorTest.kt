package net.libreguard.vpn.viewmodel

import kotlinx.coroutines.CancellationException
import net.libreguard.vpn.service.vpn.VpnPermissionFailure
import org.junit.Assert.*
import org.junit.Test

class VpnConsentCoordinatorTest {
    private var intent: String? = "system-consent"
    private var guidance = false
    private var checks = 0
    private fun coordinator() = VpnConsentCoordinator<String, String>(
        prepare = { checks++; intent }, needsGuidance = { guidance }, saveGuidance = { guidance = it }
    )

    @Test fun preparedRequestStartsWithoutPromptOrCachedGrant() {
        intent = null
        val coordinator = coordinator()
        assertEquals("server-a", coordinator.request("server-a"))
        intent = "system-consent"
        assertNull(coordinator.request("server-b"))
        assertEquals(VpnConsentStage.REQUEST_PENDING, coordinator.state.value.stage)
        assertEquals(2, checks)
    }

    @Test fun requestSurvivesMissingUiCollectorAndCanBeLaunchedOnlyOnce() {
        val coordinator = coordinator()
        assertNull(coordinator.request("server-a"))
        val id = coordinator.state.value.attemptId!!
        assertNull(coordinator.request("server-b"))
        assertEquals("system-consent", coordinator.claimLaunch(id))
        assertNull(coordinator.claimLaunch(id))
        assertEquals(VpnConsentStage.AWAITING_RESULT, coordinator.state.value.stage)
        assertEquals(1, checks)
    }

    @Test fun approvalRechecksAndResumesTheCapturedRequestExactlyOnce() {
        val coordinator = coordinator()
        coordinator.request("server-a:ikev2")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        intent = null
        assertEquals("server-a:ikev2", coordinator.result(id, approved = true))
        assertNull(coordinator.result(id, approved = true))
        assertEquals(2, checks)
        assertFalse(coordinator.hasPendingRequest)
    }

    @Test fun denialShowsRecoveryAndDismissalRetainsTheNotice() {
        val coordinator = coordinator()
        coordinator.request("server-a")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        assertNull(coordinator.result(id, approved = false))
        assertEquals(VpnPermissionFailure.DENIED, coordinator.state.value.failure)
        assertTrue(coordinator.state.value.showRecoveryDialog)
        assertTrue(guidance)
        coordinator.dismissRecovery()
        assertEquals(VpnConsentStage.RECOVERY, coordinator.state.value.stage)
        assertFalse(coordinator.state.value.showRecoveryDialog)
        assertFalse(coordinator.hasPendingRequest)
    }

    @Test fun explicitRetryCanRequestAgainAfterPersistentDenial() {
        guidance = true
        val coordinator = coordinator()
        coordinator.request("server-a")
        assertEquals(VpnConsentStage.RECOVERY, coordinator.state.value.stage)
        coordinator.retry()
        assertEquals(VpnConsentStage.REQUEST_PENDING, coordinator.state.value.stage)
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        intent = null
        assertEquals("server-a", coordinator.result(id, true))
        assertFalse(guidance)
    }

    @Test fun guidanceNeverOverridesAnActualGrant() {
        guidance = true
        intent = null
        assertEquals("server-a", coordinator().request("server-a"))
        assertFalse(guidance)
    }

    @Test fun autoConnectPausesWithoutPromptOrAutomaticContinuation() {
        val coordinator = coordinator()
        assertNull(coordinator.request("server-a", automatic = true))
        assertEquals(VpnConsentStage.RECOVERY, coordinator.state.value.stage)
        assertFalse(coordinator.state.value.showRecoveryDialog)
        assertNull(coordinator.state.value.intent)
        assertFalse(coordinator.hasPendingRequest)
        intent = null
        assertNull(coordinator.retry())
    }

    @Test fun cancelledOrSupersededRequestIgnoresLateResults() {
        val coordinator = coordinator()
        coordinator.request("server-a")
        val oldId = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(oldId)
        coordinator.cancel()
        coordinator.request("server-b")
        val newId = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(newId)
        intent = null
        assertNull(coordinator.result(oldId, true))
        assertEquals("server-b", coordinator.result(newId, true))
    }

    @Test fun recreatedProcessCannotResumeThePreviousAttempt() {
        val old = coordinator()
        old.request("server-a")
        val oldId = old.state.value.attemptId!!
        old.claimLaunch(oldId)
        val fresh = coordinator()
        fresh.request("server-b")
        val newId = fresh.state.value.attemptId!!
        fresh.claimLaunch(newId)
        intent = null
        assertNotEquals(oldId, newId)
        assertNull(fresh.result(oldId, true))
        assertEquals("server-b", fresh.result(newId, true))
    }

    @Test fun approvedResultWithLostAuthorizationDoesNotLoopThePrompt() {
        val coordinator = coordinator()
        coordinator.request("server-a")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        assertNull(coordinator.result(id, true))
        assertEquals(VpnConsentStage.RECOVERY, coordinator.state.value.stage)
        assertEquals(VpnPermissionFailure.CONSENT_REQUIRED, coordinator.state.value.failure)
        assertNull(coordinator.state.value.intent)
    }

    @Test fun unavailableConsentActivityHasDistinctRecoveryAndStaleFailuresAreIgnored() {
        val coordinator = coordinator()
        coordinator.request("server-a")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        coordinator.launchFailed("obsolete")
        assertEquals(VpnConsentStage.AWAITING_RESULT, coordinator.state.value.stage)
        coordinator.launchFailed(id)
        assertEquals(VpnPermissionFailure.REQUEST_UNAVAILABLE, coordinator.state.value.failure)
        assertTrue(coordinator.state.value.showRecoveryDialog)
    }

    @Test fun failingPreparationStopsTheRequestAndAutoConnectStaysQuiet() {
        var failures = 0
        val coordinator = VpnConsentCoordinator<String, String>(
            prepare = { throw IllegalStateException("platform unavailable") }, onCheckFailure = { failures++ }
        )
        assertNull(coordinator.request("server-a"))
        assertEquals(VpnPermissionFailure.CHECK_FAILED, coordinator.state.value.failure)
        assertTrue(coordinator.state.value.showRecoveryDialog)
        coordinator.cancel()
        assertNull(coordinator.request("server-b", automatic = true))
        assertFalse(coordinator.state.value.showRecoveryDialog)
        assertEquals(2, failures)
    }

    @Test fun preparationPreservesCoroutineCancellation() {
        val coordinator = VpnConsentCoordinator<String, String>(prepare = { throw CancellationException("cancelled") })
        assertThrows(CancellationException::class.java) { coordinator.request("server-a") }
        assertEquals(VpnConsentStage.IDLE, coordinator.state.value.stage)
    }

    @Test fun openingSettingsDiscardsThePendingConnectionUntilAnotherExplicitRequest() {
        val coordinator = coordinator()
        coordinator.request("server-a")
        val id = coordinator.state.value.attemptId!!
        coordinator.claimLaunch(id)
        coordinator.result(id, false)
        coordinator.dismissRecovery()
        intent = null
        assertNull(coordinator.result(id, true))
        assertNull(coordinator.retry())
        assertEquals("server-b", coordinator.request("server-b"))
    }

    @Test fun revocationRetainsAnActionableStateWithoutAnAutomaticPrompt() {
        val coordinator = coordinator()
        coordinator.reportFailure(VpnPermissionFailure.CONSENT_REVOKED, showDialog = false)
        assertEquals(VpnPermissionFailure.CONSENT_REVOKED, coordinator.state.value.failure)
        assertFalse(coordinator.state.value.showRecoveryDialog)
        assertNull(coordinator.request("server-a"))
        assertTrue(coordinator.state.value.showRecoveryDialog)
    }
}
