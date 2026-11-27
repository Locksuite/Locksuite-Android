package com.ejemplo.locksuite.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 19/11/2026 (B.125, T4) — INSTALACIONES DE LA TIENDA Y AUTOACTUALIZACIÓN DESDE EL CELULAR,
 * EN UN SCOPE DE PROCESO.
 *
 * Antes la descarga corría en el `rememberCoroutineScope()` de la pantalla y se cancelaba al
 * salir de ella (así se perdió «Fondos» el 16/11). Ahora la pantalla solo MIRA [state] y pide;
 * la instalación es del proceso y sigue aunque el usuario salga o cambie de pantalla. La lógica
 * de «una a la vez» está en [StoreInstallCore] (con prueba).
 *
 * El aviso final (toast) se muestra con el contexto de aplicación, en el hilo principal.
 */
object StoreInstallManager {

    /** Clave de la autoactualización de LockSuite dentro de [state]. */
    const val SELF_KEY = "__locksuite_self_update__"

    data class InstallFailure(
        val id: Long,
        val packageName: String,
        val label: String,
        val isUpdate: Boolean,
        val error: String,
        val allowPlayFallback: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = StoreInstallCore(scope)
    private val failureSeq = AtomicLong(0L)
    private val _lastFailure = MutableStateFlow<InstallFailure?>(null)

    /** `key == null`: nada en curso. `key == SELF_KEY`: autoactualización. Otro: ese paquete. */
    val state: StateFlow<StoreInstallCore.State> get() = core.state

    /** Último fallo de descarga/instalación silenciosa para ofrecer respaldo vía Google Play. */
    val lastFailure: StateFlow<InstallFailure?> get() = _lastFailure

    fun clearFailure(id: Long) {
        if (_lastFailure.value?.id == id) {
            _lastFailure.value = null
        }
    }

    private fun toast(app: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun reportProgress(app: Context, progress: Int) {
        try {
            val id = FirebaseDeviceSync.deviceId(app)
            FirebaseDatabase.getInstance().getReference("devices/$id/updateProgress").setValue(progress)
        } catch (_: Throwable) {
        }
    }

    private fun clearProgress(app: Context) {
        try {
            val id = FirebaseDeviceSync.deviceId(app)
            FirebaseDatabase.getInstance().getReference("devices/$id/updateProgress").removeValue()
        } catch (_: Throwable) {
        }
    }

    /** Instala una app de la tienda clásica. false si ya hay una instalación en curso. */
    fun installStoreApp(
        context: Context,
        apkUrl: String,
        packageName: String,
        label: String,
        sha256: String?,
        blockWebView: Boolean,
        blockInternet: Boolean
    ): Boolean {
        val app = context.applicationContext ?: context
        return core.start(
            key = packageName,
            work = { report ->
                SelfUpdater.downloadAndInstallApk(app, apkUrl, packageName, label, sha256) { progress ->
                    report(progress)
                    reportProgress(app, progress)
                }
            },
            onFinished = { err ->
                clearProgress(app)
                if (err != null) {
                    toast(app, err)
                } else {
                    try {
                        com.ejemplo.locksuite.mdm.ClassicStorePolicyManager.applyOnInstall(
                            app, packageName, blockWebView, blockInternet
                        )
                    } catch (t: Throwable) {
                        Safe.report("StoreInstallManager.applyOnInstall", t)
                    }
                    toast(app, "Instalación de $label en curso…")
                }
            }
        )
    }

    data class AuroraBatchItem(
        val apkUrl: String,
        val packageName: String,
        val label: String,
        val sha256: String,
        val isUpdate: Boolean
    )

    private fun verifyAuroraUpdateSignature(app: Context, file: File, packageName: String): String? {
        return try {
            if (SplitApkInstaller.isSplitBundle(file)) {
                val insp = SplitApkInstaller.inspectForHostedJudge(app, file, packageName)
                if (insp.error != null) return insp.error
                if (insp.firma == com.ejemplo.locksuite.mdm.UpdateRoutePlanner.Firma.DISTINTA) {
                    return "La app instalada tiene otra firma digital; desinstalala primero o actualizala desde la misma fuente."
                }
            } else {
                val sig = ApkSignatureVerifier.verify(app, file.path, packageName)
                if (sig is ApkSignatureVerifier.Result.Mismatch) {
                    return "La app instalada tiene otra firma digital; desinstalala primero o actualizala desde la misma fuente."
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Instala o actualiza en silencio una app de Tienda Aurora (con SHA-256 obligatorio).
     * @return false si ya hay una instalación en curso.
     */
    fun installAuroraApp(
        context: Context,
        apkUrl: String,
        packageName: String,
        label: String,
        sha256: String,
        isUpdate: Boolean
    ): Boolean {
        val app = context.applicationContext ?: context
        return core.start(
            key = packageName,
            work = { report ->
                SelfUpdater.downloadAndInstallApk(
                    context = app,
                    apkUrl = apkUrl,
                    packageName = packageName,
                    label = label,
                    sha256 = sha256,
                    preInstallCheck = { file ->
                        if (isUpdate) verifyAuroraUpdateSignature(app, file, packageName) else null
                    }
                ) { progress ->
                    report(progress)
                    reportProgress(app, progress)
                }
            },
            onFinished = { err ->
                clearProgress(app)
                if (err != null) {
                    com.ejemplo.locksuite.mdm.AuroraStoreManager.clearPendingClaim(app, packageName)
                    _lastFailure.value = InstallFailure(
                        id = failureSeq.incrementAndGet(),
                        packageName = packageName,
                        label = label,
                        isUpdate = isUpdate,
                        error = err,
                        allowPlayFallback = !com.ejemplo.locksuite.mdm.UpdateRoutePlanner.isNoSpaceMessage(err)
                    )
                    toast(app, err)
                } else {
                    val verb = if (isUpdate) "Actualización de $label" else "Instalación de $label"
                    toast(app, "$verb en curso…")
                }
            }
        )
    }

    /**
     * Encola actualizaciones secuenciales de Tienda Aurora en segundo plano.
     */
    fun installAuroraBatch(context: Context, items: List<AuroraBatchItem>): Boolean {
        if (items.isEmpty() || core.isBusy()) return false
        val app = context.applicationContext ?: context
        if (items.size == 1) {
            val one = items[0]
            return installAuroraApp(app, one.apkUrl, one.packageName, one.label, one.sha256, one.isUpdate)
        }
        scope.launch {
            for (item in items) {
                var guard = 0
                while (core.isBusy() && guard < 180) {
                    kotlinx.coroutines.delay(500L)
                    guard++
                }
                if (core.isBusy()) break
                val latch = java.util.concurrent.CountDownLatch(1)
                val started = core.start(
                    key = item.packageName,
                    work = { report ->
                        SelfUpdater.downloadAndInstallApk(
                            context = app,
                            apkUrl = item.apkUrl,
                            packageName = item.packageName,
                            label = item.label,
                            sha256 = item.sha256,
                            preInstallCheck = { file ->
                                if (item.isUpdate) verifyAuroraUpdateSignature(app, file, item.packageName) else null
                            }
                        ) { progress ->
                            report(progress)
                            reportProgress(app, progress)
                        }
                    },
                    onFinished = { err ->
                        clearProgress(app)
                        if (err != null) toast(app, "${item.label}: $err")
                        latch.countDown()
                    }
                )
                if (started) latch.await()
            }
        }
        return true
    }

    /**
     * Instala o actualiza silenciosamente una app ordenada por el administrador (`INSTALL_APP`),
     * verificando SHA-256 (y firma digital si ya está instalada) y limpiando `hide_`/`suspend_`
     * residuales justo antes del commit para que `PackageReceiver` no la re-bloquee.
     *
     * No muestra Toasts ni la reclama en `PlayStoreManager`.
     */
    fun installRemoteAdminApp(
        context: Context,
        packageName: String,
        label: String,
        apkUrl: String,
        sha256: String,
        isUpdate: Boolean,
        onProgress: (Int) -> Unit,
        onFinished: (String?) -> Unit
    ): Boolean {
        val app = context.applicationContext ?: context
        return core.start(
            key = packageName,
            work = { report ->
                SelfUpdater.downloadAndInstallApk(
                    context = app,
                    apkUrl = apkUrl,
                    packageName = packageName,
                    label = label,
                    sha256 = sha256,
                    quiet = true,
                    preInstallCheck = { file ->
                        if (isUpdate) {
                            verifyAuroraUpdateSignature(app, file, packageName)?.let { return@downloadAndInstallApk it }
                        }
                        try {
                            PrefsHelper.getMdmPrefs(app).edit()
                                .putBoolean("hide_$packageName", false)
                                .putBoolean("suspend_$packageName", false)
                                .commit()
                            com.ejemplo.locksuite.mdm.AppUnhideGuard.clear(app, packageName)
                        } catch (_: Exception) {
                        }
                        null
                    }
                ) { progress ->
                    report(progress)
                    reportProgress(app, progress)
                    onProgress(progress)
                }
            },
            onFinished = { err ->
                clearProgress(app)
                onFinished(err)
            }
        )
    }

    /** Busca e instala la versión nueva de LockSuite. false si ya hay una instalación en curso. */
    fun selfUpdate(context: Context, showToasts: Boolean = true): Boolean {
        val app = context.applicationContext ?: context
        return core.start(
            key = SELF_KEY,
            work = { report ->
                SelfUpdater.checkAndPerformUpdate(app, showToasts) { progress ->
                    report(progress)
                    reportProgress(app, progress)
                }
            },
            onFinished = { err ->
                clearProgress(app)
                if (err != null) toast(app, err)
            }
        )
    }
}
