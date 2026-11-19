package com.ejemplo.locksuite.diag

import com.ejemplo.locksuite.diag.ForceStopVerdict.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 19/11/2026 (B.125, T2.3 y T2.4) — detención forzada y tiempo caído. */
class ProcessHealthMathTest {

    private fun at(d: Int, h: Int, mi: Int, s: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(2026, Calendar.NOVEMBER, d, h, mi, s)
        return c.timeInMillis
    }

    // ── detención forzada ────────────────────────────────────────────────────────────
    @Test fun primeraVezNoSeJuzga() =
        assertEquals(Verdict.FIRST_RUN, ForceStopVerdict.decide(false, 1_000L, 0L))

    @Test fun siSeActualizoLoBorradoEsNormal() =
        assertEquals(Verdict.UPDATED, ForceStopVerdict.decide(false, 2_000L, 1_000L))

    @Test fun sinActualizacionYSinSentinelaFueDetencionForzada() =
        assertEquals(Verdict.FORCE_STOPPED, ForceStopVerdict.decide(false, 1_000L, 1_000L))

    @Test fun conSentinelaEsNormal() =
        assertEquals(Verdict.NORMAL, ForceStopVerdict.decide(true, 1_000L, 1_000L))

    // ── tiempo caído ─────────────────────────────────────────────────────────────────
    @Test fun elCasoDel13DeNoviembreDa52h25min() {
        // ANR anotado el 13/11 16:30:07; el proceso nuevo arrancó el 15/11 20:55:27; el equipo
        // se encendió el 8/11 (mucho antes del ANR). `now` = 5 s después del arranque.
        val exitAt = at(13, 16, 30, 7)
        val started = at(15, 20, 55, 27)
        val now = started + 5_000L
        val boot = at(8, 9, 0, 0)
        val o = OutageMath.classify(exitAt, now, uptimeMs = now - boot, processAgeMs = 5_000L)
        assertNotNull(o)
        assertEquals("52 h 25 min", OutageMath.format(o!!.gapMs))
        assertEquals(exitAt, o.diedAt)
        assertEquals(started, o.restartedAt)
    }

    @Test fun elCasoDel16DeNoviembreDa4h26min() {
        val exitAt = at(16, 11, 55, 33)
        val started = at(16, 16, 21, 6)
        val now = started + 1_000L
        val boot = at(16, 11, 54, 0) // el reinicio fue ANTES de la muerte
        val o = OutageMath.classify(exitAt, now, now - boot, 1_000L)
        assertNotNull(o)
        assertEquals("4 h 25 min", OutageMath.format(o!!.gapMs))
    }

    @Test fun unReinicioDelEquipoEnElMedioNoEsCaida() {
        // La muerte fue ANTES del último encendido: el celular estaba apagado.
        val exitAt = at(13, 16, 30, 7)
        val started = at(14, 9, 0, 30)
        val now = started + 2_000L
        val boot = at(14, 9, 0, 0)
        assertNull(OutageMath.classify(exitAt, now, now - boot, 2_000L))
    }

    @Test fun unaActualizacionNoEsCaida() {
        // Murió por instalar y volvió 5 segundos después.
        val exitAt = at(18, 8, 13, 22)
        val started = at(18, 8, 13, 27)
        val now = started + 1_000L
        val boot = at(16, 11, 54, 0)
        assertNull(OutageMath.classify(exitAt, now, now - boot, 1_000L))
    }

    @Test fun elUmbralEsDeDosMinutos() {
        val exitAt = at(18, 8, 0, 0)
        val boot = at(16, 0, 0, 0)
        val justo = exitAt + OutageMath.THRESHOLD_MS
        val now1 = justo + 1_000L
        assertNull(OutageMath.classify(exitAt, now1, now1 - boot, 1_000L))
        val now2 = justo + 2_000L
        assertNotNull(OutageMath.classify(exitAt, now2, now2 - boot, 1_000L))
    }

    @Test fun sinMuerteRegistradaNoHayNada() =
        assertNull(OutageMath.classify(0L, 10_000_000L, 5_000_000L, 1_000L))

    @Test fun formatoCorto() {
        assertEquals("7 min", OutageMath.format(7 * 60_000L))
        assertEquals("1 h 0 min", OutageMath.format(60 * 60_000L))
        assertEquals("0 min", OutageMath.format(-5L))
    }
}
