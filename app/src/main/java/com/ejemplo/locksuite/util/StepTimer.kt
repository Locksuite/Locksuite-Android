package com.ejemplo.locksuite.util

/**
 * 19/11/2026 (B.125, T2.5) — CRONÓMETRO POR PASOS. Sin Android, con prueba.
 *
 * `reapplyAllRestrictions()` es la función más pesada del arranque y nunca se supo cuánto
 * tarda cada bloque en un equipo lento. Esto lo mide: `mark("nombre")` anota cuánto pasó desde
 * la marca anterior. [summary] lo devuelve de más lento a más rápido, para el registro de
 * diagnóstico. El reloj se inyecta para poder probarlo.
 */
class StepTimer(private val clock: () -> Long) {
    private val start = clock()
    private var last = start
    private val steps = ArrayList<Pair<String, Long>>()

    fun mark(name: String) {
        val now = clock()
        steps.add(name to (now - last))
        last = now
    }

    fun totalMs(): Long = clock() - start

    /** `recorrido de apps 18200 ms, lista blanca 4100 ms, …` — solo pasos de 100 ms o más. */
    fun summary(): String =
        steps.filter { it.second >= 100L }
            .sortedByDescending { it.second }
            .joinToString(", ") { "${it.first} ${it.second} ms" }
}
