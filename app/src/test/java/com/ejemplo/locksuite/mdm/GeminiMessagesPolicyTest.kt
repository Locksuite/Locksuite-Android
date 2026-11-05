package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pruebas unitarias de [GeminiMessagesPolicy].
 * Valida que se detecte e identifique únicamente la interacción con el bot de Gemini
 * en Google Mensajes, y NUNCA se produzcan falsos positivos con contactos humanos,
 * mensajes de texto ordinarios o la bandeja de entrada de la aplicación.
 */
class GeminiMessagesPolicyTest {

    private val PKG = GeminiMessagesPolicy.PKG_MESSAGING

    @Test
    fun `detecta pantalla de bienvenida de Gemini con disclaimer legal`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            hasWelcomeTitle = true,
            hasPrivacyDisclaimer = true,
            hasCallButtons = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.GEMINI, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `detecta pantalla de bienvenida de Gemini sin botones de llamada`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            hasWelcomeTitle = true,
            hasPrivacyDisclaimer = false,
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
    fun `detecta vista con identificador penpal dentro de conversacion`() {
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
    fun `NO bloquea contacto humano llamado Gemini si tiene botones de llamada y hint normal`() {
        // Una persona real con ese nombre o apodo tiene botón de llamada / videollamada
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Gemini",
            composerHint = "Mensaje de RCS",
            hasCallButtons = true,
            hasWelcomeTitle = false,
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = false
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
            hasCallButtons = true,
            hasWelcomeTitle = false,
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea conversacion ordinaria donde se habla de Gemini`() {
        // En una conversación con "Mama" o "Juan", el título no es Gemini y tiene botones de llamada
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
            conversationTitle = "Juan Perez",
            composerHint = "Mensaje de RCS",
            hasCallButtons = true,
            hasWelcomeTitle = false,
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = false
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea la bandeja de entrada o lista principal de chats`() {
        // La pantalla principal no es conversación (isConversationScreen = false)
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = false,
            conversationTitle = null,
            composerHint = null,
            hasCallButtons = false,
            hasWelcomeTitle = false,
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = true // puede tener el FAB con id penpal_fab
        )
        assertEquals(GeminiMessagesPolicy.Veredicto.NORMAL, GeminiMessagesPolicy.evaluar(retrato))
    }

    @Test
    fun `NO bloquea pantalla de Ajustes de la aplicacion`() {
        val retrato = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = false,
            conversationTitle = "Configuración",
            composerHint = null,
            hasCallButtons = false,
            hasWelcomeTitle = false,
            hasPrivacyDisclaimer = false,
            hasPenpalViewId = false
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
    fun `motivo genera explicacion clara en castellano`() {
        val r1 = GeminiMessagesPolicy.Retrato(
            packageName = PKG,
            isConversationScreen = true,
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
