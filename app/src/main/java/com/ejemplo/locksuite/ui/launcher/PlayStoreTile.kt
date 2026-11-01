package com.ejemplo.locksuite.ui.launcher

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.Color
import com.ejemplo.locksuite.ui.playstore.PlayStoreActivity
import com.ejemplo.locksuite.ui.playstore.PlayStoreText
import com.ejemplo.locksuite.util.LocaleManager

/**
 * La Tienda Play como un ícono más de la grilla del lanzador kosher y del modo teclas
 * (29/10/2026, B.101).
 *
 * El lanzador kosher propio NO aloja widgets (ver `PlayStoreWidgetProvider`), así que esta
 * es su puerta de entrada a la tienda. Solo se agrega mientras la tienda está abierta en el
 * equipo (`PlayStoreManager.isOpen`): con la tienda cerrada no hay ícono que tocar.
 *
 * El paquete es ficticio (no existe en el equipo); lo único que importa de él es que no
 * choca con ninguno real y que el orden de la grilla lo reconoce.
 */
object PlayStoreTile {

    const val PACKAGE = "com.ejemplo.locksuite.playstore"

    fun item(context: Context): AppItem {
        val label = PlayStoreText.t(PlayStoreText.Key.WIDGET_LABEL, LocaleManager.getLang())
        return AppItem(
            packageName = PACKAGE,
            label = label,
            launchIntent = PlayStoreActivity.intent(context),
            kosherIcon = KosherAppIcon(label, Icons.Filled.ShoppingCart, Color(0xFF34D399), Color(0xFF0F766E)),
            resolveInfo = null
        )
    }
}
