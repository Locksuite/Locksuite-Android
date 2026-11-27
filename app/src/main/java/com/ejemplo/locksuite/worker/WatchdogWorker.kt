package com.ejemplo.locksuite.worker

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.ejemplo.locksuite.util.PrefsHelper

class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // 19/11/2026 (B.125, T2.2): quién arrancó este proceso, si fue el Worker (JobScheduler).
        com.ejemplo.locksuite.diag.DiagLog.firstEntry("WatchdogWorker (JobScheduler)")
        if (!com.ejemplo.locksuite.LockSuiteApplication.isStorageUnlocked(applicationContext)) {
            return Result.retry()
        }
        com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(applicationContext, "WatchdogWorker")
        val purged = try {
            PrefsHelper.getMdmPrefs(applicationContext).getBoolean("locksuite_purged", false)
        } catch (_: Throwable) {
            false
        }
        if (purged) {
            com.ejemplo.locksuite.util.ProcessHealth.cancelAll(applicationContext)
            return Result.success()
        }
        // Y la alarma de rescate: otro mecanismo distinto de WorkManager (ver util/ProcessHealth).
        com.ejemplo.locksuite.util.ProcessHealth.scheduleRescueIfDue(applicationContext)

        val networkAvailable = hasUsableNetwork(applicationContext)

        if (networkAvailable) {
            try {
                com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // ARRANQUE PROTEGIDO — red de seguridad de último recurso (21/8/2026).
        //
        // Va PRIMERO y a propósito. Este Worker es el único mecanismo del proyecto que
        // sobrevive a que el proceso muera: WorkManager lo vuelve a levantar cada 15
        // minutos aunque el servicio de primer plano esté caído. Antes NO llamaba al
        // arranque protegido, que es justamente lo que hacía falta: si el proxy global
        // a puerto muerto quedaba puesto y el Watchdog de primer plano no estaba vivo
        // para correr su `tick()` de 20 s, el equipo se quedaba sin internet
        // indefinidamente — hasta que alguien abría la app y apagaba y prendía la VPN
        // a mano. Ese era, textual, el reporte del dueño. Ver util/BootGate.kt y B.20.
        //
        // Con esto el peor caso posible pasa a ser 15 minutos sin internet, y se
        // arregla solo. `healStuckProxy()` es barata: una lectura de Settings.Global
        // que en el caso normal no encuentra nada y sale.
        // Los dos van en try/catch SEPARADOS por el mismo motivo que explica el
        // comentario de abajo: si `tick()` falla, la limpieza del proxy —que es la que
        // devuelve el internet— tiene que correr igual.
        try {
            com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            com.ejemplo.locksuite.util.BootGate.healStuckProxy(applicationContext, "WatchdogWorker 15 min")

            // ── PERÍODO DE GRACIA: EL CIERRE LO GARANTIZA ESTE WORKER (10/9/2026) ──
            //
            // Va acá y no solo en el ciclo de 20 s del servicio de primer plano, porque
            // este Worker es **lo único del proyecto que sobrevive a que muera el
            // proceso**. Un equipo cuya gracia venza con LockSuite caído tiene que
            // cerrarse igual: si no, queda abierto para siempre, que es literal el riesgo
            // que B.11 dejó anotado para la suspensión ("no hay expiración automática").
            //
            // Cuando no hay período activo son dos lecturas de preferencias: no mueve la
            // aguja del costo de este ciclo.
            try {
                com.ejemplo.locksuite.mdm.GracePeriodManager.checkAndHarden(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                com.ejemplo.locksuite.mdm.TempInstallWindowManager.checkAndExpire(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 22/9/2026 — Instalación "en curso" que quedó colgada (reinicio en el medio de
        // una actualización: las alarmas de cierre no sobreviven). Va ANTES de re-aplicar,
        // así esa misma vuelta ya vuelve a poner el bloqueo de instalación. Ver
        // UpdateFlowManager.healStaleInstall().
        try {
            com.ejemplo.locksuite.util.UpdateFlowManager.healStaleInstall(applicationContext, "WatchdogWorker")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Re-aplicar todas las restricciones MDM guardadas.
        // Envuelto en try/catch: antes, una excepción acá (p.ej. un fallo transitorio
        // de Binder con DevicePolicyManager) abortaba el resto de doWork() sin
        // ejecutarse — es decir, ese ciclo también se saltaba la verificación de
        // que la VPN siga viva y la sincronización con Firebase, justo las dos
        // comprobaciones que existen para garantizar "el filtro nunca se cae" y
        // "siempre controlable desde la web". Un Worker de auto-sanación redundante
        // no debe dejar que un fallo puntual en un chequeo cancele los demás.
        try {
            PolicyManager(applicationContext).reapplyAllRestrictions()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Verificar que el servicio de primer plano siga ejecutándose
        if (!WatchdogForegroundService.isRunning()) {
            try {
                val serviceIntent = Intent(applicationContext, WatchdogForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Garantizar que KosherVpnService siga activo si cualquier política lo requiere
        try {
            com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sincronización COMPLETA: una de cada cuatro vueltas, o sea ~1 vez por hora.
        //
        // 2/9/2026 (batería). `syncDeviceInfo()` no es barata: pide el token de FCM,
        // construye un PolicyManager, enumera el estado de ~40 políticas, consulta
        // `getUserRestrictions()` y escribe un nodo grande en Firebase — y encima llama a
        // `syncAppsListInternal()`, que enumera TODAS las apps del equipo. Hacer eso cada
        // 15 minutos es cuatro veces por hora un trabajo que casi nunca cambia entre una
        // vuelta y la siguiente.
        //
        // Lo que sí tiene que pasar siempre —que el panel vea el equipo en línea— ya se
        // hizo arriba con `syncLastSeenOnly()`, que son dos campos.
        //
        // Y lo que hace que esto NO retrase un cambio real: cada comando del panel llama a
        // `syncDeviceInfo()` al terminar de aplicarse (ver LockSuiteFirebaseService), así
        // que el panel refleja los cambios al instante. Esta vuelta periódica solo existe
        // para reconciliar lo que haya cambiado SIN pasar por el panel.
        val vuelta = PrefsHelper.getMdmPrefs(applicationContext)
            .getInt("watchdog_worker_tick", 0)
        PrefsHelper.getMdmPrefs(applicationContext).edit()
            .putInt("watchdog_worker_tick", (vuelta + 1) % 4).apply()
        if (networkAvailable) {
            if (vuelta == 0) {
                try {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.pullWhitelistConfig(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.pullPlayStoreConfig(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            try {
                com.ejemplo.locksuite.util.CommandMailbox.drainNow(applicationContext, "WatchdogWorker")
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                if (com.ejemplo.locksuite.mdm.WhitelistManager(applicationContext).isEnabled()) {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncWhitelistState(applicationContext)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                if (com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
                        .isEmbeddedBrowserFinderEnabled()
                ) {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync
                        .syncEmbeddedBrowserState(applicationContext)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Result.success()
    }

    private fun hasUsableNetwork(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            @Suppress("DEPRECATION")
            val nets = cm.allNetworks
            for (net in nets) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
            }
            false
        } catch (_: Throwable) {
            true
        }
    }
}
