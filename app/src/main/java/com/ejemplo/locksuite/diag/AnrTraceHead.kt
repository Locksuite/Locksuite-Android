package com.ejemplo.locksuite.diag

/**
 * 19/11/2026 (B.125, T2.1) — QUÉ SE GUARDA DE LA TRAZA DE UN ANR. Lógica pura, con prueba.
 *
 * Hasta hoy se guardaba solo el hilo `"main"`, y en los cuatro ANR del Qin F21 de Iejiel estaba
 * ocioso (`nativePollOnce`): no decía NADA de qué había provocado el cartel. Lo que sí dice
 * qué fue es la cabecera de la traza, que Android 10+ encabeza con el motivo exacto:
 *
 *     Subject: Broadcast of Intent { act=android.intent.action.MY_PACKAGE_REPLACED … }
 *     Subject: Input dispatching timed out (…)
 *     Subject: executing service com.ejemplo.locksuite/.service.…
 *
 * Con ese renglón se sabe de una vez si fue un broadcast retenido, un servicio o la pantalla.
 *
 * ── QUÉ DEVUELVE ─────────────────────────────────────────────────────────────────────
 *  1. El motivo: la línea `Subject:` si está en las primeras 60 líneas; si no, las primeras
 *     3 líneas no vacías ANTES del primer `----- pid` (el sistema a veces lo escribe distinto).
 *  2. El hilo `"main"`, hasta [MAIN_LINES] líneas (como hasta ahora).
 *  3. Hasta [OTHER_THREADS] hilos más que tengan algún marco de LockSuite, [THREAD_LINES]
 *     líneas cada uno: si el trabajo pesado corría en otro hilo, acá aparece.
 *  Todo con tope de [MAX_CHARS] caracteres y de [MAX_LINE] por línea.
 *
 * Quien lee el archivo (con permiso y sin lanzar) es `DiagCollector`; quien limpia datos
 * personales es `DiagSanitizer`.
 */
object AnrTraceHead {

    const val MAX_CHARS = 2500
    const val MAIN_LINES = 18
    const val OTHER_THREADS = 2
    const val THREAD_LINES = 10
    private const val MAX_LINE = 240
    private const val HEADER_SCAN = 60

    fun extract(lines: List<String>, ownPackage: String = "com.ejemplo.locksuite"): String {
        if (lines.isEmpty()) return ""
        val out = ArrayList<String>()

        // 1. El motivo.
        val subject = lines.take(HEADER_SCAN).firstOrNull { it.trimStart().startsWith("Subject:") }
        if (subject != null) {
            out.add(subject.trim().take(MAX_LINE))
        } else {
            val firstPid = lines.indexOfFirst { it.startsWith("----- pid") }
            val head = if (firstPid >= 0) lines.take(firstPid) else emptyList()
            head.filter { it.isNotBlank() }.take(3).forEach { out.add(it.trim().take(MAX_LINE)) }
        }

        // 2. y 3. Los hilos.
        val starts = lines.indices.filter { lines[it].startsWith("\"") }
        fun blockEnd(i: Int): Int {
            val next = starts.firstOrNull { it > i } ?: lines.size
            // Un bloque también termina en la línea de cierre del volcado.
            val end = (i + 1 until next).firstOrNull { lines[it].startsWith("----- end") } ?: next
            return end
        }

        val mainIdx = starts.firstOrNull { lines[it].startsWith("\"main\"") }
        if (mainIdx != null) {
            val end = minOf(blockEnd(mainIdx), mainIdx + MAIN_LINES)
            out.addAll(lines.subList(mainIdx, end).map { it.trimEnd().take(MAX_LINE) })
        } else {
            // Sin hilo "main" reconocible: las primeras líneas, como se hacía antes.
            out.addAll(lines.take(MAIN_LINES).map { it.trimEnd().take(MAX_LINE) })
        }

        var others = 0
        for (i in starts) {
            if (others >= OTHER_THREADS) break
            if (i == mainIdx) continue
            val end = blockEnd(i)
            val block = lines.subList(i, end)
            if (block.none { it.contains(ownPackage) }) continue
            out.addAll(block.take(THREAD_LINES).map { it.trimEnd().take(MAX_LINE) })
            others++
        }

        val text = out.joinToString("\n")
        return if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS - 1) + "…" else text
    }
}
