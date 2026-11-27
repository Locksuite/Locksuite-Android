package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.UnifiedStoreLogic.Delivery
import com.ejemplo.locksuite.mdm.UnifiedStoreLogic.InstalledAppSnapshot
import com.ejemplo.locksuite.mdm.UnifiedStoreLogic.RowAction
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedStoreLogicTest {

    private val goodSha = "a".repeat(64)
    private val signer1 = "1".repeat(64)
    private val signer2 = "2".repeat(64)

    private fun variant(vc: Long, source: String = "apkpure", signer: String? = signer1) =
        AuroraStoreCatalog.AuroraVariant(
            key = "universal",
            apkUrl = AuroraStoreCatalog.ALLOWED_APK_PREFIX + "aurora/test/${vc}_universal.apk",
            sha256 = goodSha,
            sizeBytes = 12_000_000L,
            versionCode = vc,
            versionName = "v$vc",
            splitCount = 0,
            bundleFormat = "apk",
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            source = source,
            signerSha256 = signer
        )

    private fun serverPkg(
        pkg: String,
        vc: Long,
        source: String = "apkpure",
        signer: String? = signer1,
        withVariant: Boolean = true
    ) = AuroraStoreCatalog.AuroraPackage(
        packageName = pkg,
        title = pkg,
        versionCode = vc,
        versionName = "v$vc",
        status = if (withVariant) AuroraStoreCatalog.STATUS_READY else "play_fallback",
        error = null,
        updatedAt = 1L,
        hasObb = false,
        variants = if (withVariant) mapOf("universal" to variant(vc, source, signer)) else emptyMap(),
        primarySource = source,
        latestVersionCode = vc,
        latestVersionName = "v$vc"
    )

    private fun inst(
        pkg: String,
        label: String,
        vc: Long,
        isSystem: Boolean = false,
        isBlocked: Boolean = false,
        isCritical: Boolean = false,
        isStub: Boolean = false,
        signer: String? = signer1
    ) = InstalledAppSnapshot(
        packageName = pkg,
        label = label,
        versionCode = vc,
        versionName = "1.0",
        isSystem = isSystem,
        isUpdatedSystem = false,
        isBlocked = isBlocked,
        isCritical = isCritical,
        isStub = isStub,
        signerSha256 = signer
    )

    @Test
    fun buildRows_unifiesCatalogAndUpdatableAppsAndExcludesBlockedSystemGms() {
        val catalog = listOf(
            PlayStoreCatalog.StoreApp("com.waze", "Waze", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_ENABLED),
            PlayStoreCatalog.StoreApp("com.request.app", "RequestApp", 1, PlayStoreCatalog.ACCESS_NO_WEB, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_REQUEST),
            PlayStoreCatalog.StoreApp("com.playonly.app", "PlayOnlyApp", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_ENABLED)
        )
        val installed = listOf(
            // User app with silent update
            inst("com.whatsapp", "WhatsApp", vc = 100L),
            // User app already up to date -> OPEN + "Sin actualizaciones"
            inst("com.mercadopago.wallet", "Mercado Pago", vc = 200L),
            // Safe updatable system app (Android Auto) with update
            inst("com.google.android.projection.gearhead", "Android Auto", vc = 10L, isSystem = true),
            // Play Services -> MUST NEVER appear
            inst("com.google.android.gms", "Google Play Services", vc = 500L, isSystem = true, isCritical = true),
            // Blocked user app -> MUST NEVER appear
            inst("com.blocked.app", "Blocked", vc = 100L, isBlocked = true),
            // Non-updatable random system app -> MUST NEVER appear
            inst("com.android.bluetooth", "Bluetooth", vc = 1L, isSystem = true),
            // AA stub -> MUST NEVER appear
            inst("com.google.android.apps.maps", "Maps", vc = 2100000000L, isStub = true)
        )
        val server = mapOf(
            "com.waze" to serverPkg("com.waze", 500L, source = "apkpure"),
            "com.whatsapp" to serverPkg("com.whatsapp", 300L, source = "apkpure"),
            "com.mercadopago.wallet" to serverPkg("com.mercadopago.wallet", 200L, source = "apkpure"),
            "com.google.android.projection.gearhead" to serverPkg("com.google.android.projection.gearhead", 50L, source = "aurora")
        )

        val rows = UnifiedStoreLogic.buildRows(
            storeOpen = true,
            catalogApps = catalog,
            installedApps = installed,
            serverPackages = server,
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        val byPkg = rows.associateBy { it.packageName }
        assertNull(byPkg["com.google.android.gms"])
        assertNull(byPkg["com.blocked.app"])
        assertNull(byPkg["com.android.bluetooth"])
        assertNull(byPkg["com.google.android.apps.maps"])

        assertEquals(RowAction.UPDATE_SILENT, byPkg["com.google.android.projection.gearhead"]!!.action)
        assertEquals("aurora", byPkg["com.google.android.projection.gearhead"]!!.source)
        assertEquals(RowAction.UPDATE_SILENT, byPkg["com.whatsapp"]!!.action)
        assertEquals(RowAction.INSTALL_SILENT, byPkg["com.waze"]!!.action)
        assertEquals(RowAction.INSTALL_PLAY, byPkg["com.playonly.app"]!!.action)
        assertEquals(Delivery.GOOGLE_PLAY, byPkg["com.playonly.app"]!!.delivery)
        assertEquals(RowAction.REQUEST, byPkg["com.request.app"]!!.action)
        assertEquals(RowAction.OPEN_UP_TO_DATE, byPkg["com.mercadopago.wallet"]!!.action)

        // Order check: updates come before installs, which come before requests, which come before OPEN
        val idxUpdate = rows.indexOfFirst { it.packageName == "com.whatsapp" }
        val idxInstall = rows.indexOfFirst { it.packageName == "com.waze" }
        val idxReq = rows.indexOfFirst { it.packageName == "com.request.app" }
        val idxOpen = rows.indexOfFirst { it.packageName == "com.mercadopago.wallet" }
        assertTrue(idxUpdate < idxInstall && idxInstall < idxReq && idxReq < idxOpen)
    }

    @Test
    fun buildRows_routesSignatureMismatchToPlayStoreFallback() {
        val installed = listOf(inst("com.example.app", "Example", vc = 100L, signer = signer1))
        val server = mapOf("com.example.app" to serverPkg("com.example.app", 200L, source = "fdroid", signer = signer2))

        val rows = UnifiedStoreLogic.buildRows(
            storeOpen = true,
            catalogApps = emptyList(),
            installedApps = installed,
            serverPackages = server,
            deviceAbis = listOf("arm64-v8a")
        )

        val row = rows.single()
        assertEquals(RowAction.UPDATE_PLAY, row.action)
        assertEquals(Delivery.GOOGLE_PLAY, row.delivery)
        assertTrue(row.signatureMismatch)
    }

    @Test
    fun buildRows_whenStoreClosed_showsOnlyInstalledNonBlockedAppsAndNoCatalogInstalls() {
        val catalog = listOf(
            PlayStoreCatalog.StoreApp("com.waze", "Waze", 1, PlayStoreCatalog.ACCESS_FULL, emptyList(), emptyList(), null, false, PlayStoreCatalog.STATUS_ENABLED)
        )
        val installed = listOf(inst("com.whatsapp", "WhatsApp", vc = 100L))
        val server = mapOf("com.whatsapp" to serverPkg("com.whatsapp", 200L))

        val rows = UnifiedStoreLogic.buildRows(
            storeOpen = false,
            catalogApps = catalog,
            installedApps = installed,
            serverPackages = server,
            deviceAbis = listOf("arm64-v8a")
        )

        assertEquals(1, rows.size)
        assertEquals("com.whatsapp", rows[0].packageName)
        assertEquals(RowAction.UPDATE_SILENT, rows[0].action)
    }

    @Test
    fun syncDue_respects48HoursInterval() {
        val h48 = 48L * 60L * 60L * 1000L
        assertTrue(FirebaseDeviceSync.isStoreBgSyncDue(0L, 1_000_000L))
        assertFalse(FirebaseDeviceSync.isStoreBgSyncDue(1_000_000L, 1_000_000L + h48 - 1L))
        assertTrue(FirebaseDeviceSync.isStoreBgSyncDue(1_000_000L, 1_000_000L + h48))
    }
}
