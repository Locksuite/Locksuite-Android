package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.dns.DomainRuleTrie
import com.ejemplo.locksuite.dns.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBlockingTest {

    @Test
    fun ridesSngLink_isContainedInBlockAlways() {
        assertTrue(
            "rides.sng.link debe estar en BLOCK_ALWAYS",
            WhitelistCatalog.BLOCK_ALWAYS.contains("rides.sng.link")
        )
    }

    @Test
    fun ridesSngLink_isGloballyBlacklistedInWebViewPolicy() {
        assertTrue(
            "rides.sng.link debe estar bloqueado en WebViewPolicy",
            WebViewPolicy.isGloballyBlacklisted("rides.sng.link")
        )
        assertTrue(
            "subdominios de rides.sng.link deben estar bloqueados en WebViewPolicy",
            WebViewPolicy.isGloballyBlacklisted("sub.rides.sng.link")
        )
    }

    @Test
    fun ridesSngLink_isBlockedInWhitelistRulesTrie() {
        val rules = WhitelistManager.buildRules(
            decisions = emptyMap(),
            custom = emptyMap(),
            includeSharedCdn = true
        )
        assertEquals(
            "rides.sng.link debe resolverse como BLOCK en buildRules",
            RuleType.BLOCK,
            rules["rides.sng.link"]
        )

        val trie = DomainRuleTrie.build(rules)
        assertEquals(
            "rides.sng.link debe resolverse como BLOCK en el Trie",
            RuleType.BLOCK,
            trie.resolve("rides.sng.link")
        )
        assertEquals(
            "subdominios de rides.sng.link deben resolverse como BLOCK en el Trie",
            RuleType.BLOCK,
            trie.resolve("track.rides.sng.link")
        )
    }

    @Test
    fun googleLocatorMapsDomains_areAllowedInWebViewPolicy_whileSearchIsBlocked() {
        // Servidores de mapas y teselas deben estar autorizados en WebViewPolicy
        val mapDomains = listOf(
            "maps.google.com",
            "khm.google.com",
            "khms.google.com",
            "khm0.google.com",
            "khm1.google.com",
            "khms0.google.com",
            "khms1.google.com"
        )
        for (domain in mapDomains) {
            org.junit.Assert.assertFalse(
                "El dominio de mapas $domain no debe estar bloqueado globalmente",
                WebViewPolicy.isGloballyBlacklisted(domain)
            )
        }

        // Pero el buscador web general de Google y portales deben permanecer estrictamente bloqueados
        val searchDomains = listOf(
            "google.com",
            "www.google.com",
            "search.google.com",
            "bing.com"
        )
        for (domain in searchDomains) {
            assertTrue(
                "El buscador $domain debe permanecer bloqueado globalmente",
                WebViewPolicy.isGloballyBlacklisted(domain)
            )
        }
    }

    @Test
    fun googleLocator_entryContainsMapsDomainsInWhitelistCatalog() {
        val admEntry = WhitelistCatalog.entryFor("com.google.android.apps.adm")
        org.junit.Assert.assertNotNull("La entrada de Localizador debe existir", admEntry)
        val allowed = admEntry!!.allow
        assertTrue("Debe permitir maps.google.com", allowed.contains("maps.google.com"))
        assertTrue("Debe permitir khm.google.com", allowed.contains("khm.google.com"))
        assertTrue("Debe permitir khms.google.com", allowed.contains("khms.google.com"))
        assertTrue("Debe permitir khms0.google.com", allowed.contains("khms0.google.com"))
    }
}
