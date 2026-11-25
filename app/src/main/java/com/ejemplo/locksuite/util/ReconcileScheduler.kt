package com.ejemplo.locksuite.util

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.ejemplo.locksuite.diag.DiagLog
import com.ejemplo.locksuite.mdm.PolicyManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 19/11/2026 (B.125, T1) — LA PASADA COMPLETA DE POLÍTICAS, FUERA DE LOS BROADCASTS.
 *
 * ── POR QUÉ EXISTE ───────────────────────────────────────────────────────────────────
 * `BootReceiver` usaba `goAsync()` pensando que alargaba el plazo del broadcast. No lo
 * alarga: Android sigue exigiendo `finish()` en 10 s (broadcast de primer plano) o 60 s
 * (segundo plano) y después muestra el cartel de ANR con «Cerrar app». Adentro corría
 * `reapplyAllRestrictions()`, que en un Qin F21 tarda 15–20 s y que además esperaba (por
 * `reapplyLock`) a otra pasada idéntica lanzada por `LockSuiteApplication` en el mismo
 * arranque: dos pasadas enteras. Cuatro ANR en 8 días en un solo celular, y dos veces el
 * proceso quedó muerto 52 h y 4,5 h después de tocar «Cerrar app». Ver B.125.
 *
 * ── CÓMO SE USA ──────────────────────────────────────────────────────────────────────
 * `ReconcileScheduler.request(ctx, "motivo", startup = true)` NO BLOQUEA nunca: decide con
 * [ReconcilePolicy] y, si hay que correr, lo hace en un hilo propio. Los broadcasts lo llaman
 * y terminan al instante; el proceso sigue vivo porque antes arrancaron el Watchdog (servicio
 * en primer plano).
 *
 * `afterBoot = true` suma los extras que `BootReceiver` hacía al final de la pasada
 * (`GracePeriodManager.checkAndHarden` y la sincronización con el panel). Si el pedido queda
 * cubierto por una pasada anterior, igual se corren, una sola vez.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • Un pedido que no es de arranque durante una pasada garantiza UNA pasada completa
 *    después. No convertirlo en «si está ocupado, ignorar».
 *  • Nunca lanza hacia afuera y el hilo no muere por una excepción de una pasada.
 *  • No usar `goAsync()` en ningún receptor para trabajo que pueda tardar más de ~5 s.
 */
object ReconcileScheduler {

    private val gate = ReconcileGate()
    private val extrasPending = AtomicBoolean(false)

    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {
                }
                r.run()
            }, "LockSuiteReconcile").apply { isDaemon = true }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun request(context: Context, reason: String, startup: Boolean, afterBoot: Boolean = false) {
        try {
            val app = context.applicationContext ?: context
            if (afterBoot) extrasPending.set(true)
            when (gate.request(startup, SystemClock.elapsedRealtime())) {
                ReconcilePolicy.Decision.RUN -> executor.execute { loop(app, reason) }
                ReconcilePolicy.Decision.QUEUE -> Unit
                ReconcilePolicy.Decision.COVERED -> {
                    // Los extras no dependen de la pasada: si no hay ninguna corriendo, se
                    // hacen ya; si la hay, los hace ella al terminar.
                    if (afterBoot && !gate.isRunning()) executor.execute { drainExtras(app) }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("ReconcileScheduler", "request($reason): ${t.message}")
        }
    }

    private fun loop(app: Context, reason: String) {
        try {
            do {
                runPass(app, reason)
            } while (gate.finished(SystemClock.elapsedRealtime()))
        } catch (t: Throwable) {
            // Pase lo que pase la compuerta tiene que quedar libre, o no correría nunca más.
            try {
                while (gate.finished(SystemClock.elapsedRealtime())) { /* vaciar pendientes */ }
            } catch (_: Throwable) {
            }
        }
        // Un pedido `afterBoot` que llegó entre el último drenado y el cierre de la compuerta.
        drainExtras(app)
    }

    private fun runPass(app: Context, reason: String) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            PolicyManager(app).reapplyAllRestrictions()
        } catch (t: Throwable) {
            android.util.Log.e("ReconcileScheduler", "reapplyAllRestrictions ($reason): ${t.message}")
        }
        val ms = SystemClock.elapsedRealtime() - t0
        if (ms > SLOW_PASS_MS) {
            DiagLog.w("reapply", "reapplyAllRestrictions tardó ${ms / 1000} s (motivo: $reason)")
        }
        drainExtras(app)
    }

    private fun drainExtras(app: Context) {
        if (!extrasPending.getAndSet(false)) return
        try {
            com.ejemplo.locksuite.mdm.GracePeriodManager.checkAndHarden(app)
        } catch (e: Exception) {
            android.util.Log.e("ReconcileScheduler", "checkAndHarden: ${e.message}")
        }
        try {
            com.ejemplo.locksuite.mdm.TempInstallWindowManager.checkAndExpire(app)
        } catch (e: Exception) {
            android.util.Log.e("ReconcileScheduler", "tempInstall: ${e.message}")
        }
        try {
            FirebaseDeviceSync.requestSync(app)
        } catch (e: Exception) {
            android.util.Log.e("ReconcileScheduler", "requestSync: ${e.message}")
        }
    }

    private const val SLOW_PASS_MS = 10_000L
}
