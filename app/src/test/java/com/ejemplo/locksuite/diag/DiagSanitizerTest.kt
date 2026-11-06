package com.ejemplo.locksuite.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6/11/2026 (B.110) — el limpiador de datos personales. Dos mitades, las dos igual de
 * importantes: que SAQUE lo privado (correos, teléfonos, direcciones, rutas, tokens) y que
 * NO toque lo que sirve para diagnosticar (nombres de clase, líneas, números sueltos).
 */
class DiagSanitizerTest {

    private fun s(x: String) = DiagSanitizer.sanitize(x)

    // ── lo que se saca ──────────────────────────────────────────────────────────────

    @Test
    fun saca_correos() {
        val r = s("Cuenta gonzalo.perez+x@gmail.com falló")
        assertFalse(r, r.contains("@"))
        assertTrue(r, r.contains("<email>"))
    }

    @Test
    fun saca_direcciones_web_de_terceros_y_deja_solo_el_sitio_de_lockSuite() {
        val ajeno = s("GET https://sitio-privado.com/ruta/secreta?q=algo falló")
        assertTrue(ajeno, ajeno.contains("<url>"))
        assertFalse(ajeno, ajeno.contains("secreta") || ajeno.contains("sitio-privado"))

        val propio = s("POST https://locksuite-nueva-default-rtdb.firebaseio.com/devices/abc.json?auth=XYZ falló")
        assertTrue(propio, propio.contains("https://locksuite-nueva-default-rtdb.firebaseio.com/…"))
        assertFalse(propio, propio.contains("devices/abc") || propio.contains("auth=XYZ"))
    }

    @Test
    fun saca_content_uri_y_rutas_de_almacenamiento() {
        val a = s("No pude abrir content://com.android.contacts/contacts/123 ni file:///sdcard/x")
        assertFalse(a, a.contains("contacts/123") || a.contains("/sdcard"))
        assertTrue(a, a.contains("content://<ruta>"))
        val b = s("Falló /storage/emulated/0/DCIM/foto.jpg")
        assertTrue(b, b.contains("<ruta-almacenamiento>") && !b.contains("foto.jpg"))
    }

    @Test
    fun saca_telefonos_pero_no_numeros_tecnicos() {
        assertTrue(s("llamó +54 9 11 5555-1234 ayer").contains("<tel>"))
        assertTrue(s("llamó 11 5555 1234 ayer").contains("<tel>"))
        assertTrue(s("número 5491155551234 guardado").contains("<tel>"))
        // Lo técnico queda intacto.
        val tec = "ts=1730851200000 pid=1234 uid=10234 elapsedMs=12000 versionCode=140"
        assertEquals(tec, s(tec))
        assertEquals("fecha 2026-11-06 hora 12:30:45", s("fecha 2026-11-06 hora 12:30:45"))
    }

    @Test
    fun saca_tokens_hashes_mac_ssid_e_ip_publica() {
        val fcm = "fAbCdEfGhIjKlMn:APA91b" + "x".repeat(80)
        assertTrue(s("token $fcm listo").contains("<token>"))
        assertTrue(s("sha=" + "a".repeat(40)).contains("<hex>"))
        assertTrue(s("mac AA:BB:CC:DD:EE:FF").contains("<mac>"))
        val w = s("conectado a SSID: \"MiWifiDeCasa\" ok")
        assertFalse(w, w.contains("MiWifiDeCasa"))
        assertTrue(s("connect to /142.250.78.14:443 failed").contains("<ip>"))
    }

    @Test
    fun saca_cadenas_largas_sin_puntos_pero_no_las_clases() {
        val jwtLike = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcDEF123456"   // 46 caracteres, sin puntos
        assertTrue(s("auth $jwtLike fin").contains("<token>"))
        assertFalse(s("auth $jwtLike fin").contains("eyJhbGci"))
        // Un nombre de clase largo tiene puntos: cada tramo es corto y NO se toca.
        val clase = "com.ejemplo.locksuite.service.LockSuiteAccessibilityService.onServiceConnected"
        assertEquals(clase, s(clase))
    }

    @Test
    fun saca_nombres_de_sitio_de_los_errores_de_red() {
        val a = s("Unable to resolve host \"www.sitio-malo.com\": No address associated with hostname")
        assertFalse(a, a.contains("sitio-malo"))
        assertTrue(a, a.contains("<sitio>"))
        val b = s("java.net.UnknownHostException: www.sitio-malo.com")
        assertFalse(b, b.contains("sitio-malo"))
        // El de LockSuite/Google sí se deja: sirve para diagnosticar.
        assertTrue(s("Unable to resolve host \"firebaseio.com\"").contains("firebaseio.com"))
    }

    // ── lo que NO se toca ───────────────────────────────────────────────────────────

    @Test
    fun conserva_clases_metodos_y_lineas() {
        val frame = "at com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(FirebaseDeviceSync.kt:168)"
        assertEquals(frame, s(frame))
        val ex = "java.lang.IllegalStateException: Foreground service did not call startForeground() in time"
        assertEquals(ex, s(ex))
    }

    @Test
    fun conserva_ip_local_y_versiones() {
        assertEquals("gateway 192.168.1.1 dns 10.0.0.2", s("gateway 192.168.1.1 dns 10.0.0.2"))
        assertEquals("version 999.1.2.3", s("version 999.1.2.3"))
        assertEquals("app 0.6.77 (140)", s("app 0.6.77 (140)"))
    }

    // ── bordes ──────────────────────────────────────────────────────────────────────

    @Test
    fun trunca_al_final_y_un_correo_cortado_no_se_cuela() {
        val largo = "x ".repeat(205) + "persona@gmail.com resto " + "y".repeat(100)
        val r = DiagSanitizer.sanitize(largo, 420)
        assertTrue(r.length <= 420)
        assertFalse(r, r.contains("@"))
        assertTrue(r.endsWith("…"))
    }

    @Test
    fun caracteres_de_control_y_nulos() {
        assertEquals("", s(""))
        assertEquals("", DiagSanitizer.sanitize(null))
        assertEquals("a b c", s("a\u0000b\u0007c"))
        assertEquals("línea1\nlínea2", s("línea1\nlínea2"))
    }

    @Test
    fun traza_recorta_lineas() {
        val raw = (1..40).joinToString("\n") { "  at com.x.Clase$it.metodo(Clase.kt:$it)" }
        val r = DiagSanitizer.sanitizeTrace(raw)
        assertTrue(r.lines().size <= 15)
        assertTrue(r, r.contains("más líneas"))
        assertTrue(r.length <= 1800)
    }

    @Test
    fun bloque_limpiaCadaLineaYCortaElTotal() {
        val raw = (1..200).joinToString("\n") { "línea $it de ana$it@example.com" }
        val r = DiagSanitizer.sanitizeBlock(raw, 1000)
        assertTrue(r.length <= 1000 + 20)
        assertFalse(r, r.contains("@example.com"))
        assertTrue(r, r.endsWith("… (recortado)"))
        assertEquals("corto: intacto", "a\nb", DiagSanitizer.sanitizeBlock("a\nb", 1000))
        assertEquals("", DiagSanitizer.sanitizeBlock(null, 10))
    }

    @Test
    fun describe_incluye_clase_causa_y_limpia_el_mensaje() {
        val causa = IllegalArgumentException("sin red para luis@example.com")
        val ex = IllegalStateException("falló el paso", causa)
        val r = DiagSanitizer.describe(ex)
        assertTrue(r, r.startsWith("java.lang.IllegalStateException: falló el paso"))
        assertTrue(r, r.contains("Caused by: java.lang.IllegalArgumentException"))
        assertFalse(r, r.contains("luis@example.com"))
        assertEquals("", DiagSanitizer.describe(null))
        assertEquals("java.lang.RuntimeException: boom", DiagSanitizer.headline(RuntimeException("boom")))
    }

    @Test
    fun nunca_lanza_con_entradas_raras() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(200)) { sb.append((rnd.nextInt(0x2FF)).toChar()) }
            DiagSanitizer.sanitize(sb.toString())
            DiagSanitizer.sanitizeTrace(sb.toString())
        }
    }
}
