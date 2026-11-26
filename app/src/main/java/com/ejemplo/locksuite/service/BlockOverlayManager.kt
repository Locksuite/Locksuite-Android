package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.ejemplo.locksuite.util.Safe

/**
 * Tapado visual de regiones de pantalla y overlay opaco de actualizaciones.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * REESCRITO EL 17/8/2026 — de N ventanas a UNA capa de canvas
 * ACTUALIZADO EL 19/11/2026 (B.126, Opción B) — solo portales cautivos Wi-Fi
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Desde B.126 se retiraron el bloqueo general de imágenes por app, el Modo IA y
 * el tapado estricto al desplazar (`setStrictCover`). La capa de canvas de regiones
 * queda reservada exclusivamente para tapar imágenes (`<img>` no clicables) dentro
 * de portales cautivos Wi-Fi (`captive_portal_cover_images`).
 *
 * Cómo funciona la capa de regiones:
 *
 *   Hay UNA sola ventana transparente a pantalla completa. Los recuadros son
 *   rectángulos dibujados en su canvas. Mover todos los recuadros = cambiar unos
 *   números en memoria y llamar a `invalidate()`: cero IPC, un solo frame, todos
 *   los recuadros se mueven juntos y en el mismo instante.
 *
 *   `translateRegions(dx, dy)` — cuando llega un evento de scroll en el portal,
 *   los recuadros se corren por el delta que informa el propio evento sin recorrer
 *   el árbol de nodos; el recorrido real ajusta un instante después.
 *
 * Costo de batería: la ventana solo existe si hay al menos una región que tapar; si
 * no hay ninguna, se quita y el compositor deja de tener una capa extra. `invalidate()`
 * se llama únicamente si algo cambió de verdad.
 */
class BlockOverlayManager(private val service: AccessibilityService) {

    private companion object {
        /** Cuánto se espera antes de borrar una región cuyo nodo dejó de verse. */
        private const val GRACE_MS = 350L

        /** Lista vacía compartida, para no asignar un array nuevo cada vez que se limpia. */
        private val EMPTY_RECTS = emptyArray<Rect>()

        /** Relleno de los recuadros. Opaco a propósito. */
        private const val FILL_COLOR = 0xFF0A0A0C.toInt()

        /** Borde tenue, para que se lea como un panel puesto a propósito y no como un glitch. */
        private const val EDGE_COLOR = 0xFF1E2430.toInt()

        /** A partir de este tamaño (px) el recuadro lleva el ícono de "contenido oculto". */
        private const val GLYPH_MIN_PX = 220
    }

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────────
    // Estado de las regiones (tapado de imágenes en portales cautivos)
    //
    // `drawList` es la foto inmutable que lee onDraw() en el hilo de UI sin
    // tomar ningún lock.
    // ──────────────────────────────────────────────────────────────
    private val lock = Any()
    private val regions = LinkedHashMap<String, Rect>(32)
    private val staged = HashMap<String, Rect>(32)
    private val pendingRemoval = HashMap<String, Long>(8)

    @Volatile private var drawList: Array<Rect> = EMPTY_RECTS

    private var overlayView: RegionOverlayView? = null
    private var sweepScheduled = false

    private val sweepRunnable = Runnable {
        Safe.guard("overlay.sweep") {
            sweepScheduled = false
            sweepExpired()
        }
    }

    /** Ejecuta ya mismo si estamos en el hilo principal; si no, lo encola. */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Safe.guard("overlay.main") { block() }
        } else {
            mainHandler.post {
                Safe.guard("overlay.mainPost") { block() }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // API pública de regiones (misma firma que la versión de ventanas,
    // para no tocar a quien la llama)
    // ──────────────────────────────────────────────────────────────

    /** ¿Hay al menos una región activa cuya clave empieza con [prefix]? */
    fun hasRegions(prefix: String): Boolean {
        synchronized(lock) {
            if (prefix.isEmpty()) return regions.isNotEmpty()
            return regions.keys.any { it.startsWith(prefix) }
        }
    }

    /** Cantidad de regiones activas. Sirve para decidir caminos rápidos sin recorrer nada. */
    fun regionCount(): Int = synchronized(lock) { regions.size }

    /**
     * Registra una región a tapar. NO dibuja todavía: queda en el área de preparación
     * y se aplica entera en [clearStaleRegions]. Así todas las regiones de un escaneo
     * aparecen en el mismo frame en vez de una por una.
     */
    fun blockRegion(key: String, rect: Rect, blockTouches: Boolean = false) {
        if (rect.isEmpty) return
        synchronized(lock) {
            // Copia defensiva: quien llama reutiliza el Rect para el nodo siguiente.
            staged[key] = Rect(rect)
        }
    }

    /**
     * Aplica el escaneo: las claves de [currentKeys] quedan visibles con la posición
     * que se preparó, y las que tenían este prefijo y ya no aparecen entran en período
     * de gracia (GRACE_MS) antes de borrarse, para que un escaneo que falla puntualmente
     * no destape la imagen uno o dos frames.
     */
    fun clearStaleRegions(prefix: String, currentKeys: Set<String>) {
        var changed = false
        var anyPending = false
        val now = SystemClock.uptimeMillis()

        synchronized(lock) {
            // 1. Volcar lo preparado que corresponde a este prefijo.
            val it = staged.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!e.key.startsWith(prefix)) continue
                it.remove()
                if (e.key !in currentKeys) continue
                val previous = regions.put(e.key, e.value)
                pendingRemoval.remove(e.key)
                if (previous == null || previous != e.value) changed = true
            }

            // 2. Marcar como ausentes las que ya no están.
            for (key in regions.keys) {
                if (!key.startsWith(prefix)) continue
                if (key in currentKeys) {
                    pendingRemoval.remove(key)
                } else {
                    if (pendingRemoval[key] == null) pendingRemoval[key] = now
                    anyPending = true
                }
            }
        }

        if (changed) rebuildAndInvalidate()
        // sweepExpired() repinta por su cuenta si borró algo; su resultado no se usa acá.
        sweepExpired()
        if (anyPending) onMain { scheduleSweep() }
    }

    /**
     * Camino rápido de scroll: corre TODAS las regiones por el delta informado por el
     * evento de accesibilidad, sin recorrer el árbol de nodos.
     *
     * Es la diferencia entre "el recuadro va atrás de la imagen" y "el recuadro va con
     * la imagen": recorrer el árbol cuesta milisegundos y solo se puede hacer unas
     * pocas veces por segundo; esto cuesta microsegundos y se puede hacer en cada frame.
     * El recorrido real corrige la posición un instante después.
     */
    fun translateRegions(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        var any = false
        synchronized(lock) {
            if (regions.isEmpty()) return
            for (r in regions.values) {
                r.offset(-dx, -dy)
                any = true
            }
        }
        if (any) rebuildAndInvalidate()
    }

    /** Destruye las regiones cuyo período de gracia venció. Devuelve true si algo cambió. */
    private fun sweepExpired(): Boolean {
        var changed = false
        synchronized(lock) {
            if (pendingRemoval.isEmpty()) return false
            val now = SystemClock.uptimeMillis()
            val it = pendingRemoval.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value < GRACE_MS) continue
                it.remove()
                if (regions.remove(e.key) != null) changed = true
            }
            if (pendingRemoval.isNotEmpty()) onMain { scheduleSweep() }
        }
        if (changed) rebuildAndInvalidate()
        return changed
    }

    private fun scheduleSweep() {
        if (sweepScheduled) return
        sweepScheduled = true
        mainHandler.postDelayed(sweepRunnable, GRACE_MS)
    }

    // ──────────────────────────────────────────────────────────────
    // La capa de dibujo
    // ──────────────────────────────────────────────────────────────

    private fun rebuildAndInvalidate() {
        // Foto inmutable de las regiones para que onDraw() la lea sin tomar locks.
        val snapshot: Array<Rect> = synchronized(lock) {
            if (regions.isEmpty()) {
                EMPTY_RECTS
            } else {
                val arr = Array(regions.size) { Rect() }
                var i = 0
                for (r in regions.values) arr[i++].set(r)
                arr
            }
        }
        drawList = snapshot

        onMain {
            val needed = snapshot.isNotEmpty()
            if (needed) {
                ensureOverlayAttached()
                overlayView?.invalidate()
            } else {
                detachOverlay()
            }
        }
    }

    private fun ensureOverlayAttached() {
        if (overlayView != null) return
        val view = RegionOverlayView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE es imprescindible: la capa cubre toda la pantalla, así que
            // sin esto el usuario no podría tocar nada de la app de abajo.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Coordenadas absolutas de pantalla: hacen que lo que dibujamos coincida
                // con lo que devuelve AccessibilityNodeInfo.getBoundsInScreen(), sin
                // corrimiento por barra de estado ni por el notch.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            android.util.Log.w("LockSuite_Overlay", "No se pudo crear la capa de tapado: ${e.message}")
        }
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // ignorado: puede haber sido quitada por el sistema
        }
    }

    private inner class RegionOverlayView(ctx: Context) : View(ctx) {

        private val fill = Paint().apply {
            color = FILL_COLOR
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        private val edge = Paint().apply {
            color = EDGE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 1f * ctx.resources.displayMetrics.density
            isAntiAlias = false
        }
        private val glyph = Paint().apply {
            color = 0xFF39424F.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * ctx.resources.displayMetrics.density
            isAntiAlias = true
        }

        init {
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
            // ⚠️ IMPRESCINDIBLE, y es la diferencia con la versión de ventanas chicas.
            //
            // Esta capa ocupa TODA la pantalla. Si apareciera en el árbol de
            // accesibilidad, `rootInActiveWindow` podría devolverla a ella en vez de la
            // app de abajo: el escaneo no encontraría ninguna imagen, se borrarían todas
            // las regiones, la capa se quitaría, el escaneo siguiente volvería a
            // encontrar las imágenes y la capa se crearía otra vez. Un ciclo de
            // parpadeo alimentado por el propio filtro.
            //
            // Con esto la ventana existe para el compositor pero es invisible para el
            // sistema de accesibilidad. (Hay una segunda red: `runCaptivePortalImageCover()`
            // corta si el paquete de la ventana raíz es el nuestro. Las dos, porque el
            // costo de equivocarse acá es un parpadeo permanente en pantalla.)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        override fun onDraw(canvas: Canvas) {
            val list = drawList
            for (r in list) {
                canvas.drawRect(r, fill)
                canvas.drawRect(
                    r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, edge
                )
                // Ícono de "contenido oculto" solo en recuadros grandes: en una miniatura
                // de 40 px sería ruido, y dibujarlo en todos costaría de más.
                if (r.width() >= GLYPH_MIN_PX && r.height() >= GLYPH_MIN_PX) {
                    val cx = r.exactCenterX()
                    val cy = r.exactCenterY()
                    val radius = (minOf(r.width(), r.height()) * 0.10f).coerceAtMost(44f)
                    canvas.drawCircle(cx, cy, radius, glyph)
                    canvas.drawLine(
                        cx - radius * 0.9f, cy + radius * 0.9f,
                        cx + radius * 0.9f, cy - radius * 0.9f,
                        glyph
                    )
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Pantalla negra de actualizacion de apps
    //
    // Esto es una ventana APARTE de la capa de regiones: es opaca, a pantalla
    // completa y tiene que absorber el 100% de los toques, así que no comparte
    // nada con lo de arriba.
    //
    // Cambios del 16/8/2026:
    //  • Titulo y subtitulo actualizables por separado, para poder ir contando
    //    en que etapa esta la actualizacion en vez de un texto fijo.
    //  • Boton "Cancelar" opcional. El fondo sigue consumiendo el 100% de los
    //    toques (setOnTouchListener del contenedor), pero los hijos reciben el
    //    toque antes que el padre, asi que el boton si responde.
    //  • showBlockingMessageOverlay() ya no se va en silencio si el overlay ya
    //    existe: refresca los textos.
    // ──────────────────────────────────────────────

    private var blockingCancelable = false
    private var blockingCancelAction: (() -> Unit)? = null
    @Volatile private var blockingView: android.widget.LinearLayout? = null
    /**
     * Últimos pasos del registro de la actualización (26/10/2026, B.97). Se guardan acá
     * para que, si la vista se rearma (cambia si es cancelable), no se pierdan.
     */
    private var blockingStepsText: String = ""
    /**
     * Porcentaje REAL de la descarga (0..100), o null si no se sabe (B.97). Con valor, la
     * ruedita se cambia por una barra que avanza: "que muestre exactamente el proceso".
     * Guardado acá por lo mismo que los pasos: la vista se puede rearmar.
     */
    private var blockingPct: Int? = null

    fun isBlockingMessageVisible(): Boolean = blockingView != null

    @JvmOverloads
    fun showBlockingMessageOverlay(
        title: String,
        subtitle: String? = null,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null
    ) {
        onMain {
            blockingCancelAction = onCancel

            val existing = blockingView
            if (existing != null) {
                if (blockingCancelable == cancelable) {
                    // Mismo formato: solo refrescar textos, sin recrear la ventana.
                    existing.findViewWithTag<android.widget.TextView>("title")?.text = title
                    existing.findViewWithTag<android.widget.TextView>("subtitle")?.text =
                        subtitle ?: "Por favor, no toque la pantalla..."
                    return@onMain
                }
                // Cambio si es cancelable o no: hay que rearmar la vista.
                removeBlockingView()
            }
            blockingCancelable = cancelable

            val density = service.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val view = object : android.widget.LinearLayout(service) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    // Consumir teclas físicas (Atrás, D-Pad, Buscar, Menú, Enter) para que nunca
                    // lleguen a Google Play debajo de la pantalla negra. Los toques táctiles sobre
                    // el botón Cancelar van por dispatchTouchEvent y siguen respondiendo igual.
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_SEARCH,
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> true
                        else -> super.dispatchKeyEvent(event)
                    }
                }
            }.apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#090A0F")) // opaco 100%
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(24), dp(48), dp(24), dp(48))
                // Consume cualquier toque que no haya agarrado un hijo (el boton).
                setOnTouchListener { _, _ -> true }

                addView(android.widget.TextView(service).apply {
                    tag = "title"
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), dp(12))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })

                addView(android.widget.TextView(service).apply {
                    tag = "subtitle"
                    text = subtitle ?: "Por favor, no toque la pantalla..."
                    setTextColor(Color.parseColor("#8E9AA8"))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), dp(24))
                })

                addView(android.widget.ProgressBar(service).apply {
                    tag = "spinner"
                    isIndeterminate = true
                    visibility = if (blockingPct == null) android.view.View.VISIBLE else android.view.View.GONE
                })

                // B.97 — la barra con el porcentaje real, cuando se sabe (sesión de
                // instalación de Google Play o barra con valor en la ficha).
                addView(android.widget.ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
                    tag = "pct"
                    isIndeterminate = false
                    max = 100
                    progress = blockingPct ?: 0
                    visibility = if (blockingPct == null) android.view.View.GONE else android.view.View.VISIBLE
                }, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(32); rightMargin = dp(32) })

                // B.97 — qué está pasando, paso a paso: las últimas líneas del registro de
                // la actualización ("+12s › Apreté «Actualizar»"). El dueño pidió ver
                // EXACTAMENTE el proceso; la línea de arriba dice en qué etapa está, esto
                // dice cómo llegó ahí. Chico y gris a propósito: es para quien quiera
                // mirarlo, no para tapar el mensaje principal.
                addView(android.widget.TextView(service).apply {
                    tag = "steps"
                    text = blockingStepsText
                    visibility = if (blockingStepsText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    setTextColor(Color.parseColor("#6B7A8C"))
                    textSize = 11.5f
                    gravity = Gravity.START
                    maxLines = 6
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(20), dp(8), 0)
                }, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                if (cancelable) {
                    addView(android.widget.Button(service).apply {
                        tag = "cancel"
                        text = "Cancelar"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        // El fondo va ANTES del padding: cambiar el background de
                        // una View puede reescribir el padding, así que al revés
                        // el botón quedaba sin márgenes internos.
                        setBackgroundColor(Color.parseColor("#33405A"))
                        setPadding(dp(28), dp(10), dp(28), dp(10))
                        setOnClickListener {
                            // Desarmar de inmediato para que un doble toque no
                            // dispare la cancelacion dos veces.
                            isEnabled = false
                            text = "Cancelando..."
                            val action = blockingCancelAction
                            blockingCancelAction = null
                            try {
                                action?.invoke()
                            } catch (e: Exception) {
                                android.util.Log.e("LockSuite_Overlay", "Error al cancelar: ${e.message}", e)
                            }
                        }
                    }, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(36) })
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Sin FLAG_NOT_FOCUSABLE: la ventana absorbe teclas físicas (Atrás, D-Pad, Buscar,
                // Menú, Enter) en dispatchKeyEvent para impedir escapar hacia Play Store.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                windowManager.addView(view, params)
                view.requestFocus()
                blockingView = view
                android.util.Log.i("LockSuite_Overlay", "Overlay de actualizacion mostrado (cancelable=$cancelable)")
            } catch (e: Exception) {
                android.util.Log.e("LockSuite_Overlay", "Error al agregar overlay de actualizacion: ${e.message}", e)
            }
        }
    }

    /** Actualiza titulo y/o subtitulo de la pantalla negra si esta visible. */
    fun updateBlockingMessage(title: String?, subtitle: String?) {
        onMain {
            val overlay = blockingView ?: return@onMain
            if (title != null) overlay.findViewWithTag<android.widget.TextView>("title")?.text = title
            if (subtitle != null) overlay.findViewWithTag<android.widget.TextView>("subtitle")?.text = subtitle
        }
    }

    fun updateBlockingMessageSubtitle(subtitle: String) = updateBlockingMessage(null, subtitle)

    /** Las últimas líneas del registro paso a paso (B.97). Se puede llamar desde cualquier hilo. */
    fun updateBlockingSteps(text: String) {
        onMain {
            blockingStepsText = text
            val tv = blockingView?.findViewWithTag<android.widget.TextView>("steps") ?: return@onMain
            tv.text = text
            tv.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    /**
     * El porcentaje real de la descarga (0..100), o null para volver a la ruedita (B.97).
     * Se puede llamar desde cualquier hilo y tantas veces como se quiera: si no cambió,
     * no toca la vista.
     */
    fun updateBlockingProgress(percent: Int?) {
        onMain {
            val pct = percent?.coerceIn(0, 100)
            if (pct == blockingPct) return@onMain
            blockingPct = pct
            val overlay = blockingView ?: return@onMain
            overlay.findViewWithTag<android.widget.ProgressBar>("pct")?.let {
                if (pct != null) it.progress = pct
                it.visibility = if (pct == null) android.view.View.GONE else android.view.View.VISIBLE
            }
            overlay.findViewWithTag<android.widget.ProgressBar>("spinner")?.visibility =
                if (pct == null) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    fun hideBlockingMessageOverlay() {
        onMain {
            blockingCancelAction = null
            blockingCancelable = false
            blockingPct = null
            removeBlockingView()
        }
    }

    /** Se llama al cerrar un flujo: la próxima pantalla negra arranca sin pasos ni barra viejos. */
    fun clearBlockingSteps() {
        onMain {
            blockingStepsText = ""
            blockingPct = null
        }
    }

    private fun removeBlockingView() {
        val view = blockingView ?: return
        blockingView = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // ignorado
        }
    }

    /** Saca todo de pantalla: regiones de portal cautivo y la pantalla de actualización. */
    fun clearAll() {
        synchronized(lock) {
            regions.clear()
            staged.clear()
            pendingRemoval.clear()
        }
        drawList = EMPTY_RECTS
        onMain {
            mainHandler.removeCallbacks(sweepRunnable)
            sweepScheduled = false
            detachOverlay()
            removeBlockingView()
            blockingCancelAction = null
            blockingCancelable = false
            blockingPct = null
        }
    }
}
