package com.ejemplo.locksuite.util

/**
 * 19/11/2026 (B.125, T1) — CUÁNDO CORRER UNA PASADA COMPLETA DE `reapplyAllRestrictions()`.
 *
 * Lógica pura (sin Android) para poder probarla con JUnit (`ReconcilePolicyTest`). Quien
 * ejecuta es [ReconcileScheduler]; acá solo se decide.
 *
 * Por qué existe: `BootReceiver` retenía el broadcast del sistema (`goAsync()`) mientras corría
 * una pasada completa, y esa pasada esperaba (por `reapplyLock`) a OTRA idéntica que ya había
 * lanzado `LockSuiteApplication` al arrancar. Eran dos pasadas enteras antes de soltar el
 * broadcast, y Android da 10 s (primer plano) / 60 s (segundo plano) — en un Qin F21 recién
 * actualizado no alcanzaba y saltaba el cartel de ANR, con su botón «Cerrar app». Ver B.125.
 *
 * ── REGLAS ────────────────────────────────────────────────────────────────────────────
 *  • Un pedido de ARRANQUE (el proceso nació, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) está
 *    CUBIERTO si ya hay una pasada en curso o si otra empezó hace menos de [STARTUP_COVER_MS].
 *    Varios puntos de entrada del mismo arranque piden lo mismo: con una pasada alcanza.
 *  • Un pedido que NO es de arranque (un comando, el Watchdog) con una pasada en curso se
 *    ENCOLA: no se pierde, y se hace UNA pasada más al terminar, sin importar cuántos se
 *    juntaron. Es lo que impide «si está ocupado, ignorar», que dejaría pasar un cambio que
 *    llegó a mitad de la pasada.
 *  • Sin pasada en curso y sin estar cubierto, se CORRE.
 */
object ReconcilePolicy {

    /** Una pasada que empezó hace menos de esto cubre a los pedidos de arranque. */
    const val STARTUP_COVER_MS = 30_000L

    enum class Decision { RUN, COVERED, QUEUE }

    /**
     * @param startup el pedido viene de un arranque (proceso nuevo, broadcast de arranque o de
     *   actualización) y no de un cambio de configuración.
     * @param running hay una pasada ejecutándose ahora.
     * @param lastStartAt reloj monótono en que empezó la última pasada de ESTE proceso (0 =
     *   ninguna todavía).
     * @param now reloj monótono actual.
     */
    fun decide(startup: Boolean, running: Boolean, lastStartAt: Long, now: Long): Decision {
        if (startup) {
            if (running) return Decision.COVERED
            if (lastStartAt > 0L && now - lastStartAt in 0..STARTUP_COVER_MS) return Decision.COVERED
            return Decision.RUN
        }
        return if (running) Decision.QUEUE else Decision.RUN
    }
}

/**
 * El estado que acompaña a [ReconcilePolicy]: «hay una pasada en curso», «hay otra pendiente»
 * y cuándo empezó la última. Sincronizado y sin Android: se prueba con JUnit.
 */
class ReconcileGate {
    private var running = false
    private var pending = false
    private var lastStartAt = 0L

    @Synchronized
    fun request(startup: Boolean, now: Long): ReconcilePolicy.Decision {
        val d = ReconcilePolicy.decide(startup, running, lastStartAt, now)
        when (d) {
            ReconcilePolicy.Decision.RUN -> {
                running = true
                pending = false
                lastStartAt = now
            }
            ReconcilePolicy.Decision.QUEUE -> pending = true
            ReconcilePolicy.Decision.COVERED -> Unit
        }
        return d
    }

    /**
     * La pasada terminó. Devuelve true si hay que hacer UNA más (llegó algún pedido que no era
     * de arranque mientras corría); en ese caso la compuerta sigue «en curso».
     */
    @Synchronized
    fun finished(now: Long): Boolean {
        if (pending) {
            pending = false
            lastStartAt = now
            return true
        }
        running = false
        return false
    }

    @Synchronized
    fun isRunning(): Boolean = running
}
