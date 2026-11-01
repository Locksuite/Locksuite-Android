package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué modo de la Tienda Play fija cada perfil automático (B.101).
 *
 * Son las reglas textuales del dueño:
 *  - el Estricto abre el modo kosher de la tienda;
 *  - el Nivel 2 (Trabajo) abre la tienda en modo trabajo;
 *  - el período de gracia abre toda la tienda por ese tiempo y, al terminar, la deja en
 *    modo kosher (porque cierra aplicando el Estricto);
 *  - "Solo llamadas" cierra la tienda;
 *  - "Lite" la abre completa (nivel 3).
 *
 * No toca JSON ni Android: solo los datos de `EnrollmentProfiles.ALL`.
 */
class EnrollmentProfilesStoreModeTest {

    private fun modeOf(id: String): String? = EnrollmentProfiles.byId(id)?.storeMode

    @Test
    fun estricto_abreModoKosher() {
        assertEquals(PlayStoreCatalog.MODE_KOSHER, modeOf(EnrollmentProfiles.LEVEL_STRICT))
    }

    @Test
    fun trabajo_abreModoTrabajo() {
        assertEquals(PlayStoreCatalog.MODE_WORK, modeOf(EnrollmentProfiles.LEVEL_WORK))
    }

    @Test
    fun gracia_abreTodaLaTiendaMientrasDura() {
        assertEquals(PlayStoreCatalog.MODE_LITE, modeOf(EnrollmentProfiles.LEVEL_GRACE))
        // "Toda la tienda" tiene que ser de verdad la más abierta que existe.
        val gracia = PlayStoreCatalog.maxTier(modeOf(EnrollmentProfiles.LEVEL_GRACE))
        assertTrue(PlayStoreCatalog.MODES.all { PlayStoreCatalog.maxTier(it) <= gracia })
    }

    @Test
    fun alTerminarLaGracia_laTiendaQuedaEnKosher() {
        // La gracia cierra aplicando el perfil destino, que por omisión es el Estricto
        // (GracePeriodManager.targetProfile / PolicyManager.applyMasterProfile).
        val destinoPorOmision = EnrollmentProfiles.LEVEL_STRICT
        assertEquals(PlayStoreCatalog.MODE_KOSHER, modeOf(destinoPorOmision))
    }

    @Test
    fun soloLlamadas_cierraLaTienda() {
        assertEquals(PlayStoreCatalog.MODE_CLOSED, modeOf(EnrollmentProfiles.LEVEL_CALLS_ONLY))
        assertTrue(!PlayStoreCatalog.isOpen(modeOf(EnrollmentProfiles.LEVEL_CALLS_ONLY)))
    }

    @Test
    fun lite_abreLaTiendaCompleta() {
        assertEquals(PlayStoreCatalog.MODE_LITE, modeOf(EnrollmentProfiles.LEVEL_LITE))
        assertEquals(PlayStoreCatalog.TIER_LITE, PlayStoreCatalog.maxTier(modeOf(EnrollmentProfiles.LEVEL_LITE)))
    }

    @Test
    fun baseMinima_noTocaLaTienda() {
        // Perfil de diagnóstico: "clave ausente = no se toca".
        assertNull(modeOf(EnrollmentProfiles.LEVEL_BASE))
    }

    @Test
    fun todoModoDeUnPerfilEsUnModoValido() {
        for (p in EnrollmentProfiles.ALL) {
            val mode = p.storeMode ?: continue
            // El importador usa modeFromProfile: un modo que no reconoce se IGNORA en
            // silencio, o sea que un perfil con un modo mal escrito no abriría ni cerraría
            // nada. Acá se afirma que ninguno está mal escrito.
            assertNotNull("El perfil ${p.id} trae un modo que el importador no reconoce: $mode",
                PlayStoreCatalog.modeFromProfile(mode))
        }
    }

    @Test
    fun losIdsSonUnicosYSeranLosQueConoceLaFuncionDeLaNube() {
        val ids = EnrollmentProfiles.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf("kosher_estricto", "trabajo", "base_minima", "gracia", "solo_llamadas", "lite"),
            ids.toSet()
        )
    }

    @Test
    fun losNivelesNuevosNoRenumeranLosViejos() {
        assertEquals("Nivel 1 — Kosher estricto", EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_STRICT)?.label)
        assertEquals("Nivel 2 — Trabajo", EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_WORK)?.label)
        assertEquals("Nivel 3 — Base mínima", EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_BASE)?.label)
        assertEquals("Nivel 4 — Período de gracia", EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_GRACE)?.label)
        assertEquals("Nivel 5 — Solo llamadas", EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_CALLS_ONLY)?.label)
        assertEquals("Nivel 6 — Lite", EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_LITE)?.label)
    }

    @Test
    fun soloLlamadas_yEstricto_tienenLasMismasProtecciones() {
        // Hoy la única diferencia declarada es la tienda. Si alguien las separa, que sea a
        // propósito y que este test se actualice con él.
        val a = EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_CALLS_ONLY)!!
        val b = EnrollmentProfiles.byId(EnrollmentProfiles.LEVEL_STRICT)!!
        assertEquals(b.restrictions, a.restrictions)
        assertEquals(b.switches, a.switches)
    }
}
