package com.ejemplo.locksuite.diag

import com.ejemplo.locksuite.diag.DiagUploadPolicy.Decision
import com.ejemplo.locksuite.diag.DiagUploadPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6/11/2026 (B.110) — cuándo sube el celular. La regla del dueño fue «que no consuma
 * batería»: casi todos los latidos tienen que responder «nada que subir», un error tiene que
 * llegar en minutos pero sin ráfagas, y un fallo permanente (reglas sin desplegar) no puede
 * reintentarse en cada latido.
 */
class DiagUploadPolicyTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun st(
        enabled: Boolean = true, pending: Long = 5, important: Boolean = false,
        lastUpload: Long = 0, lastAttempt: Long = 0, fails: Int = 0,
        dayKey: Long = DiagUploadPolicy.dayKeyOf(now), today: Int = 0
    ) = State(enabled, pending, important, lastUpload, lastAttempt, fails, dayKey, today)

    @Test
    fun apagadoNoSubeNunca() {
        assertEquals(Decision.DISABLED, DiagUploadPolicy.decide(st(enabled = false, important = true), now))
    }

    @Test
    fun sinPendientesNoHaceNada() {
        assertEquals(Decision.NOTHING_PENDING, DiagUploadPolicy.decide(st(pending = 0), now))
    }

    @Test
    fun errorSubeEnElProximoLatido_peroNoMasDeUnaVezCada2Min() {
        assertEquals(Decision.WAIT_GAP, DiagUploadPolicy.decide(st(important = true, lastUpload = now - 60_000), now))
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(important = true, lastUpload = now - 121_000), now))
    }

    @Test
    fun informativosSubenComoMuchoCadaHora() {
        assertEquals(Decision.WAIT_GAP, DiagUploadPolicy.decide(st(lastUpload = now - 30 * min), now))
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(lastUpload = now - 61 * min), now))
    }

    @Test
    fun primeraVezSubeEnSeguida() {
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(lastUpload = 0), now))
    }

    @Test
    fun topeDiario_yReinicioAlOtroDia() {
        val hoy = DiagUploadPolicy.dayKeyOf(now)
        assertEquals(Decision.DAILY_CAP, DiagUploadPolicy.decide(st(important = true, dayKey = hoy, today = DiagUploadPolicy.MAX_UPLOADS_PER_DAY), now))
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(important = true, dayKey = hoy - 1, today = 99), now))
    }

    @Test
    fun trasUnFallo_esperaConRetroceso() {
        // 1 fallo → 5 min; 3 fallos → 20 min.
        assertEquals(Decision.WAIT_BACKOFF, DiagUploadPolicy.decide(st(important = true, fails = 1, lastAttempt = now - 1 * min), now))
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(important = true, fails = 1, lastAttempt = now - 6 * min), now))
        assertEquals(Decision.WAIT_BACKOFF, DiagUploadPolicy.decide(st(important = true, fails = 3, lastAttempt = now - 15 * min), now))
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(important = true, fails = 3, lastAttempt = now - 21 * min), now))
    }

    @Test
    fun backoff_tieneTopeDe6Horas_yNoDesborda() {
        assertEquals(0L, DiagUploadPolicy.backoffMs(0))
        assertEquals(5 * min, DiagUploadPolicy.backoffMs(1))
        assertEquals(6 * 60 * min, DiagUploadPolicy.backoffMs(50))
        assertEquals(6 * 60 * min, DiagUploadPolicy.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun relojHaciaAtras_seTrataComoHaceMucho() {
        assertEquals(Decision.UPLOAD, DiagUploadPolicy.decide(st(lastUpload = now + 10 * min), now))
    }

    // ── lote ───────────────────────────────────────────────────────────────────────

    private fun ev(seq: Long, len: Int = 10, level: Char = 'I') =
        DiagEvent(seq, seq, level, "t", "x".repeat(len))

    @Test
    fun lote_conMasDelTopeSeQuedaConLosMasNuevos() {
        val pend = (1L..500L).map { ev(it) }
        val b = DiagUploadPolicy.nextBatch(pend)
        assertEquals(DiagUploadPolicy.MAX_BATCH_EVENTS, b.events.size)
        assertEquals(300, b.skipped)
        assertEquals(301L, b.events.first().seq)
        assertEquals(500L, b.events.last().seq)
    }

    @Test
    fun lote_tambienTienTopeDeCaracteres() {
        val pend = (1L..100L).map { ev(it, len = 1000) }
        val b = DiagUploadPolicy.nextBatch(pend)
        assertTrue(b.events.size in 30..40)
        assertEquals(100L, b.events.last().seq)
        assertEquals(100 - b.events.size, b.skipped)
    }

    @Test
    fun lote_vacioYDeUno() {
        assertTrue(DiagUploadPolicy.nextBatch(emptyList()).events.isEmpty())
        val one = DiagUploadPolicy.nextBatch(listOf(ev(7)))
        assertEquals(1, one.events.size)
        assertEquals(0, one.skipped)
    }

    @Test
    fun lote_conUnSoloMensajeEnorme_igualSubeElMasNuevo() {
        val pend = listOf(ev(1, len = 100), ev(2, len = 50_000))
        val b = DiagUploadPolicy.nextBatch(pend)
        assertEquals(1, b.events.size)
        assertEquals(2L, b.events[0].seq)
        assertEquals(1, b.skipped)
    }

    // ── claves y datos de Firebase ─────────────────────────────────────────────────

    @Test
    fun clavesOrdenanComoTexto() {
        assertEquals("e0000000123", DiagUploadPolicy.eventKey(123))
        assertTrue(DiagUploadPolicy.eventKey(9) < DiagUploadPolicy.eventKey(10))
        assertEquals("s00000004", DiagUploadPolicy.snapshotKey(4))
    }

    @Test
    fun borraLosRenglonesViejosDeFirebase() {
        assertEquals(listOf("e0000000050"), DiagUploadPolicy.staleKeys(listOf(ev(450))))
        assertTrue("antes de llegar al tope no se borra nada", DiagUploadPolicy.staleKeys(listOf(ev(399), ev(400))).isEmpty())
    }

    @Test
    fun eventMap_soloIncluyeNSiSeRepitio() {
        val m1 = DiagUploadPolicy.eventMap(DiagEvent(1, 5, 'E', "vpn", "boom", 1))
        assertEquals(setOf("t", "l", "g", "m"), m1.keys)
        assertEquals("E", m1["l"])
        val m2 = DiagUploadPolicy.eventMap(DiagEvent(1, 5, 'E', "vpn", "boom", 4))
        assertEquals(4, m2["n"])
        assertFalse(m2.containsKey("seq"))
    }
}
