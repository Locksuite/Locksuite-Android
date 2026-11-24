package com.ejemplo.locksuite.mdm

/**
 * Parser puro de `AndroidManifest.xml` binario (AXML) en Kotlin sin dependencias de Android.
 *
 * Se usa para inspeccionar Split APKs (`.apks`, `.xapk`, `.apkm`) en streaming antes de crear
 * una sesión de `PackageInstaller`, sin extraer los APKs completos a disco ni cargar archivos
 * enteros en memoria. Ante cualquier dato truncado o mal formado devuelve `null` sin lanzar
 * excepciones.
 */
object BinaryManifestParser {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val UTF8_FLAG = 0x100

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    // IDs de atributos estándar de Android (`android:*`) en `RES_XML_RESOURCE_MAP`.
    // Reconocer por resource ID evita depender únicamente del nombre en la string pool
    // (útil cuando herramientas de ofuscación/reducción omiten o acortan nombres).
    private const val ATTR_MIN_SDK_VERSION = 0x0101020c
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_IS_FEATURE_SPLIT = 0x0101055a
    private const val ATTR_CONFIG_FOR_SPLIT = 0x0101055b
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576

    data class ManifestInfo(
        val packageName: String?,
        val splitName: String?,
        val configForSplit: String?,
        val isFeatureSplit: Boolean,
        val versionCode: Long,
        val versionName: String?,
        val minSdkVersion: Int
    )

    fun parse(bytes: ByteArray?): ManifestInfo? {
        if (bytes == null || bytes.size < 16) return null
        return try {
            if (u16(bytes, 0) != RES_XML_TYPE) return null
            val headerSize = u16(bytes, 2)
            val declaredSize = u32(bytes, 4)
            if (headerSize < 8 || declaredSize < 16L) return null
            val fileSize = minOf(bytes.size.toLong(), declaredSize).toInt()
            if (headerSize > fileSize) return null

            var strings: List<String> = emptyList()
            var resourceMap: IntArray = IntArray(0)

            var hasManifest = false
            var packageName: String? = null
            var splitName: String? = null
            var configForSplit: String? = null
            var isFeatureSplit = false
            var versionName: String? = null
            var vcMinor = 0
            var vcMajor = 0
            var hasVcMinor = false
            var hasVcMajor = false
            var minSdkVersion = 0

            var off = headerSize
            while (off + 8 <= fileSize) {
                val chunkType = u16(bytes, off)
                val chunkHdrSize = u16(bytes, off + 2)
                val chunkSizeLong = u32(bytes, off + 4)
                if (chunkSizeLong < 8L || off.toLong() + chunkSizeLong > fileSize.toLong()) break
                val chunkSize = chunkSizeLong.toInt()
                val chunkEnd = off + chunkSize

                when (chunkType) {
                    RES_STRING_POOL_TYPE -> {
                        if (chunkHdrSize >= 28 && off + 28 <= chunkEnd) {
                            strings = parseStringPool(bytes, off, chunkHdrSize, chunkEnd)
                        }
                    }
                    RES_XML_RESOURCE_MAP_TYPE -> {
                        val mapStart = off + chunkHdrSize
                        if (chunkHdrSize >= 8 && mapStart <= chunkEnd) {
                            val count = (chunkEnd - mapStart) / 4
                            val map = IntArray(count)
                            for (i in 0 until count) {
                                map[i] = i32(bytes, mapStart + i * 4)
                            }
                            resourceMap = map
                        }
                    }
                    RES_XML_START_ELEMENT_TYPE -> {
                        val extOff = off + chunkHdrSize
                        if (chunkHdrSize >= 16 && extOff + 20 <= chunkEnd) {
                            val elNameIdx = i32(bytes, extOff + 4)
                            val elName = if (elNameIdx in strings.indices) strings[elNameIdx] else null

                            if (elName == "application") {
                                break
                            }

                            if (elName == "manifest" || elName == "uses-sdk") {
                                val attrStart = u16(bytes, extOff + 8)
                                val attrSize = u16(bytes, extOff + 10)
                                val attrCount = u16(bytes, extOff + 12)
                                var a = extOff + attrStart
                                if (elName == "manifest") hasManifest = true

                                if (attrSize >= 20) {
                                    for (i in 0 until attrCount) {
                                        if (a < extOff || a + 20 > chunkEnd) break
                                        val attrNameIdx = i32(bytes, a + 4)
                                        val attrName = if (attrNameIdx in strings.indices) strings[attrNameIdx] else ""
                                        val resId = if (attrNameIdx in resourceMap.indices) resourceMap[attrNameIdx] else 0
                                        val rawValIdx = i32(bytes, a + 8)
                                        val valType = bytes[a + 15].toInt() and 0xFF
                                        val valData = i32(bytes, a + 16)

                                        fun readStr(): String? {
                                            if (rawValIdx in strings.indices) return strings[rawValIdx]
                                            if (valType == TYPE_STRING && valData in strings.indices) return strings[valData]
                                            return null
                                        }

                                        fun readInt(): Int? {
                                            if (valType == TYPE_INT_DEC || valType == TYPE_INT_HEX) return valData
                                            return readStr()?.trim()?.toIntOrNull()
                                        }

                                        fun readBool(): Boolean {
                                            if (valType == TYPE_INT_BOOLEAN || valType == TYPE_INT_DEC || valType == TYPE_INT_HEX) {
                                                return valData != 0
                                            }
                                            return readStr()?.trim()?.equals("true", ignoreCase = true) == true
                                        }

                                        if (elName == "manifest") {
                                            when {
                                                resId == 0 && attrName == "package" -> packageName = readStr()
                                                resId == 0 && attrName == "split" -> splitName = readStr()
                                                resId == ATTR_CONFIG_FOR_SPLIT || (resId == 0 && attrName == "configForSplit") ->
                                                    configForSplit = readStr()
                                                resId == ATTR_IS_FEATURE_SPLIT || (resId == 0 && attrName == "isFeatureSplit") ->
                                                    isFeatureSplit = readBool()
                                                resId == ATTR_VERSION_NAME || (resId == 0 && attrName == "versionName") ->
                                                    versionName = readStr()
                                                resId == ATTR_VERSION_CODE || (resId == 0 && attrName == "versionCode") -> {
                                                    readInt()?.let {
                                                        vcMinor = it
                                                        hasVcMinor = true
                                                    }
                                                }
                                                resId == ATTR_VERSION_CODE_MAJOR || (resId == 0 && attrName == "versionCodeMajor") -> {
                                                    readInt()?.let {
                                                        vcMajor = it
                                                        hasVcMajor = true
                                                    }
                                                }
                                            }
                                        } else {
                                            if (resId == ATTR_MIN_SDK_VERSION || (resId == 0 && attrName == "minSdkVersion")) {
                                                val v = readInt()
                                                if (v != null && v > 0) {
                                                    minSdkVersion = v
                                                }
                                            }
                                        }

                                        a += attrSize
                                    }
                                }
                            }
                        }
                    }
                }

                val step = maxOf(chunkSize, chunkHdrSize)
                if (step <= 0) break
                off += step
            }

            if (!hasManifest) return null

            val versionCode = if (hasVcMinor || hasVcMajor) {
                ((vcMajor.toLong() and 0xFFFF_FFFFL) shl 32) or (vcMinor.toLong() and 0xFFFF_FFFFL)
            } else {
                -1L
            }

            ManifestInfo(
                packageName = packageName,
                splitName = splitName?.takeIf { it.isNotEmpty() },
                configForSplit = configForSplit?.takeIf { it.isNotEmpty() },
                isFeatureSplit = isFeatureSplit,
                versionCode = versionCode,
                versionName = versionName,
                minSdkVersion = minSdkVersion
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStringPool(bytes: ByteArray, chunkOff: Int, chunkHdrSize: Int, chunkEnd: Int): List<String> {
        val stringCountLong = u32(bytes, chunkOff + 8)
        val flags = u32(bytes, chunkOff + 16).toInt()
        val stringsStartRel = u32(bytes, chunkOff + 20)
        if (stringCountLong < 0L || stringCountLong > 200_000L) return emptyList()
        val stringCount = stringCountLong.toInt()
        val offsetsStart = chunkOff + chunkHdrSize
        val stringsStartLong = chunkOff.toLong() + stringsStartRel
        if (stringsStartLong < chunkOff.toLong() || stringsStartLong > chunkEnd.toLong()) return emptyList()
        val stringsStart = stringsStartLong.toInt()
        val isUtf8 = (flags and UTF8_FLAG) != 0

        val out = ArrayList<String>(minOf(stringCount, 4096))
        for (i in 0 until stringCount) {
            val offPos = offsetsStart + i * 4
            if (offPos + 4 > chunkEnd) break
            val rel = u32(bytes, offPos)
            val strPosLong = stringsStart.toLong() + rel
            if (strPosLong < stringsStart.toLong() || strPosLong >= chunkEnd.toLong()) {
                out.add("")
                continue
            }
            var pos = strPosLong.toInt()
            if (isUtf8) {
                // UTF-8 pool: charCount (1..2 bytes) seguido de byteCount (1..2 bytes)
                if (pos >= chunkEnd) {
                    out.add("")
                    continue
                }
                val b0 = bytes[pos++].toInt() and 0xFF
                if ((b0 and 0x80) != 0) {
                    if (pos >= chunkEnd) {
                        out.add("")
                        continue
                    }
                    pos++
                }
                if (pos >= chunkEnd) {
                    out.add("")
                    continue
                }
                var byteLen = bytes[pos++].toInt() and 0xFF
                if ((byteLen and 0x80) != 0) {
                    if (pos >= chunkEnd) {
                        out.add("")
                        continue
                    }
                    val low = bytes[pos++].toInt() and 0xFF
                    byteLen = ((byteLen and 0x7F) shl 8) or low
                }
                val safeLen = minOf(byteLen, chunkEnd - pos).coerceAtLeast(0)
                out.add(String(bytes, pos, safeLen, Charsets.UTF_8))
            } else {
                if (pos + 2 > chunkEnd) {
                    out.add("")
                    continue
                }
                var charLen = u16(bytes, pos)
                pos += 2
                if ((charLen and 0x8000) != 0) {
                    if (pos + 2 > chunkEnd) {
                        out.add("")
                        continue
                    }
                    val low = u16(bytes, pos)
                    pos += 2
                    charLen = ((charLen and 0x7FFF) shl 16) or low
                }
                val byteLenLong = charLen.toLong() * 2L
                val avail = (chunkEnd - pos).coerceAtLeast(0)
                val safeBytes = minOf(byteLenLong, avail.toLong()).toInt()
                val evenBytes = safeBytes and 0x7FFF_FFFE
                out.add(String(bytes, pos, evenBytes, Charsets.UTF_16LE))
            }
        }
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun u32(b: ByteArray, off: Int): Long = i32(b, off).toLong() and 0xFFFF_FFFFL
}
