package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6/11/2026 (B.110) — el armado de UN evento a partir de las líneas de `printStackTrace()`.
 * Es lo que permite ver las excepciones que el código se traga sin tocar ningún catch.
 */
class StackTraceGrouperTest {

    private fun run(vararg lines: String): List<String> {
        val out = ArrayList<String>()
        val g = StackTraceGrouper { out.add(it) }
        lines.forEach { g.feed(it) }
        g.flush()
        return out
    }

    @Test
    fun unaExcepcionConSusFrames_sale_como_un_solo_evento() {
        val r = run(
            "java.lang.IllegalStateException: boom",
            "\tat com.ejemplo.locksuite.A.b(A.kt:10)",
            "\tat com.ejemplo.locksuite.C.d(C.kt:20)"
        )
        assertEquals(1, r.size)
        assertEquals(3, r[0].lines().size)
        assertTrue(r[0], r[0].startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(r[0], r[0].contains("at com.ejemplo.locksuite.A.b(A.kt:10)"))
    }

    @Test
    fun conCausas_losFramesDeCadaCausaVanCon_su_causa() {
        val r = run(
            "java.lang.RuntimeException: externo",
            "\tat a.B.c(B.kt:1)",
            "Caused by: java.io.IOException: interno",
            "\tat d.E.f(E.kt:2)",
            "\tat d.E.g(E.kt:3)",
            "\tat d.E.h(E.kt:4)",
            "\t... 5 more"
        )
        assertEquals(1, r.size)
        val t = r[0]
        assertTrue(t, t.contains("Caused by: java.io.IOException: interno"))
        // De la causa solo se guardan 2 frames.
        assertTrue(t, t.contains("d.E.f") && t.contains("d.E.g") && !t.contains("d.E.h"))
        assertFalse(t, t.contains("5 more"))
    }

    @Test
    fun masDeSeisFrames_seResumen() {
        val lines = ArrayList<String>()
        lines.add("java.lang.Error: x")
        for (i in 1..20) lines.add("\tat p.Q.m$i(Q.kt:$i)")
        val r = run(*lines.toTypedArray())
        assertTrue(r[0], r[0].contains("… +14 frames"))
        assertEquals(1 + 6 + 1, r[0].lines().size)
    }

    @Test
    fun lineasQueNoSonDeExcepcion_noGeneranNada() {
        assertTrue(run("hola mundo", "otra cosa: 123", "").isEmpty())
        assertTrue(run("Excepcion: no tiene paquete").isEmpty())
    }

    @Test
    fun unaExcepcionNuevaCierraLaAnterior() {
        val r = run(
            "java.lang.IllegalStateException: uno",
            "\tat a.B.c(B.kt:1)",
            "java.io.IOException: dos",
            "\tat d.E.f(E.kt:2)"
        )
        assertEquals(2, r.size)
        assertTrue(r[0].startsWith("java.lang.IllegalStateException: uno"))
        assertTrue(r[1].startsWith("java.io.IOException: dos"))
    }

    @Test
    fun unaLineaComunCortaElBloque() {
        val r = run(
            "java.lang.IllegalStateException: uno",
            "\tat a.B.c(B.kt:1)",
            "mensaje cualquiera",
            "\tat esto.NoPertenece(X.kt:9)"
        )
        assertEquals(1, r.size)
        assertFalse(r[0], r[0].contains("NoPertenece"))
    }

    @Test
    fun bloqueAbierto_seVaciaConFlush() {
        val out = ArrayList<String>()
        val g = StackTraceGrouper { out.add(it) }
        g.feed("java.lang.Error: x")
        g.feed("\tat a.B.c(B.kt:1)")
        assertTrue(g.hasOpenBlock)
        assertTrue(out.isEmpty())
        g.flush()
        assertFalse(g.hasOpenBlock)
        assertEquals(1, out.size)
        g.flush()
        assertEquals("un flush sin bloque no emite nada", 1, out.size)
    }

    @Test
    fun clasesInternasConDolar_yErrorConPaqueteLargo() {
        val r = run("kotlinx.coroutines.JobCancellationException: Job was cancelled", "\tat a.B\$c.d(B.kt:1)")
        assertEquals(1, r.size)
    }
}
