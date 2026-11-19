package com.ejemplo.locksuite.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 19/11/2026 (B.125, T4) — una instalación a la vez y que sobreviva a quien la mira. */
class StoreInstallCoreTest {

    private fun core() = StoreInstallCore(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Test fun noDuplicaInstalaciones() {
        val c = core()
        val gate = CompletableDeferred<Unit>()
        val corridas = AtomicInteger(0)
        val terminado = CountDownLatch(1)
        assertTrue(c.start("com.fondos", { corridas.incrementAndGet(); gate.await(); null }, { terminado.countDown() }))
        // Mientras corre, otra (igual o distinta) se rechaza y no corre nada.
        assertFalse(c.start("com.fondos", { corridas.incrementAndGet(); null }, { }))
        assertFalse(c.start("com.otra", { corridas.incrementAndGet(); null }, { }))
        assertTrue(c.isBusy())
        gate.complete(Unit)
        assertTrue(terminado.await(5, TimeUnit.SECONDS))
        assertEquals(1, corridas.get())
    }

    @Test fun laInstalacionSigueAunqueSeCancelaQuienLaMira() {
        // «Quien la mira» es otro scope (la pantalla): cancelarlo no toca la instalación.
        val c = core()
        val pantalla = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        var resultado: String? = "sin terminar"
        val terminado = CountDownLatch(1)
        c.start("com.fondos", { gate.await(); null }, { resultado = it; terminado.countDown() })
        pantalla.cancel() // la pantalla se fue
        gate.complete(Unit)
        assertTrue(terminado.await(5, TimeUnit.SECONDS))
        assertNull("terminó bien, sin 'left the composition'", resultado)
    }

    @Test fun elProgresoSeLeeDesdeElEstadoYAlTerminarVuelveAVacio() {
        runBlocking {
            val c = core()
            val vioProgreso = CompletableDeferred<Int>()
            val seguir = CompletableDeferred<Unit>()
            val terminado = CountDownLatch(1)
            c.start("com.fondos", { report -> report(42); vioProgreso.complete(c.state.value.progress); seguir.await(); null }, { terminado.countDown() })
            assertEquals(42, withTimeout(5_000) { vioProgreso.await() })
            assertEquals("com.fondos", c.state.value.key)
            seguir.complete(Unit)
            assertTrue(terminado.await(5, TimeUnit.SECONDS))
            assertNull(c.state.value.key)
        }
    }

    @Test fun unaExcepcionSeConvierteEnTextoYLiberaLaCompuerta() {
        val c = core()
        var err: String? = null
        val terminado = CountDownLatch(1)
        c.start("a", { throw IllegalStateException("se cayó la red") }, { err = it; terminado.countDown() })
        assertTrue(terminado.await(5, TimeUnit.SECONDS))
        assertEquals("se cayó la red", err)
        // Y se puede volver a instalar.
        val otra = CountDownLatch(1)
        assertTrue(c.start("b", { null }, { otra.countDown() }))
        assertTrue(otra.await(5, TimeUnit.SECONDS))
    }

    @Test fun elProgresoSeAcotaA0_100() {
        runBlocking {
            val c = core()
            val visto = CompletableDeferred<Int>()
            val seguir = CompletableDeferred<Unit>()
            c.start("a", { report -> report(250); visto.complete(c.state.value.progress); seguir.await(); null }, { })
            assertEquals(100, withTimeout(5_000) { visto.await() })
            seguir.complete(Unit)
        }
    }
}
