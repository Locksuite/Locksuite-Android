package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de [GeminiMessagesPolicy].
 * Valida que se detecte e identifique únicamente la interacción con el bot de Gemini
 * en Google Mensajes (onboarding o chat activo), y NUNCA se produzcan falsos positivos
 * en la página principal con el logo de Gemini, contactos humanos, chats grupales/bancos
 * con Magic Compose, mensajes que mencionen a Gemini o la pantalla de Ajustes.
 */
class GeminiMessagesPolicyTest {

    private val PKG = GeminiMessagesPolicy.PKG_MESSAGING

    @Test
    fun `detecta pantalla de bienvenida de Gemini con disclaimer legal`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = false,
            hasWelcomeTitle = true,
            hasPrivacyDisclaimer = true,
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta pantalla de bienvenida dedicada de Gemini sin botones de llamada`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isInboxScreen = false,
            isConversationScreen = false,
            hasWelcomeTitle = true,
            hasPrivacyDisclaimer = false,
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta modal de consentimiento de Gemini superpuesto sobre bandeja`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isInboxScreen = true,
            isConversationScreen = false,
            hasWelcomeTitle = true,
            hasPrivacyDisclaimer = true,
            hasConsentButton = true,
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta conversacion activa con Gemini por titulo y ausencia de botones de llamada`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Gemini",
            hasCallButtons = false,
            composerHint = "Mensaje"
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta conversacion con Gemini por hint en espanol Preguntar a Gemini`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Gemini",
            composerHint = "Preguntar a Gemini...",
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta conversacion con Gemini por hint en ingles Ask Gemini`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Gemini",
            composerHint = "Ask Gemini",
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta conversacion con Gemini en hebreo sin botones de llamada`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "ג'מיני",
            composerHint = "שאל את Gemini",
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta chat vacio de Gemini por aviso legal aunque el titulo no se haya leido`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = null,
            hasPrivacyDisclaimer = true,
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta vista con identificador penpal dentro de conversacion de Gemini`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Gemini",
            hasPenpalViewId = true,
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    // ── PRUEBAS DE NO-REGRESIÓN (FALSOS POSITIVOS) ───────────────────────────

    @Test
    fun `NO bloquea pagina principal de Mensajes que tiene el logo FAB de Gemini (B114)`() {
        // Bug B.114 reportado en 3 celulares reales: la bandeja principal tiene el botón/logo de
        // Gemini (id penpal_fab / texto o tooltip "Chatear con Gemini") y no tiene botón de llamada.
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isInboxScreen = true,
            isConversationScreen = false,
            conversationTitle = null,
            composerHint = null,
            hasCallButtons = false,
            hasWelcomeTitle = true, // tooltip o etiqueta del FAB: "Chatear con Gemini"
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = true // id penpal_fab en la pantalla principal
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea bandeja principal durante carga parcial antes de encontrar start_chat_fab`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isInboxScreen = false,
            isConversationScreen = false,
            hasCallButtons = false,
            hasPenpalViewId = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea grupo familiar ni SMS bancario sin llamadas aunque tenga Magic Compose penpal`() {
        val grupo = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Familia",
            composerHint = "Mensaje de RCS",
            hasCallButtons = false,
            hasPenpalViewId = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(grupo))

        val grupoSinTitulo = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = null,
            composerHint = "Mensaje de texto",
            hasCallButtons = false,
            hasPenpalViewId = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(grupoSinTitulo))
    }

    @Test
    fun `NO bloquea contacto humano llamado Gemini si tiene botones de llamada incluso con Magic Compose`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Gemini",
            composerHint = "Mensaje de RCS",
            hasCallButtons = true,
            hasWelcomeTitle = false,
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea contacto humano llamado Geminis con llamada telefonica`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Géminis",
            composerHint = "Mensaje de texto",
            hasCallButtons = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea conversacion ordinaria aunque el texto mencionado incluya frases de Gemini`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Juan Perez",
            composerHint = "Mensaje de RCS",
            hasCallButtons = true,
            hasWelcomeTitle = true,
            hasPrivacyDisclaimer = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea pantalla de Ajustes de Mensajes incluso en subtitulo Gemini en Mensajes`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isSettingsScreen = true,
            isConversationScreen = false,
            conversationTitle = "Gemini en Mensajes",
            hasWelcomeTitle = true
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea otras aplicaciones que no sean Google Mensajes`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = "com.whatsapp",
            isConversationScreen = true,
            conversationTitle = "Gemini",
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `predicados de clasificacion reconocen ES EN HE y excluyen falsos positivos`() {
        assertTrue(GeminiMessagesPolicy.esTituloGemini("Gemini"))
        assertTrue(GeminiMessagesPolicy.esTituloGemini("Géminis"))
        assertTrue(GeminiMessagesPolicy.esTituloGemini("ג'מיני"))
        assertFalse(GeminiMessagesPolicy.esTituloGemini("Gemini en Mensajes"))
        assertFalse(GeminiMessagesPolicy.esTituloGemini("Mamá"))

        assertTrue(GeminiMessagesPolicy.esFraseComposerGemini("Preguntar a Gemini..."))
        assertTrue(GeminiMessagesPolicy.esFraseComposerGemini("Ask Gemini"))
        assertTrue(GeminiMessagesPolicy.esFraseComposerGemini("שאל את Gemini"))
        assertFalse(GeminiMessagesPolicy.esFraseComposerGemini("Mensaje de RCS"))

        assertTrue(GeminiMessagesPolicy.esBotonLlamadaToolbar("com.google.android.apps.messaging:id/action_call", ""))
        assertTrue(GeminiMessagesPolicy.esBotonLlamadaToolbar("", "videollamada"))
        assertTrue(GeminiMessagesPolicy.esBotonLlamadaToolbar("", "voice call"))
        assertFalse(GeminiMessagesPolicy.esBotonLlamadaToolbar("", "mensaje de voz"))

        assertTrue(GeminiMessagesPolicy.esMarcadorBandejaPorId("com.google.android.apps.messaging:id/start_chat_fab"))
        assertTrue(GeminiMessagesPolicy.esMarcadorBandejaPorId("com.google.android.apps.messaging:id/conversation_list"))
        assertFalse(GeminiMessagesPolicy.esMarcadorBandejaPorId("com.google.android.apps.messaging:id/action_bar_root"))
    }

    @Test
    fun `motivo genera explicacion clara en castellano`() {
        val r1 = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = false,
            hasWelcomeTitle = true
        )
        assertNotEquals("", GeminiMessagesPolicy.motivo(r1))

        val r2 = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            composerHint = "Preguntar a Gemini"
        )
        assertNotEquals("", GeminiMessagesPolicy.motivo(r2))
    }
}
