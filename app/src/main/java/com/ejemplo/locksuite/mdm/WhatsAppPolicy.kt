package com.ejemplo.locksuite.mdm

/**
 * Políticas puras (sin dependencias de Android) para bloquear Estados y Canales de WhatsApp
 * (`com.whatsapp` y `com.whatsapp.w4b`) con precisión, evitando tanto bloqueos de más
 * (falsos positivos) como bloqueos de menos (evasiones).
 *
 * Reglas explícitas de diseño:
 * 1. **Clasificación de Activity sin substrings crudos:** nunca se usa `contains("status")`
 *    ni `contains("channel")`. Pantallas legítimas como "Info" del perfil
 *    (`AboutStatusPrivacyActivity`, `AddTextStatusActivity`), estado de pedidos Business
 *    (`orderstatus`), estado del sistema (`systemstatus`), pagos (`payments.*`), Ajustes
 *    (`ChannelsPrivacySettingsActivity`), Llamadas (`calling.*`), Comunidades (`community.*`)
 *    y Visor de multimedia de chats (`mediaview.*`) se marcan `SAFE` y NUNCA se bloquean.
 * 2. **Pestaña Novedades / Estados:** solo se detecta en `HomeActivity` (o cuando hay barra de
 *    navegación), priorizando IDs estructurales (`TabBarButton_Status`, `updates_list`) sobre
 *    etiquetas de texto — y las etiquetas de texto solo se comparan dentro del chrome de
 *    navegación (nunca sobre mensajes seleccionados ni búsquedas). Si ambos interruptores
 *    (`blockStatus` y `blockChannels`) están encendidos, se redirige a Chats. Si solo UNO
 *    está encendido, el usuario puede permanecer en Novedades y se bloquea recién al intentar
 *    abrir lo restringido.
 * 3. **Canales dentro de `com.whatsapp.Conversation`:** en WhatsApp los Canales se abren en
 *    `com.whatsapp.Conversation` (no tienen una Activity de conversación propia). Cuando
 *    `blockChannels` está activo y NO estamos en `HomeActivity` ni en una pantalla `SAFE`, se
 *    identifica un Canal por sus IDs estructurales propios (excluyendo burbujas reenviadas
 *    dentro de chats normales) o por el subtítulo del encabezado (`conversation_contact_status`)
 *    cuando hay ausencia de botones de llamada y de compositor de chat normal.
 */
object WhatsAppPolicy {

    enum class ActivityCategory {
        SAFE,
        HOME,
        CONVERSATION,
        STATUS,
        CHANNEL,
        CHANNEL_STATUS,
        OTHER
    }

    enum class Action {
        ALLOW,
        REDIRECT_TO_CHATS,
        BLOCK_STATUS,
        BLOCK_CHANNEL
    }

    data class TabCandidate(
        val viewId: String,
        val label: String,
        val isSelected: Boolean,
        val inNavChrome: Boolean
    )

    data class ScreenSnapshot(
        val activityClass: String?,
        val viewIds: Set<String> = emptySet(),
        val tabs: List<TabCandidate> = emptyList(),
        val conversationSubtitle: String? = null
    )

    data class Decision(
        val action: Action,
        val reason: String
    )

    private val SAFE_PACKAGE_PREFIXES = listOf(
        "settings.",
        "profile.",
        "evolvedabout.",
        "orderstatus.",
        "systemstatus.",
        "payments.",
        "calling.",
        "community.",
        "mediaview.",
        "chatinfo.group."
    )

    private val SAFE_EXACT_RELATIVE = setOf(
        "chatinfo.contactinfoactivity",
        "mediaview",
        "contactinfo",
        "groupchatinfo"
    )

    private val SAFE_SIMPLE_NAMES = setOf(
        "aboutstatusprivacyactivity",
        "aboutstatusblocklistpickeractivity",
        "addtextstatusactivity",
        "richorderdetailactivity",
        "systemstatusactivity",
        "channelsprivacysettingsactivity",
        "memberupdatesactivity",
        "mediaviewactivity",
        "singlemediaviewactivity",
        "contactinfoactivity",
        "groupchatinfoactivity"
    )

    private val CHANNEL_PACKAGE_PREFIXES = listOf(
        "newsletter.",
        "newsletterenforcements.",
        "chatinfo.newsletter.",
        "conversation.newsletterscheduledupdates.",
        "followerinvite."
    )

    private val STATUS_LEGACY_PREFIXES = listOf(
        "statusplayback",
        "statusreply",
        "textstatuscomposer",
        "consolidatedstatuscomposer",
        "mystatuses",
        "mystatusaudience",
        "archivedstatuses",
        "mutedstatuses",
        "statusprivacy",
        "statusrecipients",
        "statusaudience",
        "statusinteractions",
        "statuscaption",
        "statusmentions",
        "statusquestion",
        "groupstatusrecipient",
        "setstatus",
        "wamostatus"
    )

    private val STATUS_VIEWER_IDS = setOf(
        "status_viewer_top_bar",
        "status_viewer_wds_top_bar",
        "status_playback_image",
        "status_playback_video",
        "status_playback_voice",
        "status_playback_gif",
        "status_composer_layout",
        "archived_statuses_grid"
    )

    private val NORMAL_CHAT_CALL_IDS = setOf(
        "action_call",
        "action_videocall",
        "action_unified_call"
    )

    private const val ID_NORMAL_COMPOSER = "entry"
    private const val ID_TAB_STATUS = "tabbarbutton_status"
    private const val ID_UPDATES_LIST = "updates_list"
    const val ID_TAB_CHATS_RAW = "TabBarButton_Chats"
    const val ID_CONVERSATION_SUBTITLE_RAW = "conversation_contact_status"

    /**
     * IDs exclusivos de administración de un Canal dentro de `Conversation` / hojas asociadas.
     * No aparecen nunca dentro de un mensaje reenviado en un chat común.
     */
    private val CHANNEL_ADMIN_IDS = setOf(
        "channel_admin_bottom_bar",
        "channel_admin_reshare_button",
        "owner_action_newsletter_main_view",
        "newsletter_admin_profile_floating_banner_content",
        "newsletter_invite_followers_floating_content",
        "newsletter_invite_footer_floating_content",
        "newsletter_admin_context_card_title",
        "newsletter_public_card_producer"
    )

    /**
     * IDs de la pantalla/visor/ficha propia de un Canal.
     * Excluye deliberadamente IDs de enlaces o mensajes reenviados dentro de un chat normal
     * (`newsletter_link_card`, `newsletter_name_forwarded_message`, `view_channel_button`, etc.).
     */
    private val CHANNEL_SCREEN_IDS = setOf(
        "follow_channel_button",
        "follow_button",
        "quick_follow_button",
        "quick_follow_button_container",
        "action_follow",
        "newsletter_follow_privacy_banner",
        "newsletter_privacy_banner",
        "newsletter_unmute_nudge_banner",
        "newsletter_context_card",
        "newsletter_details_card",
        "newsletter_follower_count",
        "newsletter_follow_count",
        "newsletter_message_view_count",
        "newsletterpinnedupdatebanner",
        "newsletter_pinning_layout",
        "suspended_newsletter_view_text",
        "channel_guidelines_footer_text",
        "channel_info_content",
        "channel_info_followers",
        "newsletter_info_layout",
        "newsletter_public_card_consumer",
        "unfollow_newsletter_btn",
        "newsletter_migration_channel_sheet_view",
        "newsletter_mv_create_channel_layout",
        "newsletter_guidelines_fragment",
        "newsletter_alerts"
    )

    val NAV_CONTAINER_IDS = setOf(
        "bottom_nav",
        "bottom_nav_container",
        "tabs",
        "tab_layout",
        "navigation_bar_item_icon_container",
        "navigation_bar_item_labels_group"
    )

    private val UPDATES_TAB_LABELS = setOf(
        "updates", "status",
        "novedades", "actualizaciones", "estados",
        "novidades", "atualizacoes",
        "actualites", "mises a jour",
        "aktuelles", "neuigkeiten",
        "aggiornamenti",
        "עדכונים", "סטטוס", "סטטוסים",
        "דערהייַנטיקונגען"
    )

    private val CHATS_TAB_LABELS = setOf(
        "chats", "chat",
        "conversaciones", "conversas",
        "unterhaltungen", "discussions",
        "צאטים", "צ'אטים", "צ׳אטים", "שיחות",
        "שמועסן"
    )

    private val CHANNEL_SUBTITLE_WORDS = setOf(
        "followers", "follower",
        "seguidores", "seguidor", "seguidoras",
        "abonnes", "abonne", "abonnenten",
        "iscritti",
        "עוקבים", "עוקב", "נאכפאלגער"
    )

    private val CHANNEL_SUBTITLE_EXACT = setOf(
        "channel", "canal", "ערוץ", "קאנאל",
        "following", "siguiendo", "seguindo", "a seguir",
        "עוקב", "במעקב", "מעקב"
    )

    private val CHANNEL_SUBTITLE_STATUS_WORDS = setOf(
        "following", "siguiendo", "seguindo", "a seguir",
        "במעקב", "עוקב"
    )

    private val CHANNEL_SUBTITLE_TYPE_WORDS = setOf(
        "channel", "canal", "ערוץ", "קאנאל"
    )

    fun normalizeViewId(rawId: String?): String {
        if (rawId.isNullOrBlank()) return ""
        val trimmed = rawId.trim()
        val afterId = trimmed.substringAfter(":id/", "")
        val base = if (afterId.isNotEmpty()) {
            afterId
        } else {
            trimmed.substringAfterLast('/')
        }
        return base.lowercase()
    }

    fun classifyActivity(className: String?): ActivityCategory {
        val raw = className?.trim()?.lowercase() ?: return ActivityCategory.OTHER
        if (raw.isEmpty() || raw.startsWith("android.") || raw.startsWith("androidx.")) {
            return ActivityCategory.OTHER
        }

        val rel = when {
            raw.startsWith("com.whatsapp.w4b.") -> raw.removePrefix("com.whatsapp.w4b.")
            raw.startsWith("com.whatsapp.") -> raw.removePrefix("com.whatsapp.")
            else -> raw
        }
        val simple = rel.substringAfterLast('.')

        // 1. Exclusiones explícitas primero: nunca bloquear pantallas legítimas que llevan "status"/"channel" en el nombre.
        if (SAFE_PACKAGE_PREFIXES.any { rel.startsWith(it) } ||
            rel in SAFE_EXACT_RELATIVE ||
            simple in SAFE_SIMPLE_NAMES
        ) {
            return ActivityCategory.SAFE
        }

        // 2. Pantallas principales / conversación.
        if (rel == "homeactivity" || rel == "home.ui.homeactivity" || rel == "main" || simple == "homeactivity") {
            return ActivityCategory.HOME
        }
        if (rel == "conversation" || simple == "conversation") {
            return ActivityCategory.CONVERSATION
        }

        // 3. Estado de un Canal (contiene ambos conceptos).
        if (rel.startsWith("status.playback.newsletterstatus.") || simple.contains("newsletterstatus")) {
            return ActivityCategory.CHANNEL_STATUS
        }

        // 4. Canales (Newsletters).
        if (CHANNEL_PACKAGE_PREFIXES.any { rel.startsWith(it) } ||
            simple.contains("newsletter") ||
            simple.startsWith("channel") ||
            simple.endsWith("channelactivity")
        ) {
            return ActivityCategory.CHANNEL
        }

        // 5. Estados (Stories).
        if (rel.startsWith("status.") ||
            rel == "contact.ui.picker.statusaudienceselectionactivity" ||
            rel.startsWith("pmta.sponsorcontrols.statusview") ||
            rel == "wamo.deeplink.wamostatusinhouseadacquisitionactivity" ||
            STATUS_LEGACY_PREFIXES.any { simple.startsWith(it) }
        ) {
            return ActivityCategory.STATUS
        }

        return ActivityCategory.OTHER
    }

    fun evaluate(
        snapshot: ScreenSnapshot,
        blockStatus: Boolean,
        blockChannels: Boolean
    ): Decision {
        if (!blockStatus && !blockChannels) {
            return Decision(Action.ALLOW, "whatsapp_policies_disabled")
        }

        val activityCat = classifyActivity(snapshot.activityClass)

        // 1. Por Activity conocida.
        when (activityCat) {
            ActivityCategory.SAFE -> return Decision(Action.ALLOW, "activity:safe")
            ActivityCategory.CHANNEL_STATUS -> {
                if (blockChannels) return Decision(Action.BLOCK_CHANNEL, "activity:channel_status")
                if (blockStatus) return Decision(Action.BLOCK_STATUS, "activity:channel_status")
                return Decision(Action.ALLOW, "activity:channel_status_allowed")
            }
            ActivityCategory.CHANNEL -> {
                if (blockChannels) return Decision(Action.BLOCK_CHANNEL, "activity:channel")
                return Decision(Action.ALLOW, "activity:channel_allowed")
            }
            ActivityCategory.STATUS -> {
                if (blockStatus) return Decision(Action.BLOCK_STATUS, "activity:status")
                return Decision(Action.ALLOW, "activity:status_allowed")
            }
            ActivityCategory.HOME, ActivityCategory.CONVERSATION, ActivityCategory.OTHER -> Unit
        }

        val ids = snapshot.viewIds.asSequence()
            .map { normalizeViewId(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        // 2. Visor o compositor de Estados detectado por IDs estructurales.
        if (blockStatus && ids.any { it in STATUS_VIEWER_IDS }) {
            return Decision(Action.BLOCK_STATUS, "view:status_viewer")
        }

        val hasNavChrome = snapshot.tabs.any { it.inNavChrome } ||
            ids.any { it.startsWith("tabbarbutton_") || it in NAV_CONTAINER_IDS || it == ID_UPDATES_LIST }

        // 3. Pestaña Novedades / Estados: solo se redirige si AMBOS están bloqueados y estamos en Home (o pantalla con barra nav).
        if (activityCat == ActivityCategory.HOME || (activityCat == ActivityCategory.OTHER && hasNavChrome)) {
            if (isUpdatesTabActive(snapshot.tabs, ids, activityCat)) {
                return if (blockStatus && blockChannels) {
                    Decision(Action.REDIRECT_TO_CHATS, "tab:updates_both_blocked")
                } else {
                    Decision(Action.ALLOW, "tab:updates_partial_allow")
                }
            }
            // En HomeActivity no disparamos BLOCK_CHANNEL por tarjetas de sugerencias en Novedades.
            if (activityCat == ActivityCategory.HOME) {
                return Decision(Action.ALLOW, "home:not_updates_tab")
            }
        }

        // 4. Canal abierto dentro de `Conversation` (o sheet de Canal).
        if (blockChannels && activityCat != ActivityCategory.HOME) {
            val hasNormalCallButtons = ids.any { it in NORMAL_CHAT_CALL_IDS }
            val hasComposer = ID_NORMAL_COMPOSER in ids

            if (!hasNormalCallButtons && ids.any { it in CHANNEL_ADMIN_IDS }) {
                return Decision(Action.BLOCK_CHANNEL, "conv:channel_admin_id")
            }
            if (!hasNormalCallButtons && !hasComposer && ids.any { it in CHANNEL_SCREEN_IDS }) {
                return Decision(Action.BLOCK_CHANNEL, "conv:channel_screen_id")
            }
            if (activityCat == ActivityCategory.CONVERSATION &&
                !hasNormalCallButtons &&
                !hasComposer &&
                isChannelHeaderSubtitle(snapshot.conversationSubtitle)
            ) {
                return Decision(Action.BLOCK_CHANNEL, "conv:channel_subtitle")
            }
        }

        return Decision(Action.ALLOW, "default:allow")
    }

    internal fun isUpdatesTabActive(
        tabs: List<TabCandidate>,
        normalizedIds: Set<String>,
        activityCat: ActivityCategory
    ): Boolean {
        val navTabs = tabs.filter { it.inNavChrome }
        // Si está presente la barra moderna TabBarButton_*, su estado isSelected es autoritativo
        // (evita falsos positivos cuando ViewPager mantiene updates_list montada en background).
        val modernTabBar = navTabs.filter { normalizeViewId(it.viewId).startsWith("tabbarbutton_") }
        if (modernTabBar.isNotEmpty()) {
            return modernTabBar.any { normalizeViewId(it.viewId) == ID_TAB_STATUS && it.isSelected }
        }

        if (ID_UPDATES_LIST in normalizedIds && activityCat != ActivityCategory.CONVERSATION) {
            return true
        }

        // Respaldo para versiones sin TabBarButton_Status: solo mira tabs seleccionados dentro del chrome de navegación.
        return navTabs.any { tab ->
            tab.isSelected && isUpdatesTabLabel(tab.label)
        }
    }

    fun isUpdatesTabLabel(rawLabel: String?): Boolean {
        val norm = normalizeTabLabel(rawLabel) ?: return false
        return norm in UPDATES_TAB_LABELS ||
            UPDATES_TAB_LABELS.any { MercadoPagoOffersPolicy.contienePalabraCompleta(norm, it) }
    }

    fun isChatsTabLabel(rawLabel: String?): Boolean {
        val norm = normalizeTabLabel(rawLabel) ?: return false
        return norm in CHATS_TAB_LABELS ||
            CHATS_TAB_LABELS.any { MercadoPagoOffersPolicy.contienePalabraCompleta(norm, it) }
    }

    internal fun isChannelHeaderSubtitle(subtitle: String?): Boolean {
        if (subtitle.isNullOrBlank()) return false
        val folded = MercadoPagoOffersPolicy.plegarAcentos(subtitle).trim()
        if (folded.isEmpty() || folded.length > 64) return false
        if (folded in CHANNEL_SUBTITLE_EXACT) return true

        val hasDigit = folded.any { it.isDigit() }
        if (hasDigit && CHANNEL_SUBTITLE_WORDS.any {
                MercadoPagoOffersPolicy.contienePalabraCompleta(folded, it)
            }
        ) {
            return true
        }

        // En canales ya seguidos o en idiomas ES/EN/HE/PT el subtítulo puede no incluir dígitos
        // (p. ej. "Siguiendo", "Following", "Seguindo", "Canal público", "Public channel",
        // "ערוץ ציבורי", "עוקב", "במעקב", "Canal · Siguiendo"). Evitamos listas de miembros
        // separadas por múltiples comas ("Juan, María, Tú").
        val looksLikeMemberList = folded.count { it == ',' } >= 2
        if (looksLikeMemberList) return false

        if (CHANNEL_SUBTITLE_STATUS_WORDS.any {
                MercadoPagoOffersPolicy.contienePalabraCompleta(folded, it)
            }
        ) {
            return true
        }
        if (CHANNEL_SUBTITLE_TYPE_WORDS.any {
                MercadoPagoOffersPolicy.contienePalabraCompleta(folded, it)
            }
        ) {
            return true
        }
        // Conteo verbal sin dígitos (p. ej. "עוקב אחד", "un seguidor", "one follower")
        return CHANNEL_SUBTITLE_WORDS.any {
            MercadoPagoOffersPolicy.contienePalabraCompleta(folded, it)
        }
    }

    private fun normalizeTabLabel(rawLabel: String?): String? {
        if (rawLabel.isNullOrBlank()) return null
        val folded = MercadoPagoOffersPolicy.plegarAcentos(rawLabel)
        // Quitar badges numéricos ("Novedades 3", "Updates, 2") y signos sueltos manteniendo letras.
        val cleaned = buildString(folded.length) {
            for (ch in folded) {
                when {
                    ch.isLetter() || ch == '\'' || ch == '׳' -> append(ch)
                    else -> append(' ')
                }
            }
        }.trim().replace(Regex("\\s+"), " ")
        return cleaned.takeIf { it.isNotEmpty() && it.length <= 40 }
    }
}
