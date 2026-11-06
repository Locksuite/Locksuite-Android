package com.ejemplo.locksuite.diag

/**
 * LECTURA DEL LOGCAT PROPIO (6/11/2026, B.110).
 *
 * Una app puede leer SU PROPIO logcat sin ningún permiso (`logcat -d`: Android solo le
 * muestra lo que escribió su propio uid). Es lo más parecido a «tenerlo conectado por USB»
 * que se puede hacer desde adentro, y es de donde sale el error real cuando el código lo
 * escribió con `Log.e(...)` o lo dejó pasar con `e.printStackTrace()`.
 *
 * Esta clase NO ejecuta nada: solo interpreta el texto (formato `-v threadtime`), para que
 * se pueda probar con JUnit (`DiagLogcatParserTest`). Quien ejecuta `logcat` es
 * `DiagCollector`.
 *
 *     MM-DD HH:MM:SS.mmm  PID  TID L TAG     : mensaje
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • El CURSOR es la marca de hora de la última línea vista (`MM-DD HH:MM:SS.mmm`). Se
 *    compara como TEXTO: sirve dentro de un mismo año. Cuando la marca más nueva del
 *    logcat es MENOR que el cursor (cambio de año, o el reloj se corrigió hacia atrás) se
 *    toma todo como nuevo en vez de quedar ciego hasta que el reloj "alcance".
 *  • Las líneas de una misma ráfaga (un stack trace ocupa una línea de logcat por frame)
 *    se juntan en UN solo evento: misma etiqueta, mismo nivel y el mismo segundo. Sin esto,
 *    un crash serían 30 eventos.
 *  • Cada línea se limpia con [DiagSanitizer] antes de salir de acá.
 */
object DiagLogcatParser {

    class Entry(val stamp: String, val level: Char, val tag: String, val msg: String)

    /** Una ráfaga de líneas seguidas de la misma etiqueta y nivel. */
    class Burst(val stamp: String, val level: Char, val tag: String, val text: String)

    private val LINE = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?)\s*:\s?(.*)$""")
    private const val LEVELS = "VDIWEFA"
    private const val MAX_BURST_LINES = 12
    private const val SECOND_LEN = 14 // "MM-DD HH:MM:SS"

    fun parse(lines: List<String>): List<Entry> {
        val out = ArrayList<Entry>(lines.size)
        for (raw in lines) {
            val m = LINE.matchEntire(raw.trimEnd()) ?: continue
            out.add(Entry(m.groupValues[1], m.groupValues[2][0], m.groupValues[3].trim(), m.groupValues[4]))
        }
        return out
    }

    /** Orden de gravedad de un nivel de logcat (V < D < I < W < E < F < A). */
    fun rank(level: Char): Int = LEVELS.indexOf(level).let { if (it < 0) 0 else it }

    /** Las entradas posteriores al cursor. Ver la nota del reloj hacia atrás en la cabecera. */
    fun newerThan(entries: List<Entry>, cursor: String?): List<Entry> {
        if (cursor.isNullOrBlank() || entries.isEmpty()) return entries
        val newest = entries.maxOf { it.stamp }
        if (newest < cursor) return entries
        return entries.filter { it.stamp > cursor }
    }

    /** La marca de hora más nueva, o null si no hay entradas. Es el próximo cursor. */
    fun newestStamp(entries: List<Entry>): String? = entries.maxOfOrNull { it.stamp }

    /** Junta líneas seguidas de la misma etiqueta/nivel/segundo, dejando solo `>= minLevel`. */
    fun bursts(entries: List<Entry>, minLevel: Char): List<Burst> {
        val min = rank(minLevel)
        val out = ArrayList<Burst>()
        var curStamp = ""
        var curLevel = ' '
        var curTag = ""
        var lines = ArrayList<String>()

        fun close() {
            if (lines.isNotEmpty()) {
                out.add(Burst(curStamp, curLevel, curTag, lines.joinToString("\n")))
                lines = ArrayList()
            }
        }
        for (e in entries) {
            if (rank(e.level) < min) { close(); continue }
            val sameBurst = lines.isNotEmpty() && e.tag == curTag && e.level == curLevel &&
                e.stamp.take(SECOND_LEN) == curStamp.take(SECOND_LEN) && lines.size < MAX_BURST_LINES
            if (!sameBurst) {
                close()
                curStamp = e.stamp; curLevel = e.level; curTag = e.tag
            }
            lines.add(DiagSanitizer.sanitize(e.msg, 240))
        }
        close()
        return out
    }

    /**
     * Marca de hora de logcat (`MM-DD HH:MM:SS.mmm`, hora LOCAL del equipo) → milisegundos
     * desde 1970. El logcat no trae el año: se usa el actual, y si eso da una fecha en el
     * futuro (renglón de diciembre leído en enero) se usa el año anterior. null si el
     * texto no tiene el formato.
     */
    fun stampToEpoch(stamp: String, nowMs: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        if (stamp.length < 18) return null
        return try {
            val mo = stamp.substring(0, 2).toInt()
            val d = stamp.substring(3, 5).toInt()
            val h = stamp.substring(6, 8).toInt()
            val mi = stamp.substring(9, 11).toInt()
            val s = stamp.substring(12, 14).toInt()
            val ms = stamp.substring(15, 18).toInt()
            val cal = java.util.Calendar.getInstance(tz)
            cal.timeInMillis = nowMs
            val year = cal.get(java.util.Calendar.YEAR)
            cal.clear()
            cal.set(year, mo - 1, d, h, mi, s)
            cal.set(java.util.Calendar.MILLISECOND, ms)
            var t = cal.timeInMillis
            if (t > nowMs + 36L * 3600_000L) {
                cal.set(java.util.Calendar.YEAR, year - 1)
                t = cal.timeInMillis
            }
            t
        } catch (_: Throwable) {
            null
        }
    }

    /** Una línea de logcat ya limpia, para el informe. */
    fun formatLine(e: Entry): String =
        DiagSanitizer.sanitize("${e.stamp} ${e.level}/${e.tag}: ${e.msg}", 300)

    /**
     * Las últimas líneas que entran en [maxLines] líneas y [maxChars] caracteres, en orden
     * cronológico. Si se corta se avisa en la primera línea.
     */
    fun tail(entries: List<Entry>, maxLines: Int, maxChars: Int): String {
        val picked = ArrayList<String>()
        var chars = 0
        var i = entries.size - 1
        while (i >= 0 && picked.size < maxLines) {
            val l = formatLine(entries[i])
            if (chars + l.length + 1 > maxChars) break
            picked.add(l)
            chars += l.length + 1
            i--
        }
        picked.reverse()
        return if (i >= 0) "… (hay ${i + 1} líneas más viejas)\n" + picked.joinToString("\n") else picked.joinToString("\n")
    }
}
