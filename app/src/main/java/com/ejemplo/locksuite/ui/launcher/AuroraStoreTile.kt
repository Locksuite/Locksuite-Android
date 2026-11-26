package com.ejemplo.locksuite.ui.launcher

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.ui.graphics.Color
import com.ejemplo.locksuite.ui.aurora.AuroraStoreActivity
import com.ejemplo.locksuite.ui.aurora.AuroraStoreText
import com.ejemplo.locksuite.util.LocaleManager

/**
 * Tile de Tienda Aurora en el lanzador kosher propio y en el modo teclas Nokia (B.132).
 */
object AuroraStoreTile {

    const val PACKAGE = "com.ejemplo.locksuite.aurorastore"

    fun item(context: Context): AppItem {
        val label = AuroraStoreText.t(AuroraStoreText.Key.WIDGET_LABEL, LocaleManager.getLang())
        return AppItem(
            packageName = PACKAGE,
            label = label,
            launchIntent = AuroraStoreActivity.intent(context),
            kosherIcon = KosherAppIcon(label, Icons.Filled.CloudDownload, Color(0xFF06B6D4), Color(0xFF1E3A8A)),
            resolveInfo = null
        )
    }
}
