package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.AuroraPackage
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.AuroraVariant
import java.util.Locale

/**
 * Motor puro (sin Android) de la Tienda única de LockSuite (B.134).
 *
 * Unifica en una sola lista:
 *   1. Apps permitidas del catálogo administrado (instalables o a pedido).
 *   2. Todas las apps de usuario instaladas NO bloqueadas + apps de sistema actualizables
 *      seguras ([UPDATABLE_SYSTEM_ALLOWLIST]), excluyendo siempre [PlayStoreCatalog.NEVER_OFFERED],
 *      stubs de Android Auto, WebView/Trichrome y apps bloqueadas/ocultas/suspendidas.
 *
 * Decide por fila el origen óptimo:
 *   - Servidor cacheado (APKPure -> F-Droid -> Aptoide -> Aurora/Play) con instalación silenciosa
 *     cuando hay variante compatible por ABI y firma coincidente.
 *   - Google Play (respaldo con pantalla tapada y aviso de cuenta Google) cuando falta variante,
 *     la firma instalada difiere, o el servidor marcó `play_fallback`.
 *   - "Abrir" + "Sin actualizaciones" cuando no hay versión más nueva disponible.
 */
object UnifiedStoreLogic {

    /**
     * Apps preinstaladas / del sistema seguras para actualizar desde la tienda (incluye Android Auto;
     * excluye explícitamente `com.google.android.gms` — ver incidente Lockfox 15/11/2026).
     */
    val UPDATABLE_SYSTEM_ALLOWLIST: Set<String> = setOf(
        AndroidAutoStubs.PKG_ANDROID_AUTO,
        "com.google.android.inputmethod.latin",
        "com.google.android.apps.messaging",
        "com.google.android.contacts",
        "com.google.android.dialer",
        "com.google.android.calendar",
        "com.google.android.deskclock",
        "com.google.android.calculator",
        "com.google.android.apps.translate",
        "com.google.android.apps.docs",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.adm",
        "com.google.android.tts",
        "com.google.android.marvin.talkback"
    )

    private val SYSTEM_EXCLUDE_PACKAGES: Set<String> = setOf(
        "com.google.android.webview",
        "com.android.webview"
    )

    data class InstalledAppSnapshot(
        val packageName: String,
        val label: String,
        val versionCode: Long,
        val versionName: String,
        val isSystem: Boolean,
        val isUpdatedSystem: Boolean,
        val isBlocked: Boolean,
        val isCritical: Boolean,
        val isStub: Boolean,
        val signerSha256: String?
    )

    enum class Delivery { NONE, SERVER_SILENT, GOOGLE_PLAY }

    enum class RowAction {
        INSTALL_SILENT,
        INSTALL_PLAY,
        UPDATE_SILENT,
        UPDATE_PLAY,
        OPEN_UP_TO_DATE,
        INSTALLING,
        REQUEST,
        REQUESTED
    }

    data class StoreRowModel(
        val packageName: String,
        val label: String,
        val installed: Boolean,
        val installedVersionCode: Long,
        val installedVersionName: String,
        val availableVersionCode: Long,
        val availableVersionName: String,
        val sizeBytes: Long,
        val source: String,
        val delivery: Delivery,
        val action: RowAction,
        val access: String,
        val catalogStatus: String,
        val iconUrl: String?,
        val variant: AuroraVariant?,
        val pausedByStore: Boolean,
        val progressPct: Int?,
        val signatureMismatch: Boolean
    ) {
        val isUpdateAction: Boolean
            get() = action == RowAction.UPDATE_SILENT || action == RowAction.UPDATE_PLAY

        val isInstallAction: Boolean
            get() = action == RowAction.INSTALL_SILENT || action == RowAction.INSTALL_PLAY
    }

    fun isExcludedInstalledApp(snap: InstalledAppSnapshot): Boolean {
        val pkg = snap.packageName
        if (pkg in PlayStoreCatalog.NEVER_OFFERED) return true
        if (pkg in AndroidAutoStubs.PACKAGES) return true
        if (pkg in SYSTEM_EXCLUDE_PACKAGES) return true
        if (pkg.startsWith("com.google.android.trichromelibrary")) return true
        if (snap.isStub || snap.isBlocked) return true
        if (snap.isSystem) {
            if (pkg !in UPDATABLE_SYSTEM_ALLOWLIST) return true
        } else if (snap.isCritical) {
            return true
        }
        return false
    }

    fun sourceDisplayName(source: String): String = when (source.trim().lowercase(Locale.ROOT)) {
        "apkpure" -> "APKPure"
        "fdroid" -> "F-Droid"
        "aptoide" -> "Aptoide"
        "aurora" -> "Aurora"
        "play" -> "Google Play"
        else -> ""
    }

    /**
     * Construye y ordena las filas de la Tienda unificada.
     *
     * @param storeOpen Si false (modo `cerrada`), oculta catálogo de instalación/pedido y deja
     *   solo mantenimiento de apps ya instaladas no bloqueadas.
     */
    fun buildRows(
        storeOpen: Boolean,
        catalogApps: List<PlayStoreCatalog.StoreApp>,
        installedApps: Collection<InstalledAppSnapshot>,
        serverPackages: Map<String, AuroraPackage>,
        deviceAbis: List<String>,
        requestedPackages: Set<String> = emptySet(),
        pausedByStorePackages: Set<String> = emptySet(),
        activeSilentPkg: String? = null,
        activeSilentProgress: Int? = null,
        activePlayPkg: String? = null
    ): List<StoreRowModel> {
        val installedMap = LinkedHashMap<String, InstalledAppSnapshot>()
        for (snap in installedApps) {
            installedMap[snap.packageName] = snap
        }

        val catalogMap = LinkedHashMap<String, PlayStoreCatalog.StoreApp>()
        if (storeOpen) {
            for (app in catalogApps) {
                if (app.status == PlayStoreCatalog.STATUS_BLOCKED) continue
                if (app.packageName in PlayStoreCatalog.NEVER_OFFERED) continue
                if (app.packageName in AndroidAutoStubs.PACKAGES) continue
                catalogMap[app.packageName] = app
            }
        }

        // Conjunto de paquetes elegibles para mostrar
        val orderedPkgs = LinkedHashSet<String>()
        for (app in catalogMap.values) {
            val inst = installedMap[app.packageName]
            if (inst != null && (inst.isBlocked || inst.isStub)) continue
            orderedPkgs.add(app.packageName)
        }
        for (snap in installedApps) {
            if (snap.packageName in orderedPkgs) continue
            if (isExcludedInstalledApp(snap)) continue
            orderedPkgs.add(snap.packageName)
        }

        val out = ArrayList<StoreRowModel>(orderedPkgs.size)
        for (pkg in orderedPkgs) {
            val cat = catalogMap[pkg]
            val inst = installedMap[pkg]
            val isInstalled = inst != null && inst.versionCode > 0L
            val serverPkg = serverPackages[pkg]
            val variant = AuroraStoreCatalog.selectVariant(serverPkg, deviceAbis)
            val latestVc = AuroraStoreCatalog.effectiveLatestVersionCode(serverPkg, variant)
            val latestVn = when {
                variant != null && variant.versionName.isNotBlank() -> variant.versionName
                serverPkg != null && serverPkg.latestVersionName.isNotBlank() -> serverPkg.latestVersionName
                serverPkg != null && serverPkg.versionName.isNotBlank() -> serverPkg.versionName
                else -> ""
            }

            val pausedByStore = isInstalled && pkg in pausedByStorePackages
            val status = cat?.status ?: if (isInstalled) PlayStoreCatalog.STATUS_ENABLED else PlayStoreCatalog.STATUS_REQUEST
            val label = inst?.label?.takeIf { it.isNotBlank() } ?: cat?.label ?: serverPkg?.title ?: pkg
            val isInstallingNow = pkg == activeSilentPkg || pkg == activePlayPkg
            val progressPct = if (pkg == activeSilentPkg) activeSilentProgress else null

            val signatureMismatch = isInstalled &&
                variant != null &&
                !variant.signerSha256.isNullOrBlank() &&
                !inst!!.signerSha256.isNullOrBlank() &&
                !variant.signerSha256.equals(inst.signerSha256, ignoreCase = true)

            val hasSilentUpdate = isInstalled &&
                variant != null &&
                variant.versionCode > inst!!.versionCode &&
                !signatureMismatch

            val hasPlayUpdate = isInstalled &&
                !hasSilentUpdate &&
                latestVc > inst!!.versionCode

            val requested = pkg in requestedPackages

            val action: RowAction
            val delivery: Delivery
            val source: String

            when {
                isInstallingNow -> {
                    action = RowAction.INSTALLING
                    delivery = if (pkg == activePlayPkg) Delivery.GOOGLE_PLAY else Delivery.SERVER_SILENT
                    source = if (pkg == activePlayPkg) "play" else (variant?.source?.ifBlank { serverPkg?.primarySource ?: "" } ?: "")
                }
                cat != null && status == PlayStoreCatalog.STATUS_REQUEST && (!isInstalled || pausedByStore) -> {
                    action = if (requested) RowAction.REQUESTED else RowAction.REQUEST
                    delivery = Delivery.NONE
                    source = ""
                }
                isInstalled -> {
                    when {
                        hasSilentUpdate -> {
                            action = RowAction.UPDATE_SILENT
                            delivery = Delivery.SERVER_SILENT
                            source = variant!!.source.ifBlank { serverPkg?.primarySource ?: "aurora" }
                        }
                        hasPlayUpdate -> {
                            action = RowAction.UPDATE_PLAY
                            delivery = Delivery.GOOGLE_PLAY
                            source = "play"
                        }
                        else -> {
                            action = RowAction.OPEN_UP_TO_DATE
                            delivery = Delivery.NONE
                            source = ""
                        }
                    }
                }
                else -> {
                    // No instalada (solo entra acá si storeOpen == true y es app habilitada del catálogo)
                    if (variant != null) {
                        action = RowAction.INSTALL_SILENT
                        delivery = Delivery.SERVER_SILENT
                        source = variant.source.ifBlank { serverPkg?.primarySource ?: "aurora" }
                    } else {
                        action = RowAction.INSTALL_PLAY
                        delivery = Delivery.GOOGLE_PLAY
                        source = "play"
                    }
                }
            }

            out.add(
                StoreRowModel(
                    packageName = pkg,
                    label = label,
                    installed = isInstalled,
                    installedVersionCode = inst?.versionCode ?: -1L,
                    installedVersionName = inst?.versionName ?: "",
                    availableVersionCode = variant?.versionCode ?: latestVc,
                    availableVersionName = variant?.versionName?.ifBlank { latestVn } ?: latestVn,
                    sizeBytes = variant?.sizeBytes ?: 0L,
                    source = source,
                    delivery = delivery,
                    action = action,
                    access = cat?.access ?: PlayStoreCatalog.ACCESS_FULL,
                    catalogStatus = status,
                    iconUrl = cat?.iconUrl,
                    variant = variant,
                    pausedByStore = pausedByStore,
                    progressPct = progressPct,
                    signatureMismatch = signatureMismatch
                )
            )
        }

        return out.sortedWith(
            compareBy<StoreRowModel>(
                { sortBucket(it.action) },
                { it.label.lowercase(Locale.ROOT) },
                { it.packageName }
            )
        )
    }

    private fun sortBucket(action: RowAction): Int = when (action) {
        RowAction.UPDATE_SILENT, RowAction.UPDATE_PLAY, RowAction.INSTALLING -> 0
        RowAction.INSTALL_SILENT, RowAction.INSTALL_PLAY -> 1
        RowAction.REQUEST, RowAction.REQUESTED -> 2
        RowAction.OPEN_UP_TO_DATE -> 3
    }

    fun silentUpdatableRows(rows: List<StoreRowModel>): List<StoreRowModel> =
        rows.filter { it.action == RowAction.UPDATE_SILENT && it.variant != null }
}
