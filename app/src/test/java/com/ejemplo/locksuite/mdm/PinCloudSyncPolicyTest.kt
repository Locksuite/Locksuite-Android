package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.PinCloudSyncPolicy.CloudFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1/11/2026 (B.104) — cuándo un celular tiene que (re)publicar su PIN de administrador en la nube.
 *
 * El caso que originó todo: celular nuevo, el dueño elige el PIN, la única escritura a la nube
 * cae mal (sin internet, sin sesión, otro uid) y el panel queda diciendo
 * `DEVICE_PIN_NOT_ENROLLED` hasta que se cambia el PIN a mano. Estas pruebas fijan las reglas
 * que lo evitan: lo que no se confirmó se vuelve a mandar, con espera creciente, y lo que la
 * nube perdió también.
 */
class PinCloudSyncPolicyTest {

    private val key = PinCloudSyncPolicy.credentialKey("HASH", "SAL", "1234567890")

    // ── Clave de credenciales ─────────────────────────────────────────────────────────────

    @Test
    fun claveVaciaSinPin() {
        assertEquals("", PinCloudSyncPolicy.credentialKey(null, "SAL", "123"))
        assertEquals("", PinCloudSyncPolicy.credentialKey("HASH", null, "123"))
        assertEquals("", PinCloudSyncPolicy.credentialKey("", "SAL", "123"))
        assertEquals("", PinCloudSyncPolicy.credentialKey("HASH", "", "123"))
    }

    @Test
    fun claveCambiaConCualquierPieza() {
        val base = PinCloudSyncPolicy.credentialKey("HASH", "SAL", "111")
        assertEquals("misma entrada, misma clave", base, PinCloudSyncPolicy.credentialKey("HASH", "SAL", "111"))
        assertNotEquals(base, PinCloudSyncPolicy.credentialKey("HASH2", "SAL", "111"))
        assertNotEquals(base, PinCloudSyncPolicy.credentialKey("HASH", "SAL2", "111"))
        assertNotEquals(base, PinCloudSyncPolicy.credentialKey("HASH", "SAL", "222"))
        // Que aparezca el código de recuperación (se genera justo después del PIN) también cuenta.
        assertNotEquals(base, PinCloudSyncPolicy.credentialKey("HASH", "SAL", null))
    }

    @Test
    fun claveNoSeConfundeAlMoverSeparadores() {
        // Sal y hash en base64 no llevan '|', pero la clave no debe depender de eso para distinguirse.
        assertNotEquals(
            PinCloudSyncPolicy.credentialKey("B", "A", "C"),
            PinCloudSyncPolicy.credentialKey("A", "B", "C")
        )
    }

    // ── ¿Hay que publicar? ────────────────────────────────────────────────────────────────

    @Test
    fun sinPinEnElEquipoNuncaPublica() {
        assertFalse(PinCloudSyncPolicy.needsPublish("", null, CloudFlag.ABSENT))
        assertFalse(PinCloudSyncPolicy.needsPublish(null, key, CloudFlag.ABSENT))
        assertFalse(PinCloudSyncPolicy.needsPublish("", "", CloudFlag.UNKNOWN))
    }

    @Test
    fun nuncaConfirmadoPublica_elCasoDelCelularNuevo() {
        assertTrue(PinCloudSyncPolicy.needsPublish(key, null, CloudFlag.UNKNOWN))
        assertTrue(PinCloudSyncPolicy.needsPublish(key, "", CloudFlag.UNKNOWN))
        assertTrue("aunque la bandera diga que sí: la confirmación es la que manda",
            PinCloudSyncPolicy.needsPublish(key, null, CloudFlag.PRESENT))
    }

    @Test
    fun cambioDePinSinConfirmarPublica() {
        val viejo = PinCloudSyncPolicy.credentialKey("HASH_VIEJO", "SAL", "1234567890")
        assertTrue(PinCloudSyncPolicy.needsPublish(key, viejo, CloudFlag.PRESENT))
    }

    @Test
    fun confirmadoYNubeAlDiaNoPublica() {
        assertFalse(PinCloudSyncPolicy.needsPublish(key, key, CloudFlag.PRESENT))
        assertFalse("sin poder leer la nube se confía en la confirmación",
            PinCloudSyncPolicy.needsPublish(key, key, CloudFlag.UNKNOWN))
    }

    @Test
    fun confirmadoPeroLaNubeLoPerdioPublicaDeNuevo_eliminarYVolverAAgregar() {
        assertTrue(PinCloudSyncPolicy.needsPublish(key, key, CloudFlag.ABSENT))
    }

    // ── Espera entre intentos ─────────────────────────────────────────────────────────────

    @Test
    fun esperaCreceDuplicandoYTieneTope() {
        assertEquals(0L, PinCloudSyncPolicy.backoffMs(0))
        assertEquals(0L, PinCloudSyncPolicy.backoffMs(-3))
        assertEquals(10_000L, PinCloudSyncPolicy.backoffMs(1))
        assertEquals(20_000L, PinCloudSyncPolicy.backoffMs(2))
        assertEquals(40_000L, PinCloudSyncPolicy.backoffMs(3))
        assertEquals(80_000L, PinCloudSyncPolicy.backoffMs(4))
        assertEquals(160_000L, PinCloudSyncPolicy.backoffMs(5))
        assertEquals(PinCloudSyncPolicy.MAX_BACKOFF_MS, PinCloudSyncPolicy.backoffMs(6))
        assertEquals(PinCloudSyncPolicy.MAX_BACKOFF_MS, PinCloudSyncPolicy.backoffMs(40))
        assertEquals("no desborda con una cantidad absurda de fallos",
            PinCloudSyncPolicy.MAX_BACKOFF_MS, PinCloudSyncPolicy.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun primerIntentoSiempreSePermite() {
        assertTrue(PinCloudSyncPolicy.mayAttempt(1_000L, 0L, 0))
        assertTrue(PinCloudSyncPolicy.mayAttempt(1_000L, 999L, 0))
        assertTrue("sin hora del último intento", PinCloudSyncPolicy.mayAttempt(1_000L, 0L, 5))
    }

    @Test
    fun trasUnFalloEsperaAntesDeReintentar() {
        val last = 100_000L
        assertFalse(PinCloudSyncPolicy.mayAttempt(last + 9_999L, last, 1))
        assertTrue(PinCloudSyncPolicy.mayAttempt(last + 10_000L, last, 1))
        assertFalse(PinCloudSyncPolicy.mayAttempt(last + 19_999L, last, 2))
        assertTrue(PinCloudSyncPolicy.mayAttempt(last + 20_000L, last, 2))
        assertFalse(PinCloudSyncPolicy.mayAttempt(last + 299_999L, last, 9))
        assertTrue(PinCloudSyncPolicy.mayAttempt(last + 300_000L, last, 9))
    }

    @Test
    fun relojAtrasadoNuncaBloquea() {
        assertTrue(PinCloudSyncPolicy.mayAttempt(50_000L, 200_000L, 4))
        assertTrue(PinCloudSyncPolicy.shouldCheckCloudFlag(50_000L, 200_000L))
        assertTrue(PinCloudSyncPolicy.isInFlightStale(50_000L, 200_000L))
    }

    // ── Revisión de la bandera pública ────────────────────────────────────────────────────

    @Test
    fun laBanderaSeMiraAlPrincipioYCadaCincoMinutos() {
        assertTrue(PinCloudSyncPolicy.shouldCheckCloudFlag(1_000L, 0L))
        val last = 1_000_000L
        assertFalse(PinCloudSyncPolicy.shouldCheckCloudFlag(last + 60_000L, last))
        assertFalse(PinCloudSyncPolicy.shouldCheckCloudFlag(last + PinCloudSyncPolicy.CLOUD_CHECK_EVERY_MS - 1, last))
        assertTrue(PinCloudSyncPolicy.shouldCheckCloudFlag(last + PinCloudSyncPolicy.CLOUD_CHECK_EVERY_MS, last))
    }

    // ── Publicación colgada ───────────────────────────────────────────────────────────────

    @Test
    fun publicacionColgadaSeDaPorPerdidaPasadosDiezMinutos() {
        val started = 500_000L
        assertFalse(PinCloudSyncPolicy.isInFlightStale(started + 1_000L, started))
        assertFalse(PinCloudSyncPolicy.isInFlightStale(started + PinCloudSyncPolicy.IN_FLIGHT_STALE_MS - 1, started))
        assertTrue(PinCloudSyncPolicy.isInFlightStale(started + PinCloudSyncPolicy.IN_FLIGHT_STALE_MS, started))
        assertTrue("sin hora de inicio no hay nada en curso", PinCloudSyncPolicy.isInFlightStale(1_000L, 0L))
    }

    // ── Lanzar una publicación nueva mientras hay una en vuelo ────────────────────────────

    @Test
    fun sinNingunaEnCursoSePuedeLanzar() {
        assertTrue(PinCloudSyncPolicy.mayStartPublish(false, true, 1_000L, 0L))
        assertTrue(PinCloudSyncPolicy.mayStartPublish(false, false, 1_000L, 0L))
    }

    @Test
    fun conLasMismasCredencialesEnVueloNoSeApila() {
        val started = 1_000_000L
        assertFalse("sin internet la escritura queda en cola: no se apilan más",
            PinCloudSyncPolicy.mayStartPublish(true, true, started + 60_000L, started))
        assertFalse(PinCloudSyncPolicy.mayStartPublish(true, true,
            started + PinCloudSyncPolicy.IN_FLIGHT_STALE_MS - 1, started))
    }

    @Test
    fun conLasMismasCredencialesPeroColgadaSePuedeReemplazar() {
        val started = 1_000_000L
        assertTrue(PinCloudSyncPolicy.mayStartPublish(true, true,
            started + PinCloudSyncPolicy.IN_FLIGHT_STALE_MS, started))
    }

    @Test
    fun siCambioElPinLoQueEstabaEnVueloYaEsViejo() {
        val started = 1_000_000L
        assertTrue("el PIN nuevo no espera a que termine el viejo",
            PinCloudSyncPolicy.mayStartPublish(true, false, started + 1_000L, started))
    }
}
