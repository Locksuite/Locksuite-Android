package com.ejemplo.locksuite.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Punto de entrada de FCM. Desde el 22/9/2026 solo delega: la ejecución de comandos vive
 * en [CommandProcessor], que comparte con el buzón de la base de datos
 * (`util/CommandMailbox.kt`). Ver el encabezado de CommandProcessor para el porqué.
 */
class LockSuiteFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // 19/11/2026 (B.125, T3.1) — LATIDO INVERSO. Una función programada del panel le manda
        // `wake=1` (FCM de prioridad alta) a los equipos que llevan un rato sin latir. Despierta
        // el proceso, vuelve a levantar el Watchdog y la VPN, y avisa que está vivo. NO es un
        // comando: sin firma, sin PIN, sin ACK y no ejecuta ninguna acción del panel.
        if (message.data["wake"] == "1") {
            com.ejemplo.locksuite.diag.DiagLog.firstEntry("FCM WAKE del servidor")
            com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(this, "FCM WAKE")
            com.ejemplo.locksuite.util.Safe.guard("FCM.wake") {
                val watchdog = android.content.Intent(this, WatchdogForegroundService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(watchdog)
                } else {
                    startService(watchdog)
                }
                com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(this)
                com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(this)
            }
            return
        }
        com.ejemplo.locksuite.diag.DiagLog.firstEntry("FCM")
        // 7/11/2026 (B.112): un comando puede llegar justo al desbloquear, antes de que corra la
        // inicialización completa (Firebase incluido) en un proceso nacido con el equipo bloqueado.
        com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(this, "FCM")
        // Y con red: una excepción acá (hilo de FCM) mataba el proceso.
        com.ejemplo.locksuite.util.Safe.guard("FCM.onMessageReceived") {
            CommandProcessor(this).process(message.data, CommandProcessor.Source.FCM)
        }
    }

    /**
     * FCM descartó mensajes pendientes para este equipo (más de 100 en cola, o vencidos
     * mientras estaba sin red). Antes esto no estaba implementado y esos comandos se
     * perdían sin rastro. Ahora se drena el buzón (ahí está cada comando firmado) y se
     * relee la configuración de apps, que es estado y no comando.
     */
    override fun onDeletedMessages() {
        try {
            com.ejemplo.locksuite.util.CommandMailbox.drainAsync(applicationContext, "FCM descartó mensajes")
            com.ejemplo.locksuite.util.FirebaseDeviceSync.pullWhitelistConfigAsync(applicationContext)
            com.ejemplo.locksuite.util.FirebaseDeviceSync.pullPlayStoreConfigAsync(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNewToken(token: String) {
        com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(this, "FCM token nuevo")
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncToken(this, token)
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
