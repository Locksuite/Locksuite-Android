package com.ejemplo.locksuite.mdm

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Motor puro de inspección y filtrado de Split APKs (`.apks`, `.xapk`, `.apkm` sin cifrar)
 * sin dependencias de Android.
 *
 * Lee los manifiestos binarios en streaming desde el contenedor ZIP sin volcar los APKs
 * completos en RAM ni extraer todo el paquete a disco (crítico en equipos de 2 GB de RAM).
 * Selecciona únicamente el `base.apk`, los módulos de funciones y los splits de configuración
 * (ABI, densidad e idioma) compatibles con el equipo destino.
 */
object SplitApkBundle {

    const val MAX_SPLIT_ENTRIES = 128
    const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L
    private const val MAX_OUTER_ZIP_ENTRIES = 4096

    data class SplitEntryInfo(
        val entryName: String,
        val sizeBytes: Long,
        val manifest: BinaryManifestParser.ManifestInfo?,
        val abis: Set<String>
    )

    sealed class InspectResult {
        /** El archivo es un APK único común (`AndroidManifest.xml` en la raíz). */
        object SingleApk : InspectResult()

        /** Contenedor ZIP con múltiples `.apk` internos. */
        data class SplitBundle(
            val entries: List<SplitEntryInfo>,
            val hasObb: Boolean
        ) : InspectResult()

        data class Error(val reason: String) : InspectResult()
    }

    sealed class SelectionResult {
        data class Selected(
            val base: SplitEntryInfo,
            val splits: List<SplitEntryInfo>,
            val totalBytes: Long,
            val availableAbis: Set<String>
        ) : SelectionResult()

        data class Error(
            val reason: String,
            val availableAbis: Set<String> = emptySet()
        ) : SelectionResult()
    }

    private enum class SplitKind { ABI, DENSITY, LANGUAGE, FEATURE_OR_OTHER }

    private data class ClassifiedSplit(
        val entry: SplitEntryInfo,
        val kind: SplitKind,
        val module: String,
        val qualifier: String,
        val splitAbis: Set<String>,
        val dpi: Int?
    )

    private val LANG_QUALIFIER_RE = Regex("^[a-z]{2}(?:[_-](?:r?[a-zA-Z]{2}|\\d{3}))?$")

    fun isZipMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return false
        return (bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
            (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte())
    }

    fun isSafeZipEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\\') || name.contains('\u0000')) return false
        if (name.contains("..")) return false
        if (name.any { it.code < 0x20 }) return false
        val segments = name.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /**
     * Genera un nombre plano y único (sin `/`) para `PackageInstaller.Session.openWrite()`.
     */
    fun sessionSplitName(index: Int, entryName: String): String {
        val baseFileName = entryName.substringAfterLast('/')
            .replace(Regex("\\.apk$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifEmpty { "split" }
        val prefix = String.format(Locale.ROOT, "%02d_", index)
        val maxStem = (80 - prefix.length - 4).coerceAtLeast(1)
        val stem = if (baseFileName.length > maxStem) baseFileName.substring(0, maxStem) else baseFileName
        return "$prefix$stem.apk"
    }

    fun inspectBundleZip(file: File, fallbackTempDir: File? = null): InspectResult {
        if (!file.exists() || file.length() <= 0L) {
            return InspectResult.Error("El archivo descargado no existe o está vacío.")
        }

        val header = ByteArray(4)
        val readHead = try {
            FileInputStream(file).use { it.read(header) }
        } catch (e: Exception) {
            return InspectResult.Error("No se pudo leer el archivo descargado: ${e.message}")
        }
        if (readHead < 4 || !isZipMagic(header)) {
            return InspectResult.Error(
                "El archivo no es un paquete ZIP/APK válido (si es un .apkm cifrado de APKMirror, " +
                    "convertilo primero a .apks sin cifrar)."
            )
        }

        return try {
            ZipFile(file).use { outerZip ->
                if (outerZip.getEntry("AndroidManifest.xml") != null) {
                    return@use InspectResult.SingleApk
                }

                val apkEntries = ArrayList<ZipEntry>()
                var hasObb = false
                var scanned = 0
                val enumEntries = outerZip.entries()
                while (enumEntries.hasMoreElements()) {
                    scanned++
                    if (scanned > MAX_OUTER_ZIP_ENTRIES) {
                        return@use InspectResult.Error("El paquete contiene demasiadas entradas internas (límite $MAX_OUTER_ZIP_ENTRIES).")
                    }
                    val entry = enumEntries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val lower = name.lowercase(Locale.ROOT)
                    if (lower.startsWith("android/obb/") && lower.endsWith(".obb")) {
                        hasObb = true
                    }
                    if (lower.endsWith(".apk")) {
                        if (!isSafeZipEntryName(name)) {
                            return@use InspectResult.Error("El paquete contiene una entrada APK con ruta insegura: $name")
                        }
                        if (apkEntries.size >= MAX_SPLIT_ENTRIES) {
                            return@use InspectResult.Error("El paquete supera el límite de $MAX_SPLIT_ENTRIES APKs internos.")
                        }
                        apkEntries.add(entry)
                    }
                }

                if (apkEntries.isEmpty()) {
                    return@use InspectResult.Error("El archivo no es un APK ni contiene archivos .apk en su interior.")
                }

                val inspected = ArrayList<SplitEntryInfo>(apkEntries.size)
                for (apkEntry in apkEntries) {
                    val parsed = inspectInnerApkStream(outerZip, apkEntry)
                    val resolved = if (parsed.manifest != null || fallbackTempDir == null) {
                        parsed
                    } else {
                        inspectInnerApkFallback(outerZip, apkEntry, fallbackTempDir) ?: parsed
                    }
                    inspected.add(
                        SplitEntryInfo(
                            entryName = apkEntry.name,
                            sizeBytes = apkEntry.size,
                            manifest = resolved.manifest,
                            abis = resolved.abis
                        )
                    )
                }

                InspectResult.SplitBundle(entries = inspected, hasObb = hasObb)
            }
        } catch (e: Exception) {
            InspectResult.Error("No se pudo leer el paquete ZIP (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private class InnerInspection(
        val manifest: BinaryManifestParser.ManifestInfo?,
        val abis: Set<String>
    )

    private fun inspectInnerApkStream(outerZip: ZipFile, apkEntry: ZipEntry): InnerInspection {
        var manifest: BinaryManifestParser.ManifestInfo? = null
        val libNames = ArrayList<String>()
        try {
            ZipInputStream(BufferedInputStream(outerZip.getInputStream(apkEntry), 16384)).use { zis ->
                while (true) {
                    val inner = zis.nextEntry ?: break
                    if (!inner.isDirectory) {
                        val innerName = inner.name
                        if (innerName == "AndroidManifest.xml" && manifest == null) {
                            val bytes = readStreamBounded(zis, MAX_MANIFEST_BYTES)
                            if (bytes != null) {
                                manifest = BinaryManifestParser.parse(bytes)
                            }
                        } else if (innerName.startsWith("lib/") && innerName.endsWith(".so")) {
                            libNames.add(innerName)
                        }
                    }
                    zis.closeEntry()
                }
            }
        } catch (_: Exception) {
            // Fallback por archivo temporal si el stream comprimido interno usa descriptores especiales
        }
        return InnerInspection(manifest, UpdateRoutePlanner.abisFromZipEntries(libNames))
    }

    private fun inspectInnerApkFallback(
        outerZip: ZipFile,
        apkEntry: ZipEntry,
        tempDir: File
    ): InnerInspection? {
        if (!tempDir.exists()) tempDir.mkdirs()
        val tmp = try {
            File.createTempFile("split_inspect_", ".apk", tempDir)
        } catch (_: Exception) {
            return null
        }
        return try {
            outerZip.getInputStream(apkEntry).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            return null
                        }
                        output.write(buf, 0, n)
                    }
                }
            }
            ZipFile(tmp).use { innerZip ->
                val manifestEntry = innerZip.getEntry("AndroidManifest.xml") ?: return null
                val manifestBytes = innerZip.getInputStream(manifestEntry).use {
                    readStreamBounded(it, MAX_MANIFEST_BYTES)
                } ?: return null
                val manifest = BinaryManifestParser.parse(manifestBytes)
                val libNames = ArrayList<String>()
                val entries = innerZip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory && e.name.startsWith("lib/") && e.name.endsWith(".so")) {
                        libNames.add(e.name)
                    }
                }
                InnerInspection(manifest, UpdateRoutePlanner.abisFromZipEntries(libNames))
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun readStreamBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            baos.write(buf, 0, n)
        }
        return baos.toByteArray()
    }

    fun selectSplits(
        entries: List<SplitEntryInfo>,
        expectedPackageName: String?,
        deviceAbis: List<String>,
        deviceDensityDpi: Int,
        deviceLocales: List<String>,
        deviceSdk: Int
    ): SelectionResult {
        if (entries.isEmpty()) {
            return SelectionResult.Error("El paquete dividido está vacío.")
        }

        for (e in entries) {
            val m = e.manifest
            if (m == null || m.packageName.isNullOrBlank()) {
                return SelectionResult.Error(
                    "Uno de los APKs del paquete (${e.entryName}) no tiene un AndroidManifest.xml legible."
                )
            }
        }

        val bases = entries.filter { it.manifest!!.splitName.isNullOrBlank() }
        if (bases.isEmpty()) {
            return SelectionResult.Error("El paquete dividido no contiene un APK base (sin atributo split).")
        }
        if (bases.size > 1) {
            return SelectionResult.Error(
                "El paquete contiene más de un APK base (${bases.joinToString(", ") { it.entryName }})."
            )
        }

        val base = bases[0]
        val baseManifest = base.manifest!!
        val basePkg = baseManifest.packageName!!

        for (e in entries) {
            val p = e.manifest!!.packageName!!
            if (p != basePkg) {
                return SelectionResult.Error(
                    "El archivo ${e.entryName} pertenece a otro paquete ($p) distinto de $basePkg."
                )
            }
        }

        if (!expectedPackageName.isNullOrBlank() && basePkg != expectedPackageName) {
            return SelectionResult.Error(
                "El paquete descargado es de $basePkg, pero se esperaba $expectedPackageName."
            )
        }

        if (deviceSdk > 0 && baseManifest.minSdkVersion > deviceSdk) {
            return SelectionResult.Error(
                "La aplicación requiere Android con API ${baseManifest.minSdkVersion} y este equipo tiene API $deviceSdk."
            )
        }

        val nonBases = entries.filter { !it.manifest!!.splitName.isNullOrBlank() }
        val classified = nonBases.map { classify(it) }

        val abiSplits = classified.filter { it.kind == SplitKind.ABI }
        val baseAbiSplits = abiSplits.filter { it.module.isEmpty() }
        val availableAbis = LinkedHashSet<String>().apply {
            when {
                baseAbiSplits.isNotEmpty() -> baseAbiSplits.forEach { addAll(it.splitAbis) }
                abiSplits.isNotEmpty() -> abiSplits.forEach { addAll(it.splitAbis) }
                else -> addAll(base.abis)
            }
        }

        val selectedAbiSplits = if (availableAbis.isNotEmpty() && deviceAbis.isNotEmpty()) {
            val winningAbi = deviceAbis.firstOrNull { it in availableAbis }
            if (winningAbi == null) {
                return SelectionResult.Error(
                    "El paquete solo soporta arquitecturas ${availableAbis.sorted().joinToString(", ")} " +
                        "y este equipo es ${deviceAbis.joinToString(", ")}.",
                    availableAbis = availableAbis
                )
            }
            abiSplits.filter { winningAbi in it.splitAbis }.map { it.entry }
        } else {
            abiSplits.map { it.entry }
        }

        val selectedDensitySplits = ArrayList<SplitEntryInfo>()
        val densityByModule = classified.filter { it.kind == SplitKind.DENSITY }.groupBy { it.module }
        for ((_, list) in densityByModule) {
            val chosen = chooseDensitySplit(list, deviceDensityDpi)
            if (chosen != null) selectedDensitySplits.add(chosen.entry)
        }

        val wantedLangs = LinkedHashSet<String>().apply {
            add("es")
            add("en")
            add("he")
            for (loc in deviceLocales) {
                val lang = canonicalBaseLang(loc)
                if (lang.length >= 2) add(lang)
            }
        }
        val selectedLangSplits = ArrayList<SplitEntryInfo>()
        val langsByModule = classified.filter { it.kind == SplitKind.LANGUAGE }.groupBy { it.module }
        for ((_, list) in langsByModule) {
            val matched = list.filter { canonicalBaseLang(it.qualifier) in wantedLangs }
            if (matched.isNotEmpty()) {
                matched.forEach { selectedLangSplits.add(it.entry) }
            } else {
                val fallback = list.firstOrNull { canonicalBaseLang(it.qualifier) == "en" } ?: list.first()
                selectedLangSplits.add(fallback.entry)
            }
        }

        val featureOrOtherSplits = classified
            .filter { it.kind == SplitKind.FEATURE_OR_OTHER }
            .map { it.entry }

        val selected = ArrayList<SplitEntryInfo>(
            1 + featureOrOtherSplits.size + selectedAbiSplits.size + selectedDensitySplits.size + selectedLangSplits.size
        ).apply {
            add(base)
            addAll(featureOrOtherSplits)
            addAll(selectedAbiSplits)
            addAll(selectedDensitySplits)
            addAll(selectedLangSplits)
        }

        var totalBytes = 0L
        for (s in selected) {
            if (s.sizeBytes > 0L) {
                totalBytes += s.sizeBytes
                if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    return SelectionResult.Error(
                        "Los splits seleccionados superan el límite máximo permitido (1 GB).",
                        availableAbis = availableAbis
                    )
                }
            }
        }

        return SelectionResult.Selected(
            base = base,
            splits = selected,
            totalBytes = totalBytes,
            availableAbis = availableAbis
        )
    }

    private fun classify(entry: SplitEntryInfo): ClassifiedSplit {
        val m = entry.manifest!!
        val splitName = m.splitName.orEmpty()
        val cfgForSplit = m.configForSplit.orEmpty()
        val isFeature = m.isFeatureSplit

        val rawModule = when {
            cfgForSplit.isNotBlank() -> cfgForSplit.trim()
            splitName.contains(".config.") -> splitName.substringBefore(".config.")
            else -> ""
        }
        val module = if (rawModule == "base") "" else rawModule

        val isConfigSplit = !isFeature && (
            splitName.startsWith("config.") ||
                splitName.contains(".config.") ||
                cfgForSplit.isNotBlank() ||
                splitName.startsWith("base-")
            )

        val qualifier = when {
            splitName.startsWith("config.") -> splitName.removePrefix("config.")
            splitName.contains(".config.") -> splitName.substringAfter(".config.")
            cfgForSplit.isNotBlank() && splitName.startsWith("$cfgForSplit.") ->
                splitName.removePrefix("$cfgForSplit.")
            splitName.startsWith("base-") -> splitName.removePrefix("base-")
            else -> splitName
        }.trim()

        val qLower = qualifier.lowercase(Locale.ROOT)
        val mappedAbi = mapConfigAbi(qLower)
        val mappedDpi = if (!isFeature) mapConfigDensity(qLower) else null

        return when {
            entry.abis.isNotEmpty() || (!isFeature && mappedAbi != null) -> ClassifiedSplit(
                entry = entry,
                kind = SplitKind.ABI,
                module = module,
                qualifier = qualifier,
                splitAbis = if (entry.abis.isNotEmpty()) entry.abis else setOf(mappedAbi!!),
                dpi = null
            )
            mappedDpi != null -> ClassifiedSplit(
                entry = entry,
                kind = SplitKind.DENSITY,
                module = module,
                qualifier = qualifier,
                splitAbis = emptySet(),
                dpi = mappedDpi
            )
            isConfigSplit && LANG_QUALIFIER_RE.matches(qualifier) -> ClassifiedSplit(
                entry = entry,
                kind = SplitKind.LANGUAGE,
                module = module,
                qualifier = qualifier,
                splitAbis = emptySet(),
                dpi = null
            )
            else -> ClassifiedSplit(
                entry = entry,
                kind = SplitKind.FEATURE_OR_OTHER,
                module = module,
                qualifier = qualifier,
                splitAbis = emptySet(),
                dpi = null
            )
        }
    }

    private fun chooseDensitySplit(list: List<ClassifiedSplit>, deviceDpi: Int): ClassifiedSplit? {
        if (list.isEmpty()) return null
        if (deviceDpi <= 0) return list.maxByOrNull { it.dpi ?: 0 }
        return list.firstOrNull { it.dpi == deviceDpi }
            ?: list.filter { (it.dpi ?: 0) >= deviceDpi }.minByOrNull { it.dpi ?: Int.MAX_VALUE }
            ?: list.maxByOrNull { it.dpi ?: 0 }
    }

    private fun mapConfigAbi(qLower: String): String? = when (qLower) {
        "arm64_v8a", "arm64-v8a" -> "arm64-v8a"
        "armeabi_v7a", "armeabi-v7a" -> "armeabi-v7a"
        "armeabi" -> "armeabi"
        "x86_64", "x86-64" -> "x86_64"
        "x86" -> "x86"
        "mips64" -> "mips64"
        "mips" -> "mips"
        else -> null
    }

    private fun mapConfigDensity(qLower: String): Int? = when (qLower) {
        "ldpi" -> 120
        "mdpi" -> 160
        "tvdpi" -> 213
        "hdpi" -> 240
        "xhdpi" -> 320
        "xxhdpi" -> 480
        "xxxhdpi" -> 640
        else -> null
    }

    private fun canonicalBaseLang(tag: String): String {
        val primary = tag.trim()
            .substringBefore('-')
            .substringBefore('_')
            .lowercase(Locale.ROOT)
        return when (primary) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> primary
        }
    }
}
