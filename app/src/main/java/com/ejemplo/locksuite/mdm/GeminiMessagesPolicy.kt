package com.ejemplo.locksuite.mdm

/**
 * GeminiMessagesPolicy — Decidir si la pantalla de Google Mensajes es REALMENTE Gemini (Capa 3).
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * QUÉ CIERRA
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * Google Mensajes (com.google.android.apps.messaging) integra a Gemini como bot
 * conversacional vía RCS / RBM (Rich Communication Services).
 *
 * Como viaja sobre la misma conexión cifrada y los mismos endpoints de Jibe
 * (*.jibe.google.com) y Google APIs (*.googleapis.com) que el RCS legítimo entre
 * personas, es TÉCNICAMENTE IMPOSIBLE filtrarlo por DNS (Capa 2) sin romper los
 * chats RCS con personas reales (fotos en alta definición, confirmaciones de lectura,
 * grupos RCS) o las notificaciones y servicios de Google.
 *
 * La Capa 3 (Accesibilidad) es la única capa capaz de ver si el usuario está
 * interactuando con Gemini sin interferir en los chats reales.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * CÓMO SE EVITAN LOS FALSOS POSITIVOS (Garantía de no romper nada)
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * 1. NUNCA se lee texto libre de mensajes: Si un amigo o familiar manda un mensaje
 *    diciendo "¿viste lo de Gemini?" o "mi signo es géminis", el escáner NO lee
 *    los globos de mensajes (message_text). Solo lee elementos estructurales:
 *    título del encabezado (toolbar), hint del campo de redacción (composer)
 *    y presencia de botones de llamada.
 *
 * 2. Contactos humanos llamados "Gemini" / "Géminis": Un contacto real SIEMPRE
 *    tiene botones de llamada telefónica (action_call / "Llamar") o videollamada
 *    (action_video_call / "Videollamada" / Meet). El bot de Gemini NO tiene llamadas.
 *    Además, el campo de texto de un contacto dice "Mensaje de RCS" o "Mensaje de texto",
 *    NUNCA "Preguntar a Gemini" ni "Mensaje a Gemini".
 *
 * 3. Lista principal de mensajes (inbox): No se bloquea por ver el botón flotante
 *    (FAB) de Gemini en la lista de chats. Solo se actúa cuando la conversación
 *    está abierta (isConversationScreen = true).
 *
 * 4. Ajustes y búsqueda: No son pantallas de conversación, no se bloquean.
 */
object GeminiMessagesPolicy {

    const val PKG_MESSAGING = "com.google.android.apps.messaging"

    enum class Veredicto {
        GEMINI,
        NORMAL
    }

    data class Retrato(
        val packageName: String,
        val isConversationScreen: Boolean,
        val conversationTitle: String? = null,
        val composerHint: String? = null,
        val hasCallButtons: Boolean = false,
        val hasWelcomeTitle: Boolean = false,
        val hasPrivacyDisclaimer: Boolean = false,
        val hasPenpalViewId: Boolean = false
    )

    fun evaluar(retrato: Retrato): Veredicto {
        if (retrato.packageName != PKG_MESSAGING) return Veredicto.NORMAL
        if (!retrato.isConversationScreen) return Veredicto.NORMAL

        // 1. Pantalla de bienvenida / onboarding de Gemini
        // "Chatear con Gemini" con disclaimer de privacidad o sin botones de llamada
        if (retrato.hasWelcomeTitle && (retrato.hasPrivacyDisclaimer || !retrato.hasCallButtons)) {
            return Veredicto.GEMINI
        }

        // 2. Campo de texto con sugerencia explícita de Gemini ("Preguntar a Gemini", "Mensaje a Gemini", etc.)
        val hint = retrato.composerHint?.lowercase() ?: ""
        if (hint.contains("gemini") || hint.contains("penpal")) {
            return Veredicto.GEMINI
        }

        // 3. Título de conversación "Gemini" / "Géminis"
        val title = retrato.conversationTitle?.trim()?.lowercase() ?: ""
        if (title == "gemini" || title == "géminis") {
            // Un contacto humano tendría botones de llamada (action_call / action_video_call)
            // y su hint sería "Mensaje de RCS" o "Mensaje de texto".
            // Si NO tiene botones de llamada o tiene IDs de penpal, es indiscutiblemente el bot de Gemini.
            if (!retrato.hasCallButtons || retrato.hasPenpalViewId) {
                return Veredicto.GEMINI
            }
        }

        // 4. Presencia de vistas penpal/gemini dentro de la conversación sin botones de llamada
        if (retrato.hasPenpalViewId && !retrato.hasCallButtons) {
            return Veredicto.GEMINI
        }

        return Veredicto.NORMAL
    }

    fun motivo(retrato: Retrato): String {
        return when {
            retrato.hasWelcomeTitle -> "pantalla de bienvenida de Gemini en Mensajes"
            retrato.composerHint?.lowercase()?.contains("gemini") == true ->
                "campo de redacción de Gemini (hint: ${retrato.composerHint})"
            retrato.conversationTitle?.trim()?.equals("gemini", ignoreCase = true) == true ->
                "conversación con el bot de Gemini (sin botones de llamada)"
            retrato.hasPenpalViewId -> "componente interno de Gemini/Penpal en pantalla de chat"
            else -> "Gemini en Google Mensajes detectado"
        }
    }
}
