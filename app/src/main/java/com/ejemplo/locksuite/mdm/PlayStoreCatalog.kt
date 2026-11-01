package com.ejemplo.locksuite.mdm

/**
 * PlayStoreCatalog — modelo PURO de la "Tienda Play" (29/10/2026, B.101).
 *
 * Sin Android, sin `org.json` y sin estado: todo lo que decide QUÉ se ofrece en la tienda
 * está acá, para poder probarlo con JUnit sin emulador (el JSON de la nube se traduce a
 * [RawApp] en `PlayStoreManager`, que es la única capa que toca Android).
 *
 * ## La idea (pedido textual del dueño, resumido)
 * La tienda es una lista de paquetes que el administrador permite. Al tocar "Instalar" la
 * app abre Google Play OFICIAL y instala ese paquete con la pantalla tapada, usando
 * exactamente el mismo mecanismo que la actualización de apps (`UpdateFlowManager`). Por
 * eso el filtro no se puede esquivar: el usuario nunca ve Play ni elige qué instalar.
 *
 * ## Tres niveles ACUMULATIVOS (global, para todos los equipos)
 *   1 = Kosher (pocas apps)  ⊂  2 = Trabajo (algunas más)  ⊂  3 = Lite (todavía más)
 * "Acumulativo" quiere decir que el modo Trabajo muestra los niveles 1 y 2, y el modo
 * Lite muestra los 1, 2 y 3. Una app se carga UNA vez, en el nivel más bajo en el que
 * debe aparecer; no hace falta repetirla en los de arriba.
 *
 * ## Modo por equipo (lo que el administrador elige en Políticas o fija un perfil)
 *   cerrada (no hay tienda) · kosher (nivel 1) · trabajo (niveles 1–2) · lite (niveles 1–3)
 *
 * ## Acceso por paquete (qué pasa con la app una vez instalada)
 *   full    → permitida completa.
 *   noweb   → permitida SIN web: se le aplica el bloqueo de WebView por app de siempre
 *             (`WebViewBlockManager`) y en la sección Aplicaciones aparece como "Sin web".
 *   domains → permitida con dominios propios: el administrador lista los que se permiten
 *             y/o los que se prohíben; entran a la lista blanca como una app personalizada.
 *
 * ## Estado de cada app (30/10/2026) — lo que el administrador decide desde el panel
 *   enabled → HABILITADA: se ofrece y se puede instalar.
 *   request → SOLO A PEDIDO: se muestra con "Pedir"; no se puede instalar hasta que el
 *             administrador apruebe el pedido de ESE usuario (la aprobación la guarda como
 *             paquete extra habilitado de ese equipo).
 *   blocked → BLOQUEADA: no se muestra ni se instala (y si la tienda la instaló, se pausa).
 * Un estado ausente cuenta como `enabled`; uno desconocido DESCARTA la entrada (igual que
 * un acceso desconocido: nunca se interpreta como lo más abierto). En un paquete extra de
 * un equipo el estado manda sobre el global de ese paquete: `blocked` en el extra bloquea
 * una app global SOLO para ese usuario, y `enabled` en el extra aprueba una app "a pedido".
 *
 * ## Reglas que no se negocian (fallar cerrado)
 *  - Modo desconocido, vacío o ausente = CERRADA. Nunca se abre por un dato roto.
 *  - Un nivel fuera de 1..3, un paquete mal escrito o un acceso desconocido descarta la
 *    entrada (el acceso desconocido NO se interpreta como "completo": se descarta).
 *  - [NEVER_OFFERED]: ni el panel puede ofrecer LockSuite, Google Play, Play Services,
 *    los instaladores ni Ajustes.
 *  - Los "paquetes extra" de un equipo solo valen mientras la tienda está abierta: con la
 *    tienda cerrada no hay tienda, tenga extras o no.
 *  - Lo que el llamador marque como prohibido (`isBlocked`: la lista blanca lo prohibió, la
 *    política "bloquear populares no kosher" está prendida, etc.) NO se ofrece.
 */
object PlayStoreCatalog {

    // ── Modos ───────────────────────────────────────────────────────────────────

    const val MODE_CLOSED = "cerrada"
    const val MODE_KOSHER = "kosher"
    const val MODE_WORK = "trabajo"
    const val MODE_LITE = "lite"

    /** En el orden en que se muestran (de más cerrada a más abierta). */
    val MODES: List<String> = listOf(MODE_CLOSED, MODE_KOSHER, MODE_WORK, MODE_LITE)

    /** Lo que rige si nadie eligió nada: sin tienda. */
    const val DEFAULT_MODE = MODE_CLOSED

    // ── Niveles del catálogo ────────────────────────────────────────────────────

    const val TIER_KOSHER = 1
    const val TIER_WORK = 2
    const val TIER_LITE = 3

    // ── Acceso por paquete ──────────────────────────────────────────────────────

    const val ACCESS_FULL = "full"
    const val ACCESS_NO_WEB = "noweb"
    const val ACCESS_DOMAINS = "domains"
    val ACCESS_TYPES: List<String> = listOf(ACCESS_FULL, ACCESS_NO_WEB, ACCESS_DOMAINS)

    // ── Estado por paquete ──────────────────────────────────────────────────────

    const val STATUS_ENABLED = "enabled"
    const val STATUS_REQUEST = "request"
    const val STATUS_BLOCKED = "blocked"
    val STATUSES: List<String> = listOf(STATUS_ENABLED, STATUS_REQUEST, STATUS_BLOCKED)

    // ── Topes (la config viaja por Firebase y se lee en equipos lentos) ──────────

    const val MAX_APPS = 400
    const val MAX_EXTRAS = 100
    const val MAX_DOMAINS_PER_LIST = 60
    const val MAX_LABEL_CHARS = 60

    /**
     * Paquetes que jamás se ofrecen, ni en el panel ni en el equipo. Instalar o
     * "actualizar" cualquiera de estos desde la tienda rompería la propia protección:
     * LockSuite, Google Play (si estuviera en la lista, la tienda se podría usar para
     * reinstalar/actualizar Play a una versión que no controlamos), los servicios de
     * Google, los instaladores de paquetes y Ajustes.
     */
    val NEVER_OFFERED: Set<String> = setOf(
        "com.ejemplo.locksuite",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.providers.downloads",
        "com.google.android.apps.work.clouddpc"
    )

    // ── Modelo ──────────────────────────────────────────────────────────────────

    /**
     * Una app de la tienda ya validada y limpia.
     *
     * @param tier   1..3 para el catálogo global; 0 para un "paquete extra" de un equipo
     *               (los extras no tienen nivel: valen siempre que la tienda esté abierta).
     * @param allow  dominios permitidos (solo con acceso [ACCESS_DOMAINS]; vacío si no).
     * @param block  dominios prohibidos (solo con acceso [ACCESS_DOMAINS]; vacío si no).
     * @param iconUrl ícono para cuando la app todavía no está instalada (host de Google).
     */
    data class StoreApp(
        val packageName: String,
        val label: String,
        val tier: Int,
        val access: String,
        val allow: List<String>,
        val block: List<String>,
        val iconUrl: String?,
        val extra: Boolean,
        /** [STATUS_ENABLED] / [STATUS_REQUEST] / [STATUS_BLOCKED]. */
        val status: String = STATUS_ENABLED
    )

    /** Lo que llega de la nube, sin validar (todo opcional, todo texto). */
    data class RawApp(
        val packageName: String?,
        val label: String? = null,
        val tier: Int? = null,
        val access: String? = null,
        val allow: List<String>? = null,
        val block: List<String>? = null,
        val iconUrl: String? = null,
        /** `null` = ausente (cuenta como habilitada); texto desconocido = se descarta. */
        val status: String? = null
    )

    // ── Modos y niveles ─────────────────────────────────────────────────────────

    /** Modo válido o, si no se reconoce, el que rige por defecto (CERRADA). */
    fun normalizeMode(raw: String?): String {
        val v = raw?.trim()?.lowercase() ?: return DEFAULT_MODE
        return if (v in MODES) v else DEFAULT_MODE
    }

    /**
     * Modo para un perfil automático / preset: `null` significa "no tocar" (clave
     * ausente o valor que no se reconoce). Es la misma regla de `importPolicyPresetJson`
     * ("clave ausente = no se toca"): un perfil viejo que no habla de la tienda no debe
     * cerrarla ni abrirla.
     */
    fun modeFromProfile(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return if (v in MODES) v else null
    }

    /** Hasta qué nivel llega un modo. 0 = cerrada (y todo lo desconocido). */
    fun maxTier(mode: String?): Int = when (mode?.trim()?.lowercase()) {
        MODE_KOSHER -> TIER_KOSHER
        MODE_WORK -> TIER_WORK
        MODE_LITE -> TIER_LITE
        else -> 0
    }

    fun isOpen(mode: String?): Boolean = maxTier(mode) > 0

    // ── Validación de campos ────────────────────────────────────────────────────

    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val DOMAIN_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /** Hosts desde los que se aceptan íconos (el CDN de Google Play). */
    private val ICON_HOST_SUFFIXES = listOf(".googleusercontent.com", ".ggpht.com", ".gstatic.com")

    fun isValidPackageName(pkg: String?): Boolean =
        pkg != null && pkg.length <= 200 && PACKAGE_REGEX.matches(pkg)

    /** `null` = acceso desconocido (la entrada se descarta, nunca se asume "completo"). */
    fun parseAccess(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return if (v in ACCESS_TYPES) v else null
    }

    /** `null` = estado desconocido (la entrada se descarta). Ausente se resuelve en [build]. */
    fun parseStatus(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return if (v in STATUSES) v else null
    }

    /**
     * Dominio limpio o `null` si no sirve. Acepta lo que un humano pega en el panel
     * (`https://www.Ejemplo.com/ruta`, `*.ejemplo.com`, `ejemplo.com.`) y lo reduce a
     * `www.ejemplo.com`. Exige al menos un punto: una etiqueta sola (`com`) cerraría o
     * abriría un TLD entero.
     */
    fun cleanDomain(raw: String?): String? {
        if (raw == null) return null
        var d = raw.trim().lowercase()
        if (d.isEmpty()) return null
        val scheme = d.indexOf("://")
        if (scheme >= 0) d = d.substring(scheme + 3)
        d = d.substringBefore('/').substringBefore('?').substringBefore('#')
        d = d.substringAfterLast('@') // usuario@host
        d = d.substringBefore(':')    // puerto
        while (d.startsWith("*.") || d.startsWith(".")) d = d.removePrefix("*.").removePrefix(".")
        d = d.trimEnd('.')
        if (d.length < 4 || d.length > 253) return null
        if (!DOMAIN_REGEX.matches(d)) return null
        // La última etiqueta (TLD) tiene que tener una letra: "1.2.3.4" no es un dominio.
        return if (d.substringAfterLast('.').any { it.isLetter() }) d else null
    }

    /** Lista de dominios limpia, sin repetidos y con tope. Los inválidos se descartan. */
    fun cleanDomains(raw: List<String>?): List<String> {
        if (raw == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (item in raw) {
            val d = cleanDomain(item) ?: continue
            out.add(d)
            if (out.size >= MAX_DOMAINS_PER_LIST) break
        }
        return out.toList()
    }

    /** Nombre para mostrar: el escrito, o uno razonable derivado del paquete. */
    fun cleanLabel(raw: String?, packageName: String): String {
        val cleaned = raw?.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_LABEL_CHARS)
            ?.trim()
        if (!cleaned.isNullOrEmpty()) return cleaned
        return fallbackLabel(packageName)
    }

    /** `com.waze.navigation` → `Navigation`. Solo para cuando nadie escribió un nombre. */
    fun fallbackLabel(packageName: String): String {
        val last = packageName.substringAfterLast('.').ifEmpty { packageName }
        val words = last.split('_').filter { it.isNotEmpty() }
        val text = words.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        return text.ifEmpty { packageName }
    }

    /** Solo https hacia el CDN de Google; cualquier otra cosa se descarta. */
    fun cleanIconUrl(raw: String?): String? {
        val url = raw?.trim() ?: return null
        if (url.length > 600 || !url.startsWith("https://")) return null
        if (url.any { it.isWhitespace() || it == '"' || it == '\'' || it == '<' || it == '>' }) return null
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?')
        if (authority.contains('@')) return null // usuario@host: ambiguo, se descarta
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        return if (ICON_HOST_SUFFIXES.any { host.endsWith(it) }) url else null
    }

    // ── Construcción ────────────────────────────────────────────────────────────

    /**
     * Valida y limpia una entrada de la nube. `null` = se descarta (paquete inválido,
     * prohibido, nivel fuera de rango en el catálogo global o acceso desconocido).
     *
     * Los dominios solo se conservan con acceso [ACCESS_DOMAINS]; con los otros dos se
     * vacían a propósito para que no quede configuración escondida que el administrador no
     * ve (el panel solo muestra los campos de dominios con ese acceso).
     */
    fun build(raw: RawApp, extra: Boolean): StoreApp? {
        val pkg = raw.packageName?.trim() ?: return null
        if (!isValidPackageName(pkg) || pkg in NEVER_OFFERED) return null
        val access = parseAccess(raw.access ?: ACCESS_FULL) ?: return null
        val status = parseStatus(raw.status ?: STATUS_ENABLED) ?: return null
        val tier = if (extra) 0 else (raw.tier ?: return null)
        if (!extra && tier !in TIER_KOSHER..TIER_LITE) return null
        val withDomains = access == ACCESS_DOMAINS
        return StoreApp(
            packageName = pkg,
            label = cleanLabel(raw.label, pkg),
            tier = tier,
            access = access,
            allow = if (withDomains) cleanDomains(raw.allow) else emptyList(),
            block = if (withDomains) cleanDomains(raw.block) else emptyList(),
            iconUrl = cleanIconUrl(raw.iconUrl),
            extra = extra,
            status = status
        )
    }

    /** Valida una lista completa. Se queda con los primeros [limit] válidos. */
    fun buildAll(raws: List<RawApp>, extra: Boolean, limit: Int = if (extra) MAX_EXTRAS else MAX_APPS): List<StoreApp> {
        val out = LinkedHashMap<String, StoreApp>()
        for (r in raws) {
            val app = build(r, extra) ?: continue
            val prev = out[app.packageName]
            // Un mismo paquete dos veces en el catálogo (no debería pasar: en Firebase la
            // clave es el paquete): gana el nivel MÁS ALTO, o sea el que se ve en menos
            // modos. Fallar cerrado.
            if (prev == null || app.tier > prev.tier) out[app.packageName] = app
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    // ── Qué se ofrece ───────────────────────────────────────────────────────────

    /**
     * Junta el catálogo global con los extras del equipo para el modo dado, SIN mirar el
     * estado todavía: el extra del mismo paquete reemplaza a la entrada global (la más
     * específica manda, igual que `devices/<id>/appPolicy` sobre `globalSettings/whitelist`),
     * y recién después se descartan las bloqueadas.
     */
    private fun merged(
        mode: String?,
        global: Collection<StoreApp>,
        extras: Collection<StoreApp>
    ): Collection<StoreApp> {
        val limit = maxTier(mode)
        if (limit <= 0) return emptyList()
        val byPackage = LinkedHashMap<String, StoreApp>()
        for (app in global) {
            if (app.tier in TIER_KOSHER..limit) byPackage[app.packageName] = app
        }
        for (app in extras) byPackage[app.packageName] = app
        return byPackage.values
    }

    private val BY_LABEL = compareBy<StoreApp>({ it.label.lowercase() }, { it.packageName })

    /**
     * Lo que la tienda MUESTRA en un equipo: las habilitadas (con "Instalar") y las "solo a
     * pedido" (con "Pedir"). Las bloqueadas no se muestran. Con la tienda cerrada devuelve
     * vacío SIEMPRE, aunque haya extras.
     *
     * @param mode      modo del equipo (cualquier valor; lo desconocido es CERRADA).
     * @param global    catálogo global (con nivel).
     * @param extras    paquetes extra de ese equipo (sin nivel).
     * @param isBlocked paquetes que no se pueden ofrecer por una regla del equipo.
     */
    fun listed(
        mode: String?,
        global: Collection<StoreApp>,
        extras: Collection<StoreApp>,
        isBlocked: (String) -> Boolean = { false }
    ): List<StoreApp> =
        merged(mode, global, extras)
            .filter { it.status != STATUS_BLOCKED }
            .filter { it.packageName !in NEVER_OFFERED && !isBlocked(it.packageName) }
            .sortedWith(BY_LABEL)

    /**
     * Las apps que la tienda ofrece AHORA para INSTALAR en un equipo: las habilitadas de lo
     * que [listed] muestra. Es lo que cuenta para todo lo que no sea la lista de la
     * pantalla: permiso de instalar, lista blanca, kiosco, pausa de lo ya instalado.
     * Una app "solo a pedido" NO está acá hasta que se apruebe.
     */
    fun effective(
        mode: String?,
        global: Collection<StoreApp>,
        extras: Collection<StoreApp>,
        isBlocked: (String) -> Boolean = { false }
    ): List<StoreApp> =
        listed(mode, global, extras, isBlocked).filter { it.status == STATUS_ENABLED }

    /** ¿Este paquete se puede instalar desde la tienda ahora mismo? */
    fun isOffered(packageName: String?, effective: Collection<StoreApp>): Boolean =
        packageName != null && effective.any { it.packageName == packageName }

    // ── Lista blanca (DNS) ──────────────────────────────────────────────────────

    /**
     * Dominios propios de una app con acceso [ACCESS_DOMAINS]. Es lo que
     * `WhitelistManager.reload()` suma a las apps personalizadas del panel.
     */
    data class DomainLists(val allow: List<String>, val block: List<String>)

    /** Lo que la tienda aporta a la lista blanca. */
    data class DnsContribution(
        /** Apps ofrecidas: cuentan como "permitidas" (salvo que la lista blanca las haya prohibido). */
        val allowedPackages: Set<String>,
        /** Dominios propios (solo apps con acceso `domains`). */
        val domainLists: Map<String, DomainLists>
    )

    fun dnsContribution(apps: Collection<StoreApp>): DnsContribution {
        val allowed = LinkedHashSet<String>()
        val lists = LinkedHashMap<String, DomainLists>()
        for (app in apps) {
            allowed.add(app.packageName)
            if (app.access == ACCESS_DOMAINS && (app.allow.isNotEmpty() || app.block.isNotEmpty())) {
                lists[app.packageName] = DomainLists(app.allow, app.block)
            }
        }
        return DnsContribution(allowed, lists)
    }

    // ── "Sin web" ───────────────────────────────────────────────────────────────

    /** Paquetes cuya web hay que bloquear (acceso `noweb`). */
    fun noWebPackages(apps: Collection<StoreApp>): Set<String> =
        apps.filter { it.access == ACCESS_NO_WEB }.map { it.packageName }.toSet()

    /**
     * Plan para llevar el bloqueo de WebView al estado que pide la tienda SIN pisar lo que
     * el administrador hizo a mano.
     *
     * La tienda solo "es dueña" de un bloqueo si fue ella quien lo encendió. Si la app ya
     * tenía el bloqueo puesto por el administrador, queda como estaba y la tienda no lo
     * reclama (así, al cambiarle el acceso a "completa", no se le quita un bloqueo ajeno).
     *
     * @param desired       paquetes que deben quedar sin web (acceso `noweb` vigente)
     * @param currentlyBlocked paquetes con bloqueo de WebView encendido ahora
     * @param owned         paquetes cuyo bloqueo encendió la tienda antes
     */
    data class WebPlan(val toBlock: Set<String>, val toUnblock: Set<String>, val ownedAfter: Set<String>)

    fun planWebBlocks(desired: Set<String>, currentlyBlocked: Set<String>, owned: Set<String>): WebPlan {
        val toBlock = LinkedHashSet<String>()
        val toUnblock = LinkedHashSet<String>()
        val ownedAfter = LinkedHashSet<String>()
        for (pkg in desired) {
            if (pkg !in currentlyBlocked) {
                toBlock.add(pkg)
                ownedAfter.add(pkg)
            } else if (pkg in owned) {
                ownedAfter.add(pkg)
            }
            // Ya bloqueada por el administrador: queda como está y no la reclamamos.
        }
        for (pkg in owned) {
            if (pkg in desired) continue
            if (pkg in currentlyBlocked) toUnblock.add(pkg)
            // Si ya no estaba bloqueada, solo se suelta la propiedad (no está en ownedAfter).
        }
        return WebPlan(toBlock, toUnblock, ownedAfter)
    }

    // ── Apps instaladas por la tienda que dejan de estar permitidas ─────────────

    /**
     * Qué hacer con las apps que la tienda instaló cuando cambia lo que se ofrece.
     *
     * Principio: lo instalado desde la tienda solo funciona mientras la tienda lo ofrezca.
     * Si el modo baja (la gracia terminó, el administrador cerró la tienda, un paquete
     * extra se quitó) esas apps se PAUSAN (suspendidas, nunca desinstaladas: los datos del
     * usuario quedan y vuelven al subir el modo).
     *
     * Solo se toca lo que la tienda instaló (`installedByStore`): una app que ya estaba
     * en el equipo no se pausa nunca por estar en esta lista.
     *
     * @param offered          paquetes que la tienda ofrece ahora
     * @param installedByStore paquetes que la tienda instaló alguna vez (y siguen en el equipo)
     * @param pausedByStore    paquetes que la tienda pausó antes
     * @param keepPaused       paquetes que NO se deben reanudar aunque vuelvan a estar ofrecidos
     *                         (los que el administrador suspendió/prohibió por su cuenta)
     */
    data class PausePlan(val toPause: Set<String>, val toResume: Set<String>, val pausedAfter: Set<String>)

    fun planPause(
        offered: Set<String>,
        installedByStore: Set<String>,
        pausedByStore: Set<String>,
        keepPaused: Set<String> = emptySet()
    ): PausePlan {
        val toPause = LinkedHashSet<String>()
        val toResume = LinkedHashSet<String>()
        val pausedAfter = LinkedHashSet<String>()
        for (pkg in installedByStore) {
            if (pkg in offered) {
                if (pkg in pausedByStore && pkg !in keepPaused) toResume.add(pkg)
                else if (pkg in pausedByStore) pausedAfter.add(pkg)
            } else {
                if (pkg !in pausedByStore) toPause.add(pkg)
                pausedAfter.add(pkg)
            }
        }
        // Lo que figuraba pausado pero ya no está instalado: se suelta.
        return PausePlan(toPause, toResume, pausedAfter)
    }
}
