package net.libreguard.vpn.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object DeviceKeyManager {
    private const val TAG = "DeviceKeyManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "managementpanel_device_key"
    private const val SOFTWARE_KEY_ALIAS = "managementpanel_device_key_software"
    private const val PREFS_NAME = "device_key_manager_secure_store"
    private const val PREF_BACKEND = "device_key_backend"
    private const val PREF_VERIFIED_BACKEND = "device_key_verified_backend"
    private const val PREF_VERIFIED_KEY_ID = "device_key_verified_key_id"
    private const val PREF_SOFTWARE_PUBLIC_KEY = "software_public_key"
    private const val PREF_SOFTWARE_PRIVATE_KEY = "software_private_key"
    private val REQUIRED_OAEP_DIGESTS = setOf(
        KeyProperties.DIGEST_SHA1,
        KeyProperties.DIGEST_SHA256
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var securePrefs: SharedPreferences? = null

    private enum class KeyBackend(val persistedValue: String) {
        ANDROID_KEYSTORE("keystore"),
        SOFTWARE("software");

        companion object {
            fun fromPersisted(value: String?): KeyBackend? = entries.firstOrNull { it.persistedValue == value }
        }
    }

    data class DeviceKeyBinding(
        val alias: String,
        val publicKey: PublicKey,
        val publicKeyBase64: String,
        val keyId: String,
        val backend: String
    )

    private data class OaepSelfTestMode(
        val name: String,
        val transformation: String,
        val spec: OAEPParameterSpec? = null
    )

    private val oaepSelfTestModes = listOf(
        OaepSelfTestMode(
            name = "generic-OAEP SHA-256/MGF1-SHA256",
            transformation = "RSA/ECB/OAEPPadding",
            spec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            )
        )
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun ensureKeyBinding(): DeviceKeyBinding {
        getPreferredBackend()?.let { preferred ->
            resolveExistingBinding(preferred)?.let { return it }
            Log.w(TAG, "Preferred device key backend ${preferred.persistedValue} is unavailable; re-evaluating key backend")
        }

        ensureKeyStoreBinding()?.let {
            setPreferredBackend(KeyBackend.ANDROID_KEYSTORE)
            return it
        }

        return ensureSoftwareBinding().also {
            setPreferredBackend(KeyBackend.SOFTWARE)
        }
    }

    fun ensureKeyPair(): PublicKey {
        return ensureKeyBinding().publicKey
    }

    fun deleteKeyPair() {
        deleteKeyStoreKeyPair()
        deleteSoftwareKeyPair()
        clearBackendState()
    }

    fun rotateKeyPair(): PublicKey {
        val previousBackend = getPreferredBackend()
        deleteKeyPair()
        val binding = when (previousBackend) {
            KeyBackend.SOFTWARE -> ensureSoftwareBinding().also { setPreferredBackend(KeyBackend.SOFTWARE) }
            else -> ensureKeyBinding()
        }
        Log.w(TAG, "Rotated device key pair for alias ${binding.alias}; backend=${binding.backend}; newKeyId=${binding.keyId}")
        return binding.publicKey
    }

    fun currentAlias(): String = getBindingOrNull()?.alias ?: KEY_ALIAS

    fun describeCurrentBinding(): String {
        val binding = getBindingOrNull()
        return if (binding == null) {
            "alias=$KEY_ALIAS missing"
        } else {
            "alias=${binding.alias}, backend=${binding.backend}, keyId=${binding.keyId}, publicKeyBytes=${binding.publicKey.encoded.size}"
        }
    }

    fun describeCurrentKeyCapabilities(): String {
        val binding = getBindingOrNull() ?: return "alias=$KEY_ALIAS missing"
        return if (binding.backend == KeyBackend.SOFTWARE.persistedValue) {
            "backend=software,algorithm=${binding.publicKey.algorithm},class=${binding.publicKey.javaClass.simpleName},insideSecureHardware=false,digests=[SHA-1,SHA-256,SHA-384,SHA-512],paddings=[OAEPPadding],purposes=[DECRYPT,ENCRYPT],compatibility=ok"
        } else {
            runCatching {
                val privateKey = getKeyStorePrivateKeyOrNull() ?: throw IllegalStateException("Android Keystore private key unavailable")
                val keyFactory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
                val keyInfo = keyFactory.getKeySpec(privateKey, KeyInfo::class.java)
                val digests = keyInfo.digests.orEmpty().joinToString(",")
                val paddings = keyInfo.encryptionPaddings.orEmpty().joinToString(",")
                val purposes = buildList {
                    if ((keyInfo.purposes and KeyProperties.PURPOSE_DECRYPT) != 0) add("DECRYPT")
                    if ((keyInfo.purposes and KeyProperties.PURPOSE_ENCRYPT) != 0) add("ENCRYPT")
                    if ((keyInfo.purposes and KeyProperties.PURPOSE_SIGN) != 0) add("SIGN")
                    if ((keyInfo.purposes and KeyProperties.PURPOSE_VERIFY) != 0) add("VERIFY")
                }.joinToString(",")
                val incompatibility = getKeyStoreCompatibilityIssueOrNull(binding)
                "backend=keystore,algorithm=${privateKey.algorithm},class=${privateKey.javaClass.simpleName},insideSecureHardware=${keyInfo.isInsideSecureHardware},digests=[$digests],paddings=[$paddings],purposes=[$purposes],compatibility=${incompatibility ?: "ok"}"
            }.getOrElse { error ->
                "backend=keystore,alias=${binding.alias},keyId=${binding.keyId},capabilityError=${error.javaClass.simpleName}:${error.message}"
            }
        }
    }

    fun currentPublicKeyId(): String? {
        return getBindingOrNull()?.keyId
    }

    fun isCurrentKeyPairUsable(): Boolean {
        return getBindingOrNull() != null
    }

    fun getPrivateKey(): PrivateKey {
        val binding = ensureKeyBinding()
        return if (binding.backend == KeyBackend.SOFTWARE.persistedValue) {
            getSoftwarePrivateKeyOrNull() ?: throw IllegalStateException("Software device private key is unavailable")
        } else {
            getKeyStorePrivateKeyOrNull() ?: throw IllegalStateException("Device key alias $KEY_ALIAS is missing")
        }
    }

    fun exportPublicKeyBase64(): String {
        return ensureKeyBinding().publicKeyBase64
    }

    fun publicKeyId(): String {
        return ensureKeyBinding().keyId
    }

    fun algorithm(): String = "RSA-OAEP-256"

    private fun getBindingOrNull(): DeviceKeyBinding? {
        return when (getPreferredBackend()) {
            KeyBackend.SOFTWARE -> getSoftwareBindingOrNull() ?: getKeyStoreBindingOrNull()
            KeyBackend.ANDROID_KEYSTORE -> getKeyStoreBindingOrNull() ?: getSoftwareBindingOrNull()
            null -> getKeyStoreBindingOrNull() ?: getSoftwareBindingOrNull()
        }
    }

    private fun resolveExistingBinding(preferredBackend: KeyBackend): DeviceKeyBinding? {
        return when (preferredBackend) {
            KeyBackend.ANDROID_KEYSTORE -> ensureKeyStoreBinding()
            KeyBackend.SOFTWARE -> ensureSoftwareBindingOrNull()
        }
    }

    private fun ensureKeyStoreBinding(): DeviceKeyBinding? {
        repeat(2) { attempt ->
            val binding = getKeyStoreBindingOrNull() ?: run {
                Log.d(TAG, "No Android Keystore device key present for alias $KEY_ALIAS, generating a new RSA key pair")
                generateKeyStoreKeyPair()
                getKeyStoreBindingOrNull()
            } ?: return null

            val incompatibilityReason = getKeyStoreCompatibilityIssueOrNull(binding)
            if (incompatibilityReason != null) {
                Log.w(TAG, "Android Keystore key ${binding.keyId} is incompatible with OAEP decrypt requirements ($incompatibilityReason); regenerating")
                deleteKeyStoreKeyPair()
                clearVerifiedState(binding.backend, binding.keyId)
                return@repeat
            }

            val privateKey = getKeyStorePrivateKeyOrNull()
            if (privateKey == null) {
                Log.w(TAG, "Android Keystore private key missing for alias $KEY_ALIAS; regenerating")
                deleteKeyStoreKeyPair()
                clearVerifiedState(binding.backend, binding.keyId)
                return@repeat
            }

            if (!isBindingFunctional(binding, privateKey)) {
                Log.w(TAG, "Android Keystore RSA OAEP self-test failed for keyId=${binding.keyId}; regenerating or falling back")
                deleteKeyStoreKeyPair()
                clearVerifiedState(binding.backend, binding.keyId)
                return@repeat
            }

            if (attempt > 0) {
                Log.i(TAG, "Recovered Android Keystore device key after regeneration; keyId=${binding.keyId}")
            }
            return binding
        }
        return null
    }

    private fun ensureSoftwareBinding(): DeviceKeyBinding {
        return ensureSoftwareBindingOrNull() ?: run {
            Log.w(TAG, "Falling back to software-managed RSA device key because Android Keystore RSA OAEP is unavailable on this device")
            generateSoftwareKeyPair()
            ensureSoftwareBindingOrNull()
                ?: throw IllegalStateException("Failed to create software device key binding")
        }
    }

    private fun ensureSoftwareBindingOrNull(): DeviceKeyBinding? {
        repeat(2) {
            val binding = getSoftwareBindingOrNull() ?: return@repeat
            val privateKey = getSoftwarePrivateKeyOrNull()
            if (privateKey == null) {
                Log.w(TAG, "Stored software device private key is missing or unreadable; regenerating")
                deleteSoftwareKeyPair()
                clearVerifiedState(binding.backend, binding.keyId)
                return@repeat
            }

            if (!isBindingFunctional(binding, privateKey)) {
                Log.w(TAG, "Software RSA OAEP self-test failed for keyId=${binding.keyId}; regenerating")
                deleteSoftwareKeyPair()
                clearVerifiedState(binding.backend, binding.keyId)
                return@repeat
            }

            return binding
        }
        return null
    }

    private fun generateKeyStoreKeyPair(): PublicKey {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
        )
            .setKeySize(2048)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .build()

        generator.initialize(spec)
        return generator.generateKeyPair().public
    }

    private fun generateSoftwareKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val prefs = getSecurePrefs()
        prefs.edit()
            .putString(PREF_SOFTWARE_PUBLIC_KEY, Base64.getEncoder().encodeToString(keyPair.public.encoded))
            .putString(PREF_SOFTWARE_PRIVATE_KEY, Base64.getEncoder().encodeToString(keyPair.private.encoded))
            .putString(PREF_BACKEND, KeyBackend.SOFTWARE.persistedValue)
            .apply()
        clearVerifiedState(KeyBackend.SOFTWARE.persistedValue, null)
    }

    private fun deleteKeyStoreKeyPair() {
        val ks = loadKeyStore()
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun deleteSoftwareKeyPair() {
        getSecurePrefs().edit()
            .remove(PREF_SOFTWARE_PUBLIC_KEY)
            .remove(PREF_SOFTWARE_PRIVATE_KEY)
            .apply()
    }

    private fun clearBackendState() {
        getSecurePrefs().edit()
            .remove(PREF_BACKEND)
            .remove(PREF_VERIFIED_BACKEND)
            .remove(PREF_VERIFIED_KEY_ID)
            .apply()
    }

    private fun setPreferredBackend(backend: KeyBackend) {
        getSecurePrefs().edit().putString(PREF_BACKEND, backend.persistedValue).apply()
    }

    private fun getPreferredBackend(): KeyBackend? {
        return KeyBackend.fromPersisted(getSecurePrefs().getString(PREF_BACKEND, null))
    }

    private fun cacheVerifiedBinding(binding: DeviceKeyBinding) {
        getSecurePrefs().edit()
            .putString(PREF_VERIFIED_BACKEND, binding.backend)
            .putString(PREF_VERIFIED_KEY_ID, binding.keyId)
            .apply()
    }

    private fun clearVerifiedState(backend: String?, keyId: String?) {
        val prefs = getSecurePrefs()
        val storedBackend = prefs.getString(PREF_VERIFIED_BACKEND, null)
        val storedKeyId = prefs.getString(PREF_VERIFIED_KEY_ID, null)
        val shouldClear = (backend == null || storedBackend == backend) && (keyId == null || storedKeyId == keyId)
        if (shouldClear) {
            prefs.edit()
                .remove(PREF_VERIFIED_BACKEND)
                .remove(PREF_VERIFIED_KEY_ID)
                .apply()
        }
    }

    private fun isBindingVerified(binding: DeviceKeyBinding): Boolean {
        val prefs = getSecurePrefs()
        return prefs.getString(PREF_VERIFIED_BACKEND, null) == binding.backend &&
            prefs.getString(PREF_VERIFIED_KEY_ID, null) == binding.keyId
    }

    private fun isBindingFunctional(binding: DeviceKeyBinding, privateKey: PrivateKey): Boolean {
        if (isBindingVerified(binding)) {
            return true
        }

        val testPlaintext = "libreguard:${binding.backend}:${binding.keyId.take(16)}".toByteArray(Charsets.UTF_8)
        for (mode in oaepSelfTestModes) {
            try {
                val encryptCipher = Cipher.getInstance(mode.transformation)
                if (mode.spec != null) {
                    encryptCipher.init(Cipher.ENCRYPT_MODE, binding.publicKey, mode.spec)
                } else {
                    encryptCipher.init(Cipher.ENCRYPT_MODE, binding.publicKey)
                }
                val ciphertext = encryptCipher.doFinal(testPlaintext)

                val decryptCipher = Cipher.getInstance(mode.transformation)
                if (mode.spec != null) {
                    decryptCipher.init(Cipher.DECRYPT_MODE, privateKey, mode.spec)
                } else {
                    decryptCipher.init(Cipher.DECRYPT_MODE, privateKey)
                }
                val decrypted = decryptCipher.doFinal(ciphertext)
                if (decrypted.contentEquals(testPlaintext)) {
                    Log.i(
                        TAG,
                        "RSA OAEP self-test succeeded for backend=${binding.backend} keyId=${binding.keyId} mode=${mode.name} transformation=${mode.transformation}"
                    )
                    cacheVerifiedBinding(binding)
                    return true
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "RSA OAEP self-test failed for backend=${binding.backend} keyId=${binding.keyId} mode=${mode.name} transformation=${mode.transformation}: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        return false
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getKeyStoreEntryOrNull(): KeyStore.PrivateKeyEntry? {
        val ks = loadKeyStore()
        val entry = ks.getEntry(KEY_ALIAS, null)
        return entry as? KeyStore.PrivateKeyEntry
    }

    private fun getKeyStoreBindingOrNull(): DeviceKeyBinding? {
        val entry = getKeyStoreEntryOrNull() ?: return null
        return try {
            val publicKey = entry.certificate.publicKey
            createBinding(
                alias = KEY_ALIAS,
                publicKey = publicKey,
                backend = KeyBackend.ANDROID_KEYSTORE.persistedValue
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect Android Keystore device key binding for alias $KEY_ALIAS: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getSoftwareBindingOrNull(): DeviceKeyBinding? {
        val publicKeyBase64 = getSecurePrefs().getString(PREF_SOFTWARE_PUBLIC_KEY, null) ?: return null
        return try {
            val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val publicKey = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
            createBinding(
                alias = SOFTWARE_KEY_ALIAS,
                publicKey = publicKey,
                backend = KeyBackend.SOFTWARE.persistedValue
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect software device key binding: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun createBinding(alias: String, publicKey: PublicKey, backend: String): DeviceKeyBinding {
        val publicKeyBytes = publicKey.encoded
        val keyId = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .joinToString("") { "%02x".format(it) }
        return DeviceKeyBinding(
            alias = alias,
            publicKey = publicKey,
            publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyBytes),
            keyId = keyId,
            backend = backend
        )
    }

    private fun getKeyStorePrivateKeyOrNull(): PrivateKey? {
        return getKeyStoreEntryOrNull()?.privateKey
    }

    private fun getSoftwarePrivateKeyOrNull(): PrivateKey? {
        val privateKeyBase64 = getSecurePrefs().getString(PREF_SOFTWARE_PRIVATE_KEY, null) ?: return null
        return try {
            val privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64)
            KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode software device private key: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getKeyStoreCompatibilityIssueOrNull(binding: DeviceKeyBinding): String? {
        return runCatching {
            val privateKey = getKeyStorePrivateKeyOrNull() ?: return@runCatching "missing private key"
            val keyFactory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = keyFactory.getKeySpec(privateKey, KeyInfo::class.java)

            if ((keyInfo.purposes and KeyProperties.PURPOSE_DECRYPT) == 0) {
                return@runCatching "missing decrypt purpose"
            }

            val encryptionPaddings = keyInfo.encryptionPaddings.orEmpty().toSet()
            if (KeyProperties.ENCRYPTION_PADDING_RSA_OAEP !in encryptionPaddings) {
                return@runCatching "missing RSA OAEP padding"
            }

            val supportedDigests = keyInfo.digests.orEmpty().toSet()
            val missingDigests = REQUIRED_OAEP_DIGESTS - supportedDigests
            if (missingDigests.isNotEmpty()) {
                return@runCatching "missing digests=${missingDigests.joinToString(",")}" 
            }

            null
        }.getOrElse { error ->
            Log.w(
                TAG,
                "Unable to inspect Android Keystore compatibility for alias ${binding.alias}: ${error.javaClass.simpleName}: ${error.message}"
            )
            null
        }
    }

    private fun getSecurePrefs(): SharedPreferences {
        securePrefs?.let { return it }
        val context = appContext ?: throw IllegalStateException("DeviceKeyManager.init(context) must be called before using device keys")
        synchronized(this) {
            securePrefs?.let { return it }
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { securePrefs = it }
        }
    }
}
