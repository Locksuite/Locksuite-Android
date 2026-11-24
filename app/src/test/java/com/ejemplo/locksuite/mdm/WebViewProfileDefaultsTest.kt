package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewProfileDefaultsTest {

    @Test
    fun defaultProfileBlockedPackagesIncluyeWazeYDidiConReglasCore() {
        val pkgs = WebViewPolicy.DEFAULT_PROFILE_BLOCKED_PACKAGES
        assertTrue(pkgs.contains("com.waze"))
        assertTrue(pkgs.contains("com.didiglobal.passenger"))
        for (pkg in pkgs) {
            assertNotNull("Falta CORE_DOMAINS en WebViewPolicy para $pkg", WebViewPolicy.getCoreDomainsFor(pkg))
        }
    }

    @Test
    fun liberarPropiedadEnTiendasConservaBloqueoWazeYDidi() {
        val waze = "com.waze"
        val didi = "com.didiglobal.passenger"
        val ownedAfterRelease = emptySet<String>()
        val currentlyBlocked = setOf(waze, didi)

        // Cuando ninguna tienda tiene ownership sobre Waze/DiDi, reconciliar sin pedir "noweb"
        // jamás desbloquea Waze ni DiDi.
        val playPlan = PlayStoreCatalog.planWebBlocks(
            desired = emptySet(),
            currentlyBlocked = currentlyBlocked,
            owned = ownedAfterRelease
        )
        assertFalse(playPlan.toUnblock.contains(waze))
        assertFalse(playPlan.toUnblock.contains(didi))
    }
}
