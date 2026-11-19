package com.ejemplo.locksuite.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 19/11/2026 (B.125, T2.5) — el cronómetro por pasos de reapplyAllRestrictions(). */
class StepTimerTest {

    @Test fun ordenaDeMasLentoAMasRapidoYOmiteLoMenorA100ms() {
        var t = 0L
        val timer = StepTimer { t }
        t += 4_100; timer.mark("lista blanca")
        t += 50; timer.mark("permisos")
        t += 18_200; timer.mark("recorrido de apps")
        assertEquals("recorrido de apps 18200 ms, lista blanca 4100 ms", timer.summary())
        assertEquals(22_350L, timer.totalMs())
    }

    @Test fun sinPasosLentosElResumenQuedaVacio() {
        var t = 0L
        val timer = StepTimer { t }
        t += 10; timer.mark("a")
        assertEquals("", timer.summary())
    }
}
