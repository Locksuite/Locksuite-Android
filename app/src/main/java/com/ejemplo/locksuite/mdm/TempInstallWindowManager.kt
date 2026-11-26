package com.ejemplo.locksuite.mdm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ejemplo.locksuite.diag.DiagLog
import com.ejemplo.locksuite.receiver.PackageReceiver
import com.ejemplo.locksuite.service.LockSuiteAccessibilityService
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.UpdateFlowManager

/**
 * TempInstallWindowManager — VENTANA TEMPORAL DE INSTALACIÓN DE APPS.
 *
 * Permite al administrador abrir temporalmente Play Store y desactivar las restricciones de
 * instalación de apps (`DISALLOW_INSTALL_APPS`, `DISALLOW_INSTALL_UNKNOWN_SOURCES`, bloqueo
 * programático de `PackageReceiver`, suspensión/ocultamiento/rebote de Play Store y regla
 * Kiosk/Lock Task) por una duración elegida (1 min .. 30 días).
 *
 * Principios de seguridad y confiabilidad:
 * 1. **Nunca sobrescribe las preferencias del administrador**: solo levanta el estado real en
 *    el sistema mientras la ventana está activa; al vencer o cancelarse, restaura exactamente
 *    lo que estaba configurado antes.
 * 2. **Doble reloj anti-manipulación** (`TempInstallWindowClock`, patrón B.58): reloj de pared
 *    + acumulador monotónico (`elapsedRealtime`), con absorción de saltos NTP.
 * 3. **Cierre garantizado por 4 vías**: `AlarmManager` (con fallback inexacto B.54/B.64),
 *    `WatchdogForegroundService` (20 s), `WatchdogWorker` (15 min) y `ReconcileScheduler`.
 * 4. **Sin colisión con `UpdateFlowManager`**: usa claves propias (`temp_install_*`) y no
 *    toca `mdm_install_in_progress`.
 */
object TempInstallWindowManager {

    private const val TAG = "TempInstallWindow"
    private const val ALARM_REQUEST_CODE = 9933

    const val ACTION_EXPIRED = "com.ejemplo.locksuite.TEMP_INSTALL_WINDOW_EXPIRED"

    const val KEY_ACTIVE = "temp_install_active"
    private const val KEY_STARTED_AT = "temp_install_started_at"
    private const val KEY_DURATION_MS = "temp_install_duration_ms"
    private const val KEY_DEADLINE_WALL = "temp_install_deadline_wall"
    private const val KEY_ACCUM_MS = "temp_install_accumulated_ms"
    private const val KEY_LAST_ELAPSED = "temp_install_last_elapsed_ms"
    private const val KEY_LAST_BOOT_COUNT = "temp_install_last_boot_count"
    private const val KEY_LAST_WALL_SAMPLE = "temp_install_last_wall_sample"
    private const val KEY_SOURCE = "temp_install_source"
    private const val KEY_LAST_CLOSE_REASON = "temp_install_last_close_reason"

    data class Status(
        val active: Boolean,
        val remainingMs: Long,
        val deadlineWall: Long,
        val startedAtWall: Long,
        val durationMs: Long,
        val lastCloseReason: String,
        val source: String
    )

    private val lock = Any()
    @Volatile private var lastDiskSaveRealtime = 0L

    private fun prefs(context: Context) = PrefsHelper.getMdmPrefs(context)

    private fun currentBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    private fun readState(context: Context): TempInstallWindowClock.WindowState? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return null
        val deadline = p.getLong(KEY_DEADLINE_WALL, 0L)
        val duration = p.getLong(KEY_DURATION_MS, 0L)
        if (deadline <= 0L || duration <= 0L) return null
        return TempInstallWindowClock.WindowState(
            active = true,
            startedAtWall = p.getLong(KEY_STARTED_AT, 0L),
            durationMs = duration,
            deadlineWall = deadline,
            accumulatedMs = p.getLong(KEY_ACCUM_MS, 0L),
            lastElapsedMs = p.getLong(KEY_LAST_ELAPSED, 0L),
            lastBootCount = p.getInt(KEY_LAST_BOOT_COUNT, -1),
            lastWallSample = p.getLong(KEY_LAST_WALL_SAMPLE, 0L)
        )
    }

    private fun writeState(context: Context, state: TempInstallWindowClock.WindowState, source: String?) {
        val ed = prefs(context).edit()
            .putBoolean(KEY_ACTIVE, state.active)
            .putLong(KEY_STARTED_AT, state.startedAtWall)
            .putLong(KEY_DURATION_MS, state.durationMs)
            .putLong(KEY_DEADLINE_WALL, state.deadlineWall)
            .putLong(KEY_ACCUM_MS, state.accumulatedMs)
            .putLong(KEY_LAST_ELAPSED, state.lastElapsedMs)
            .putInt(KEY_LAST_BOOT_COUNT, state.lastBootCount)
            .putLong(KEY_LAST_WALL_SAMPLE, state.lastWallSample)
            .remove(KEY_LAST_CLOSE_REASON)
        if (source != null) ed.putString(KEY_SOURCE, source)
        ed.commit()
    }

    private fun clearStatePrefs(context: Context, reason: String) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_STARTED_AT)
            .remove(KEY_DURATION_MS)
            .remove(KEY_DEADLINE_WALL)
            .remove(KEY_ACCUM_MS)
            .remove(KEY_LAST_ELAPSED)
            .remove(KEY_LAST_BOOT_COUNT)
            .remove(KEY_LAST_WALL_SAMPLE)
            .putString(KEY_LAST_CLOSE_REASON, reason)
            .commit()
    }

    fun isActive(context: Context): Boolean {
        val ctx = context.applicationContext
        if (!prefs(ctx).getBoolean(KEY_ACTIVE, false)) return false
        synchronized(lock) {
            val state = readState(ctx) ?: return false
            val tick = TempInstallWindowClock.evaluate(
                state,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
                currentBootCount(ctx)
            )
            if (!tick.expired && tick.active) return true
        }
        // Si venció justo ahora, cerramos y restauramos restricciones de inmediato.
        checkAndExpire(ctx)
        return false
    }

    fun remainingMs(context: Context): Long {
        val ctx = context.applicationContext
        val state = readState(ctx) ?: return 0L
        val tick = TempInstallWindowClock.evaluate(
            state,
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            currentBootCount(ctx)
        )
        return tick.remainingMs
    }

    fun statusForSync(context: Context): Status {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val state = readState(ctx)
        if (state == null) {
            return Status(
                active = false,
                remainingMs = 0L,
                deadlineWall = 0L,
                startedAtWall = 0L,
                durationMs = 0L,
                lastCloseReason = p.getString(KEY_LAST_CLOSE_REASON, "") ?: "",
                source = p.getString(KEY_SOURCE, "") ?: ""
            )
        }
        val tick = TempInstallWindowClock.evaluate(
            state,
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            currentBootCount(ctx)
        )
        return Status(
            active = tick.active && !tick.expired,
            remainingMs = tick.remainingMs,
            deadlineWall = tick.updatedState.deadlineWall,
            startedAtWall = state.startedAtWall,
            durationMs = state.durationMs,
            lastCloseReason = p.getString(KEY_LAST_CLOSE_REASON, "") ?: "",
            source = p.getString(KEY_SOURCE, "") ?: ""
        )
    }

    fun start(context: Context, durationMs: Long, source: String = "panel"): Boolean {
        if (!TempInstallWindowClock.validateDuration(durationMs)) {
            Log.w(TAG, "Duración inválida rechazada: $durationMs ms")
            return false
        }
        val ctx = context.applicationContext
        val pm = PolicyManager(ctx)
        if (pm.isLockSuiteSuspended()) {
            Log.w(TAG, "LockSuite está suspendido: no hace falta ventana temporal")
            return false
        }

        synchronized(lock) {
            val nowWall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val bootCount = currentBootCount(ctx)
            val state = TempInstallWindowClock.createState(nowWall, elapsed, bootCount, durationMs)
            lastDiskSaveRealtime = elapsed
            writeState(ctx, state, source)
            scheduleAlarm(ctx, durationMs)
            pm.applyTempInstallWindowOpen()
        }

        DiagLog.i("temp_install", "Ventana temporal iniciada ($source): ${TempInstallWindowClock.formatRemaining(durationMs)}")
        Log.i(TAG, "🔓 Ventana temporal de instalación iniciada ($source) por ${durationMs / 60_000L} min")
        try {
            FirebaseDeviceSync.requestSync(ctx)
        } catch (_: Exception) {
        }
        return true
    }

    fun stop(context: Context, reason: String = "cancelado"): Boolean {
        val ctx = context.applicationContext
        val wasActive: Boolean
        synchronized(lock) {
            wasActive = prefs(ctx).getBoolean(KEY_ACTIVE, false)
            clearStatePrefs(ctx, reason)
            cancelAlarm(ctx)
        }

        val pm = PolicyManager(ctx)
        if (!pm.isLockSuiteSuspended() && !UpdateFlowManager.mustKeepPlayStoreOpen(ctx)) {
            pm.applyTempInstallWindowClosed()
            LockSuiteAccessibilityService.onTempInstallWindowClosed()
        }

        if (wasActive) {
            DiagLog.i("temp_install", "Ventana temporal cerrada ($reason)")
            Log.i(TAG, "🔒 Ventana temporal de instalación cerrada ($reason)")
        }
        try {
            FirebaseDeviceSync.requestSync(ctx)
        } catch (_: Exception) {
        }
        return true
    }

    /** Limpieza silenciosa cuando se suspende o purga LockSuite por completo. */
    fun clearSilent(context: Context, reason: String) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (!prefs(ctx).getBoolean(KEY_ACTIVE, false)) return
            clearStatePrefs(ctx, reason)
            cancelAlarm(ctx)
        }
    }

    /**
     * Evalúa los dos relojes y cierra la ventana si venció.
     * Barato cuando está inactiva (una sola lectura booleana de SharedPreferences).
     *
     * @return `true` si la ventana venció y se cerró en esta llamada.
     */
    fun checkAndExpire(context: Context): Boolean {
        val ctx = context.applicationContext
        if (!prefs(ctx).getBoolean(KEY_ACTIVE, false)) return false

        val closeReason: String?
        synchronized(lock) {
            val state = readState(ctx)
            if (state == null) {
                clearStatePrefs(ctx, "estado_invalido")
                cancelAlarm(ctx)
                closeReason = "estado_invalido"
            } else {
                val elapsed = SystemClock.elapsedRealtime()
                val tick = TempInstallWindowClock.evaluate(
                    state,
                    System.currentTimeMillis(),
                    elapsed,
                    currentBootCount(ctx)
                )

                if (tick.expired) {
                    closeReason = tick.expireReason ?: "vencido"
                    clearStatePrefs(ctx, closeReason)
                    cancelAlarm(ctx)
                } else {
                    closeReason = null
                    if (tick.rebootDetected ||
                        tick.updatedState.deadlineWall != state.deadlineWall ||
                        elapsed - lastDiskSaveRealtime >= 60_000L
                    ) {
                        writeState(ctx, tick.updatedState, null)
                        lastDiskSaveRealtime = elapsed
                        if (tick.rebootDetected) {
                            scheduleAlarm(ctx, tick.remainingMs.coerceAtLeast(1_000L))
                        }
                    }
                }
            }
        }

        if (closeReason == null) return false

        val pm = PolicyManager(ctx)
        if (!pm.isLockSuiteSuspended() && !UpdateFlowManager.mustKeepPlayStoreOpen(ctx)) {
            pm.applyTempInstallWindowClosed()
            LockSuiteAccessibilityService.onTempInstallWindowClosed()
        }
        DiagLog.i("temp_install", "Ventana temporal vencida ($closeReason)")
        Log.i(TAG, "🔒 Ventana temporal cerrada automáticamente: $closeReason")
        try {
            FirebaseDeviceSync.requestSync(ctx)
        } catch (_: Exception) {
        }
        return true
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PackageReceiver::class.java).apply {
            action = ACTION_EXPIRED
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    private fun scheduleAlarm(context: Context, delayMs: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = alarmPendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(5_000L)
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w(TAG, "Sin permiso de alarma exacta: cierre programado inexacto")
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo programar alarma de cierre temporal: ${e.message}")
        }
    }

    private fun cancelAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(alarmPendingIntent(context))
        } catch (_: Exception) {
        }
    }
}
