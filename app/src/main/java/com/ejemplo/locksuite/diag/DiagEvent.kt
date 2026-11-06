package com.ejemplo.locksuite.diag

/**
 * Un renglón del REGISTRO DE DIAGNÓSTICO REMOTO (6/11/2026, B.110).
 *
 * ── PARA QUÉ EXISTE TODO EL PAQUETE `diag` ───────────────────────────────────────────
 * El dueño recibe reportes como «no me abre…» o «se me cortó…» y desde el panel no tiene
 * forma de ver QUÉ pasó en ese celular: el error real queda en un teléfono que no está
 * conectado a ninguna PC. Este paquete es el equivalente a tener cada equipo enchufado
 * por depuración USB, pero sin cable, sin que el usuario vea nada y sin gastar batería:
 *   1. Cada error o hecho importante que LockSuite ya conoce (se cayó un servicio, el
 *      sistema la mató, un comando falló, una excepción que el código se tragaba…) deja un
 *      renglón de estos en un archivo chico del propio celular.
 *   2. Los renglones suben a Firebase (`deviceDiag/<id>/events`) pegados al latido que el
 *      equipo YA hace — sin temporizadores nuevos, sin despertar nada.
 *   3. Si hace falta más, el panel pide un «diagnóstico completo» (`COLLECT_DIAGNOSTICS`):
 *      el celular junta su propio logcat, los últimos cierres del proceso, el estado del
 *      sistema y el de la app con problema, y lo sube. El panel arma un informe que se
 *      le pega a la IA.
 *
 * ── ESTE ARCHIVO ─────────────────────────────────────────────────────────────────────
 * Es el formato de UN renglón y su idea ida-y-vuelta a texto. Kotlin puro (sin Android,
 * sin org.json): lo prueba `DiagEventTest` y se puede leer a ojo con `adb shell run-as`.
 *
 * Línea guardada en el archivo (campos separados por TAB, el mensaje al final y escapado):
 *
 *     seq <TAB> epochMs <TAB> nivel <TAB> etiqueta <TAB> veces <TAB> mensaje
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • `seq` es correlativo y NO se reinicia: es la clave del renglón en Firebase
 *    (`e0000000123`) y el cursor de «hasta dónde ya subí». Si se reiniciara, los renglones
 *    nuevos tendrían número menor que el cursor y NO subirían nunca (ver DiagStore.init).
 *  • El mensaje se ESCAPA (`\n`, `\t`, `\\`) en vez de limpiarse: un stack trace tiene
 *    saltos de línea y perderlos lo vuelve ilegible. El archivo sigue siendo UN renglón
 *    por evento, así que se puede cortar/ordenar con herramientas de línea.
 *  • Una línea que no se puede leer se IGNORA (devuelve null). Un archivo a medio escribir
 *    por un corte de energía no puede tirar abajo la lectura de los demás renglones.
 *  • El mensaje que llega acá YA pasó por [DiagSanitizer]: este archivo no limpia datos
 *    personales, solo da formato.
 */
data class DiagEvent(
    val seq: Long,
    /** Hora del equipo, en milisegundos desde 1970. */
    val at: Long,
    /** [LEVEL_INFO], [LEVEL_WARN], [LEVEL_ERROR] o [LEVEL_FATAL]. */
    val level: Char,
    /** Área corta en minúsculas: `app`, `vpn`, `a11y`, `cmd`, `crash`, `exit`, `net`, `err`… */
    val tag: String,
    val msg: String,
    /** Cuántas veces ocurrió lo mismo desde el renglón anterior (agrupa repeticiones). */
    val count: Int = 1
) {
    /** Los errores y las caídas se conservan y se suben antes que lo demás. */
    val isImportant: Boolean get() = level == LEVEL_ERROR || level == LEVEL_FATAL

    fun encode(): String {
        val sb = StringBuilder(64 + msg.length)
        sb.append(seq).append('\t')
            .append(at).append('\t')
            .append(normalizeLevel(level)).append('\t')
            .append(cleanTag(tag)).append('\t')
            .append(if (count < 1) 1 else count).append('\t')
        escapeInto(sb, msg)
        return sb.toString()
    }

    companion object {
        const val LEVEL_INFO = 'I'
        const val LEVEL_WARN = 'W'
        const val LEVEL_ERROR = 'E'
        const val LEVEL_FATAL = 'F'

        private const val MAX_TAG = 16

        fun normalizeLevel(c: Char): Char = when (c.uppercaseChar()) {
            LEVEL_WARN -> LEVEL_WARN
            LEVEL_ERROR -> LEVEL_ERROR
            LEVEL_FATAL -> LEVEL_FATAL
            else -> LEVEL_INFO
        }

        /** Etiqueta en minúsculas con solo `[a-z0-9_.-]`, de hasta 16 caracteres. Vacía → `app`. */
        fun cleanTag(tag: String): String {
            val sb = StringBuilder(MAX_TAG)
            for (ch in tag.lowercase()) {
                if (sb.length >= MAX_TAG) break
                sb.append(if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.' || ch == '-') ch else '_')
            }
            return if (sb.isEmpty()) "app" else sb.toString()
        }

        /** null si la línea está rota. Nunca lanza excepciones. */
        fun decode(line: String?): DiagEvent? {
            if (line.isNullOrEmpty()) return null
            val p = line.split('\t', limit = 6)
            if (p.size < 6) return null
            val seq = p[0].toLongOrNull() ?: return null
            val at = p[1].toLongOrNull() ?: return null
            if (seq < 1 || at < 0) return null
            val lvl = p[2].singleOrNull() ?: return null
            val count = p[4].toIntOrNull() ?: return null
            return DiagEvent(seq, at, normalizeLevel(lvl), cleanTag(p[3]), unescape(p[5]), if (count < 1) 1 else count)
        }

        private fun escapeInto(sb: StringBuilder, s: String) {
            for (ch in s) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(ch)
                }
            }
        }

        internal fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2; continue }
                        'r' -> { sb.append('\r'); i += 2; continue }
                        't' -> { sb.append('\t'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                    }
                }
                sb.append(ch)
                i++
            }
            return sb.toString()
        }
    }
}
