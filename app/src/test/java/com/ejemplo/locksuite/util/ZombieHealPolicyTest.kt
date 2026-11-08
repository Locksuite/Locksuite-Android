package com.ejemplo.locksuite.util

import com.ejemplo.locksuite.util.ZombieHealPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7/11/2026 (B.112) — cuándo y cómo se repara sola la accesibilidad «zombi». */
class ZombieHealPolicyTest {

    private val min = 60_000L
    private val hora = 60 * min
    private val wall = 1_800_000_000_000L // una hora de pared cualquiera

    private fun input(
        zombieSince: Long? = 10 * min,
        now: Long = 20 * min,
        nowWall: Long = wall,
        attempts: List<Long> = emptyList(),
        canWrite: Boolean = false,
        lastToggle: Long? = null,
        blocked: String? = null,
    ) = ZombieHealPolicy.Input(zombieSince, now, nowWall, attempts, canWrite, lastToggle, blocked)

    private fun action(i: ZombieHealPolicy.Input) = ZombieHealPolicy.decide(i).action

    // ── cuándo NO hacer nada ─────────────────────────────────────────────────────────────────

    @Test fun sinZombiNoHaceNada() = assertEquals(Action.NONE, action(input(zombieSince = null)))

    @Test fun bloqueadoNoHaceNada() =
        assertEquals(Action.NONE, action(input(blocked = "LockSuite suspendido")))

    @Test fun equipoRecienEncendidoNoHaceNada() {
        // Al arrancar, Android tarda en enlazar: eso no es un zombi.
        assertEquals(Action.NONE, action(input(zombieSince = 0L, now = 3 * min + 59_000L)))
        assertEquals(Action.REINSTALL, action(input(zombieSince = 0L, now = 4 * min)))
    }

    @Test fun zombiRecienteEsperaTresMinutos() {
        assertEquals(Action.NONE, action(input(zombieSince = 10 * min, now = 12 * min + 59_000L)))
        assertEquals(Action.REINSTALL, action(input(zombieSince = 10 * min, now = 13 * min)))
    }

    // ── camino liviano: ciclo del ajuste ─────────────────────────────────────────────────────

    @Test fun conPermisoPrimeroCicloDelAjuste() =
        assertEquals(Action.TOGGLE_SETTING, action(input(canWrite = true)))

    @Test fun despuesDelCicloEsperaAntesDeReinstalar() {
        val toggle = 20 * min
        assertEquals(Action.NONE, action(input(canWrite = true, lastToggle = toggle, now = toggle + 89_000L)))
        assertEquals(Action.REINSTALL, action(input(canWrite = true, lastToggle = toggle, now = toggle + 90_000L)))
    }

    @Test fun unCicloDeOtroEpisodioNoCuenta() {
        // El ciclo anterior fue antes de que empezara ESTE zombi: corresponde otro ciclo.
        assertEquals(
            Action.TOGGLE_SETTING,
            action(input(canWrite = true, zombieSince = 10 * min, lastToggle = 5 * min))
        )
    }

    @Test fun sinPermisoVaDirectoAReinstalar() =
        assertEquals(Action.REINSTALL, action(input(canWrite = false)))

    // ── límites de la reinstalación (lo que impide un bucle) ─────────────────────────────────

    @Test fun topeDeTresPorDia() {
        val tres = listOf(wall - 20 * hora, wall - 10 * hora, wall - 2 * hora)
        assertEquals(Action.NONE, action(input(attempts = tres)))
        val dosYUnaVieja = listOf(wall - 30 * hora, wall - 10 * hora, wall - 2 * hora)
        assertEquals(Action.REINSTALL, action(input(attempts = dosYUnaVieja)))
    }

    @Test fun unaCadaTreintaMinutos() {
        assertEquals(Action.NONE, action(input(attempts = listOf(wall - 29 * min))))
        assertEquals(Action.REINSTALL, action(input(attempts = listOf(wall - 30 * min))))
    }

    @Test fun relojAtrasadoNoHabilitaMasIntentos() {
        // Intento anotado "en el futuro" (el reloj se atrasó): cuenta como reciente.
        assertEquals(Action.NONE, action(input(attempts = listOf(wall + 2 * hora))))
        val tresFuturos = listOf(wall + 1 * hora, wall + 2 * hora, wall + 3 * hora)
        assertEquals(Action.NONE, action(input(attempts = tresFuturos)))
    }

    @Test fun intentoMasDeUnDiaEnElFuturoSeDescarta() {
        // Un reloj que saltó meses atrás no puede apagar la reparación por meses.
        assertEquals(Action.REINSTALL, action(input(attempts = listOf(wall + 400 * 24 * hora))))
    }

    @Test fun laRazonSiempreExplicaLaDecision() {
        val d = ZombieHealPolicy.decide(input(attempts = listOf(wall - 5 * min)))
        assertEquals(Action.NONE, d.action)
        assertTrue(d.why, d.why.contains("30 min"))
    }

    // ── historial persistido ─────────────────────────────────────────────────────────────────

    @Test fun historialToleraBasuraYGuardaLosUltimosDiez() {
        assertEquals(emptyList<Long>(), ZombieHealPolicy.parseHistory(null))
        assertEquals(listOf(1L, 3L), ZombieHealPolicy.parseHistory("1,x,,3"))
        var raw: String? = null
        for (t in 1L..15L) raw = ZombieHealPolicy.appendHistory(raw, t)
        assertEquals((6L..15L).toList(), ZombieHealPolicy.parseHistory(raw))
    }

    // ── ENABLED_ACCESSIBILITY_SERVICES ───────────────────────────────────────────────────────

    private val pkg = "com.ejemplo.locksuite"
    private val cls = "com.ejemplo.locksuite.service.LockSuiteAccessibilityService"

    @Test fun reconoceFormaLargaYCorta() {
        assertTrue(ZombieHealPolicy.isComponent("$pkg/$cls", pkg, cls))
        assertTrue(ZombieHealPolicy.isComponent("$pkg/.service.LockSuiteAccessibilityService", pkg, cls))
        assertFalse(ZombieHealPolicy.isComponent("com.otro/$cls", pkg, cls))
        assertFalse(ZombieHealPolicy.isComponent("$pkg/.service.Otro", pkg, cls))
        assertFalse(ZombieHealPolicy.isComponent("basura", pkg, cls))
    }

    @Test fun sacarNuestroComponenteRespetaLosDemas() {
        val otros = "com.a/com.a.Svc:com.b/.Lector"
        val todo = "com.a/com.a.Svc:$pkg/.service.LockSuiteAccessibilityService:com.b/.Lector"
        assertEquals(otros, ZombieHealPolicy.withoutComponent(todo, pkg, cls))
        assertEquals("", ZombieHealPolicy.withoutComponent("$pkg/$cls", pkg, cls))
        assertEquals("", ZombieHealPolicy.withoutComponent(null, pkg, cls))
    }

    @Test fun ponerNuestroComponenteNoLoDuplica() {
        assertEquals("$pkg/$cls", ZombieHealPolicy.withComponent(null, pkg, cls))
        assertEquals("com.a/.X:$pkg/$cls", ZombieHealPolicy.withComponent("com.a/.X", pkg, cls))
        val yaEsta = "$pkg/.service.LockSuiteAccessibilityService:com.a/.X"
        assertEquals(yaEsta, ZombieHealPolicy.withComponent(yaEsta, pkg, cls))
        assertTrue(ZombieHealPolicy.containsComponent(yaEsta, pkg, cls))
    }

    @Test fun cicloCompletoDejaLaListaComoEstaba() {
        val original = "com.a/.X:$pkg/$cls"
        val apagado = ZombieHealPolicy.withoutComponent(original, pkg, cls)
        assertFalse(ZombieHealPolicy.containsComponent(apagado, pkg, cls))
        val prendido = ZombieHealPolicy.withComponent(apagado, pkg, cls)
        assertEquals(original, prendido)
    }
}
