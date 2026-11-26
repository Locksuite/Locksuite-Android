package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.ItemAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraStoreCatalogTest {

    private val goodSha = "a".repeat(64)
    private val goodSha2 = "b".repeat(64)

    @Test
    fun parsePackages_acceptsValidAndPinsBucketAndSha256() {
        val json = """
            {
              "com_waze": {
                "packageName": "com.waze",
                "title": "Waze",
                "versionCode": 1000,
                "versionName": "4.108.0",
                "status": "ready",
                "variants": {
                  "arm64_v8a": {
                    "apkUrl": "https://storage.googleapis.com/locksuite-nueva-store-apks/aurora/com_waze/1000_arm64_v8a.apks",
                    "sha256": "$goodSha",
                    "sizeBytes": 50000000,
                    "versionCode": 1000,
                    "versionName": "4.108.0",
                    "splitCount": 3,
                    "bundleFormat": "apks",
                    "abis": ["arm64-v8a"]
                  },
                  "armeabi_v7a": {
                    "apkUrl": "https://storage.googleapis.com/locksuite-nueva-store-apks/aurora/com_waze/1000_armeabi_v7a.apks",
                    "sha256": "$goodSha2",
                    "sizeBytes": 42000000,
                    "versionCode": 1000,
                    "versionName": "4.108.0",
                    "splitCount": 3,
                    "bundleFormat": "apks",
                    "abis": ["armeabi-v7a"]
                  }
                }
              },
              "com_evil_host": {
                "packageName": "com.evil.host",
                "versionCode": 10,
                "status": "ready",
                "variants": {
                  "universal": {
                    "apkUrl": "https://evil.example.com/app.apk",
                    "sha256": "$goodSha",
                    "versionCode": 10
                  }
                }
              },
              "com_bad_sha": {
                "packageName": "com.bad.sha",
                "versionCode": 10,
                "status": "ready",
                "variants": {
                  "universal": {
                    "apkUrl": "https://storage.googleapis.com/locksuite-nueva-store-apks/aurora/com_bad_sha/10.apk",
                    "sha256": "short",
                    "versionCode": 10
                  }
                }
              },
              "com_android_vending": {
                "packageName": "com.android.vending",
                "versionCode": 99,
                "status": "ready",
                "variants": {
                  "universal": {
                    "apkUrl": "https://storage.googleapis.com/locksuite-nueva-store-apks/aurora/vending/99.apk",
                    "sha256": "$goodSha",
                    "versionCode": 99
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = AuroraStoreCatalog.parsePackages(json)
        assertNotNull(parsed)
        val map = parsed!!

        // NEVER_OFFERED descartado
        assertNull(map["com.android.vending"])

        // Variantes de hosts ajenos o SHA inválido descartadas
        assertTrue(map["com.evil.host"]!!.variants.isEmpty())
        assertTrue(map["com.bad.sha"]!!.variants.isEmpty())

        val waze = map["com.waze"]!!
        assertEquals(2, waze.variants.size)

        val v64 = AuroraStoreCatalog.selectVariant(waze, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("arm64_v8a", v64?.key)

        val v32 = AuroraStoreCatalog.selectVariant(waze, listOf("armeabi-v7a", "armeabi"))
        assertEquals("armeabi_v7a", v32?.key)

        // ABI no compatible -> null (fail-closed)
        assertNull(AuroraStoreCatalog.selectVariant(waze, listOf("x86_64")))
    }

    @Test
    fun parsePackages_returnsNullOnCorruptJson() {
        assertNull(AuroraStoreCatalog.parsePackages("{corrupt"))
        assertNull(AuroraStoreCatalog.parsePackages(null))
    }

    @Test
    fun actionFor_coversAllStatesAndUpdates() {
        val v = AuroraStoreCatalog.AuroraVariant(
            key = "universal",
            apkUrl = AuroraStoreCatalog.ALLOWED_APK_PREFIX + "aurora/x/10.apk",
            sha256 = goodSha,
            sizeBytes = 1000L,
            versionCode = 200L,
            versionName = "2.0",
            splitCount = 0,
            bundleFormat = "apk",
            abis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertEquals(
            ItemAction.INSTALLING,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_ENABLED, false, -1L, v, isInstalling = true, requested = false)
        )
        assertEquals(
            ItemAction.INSTALL,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_ENABLED, false, -1L, v, isInstalling = false, requested = false)
        )
        assertEquals(
            ItemAction.UNAVAILABLE,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_ENABLED, false, -1L, null, isInstalling = false, requested = false)
        )
        assertEquals(
            ItemAction.UPDATE,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_ENABLED, true, 150L, v, isInstalling = false, requested = false)
        )
        assertEquals(
            ItemAction.OPEN,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_ENABLED, true, 200L, v, isInstalling = false, requested = false)
        )
        assertEquals(
            ItemAction.OPEN,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_ENABLED, true, 250L, v, isInstalling = false, requested = false)
        )
        assertEquals(
            ItemAction.REQUEST,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_REQUEST, false, -1L, v, isInstalling = false, requested = false)
        )
        assertEquals(
            ItemAction.REQUESTED,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_REQUEST, false, -1L, v, isInstalling = false, requested = true)
        )
        // Instalada pero pausada por store en modo a pedido -> vuelve a Pedir
        assertEquals(
            ItemAction.REQUEST,
            AuroraStoreCatalog.actionFor(PlayStoreCatalog.STATUS_REQUEST, true, 150L, v, isInstalling = false, requested = false, pausedByStore = true)
        )
    }

    @Test
    fun updatablePackages_onlyReturnsInstalledEnabledWithNewerServerVersion() {
        val offered = listOf(
            PlayStoreCatalog.StoreApp("com.waze", "Waze", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_ENABLED),
            PlayStoreCatalog.StoreApp("com.whatsapp", "WhatsApp", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_ENABLED),
            PlayStoreCatalog.StoreApp("com.didi", "DiDi", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_REQUEST),
            PlayStoreCatalog.StoreApp("com.notinstalled", "Other", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_ENABLED)
        )
        val mkPkg = { pkg: String, vc: Long ->
            AuroraStoreCatalog.AuroraPackage(
                packageName = pkg,
                title = pkg,
                versionCode = vc,
                versionName = "$vc",
                status = AuroraStoreCatalog.STATUS_READY,
                error = null,
                updatedAt = 1L,
                hasObb = false,
                variants = mapOf(
                    "universal" to AuroraStoreCatalog.AuroraVariant(
                        "universal", AuroraStoreCatalog.ALLOWED_APK_PREFIX + "$pkg.apk",
                        goodSha, 100L, vc, "$vc", 0, "apk", listOf("arm64-v8a")
                    )
                )
            )
        }
        val pkgs = mapOf(
            "com.waze" to mkPkg("com.waze", 500L),
            "com.whatsapp" to mkPkg("com.whatsapp", 300L),
            "com.didi" to mkPkg("com.didi", 900L),
            "com.notinstalled" to mkPkg("com.notinstalled", 400L)
        )
        val installed = mapOf("com.waze" to 400L, "com.whatsapp" to 300L, "com.didi" to 100L)
        val list = AuroraStoreCatalog.updatablePackages(offered, pkgs, installed, listOf("arm64-v8a"))
        assertEquals(listOf("com.waze"), list)
    }
}
