package net.libreguard.vpn.util

import android.util.Log
import net.libreguard.vpn.network.EncryptedPassphrasePayload
import java.util.Base64
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object PassphraseDecryptor {
    private const val TAG = "PassphraseDecryptor"
    private const val REVISION = "oaep-diagnostics-r2"

    private data class OaepMode(
        val name: String,
        val transformation: String,
        val spec: OAEPParameterSpec? = null
    )

    private val oaepModes = listOf(
        OaepMode(
            name = "RSA/ECB/PKCS1Padding",
            transformation = "RSA/ECB/PKCS1Padding",
            spec = null
        ),
        OaepMode(
            name = "generic-OAEP SHA-512/MGF1-SHA512",
            transformation = "RSA/ECB/OAEPPadding",
            spec = OAEPParameterSpec(
                "SHA-512",
                "MGF1",
                MGF1ParameterSpec.SHA512,
                PSource.PSpecified.DEFAULT
            )
        ),
        OaepMode(
            name = "generic-OAEP SHA-512/MGF1-SHA1",
            transformation = "RSA/ECB/OAEPPadding",
            spec = OAEPParameterSpec(
                "SHA-512",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT
            )
        ),
        OaepMode(
            name = "generic-OAEP SHA-256/MGF1-SHA256",
            transformation = "RSA/ECB/OAEPPadding",
            spec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            )
        ),
        OaepMode(
            name = "generic-OAEP SHA-256/MGF1-SHA1",
            transformation = "RSA/ECB/OAEPPadding",
            spec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT
            )
        ),
        OaepMode(
            name = "SHA-256/MGF1-SHA1",
            transformation = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
            spec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT
            )
        ),
        OaepMode(
            name = "SHA-256/MGF1-SHA256",
            transformation = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
            spec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            )
        ),
        OaepMode(
            name = "provider-default",
            transformation = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
            spec = null
        ),
        OaepMode(
            name = "generic-provider-default",
            transformation = "RSA/ECB/OAEPPadding",
            spec = null
        )
    )

    fun revision(): String = REVISION

    fun decrypt(payload: EncryptedPassphrasePayload): String {
        require(payload.algorithm == DeviceKeyManager.algorithm()) {
            "Unsupported encrypted passphrase algorithm: ${payload.algorithm}"
        }

        val currentKeyId = DeviceKeyManager.currentPublicKeyId()
            ?: throw IllegalStateException("Device encryption key is unavailable")
        Log.d(
            TAG,
            "Decrypting encrypted passphrase revision=$REVISION aliasState=${DeviceKeyManager.describeCurrentBinding()} keyInfo=${DeviceKeyManager.describeCurrentKeyCapabilities()} payloadKeyId=${payload.keyId} algorithm=${payload.algorithm} ciphertextChars=${payload.ciphertext.length}"
        )
        require(payload.keyId == currentKeyId) {
            "Encrypted passphrase key mismatch"
        }

        return decrypt(payload.ciphertext)
    }

    fun decrypt(ciphertextBase64: String): String {
        val privateKey = DeviceKeyManager.getPrivateKey()
        val ciphertextBytes = decodeCiphertext(ciphertextBase64)
        Log.d(
            TAG,
            "Beginning RSA OAEP decrypt revision=$REVISION ciphertextBytes=${ciphertextBytes.size} privateKeyAlgorithm=${privateKey.algorithm} privateKeyFormat=${privateKey.format} privateKeyClass=${privateKey.javaClass.name}"
        )
        var lastError: Throwable? = null

        for (mode in oaepModes) {
            val specSummary = describeSpec(mode.spec)
            try {
                val cipher = Cipher.getInstance(mode.transformation)
                Log.d(
                    TAG,
                    "Attempting OAEP mode ${mode.name} transformation=${mode.transformation} spec=$specSummary provider=${cipher.provider.name}/${cipher.provider.javaClass.name}"
                )
                if (mode.spec != null) {
                    cipher.init(Cipher.DECRYPT_MODE, privateKey, mode.spec)
                } else {
                    cipher.init(Cipher.DECRYPT_MODE, privateKey)
                }
                val plaintextBytes = cipher.doFinal(ciphertextBytes)
                Log.d(
                    TAG,
                    "Decrypted VPN passphrase using OAEP mode ${mode.name} transformation=${mode.transformation} plaintextBytes=${plaintextBytes.size}"
                )
                return plaintextBytes.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                lastError = e
                Log.w(
                    TAG,
                    "Failed OAEP mode ${mode.name} transformation=${mode.transformation} spec=$specSummary: ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
            }
        }

        throw IllegalStateException(
            "Unable to decrypt ciphertext (${ciphertextBytes.size} bytes) with any supported OAEP mode",
            lastError
        )
    }

    private fun decodeCiphertext(ciphertextBase64: String): ByteArray {
        val cleaned = ciphertextBase64.replace("\\s".toRegex(), "")
        val decoders = listOf<(String) -> ByteArray>(
            { Base64.getDecoder().decode(it) },
            { Base64.getUrlDecoder().decode(it) }
        )

        var lastError: Throwable? = null
        for (decoder in decoders) {
            try {
                return decoder(cleaned)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw IllegalArgumentException("Invalid encrypted passphrase base64", lastError)
    }

    private fun describeSpec(spec: OAEPParameterSpec?): String {
        if (spec == null) return "provider-default"
        val mgfParameters = spec.mgfParameters as? MGF1ParameterSpec
        return "digest=${spec.digestAlgorithm},mgf=${spec.mgfAlgorithm},mgfDigest=${mgfParameters?.digestAlgorithm ?: "unknown"},pSource=${spec.pSource.javaClass.simpleName}"
    }
}
