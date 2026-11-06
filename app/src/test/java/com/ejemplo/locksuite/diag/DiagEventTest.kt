package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6/11/2026 (B.110) — el formato de un renglón del registro de diagnóstico. Lo que importa:
 * que ida y vuelta no pierda nada (ni siquiera con saltos de línea y tabs en el mensaje),
 * que siga siendo UNA línea por evento, y que una línea rota (corte de energía a mitad de
 * escritura) se ignore en vez de tirar abajo la lectura del resto.
 */
class DiagEventTest {

    @Test
    fun idaYVuelta_conSaltosTabsYBarras() {
        val e = DiagEvent(12, 1730851200000L, 'E', "vpn", "falló\nsegunda\tlínea con \\ barra y \r retorno", 3)
        val d = DiagEvent.decode(e.encode())
        assertNotNull(d)
        assertEquals(e, d)
    }

    @Test
    fun unaLineaPorEvento() {
        val e = DiagEvent(1, 1L, 'W', "cmd", "a\nb\nc\td")
        val enc = e.encode()
        assertFalse(enc, enc.contains('\n'))
        assertFalse(enc, enc.contains('\r'))
        // 5 tabs de separación y ninguna más (las del mensaje van escapadas).
        assertEquals(5, enc.count { it == '\t' })
    }

    @Test
    fun lineasRotasSeIgnoran_sinLanzarExcepcion() {
        assertNull(DiagEvent.decode(null))
        assertNull(DiagEvent.decode(""))
        assertNull(DiagEvent.decode("basura"))
        assertNull(DiagEvent.decode("x\t1\tE\tvpn\t1\tm"))       // seq no numérico
        assertNull(DiagEvent.decode("1\ty\tE\tvpn\t1\tm"))       // hora no numérica
        assertNull(DiagEvent.decode("1\t2\tE\tvpn\tNaN\tm"))     // veces no numérico
        assertNull(DiagEvent.decode("0\t2\tE\tvpn\t1\tm"))       // seq 0 no existe
        assertNull(DiagEvent.decode("5\t1730"))                   // escritura cortada
        assertNull(DiagEvent.decode("1\t2\tEE\tvpn\t1\tm"))      // nivel de dos letras
    }

    @Test
    fun nivelYEtiquetaSeNormalizan() {
        val d = DiagEvent.decode(DiagEvent(1, 1, 'x', "VPN Service!!", "m").encode())!!
        assertEquals('I', d.level)
        assertEquals("vpn_service__", d.tag)
        assertEquals("app", DiagEvent.cleanTag(""))
        assertEquals(16, DiagEvent.cleanTag("a".repeat(40)).length)
        assertEquals('W', DiagEvent.normalizeLevel('w'))
    }

    @Test
    fun contadorNuncaBajaDeUno() {
        val d = DiagEvent.decode(DiagEvent(1, 1, 'I', "app", "m", 0).encode())!!
        assertEquals(1, d.count)
    }

    @Test
    fun importantesSonErrorYFatal() {
        assertTrue(DiagEvent(1, 1, 'E', "a", "m").isImportant)
        assertTrue(DiagEvent(1, 1, 'F', "a", "m").isImportant)
        assertFalse(DiagEvent(1, 1, 'W', "a", "m").isImportant)
        assertFalse(DiagEvent(1, 1, 'I', "a", "m").isImportant)
    }
}
