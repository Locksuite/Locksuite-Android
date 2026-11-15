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
}
