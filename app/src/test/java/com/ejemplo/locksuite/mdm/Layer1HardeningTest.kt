package com.ejemplo.locksuite.mdm

import android.os.UserManager
import com.ejemplo.locksuite.util.ProcessHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Layer1HardeningTest {

    // ── PolicyManager.profileCreationRestrictions ───────────────────────────────

    @Test
    fun `profileCreationRestrictions incluye usuarios y perfiles gestionados en API menor a 34`() {
        val restrictions = PolicyManager.profileCreationRestrictions(33)
        assertTrue(UserManager.DISALLOW_ADD_USER in restrictions)
        assertTrue(UserManager.DISALLOW_ADD_MANAGED_PROFILE in restrictions)
        assertFalse("no_add_clone_profile" in restrictions)
        assertFalse("no_add_private_profile" in restrictions)
    }

    @Test
    fun `profileCreationRestrictions agrega clone profile desde API 34 y private profile desde API 35`() {
        val api34 = PolicyManager.profileCreationRestrictions(34)
        assertTrue(UserManager.DISALLOW_ADD_USER in api34)
        assertTrue(UserManager.DISALLOW_ADD_MANAGED_PROFILE in api34)
        assertTrue("no_add_clone_profile" in api34)
        assertFalse("no_add_private_profile" in api34)

        val api35 = PolicyManager.profileCreationRestrictions(35)
        assertTrue("no_add_clone_profile" in api35)
        assertTrue("no_add_private_profile" in api35)
    }

    // ── PolicyManager.signaturesMatchConstantTime ─────────────────────────────

    @Test
    fun `signaturesMatchConstantTime rechaza cadenas vacias o en blanco aunque coincidan`() {
        assertFalse(PolicyManager.signaturesMatchConstantTime("", ""))
        assertFalse(PolicyManager.signaturesMatchConstantTime("   ", "   "))
        assertFalse(PolicyManager.signaturesMatchConstantTime("deadbeef", ""))
        assertFalse(PolicyManager.signaturesMatchConstantTime("", "deadbeef"))
    }

    @Test
    fun `signaturesMatchConstantTime compara en tiempo constante normalizando mayusculas y espacios`() {
        assertTrue(PolicyManager.signaturesMatchConstantTime("a1b2c3d4", "A1B2C3D4"))
        assertTrue(PolicyManager.signaturesMatchConstantTime("a1b2c3d4", " a1b2c3d4 "))
        assertFalse(PolicyManager.signaturesMatchConstantTime("a1b2c3d4", "a1b2c3d5"))
        assertFalse(PolicyManager.signaturesMatchConstantTime("a1b2c3d4", "a1b2c3d400"))
    }

    // ── PolicyManager.isTempVpnUnlockActive ───────────────────────────────────

    @Test
    fun `isTempVpnUnlockActive valida ventana monotonica y rechaza reinicio o ventana excesiva`() {
        val win = PolicyManager.VPN_TEMP_UNLOCK_WINDOW_MS
        assertTrue(
            PolicyManager.isTempVpnUnlockActive(
                untilElapsed = 100_000L + win,
                savedBootCount = 7,
                nowElapsed = 120_000L,
                currentBootCount = 7,
                legacyUntilWall = 0L,
                nowWall = 0L
            )
        )
        // Ventana expirada
        assertFalse(
            PolicyManager.isTempVpnUnlockActive(
                untilElapsed = 100_000L + win,
                savedBootCount = 7,
                nowElapsed = 100_000L + win,
                currentBootCount = 7,
                legacyUntilWall = 0L,
                nowWall = 0L
            )
        )
        // Cambio de bootCount (reinicio) invalida el desbloqueo temporal
        assertFalse(
            PolicyManager.isTempVpnUnlockActive(
                untilElapsed = 100_000L + win,
                savedBootCount = 7,
                nowElapsed = 110_000L,
                currentBootCount = 8,
                legacyUntilWall = 0L,
                nowWall = 0L
            )
        )
        // Ventana mayor al máximo permitido (dato corrupto o manipulado)
        assertFalse(
            PolicyManager.isTempVpnUnlockActive(
                untilElapsed = 100_000L + win + 10_000L,
                savedBootCount = 7,
                nowElapsed = 100_000L,
                currentBootCount = 7,
                legacyUntilWall = 0L,
                nowWall = 0L
            )
        )
    }

    @Test
    fun `isTempVpnUnlockActive rechaza atraso de reloj en clave legacy y acepta dentro de ventana`() {
        val win = PolicyManager.VPN_TEMP_UNLOCK_WINDOW_MS
        val nowWall = 1_790_000_000_000L
        assertTrue(
            PolicyManager.isTempVpnUnlockActive(
                untilElapsed = 0L,
                savedBootCount = -1,
                nowElapsed = 50_000L,
                currentBootCount = -1,
                legacyUntilWall = nowWall + 30_000L,
                nowWall = nowWall
            )
        )
        // El usuario atrasó el reloj 1 hora: remainingWall > maxWindowMs -> rechazado
        assertFalse(
            PolicyManager.isTempVpnUnlockActive(
                untilElapsed = 0L,
                savedBootCount = -1,
                nowElapsed = 50_000L,
                currentBootCount = -1,
                legacyUntilWall = nowWall + win + 3_600_000L,
                nowWall = nowWall
            )
        )
    }

    // ── GracePeriodManager.adjustDeadlineForNtpJump ───────────────────────────

    @Test
    fun `GracePeriodManager adjustDeadlineForNtpJump solo ajusta desde fecha de fabrica y acota al remanente`() {
        val dur = 2L * 24 * 60 * 60 * 1000L
        val factoryWall = 1_000_000L
        val realWall = 1_790_000_000_000L
        val accum = 60_000L

        val adjusted = GracePeriodManager.adjustDeadlineForNtpJump(
            currentDeadline = factoryWall + dur,
            durationMs = dur,
            accumulatedMs = accum,
            wallPrevio = factoryWall,
            wallAhora = realWall,
            deltaElapsedMs = accum,
            rebootDetected = false
        )
        assertTrue(adjusted <= realWall + (dur - accum))
        assertEquals(realWall + (dur - accum), adjusted)

        // Si el reloj previo ya era válido (>= 1_700_000_000_000L), no extiende nunca el deadline
        val validDeadline = realWall + dur
        val notAdjusted = GracePeriodManager.adjustDeadlineForNtpJump(
            currentDeadline = validDeadline,
            durationMs = dur,
            accumulatedMs = accum,
            wallPrevio = realWall,
            wallAhora = realWall + 2L * 3600_000L,
            deltaElapsedMs = 30_000L,
            rebootDetected = false
        )
        assertEquals(validDeadline, notAdjusted)

        // Si ya venció el acumulador, el tope es wallAhora + 0
        val exhaustedCap = GracePeriodManager.adjustDeadlineForNtpJump(
            currentDeadline = factoryWall + dur,
            durationMs = dur,
            accumulatedMs = dur + 5_000L,
            wallPrevio = factoryWall,
            wallAhora = realWall,
            deltaElapsedMs = 10_000L,
            rebootDetected = false
        )
        assertEquals(realWall, exhaustedCap)
    }

    // ── ProcessHealth.isSameBoot ──────────────────────────────────────────────

    @Test
    fun `ProcessHealth isSameBoot requiere ambos boot counts validos e iguales`() {
        assertTrue(ProcessHealth.isSameBoot(0L, 0L))
        assertTrue(ProcessHealth.isSameBoot(12L, 12L))
        assertFalse(ProcessHealth.isSameBoot(12L, 13L))
        assertFalse(ProcessHealth.isSameBoot(-1L, -1L))
        assertFalse(ProcessHealth.isSameBoot(-1L, 12L))
        assertFalse(ProcessHealth.isSameBoot(12L, -1L))
    }
}
