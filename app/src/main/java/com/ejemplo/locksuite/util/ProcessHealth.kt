package com.ejemplo.locksuite.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.ejemplo.locksuite.diag.DiagLog
import com.ejemplo.locksuite.diag.DiagStore
import com.ejemplo.locksuite.diag.ForceStopVerdict
import com.ejemplo.locksuite.receiver.ReviveReceiver

/**
 * 19/11/2026 (B.125, T2.3 y T3.2) — SALUD DEL PROCESO: ¿ME DETUVIERON A LA FUERZA? y ALARMA
 * DE RESCATE.
 *
 * ── LA SENTINELA (detección de detención forzada) ────────────────────────────────────
 * Una detención forzada borra las alarmas y los `PendingIntent` del paquete. Al arrancar se
 * busca un `PendingIntent` con `FLAG_NO_CREATE`, sostenido por una alarma muy lejana (el
 * mismo truco de `ForceStopRunnable` de WorkManager; la alarma es lo que mantiene vivo el
 * `PendingIntent` dentro del sistema). Si no está y el paquete no se actualizó, alguien forzó
 * la detención: queda anotado. Después se vuelve a armar. La cuenta está en [ForceStopVerdict].
 *
 * ── LA ALARMA DE RESCATE (T3.2) ──────────────────────────────────────────────────────
 * El Watchdog (cada ciclo) y el Worker la corren siempre hacia adelante, a 20 min, como mucho
 * una vez cada 5. Mientras LockSuite vive NUNCA suena. Si el proceso muere sin una detención
 * forzada, suena a los 20 min o antes y [ReviveReceiver] vuelve a arrancar el Watchdog y la
 * VPN. Es un mecanismo distinto de WorkManager (JobScheduler): diversidad barata, un IPC cada
 * 5 min. Un Device Owner está exento de la restricción de Android 12+ para arrancar servicios
 * en primer plano desde segundo plano.
 *
 * Nada de acá lanza hacia afuera.
 */
object ProcessHealth {

    const val ACTION_SENTINEL = "com.ejemplo.locksuite.action.SENTINEL"
    const val ACTION_REVIVE = "com.ejemplo.locksuite.action.REVIVE"
    private const val REQ_SENTINEL = 7125
    private const val REQ_REVIVE = 7126
    private const val SENTINEL_DELAY_MS = 365L * 24 * 3600_000L
    private const val RESCUE_DELAY_MS = 20 * 60_000L
    private const val RESCUE_REARM_EVERY_MS = 5 * 60_000L
    private const val KEY_BOOT_COUNT = "boot_count"

    @Volatile private var rescueArmedAt = 0L

    private fun flagsImmutable(extra: Int = 0): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) extra or PendingIntent.FLAG_IMMUTABLE else extra

    private fun intent(ctx: Context, action: String) =
        Intent(ctx, ReviveReceiver::class.java).setAction(action)

    private fun currentBootCount(app: Context): Long = try {
        android.provider.Settings.Global.getInt(
            app.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT,
            -1
        ).toLong()
    } catch (_: Throwable) {
        -1L
    }

    /**
     * Devuelve `true` solo si ambos conteos de arranque son válidos y pertenecen al MISMO arranque.
     * En un reinicio limpio del equipo Android borra todas las alarmas y PendingIntents, así que
     * un cambio de `BOOT_COUNT` descarta un falso positivo de detención forzada.
     */
    internal fun isSameBoot(currentBoot: Long, savedBoot: Long): Boolean =
        currentBoot >= 0L && savedBoot >= 0L && currentBoot == savedBoot

    /** Al arrancar el proceso (ya desbloqueado). Anota la detención forzada si la hubo. */
    @JvmStatic
    fun onProcessStart(context: Context) {
        try {
            val app = context.applicationContext ?: context
            val lastUpdate = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            val saved = DiagStore.getLong("last_update_time", 0L)
            val currentBoot = currentBootCount(app)
            val savedBoot = DiagStore.getLong(KEY_BOOT_COUNT, -1L)
            val sentinelPresent = PendingIntent.getBroadcast(
                app, REQ_SENTINEL, intent(app, ACTION_SENTINEL),
                flagsImmutable(PendingIntent.FLAG_NO_CREATE)
            ) != null
            val rebootDetected = currentBoot >= 0L && savedBoot >= 0L && currentBoot != savedBoot
            val canJudgeForceStop = !rebootDetected && (isSameBoot(currentBoot, savedBoot) || currentBoot < 0L)
            if (canJudgeForceStop &&
                ForceStopVerdict.decide(sentinelPresent, lastUpdate, saved) == ForceStopVerdict.Verdict.FORCE_STOPPED
            ) {
                DiagLog.w(
                    "app",
                    "Arranque después de una DETENCIÓN FORZADA del paquete (se cancelaron alarmas y trabajos)"
                )
            }
            DiagStore.putLong("last_update_time", lastUpdate)
            if (currentBoot >= 0L) {
                DiagStore.putLong(KEY_BOOT_COUNT, currentBoot)
            }
            armSentinel(app)
        } catch (t: Throwable) {
            android.util.Log.w("ProcessHealth", "onProcessStart: ${t.message}")
        }
    }

    /** Cancela todas las alarmas de rescate y sentinela (usado en purga total / liberación). */
    @JvmStatic
    fun cancelAll(context: Context) {
        try {
            val app = context.applicationContext ?: context
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            listOf(REQ_REVIVE to ACTION_REVIVE, REQ_SENTINEL to ACTION_SENTINEL).forEach { (req, action) ->
                try {
                    val pi = PendingIntent.getBroadcast(
                        app, req, intent(app, action),
                        flagsImmutable(PendingIntent.FLAG_NO_CREATE)
                    )
                    if (pi != null) {
                        am?.cancel(pi)
                        pi.cancel()
                    }
                } catch (_: Throwable) {}
            }
            rescueArmedAt = 0L
        } catch (t: Throwable) {
            android.util.Log.w("ProcessHealth", "cancelAll: ${t.message}")
        }
    }

    private fun armSentinel(app: Context) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            app, REQ_SENTINEL, intent(app, ACTION_SENTINEL),
            flagsImmutable(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        am.set(AlarmManager.RTC, System.currentTimeMillis() + SENTINEL_DELAY_MS, pi)
    }

    /**
     * Corre la alarma de rescate 20 min hacia adelante, como mucho cada 5 min (salvo `force`).
     * Barato: una comparación en memoria casi siempre.
     */
    @JvmStatic
    @JvmOverloads
    fun scheduleRescueIfDue(context: Context, force: Boolean = false) {
        try {
            val nowE = SystemClock.elapsedRealtime()
            if (!force && rescueArmedAt != 0L && nowE - rescueArmedAt < RESCUE_REARM_EVERY_MS) return
            val app = context.applicationContext ?: context
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                app, REQ_REVIVE, intent(app, ACTION_REVIVE),
                flagsImmutable(PendingIntent.FLAG_UPDATE_CURRENT)
            )
            val triggerAt = nowE + RESCUE_DELAY_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            rescueArmedAt = nowE
        } catch (t: Throwable) {
            android.util.Log.w("ProcessHealth", "scheduleRescueIfDue: ${t.message}")
        }
    }
}
