package com.ejemplo.locksuite.util

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.ejemplo.locksuite.diag.DiagLog
import java.io.File
import java.io.FileInputStream

/**
 * 7/11/2026 (B.112) — AUTORREPARACIÓN DE LA ACCESIBILIDAD «ZOMBI».
 *
 * Hasta B.112 un zombi (servicio «Activado» en Ajustes pero que Android no vuelve a enlazar
 * después de que el proceso murió) solo se curaba con una persona apagando y prendiendo el
 * interruptor en Ajustes, o con una actualización de LockSuite desde el panel. R Shaul Said no
 * sabe hacer lo primero y lo segundo depende de que el dueño se entere. Esto lo cura solo, en
 * minutos, en cualquier celular de la flota. El porqué de cada paso y los límites que impiden un
 * bucle están en `ZombieHealPolicy` (lógica pura, con pruebas).
 *
 * Cómo se usa:
 *  - `tick()` desde el ciclo de accesibilidad del Watchdog (cada 5 s con la accesibilidad caída,
 *    60 s con todo bien). Con todo bien cuesta una consulta al AccessibilityManager.
 *  - `onProcessStart()` al arrancar el proceso ya desbloqueado: termina un ciclo del ajuste que
 *    quedó a medias si el proceso murió en el medio (si no, el servicio quedaría APAGADO de
 *    verdad, que es peor que zombi).
 *
 * Qué deja anotado: cada paso en el registro de diagnóstico (etiqueta `a11y-heal`, se ve en el
 * panel) y, al terminar, `a11yHealCount` / `a11yHealLastAt` / `a11yHealLastResult` en
 * `devices/<id>`.
 *
 * ⚠️ SIN PROBAR EN UN EQUIPO REAL cuando se escribió. Prueba: `adb shell am crash
 * com.ejemplo.locksuite` con la accesibilidad enlazada y ver en `adb shell dumpsys accessibility`
 * que figura en «Crashed services»; a los ~4 min tiene que volver sola.
 */
object AccessibilityZombieHealer {

    private const val TAG = "ZombieHealer"
    private const val DIAG = "a11y-heal"

    private const val KEY_REINSTALL_HISTORY = "a11y_heal_reinstall_history"
    private const val KEY_PENDING_REINSTALL_AT = "a11y_heal_pending_reinstall_at"
    private const val KEY_TOGGLE_RESTORE = "a11y_heal_toggle_restore"
    private const val KEY_HEAL_COUNT = "a11y_heal_count"

    /** Si una reinstalación pedida no terminó con el proceso muerto en este tiempo, se la da por fallida. */
    private const val REINSTALL_IN_FLIGHT_MS = 10 * 60_000L
    /** Cuánto se espera, con una reinstalación anotada, antes de declarar que no alcanzó. */
    private const val PENDING_VERDICT_MS = 10 * 60_000L
    private const val TOGGLE_OFF_MS = 2_000L

    @Volatile private var zombieSinceElapsed: Long? = null
    @Volatile private var lastToggleElapsed: Long? = null
    @Volatile private var reinstallStartedElapsed = 0L
    @Volatile private var toggleRestoreScheduled = false

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun tick(context: Context) {
        Safe.guard("ZombieHealer.tick") { tickInternal(context.applicationContext) }
    }

    fun onProcessStart(context: Context) {
        Safe.guard("ZombieHealer.onProcessStart") {
            val ctx = context.applicationContext
            // Un ciclo del ajuste que quedó a medias (el proceso murió en los 2 s): reponer YA.
            if (PrefsHelper.getMdmPrefs(ctx).contains(KEY_TOGGLE_RESTORE)) {
                restoreAfterToggle(ctx, "al arrancar el proceso")
            }
            if (PrefsHelper.getMdmPrefs(ctx).getLong(KEY_PENDING_REINSTALL_AT, 0L) != 0L) {
                DiagLog.i(DIAG, "El proceso arrancó con una reinstalación de reparación anotada; se verifica en el próximo ciclo")
            }
        }
    }

    private fun tickInternal(ctx: Context) {
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        if (!toggleRestoreScheduled && prefs.contains(KEY_TOGGLE_RESTORE)) {
            restoreAfterToggle(ctx, "red de seguridad del ciclo")
        }

        val now = SystemClock.elapsedRealtime()
        val zombie = AccessibilityEnforcer.isZombie(ctx)
        if (!zombie) {
            if (zombieSinceElapsed != null) {
                DiagLog.i(DIAG, "La accesibilidad volvió a estar enlazada (fin del zombi)")
            }
            zombieSinceElapsed = null
            reportPendingReinstallOutcome(ctx, recovered = true)
            return
        }

        val since = zombieSinceElapsed ?: now.also {
            zombieSinceElapsed = it
            DiagLog.w(DIAG, "Accesibilidad ZOMBI: habilitada en Ajustes pero Android no la enlaza. Reparación automática en unos minutos si no vuelve sola")
        }

        // Una reinstalación anotada que no la arregló: se avisa y se libera el lugar para el próximo intento.
        val pendingAt = prefs.getLong(KEY_PENDING_REINSTALL_AT, 0L)
        if (pendingAt != 0L && System.currentTimeMillis() - pendingAt > PENDING_VERDICT_MS) {
            reportPendingReinstallOutcome(ctx, recovered = false)
        }

        val decision = ZombieHealPolicy.decide(
            ZombieHealPolicy.Input(
                zombieSinceElapsed = since,
                nowElapsed = now,
                nowWall = System.currentTimeMillis(),
                reinstallAttemptsWall = ZombieHealPolicy.parseHistory(prefs.getString(KEY_REINSTALL_HISTORY, null)),
                canWriteSecureSettings = canWriteSecureSettings(ctx),
                lastToggleElapsed = lastToggleElapsed,
                blockedReason = blockedReason(ctx, now),
            )
        )
        when (decision.action) {
            ZombieHealPolicy.Action.NONE -> Unit
            ZombieHealPolicy.Action.TOGGLE_SETTING -> {
                lastToggleElapsed = now
                toggleSetting(ctx)
            }
            ZombieHealPolicy.Action.REINSTALL -> startReinstall(ctx, now)
        }
    }

    private fun blockedReason(ctx: Context, nowElapsed: Long): String? {
        if (reinstallStartedElapsed != 0L && nowElapsed - reinstallStartedElapsed < REINSTALL_IN_FLIGHT_MS) {
            return "reinstalación en curso"
        }
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        if (prefs.getBoolean("locksuite_purged", false)) return "LockSuite purgado"
        if (prefs.getBoolean("mdm_install_in_progress", false)) return "hay una instalación en curso"
        if (try { com.ejemplo.locksuite.mdm.PolicyManager(ctx).isLockSuiteSuspended() } catch (_: Throwable) { false }) {
            return "LockSuite suspendido"
        }
        if (try { UpdateFlowManager.isRunning(ctx) } catch (_: Throwable) { false }) {
            return "flujo de actualización de apps en curso"
        }
        return null
    }

    private fun canWriteSecureSettings(ctx: Context): Boolean = try {
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun ourComponent(ctx: Context): ComponentName =
        ComponentName(ctx, com.ejemplo.locksuite.service.LockSuiteAccessibilityService::class.java)

    // ── Reparación liviana: apagar y prender por ajuste seguro ─────────────────────────────────

    private fun toggleSetting(ctx: Context) {
        val cr = ctx.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val comp = ourComponent(ctx)
        val original = Settings.Secure.getString(cr, key)
        val conNuestro = ZombieHealPolicy.withComponent(original, comp.packageName, comp.className)
        val sinNuestro = ZombieHealPolicy.withoutComponent(original, comp.packageName, comp.className)
        // Primero se guarda cómo tiene que quedar: si el proceso muere en los próximos 2 s,
        // `onProcessStart()` lo repone. Sin esto el servicio quedaría APAGADO de verdad.
        PrefsHelper.getMdmPrefs(ctx).edit().putString(KEY_TOGGLE_RESTORE, conNuestro).commit()
        DiagLog.w(DIAG, "Reparando zombi: se apaga y se vuelve a prender el servicio por ajuste seguro")
        Settings.Secure.putString(cr, key, sinNuestro)
        toggleRestoreScheduled = true
        mainHandler.postDelayed({ restoreAfterToggle(ctx, "ciclo del ajuste") }, TOGGLE_OFF_MS)
    }

    private fun restoreAfterToggle(ctx: Context, origen: String) {
        Safe.guard("ZombieHealer.restoreAfterToggle") {
            toggleRestoreScheduled = false
            val prefs = PrefsHelper.getMdmPrefs(ctx)
            val valor = prefs.getString(KEY_TOGGLE_RESTORE, null) ?: return
            val cr = ctx.contentResolver
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, valor)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            prefs.edit().remove(KEY_TOGGLE_RESTORE).apply()
            DiagLog.i(DIAG, "Servicio vuelto a habilitar tras el ciclo del ajuste ($origen)")
            report(ctx, "ajuste: servicio apagado y prendido ($origen)", sumar = true)
        }
    }

    // ── Reparación fuerte: reinstalar el propio APK ─────────────────────────────────────────────

    private fun startReinstall(ctx: Context, nowElapsed: Long) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(ctx.packageName)) {
            DiagLog.w(DIAG, "Zombi sin reparar: LockSuite no es Device Owner y no puede reinstalarse en silencio")
            reinstallStartedElapsed = nowElapsed // no reintentar en cada ciclo
            return
        }
        reinstallStartedElapsed = nowElapsed
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        // El intento se anota ANTES: si el proceso muere a mitad, cuenta igual para los límites.
        prefs.edit()
            .putString(
                KEY_REINSTALL_HISTORY,
                ZombieHealPolicy.appendHistory(prefs.getString(KEY_REINSTALL_HISTORY, null), System.currentTimeMillis())
            )
            .putLong(KEY_PENDING_REINSTALL_AT, System.currentTimeMillis())
            .commit()
        DiagLog.w(DIAG, "Reparando zombi: se reinstala el propio APK para que Android levante la cuarentena del servicio")
        Thread({
            val error = try {
                reinstallOwnApk(ctx)
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            if (error != null) {
                reinstallStartedElapsed = 0L
                try { PrefsHelper.getMdmPrefs(ctx).edit().remove(KEY_PENDING_REINSTALL_AT).apply() } catch (_: Throwable) { }
                DiagLog.e(DIAG, "No se pudo reinstalar para reparar la accesibilidad: $error")
                report(ctx, "reinstalación falló: $error", sumar = false)
            }
            // Sin error: Android reemplaza el paquete y mata este proceso. El resultado lo anota
            // el proceso nuevo en su primer ciclo (`reportPendingReinstallOutcome`).
        }, "LockSuiteA11yHeal").start()
    }

    /**
     * Mismo camino que la actualización por `UPDATE_LOCKSUITE` (`SelfUpdater.checkAndPerformUpdate`),
     * pero con los APK ya instalados en vez de uno bajado: no hay nada que verificar porque son
     * exactamente los bytes que están corriendo. Devuelve null si el commit salió.
     */
    private fun reinstallOwnApk(ctx: Context): String? {
        val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        val apks = ArrayList<File>()
        apks.add(File(ai.sourceDir))
        ai.splitSourceDirs?.forEach { apks.add(File(it)) }
        val ilegible = apks.firstOrNull { !it.canRead() || it.length() <= 0L }
        if (ilegible != null) return "no se puede leer ${ilegible.name}"

        var prepared = false
        var committed = false
        try {
            if (!SelfUpdater.prepareTemporaryInstallAccess(ctx)) {
                return "no se pudieron preparar los permisos temporales de instalación"
            }
            prepared = true
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                apks.forEachIndexed { idx, apk ->
                    val nombre = if (idx == 0) "base.apk" else "split_$idx.apk"
                    session.openWrite(nombre, 0, apk.length()).use { out ->
                        FileInputStream(apk).use { input -> input.copyTo(out, 65536) }
                        session.fsync(out)
                    }
                }
                val intent = Intent(ctx, com.ejemplo.locksuite.receiver.PackageInstallStatusReceiver::class.java)
                    .putExtra(com.ejemplo.locksuite.receiver.PackageInstallStatusReceiver.EXTRA_LS_PACKAGE, ctx.packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                session.commit(pendingIntent.intentSender)
                committed = true
            } finally {
                if (!committed) {
                    try { session.abandon() } catch (_: Exception) { }
                }
                try { session.close() } catch (_: Exception) { }
            }
            return null
        } catch (e: Exception) {
            if (prepared && !committed) {
                try { com.ejemplo.locksuite.mdm.PolicyManager(ctx).restoreInstallRestrictions() } catch (_: Exception) { }
            }
            return e.message ?: e.javaClass.simpleName
        }
    }

    private fun reportPendingReinstallOutcome(ctx: Context, recovered: Boolean) {
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val at = prefs.getLong(KEY_PENDING_REINSTALL_AT, 0L)
        if (at == 0L) return
        prefs.edit().remove(KEY_PENDING_REINSTALL_AT).apply()
        reinstallStartedElapsed = 0L
        val segundos = (System.currentTimeMillis() - at) / 1000
        if (recovered) {
            DiagLog.i(DIAG, "Accesibilidad reparada sola tras reinstalar el APK (${segundos} s)")
            report(ctx, "reinstalación: reparada en ${segundos} s", sumar = true)
        } else {
            DiagLog.e(DIAG, "La reinstalación de hace ${segundos / 60} min no reparó la accesibilidad")
            report(ctx, "reinstalación: NO alcanzó (${segundos / 60} min)", sumar = false)
        }
    }

    private fun report(ctx: Context, result: String, sumar: Boolean) {
        try {
            val prefs = PrefsHelper.getMdmPrefs(ctx)
            var count = prefs.getInt(KEY_HEAL_COUNT, 0)
            if (sumar) {
                count += 1
                prefs.edit().putInt(KEY_HEAL_COUNT, count).apply()
            }
            FirebaseDeviceSync.reportFields(
                ctx,
                mapOf(
                    "a11yHealCount" to count,
                    "a11yHealLastAt" to System.currentTimeMillis(),
                    "a11yHealLastResult" to result,
                )
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "No se pudo informar el resultado: ${t.message}")
        }
    }
}
