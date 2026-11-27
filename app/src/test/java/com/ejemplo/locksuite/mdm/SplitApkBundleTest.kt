package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.SplitApkBundle.InspectResult
import com.ejemplo.locksuite.mdm.SplitApkBundle.SelectionResult
import com.ejemplo.locksuite.mdm.SplitApkBundle.SplitEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SplitApkBundleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor de AXML sintético (UTF-8 / UTF-16LE + RES_XML_RESOURCE_MAP)
    // ─────────────────────────────────────────────────────────────────────────

    private data class AxmlAttr(
        val resId: Int,
        val name: String,
        val valueType: Int,
        val strValue: String? = null,
        val intValue: Int = 0
    )

    private fun buildAxml(
        attrs: List<AxmlAttr>,
        minSdk: Int? = null,
        utf8Pool: Boolean = true,
        omitAttrNamesWithResId: Boolean = false
    ): ByteArray {
        val strings = ArrayList<String>()
        val resIds = ArrayList<Int>()

        fun addString(s: String, resId: Int = 0): Int {
            val existing = strings.indexOf(s)
            if (existing >= 0 && (resId == 0 || (existing < resIds.size && resIds[existing] == resId))) {
                return existing
            }
            strings.add(s)
            resIds.add(resId)
            return strings.size - 1
        }

        val manifestTagIdx = addString("manifest")
        val usesSdkTagIdx = if (minSdk != null) addString("uses-sdk") else -1
        val appTagIdx = addString("application")

        data class PreparedAttr(val nameIdx: Int, val rawValIdx: Int, val valType: Int, val valData: Int)

        val manifestPrepared = attrs.map { a ->
            val nameStr = if (omitAttrNamesWithResId && a.resId != 0) "" else a.name
            val nIdx = strings.size
            strings.add(nameStr)
            resIds.add(a.resId)
            if (a.strValue != null) {
                val vIdx = addString(a.strValue)
                PreparedAttr(nIdx, vIdx, 0x03, vIdx)
            } else {
                PreparedAttr(nIdx, -1, a.valueType, a.intValue)
            }
        }

        val usesSdkPrepared = if (minSdk != null) {
            val nIdx = strings.size
            strings.add(if (omitAttrNamesWithResId) "" else "minSdkVersion")
            resIds.add(0x0101020c)
            listOf(PreparedAttr(nIdx, -1, 0x10, minSdk))
        } else emptyList()

        // String pool chunk
        val strData = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = strData.size()
            val s = strings[i]
            if (utf8Pool) {
                val utf8 = s.toByteArray(Charsets.UTF_8)
                writeUtf8Len(strData, s.length)
                writeUtf8Len(strData, utf8.size)
                strData.write(utf8)
                strData.write(0)
            } else {
                writeU16(strData, s.length)
                strData.write(s.toByteArray(Charsets.UTF_16LE))
                writeU16(strData, 0)
            }
        }
        while (strData.size() % 4 != 0) strData.write(0)

        val spHeaderSize = 28
        val spOffsetsSize = strings.size * 4
        val spStringsStart = spHeaderSize + spOffsetsSize
        val spChunkSize = spStringsStart + strData.size()
        val spBuf = ByteBuffer.allocate(spChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        spBuf.putShort(0x0001).putShort(spHeaderSize.toShort()).putInt(spChunkSize)
        spBuf.putInt(strings.size).putInt(0)
        spBuf.putInt(if (utf8Pool) 0x100 else 0)
        spBuf.putInt(spStringsStart).putInt(0)
        for (off in offsets) spBuf.putInt(off)
        spBuf.put(strData.toByteArray())

        // Resource map chunk
        val rmChunkSize = 8 + resIds.size * 4
        val rmBuf = ByteBuffer.allocate(rmChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        rmBuf.putShort(0x0180).putShort(8).putInt(rmChunkSize)
        for (id in resIds) rmBuf.putInt(id)

        fun startElemChunk(tagIdx: Int, list: List<PreparedAttr>): ByteArray {
            val size = 36 + list.size * 20
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0102).putShort(16).putInt(size)
            b.putInt(1).putInt(-1)
            b.putInt(-1).putInt(tagIdx)
            b.putShort(20).putShort(20).putShort(list.size.toShort())
            b.putShort(0).putShort(0).putShort(0)
            for (p in list) {
                b.putInt(-1).putInt(p.nameIdx).putInt(p.rawValIdx)
                b.putShort(8).put(0).put(p.valType.toByte()).putInt(p.valData)
            }
            return b.array()
        }

        val manifestChunk = startElemChunk(manifestTagIdx, manifestPrepared)
        val usesSdkChunk = if (usesSdkTagIdx >= 0) startElemChunk(usesSdkTagIdx, usesSdkPrepared) else ByteArray(0)
        val appChunk = startElemChunk(appTagIdx, emptyList())

        val totalSize = 8 + spChunkSize + rmChunkSize + manifestChunk.size + usesSdkChunk.size + appChunk.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003).putShort(8).putInt(totalSize)
        out.put(spBuf.array())
        out.put(rmBuf.array())
        out.put(manifestChunk)
        out.put(usesSdkChunk)
        out.put(appChunk)
        return out.array()
    }

    private fun writeUtf8Len(out: ByteArrayOutputStream, len: Int) {
        if (len >= 0x80) {
            out.write(((len ushr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len and 0x7F)
        }
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun buildApkBytes(
        axml: ByteArray,
        extraEntries: List<String> = emptyList()
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(axml)
            zos.closeEntry()
            for (name in extraEntries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun writeZipFile(dest: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return dest
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BinaryManifestParser
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun binaryManifestParser_leeApkRealDeLockSuite() {
        val apk = listOf(
            File("admin-backend/public/locksuite-latest.apk"),
            File("../admin-backend/public/locksuite-latest.apk")
        ).firstOrNull { it.exists() }
        assertNotNull("Debe existir admin-backend/public/locksuite-latest.apk para la prueba", apk)

        val manifestBytes = ZipFile(apk!!).use { z ->
            z.getInputStream(z.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val info = BinaryManifestParser.parse(manifestBytes)
        assertNotNull(info)
        assertEquals("com.ejemplo.locksuite", info!!.packageName)
        assertNull(info.splitName)
        assertTrue(info.versionCode > 0L)
        assertTrue(info.minSdkVersion >= 24)
    }

    @Test
    fun binaryManifestParser_utf8YUtf16YVersionCode64BitsYResourceMap() {
        val attrs = listOf(
            AxmlAttr(0, "package", 0x03, strValue = "com.example.app"),
            AxmlAttr(0, "split", 0x03, strValue = "feature_maps.config.arm64_v8a"),
            AxmlAttr(0x0101055b, "configForSplit", 0x03, strValue = "feature_maps"),
            AxmlAttr(0x0101055a, "isFeatureSplit", 0x12, intValue = 0),
            AxmlAttr(0x0101021c, "versionName", 0x03, strValue = "6.3.7"),
            AxmlAttr(0x0101021b, "versionCode", 0x10, intValue = 12345),
            AxmlAttr(0x01010576, "versionCodeMajor", 0x10, intValue = 2)
        )
        val expectedVc = (2L shl 32) or 12345L

        for (utf8 in listOf(true, false)) {
            val info = BinaryManifestParser.parse(
                buildAxml(attrs, minSdk = 26, utf8Pool = utf8, omitAttrNamesWithResId = true)
            )
            assertNotNull("utf8=$utf8", info)
            assertEquals("com.example.app", info!!.packageName)
            assertEquals("feature_maps.config.arm64_v8a", info.splitName)
            assertEquals("feature_maps", info.configForSplit)
            assertFalse(info.isFeatureSplit)
            assertEquals("6.3.7", info.versionName)
            assertEquals(expectedVc, info.versionCode)
            assertEquals(26, info.minSdkVersion)
        }
    }

    @Test
    fun binaryManifestParser_datosTruncadosOBasuraDevuelvenNull() {
        assertNull(BinaryManifestParser.parse(null))
        assertNull(BinaryManifestParser.parse(ByteArray(0)))
        assertNull(BinaryManifestParser.parse(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        val valido = buildAxml(listOf(AxmlAttr(0, "package", 0x03, strValue = "com.x.y")))
        assertNull(BinaryManifestParser.parse(valido.copyOf(12)))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SplitApkBundle — inspección y selección
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeTransitBundleZip(dest: File): File {
        val pkg = "com.example.transit"
        val baseAxml = buildAxml(
            listOf(
                AxmlAttr(0, "package", 0x03, strValue = pkg),
                AxmlAttr(0x0101021c, "versionName", 0x03, strValue = "6.3.7"),
                AxmlAttr(0x0101021b, "versionCode", 0x10, intValue = 200)
            ),
            minSdk = 26
        )
        fun splitAxml(split: String, cfgFor: String? = null, feature: Boolean = false): ByteArray {
            val l = mutableListOf(
                AxmlAttr(0, "package", 0x03, strValue = pkg),
                AxmlAttr(0, "split", 0x03, strValue = split),
                AxmlAttr(0x0101021b, "versionCode", 0x10, intValue = 200)
            )
            if (cfgFor != null) l.add(AxmlAttr(0x0101055b, "configForSplit", 0x03, strValue = cfgFor))
            if (feature) l.add(AxmlAttr(0x0101055a, "isFeatureSplit", 0x12, intValue = -1))
            return buildAxml(l)
        }

        val entries = listOf(
            "base.apk" to buildApkBytes(baseAxml),
            "split_config.arm64_v8a.apk" to buildApkBytes(splitAxml("config.arm64_v8a"), listOf("lib/arm64-v8a/libmap.so")),
            "split_config.armeabi_v7a.apk" to buildApkBytes(splitAxml("config.armeabi_v7a"), listOf("lib/armeabi-v7a/libmap.so")),
            "split_config.x86.apk" to buildApkBytes(splitAxml("config.x86"), listOf("lib/x86/libmap.so")),
            "split_config.hdpi.apk" to buildApkBytes(splitAxml("config.hdpi")),
            "split_config.xhdpi.apk" to buildApkBytes(splitAxml("config.xhdpi")),
            "split_config.xxhdpi.apk" to buildApkBytes(splitAxml("config.xxhdpi")),
            "split_config.es.apk" to buildApkBytes(splitAxml("config.es")),
            "split_config.en.apk" to buildApkBytes(splitAxml("config.en")),
            "split_config.iw.apk" to buildApkBytes(splitAxml("config.iw")),
            "split_config.fr.apk" to buildApkBytes(splitAxml("config.fr")),
            "feature_maps.apk" to buildApkBytes(splitAxml("feature_maps", feature = true)),
            "feature_maps.config.arm64_v8a.apk" to buildApkBytes(
                splitAxml("feature_maps.config.arm64_v8a", cfgFor = "feature_maps"),
                listOf("lib/arm64-v8a/libfeat.so")
            ),
            "feature_maps.config.armeabi_v7a.apk" to buildApkBytes(
                splitAxml("feature_maps.config.armeabi_v7a", cfgFor = "feature_maps"),
                listOf("lib/armeabi-v7a/libfeat.so")
            ),
            "Android/obb/$pkg/main.200.$pkg.obb" to byteArrayOf(1, 2, 3, 4)
        )
        return writeZipFile(dest, entries)
    }

    @Test
    fun inspectBundleZip_apkUnicoDevuelveSingleApk() {
        val axml = buildAxml(listOf(AxmlAttr(0, "package", 0x03, strValue = "com.uno.solo")), minSdk = 24)
        val singleFile = File(tempFolder.root, "single.apk")
        singleFile.writeBytes(buildApkBytes(axml))
        assertTrue(SplitApkBundle.inspectBundleZip(singleFile, tempFolder.root) is InspectResult.SingleApk)
    }

    @Test
    fun selectSplits_equipo64BitsY32BitsYMinSdkYIncompatibles() {
        val bundle = makeTransitBundleZip(File(tempFolder.root, "transit.apks"))
        val insp = SplitApkBundle.inspectBundleZip(bundle, tempFolder.root)
        assertTrue(insp is InspectResult.SplitBundle)
        insp as InspectResult.SplitBundle
        assertEquals(14, insp.entries.size)
        assertTrue(insp.hasObb)

        // Equipo 64-bit, 420 dpi, español
        val sel64 = SplitApkBundle.selectSplits(
            entries = insp.entries,
            expectedPackageName = "com.example.transit",
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            deviceDensityDpi = 420,
            deviceLocales = listOf("es-AR"),
            deviceSdk = 33
        )
        assertTrue(sel64 is SelectionResult.Selected)
        sel64 as SelectionResult.Selected
        assertEquals("base.apk", sel64.splits.first().entryName)
        val names64 = sel64.splits.map { it.entryName }.toSet()
        assertEquals(
            setOf(
                "base.apk",
                "feature_maps.apk",
                "split_config.arm64_v8a.apk",
                "feature_maps.config.arm64_v8a.apk",
                "split_config.xxhdpi.apk",
                "split_config.es.apk",
                "split_config.en.apk",
                "split_config.iw.apk"
            ),
            names64
        )
        assertFalse(names64.contains("split_config.armeabi_v7a.apk"))
        assertFalse(names64.contains("split_config.x86.apk"))
        assertFalse(names64.contains("split_config.fr.apk"))

        // Equipo 32-bit (CAT S22 Flip: 286 dpi, he-IL)
        val sel32 = SplitApkBundle.selectSplits(
            entries = insp.entries,
            expectedPackageName = "com.example.transit",
            deviceAbis = listOf("armeabi-v7a", "armeabi"),
            deviceDensityDpi = 286,
            deviceLocales = listOf("he-IL"),
            deviceSdk = 30
        )
        assertTrue(sel32 is SelectionResult.Selected)
        val names32 = (sel32 as SelectionResult.Selected).splits.map { it.entryName }.toSet()
        assertTrue(names32.contains("split_config.armeabi_v7a.apk"))
        assertTrue(names32.contains("feature_maps.config.armeabi_v7a.apk"))
        assertTrue(names32.contains("split_config.xhdpi.apk"))
        assertTrue(names32.contains("split_config.iw.apk"))
        assertFalse(names32.contains("split_config.arm64_v8a.apk"))
        assertFalse(names32.contains("split_config.xxhdpi.apk"))

        // Arquitectura incompatible (x86_64 solo)
        val badAbi = SplitApkBundle.selectSplits(
            entries = insp.entries,
            expectedPackageName = "com.example.transit",
            deviceAbis = listOf("x86_64"),
            deviceDensityDpi = 320,
            deviceLocales = listOf("es"),
            deviceSdk = 33
        )
        assertTrue(badAbi is SelectionResult.Error)
        assertTrue((badAbi as SelectionResult.Error).reason.contains("arm64-v8a"))

        // minSdk mayor que SDK del equipo
        val badSdk = SplitApkBundle.selectSplits(
            entries = insp.entries,
            expectedPackageName = "com.example.transit",
            deviceAbis = listOf("arm64-v8a"),
            deviceDensityDpi = 320,
            deviceLocales = listOf("es"),
            deviceSdk = 24
        )
        assertTrue(badSdk is SelectionResult.Error)
        assertTrue((badSdk as SelectionResult.Error).reason.contains("26"))
    }

    @Test
    fun controlesNegativos_zipSlip_apkmCifrado_sinBase_dobleBase_otroPaquete_y_sessionName() {
        // Zip Slip
        val slipZip = writeZipFile(
            File(tempFolder.root, "slip.apks"),
            listOf("../evil.apk" to byteArrayOf(1, 2, 3))
        )
        val slipRes = SplitApkBundle.inspectBundleZip(slipZip, tempFolder.root)
        assertTrue(slipRes is InspectResult.Error)

        assertFalse(SplitApkBundle.isSafeZipEntryName("../evil.apk"))
        assertFalse(SplitApkBundle.isSafeZipEntryName("/abs.apk"))
        assertFalse(SplitApkBundle.isSafeZipEntryName("splits\\a.apk"))
        assertFalse(SplitApkBundle.isSafeZipEntryName("a\u0000.apk"))
        assertTrue(SplitApkBundle.isSafeZipEntryName("splits/base-master.apk"))

        // Archivo no-ZIP (como un .apkm cifrado)
        val encrypted = File(tempFolder.root, "enc.apkm")
        encrypted.writeBytes(byteArrayOf(0x41, 0x50, 0x4B, 0x4D, 0x01, 0x02))
        val encRes = SplitApkBundle.inspectBundleZip(encrypted, tempFolder.root)
        assertTrue(encRes is InspectResult.Error)
        assertTrue((encRes as InspectResult.Error).reason.contains(".apkm"))

        val baseM = BinaryManifestParser.ManifestInfo("com.a.b", null, null, false, 10L, "1.0", 24)
        val splitSame = BinaryManifestParser.ManifestInfo("com.a.b", "config.es", null, false, 10L, null, 0)
        val splitOther = BinaryManifestParser.ManifestInfo("com.otro.pkg", "config.es", null, false, 10L, null, 0)

        // Sin APK base
        assertTrue(
            SplitApkBundle.selectSplits(
                listOf(SplitEntryInfo("config.es.apk", 100L, splitSame, emptySet())),
                null, listOf("arm64-v8a"), 320, listOf("es"), 33
            ) is SelectionResult.Error
        )
        // Doble APK base
        assertTrue(
            SplitApkBundle.selectSplits(
                listOf(
                    SplitEntryInfo("base1.apk", 100L, baseM, emptySet()),
                    SplitEntryInfo("base2.apk", 100L, baseM, emptySet())
                ),
                null, listOf("arm64-v8a"), 320, listOf("es"), 33
            ) is SelectionResult.Error
        )
        // Split de otro paquete
        assertTrue(
            SplitApkBundle.selectSplits(
                listOf(
                    SplitEntryInfo("base.apk", 100L, baseM, emptySet()),
                    SplitEntryInfo("split_config.es.apk", 100L, splitOther, emptySet())
                ),
                null, listOf("arm64-v8a"), 320, listOf("es"), 33
            ) is SelectionResult.Error
        )
        // expectedPackageName distinto
        assertTrue(
            SplitApkBundle.selectSplits(
                listOf(
                    SplitEntryInfo("base.apk", 100L, baseM, emptySet()),
                    SplitEntryInfo("split_config.es.apk", 100L, splitSame, emptySet())
                ),
                "com.esperado.otro", listOf("arm64-v8a"), 320, listOf("es"), 33
            ) is SelectionResult.Error
        )

        // sessionSplitName sin barras y acotado
        val s1 = SplitApkBundle.sessionSplitName(0, "splits/muy/largo/" + "a".repeat(120) + ".apk")
        val s2 = SplitApkBundle.sessionSplitName(1, "splits/base.apk")
        assertFalse(s1.contains("/"))
        assertTrue(s1.endsWith(".apk"))
        assertTrue(s1.length <= 80)
        assertTrue(s1 != s2)
    }

    @Test
    fun inspectBundleZip_acceptsLargeLegitApkAbove16384Entries() {
        val innerApk = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                val manifestBytes = buildAxml(
                    attrs = listOf(
                        AxmlAttr(0, "package", 0x03, strValue = "com.example.largelegit"),
                        AxmlAttr(0x0101021b, "versionCode", 0x10, intValue = 10),
                        AxmlAttr(0x0101021c, "versionName", 0x03, strValue = "7.7.12")
                    ),
                    minSdk = 24
                )
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(manifestBytes)
                zos.closeEntry()

                for (i in 0 until 18_000) {
                    zos.putNextEntry(ZipEntry("res/raw/e_$i.txt"))
                    zos.closeEntry()
                }
            }
            bos.toByteArray()
        }

        val bundleFile = writeZipFile(
            File(tempFolder.root, "large_legit.apks"),
            listOf("base.apk" to innerApk)
        )
        val res = SplitApkBundle.inspectBundleZip(bundleFile, tempFolder.root)
        assertTrue("Expected SplitBundle for 18k-entry APK, got $res", res is InspectResult.SplitBundle)
        val sel = SplitApkBundle.selectSplits(
            entries = (res as InspectResult.SplitBundle).entries,
            expectedPackageName = "com.example.largelegit",
            deviceAbis = listOf("armeabi-v7a"),
            deviceDensityDpi = 320,
            deviceLocales = listOf("es"),
            deviceSdk = 30
        )
        assertTrue("Expected Selected for 18k-entry APK, got $sel", sel is SelectionResult.Selected)
    }

    @Test
    fun inspectBundleZip_rejectsInnerApkWithTooManyEntries() {
        val innerApk = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                val manifestBytes = buildAxml(
                    attrs = listOf(
                        AxmlAttr(0, "package", 0x03, strValue = "com.example.manyentries"),
                        AxmlAttr(0x0101021b, "versionCode", 0x10, intValue = 1),
                        AxmlAttr(0x0101021c, "versionName", 0x03, strValue = "1.0")
                    ),
                    minSdk = 24
                )
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(manifestBytes)
                zos.closeEntry()

                for (i in 0 until SplitApkBundle.MAX_INNER_ZIP_ENTRIES) {
                    zos.putNextEntry(ZipEntry("res/raw/e_$i.txt"))
                    zos.closeEntry()
                }
            }
            bos.toByteArray()
        }

        val bundleFile = writeZipFile(
            File(tempFolder.root, "many_entries.apks"),
            listOf("base.apk" to innerApk)
        )
        val res = SplitApkBundle.inspectBundleZip(bundleFile, tempFolder.root)
        assertTrue("Expected Error on inner entries > MAX_INNER_ZIP_ENTRIES, got $res", res is InspectResult.Error)
    }

    @Test
    fun inspectBundleZip_rejectsCumulativeInnerZipBomb() {
        // Dos APKs internos de ~530 MB descomprimidos cada uno (superan 1 GB acumulados entre ambos,
        // aunque ninguno individualmente excede 1 GB). Comprimidos al vuelo en pocos KB.
        fun makeLargeInnerApk(pkg: String, uncompressedBytes: Long): ByteArray {
            val zeroChunk = ByteArray(1024 * 1024)
            return ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
                    val manifestBytes = buildAxml(
                        attrs = listOf(
                            AxmlAttr(0, "package", 0x03, strValue = pkg),
                            AxmlAttr(0x0101021b, "versionCode", 0x10, intValue = 1),
                            AxmlAttr(0x0101021c, "versionName", 0x03, strValue = "1.0")
                        ),
                        minSdk = 24
                    )
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(manifestBytes)
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("assets/padding.bin"))
                    var remaining = uncompressedBytes
                    while (remaining > 0L) {
                        val toWrite = minOf(zeroChunk.size.toLong(), remaining).toInt()
                        zos.write(zeroChunk, 0, toWrite)
                        remaining -= toWrite
                    }
                    zos.closeEntry()
                }
                bos.toByteArray()
            }
        }

        val perApk = 530L * 1024L * 1024L
        val apk1 = makeLargeInnerApk("com.example.bomb", perApk)
        val apk2 = makeLargeInnerApk("com.example.bomb", perApk)
        val bundleFile = writeZipFile(
            File(tempFolder.root, "cumulative_bomb.apks"),
            listOf("base.apk" to apk1, "split_config.arm64_v8a.apk" to apk2)
        )
        val res = SplitApkBundle.inspectBundleZip(bundleFile, tempFolder.root)
        assertTrue("Expected Error on cumulative inner bomb > 1GB, got $res", res is InspectResult.Error)
    }
}
