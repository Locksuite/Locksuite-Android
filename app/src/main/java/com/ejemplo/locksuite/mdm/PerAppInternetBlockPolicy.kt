package com.ejemplo.locksuite.mdm

import java.util.Locale

/**
 * Política de bloqueo de internet por app (B.127).
 *
 * El bloqueo genérico por UID (`ConnectivityManager.getConnectionOwnerUid()`)
 * resultó poco confiable en la práctica porque `netd` resuelve el DNS del sistema
 * desde su propio UID o mediante sockets desconectados, y un túnel solo-DNS no
 * intercepta conexiones por IP literal ni DNS encriptado (DoH/DoT).
 *
 * Por eso esta función queda restringida **exclusivamente** a Pulsar y Subway Surfers,
 * aplicando un bloqueo global por dominios conocidos en Capa 2 (VPN DNS) mientras la
 * política esté activa para la app correspondiente (funciona en Android 7–16), más
 * corte de datos móviles a nivel kernel en Android 9+ vía
 * `DevicePolicyManager.setMeteredDataDisabledPackages()`.
 *
 * ⚠️ NO agregar `fbcdn.net` (WhatsApp lo necesita en `WhitelistCatalog`), ni
 * `googleapis.com`, `gstatic.com`, `firebaseio.com` ni ningún dominio de
 * `WhitelistCatalog.INFRASTRUCTURE`.
 */
object PerAppInternetBlockPolicy {

    const val PULSAR_FREE = "com.rhmsoft.pulsar"
    const val PULSAR_PRO = "com.rhmsoft.pulsar.pro"
    const val SUBWAY_SURFERS = "com.kiloo.subwaysurf"

    val PULSAR_PACKAGES: Set<String> = setOf(PULSAR_FREE, PULSAR_PRO)
    val SUBWAY_SURFERS_PACKAGES: Set<String> = setOf(SUBWAY_SURFERS)
    val SUPPORTED_PACKAGES: Set<String> = PULSAR_PACKAGES + SUBWAY_SURFERS_PACKAGES

    /**
     * Dominios usados por Pulsar Music Player (gratis y Pro):
     * - Sitio y servicios del desarrollador (`rhmsoft.com`)
     * - Scrobbling y metadatos Last.fm (`audioscrobbler.com`, `last.fm`, `lst.fm`)
     * - Descarga automática de carátulas y fotos de artistas (Spotify primario + fuentes musicales)
     * - Telemetría puntual Firebase Analytics / Crashlytics (subdominios seguros)
     */
    val PULSAR_DOMAINS: Set<String> = setOf(
        // Desarrollador
        "rhmsoft.com",
        // Scrobbling y API musical Last.fm
        "last.fm",
        "audioscrobbler.com",
        "lst.fm",
        "lastfm.freetls.fastly.net",
        // Carátulas y fotos de artistas (fuente principal Spotify + fuentes habituales)
        "spotify.com",
        "scdn.co",
        "spotifycdn.com",
        "itunes.apple.com",
        "mzstatic.com",
        "deezer.com",
        "dzcdn.net",
        "discogs.com",
        "musicbrainz.org",
        "coverartarchive.org",
        "fanart.tv",
        "theaudiodb.com",
        "musixmatch.com",
        // Telemetría puntual (nunca googleapis.com ni firebaseio.com enteros)
        "app-measurement.com",
        "crashlytics.com",
        "crashlyticsreports-pa.googleapis.com",
        "firebase-settings.crashlytics.com",
        "firebaselogging-pa.googleapis.com"
    )

    /**
     * Dominios usados por Subway Surfers (`com.kiloo.subwaysurf`):
     * - Servidores SYBO / Kiloo (eventos en vivo, assets remotos, network-check, soporte Helpshift)
     * - SDKs de publicidad, mediación y telemetría verificados en el APK (Exodus Privacy + redes)
     */
    val SUBWAY_SURFERS_DOMAINS: Set<String> = setOf(
        // Backend del juego / live-ops / descarga de temporadas / soporte
        "sybo.net",
        "sybogames.com",
        "subwaysurfers.com",
        "kiloo.com",
        "kiloogames.com",
        "sybo.helpshift.com",
        "helpshift.com",
        // ironSource / LevelPlay
        "ironsrc.com",
        "ironsrc.mobi",
        "supersonicads.com",
        "supersonic.com",
        "isprog.com",
        // AppLovin / MAX
        "applovin.com",
        "applvn.com",
        // Unity Ads / Analytics
        "unity3d.com",
        "unity.com",
        // Google AdMob / DoubleClick / Google Ads
        "doubleclick.net",
        "doubleclick.com",
        "googlesyndication.com",
        "googleadservices.com",
        "admob.com",
        "2mdn.net",
        "adservice.google.com",
        "mobileads.google.com",
        // Mintegral
        "mintegral.com",
        "rayjump.com",
        "mtgglobals.com",
        "mbridge.com",
        // Pangle (ByteDance)
        "pangle.io",
        "pangleglobal.com",
        "pglstatp-toutiao.com",
        "byteoversea.com",
        "isnssdk.com",
        "bytedance.com",
        // InMobi
        "inmobi.com",
        "inmobicdn.net",
        "inmobicdn.com",
        "inmobi.cn",
        "inmobi.net",
        "inmobisdk-a.akamaihd.net",
        // Fyber / Digital Turbine
        "fyber.com",
        "inner-active.mobi",
        "digitalturbine.com",
        "dtscout.com",
        // Vungle / Liftoff
        "vungle.com",
        "liftoff.io",
        // Chartboost
        "chartboost.com",
        // Appodeal / BidMachine
        "appodeal.com",
        "appbaqend.com",
        "bidmachine.io",
        // PubNative
        "pubnative.net",
        // Amazon Ads
        "amazon-adsystem.com",
        // Moloco
        "moloco.com",
        "molocoads.com",
        // Tapjoy / Smaato / AdColony / Kidoz
        "tapjoy.com",
        "tapjoyads.com",
        "smaato.net",
        "adcolony.com",
        "kidoz.net",
        // SuperAwesome
        "superawesome.tv",
        "superawesome.com",
        // Singular attribution
        "singular.net",
        "sng.link",
        // Meta / Facebook Ads & Login SDK (SIN fbcdn.net para no afectar WhatsApp)
        "facebook.com",
        "facebook.net",
        // Telemetría puntual (nunca googleapis.com ni firebaseio.com enteros)
        "app-measurement.com",
        "crashlytics.com",
        "crashlyticsreports-pa.googleapis.com",
        "firebase-settings.crashlytics.com",
        "firebaselogging-pa.googleapis.com"
    )

    fun isSupported(packageName: String): Boolean = packageName in SUPPORTED_PACKAGES

    fun filterSupported(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (pkg in packages) {
            if (pkg in SUPPORTED_PACKAGES) out.add(pkg)
        }
        return out
    }

    fun domainsForPackage(packageName: String): Set<String> = when (packageName) {
        in PULSAR_PACKAGES -> PULSAR_DOMAINS
        in SUBWAY_SURFERS_PACKAGES -> SUBWAY_SURFERS_DOMAINS
        else -> emptySet()
    }

    fun domainsForPackages(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (pkg in packages) {
            out.addAll(domainsForPackage(pkg))
        }
        return out
    }

    fun normalizeDomain(domain: String): String =
        domain.trim().trim('.').lowercase(Locale.ROOT)

    /**
     * Comprueba coincidencia exacta o por subdominio caminando los sufijos en O(etiquetas).
     */
    fun matchesAnyDomain(domain: String, blockedDomains: Set<String>): Boolean {
        if (blockedDomains.isEmpty()) return false
        var candidate = normalizeDomain(domain)
        if (candidate.isEmpty()) return false
        while (true) {
            if (candidate in blockedDomains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot + 1 >= candidate.length) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /**
     * Si alguno de los [activeBlockedPackages] soportados reclama [domain], devuelve ese paquete;
     * si no corresponde bloquearlo, devuelve `null`.
     */
    fun matchedPackageForDomain(
        domain: String,
        activeBlockedPackages: Collection<String>
    ): String? {
        if (activeBlockedPackages.isEmpty()) return null
        val normalized = normalizeDomain(domain)
        if (normalized.isEmpty()) return null
        val active = filterSupported(activeBlockedPackages)
        if (active.isEmpty()) return null
        for (pkg in active) {
            if (matchesAnyDomain(normalized, domainsForPackage(pkg))) {
                return pkg
            }
        }
        return null
    }
}
