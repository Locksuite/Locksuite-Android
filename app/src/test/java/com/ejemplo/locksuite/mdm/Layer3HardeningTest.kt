package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.util.PlayButtonFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de las políticas puras endurecidas en Capa 3:
 * PhotoPickerPolicy, PlayButtonFinder, GoogleAccountWebPolicy,
 * EmbeddedBrowserDetector y CaptivePortalPolicy.
 */
class Layer3HardeningTest {

    // ── PhotoPickerPolicy.cameFromContactsImmediate ──────────────────────────

    @Test
    fun `cameFromContactsImmediate solo acepta predecesor inmediato de contactos`() {
        // Camino directo: Contactos -> selector del sistema
        assertTrue(
            PhotoPickerPolicy.cameFromContactsImmediate(
                recentAppStack = listOf("com.google.android.providers.media.module", "com.google.android.contacts"),
                currentPkg = "com.google.android.providers.media.module"
            )
        )
        // Camino desde Dialer hacia Google Photos picker
        assertTrue(
            PhotoPickerPolicy.cameFromContactsImmediate(
                recentAppStack = listOf("com.google.android.dialer"),
                currentPkg = "com.google.android.apps.photos"
            )
        )
        // El usuario abrió Contactos antes, luego WhatsApp, y adjunta foto desde WhatsApp: NO debe bloquear
        assertFalse(
            PhotoPickerPolicy.cameFromContactsImmediate(
                recentAppStack = listOf(
                    "com.google.android.providers.media.module",
                    "com.whatsapp",
                    "com.google.android.contacts"
                ),
                currentPkg = "com.google.android.providers.media.module"
            )
        )
        // La ventana actual es un paquete de contactos directamente
        assertTrue(
            PhotoPickerPolicy.cameFromContactsImmediate(
                recentAppStack = listOf("com.whatsapp"),
                currentPkg = "com.android.contacts"
            )
        )
    }

    @Test
    fun `PhotoPickerPolicy distingue selector de perfil vs generico y evita StartActivity`() {
        assertTrue(
            PhotoPickerPolicy.shouldBounce(
                "com.google.android.contacts",
                "com.google.android.apps.contacts.art.ArtActivity",
                cameFromContacts = false
            )
        )
        assertTrue(
            PhotoPickerPolicy.shouldBounce(
                "com.google.android.gms",
                "com.google.android.libraries.user.profile.photopicker.IllustrationPickerActivity",
                cameFromContacts = false
            )
        )
        assertFalse(
            PhotoPickerPolicy.shouldBounce(
                "tfilon.tfilon",
                "tfilon.tfilon.TfilonStartActivity",
                cameFromContacts = true
            )
        )
        assertTrue(
            PhotoPickerPolicy.shouldBounce(
                "com.google.android.apps.photos",
                "com.google.android.apps.photos.picker.external.ExternalPickerActivity",
                cameFromContacts = true
            )
        )
        assertFalse(
            PhotoPickerPolicy.shouldBounce(
                "com.google.android.apps.photos",
                "com.google.android.apps.photos.picker.external.ExternalPickerActivity",
                cameFromContacts = false
            )
        )
    }

    // ── PlayButtonFinder helpers ─────────────────────────────────────────────

    @Test
    fun `PlayButtonFinder excluye metadatos de fecha y palabras prohibidas`() {
        assertTrue(PlayButtonFinder.isExcludedActionMetadata("ultima actualizacion: 15 nov 2026"))
        assertTrue(PlayButtonFinder.isExcludedActionMetadata("last updated"))
        assertTrue(PlayButtonFinder.isExcludedActionMetadata("last update: oct 2026"))
        assertTrue(PlayButtonFinder.isExcludedActionMetadata("ultima atualizacao"))
        assertTrue(PlayButtonFinder.isExcludedActionMetadata("updated on nov 10"))
        assertTrue(PlayButtonFinder.isExcludedActionMetadata("actualizado el 10 nov"))
        assertFalse(PlayButtonFinder.isExcludedActionMetadata("actualizar"))

        assertTrue(PlayButtonFinder.isActionText("actualizar"))
        assertTrue(PlayButtonFinder.isActionText("actualizar waze"))
        assertTrue(PlayButtonFinder.isActionText("update"))
        assertTrue(PlayButtonFinder.isActionText("instalar"))
        assertTrue(PlayButtonFinder.isActionText("install"))
        assertTrue(PlayButtonFinder.isActionText("עדכון"))
        assertTrue(PlayButtonFinder.isActionText("התקנה"))

        assertFalse(PlayButtonFinder.isActionText("ultima actualizacion 15 nov 2026"))
        assertFalse(PlayButtonFinder.isActionText("last updated"))
        assertFalse(PlayButtonFinder.isActionText("desinstalar"))
        assertFalse(PlayButtonFinder.isActionText("cancelar"))
        assertFalse(PlayButtonFinder.isActionText("liberar espacio"))
        assertFalse(PlayButtonFinder.isActionText("installing"))
    }

    @Test
    fun `PlayButtonFinder shouldAllowCandidateWhenOpenVisible bloquea candidatos posicionales`() {
        assertTrue(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_POSITION, false))
        assertTrue(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_POSITION_WIDE, false))

        assertFalse(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_POSITION, true))
        assertFalse(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_POSITION_WIDE, true))
        assertFalse(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_WORD - 10, true))
        assertTrue(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_WORD, true))
        assertTrue(PlayButtonFinder.shouldAllowCandidateWhenOpenVisible(PlayButtonFinder.SCORE_VIEW_ID, true))
    }

    // ── GoogleAccountWebPolicy nuevos dominios y marcadores ──────────────────

    @Test
    fun `GoogleAccountWebPolicy cubre googlehelp octarine activity y myadcenter`() {
        // Nuevos marcadores de clase
        assertTrue(
            GoogleAccountWebPolicy.isAccountWebClass(
                "com.google.android.gms.googlehelp.helpactivities.HelpActivity",
                strict = false
            )
        )
        assertFalse(
            GoogleAccountWebPolicy.isAccountWebClass(
                "com.google.android.gms.accountsettings.octarine.OctarineActivity",
                strict = false
            )
        )
        assertTrue(
            GoogleAccountWebPolicy.isAccountWebClass(
                "com.google.android.gms.octarine.OctarineActivity",
                strict = true
            )
        )
        // Exclusiones siguen ganando aun en modo estricto
        assertFalse(
            GoogleAccountWebPolicy.isAccountWebClass(
                "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity",
                strict = true
            )
        )

        // Nuevos dominios en modo normal
        assertTrue(GoogleAccountWebPolicy.isBlockedHost("activity.google.com", strict = false))
        assertTrue(GoogleAccountWebPolicy.isBlockedHost("sub.activity.google.com.", strict = false))
        assertTrue(GoogleAccountWebPolicy.isBlockedHost("myadcenter.google.com", strict = false))
        assertTrue(GoogleAccountWebPolicy.isBlockedHost("www.myadcenter.google.com", strict = false))
        assertTrue(GoogleAccountWebPolicy.isBlockedHost("myactivity.google.com", strict = false))

        // Dominios de login/administracion en modo normal vs estricto
        assertFalse(GoogleAccountWebPolicy.isBlockedHost("accounts.google.com", strict = true))
        assertFalse(GoogleAccountWebPolicy.isBlockedHost("myaccount.google.com", strict = false))
        assertTrue(GoogleAccountWebPolicy.isBlockedHost("myaccount.google.com", strict = true))
    }

    // ── EmbeddedBrowserDetector geckoview/smtt y exenciones ──────────────────

    @Test
    fun `EmbeddedBrowserDetector reconoce geckoview smtt y evalua exenciones`() {
        assertTrue(EmbeddedBrowserDetector.isWebViewClassName("org.mozilla.geckoview.GeckoView"))
        assertTrue(EmbeddedBrowserDetector.isWebViewClassName("com.tencent.smtt.sdk.WebView"))
        assertTrue(EmbeddedBrowserDetector.isWebViewClassName("android.webkit.WebView"))
        assertTrue(EmbeddedBrowserDetector.isWebViewClassName("org.xwalk.core.XWalkView"))
        assertTrue(EmbeddedBrowserDetector.isWebViewClassName("org.chromium.content.browser.RenderFrameHost"))
        assertFalse(EmbeddedBrowserDetector.isWebViewClassName("android.widget.TextView"))
        assertFalse(EmbeddedBrowserDetector.isWebViewClassName(null))

        val exemptMinutemaid = EmbeddedBrowserDetector.Retrato(
            packageName = "com.google.android.gms",
            activityClass = "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity",
            tieneWebView = true,
            fraccionWebView = 0.9f,
            idsDeVista = listOf("url_bar")
        )
        assertTrue(EmbeddedBrowserDetector.isExempt(exemptMinutemaid))
        assertEquals(EmbeddedBrowserDetector.Veredicto.NO_ES_NAVEGADOR, EmbeddedBrowserDetector.evaluar(exemptMinutemaid))

        val exemptPortal = EmbeddedBrowserDetector.Retrato(
            packageName = "com.google.android.networkstack",
            activityClass = "com.android.captiveportallogin.CaptivePortalLoginActivity",
            esPortalCautivo = true,
            tieneWebView = true,
            fraccionWebView = 0.9f,
            idsDeVista = listOf("url_bar")
        )
        assertTrue(EmbeddedBrowserDetector.isExempt(exemptPortal))
        assertEquals(EmbeddedBrowserDetector.Veredicto.NO_ES_NAVEGADOR, EmbeddedBrowserDetector.evaluar(exemptPortal))

        val embeddedBrowser = EmbeddedBrowserDetector.Retrato(
            packageName = "com.example.reader",
            activityClass = "com.example.reader.InAppBrowserActivity",
            tieneWebView = true,
            fraccionWebView = 0.85f,
            idsDeVista = listOf("com.example.reader:id/url_bar")
        )
        assertFalse(EmbeddedBrowserDetector.isExempt(embeddedBrowser))
        assertEquals(EmbeddedBrowserDetector.Veredicto.NAVEGADOR_EMBEBIDO, EmbeddedBrowserDetector.evaluar(embeddedBrowser))
    }

    // ── CaptivePortalPolicy variantes NetworkStack / OEM ─────────────────────

    @Test
    fun `CaptivePortalPolicy reconoce networkstack y variantes OEM`() {
        assertTrue(
            CaptivePortalPolicy.isCaptivePortalWindow(
                "com.google.android.networkstack",
                "com.android.captiveportallogin.CaptivePortalLoginActivity"
            )
        )
        assertTrue(CaptivePortalPolicy.isCaptivePortalWindow("com.google.android.captiveportallogin", null))
        assertTrue(CaptivePortalPolicy.isCaptivePortalWindow("com.android.captiveportallogin", null))
        assertFalse(CaptivePortalPolicy.isCaptivePortalWindow("com.android.chrome", null))
    }
}
