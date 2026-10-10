package net.libreguard.vpn.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.*
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
class NewsletterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = AccountSessionContext("account-a", 1)
    private var active = AccountSessionSnapshot(session, "owned-token")
    private val sessions = object : AccountSessionSource {
        override fun sessionSnapshot() = active
        override fun isSessionCurrent(context: AccountSessionContext) = context == active.context
    }
    private val initial = NewsletterSettingsResponse("account-a", false, true, true,
        "News and offers", "v1", "https://libreguard.net/privacy",
        "3fea7638-e486-46cc-9c12-93863a70611c")

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun api(answer: (String, Array<out Any?>) -> Any?): ApiService =
        Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        } as ApiService

    @Test fun conflict_refetches_and_requires_another_explicit_choice_instead_of_replaying() = runTest(dispatcher) {
        var reads = 0
        val writes = mutableListOf<NewsletterOnboardingRequest>()
        val updated = initial.copy(consentTextVersion = "v2",
            revision = "fd974ab0-4136-4a37-9b5a-94a97761d902")
        val vm = NewsletterViewModel(api { method, args ->
            assertEquals(session, args[1])
            when (method) {
                "getNewsletter" -> Response.success(if (reads++ == 0) initial else updated)
                "completeNewsletterOnboarding" -> {
                    writes.add(args[2] as NewsletterOnboardingRequest)
                    if (writes.size == 1) Response.error<NewsletterSettingsResponse>(
                        409, "{}".toResponseBody("application/json".toMediaType())
                    ) else Response.success(updated.copy(subscribed = true, promptPending = false))
                }
                else -> error(method)
            }
        }, sessions)
        vm.bindSession(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.promptVisible)
        assertFalse(vm.state.value.consentChecked)
        vm.setConsentChecked(true)
        vm.completeOnboarding(true)
        advanceUntilIdle()
        assertEquals(1, writes.size)
        assertEquals("v2", vm.state.value.settings!!.consentTextVersion)
        assertFalse(vm.state.value.consentChecked)
        vm.completeOnboarding(true)
        advanceUntilIdle()
        assertEquals(1, writes.size)
        vm.setConsentChecked(true)
        vm.completeOnboarding(true)
        advanceUntilIdle()
        assertEquals("v2", writes.last().consentTextVersion)
        assertTrue(vm.state.value.settings!!.subscribed)
        assertFalse(vm.state.value.promptVisible)
    }

    @Test fun skip_completes_initial_choice_without_enrollment_and_existing_accounts_do_not_prompt() = runTest(dispatcher) {
        var completed = initial
        var request: NewsletterOnboardingRequest? = null
        val vm = NewsletterViewModel(api { method, args ->
            when (method) {
                "getNewsletter" -> Response.success(completed)
                "completeNewsletterOnboarding" -> {
                    request = args[2] as NewsletterOnboardingRequest
                    completed = initial.copy(promptPending = false)
                    Response.success(completed)
                }
                else -> error(method)
            }
        }, sessions)
        vm.bindSession(true)
        advanceUntilIdle()
        vm.completeOnboarding(false)
        advanceUntilIdle()
        assertEquals("skip", request!!.decision)
        assertNull(request!!.consentTextVersion)
        assertFalse(vm.state.value.settings!!.subscribed)
        assertFalse(vm.state.value.promptVisible)
        vm.reset()
        vm.bindSession(true)
        advanceUntilIdle()
        assertFalse(vm.state.value.promptVisible)
    }

    @Test fun interrupted_choice_resumes_from_backend_after_session_restoration() = runTest(dispatcher) {
        val vm = NewsletterViewModel(api { method, _ ->
            check(method == "getNewsletter")
            Response.success(initial)
        }, sessions)
        vm.bindSession(true)
        advanceUntilIdle()
        vm.dismissPrompt()
        assertFalse(vm.state.value.promptVisible)
        vm.reset()
        vm.bindSession(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.promptVisible)
        assertFalse(vm.state.value.consentChecked)
    }

    @Test fun late_mutation_response_cannot_replace_another_accounts_settings() = runTest(dispatcher) {
        lateinit var pending: Continuation<Response<NewsletterSettingsResponse>>
        val vm = NewsletterViewModel(api { method, args ->
            when (method) {
                "getNewsletter" -> Response.success(initial.copy(accountId = active.context.accountId, promptPending = false))
                "updateNewsletter" -> {
                    @Suppress("UNCHECKED_CAST")
                    pending = args.last() as Continuation<Response<NewsletterSettingsResponse>>
                    COROUTINE_SUSPENDED
                }
                else -> error(method)
            }
        }, sessions)
        vm.bindSession(true)
        advanceUntilIdle()
        vm.setSubscribed(true)
        runCurrent()
        active = AccountSessionSnapshot(AccountSessionContext("account-b", 2), "other-token")
        vm.bindSession(true)
        runCurrent()
        pending.resume(Response.success(initial.copy(subscribed = true)))
        advanceUntilIdle()
        assertEquals("account-b", vm.state.value.settings!!.accountId)
        assertFalse(vm.state.value.settings!!.subscribed)
        assertFalse(vm.state.value.isSaving)
    }

    @Test fun failed_refetch_after_write_failure_blocks_more_writes_until_current_state_is_known() = runTest(dispatcher) {
        var reads = 0
        var writes = 0
        val vm = NewsletterViewModel(api { method, _ ->
            when (method) {
                "getNewsletter" -> {
                    if (reads++ == 0) Response.success(initial.copy(promptPending = false)) else throw java.io.IOException()
                }
                "updateNewsletter" -> { writes++; throw java.io.IOException() }
                else -> error(method)
            }
        }, sessions)
        vm.bindSession(true)
        advanceUntilIdle()
        vm.setSubscribed(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.requiresRefresh)
        assertFalse(vm.state.value.settings!!.subscribed)
        vm.setSubscribed(true)
        advanceUntilIdle()
        assertEquals(1, writes)
    }
}
