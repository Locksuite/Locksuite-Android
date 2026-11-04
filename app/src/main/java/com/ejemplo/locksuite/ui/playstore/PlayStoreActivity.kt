package com.ejemplo.locksuite.ui.playstore

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ejemplo.locksuite.mdm.AppRequestManager
import com.ejemplo.locksuite.mdm.PlayStoreManager
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.LocaleManager
import com.ejemplo.locksuite.util.PrefsHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TIENDA PLAY (29/10/2026, B.101) — la pantalla.
 *
 * Muestra las apps que el administrador permitió para ESTE equipo (`PlayStoreManager.items`),
 * con el ícono y el nombre reales, y un botón por fila:
 *
 *  - **Instalar** → `PlayStoreManager.install` → el MISMO flujo de actualización de apps:
 *    pantalla negra con el progreso, Google Play abierto por debajo y la instalación
 *    permitida solo para ese paquete. Acá no hay ningún otro camino de instalación.
 *  - **Abrir** → la app ya está instalada.
 *  - **Pedir** → la app es "solo a pedido": el pedido le llega al administrador por el mismo
 *    canal de la tienda vieja (`devices/<id>/appRequests`, marcado `store: "play"`), y recién
 *    cuando lo aprueba aparece "Instalar".
 *  - **Instalando… / Pedida** → informativos, sin acción.
 *
 * ## Por qué Views armadas por código y no Compose
 * Es una pantalla de una lista y un botón, y las sesiones de IA de este proyecto no pueden
 * compilar con Gradle: el código de Views sí se puede comprobar de tipos contra el
 * `android.jar` real; el de Compose no. Es una decisión de verificabilidad, no de estilo.
 *
 * ## Reglas
 *  - **Fallar cerrado**: con la tienda cerrada muestra el cartel de "cerrada" y nada más,
 *    aunque la pantalla la haya abierto un widget viejo de la pantalla de inicio.
 *  - La pantalla no decide nada: todo sale de `PlayStoreManager` (que ya comprueba modo,
 *    estado de cada app y la lista blanca) y se vuelve a comprobar al tocar el botón.
 *  - Se redibuja sola cuando cambia la configuración o termina una instalación, y solo si
 *    algo cambió de verdad (sin parpadeos ni perder el lugar de la lista).
 *  - Hebreo: se arma de derecha a izquierda.
 */
class PlayStoreActivity : Activity() {

    companion object {
        private const val TAG = "PlayStoreActivity"

        /** Cuánto se espera para juntar varios cambios de configuración seguidos en un solo redibujado. */
        private const val REFRESH_DEBOUNCE_MS = 300L

        /** No se vuelven a leer los pedidos más seguido que esto. */
        private const val REQUESTS_REFRESH_MS = 20_000L

        /** Preferencia que `PlayStoreManager.notifyChanged` y `PackageReceiver` mueven ante cualquier cambio. */
        private const val KEY_LAUNCHER_VERSION = "launcher_packages_version"

        /** Intent para abrir la tienda desde el widget, el lanzador o cualquier otro lado. */
        fun intent(context: Context): Intent =
            Intent(context, PlayStoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private enum class Action { INSTALL, INSTALLING, OPEN, REQUEST, REQUESTED }

    private class Row(val item: PlayStoreManager.StoreItem, val installed: Boolean)

    private class Snapshot(val open: Boolean, val mode: String, val rows: List<Row>)

    // ── Colores (los del launcher kosher, para que se sienta parte del mismo equipo) ──
    private val colorBgTop = 0xFF0E141B.toInt()
    private val colorBgBottom = 0xFF080C11.toInt()
    private val colorCard = 0xFF1A222C.toInt()
    private val colorTextPrimary = 0xFFFFFFFF.toInt()
    private val colorTextSecondary = 0xFF9FB0C8.toInt()
    private val colorGreen = 0xFF22C55E.toInt()
    private val colorGreenText = 0xFF04210F.toInt()
    private val colorBlue = 0xFF3B82F6.toInt()
    private val colorAmber = 0xFFF5C438.toInt()
    private val colorAmberText = 0xFF1F1300.toInt()
    private val colorDisabled = 0xFF334155.toInt()
    private val colorDisabledText = 0xFF94A3B8.toInt()
    private val colorBadge = 0xFF1E3A5F.toInt()
    private val colorBadgeText = 0xFF93C5FD.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null

    private var lang = PlayStoreText.LANG_ES
    private var rtl = false

    private lateinit var modeBadge: TextView
    private lateinit var hint: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    /** Cada pedido de redibujado sube el número; solo el último resultado se aplica. */
    private var generation = 0
    private var lastSignature = ""
    private var snapshot: Snapshot? = null

    private var requests: List<AppRequestManager.Solicitud> = emptyList()
    private val sentHere = ArrayList<AppRequestManager.Solicitud>()
    private var requestsLoadedAt = 0L

    private val refreshRunnable = Runnable { refresh() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("play_store_") || key == KEY_LAUNCHER_VERSION)) {
            scheduleRefresh(REFRESH_DEBOUNCE_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = LocaleManager.getLang()
        rtl = PlayStoreText.isRtl(lang)
        worker = Executors.newSingleThreadExecutor()
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        try {
            PrefsHelper.getMdmPrefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo escuchar los cambios de la tienda: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refresh()
    }

    override fun onStop() {
        try {
            PrefsHelper.getMdmPrefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (_: Exception) {
        }
        ui.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        worker?.shutdownNow()
        worker = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ARMADO DE LA PANTALLA
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildContent(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colorBgTop, colorBgBottom)
        )

        // ── Encabezado: volver · título · modo ──
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPaddingRelative(dp(4), dp(6), dp(12), dp(4))

        val back = TextView(this)
        back.text = if (rtl) "→" else "←"
        back.textSize = 22f
        back.setTextColor(colorTextPrimary)
        back.gravity = Gravity.CENTER
        back.minWidth = dp(44)
        back.minHeight = dp(44)
        back.isClickable = true
        back.isFocusable = true
        back.contentDescription = PlayStoreText.t(PlayStoreText.Key.BACK, lang)
        back.background = focusOnly(dp(22))
        back.setOnClickListener { finish() }
        header.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = TextView(this)
        title.text = PlayStoreText.t(PlayStoreText.Key.TITLE, lang)
        title.textSize = 19f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(colorTextPrimary)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        modeBadge = TextView(this)
        modeBadge.textSize = 11f
        modeBadge.typeface = Typeface.DEFAULT_BOLD
        modeBadge.setTextColor(colorBadgeText)
        modeBadge.setPaddingRelative(dp(10), dp(4), dp(10), dp(4))
        modeBadge.background = rounded(colorBadge, dp(10))
        modeBadge.visibility = View.GONE
        header.addView(modeBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── Aclaración ──
        hint = TextView(this)
        hint.text = PlayStoreText.t(PlayStoreText.Key.HINT, lang)
        hint.textSize = 12f
        hint.setTextColor(colorTextSecondary)
        hint.setPaddingRelative(dp(16), 0, dp(16), dp(8))
        hint.visibility = View.GONE
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── Lista ──
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPaddingRelative(dp(12), dp(4), dp(12), dp(16))

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Pie discreto: Acceso a la tienda clásica de APKs directos ──
        val classicStoreBtn = TextView(this)
        classicStoreBtn.text = if (lang == PlayStoreText.LANG_HE) "📦 חנות קלאסית (הורדה ישירה)"
            else if (lang == PlayStoreText.LANG_EN) "📦 Classic Store (Direct APKs)"
            else "📦 Tienda clásica (APKs directos)"
        classicStoreBtn.textSize = 12f
        classicStoreBtn.setTextColor(colorTextSecondary)
        classicStoreBtn.gravity = Gravity.CENTER
        classicStoreBtn.setPaddingRelative(dp(16), dp(10), dp(16), dp(14))
        classicStoreBtn.isClickable = true
        classicStoreBtn.isFocusable = true
        classicStoreBtn.background = focusOnly(dp(8))
        classicStoreBtn.setOnClickListener {
            val intent = Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_OLD_STORE", true)
            }
            startActivity(intent)
        }
        root.addView(classicStoreBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return root
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DATOS: se leen en segundo plano, se pintan en el hilo principal
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleRefresh(delayMs: Long) {
        ui.removeCallbacks(refreshRunnable)
        ui.postDelayed(refreshRunnable, delayMs)
    }

    private fun refresh() {
        val gen = ++generation
        val ctx = applicationContext
        val exec = worker ?: return
        try {
            exec.execute {
                var open = false
                var mode = ""
                var rows: List<Row> = emptyList()
                try {
                    open = PlayStoreManager.isOpen(ctx)
                    mode = PlayStoreManager.mode(ctx)
                    if (open) {
                        rows = PlayStoreManager.items(ctx).map {
                            Row(it, PlayStoreManager.isInstalled(ctx, it.app.packageName))
                        }
                    }
                } catch (e: Exception) {
                    // Fallar cerrado: ante un error no se muestra nada que se pueda dudar.
                    Log.w(TAG, "No se pudo leer la tienda; se muestra cerrada: ${e.message}")
                    open = false
                    rows = emptyList()
                }
                val result = Snapshot(open, mode, rows)
                ui.post {
                    if (gen == generation && !isFinishing && !isDestroyed) {
                        snapshot = result
                        renderCurrent()
                        maybeLoadRequests(result)
                    }
                }
            }
        } catch (e: Exception) {
            // El ejecutor ya se cerró: la pantalla se está yendo.
        }
    }

    /** Los pedidos solo hacen falta si hay alguna app "a pedido" en la lista. */
    private fun maybeLoadRequests(s: Snapshot) {
        val needed = s.rows.any { it.item.state == PlayStoreManager.InstallState.REQUESTABLE }
        val now = System.currentTimeMillis()
        if (!needed || now - requestsLoadedAt < REQUESTS_REFRESH_MS) return
        requestsLoadedAt = now
        try {
            FirebaseDeviceSync.leerSolicitudesApp(applicationContext) { fetched ->
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        requests = PlayStoreRows.mergeRequests(fetched, sentHere, System.currentTimeMillis())
                        renderCurrent()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer los pedidos: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PINTADO
    // ─────────────────────────────────────────────────────────────────────────

    private fun vigentRequests(): List<AppRequestManager.Solicitud> =
        AppRequestManager.sinAprobacionesViejasDePlay(requests, System.currentTimeMillis())

    private fun renderCurrent() {
        val s = snapshot ?: return
        val vigentes = vigentRequests()
        val sig = signature(s, vigentes)
        if (sig == lastSignature) return
        lastSignature = sig

        val keepY = scroll.scrollY
        list.removeAllViews()

        if (!s.open) {
            modeBadge.visibility = View.GONE
            hint.visibility = View.GONE
            showMessage(PlayStoreText.Key.CLOSED_TITLE, PlayStoreText.Key.CLOSED_BODY)
            return
        }

        modeBadge.text = PlayStoreText.modeLabel(s.mode, lang)
        modeBadge.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE

        if (s.rows.isEmpty()) {
            showMessage(PlayStoreText.Key.EMPTY, null)
            return
        }

        list.gravity = Gravity.TOP
        for (row in s.rows) {
            list.addView(buildRow(row, vigentes))
        }
        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun signature(s: Snapshot, vigentes: List<AppRequestManager.Solicitud>): String {
        val sb = StringBuilder()
        sb.append(s.open).append('|').append(s.mode).append('|').append(lang)
        for (r in s.rows) {
            val app = r.item.app
            sb.append('\n')
                .append(app.packageName).append(':')
                .append(r.item.label).append(':')
                .append(r.item.state.name).append(':')
                .append(r.installed).append(':')
                .append(PlayStoreRows.isRequested(vigentes, app.packageName)).append(':')
                .append(app.access).append(':')
                .append(app.status).append(':')
                .append(app.iconUrl ?: "")
        }
        return sb.toString()
    }

    private fun showMessage(titleKey: PlayStoreText.Key, bodyKey: PlayStoreText.Key?) {
        list.gravity = Gravity.CENTER
        val title = TextView(this)
        title.text = PlayStoreText.t(titleKey, lang)
        title.textSize = 18f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(colorTextPrimary)
        title.gravity = Gravity.CENTER
        list.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (bodyKey != null) {
            val body = TextView(this)
            body.text = PlayStoreText.t(bodyKey, lang)
            body.textSize = 14f
            body.setTextColor(colorTextSecondary)
            body.gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(8)
            list.addView(body, params)
        }
    }

    private fun buildRow(row: Row, vigentes: List<AppRequestManager.Solicitud>): View {
        val item = row.item
        val app = item.app
        val requested = PlayStoreRows.isRequested(vigentes, app.packageName)
        val action = actionFor(item.state, requested)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPaddingRelative(dp(10), dp(8), dp(10), dp(8))
        card.background = rounded(colorCard, dp(14))
        val cardParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardParams.bottomMargin = dp(8)
        card.layoutParams = cardParams

        // Ícono
        val iconParams = LinearLayout.LayoutParams(dp(46), dp(46))
        iconParams.marginEnd = dp(10)
        card.addView(buildIcon(row), iconParams)

        // Nombre + aclaraciones
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL

        val name = TextView(this)
        name.text = item.label
        name.textSize = 15f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(colorTextPrimary)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        texts.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tags = PlayStoreRows.tagsFor(app.access, app.status, row.installed, item.state == PlayStoreManager.InstallState.REQUESTABLE)
        if (tags.isNotEmpty()) {
            val meta = TextView(this)
            meta.text = PlayStoreRows.subtitle(tags, lang)
            meta.textSize = 11f
            meta.setTextColor(colorTextSecondary)
            meta.maxLines = 2
            meta.ellipsize = TextUtils.TruncateAt.END
            texts.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Botón
        val buttonParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonParams.marginStart = dp(8)
        card.addView(buildButton(action, item), buttonParams)

        return card
    }

    private fun buildIcon(row: Row): View {
        val pkg = row.item.app.packageName
        val size = dp(46)

        val box = FrameLayout(this)
        box.background = rounded(PlayStoreRows.tileColor(pkg), dp(12))

        val letter = TextView(this)
        letter.text = PlayStoreRows.initial(row.item.label)
        letter.gravity = Gravity.CENTER
        letter.textSize = 20f
        letter.typeface = Typeface.DEFAULT_BOLD
        letter.setTextColor(colorTextPrimary)
        box.addView(letter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.visibility = View.GONE
        val radius = dp(12)
        image.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
            }
        }
        image.clipToOutline = true
        image.tag = pkg
        box.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        PlayStoreIcons.load(this, pkg, row.installed, row.item.app.iconUrl, size) { bitmap ->
            if (image.tag == pkg) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                letter.visibility = View.INVISIBLE
            }
        }
        return box
    }

    private fun actionFor(state: PlayStoreManager.InstallState, requested: Boolean): Action = when (state) {
        PlayStoreManager.InstallState.NOT_INSTALLED -> Action.INSTALL
        PlayStoreManager.InstallState.INSTALLING -> Action.INSTALLING
        PlayStoreManager.InstallState.INSTALLED -> Action.OPEN
        PlayStoreManager.InstallState.REQUESTABLE -> if (requested) Action.REQUESTED else Action.REQUEST
    }

    private fun buildButton(action: Action, item: PlayStoreManager.StoreItem): TextView {
        val enabled = action != Action.INSTALLING && action != Action.REQUESTED
        val textKey = when (action) {
            Action.INSTALL -> PlayStoreText.Key.INSTALL
            Action.INSTALLING -> PlayStoreText.Key.INSTALLING
            Action.OPEN -> PlayStoreText.Key.OPEN
            Action.REQUEST -> PlayStoreText.Key.REQUEST
            Action.REQUESTED -> PlayStoreText.Key.REQUESTED
        }
        val fill = when (action) {
            Action.INSTALL -> colorGreen
            Action.OPEN -> colorBlue
            Action.REQUEST -> colorAmber
            else -> colorDisabled
        }
        val textColor = when (action) {
            Action.INSTALL -> colorGreenText
            Action.OPEN -> colorTextPrimary
            Action.REQUEST -> colorAmberText
            else -> colorDisabledText
        }
        val label = PlayStoreText.t(textKey, lang)

        val button = TextView(this)
        button.text = label
        button.textSize = 13f
        button.typeface = Typeface.DEFAULT_BOLD
        button.setTextColor(textColor)
        button.gravity = Gravity.CENTER
        button.minWidth = dp(84)
        button.minHeight = dp(38)
        button.setPaddingRelative(dp(12), 0, dp(12), 0)
        button.contentDescription = label + " " + item.label
        button.isEnabled = enabled
        button.isClickable = enabled
        button.isFocusable = enabled
        if (enabled) {
            button.background = buttonBackground(fill)
            button.setOnClickListener {
                // Se apaga en el acto: dos toques seguidos no pueden mandar dos pedidos.
                button.isEnabled = false
                button.isClickable = false
                onAction(action, item)
            }
        } else {
            button.background = rounded(fill, dp(12))
        }
        return button
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCIONES
    // ─────────────────────────────────────────────────────────────────────────

    private fun onAction(action: Action, item: PlayStoreManager.StoreItem) {
        when (action) {
            Action.INSTALL -> install(item)
            Action.OPEN -> openApp(item)
            Action.REQUEST -> requestApp(item)
            Action.INSTALLING, Action.REQUESTED -> Unit
        }
    }

    private fun install(item: PlayStoreManager.StoreItem) {
        // Toda la comprobación (tienda abierta, app ofrecida, espacio, accesibilidad…) vive
        // en PlayStoreManager.install / UpdateFlowManager.startInstall. Devuelve null si el
        // flujo arrancó (la pantalla se tapa sola) o el motivo, ya escrito para el usuario.
        val error = PlayStoreManager.install(applicationContext, item.app.packageName)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
        lastSignature = ""
        scheduleRefresh(150L)
    }

    private fun openApp(item: PlayStoreManager.StoreItem) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(item.app.packageName)
            if (launch == null) {
                Toast.makeText(this, PlayStoreText.t(PlayStoreText.Key.OPEN_FAILED, lang), Toast.LENGTH_LONG).show()
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir ${item.app.packageName}: ${e.message}")
            Toast.makeText(this, PlayStoreText.t(PlayStoreText.Key.OPEN_FAILED, lang), Toast.LENGTH_LONG).show()
        }
        lastSignature = ""
        scheduleRefresh(150L)
    }

    private fun requestApp(item: PlayStoreManager.StoreItem) {
        val ctx = applicationContext
        val verdict = FirebaseDeviceSync.enviarSolicitudApp(
            ctx,
            item.app.packageName,
            item.label,
            null,
            PlayStoreManager.offeredPackages(ctx),
            vigentRequests(),
            AppRequestManager.FUENTE_PLAY
        )
        // Siempre se dice algo: un botón que no contesta es el bug de B.28/B.42/B.54.
        Toast.makeText(this, AppRequestManager.motivo(verdict, lang), Toast.LENGTH_LONG).show()
        if (verdict == AppRequestManager.Veredicto.OK) {
            val mine = AppRequestManager.Solicitud(
                packageName = item.app.packageName,
                label = item.label,
                estado = AppRequestManager.PENDIENTE,
                pedidaEnMs = System.currentTimeMillis(),
                fuente = AppRequestManager.FUENTE_PLAY
            )
            sentHere.removeAll { it.packageName == mine.packageName }
            sentHere.add(mine)
            requests = requests.filterNot { it.packageName == mine.packageName } + mine
        }
        lastSignature = ""
        renderCurrent()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ESTILO
    // ─────────────────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusPx: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusPx.toFloat()
        d.setColor(color)
        return d
    }

    private fun roundedRing(color: Int, radiusPx: Int, ringColor: Int, ringPx: Int): GradientDrawable {
        val d = rounded(color, radiusPx)
        d.setStroke(ringPx, ringColor)
        return d
    }

    private fun darker(color: Int): Int {
        val r = (Color.red(color) * 0.8f).toInt()
        val g = (Color.green(color) * 0.8f).toInt()
        val b = (Color.blue(color) * 0.8f).toInt()
        return Color.rgb(r, g, b)
    }

    /** Botón con estado enfocado (para teclas / cruceta), apretado y normal. */
    private fun buttonBackground(fill: Int): StateListDrawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), roundedRing(fill, dp(12), colorTextPrimary, dp(2)))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(darker(fill), dp(12)))
        states.addState(intArrayOf(), rounded(fill, dp(12)))
        return states
    }

    /** Fondo invisible que solo se marca cuando el elemento tiene el foco (cruceta). */
    private fun focusOnly(radiusPx: Int): StateListDrawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(0x33FFFFFF, radiusPx))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(0x22FFFFFF, radiusPx))
        states.addState(intArrayOf(), rounded(0x00000000, radiusPx))
        return states
    }
}
