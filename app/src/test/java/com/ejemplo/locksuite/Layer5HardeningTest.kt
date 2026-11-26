package com.ejemplo.locksuite

import com.ejemplo.locksuite.security.PinManager
import com.ejemplo.locksuite.service.CommandProcessor
import com.ejemplo.locksuite.util.ApkSignatureVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class Layer5HardeningTest {

    // ── PinManager backward-clock lockout freeze protection ───────────────────

    @Test
    fun computeLockoutRemainingMs_doesNotFreezeOnBackwardWallClockJump() {
        val duration = 300_000L
        val lockWall = 1_750_000_000_000L
        val lockMono = 100_000L

        // Reloj retrocedió 55 años (p. ej. 1970) cuando ya pasaron 4 minutos reales en el mismo arranque
        val remaining = PinManager.computeLockoutRemainingMs(
            wallNow = 10_000L,
            lockWall = lockWall,
            monoNow = lockMono + 240_000L,
            lockMono = lockMono,
            sameBoot = true,
            duration = duration
        )
        assertEquals(60_000L, remaining)

        // Tras los 5 minutos reales, se desbloquea aunque el reloj siga en el pasado
        val afterWindow = PinManager.computeLockoutRemainingMs(
            wallNow = 70_000L,
            lockWall = lockWall,
            monoNow = lockMono + 305_000L,
            lockMono = lockMono,
            sameBoot = true,
            duration = duration
        )
        assertTrue("Debe desbloquearse tras 5 minutos reales (obtenido=$afterWindow)", afterWindow <= 0L)
    }

    @Test
    fun computeLockoutRemainingMs_stillBlocksForwardClockJumpAndReboot() {
        val duration = 300_000L
        val lockWall = 1_750_000_000_000L
        val lockMono = 100_000L

        // Adelanto de reloj +1 hora en el mismo arranque: monoRemaining sigue mandando
        val forwardSameBoot = PinManager.computeLockoutRemainingMs(
            wallNow = lockWall + 3_600_000L,
            lockWall = lockWall,
            monoNow = lockMono + 60_000L,
            lockMono = lockMono,
            sameBoot = true,
            duration = duration
        )
        assertEquals(240_000L, forwardSameBoot)

        // Reinicio + adelanto de reloj: exige ventana completa desde el nuevo arranque
        val afterReboot = PinManager.computeLockoutRemainingMs(
            wallNow = lockWall + 3_600_000L,
            lockWall = lockWall,
            monoNow = 5_000L,
            lockMono = lockMono,
            sameBoot = false,
            duration = duration
        )
        assertEquals(duration, afterReboot)
    }

    // ── CommandProcessor expiresAt & KEY_PIN_CHANGED_AT checks ────────────────

    @Test
    fun checkCommandTemporalValidity_enforcesSignedExpiresAtOnMailboxWhenClockCredible() {
        val nowCredible = 1_760_000_000_000L
        val cmdTime = nowCredible - 60_000L

        // Expirado en el buzón con reloj creíble -> COMMAND_EXPIRED
        assertEquals(
            "COMMAND_EXPIRED",
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.MAILBOX,
                timeMs = cmdTime,
                expiresAtMs = nowCredible - 1_000L,
                expiresAtMalformed = false,
                nowMs = nowCredible,
                lastAcceptedMs = 0L,
                pinChangedAtMs = 0L
            )
        )

        // Todavía vigente -> aceptado
        assertNull(
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.MAILBOX,
                timeMs = cmdTime,
                expiresAtMs = nowCredible + 60_000L,
                expiresAtMalformed = false,
                nowMs = nowCredible,
                lastAcceptedMs = 0L,
                pinChangedAtMs = 0L
            )
        )

        // Sin expiresAt (retrocompatibilidad con payloads viejos) -> aceptado
        assertNull(
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.MAILBOX,
                timeMs = cmdTime,
                expiresAtMs = null,
                expiresAtMalformed = false,
                nowMs = nowCredible,
                lastAcceptedMs = 0L,
                pinChangedAtMs = 0L
            )
        )

        // Reloj no creíble (ej. 1970 tras pérdida de RTC) -> no bloquea buzón por expiresAt
        assertNull(
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.MAILBOX,
                timeMs = cmdTime,
                expiresAtMs = 1_000_000L,
                expiresAtMalformed = false,
                nowMs = 500_000L,
                lastAcceptedMs = 0L,
                pinChangedAtMs = 0L
            )
        )

        // expiresAt malformado -> rechazado aunque el reloj sea antiguo
        assertEquals(
            "INVALID_EXPIRES_AT",
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.MAILBOX,
                timeMs = cmdTime,
                expiresAtMs = null,
                expiresAtMalformed = true,
                nowMs = 500_000L,
                lastAcceptedMs = 0L,
                pinChangedAtMs = 0L
            )
        )
    }

    @Test
    fun checkCommandTemporalValidity_rejectsCommandsSignedBeforePinChangeMinusSlack() {
        val nowCredible = 1_760_000_000_000L
        val pinChangedAt = nowCredible - 5_000L

        // Firmado 11 minutos antes del cambio de PIN -> rechazado
        assertEquals(
            "STALE_PRE_PIN_CHANGE",
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.MAILBOX,
                timeMs = pinChangedAt - 11 * 60 * 1000L,
                expiresAtMs = null,
                expiresAtMalformed = false,
                nowMs = nowCredible,
                lastAcceptedMs = 0L,
                pinChangedAtMs = pinChangedAt
            )
        )

        // Dentro de la tolerancia de reordenamiento (10 min) -> permitido
        assertNull(
            CommandProcessor.checkCommandTemporalValidity(
                source = CommandProcessor.Source.FCM,
                timeMs = pinChangedAt - 5_000L,
                expiresAtMs = null,
                expiresAtMalformed = false,
                nowMs = nowCredible,
                lastAcceptedMs = 0L,
                pinChangedAtMs = pinChangedAt
            )
        )
    }

    // ── ApkSignatureVerifier multi-signer sorted digest ───────────────────────

    @Test
    fun signerSetDigest_preservesSingleSignerAndOrdersMultiSignersDeterministically() {
        val certA = byteArrayOf(1, 2, 3, 4, 5)
        val certB = byteArrayOf(9, 8, 7, 6, 5)

        val expectedSingle = MessageDigest.getInstance("SHA-256").digest(certA)
        assertArrayEquals(expectedSingle, ApkSignatureVerifier.signerSetDigest(listOf(certA)))
        assertArrayEquals(expectedSingle, ApkSignatureVerifier.signerSetDigest(listOf(certA, certA)))

        val ab = ApkSignatureVerifier.signerSetDigest(listOf(certA, certB))!!
        val ba = ApkSignatureVerifier.signerSetDigest(listOf(certB, certA))!!
        assertArrayEquals(ab, ba)
        assertNotEquals(
            ab.joinToString("") { "%02x".format(it) },
            expectedSingle.joinToString("") { "%02x".format(it) }
        )
    }
}
