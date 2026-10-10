package net.libreguard.vpn.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.AccountSessionContext
import net.libreguard.vpn.util.AccountSessionSource
import retrofit2.Response
import java.util.UUID

data class NewsletterUiState(
    val settings: NewsletterSettingsResponse? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val requiresRefresh: Boolean = true,
    val promptVisible: Boolean = false,
    val consentChecked: Boolean = false,
    val error: String? = null
)

class NewsletterViewModel(
    private val api: ApiService,
    private val sessions: AccountSessionSource
) : ViewModel() {
    private val mutableState = MutableStateFlow(NewsletterUiState())
    val state: StateFlow<NewsletterUiState> = mutableState
    private var job: Job? = null
    private var generation = 0L
    private var boundSession: AccountSessionContext? = null

    fun bindSession(authenticated: Boolean) {
        val context = if (authenticated) sessions.sessionSnapshot()?.context else null
        if (context != boundSession) {
            reset()
            boundSession = context
        }
        if (context != null) refresh()
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        boundSession = null
        mutableState.value = NewsletterUiState()
    }

    fun refresh() {
        if (!mutableState.value.isSaving && boundSession != null) load()
    }

    fun setConsentChecked(checked: Boolean) {
        if (!mutableState.value.isSaving && !mutableState.value.isLoading)
            mutableState.value = mutableState.value.copy(consentChecked = checked, error = null)
    }

    fun dismissPrompt() {
        mutableState.value = mutableState.value.copy(promptVisible = false, consentChecked = false)
    }

    fun completeOnboarding(subscribe: Boolean) {
        val previous = mutableState.value
        val settings = previous.settings ?: return
        if (!settings.promptPending || subscribe && !previous.consentChecked) return
        write { authorization, session ->
            api.completeNewsletterOnboarding(authorization, session, NewsletterOnboardingRequest(
                if (subscribe) "subscribe" else "skip", settings.revision,
                settings.consentTextVersion.takeIf { subscribe }
            ))
        }
    }

    fun setSubscribed(subscribed: Boolean) {
        val settings = mutableState.value.settings ?: return
        if (settings.subscribed == subscribed) return
        if (subscribed && !settings.canSubscribe) {
            mutableState.value = mutableState.value.copy(error = "Verify your account email before subscribing.")
            return
        }
        write { authorization, session ->
            api.updateNewsletter(authorization, session, UpdateNewsletterRequest(
                subscribed, settings.revision, settings.consentTextVersion.takeIf { subscribed }
            ))
        }
    }

    private fun currentSnapshot() = sessions.sessionSnapshot()?.takeIf {
        it.context == boundSession && sessions.isSessionCurrent(it.context)
    }

    private fun accepts(requestGeneration: Long, session: AccountSessionContext): Boolean =
        generation == requestGeneration && boundSession == session && sessions.isSessionCurrent(session)

    private fun validate(settings: NewsletterSettingsResponse, session: AccountSessionContext) {
        require(settings.accountId == session.accountId)
        require(settings.consentText.isNotBlank() && settings.consentTextVersion.isNotBlank())
        UUID.fromString(settings.revision)
    }

    private fun load(message: String? = null) {
        val snapshot = currentSnapshot() ?: return
        job?.cancel()
        val requestGeneration = ++generation
        mutableState.value = mutableState.value.copy(
            isLoading = true, isSaving = false, requiresRefresh = true, consentChecked = false, error = message
        )
        job = viewModelScope.launch {
            try {
                val response = api.getNewsletter("Bearer " + snapshot.accessToken, snapshot.context)
                if (!accepts(requestGeneration, snapshot.context)) return@launch
                val settings = response.body()
                if (response.isSuccessful && settings != null) {
                    validate(settings, snapshot.context)
                    mutableState.value = NewsletterUiState(
                        settings = settings, requiresRefresh = false, promptVisible = settings.promptPending, error = message
                    )
                } else mutableState.value = mutableState.value.copy(
                    isLoading = false, error = message ?: "Could not load email preferences. Try again."
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                if (accepts(requestGeneration, snapshot.context))
                    mutableState.value = mutableState.value.copy(
                        isLoading = false, error = message ?: "Could not load email preferences. Try again."
                    )
            }
        }
    }

    private fun write(action: suspend (String, AccountSessionContext) -> Response<NewsletterSettingsResponse>) {
        val previous = mutableState.value
        if (previous.settings == null || previous.requiresRefresh || previous.isLoading || previous.isSaving) return
        val snapshot = currentSnapshot() ?: return
        job?.cancel()
        val requestGeneration = ++generation
        mutableState.value = previous.copy(isSaving = true, error = null)
        job = viewModelScope.launch {
            try {
                val response = action("Bearer " + snapshot.accessToken, snapshot.context)
                if (!accepts(requestGeneration, snapshot.context)) return@launch
                val settings = response.body()
                if (response.isSuccessful && settings != null) {
                    validate(settings, snapshot.context)
                    mutableState.value = NewsletterUiState(settings = settings, requiresRefresh = false, promptVisible = settings.promptPending)
                    return@launch
                }
                val message = when (response.code()) {
                    409 -> "Your email preferences or consent wording changed. Review the updated choice and choose again."
                    422 -> "Verify your account email before subscribing."
                    429 -> "Too many requests. Please wait and try again."
                    else -> "Could not confirm your choice. Review your current preference before trying again."
                }
                // Read current state after an ambiguous result; never replay a consent write.
                load(message)
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                if (accepts(requestGeneration, snapshot.context))
                    load("Could not confirm your choice. Review your current preference before trying again.")
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val context = context.applicationContext
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NewsletterViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return NewsletterViewModel(RetrofitClient.newsletterApiService, RetrofitClient.getTokenManager()) as T
        }
    }
}
