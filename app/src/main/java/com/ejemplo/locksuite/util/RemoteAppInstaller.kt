package com.ejemplo.locksuite.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.ejemplo.locksuite.diag.DiagLog
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.AuroraStoreManager
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.mdm.RemoteInstallPolicy
import com.ejemplo.locksuite.mdm.RemoteInstallPolicy.Resolution
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.google.firebase.database.FirebaseDatabase

/**
 * Ejecutor en dispositivo del comando remoto `INSTALL_APP`.
 *
 * Descarga e instala silenciosamente una app verificada en el catálogo oficial del servidor
 * (`auroraStore/packages` o `storeApps`) sin abrir Play Store ni reclamarla en `PlayStoreManager`,
 * reportando el avance y resultado final en `devices/<id>/remoteInstall`.
 */
object RemoteAppInstaller {

    private const val RESULT_TIMEOUT_MS = 15 * 60_000L
    private const val PROGRESS_STEP_PCT = 5
    private const val PROGRESS_STEP_MS = 2_000L

    @Volatile private var activeRemotePkg: String? = null
    @Volatile private var activeRemoteLabel: String? = null
    @Volatile private var activeRemoteAt: Long = 0L

    @Volatile private var lastPublishedPct: Int = -1
    @Volatile private var lastPublishedAtElapsed: Long = 0L

    private fun publishStatus(
        ctx: Context,
        pkg: String,
        label: String,
        stage: String,
        progress: Int,
        status: String,
        error: String = ""
    ) {
        try {
            val devId = FirebaseDeviceSync.deviceId(ctx)
            val payload = mapOf(
                "packageName" to pkg,
                "label" to label,
                "stage" to stage,
                "progress" to progress.coerceIn(0, 100),
                "status" to status,
                "error" to error,
                "updatedAt" to System.currentTimeMillis()
            )
            FirebaseDeviceSync.runAuthenticated {
                FirebaseDatabase.getInstance()
                    .getReference("devices/$devId/remoteInstall")
                    .setValue(payload)
            }
        } catch (_: Exception) {
        }
    }

    fun executeRemoteInstall(context: Context, packageName: String, commandId: String?) {
        val ctx = context.applicationContext ?: context

        fun fail(reason: String) {
            DiagLog.w("install", "INSTALL_APP rechazado/falló para $packageName: $reason")
            publishStatus(ctx, packageName, packageName, "error", 0, "failed", reason)
            FirebaseDeviceSync.writeCommandAck(ctx, commandId, "INSTALL_APP", "failed", reason)
        }

        val appController = AppController(ctx)
        RemoteInstallPolicy.validatePackageName(packageName, appController.isCritical(packageName))?.let {
            fail(it)
            return
        }

        if (PolicyManager(ctx).isLockSuiteSuspended()) {
            fail("LockSuite está suspendido en este equipo.")
            return
        }

        if (UpdateFlowManager.hasLiveFlow(ctx) || StoreInstallManager.state.value.key != null) {
            fail("Ya hay otra instalación o actualización en curso en este equipo.")
            return
        }

        val installedFlags = try {
            val pm = ctx.packageManager
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, PackageManager.GET_UNINSTALLED_PACKAGES)
            }
            ai.flags
        } catch (_: Exception) {
            null
        }
        val hiddenNow = if (RemoteInstallPolicy.isPackageInstalled(installedFlags)) {
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
                RemoteInstallPolicy.isInstalledButHidden(installedFlags, dpm.isApplicationHidden(admin, packageName))
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (hiddenNow) {
            fail("$packageName ya está instalada pero oculta. Usá «Mostrar» en Apps del celular.")
            return
        }

        val meta = FirebaseDeviceSync.fetchRemoteInstallMeta(ctx, packageName)
        if (meta.auroraPkg == null && meta.classicUrl.isNullOrBlank() && !meta.fetchError.isNullOrBlank()) {
            fail("No se pudo consultar el catálogo en el servidor: ${meta.fetchError}")
            return
        }

        val resolution = RemoteInstallPolicy.resolveCandidate(
            packageName = packageName,
            auroraPkg = meta.auroraPkg,
            deviceAbis = AuroraStoreManager.deviceAbis(),
            classicLabel = meta.classicLabel,
            classicUrl = meta.classicUrl,
            classicSha256 = meta.classicSha256,
            classicVersionCode = meta.classicVersionCode,
            isCritical = appController.isCritical(packageName)
        )

        val candidate = when (resolution) {
            is Resolution.Allowed -> resolution.candidate
            is Resolution.Rejected -> {
                fail(resolution.reason)
                return
            }
        }

        val installedVc = AuroraStoreManager.installedVersionCode(ctx, packageName)
        val isUpdate = installedVc > 0L
        if (isUpdate && candidate.versionCode > 0L && installedVc >= candidate.versionCode) {
            fail("${candidate.label} ya está instalada y actualizada (v$installedVc).")
            return
        }

        val freeMb = UpdateFlowManager.freeSpaceMb(ctx)
        val neededMb = RemoteInstallPolicy.minRequiredFreeMb(candidate.sizeBytes)
        if (freeMb in 0L until neededMb) {
            fail("Espacio insuficiente: quedan $freeMb MB libres y hacen falta al menos $neededMb MB.")
            return
        }

        activeRemotePkg = packageName
        activeRemoteLabel = candidate.label
        activeRemoteAt = System.currentTimeMillis()
        lastPublishedPct = 0
        lastPublishedAtElapsed = SystemClock.elapsedRealtime()
        publishStatus(ctx, packageName, candidate.label, "downloading", 0, "running")

        val started = StoreInstallManager.installRemoteAdminApp(
            context = ctx,
            packageName = packageName,
            label = candidate.label,
            apkUrl = candidate.apkUrl,
            sha256 = candidate.sha256,
            isUpdate = isUpdate,
            onProgress = { pct ->
                val nowEl = SystemClock.elapsedRealtime()
                if (pct >= 100 ||
                    pct - lastPublishedPct >= PROGRESS_STEP_PCT ||
                    nowEl - lastPublishedAtElapsed >= PROGRESS_STEP_MS
                ) {
                    lastPublishedPct = pct
                    lastPublishedAtElapsed = nowEl
                    publishStatus(ctx, packageName, candidate.label, "downloading", pct, "running")
                }
            },
            onFinished = { err ->
                if (err != null) {
                    if (activeRemotePkg == packageName) {
                        activeRemotePkg = null
                    }
                    DiagLog.w("install", "Fallo instalación remota $packageName: $err")
                    publishStatus(ctx, packageName, candidate.label, "error", 0, "failed", err)
                } else {
                    publishStatus(ctx, packageName, candidate.label, "installing", 100, "running")
                }
            }
        )

        if (!started) {
            if (activeRemotePkg == packageName) {
                activeRemotePkg = null
            }
            fail("Ya hay otra instalación en curso.")
            return
        }

        DiagLog.i("install", "Instalación remota iniciada: $packageName (${candidate.source})")
        FirebaseDeviceSync.writeCommandAck(
            ctx,
            commandId,
            "INSTALL_APP",
            "applied",
            "Descargando e instalando ${candidate.label} en segundo plano…"
        )
    }

    fun onInstallStatus(context: Context, packageName: String?, status: Int, message: String?): Boolean {
        val ctx = context.applicationContext ?: context
        val pkg = packageName ?: return false
        val expected = activeRemotePkg ?: return false
        if (pkg != expected) return false
        if (System.currentTimeMillis() - activeRemoteAt > RESULT_TIMEOUT_MS) {
            activeRemotePkg = null
            return false
        }
        val label = activeRemoteLabel ?: pkg
        activeRemotePkg = null

        if (status == PackageInstaller.STATUS_SUCCESS) {
            DiagLog.i("install", "Instalación remota completada: $pkg")
            publishStatus(ctx, pkg, label, "done", 100, "installed", "")
            FirebaseDeviceSync.invalidateAppsSyncCache()
            FirebaseDeviceSync.requestSync(ctx)
        } else {
            val reason = message?.takeIf { it.isNotBlank() }
                ?: "Error de PackageInstaller (status=$status)"
            DiagLog.w("install", "Instalación remota falló ($pkg): $reason")
            publishStatus(ctx, pkg, label, "error", 100, "failed", reason)
        }
        return true
    }
}
