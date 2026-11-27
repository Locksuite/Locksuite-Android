package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.dns.DomainSafety
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Parser, serializador y normalizador del catálogo de Lista Blanca por dispositivo
 * en formato humano legible `lockfox-dominios-v1` (.txt y .docx).
 *
 * Clases y funciones puras sin Android `Context`: se testean directo en JVM.
 */
object WhitelistCatalogIo {

    const val FORMAT_VERSION = "lockfox-dominios-v1"

    const val STATUS_ENABLED = "enabled"
    const val STATUS_DISABLED = "disabled"
    const val STATUS_BLOCKED = "blocked"

    const val ACCESS_DOMAINS = "domains"
    const val ACCESS_NOWEB = "noweb"
    const val ACCESS_FULL = "full"

    private val DOMAIN_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val PKG_RE = Regex("^[a-zA-Z0-9_.]+$")
    private val COMMENT_RE = Regex("(?:^|\\s)(?:#|//).*$")

    data class WhitelistAppEntry(
        val packageName: String,
        val label: String,
        val status: String = STATUS_ENABLED,
        val access: String = ACCESS_DOMAINS,
        val allow: List<String> = emptyList(),
        val block: List<String> = emptyList()
    )

    data class DeviceWhitelistCatalog(
        val apps: List<WhitelistAppEntry> = emptyList(),
        val globalAllow: List<String> = emptyList(),
        val globalBlock: List<String> = emptyList()
    )

    data class CatalogStats(
        val appCount: Int,
        val enabledAppCount: Int,
        val appAllow: Int,
        val appBlock: Int,
        val globalAllow: Int,
        val globalBlock: Int
    )

    data class CatalogParseResult(
        val ok: Boolean,
        val catalog: DeviceWhitelistCatalog?,
        val warnings: List<String> = emptyList(),
        val error: String? = null
    )

    data class DomainListParseResult(
        val domains: List<String>,
        val rejected: List<Pair<String, String>>
    )

    fun normalizeStatus(raw: String?): String {
        val v = stripAccentsLower(raw)
        return when (v) {
            "disabled", "deshabilitada", "deshabilitado", "false", "no" -> STATUS_DISABLED
            "blocked", "bloqueada", "bloqueado", "prohibida", "prohibido", "block" -> STATUS_BLOCKED
            else -> STATUS_ENABLED
        }
    }

    fun normalizeAccess(raw: String?): String {
        val v = stripAccentsLower(raw)
        return when (v) {
            "noweb", "sin web", "sin_web" -> ACCESS_NOWEB
            "full", "completa", "completo" -> ACCESS_FULL
            else -> ACCESS_DOMAINS
        }
    }

    fun normalizeDomain(raw: String?): String {
        var d = (raw ?: "").trim().lowercase(Locale.ROOT)
        val scheme = d.indexOf("://")
        if (scheme >= 0) d = d.substring(scheme + 3)
        d = d.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in d) d = d.substringAfterLast('@')
        d = d.substringBefore(':')
        while (d.startsWith("*.") || d.startsWith(".")) {
            d = if (d.startsWith("*.")) d.substring(2) else d.substring(1)
        }
        return d.trimEnd('.')
    }

    fun cleanDomainDetailed(raw: String?): Pair<String?, String?> {
        val d = normalizeDomain(raw)
        if (d.isEmpty()) return null to "vacío"
        if ('.' !in d) return null to "es un TLD o una sola palabra"
        if (d.length < 4 || d.length > 253) return null to "largo inválido"
        if (!DOMAIN_RE.matches(d)) return null to "formato inválido"
        if (d.substringAfterLast('.').none { it in 'a'..'z' }) return null to "no es un dominio"
        return d to null
    }

    fun cleanDomainList(list: Iterable<String>): List<String> =
        list.map { it.trim().lowercase(Locale.ROOT).trimEnd('.') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun normalizeCatalog(catalog: DeviceWhitelistCatalog): DeviceWhitelistCatalog {
        val byPkg = LinkedHashMap<String, WhitelistAppEntry>()
        for (rawApp in catalog.apps) {
            val pkg = rawApp.packageName.trim()
            if (pkg.isEmpty()) continue
            val allow = cleanDomainList(rawApp.allow)
            val block = cleanDomainList(rawApp.block)
            byPkg[pkg] = WhitelistAppEntry(
                packageName = pkg,
                label = rawApp.label.trim().ifEmpty { pkg },
                status = normalizeStatus(rawApp.status),
                access = normalizeAccess(rawApp.access),
                allow = allow,
                block = block
            )
        }
        val apps = byPkg.keys.sorted().map { byPkg[it]!! }
        val gBlock = cleanDomainList(catalog.globalBlock)
        val gBlockSet = gBlock.toSet()
        val gAllow = cleanDomainList(catalog.globalAllow).filter { it !in gBlockSet }
        return DeviceWhitelistCatalog(apps = apps, globalAllow = gAllow, globalBlock = gBlock)
    }

    fun stats(catalog: DeviceWhitelistCatalog): CatalogStats {
        val norm = normalizeCatalog(catalog)
        return CatalogStats(
            appCount = norm.apps.size,
            enabledAppCount = norm.apps.count { it.status == STATUS_ENABLED },
            appAllow = norm.apps.sumOf { it.allow.size },
            appBlock = norm.apps.sumOf { it.block.size },
            globalAllow = norm.globalAllow.size,
            globalBlock = norm.globalBlock.size
        )
    }

    fun catalogsDiffer(a: DeviceWhitelistCatalog, b: DeviceWhitelistCatalog): Boolean {
        val na = normalizeCatalog(a)
        val nb = normalizeCatalog(b)
        return toJson(na) != toJson(nb)
    }

    fun parseDomainListText(raw: String, isAllow: Boolean): DomainListParseResult {
        val cleanedText = (raw).removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val accepted = LinkedHashSet<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        for (rawLine in cleanedText.split('\n')) {
            val line = stripComment(rawLine)
            if (line.isEmpty()) continue
            for (tok in line.split(Regex("[\\s,;]+"))) {
                if (tok.isEmpty()) continue
                val (dom, err) = cleanDomainDetailed(tok)
                if (dom == null) {
                    rejected.add(tok.take(60) to (err ?: "inválido"))
                    continue
                }
                if (isAllow) {
                    val safetyWhy = DomainSafety.rejectReason(dom)
                    if (safetyWhy != null) {
                        rejected.add(dom to safetyWhy)
                        continue
                    }
                }
                accepted.add(dom)
            }
        }
        return DomainListParseResult(accepted.sorted(), rejected)
    }

    fun formatDomainListText(domains: List<String>, title: String): String {
        val list = cleanDomainList(domains)
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        sb.append("# Total: ").append(list.size).append('\n')
        for (d in list) sb.append(d).append('\n')
        return sb.toString()
    }

    fun parseCatalogText(raw: String): CatalogParseResult {
        val cleaned = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val warnings = mutableListOf<String>()
        val appOrder = mutableListOf<String>()
        val appsMap = LinkedHashMap<String, MutableAppBuilder>()
        val globalAllow = LinkedHashSet<String>()
        val globalBlock = LinkedHashSet<String>()
        var hasGlobalAllow = false
        var hasGlobalBlock = false
        var foundAnySection = false

        var sectionType: String? = null // app | global_allow | global_block
        var currentApp: MutableAppBuilder? = null
        var currentList: String? = null // allow | block

        fun addTokens(tokensText: String, kind: String, target: MutableSet<String>) {
            for (tok in tokensText.split(Regex("[\\s,;]+"))) {
                if (tok.isEmpty()) continue
                val (dom, err) = cleanDomainDetailed(tok)
                if (dom == null) {
                    warnings.add("${tok.take(60)} (${err ?: "inválido"})")
                    continue
                }
                if (kind == "allow") {
                    val safetyWhy = DomainSafety.rejectReason(dom)
                    if (safetyWhy != null) {
                        warnings.add("$dom ($safetyWhy)")
                        continue
                    }
                }
                target.add(dom)
            }
        }

        for (rawLine in cleaned.split('\n')) {
            val line = stripComment(rawLine)
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                val inner = line.substring(1, line.length - 1).trim()
                val appMatch = Regex("^app\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(inner)
                if (appMatch != null) {
                    val pkg = appMatch.groupValues[1].trim()
                    if (!PKG_RE.matches(pkg) || '.' !in pkg) {
                        warnings.add("[APP: $pkg] (paquete inválido)")
                        sectionType = null
                        currentApp = null
                        currentList = null
                        continue
                    }
                    foundAnySection = true
                    sectionType = "app"
                    currentList = null
                    currentApp = appsMap.getOrPut(pkg) {
                        appOrder.add(pkg)
                        MutableAppBuilder(pkg)
                    }
                    continue
                }
                val normHeader = stripAccentsLower(inner).replace(Regex("\\s+"), " ")
                if (Regex("^global(?:es)?\\s*:\\s*(?:permitidos?|allow)$").matches(normHeader)) {
                    foundAnySection = true
                    sectionType = "global_allow"
                    currentApp = null
                    currentList = null
                    hasGlobalAllow = true
                    continue
                }
                if (Regex("^global(?:es)?\\s*:\\s*(?:prohibidos?|block)$").matches(normHeader)) {
                    foundAnySection = true
                    sectionType = "global_block"
                    currentApp = null
                    currentList = null
                    hasGlobalBlock = true
                    continue
                }
            }

            if (sectionType == "app" && currentApp != null) {
                val kvMatch = Regex("^([A-Za-zÁÉÍÓÚáéíóúÑñ_]+)\\s*:\\s*(.*)$").matchEntire(line)
                if (kvMatch != null) {
                    val key = stripAccentsLower(kvMatch.groupValues[1])
                    val value = kvMatch.groupValues[2].trim()
                    when (key) {
                        "nombre", "label" -> {
                            if (value.isNotEmpty()) currentApp.label = value
                            continue
                        }
                        "estado", "status" -> {
                            currentApp.status = normalizeStatus(value)
                            continue
                        }
                        "acceso", "access" -> {
                            currentApp.access = normalizeAccess(value)
                            continue
                        }
                        "permitidos", "permitido", "allow" -> {
                            currentList = "allow"
                            if (value.isNotEmpty()) addTokens(value, "allow", currentApp.allow)
                            continue
                        }
                        "prohibidos", "prohibido", "block" -> {
                            currentList = "block"
                            if (value.isNotEmpty()) addTokens(value, "block", currentApp.block)
                            continue
                        }
                    }
                }
                when (currentList) {
                    "allow" -> addTokens(line, "allow", currentApp.allow)
                    "block" -> addTokens(line, "block", currentApp.block)
                }
                continue
            }

            if (sectionType == "global_allow") {
                addTokens(line, "allow", globalAllow)
            } else if (sectionType == "global_block") {
                addTokens(line, "block", globalBlock)
            }
        }

        if (!foundAnySection) {
            return CatalogParseResult(
                ok = false,
                catalog = null,
                warnings = warnings,
                error = "El archivo no tiene el formato de catálogo de dominios ($FORMAT_VERSION)."
            )
        }

        val apps = appOrder.map { pkg ->
            val a = appsMap[pkg]!!
            WhitelistAppEntry(
                packageName = a.packageName,
                label = a.label.ifEmpty { a.packageName },
                status = a.status,
                access = a.access,
                allow = a.allow.sorted(),
                block = a.block.sorted()
            )
        }
        val finalBlock = if (hasGlobalBlock) globalBlock.sorted() else emptyList()
        val blockSet = finalBlock.toSet()
        val finalAllow = if (hasGlobalAllow) globalAllow.filter { it !in blockSet }.sorted() else emptyList()

        return CatalogParseResult(
            ok = true,
            catalog = normalizeCatalog(DeviceWhitelistCatalog(apps = apps, globalAllow = finalAllow, globalBlock = finalBlock)),
            warnings = warnings
        )
    }

    fun formatCatalogText(
        catalog: DeviceWhitelistCatalog,
        deviceLabel: String = "",
        reason: String = "",
        note: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val norm = normalizeCatalog(catalog)
        val st = stats(norm)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateIso = sdf.format(Date(nowMs))
        val sb = StringBuilder()
        sb.append("# LockSuite — Catálogo de dominios de lista blanca\n")
        sb.append("# Formato: ").append(FORMAT_VERSION).append('\n')
        sb.append("# Fecha: ").append(dateIso).append('\n')
        if (deviceLabel.isNotBlank()) {
            sb.append("# Celular: ").append(cleanSingleLine(deviceLabel)).append('\n')
        }
        sb.append("# Apps: ").append(st.appCount)
            .append(" | Permitidos app: ").append(st.appAllow)
            .append(" | Prohibidos app: ").append(st.appBlock)
            .append(" | Globales permitidos: ").append(st.globalAllow)
            .append(" | Globales prohibidos: ").append(st.globalBlock)
            .append('\n')
        if (reason.isNotBlank()) sb.append("# Motivo: ").append(cleanSingleLine(reason)).append('\n')
        if (note.isNotBlank()) sb.append("# Nota: ").append(cleanSingleLine(note)).append('\n')
        sb.append("# En Permitidos/Prohibidos vale uno por línea o separados por coma; líneas con # se ignoran.\n\n")

        val sortedApps = norm.apps.sortedWith(
            compareBy<WhitelistAppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName }
        )
        for (app in sortedApps) {
            sb.append("[APP: ").append(app.packageName).append("]\n")
            sb.append("Nombre: ").append(cleanSingleLine(app.label.ifEmpty { app.packageName })).append('\n')
            val estadoStr = when (app.status) {
                STATUS_DISABLED -> "deshabilitada"
                STATUS_BLOCKED -> "bloqueada"
                else -> "habilitada"
            }
            sb.append("Estado: ").append(estadoStr).append('\n')
            sb.append("Acceso: ").append(normalizeAccess(app.access)).append('\n')
            sb.append("Permitidos:\n")
            if (app.allow.isEmpty()) sb.append("# (ninguno)\n")
            else for (d in app.allow) sb.append(d).append('\n')
            sb.append("Prohibidos:\n")
            if (app.block.isEmpty()) sb.append("# (ninguno)\n")
            else for (d in app.block) sb.append(d).append('\n')
            sb.append('\n')
        }

        sb.append("[GLOBAL: PERMITIDOS]\n")
        if (norm.globalAllow.isEmpty()) sb.append("# (ninguno)\n")
        else for (d in norm.globalAllow) sb.append(d).append('\n')
        sb.append("\n[GLOBAL: PROHIBIDOS]\n")
        if (norm.globalBlock.isEmpty()) sb.append("# (ninguno)\n")
        else for (d in norm.globalBlock) sb.append(d).append('\n')
        sb.append('\n')
        return sb.toString()
    }

    fun extractTextFromDocx(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                    val xml = zis.readBytes().toString(Charsets.UTF_8)
                    val withBreaks = xml
                        .replace(Regex("</w:p>"), "\n")
                        .replace(Regex("<w:(?:br|cr)\\b[^>]*/>"), "\n")
                        .replace(Regex("<w:tab\\b[^>]*/>"), " ")
                        .replace(Regex("<[^>]+>"), "")
                    return decodeXmlEntities(withBreaks)
                }
                entry = zis.nextEntry
            }
        }
        throw IllegalArgumentException("El archivo .docx no contiene word/document.xml")
    }

    fun toJson(catalog: DeviceWhitelistCatalog): String {
        val norm = normalizeCatalog(catalog)
        val root = JSONObject()
        val appsArr = JSONArray()
        for (app in norm.apps) {
            val o = JSONObject()
            o.put("packageName", app.packageName)
            o.put("label", app.label)
            o.put("status", app.status)
            o.put("access", app.access)
            o.put("allow", JSONArray(app.allow))
            o.put("block", JSONArray(app.block))
            appsArr.put(o)
        }
        root.put("apps", appsArr)
        root.put("globalAllow", JSONArray(norm.globalAllow))
        root.put("globalBlock", JSONArray(norm.globalBlock))
        return root.toString()
    }

    fun fromJson(raw: String?): DeviceWhitelistCatalog? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val appsList = mutableListOf<WhitelistAppEntry>()
            val appsNode = obj.opt("apps")
            when (appsNode) {
                is JSONArray -> {
                    for (i in 0 until appsNode.length()) {
                        val item = appsNode.optJSONObject(i) ?: continue
                        val pkg = item.optString("packageName", "").trim()
                        if (pkg.isEmpty()) continue
                        appsList.add(parseAppObj(pkg, item))
                    }
                }
                is JSONObject -> {
                    for (k in appsNode.keys()) {
                        val item = appsNode.optJSONObject(k) ?: continue
                        val pkg = item.optString("packageName", "").trim().ifEmpty { k.replace('_', '.') }
                        if (pkg.isEmpty()) continue
                        appsList.add(parseAppObj(pkg, item))
                    }
                }
            }
            val gAllow = jsonArrayToStrings(obj.optJSONArray("globalAllow"))
            val gBlock = jsonArrayToStrings(obj.optJSONArray("globalBlock"))
            normalizeCatalog(DeviceWhitelistCatalog(apps = appsList, globalAllow = gAllow, globalBlock = gBlock))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAppObj(pkg: String, item: JSONObject): WhitelistAppEntry {
        val status = when {
            item.has("status") -> normalizeStatus(item.optString("status", STATUS_ENABLED))
            item.has("enabled") -> if (item.optBoolean("enabled", true)) STATUS_ENABLED else STATUS_DISABLED
            else -> STATUS_ENABLED
        }
        return WhitelistAppEntry(
            packageName = pkg,
            label = item.optString("label", pkg).trim().ifEmpty { pkg },
            status = status,
            access = normalizeAccess(item.optString("access", ACCESS_DOMAINS)),
            allow = jsonArrayToStrings(item.optJSONArray("allow")),
            block = jsonArrayToStrings(item.optJSONArray("block"))
        )
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun stripComment(line: String): String =
        COMMENT_RE.replace(line, "").trim()

    private fun cleanSingleLine(s: String): String =
        s.replace(Regex("[\\r\\n]+"), " ").trim()

    private fun stripAccentsLower(s: String?): String =
        Normalizer.normalize((s ?: "").trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")

    private fun decodeXmlEntities(s: String): String {
        var out = s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        out = Regex("&#(\\d+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            code.toChar().toString()
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            code.toChar().toString()
        }
        return out
    }

    private class MutableAppBuilder(val packageName: String) {
        var label: String = ""
        var status: String = STATUS_ENABLED
        var access: String = ACCESS_DOMAINS
        val allow = LinkedHashSet<String>()
        val block = LinkedHashSet<String>()
    }
}
