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
 * 1. NUNCA se lee texto libre de mensajes ni lo que escribe el usuario:
 *    - Los globos de mensajes (`message_text` / `message_bubble`) se excluyen.
 *    - El hint del compositor solo se lee de `hintText` / `isShowingHintText == true`,
 *      jamás del texto tipeado por el usuario en un chat real.
 *
 * 2. Bandeja principal (página inicial con logo/botón de Gemini):
 *    - La página principal de Mensajes muestra el logo/FAB de Gemini (`penpal_fab`)
 *      y `action_bar_root`. NUNCA se marca como conversación ni se rebota por
 *      tener `action_bar` o vistas `penpal`, ni por mostrar una fila pasada de Gemini.
 *    - Solo se actúa en bandeja si aparece superpuesto el diálogo modal explícito
 *      de consentimiento con el descargo legal ("no están encriptados...").
 *
 * 3. Contactos humanos llamados "Gemini" / "Géminis":
 *    - Un contacto real SIEMPRE tiene botones de llamada telefónica o videollamada
 *      en la barra superior (`hasCallButtons = true`). El bot de Gemini NO tiene llamadas.
 *
 * 4. Chats grupales / SMS de bancos o empresas (sin llamada, con Magic Compose):
 *    - Google usa el nombre clave interno `penpal` también para Magic Compose dentro
 *      de chats normales. Por eso un ID `penpal` aislado SIN título/hint/disclaimer
 *      de Gemini NUNCA bloquea una conversación.
 *
 * 5. Ajustes (incluso "Ajustes → Gemini en Mensajes"):
 *    - Las pantallas de preferencias con interruptores (`Switch`) no son chats y
 *      no se bloquean.
 */
object GeminiMessagesPolicy {

    const val PKG_MESSAGING = "com.google.android.apps.messaging"

    enum class Veredicto {
        GEMINI,
        NORMAL
    }

    data class Retrato(
        val packageName: String,
        val isInboxScreen: Boolean = false,
        val isSettingsScreen: Boolean = false,
        val isConversationScreen: Boolean = false,
        val conversationTitle: String? = null,
        val composerHint: String? = null,
        val hasCallButtons: Boolean = false,
        val hasWelcomeTitle: Boolean = false,
        val hasPrivacyDisclaimer: Boolean = false,
        val hasConsentButton: Boolean = false,
        val hasPenpalViewId: Boolean = false
    )

    fun evaluar(retrato: Retrato): Veredicto {
        if (retrato.packageName != PKG_MESSAGING) return Veredicto.NORMAL

        // Ajustes de Google Mensajes (ej. para apagar el botón de Gemini) nunca se bloquean.
        if (retrato.isSettingsScreen && !retrato.isConversationScreen) {
            return Veredicto.NORMAL
        }

        // Página principal (bandeja de chats, incluso mostrando el logo/FAB de Gemini o
        // un hilo previo llamado Gemini): solo rebotar si hay un modal/sheet de consentimiento
        // superpuesto con el descargo legal de Gemini.
        if (retrato.isInboxScreen && !retrato.isConversationScreen) {
            val isModalOnboardingOverInbox = retrato.hasPrivacyDisclaimer &&
                (retrato.hasWelcomeTitle || retrato.hasConsentButton) &&
                !retrato.hasCallButtons
            return if (isModalOnboardingOverInbox) Veredicto.GEMINI else Veredicto.NORMAL
        }

        // Pantalla dedicada fuera de bandeja (bienvenida / consentimiento de Gemini, búsqueda, etc.)
        if (!retrato.isConversationScreen) {
            val isDedicatedOnboarding = (retrato.hasWelcomeTitle || retrato.hasPrivacyDisclaimer) &&
                !retrato.hasCallButtons
            return if (isDedicatedOnboarding) Veredicto.GEMINI else Veredicto.NORMAL
        }

        // ── A partir de acá, estamos dentro de una conversación abierta ──

        // 1. Compositor con placeholder explícito de Gemini ("Preguntar a Gemini", "Ask Gemini", etc.)
        if (esHintGemini(retrato.composerHint)) {
            return Veredicto.GEMINI
        }

        // 2. Barra superior con título "Gemini" / "Géminis" sin botones de llamada de persona real
        if (esTituloGemini(retrato.conversationTitle)) {
            return if (!retrato.hasCallButtons) Veredicto.GEMINI else Veredicto.NORMAL
        }

        // 3. Conversación vacía/activa de Gemini mostrando bienvenida o descargo de IA
        //    sin botones de llamada y sin título de otra persona/grupo.
        if (!retrato.hasCallButtons &&
            (retrato.hasWelcomeTitle || retrato.hasPrivacyDisclaimer) &&
            (retrato.conversationTitle.isNullOrBlank() || esTituloGemini(retrato.conversationTitle))
        ) {
            return Veredicto.GEMINI
        }

        return Veredicto.NORMAL
    }

    fun motivo(retrato: Retrato): String {
        return when {
            retrato.hasWelcomeTitle -> "pantalla de bienvenida de Gemini en Mensajes"
            esHintGemini(retrato.composerHint) ->
                "campo de redacción de Gemini (hint: ${retrato.composerHint})"
            esTituloGemini(retrato.conversationTitle) ->
                "conversación con el bot de Gemini (sin botones de llamada)"
            retrato.hasPrivacyDisclaimer ->
                "aviso de chat con Gemini en Mensajes"
            retrato.hasPenpalViewId ->
                "componente interno de Gemini/Penpal en pantalla de chat"
            else -> "Gemini en Google Mensajes detectado"
        }
    }

    fun esTituloGemini(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase() ?: return false
        return t == "gemini" || t == "géminis" || t == "geminis" || t == "ג'מיני" || t == "ג׳מיני"
    }

    fun esHintGemini(raw: String?): Boolean {
        val h = raw?.lowercase() ?: return false
        return h.contains("gemini") || h.contains("géminis") || h.contains("ג'מיני") || h.contains("ג׳מיני")
    }

    fun esFraseComposerGemini(raw: String?): Boolean {
        val lower = raw?.lowercase() ?: return false
        return lower.contains("preguntar a gemini") ||
            lower.contains("pregunta a gemini") ||
            lower.contains("mensaje a gemini") ||
            lower.contains("escribe a gemini") ||
            lower.contains("escríbele a gemini") ||
            lower.contains("escribele a gemini") ||
            lower.contains("envía un mensaje a gemini") ||
            lower.contains("chatea con gemini") ||
            lower.contains("ask gemini") ||
            lower.contains("message gemini") ||
            lower.contains("שאל את gemini") ||
            lower.contains("אפשר לשאול את gemini") ||
            lower.contains("הודעה אל gemini") ||
            lower.contains("כתיבת הודעה אל gemini") ||
            lower.contains("שליחת הודעה אל gemini")
    }

    fun esBienvenidaGemini(raw: String?): Boolean {
        val lower = raw?.lowercase() ?: return false
        return lower.contains("chatear con gemini") ||
            lower.contains("chatea con gemini") ||
            lower.contains("chat with gemini") ||
            lower.contains("meet gemini") ||
            lower.contains("conoce a gemini") ||
            lower.contains("conocé a gemini") ||
            lower.contains("te damos la bienvenida a gemini") ||
            lower.contains("welcome to gemini") ||
            lower.contains("צ'אט עם gemini") ||
            lower.contains("שיחה עם gemini") ||
            lower.contains("לשוחח עם gemini") ||
            lower.contains("שוחח עם gemini") ||
            lower.contains("הכירו את gemini")
    }

    fun esDisclaimerGemini(raw: String?): Boolean {
        val lower = raw?.lowercase() ?: return false
        return lower.contains("no están encriptados de extremo a extremo") ||
            lower.contains("no está encriptado de extremo a extremo") ||
            lower.contains("aren't end-to-end encrypted") ||
            lower.contains("not end-to-end encrypted") ||
            lower.contains("אינם מוצפנים מקצה לקצה") ||
            lower.contains("אינן מוצפנות מקצה לקצה") ||
            lower.contains("gemini puede cometer errores") ||
            lower.contains("gemini can make mistakes") ||
            lower.contains("gemini עלול לטעות") ||
            lower.contains("actividad en las aplicaciones de gemini") ||
            lower.contains("gemini apps activity")
    }

    fun esBotonConsentimientoGemini(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase() ?: return false
        return t == "usar gemini" ||
            t == "use gemini" ||
            t == "chatear con gemini" ||
            t == "chatea con gemini" ||
            t == "chat with gemini" ||
            t == "aceptar" ||
            t == "acepto" ||
            t == "agree" ||
            t == "i agree" ||
            t == "continuar" ||
            t == "continue" ||
            t == "המשך" ||
            t == "אני מסכים" ||
            t == "שימוש ב-gemini" ||
            t == "שימוש בג'מיני"
    }

    fun esMarcadorAjustes(raw: String?): Boolean {
        val lower = raw?.lowercase() ?: return false
        return lower.contains("mostrar el botón de gemini") ||
            lower.contains("show gemini button") ||
            lower.contains("configuración de mensajes") ||
            lower.contains("ajustes de mensajes") ||
            lower.contains("messages settings") ||
            lower == "gemini en mensajes" ||
            lower == "gemini in messages" ||
            lower.contains("הצגת לחצן gemini") ||
            lower.contains("הגדרות הודעות") ||
            lower.contains("הגדרות gemini")
    }

    fun esBotonLlamadaToolbar(viewIdLower: String, descLower: String): Boolean {
        if (viewIdLower.contains("action_call") ||
            viewIdLower.contains("action_video_call") ||
            viewIdLower.contains("voice_call") ||
            viewIdLower.contains("video_call") ||
            viewIdLower.contains("call_button")
        ) {
            return true
        }
        if (descLower.isEmpty()) return false
        return descLower.contains("llamar") ||
            descLower.contains("llamada") ||
            descLower.contains("videollamada") ||
            descLower.contains("voice call") ||
            descLower.contains("video call") ||
            descLower.contains("audio call") ||
            descLower.contains("phone call") ||
            descLower.contains("google meet") ||
            descLower == "meet" ||
            descLower == "call" ||
            descLower.contains("שיחה קולית") ||
            descLower.contains("שיחת שמע") ||
            descLower.contains("שיחת וידאו") ||
            descLower.contains("חיוג") ||
            descLower.contains("התקשר")
    }

    fun esMarcadorBandejaPorId(viewIdLower: String): Boolean {
        return viewIdLower.contains("start_chat_fab") ||
            viewIdLower.contains("start_new_conversation") ||
            viewIdLower.contains("conversation_list") ||
            viewIdLower.contains("swipeable_container") ||
            viewIdLower.contains("open_search_bar")
    }

    fun esFabIniciarChat(textOrDescLower: String): Boolean {
        val t = textOrDescLower.trim()
        return t == "iniciar chat" ||
            t == "start chat" ||
            t == "nuevo chat" ||
            t == "new conversation" ||
            t == "iniciar conversación" ||
            t == "התחלת צ'אט" ||
            t == "התחל צ'אט" ||
            t == "צ'אט חדש"
    }

    fun esBarraBusquedaBandeja(textOrHintLower: String): Boolean {
        val t = textOrHintLower.trim()
        return t == "mensajes" ||
            t == "messages" ||
            t == "הודעות" ||
            t.startsWith("buscar imágenes y vídeos") ||
            t.startsWith("buscar mensajes") ||
            t.startsWith("search images & videos") ||
            t.startsWith("search messages") ||
            t.startsWith("search conversations")
    }
}
