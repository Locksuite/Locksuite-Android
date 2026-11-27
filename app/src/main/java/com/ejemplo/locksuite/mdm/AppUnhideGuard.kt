package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.ejemplo.locksuite.util.PrefsHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Guardia contra falsas detecciones de "instalación no autorizada" al des-ocultar una app.
 *
 * En Android, llamar a `dpm.setApplicationHidden(admin, pkg, false)` provoca que el sistema
 * emita `Intent.ACTION_PACKAGE_ADDED` con `EXTRA_REPLACING = false`, idéntico al de una
 * instalación nueva. Sin esta marca previa, `PackageReceiver` interpretaba el des-ocultamiento
 * como una instalación no autorizada cuando `DISALLOW_INSTALL_APPS` estaba activo y
 * desinstalaba la app en el acto.
 */
object AppUnhideGuard {

    const val UNHIDE_WINDOW_MS = 60_000L

    private const val PREF_PREFIX_ELAPSED = "unhide_mark_elapsed_"
    private const val PREF_PREFIX_BOOT = "unhide_mark_boot_"

    private data class UnhideMark(val elapsedMs: Long, val bootCount: Int)

    private val inMemoryMarks = ConcurrentHashMap<String, UnhideMark>()

    internal fun isWithinWindow(
        markedElapsedMs: Long,
        markedBootCount: Int,
        nowElapsedMs: Long,
        nowBootCount: Int,
        windowMs: Long = UNHIDE_WINDOW_MS
    ): Boolean {
        if (markedElapsedMs <= 0L) return false
        if ((markedBootCount >= 0 || nowBootCount >= 0) && markedBootCount != nowBootCount) {
            return false
        }
        val delta = nowElapsedMs - markedElapsedMs
        return delta in 0L..windowMs
    }

    internal fun isIntentionalUnhideBroadcast(
        isReplacing: Boolean,
        recentlyUnhidden: Boolean
    ): Boolean = !isReplacing && recentlyUnhidden

    private fun currentBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /**
     * Registra en memoria y en preferencias (síncrono) que [packageName] está siendo
     * des-ocultado intencionalmente por LockSuite. Debe llamarse ANTES de `setApplicationHidden(false)`.
     */
    fun markUnhiding(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val bootCount = currentBootCount(context)
        inMemoryMarks[packageName] = UnhideMark(nowElapsed, bootCount)
        try {
            PrefsHelper.getMdmPrefs(context).edit()
                .putLong(PREF_PREFIX_ELAPSED + packageName, nowElapsed)
                .putInt(PREF_PREFIX_BOOT + packageName, bootCount)
                .commit()
        } catch (_: Exception) {
        }
    }

    /**
     * Devuelve `true` si [packageName] fue des-ocultado por LockSuite dentro de la ventana
     * activa y sin reinicio intermedio.
     */
    fun isRecentlyUnhidden(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val nowElapsed = SystemClock.elapsedRealtime()
        val bootCount = currentBootCount(context)

        val mem = inMemoryMarks[packageName]
        if (mem != null) {
            if (isWithinWindow(mem.elapsedMs, mem.bootCount, nowElapsed, bootCount)) {
                return true
            }
            inMemoryMarks.remove(packageName, mem)
        }

        val prefs = try {
            PrefsHelper.getMdmPrefs(context)
        } catch (_: Exception) {
            return false
        }
        val savedElapsed = prefs.getLong(PREF_PREFIX_ELAPSED + packageName, 0L)
        if (savedElapsed <= 0L) return false
        val savedBoot = prefs.getInt(PREF_PREFIX_BOOT + packageName, -1)
        if (isWithinWindow(savedElapsed, savedBoot, nowElapsed, bootCount)) {
            inMemoryMarks[packageName] = UnhideMark(savedElapsed, savedBoot)
            return true
        }
        prefs.edit()
            .remove(PREF_PREFIX_ELAPSED + packageName)
            .remove(PREF_PREFIX_BOOT + packageName)
            .apply()
        return false
    }

    /** Limpia la marca de des-ocultamiento para [packageName]. */
    fun clear(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        inMemoryMarks.remove(packageName)
        try {
            PrefsHelper.getMdmPrefs(context).edit()
                .remove(PREF_PREFIX_ELAPSED + packageName)
                .remove(PREF_PREFIX_BOOT + packageName)
                .commit()
        } catch (_: Exception) {
        }
    }

    /**
     * Marca [packageName] como des-ocultado intencionalmente y ejecuta `dpm.setApplicationHidden(admin, pkg, false)`.
     * Si el des-ocultamiento falla o lanza excepción y la app sigue oculta, limpia la marca.
     */
    fun unhideSafely(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String
    ): Boolean {
        markUnhiding(context, packageName)
        val applied = try {
            dpm.setApplicationHidden(admin, packageName, false)
        } catch (e: Exception) {
            clear(context, packageName)
            throw e
        }
        if (!applied) {
            val stillHidden = try {
                dpm.isApplicationHidden(admin, packageName)
            } catch (_: Exception) {
                true
            }
            if (stillHidden) {
                clear(context, packageName)
            }
        }
        return applied
    }
}
