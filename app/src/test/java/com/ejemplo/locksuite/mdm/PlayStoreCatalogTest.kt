package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.PlayStoreCatalog.ACCESS_DOMAINS
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.ACCESS_FULL
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.ACCESS_NO_WEB
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.MODE_CLOSED
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.MODE_KOSHER
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.MODE_LITE
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.MODE_WORK
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.RawApp
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.StoreApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 29/10/2026 (B.101) — qué ofrece la Tienda Play. Lo caro de equivocarse acá es abrir de
 * más: una app que el administrador no permitió apareciendo (y, por lo tanto,
 * instalándose) en un equipo cuyo modo no la incluye.
 */
class PlayStoreCatalogTest {

    private fun app(
        pkg: String,
        tier: Int = 1,
        access: String = ACCESS_FULL,
        label: String = pkg.substringAfterLast('.'),
        allow: List<String> = emptyList(),
        block: List<String> = emptyList()
    ): StoreApp = PlayStoreCatalog.build(
        RawApp(pkg, label, tier, access, allow, block, null), extra = false
    )!!

    private fun extra(pkg: String, access: String = ACCESS_FULL, label: String = pkg.substringAfterLast('.')): StoreApp =
        PlayStoreCatalog.build(RawApp(pkg, label, null, access), extra = true)!!

    /** Una entrada global con estado explícito (habilitada / a pedido / bloqueada). */
    private fun appS(pkg: String, status: String?, tier: Int = 1, label: String = pkg.substringAfterLast('.')): StoreApp =
        PlayStoreCatalog.build(RawApp(pkg, label, tier, null, null, null, null, status), extra = false)!!

    /** Un extra de equipo con estado explícito. */
    private fun extraS(pkg: String, status: String?, label: String = pkg.substringAfterLast('.')): StoreApp =
        PlayStoreCatalog.build(RawApp(pkg, label, null, null, null, null, null, status), extra = true)!!

    private val catalog = listOf(
        app("com.kosher.uno", tier = 1, label = "Uno"),
        app("com.trabajo.dos", tier = 2, label = "Dos"),
        app("com.lite.tres", tier = 3, label = "Tres")
    )

    private fun pkgs(list: List<StoreApp>) = list.map { it.packageName }

    // ── Modos ───────────────────────────────────────────────────────────────────

    @Test
    fun normalizeMode_desconocidoEsCerrada() {
        assertEquals(MODE_CLOSED, PlayStoreCatalog.normalizeMode(null))
        assertEquals(MODE_CLOSED, PlayStoreCatalog.normalizeMode(""))
        assertEquals(MODE_CLOSED, PlayStoreCatalog.normalizeMode("abierta"))
        assertEquals(MODE_CLOSED, PlayStoreCatalog.normalizeMode("4"))
        assertEquals(MODE_KOSHER, PlayStoreCatalog.normalizeMode(" KOSHER "))
        assertEquals(MODE_WORK, PlayStoreCatalog.normalizeMode("trabajo"))
        assertEquals(MODE_LITE, PlayStoreCatalog.normalizeMode("Lite"))
    }

    @Test
    fun modeFromProfile_ausenteODesconocidoNoSeToca() {
        assertNull(PlayStoreCatalog.modeFromProfile(null))
        assertNull(PlayStoreCatalog.modeFromProfile(""))
        assertNull(PlayStoreCatalog.modeFromProfile("cualquiera"))
        assertEquals(MODE_CLOSED, PlayStoreCatalog.modeFromProfile("cerrada"))
        assertEquals(MODE_LITE, PlayStoreCatalog.modeFromProfile(" lite "))
    }

    @Test
    fun maxTier_porModo() {
        assertEquals(0, PlayStoreCatalog.maxTier(MODE_CLOSED))
        assertEquals(0, PlayStoreCatalog.maxTier(null))
        assertEquals(0, PlayStoreCatalog.maxTier("raro"))
        assertEquals(1, PlayStoreCatalog.maxTier(MODE_KOSHER))
        assertEquals(2, PlayStoreCatalog.maxTier(MODE_WORK))
        assertEquals(3, PlayStoreCatalog.maxTier(MODE_LITE))
        assertFalse(PlayStoreCatalog.isOpen(MODE_CLOSED))
        assertTrue(PlayStoreCatalog.isOpen(MODE_KOSHER))
    }

    // ── Niveles acumulativos ────────────────────────────────────────────────────

    @Test
    fun effective_nivelesAcumulativos() {
        assertEquals(emptyList<String>(), pkgs(PlayStoreCatalog.effective(MODE_CLOSED, catalog, emptyList())))
        assertEquals(listOf("com.kosher.uno"), pkgs(PlayStoreCatalog.effective(MODE_KOSHER, catalog, emptyList())))
        assertEquals(
            listOf("com.trabajo.dos", "com.kosher.uno"),
            pkgs(PlayStoreCatalog.effective(MODE_WORK, catalog, emptyList()))
        )
        assertEquals(
            listOf("com.trabajo.dos", "com.lite.tres", "com.kosher.uno"),
            pkgs(PlayStoreCatalog.effective(MODE_LITE, catalog, emptyList()))
        )
    }

    @Test
    fun effective_modoDesconocidoOfreceNada() {
        assertTrue(PlayStoreCatalog.effective("loquesea", catalog, emptyList()).isEmpty())
        assertTrue(PlayStoreCatalog.effective(null, catalog, emptyList()).isEmpty())
    }

    @Test
    fun effective_ordenadoPorNombreSinMirarMayusculas() {
        val list = listOf(
            app("com.a.zeta", label = "zeta"),
            app("com.a.alfa", label = "Alfa"),
            app("com.a.beta", label = "beta")
        )
        assertEquals(
            listOf("com.a.alfa", "com.a.beta", "com.a.zeta"),
            pkgs(PlayStoreCatalog.effective(MODE_KOSHER, list, emptyList()))
        )
    }

    // ── Extras por equipo ───────────────────────────────────────────────────────

    @Test
    fun effective_extrasSoloConLaTiendaAbierta() {
        val extras = listOf(extra("com.solo.extra", label = "Extra"))
        assertTrue(PlayStoreCatalog.effective(MODE_CLOSED, catalog, extras).isEmpty())
        val kosher = pkgs(PlayStoreCatalog.effective(MODE_KOSHER, catalog, extras))
        assertTrue("com.solo.extra" in kosher)
        assertTrue("com.trabajo.dos" !in kosher)
    }

    @Test
    fun effective_unExtraGanaSobreLaEntradaGlobal() {
        // La global está en el nivel 3 (modo kosher no la ve); el equipo la agregó con "sin web".
        val extras = listOf(extra("com.lite.tres", access = ACCESS_NO_WEB, label = "Tres local"))
        val out = PlayStoreCatalog.effective(MODE_KOSHER, catalog, extras)
        val tres = out.single { it.packageName == "com.lite.tres" }
        assertEquals(ACCESS_NO_WEB, tres.access)
        assertTrue(tres.extra)
        assertEquals("Tres local", tres.label)
    }

    @Test
    fun effective_noDuplicaUnPaqueteQueEstaEnLasDosListas() {
        val extras = listOf(extra("com.kosher.uno"))
        val out = PlayStoreCatalog.effective(MODE_LITE, catalog, extras)
        assertEquals(1, out.count { it.packageName == "com.kosher.uno" })
    }

    // ── Lo prohibido ────────────────────────────────────────────────────────────

    @Test
    fun effective_loProhibidoPorElEquipoNoSeOfrece() {
        val out = PlayStoreCatalog.effective(MODE_LITE, catalog, emptyList()) { it == "com.trabajo.dos" }
        assertTrue("com.trabajo.dos" !in pkgs(out))
        assertEquals(2, out.size)
    }

    @Test
    fun effective_tambienFiltraLosExtras() {
        val extras = listOf(extra("com.mala.app"))
        assertTrue(PlayStoreCatalog.effective(MODE_KOSHER, catalog, extras) { it == "com.mala.app" }
            .none { it.packageName == "com.mala.app" })
    }

    @Test
    fun build_nuncaOfreceLockSuiteNiGooglePlay() {
        for (p in PlayStoreCatalog.NEVER_OFFERED) {
            assertNull(p, PlayStoreCatalog.build(RawApp(p, "x", 1), extra = false))
            assertNull(p, PlayStoreCatalog.build(RawApp(p, "x"), extra = true))
        }
        assertTrue("com.ejemplo.locksuite" in PlayStoreCatalog.NEVER_OFFERED)
        assertTrue("com.android.vending" in PlayStoreCatalog.NEVER_OFFERED)
    }

    // ── Validación de entradas ──────────────────────────────────────────────────

    @Test
    fun build_descartaLoInvalido() {
        assertNull(PlayStoreCatalog.build(RawApp(null, "x", 1), false))
        assertNull(PlayStoreCatalog.build(RawApp("", "x", 1), false))
        assertNull(PlayStoreCatalog.build(RawApp("sinpunto", "x", 1), false))
        assertNull(PlayStoreCatalog.build(RawApp("com..doble", "x", 1), false))
        assertNull(PlayStoreCatalog.build(RawApp("1com.algo", "x", 1), false))
        assertNull(PlayStoreCatalog.build(RawApp("com.algo con espacio", "x", 1), false))
        assertNull(PlayStoreCatalog.build(RawApp("com.algo;rm -rf", "x", 1), false))
        // nivel
        assertNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", null), false))
        assertNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", 0), false))
        assertNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", 4), false))
        assertNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", -1), false))
        assertNotNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", 3), false))
    }

    @Test
    fun build_accesoDesconocidoSeDescartaNoSeAsumeCompleto() {
        assertNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, "libre"), false))
        assertNull(PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, ""), false))
        // Sin el campo: completo (así viene lo más simple, un paquete y un nivel).
        assertEquals(ACCESS_FULL, PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, null), false)!!.access)
        assertEquals(ACCESS_NO_WEB, PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, " NoWeb "), false)!!.access)
    }

    @Test
    fun build_extraNoNecesitaNivel() {
        val a = PlayStoreCatalog.build(RawApp("com.ok.app", "Ok"), extra = true)!!
        assertEquals(0, a.tier)
        assertTrue(a.extra)
    }

    @Test
    fun build_losDominiosSoloSeConservanConAccesoDominios() {
        val full = PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, ACCESS_FULL, listOf("a.com"), listOf("b.com")), false)!!
        assertTrue(full.allow.isEmpty() && full.block.isEmpty())
        val noweb = PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, ACCESS_NO_WEB, listOf("a.com"), listOf("b.com")), false)!!
        assertTrue(noweb.allow.isEmpty() && noweb.block.isEmpty())
        val dom = PlayStoreCatalog.build(RawApp("com.ok.app", "x", 1, ACCESS_DOMAINS, listOf("a.com"), listOf("b.com")), false)!!
        assertEquals(listOf("a.com"), dom.allow)
        assertEquals(listOf("b.com"), dom.block)
    }

    @Test
    fun buildAll_duplicadoGanaElNivelMasAltoYRespetaElTope() {
        val raws = listOf(
            RawApp("com.dup.app", "A", 1),
            RawApp("com.dup.app", "A", 3),
            RawApp("com.otra.app", "B", 2),
            RawApp("malo", "C", 1)
        )
        val out = PlayStoreCatalog.buildAll(raws, extra = false)
        assertEquals(2, out.size)
        assertEquals(3, out.single { it.packageName == "com.dup.app" }.tier)

        val many = (1..500).map { RawApp("com.app.n$it", "n$it", 1) }
        assertEquals(PlayStoreCatalog.MAX_APPS, PlayStoreCatalog.buildAll(many, extra = false).size)
        assertEquals(5, PlayStoreCatalog.buildAll(many, extra = false, limit = 5).size)
    }

    // ── Dominios ────────────────────────────────────────────────────────────────

    @Test
    fun cleanDomain_aceptaLoQueUnHumanoPega() {
        assertEquals("www.ejemplo.com", PlayStoreCatalog.cleanDomain("https://www.Ejemplo.com/ruta?x=1#y"))
        assertEquals("ejemplo.com", PlayStoreCatalog.cleanDomain("*.ejemplo.com"))
        assertEquals("ejemplo.com", PlayStoreCatalog.cleanDomain(".ejemplo.com."))
        assertEquals("ejemplo.com", PlayStoreCatalog.cleanDomain("  EJEMPLO.COM  "))
        assertEquals("ejemplo.com", PlayStoreCatalog.cleanDomain("http://user@ejemplo.com:8080/x"))
        assertEquals("a-b.ejemplo.co.il", PlayStoreCatalog.cleanDomain("a-b.ejemplo.co.il"))
    }

    @Test
    fun cleanDomain_rechazaLoQueNoEsUnDominio() {
        assertNull(PlayStoreCatalog.cleanDomain(null))
        assertNull(PlayStoreCatalog.cleanDomain(""))
        assertNull(PlayStoreCatalog.cleanDomain("com"))          // un TLD entero
        assertNull(PlayStoreCatalog.cleanDomain("localhost"))
        assertNull(PlayStoreCatalog.cleanDomain("1.2.3.4"))      // una IP no es un dominio
        assertNull(PlayStoreCatalog.cleanDomain("-malo.com"))
        assertNull(PlayStoreCatalog.cleanDomain("malo-.com"))
        assertNull(PlayStoreCatalog.cleanDomain("ma lo.com"))
        assertNull(PlayStoreCatalog.cleanDomain("a..com"))
        assertNull(PlayStoreCatalog.cleanDomain("a_b.com"))
        assertNull(PlayStoreCatalog.cleanDomain("x".repeat(260) + ".com"))
    }

    @Test
    fun cleanDomains_sinRepetidosYConTope() {
        assertEquals(listOf("a.com", "b.com"), PlayStoreCatalog.cleanDomains(listOf("A.com", "https://a.com/", "b.com", "roto", "")))
        val many = (1..100).map { "d$it.com" }
        assertEquals(PlayStoreCatalog.MAX_DOMAINS_PER_LIST, PlayStoreCatalog.cleanDomains(many).size)
        assertTrue(PlayStoreCatalog.cleanDomains(null).isEmpty())
    }

    // ── Nombre e ícono ──────────────────────────────────────────────────────────

    @Test
    fun cleanLabel_usaElEscritoOUnoDerivado() {
        assertEquals("Waze", PlayStoreCatalog.cleanLabel("  Waze  ", "com.waze"))
        assertEquals("Mi App Buena", PlayStoreCatalog.cleanLabel("Mi\n\tApp   Buena", "com.x.y"))
        assertEquals("Waze", PlayStoreCatalog.cleanLabel(null, "com.waze"))
        assertEquals("Mi App", PlayStoreCatalog.cleanLabel("  ", "com.x.mi_app"))
        assertEquals(PlayStoreCatalog.MAX_LABEL_CHARS, PlayStoreCatalog.cleanLabel("a".repeat(200), "com.x.y").length)
    }

    @Test
    fun cleanIconUrl_soloHttpsDelCdnDeGoogle() {
        val ok = "https://play-lh.googleusercontent.com/abc=w96-h96"
        assertEquals(ok, PlayStoreCatalog.cleanIconUrl(ok))
        assertEquals("https://lh3.googleusercontent.com/x", PlayStoreCatalog.cleanIconUrl(" https://lh3.googleusercontent.com/x "))
        assertNull(PlayStoreCatalog.cleanIconUrl(null))
        assertNull(PlayStoreCatalog.cleanIconUrl("http://play-lh.googleusercontent.com/abc"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://evil.com/abc"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://evil.com/x.googleusercontent.com"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://play-lh.googleusercontent.com.evil.com/abc"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://play-lh.googleusercontent.com@evil.com/abc"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://evil.com@play-lh.googleusercontent.com/abc"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://play-lh.googleusercontent.com/a b"))
        assertNull(PlayStoreCatalog.cleanIconUrl("javascript:alert(1)"))
        assertNull(PlayStoreCatalog.cleanIconUrl("https://googleusercontent.com/abc")) // sin subdominio
    }

    // ── Lista blanca ────────────────────────────────────────────────────────────

    @Test
    fun dnsContribution_todasPermitidasYDominiosSoloDeLasDeDominios() {
        val apps = listOf(
            app("com.a.full", access = ACCESS_FULL),
            app("com.b.noweb", access = ACCESS_NO_WEB),
            app("com.c.dom", access = ACCESS_DOMAINS, allow = listOf("ok.com"), block = listOf("mal.com")),
            app("com.d.domvacia", access = ACCESS_DOMAINS)
        )
        val c = PlayStoreCatalog.dnsContribution(apps)
        assertEquals(setOf("com.a.full", "com.b.noweb", "com.c.dom", "com.d.domvacia"), c.allowedPackages)
        assertEquals(setOf("com.c.dom"), c.domainLists.keys)
        assertEquals(listOf("ok.com"), c.domainLists.getValue("com.c.dom").allow)
        assertEquals(listOf("mal.com"), c.domainLists.getValue("com.c.dom").block)
    }

    @Test
    fun dnsContribution_sinAppsNoAportaNada() {
        val c = PlayStoreCatalog.dnsContribution(emptyList())
        assertTrue(c.allowedPackages.isEmpty() && c.domainLists.isEmpty())
    }

    // ── Sin web ─────────────────────────────────────────────────────────────────

    @Test
    fun noWebPackages_soloLasDeAccesoNoweb() {
        val apps = listOf(app("com.a.full"), app("com.b.nw", access = ACCESS_NO_WEB), app("com.c.dom", access = ACCESS_DOMAINS))
        assertEquals(setOf("com.b.nw"), PlayStoreCatalog.noWebPackages(apps))
    }

    @Test
    fun planWebBlocks_enciendeLoQueFaltaYLoReclama() {
        val p = PlayStoreCatalog.planWebBlocks(desired = setOf("a", "b"), currentlyBlocked = setOf("b"), owned = emptySet())
        assertEquals(setOf("a"), p.toBlock)
        assertTrue(p.toUnblock.isEmpty())
        // "b" ya estaba bloqueada por el administrador: no se reclama.
        assertEquals(setOf("a"), p.ownedAfter)
    }

    @Test
    fun planWebBlocks_sueltaLoPropioQueYaNoHaceFalta() {
        val p = PlayStoreCatalog.planWebBlocks(desired = setOf("a"), currentlyBlocked = setOf("a", "x", "y"), owned = setOf("a", "x"))
        assertTrue(p.toBlock.isEmpty())
        assertEquals(setOf("x"), p.toUnblock) // "y" no es de la tienda: no se toca
        assertEquals(setOf("a"), p.ownedAfter)
    }

    @Test
    fun planWebBlocks_siElAdminLaDesbloqueoSeVuelveAPonerYSigueSiendoPropia() {
        val p = PlayStoreCatalog.planWebBlocks(desired = setOf("a"), currentlyBlocked = emptySet(), owned = setOf("a"))
        assertEquals(setOf("a"), p.toBlock)
        assertEquals(setOf("a"), p.ownedAfter)
    }

    @Test
    fun planWebBlocks_propiaQueYaNoEstabaBloqueadaSoloSeSuelta() {
        val p = PlayStoreCatalog.planWebBlocks(desired = emptySet(), currentlyBlocked = emptySet(), owned = setOf("a"))
        assertTrue(p.toBlock.isEmpty() && p.toUnblock.isEmpty() && p.ownedAfter.isEmpty())
    }

    // ── Pausa de lo instalado por la tienda ─────────────────────────────────────

    @Test
    fun planPause_pausaLoQueYaNoSeOfrece() {
        val p = PlayStoreCatalog.planPause(
            offered = setOf("a"),
            installedByStore = setOf("a", "b"),
            pausedByStore = emptySet()
        )
        assertEquals(setOf("b"), p.toPause)
        assertTrue(p.toResume.isEmpty())
        assertEquals(setOf("b"), p.pausedAfter)
    }

    @Test
    fun planPause_reanudaLoQueVuelveAOfrecerse() {
        val p = PlayStoreCatalog.planPause(
            offered = setOf("a", "b"),
            installedByStore = setOf("a", "b"),
            pausedByStore = setOf("b")
        )
        assertTrue(p.toPause.isEmpty())
        assertEquals(setOf("b"), p.toResume)
        assertTrue(p.pausedAfter.isEmpty())
    }

    @Test
    fun planPause_noReanudaLoQueElAdminTieneSuspendido() {
        val p = PlayStoreCatalog.planPause(
            offered = setOf("b"),
            installedByStore = setOf("b"),
            pausedByStore = setOf("b"),
            keepPaused = setOf("b")
        )
        assertTrue(p.toResume.isEmpty())
        assertEquals(setOf("b"), p.pausedAfter)
    }

    @Test
    fun planPause_tiendaCerradaPausaTodoLoQueInstalo() {
        val p = PlayStoreCatalog.planPause(
            offered = emptySet(),
            installedByStore = setOf("a", "b"),
            pausedByStore = setOf("a")
        )
        assertEquals(setOf("b"), p.toPause) // "a" ya estaba pausada
        assertEquals(setOf("a", "b"), p.pausedAfter)
    }

    @Test
    fun planPause_nuncaTocaLoQueLaTiendaNoInstalo() {
        val p = PlayStoreCatalog.planPause(
            offered = emptySet(),
            installedByStore = emptySet(),
            pausedByStore = emptySet()
        )
        assertTrue(p.toPause.isEmpty() && p.toResume.isEmpty() && p.pausedAfter.isEmpty())
    }

    @Test
    fun planPause_loPausadoQueYaNoEstaInstaladoSeSuelta() {
        val p = PlayStoreCatalog.planPause(
            offered = emptySet(),
            installedByStore = emptySet(),
            pausedByStore = setOf("fantasma")
        )
        assertTrue(p.pausedAfter.isEmpty())
    }

    // ── isOffered ───────────────────────────────────────────────────────────────

    @Test
    fun isOffered_soloLoQueEstaEnLaListaEfectiva() {
        val out = PlayStoreCatalog.effective(MODE_KOSHER, catalog, emptyList())
        assertTrue(PlayStoreCatalog.isOffered("com.kosher.uno", out))
        assertFalse(PlayStoreCatalog.isOffered("com.lite.tres", out))
        assertFalse(PlayStoreCatalog.isOffered(null, out))
        assertFalse(PlayStoreCatalog.isOffered("com.kosher.uno", emptyList()))
    }

    // ── Estado por app: habilitada / solo a pedido / bloqueada (30/10/2026) ─────

    @Test
    fun status_ausenteCuentaComoHabilitada() {
        val a = PlayStoreCatalog.build(RawApp("com.waze", "Waze", 1), extra = false)!!
        assertEquals(PlayStoreCatalog.STATUS_ENABLED, a.status)
    }

    @Test
    fun status_desconocidoDescartaLaEntrada() {
        assertNull(PlayStoreCatalog.build(RawApp("com.waze", "Waze", 1, status = "abierta"), extra = false))
        assertNull(PlayStoreCatalog.build(RawApp("com.waze", "Waze", 1, status = ""), extra = false))
        assertNull(PlayStoreCatalog.build(RawApp(null, "Waze", 1, status = "enabled"), extra = false))
        // Con mayúsculas y espacios se reconoce.
        val a = PlayStoreCatalog.build(RawApp("com.waze", "Waze", 1, status = " Request "), extra = false)
        assertEquals(PlayStoreCatalog.STATUS_REQUEST, a!!.status)
    }

    @Test
    fun listed_muestraHabilitadasYAPedido_nuncaLasBloqueadas() {
        val global = listOf(
            appS("com.a.hab", "enabled", label = "A"),
            appS("com.b.ped", "request", label = "B"),
            appS("com.c.blo", "blocked", label = "C")
        )
        val out = PlayStoreCatalog.listed(MODE_KOSHER, global, emptyList())
        assertEquals(listOf("com.a.hab", "com.b.ped"), pkgs(out))
    }

    @Test
    fun effective_dejaInstalarSoloLasHabilitadas() {
        val global = listOf(
            appS("com.a.hab", "enabled", label = "A"),
            appS("com.b.ped", "request", label = "B"),
            appS("com.c.blo", "blocked", label = "C")
        )
        val out = PlayStoreCatalog.effective(MODE_KOSHER, global, emptyList())
        assertEquals(listOf("com.a.hab"), pkgs(out))
        // Una app "a pedido" no es instalable, aunque se la muestre.
        assertFalse(PlayStoreCatalog.isOffered("com.b.ped", out))
    }

    @Test
    fun extraBloqueado_quitaUnaGlobalSoloParaEseEquipo() {
        val global = listOf(appS("com.waze", "enabled", label = "Waze"))
        val conExtra = PlayStoreCatalog.effective(MODE_KOSHER, global, listOf(extraS("com.waze", "blocked")))
        assertTrue(conExtra.isEmpty())
        // Sin el extra, otro equipo la sigue viendo.
        assertEquals(listOf("com.waze"), pkgs(PlayStoreCatalog.effective(MODE_KOSHER, global, emptyList())))
    }

    @Test
    fun extraHabilitado_apruebaUnaAppSoloAPedido() {
        val global = listOf(appS("com.waze", "request", label = "Waze"))
        assertTrue(PlayStoreCatalog.effective(MODE_KOSHER, global, emptyList()).isEmpty())
        val aprobada = PlayStoreCatalog.effective(MODE_KOSHER, global, listOf(extraS("com.waze", "enabled")))
        assertEquals(listOf("com.waze"), pkgs(aprobada))
    }

    @Test
    fun extraHabilitado_destapaUnaGlobalBloqueada() {
        val global = listOf(appS("com.waze", "blocked", label = "Waze"))
        val out = PlayStoreCatalog.effective(MODE_KOSHER, global, listOf(extraS("com.waze", "enabled")))
        assertEquals(listOf("com.waze"), pkgs(out))
    }

    @Test
    fun tiendaCerrada_noMuestraNadaNiAPedidoNiExtras() {
        val global = listOf(appS("com.a.hab", "enabled"), appS("com.b.ped", "request"))
        val extras = listOf(extraS("com.x.ext", "enabled"), extraS("com.y.ped", "request"))
        assertTrue(PlayStoreCatalog.listed(MODE_CLOSED, global, extras).isEmpty())
        assertTrue(PlayStoreCatalog.listed("raro", global, extras).isEmpty())
        assertTrue(PlayStoreCatalog.listed(null, global, extras).isEmpty())
    }

    @Test
    fun listed_respetaElNivelDelModoTambienParaLasAPedido() {
        val global = listOf(
            appS("com.uno.ped", "request", tier = 1, label = "Uno"),
            appS("com.dos.ped", "request", tier = 2, label = "Dos"),
            appS("com.tres.ped", "request", tier = 3, label = "Tres")
        )
        assertEquals(listOf("com.uno.ped"), pkgs(PlayStoreCatalog.listed(MODE_KOSHER, global, emptyList())))
        assertEquals(listOf("com.dos.ped", "com.uno.ped"), pkgs(PlayStoreCatalog.listed(MODE_WORK, global, emptyList())))
        assertEquals(3, PlayStoreCatalog.listed(MODE_LITE, global, emptyList()).size)
    }

    @Test
    fun listed_laReglaDelEquipoTambienQuitaLasAPedido() {
        val global = listOf(appS("com.a.ped", "request"), appS("com.b.hab", "enabled"))
        val out = PlayStoreCatalog.listed(MODE_KOSHER, global, emptyList()) { it == "com.a.ped" }
        assertEquals(listOf("com.b.hab"), pkgs(out))
    }

    @Test
    fun build_nuncaConstruyeLoProhibidoAunqueEsteHabilitado() {
        assertNull(PlayStoreCatalog.build(RawApp("com.android.vending", "Play", 1, status = "enabled"), extra = false))
        assertNull(PlayStoreCatalog.build(RawApp("com.ejemplo.locksuite", "LockSuite", status = "enabled"), extra = true))
    }

    @Test
    fun dnsContribution_soloContieneLoInstalable() {
        val global = listOf(
            appS("com.a.hab", "enabled"),
            appS("com.b.ped", "request"),
            appS("com.c.blo", "blocked")
        )
        val dns = PlayStoreCatalog.dnsContribution(PlayStoreCatalog.effective(MODE_LITE, global, emptyList()))
        assertEquals(setOf("com.a.hab"), dns.allowedPackages)
    }

    @Test
    fun buildAll_conservaElEstadoYSeSerializaConLosMismosCampos() {
        val raws = listOf(
            RawApp("com.a.hab", "A", 1, status = "enabled"),
            RawApp("com.b.ped", "B", 1, status = "request"),
            RawApp("com.c.blo", "C", 2, status = "blocked"),
            RawApp("com.d.malo", "D", 1, status = "quizas")
        )
        val out = PlayStoreCatalog.buildAll(raws, extra = false)
        assertEquals(listOf("com.a.hab", "com.b.ped", "com.c.blo"), pkgs(out))
        assertEquals(listOf("enabled", "request", "blocked"), out.map { it.status })
    }
}
