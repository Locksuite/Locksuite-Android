package com.ejemplo.locksuite.mdm

/**
 * SantanderBlockPolicy — Bloqueo quirúrgico de navegación externa dentro de Santander Argentina (Capa 3).
 *
 * Objetivo:
 * - Permitir TODA la operatoria bancaria legítima de Santander (`ar.com.santander.rio.mbanking`),
 *   incluyendo sus pantallas nativas, sus WebViews internos (Seguros, CEDIP, Comisiones, etc.),
 *   y la lectura/aceptación de *Términos y Condiciones* y *Políticas de Privacidad*.
 * - Bloquear únicamente cuando el usuario navega desde esos WebViews hacia afuera:
 *   1. Google Play Web (`play.google.com`), que no puede bloquearse por DNS sin romper
 *      actualizaciones de apps del dispositivo.
 *   2. Apple App Store Web (`apps.apple.com`).
 *   3. El portal público general de Santander (`www.santander.com.ar` / Centro de ayuda público),
 *      cuyo dominio no puede bloquearse por DNS porque la app comparte infraestructura con
 *      `santander.com.ar`.
 *
 * Función pura sin dependencias de Android, verificable con tests unitarios contra capturas reales.
 */
object SantanderBlockPolicy {

    const val PKG_SANTANDER = "ar.com.santander.rio.mbanking"
    const val KEY_ENABLED = "block_santander_external"

    /** Longitud máxima de un nodo de UI (botón, pestaña, enlace, título corto) vs. prosa legal larga. */
    const val MAX_CARACTERES_UI = 110

    enum class Veredicto {
        BLOQUEAR,
        PERMITIR
    }

    data class Retrato(
        val packageName: String,
        val windowClass: String = "",
        val tieneWebView: Boolean = false,
        val idsDeVista: List<String> = emptyList(),
        /** Textos y contentDescriptions normalizados (minúscula y sin tildes) con longitud <= [MAX_CARACTERES_UI]. */
        val textos: List<String> = emptyList()
    )

    private val MARCADORES_PLAY_TIENDA = listOf(
        "juegos", "aplicaciones", "libros", "ninos",
        "games", "apps", "books", "kids",
        "trailer", "para todos", "interaccion de usuarios",
        "esrb", "mil resenas", "acerca de este juego",
        "acerca de esta app", "seguridad de los datos"
    )

    private val MARCADORES_APP_STORE = listOf(
        // Inglés (apps.apple.com en EN)
        "arcade", "today", "categories",
        "iphone", "ipad", "mac", "vision", "watch",
        "age rating", "designed for ipad", "not verified for macos",
        "in-app purchases",
        // Español (apps.apple.com en ES; evita términos genéricos bancarios o "store" que
        // colisionarían con el enlace "App Store" del pie de Términos y Condiciones)
        "hoy", "categorias", "juegos", "apps",
        "clasificacion por edad", "disenado para ipad", "disenado para iphone",
        "no verificado para macos", "compras dentro de la app",
        "vista previa de app store", "capturas de pantalla",
        "historial de versiones", "calificaciones y resenas",
        "tienda de apps", "app store preview",
        // Portugués (apps.apple.com en PT)
        "hoje", "jogos",
        "classificacao etaria", "classificacao indicativa",
        "feito para ipad", "projetado para ipad",
        "nao verificado para macos", "compras dentro do app", "compras no app",
        "pre-visualizacao da app store", "capturas de tela",
        "historico de versoes", "avaliacoes e resenhas",
        "loja de apps"
    )

    /**
     * Etiquetas y botones exclusivos de la navegación del portal público `www.santander.com.ar`.
     * Ninguna de estas etiquetas aparece como nodo corto de UI en *Términos y Condiciones*
     * (cuyo pie solo dice `"¿En qué podemos ayudarte?"`, `"App Store"`, `"Google Play"`)
     * ni en *Nueva Política de Privacidad*.
     */
    private val SENALES_PORTAL_PUBLICO = listOf(
        "online banking",
        "hacete cliente ahora",
        "cajeros y sucursales",
        "beneficios y promociones",
        "centro de ayuda",
        "ir al centro de ayuda",
        "numeros utiles",
        "tus claves",
        "¡hola! ¿en que podemos ayudarte?",
        "hola! ¿en que podemos ayudarte?",
        "conoce nuestros productos y servicios",
        "prestamos prendarios"
    )

    fun evaluar(r: Retrato): Veredicto {
        if (r.packageName != PKG_SANTANDER || !r.tieneWebView) {
            return Veredicto.PERMITIR
        }
        if (esGooglePlayWeb(r.textos)) return Veredicto.BLOQUEAR
        if (esAppStoreWeb(r.textos)) return Veredicto.BLOQUEAR
        if (esPortalPublicoSantander(r.textos)) return Veredicto.BLOQUEAR
        return Veredicto.PERMITIR
    }

    fun motivo(r: Retrato): String {
        if (r.packageName != PKG_SANTANDER || !r.tieneWebView) {
            return "permitido: fuera de WebView de Santander"
        }
        if (esGooglePlayWeb(r.textos)) {
            return "santander webview: google play web"
        }
        if (esAppStoreWeb(r.textos)) {
            return "santander webview: app store web"
        }
        val senales = senalesPortalPublicoDetectadas(r.textos)
        if (senales.size >= 2) {
            return "santander webview: portal publico santander.com.ar (${senales.take(3).joinToString("+")})"
        }
        return "permitido: contenido interno o legal de Santander"
    }

    private fun esGooglePlayWeb(textos: List<String>): Boolean {
        val tieneGooglePlay = textos.any { it.contains("google play") || it.contains("play.google.com") }
        if (!tieneGooglePlay) return false
        var hits = 0
        val vistos = HashSet<String>(4)
        for (t in textos) {
            for (m in MARCADORES_PLAY_TIENDA) {
                if (vistos.contains(m)) continue
                val coincide = if (' ' in m) t.contains(m) else MercadoPagoOffersPolicy.contienePalabraCompleta(t, m)
                if (coincide) {
                    vistos.add(m)
                    hits++
                    if (hits >= 2) return true
                }
            }
        }
        return false
    }

    private fun esAppStoreWeb(textos: List<String>): Boolean {
        val tieneAppStore = textos.any { it.contains("app store") || it.contains("apps.apple.com") }
        if (!tieneAppStore) return false
        var hits = 0
        val vistos = HashSet<String>(4)
        for (t in textos) {
            for (m in MARCADORES_APP_STORE) {
                if (vistos.contains(m)) continue
                val coincide = if (' ' in m) t.contains(m) else MercadoPagoOffersPolicy.contienePalabraCompleta(t, m)
                if (coincide) {
                    vistos.add(m)
                    hits++
                    if (hits >= 2) return true
                }
            }
        }
        return false
    }

    private fun senalesPortalPublicoDetectadas(textos: List<String>): List<String> {
        val vistas = ArrayList<String>(4)
        for (t in textos) {
            for (s in SENALES_PORTAL_PUBLICO) {
                // Evitar doble conteo entre variantes de ayuda/hola similares
                val claveCanonica = when (s) {
                    "ir al centro de ayuda" -> "centro de ayuda"
                    "hola! ¿en que podemos ayudarte?" -> "¡hola! ¿en que podemos ayudarte?"
                    else -> s
                }
                if (vistas.contains(claveCanonica)) continue
                if (t.contains(s)) {
                    vistas.add(claveCanonica)
                }
            }
        }
        return vistas
    }

    private fun esPortalPublicoSantander(textos: List<String>): Boolean =
        senalesPortalPublicoDetectadas(textos).size >= 2

    fun normalizar(raw: CharSequence): String =
        MercadoPagoOffersPolicy.plegarAcentos(raw).trim()
}
