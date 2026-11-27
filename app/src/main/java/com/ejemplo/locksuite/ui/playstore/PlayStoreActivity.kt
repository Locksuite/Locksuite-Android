package com.ejemplo.locksuite.ui.playstore

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
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
import com.ejemplo.locksuite.mdm.AndroidAutoStubInstaller
import com.ejemplo.locksuite.mdm.AndroidAutoStubs
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.AppRequestManager
import com.ejemplo.locksuite.mdm.AuroraStoreCatalog
import com.ejemplo.locksuite.mdm.AuroraStoreManager
import com.ejemplo.locksuite.mdm.PlayStoreCatalog
import com.ejemplo.locksuite.mdm.PlayStoreManager
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.mdm.PopularNonKosherApps
import com.ejemplo.locksuite.mdm.UnifiedStoreLogic
import com.ejemplo.locksuite.mdm.UnifiedStoreLogic.InstalledAppSnapshot
import com.ejemplo.locksuite.mdm.UnifiedStoreLogic.RowAction
import com.ejemplo.locksuite.mdm.UnifiedStoreLogic.StoreRowModel
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner
import com.ejemplo.locksuite.mdm.WhitelistManager
import com.ejemplo.locksuite.util.ApkSignatureVerifier
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.GoogleAccountGate
import com.ejemplo.locksuite.util.LocaleManager
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.StoreInstallManager
import com.ejemplo.locksuite.util.UpdateFlowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TIENDA ÚNICA DE LOCKSUITE (B.101 / B.134).
 *
 * Unifica instalación y actualización de apps:
 *   - Prioriza siempre la descarga silenciosa desde el caché multi-fuente del servidor
 *     (APKPure -> F-Droid -> Aptoide -> Aurora/Play).
 *   - Si no hay variante compatible o la firma instalada difiere o falla la descarga directa,
 *     ofrece respaldo por Google Play previa confirmación y advertencia clara al usuario
 *     (pantalla tapada + requisito de cuenta Google iniciada en el dispositivo).
 *   - Mantiene enlace discreto abajo para la Tienda clásica (APKs directos).
 */
class PlayStoreActivity : Activity() {

    companion object {
        private const val TAG = "PlayStoreActivity"
        private const val REFRESH_DEBOUNCE_MS = 280L
        private const val REQUESTS_REFRESH_MS = 20_000L
        private const val KEY_LAUNCHER_VERSION = "launcher_packages_version"

        fun intent(context: Context): Intent =
            Intent(context, PlayStoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private class Snapshot(val open: Boolean, val mode: String, val rows: List<StoreRowModel>)

    private val colorBgTop = 0xFF0E141B.toInt()
    private val colorBgBottom = 0xFF080C11.toInt()
    private val colorCard = 0xFF1A222C.toInt()
    private val colorSearch = 0xFF141B24.toInt()
    private val colorTextPrimary = 0xFFFFFFFF.toInt()
    private val colorTextSecondary = 0xFF9FB0C8.toInt()
    private val colorGreen = 0xFF22C55E.toInt()
    private val colorGreenText = 0xFF04210F.toInt()
    private val colorBlue = 0xFF3B82F6.toInt()
    private val colorAmber = 0xFFF5C438.toInt()
    private val colorAmberText = 0xFF1F1300.toInt()
    private val colorCyan = 0xFF06B6D4.toInt()
    private val colorCyanText = 0xFF032029.toInt()
    private val colorDisabled = 0xFF334155.toInt()
    private val colorDisabledText = 0xFF94A3B8.toInt()
    private val colorBadge = 0xFF1E3A5F.toInt()
    private val colorBadgeText = 0xFF93C5FD.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var uiScope: CoroutineScope? = null
    private var installStateJob: Job? = null
    private var installFailureJob: Job? = null
    private var aaNoticeJob: Job? = null

    private var lang = PlayStoreText.LANG_ES
    private var rtl = false

    private lateinit var reloadBtn: TextView
    private lateinit var modeBadge: TextView
    private lateinit var hint: TextView
    private lateinit var updateAllBar: TextView
    private lateinit var searchBox: EditText
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    private var generation = 0
    private var lastSignature = ""
    private var snapshot: Snapshot? = null
    private var filterQuery = ""
    private var activeSilentPkg: String? = null
    private var activeSilentPct: Int? = null
    private var syncingRemote = false

    private var requests: List<AppRequestManager.Solicitud> = emptyList()
    private val sentHere = ArrayList<AppRequestManager.Solicitud>()
    private var requestsLoadedAt = 0L

    private val refreshRunnable = Runnable { refresh() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (
                key.startsWith("play_store_") ||
                    key.startsWith("aurora_store_") ||
                    key == KEY_LAUNCHER_VERSION ||
                    key == UpdateFlowManager.KEY_PKG
                )
        ) {
            scheduleRefresh(REFRESH_DEBOUNCE_MS)
        }
    }

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
            Log.w(TAG, "No se pudo escuchar cambios de la tienda: ${e.message}")
        }
        startCollectors()
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
        stopCollectors()
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

    private fun startCollectors() {
        stopCollectors()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        installStateJob = scope.launch {
            StoreInstallManager.state.collect { st ->
                val newPkg = if (st.key == StoreInstallManager.SELF_KEY) null else st.key
                val newPct = if (newPkg != null) st.progress else null
                if (newPkg != activeSilentPkg || newPct != activeSilentPct) {
                    val pkgChanged = newPkg != activeSilentPkg
                    activeSilentPkg = newPkg
                    activeSilentPct = newPct
                    if (pkgChanged) {
                        scheduleRefresh(50L)
                    } else {
                        updateActiveButtonProgress(newPkg, newPct)
                    }
                }
            }
        }
        installFailureJob = scope.launch {
            StoreInstallManager.lastFailure.collect { failure ->
                if (failure != null && !isFinishing && !isDestroyed) {
                    StoreInstallManager.clearFailure(failure.id)
                    if (failure.allowPlayFallback) {
                        showSilentFailedPlayOfferDialog(failure)
                    }
                }
            }
        }
        aaNoticeJob = scope.launch {
            AndroidAutoStubInstaller.lastNotice.collect { notice ->
                if (notice != null && notice.isWarning && !isFinishing && !isDestroyed) {
                    AndroidAutoStubInstaller.clearNotice(notice.id)
                    showSimpleNoticeDialog(notice.title, notice.message)
                }
            }
        }
    }

    private fun stopCollectors() {
        installStateJob?.cancel()
        installFailureJob?.cancel()
        aaNoticeJob?.cancel()
        installStateJob = null
        installFailureJob = null
        aaNoticeJob = null
        uiScope?.cancel()
        uiScope = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERFAZ
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildContent(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colorBgTop, colorBgBottom)
        )

        // ── Encabezado: volver · título · recargar (↻) · modo ──
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

        reloadBtn = TextView(this)
        reloadBtn.text = "↻"
        reloadBtn.textSize = 19f
        reloadBtn.setTextColor(colorTextPrimary)
        reloadBtn.gravity = Gravity.CENTER
        reloadBtn.minWidth = dp(40)
        reloadBtn.minHeight = dp(40)
        reloadBtn.isClickable = true
        reloadBtn.isFocusable = true
        reloadBtn.contentDescription = PlayStoreText.t(PlayStoreText.Key.REFRESH, lang)
        reloadBtn.background = focusOnly(dp(20))
        reloadBtn.setOnClickListener { forceReloadCatalog() }
        val reloadLp = LinearLayout.LayoutParams(dp(40), dp(40))
        reloadLp.marginEnd = dp(6)
        header.addView(reloadBtn, reloadLp)

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
        hint.setPaddingRelative(dp(16), 0, dp(16), dp(6))
        hint.visibility = View.GONE
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── Banner Actualizar todas (N) ──
        updateAllBar = TextView(this)
        updateAllBar.textSize = 13f
        updateAllBar.typeface = Typeface.DEFAULT_BOLD
        updateAllBar.setTextColor(colorCyanText)
        updateAllBar.gravity = Gravity.CENTER
        updateAllBar.setPaddingRelative(dp(14), dp(9), dp(14), dp(9))
        updateAllBar.background = buttonBackground(colorCyan)
        updateAllBar.isClickable = true
        updateAllBar.isFocusable = true
        updateAllBar.visibility = View.GONE
        updateAllBar.setOnClickListener { onUpdateAllClicked() }
        val updAllParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        updAllParams.marginStart = dp(12)
        updAllParams.marginEnd = dp(12)
        updAllParams.bottomMargin = dp(8)
        root.addView(updateAllBar, updAllParams)

        // ── Buscador ──
        searchBox = EditText(this)
        searchBox.hint = PlayStoreText.t(PlayStoreText.Key.SEARCH_HINT, lang)
        searchBox.setHintTextColor(colorDisabledText)
        searchBox.setTextColor(colorTextPrimary)
        searchBox.textSize = 14f
        searchBox.setSingleLine(true)
        searchBox.background = rounded(colorSearch, dp(12))
        searchBox.setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString()?.trim().orEmpty()
                renderCurrent()
            }
        })
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchParams.marginStart = dp(12)
        searchParams.marginEnd = dp(12)
        searchParams.bottomMargin = dp(8)
        root.addView(searchBox, searchParams)

        // ── Lista ──
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPaddingRelative(dp(12), dp(2), dp(12), dp(14))

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Pie discreto: Tienda clásica (APKs directos) ──
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
    // RECARGA DE CATÁLOGO Y LECTURA EN SEGUNDO PLANO
    // ─────────────────────────────────────────────────────────────────────────

    private fun forceReloadCatalog() {
        if (syncingRemote) return
        syncingRemote = true
        reloadBtn.text = "…"
        reloadBtn.alpha = 0.6f
        reloadBtn.isEnabled = false
        Toast.makeText(this, PlayStoreText.t(PlayStoreText.Key.REFRESHING, lang), Toast.LENGTH_SHORT).show()

        val ctx = applicationContext
        val exec = worker ?: return
        try {
            exec.execute {
                val ok = try {
                    FirebaseDeviceSync.pullPlayStoreConfig(ctx)
                } catch (_: Exception) {
                    false
                }
                ui.post {
                    syncingRemote = false
                    if (!isFinishing && !isDestroyed) {
                        reloadBtn.text = "↻"
                        reloadBtn.alpha = 1f
                        reloadBtn.isEnabled = true
                        requestsLoadedAt = 0L
                        lastSignature = ""
                        refresh()
                        if (ok) {
                            Toast.makeText(this, PlayStoreText.t(PlayStoreText.Key.SYNC_DONE, lang), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            syncingRemote = false
            reloadBtn.text = "↻"
            reloadBtn.alpha = 1f
            reloadBtn.isEnabled = true
        }
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
                var mode = PlayStoreCatalog.MODE_CLOSED
                var rows: List<StoreRowModel> = emptyList()
                try {
                    open = PlayStoreManager.isOpen(ctx)
                    mode = PlayStoreManager.mode(ctx)
                    val catalogApps = if (open) PlayStoreManager.listed(ctx) else emptyList()
                    val serverPkgs = AuroraStoreManager.packages(ctx)
                    val abis = AuroraStoreManager.deviceAbis()
                    val installedSnapshots = collectInstalledSnapshots(ctx, serverPkgs)
                    val pausedByStore = AuroraStoreManager.pausedByStore(ctx)
                    val vigentReqPkgs = vigentRequests()
                        .filter {
                            it.estado == AppRequestManager.PENDIENTE || it.estado == AppRequestManager.APROBADA
                        }
                        .map { it.packageName }
                        .toSet()
                    val activePlay = try {
                        if (UpdateFlowManager.hasLiveFlow(ctx)) UpdateFlowManager.currentPackage(ctx) else null
                    } catch (_: Exception) {
                        null
                    }

                    rows = UnifiedStoreLogic.buildRows(
                        storeOpen = open,
                        catalogApps = catalogApps,
                        installedApps = installedSnapshots,
                        serverPackages = serverPkgs,
                        deviceAbis = abis,
                        requestedPackages = vigentReqPkgs,
                        pausedByStorePackages = pausedByStore,
                        activeSilentPkg = activeSilentPkg,
                        activeSilentProgress = activeSilentPct,
                        activePlayPkg = activePlay
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo armar la Tienda: ${e.message}", e)
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

    private fun collectInstalledSnapshots(
        ctx: Context,
        serverPackages: Map<String, AuroraStoreCatalog.AuroraPackage>
    ): List<InstalledAppSnapshot> {
        val pm = ctx.packageManager
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val policyManager = PolicyManager(ctx)
        val wlDecisions = try { WhitelistManager(ctx).allDecisions() } catch (_: Exception) { emptyMap() }
        val blockPopular = try { policyManager.isBlockPopularNonKosherEnabled() } catch (_: Exception) { false }
        val appController = AppController(ctx)

        val pkgFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES
        }
        val pkgs = try {
            pm.getInstalledPackages(pkgFlags)
        } catch (_: Exception) {
            emptyList()
        }

        val out = ArrayList<InstalledAppSnapshot>(pkgs.size)
        for (pi in pkgs) {
            val pkg = pi.packageName ?: continue
            val ai = pi.applicationInfo ?: continue
            val isActuallyInstalled = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            if (!isActuallyInstalled) continue

            val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            val vn = pi.versionName ?: ""
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isHidden = try { appController.isAppHidden(pkg) } catch (_: Exception) { prefs.getBoolean("hide_$pkg", false) }
            val osSuspended = (ai.flags and AppController.FLAG_SUSPENDED) != 0
            val isSuspended = osSuspended || try { appController.isAppSuspended(pkg) } catch (_: Exception) { false }
            val isBlocked = isHidden ||
                isSuspended ||
                wlDecisions[pkg] == WhitelistManager.STATE_BLOCK ||
                (blockPopular && PopularNonKosherApps.isPopularNonKosher(pkg)) ||
                pkg in AppController.DEFAULT_BLOCKED_PACKAGES
            val isStub = AndroidAutoStubs.isStub(ctx, pkg)
            val isCritical = if (pkg in UnifiedStoreLogic.UPDATABLE_SYSTEM_ALLOWLIST) {
                false
            } else {
                try { appController.isCritical(pkg) } catch (_: Exception) { false }
            }
            val label = try {
                pm.getApplicationLabel(ai).toString().trim().ifEmpty { pkg }
            } catch (_: Exception) {
                pkg
            }

            // Solo leemos la firma instalada cuando existe variante en servidor más nueva, para evitar I/O innecesario.
            val serverEntry = serverPackages[pkg]
            val signer = if (!isBlocked && !isStub && serverEntry != null && serverEntry.versionCode > vc) {
                ApkSignatureVerifier.installedSignerSha256(ctx, pkg)
            } else {
                null
            }

            out.add(
                InstalledAppSnapshot(
                    packageName = pkg,
                    label = label,
                    versionCode = vc,
                    versionName = vn,
                    isSystem = isSystem,
                    isUpdatedSystem = isUpdatedSystem,
                    isBlocked = isBlocked,
                    isCritical = isCritical,
                    isStub = isStub,
                    signerSha256 = signer
                )
            )
        }
        return out
    }

    private fun maybeLoadRequests(s: Snapshot) {
        val needed = s.rows.any { it.catalogStatus == PlayStoreCatalog.STATUS_REQUEST }
        val now = System.currentTimeMillis()
        if (!needed || now - requestsLoadedAt < REQUESTS_REFRESH_MS) return
        requestsLoadedAt = now
        try {
            FirebaseDeviceSync.leerSolicitudesApp(applicationContext) { fetched ->
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        requests = PlayStoreRows.mergeRequests(fetched, sentHere, System.currentTimeMillis())
                        scheduleRefresh(50L)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer pedidos: ${e.message}")
        }
    }

    private fun vigentRequests(): List<AppRequestManager.Solicitud> =
        AppRequestManager.sinAprobacionesViejasDePlay(requests, System.currentTimeMillis())

    // ─────────────────────────────────────────────────────────────────────────
    // RENDER
    // ─────────────────────────────────────────────────────────────────────────

    private fun renderCurrent() {
        val s = snapshot ?: return
        val q = filterQuery.lowercase(Locale.ROOT)
        val visibleRows = if (q.isEmpty()) {
            s.rows
        } else {
            s.rows.filter {
                it.label.lowercase(Locale.ROOT).contains(q) ||
                    it.packageName.lowercase(Locale.ROOT).contains(q)
            }
        }
        val updatable = UnifiedStoreLogic.silentUpdatableRows(s.rows)
        val sig = signature(s, visibleRows, updatable.size)
        if (sig == lastSignature) return
        lastSignature = sig

        val keepY = scroll.scrollY
        list.removeAllViews()

        if (!s.open && s.rows.isEmpty()) {
            modeBadge.visibility = View.GONE
            hint.visibility = View.GONE
            updateAllBar.visibility = View.GONE
            searchBox.visibility = View.GONE
            showMessage(PlayStoreText.Key.CLOSED_TITLE, PlayStoreText.Key.CLOSED_BODY)
            return
        }

        modeBadge.text = PlayStoreText.modeLabel(s.mode, lang)
        modeBadge.visibility = View.VISIBLE
        hint.text = if (s.open) {
            PlayStoreText.t(PlayStoreText.Key.HINT, lang)
        } else {
            PlayStoreText.t(PlayStoreText.Key.CLOSED_UPDATES_ONLY, lang)
        }
        hint.visibility = View.VISIBLE
        searchBox.visibility = View.VISIBLE

        if (updatable.isNotEmpty() && activeSilentPkg == null) {
            updateAllBar.text = String.format(
                Locale.US,
                PlayStoreText.t(PlayStoreText.Key.UPDATE_ALL_FMT, lang),
                updatable.size
            )
            updateAllBar.visibility = View.VISIBLE
        } else {
            updateAllBar.visibility = View.GONE
        }

        if (visibleRows.isEmpty()) {
            showMessage(PlayStoreText.Key.EMPTY, null)
            return
        }

        list.gravity = Gravity.TOP
        for (row in visibleRows) {
            list.addView(buildRow(row))
        }
        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun signature(s: Snapshot, visibleRows: List<StoreRowModel>, updatableCount: Int): String {
        val sb = StringBuilder()
        sb.append(s.open).append('|').append(s.mode).append('|').append(lang)
            .append('|').append(filterQuery).append('|').append(updatableCount)
            .append('|').append(activeSilentPkg ?: "")
        for (r in visibleRows) {
            sb.append('\n')
                .append(r.packageName).append(':')
                .append(r.label).append(':')
                .append(r.action.name).append(':')
                .append(r.installedVersionCode).append(':')
                .append(r.availableVersionCode).append(':')
                .append(r.source).append(':')
                .append(r.access).append(':')
                .append(r.iconUrl ?: "")
        }
        return sb.toString()
    }

    private fun updateActiveButtonProgress(pkg: String?, pct: Int?) {
        if (pkg == null) return
        val tag = "btn:$pkg"
        val btn = list.findViewWithTag<TextView>(tag) ?: return
        val row = snapshot?.rows?.firstOrNull { it.packageName == pkg }
        val baseLabel = if (row?.installed == true) {
            PlayStoreText.t(PlayStoreText.Key.UPDATING, lang)
        } else {
            PlayStoreText.t(PlayStoreText.Key.INSTALLING, lang)
        }
        btn.text = if (pct != null && pct in 0..100) "$pct%" else baseLabel
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

    private fun buildRow(row: StoreRowModel): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPaddingRelative(dp(10), dp(8), dp(10), dp(8))
        card.background = rounded(colorCard, dp(14))
        val cardParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardParams.bottomMargin = dp(8)
        card.layoutParams = cardParams

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

        val subtitleStr = buildSubtitle(row)
        if (subtitleStr.isNotEmpty()) {
            val meta = TextView(this)
            meta.text = subtitleStr
            meta.textSize = 11.5f
            meta.setTextColor(colorTextSecondary)
            meta.maxLines = 2
            meta.ellipsize = TextUtils.TruncateAt.END
            texts.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val buttonParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonParams.marginStart = dp(8)
        card.addView(buildButton(row), buttonParams)

        return card
    }

    private fun buildSubtitle(row: StoreRowModel): String {
        val parts = ArrayList<String>(4)
        when (row.action) {
            RowAction.UPDATE_SILENT, RowAction.UPDATE_PLAY -> {
                val vOld = row.installedVersionName.ifBlank { row.installedVersionCode.toString() }
                val vNew = row.availableVersionName.ifBlank { row.availableVersionCode.toString() }
                if (vOld.isNotBlank() && vNew.isNotBlank() && vOld != vNew) {
                    parts.add("v$vOld → v$vNew")
                } else if (vNew.isNotBlank()) {
                    parts.add("v$vNew")
                }
                val sizeTxt = AuroraStoreCatalog.formatSizeMb(row.sizeBytes)
                if (sizeTxt.isNotEmpty()) parts.add(sizeTxt)
                if (row.action == RowAction.UPDATE_PLAY) {
                    parts.add(PlayStoreText.t(PlayStoreText.Key.VIA_GOOGLE_PLAY, lang))
                } else {
                    val src = UnifiedStoreLogic.sourceDisplayName(row.source)
                    if (src.isNotEmpty()) parts.add(src)
                }
            }
            RowAction.OPEN_UP_TO_DATE -> {
                parts.add(PlayStoreText.t(PlayStoreText.Key.UP_TO_DATE, lang))
            }
            RowAction.INSTALL_SILENT, RowAction.INSTALL_PLAY -> {
                if (row.availableVersionName.isNotBlank()) parts.add("v${row.availableVersionName}")
                val sizeTxt = AuroraStoreCatalog.formatSizeMb(row.sizeBytes)
                if (sizeTxt.isNotEmpty()) parts.add(sizeTxt)
                if (row.action == RowAction.INSTALL_PLAY) {
                    parts.add(PlayStoreText.t(PlayStoreText.Key.VIA_GOOGLE_PLAY, lang))
                } else {
                    val src = UnifiedStoreLogic.sourceDisplayName(row.source)
                    if (src.isNotEmpty()) parts.add(src)
                }
            }
            RowAction.REQUEST, RowAction.REQUESTED, RowAction.INSTALLING -> Unit
        }

        val accessTags = PlayStoreRows.tagsFor(
            row.access,
            row.catalogStatus,
            row.installed,
            row.action == RowAction.REQUEST || row.action == RowAction.REQUESTED
        )
        val tagSub = PlayStoreRows.subtitle(accessTags, lang)
        if (tagSub.isNotEmpty()) parts.add(tagSub)

        return parts.joinToString(" · ")
    }

    private fun buildIcon(row: StoreRowModel): View {
        val pkg = row.packageName
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

        PlayStoreIcons.load(this, pkg, row.installed, row.iconUrl, size) { bitmap ->
            if (image.tag == pkg) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                letter.visibility = View.INVISIBLE
            }
        }
        return box
    }

    private fun buildButton(row: StoreRowModel): TextView {
        val enabled = row.action != RowAction.INSTALLING && row.action != RowAction.REQUESTED
        val label = when (row.action) {
            RowAction.INSTALL_SILENT, RowAction.INSTALL_PLAY -> PlayStoreText.t(PlayStoreText.Key.INSTALL, lang)
            RowAction.UPDATE_SILENT, RowAction.UPDATE_PLAY -> PlayStoreText.t(PlayStoreText.Key.UPDATE, lang)
            RowAction.OPEN_UP_TO_DATE -> PlayStoreText.t(PlayStoreText.Key.OPEN, lang)
            RowAction.REQUEST -> PlayStoreText.t(PlayStoreText.Key.REQUEST, lang)
            RowAction.REQUESTED -> PlayStoreText.t(PlayStoreText.Key.REQUESTED, lang)
            RowAction.INSTALLING -> {
                val pct = row.progressPct
                if (pct != null && pct in 0..100) "$pct%"
                else if (row.installed) PlayStoreText.t(PlayStoreText.Key.UPDATING, lang)
                else PlayStoreText.t(PlayStoreText.Key.INSTALLING, lang)
            }
        }
        val fill = when (row.action) {
            RowAction.INSTALL_SILENT, RowAction.INSTALL_PLAY -> colorGreen
            RowAction.UPDATE_SILENT, RowAction.UPDATE_PLAY -> colorAmber
            RowAction.OPEN_UP_TO_DATE -> colorBlue
            RowAction.REQUEST -> colorAmber
            else -> colorDisabled
        }
        val textColor = when (row.action) {
            RowAction.INSTALL_SILENT, RowAction.INSTALL_PLAY -> colorGreenText
            RowAction.UPDATE_SILENT, RowAction.UPDATE_PLAY -> colorAmberText
            RowAction.OPEN_UP_TO_DATE -> colorTextPrimary
            RowAction.REQUEST -> colorAmberText
            else -> colorDisabledText
        }

        val button = TextView(this)
        button.tag = "btn:${row.packageName}"
        button.text = label
        button.textSize = 13f
        button.typeface = Typeface.DEFAULT_BOLD
        button.setTextColor(textColor)
        button.gravity = Gravity.CENTER
        button.minWidth = dp(88)
        button.minHeight = dp(38)
        button.setPaddingRelative(dp(12), 0, dp(12), 0)
        button.contentDescription = "$label ${row.label}"
        button.isEnabled = enabled
        button.isClickable = enabled
        button.isFocusable = enabled
        if (enabled) {
            button.background = buttonBackground(fill)
            button.setOnClickListener {
                button.isEnabled = false
                button.isClickable = false
                onAction(row)
            }
        } else {
            button.background = rounded(fill, dp(12))
        }
        return button
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCIONES Y RESPALDO POR GOOGLE PLAY
    // ─────────────────────────────────────────────────────────────────────────

    private fun onAction(row: StoreRowModel) {
        when (row.action) {
            RowAction.INSTALL_SILENT -> {
                val err = AuroraStoreManager.startInstallOrUpdate(applicationContext, row.packageName)
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
                lastSignature = ""
                scheduleRefresh(120L)
            }
            RowAction.UPDATE_SILENT -> {
                val err = AuroraStoreManager.startSilentUpdate(applicationContext, row.packageName, row.variant, row.label)
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
                lastSignature = ""
                scheduleRefresh(120L)
            }
            RowAction.INSTALL_PLAY -> showPlayFallbackDialog(row.packageName, row.label, isInstall = true)
            RowAction.UPDATE_PLAY -> showPlayFallbackDialog(row.packageName, row.label, isInstall = false)
            RowAction.OPEN_UP_TO_DATE -> openApp(row)
            RowAction.REQUEST -> requestApp(row)
            RowAction.INSTALLING, RowAction.REQUESTED -> Unit
        }
    }

    private fun onUpdateAllClicked() {
        val rows = snapshot?.rows ?: return
        val (count, err) = AuroraStoreManager.startUpdateAllRows(applicationContext, rows)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Iniciando $count actualización(es) en silencio…", Toast.LENGTH_SHORT).show()
        }
        lastSignature = ""
        scheduleRefresh(150L)
    }

    private fun showPlayFallbackDialog(
        packageName: String,
        label: String,
        isInstall: Boolean,
        extraPrefix: String? = null
    ) {
        val acctState = GoogleAccountGate.state(this)
        val sb = StringBuilder()
        if (!extraPrefix.isNullOrBlank()) {
            sb.append(extraPrefix).append("\n\n")
        }
        sb.append(PlayStoreText.t(PlayStoreText.Key.PLAY_FALLBACK_BODY, lang))
        when (acctState) {
            GoogleAccountGate.State.NO_ACCOUNT -> {
                sb.append("\n\n").append(PlayStoreText.t(PlayStoreText.Key.PLAY_FALLBACK_NO_ACCOUNT, lang))
            }
            GoogleAccountGate.State.BLOCKED -> {
                sb.append("\n\n").append(PlayStoreText.t(PlayStoreText.Key.PLAY_FALLBACK_ACCOUNT_BLOCKED, lang))
            }
            else -> Unit
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("${PlayStoreText.t(PlayStoreText.Key.PLAY_FALLBACK_TITLE, lang)}: $label")
            .setMessage(sb.toString())
            .setNegativeButton(PlayStoreText.t(PlayStoreText.Key.CANCEL, lang)) { _, _ ->
                lastSignature = ""
                scheduleRefresh(100L)
            }
            .setPositiveButton(PlayStoreText.t(PlayStoreText.Key.PLAY_FALLBACK_CONTINUE, lang)) { _, _ ->
                launchPlayFlow(packageName, isInstall)
            }

        if (acctState == GoogleAccountGate.State.NO_ACCOUNT) {
            builder.setNeutralButton(PlayStoreText.t(PlayStoreText.Key.PLAY_FALLBACK_ADD_ACCOUNT, lang)) { _, _ ->
                GoogleAccountGate.startSignIn(this)
            }
        }
        builder.setOnCancelListener {
            lastSignature = ""
            scheduleRefresh(100L)
        }
        builder.show()
    }

    private fun showSilentFailedPlayOfferDialog(failure: StoreInstallManager.InstallFailure) {
        val prefix = "${failure.label}: ${failure.error}\n\n${PlayStoreText.t(PlayStoreText.Key.SILENT_FAILED_PLAY_PROMPT, lang)}"
        showPlayFallbackDialog(
            packageName = failure.packageName,
            label = failure.label,
            isInstall = !failure.isUpdate,
            extraPrefix = prefix
        )
    }

    private fun showSimpleNoticeDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(PlayStoreText.t(PlayStoreText.Key.OK, lang), null)
            .show()
    }

    private fun launchPlayFlow(packageName: String, isInstall: Boolean) {
        val error = if (isInstall) {
            PlayStoreManager.install(applicationContext, packageName)
        } else {
            UpdateFlowManager.start(
                context = applicationContext,
                packageName = packageName,
                source = UpdateFlowManager.SOURCE_LOCAL,
                cancelable = true,
                routePref = UpdateRoutePlanner.PREF_PLAY
            )
        }
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
        lastSignature = ""
        scheduleRefresh(150L)
    }

    private fun openApp(row: StoreRowModel) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(row.packageName)
            if (launch == null) {
                Toast.makeText(this, PlayStoreText.t(PlayStoreText.Key.OPEN_FAILED, lang), Toast.LENGTH_LONG).show()
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir ${row.packageName}: ${e.message}")
            Toast.makeText(this, PlayStoreText.t(PlayStoreText.Key.OPEN_FAILED, lang), Toast.LENGTH_LONG).show()
        }
        lastSignature = ""
        scheduleRefresh(150L)
    }

    private fun requestApp(row: StoreRowModel) {
        val ctx = applicationContext
        val verdict = FirebaseDeviceSync.enviarSolicitudApp(
            ctx,
            row.packageName,
            row.label,
            null,
            PlayStoreManager.offeredPackages(ctx),
            vigentRequests(),
            AppRequestManager.FUENTE_PLAY
        )
        Toast.makeText(this, AppRequestManager.motivo(verdict, lang), Toast.LENGTH_LONG).show()
        if (verdict == AppRequestManager.Veredicto.OK) {
            val mine = AppRequestManager.Solicitud(
                packageName = row.packageName,
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
        scheduleRefresh(50L)
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
