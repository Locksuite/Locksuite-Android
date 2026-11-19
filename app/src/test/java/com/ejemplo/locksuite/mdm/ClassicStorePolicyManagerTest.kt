package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicStorePolicyManagerTest {

    @Test
    fun adoptaYBloquearPaquetesNuevosYSueltaAlQuitar() {
        val p1 = ClassicStorePolicyManager.planBlockOwnership(
            desired = setOf("com.a", "com.b"),
            currentlyBlocked = emptySet(),
            owned = emptySet()
        )
        assertEquals(setOf("com.a", "com.b"), p1.toBlock)
        assertTrue(p1.toUnblock.isEmpty())
        assertEquals(setOf("com.a", "com.b"), p1.ownedAfter)

        val p2 = ClassicStorePolicyManager.planBlockOwnership(
            desired = setOf("com.b"),
            currentlyBlocked = setOf("com.a", "com.b"),
            owned = p1.ownedAfter
        )
        assertTrue(p2.toBlock.isEmpty())
        assertEquals(setOf("com.a"), p2.toUnblock)
        assertEquals(setOf("com.b"), p2.ownedAfter)
    }

    @Test
    fun noDesbloqueaBloqueoManualPrevio() {
        val p1 = ClassicStorePolicyManager.planBlockOwnership(
            desired = setOf("com.manual", "com.tienda"),
            currentlyBlocked = setOf("com.manual"),
            owned = emptySet()
        )
        assertEquals(setOf("com.tienda"), p1.toBlock)
        assertEquals(setOf("com.tienda"), p1.ownedAfter)

        val p2 = ClassicStorePolicyManager.planBlockOwnership(
            desired = emptySet(),
            currentlyBlocked = setOf("com.manual", "com.tienda"),
            owned = p1.ownedAfter
        )
        assertEquals(setOf("com.tienda"), p2.toUnblock)
        assertTrue(p2.ownedAfter.isEmpty())
    }

    @Test
    fun noDesbloqueaPaqueteProtegidoPorTiendaPlay() {
        val p = ClassicStorePolicyManager.planBlockOwnership(
            desired = emptySet(),
            currentlyBlocked = setOf("com.waze"),
            owned = setOf("com.waze"),
            protectedKeepBlocked = setOf("com.waze")
        )
        assertTrue(p.toUnblock.isEmpty())
        assertTrue(p.ownedAfter.isEmpty())
    }

    @Test
    fun idempotenteCuandoEstadoYaCoincide() {
        val p = ClassicStorePolicyManager.planBlockOwnership(
            desired = setOf("com.a"),
            currentlyBlocked = setOf("com.a"),
            owned = setOf("com.a")
        )
        assertTrue(p.toBlock.isEmpty())
        assertTrue(p.toUnblock.isEmpty())
        assertEquals(setOf("com.a"), p.ownedAfter)
    }
}
