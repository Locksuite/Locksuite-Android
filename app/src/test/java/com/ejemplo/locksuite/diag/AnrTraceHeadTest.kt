package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19/11/2026 (B.125, T2.1) — qué se guarda de la traza de un ANR. */
class AnrTraceHeadTest {

    private val conCabecera = """
Subject: Broadcast of Intent { act=android.intent.action.MY_PACKAGE_REPLACED flg=0x1000010 cmp=com.ejemplo.locksuite/.receiver.BootReceiver }

----- pid 23840 at 2026-11-13 16:30:07 -----
Cmd line: com.ejemplo.locksuite
"main" prio=5 tid=1 Native
  | group="main" sCount=1 dsCount=0 flags=1 obj=0x72c25198 self=0xb400007aa04f7380
  at android.os.MessageQueue.nativePollOnce(Native method)
  at android.os.Looper.loop(Looper.java:183)

"LockSuiteReconcile" prio=5 tid=21 Waiting
  at java.lang.Object.wait(Native method)
  at com.ejemplo.locksuite.mdm.PolicyManager.reapplyAllRestrictions(PolicyManager.kt:2563)
  at com.ejemplo.locksuite.util.ReconcileScheduler.runPass(ReconcileScheduler.kt:99)

"Binder:23840_1" prio=5 tid=5 Native
  at android.os.Binder.execTransact(Binder.java:1021)

----- end 23840 -----
""".trimIndent().lines()

    @Test fun poneElMotivoPrimero() {
        val r = AnrTraceHead.extract(conCabecera).lines()
        assertTrue(r.first().startsWith("Subject: Broadcast of Intent"))
        assertTrue(r.first().contains("MY_PACKAGE_REPLACED"))
    }

    @Test fun incluyeElHiloPrincipal() {
        val r = AnrTraceHead.extract(conCabecera)
        assertTrue(r.contains("\"main\" prio=5 tid=1 Native"))
        assertTrue(r.contains("nativePollOnce"))
    }

    @Test fun sumaElHiloDeTrabajoQueTieneMarcosDeLockSuiteYDescartaLosDemas() {
        val r = AnrTraceHead.extract(conCabecera)
        assertTrue(r.contains("LockSuiteReconcile"))
        assertTrue(r.contains("reapplyAllRestrictions"))
        assertFalse(r.contains("Binder:23840_1"))
    }

    @Test fun noSeSaleDelBloqueDelHiloPrincipal() {
        val r = AnrTraceHead.extract(conCabecera)
        // El hilo principal termina antes del siguiente "…"; lo de después no se mezcla.
        val mainPart = r.substringAfter("\"main\"").substringBefore("\"LockSuiteReconcile\"")
        assertFalse(mainPart.contains("Waiting"))
    }

    @Test fun sinSubjectUsaLasPrimerasLineasAntesDelPrimerPid() {
        val lineas = listOf(
            "Motivo raro del sistema",
            "",
            "otra linea",
            "tercera",
            "cuarta que no entra",
            "----- pid 1 at x -----",
            "\"main\" prio=5 tid=1 Native",
            "  at algo"
        )
        val r = AnrTraceHead.extract(lineas).lines()
        assertEquals("Motivo raro del sistema", r[0])
        assertEquals("otra linea", r[1])
        assertEquals("tercera", r[2])
        assertFalse(r.contains("cuarta que no entra"))
    }

    @Test fun sinHiloMainDevuelveLasPrimerasLineas() {
        val lineas = (1..40).map { "linea $it" }
        val r = AnrTraceHead.extract(lineas).lines()
        assertTrue(r.contains("linea 1"))
        assertTrue(r.contains("linea 18"))
        assertFalse(r.contains("linea 19"))
    }

    @Test fun vacioDaVacio() = assertEquals("", AnrTraceHead.extract(emptyList()))

    @Test fun respetaElTopeDeCaracteres() {
        val larga = listOf("Subject: " + "x".repeat(500), "\"main\" prio=5") +
            (1..200).map { "  at com.ejemplo.locksuite.Cosa$it.metodo(Archivo.kt:$it)" }
        val r = AnrTraceHead.extract(larga)
        assertTrue(r.length <= AnrTraceHead.MAX_CHARS)
        // La cabecera se corta por línea, no se pierde.
        assertTrue(r.lines().first().length <= 240)
    }
}
