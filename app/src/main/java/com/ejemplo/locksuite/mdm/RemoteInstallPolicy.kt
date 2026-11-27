package com.ejemplo.locksuite.mdm

import java.util.Locale

/**
 * Política pura y fail-closed para la instalación remota por administrador (`INSTALL_APP`).
 *
 * El comando remoto solo transporta `packageName`; el APK y su huella SHA-256 se resuelven
 * exclusivamente desde fuentes oficiales del servidor (`auroraStore/packages` o `storeApps`).
 */
object RemoteInstallPolicy {

    const val MAX_PACKAGE_NAME_LEN = 200

    val TRUSTED_APK_PREFIXES = listOf(
        AuroraStoreCatalog.ALLOWED_APK_PREFIX,
        "https://locksuite-nueva.web.app/"
    )

    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    data class InstallCandidate(
        val packageName: String,
        val label: String,
        val apkUrl: String,
        val sha256: String,
        val versionCode: Long,
        val sizeBytes: Long,
        val source: String
    )

    sealed class Resolution {
        data class Allowed(val candidate: InstallCandidate) : Resolution()
        data class Rejected(val reason: String) : Resolution()
    }

    /**
     * Valida que [packageName] sea instalable de forma remota. Devuelve `null` si es válido,
     * o un mensaje legible si debe rechazarse.
     */
    fun validatePackageName(packageName: String?, isCritical: Boolean = false): String? {
        val pkg = packageName?.trim() ?: ""
        if (pkg.isEmpty()) return "Falta el nombre de paquete."
        if (pkg.length > MAX_PACKAGE_NAME_LEN) return "El nombre de paquete supera el máximo permitido."
        if (!PlayStoreCatalog.isValidPackageName(pkg)) return "Nombre de paquete inválido: $pkg"
        if (pkg in PlayStoreCatalog.NEVER_OFFERED) {
            return "El paquete $pkg es crítico del sistema o de LockSuite y no puede instalarse remotamente."
        }
        if (pkg in AndroidAutoStubs.PACKAGES) {
            return "El paquete $pkg es un stub protegido de Android Auto."
        }
        if (isCritical) {
            return "El paquete $pkg está protegido como crítico en este equipo."
        }
        return null
    }

    fun isTrustedApkUrl(url: String?): Boolean {
        if (url == null) return false
        for (ch in url) {
            if (ch.code < 0x20 || ch.code == 0x7f) return false
        }
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains("..")) return false
        for (ch in trimmed) {
            if (ch.isWhitespace()) return false
        }
        return TRUSTED_APK_PREFIXES.any { trimmed.startsWith(it) }
    }

    fun isValidSha256(sha256: String?): Boolean {
        val trimmed = sha256?.trim() ?: return false
        return SHA256_REGEX.matches(trimmed)
    }

    fun minRequiredFreeMb(sizeBytes: Long): Long {
        val sizeMb = if (sizeBytes > 0L) (sizeBytes / (1024L * 1024L)).coerceAtLeast(1L) else 0L
        return maxOf(350L, sizeMb * 2L + 150L)
    }

    fun resolveCandidate(
        packageName: String,
        auroraPkg: AuroraStoreCatalog.AuroraPackage?,
        deviceAbis: List<String>,
        classicLabel: String?,
        classicUrl: String?,
        classicSha256: String?,
        classicVersionCode: Long = 0L,
        classicSizeBytes: Long = 0L,
        isCritical: Boolean = false
    ): Resolution {
        validatePackageName(packageName, isCritical)?.let { return Resolution.Rejected(it) }

        val auroraReady = auroraPkg != null && auroraPkg.status == AuroraStoreCatalog.STATUS_READY
        if (auroraReady && auroraPkg != null) {
            val variant = AuroraStoreCatalog.selectVariant(auroraPkg, deviceAbis)
            if (variant != null && isTrustedApkUrl(variant.apkUrl) && isValidSha256(variant.sha256)) {
                val label = auroraPkg.title.trim().ifEmpty { packageName }
                return Resolution.Allowed(
                    InstallCandidate(
                        packageName = packageName,
                        label = label,
                        apkUrl = variant.apkUrl.trim(),
                        sha256 = variant.sha256.trim().lowercase(Locale.ROOT),
                        versionCode = variant.versionCode,
                        sizeBytes = variant.sizeBytes,
                        source = "aurora"
                    )
                )
            }
        }

        var classicError: String? = null
        val trimmedClassicUrl = classicUrl?.trim()
        if (!trimmedClassicUrl.isNullOrEmpty()) {
            if (!isTrustedApkUrl(trimmedClassicUrl)) {
                classicError = "La copia de Tienda clásica para $packageName no proviene de un almacenamiento oficial."
            } else if (!isValidSha256(classicSha256)) {
                classicError = "La copia de Tienda clásica para $packageName no tiene huella SHA-256 válida."
            } else {
                val label = classicLabel?.trim()?.takeIf { it.isNotEmpty() } ?: packageName
                return Resolution.Allowed(
                    InstallCandidate(
                        packageName = packageName,
                        label = label,
                        apkUrl = trimmedClassicUrl,
                        sha256 = classicSha256!!.trim().lowercase(Locale.ROOT),
                        versionCode = classicVersionCode.coerceAtLeast(0L),
                        sizeBytes = classicSizeBytes.coerceAtLeast(0L),
                        source = "classic"
                    )
                )
            }
        }

        if (auroraReady && auroraPkg != null && auroraPkg.variants.isNotEmpty()) {
            return Resolution.Rejected(
                "No hay una copia compatible con la arquitectura de este equipo (${deviceAbis.joinToString(", ")})."
            )
        }
        if (classicError != null) {
            return Resolution.Rejected(classicError)
        }
        return Resolution.Rejected(
            "La app $packageName todavía no está lista en el servidor. Sincronizala primero desde el panel."
        )
    }
}
