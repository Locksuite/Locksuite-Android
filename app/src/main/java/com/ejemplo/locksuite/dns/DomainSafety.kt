package com.ejemplo.locksuite.dns

/**
 * DomainSafety — qué dominios NO se aceptan como permiso cuando vienen de una fuente
 * editable (catálogo por dispositivo en Firebase, UI del celular, importación .txt/.docx).
 *
 * El Trie hereda subdominios, así que permitir una base de hosting o CDN compartido
 * (`cloudfront.net`, `amazonaws.com`, `web.app`, `googleapis.com`...) abriría cualquier
 * sitio alojado ahí con TLS válido. Un host concreto bajo esas bases sí se acepta
 * (`d1a3f4spazzrp4.cloudfront.net`, `apis.google.com`).
 *
 * Función pura sin dependencias de Android: se prueba directo en JVM.
 */
object DomainSafety {

    /** Bases de hosting/CDN multi-inquilino: nunca como permiso entero. */
    val HOSTING_BASES: Set<String> = setOf(
        // Google
        "googleapis.com", "storage.googleapis.com", "commondatastorage.googleapis.com",
        "firebasestorage.googleapis.com", "googleusercontent.com", "ggpht.com",
        "translate.goog", "sites.google.com", "script.google.com", "appspot.com",
        "web.app", "firebaseapp.com", "cloudfunctions.net", "run.app", "a.run.app",
        "blogspot.com", "firebaseio.com", "firebasedatabase.app",
        // Amazon
        "amazonaws.com", "s3.amazonaws.com", "cloudfront.net", "elasticbeanstalk.com",
        "amplifyapp.com", "on.aws",
        // Microsoft
        "azurewebsites.net", "azureedge.net", "blob.core.windows.net", "web.core.windows.net",
        "cloudapp.net", "azurestaticapps.net", "trafficmanager.net",
        // CDN
        "akamai.net", "akamaihd.net", "akamaized.net", "akamaiedge.net", "edgekey.net",
        "edgesuite.net", "fastly.net", "fastlylb.net", "global.ssl.fastly.net", "edgecompute.app",
        "b-cdn.net", "bunnycdn.com", "cdn77.org", "rsc.cdn77.org", "cdn77.com",
        // Plataformas de sitios / despliegue
        "vercel.app", "now.sh", "netlify.app", "netlify.com", "github.io",
        "githubusercontent.com", "gitlab.io", "bitbucket.io", "pages.dev", "workers.dev",
        "r2.dev", "trycloudflare.com", "herokuapp.com", "onrender.com", "railway.app",
        "fly.dev", "glitch.me", "repl.co", "replit.app", "replit.dev", "surge.sh",
        "deno.dev", "csb.app", "codesandbox.io", "stackblitz.io", "ngrok.io", "ngrok-free.app",
        "ngrok.app", "loca.lt", "duckdns.org", "wixsite.com", "wordpress.com", "weebly.com",
        "squarespace.com", "myshopify.com", "webflow.io", "framer.app", "framer.website",
        "notion.site", "carrd.co", "tumblr.com", "000webhostapp.com", "neocities.org",
        "pythonanywhere.com", "ipfs.io", "dweb.link", "cloudflare-ipfs.com"
    )

    /** Primera etiqueta típica de un sufijo público de dos niveles (`com.ar`, `co.il`). */
    private val GENERIC_SLD = setOf(
        "com", "net", "org", "gob", "gov", "edu", "ac", "co", "mil", "int", "nom",
        "ltd", "plc", "sch", "or", "ne", "go", "muni", "tur"
    )

    private fun cleanInput(raw: String?): String {
        var d = (raw ?: "").trim().lowercase()
        val schemeIdx = d.indexOf("://")
        if (schemeIdx >= 0) d = d.substring(schemeIdx + 3)
        d = d.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in d) d = d.substringAfterLast('@')
        d = d.substringBefore(':')
        while (d.startsWith("*.") || d.startsWith(".")) {
            d = if (d.startsWith("*.")) d.substring(2) else d.substring(1)
        }
        return d.trimEnd('.')
    }

    /** Motivo del rechazo, o `null` si el dominio se puede permitir. */
    fun rejectReason(raw: String?): String? {
        val input = (raw ?: "").trim().lowercase()
        if (input.isEmpty()) return "vacío"
        if (input.contains("*")) return "no se admiten wildcards (*)"
        val d = normalizeDomain(input).trimEnd('.')
        if (d.isEmpty()) return "vacío"
        if (d.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.') }) {
            return "caracteres inválidos"
        }
        val labels = d.split('.')
        if (labels.any { it.isEmpty() || it.startsWith("-") || it.endsWith("-") }) {
            return "etiqueta inválida"
        }
        if (labels.size < 2) return "es un TLD"
        if (labels.last().none { it in 'a'..'z' }) return "no es un dominio"
        if (labels.size == 2 && labels[0] in GENERIC_SLD && labels[1].length == 2) {
            return "sufijo público genérico"
        }
        if (labels.size == 2 && labels[0].startsWith("akamai")) return "base de CDN (Akamai)"
        if (d in HOSTING_BASES) return "base de hosting/CDN compartida"
        if (HOSTING_BASES.any { it.endsWith(".$d") }) return "contiene una base de hosting"
        return null
    }

    /** Limpia una URL o dominio pegado a mano antes de validarlo. Devuelve `Pair(domain, null)` o `Pair(null, reason)`. */
    fun cleanAndValidateAllow(raw: String?): Pair<String?, String?> {
        val cleaned = cleanInput(raw)
        if (cleaned.isEmpty()) return null to "vacío"
        val why = rejectReason(cleaned)
        return if (why != null) null to why else cleaned to null
    }

    fun isSafeToAllow(raw: String?): Boolean = rejectReason(raw) == null
}
