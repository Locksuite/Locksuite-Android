package com.ejemplo.locksuite.ui.playstore

import com.ejemplo.locksuite.mdm.AppRequestManager
import com.ejemplo.locksuite.mdm.AppRequestManager.Solicitud
import com.ejemplo.locksuite.mdm.PlayStoreCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se puede afirmar de la pantalla de la Tienda Play SIN Android (B.101): textos,
 * etiquetas de cada fila, colores de los recuadros y la regla que decide qué pedidos
 * impiden volver a pedir. Lo que dibuja `PlayStoreActivity` no se prueba acá (necesita un
 * equipo real); lo que decide qué dibuja, sí.
 */
class PlayStoreUiLogicTest {

    private val dia = 24L * 60L * 60L * 1000L

    // ── Textos ──────────────────────────────────────────────────────────────

    @Test
    fun ningunaClaveSeQuedaSinTextoEnNingunIdioma() {
        for (lang in PlayStoreText.LANGS) {
            for (key in PlayStoreText.Key.values()) {
                val text = PlayStoreText.t(key, lang)
                assertTrue("$key en $lang está vacío", text.isNotBlank())
            }
        }
    }

    @Test
    fun elHebreoTrae_letrasHebreas_salvoLasMarcas() {
        // Lite es una marca y se escribe igual en los tres idiomas.
        val excepciones = setOf(PlayStoreText.Key.MODE_LITE)
        for (key in PlayStoreText.Key.values()) {
            if (key in excepciones) continue
            val text = PlayStoreText.t(key, PlayStoreText.LANG_HE)
            val hayHebreo = text.any { it in '֐'..'׿' }
            assertTrue("$key en hebreo no tiene letras hebreas: $text", hayHebreo)
        }
    }

    @Test
    fun losTresIdiomasSonDistintos_enLasClavesQueSeTraducen() {
        val iguales = setOf(PlayStoreText.Key.MODE_KOSHER, PlayStoreText.Key.MODE_LITE)
        for (key in PlayStoreText.Key.values()) {
            if (key in iguales) continue
            val es = PlayStoreText.t(key, PlayStoreText.LANG_ES)
            val en = PlayStoreText.t(key, PlayStoreText.LANG_EN)
            val he = PlayStoreText.t(key, PlayStoreText.LANG_HE)
            assertNotEquals("$key: es == en", es, en)
            assertNotEquals("$key: es == he", es, he)
        }
    }

    @Test
    fun unIdiomaDesconocidoOAusenteCaeEnEspanol() {
        for (lang in listOf(null, "", "fr", "ES")) {
            assertEquals("Tienda Play", PlayStoreText.t(PlayStoreText.Key.TITLE, lang))
        }
    }

    @Test
    fun soloElHebreoSeEscribeDeDerechaAIzquierda() {
        assertTrue(PlayStoreText.isRtl("he"))
        assertFalse(PlayStoreText.isRtl("es"))
        assertFalse(PlayStoreText.isRtl("en"))
        assertFalse(PlayStoreText.isRtl(null))
    }

    @Test
    fun elNombreDelModoSigueLaMismaRegla_queElCatalogo() {
        assertEquals("Kosher", PlayStoreText.modeLabel(PlayStoreCatalog.MODE_KOSHER, "es"))
        assertEquals("Trabajo", PlayStoreText.modeLabel(PlayStoreCatalog.MODE_WORK, "es"))
        assertEquals("Work", PlayStoreText.modeLabel(PlayStoreCatalog.MODE_WORK, "en"))
        assertEquals("Lite", PlayStoreText.modeLabel(PlayStoreCatalog.MODE_LITE, "he"))
        assertEquals("Cerrada", PlayStoreText.modeLabel(PlayStoreCatalog.MODE_CLOSED, "es"))
        // Un modo desconocido, vacío o ausente es CERRADA (fallar cerrado), también acá.
        assertEquals("Cerrada", PlayStoreText.modeLabel("inventado", "es"))
        assertEquals("Closed", PlayStoreText.modeLabel("", "en"))
        assertEquals("סגורה", PlayStoreText.modeLabel(null, "he"))
    }

    @Test
    fun todosLosVeredictosDePedidoTienenTextoEnLosTresIdiomas() {
        for (v in AppRequestManager.Veredicto.values()) {
            for (lang in PlayStoreText.LANGS) {
                assertTrue("$v en $lang", AppRequestManager.motivo(v, lang).isNotBlank())
            }
        }
    }

    // ── Etiquetas de cada fila ──────────────────────────────────────────────

    @Test
    fun accesoCompletoYHabilitada_noLlevaEtiquetas() {
        val tags = PlayStoreRows.tagsFor(
            PlayStoreCatalog.ACCESS_FULL, PlayStoreCatalog.STATUS_ENABLED, installed = false, requestable = false
        )
        assertTrue(tags.isEmpty())
        assertEquals("", PlayStoreRows.subtitle(tags, "es"))
    }

    @Test
    fun sinWeb_seMuestraComoSinWeb() {
        val tags = PlayStoreRows.tagsFor(
            PlayStoreCatalog.ACCESS_NO_WEB, PlayStoreCatalog.STATUS_ENABLED, installed = true, requestable = false
        )
        assertEquals(listOf(PlayStoreRows.Tag.NO_WEB), tags)
        assertEquals("Sin web", PlayStoreRows.subtitle(tags, "es"))
        assertEquals("No web", PlayStoreRows.subtitle(tags, "en"))
    }

    @Test
    fun dominios_seMuestraComoSitiosLimitados() {
        val tags = PlayStoreRows.tagsFor(
            PlayStoreCatalog.ACCESS_DOMAINS, PlayStoreCatalog.STATUS_ENABLED, installed = false, requestable = false
        )
        assertEquals(listOf(PlayStoreRows.Tag.DOMAINS), tags)
    }

    @Test
    fun soloAPedido_seAclara_yUnaPausadaDiceQueEstaEnPausa() {
        val aPedido = PlayStoreRows.tagsFor(
            PlayStoreCatalog.ACCESS_FULL, PlayStoreCatalog.STATUS_REQUEST, installed = false, requestable = true
        )
        assertEquals(listOf(PlayStoreRows.Tag.ON_REQUEST), aPedido)

        // Instalada por la tienda y pausada hasta que se apruebe: "En pausa", no "A pedido".
        val pausada = PlayStoreRows.tagsFor(
            PlayStoreCatalog.ACCESS_FULL, PlayStoreCatalog.STATUS_REQUEST, installed = true, requestable = true
        )
        assertEquals(listOf(PlayStoreRows.Tag.PAUSED), pausada)
    }

    @Test
    fun lasEtiquetasSeCombinan_conSeparador() {
        val tags = PlayStoreRows.tagsFor(
            PlayStoreCatalog.ACCESS_NO_WEB, PlayStoreCatalog.STATUS_REQUEST, installed = false, requestable = true
        )
        assertEquals("Sin web · A pedido", PlayStoreRows.subtitle(tags, "es"))
    }

    // ── Recuadro sin ícono ──────────────────────────────────────────────────

    @Test
    fun laInicialEsLaPrimeraLetraOCifra_enMayuscula() {
        assertEquals("W", PlayStoreRows.initial("waze"))
        assertEquals("W", PlayStoreRows.initial("  Waze"))
        assertEquals("1", PlayStoreRows.initial("1Password"))
        assertEquals("•", PlayStoreRows.initial(""))
        assertEquals("•", PlayStoreRows.initial("  --  "))
        assertEquals("א", PlayStoreRows.initial("אפליקציה"))
    }

    @Test
    fun elColorDelRecuadroEsEstable_yNuncaFalla() {
        val a = PlayStoreRows.tileColor("com.waze")
        assertEquals(a, PlayStoreRows.tileColor("com.waze"))
        // Un hashCode negativo no puede dar un índice fuera de la paleta.
        for (pkg in listOf("com.a", "com.b", "zzzzzzzzzzzzzzzzzzzzzz.yyyyyyyyyyyyyyyyyyy", "", "ñ.ü")) {
            PlayStoreRows.tileColor(pkg)
        }
        // La paleta se reparte: no todas las apps salen del mismo color.
        val colores = (0 until 40).map { PlayStoreRows.tileColor("com.app$it") }.toSet()
        assertTrue(colores.size > 3)
    }

    // ── Pedidos ─────────────────────────────────────────────────────────────

    private fun pedido(
        pkg: String,
        estado: String,
        pedidaEn: Long = 0L,
        resueltaEn: Long = 0L,
        fuente: String = AppRequestManager.FUENTE_PLAY
    ) = Solicitud(packageName = pkg, label = pkg, estado = estado, pedidaEnMs = pedidaEn, resueltaEnMs = resueltaEn, fuente = fuente)

    @Test
    fun unaAprobacionVieja_deLaTiendaPlay_yaNoImpidePedirDeNuevo() {
        val ahora = 100L * dia
        val vieja = pedido("com.waze", AppRequestManager.APROBADA, pedidaEn = ahora - 31 * dia, resueltaEn = ahora - 30 * dia)
        val lista = listOf(vieja)

        // Sin el filtro, `decidir` dice "ya la pediste" para siempre (aprobada y sin catálogo).
        assertEquals(
            AppRequestManager.Veredicto.YA_PEDIDA,
            AppRequestManager.decidir("com.waze", emptySet(), lista, ahora)
        )
        // Con el filtro, se puede volver a pedir.
        val vigentes = AppRequestManager.sinAprobacionesViejasDePlay(lista, ahora)
        assertTrue(vigentes.isEmpty())
        assertEquals(
            AppRequestManager.Veredicto.OK,
            AppRequestManager.decidir("com.waze", emptySet(), vigentes, ahora)
        )
    }

    @Test
    fun unaAprobacionReciente_sigueValiendo_porqueElSyncPuedeEstarEnCamino() {
        val ahora = 100L * dia
        val reciente = pedido("com.waze", AppRequestManager.APROBADA, pedidaEn = ahora - 2 * dia, resueltaEn = ahora - dia / 2)
        val vigentes = AppRequestManager.sinAprobacionesViejasDePlay(listOf(reciente), ahora)
        assertEquals(listOf(reciente), vigentes)
        assertEquals(
            AppRequestManager.Veredicto.YA_PEDIDA,
            AppRequestManager.decidir("com.waze", emptySet(), vigentes, ahora)
        )
    }

    @Test
    fun elLimiteSonExactamente24Horas() {
        val ahora = 100L * dia
        val justo = pedido("a.b", AppRequestManager.APROBADA, resueltaEn = ahora - AppRequestManager.ESPERA_REPEDIDO_MS)
        val casi = pedido("c.d", AppRequestManager.APROBADA, resueltaEn = ahora - AppRequestManager.ESPERA_REPEDIDO_MS + 1)
        val vigentes = AppRequestManager.sinAprobacionesViejasDePlay(listOf(justo, casi), ahora)
        assertEquals(listOf(casi), vigentes)
    }

    @Test
    fun sinFechaDeResolucion_seFallaDelLadoRestrictivo() {
        val ahora = 100L * dia
        val sinFecha = pedido("com.waze", AppRequestManager.APROBADA, pedidaEn = 1L, resueltaEn = 0L)
        assertEquals(listOf(sinFecha), AppRequestManager.sinAprobacionesViejasDePlay(listOf(sinFecha), ahora))
    }

    @Test
    fun noSeTocanLosPedidosDeLaTiendaVieja_niLosPendientes_niLosRechazados() {
        val ahora = 100L * dia
        val viejaTiendaVieja = pedido("x.y", AppRequestManager.APROBADA, resueltaEn = ahora - 50 * dia, fuente = "")
        val pendiente = pedido("p.q", AppRequestManager.PENDIENTE, pedidaEn = ahora - 90 * dia)
        val rechazada = pedido("r.s", AppRequestManager.RECHAZADA, resueltaEn = ahora - 90 * dia)
        val lista = listOf(viejaTiendaVieja, pendiente, rechazada)
        assertEquals(lista, AppRequestManager.sinAprobacionesViejasDePlay(lista, ahora))
    }

    @Test
    fun elPedidoRecienMandado_seConserva_hastaQueLaLecturaLoTrae() {
        val ahora = 1_000_000L
        val mio = pedido("com.waze", AppRequestManager.PENDIENTE, pedidaEn = ahora - 5_000L)
        // La lectura de Firebase todavía no lo trae: se conserva.
        assertEquals(listOf(mio), PlayStoreRows.mergeRequests(emptyList(), listOf(mio), ahora))
        // Después de la gracia, deja de conservarse (si Firebase no lo trae, no existe).
        assertTrue(PlayStoreRows.mergeRequests(emptyList(), listOf(mio), ahora + PlayStoreRows.LOCAL_GRACE_MS + 10_000L).isEmpty())
    }

    @Test
    fun siFirebaseYaTraeElPedido_mandaLoDeFirebase() {
        val ahora = 1_000_000L
        val mio = pedido("com.waze", AppRequestManager.PENDIENTE, pedidaEn = ahora - 5_000L)
        val contestado = pedido("com.waze", AppRequestManager.APROBADA, pedidaEn = ahora - 5_000L, resueltaEn = ahora - 1_000L)
        val unido = PlayStoreRows.mergeRequests(listOf(contestado), listOf(mio), ahora)
        assertEquals(listOf(contestado), unido)
    }

    @Test
    fun estaPedida_siEstaPendienteOAprobadaSinCatalogo_peroNoSiFueRechazada() {
        val pendiente = pedido("a.a", AppRequestManager.PENDIENTE)
        val aprobada = pedido("b.b", AppRequestManager.APROBADA)
        val rechazada = pedido("c.c", AppRequestManager.RECHAZADA)
        val todas = listOf(pendiente, aprobada, rechazada)
        assertTrue(PlayStoreRows.isRequested(todas, "a.a"))
        assertTrue(PlayStoreRows.isRequested(todas, "b.b"))
        assertFalse(PlayStoreRows.isRequested(todas, "c.c"))
        assertFalse(PlayStoreRows.isRequested(todas, "z.z"))
    }
}
