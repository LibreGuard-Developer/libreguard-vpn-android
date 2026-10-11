package net.libreguard.vpn.util

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import net.libreguard.vpn.network.ClientGoogleBeginResponse
import java.lang.ref.WeakReference

interface GoogleCredentialSource {
    fun attach(activity: Activity)
    fun detach(activity: Activity)
    fun ensureActivityAttached() {}
    suspend fun getToken(begin: ClientGoogleBeginResponse): String
}

class GoogleCredentialProvider(context: Context) : GoogleCredentialSource {
    private val applicationContext = context.applicationContext
    private val manager = CredentialManager.create(applicationContext)
    private var activity = WeakReference<Activity>(null)
    private var pendingContext: MutableContextWrapper? = null

    override fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        pendingContext?.baseContext = activity
    }
    override fun detach(activity: Activity) {
        if (this.activity.get() === activity) {
            this.activity.clear()
            pendingContext?.baseContext = applicationContext
        }
    }

    override fun ensureActivityAttached() {
        requireActivity()
    }

    private fun requireActivity(): Activity = checkNotNull(
        activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    ) { "Google sign-in requires a live Activity." }

    override suspend fun getToken(begin: ClientGoogleBeginResponse): String {
        // The explicit button flow does not request automatic account selection.
        val option = GetSignInWithGoogleOption.Builder(begin.clientId).setNonce(begin.nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val context = MutableContextWrapper(requireActivity())
        pendingContext = context
        try {
            val credential = manager.getCredential(context, request).credential
            require(credential is CustomCredential)
            require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL)
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        } finally {
            context.baseContext = applicationContext
            if (pendingContext === context) pendingContext = null
        }
    }

    companion object {
        suspend fun clearState(context: Context) {
            CredentialManager.create(context.applicationContext).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
