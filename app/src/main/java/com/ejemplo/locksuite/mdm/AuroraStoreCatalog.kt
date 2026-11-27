package com.ejemplo.locksuite.mdm

import java.util.Locale

/**
 * Lógica pura (sin Android ni org.json) de Tienda Aurora (B.132).
 *
 * Parsea y valida los metadatos publicados por la Cloud Function en `auroraStore/packages`
 * (URL fijada al bucket oficial GCS + SHA-256 obligatorio de 64 hex), elige la variante
 * compatible según `Build.SUPPORTED_ABIS` y decide la acción de cada fila
 * (Instalar / Actualizar / Abrir / Pedir).
 */
object AuroraStoreCatalog {

    const val ALLOWED_APK_PREFIX = "https://storage.googleapis.com/locksuite-nueva-store-apks/"
    const val STATUS_READY = "ready"
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    data class AuroraVariant(
        val key: String,
        val apkUrl: String,
        val sha256: String,
        val sizeBytes: Long,
        val versionCode: Long,
        val versionName: String,
        val splitCount: Int,
        val bundleFormat: String,
        val abis: List<String>,
        val source: String = "",
        val signerSha256: String? = null
    )

    data class AuroraPackage(
        val packageName: String,
        val title: String,
        val versionCode: Long,
        val versionName: String,
        val status: String,
        val error: String?,
        val updatedAt: Long,
        val hasObb: Boolean,
        val variants: Map<String, AuroraVariant>,
        val primarySource: String = "",
        val latestVersionCode: Long = 0L,
        val latestVersionName: String = ""
    )

    fun effectiveLatestVersionCode(pkg: AuroraPackage?, variant: AuroraVariant?): Long =
        maxOf(pkg?.latestVersionCode ?: 0L, pkg?.versionCode ?: 0L, variant?.versionCode ?: 0L)

    enum class ItemAction {
        INSTALL,
        UPDATE,
        OPEN,
        INSTALLING,
        REQUEST,
        REQUESTED,
        UNAVAILABLE
    }

    /**
     * Parsea `auroraStore/packages` desde un arreglo JSON o desde un mapa clave->objeto.
     * Retorna `null` si el JSON es inválido (fail-closed: quien llama conserva la copia anterior).
     */
    fun parsePackages(json: String?): Map<String, AuroraPackage>? {
        val raw = json?.trim() ?: return null
        if (raw.isEmpty()) return emptyMap()
        return try {
            val parsed = JsonMini.parse(raw)
            val out = LinkedHashMap<String, AuroraPackage>()
            when (parsed) {
                is List<*> -> {
                    for (entry in parsed) {
                        val map = asMap(entry) ?: continue
                        parseOne(map)?.let { out[it.packageName] = it }
                    }
                }
                is Map<*, *> -> {
                    for ((k, v) in parsed) {
                        val keyStr = k as? String ?: continue
                        val map = asMap(v) ?: continue
                        parseOne(map, keyStr.replace('_', '.'))?.let { out[it.packageName] = it }
                    }
                }
                else -> return null
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    private fun optString(m: Map<String, Any?>, key: String): String =
        (m[key] as? String)?.trim() ?: ""

    private fun optLong(m: Map<String, Any?>, key: String, fallback: Long): Long =
        when (val v = m[key]) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: fallback
            else -> fallback
        }

    private fun optBool(m: Map<String, Any?>, key: String): Boolean =
        (m[key] as? Boolean) == true

    private fun parseOne(m: Map<String, Any?>, fallbackPkg: String? = null): AuroraPackage? {
        val pkg = optString(m, "packageName").ifEmpty { fallbackPkg ?: "" }
        if (!PlayStoreCatalog.isValidPackageName(pkg) || pkg in PlayStoreCatalog.NEVER_OFFERED) {
            return null
        }
        val title = optString(m, "title").ifEmpty { pkg }
        val status = optString(m, "status").lowercase(Locale.ROOT)
        val error = optString(m, "error").ifEmpty { null }
        val updatedAt = optLong(m, "updatedAt", 0L)
        val hasObb = optBool(m, "hasObb")
        val pkgVc = optLong(m, "versionCode", 0L)
        val pkgVn = optString(m, "versionName")
        val primarySource = optString(m, "primarySource").lowercase(Locale.ROOT)
        val latestVc = optLong(m, "latestVersionCode", pkgVc)
        val latestVn = optString(m, "latestVersionName").ifEmpty { pkgVn }

        val variantsMap = asMap(m["variants"])
        val variants = LinkedHashMap<String, AuroraVariant>()
        if (variantsMap != null) {
            for ((vkAny, vv) in variantsMap) {
                val vk = vkAny as? String ?: continue
                val vo = asMap(vv) ?: continue
                parseVariant(vk, vo, pkgVc, pkgVn)?.let { variants[vk] = it }
            }
        }

        return AuroraPackage(
            packageName = pkg,
            title = title,
            versionCode = pkgVc,
            versionName = pkgVn,
            status = status,
            error = error,
            updatedAt = updatedAt,
            hasObb = hasObb,
            variants = variants,
            primarySource = primarySource,
            latestVersionCode = latestVc,
            latestVersionName = latestVn
        )
    }

    private fun parseVariant(
        key: String,
        m: Map<String, Any?>,
        fallbackVc: Long,
        fallbackVn: String
    ): AuroraVariant? {
        val url = optString(m, "apkUrl")
        if (!url.startsWith(ALLOWED_APK_PREFIX)) return null
        val sha = optString(m, "sha256").lowercase(Locale.ROOT)
        if (!SHA256_REGEX.matches(sha)) return null
        val vc = optLong(m, "versionCode", fallbackVc)
        if (vc <= 0L) return null
        val vn = optString(m, "versionName").ifEmpty { fallbackVn }
        val sizeBytes = optLong(m, "sizeBytes", 0L).coerceAtLeast(0L)
        val splitCount = optLong(m, "splitCount", 0L).toInt().coerceAtLeast(0)
        val bundleFormat = optString(m, "bundleFormat").ifEmpty { "apk" }.lowercase(Locale.ROOT)
        val abis = (m["abis"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: emptyList()
        val source = optString(m, "source").lowercase(Locale.ROOT)
        val rawSigner = optString(m, "signerSha256").lowercase(Locale.ROOT)
        val signerSha256 = if (SHA256_REGEX.matches(rawSigner)) rawSigner else null
        return AuroraVariant(
            key = key,
            apkUrl = url,
            sha256 = sha,
            sizeBytes = sizeBytes,
            versionCode = vc,
            versionName = vn,
            splitCount = splitCount,
            bundleFormat = bundleFormat,
            abis = abis,
            source = source,
            signerSha256 = signerSha256
        )
    }

    /** Parser JSON mínimo en Kotlin puro para poder correr tests JVM sin stubs de org.json. */
    private object JsonMini {
        private class Cursor(val s: String) {
            var i = 0
            fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun peek(): Char = s[i]
            fun next(): Char = s[i++]
            fun has(): Boolean = i < s.length
        }

        fun parse(raw: String): Any? {
            val c = Cursor(raw)
            val v = readValue(c)
            c.skipWs()
            if (c.has()) throw IllegalArgumentException("Trailing chars at ${c.i}")
            return v
        }

        private fun readValue(c: Cursor): Any? {
            c.skipWs()
            if (!c.has()) throw IllegalArgumentException("Unexpected EOF")
            return when (val ch = c.peek()) {
                '{' -> readObject(c)
                '[' -> readArray(c)
                '"' -> readString(c)
                't' -> expectLiteral(c, "true", true)
                'f' -> expectLiteral(c, "false", false)
                'n' -> expectLiteral(c, "null", null)
                '-', in '0'..'9' -> readNumber(c)
                else -> throw IllegalArgumentException("Unexpected '$ch' at ${c.i}")
            }
        }

        private fun readObject(c: Cursor): Map<String, Any?> {
            c.next() // '{'
            val map = LinkedHashMap<String, Any?>()
            c.skipWs()
            if (c.has() && c.peek() == '}') { c.next(); return map }
            while (true) {
                c.skipWs()
                if (!c.has() || c.peek() != '"') throw IllegalArgumentException("Expected key at ${c.i}")
                val key = readString(c)
                c.skipWs()
                if (!c.has() || c.next() != ':') throw IllegalArgumentException("Expected ':' at ${c.i}")
                map[key] = readValue(c)
                c.skipWs()
                if (!c.has()) throw IllegalArgumentException("Unterminated object")
                when (c.next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Expected ',' or '}' at ${c.i}")
                }
            }
        }

        private fun readArray(c: Cursor): List<Any?> {
            c.next() // '['
            val list = ArrayList<Any?>()
            c.skipWs()
            if (c.has() && c.peek() == ']') { c.next(); return list }
            while (true) {
                list.add(readValue(c))
                c.skipWs()
                if (!c.has()) throw IllegalArgumentException("Unterminated array")
                when (c.next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Expected ',' or ']' at ${c.i}")
                }
            }
        }

        private fun readString(c: Cursor): String {
            c.next() // '"'
            val sb = StringBuilder()
            while (c.has()) {
                val ch = c.next()
                if (ch == '"') return sb.toString()
                if (ch == '\\') {
                    if (!c.has()) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = c.next()) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (c.i + 4 > c.s.length) throw IllegalArgumentException("Bad unicode escape")
                            val hex = c.s.substring(c.i, c.i + 4)
                            sb.append(hex.toInt(16).toChar())
                            c.i += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$esc")
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(c: Cursor): Number {
            val start = c.i
            while (c.has() && c.peek() in "0123456789.-+eE") c.next()
            val str = c.s.substring(start, c.i)
            return if (str.contains('.') || str.contains('e', true)) str.toDouble() else str.toLong()
        }

        private fun <T> expectLiteral(c: Cursor, lit: String, value: T): T {
            if (!c.s.startsWith(lit, c.i)) throw IllegalArgumentException("Expected $lit at ${c.i}")
            c.i += lit.length
            return value
        }
    }

    /**
     * Elige la variante adecuada para los ABIs del equipo en orden de preferencia.
     * Devuelve `null` si no existe ninguna variante compatible (fail-closed).
     */
    fun selectVariant(pkg: AuroraPackage?, deviceAbis: List<String>): AuroraVariant? {
        if (pkg == null || pkg.variants.isEmpty()) return null
        pkg.variants["universal"]?.let { return it }
        for (rawAbi in deviceAbis) {
            val abi = rawAbi.trim()
            if (abi.isEmpty()) continue
            val normalizedKey = abi.replace('-', '_')
            pkg.variants[normalizedKey]?.let { return it }
            pkg.variants.values.firstOrNull { variant ->
                variant.abis.any { it.equals(abi, ignoreCase = true) }
            }?.let { return it }
        }
        return null
    }

    /**
     * Decide la acción principal para una fila en Tienda Aurora.
     */
    fun actionFor(
        status: String,
        installed: Boolean,
        installedVersionCode: Long,
        variant: AuroraVariant?,
        isInstalling: Boolean,
        requested: Boolean,
        pausedByStore: Boolean = false
    ): ItemAction {
        if (isInstalling) return ItemAction.INSTALLING

        if (status == PlayStoreCatalog.STATUS_REQUEST) {
            if (installed && !pausedByStore) {
                return if (variant != null && installedVersionCode in 1L until variant.versionCode) {
                    ItemAction.UPDATE
                } else {
                    ItemAction.OPEN
                }
            }
            return if (requested) ItemAction.REQUESTED else ItemAction.REQUEST
        }

        if (status == PlayStoreCatalog.STATUS_ENABLED) {
            if (installed) {
                return if (variant != null && installedVersionCode in 1L until variant.versionCode) {
                    ItemAction.UPDATE
                } else {
                    ItemAction.OPEN
                }
            }
            return if (variant != null) ItemAction.INSTALL else ItemAction.UNAVAILABLE
        }

        return ItemAction.UNAVAILABLE
    }

    /**
     * Paquetes ofrecidos actualmente que ya están instalados y tienen actualización lista en GCS.
     */
    fun updatablePackages(
        offeredApps: List<PlayStoreCatalog.StoreApp>,
        packages: Map<String, AuroraPackage>,
        installedVersions: Map<String, Long>,
        deviceAbis: List<String>
    ): List<String> {
        val out = ArrayList<String>()
        for (app in offeredApps) {
            if (app.status != PlayStoreCatalog.STATUS_ENABLED) continue
            val installedVc = installedVersions[app.packageName] ?: continue
            if (installedVc <= 0L) continue
            val variant = selectVariant(packages[app.packageName], deviceAbis) ?: continue
            if (variant.versionCode > installedVc) {
                out.add(app.packageName)
            }
        }
        return out
    }

    fun formatSizeMb(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return ""
        return if (sizeBytes < 1024L * 1024L) {
            "${(sizeBytes / 1024L).coerceAtLeast(1L)} KB"
        } else {
            String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
        }
    }
}
