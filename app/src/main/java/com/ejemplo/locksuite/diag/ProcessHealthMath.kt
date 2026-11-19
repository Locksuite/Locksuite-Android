package com.ejemplo.locksuite.diag

/**
 * 19/11/2026 (B.125, T2.3 y T2.4) — LAS DOS CUENTAS PARA SABER QUÉ LE PASÓ AL PROCESO.
 * Lógica pura (sin Android), con prueba. Las usa `ProcessHealth` y `DiagCollector`.
 *
 * En el Qin F21 de Iejiel LockSuite estuvo muerto 52 h y 4,5 h después de un ANR y no había
 * forma de saber QUIÉN lo revivió ni CUÁNTO estuvo caído. Estas dos cuentas lo contestan sin
 * latidos ni nada que corra de más.
 */

/**
 * ¿Este arranque viene de una DETENCIÓN FORZADA del paquete?
 *
 * Una detención forzada (Ajustes → Forzar detención, o `am force-stop`) borra las alarmas y los
 * `PendingIntent` del paquete. Se aprovecha: al arrancar, `ProcessHealth` busca una «sentinela»
 * (un `PendingIntent` con `FLAG_NO_CREATE` sostenido por una alarma muy lejana, el mismo truco
 * que usa `ForceStopRunnable` de WorkManager). Si no está, el sistema la borró.
 *
 * Una ACTUALIZACIÓN del paquete también detiene y borra todo, así que se distingue por
 * `lastUpdateTime`: si cambió, fue una actualización y no se avisa nada raro.
 */
object ForceStopVerdict {

    enum class Verdict {
        /** Primera vez que se mide: no hay con qué comparar. */
        FIRST_RUN,
        /** El paquete se actualizó desde el último arranque: lo borrado es normal. */
        UPDATED,
        /** Sin actualización y sin sentinela: alguien forzó la detención. */
        FORCE_STOPPED,
        NORMAL
    }

    fun decide(sentinelPresent: Boolean, lastUpdateTimeNow: Long, lastUpdateTimeSaved: Long): Verdict {
        if (lastUpdateTimeSaved <= 0L) return Verdict.FIRST_RUN
        if (lastUpdateTimeNow != lastUpdateTimeSaved) return Verdict.UPDATED
        return if (sentinelPresent) Verdict.NORMAL else Verdict.FORCE_STOPPED
    }
}

/**
 * ¿Cuánto estuvo LockSuite sin proceso entre la muerte del anterior y el arranque de este?
 *
 * Exacto y gratis: sale del momento en que Android anotó la muerte (`ApplicationExitInfo`) y
 * de cuándo arrancó este proceso. Una muerte anterior al último ENCENDIDO del equipo no es una
 * caída (el celular estaba apagado).
 */
object OutageMath {

    /** Menos que esto es un reinicio normal del proceso (una actualización, un `START_STICKY`). */
    const val THRESHOLD_MS = 2 * 60_000L

    class Outage(val gapMs: Long, val diedAt: Long, val restartedAt: Long)

    /**
     * @param exitAt hora (reloj de pared) en que Android anotó la muerte del proceso anterior.
     * @param now hora de pared actual.
     * @param uptimeMs tiempo desde que se encendió el equipo (`SystemClock.elapsedRealtime()`).
     * @param processAgeMs cuánto hace que arrancó ESTE proceso.
     */
    fun classify(exitAt: Long, now: Long, uptimeMs: Long, processAgeMs: Long): Outage? {
        if (exitAt <= 0L) return null
        val bootAt = now - uptimeMs
        if (exitAt < bootAt) return null
        val startedAt = now - processAgeMs
        val gap = startedAt - exitAt
        if (gap <= THRESHOLD_MS) return null
        return Outage(gap, exitAt, startedAt)
    }

    /** `52 h 25 min`, `4 h 26 min`, `7 min`. */
    fun format(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0L)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }
}
