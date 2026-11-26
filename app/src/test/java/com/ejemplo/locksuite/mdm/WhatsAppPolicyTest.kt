package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action
import com.ejemplo.locksuite.mdm.WhatsAppPolicy.ActivityCategory
import com.ejemplo.locksuite.mdm.WhatsAppPolicy.ScreenSnapshot
import com.ejemplo.locksuite.mdm.WhatsAppPolicy.TabCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppPolicyTest {

    // ── Falsos positivos que NUNCA deben bloquearse ──────────────────────────

    @Test
    fun `no bloquea About del perfil aunque contenga status en la clase`() {
        val safeClasses = listOf(
            "com.whatsapp.profile.ui.AboutStatusPrivacyActivity",
            "com.whatsapp.profile.ui.AboutStatusBlockListPickerActivity",
            "com.whatsapp.evolvedabout.ui.creation.AddTextStatusActivity",
            "com.whatsapp.orderstatus.view.RichOrderDetailActivity",
            "com.whatsapp.systemstatus.ui.SystemStatusActivity",
            "com.whatsapp.payments.brazilpay.pixnative.ui.PixNativeConnectedStatusActivity",
            "com.whatsapp.payments.indiaupi.ui.IndiaBillPaymentsComplaintStatusActivity"
        )
        for (cls in safeClasses) {
            assertEquals("Esperaba SAFE para $cls", ActivityCategory.SAFE, WhatsAppPolicy.classifyActivity(cls))
            val dec = WhatsAppPolicy.evaluate(ScreenSnapshot(activityClass = cls), blockStatus = true, blockChannels = true)
            assertEquals("No debe bloquear $cls", Action.ALLOW, dec.action)
        }
    }

    @Test
    fun `no bloquea Ajustes de privacidad de canales ni Comunidades ni Llamadas ni MediaView`() {
        val safeClasses = listOf(
            "com.whatsapp.settings.ui.privacy.ChannelsPrivacySettingsActivity",
            "com.whatsapp.settings.ui.privacy.SettingsPrivacy",
            "com.whatsapp.community.product.CommunityHomeActivity",
            "com.whatsapp.calling.ui.VoipActivityV2",
            "com.whatsapp.mediaview.MediaViewActivity",
            "com.whatsapp.mediaview.single.SingleMediaViewActivity",
            "com.whatsapp.chatinfo.ContactInfoActivity",
            "com.whatsapp.chatinfo.group.GroupChatInfoActivity",
            "com.whatsapp.chatinfo.group.memberupdates.MemberUpdatesActivity"
        )
        for (cls in safeClasses) {
            assertEquals(ActivityCategory.SAFE, WhatsAppPolicy.classifyActivity(cls))
            val dec = WhatsAppPolicy.evaluate(ScreenSnapshot(activityClass = cls), blockStatus = true, blockChannels = true)
            assertEquals(Action.ALLOW, dec.action)
        }
    }

    @Test
    fun `no bloquea chat normal con mensaje seleccionado que dice estados o novedades o canales`() {
        val snapshot = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf("com.whatsapp:id/entry", "com.whatsapp:id/action_call", "com.whatsapp:id/action_videocall"),
            tabs = listOf(
                // Un mensaje largo seleccionado fuera de la barra de navegación
                TabCandidate(viewId = "message_text", label = "Viste los estados y canales en novedades?", isSelected = true, inNavChrome = false)
            ),
            conversationSubtitle = "en línea"
        )
        val dec = WhatsAppPolicy.evaluate(snapshot, blockStatus = true, blockChannels = true)
        assertEquals(Action.ALLOW, dec.action)
    }

    @Test
    fun `no bloquea chat normal que contiene un mensaje o enlace reenviado de un Canal`() {
        val snapshot = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf(
                "com.whatsapp:id/entry",
                "com.whatsapp:id/action_call",
                "com.whatsapp:id/newsletter_link_card",
                "com.whatsapp:id/newsletter_link_name",
                "com.whatsapp:id/newsletter_link_followers",
                "com.whatsapp:id/newsletter_name_forwarded_message",
                "com.whatsapp:id/view_channel_button"
            ),
            conversationSubtitle = "últ. vez hoy a las 14:20"
        )
        val dec = WhatsAppPolicy.evaluate(snapshot, blockStatus = true, blockChannels = true)
        assertEquals(Action.ALLOW, dec.action)
    }

    @Test
    fun `no bloquea grupo de solo anuncios donde no hay compositor ni llamadas`() {
        val snapshot = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp:id/conversation_contact_status"),
            conversationSubtitle = "Juan, María, Tú"
        )
        val dec = WhatsAppPolicy.evaluate(snapshot, blockStatus = true, blockChannels = true)
        assertEquals(Action.ALLOW, dec.action)
    }

    // ── Bloqueos precisos de Estados ─────────────────────────────────────────

    @Test
    fun `bloquea todas las pantallas reales de Estados solo cuando blockStatus esta activo`() {
        val statusClasses = listOf(
            "com.whatsapp.status.playback.StatusPlaybackActivity",
            "com.whatsapp.status.playback.MyStatusesActivity",
            "com.whatsapp.status.playback.ArchivedStatusesActivity",
            "com.whatsapp.status.playback.reply.StatusReplyActivity",
            "com.whatsapp.status.playback.reply.MessageReplyActivity",
            "com.whatsapp.status.composer.ConsolidatedStatusComposerActivity",
            "com.whatsapp.status.composer.textcomposer.TextStatusComposerActivityV2",
            "com.whatsapp.status.updates.ui.statusmuting.MutedStatusesActivity",
            "com.whatsapp.status.audienceselector.StatusPrivacyActivity",
            "com.whatsapp.contact.ui.picker.StatusAudienceSelectionActivity",
            "com.whatsapp.wamo.deeplink.WamoStatusInHouseAdAcquisitionActivity"
        )
        for (cls in statusClasses) {
            assertEquals("Esperaba STATUS para $cls", ActivityCategory.STATUS, WhatsAppPolicy.classifyActivity(cls))
            assertEquals(
                Action.BLOCK_STATUS,
                WhatsAppPolicy.evaluate(ScreenSnapshot(activityClass = cls), blockStatus = true, blockChannels = false).action
            )
            assertEquals(
                "No debe bloquear Estado $cls si solo blockChannels=true",
                Action.ALLOW,
                WhatsAppPolicy.evaluate(ScreenSnapshot(activityClass = cls), blockStatus = false, blockChannels = true).action
            )
        }
    }

    @Test
    fun `bloquea visor de Estados por IDs estructurales si la Activity reportada es generica`() {
        val snapshot = ScreenSnapshot(
            activityClass = "android.widget.FrameLayout",
            viewIds = setOf("com.whatsapp.w4b:id/status_viewer_wds_top_bar", "com.whatsapp.w4b:id/status_playback_image")
        )
        assertEquals(Action.BLOCK_STATUS, WhatsAppPolicy.evaluate(snapshot, blockStatus = true, blockChannels = false).action)
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(snapshot, blockStatus = false, blockChannels = true).action)
    }

    // ── Bloqueos precisos de Canales ─────────────────────────────────────────

    @Test
    fun `bloquea todas las pantallas de Canales y NewsletterStatus segun el switch correspondiente`() {
        val channelClasses = listOf(
            "com.whatsapp.newsletter.directory.ui.NewsletterDirectoryActivity",
            "com.whatsapp.newsletter.directory.ui.NewsletterDirectoryCategoriesActivity",
            "com.whatsapp.newsletter.editcreate.ui.NewsletterCreationActivity",
            "com.whatsapp.newsletter.settings.ui.NewsletterSettingsActivity",
            "com.whatsapp.chatinfo.newsletter.NewsletterInfoActivity",
            "com.whatsapp.chatinfo.newsletter.insights.NewsletterInsightsActivity",
            "com.whatsapp.newsletterenforcements.ui.alerts.NewsletterAlertsActivity",
            "com.whatsapp.followerinvite.invitepicker.InviteNewsletterFollowerSelector",
            "com.whatsapp.conversation.ui.conversationrow.bottomsheets.newsletteradminprofilephoto.ViewNewsletterAdminProfilePhoto",
            "com.whatsapp.response.ui.NewsletterResponseListActivity"
        )
        for (cls in channelClasses) {
            assertEquals("Esperaba CHANNEL para $cls", ActivityCategory.CHANNEL, WhatsAppPolicy.classifyActivity(cls))
            assertEquals(
                Action.BLOCK_CHANNEL,
                WhatsAppPolicy.evaluate(ScreenSnapshot(activityClass = cls), blockStatus = false, blockChannels = true).action
            )
            assertEquals(
                Action.ALLOW,
                WhatsAppPolicy.evaluate(ScreenSnapshot(activityClass = cls), blockStatus = true, blockChannels = false).action
            )
        }

        val ns = "com.whatsapp.status.playback.newsletterstatus.MyNewsletterStatusesActivity"
        assertEquals(ActivityCategory.CHANNEL_STATUS, WhatsAppPolicy.classifyActivity(ns))
        assertEquals(Action.BLOCK_CHANNEL, WhatsAppPolicy.evaluate(ScreenSnapshot(ns), blockStatus = false, blockChannels = true).action)
        assertEquals(Action.BLOCK_STATUS, WhatsAppPolicy.evaluate(ScreenSnapshot(ns), blockStatus = true, blockChannels = false).action)
    }

    @Test
    fun `bloquea Canal abierto dentro de com whatsapp Conversation`() {
        // Caso A: Canal que el usuario no sigue aún ("Seguir")
        val previewChannel = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf("com.whatsapp:id/follow_channel_button", "com.whatsapp:id/newsletter_follow_privacy_banner")
        )
        assertEquals(Action.BLOCK_CHANNEL, WhatsAppPolicy.evaluate(previewChannel, blockStatus = false, blockChannels = true).action)
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(previewChannel, blockStatus = true, blockChannels = false).action)

        // Caso B: Canal seguido sin compositor ("12,4 mil seguidores")
        val followedChannel = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf("com.whatsapp.w4b:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_status"),
            conversationSubtitle = "12,4K seguidores"
        )
        assertEquals(Action.BLOCK_CHANNEL, WhatsAppPolicy.evaluate(followedChannel, blockStatus = false, blockChannels = true).action)

        // Caso C: Canal propio modo administrador (tiene compositor pero también channel_admin_bottom_bar)
        val adminChannel = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf("com.whatsapp:id/entry", "com.whatsapp:id/channel_admin_bottom_bar", "com.whatsapp:id/newsletter_message_view_count")
        )
        assertEquals(Action.BLOCK_CHANNEL, WhatsAppPolicy.evaluate(adminChannel, blockStatus = false, blockChannels = true).action)
    }

    // ── Pestaña Novedades / Chats ────────────────────────────────────────────

    @Test
    fun `pestaña Novedades redirige a Chats solo cuando ambos switches estan activos`() {
        val updatesById = ScreenSnapshot(
            activityClass = "com.whatsapp.HomeActivity",
            viewIds = setOf("com.whatsapp:id/bottom_nav", "com.whatsapp:id/updates_list", "com.whatsapp:id/channel_row"),
            tabs = listOf(
                TabCandidate("com.whatsapp:id/TabBarButton_Chats", "Chats", isSelected = false, inNavChrome = true),
                TabCandidate("com.whatsapp:id/TabBarButton_Status", "Novedades 2", isSelected = true, inNavChrome = true)
            )
        )
        assertEquals(Action.REDIRECT_TO_CHATS, WhatsAppPolicy.evaluate(updatesById, blockStatus = true, blockChannels = true).action)
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(updatesById, blockStatus = true, blockChannels = false).action)
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(updatesById, blockStatus = false, blockChannels = true).action)
    }

    @Test
    fun `pestaña Chats en HomeActivity nunca se redirige ni se bloquea aunque haya badge`() {
        val chatsHome = ScreenSnapshot(
            activityClass = "com.whatsapp.home.ui.HomeActivity",
            viewIds = setOf("com.whatsapp:id/bottom_nav"),
            tabs = listOf(
                TabCandidate("com.whatsapp:id/TabBarButton_Chats", "Chats 5", isSelected = true, inNavChrome = true),
                TabCandidate("com.whatsapp:id/TabBarButton_Status", "עדכונים", isSelected = false, inNavChrome = true)
            )
        )
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(chatsHome, blockStatus = true, blockChannels = true).action)
    }

    @Test
    fun `no redirige desde Chats si ViewPager deja updates_list montada en el arbol`() {
        val chatsWithOffscreenUpdates = ScreenSnapshot(
            activityClass = "com.whatsapp.HomeActivity",
            viewIds = setOf("com.whatsapp:id/bottom_nav", "com.whatsapp:id/updates_list"),
            tabs = listOf(
                TabCandidate("com.whatsapp:id/TabBarButton_Chats", "Chats", isSelected = true, inNavChrome = true),
                TabCandidate("com.whatsapp:id/TabBarButton_Status", "Novedades", isSelected = false, inNavChrome = true)
            )
        )
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(chatsWithOffscreenUpdates, blockStatus = true, blockChannels = true).action)
    }

    @Test
    fun `detecta pestaña Novedades en hebreo o idish dentro del nav chrome aunque falte TabBarButton_Status`() {
        val hebrewUpdates = ScreenSnapshot(
            activityClass = "com.whatsapp.HomeActivity",
            tabs = listOf(
                TabCandidate("tab_chats", "צ׳אטים", isSelected = false, inNavChrome = true),
                TabCandidate("tab_updates", "עדכונים 4", isSelected = true, inNavChrome = true)
            )
        )
        assertEquals(Action.REDIRECT_TO_CHATS, WhatsAppPolicy.evaluate(hebrewUpdates, blockStatus = true, blockChannels = true).action)
        assertTrue(WhatsAppPolicy.isChatsTabLabel("צ׳אטים 12"))
        assertFalse(WhatsAppPolicy.isChatsTabLabel("עדכונים 4"))
    }

    @Test
    fun `detecta subtitulos de Canal sin digitos en ES EN HE y PT`() {
        val unnumberedChannelSubtitles = listOf(
            "Siguiendo",
            "Following",
            "Seguindo",
            "A seguir",
            "Canal público",
            "Public channel",
            "ערוץ ציבורי",
            "עוקב",
            "במעקב",
            "Canal · Siguiendo",
            "un seguidor",
            "one follower",
            "עוקב אחד"
        )
        for (subtitle in unnumberedChannelSubtitles) {
            assertTrue("Esperaba match de canal para '$subtitle'", WhatsAppPolicy.isChannelHeaderSubtitle(subtitle))
            val snap = ScreenSnapshot(
                activityClass = "com.whatsapp.Conversation",
                viewIds = setOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp:id/conversation_contact_status"),
                conversationSubtitle = subtitle
            )
            assertEquals(
                "Debe bloquear Canal con subtitulo '$subtitle'",
                Action.BLOCK_CHANNEL,
                WhatsAppPolicy.evaluate(snap, blockStatus = false, blockChannels = true).action
            )
            assertEquals(
                "No debe bloquear si blockChannels=false para '$subtitle'",
                Action.ALLOW,
                WhatsAppPolicy.evaluate(snap, blockStatus = true, blockChannels = false).action
            )
        }
    }

    @Test
    fun `no confunde lista de miembros ni chat normal con subtitulo de Canal`() {
        val memberLists = listOf(
            "Juan, María, Tú",
            "Ana, Pedro, Sofía, Tú",
            "Siguiendo, Juan, Pedro"
        )
        for (subtitle in memberLists) {
            assertFalse("No debe tratar lista '$subtitle' como Canal", WhatsAppPolicy.isChannelHeaderSubtitle(subtitle))
            val snap = ScreenSnapshot(
                activityClass = "com.whatsapp.Conversation",
                viewIds = setOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp:id/conversation_contact_status"),
                conversationSubtitle = subtitle
            )
            assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(snap, blockStatus = true, blockChannels = true).action)
        }

        // Chat normal con compositor o llamada nunca bloquea aunque el contacto se llame/tenga estado parecido
        val chatWithComposer = ScreenSnapshot(
            activityClass = "com.whatsapp.Conversation",
            viewIds = setOf("com.whatsapp:id/entry", "com.whatsapp:id/conversation_contact_status"),
            conversationSubtitle = "Siguiendo"
        )
        assertEquals(Action.ALLOW, WhatsAppPolicy.evaluate(chatWithComposer, blockStatus = true, blockChannels = true).action)
    }
}
