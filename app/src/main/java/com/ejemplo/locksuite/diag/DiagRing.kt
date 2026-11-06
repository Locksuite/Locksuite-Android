package com.ejemplo.locksuite.diag

/**
 * QUÉ SE CONSERVA CUANDO EL ARCHIVO DEL REGISTRO SE LLENA (6/11/2026, B.110).
 *
 * El archivo del celular es un anillo: cuando pasa de cierto tamaño se reescribe dejando
 * solo lo más nuevo. Una regla ingenua («quedate con los últimos 700») tiene un defecto
 * grave para este uso: **una racha de renglones informativos expulsa al error que importa**.
 * Ejemplo real: la accesibilidad se cae a las 03:00 (error) y durante la noche el celular
 * anota 900 avisos menores; a la mañana el error de las 03:00 ya no está y el dueño no
 * puede diagnosticar justo lo que le reportaron.
 *
 * Por eso acá se reservan lugares para los renglones IMPORTANTES (errores y caídas):
 * se conservan hasta [MAX_IMPORTANT] de los más nuevos aunque haya mucho ruido, y el resto
 * del cupo se llena con lo más nuevo de lo demás. Si hay una avalancha de errores, ellos
 * tampoco crecen sin límite: se queda con los [MAX_IMPORTANT] más recientes.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • El resultado sale ORDENADO por `seq`: quien lo escribe de vuelta al archivo no tiene
 *    que reordenar, y el cursor de subida (`seq` más alto ya subido) sigue funcionando.
 *  • Con menos renglones que el tope NO se toca nada (la mayoría de las veces).
 *
 * Kotlin puro: lo prueba `DiagRingTest`.
 */
object DiagRing {

    /** Renglones que se conservan al compactar el archivo. */
    const val MAX_EVENTS = 700
    /** De esos, cuántos lugares se reservan para errores y caídas. */
    const val MAX_IMPORTANT = 250

    fun select(
        events: List<DiagEvent>,
        maxTotal: Int = MAX_EVENTS,
        maxImportant: Int = MAX_IMPORTANT
    ): List<DiagEvent> {
        if (maxTotal <= 0) return emptyList()
        if (events.size <= maxTotal) return events
        val important = events.filter { it.isImportant }.takeLast(minOf(maxImportant, maxTotal))
        val rest = events.filter { !it.isImportant }.takeLast((maxTotal - important.size).coerceAtLeast(0))
        return (important + rest).sortedBy { it.seq }
    }
}
