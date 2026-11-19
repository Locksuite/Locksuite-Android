package com.ejemplo.locksuite.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = StoreInstallCore(scope)

    /** `key == null`: nada en curso. `key == SELF_KEY`: autoactualización. Otro: ese paquete. */
    val state: StateFlow<StoreInstallCore.State> get() = core.state

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
                    toast(app, "$label instalado correctamente.")
                }
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
