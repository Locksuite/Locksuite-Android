package com.ejemplo.locksuite.admin

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.net.URI
import java.util.Locale
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

/**
 * Cáscara nativa del panel web de LockSuite, pensada para administrar desde un celular
 * que NO tiene navegador (celular kosher).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REGLA DE ORO DE ESTE ARCHIVO — leer antes de tocar nada
 * ─────────────────────────────────────────────────────────────────────────────
 * Un WebView SIN restricciones es un navegador completo, solo que sin barra de
 * direcciones. Con un solo enlace externo (o un redirect, o un iframe) el equipo deja
 * de ser kosher. Por eso la navegación NO es "permitir todo menos lo prohibido" sino
 * al revés: **lista blanca cerrada**, y todo lo que no esté explícitamente listado se
 * bloquea.
 *
 * Hay DOS listas blancas y son distintas a propósito:
 *
 *   1. [NAV_ALLOWED_HOSTS]      — a qué páginas puede NAVEGAR el WebView (lo que el
 *                                 usuario "ve" como estar en un sitio). Muy corta.
 *   2. [RESOURCE_ALLOWED_*]     — de dónde puede bajar subrecursos (JS, XHR, imágenes,
 *                                 fuentes). Más larga, pero cargar una imagen de un
 *                                 dominio NO es poder navegar a ese dominio.
 *
 * Un host que está solo en la lista 2 no se puede visitar: si la página intenta
 * navegar ahí, [WebViewClient.shouldOverrideUrlLoading] lo corta igual.
 *
 * Todo lo que se bloquea queda logueado con el tag [TAG]. Si algo del panel deja de
 * funcionar (típicamente el login con Google, que toca varios dominios de Google),
 * el diagnóstico es de diez segundos:
 *
 *     adb logcat -s LockSuiteAdmin
 *
 * y el host que aparezca en "BLOQUEADO" se agrega a la lista que corresponda.
 * NO desactivar el filtro entero para "probar si era eso".
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LockSuiteAdmin"

        private const val PANEL_HOST = "locksuite-nueva.web.app"
        private const val ADMIN_PANEL_URL = "https://$PANEL_HOST"

        /**
         * Hosts a los que se permite NAVEGAR. Coincidencia EXACTA, nunca por sufijo:
         * un sufijo tipo ".google.com" abriría media web (support.google.com,
         * news.google.com, translate.google.com…), que es exactamente lo que hay que
         * evitar.
         *
         * - locksuite-nueva.web.app / .firebaseapp.com : el panel (Firebase Hosting
         *   sirve el mismo contenido en los dos dominios; el segundo además hospeda
         *   el "auth handler" del login de Firebase).
         * - accounts.google.com : necesario para "Iniciar sesión con Google".
         *   Sus enlaces de pie de página (Ayuda / Privacidad / Condiciones) apuntan a
         *   support.google.com y policies.google.com, que NO están en esta lista, así
         *   que quedan bloqueados y desde ahí no se sale a la web abierta.
         */
        private val PANEL_ALLOWED_HOSTS = setOf(
            "locksuite-nueva.web.app",
            "locksuite-nueva.firebaseapp.com"
        )

        private val NAV_ALLOWED_HOSTS = setOf(
            "locksuite-nueva.web.app",
            "locksuite-nueva.firebaseapp.com",
            "accounts.google.com"
        )

        private val BLOCKED_ACCOUNTS_PATH_PREFIXES = listOf(
            "/signup",
            "/manageaccount",
            "/signin/recovery",
            "/recover",
            "/lifecycle/steps/signup",
            "/v3/signin/recovery",
            "/createaccount",
            "/myaccount",
            "/b/"
        )

        private val ALLOWED_RASTER_DATA_IMAGE_REGEX = Regex(
            "^data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$",
            RegexOption.IGNORE_CASE
        )

        private val ALLOWED_EDIT_ACTION_IDS = setOf(
            android.R.id.copy,
            android.R.id.cut,
            android.R.id.paste,
            android.R.id.pasteAsPlainText,
            android.R.id.selectAll
        )

        /** Subrecursos: hosts exactos. */
        private val RESOURCE_ALLOWED_HOSTS = setOf(
            "www.gstatic.com",                                  // SDK de Firebase
            "apis.google.com",                                  // iframe de Firebase Auth
            "sendcommandv8-687828714595.us-central1.run.app",   // Cloud Function sendCommandV8
            "checkplayapps-5i52mt6h3q-uc.a.run.app",            // Cloud Function checkPlayApps
            "classicstoreapk-5i52mt6h3q-uc.a.run.app",          // Cloud Function classicStoreApk
            "aurorastore-5i52mt6h3q-uc.a.run.app",              // Cloud Function auroraStore
            "upload.wikimedia.org"                              // íconos de apps del panel
        )

        /**
         * Subrecursos: sufijos. Cada uno cubre un conjunto de endpoints de Google/Firebase
         * que cambian de nombre según la región o el servicio, y ninguno es un sitio
         * navegable (además la navegación va por [NAV_ALLOWED_HOSTS], no por acá).
         */
        private val RESOURCE_ALLOWED_SUFFIXES = listOf(
            ".googleapis.com",        // identitytoolkit, securetoken, firebaseinstallations
            ".firebaseio.com",        // Realtime Database (también el WebSocket)
            ".firebasedatabase.app",
            ".cloudfunctions.net",
            ".gstatic.com",           // ssl.gstatic.com / fonts.gstatic.com (login de Google)
            ".googleusercontent.com"  // foto de perfil en el selector de cuenta
        )

        /**
         * Alto (en dp) de la franja superior desde la que se permite arrancar el
         * gesto de "deslizar para recargar". Ver [setupSwipeRefresh].
         */
        private const val PULL_TO_REFRESH_ZONE_DP = 64
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    /** Contenedor de la pantalla "sin conexión" (un ScrollView, ver activity_main.xml). */
    private lateinit var layoutOffline: View
    private lateinit var btnRetry: Button

    private var backPressedTime: Long = 0
    private var hasError: Boolean = false

    /** El WebView ya fue destruido (onDestroy, o el renderer se murió). No tocarlo más. */
    private var webViewDestroyed: Boolean = false

    /** Y del ACTION_DOWN del último toque sobre el WebView, en píxeles. */
    private var lastTouchDownY: Float = Float.MAX_VALUE

    private val pullZonePx: Int by lazy {
        (PULL_TO_REFRESH_ZONE_DP * resources.displayMetrics.density).toInt()
    }

    /** Callback pendiente de <input type="file"> (importar preset). */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Proteger el panel de administración contra capturas de pantalla y vista previa en Recientes
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // El WebView de Android vive en la app "Android System WebView", que se puede
        // desactivar, estar actualizándose, o —en un equipo administrado por el propio
        // LockSuite— quedar suspendida. En cualquiera de esos casos inflar el layout
        // lanza y la app se cierra sola sin decir por qué. Preferimos un cartel.
        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo inflar el layout (¿Android System WebView desactivado?)", e)
            Toast.makeText(this, R.string.webview_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val xmlWebView = findViewById<WebView>(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        layoutOffline = findViewById(R.id.layoutOffline)
        btnRetry = findViewById(R.id.btnRetry)

        webView = createConfinedWebView(xmlWebView)

        setupWebView()
        setupSwipeRefresh()
        setupBackHandler()

        btnRetry.setOnClickListener {
            layoutOffline.visibility = View.GONE
            webView.visibility = View.VISIBLE
            hasError = false
            loadPanel()
        }

        // restoreState() devuelve null cuando el Bundle guardado se perdió o quedó
        // truncado (Android tiene un tope de ~1 MB por Bundle y el historial de un
        // WebView lo puede pasar). Sin este chequeo, al rotar la pantalla o al volver
        // después de que el sistema mate el proceso, la app quedaba en NEGRO PARA
        // SIEMPRE porque nadie llamaba a loadUrl().
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            loadPanel()
        }
    }

    private fun editActionTitles(): Set<String> {
        val out = HashSet<String>()
        for (res in intArrayOf(android.R.string.copy, android.R.string.paste, android.R.string.cut, android.R.string.selectAll)) {
            try {
                out.add(getString(res).trim())
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun isAllowedEditItem(item: MenuItem, allowedTitles: Set<String>): Boolean {
        if (item.intent != null) return false
        if (item.itemId == android.R.id.textAssist) return false
        if (item.itemId in ALLOWED_EDIT_ACTION_IDS) return true
        val title = item.title?.toString()?.trim() ?: return false
        return title in allowedTitles
    }

    private fun filterActionMenu(menu: Menu, allowedTitles: Set<String>) {
        for (i in menu.size() - 1 downTo 0) {
            if (i >= menu.size()) continue
            val item = menu.getItem(i)
            if (!isAllowedEditItem(item, allowedTitles)) {
                item.isVisible = false
                item.isEnabled = false
            }
        }
    }

    private fun createConfinedWebView(placeholder: WebView): WebView {
        val editTitles = editActionTitles()
        val confined = object : WebView(this) {
            override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
                return wrapActionModeCallback(callback)?.let { super.startActionMode(it) }
            }

            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                return wrapActionModeCallback(callback)?.let { super.startActionMode(it, type) }
            }

            private fun wrapActionModeCallback(callback: ActionMode.Callback?): ActionMode.Callback? {
                if (callback == null) return null
                return object : ActionMode.Callback2() {
                    override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: android.graphics.Rect?) {
                        if (callback is ActionMode.Callback2) {
                            callback.onGetContentRect(mode, view, outRect)
                        } else {
                            super.onGetContentRect(mode, view, outRect)
                        }
                    }

                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        val res = callback.onCreateActionMode(mode, menu)
                        menu?.let { filterActionMenu(it, editTitles) }
                        return res
                    }

                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                        val res = callback.onPrepareActionMode(mode, menu)
                        menu?.let { filterActionMenu(it, editTitles) }
                        return res
                    }

                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                        if (item == null || !isAllowedEditItem(item, editTitles)) {
                            return true
                        }
                        return callback.onActionItemClicked(mode, item)
                    }

                    override fun onDestroyActionMode(mode: ActionMode?) {
                        callback.onDestroyActionMode(mode)
                    }
                }
            }
        }
        confined.id = placeholder.id
        confined.layoutParams = placeholder.layoutParams
        val parent = placeholder.parent as? ViewGroup
        if (parent != null) {
            val idx = parent.indexOfChild(placeholder)
            parent.removeViewAt(idx)
            parent.addView(confined, idx, placeholder.layoutParams)
        }
        try {
            placeholder.destroy()
        } catch (_: Exception) {
        }
        return confined
    }

    /**
     * Defensa adicional sobre el menú de selección si algún fabricante notifica el
     * ActionMode por la ventana de la Activity en vez de startActionMode() del WebView.
     */
    override fun onActionModeStarted(mode: android.view.ActionMode?) {
        super.onActionModeStarted(mode)
        val menu = mode?.menu ?: return
        filterActionMenu(menu, editActionTitles())
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) webView.onResume()
    }

    override fun onPause() {
        if (!webViewDestroyed) {
            webView.onPause()
            // La sesión de Firebase Auth viaja en cookies + almacenamiento local.
            // flush() las baja a disco ya: si el sistema mata el proceso sin avisar
            // (habitual en equipos de poca RAM), sin esto habría que volver a loguearse.
            CookieManager.getInstance().flush()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDestroyed) {
            webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }

    private fun destroyWebView() {
        if (webViewDestroyed || !this::webView.isInitialized) return
        webViewDestroyed = true
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        webView.stopLoading()
        // Sacarlo del árbol ANTES de destruirlo: destruir un WebView que todavía está
        // adjunto a una ventana deja el compositor con una capa colgada.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deslizar para recargar
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            Color.parseColor("#F1C40F"), // Accent gold
            Color.parseColor("#2C5282")  // Navy light
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1E3E62"))

        swipeRefresh.setOnRefreshListener {
            hasError = false
            webView.reload()
        }

        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTouchDownY = event.y
            false // no consumimos: el WebView sigue recibiendo el toque normalmente
        }

        // ⚠️ ESTE BLOQUE ARREGLA UN BUG REAL, NO ES DECORACIÓN.
        //
        // El panel dibuja el detalle del dispositivo dentro de .sidebar-content, que es
        // un contenedor con `overflow-y: auto` DENTRO de una barra `position: fixed`.
        // En celular esa barra ocupa el 100% del ancho, o sea que casi todo lo que uno
        // manipula está dentro de un scroll propio de CSS… y ese scroll NO mueve
        // webView.scrollY, que se queda clavado en 0.
        //
        // La comprobación anterior era `swipeRefresh.isEnabled = webView.scrollY == 0`:
        // con el panel de un dispositivo abierto daba SIEMPRE true, así que cualquier
        // arrastre hacia abajo para subir en la lista disparaba una recarga completa de
        // la página. Es decir: el gesto principal de la app rompía la app.
        //
        // Solución sin depender de JavaScript ni de puentes: el gesto de recargar solo
        // se arma si el dedo BAJÓ dentro de los primeros PULL_TO_REFRESH_ZONE_DP dp de
        // la pantalla — que en el panel es la cabecera de la barra lateral, un área que
        // no tiene scroll propio. Adentro del contenido, deslizar es siempre deslizar.
        //
        // La firma es "canChildScrollUp": devolver true significa "el hijo todavía puede
        // desplazarse hacia arriba", o sea NO dispares el refresh.
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webViewDestroyed || webView.scrollY > 0 || lastTouchDownY > pullZonePx
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        val settings = webView.settings

        settings.javaScriptEnabled = true      // el panel es una SPA, sin esto no hay nada
        settings.domStorageEnabled = true      // Firebase Auth guarda la sesión acá
        settings.databaseEnabled = true

        // Ajuste visual para pantallas chicas
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100

        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // ── Endurecimiento ───────────────────────────────────────────────────
        // Nada de contenido http:// dentro de una página https://.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // El panel es 100% remoto: la app nunca necesita leer archivos locales ni
        // proveedores de contenido. Cerrar esto elimina toda la familia de ataques
        // "file:// dentro del WebView".
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        // Nada de ventanas nuevas: window.open() y target="_blank" quedan resueltos
        // DENTRO del mismo WebView, o sea que pasan por shouldOverrideUrlLoading y por
        // la lista blanca. Si se permitieran ventanas múltiples, una ventana nueva
        // podría abrirse sin pasar por ese filtro.
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // Ubicación: el panel no la usa y no tiene por qué pedirla.
        settings.setGeolocationEnabled(false)
        // Nada de audio/video que arranque solo.
        settings.mediaPlaybackRequiresUserGesture = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // El User-Agent por defecto de un WebView contiene "; wv". Google rechaza el
        // login OAuth con "403 disallowed_useragent" cuando lo ve, así que se saca.
        // Se agrega además un token propio para que el panel pueda saber que está
        // corriendo dentro de la app (lo usa para elegir login por redirección en vez
        // de por popup — ver app.js). NO cambiar el nombre del token sin cambiarlo
        // también allá.
        val defaultUa = settings.userAgentString ?: ""
        settings.userAgentString = defaultUa.replace("; wv", "") + " LockSuiteAdminApp"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // Necesario para el login con Google: el handler de Firebase Auth vive en
        // firebaseapp.com y el panel en web.app, o sea que son terceros entre sí.
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.parseColor("#0B192C"))
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        webView.webChromeClient = PanelChromeClient()
        webView.webViewClient = PanelWebViewClient()

        // Descargas: bloqueadas a propósito. En un celular sin navegador, poder bajar
        // archivos arbitrarios es una vía de escape (y un .apk bajado a mano saltea el
        // control de la Tienda administrada). Los presets se exportan desde la PC.
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            Log.w(TAG, "BLOQUEADO (descarga): mime=$mimeType url=${shortUrl(url)}")
            toast(getString(R.string.blocked_download))
        }
    }

    private inner class PanelChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress < 100) {
                if (layoutOffline.visibility != View.VISIBLE) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            } else {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }
        }

        /**
         * Con setSupportMultipleWindows(false) esto no debería llamarse nunca, pero si
         * una versión de WebView decide llamarlo igual, la respuesta es "no".
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            Log.w(TAG, "BLOQUEADO: intento de abrir una ventana nueva")
            return false
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }

        /** Cámara y micrófono: el panel no los usa. Denegar sin preguntar. */
        override fun onPermissionRequest(request: PermissionRequest?) {
            Log.w(TAG, "BLOQUEADO (permiso web): ${request?.resources?.joinToString()}")
            request?.deny()
        }

        /**
         * <input type="file"> del panel (importar preset .locksuite). Abre el selector
         * de documentos del sistema, que no es un navegador: no rompe el confinamiento.
         */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            if (!isPanelHostUrl(webView?.url)) {
                filePathCallback?.onReceiveValue(null)
                return true
            }
            // Si había uno pendiente, cerrarlo: dejar un ValueCallback sin responder
            // deja el <input> del panel colgado para siempre.
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback

            val intent = fileChooserParams?.createIntent()
            if (intent == null) {
                pendingFileCallback = null
                filePathCallback?.onReceiveValue(null)
                return true
            }
            return try {
                filePickerLauncher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No hay app para elegir archivos", e)
                pendingFileCallback = null
                filePathCallback?.onReceiveValue(null)
                toast(getString(R.string.no_file_picker))
                true
            }
        }
    }

    private inner class PanelWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url != null && !isNavigationAllowed(url)) {
                Log.w(TAG, "BLOQUEADO (onPageStarted): ${shortUrl(url)}")
                view?.stopLoading()
                toast(getString(R.string.blocked_navigation))
                view?.post { if (!webViewDestroyed) loadPanel() }
                return
            }
            hasError = false
            progressBar.visibility = View.VISIBLE
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (url != null && !isNavigationAllowed(url)) {
                Log.w(TAG, "BLOQUEADO (doUpdateVisitedHistory): ${shortUrl(url)}")
                view?.stopLoading()
                toast(getString(R.string.blocked_navigation))
                view?.post { if (!webViewDestroyed) loadPanel() }
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            if (!hasError) {
                layoutOffline.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }

        // ── El filtro kosher ─────────────────────────────────────────────────

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString()
            if (isNavigationAllowed(url)) return false
            Log.w(TAG, "BLOQUEADO (navegación): ${shortUrl(url)}")
            // Solo avisamos si el usuario intentó ir a algún lado a propósito; un iframe
            // bloqueado no merece un cartel.
            if (request?.isForMainFrame == true) toast(getString(R.string.blocked_navigation))
            return true
        }

        /**
         * Variante vieja de la API. Con minSdk 24 el sistema usa siempre la de arriba,
         * pero algunos WebView de fabricante llaman a esta. Cuesta cuatro líneas y
         * cierra la duda.
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (isNavigationAllowed(url)) return false
            Log.w(TAG, "BLOQUEADO (navegación, API vieja): ${shortUrl(url)}")
            toast(getString(R.string.blocked_navigation))
            return true
        }

        /**
         * Segunda barrera. shouldOverrideUrlLoading solo ve navegaciones; esto ve TODA
         * petición (scripts, XHR, fetch, imágenes, iframes). Sin esto, un script del
         * panel comprometido podría filtrar datos a un tercero aunque no se pueda
         * "navegar" ahí.
         *
         * OJO: corre en un hilo secundario. Nada de tocar vistas acá.
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val req = request ?: return null
            val url = req.url?.toString() ?: return null
            val allowed = if (req.isForMainFrame) {
                isNavigationAllowed(url)
            } else {
                isResourceAllowed(url)
            }
            if (allowed) return null // null = que siga el camino normal
            Log.w(TAG, "BLOQUEADO (${if (req.isForMainFrame) "navegación" else "recurso"}): ${shortUrl(url)}")
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked by LockSuite Admin",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // ── Errores ──────────────────────────────────────────────────────────

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                hasError = true
                showOfflineScreen()
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            // Un 500 del hosting dejaba una pantalla en blanco sin explicación.
            if (request?.isForMainFrame == true) {
                Log.w(TAG, "HTTP ${errorResponse?.statusCode} en ${shortUrl(request.url?.toString())}")
                hasError = true
                showOfflineScreen()
            }
        }

        /**
         * NUNCA llamar a handler.proceed(). Un certificado inválido en el panel de
         * administración es, por definición, alguien en el medio: el panel manda
         * comandos de MDM y tokens de sesión.
         */
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            Log.e(TAG, "BLOQUEADO (TLS): error=${error?.primaryError} url=${shortUrl(error?.url)}")
            handler?.cancel()
            hasError = true
            toast(getString(R.string.blocked_ssl))
            showOfflineScreen()
        }

        /**
         * El proceso del renderizador se murió (típico por falta de memoria en equipos
         * económicos, justo el perfil del "celular kosher"). Si nadie devuelve true acá,
         * Android se lleva puesto el proceso de la app entera y el usuario ve un cierre
         * inesperado sin explicación.
         */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            Log.e(TAG, "El proceso del WebView murió; recreando la pantalla")
            if (isFinishing || isDestroyed) return true
            destroyWebView()
            toast(getString(R.string.webview_recovered))
            window.decorView.post { if (!isFinishing && !isDestroyed) recreate() }
            return true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lista blanca
    // ─────────────────────────────────────────────────────────────────────────

    private data class ParsedUri(
        val scheme: String,
        val host: String,
        val canonicalPath: String
    )

    private fun parseStrictUri(url: String?, allowWss: Boolean = false): ParsedUri? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        for (ch in trimmed) {
            if (ch <= ' ' || ch == '\\') return null
        }

        val doubleSlashIdx = trimmed.indexOf("://")
        if (doubleSlashIdx > 0) {
            val authorityStart = doubleSlashIdx + 3
            val slashIdx = trimmed.indexOf('/', authorityStart)
            val questionIdx = trimmed.indexOf('?', authorityStart)
            val hashIdx = trimmed.indexOf('#', authorityStart)
            val authorityEnd = listOf(slashIdx, questionIdx, hashIdx)
                .filter { it >= 0 }
                .minOrNull() ?: trimmed.length
            if (trimmed.substring(authorityStart, authorityEnd).contains('@')) {
                return null
            }
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val schemeValid = scheme == "https" || (allowWss && scheme == "wss")
        if (!schemeValid) return null

        if (uri.rawUserInfo != null || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val rawPath = (uri.rawPath ?: "").ifEmpty { "/" }
        val lowerRawPath = rawPath.lowercase(Locale.ROOT)
        if (lowerRawPath.contains("%2e") || lowerRawPath.contains("%2f") || lowerRawPath.contains("%5c")) {
            return null
        }
        if (rawPath.split('/').any { it == "." || it == ".." }) {
            return null
        }

        val canonicalPath = ((uri.normalize().rawPath ?: "").ifEmpty { "/" })
        if (!canonicalPath.startsWith("/")) return null
        return ParsedUri(scheme, host, canonicalPath)
    }

    private fun isPanelHostUrl(url: String?): Boolean {
        val parsed = parseStrictUri(url) ?: return false
        return parsed.scheme == "https" && parsed.host in PANEL_ALLOWED_HOSTS
    }

    /** ¿Puede el WebView NAVEGAR a esta URL? */
    private fun isNavigationAllowed(url: String?): Boolean {
        val trimmed = url?.trim() ?: return false
        // Permitir ÚNICAMENTE exacto about:blank (rechaza about:srcdoc, etc.)
        if (trimmed == "about:blank") return true

        val parsed = parseStrictUri(trimmed, allowWss = false) ?: return false
        if (parsed.host !in NAV_ALLOWED_HOSTS) return false
        if (parsed.host == "accounts.google.com") {
            val path = parsed.canonicalPath.lowercase(Locale.ROOT)
            // En accounts.google.com permitimos el flujo OAuth/SignIn pero bloqueamos creación
            // de cuentas, recuperación, cambio/selector /b/ y gestión de cuenta general.
            if (BLOCKED_ACCOUNTS_PATH_PREFIXES.any { path.startsWith(it) }) {
                return false
            }
        }
        return true
    }

    /** ¿Puede la página bajar este subrecurso? */
    private fun isResourceAllowed(url: String?): Boolean {
        val trimmed = url?.trim() ?: return false
        if (trimmed == "about:blank") return true

        if (trimmed.startsWith("data:", ignoreCase = true)) {
            return ALLOWED_RASTER_DATA_IMAGE_REGEX.matches(trimmed)
        }
        if (trimmed.startsWith("blob:", ignoreCase = true)) {
            val inner = trimmed.substring(5)
            val parsedInner = parseStrictUri(inner, allowWss = false) ?: return false
            val host = parsedInner.host
            return host in NAV_ALLOWED_HOSTS ||
                host in RESOURCE_ALLOWED_HOSTS ||
                RESOURCE_ALLOWED_SUFFIXES.any { host.endsWith(it) }
        }

        val parsed = parseStrictUri(trimmed, allowWss = true) ?: return false
        val host = parsed.host
        if (host in NAV_ALLOWED_HOSTS || host in RESOURCE_ALLOWED_HOSTS) return true
        return RESOURCE_ALLOWED_SUFFIXES.any { host.endsWith(it) }
    }

    /** Para el log: host + ruta, sin query (ahí viajan tokens de sesión). */
    private fun shortUrl(url: String?): String {
        if (url == null) return "(null)"
        return try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}${uri.path ?: ""}"
        } catch (e: Exception) {
            "(url no parseable)"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carga / estado
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadPanel() {
        if (!isNetworkAvailable()) {
            showOfflineScreen()
            return
        }
        hasError = false
        layoutOffline.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(ADMIN_PANEL_URL)
    }

    private fun showOfflineScreen() {
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        layoutOffline.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layoutOffline.visibility == View.VISIBLE) {
                    finish()
                    return
                }
                if (!webViewDestroyed && webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 2000) {
                    finish()
                } else {
                    backPressedTime = currentTime
                    toast(getString(R.string.press_back_again))
                }
            }
        })
    }
}
