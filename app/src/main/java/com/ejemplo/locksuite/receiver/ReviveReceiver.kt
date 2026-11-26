package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ejemplo.locksuite.diag.DiagLog
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.ejemplo.locksuite.util.ProcessHealth

/**
 * 19/11/2026 (B.125, T3.2) — ALARMA DE RESCATE. Ver `util/ProcessHealth.kt`.
 *
 * Mientras LockSuite vive, el Watchdog corre esta alarma siempre hacia adelante y NUNCA suena.
 * Si el proceso murió (un ANR con «Cerrar app», falta de memoria…) suena a los 20 min o antes y
 * este receptor vuelve a levantar el Watchdog y la VPN.
 *
 * `ACTION_SENTINEL` es la alarma de 365 días que solo existe para detectar una detención
 * forzada: si alguna vez suena, no hace nada.
 *
 * No es `directBootAware` a propósito: solo corre con el equipo desbloqueado, o sea con el
 * almacenamiento de las políticas disponible. Sin `goAsync()` (regla de B.125): solo arranca
 * servicios, que es trabajo de milisegundos.
 */
class ReviveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        com.ejemplo.locksuite.util.Safe.guard("ReviveReceiver.onReceive") {
            val action = intent.action
            if (action == BootReceiver.ACTION_BOOT_GATE_TIMEOUT) {
                if (!com.ejemplo.locksuite.LockSuiteApplication.isStorageUnlocked(context)) return@guard
                com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(context, "alarma de arranque protegido")
                com.ejemplo.locksuite.util.BootGate.healStuckProxy(context, "alarma de seguridad")
                return@guard
            }
            if (action != ProcessHealth.ACTION_REVIVE) return@guard
            if (!com.ejemplo.locksuite.LockSuiteApplication.isStorageUnlocked(context)) return@guard
            if (com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context).getBoolean("locksuite_purged", false)) {
                ProcessHealth.cancelAll(context)
                return@guard
            }
            DiagLog.firstEntry("alarma de rescate")
            com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(context, "alarma de rescate")
            if (!WatchdogForegroundService.isRunning()) {
                try {
                    val watchdog = Intent(context, WatchdogForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(watchdog)
                    } else {
                        context.startService(watchdog)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ReviveReceiver", "No se pudo arrancar el Watchdog: ${e.message}")
                }
            }
            BootReceiver.ensureVpnRunning(context)
            // Se vuelve a armar sola: si el Watchdog no llegara a correr, esto sigue vigilando.
            ProcessHealth.scheduleRescueIfDue(context, force = true)
        }
    }
}
