package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppInternetBlockPolicyTest {

    @Test
    fun soloPulsarYSubwaySurfersEstanSoportados() {
        assertTrue(PerAppInternetBlockPolicy.isSupported("com.rhmsoft.pulsar"))
        assertTrue(PerAppInternetBlockPolicy.isSupported("com.rhmsoft.pulsar.pro"))
        assertTrue(PerAppInternetBlockPolicy.isSupported("com.kiloo.subwaysurf"))

        assertFalse(PerAppInternetBlockPolicy.isSupported("com.whatsapp"))
        assertFalse(PerAppInternetBlockPolicy.isSupported("com.waze"))
        assertFalse(PerAppInternetBlockPolicy.isSupported("com.android.chrome"))
        assertFalse(PerAppInternetBlockPolicy.isSupported(""))

        assertEquals(
            setOf("com.rhmsoft.pulsar", "com.kiloo.subwaysurf"),
            PerAppInternetBlockPolicy.filterSupported(
                listOf("com.whatsapp", "com.rhmsoft.pulsar", "com.waze", "com.kiloo.subwaysurf")
            )
        )
    }

    @Test
    fun matcheaDominiosExactosSubdominiosMayusculasYPuntoFinal() {
        val blocked = setOf("com.rhmsoft.pulsar", "com.kiloo.subwaysurf")

        // Pulsar
        assertEquals(
            "com.rhmsoft.pulsar",
            PerAppInternetBlockPolicy.matchedPackageForDomain("rhmsoft.com", blocked)
        )
        assertEquals(
            "com.rhmsoft.pulsar",
            PerAppInternetBlockPolicy.matchedPackageForDomain("API.Spotify.COM.", blocked)
        )
        assertEquals(
            "com.rhmsoft.pulsar",
            PerAppInternetBlockPolicy.matchedPackageForDomain("i.scdn.co", blocked)
        )
        assertEquals(
            "com.rhmsoft.pulsar",
            PerAppInternetBlockPolicy.matchedPackageForDomain("ws.audioscrobbler.com", blocked)
        )
        assertEquals(
            "com.rhmsoft.pulsar",
            PerAppInternetBlockPolicy.matchedPackageForDomain("is1-ssl.mzstatic.com", blocked)
        )

        // Subway Surfers (backend SYBO/Kiloo + SDKs de anuncios/telemetría)
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("subway.prod.sybo.net", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("network-check.sybo.net", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("manifest.tower.sybo.net", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("sybo.helpshift.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("adserver.unityads.unity3d.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("cdn-adn.rayjump.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("googleads.g.doubleclick.net", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("graph.facebook.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("init.supersonicads.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("d.applovin.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("api.vungle.com", blocked)
        )
        assertEquals(
            "com.kiloo.subwaysurf",
            PerAppInternetBlockPolicy.matchedPackageForDomain("sdk.singular.net", blocked)
        )
    }

    @Test
    fun aislamientoEntreAppsYDesactivadoNoBloqueaNada() {
        // Solo Pulsar bloqueado -> dominios exclusivos de Subway Surfers pasan
        val soloPulsar = setOf("com.rhmsoft.pulsar")
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("subway.prod.sybo.net", soloPulsar))
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("adserver.unityads.unity3d.com", soloPulsar))
        assertEquals("com.rhmsoft.pulsar", PerAppInternetBlockPolicy.matchedPackageForDomain("api.spotify.com", soloPulsar))

        // Solo Subway Surfers bloqueado -> dominios exclusivos de Pulsar pasan
        val soloSubway = setOf("com.kiloo.subwaysurf")
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("rhmsoft.com", soloSubway))
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("api.spotify.com", soloSubway))
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("ws.audioscrobbler.com", soloSubway))
        assertEquals("com.kiloo.subwaysurf", PerAppInternetBlockPolicy.matchedPackageForDomain("subway.prod.sybo.net", soloSubway))

        // Ninguna activa o solo paquetes no soportados -> todo pasa
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("subway.prod.sybo.net", emptySet()))
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("api.spotify.com", setOf("com.whatsapp")))

        // No debe haber falsos positivos por sufijo parcial (e.g. "mysybo.net" != "sybo.net")
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("mysybo.net", soloSubway))
        assertNull(PerAppInternetBlockPolicy.matchedPackageForDomain("notspotify.com", soloPulsar))
    }

    @Test
    fun nuncaBloqueaInfraestructuraNiDominiosDeAppsKosher() {
        val allSupported = PerAppInternetBlockPolicy.SUPPORTED_PACKAGES
        val allDomains = PerAppInternetBlockPolicy.domainsForPackages(allSupported)

        // Ningún dominio de infraestructura de LockSuite/Google/FCM/Play puede quedar bloqueado
        for (infra in WhitelistCatalog.INFRASTRUCTURE) {
            assertNull(
                "El dominio de infraestructura $infra no puede quedar bloqueado",
                PerAppInternetBlockPolicy.matchedPackageForDomain(infra, allSupported)
            )
            val normInfra = PerAppInternetBlockPolicy.normalizeDomain(infra)
            for (blocked in allDomains) {
                assertFalse(
                    "El bloqueo '$blocked' no puede cubrir ni igualar al dominio de infraestructura '$normInfra'",
                    normInfra == blocked || normInfra.endsWith(".$blocked")
                )
            }
        }

        // fbcdn.net lo usa WhatsApp en WhitelistCatalog; no se puede bloquear nunca
        val mustNeverBlock = listOf(
            "fbcdn.net",
            "scontent.xx.fbcdn.net",
            "whatsapp.com",
            "whatsapp.net",
            "mercadopago.com",
            "mercadolibre.com.ar",
            "waze.com",
            "googleapis.com",
            "gstatic.com",
            "firebaseio.com",
            "mtalk.google.com",
            "play.google.com",
            "locksuite-nueva.firebaseapp.com"
        )
        for (safeDomain in mustNeverBlock) {
            assertNull(
                "Dominio esencial/kosher bloqueado por error: $safeDomain",
                PerAppInternetBlockPolicy.matchedPackageForDomain(safeDomain, allSupported)
            )
        }
    }
}
