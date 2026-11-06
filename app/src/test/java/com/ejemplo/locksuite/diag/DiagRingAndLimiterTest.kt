package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6/11/2026 (B.110) — qué se conserva cuando el archivo se llena (DiagRing) y el freno de
 * volumen (DiagRateLimiter). Lo que importa: que el ruido NUNCA expulse a un error, que los
 * repetidos se agrupen en vez de llenar el archivo, que lo descartado por exceso deje
 * constancia, y que un reloj que retrocede no deje al freno bloqueado.
 */
class DiagRingAndLimiterTest {

    private fun ev(seq: Long, level: Char) = DiagEvent(seq, seq, level, "t", "m$seq")

    // ── DiagRing ────────────────────────────────────────────────────────────────────

    @Test
    fun ring_bajoElTope_noTocaNada() {
        val l = (1L..50L).map { ev(it, 'I') }
        assertEquals(l, DiagRing.select(l))
    }

    @Test
    fun ring_elRuidoNoExpulsaAlError() {
        // Un error viejo (seq 1) y 999 informativos después: el error TIENE que sobrevivir.
        val l = listOf(ev(1, 'E')) + (2L..1000L).map { ev(it, 'I') }
        val r = DiagRing.select(l, maxTotal = 700, maxImportant = 250)
        assertEquals(700, r.size)
        assertTrue("el error de seq 1 se perdió", r.any { it.seq == 1L })
        assertEquals("sale ordenado por seq", r.map { it.seq }.sorted(), r.map { it.seq })
        assertEquals("lo más nuevo se conserva", 1000L, r.last().seq)
    }

    @Test
    fun ring_laAvalanchaDeErroresTambienTieneTope() {
        val l = (1L..1000L).map { ev(it, 'E') }
        val r = DiagRing.select(l, maxTotal = 700, maxImportant = 250)
        assertEquals(250, r.size)
        assertEquals(751L, r.first().seq)
        assertEquals(1000L, r.last().seq)
    }

    @Test
    fun ring_topeCeroOMayorImportanteQueElTotal() {
        assertTrue(DiagRing.select((1L..5L).map { ev(it, 'E') }, maxTotal = 0).isEmpty())
        val r = DiagRing.select((1L..10L).map { ev(it, 'E') }, maxTotal = 4, maxImportant = 99)
        assertEquals(4, r.size)
    }

    // ── DiagRateLimiter ─────────────────────────────────────────────────────────────

    @Test
    fun limiter_agrupaRepetidosYCuentaLasVeces() {
        val rl = DiagRateLimiter()
        val a = rl.check('E', "vpn", "se cayó", 0)
        assertTrue(a.keep); assertEquals(1, a.count)
        assertFalse(rl.check('E', "vpn", "se cayó", 1_000).keep)
        assertFalse(rl.check('E', "vpn", "se cayó", 2_000).keep)
        val b = rl.check('E', "vpn", "se cayó", 61_000)
        assertTrue(b.keep)
        assertEquals("1 escrito + 2 suprimidos + este", 3, b.count)
    }

    @Test
    fun limiter_mensajesDistintosONivelesDistintosNoSeAgrupan() {
        val rl = DiagRateLimiter()
        assertTrue(rl.check('E', "vpn", "uno", 0).keep)
        assertTrue(rl.check('E', "vpn", "dos", 1).keep)
        assertTrue(rl.check('W', "vpn", "uno", 2).keep)
        assertTrue(rl.check('E', "a11y", "uno", 3).keep)
    }

    @Test
    fun limiter_topePorVentana_ydejaConstanciaDeLoDescartado() {
        val rl = DiagRateLimiter(maxPerWindow = 3, maxImportantPerWindow = 10, windowMs = 60_000, dedupeMs = 60_000)
        val kept = (0 until 5).map { rl.check('I', "t", "m$it", it.toLong()) }
        assertEquals(listOf(true, true, true, false, false), kept.map { it.keep })
        val next = rl.check('I', "t", "nuevo", 120_000)   // ventana nueva
        assertTrue(next.keep)
        assertEquals("avisa cuántos se descartaron", 2, next.droppedBefore)
        assertEquals("y después queda en cero", 0, rl.check('I', "t", "otro", 120_001).droppedBefore)
    }

    @Test
    fun limiter_losErroresTienenTopePropio() {
        val rl = DiagRateLimiter(maxPerWindow = 2, maxImportantPerWindow = 5, windowMs = 60_000, dedupeMs = 60_000)
        assertTrue(rl.check('I', "t", "a", 0).keep)
        assertTrue(rl.check('I', "t", "b", 1).keep)
        assertFalse("tercer informativo: tope", rl.check('I', "t", "c", 2).keep)
        for (i in 0 until 5) assertTrue("error $i", rl.check('E', "t", "e$i", 3L + i).keep)
        assertFalse("sexto error: tope propio", rl.check('E', "t", "e5", 20).keep)
    }

    @Test
    fun limiter_relojHaciaAtras_noQuedaBloqueado() {
        val rl = DiagRateLimiter(maxPerWindow = 1, windowMs = 60_000, dedupeMs = 60_000)
        assertTrue(rl.check('I', "t", "a", 1_000_000).keep)
        assertFalse(rl.check('I', "t", "b", 1_000_001).keep)
        // El reloj salta 20 minutos hacia atrás: tiene que reiniciar la ventana.
        assertTrue(rl.check('I', "t", "c", 1_000L).keep)
    }

    @Test
    fun limiter_mapaAcotado_seguiFuncionando() {
        val rl = DiagRateLimiter(maxPerWindow = 10_000, windowMs = 60_000, dedupeMs = 60_000, maxKeys = 4)
        for (i in 0 until 50) assertTrue(rl.check('I', "t", "distinto$i", i.toLong()).keep)
        // El más viejo ya se descartó del mapa: vuelve a escribirse como nuevo (count 1).
        val v = rl.check('I', "t", "distinto0", 100)
        assertTrue(v.keep); assertEquals(1, v.count)
    }
}
