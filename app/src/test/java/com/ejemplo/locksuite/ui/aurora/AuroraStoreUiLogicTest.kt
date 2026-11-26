package com.ejemplo.locksuite.ui.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraStoreUiLogicTest {

    @Test
    fun allKeysNonBlankInAllLanguages() {
        val langs = listOf(AuroraStoreText.LANG_ES, AuroraStoreText.LANG_EN, AuroraStoreText.LANG_HE)
        for (lang in langs) {
            for (key in AuroraStoreText.Key.values()) {
                val value = AuroraStoreText.t(key, lang)
                assertTrue("$key in $lang should not be blank", value.isNotBlank())
            }
        }
    }

    @Test
    fun hebrewKeysContainHebrewLettersExceptNeutralTokens() {
        val exceptions = setOf(AuroraStoreText.Key.MODE_LITE, AuroraStoreText.Key.INSTALLING_PCT_FMT)
        for (key in AuroraStoreText.Key.values()) {
            if (key in exceptions) continue
            val value = AuroraStoreText.t(key, AuroraStoreText.LANG_HE)
            val hasHebrew = value.any { it in '\u0590'..'\u05FF' }
            assertTrue("$key in he should have Hebrew characters: $value", hasHebrew)
        }
    }

    @Test
    fun languagesDifferForTranslatedKeys() {
        val same = setOf(
            AuroraStoreText.Key.MODE_KOSHER,
            AuroraStoreText.Key.MODE_LITE,
            AuroraStoreText.Key.INSTALLING_PCT_FMT
        )
        for (key in AuroraStoreText.Key.values()) {
            if (key in same) continue
            val es = AuroraStoreText.t(key, AuroraStoreText.LANG_ES)
            val en = AuroraStoreText.t(key, AuroraStoreText.LANG_EN)
            val he = AuroraStoreText.t(key, AuroraStoreText.LANG_HE)
            assertNotEquals("$key: es == en", es, en)
            assertNotEquals("$key: es == he", es, he)
        }
    }

    @Test
    fun unknownLangFallsBackToSpanishAndRtlMatchesHebrew() {
        assertEquals("es", AuroraStoreText.normalizeLang(null))
        assertEquals("he", AuroraStoreText.normalizeLang("iw"))
        assertTrue(AuroraStoreText.isRtl("he"))
        assertEquals(
            AuroraStoreText.t(AuroraStoreText.Key.TITLE, "es"),
            AuroraStoreText.t(AuroraStoreText.Key.TITLE, "fr")
        )
    }
}
