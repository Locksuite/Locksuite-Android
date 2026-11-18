package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SantanderBlockPolicyTest {

    private fun norm(vararg items: String): List<String> =
        items.map { SantanderBlockPolicy.normalizar(it) }

    @Test
    fun `permite pantalla inicial nativa de login con enlace Terminos y Condiciones`() {
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            windowClass = "ar.com.santander.rio.mbanking.app.ui.activities.SplashActivity",
            tieneWebView = false,
            textos = norm("Iniciar sesión", "Términos y Condiciones", "Ayuda")
        )
        assertEquals(SantanderBlockPolicy.Veredicto.PERMITIR, SantanderBlockPolicy.evaluar(r))
    }

    @Test
    fun `permite WebView de Terminos y Condiciones incluyendo pie con links sin navegar`() {
        // Capturas 131705 y 131718: se deja leer Términos y Condiciones hasta que naveguen fuera
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            windowClass = "ar.com.santander.rio.mbanking.app.ui.fragments.publico.PrivacyTermsActivity",
            tieneWebView = true,
            textos = norm(
                "Términos y condiciones",
                "¡Conocé los Términos y Condiciones!",
                "Nueva Política de Privacidad.",
                "¿En qué podemos ayudarte?",
                "App Store",
                "Google Play",
                "Banco Santander Argentina S.A."
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.PERMITIR, SantanderBlockPolicy.evaluar(r))
    }

    @Test
    fun `permite WebView de Nueva Politica de Privacidad con enlace a santander com ar sin navegar`() {
        // Capturas 131730, 131737 y 131743
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            windowClass = "ar.com.santander.rio.mbanking.mvvm.webview.WebViewActivity",
            tieneWebView = true,
            textos = norm(
                "Nueva Política de Privacidad",
                "¿Qué tipo de información recopilamos?",
                "www.santander.com.ar",
                "¿En qué podemos ayudarte?",
                "App Store",
                "Google Play"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.PERMITIR, SantanderBlockPolicy.evaluar(r))
    }

    @Test
    fun `bloquea Google Play Web abierto desde el pie de Terminos y Condiciones (juego)`() {
        // Captura 131751
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            tieneWebView = true,
            textos = norm(
                "Google Play",
                "Tráiler",
                "Instalar",
                "Acerca de este juego",
                "Seguridad de los datos",
                "Para todos",
                "Interacción de usuarios"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.BLOQUEAR, SantanderBlockPolicy.evaluar(r))
        assertTrue(SantanderBlockPolicy.motivo(r).contains("google play"))
    }

    @Test
    fun `bloquea Google Play Web catalogo abierto desde Santander`() {
        // Captura 131752
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            tieneWebView = true,
            textos = norm(
                "Google Play",
                "Juegos",
                "Aplicaciones",
                "Libros",
                "Niños",
                "Descubrir juegos populares"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.BLOQUEAR, SantanderBlockPolicy.evaluar(r))
    }

    @Test
    fun `bloquea Apple App Store Web abierto desde Santander`() {
        // Captura 131808
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            tieneWebView = true,
            textos = norm(
                "App Store",
                "Santander Argentina",
                "4.8",
                "Age Rating",
                "Designed for iPad",
                "Arcade",
                "iPhone",
                "iPad"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.BLOQUEAR, SantanderBlockPolicy.evaluar(r))
        assertTrue(SantanderBlockPolicy.motivo(r).contains("app store"))
    }

    @Test
    fun `bloquea portal publico santander com ar desde Politica de Privacidad (captura 132905)`() {
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            tieneWebView = true,
            textos = norm(
                "Online Banking",
                "¡Hola! ¿En qué podemos ayudarte?",
                "Buscar",
                "Tus claves",
                "Números útiles",
                "¿Buscás otra cosa? Ingresá al centro de ayuda",
                "Cajeros y Sucursales",
                "Hacete Cliente Ahora",
                "Beneficios y Promociones"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.BLOQUEAR, SantanderBlockPolicy.evaluar(r))
        assertTrue(SantanderBlockPolicy.motivo(r).contains("santander.com.ar"))
    }

    @Test
    fun `bloquea portal publico santander com ar scrolleado (captura 132926)`() {
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            tieneWebView = true,
            textos = norm(
                "Online Banking",
                "Conocé nuestros productos y servicios",
                "Tarjetas",
                "Préstamos",
                "Inversiones",
                "Seguros",
                "Cuenta Sueldo",
                "Préstamos Prendarios",
                "Cajeros y Sucursales",
                "Hacete Cliente Ahora",
                "Beneficios y Promociones"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.BLOQUEAR, SantanderBlockPolicy.evaluar(r))
    }

    @Test
    fun `permite WebViews bancarios internos de Seguros CEDIP y Comisiones`() {
        val r = SantanderBlockPolicy.Retrato(
            packageName = SantanderBlockPolicy.PKG_SANTANDER,
            windowClass = "ar.com.santander.rio.mbanking.app.ui.activities.SegurosWebViewActivity",
            tieneWebView = true,
            textos = norm(
                "Mis Seguros",
                "Coberturas activas",
                "Comprobante de pago",
                "Transferir",
                "CBU",
                "Token de seguridad"
            )
        )
        assertEquals(SantanderBlockPolicy.Veredicto.PERMITIR, SantanderBlockPolicy.evaluar(r))
    }

    @Test
    fun `ignora otras aplicaciones aunque muestren WebView con los mismos textos`() {
        val r = SantanderBlockPolicy.Retrato(
            packageName = "com.android.chrome",
            tieneWebView = true,
            textos = norm("Online Banking", "Hacete Cliente Ahora", "Cajeros y Sucursales")
        )
        assertEquals(SantanderBlockPolicy.Veredicto.PERMITIR, SantanderBlockPolicy.evaluar(r))
    }
}
