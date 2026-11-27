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
            "rides.sng.link debe resolverse como FORCE_BLOCK en buildRules",
            RuleType.FORCE_BLOCK,
            rules["rides.sng.link"]
        )

        val trie = DomainRuleTrie.build(rules)
        assertEquals(
            "rides.sng.link debe resolverse como FORCE_BLOCK en el Trie",
            RuleType.FORCE_BLOCK,
            trie.resolve("rides.sng.link")
        )
        assertEquals(
            "subdominios de rides.sng.link deben resolverse como FORCE_BLOCK en el Trie",
            RuleType.FORCE_BLOCK,
            trie.resolve("track.rides.sng.link")
        )
    }

    @Test
    fun appsAppleAndXCom_areGloballyBlockedForAllApps() {
        for (domain in listOf("apps.apple.com", "x.com")) {
            assertTrue(
                "$domain debe estar en WhitelistCatalog.BLOCK_ALWAYS",
                WhitelistCatalog.BLOCK_ALWAYS.contains(domain)
            )
            assertTrue(
                "$domain debe estar bloqueado en WebViewPolicy",
                WebViewPolicy.isGloballyBlacklisted(domain)
            )
            assertTrue(
                "subdominios de $domain deben estar bloqueados en WebViewPolicy",
                WebViewPolicy.isGloballyBlacklisted("www.$domain")
            )
        }

        val rules = WhitelistManager.buildRules(
            decisions = emptyMap(),
            custom = emptyMap(),
            includeSharedCdn = true
        )
        val trie = DomainRuleTrie.build(rules)
        for (domain in listOf("apps.apple.com", "x.com")) {
            assertEquals(
                "$domain debe resolverse como FORCE_BLOCK en buildRules",
                RuleType.FORCE_BLOCK,
                rules[domain]
            )
            assertEquals(
                "$domain debe resolverse como FORCE_BLOCK en el Trie",
                RuleType.FORCE_BLOCK,
                trie.resolve(domain)
            )
            assertEquals(
                "subdominios de $domain deben resolverse como FORCE_BLOCK en el Trie",
                RuleType.FORCE_BLOCK,
                trie.resolve("sub.$domain")
            )
        }
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
            "khms1.google.com",
            "mt.google.com",
            "mt0.google.com",
            "mt1.google.com",
            "mt2.google.com",
            "mt3.google.com",
            "mt4.google.com",
            "mt7.google.com",
            "mts.google.com",
            "mts0.google.com",
            "mts1.google.com",
            "mts2.google.com",
            "mts3.google.com",
            "mts5.google.com",
            "lh3.google.com",
            "lh6.google.com",
            "clients1.google.com",
            "clients5.google.com",
            "geo.google.com",
            "geocoding.google.com"
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
            "news.google.com",
            "translate.google.com",
            "images.google.com",
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
        assertTrue("Debe permitir mt0.google.com", allowed.contains("mt0.google.com"))
        assertTrue("Debe permitir mts0.google.com", allowed.contains("mts0.google.com"))
    }

    @Test
    fun transit_entryContainsMapsDomainsInWhitelistCatalog() {
        val transitEntry = WhitelistCatalog.entryFor("com.thetransitapp.droid")
        org.junit.Assert.assertNotNull("La entrada de Transit debe existir", transitEntry)
        val allowed = transitEntry!!.allow
        assertTrue("Debe permitir transitapp.com", allowed.contains("transitapp.com"))
        assertTrue("Debe permitir thetransitapp.com", allowed.contains("thetransitapp.com"))
        assertTrue("Debe permitir transit.land", allowed.contains("transit.land"))
        assertTrue("Debe permitir mapbox.com", allowed.contains("mapbox.com"))
        assertTrue("Debe permitir maps.google.com", allowed.contains("maps.google.com"))
        assertTrue("Debe permitir khm.google.com", allowed.contains("khm.google.com"))
        assertTrue("Debe permitir khms0.google.com", allowed.contains("khms0.google.com"))
        assertTrue("Debe permitir mt.google.com", allowed.contains("mt.google.com"))
        assertTrue("Debe permitir mt0.google.com", allowed.contains("mt0.google.com"))
        assertTrue("Debe permitir mt1.google.com", allowed.contains("mt1.google.com"))
        assertTrue("Debe permitir mts0.google.com", allowed.contains("mts0.google.com"))
    }

    @Test
    fun transit_isConfiguredInCoreDomains() {
        val core = WebViewPolicy.getCoreDomainsFor("com.thetransitapp.droid")
        org.junit.Assert.assertNotNull("Transit debe estar registrado en CORE_DOMAINS", core)
        assertTrue("CORE_DOMAINS debe contener transitapp.com", core!!.contains("transitapp.com"))
        assertTrue("CORE_DOMAINS debe contener mapbox.com", core.contains("mapbox.com"))
        assertTrue("CORE_DOMAINS debe contener googleapis.com", core.contains("googleapis.com"))
    }

    @Test
    fun domainRuleTrie_stripsWildcardAndNeverWeakensForceRules() {
        val trie = DomainRuleTrie.build(
            mapOf(
                "*.example.com" to RuleType.FORCE_BLOCK,
                "safe.example.com" to RuleType.ALLOW,
                "*.example.org" to RuleType.FORCE_ALLOW,
                "ads.example.org" to RuleType.BLOCK,
                "override.example.com" to RuleType.FORCE_ALLOW,
                "override-block.example.org" to RuleType.FORCE_BLOCK
            )
        )
        // Wildcard stripping
        assertEquals(RuleType.FORCE_BLOCK, trie.resolve("example.com"))
        assertEquals(RuleType.FORCE_BLOCK, trie.resolve("sub.example.com"))
        // Normal child rules NEVER weaken parent FORCE_* rules
        assertEquals(RuleType.FORCE_BLOCK, trie.resolve("safe.example.com"))
        assertEquals(RuleType.FORCE_BLOCK, trie.resolve("deep.safe.example.com"))
        assertEquals(RuleType.FORCE_ALLOW, trie.resolve("ads.example.org"))
        // Child FORCE_* rule CAN override parent FORCE_* rule
        assertEquals(RuleType.FORCE_ALLOW, trie.resolve("override.example.com"))
        assertEquals(RuleType.FORCE_BLOCK, trie.resolve("override-block.example.org"))
    }

    @Test
    fun dohAndProxyDomains_areBlockedInCatalogWebViewPolicyAndTrie() {
        val evadeDomains = listOf(
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "mozilla.cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard-dns.com",
            "dns.nextdns.io",
            "doh.cleanbrowsing.org",
            "dns.mullvad.net",
            "freedns.controld.com",
            "dns.alidns.com",
            "use-application-dns.net",
            "mask.icloud.com",
            "croxyproxy.com",
            "proxysite.com",
            "torproject.org",
            "webcache.googleusercontent.com",
            "translate.goog",
            "youtubeembeddedplayer.googleapis.com"
        )
        val rules = WhitelistManager.buildRules(
            decisions = emptyMap(),
            custom = emptyMap(),
            includeSharedCdn = true
        )
        val trie = DomainRuleTrie.build(rules)
        for (domain in evadeDomains) {
            assertTrue("$domain debe estar en BLOCK_ALWAYS", WhitelistCatalog.BLOCK_ALWAYS.contains(domain))
            assertTrue("$domain debe estar en WebViewPolicy.GLOBAL_BLACKLIST", WebViewPolicy.getGlobalBlacklist().contains(domain))
            assertTrue("$domain debe quedar bloqueado globalmente en WebViewPolicy", WebViewPolicy.isGloballyBlacklisted(domain))
            assertEquals("$domain debe resolverse como FORCE_BLOCK en Trie", RuleType.FORCE_BLOCK, trie.resolve(domain))
            assertEquals("sub.$domain debe resolverse como FORCE_BLOCK en Trie", RuleType.FORCE_BLOCK, trie.resolve("sub.$domain"))
        }
    }

    @Test
    fun didi_includesGameXiaojukejiAndSevenBlockedHosts() {
        val didi = WhitelistCatalog.entryFor("com.didiglobal.passenger")
        org.junit.Assert.assertNotNull(didi)
        assertEquals(7, didi!!.block.size)
        assertTrue(didi.block.contains("game.xiaojukeji.com"))
        assertTrue(WebViewPolicy.isGloballyBlacklisted("game.xiaojukeji.com"))
    }

    @Test
    fun webViewPolicy_genericAppFailsClosedOnEmptyTokens() {
        org.junit.Assert.assertFalse(WebViewPolicy.isDomainAllowedForGenericApp("com.android.app", "arbitrary-web.com"))
        org.junit.Assert.assertFalse(WebViewPolicy.isDomainAllowedForGenericApp("com.mobile.user", "anything.org"))
        org.junit.Assert.assertFalse(WebViewPolicy.isDomainAllowedForGenericApp("", "anything.org"))
        // Legitimate token match works while hidden substring fails
        assertTrue(WebViewPolicy.isDomainAllowedForGenericApp("com.cabify.passenger", "api.cabify.com"))
        org.junit.Assert.assertFalse(WebViewPolicy.isDomainAllowedForGenericApp("com.cabify.passenger", "evilcabifyscam.ru"))
    }

    @Test
    fun irloxNonKosherDomains_areGloballyBlockedForAllDevices_andCatalogEntriesExist() {
        val irloxBlocked = listOf(
            "parodebondis.com.ar",
            "torrezm.dev",
            "itunes.apple.com",
            "doubleclick.net",
            "googleads.g.doubleclick.net",
            "googlesyndication.com",
            "pagead2.googlesyndication.com",
            "googleadservices.com",
            "imasdk.googleapis.com",
            "fundingchoicesmessages.google.com",
            "mediation.goog",
            "admob-gmats.uc.r.appspot.com",
            "unityads.unity3d.com",
            "gateway.unityads.unity3d.com",
            "unity3dusercontent.com"
        )
        val rules = WhitelistManager.buildRules(
            decisions = emptyMap(),
            custom = emptyMap(),
            includeSharedCdn = true
        )
        val trie = DomainRuleTrie.build(rules)
        for (domain in irloxBlocked) {
            assertTrue("$domain debe estar bloqueado globalmente en WebViewPolicy", WebViewPolicy.isGloballyBlacklisted(domain))
            assertEquals("$domain debe resolverse como FORCE_BLOCK en Trie", RuleType.FORCE_BLOCK, trie.resolve(domain))
            assertEquals("sub.$domain debe resolverse como FORCE_BLOCK en Trie", RuleType.FORCE_BLOCK, trie.resolve("sub.$domain"))
        }

        org.junit.Assert.assertFalse("api.irlox.com no debe bloquearse globalmente", WebViewPolicy.isGloballyBlacklisted("api.irlox.com"))
        org.junit.Assert.assertFalse("osmand.net no debe bloquearse globalmente", WebViewPolicy.isGloballyBlacklisted("osmand.net"))

        val osmand = WhitelistCatalog.entryFor("net.osmand")
        val irlox = WhitelistCatalog.entryFor("com.irlox")
        org.junit.Assert.assertNotNull("Entrada OsmAnd debe existir", osmand)
        org.junit.Assert.assertNotNull("Entrada Irlox debe existir", irlox)
        assertTrue(osmand!!.allow.contains("osmand.net"))
        assertTrue(irlox!!.allow.contains("api.irlox.com"))

        val irloxCore = WebViewPolicy.getCoreDomainsFor("com.irlox")
        org.junit.Assert.assertNotNull("CORE_DOMAINS debe incluir com.irlox", irloxCore)
        assertTrue(irloxCore!!.contains("api.irlox.com"))
    }
}
