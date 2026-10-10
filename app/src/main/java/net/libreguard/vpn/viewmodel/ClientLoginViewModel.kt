package net.libreguard.vpn.viewmodel

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.DeviceKeyManager
import net.libreguard.vpn.util.GoogleCredentialProvider
import net.libreguard.vpn.util.GoogleCredentialSource
import retrofit2.Response

data class ClientLoginUiState(
    val step: ClientLoginStep = ClientLoginStep.Start,
    val busy: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
    val verificationEmail: String? = null
)

class ClientLoginViewModel(
    private val api: ApiService,
    private val device: ClientDeviceRequest,
    val googleCredentials: GoogleCredentialSource,
    private val persistSession: (ClientLoginResponse) -> Unit
) : ViewModel() {
    private val mutableState = MutableStateFlow(ClientLoginUiState())
    val state: StateFlow<ClientLoginUiState> = mutableState
    private var requestJob: Job? = null
    private var generation = 0
    private val gson = Gson()

    fun cancel() {
        generation++
        requestJob?.cancel()
        requestJob = null
        mutableState.value = ClientLoginUiState()
    }

    fun password(identifier: String, password: String) {
        if (mutableState.value.busy) return
        cancel()
        request {
            api.loginClientPassword(ClientPasswordRequest(identifier.trim(), password, device.deviceId, device.appVersion,
                device.devicePublicKey, device.devicePublicKeyId, device.devicePublicKeyAlgorithm))
        }
    }

    fun google() {
        if (mutableState.value.busy) return
        cancel()
        val currentGeneration = generation
        mutableState.value = mutableState.value.copy(busy = true, error = null, errorCode = null)
        requestJob = viewModelScope.launch {
            try {
                val response = api.beginClientGoogle(device)
                if (generation != currentGeneration) return@launch
                if (!response.isSuccessful) { failure(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"]); return@launch }
                val begin = requireNotNull(response.body())
                require(begin.redemptionToken.length in 32..128 && begin.nonce.length in 32..128 && begin.clientId.isNotBlank())
                val idToken = googleCredentials.getToken(begin)
                if (generation != currentGeneration) return@launch
                val completed = api.completeClientGoogle(ClientGoogleCompleteRequest(begin.attemptId, begin.redemptionToken, idToken))
                if (generation == currentGeneration) handle(completed)
            } catch (_: GetCredentialCancellationException) {
                if (generation == currentGeneration) mutableState.value = ClientLoginUiState()
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                if (generation == currentGeneration) mutableState.value = ClientLoginUiState(error = "Google sign-in could not be completed. Please try again.")
            } finally {
                if (generation == currentGeneration) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun factor(code: String, recovery: Boolean) {
        val step = mutableState.value.step as? ClientLoginStep.Factor ?: return
        request {
            if (recovery) api.verifyClientRecoveryCode(ClientFactorRequest(step.capability, recoveryCode = code.trim()))
            else api.verifyClientTwoFactor(ClientFactorRequest(step.capability, twoFactorCode = code.trim()))
        }
    }
    fun continueLogin(ids: List<Int> = emptyList()) {
        val step = mutableState.value.step as? ClientLoginStep.Devices ?: return
        request { api.continueClientLogin(ClientContinueRequest(step.capability, ids)) }
    }
    fun linkPassword(identifier: String, password: String) {
        val step = mutableState.value.step as? ClientLoginStep.LinkPassword ?: return
        request { api.proveClientLinkPassword(ClientLinkPasswordRequest(step.capability, identifier.trim(), password)) }
    }
    fun confirmLink() {
        val step = mutableState.value.step as? ClientLoginStep.LinkConfirmation ?: return
        request { api.confirmClientLink(ClientLinkConfirmRequest(step.capability)) }
    }

    private fun request(action: suspend () -> Response<ClientLoginResponse>) {
        if (mutableState.value.busy) return
        val currentGeneration = generation
        mutableState.value = mutableState.value.copy(busy = true, error = null, errorCode = null, verificationEmail = null)
        requestJob = viewModelScope.launch {
            try {
                val response = action()
                if (generation == currentGeneration) handle(response)
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                if (generation == currentGeneration) mutableState.value = mutableState.value.copy(error = "The request could not be completed. Try again, or start a new sign-in attempt.")
            } finally {
                if (generation == currentGeneration) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private fun handle(response: Response<ClientLoginResponse>) {
        val body = if (response.isSuccessful) response.body()
            else response.errorBody()?.string()?.let { runCatching { gson.fromJson(it, ClientLoginResponse::class.java) }.getOrNull() }
        if (body?.outcome == null) {
            failure(response.code(), body?.let { gson.toJson(it) }, response.headers()["Retry-After"])
            return
        }
        val step = decodeClientLoginStep(body, response.code(), device.deviceId)
        if (step is ClientLoginStep.SignedIn) persistSession(step.response)
        mutableState.value = ClientLoginUiState(step)
    }

    private fun failure(status: Int, body: String?, retryAfter: String?) {
        val error = body?.let { runCatching { gson.fromJson(it, ClientLoginResponse::class.java) }.getOrNull() }
        val code = error?.errorCode
        val terminal = code in setOf("CLIENT_LOGIN_ATTEMPT_INVALID", "INVALID_GOOGLE_PROOF", "GOOGLE_LINK_CONFLICT",
            "ACCOUNT_LOCKED", "GOOGLE_IDENTITY_REJECTED", "INVALID_DEVICE_SELECTION", "INVALID_DEVICE_KEY", "EMAIL_NOT_VERIFIED") ||
            status == 401 && code != "INVALID_CREDENTIALS"
        mutableState.value = mutableState.value.copy(
            step = if (terminal) ClientLoginStep.Start else mutableState.value.step,
            error = clientLoginErrorMessage(code, retryAfter), errorCode = code,
            verificationEmail = if (code == "EMAIL_NOT_VERIFIED") error?.email else null
        )
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val context = context.applicationContext
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ClientLoginViewModel::class.java))
            val tokens = RetrofitClient.getTokenManager()
            val device = ClientDeviceRequest(tokens.requireDeviceId(), tokens.getAppVersion(),
                DeviceKeyManager.exportPublicKeyBase64(), DeviceKeyManager.publicKeyId(), DeviceKeyManager.algorithm())
            @Suppress("UNCHECKED_CAST")
            return ClientLoginViewModel(RetrofitClient.authApiService, device, GoogleCredentialProvider(context)) { response ->
                tokens.beginSession(requireNotNull(response.token), requireNotNull(response.refreshToken), requireNotNull(response.userId))
                tokens.saveDeviceId(requireNotNull(response.deviceId))
                tokens.saveCurrentDeviceKeyId(requireNotNull(device.devicePublicKeyId))
                tokens.saveDeviceMetadata(response.activeDevices ?: 0, response.maxDevices ?: 1)
                context.getSharedPreferences("vpn_state_prefs", Context.MODE_PRIVATE).edit()
                    .remove("pending_device_limit_exceeded").apply()
            } as T
        }
    }
}
