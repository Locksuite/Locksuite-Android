package com.ejemplo.locksuite.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.ejemplo.locksuite.R
import com.ejemplo.locksuite.mdm.PlayStoreManager
import com.ejemplo.locksuite.ui.aurora.AuroraStoreActivity
import com.ejemplo.locksuite.ui.aurora.AuroraStoreText
import com.ejemplo.locksuite.util.LocaleManager

/**
 * TIENDA AURORA WIDGET (B.132) — ícono 1x1 en pantalla de inicio para la tercera tienda.
 *
 * Igual que [PlayStoreWidgetProvider], se enciende con acento cian/índigo cuando la tienda
 * está abierta (`PlayStoreManager.isOpen`) y se apaga en gris cuando está cerrada.
 */
class AuroraStoreWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        private const val TAG = "AuroraStoreWidget"
        private const val REQUEST_CODE = 106

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_aurora_store)

            val open = try {
                PlayStoreManager.isOpen(context)
            } catch (_: Exception) {
                false
            }
            val lang = LocaleManager.getLang()
            val label = AuroraStoreText.t(AuroraStoreText.Key.WIDGET_LABEL, lang)

            views.setImageViewResource(
                R.id.widget_aurora_icon,
                if (open) R.drawable.ic_aurora_store_tile else R.drawable.ic_aurora_store_tile_off
            )
            views.setTextViewText(R.id.widget_aurora_label, label)
            views.setContentDescription(R.id.widget_aurora_icon, AuroraStoreText.t(AuroraStoreText.Key.TITLE, lang))

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                context, REQUEST_CODE, AuroraStoreActivity.intent(context), flags
            )
            views.setOnClickPendingIntent(R.id.widget_aurora_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun refreshAll(context: Context) {
            try {
                val ctx = context.applicationContext
                val manager = AppWidgetManager.getInstance(ctx)
                val ids = manager.getAppWidgetIds(ComponentName(ctx, AuroraStoreWidgetProvider::class.java))
                for (id in ids) {
                    updateAppWidget(ctx, manager, id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo actualizar el widget de Tienda Aurora: ${e.message}")
            }
        }
    }
}
