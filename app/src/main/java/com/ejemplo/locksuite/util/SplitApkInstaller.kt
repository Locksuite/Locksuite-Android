package com.ejemplo.locksuite.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.ejemplo.locksuite.mdm.SplitApkBundle
import com.ejemplo.locksuite.mdm.SplitApkBundle.InspectResult
import com.ejemplo.locksuite.mdm.SplitApkBundle.SelectionResult
import com.ejemplo.locksuite.mdm.SplitApkBundle.SplitEntryInfo
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner
import com.ejemplo.locksuite.receiver.PackageInstallStatusReceiver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Capa Android para instalar Split APKs (`.apks`, `.xapk`, `.apkm` sin cifrar) en una
 * única sesión atómica de `PackageInstaller` como Device Owner.
 *
 * La lógica de parseo, seguridad (Zip Slip / tamaño / coincidencia de paquete) y filtrado
 * por arquitectura/densidad/idioma vive en [SplitApkBundle] (Kotlin puro, con pruebas).
 */
object SplitApkInstaller {

    private const val TAG = "SplitApkInstaller"

    sealed class PrepareResult {
        /** El archivo es un APK único estándar. */
        object SingleApk : PrepareResult()

        /** Contenedor split validado y filtrado para este equipo. */
        data class ReadySplits(
            val base: SplitEntryInfo,
            val splits: List<SplitEntryInfo>,
            val totalBytes: Long,
            val availableAbis: Set<String>,
            val hasObb: Boolean
        ) : PrepareResult() {
            val packageName: String get() = base.manifest?.packageName.orEmpty()
        }

        data class Error(val message: String) : PrepareResult()
    }

    data class HostedBundleInspection(
        val archivePkg: String?,
        val archiveVc: Long,
        val archiveVn: String,
        val minSdk: Int,
        val availableAbis: Set<String>,
        val selectedCount: Int,
        val firma: UpdateRoutePlanner.Firma,
        val error: String?
    )

    fun deviceDensityDpi(context: Context): Int = try {
        context.resources.displayMetrics.densityDpi
    } catch (_: Exception) {
        0
    }

    fun deviceLocales(context: Context): List<String> {
        val out = ArrayList<String>(4)
        try {
            val config = context.resources.configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val locales = config.locales
                for (i in 0 until locales.size()) {
                    locales[i]?.toLanguageTag()?.let { out.add(it) }
                }
            } else {
                @Suppress("DEPRECATION")
                config.locale?.toLanguageTag()?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }
        try {
            out.add(Locale.getDefault().toLanguageTag())
        } catch (_: Exception) {
        }
        return out
    }

    fun isSplitBundle(file: File): Boolean {
        if (!file.exists() || file.length() <= 4L) return false
        return try {
            ZipFile(file).use { z ->
                if (z.getEntry("AndroidManifest.xml") != null) return@use false
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory && e.name.lowercase(Locale.ROOT).endsWith(".apk")) {
                        return@use true
                    }
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun prepareForInstall(
        context: Context,
        file: File,
        expectedPackageName: String?
    ): PrepareResult {
        return when (val insp = SplitApkBundle.inspectBundleZip(file, context.cacheDir)) {
            InspectResult.SingleApk -> PrepareResult.SingleApk
            is InspectResult.Error -> PrepareResult.Error(insp.reason)
            is InspectResult.SplitBundle -> {
                val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
                val sel = SplitApkBundle.selectSplits(
                    entries = insp.entries,
                    expectedPackageName = expectedPackageName,
                    deviceAbis = deviceAbis,
                    deviceDensityDpi = deviceDensityDpi(context),
                    deviceLocales = deviceLocales(context),
                    deviceSdk = Build.VERSION.SDK_INT
                )
                when (sel) {
                    is SelectionResult.Selected -> PrepareResult.ReadySplits(
                        base = sel.base,
                        splits = sel.splits,
                        totalBytes = sel.totalBytes,
                        availableAbis = sel.availableAbis,
                        hasObb = insp.hasObb
                    )
                    is SelectionResult.Error -> PrepareResult.Error(sel.reason)
                }
            }
        }
    }

    /**
     * Inspecciona un bundle split para [HostedAppUpdater.juzgar] antes de levantar
     * restricciones. Extrae únicamente `base.apk` a un temporal para comparar firma con
     * [ApkSignatureVerifier], y lo elimina siempre al terminar.
     */
    fun inspectForHostedJudge(
        context: Context,
        bundleFile: File,
        targetPkg: String
    ): HostedBundleInspection {
        val insp = SplitApkBundle.inspectBundleZip(bundleFile, context.cacheDir)
        return when (insp) {
            is InspectResult.Error -> HostedBundleInspection(
                archivePkg = null,
                archiveVc = -1L,
                archiveVn = "?",
                minSdk = 0,
                availableAbis = emptySet(),
                selectedCount = 0,
                firma = UpdateRoutePlanner.Firma.DESCONOCIDA,
                error = insp.reason
            )
            InspectResult.SingleApk -> HostedBundleInspection(
                archivePkg = null,
                archiveVc = -1L,
                archiveVn = "?",
                minSdk = 0,
                availableAbis = emptySet(),
                selectedCount = 1,
                firma = UpdateRoutePlanner.Firma.DESCONOCIDA,
                error = "El archivo no es un paquete dividido."
            )
            is InspectResult.SplitBundle -> {
                val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
                val sel = SplitApkBundle.selectSplits(
                    entries = insp.entries,
                    expectedPackageName = null,
                    deviceAbis = deviceAbis,
                    deviceDensityDpi = deviceDensityDpi(context),
                    deviceLocales = deviceLocales(context),
                    deviceSdk = Build.VERSION.SDK_INT
                )
                val baseEntry = when (sel) {
                    is SelectionResult.Selected -> sel.base
                    is SelectionResult.Error -> {
                        val bases = insp.entries.filter { it.manifest != null && it.manifest.splitName.isNullOrBlank() }
                        if (bases.size == 1) bases[0] else null
                    }
                }
                val baseManifest = baseEntry?.manifest
                var archivePkg = baseManifest?.packageName
                var archiveVc = baseManifest?.versionCode ?: -1L
                var archiveVn = baseManifest?.versionName ?: "?"
                var minSdk = baseManifest?.minSdkVersion ?: 0

                if (sel is SelectionResult.Error) {
                    return HostedBundleInspection(
                        archivePkg = archivePkg,
                        archiveVc = archiveVc,
                        archiveVn = archiveVn,
                        minSdk = minSdk,
                        availableAbis = sel.availableAbis,
                        selectedCount = 0,
                        firma = UpdateRoutePlanner.Firma.DESCONOCIDA,
                        error = sel.reason
                    )
                }

                val selected = sel as SelectionResult.Selected
                var firma = UpdateRoutePlanner.Firma.DESCONOCIDA
                val tmpBase = File.createTempFile("split_judge_", ".apk", context.cacheDir)
                try {
                    extractEntryToTempFile(bundleFile, selected.base.entryName, tmpBase)
                    val pm = context.packageManager
                    val info = try {
                        pm.getPackageArchiveInfo(tmpBase.path, 0)
                    } catch (_: Exception) {
                        null
                    }
                    if (info != null) {
                        if (archivePkg.isNullOrBlank()) archivePkg = info.packageName
                        if (archiveVc < 0L) {
                            archiveVc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                info.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                info.versionCode.toLong()
                            }
                        }
                        val infoVn = info.versionName
                        if (archiveVn == "?" && !infoVn.isNullOrBlank()) {
                            archiveVn = infoVn
                        }
                        if (minSdk <= 0) {
                            minSdk = info.applicationInfo?.minSdkVersion ?: 0
                        }
                    }
                    firma = when (ApkSignatureVerifier.verify(context, tmpBase.path, targetPkg)) {
                        ApkSignatureVerifier.Result.Match -> UpdateRoutePlanner.Firma.COINCIDE
                        ApkSignatureVerifier.Result.NotInstalled -> UpdateRoutePlanner.Firma.NO_INSTALADA
                        is ApkSignatureVerifier.Result.Mismatch -> UpdateRoutePlanner.Firma.DISTINTA
                        is ApkSignatureVerifier.Result.Unknown -> UpdateRoutePlanner.Firma.DESCONOCIDA
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo verificar firma de base.apk en split bundle: ${e.message}")
                } finally {
                    try {
                        if (tmpBase.exists()) tmpBase.delete()
                    } catch (_: Exception) {
                    }
                }

                HostedBundleInspection(
                    archivePkg = archivePkg,
                    archiveVc = archiveVc,
                    archiveVn = archiveVn,
                    minSdk = minSdk,
                    availableAbis = selected.availableAbis,
                    selectedCount = selected.splits.size,
                    firma = firma,
                    error = null
                )
            }
        }
    }

    fun extractEntryToTempFile(bundleFile: File, entryName: String, destFile: File) {
        ZipFile(bundleFile).use { outerZip ->
            val entry = outerZip.getEntry(entryName)
                ?: throw IOException("No se encontró $entryName en el paquete")
            outerZip.getInputStream(entry).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        total += n
                        if (total > SplitApkBundle.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            throw IOException("El archivo $entryName supera el límite permitido")
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    fun commitSplitSession(
        context: Context,
        bundleFile: File,
        packageName: String,
        splits: List<SplitEntryInfo>,
        totalBytes: Long,
        isCancelled: (() -> Boolean)? = null,
        silent: Boolean = false
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        if (totalBytes > 0L && splits.all { it.sizeBytes > 0L }) {
            params.setSize(totalBytes)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        var installCommitSubmitted = false
        try {
            ZipFile(bundleFile).use { outerZip ->
                val buffer = ByteArray(65536)
                var totalWritten = 0L
                for ((idx, split) in splits.withIndex()) {
                    if (isCancelled?.invoke() == true) {
                        throw IOException("Descarga cancelada")
                    }
                    val zipEntry = outerZip.getEntry(split.entryName)
                        ?: throw IOException("Falta el split ${split.entryName} en el paquete")
                    val entrySize = zipEntry.size.takeIf { it > 0L } ?: -1L
                    val sessionName = SplitApkBundle.sessionSplitName(idx, split.entryName)
                    session.openWrite(sessionName, 0L, entrySize).use { out ->
                        outerZip.getInputStream(zipEntry).use { input ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (isCancelled?.invoke() == true) {
                                    throw IOException("Descarga cancelada")
                                }
                                totalWritten += bytesRead
                                if (totalWritten > SplitApkBundle.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                    throw IOException("El paquete excede el tamaño máximo permitido al instalarse")
                                }
                                out.write(buffer, 0, bytesRead)
                            }
                            session.fsync(out)
                        }
                    }
                }
            }

            val intent = Intent(context, PackageInstallStatusReceiver::class.java)
                .putExtra(PackageInstallStatusReceiver.EXTRA_LS_PACKAGE, packageName)
                .putExtra(PackageInstallStatusReceiver.EXTRA_SILENT, silent)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            installCommitSubmitted = true
            Log.i(TAG, "Sesión split enviada a PackageInstaller para $packageName (${splits.size} APKs)")
        } finally {
            if (!installCommitSubmitted) {
                try { session.abandon() } catch (_: Exception) {}
            }
            try { session.close() } catch (_: Exception) {}
        }
    }
}
