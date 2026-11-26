package com.ejemplo.locksuite.util

import com.ejemplo.locksuite.mdm.PerAppInternetBlockPolicy
import com.ejemplo.locksuite.service.KosherVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class Layer2HardeningTest {

    @Test
    fun adBlocker_parseHostLine_stripsInlineCommentsAndAcceptsPunycode() {
        assertEquals("ads.example.com", AdBlocker.parseHostLine("0.0.0.0 ads.example.com # inline comment"))
        assertEquals("tracker.test.org", AdBlocker.parseHostLine("127.0.0.1 tracker.test.org   # comment"))
        assertEquals("doubleclick.net", AdBlocker.parseHostLine("||doubleclick.net^#adblock"))
        assertEquals("sub.xn--p1ai", AdBlocker.parseHostLine("0.0.0.0 sub.xn--p1ai"))
        assertEquals("my-ad-host.xn--j1amh", AdBlocker.parseHostLine("my-ad-host.xn--j1amh"))

        assertNull(AdBlocker.parseHostLine("# full line comment"))
        assertNull(AdBlocker.parseHostLine("! adblock comment"))
        assertNull(AdBlocker.parseHostLine("0.0.0.0 localhost"))
        assertNull(AdBlocker.parseHostLine("127.0.0.1 127.0.0.1"))
        assertNull(AdBlocker.parseHostLine("   "))
    }

    @Test
    fun kosherVpn_publicDnsListsAreValidAndUnique() {
        val v4 = KosherVpnService.KNOWN_PUBLIC_DNS_V4
        val v6 = KosherVpnService.KNOWN_PUBLIC_DNS_V6

        assertEquals("KNOWN_PUBLIC_DNS_V4 no debe tener duplicados", v4.size, v4.toSet().size)
        assertEquals("KNOWN_PUBLIC_DNS_V6 no debe tener duplicados", v6.size, v6.toSet().size)

        for (ip in v4) {
            val parsed = InetAddress.getByName(ip)
            assertTrue("$ip debe ser Inet4Address válido", parsed is Inet4Address)
        }
        for (ip in v6) {
            val parsed = InetAddress.getByName(ip)
            assertTrue("$ip debe ser Inet6Address válido", parsed is Inet6Address)
        }

        // Verificar cobertura de resolutores públicos y endpoints DoH/DoT agregados
        assertTrue(v4.containsAll(listOf("1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3")))
        assertTrue(v4.containsAll(listOf("94.140.14.14", "45.90.28.0", "185.228.168.9", "194.242.2.2", "76.76.2.0", "223.5.5.5")))
        assertTrue(v6.containsAll(listOf(
            "2620:fe::fe", "2620:fe::9",
            "2620:119:35::35", "2620:119:53::53",
            "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
            "2001:4860:4860::6464", "2001:4860:4860::64",
            "2606:4700:4700::1112", "2606:4700:4700::1002",
            "2606:4700:4700::1113", "2606:4700:4700::1003",
            "2a07:a8c0::", "2a07:a8c1::"
        )))
    }

    @Test
    fun kosherVpn_blockedResponsesDoNotMaskDeadUpstreamInHealthTelemetry() {
        KosherVpnService.resetTelemetryForTest()

        // Simular consultas bloqueadas localmente + consultas upstream sin respuesta
        KosherVpnService.tunnelPacketsIn.set(10L)
        KosherVpnService.tunnelDnsQueriesIn.set(10L)
        repeat(5) { KosherVpnService.noteBlockedResponse() }
        repeat(3) { KosherVpnService.noteUpstreamQuery() }

        assertEquals(5L, KosherVpnService.tunnelBlockedResponsesOut.get())
        assertEquals(3L, KosherVpnService.tunnelForwardedQueriesIn.get())
        assertEquals(0L, KosherVpnService.tunnelUpstreamResponsesOut.get())
        assertEquals(5L, KosherVpnService.tunnelResponsesOut.get())

        val healthDeadUpstream = KosherVpnService.evaluateTunnelHealth(
            tunnelRunning = true,
            readyAtMs = 1_000L,
            nowElapsedMs = 200_000L,
            screenOn = true,
            hasPhysicalNetwork = true,
            packetsIn = KosherVpnService.tunnelPacketsIn.get(),
            forwardedQueriesIn = KosherVpnService.tunnelForwardedQueriesIn.get(),
            upstreamResponsesOut = KosherVpnService.tunnelUpstreamResponsesOut.get()
        )
        assertEquals(KosherVpnService.HEALTH_NO_UPSTREAM, healthDeadUpstream)

        // Si solo hubo bloqueos locales y 0 intentos upstream, no reporta SIN_SALIDA
        val healthOnlyBlocked = KosherVpnService.evaluateTunnelHealth(
            tunnelRunning = true,
            readyAtMs = 1_000L,
            nowElapsedMs = 200_000L,
            screenOn = true,
            hasPhysicalNetwork = true,
            packetsIn = 5L,
            forwardedQueriesIn = 0L,
            upstreamResponsesOut = 0L
        )
        assertEquals(KosherVpnService.HEALTH_OK, healthOnlyBlocked)

        // Cuando llegan respuestas upstream, vuelve a OK y noteTunnelResponse mantiene compatibilidad
        KosherVpnService.noteUpstreamResponse()
        assertEquals(1L, KosherVpnService.tunnelUpstreamResponsesOut.get())
        KosherVpnService.noteTunnelResponse()
        assertEquals(2L, KosherVpnService.tunnelUpstreamResponsesOut.get())

        val healthOk = KosherVpnService.evaluateTunnelHealth(
            tunnelRunning = true,
            readyAtMs = 1_000L,
            nowElapsedMs = 200_000L,
            screenOn = true,
            hasPhysicalNetwork = true,
            packetsIn = KosherVpnService.tunnelPacketsIn.get(),
            forwardedQueriesIn = KosherVpnService.tunnelForwardedQueriesIn.get(),
            upstreamResponsesOut = KosherVpnService.tunnelUpstreamResponsesOut.get()
        )
        assertEquals(KosherVpnService.HEALTH_OK, healthOk)
    }

    @Test
    fun kosherVpn_windowedUpstreamBrokenDetectsStalledUpstream() {
        KosherVpnService.resetTelemetryForTest()
        assertFalse(KosherVpnService.evaluateWindowedUpstreamBroken(now = 1_000L, inNow = 10L, outNow = 5L))
        // Dentro de la ventana (< 120s) no dispara todavía
        assertFalse(KosherVpnService.evaluateWindowedUpstreamBroken(now = 60_000L, inNow = 25L, outNow = 5L))
        // Pasados >= 120s con >= 8 consultas nuevas hacia upstream y 0 respuestas upstream nuevas
        assertTrue(KosherVpnService.evaluateWindowedUpstreamBroken(now = 125_000L, inNow = 25L, outNow = 5L))
        // Si hubo respuesta upstream en la nueva ventana, no dispara
        assertFalse(KosherVpnService.evaluateWindowedUpstreamBroken(now = 250_000L, inNow = 35L, outNow = 6L))
    }

    @Test
    fun perAppInternetBlockPolicy_matchesWithoutFalsePositives() {
        val blocked = setOf(PerAppInternetBlockPolicy.PULSAR_FREE, PerAppInternetBlockPolicy.SUBWAY_SURFERS)
        assertEquals(PerAppInternetBlockPolicy.PULSAR_FREE, PerAppInternetBlockPolicy.matchedPackageForDomain("api.spotify.com", blocked))
        assertEquals(PerAppInternetBlockPolicy.SUBWAY_SURFERS, PerAppInternetBlockPolicy.matchedPackageForDomain("subway.prod.sybo.net", blocked))
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("googleapis.com", blocked))
    }
}
