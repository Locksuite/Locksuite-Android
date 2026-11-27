package com.ejemplo.locksuite.mdm

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.ejemplo.locksuite.mdm.AndroidAutoStubs.StubPlanAction
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.ejemplo.locksuite.receiver.PackageInstallStatusReceiver
import com.ejemplo.locksuite.receiver.PackageReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instalador automático de los stubs embebidos de Android Auto (`assets/aa_stubs/`).
 *
 * Se ejecuta al instalar o actualizar `com.google.android.projection.gearhead`.
 * Regla estricta: NUNCA desinstala Google Maps ni la App de Google reales; instala cada stub
 * solo cuando falta y avisa claramente si alguna de las reales ya existe en el equipo.
 */
object AndroidAutoStubInstaller {

    private const val TAG = "AAStubInstaller"

    data class StubNotice(
        val id: Long,
        val title: String,
        val message: String,
        val isWarning: Boolean
    )

    private val _lastNotice = MutableStateFlow<StubNotice?>(null)
    val lastNotice: StateFlow<StubNotice?> get() = _lastNotice

    private val lock = Any()
    private val inFlight = AtomicBoolean(false)
    private val pendingQueue = ArrayDeque<String>()
    private val blockedRealMsgs = ArrayList<String>()
    private val installedStubs = ArrayList<String>()
    private val failedStubs = ArrayList<String>()
    private var noticeSeq = 0L

    fun clearNotice(id: Long) {
        if (_lastNotice.value?.id == id) {
            _lastNotice.value = null
        }
    }

    /**
     * Verifica e instala los stubs que falten para Android Auto (en segundo plano).
     * Siempre devuelve rápido sin bloquear el hilo principal.
     */
    fun ensureInstalledForAndroidAuto(context: Context) {
        val ctx = context.applicationContext ?: context
        if (!AndroidAutoStubs.isDeviceCompatible(ctx)) return
        if (!inFlight.compareAndSet(false, true)) return

        Thread({
            try {
                startSequence(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "ensureInstalledForAndroidAuto falló: ${e.message}", e)
                finishSequence(ctx, restoreRestrictions = true)
            }
        }, "LockSuiteAAStubs").start()
    }

    private fun startSequence(ctx: Context) {
        synchronized(lock) {
            pendingQueue.clear()
            blockedRealMsgs.clear()
            installedStubs.clear()
            failedStubs.clear()
        }

        // Asegurar visibilidad de Android Auto y de cualquier stub ya instalado
        ensureAndroidAutoAndStubsVisible(ctx)

        val toInstall = ArrayList<String>(2)
        for (pkg in listOf(AndroidAutoStubs.PKG_MAPS, AndroidAutoStubs.PKG_GOOGLE_APP)) {
            val isStub = AndroidAutoStubs.isStub(ctx, pkg)
            val imp = AndroidAutoStubs.impedimentoParaInstalar(ctx, pkg)
            val plan = AndroidAutoStubs.planStubAction(isStub, imp)
            val displayName = if (pkg == AndroidAutoStubs.PKG_MAPS) "Google Maps" else "App de Google"
            when (plan) {
                StubPlanAction.ALREADY_STUB -> {
                    Log.i(TAG, "$displayName stub ya está instalado y visible.")
                }
                StubPlanAction.INSTALL_STUB -> {
                    toInstall.add(pkg)
                }
                StubPlanAction.BLOCKED_BY_REAL_USER_APP -> {
                    blockedRealMsgs.add(
                        "• $displayName real está instalada en el equipo (no se desinstaló automáticamente; si Android Auto falla, desinstalala manualmente desde el panel)."
                    )
                }
                StubPlanAction.BLOCKED_BY_REAL_SYSTEM_APP -> {
                    blockedRealMsgs.add(
                        "• $displayName viene preinstalada de fábrica en este celular, por lo que no se puede reemplazar con el stub."
                    )
                }
            }
        }

        if (toInstall.isEmpty()) {
            finishSequence(ctx, restoreRestrictions = false)
            return
        }

        synchronized(lock) {
            pendingQueue.addAll(toInstall)
        }
        installNextInQueue(ctx)
    }

    private fun installNextInQueue(ctx: Context) {
        val nextPkg = synchronized(lock) { pendingQueue.removeFirstOrNull() }
        if (nextPkg == null) {
            finishSequence(ctx, restoreRestrictions = true)
            return
        }

        val assetPath = AndroidAutoStubs.ASSET_PATHS[nextPkg]
        val expectedSha = AndroidAutoStubs.APK_SHA256[nextPkg]
        if (assetPath == null || expectedSha == null) {
            failedStubs.add(nextPkg)
            installNextInQueue(ctx)
            return
        }

        var tmpFile: File? = null
        var committed = false
        try {
            tmpFile = File.createTempFile("aa_stub_", ".apk", ctx.cacheDir)
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }

            val actualSha = fileSha256(tmpFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                Log.e(TAG, "SHA-256 inválido para stub $nextPkg: $actualSha != $expectedSha")
                failedStubs.add(nextPkg)
                installNextInQueue(ctx)
                return
            }

            if (!prepareTemporaryInstallAccess(ctx)) {
                failedStubs.add(nextPkg)
                installNextInQueue(ctx)
                return
            }

            // Marcar antes del commit para que PackageReceiver no desinstale el stub
            PackageReceiver.markStoreInstall(ctx, nextPkg)

            val pm = ctx.packageManager
            val packageInstaller = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(nextPkg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            try {
                val len = tmpFile.length().takeIf { it > 0L } ?: -1L
                session.openWrite("aa_stub", 0, len).use { out ->
                    FileInputStream(tmpFile).use { fis ->
                        val buf = ByteArray(32768)
                        var n: Int
                        while (fis.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                        session.fsync(out)
                    }
                }
                val intent = Intent(ctx, PackageInstallStatusReceiver::class.java).apply {
                    putExtra(PackageInstallStatusReceiver.EXTRA_LS_PACKAGE, nextPkg)
                    putExtra(PackageInstallStatusReceiver.EXTRA_SILENT, true)
                    putExtra(PackageInstallStatusReceiver.EXTRA_AA_STUB, true)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getBroadcast(ctx, 8800 + (sessionId and 0x0FFF), intent, flags)
                session.commit(pi.intentSender)
                committed = true
                Log.i(TAG, "Sesión stub $nextPkg enviada a PackageInstaller.")
            } finally {
                if (!committed) {
                    try { session.abandon() } catch (_: Exception) {}
                }
                try { session.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error instalando stub $nextPkg: ${e.message}", e)
            failedStubs.add(nextPkg)
            installNextInQueue(ctx)
        } finally {
            try {
                if (tmpFile?.exists() == true) tmpFile.delete()
            } catch (_: Exception) {}
        }
    }

    /** Callback desde [PackageInstallStatusReceiver] al completar/fallar un stub. */
    fun onStubInstallResult(context: Context, packageName: String?, status: Int, message: String?) {
        val ctx = context.applicationContext ?: context
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "Stub instalado OK: $packageName")
            if (!packageName.isNullOrBlank()) installedStubs.add(packageName)
        } else {
            Log.w(TAG, "Stub falló ($packageName): status=$status msg=$message")
            if (!packageName.isNullOrBlank()) failedStubs.add("$packageName ($message)")
        }

        val hasMore = synchronized(lock) { pendingQueue.isNotEmpty() }
        if (hasMore) {
            Thread({ installNextInQueue(ctx) }, "LockSuiteAAStubNext").start()
        } else {
            finishSequence(ctx, restoreRestrictions = true)
        }
    }

    private fun finishSequence(ctx: Context, restoreRestrictions: Boolean) {
        try {
            if (restoreRestrictions) {
                try {
                    PolicyManager(ctx).restoreInstallRestrictions()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudieron restaurar restricciones tras stubs: ${e.message}")
                }
            }
            ensureAndroidAutoAndStubsVisible(ctx)
            try {
                PlayStoreManager.reconcile(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "PlayStoreManager.reconcile tras stubs falló: ${e.message}")
            }

            if (blockedRealMsgs.isNotEmpty() || failedStubs.isNotEmpty()) {
                val lines = ArrayList<String>()
                if (installedStubs.isNotEmpty()) {
                    lines.add("Stubs instalados (${installedStubs.size}) y visibles para Android Auto.")
                }
                lines.addAll(blockedRealMsgs)
                if (failedStubs.isNotEmpty()) {
                    lines.add("• No se pudieron instalar: ${failedStubs.joinToString(", ")}")
                }
                emitNotice(
                    ctx,
                    title = "Android Auto — Aviso de componentes",
                    message = lines.joinToString("\n"),
                    isWarning = true
                )
            } else if (installedStubs.isNotEmpty()) {
                val msg = "Android Auto listo: se instalaron los componentes necesarios (Maps y Google)."
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {}
                }
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun emitNotice(ctx: Context, title: String, message: String, isWarning: Boolean) {
        val nextId = synchronized(lock) { ++noticeSeq }
        _lastNotice.value = StubNotice(nextId, title, message, isWarning)
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, "$title\n$message", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    private fun ensureAndroidAutoAndStubsVisible(ctx: Context) {
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
            AndroidAutoStubs.asegurarVisibles(ctx, dpm, admin)

            // Asegurar que Android Auto quede visible si no fue ocultado/suspendido por el admin
            val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(ctx)
            val aa = AndroidAutoStubs.PKG_ANDROID_AUTO
            if (!prefs.getBoolean("hide_$aa", false)) {
                try {
                    if (dpm.isApplicationHidden(admin, aa)) AppUnhideGuard.unhideSafely(ctx, dpm, admin, aa)
                } catch (_: Exception) {}
            }
            if (!prefs.getBoolean("suspend_$aa", false)) {
                try {
                    if (dpm.isPackageSuspended(admin, aa)) dpm.setPackagesSuspended(admin, arrayOf(aa), false)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureAndroidAutoAndStubsVisible: ${e.message}")
        }
    }

    private fun prepareTemporaryInstallAccess(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
            val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(ctx)
            val wasProtected = prefs.getBoolean("install_apps_blocked_admin", false)
            if (wasProtected && !dpm.isDeviceOwnerApp(ctx.packageName)) return false

            prefs.edit()
                .putBoolean("install_blocked_programmatic", false)
                .putLong("mdm_install_started_at", System.currentTimeMillis())
                .commit()
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            true
        } catch (e: Exception) {
            Log.w(TAG, "prepareTemporaryInstallAccess: ${e.message}")
            false
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16384)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
