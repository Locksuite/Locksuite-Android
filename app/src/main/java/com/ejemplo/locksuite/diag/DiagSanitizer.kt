package com.ejemplo.locksuite.diag

/**
 * LIMPIADOR DE DATOS PERSONALES del registro de diagnóstico (6/11/2026, B.110).
 *
 * Todo texto que entra al registro pasa por acá ANTES de guardarse en el celular, y de ahí
 * sube a Firebase. Es lo que permite prometerle al usuario «no se manda nada tuyo»:
 * el registro es técnico (qué servicio se cayó, qué error dio), nunca de contenido.
 *
 * ── QUÉ SE SACA ──────────────────────────────────────────────────────────────────────
 *  • Direcciones web (`https://…`): se conserva solo el SITIO cuando es de LockSuite/Google
 *    (`firebaseio.com`, `googleapis.com`…) —sirve para diagnosticar— y sin ruta ni
 *    parámetros. Cualquier otro sitio queda como `<url>`.
 *  • Nombres de sitio que el sistema mete en errores de red (`Unable to resolve host "x"`,
 *    `UnknownHostException: x`) salvo los de LockSuite/Google.
 *  • Direcciones `content://` y `file://`, y rutas de almacenamiento (`/storage/…`, `/sdcard…`).
 *  • Correos (también el de la cuenta de Google que a veces trae una excepción).
 *  • Tokens de FCM, hashes largos y cualquier cadena de 40+ caracteres sin puntos.
 *  • Teléfonos (con `+`, con separadores, o 10 a 15 dígitos pegados), MAC, `ssid=…`
 *    y las IP públicas (las de red local se conservan: sirven para ver la red).
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • NO se quitan los nombres de clase ni de método (`com.ejemplo.locksuite.service.…`) ni
 *    los números de línea: son justamente lo que permite diagnosticar. Por eso la regla de
 *    «cadena larga» exige 40+ caracteres SIN puntos: un nombre de clase tiene puntos.
 *  • NO se quitan números sueltos (`pid=1234`, `elapsedMs=12000`, `uid=10234`): los 10 a 15
 *    dígitos solo se tratan como teléfono cuando NO van pegados a `=`, `:`, `/`, `.` o a una
 *    letra. Un `ts=1730851200000` queda intacto.
 *  • El límite de largo se aplica al FINAL, después de limpiar: si se cortara antes, un
 *    correo partido por la mitad ya no coincidiría con el patrón y se colaría.
 *  • Esto es una red de seguridad, NO una excusa para registrar contenido: el código que
 *    llama a `DiagLog` no debe pasar dominios que visitó el usuario, textos de pantalla ni
 *    nombres de contactos. Los que llaman a `DiagLog` se revisaron con ese criterio.
 *
 * Funciones puras (sin Android): las prueba `DiagSanitizerTest`.
 */
object DiagSanitizer {

    /** Largo máximo de un mensaje de una línea. */
    const val MAX_MSG = 420
    /** Largo máximo de un mensaje con stack trace. */
    const val MAX_TRACE = 1800
    private const val MAX_TRACE_LINES = 14
    private const val MAX_LINE = 240
    /** Se corta la entrada antes de aplicar patrones, para no gastar CPU en un texto monstruoso. */
    private const val MAX_INPUT = 8000

    /** Sitios de LockSuite/Google cuyo NOMBRE sí ayuda a diagnosticar (nunca su ruta). */
    private val HOSTS_OK = listOf(
        "googleapis.com", "firebaseio.com", "firebaseapp.com", "web.app", "cloudfunctions.net",
        "run.app", "gstatic.com", "google.com", "github.com", "githubusercontent.com"
    )

    private val URL = Regex("""(?i)\b(?:https?|ftps?|wss?)://([^\s/:?#"'<>)\]]+)(?::\d+)?[^\s"'<>)\]]*""")
    private val CONTENT_URI = Regex("""(?i)\b(content|file)://[^\s"'<>)\]]*""")
    private val STORAGE_PATH = Regex("""(?i)(?:/storage/|/sdcard|/mnt/(?:sdcard|media_rw|user)/)[^\s"'<>)\]]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
    private val FCM_TOKEN = Regex("""[A-Za-z0-9_\-]{11,}:[A-Za-z0-9_\-]{40,}""")
    private val MAC = Regex("""(?i)\b(?:[0-9a-f]{2}[:\-]){5}[0-9a-f]{2}\b""")
    private val SSID = Regex("""(?i)\b(b?ssid)\s*[=:]\s*(?:"[^"]*"|[^\s,;]+)""")
    private val RESOLVE_HOST = Regex("""(?i)(resolve host\s*)"([^"]*)"""")
    private val UNKNOWN_HOST = Regex("""(?i)(UnknownHostException:\s*)([^\s:,;]+)""")
    private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_\-]{40,}""")
    private val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val PHONE_PLUS = Regex("""\+\d[\d\s().\-]{6,}\d""")
    private val PHONE_GROUPS = Regex("""(?<![\d.])\d{2,4}[\s.\-]\d{3,4}[\s.\-]\d{3,4}(?![\d.])""")
    private val PHONE_PLAIN = Regex("""(?<![\w=.:/\-])\d{10,15}(?![\w.])""")

    private fun hostIsOk(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return HOSTS_OK.any { h == it || h.endsWith(".$it") }
    }

    private fun isPrivateIp(a: Int, b: Int): Boolean =
        a == 10 || a == 127 || a == 0 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)

    /**
     * Limpia un texto de una línea (o con pocas líneas). Nunca lanza excepciones.
     * Los saltos de línea se CONSERVAN (los stack traces los necesitan); los demás
     * caracteres de control se vuelven espacios.
     */
    fun sanitize(raw: String?, max: Int = MAX_MSG): String {
        if (raw.isNullOrEmpty()) return ""
        return try {
            var s = if (raw.length > MAX_INPUT) raw.substring(0, MAX_INPUT) else raw
            s = stripControl(s)
            s = URL.replace(s) { m ->
                val host = m.groupValues[1]
                if (hostIsOk(host)) m.value.substringBefore("://") + "://" + host.lowercase() + "/…" else "<url>"
            }
            s = CONTENT_URI.replace(s) { m -> m.groupValues[1].lowercase() + "://<ruta>" }
            s = STORAGE_PATH.replace(s, "<ruta-almacenamiento>")
            s = EMAIL.replace(s, "<email>")
            s = FCM_TOKEN.replace(s, "<token>")
            s = MAC.replace(s, "<mac>")
            s = SSID.replace(s) { m -> m.groupValues[1] + "=<red>" }
            s = RESOLVE_HOST.replace(s) { m ->
                m.groupValues[1] + "\"" + (if (hostIsOk(m.groupValues[2])) m.groupValues[2] else "<sitio>") + "\""
            }
            s = UNKNOWN_HOST.replace(s) { m ->
                m.groupValues[1] + (if (hostIsOk(m.groupValues[2])) m.groupValues[2] else "<sitio>")
            }
            s = LONG_HEX.replace(s, "<hex>")
            s = LONG_TOKEN.replace(s, "<token>")
            s = IPV4.replace(s) { m ->
                val o = (1..4).map { m.groupValues[it].toInt() }
                if (o.any { it > 255 } || isPrivateIp(o[0], o[1])) m.value else "<ip>"
            }
            s = PHONE_PLUS.replace(s, "<tel>")
            s = PHONE_GROUPS.replace(s, "<tel>")
            s = PHONE_PLAIN.replace(s, "<tel>")
            s = s.trim()
            if (s.length > max) s.substring(0, (max - 1).coerceAtLeast(0)) + "…" else s
        } catch (_: Throwable) {
            // Ante la duda, NO se guarda el texto crudo.
            "<mensaje no limpiable>"
        }
    }

    /**
     * Limpia un texto de varias líneas (un stack trace): hasta 14 líneas de 240 caracteres
     * y 1.800 en total. Cada línea se limpia por separado.
     */
    fun sanitizeTrace(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val lines = raw.lineSequence().filter { it.isNotBlank() }.take(MAX_TRACE_LINES + 1).toList()
        val sb = StringBuilder()
        var i = 0
        for (line in lines) {
            if (i >= MAX_TRACE_LINES) { sb.append("\n… (más líneas)"); break }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(sanitize(line, MAX_LINE))
            i++
        }
        val out = sb.toString()
        return if (out.length > MAX_TRACE) out.substring(0, MAX_TRACE - 1) + "…" else out
    }

    /**
     * Limpia un bloque largo de varias líneas (las secciones de un diagnóstico completo):
     * cada línea se limpia por separado con tope de [maxLine] caracteres y el total se corta
     * en [maxChars], avisándolo al final. A diferencia de [sanitizeTrace] no limita la
     * cantidad de líneas.
     */
    fun sanitizeBlock(raw: String?, maxChars: Int, maxLine: Int = 300): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            val clean = sanitize(line, maxLine)
            if (sb.length + clean.length + 1 > maxChars) {
                sb.append("\n… (recortado)")
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(clean)
        }
        return sb.toString()
    }

    /**
     * Describe una excepción en texto: `clase: mensaje`, sus primeros [maxFrames] frames y
     * las causas encadenadas (hasta 3, con 3 frames cada una). Ya viene limpia.
     */
    fun describe(t: Throwable?, maxFrames: Int = 8): String {
        if (t == null) return ""
        return try {
            val sb = StringBuilder()
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < 4) {
                if (depth > 0) sb.append("\nCaused by: ")
                sb.append(cur.javaClass.name)
                val m = cur.message
                if (!m.isNullOrBlank()) sb.append(": ").append(m.replace('\n', ' '))
                val frames = cur.stackTrace
                val limit = if (depth == 0) maxFrames else 3
                for (i in 0 until minOf(limit, frames.size)) sb.append("\n  at ").append(frames[i].toString())
                if (frames.size > limit) sb.append("\n  … +").append(frames.size - limit).append(" más")
                val next = cur.cause
                cur = if (next === cur) null else next
                depth++
            }
            sanitizeTrace(sb.toString())
        } catch (_: Throwable) {
            t.javaClass.name
        }
    }

    /** Primera línea de una excepción: `clase: mensaje` (útil como título en el panel). */
    fun headline(t: Throwable?): String {
        if (t == null) return ""
        val m = t.message
        return sanitize(t.javaClass.name + (if (m.isNullOrBlank()) "" else ": " + m.replace('\n', ' ')), 220)
    }

    private fun stripControl(s: String): String {
        var needs = false
        for (ch in s) {
            if (ch < ' ' && ch != '\n' && ch != '\t' || ch == '\u007f') { needs = true; break }
        }
        if (!needs) return s
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch < ' ' && ch != '\n' && ch != '\t' || ch == '\u007f') ' ' else ch)
        return sb.toString()
    }
}
