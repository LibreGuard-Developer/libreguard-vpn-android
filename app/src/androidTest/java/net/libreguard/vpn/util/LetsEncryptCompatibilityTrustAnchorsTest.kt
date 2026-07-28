package net.libreguard.vpn.util

import android.content.Context
import androidx.annotation.RawRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.libreguard.vpn.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.strongswan.android.logic.TrustedCertificateManager
import org.strongswan.android.logic.TrustedCertificateManager.TrustedCertificateSource
import org.strongswan.android.security.LocalCertificateStore
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

@RunWith(AndroidJUnit4::class)
class LetsEncryptCompatibilityTrustAnchorsTest {
    private lateinit var context: Context
    private lateinit var rootYe: X509Certificate
    private lateinit var rootYr: X509Certificate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rootYe = readRawCertificate(R.raw.root_ye)
        rootYr = readRawCertificate(R.raw.root_yr)
        removeBundledCompatibilityRoots()
    }

    @After
    fun tearDown() {
        removeBundledCompatibilityRoots()
        TrustedCertificateManager.getInstance().reset().load()
    }

    @Test
    fun bundledRootYrMatchesOfficialSelfSignedCertificate() {
        val expectedPrincipal = X500Principal("CN=Root YR,O=ISRG,C=US")

        assertEquals(expectedPrincipal, rootYr.subjectX500Principal)
        assertEquals(expectedPrincipal, rootYr.issuerX500Principal)
        assertTrue(rootYr.basicConstraints >= 0)
        assertTrue(rootYr.keyUsage[5])
        assertTrue(rootYr.keyUsage[6])
        assertEquals(ROOT_YR_SHA256, sha256Fingerprint(rootYr))

        rootYr.verify(rootYr.publicKey)
    }

    @Test
    fun hybridProfileSeedsBundledRootsIdempotentlyWithoutPinningCertificateAlias() {
        val manager = VpnConfigManager(context)
        val hybridConfig = JSONObject()
            .put("name", "Root YR compatibility test")
            .put("gateway", "vpn.example.test")
            .put("remote", JSONObject().put("cert", ""))

        val firstProfile = manager.createVpnProfile(hybridConfig)
        val secondProfile = manager.createVpnProfile(hybridConfig)

        assertNotNull(firstProfile)
        assertNotNull(secondProfile)
        assertNull(firstProfile?.certificateAlias)
        assertNull(secondProfile?.certificateAlias)

        val certificateStore = LocalCertificateStore()
        val rootYeAlias = certificateStore.getCertificateAlias(rootYe)
        val rootYrAlias = certificateStore.getCertificateAlias(rootYr)
        assertTrue(certificateStore.containsAlias(rootYeAlias))
        assertTrue(certificateStore.containsAlias(rootYrAlias))

        val trustedLocalCertificates = TrustedCertificateManager.getInstance()
            .reset()
            .load()
            .getCACertificates(TrustedCertificateSource.LOCAL)
            .values
        assertEquals(
            1,
            trustedLocalCertificates.count { sha256Fingerprint(it) == ROOT_YR_SHA256 }
        )
        assertTrue(trustedLocalCertificates.any { it.subjectX500Principal == rootYe.subjectX500Principal })
    }

    private fun readRawCertificate(@RawRes resourceId: Int): X509Certificate {
        return context.resources.openRawResource(resourceId).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
    }

    private fun removeBundledCompatibilityRoots() {
        val certificateStore = LocalCertificateStore()
        listOf(rootYe, rootYr).forEach { certificate ->
            certificateStore.deleteCertificate(certificateStore.getCertificateAlias(certificate))
        }
        listOf("root_ye.crt", "root_yr.crt").forEach { filename ->
            File(context.filesDir, "sswan_configs/$filename").delete()
            File(context.filesDir, "cacerts/$filename").delete()
        }
    }

    private fun sha256Fingerprint(certificate: X509Certificate): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val ROOT_YR_SHA256 =
            "E57B7E6F150C419102E8D5C055729FF967B9D1A829BF00CEC89CA604EBF4A86F"
    }
}
