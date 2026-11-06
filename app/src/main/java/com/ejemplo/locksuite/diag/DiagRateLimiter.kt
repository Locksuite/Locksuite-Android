package com.ejemplo.locksuite.diag

/**
 * FRENO DE VOLUMEN del registro (6/11/2026, B.110).
 *
 * Un registro que se llena de ruido deja de servir Y cuesta batería (escribir, subir). El caso
 * típico: un error que se repite en un bucle (un servicio que falla y se reinicia cada
 * segundo) o un evento que el sistema dispara cientos de veces por minuto. Este freno hace
 * dos cosas, las dos sin temporizadores (se decide en el momento en que llega cada renglón):
 *
 *  1. **Agrupa repetidos.** El mismo mensaje (mismo nivel, etiqueta y texto) dentro de
 *     [dedupeMs] NO se vuelve a escribir: se cuenta. Cuando vuelve a aparecer fuera de esa
 *     ventana se escribe una vez con `count` = cuántas veces pasó. El panel lo muestra «×37».
 *  2. **Tope por minuto.** Aunque los mensajes sean todos distintos, no se escriben más de
 *     [maxPerWindow] informativos ni [maxImportantPerWindow] errores por ventana de
 *     [windowMs]. Lo que pasa del tope se descarta y se cuenta; el siguiente renglón que sí
 *     se escribe avisa cuántos se descartaron (`droppedBefore`), para que el hueco no sea
 *     invisible.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • Los errores tienen un tope PROPIO, más alto: una racha de avisos no puede dejar sin
 *    lugar a un error.
 *  • El reloj puede ir hacia atrás (equipo sin hora de red que la corrige de golpe): si
 *    `now` es menor que el inicio de la ventana, se reinicia la ventana en vez de quedar
 *    bloqueado hasta que el reloj "alcance".
 *  • El mapa de mensajes vistos tiene tope ([maxKeys]); al llenarse se descarta el más
 *    viejo. Sin tope, un mensaje distinto por evento lo haría crecer sin fin.
 *  • Es seguro entre hilos (`@Synchronized`): lo llaman hilos distintos.
 *
 * Kotlin puro: lo prueba `DiagRateLimiterTest`.
 */
class DiagRateLimiter(
    private val maxPerWindow: Int = 40,
    private val maxImportantPerWindow: Int = 80,
    private val windowMs: Long = 60_000L,
    private val dedupeMs: Long = 60_000L,
    private val maxKeys: Int = 64
) {
    /**
     * @param keep si hay que escribir este renglón.
     * @param count cuántas veces ocurrió (≥ 1) desde la última vez que se escribió.
     * @param droppedBefore cuántos renglones se descartaron por exceso de volumen desde el
     *        último que se escribió (0 casi siempre).
     */
    class Verdict(val keep: Boolean, val count: Int, val droppedBefore: Int)

    private class Seen(var lastWrittenAt: Long, var suppressed: Int)

    private val seen = LinkedHashMap<String, Seen>()
    private var windowStart = 0L
    private var inWindow = 0
    private var importantInWindow = 0
    private var dropped = 0

    @Synchronized
    fun check(level: Char, tag: String, msg: String, nowMs: Long): Verdict {
        if (nowMs < windowStart || nowMs - windowStart >= windowMs) {
            windowStart = nowMs
            inWindow = 0
            importantInWindow = 0
        }
        val important = level == DiagEvent.LEVEL_ERROR || level == DiagEvent.LEVEL_FATAL
        val key = "$level|$tag|$msg"
        val s = seen[key]

        if (s != null && nowMs >= s.lastWrittenAt && nowMs - s.lastWrittenAt < dedupeMs) {
            s.suppressed++
            return Verdict(false, 0, 0)
        }

        val overCap = if (important) importantInWindow >= maxImportantPerWindow else inWindow >= maxPerWindow
        if (overCap) {
            dropped++
            return Verdict(false, 0, 0)
        }

        val count = (s?.suppressed ?: 0) + 1
        if (s != null) {
            s.lastWrittenAt = nowMs
            s.suppressed = 0
        } else {
            if (seen.size >= maxKeys) evictOldest()
            seen[key] = Seen(nowMs, 0)
        }
        if (important) importantInWindow++ else inWindow++
        val d = dropped
        dropped = 0
        return Verdict(true, count, d)
    }

    private fun evictOldest() {
        var oldestKey: String? = null
        var oldestAt = Long.MAX_VALUE
        for ((k, v) in seen) {
            if (v.lastWrittenAt < oldestAt) { oldestAt = v.lastWrittenAt; oldestKey = k }
        }
        if (oldestKey != null) seen.remove(oldestKey)
    }
}
