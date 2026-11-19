package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19/11/2026 (B.125, T6) — el ruido de logcat del fabricante no entra al registro. */
class DiagVendorNoiseTest {

    @Test fun losCuatroMensajesDelQinF21SonRuido() {
        assertTrue(DiagLogcatParser.isVendorNoise("emplo.locksuit", "Not starting debugger since process cannot load the jdwp agent."))
        assertTrue(DiagLogcatParser.isVendorNoise("libc", "Access denied finding property \"ro.vendor.pref_scale_resolution\""))
        assertTrue(DiagLogcatParser.isVendorNoise("ion", "ioctl c0044901 failed with code -1: Invalid argument"))
        assertTrue(DiagLogcatParser.isVendorNoise("emplo.locksuit", "No package ID 6b found for ID 0x6b0b0013."))
    }

    @Test fun unErrorPropioNuncaEsRuido() {
        assertFalse(DiagLogcatParser.isVendorNoise("SelfUpdater", "Error al instalar Fondos"))
        assertFalse(DiagLogcatParser.isVendorNoise("LockSuiteFCM", "Comando FCM rechazado (REPLAY): COLLECT_DIAGNOSTICS"))
        // Misma palabra «ioctl» pero con otra etiqueta: no es el ruido conocido de `ion`.
        assertFalse(DiagLogcatParser.isVendorNoise("KosherVPN", "ioctl failed al crear el túnel"))
        // Una propiedad que NO es del vendor sí puede ser un problema real.
        assertFalse(DiagLogcatParser.isVendorNoise("libc", "Access denied finding property \"persist.sys.locale\""))
    }

    @Test fun losBurstsDescartanElRuidoYConservanLoDemas() {
        val e = listOf(
            DiagLogcatParser.Entry("11-15 20:55:26.100", 'E', "emplo.locksuit", "Not starting debugger since process cannot load the jdwp agent."),
            DiagLogcatParser.Entry("11-15 20:55:27.100", 'E', "ion", "ioctl c0044901 failed with code -1: Invalid argument"),
            DiagLogcatParser.Entry("11-16 09:35:42.100", 'E', "SelfUpdater", "Error al instalar Fondos")
        )
        val b = DiagLogcatParser.bursts(e, 'E')
        assertEquals(1, b.size)
        assertEquals("SelfUpdater", b[0].tag)
    }
}
