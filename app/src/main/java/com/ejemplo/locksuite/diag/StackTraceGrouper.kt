package com.ejemplo.locksuite.diag

/**
 * ARMA UN SOLO EVENTO a partir de las líneas sueltas de un stack trace (6/11/2026, B.110).
 *
 * LockSuite tiene cientos de `catch (e: Exception) { e.printStackTrace() }`: el código
 * se TRAGA el error y sigue. Para el dueño eso es exactamente el «error invisible». Como
 * `printStackTrace()` escribe en `System.err` línea por línea, se instala un envoltorio de
 * `System.err` (ver `DiagErrTee`) que le pasa cada línea a esta clase; ella reconoce el
 * comienzo de una excepción (`java.lang.IllegalStateException: texto`), junta sus
 * `at …` y sus `Caused by:` y entrega UN texto. Así se capturan TODAS las excepciones
 * tragadas de la app sin tocar ninguno de esos `catch`.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • Una línea que no sea de excepción NO se guarda (solo corta el bloque abierto):
 *    `System.err` también recibe `println` sueltos que no son errores.
 *  • Solo se arman bloques que EMPIEZAN con algo que parece una clase de excepción
 *    (`algo.Algo...Exception|Error|Throwable`). Ese patrón evita tomar por error una
 *    frase cualquiera del código.
 *  • Se guardan pocos frames (6 del principal y 2 por cada causa): el nombre del archivo y
 *    la línea alcanzan para ubicar el error; el resto es volumen.
 *  • El último bloque queda "abierto" hasta que llega otra línea; por eso existe
 *    [hasOpenBlock] + [flush]: quien alimenta esto programa UN vaciado único corto
 *    después de la última línea (nada periódico).
 *  • No es seguro entre hilos a propósito: lo usa un solo hilo escritor (`DiagLog`).
 *
 * Kotlin puro: lo prueba `StackTraceGrouperTest`.
 */
class StackTraceGrouper(private val emit: (String) -> Unit) {

    private var header: String? = null
    private val frames = ArrayList<String>()
    private val causes = ArrayList<String>()
    private val causeFrames = ArrayList<ArrayList<String>>()
    private var mainFrameCount = 0

    val hasOpenBlock: Boolean get() = header != null

    fun feed(line: String) {
        val l = line.trimEnd()
        if (l.isEmpty()) { flush(); return }
        val trimmed = l.trimStart()
        val indented = l.length != trimmed.length

        if (!indented && HEADER.matches(l)) {
            if (header != null && l.startsWith("Caused by: ")) {
                causes.add(l.removePrefix("Caused by: "))
                causeFrames.add(ArrayList())
                return
            }
            flush()
            header = l
            return
        }
        if (header == null) return
        if (trimmed.startsWith("at ")) {
            if (causes.isEmpty()) {
                if (mainFrameCount < MAX_FRAMES) frames.add(trimmed)
                mainFrameCount++
            } else {
                val cf = causeFrames.last()
                if (cf.size < MAX_CAUSE_FRAMES) cf.add(trimmed)
            }
            return
        }
        if (trimmed.startsWith("...") || trimmed.startsWith("Suppressed:")) return
        flush()
    }

    /** Entrega el bloque abierto (si hay) y lo cierra. */
    fun flush() {
        val h = header ?: return
        val sb = StringBuilder(h)
        for (f in frames) sb.append("\n  ").append(f)
        if (mainFrameCount > frames.size) sb.append("\n  … +").append(mainFrameCount - frames.size).append(" frames")
        for (i in causes.indices) {
            sb.append("\nCaused by: ").append(causes[i])
            for (f in causeFrames[i]) sb.append("\n  ").append(f)
        }
        header = null
        frames.clear()
        causes.clear()
        causeFrames.clear()
        mainFrameCount = 0
        try { emit(sb.toString()) } catch (_: Throwable) { }
    }

    companion object {
        private const val MAX_FRAMES = 6
        private const val MAX_CAUSE_FRAMES = 2
        private val HEADER = Regex("""^(?:Caused by: )?(?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable)(?::.*)?$""")
    }
}
