package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6/11/2026 (B.110) — traducción de los cierres del proceso y lectura del logcat propio.
 */
class DiagExitsAndLogcatTest {

    // ── cierres del proceso ─────────────────────────────────────────────────────────

    private fun exit(at: Long, reason: Int, desc: String? = null, trace: String? = null) =
        DiagExits.Record(at, reason, 400, 0, 120_000, 180_000, desc, trace)

    @Test
    fun nombresYGravedad() {
        assertEquals("LOW_MEMORY", DiagExits.reasonName(3))
        assertEquals("ANR", DiagExits.reasonName(6))
        assertEquals("REASON_99", DiagExits.reasonName(99))
        assertEquals('E', DiagExits.levelFor(4))   // CRASH
        assertEquals('E', DiagExits.levelFor(6))   // ANR
        assertEquals('W', DiagExits.levelFor(3))   // LOW_MEMORY
        assertEquals('W', DiagExits.levelFor(14))  // FREEZER
        assertEquals('I', DiagExits.levelFor(10))  // USER_REQUESTED: no es una falla
        assertEquals('I', DiagExits.levelFor(1))   // EXIT_SELF
    }

    @Test
    fun describe_traeElNombreExacto_lasMemoriasYSanitiza() {
        val r = DiagExits.describe(exit(1, 3, desc = "killed by lmkd para luis@example.com"))
        assertTrue(r, r.contains("LOW_MEMORY"))
        assertTrue(r, r.contains("falta de memoria"))
        assertTrue(r, r.contains("PSS 117 MB"))
        assertFalse(r, r.contains("luis@example.com"))
        assertTrue(r, r.contains("importancia: en caché"))
    }

    @Test
    fun describe_conTrazaLaAgregaEnLineasAparte() {
        val r = DiagExits.describe(exit(1, 6, trace = "\"main\" prio=5 tid=1 Blocked\n  at com.ejemplo.X.y(X.kt:1)"))
        assertTrue(r, r.contains("ANR"))
        assertTrue(r, r.lines().size >= 3)
    }

    @Test
    fun newerThan_filtraYOrdena() {
        val l = listOf(exit(300, 3), exit(100, 4), exit(200, 6))
        assertEquals(listOf(200L, 300L), DiagExits.newerThan(l, 100).map { it.at })
        assertTrue(DiagExits.newerThan(l, 300).isEmpty())
    }

    @Test
    fun summary_cuentaPorMotivo() {
        val l = listOf(exit(1, 3), exit(2, 3), exit(3, 6), exit(4, 10))
        assertEquals("LOW_MEMORY ×2, ANR ×1, USER_REQUESTED ×1", DiagExits.summary(l))
        assertEquals("", DiagExits.summary(emptyList()))
    }

    // ── logcat ──────────────────────────────────────────────────────────────────────

    private val raw = listOf(
        "--------- beginning of main",
        "11-06 00:12:33.123  1234  1250 E AndroidRuntime: FATAL EXCEPTION: main",
        "11-06 00:12:33.124  1234  1250 E AndroidRuntime: java.lang.IllegalStateException: boom",
        "11-06 00:12:33.125  1234  1250 E AndroidRuntime: \tat com.ejemplo.locksuite.A.b(A.kt:1)",
        "11-06 00:12:40.000  1234  1260 W System.err: java.io.IOException: sin red",
        "11-06 00:12:41.000  1234  1260 I LockSuiteVpn: túnel arriba",
        "línea suelta que no es de logcat"
    )

    @Test
    fun parse_ignoraLoQueNoEsDeLogcat() {
        val e = DiagLogcatParser.parse(raw)
        assertEquals(5, e.size)
        assertEquals("AndroidRuntime", e[0].tag)
        assertEquals('E', e[0].level)
        assertEquals("System.err", e[3].tag)
        assertEquals("túnel arriba", e[4].msg)
    }

    @Test
    fun newerThan_conCursor_sinCursor_yRelojHaciaAtras() {
        val e = DiagLogcatParser.parse(raw)
        assertEquals(5, DiagLogcatParser.newerThan(e, null).size)
        assertEquals(2, DiagLogcatParser.newerThan(e, "11-06 00:12:33.125").size)
        assertEquals("ya vistos todos", 0, DiagLogcatParser.newerThan(e, "11-06 00:12:41.000").size)
        // El cursor es MÁS NUEVO que todo el logcat (cambio de año / reloj corregido): todo es nuevo.
        assertEquals(5, DiagLogcatParser.newerThan(e, "12-31 23:59:59.999").size)
        assertEquals("11-06 00:12:41.000", DiagLogcatParser.newestStamp(e))
        assertNull(DiagLogcatParser.newestStamp(emptyList()))
    }

    @Test
    fun bursts_juntaLaTrazaYFiltraPorNivel() {
        val e = DiagLogcatParser.parse(raw)
        val b = DiagLogcatParser.bursts(e, 'E')
        assertEquals(1, b.size)
        assertEquals("AndroidRuntime", b[0].tag)
        assertEquals(3, b[0].text.lines().size)
        val w = DiagLogcatParser.bursts(e, 'W')
        assertEquals(2, w.size)
        assertEquals("System.err", w[1].tag)
        assertEquals(3, DiagLogcatParser.bursts(e, 'I').size)
    }

    @Test
    fun bursts_noMezclaEtiquetasDistintas() {
        val e = DiagLogcatParser.parse(listOf(
            "11-06 00:00:01.000  1  1 E A: uno",
            "11-06 00:00:01.100  1  1 E B: dos",
            "11-06 00:00:01.200  1  1 E A: tres"
        ))
        assertEquals(3, DiagLogcatParser.bursts(e, 'E').size)
    }

    @Test
    fun tail_respetaLimitesYAvisaSiCorta() {
        val e = DiagLogcatParser.parse((1..50).map { "11-06 00:00:%02d.000  1  1 W T: mensaje número $it".format(it) })
        val t = DiagLogcatParser.tail(e, 10, 10_000)
        assertTrue(t, t.startsWith("… (hay 40 líneas más viejas)"))
        assertTrue(t, t.endsWith("mensaje número 50"))
        assertEquals(11, t.lines().size)
        val all = DiagLogcatParser.tail(e, 100, 100_000)
        assertFalse(all, all.startsWith("…"))
        assertEquals(50, all.lines().size)
    }

    @Test
    fun stampToEpoch_usaElAnioActual_yElAnteriorSiQuedaEnElFuturo() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val ahora = java.time.ZonedDateTime.of(2026, 11, 6, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val esperado = java.time.ZonedDateTime.of(2026, 11, 6, 11, 59, 30, 250_000_000, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(esperado, DiagLogcatParser.stampToEpoch("11-06 11:59:30.250", ahora, utc))
        // Un renglón de fin de diciembre leído en enero es del año pasado.
        val enero = java.time.ZonedDateTime.of(2027, 1, 2, 9, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val diciembre = java.time.ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(diciembre, DiagLogcatParser.stampToEpoch("12-31 23:59:00.000", enero, utc))
        assertNull(DiagLogcatParser.stampToEpoch("basura", ahora, utc))
    }

    @Test
    fun formatLine_sanitiza() {
        val e = DiagLogcatParser.parse(listOf("11-06 00:00:01.000  1  1 E T: falló para ana@example.com"))
        val l = DiagLogcatParser.formatLine(e[0])
        assertFalse(l, l.contains("ana@example.com"))
        assertTrue(l, l.startsWith("11-06 00:00:01.000 E/T:"))
    }
}
