package com.ejemplo.locksuite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7/11/2026 (B.112) — qué excepciones no atrapadas se pueden anotar sin matar el proceso. */
class CrashNetPolicyTest {

    private fun swallow(main: Boolean = false, looper: Boolean = false, vm: Boolean = false, n: Int = 0) =
        CrashNetPolicy.shouldSwallow(main, looper, vm, n)

    @Test fun hiloDeTrabajoSinLooperSeAtrapa() = assertTrue(swallow())

    @Test fun hiloPrincipalNunca() = assertFalse(swallow(main = true))

    @Test fun hiloConLooperNunca() {
        // Si se tragara, el HandlerThread muere igual y su cola queda muerta en silencio
        // (por ejemplo el ConnectivityThread que avisa los cambios de red a la VPN).
        assertFalse(swallow(looper = true))
    }

    @Test fun errorDeLaMaquinaVirtualNunca() = assertFalse(swallow(vm = true))

    @Test fun rafagaDejaMorirAlProceso() {
        assertTrue(swallow(n = CrashNetPolicy.MAX_BURST - 1))
        assertFalse(swallow(n = CrashNetPolicy.MAX_BURST))
    }

    @Test fun clasificaErroresDeLaMaquinaVirtual() {
        assertTrue(CrashNetPolicy.isFatalVmError(OutOfMemoryError()))
        assertTrue(CrashNetPolicy.isFatalVmError(InternalError()))
        // La pila ya se desarmó: el hilo de trabajo queda sano.
        assertFalse(CrashNetPolicy.isFatalVmError(StackOverflowError()))
        assertFalse(CrashNetPolicy.isFatalVmError(IllegalStateException()))
        assertFalse(CrashNetPolicy.isFatalVmError(NullPointerException()))
    }

    @Test fun ventanaDeDiezMinutos() {
        val now = 100 * 60_000L
        val marcas = listOf(now - 11 * 60_000L, now - 9 * 60_000L, now - 1_000L, now)
        assertEquals(3, CrashNetPolicy.countInWindow(marcas, now))
        // Marcas "del futuro" (no debería haberlas) no cuentan.
        assertEquals(0, CrashNetPolicy.countInWindow(listOf(now + 5_000L), now))
    }
}
