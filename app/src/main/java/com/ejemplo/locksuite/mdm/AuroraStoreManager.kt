package com.ejemplo.locksuite.mdm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.AuroraPackage
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.AuroraVariant
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.StoreInstallManager

/**
 * Capa Android de Tienda Aurora (B.132).
 *
 * Comparte el mismo catálogo efectivo por perfil (`PlayStoreManager.mode`, `.listed`, `.offered`)
 * que Tienda Play y descarga/instala de forma 100% silenciosa mediante [StoreInstallManager]
 * + `SelfUpdater.downloadAndInstallApk` (verificación SHA-256 obligatoria + `SplitApkInstaller`).
 */
object AuroraStoreManager {

    private const val TAG = "AuroraStoreManager"
    private const val KEY_PACKAGES_JSON = "aurora_store_packages_json"
    private const val KEY_UPDATED_AT = "aurora_store_updated_at"
    private const val KEY_PENDING_CLAIMS = "aurora_pending_claims"
    private const val KEY_LAUNCHER_VERSION = "launcher_packages_version"
    private const val CLAIM_TTL_MS = 10 * 60 * 1000L

    private class Cached(val raw: String?, val packages: Map<String, AuroraPackage>)

    @Volatile private var cache: Cached? = null

    private fun prefs(context: Context) = PrefsHelper.getMdmPrefs(context.applicationContext)

    fun packages(context: Context): Map<String, AuroraPackage> {
        val raw = prefs(context).getString(KEY_PACKAGES_JSON, null)
        cache?.let { if (it.raw == raw) return it.packages }
        val parsed = AuroraStoreCatalog.parsePackages(raw) ?: emptyMap()
        cache = Cached(raw, parsed)
        return parsed
    }

    fun updatedAt(context: Context): Long = prefs(context).getLong(KEY_UPDATED_AT, 0L)

    fun replacePackages(context: Context, json: String?): Boolean {
        val ctx = context.applicationContext
        val parsed = AuroraStoreCatalog.parsePackages(json) ?: run {
            Log.w(TAG, "auroraStore/packages ilegible: se conserva copia previa")
            return false
        }
        val p = prefs(ctx)
        val before = p.getString(KEY_PACKAGES_JSON, null)
        val normalized = json ?: "{}"
        p.edit()
            .putString(KEY_PACKAGES_JSON, normalized)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
        cache = Cached(normalized, parsed)
        return before != normalized
    }

    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

    fun packageEntry(context: Context, packageName: String): AuroraPackage? =
        packages(context)[packageName]

    fun variantForDevice(context: Context, packageName: String): AuroraVariant? =
        AuroraStoreCatalog.selectVariant(packageEntry(context, packageName), deviceAbis())

    fun installedVersionCode(context: Context, packageName: String): Long {
        return try {
            val pi = context.applicationContext.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            -1L
        } catch (_: Exception) {
            -1L
        }
    }

    fun pausedByStore(context: Context): Set<String> =
        prefs(context).getStringSet("play_store_paused", emptySet()) ?: emptySet()

    /**
     * Inicia instalación o actualización silenciosa para [packageName] después de re-verificar
     * que la tienda está abierta, la app está ofrecida en el perfil actual y existe un
     * artefacto compatible en GCS con SHA-256 válido.
     *
     * @return `null` si comenzó la descarga/instalación en segundo plano; mensaje de error si no.
     */
    fun startInstallOrUpdate(context: Context, packageName: String): String? {
        val ctx = context.applicationContext
        if (!PlayStoreManager.isOpen(ctx)) {
            return "La tienda está cerrada en este equipo."
        }
        val offeredApp = PlayStoreManager.offered(ctx).firstOrNull { it.packageName == packageName }
            ?: return "Esta aplicación no está habilitada para este equipo."

        val variant = variantForDevice(ctx, packageName)
            ?: return "Esta app todavía no se descargó en el servidor (Tienda Aurora)."

        val installedVc = installedVersionCode(ctx, packageName)
        val isUpdate = installedVc >= 0L
        val label = if (isUpdate) {
            PlayStoreManager.installedLabel(ctx, packageName) ?: offeredApp.label
        } else {
            offeredApp.label
        }

        if (!isUpdate) {
            markPendingClaim(ctx, packageName)
        }
        val started = StoreInstallManager.installAuroraApp(
            context = ctx,
            apkUrl = variant.apkUrl,
            packageName = packageName,
            label = label,
            sha256 = variant.sha256,
            isUpdate = isUpdate
        )
        if (!started) {
            if (!isUpdate) clearPendingClaim(ctx, packageName)
            return "Ya hay otra instalación o actualización en curso. Esperá a que termine."
        }
        return null
    }

    /**
     * Actualiza en secuencia todas las apps instaladas del catálogo que tienen versión más nueva
     * en Tienda Aurora.
     */
    fun startUpdateAll(context: Context): Pair<Int, String?> {
        val ctx = context.applicationContext
        if (!PlayStoreManager.isOpen(ctx)) {
            return 0 to "La tienda está cerrada en este equipo."
        }
        val offered = PlayStoreManager.offered(ctx)
        val pkgsMap = packages(ctx)
        val installedVersions = HashMap<String, Long>()
        for (app in offered) {
            val vc = installedVersionCode(ctx, app.packageName)
            if (vc > 0L) installedVersions[app.packageName] = vc
        }
        val toUpdate = AuroraStoreCatalog.updatablePackages(offered, pkgsMap, installedVersions, deviceAbis())
        if (toUpdate.isEmpty()) {
            return 0 to "Todas las aplicaciones ya están al día."
        }

        val items = ArrayList<StoreInstallManager.AuroraBatchItem>(toUpdate.size)
        for (pkg in toUpdate) {
            val app = offered.firstOrNull { it.packageName == pkg } ?: continue
            val variant = AuroraStoreCatalog.selectVariant(pkgsMap[pkg], deviceAbis()) ?: continue
            val label = PlayStoreManager.installedLabel(ctx, pkg) ?: app.label
            items.add(
                StoreInstallManager.AuroraBatchItem(
                    apkUrl = variant.apkUrl,
                    packageName = pkg,
                    label = label,
                    sha256 = variant.sha256,
                    isUpdate = true
                )
            )
        }
        if (items.isEmpty()) return 0 to "No hay actualizaciones listas para descargar."
        val started = StoreInstallManager.installAuroraBatch(ctx, items)
        return if (started) {
            items.size to null
        } else {
            0 to "Ya hay una instalación en curso. Esperá a que termine."
        }
    }

    fun markPendingClaim(context: Context, packageName: String) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val raw = p.getString(KEY_PENDING_CLAIMS, null)
        val obj = try {
            if (raw.isNullOrBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        obj.put(packageName, System.currentTimeMillis())
        p.edit().putString(KEY_PENDING_CLAIMS, obj.toString()).apply()
    }

    fun clearPendingClaim(context: Context, packageName: String) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val raw = p.getString(KEY_PENDING_CLAIMS, null) ?: return
        try {
            val obj = org.json.JSONObject(raw)
            if (obj.has(packageName)) {
                obj.remove(packageName)
                p.edit().putString(KEY_PENDING_CLAIMS, obj.toString()).apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun consumePendingClaim(ctx: Context, packageName: String): Boolean {
        val p = prefs(ctx)
        val raw = p.getString(KEY_PENDING_CLAIMS, null) ?: return false
        return try {
            val obj = org.json.JSONObject(raw)
            val at = obj.optLong(packageName, 0L)
            if (at <= 0L) return false
            obj.remove(packageName)
            p.edit().putString(KEY_PENDING_CLAIMS, obj.toString()).apply()
            System.currentTimeMillis() - at in 0L..CLAIM_TTL_MS
        } catch (_: Exception) {
            false
        }
    }

    fun onPackageInstalled(context: Context, packageName: String, isReplacing: Boolean) {
        val ctx = context.applicationContext
        val hadPending = consumePendingClaim(ctx, packageName)
        if (!isReplacing && (hadPending || PlayStoreManager.isOffered(ctx, packageName))) {
            if (hadPending) {
                PlayStoreManager.claimInstalledByStore(ctx, packageName)
            }
        } else if (isReplacing && PlayStoreManager.isOffered(ctx, packageName)) {
            PlayStoreManager.reconcile(ctx)
        }
        prefs(ctx).edit().putLong(KEY_LAUNCHER_VERSION, System.currentTimeMillis()).apply()
    }
}
