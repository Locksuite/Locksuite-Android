package com.ejemplo.locksuite.util

import com.ejemplo.locksuite.util.ReconcilePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19/11/2026 (B.125, T1) — cuándo se corre una pasada completa de políticas. */
class ReconcilePolicyTest {

    @Test fun primerPedidoDeArranqueCorre() {
        val g = ReconcileGate()
        assertEquals(Decision.RUN, g.request(startup = true, now = 1_000))
    }

    @Test fun dosPedidosDeArranqueJuntosHacenUnaPasada() {
        val g = ReconcileGate()
        assertEquals(Decision.RUN, g.request(true, 1_000))
        // Mientras corre: cubierto.
        assertEquals(Decision.COVERED, g.request(true, 1_500))
        // Aunque ya terminó, si empezó hace menos de 30 s sigue cubierto.
        assertFalse(g.finished(5_000))
        assertEquals(Decision.COVERED, g.request(true, 6_000))
    }

    @Test fun pedidoDeArranqueDiezSegundosDespuesDeTerminarEstaCubierto() {
        val g = ReconcileGate()
        g.request(true, 0L + 1)
        g.finished(20_000)
        assertEquals(Decision.COVERED, g.request(true, 1 + 25_000))
    }

    @Test fun pedidoDeArranqueCuarentaSegundosDespuesCorreDeNuevo() {
        val g = ReconcileGate()
        g.request(true, 1_000)
        g.finished(20_000)
        assertEquals(Decision.RUN, g.request(true, 1_000 + 40_000))
    }

    @Test fun pedidoQueNoEsDeArranqueDuranteUnaPasadaLaEncola() {
        val g = ReconcileGate()
        assertEquals(Decision.RUN, g.request(true, 1_000))
        assertEquals(Decision.QUEUE, g.request(false, 2_000))
        // Al terminar hay que hacer UNA más, y la compuerta sigue ocupada.
        assertTrue(g.finished(10_000))
        assertTrue(g.isRunning())
        assertFalse(g.finished(20_000))
        assertFalse(g.isRunning())
    }

    @Test fun cincoPedidosQueNoSonDeArranqueDuranteUnaPasadaHacenDosPasadasEnTotal() {
        val g = ReconcileGate()
        var pasadas = 0
        if (g.request(false, 1_000) == Decision.RUN) pasadas++
        repeat(5) { assertEquals(Decision.QUEUE, g.request(false, 2_000L + it)) }
        while (g.finished(50_000)) pasadas++
        // Total: la primera + UNA más, sin importar cuántos pedidos se juntaron.
        assertEquals(2, pasadas)
        assertFalse(g.isRunning())
    }

    @Test fun pedidoQueNoEsDeArranqueSinPasadaEnCursoCorreAunqueHayaEmpezadoHacePoco() {
        val g = ReconcileGate()
        g.request(true, 1_000)
        g.finished(3_000)
        assertEquals(Decision.RUN, g.request(false, 4_000))
    }

    @Test fun arranqueDuranteUnaPasadaQueNoEraDeArranqueNoLaPisa() {
        val g = ReconcileGate()
        assertEquals(Decision.RUN, g.request(false, 1_000))
        assertEquals(Decision.COVERED, g.request(true, 1_100))
        // Y no deja nada pendiente: una sola pasada.
        assertFalse(g.finished(9_000))
    }

    @Test fun decideEsPuraYCoincideConLaCompuerta() {
        assertEquals(Decision.RUN, ReconcilePolicy.decide(true, false, 0L, 100L))
        assertEquals(Decision.COVERED, ReconcilePolicy.decide(true, true, 0L, 100L))
        assertEquals(Decision.QUEUE, ReconcilePolicy.decide(false, true, 0L, 100L))
        assertEquals(Decision.COVERED, ReconcilePolicy.decide(true, false, 1_000L, 1_000L + ReconcilePolicy.STARTUP_COVER_MS))
        assertEquals(Decision.RUN, ReconcilePolicy.decide(true, false, 1_000L, 1_001L + ReconcilePolicy.STARTUP_COVER_MS))
    }
}
