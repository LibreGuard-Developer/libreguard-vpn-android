package net.libreguard.vpn.viewmodel

import android.app.Activity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.GoogleCredentialSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClientLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val device = ClientDeviceRequest("original", "2.0", "key", "key-id", "RSA-OAEP-256")
    private val capability = "a".repeat(43)
    private val replacement = "b".repeat(43)
    private val expiry = "2026-10-05T12:10:00Z"
    private val saved = mutableListOf<ClientLoginResponse>()
    private val calls = mutableListOf<String>()
    private val credentials = object : GoogleCredentialSource {
        override fun attach(activity: Activity) {}
        override fun detach(activity: Activity) {}
        override suspend fun getToken(begin: ClientGoogleBeginResponse) = "google-proof"
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun api(answer: (String, Array<out Any?>) -> Any?): ApiService =
        Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            calls.add(method.name)
            answer(method.name, args ?: emptyArray())
        } as ApiService
    private fun vm(api: ApiService) = ClientLoginViewModel(api, device, credentials, saved::add)
    private fun factor() = ClientLoginResponse(outcome = "twoFactorRequired", pendingLoginToken = capability, expiresAt = expiry)
    private fun quota(token: String = replacement) = ClientLoginResponse(outcome = "deviceSelectionRequired",
        errorCode = "DEVICE_LIMIT_EXCEEDED", loginContinuationToken = token, expiresAt = expiry,
        devices = listOf(DeviceDto(id = 9)), currentDevices = 1, maxDevices = 1)
    private fun success() = ClientLoginResponse(outcome = "signedIn", token = "jwt", refreshToken = "refresh",
        userId = "user", deviceId = device.deviceId)
    private fun error(body: ClientLoginResponse, status: Int) =
        Response.error<ClientLoginResponse>(status, Gson().toJson(body).toResponseBody("application/json".toMediaType()))

    @Test fun successful_mfa_advances_to_devices_then_signs_in_without_repeating_primary_or_factor() = runTest(dispatcher) {
        val vm = vm(api { method, args ->
            when (method) {
                "loginClientPassword" -> {
                    assertEquals(device.deviceId, (args[0] as ClientPasswordRequest).deviceId)
                    Response.success(factor())
                }
                "verifyClientTwoFactor" -> error(quota(), 409)
                "continueClientLogin" -> {
                    assertEquals(replacement, (args[0] as ClientContinueRequest).loginContinuationToken)
                    Response.success(success())
                }
                else -> error("Unexpected call")
            }
        })
        vm.password("person@example.com", "password")
        advanceUntilIdle()
        assertTrue(vm.state.value.step is ClientLoginStep.Factor)
        assertTrue(saved.isEmpty())
        vm.factor("123456", false)
        advanceUntilIdle()
        assertTrue(vm.state.value.step is ClientLoginStep.Devices)
        assertTrue(saved.isEmpty())
        vm.continueLogin(listOf(9))
        advanceUntilIdle()
        assertEquals(1, saved.size)
        assertEquals(listOf("loginClientPassword", "verifyClientTwoFactor", "continueClientLogin"), calls)
        vm.cancel()
        assertEquals(ClientLoginStep.Start, vm.state.value.step)
    }

    @Test fun invalid_recovery_code_preserves_pending_proof_and_expiry_clears_it() = runTest(dispatcher) {
        var response = ClientLoginResponse(errorCode = "INVALID_RECOVERY_CODE")
        val vm = vm(api { method, _ ->
            if (method == "loginClientPassword") Response.success(factor()) else error(response, 400)
        })
        vm.password("person", "password")
        advanceUntilIdle()
        vm.factor("bad-code", true)
        advanceUntilIdle()
        assertEquals(capability, (vm.state.value.step as ClientLoginStep.Factor).capability)
        assertEquals("INVALID_RECOVERY_CODE", vm.state.value.errorCode)
        response = ClientLoginResponse(errorCode = "CLIENT_LOGIN_ATTEMPT_INVALID")
        vm.factor("used-code", true)
        advanceUntilIdle()
        assertEquals(ClientLoginStep.Start, vm.state.value.step)
        assertTrue(saved.isEmpty())
    }

    @Test fun an_empty_continuation_rechecks_capacity_and_replaces_the_capability() = runTest(dispatcher) {
        val vm = vm(api { method, args ->
            if (method == "loginClientPassword") error(quota(capability), 409)
            else {
                assertTrue((args[0] as ClientContinueRequest).deviceIdsToRemove.isEmpty())
                error(quota(replacement), 409)
            }
        })
        vm.password("person", "password")
        advanceUntilIdle()
        vm.continueLogin()
        advanceUntilIdle()
        assertEquals(replacement, (vm.state.value.step as ClientLoginStep.Devices).capability)
        assertTrue(saved.isEmpty())
    }

    @Test fun linking_requires_password_factor_and_explicit_confirmation() = runTest(dispatcher) {
        val vm = vm(api { method, _ ->
            when (method) {
                "beginClientGoogle" -> Response.success(ClientGoogleBeginResponse("attempt", capability, replacement, "web-client", expiry))
                "completeClientGoogle" -> error(ClientLoginResponse(outcome = "accountLinkRequired", errorCode = "GOOGLE_LINK_REQUIRED",
                    accountLinkToken = capability, expiresAt = expiry), 409)
                "proveClientLinkPassword" -> Response.success(factor())
                "verifyClientTwoFactor" -> Response.success(ClientLoginResponse(outcome = "accountLinkConfirmationRequired",
                    accountLinkConfirmationToken = replacement, googleEmail = "google@example.com",
                    accountIdentifier = "person", expiresAt = expiry))
                "confirmClientLink" -> Response.success(success())
                else -> error("Unexpected call")
            }
        })
        vm.google()
        advanceUntilIdle()
        assertTrue(vm.state.value.step is ClientLoginStep.LinkPassword)
        vm.linkPassword("person", "password")
        advanceUntilIdle()
        vm.factor("123456", false)
        advanceUntilIdle()
        assertTrue(vm.state.value.step is ClientLoginStep.LinkConfirmation)
        assertTrue(saved.isEmpty())
        assertFalse(calls.contains("confirmClientLink"))
        vm.confirmLink()
        advanceUntilIdle()
        assertEquals(1, saved.size)
    }

    @Test fun cancellation_discards_even_a_late_success_from_a_non_cancellable_transport() = runTest(dispatcher) {
        lateinit var pending: Continuation<Response<ClientLoginResponse>>
        val vm = vm(api { _, args ->
            @Suppress("UNCHECKED_CAST")
            pending = args.last() as Continuation<Response<ClientLoginResponse>>
            COROUTINE_SUSPENDED
        })
        vm.password("person", "password")
        runCurrent()
        vm.cancel()
        pending.resume(Response.success(success()))
        advanceUntilIdle()
        assertEquals(ClientLoginStep.Start, vm.state.value.step)
        assertTrue(saved.isEmpty())
    }

    @Test fun ambiguous_pending_response_never_persists_credentials() = runTest(dispatcher) {
        val vm = vm(api { _, _ -> Response.success(factor().copy(token = "jwt", refreshToken = "refresh")) })
        vm.password("person", "password")
        advanceUntilIdle()
        assertTrue(saved.isEmpty())
        assertNotNull(vm.state.value.error)
        assertEquals(ClientLoginStep.Start, vm.state.value.step)
    }

    @Test fun google_credential_failure_does_not_post_a_proof() = runTest(dispatcher) {
        val unavailable = object : GoogleCredentialSource {
            override fun attach(activity: Activity) {}
            override fun detach(activity: Activity) {}
            override suspend fun getToken(begin: ClientGoogleBeginResponse): String =
                throw GetCredentialUnknownException("10: [28444] private-google-token person@example.com")
        }
        val vm = ClientLoginViewModel(api { method, _ ->
            assertEquals("beginClientGoogle", method)
            Response.success(ClientGoogleBeginResponse("attempt", capability, replacement, "web-client", expiry))
        }, device, unavailable, saved::add)
        vm.google()
        advanceUntilIdle()
        assertEquals(listOf("beginClientGoogle"), calls)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
        assertTrue(saved.isEmpty())
    }

    @Test fun google_backend_rejection_preserves_normal_error_handling() = runTest(dispatcher) {
        val vm = ClientLoginViewModel(api { method, _ ->
            when (method) {
                "beginClientGoogle" -> Response.success(ClientGoogleBeginResponse("attempt", capability, replacement, "web-client", expiry))
                "completeClientGoogle" -> error(ClientLoginResponse(errorCode = "INVALID_GOOGLE_PROOF", message = "private-backend-message"), 401)
                else -> error("Unexpected call")
            }
        }, device, credentials, saved::add)
        vm.google()
        advanceUntilIdle()
        assertEquals("INVALID_GOOGLE_PROOF", vm.state.value.errorCode)
        assertTrue(saved.isEmpty())
    }

    @Test fun malformed_google_begin_stops_before_credential_retrieval() = runTest(dispatcher) {
        var requestedToken = false
        val unused = object : GoogleCredentialSource {
            override fun attach(activity: Activity) {}
            override fun detach(activity: Activity) {}
            override suspend fun getToken(begin: ClientGoogleBeginResponse): String {
                requestedToken = true
                return "unused"
            }
        }
        val vm = ClientLoginViewModel(api { _, _ ->
            Response.success(ClientGoogleBeginResponse("attempt", capability, "bad", "web-client", expiry))
        }, device, unused, saved::add)
        vm.google()
        advanceUntilIdle()
        assertFalse(requestedToken)
        assertNotNull(vm.state.value.error)
    }

    @Test fun google_cancellation_does_not_create_an_error_or_complete_login() = runTest(dispatcher) {
        val cancelled = object : GoogleCredentialSource {
            override fun attach(activity: Activity) {}
            override fun detach(activity: Activity) {}
            override suspend fun getToken(begin: ClientGoogleBeginResponse): String =
                throw GetCredentialCancellationException("User cancelled")
        }
        val vm = ClientLoginViewModel(api { _, _ ->
            Response.success(ClientGoogleBeginResponse("attempt", capability, replacement, "web-client", expiry))
        }, device, cancelled, saved::add)
        vm.google()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(listOf("beginClientGoogle"), calls)
    }

    @Test fun missing_google_activity_stops_before_creating_a_backend_attempt() = runTest(dispatcher) {
        val detached = object : GoogleCredentialSource {
            override fun attach(activity: Activity) {}
            override fun detach(activity: Activity) {}
            override fun ensureActivityAttached() {
                throw IllegalStateException("Google sign-in requires a live Activity.")
            }
            override suspend fun getToken(begin: ClientGoogleBeginResponse): String = error("Must not request credentials")
        }
        val vm = ClientLoginViewModel(api { _, _ -> error("Must not call the backend") },
            device, detached, saved::add)
        vm.google()
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertTrue(saved.isEmpty())
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
    }

}
