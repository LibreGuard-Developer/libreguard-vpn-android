package net.libreguard.vpn.service.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalDnsPolicyTest {
    @Test
    fun `IKEv2 replaces every downloaded resolver with private DNS`() {
        assertEquals(
            "10.254.0.53",
            InternalDnsPolicy.authoritativeIkev2DnsServers("8.8.8.8 2001:4860:4860::8888")
        )
        assertEquals("10.254.0.53", InternalDnsPolicy.authoritativeIkev2DnsServers(null))
    }

    @Test
    fun `OpenVPN removes legacy modern IPv4 IPv6 and conflicting filters`() {
        val input = """
            client
            dev tun
            dhcp-option DNS 8.8.8.8
            dhcp-option DNS6 2001:4860:4860::8888
            dns server 1 address 9.9.9.9
            pull-filter accept "route"
            pull-filter ignore "dhcp-option   DNS"
            pull-filter reject "dns   server"
            <ca>
            certificate-data
            </ca>
        """.trimIndent()

        val normalized = InternalDnsPolicy.normalizeOpenVpnConfig(input)

        assertTrue(normalized.contains("client\ndev tun"))
        assertTrue(normalized.contains("pull-filter accept \"route\""))
        assertTrue(normalized.contains("<ca>\ncertificate-data\n</ca>"))
        assertFalse(normalized.contains("8.8.8.8"))
        assertFalse(normalized.contains("2001:4860:4860::8888"))
        assertFalse(normalized.contains("9.9.9.9"))
        assertEquals(1, Regex("(?m)^dhcp-option DNS 10\\.254\\.0\\.53$").findAll(normalized).count())
        assertEquals(1, Regex("(?m)^pull-filter ignore \\\"dhcp-option DNS\\\"$").findAll(normalized).count())
        assertEquals(1, Regex("(?m)^pull-filter ignore \\\"dns server\\\"$").findAll(normalized).count())
    }

    @Test
    fun `OpenVPN normalization is idempotent for cached profiles`() {
        val first = InternalDnsPolicy.normalizeOpenVpnConfig("client\r\nremote vpn.example 1194\r\n")
        val second = InternalDnsPolicy.normalizeOpenVpnConfig(first)
        assertEquals(first, second)
    }

    @Test
    fun `OpenVPN never emits filtered resolver`() {
        val normalized = InternalDnsPolicy.normalizeOpenVpnConfig(
            "client\ndhcp-option DNS 10.254.0.54\n"
        )
        assertFalse(normalized.contains("10.254.0.54"))
        assertTrue(normalized.contains("10.254.0.53"))
    }

    @Test
    fun `malformed empty OpenVPN input fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            InternalDnsPolicy.normalizeOpenVpnConfig(" \r\n\t")
        }
    }
}
