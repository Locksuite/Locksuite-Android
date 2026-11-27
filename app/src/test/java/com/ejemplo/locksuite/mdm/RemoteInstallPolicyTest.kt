package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.AuroraPackage
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.AuroraVariant
import com.ejemplo.locksuite.mdm.RemoteInstallPolicy.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInstallPolicyTest {

    private val validSha = "a".repeat(64)
    private val validSha2 = "b".repeat(64)
    private val gcsUrl = "https://storage.googleapis.com/locksuite-nueva-store-apks/aurora/com_kiloo_subwaysurf/app.apks"
    private val hostingUrl = "https://locksuite-nueva.web.app/wallpapers.apk"

    @Test
    fun `validatePackageName acepta paquetes validos y rechaza invalidos, vacios, largos o criticos`() {
        assertNull(RemoteInstallPolicy.validatePackageName("com.kiloo.subwaysurf"))
        assertNotNull(RemoteInstallPolicy.validatePackageName(""))
        assertNotNull(RemoteInstallPolicy.validatePackageName("invalid"))
        assertNotNull(RemoteInstallPolicy.validatePackageName("com." + "a".repeat(200)))
        assertNotNull(RemoteInstallPolicy.validatePackageName("com.ejemplo.locksuite"))
        assertNotNull(RemoteInstallPolicy.validatePackageName("com.android.vending"))
        assertNotNull(RemoteInstallPolicy.validatePackageName("com.google.android.gms"))
        assertNotNull(RemoteInstallPolicy.validatePackageName("com.google.android.apps.maps"))
        assertNotNull(RemoteInstallPolicy.validatePackageName("com.custom.dialer", isCritical = true))
    }

    @Test
    fun `isTrustedApkUrl acepta solo origenes oficiales sin traversal ni espacios`() {
        assertTrue(RemoteInstallPolicy.isTrustedApkUrl(gcsUrl))
        assertTrue(RemoteInstallPolicy.isTrustedApkUrl(hostingUrl))
        assertFalse(RemoteInstallPolicy.isTrustedApkUrl("https://evil.com/app.apk"))
        assertFalse(RemoteInstallPolicy.isTrustedApkUrl("http://storage.googleapis.com/locksuite-nueva-store-apks/a.apk"))
        assertFalse(RemoteInstallPolicy.isTrustedApkUrl(gcsUrl + "/../secret.apk"))
        assertFalse(RemoteInstallPolicy.isTrustedApkUrl(gcsUrl + " extra"))
        assertFalse(RemoteInstallPolicy.isTrustedApkUrl(gcsUrl + "\n"))
        assertFalse(RemoteInstallPolicy.isTrustedApkUrl(null))
    }

    @Test
    fun `isValidSha256 exige exactamente 64 hexadecimales`() {
        assertTrue(RemoteInstallPolicy.isValidSha256(validSha))
        assertTrue(RemoteInstallPolicy.isValidSha256("A".repeat(64)))
        assertFalse(RemoteInstallPolicy.isValidSha256("a".repeat(63)))
        assertFalse(RemoteInstallPolicy.isValidSha256("g".repeat(64)))
        assertFalse(RemoteInstallPolicy.isValidSha256(null))
    }

    @Test
    fun `resolveCandidate prioriza Aurora compatible y respeta fallback clasico verificado`() {
        val aurora = AuroraPackage(
            packageName = "com.kiloo.subwaysurf",
            title = "Subway Surfers",
            versionCode = 300L,
            versionName = "3.0.0",
            status = "ready",
            error = null,
            updatedAt = 1L,
            hasObb = false,
            variants = mapOf(
                "arm64_v8a" to AuroraVariant(
                    key = "arm64_v8a",
                    apkUrl = gcsUrl,
                    sha256 = validSha,
                    sizeBytes = 120_000_000L,
                    versionCode = 300L,
                    versionName = "3.0.0",
                    splitCount = 2,
                    bundleFormat = "apks",
                    abis = listOf("arm64-v8a")
                )
            )
        )

        val okAurora = RemoteInstallPolicy.resolveCandidate(
            packageName = "com.kiloo.subwaysurf",
            auroraPkg = aurora,
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a"),
            classicLabel = "Classic",
            classicUrl = hostingUrl,
            classicSha256 = validSha2
        )
        assertTrue(okAurora is Resolution.Allowed)
        assertEquals("aurora", (okAurora as Resolution.Allowed).candidate.source)
        assertEquals(gcsUrl, okAurora.candidate.apkUrl)

        val incompatibleAbi = RemoteInstallPolicy.resolveCandidate(
            packageName = "com.kiloo.subwaysurf",
            auroraPkg = aurora,
            deviceAbis = listOf("armeabi-v7a"),
            classicLabel = null,
            classicUrl = null,
            classicSha256 = null
        )
        assertTrue(incompatibleAbi is Resolution.Rejected)

        val okClassic = RemoteInstallPolicy.resolveCandidate(
            packageName = "com.example.tool",
            auroraPkg = null,
            deviceAbis = listOf("arm64-v8a"),
            classicLabel = "Tool",
            classicUrl = hostingUrl,
            classicSha256 = validSha2
        )
        assertTrue(okClassic is Resolution.Allowed)
        assertEquals("classic", (okClassic as Resolution.Allowed).candidate.source)

        val externalClassic = RemoteInstallPolicy.resolveCandidate(
            packageName = "com.example.tool",
            auroraPkg = null,
            deviceAbis = listOf("arm64-v8a"),
            classicLabel = "Tool",
            classicUrl = "https://github.com/other/app.apk",
            classicSha256 = validSha2
        )
        assertTrue(externalClassic is Resolution.Rejected)

        val noShaClassic = RemoteInstallPolicy.resolveCandidate(
            packageName = "com.example.tool",
            auroraPkg = null,
            deviceAbis = listOf("arm64-v8a"),
            classicLabel = "Tool",
            classicUrl = hostingUrl,
            classicSha256 = ""
        )
        assertTrue(noShaClassic is Resolution.Rejected)

        val missing = RemoteInstallPolicy.resolveCandidate(
            packageName = "com.example.missing",
            auroraPkg = null,
            deviceAbis = listOf("arm64-v8a"),
            classicLabel = null,
            classicUrl = null,
            classicSha256 = null
        )
        assertTrue(missing is Resolution.Rejected)
    }

    @Test
    fun `isInstalledButHidden ignora dpmHidden true cuando el paquete no esta instalado`() {
        assertFalse(RemoteInstallPolicy.isPackageInstalled(null))
        assertFalse(RemoteInstallPolicy.isPackageInstalled(0))
        assertFalse(RemoteInstallPolicy.isInstalledButHidden(null, dpmHidden = true))
        assertFalse(RemoteInstallPolicy.isInstalledButHidden(0, dpmHidden = true))
        assertTrue(RemoteInstallPolicy.isPackageInstalled(RemoteInstallPolicy.FLAG_INSTALLED))
        assertTrue(RemoteInstallPolicy.isInstalledButHidden(RemoteInstallPolicy.FLAG_INSTALLED, dpmHidden = true))
        assertFalse(RemoteInstallPolicy.isInstalledButHidden(RemoteInstallPolicy.FLAG_INSTALLED, dpmHidden = false))
    }
}
