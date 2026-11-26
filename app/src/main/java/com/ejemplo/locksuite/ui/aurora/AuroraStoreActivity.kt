package com.ejemplo.locksuite.ui.aurora

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ejemplo.locksuite.mdm.AppRequestManager
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog.ItemAction
import com.ejemplo.locksuite.mdm.AuroraStoreManager
import com.ejemplo.locksuite.mdm.PlayStoreCatalog
import com.ejemplo.locksuite.mdm.PlayStoreManager
import com.ejemplo.locksuite.ui.playstore.PlayStoreIcons
import com.ejemplo.locksuite.ui.playstore.PlayStoreRows
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.LocaleManager
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.StoreInstallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TIENDA AURORA (B.132) — tercera tienda administrada de LockSuite.
 *
 * Muestra exactamente las mismas apps y niveles que Tienda Play (`PlayStoreManager`), pero
 * descarga e instala/actualiza los APK / Split APK oficiales de Google Play en silencio
 * desde el caché verificado de LockSuite en Cloud Storage (sin abrir Play Store ni tapar la
 * pantalla con overlay). No expone ningún acceso a Play Store ni a Aurora Store real.
 */
class AuroraStoreActivity : Activity() {

    companion object {
        private const val TAG = "AuroraStoreActivity"
        private const val REFRESH_DEBOUNCE_MS = 250L
        private const val REQUESTS_REFRESH_MS = 20_000L
        private const val KEY_LAUNCHER_VERSION = "launcher_packages_version"

        fun intent(context: Context): Intent =
            Intent(context, AuroraStoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private class Row(
        val app: PlayStoreCatalog.StoreApp,
        val label: String,
        val installed: Boolean,
        val installedVc: Long,
        val pausedByStore: Boolean,
        val variant: AuroraStoreCatalog.AuroraVariant?
    )

    private class Snapshot(val open: Boolean, val mode: String, val rows: List<Row>)

    // Paleta Aurora (fondo oscuro consistente con launcher kosher + acento cian/índigo)
    private val colorBgTop = 0xFF0A1424.toInt()
    private val colorBgBottom = 0xFF070C14.toInt()
    private val colorCard = 0xFF162235.toInt()
    private val colorTextPrimary = 0xFFFFFFFF.toInt()
    private val colorTextSecondary = 0xFF9FB4CE.toInt()
    private val colorCyan = 0xFF06B6D4.toInt()
    private val colorCyanText = 0xFF032029.toInt()
    private val colorEmerald = 0xFF10B981.toInt()
    private val colorEmeraldText = 0xFF032116.toInt()
    private val colorBlue = 0xFF3B82F6.toInt()
    private val colorAmber = 0xFFF5C438.toInt()
    private val colorAmberText = 0xFF1F1300.toInt()
    private val colorDisabled = 0xFF334155.toInt()
    private val colorDisabledText = 0xFF94A3B8.toInt()
    private val colorBadge = 0xFF12364C.toInt()
    private val colorBadgeText = 0xFF67E8F9.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var uiScope: CoroutineScope? = null

    private var lang = AuroraStoreText.LANG_ES
    private var rtl = false

    private lateinit var modeBadge: TextView
    private lateinit var hint: TextView
    private lateinit var updateAllBtn: TextView
    private lateinit var searchBox: EditText
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    private var generation = 0
    private var lastSignature = ""
    private var snapshot: Snapshot? = null
    private var filterQuery = ""
    private var installingPkg: String? = null
    private var installingProgress = 0

    private var requests: List<AppRequestManager.Solicitud> = emptyList()
    private val sentHere = ArrayList<AppRequestManager.Solicitud>()
    private var requestsLoadedAt = 0L

    private val refreshRunnable = Runnable { refresh() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("play_store_") || key.startsWith("aurora_store_") || key == KEY_LAUNCHER_VERSION)) {
            scheduleRefresh(REFRESH_DEBOUNCE_MS)
        }
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lastSignature = ""
            scheduleRefresh(150L)
        }
    }
    private var pkgReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = AuroraStoreText.normalizeLang(LocaleManager.getLang())
        rtl = AuroraStoreText.isRtl(lang)
        worker = Executors.newSingleThreadExecutor()
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        setContentView(buildContent())
        observeInstallState()
    }

    override fun onStart() {
        super.onStart()
        try {
            PrefsHelper.getMdmPrefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar listener de prefs: ${e.message}")
        }
        if (!pkgReceiverRegistered) {
            try {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
                registerReceiver(pkgReceiver, f)
                pkgReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        pullFreshCatalogFromCloud()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refresh()
        pullFreshCatalogFromCloud()
    }

    override fun onStop() {
        try {
            PrefsHelper.getMdmPrefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (_: Exception) {
        }
        if (pkgReceiverRegistered) {
            try {
                unregisterReceiver(pkgReceiver)
            } catch (_: Exception) {
            }
            pkgReceiverRegistered = false
        }
        ui.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        uiScope?.cancel()
        uiScope = null
        worker?.shutdownNow()
        worker = null
        super.onDestroy()
    }

    private fun observeInstallState() {
        uiScope?.launch {
            StoreInstallManager.state.collect { st ->
                val newPkg = if (st.key != null && st.key != StoreInstallManager.SELF_KEY) st.key else null
                if (newPkg != installingPkg || (newPkg != null && st.progress != installingProgress)) {
                    installingPkg = newPkg
                    installingProgress = st.progress
                    renderCurrent()
                }
            }
        }
    }

    private fun pullFreshCatalogFromCloud() {
        val ctx = applicationContext
        val exec = worker ?: return
        try {
            exec.execute {
                val changed = FirebaseDeviceSync.pullPlayStoreConfig(ctx)
                if (changed) {
                    ui.post {
                        if (!isFinishing && !isDestroyed) {
                            lastSignature = ""
                            refresh()
                        }
                    }
                } else {
                    ui.post {
                        if (!isFinishing && !isDestroyed) refresh()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildContent(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colorBgTop, colorBgBottom)
        )

        // Encabezado
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
        back.contentDescription = AuroraStoreText.t(AuroraStoreText.Key.BACK, lang)
        back.background = focusOnly(dp(22))
        back.setOnClickListener { finish() }
        header.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = TextView(this)
        title.text = AuroraStoreText.t(AuroraStoreText.Key.TITLE, lang)
        title.textSize = 18f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(colorTextPrimary)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val reloadBtn = TextView(this)
        reloadBtn.text = "↻"
        reloadBtn.textSize = 18f
        reloadBtn.setTextColor(colorBadgeText)
        reloadBtn.gravity = Gravity.CENTER
        reloadBtn.minWidth = dp(38)
        reloadBtn.minHeight = dp(38)
        reloadBtn.isClickable = true
        reloadBtn.isFocusable = true
        reloadBtn.contentDescription = AuroraStoreText.t(AuroraStoreText.Key.REFRESH, lang)
        reloadBtn.background = focusOnly(dp(19))
        reloadBtn.setOnClickListener {
            pullFreshCatalogFromCloud()
            refresh()
        }
        header.addView(reloadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        modeBadge = TextView(this)
        modeBadge.textSize = 11f
        modeBadge.typeface = Typeface.DEFAULT_BOLD
        modeBadge.setTextColor(colorBadgeText)
        modeBadge.setPaddingRelative(dp(10), dp(4), dp(10), dp(4))
        modeBadge.background = rounded(colorBadge, dp(10))
        modeBadge.visibility = View.GONE
        val badgeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        badgeLp.marginStart = dp(4)
        header.addView(modeBadge, badgeLp)

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        hint = TextView(this)
        hint.text = AuroraStoreText.t(AuroraStoreText.Key.HINT, lang)
        hint.textSize = 12f
        hint.setTextColor(colorTextSecondary)
        hint.setPaddingRelative(dp(16), 0, dp(16), dp(6))
        hint.visibility = View.GONE
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Botón "Actualizar todas (N)" cuando hay apps instaladas con nueva versión
        updateAllBtn = TextView(this)
        updateAllBtn.textSize = 13f
        updateAllBtn.typeface = Typeface.DEFAULT_BOLD
        updateAllBtn.setTextColor(colorCyanText)
        updateAllBtn.gravity = Gravity.CENTER
        updateAllBtn.setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        updateAllBtn.background = buttonBackground(colorCyan)
        updateAllBtn.visibility = View.GONE
        updateAllBtn.isClickable = true
        updateAllBtn.isFocusable = true
        updateAllBtn.setOnClickListener { onUpdateAllClicked() }
        val uaLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        uaLp.setMargins(dp(12), dp(2), dp(12), dp(8))
        root.addView(updateAllBtn, uaLp)

        // Buscador
        searchBox = EditText(this)
        searchBox.hint = AuroraStoreText.t(AuroraStoreText.Key.SEARCH_HINT, lang)
        searchBox.setHintTextColor(colorDisabledText)
        searchBox.setTextColor(colorTextPrimary)
        searchBox.textSize = 14f
        searchBox.isSingleLine = true
        searchBox.setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
        searchBox.background = rounded(colorCard, dp(10))
        searchBox.visibility = View.GONE
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s?.toString()?.trim()?.lowercase() ?: ""
                renderCurrent()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val sbLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sbLp.setMargins(dp(12), 0, dp(12), dp(8))
        root.addView(searchBox, sbLp)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPaddingRelative(dp(12), dp(4), dp(12), dp(16))

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

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
                        val paused = AuroraStoreManager.pausedByStore(ctx)
                        val abis = AuroraStoreManager.deviceAbis()
                        val serverPkgs = AuroraStoreManager.packages(ctx)
                        rows = PlayStoreManager.listed(ctx).map { app ->
                            val vc = AuroraStoreManager.installedVersionCode(ctx, app.packageName)
                            val installed = vc >= 0L
                            val label = if (installed) {
                                PlayStoreManager.installedLabel(ctx, app.packageName) ?: app.label
                            } else {
                                app.label
                            }
                            val variant = AuroraStoreCatalog.selectVariant(serverPkgs[app.packageName], abis)
                            Row(
                                app = app,
                                label = label,
                                installed = installed,
                                installedVc = vc,
                                pausedByStore = app.packageName in paused,
                                variant = variant
                            )
                        }.sortedWith(compareBy({ it.label.lowercase() }, { it.app.packageName }))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo leer Tienda Aurora; falla cerrado: ${e.message}")
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
        } catch (_: Exception) {
        }
    }

    private fun maybeLoadRequests(s: Snapshot) {
        val needed = s.rows.any { it.app.status == PlayStoreCatalog.STATUS_REQUEST }
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
        } catch (_: Exception) {
        }
    }

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
            updateAllBtn.visibility = View.GONE
            searchBox.visibility = View.GONE
            showMessage(AuroraStoreText.Key.CLOSED_TITLE, AuroraStoreText.Key.CLOSED_BODY)
            return
        }

        modeBadge.text = AuroraStoreText.modeLabel(s.mode, lang)
        modeBadge.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        searchBox.visibility = if (s.rows.size > 6 || filterQuery.isNotEmpty()) View.VISIBLE else View.GONE

        val updatableCount = s.rows.count { row ->
            row.app.status == PlayStoreCatalog.STATUS_ENABLED &&
                row.installed &&
                row.variant != null &&
                row.installedVc in 1L until row.variant.versionCode
        }
        if (updatableCount > 0 && installingPkg == null) {
            updateAllBtn.text = String.format(AuroraStoreText.t(AuroraStoreText.Key.UPDATE_ALL_FMT, lang), updatableCount)
            updateAllBtn.visibility = View.VISIBLE
        } else {
            updateAllBtn.visibility = View.GONE
        }

        val visibleRows = if (filterQuery.isEmpty()) {
            s.rows
        } else {
            s.rows.filter {
                it.label.lowercase().contains(filterQuery) ||
                    it.app.packageName.lowercase().contains(filterQuery)
            }
        }

        if (visibleRows.isEmpty()) {
            showMessage(AuroraStoreText.Key.EMPTY, null)
            return
        }

        list.gravity = Gravity.TOP
        for (row in visibleRows) {
            list.addView(buildRow(row, vigentes))
        }
        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun signature(s: Snapshot, vigentes: List<AppRequestManager.Solicitud>): String {
        val sb = StringBuilder()
        sb.append(s.open).append('|').append(s.mode).append('|').append(lang)
            .append('|').append(filterQuery)
            .append('|').append(installingPkg ?: "")
            .append('|').append(installingProgress)
        for (r in s.rows) {
            sb.append('\n')
                .append(r.app.packageName).append(':')
                .append(r.label).append(':')
                .append(r.installed).append(':')
                .append(r.installedVc).append(':')
                .append(r.pausedByStore).append(':')
                .append(r.variant?.versionCode ?: 0L).append(':')
                .append(r.variant?.sha256 ?: "").append(':')
                .append(PlayStoreRows.isRequested(vigentes, r.app.packageName)).append(':')
                .append(r.app.access).append(':')
                .append(r.app.status)
        }
        return sb.toString()
    }

    private fun showMessage(titleKey: AuroraStoreText.Key, bodyKey: AuroraStoreText.Key?) {
        list.gravity = Gravity.CENTER
        val title = TextView(this)
        title.text = AuroraStoreText.t(titleKey, lang)
        title.textSize = 18f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(colorTextPrimary)
        title.gravity = Gravity.CENTER
        list.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (bodyKey != null) {
            val body = TextView(this)
            body.text = AuroraStoreText.t(bodyKey, lang)
            body.textSize = 14f
            body.setTextColor(colorTextSecondary)
            body.gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(8)
            list.addView(body, params)
        }
    }

    private fun buildRow(row: Row, vigentes: List<AppRequestManager.Solicitud>): View {
        val app = row.app
        val requested = PlayStoreRows.isRequested(vigentes, app.packageName)
        val isThisInstalling = installingPkg == app.packageName
        val action = AuroraStoreCatalog.actionFor(
            status = app.status,
            installed = row.installed,
            installedVersionCode = row.installedVc,
            variant = row.variant,
            isInstalling = isThisInstalling,
            requested = requested,
            pausedByStore = row.pausedByStore
        )

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPaddingRelative(dp(10), dp(8), dp(10), dp(8))
        card.background = rounded(colorCard, dp(14))
        val cardParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardParams.bottomMargin = dp(8)
        card.layoutParams = cardParams

        if (row.installed && !row.pausedByStore) {
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { openApp(row) }
        }

        val iconParams = LinearLayout.LayoutParams(dp(46), dp(46))
        iconParams.marginEnd = dp(10)
        card.addView(buildIcon(row), iconParams)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL

        val name = TextView(this)
        name.text = row.label
        name.textSize = 15f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(colorTextPrimary)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        texts.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = buildSubtitle(row, action)
        if (subtitle.isNotEmpty()) {
            val meta = TextView(this)
            meta.text = subtitle
            meta.textSize = 11f
            meta.setTextColor(if (action == ItemAction.UPDATE) colorBadgeText else colorTextSecondary)
            meta.maxLines = 2
            meta.ellipsize = TextUtils.TruncateAt.END
            texts.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val buttonParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonParams.marginStart = dp(8)
        card.addView(buildButton(action, row), buttonParams)

        return card
    }

    private fun buildSubtitle(row: Row, action: ItemAction): String {
        val parts = ArrayList<String>(3)
        val tags = PlayStoreRows.tagsFor(
            row.app.access,
            row.app.status,
            row.installed,
            action == ItemAction.REQUEST || action == ItemAction.REQUESTED
        )
        val tagStr = PlayStoreRows.subtitle(tags, lang)
        if (tagStr.isNotEmpty()) parts.add(tagStr)

        val v = row.variant
        if (action == ItemAction.UPDATE && v != null) {
            val verName = v.versionName.ifEmpty { "v${v.versionCode}" }
            val sz = AuroraStoreCatalog.formatSizeMb(v.sizeBytes)
            parts.add("Nueva $verName" + if (sz.isNotEmpty()) " · $sz" else "")
        } else if (action == ItemAction.INSTALL && v != null) {
            val verName = v.versionName.ifEmpty { "v${v.versionCode}" }
            val sz = AuroraStoreCatalog.formatSizeMb(v.sizeBytes)
            parts.add(if (sz.isNotEmpty()) "$verName · $sz" else verName)
        } else if (action == ItemAction.OPEN && v != null) {
            parts.add(AuroraStoreText.t(AuroraStoreText.Key.INSTALLED_UP_TO_DATE, lang))
        } else if (action == ItemAction.UNAVAILABLE) {
            parts.add(AuroraStoreText.t(AuroraStoreText.Key.UNAVAILABLE, lang))
        }
        return parts.joinToString(" · ")
    }

    private fun buildIcon(row: Row): View {
        val pkg = row.app.packageName
        val size = dp(46)

        val box = FrameLayout(this)
        box.background = rounded(PlayStoreRows.tileColor(pkg), dp(12))

        val letter = TextView(this)
        letter.text = PlayStoreRows.initial(row.label)
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

        PlayStoreIcons.load(this, pkg, row.installed, row.app.iconUrl, size) { bitmap ->
            if (image.tag == pkg) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                letter.visibility = View.INVISIBLE
            }
        }
        return box
    }

    private fun buildButton(action: ItemAction, row: Row): TextView {
        val enabled = action == ItemAction.INSTALL ||
            action == ItemAction.UPDATE ||
            action == ItemAction.OPEN ||
            action == ItemAction.REQUEST ||
            action == ItemAction.UNAVAILABLE
        val label = when (action) {
            ItemAction.INSTALL -> AuroraStoreText.t(AuroraStoreText.Key.INSTALL, lang)
            ItemAction.UPDATE -> AuroraStoreText.t(AuroraStoreText.Key.UPDATE, lang)
            ItemAction.OPEN -> AuroraStoreText.t(AuroraStoreText.Key.OPEN, lang)
            ItemAction.REQUEST -> AuroraStoreText.t(AuroraStoreText.Key.REQUEST, lang)
            ItemAction.REQUESTED -> AuroraStoreText.t(AuroraStoreText.Key.REQUESTED, lang)
            ItemAction.UNAVAILABLE -> AuroraStoreText.t(AuroraStoreText.Key.UNAVAILABLE, lang)
            ItemAction.INSTALLING -> if (installingProgress in 1..99) {
                String.format(AuroraStoreText.t(AuroraStoreText.Key.INSTALLING_PCT_FMT, lang), installingProgress)
            } else {
                AuroraStoreText.t(AuroraStoreText.Key.INSTALLING, lang)
            }
        }

        val fill = when (action) {
            ItemAction.INSTALL -> colorEmerald
            ItemAction.UPDATE -> colorCyan
            ItemAction.OPEN -> colorBlue
            ItemAction.REQUEST -> colorAmber
            else -> colorDisabled
        }
        val textColor = when (action) {
            ItemAction.INSTALL -> colorEmeraldText
            ItemAction.UPDATE -> colorCyanText
            ItemAction.OPEN -> colorTextPrimary
            ItemAction.REQUEST -> colorAmberText
            else -> colorDisabledText
        }

        val button = TextView(this)
        button.text = label
        button.textSize = 13f
        button.typeface = Typeface.DEFAULT_BOLD
        button.setTextColor(textColor)
        button.gravity = Gravity.CENTER
        button.minWidth = dp(86)
        button.minHeight = dp(38)
        button.setPaddingRelative(dp(12), 0, dp(12), 0)
        button.contentDescription = "$label ${row.label}"
        button.isEnabled = enabled
        button.isClickable = enabled
        button.isFocusable = enabled
        if (action == ItemAction.UNAVAILABLE || action == ItemAction.INSTALLING || action == ItemAction.REQUESTED) {
            button.background = rounded(fill, dp(12))
            if (action == ItemAction.UNAVAILABLE) {
                button.setOnClickListener {
                    Toast.makeText(this, AuroraStoreText.t(AuroraStoreText.Key.UNAVAILABLE_TOAST, lang), Toast.LENGTH_LONG).show()
                }
            }
        } else {
            button.background = buttonBackground(fill)
            button.setOnClickListener {
                onAction(action, row)
            }
        }
        return button
    }

    private fun onAction(action: ItemAction, row: Row) {
        when (action) {
            ItemAction.INSTALL, ItemAction.UPDATE -> startSilentInstallOrUpdate(row)
            ItemAction.OPEN -> openApp(row)
            ItemAction.REQUEST -> requestApp(row)
            ItemAction.INSTALLING, ItemAction.REQUESTED, ItemAction.UNAVAILABLE -> Unit
        }
    }

    private fun startSilentInstallOrUpdate(row: Row) {
        val err = AuroraStoreManager.startInstallOrUpdate(applicationContext, row.app.packageName)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                String.format(AuroraStoreText.t(AuroraStoreText.Key.UPDATE_STARTED_FMT, lang), row.label),
                Toast.LENGTH_SHORT
            ).show()
        }
        lastSignature = ""
        renderCurrent()
    }

    private fun onUpdateAllClicked() {
        val (count, err) = AuroraStoreManager.startUpdateAll(applicationContext)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                String.format(AuroraStoreText.t(AuroraStoreText.Key.UPDATE_ALL_STARTED_FMT, lang), count),
                Toast.LENGTH_LONG
            ).show()
        }
        lastSignature = ""
        renderCurrent()
    }

    private fun openApp(row: Row) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(row.app.packageName)
            if (launch == null) {
                Toast.makeText(this, AuroraStoreText.t(AuroraStoreText.Key.OPEN_FAILED, lang), Toast.LENGTH_LONG).show()
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Toast.makeText(this, AuroraStoreText.t(AuroraStoreText.Key.OPEN_FAILED, lang), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestApp(row: Row) {
        val ctx = applicationContext
        val verdict = FirebaseDeviceSync.enviarSolicitudApp(
            ctx,
            row.app.packageName,
            row.label,
            null,
            PlayStoreManager.offeredPackages(ctx),
            vigentRequests(),
            AppRequestManager.FUENTE_PLAY
        )
        Toast.makeText(this, AppRequestManager.motivo(verdict, lang), Toast.LENGTH_LONG).show()
        if (verdict == AppRequestManager.Veredicto.OK) {
            val mine = AppRequestManager.Solicitud(
                packageName = row.app.packageName,
                label = row.label,
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

    private fun buttonBackground(fill: Int): StateListDrawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), roundedRing(fill, dp(12), colorTextPrimary, dp(2)))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(darker(fill), dp(12)))
        states.addState(intArrayOf(), rounded(fill, dp(12)))
        return states
    }

    private fun focusOnly(radiusPx: Int): StateListDrawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(0x33FFFFFF, radiusPx))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(0x22FFFFFF, radiusPx))
        states.addState(intArrayOf(), rounded(0x00000000, radiusPx))
        return states
    }
}
