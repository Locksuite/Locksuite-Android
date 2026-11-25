package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempInstallWindowClockTest {

    @Test
    fun `validateDuration acepta entre 1 minuto y 30 dias y rechaza fuera de rango`() {
        assertFalse(TempInstallWindowClock.validateDuration(0L))
        assertFalse(TempInstallWindowClock.validateDuration(59_999L))
        assertTrue(TempInstallWindowClock.validateDuration(60_000L))
        assertTrue(TempInstallWindowClock.validateDuration(30L * 60_000L))
        assertTrue(TempInstallWindowClock.validateDuration(30L * 24 * 3600_000L))
        assertFalse(TempInstallWindowClock.validateDuration(30L * 24 * 3600_000L + 1L))
    }

    @Test
    fun `cuenta regresiva normal y vencimiento exacto`() {
        val dur = 15L * 60_000L
        val s0 = TempInstallWindowClock.createState(nowWall = 1_000_000L, elapsedMs = 50_000L, bootCount = 1, durationMs = dur)

        val t1 = TempInstallWindowClock.evaluate(s0, nowWall = 1_000_000L + 5L * 60_000L, elapsedMs = 50_000L + 5L * 60_000L, bootCount = 1)
        assertTrue(t1.active)
        assertFalse(t1.expired)
        assertEquals(10L * 60_000L, t1.remainingMs)

        val t2 = TempInstallWindowClock.evaluate(t1.updatedState, nowWall = 1_000_000L + dur, elapsedMs = 50_000L + dur, bootCount = 1)
        assertFalse(t2.active)
        assertTrue(t2.expired)
        assertEquals(0L, t2.remainingMs)
    }

    @Test
    fun `vence por acumulador monotónico aunque el usuario atrase el reloj del celular`() {
        val dur = 10L * 60_000L
        val s0 = TempInstallWindowClock.createState(nowWall = 10_000_000L, elapsedMs = 1_000L, bootCount = 3, durationMs = dur)

        // El usuario atrasó el reloj 2 días, pero pasaron 10 min reales de uso
        val t = TempInstallWindowClock.evaluate(s0, nowWall = 10_000_000L - 2L * 86_400_000L, elapsedMs = 1_000L + dur, bootCount = 3)
        assertFalse(t.active)
        assertTrue(t.expired)
        assertTrue(t.expireReason?.contains("tiempo de uso") == true)
    }

    @Test
    fun `sobrevive a un reinicio y conserva el tiempo ya acumulado`() {
        val dur = 20L * 60_000L
        val s0 = TempInstallWindowClock.createState(nowWall = 1_000_000L, elapsedMs = 100_000L, bootCount = 5, durationMs = dur)
        // Pasaron 8 min antes del reinicio
        val t1 = TempInstallWindowClock.evaluate(s0, nowWall = 1_480_000L, elapsedMs = 580_000L, bootCount = 5)
        assertTrue(t1.active)
        assertEquals(12L * 60_000L, t1.remainingMs)

        // Reinicio: bootCount = 6, elapsed vuelve a 30s, wall avanzó 1 min durante reinicio
        val t2 = TempInstallWindowClock.evaluate(t1.updatedState, nowWall = 1_540_000L, elapsedMs = 30_000L, bootCount = 6)
        assertTrue(t2.active)
        assertTrue(t2.rebootDetected)
        // remaining = min(wall remaining = 11 min, accum remaining = 11.5 min) = 11 min
        assertEquals(11L * 60_000L, t2.remainingMs)
    }

    @Test
    fun `absorbe salto NTP hacia adelante sin cerrar antes de tiempo`() {
        val dur = 30L * 60_000L
        val s0 = TempInstallWindowClock.createState(nowWall = 1_000_000L, elapsedMs = 10_000L, bootCount = 2, durationMs = dur)
        // Solo pasaron 30 s reales, pero el reloj saltó +2 horas por NTP
        val t = TempInstallWindowClock.evaluate(s0, nowWall = 1_000_000L + 2L * 3600_000L, elapsedMs = 40_000L, bootCount = 2)
        assertTrue(t.active)
        assertFalse(t.expired)
        assertEquals(dur - 30_000L, t.remainingMs)
    }

    @Test
    fun `formatRemaining formatea segundos minutos horas y dias correctamente`() {
        assertEquals("45 s", TempInstallWindowClock.formatRemaining(45_000L))
        assertEquals("15 min", TempInstallWindowClock.formatRemaining(15L * 60_000L))
        assertEquals("1 h 30 min", TempInstallWindowClock.formatRemaining(90L * 60_000L))
        assertEquals("2 h", TempInstallWindowClock.formatRemaining(120L * 60_000L))
        assertEquals("2 d 3 h", TempInstallWindowClock.formatRemaining((2L * 24 + 3) * 3600_000L))
    }
}
