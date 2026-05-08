package net.libreguard.vpn.util

import android.util.Log
import java.security.Provider
import java.security.Security

object BouncyCastleBootstrap {
    private const val TAG = "BouncyCastleBootstrap"
    private const val BC_PROVIDER_NAME = "BC"
    private const val EXTERNAL_BC_CLASS = "org.bouncycastle.jce.provider.BouncyCastleProvider"

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureExternalProviderRegistered(): Provider? {
        if (initialized) {
            return Security.getProvider(BC_PROVIDER_NAME)
        }

        val externalProvider = try {
            Class.forName(EXTERNAL_BC_CLASS).getDeclaredConstructor().newInstance() as Provider
        } catch (e: Exception) {
            Log.w(TAG, "External BouncyCastle provider unavailable: ${e.message}")
            return null
        }

        val current = Security.getProvider(BC_PROVIDER_NAME)
        if (current != null && current.javaClass.name == EXTERNAL_BC_CLASS) {
            initialized = true
            return current
        }

        if (current != null) {
            Log.w(TAG, "Replacing provider BC implementation ${current.javaClass.name} with external $EXTERNAL_BC_CLASS")
            Security.removeProvider(BC_PROVIDER_NAME)
        }

        Security.insertProviderAt(externalProvider, 1)
        initialized = true
        val installed = Security.getProvider(BC_PROVIDER_NAME)
        Log.i(TAG, "Registered external BC provider class=${installed?.javaClass?.name} version=${installed?.version}")
        return installed
    }
}

