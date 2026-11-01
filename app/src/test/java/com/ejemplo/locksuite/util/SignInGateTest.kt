package com.ejemplo.locksuite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1/11/2026 (B.104) — la sesión anónima de Firebase se abre una sola vez aunque la pidan varios.
 *
 * Contexto: al arrancar un celular nuevo, el latido, la sincronización completa, el token de FCM
 * y la publicación del PIN pedían sesión a la vez y cada uno abría la suya. El "usuario actual"
 * cambiaba de uid en el medio y las reglas, que atan el nodo del equipo al uid que lo creó,
 * rechazaban lo que viniera después.
 */
class SignInGateTest {

    /** Entorno falso: sesión abierta o no, y el aviso de cada intento queda guardado para contestarlo a mano. */
    private class Fixture(staleAfterMs: Long = SignInGate.DEFAULT_STALE_MS) {
        var signedIn = false
        var clock = 1_000L
        val pending = ArrayList<(Boolean) -> Unit>()
        var starts = 0
        val errors = ArrayList<Exception>()
        val log = ArrayList<String>()
        val gate = SignInGate(
            isSignedIn = { signedIn },
            startSignIn = { done -> starts++; pending.add(done) },
            now = { clock },
            staleAfterMs = staleAfterMs,
            onError = { errors.add(it) }
        )

        /** Contesta el intento número [i] (0 = el primero). */
        fun answer(i: Int, ok: Boolean) {
            if (ok) signedIn = true
            pending[i](ok)
        }
    }

    @Test
    fun conSesionCorreAlInstanteSinAbrirOtra() {
        val f = Fixture().apply { signedIn = true }
        f.gate.run { f.log.add("a") }
        assertEquals(listOf("a"), f.log)
        assertEquals(0, f.starts)
    }

    @Test
    fun variosPedidosAbrenUnaSolaSesionYCorrenEnOrden() {
        val f = Fixture()
        f.gate.run { f.log.add("latido") }
        f.gate.run { f.log.add("sync") }
        f.gate.run { f.log.add("pin") }
        assertEquals("una sola sesión abierta", 1, f.starts)
        assertTrue("nada corre hasta que conteste", f.log.isEmpty())
        f.answer(0, true)
        assertEquals(listOf("latido", "sync", "pin"), f.log)
    }

    @Test
    fun siFallaIgualCorrenTodasComoAntes() {
        val f = Fixture()
        f.gate.run { f.log.add("a") }
        f.gate.run { f.log.add("b") }
        f.answer(0, false)
        assertEquals(listOf("a", "b"), f.log)
    }

    @Test
    fun despuesDeUnFalloElSiguientePedidoVuelveAIntentar() {
        val f = Fixture()
        f.gate.run { f.log.add("a") }
        f.answer(0, false)
        f.gate.run { f.log.add("b") }
        assertEquals("el fallo no deja la puerta trabada", 2, f.starts)
        f.answer(1, true)
        assertEquals(listOf("a", "b"), f.log)
    }

    @Test
    fun despuesDeAbrirSesionLosPedidosCorrenDirecto() {
        val f = Fixture()
        f.gate.run { f.log.add("a") }
        f.answer(0, true)
        f.gate.run { f.log.add("b") }
        assertEquals(listOf("a", "b"), f.log)
        assertEquals(1, f.starts)
    }

    @Test
    fun unaAccionQueFallaNoFrenaALasDemas() {
        val f = Fixture()
        f.gate.run { f.log.add("a") }
        f.gate.run { throw IllegalStateException("se rompió") }
        f.gate.run { f.log.add("c") }
        f.answer(0, true)
        assertEquals(listOf("a", "c"), f.log)
        assertEquals(1, f.errors.size)
        assertEquals("se rompió", f.errors[0].message)
    }

    @Test
    fun siNoPuedeIniciarSesionLasAccionesCorrenYLaPuertaSeLibera() {
        var starts = 0
        val log = ArrayList<String>()
        val gate = SignInGate(
            isSignedIn = { false },
            startSignIn = { starts++; throw IllegalStateException("sin Play Services") }
        )
        gate.run { log.add("a") }
        assertEquals(listOf("a"), log)
        gate.run { log.add("b") }
        assertEquals("no queda colgada esperando un intento que nunca existió", 2, starts)
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun intentoColgadoSeReemplazaPasadoElPlazo() {
        val f = Fixture(staleAfterMs = 30_000L)
        f.gate.run { f.log.add("a") }
        f.clock += 29_999L
        f.gate.run { f.log.add("b") }
        assertEquals("todavía dentro del plazo: se espera", 1, f.starts)
        f.clock += 1L
        f.gate.run { f.log.add("c") }
        assertEquals("vencido: se abre otro intento", 2, f.starts)
        f.answer(1, true)
        assertEquals("el intento nuevo atiende a todos, una sola vez cada uno", listOf("a", "b", "c"), f.log)
        // El intento viejo contesta tarde: no vuelve a correr nada.
        f.pending[0](true)
        assertEquals(listOf("a", "b", "c"), f.log)
    }

    @Test
    fun falloTardioDeUnIntentoViejoNoTocaAlVigente() {
        val f = Fixture(staleAfterMs = 30_000L)
        f.gate.run { f.log.add("a") }
        f.clock += 30_000L
        f.gate.run { f.log.add("b") }
        assertEquals(2, f.starts)
        // El primero contesta con error cuando el segundo sigue en curso: se ignora.
        f.pending[0](false)
        assertTrue("el vigente sigue esperando", f.log.isEmpty())
        f.answer(1, true)
        assertEquals(listOf("a", "b"), f.log)
    }

    @Test
    fun exitoDeUnIntentoViejoTambienSirveATodos() {
        val f = Fixture(staleAfterMs = 30_000L)
        f.gate.run { f.log.add("a") }
        f.clock += 30_000L
        f.gate.run { f.log.add("b") }
        // El primero termina bien tarde: ya hay usuario, no hay motivo para seguir esperando al segundo.
        f.answer(0, true)
        assertEquals(listOf("a", "b"), f.log)
        f.pending[1](true)
        assertEquals("el segundo aviso no repite nada", listOf("a", "b"), f.log)
    }

    @Test
    fun unaAccionPuedeVolverAPedirSesionSinTrabarse() {
        val f = Fixture()
        f.gate.run {
            f.log.add("externa")
            f.gate.run { f.log.add("interna") }
        }
        f.answer(0, true)
        assertEquals(listOf("externa", "interna"), f.log)
    }

    @Test
    fun siLaSesionSeAbreEntreLaPreguntaYElEncoladoNoQuedaEsperando() {
        // Simula la carrera: la primera consulta dice "no hay sesión" y, para cuando se toma el
        // cerrojo, la sesión ya se abrió. La acción tiene que correr, no quedar encolada.
        var calls = 0
        val log = ArrayList<String>()
        val gate = SignInGate(
            isSignedIn = { calls++ > 0 },
            startSignIn = { throw AssertionError("no hacía falta abrir sesión") }
        )
        gate.run { log.add("a") }
        assertEquals(listOf("a"), log)
    }

    @Test
    fun relojAtrasadoNoDejaUnIntentoTrabado() {
        val f = Fixture(staleAfterMs = 30_000L)
        f.gate.run { f.log.add("a") }
        f.clock -= 10_000L
        f.gate.run { f.log.add("b") }
        assertEquals("hora hacia atrás: se considera vencido y se reintenta", 2, f.starts)
        f.answer(1, true)
        assertEquals(listOf("a", "b"), f.log)
    }
}
