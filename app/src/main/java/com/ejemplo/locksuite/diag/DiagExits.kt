package com.ejemplo.locksuite.diag

/**
 * LOS CIERRES DEL PROCESO, en palabras (6/11/2026, B.110).
 *
 * Cuando un usuario dice «se me cortó» o «dejó de funcionar», lo primero que hay que saber
 * es SI Android mató el proceso de LockSuite y POR QUÉ: poca memoria, el sistema la
 * congeló, el usuario la forzó a detener, un crash, un ANR (la app no respondió)… Android
 * 11+ lo guarda en `ApplicationExitInfo`; la clase `DiagCollector` lo lee y esta clase lo
 * traduce a texto y decide qué nivel de gravedad le corresponde.
 *
 * Trabaja con los CÓDIGOS numéricos de `ApplicationExitInfo.REASON_*` (en vez de las
 * constantes) para no depender de Android: así se prueba con JUnit puro (`DiagExitsTest`)
 * y no se rompe en un equipo con Android < 11 donde esa clase no existe.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • Se muestra el NOMBRE exacto de la constante (`LOW_MEMORY`) además de la explicación en
 *    castellano: quien diagnostica (la IA, un desarrollador) busca esa palabra tal cual.
 *  • Un cierre por `USER_REQUESTED` / `EXIT_SELF` es informativo (nivel I): no es una falla.
 *    `LOW_MEMORY`, `FREEZER`, `EXCESSIVE_RESOURCE_USAGE` y `PERMISSION_CHANGE` son avisos (W):
 *    el sistema la sacó. Crash, crash nativo, ANR, fallo al iniciar, dependencia muerta y
 *    `SIGNALED` son errores (E).
 */
object DiagExits {

    class Record(
        val at: Long,
        val reason: Int,
        val importance: Int,
        val status: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
        /** Primeras líneas de la traza (ANR / crash nativo), si el sistema la dejó. */
        val trace: String?
    )

    fun reasonName(code: Int): String = when (code) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$code"
    }

    fun reasonExplain(code: Int): String = when (code) {
        0 -> "motivo desconocido"
        1 -> "el propio proceso se cerró (System.exit)"
        2 -> "el sistema le mandó una señal (kill)"
        3 -> "Android la cerró por falta de memoria"
        4 -> "excepción no atrapada (crash de Java/Kotlin)"
        5 -> "crash nativo (código C/C++)"
        6 -> "ANR: la app dejó de responder"
        7 -> "falló al iniciar el proceso"
        8 -> "cambió un permiso mientras corría"
        9 -> "uso excesivo de recursos (CPU/memoria/batería)"
        10 -> "el usuario la cerró o forzó la detención"
        11 -> "el usuario del perfil fue detenido"
        12 -> "murió algo de lo que dependía"
        13 -> "otro motivo (lo decidió el sistema)"
        14 -> "el sistema la congeló (cached app freezer)"
        15 -> "cambió el estado del paquete (activar/desactivar/ocultar)"
        16 -> "se actualizó el paquete"
        else -> "código no reconocido"
    }

    fun importanceName(code: Int): String = when {
        code <= 0 -> "?"
        code <= 100 -> "primer plano"
        code <= 125 -> "servicio en primer plano"
        code <= 150 -> "primer plano (pantalla apagada)"
        code <= 200 -> "visible"
        code <= 230 -> "perceptible"
        code <= 270 -> "no puede guardar estado"
        code <= 300 -> "servicio"
        code <= 400 -> "en caché (segundo plano)"
        else -> "ya no existe"
    }

    /** Gravedad con que se anota el cierre en el registro. */
    fun levelFor(code: Int): Char = when (code) {
        4, 5, 6, 7, 12, 2 -> DiagEvent.LEVEL_ERROR
        3, 8, 9, 14 -> DiagEvent.LEVEL_WARN
        else -> DiagEvent.LEVEL_INFO
    }

    /** Texto de una línea para el registro / el informe. Ya sanitizado. */
    fun describe(r: Record): String {
        val sb = StringBuilder()
        sb.append("Cierre del proceso: ").append(reasonName(r.reason))
            .append(" — ").append(reasonExplain(r.reason))
            .append(" (importancia: ").append(importanceName(r.importance))
            .append(", estado ").append(r.status)
        if (r.pssKb > 0) sb.append(", PSS ").append(r.pssKb / 1024).append(" MB")
        if (r.rssKb > 0) sb.append(", RSS ").append(r.rssKb / 1024).append(" MB")
        sb.append(')')
        if (!r.description.isNullOrBlank()) sb.append(". Detalle del sistema: ").append(r.description.trim())
        val line = DiagSanitizer.sanitize(sb.toString(), DiagSanitizer.MAX_MSG)
        val tr = DiagSanitizer.sanitizeTrace(r.trace)
        return if (tr.isEmpty()) line else line + "\n" + tr
    }

    /** Los cierres más nuevos que [lastSeenAt], del más viejo al más nuevo. */
    fun newerThan(records: List<Record>, lastSeenAt: Long): List<Record> =
        records.filter { it.at > lastSeenAt }.sortedBy { it.at }

    /**
     * Resumen para el informe: cuántos cierres hubo por cada motivo, del más frecuente al
     * menos. Vacío si no hay cierres. Ej.: `LOW_MEMORY ×3, USER_REQUESTED ×1`.
     */
    fun summary(records: List<Record>): String {
        if (records.isEmpty()) return ""
        return records.groupingBy { reasonName(it.reason) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key} ×${it.value}" }
    }
}
