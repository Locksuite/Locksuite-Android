package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUnhideGuardTest {

    @Test
    fun `isWithinWindow acepta dentro del rango y rechaza expirados o negativos`() {
        val win = AppUnhideGuard.UNHIDE_WINDOW_MS
        assertTrue(AppUnhideGuard.isWithinWindow(10_000L, 4, 10_000L, 4))
        assertTrue(AppUnhideGuard.isWithinWindow(10_000L, 4, 15_000L, 4))
        assertTrue(AppUnhideGuard.isWithinWindow(10_000L, 4, 10_000L + win, 4))
        assertFalse(AppUnhideGuard.isWithinWindow(10_000L, 4, 10_000L + win + 1L, 4))
        assertFalse(AppUnhideGuard.isWithinWindow(10_000L, 4, 9_999L, 4))
        assertFalse(AppUnhideGuard.isWithinWindow(0L, 4, 10_000L, 4))
        assertFalse(AppUnhideGuard.isWithinWindow(-5L, 4, 10_000L, 4))
    }

    @Test
    fun `isWithinWindow rechaza cambio de bootCount y desajustes fail-closed`() {
        assertFalse(AppUnhideGuard.isWithinWindow(10_000L, 4, 15_000L, 5))
        assertFalse(AppUnhideGuard.isWithinWindow(10_000L, -1, 15_000L, 5))
        assertFalse(AppUnhideGuard.isWithinWindow(10_000L, 4, 15_000L, -1))
        assertTrue(AppUnhideGuard.isWithinWindow(10_000L, -1, 15_000L, -1))
    }

    @Test
    fun `isIntentionalUnhideBroadcast solo es true si no es reemplazo y hay marca reciente`() {
        assertTrue(AppUnhideGuard.isIntentionalUnhideBroadcast(isReplacing = false, recentlyUnhidden = true))
        assertFalse(AppUnhideGuard.isIntentionalUnhideBroadcast(isReplacing = true, recentlyUnhidden = true))
        assertFalse(AppUnhideGuard.isIntentionalUnhideBroadcast(isReplacing = false, recentlyUnhidden = false))
        assertFalse(AppUnhideGuard.isIntentionalUnhideBroadcast(isReplacing = true, recentlyUnhidden = false))
    }
}
