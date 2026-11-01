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
import com.ejemplo.locksuite.ui.playstore.PlayStoreActivity
import com.ejemplo.locksuite.ui.playstore.PlayStoreText
import com.ejemplo.locksuite.util.LocaleManager

/**
 * TIENDA PLAY (29/10/2026, B.101) — el "widget" que se ve como el ícono de una app.
 *
 * Un toque abre [PlayStoreActivity]: las apps que el administrador permitió para este
 * equipo, con sus íconos y nombres reales. A diferencia de los otros widgets de LockSuite
 * (tarjeta oscura con borde, título y subtítulo), este no tiene fondo, borde ni subtítulo:
 * un recuadro de color con una bolsa y el nombre debajo, como cualquier ícono del
 * lanzador. Es de una sola celda y de tamaño fijo para que no parezca un widget.
 *
 * ## Qué cambia según el estado
 *  - Tienda **abierta** (modo kosher / trabajo / lite): ícono verde.
 *  - Tienda **cerrada**: el mismo ícono apagado en gris. Un toque abre la pantalla igual y
 *    ésta explica que la tienda está cerrada (nunca un toque que no hace nada).
 *  - El nombre de abajo sale en el idioma elegido en LockSuite (es / en / he).
 *
 * `PlayStoreManager` llama a [refreshAll] cada vez que cambia el modo o la configuración,
 * así el ícono se apaga/enciende sin esperar a la actualización diaria del sistema.
 *
 * Ojo (igual que con el widget de Verificar Google): el lanzador kosher propio de LockSuite
 * NO aloja widgets. Ahí la tienda aparece como un ícono más de la grilla
 * (`KosherLauncherActivity`) y en el modo teclas como una entrada del menú.
 */
class PlayStoreWidgetProvider : AppWidgetProvider() {

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
        private const val TAG = "PlayStoreWidget"

        /** requestCode propio (101..104 son de los otros widgets). */
        private const val REQUEST_CODE = 105

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_play_store)

            val open = try {
                PlayStoreManager.isOpen(context)
            } catch (e: Exception) {
                // Ante un error se dibuja apagado: nunca se aparenta una tienda abierta.
                false
            }
            val lang = LocaleManager.getLang()
            val label = PlayStoreText.t(PlayStoreText.Key.WIDGET_LABEL, lang)

            views.setImageViewResource(
                R.id.widget_ps_icon,
                if (open) R.drawable.ic_play_store_tile else R.drawable.ic_play_store_tile_off
            )
            views.setTextViewText(R.id.widget_ps_label, label)
            views.setContentDescription(R.id.widget_ps_icon, PlayStoreText.t(PlayStoreText.Key.TITLE, lang))

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                context, REQUEST_CODE, PlayStoreActivity.intent(context), flags
            )
            views.setOnClickPendingIntent(R.id.widget_ps_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Vuelve a dibujar todos los íconos de la tienda que haya en la pantalla de inicio. */
        fun refreshAll(context: Context) {
            try {
                val ctx = context.applicationContext
                val manager = AppWidgetManager.getInstance(ctx)
                val ids = manager.getAppWidgetIds(ComponentName(ctx, PlayStoreWidgetProvider::class.java))
                for (id in ids) {
                    updateAppWidget(ctx, manager, id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo actualizar el ícono de la tienda: ${e.message}")
            }
        }
    }
}
