package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.ejemplo.locksuite.mdm.AccessibilityBouncePolicy
import com.ejemplo.locksuite.mdm.CaptivePortalPolicy
import com.ejemplo.locksuite.mdm.EmbeddedBrowserDetector
import com.ejemplo.locksuite.mdm.GoogleAccountWebPolicy
import com.ejemplo.locksuite.mdm.Layer3Audit
import com.ejemplo.locksuite.mdm.MercadoPagoOffersPolicy
import com.ejemplo.locksuite.mdm.PhotoPickerPolicy
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.mdm.WebViewBlockManager
import com.ejemplo.locksuite.mdm.UpdateStepLog
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.UpdateFlowManager
import com.ejemplo.locksuite.util.PlayButtonFinder
import com.ejemplo.locksuite.util.PlayUpdateSessionWatcher

/**
 * Capa 3 — Servicio de Accesibilidad.
 *
 * ADVERTENCIA DE RENDIMIENTO PARA QUIEN EDITE ESTE ARCHIVO
 * ────────────────────────────────────────────────────────
 * `onAccessibilityEvent` se ejecuta EN EL HILO PRINCIPAL y el sistema lo llama
 * hasta ~10 veces por segundo (notificationTimeout = 100 ms) mientras el usuario
 * usa el teléfono. Todo lo que se agregue en el camino directo del evento se paga
 * multiplicado por diez, cada segundo, con la pantalla encendida. Reglas de la casa:
 *
 *   • Nada de `new` en el camino caliente: ni PolicyManager, ni getSystemService,
 *     ni concatenación de strings para logs. Todo cacheado en campos.
 *   • Nada de consultas al PackageManager por evento. Se cachean con TTL.
 *   • Todo recorrido del árbol de nodos lleva tope de profundidad Y tope de nodos.
 *   • Los logs de diagnóstico van detrás de `if (VERBOSE)`. Como VERBOSE es una
 *     constante `false`, R8 elimina por completo el bloque en la versión final.
 *   • El snapshot de configuración se lee una vez por ráfaga desde SharedPreferences,
 *     no por evento individual.
 */
class LockSuiteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: LockSuiteAccessibilityService? = null
        private const val TAG = "LockSuite_WV"

        fun onTempInstallWindowClosed() {
            val svc = instance ?: return
            svc.cachedFlags = null
            svc.mainHandler.post {
                com.ejemplo.locksuite.util.Safe.guard("a11y.tempInstallClosed") {
                    val f = svc.flags()
                    val topPkg = svc.getOriginApp()
                    if (!f.suspended && !f.playUpdateActive && topPkg == PKG_PLAY_STORE &&
                        (f.playStoreAccessibilityBlocked || svc.policyManager.isPlayStoreAppBlocked())
                    ) {
                        Log.i(TAG, "🔒 Ventana temporal cerrada con Play Store abierta: regresando a Home")
                        svc.performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
            }
        }

        /**
         * Logs de diagnóstico del camino caliente. Dejar en `false` para producción.
         * Al ser `const`, el compilador de Kotlin y R8 eliminan por completo los
         * bloques `if (VERBOSE) { ... }` — no queda ni la rama ni el string.
         * (El proyecto tiene `buildConfig = false`, por eso no se usa BuildConfig.DEBUG.)
         */
        private const val VERBOSE = false

        private const val SETTINGS_PKG = "com.android.settings"
        private const val LOCKSUITE_PKG = "com.ejemplo.locksuite"
        private const val DEBOUNCE_MS = 300L
        /**
         * Debounce del RE-ESCANEO del árbol durante el scroll en portales cautivos.
         * Con `translateRegions()` los recuadros se corren con el delta del propio evento;
         * este debounce gobierna cada cuánto se recorre el árbol para ajustar.
         */
        private const val IMAGE_SCROLL_DEBOUNCE_MS = 90L
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val PKG_PLAY_STORE = "com.android.vending"

        /** Vida útil del cache de "qué paquetes son navegadores". */
        private const val BROWSER_CACHE_TTL_MS = 10 * 60 * 1000L

        /** Vida útil del snapshot de flags, como red de seguridad además del listener. */
        private const val FLAGS_MAX_AGE_MS = 3_000L

        /** Topes de seguridad para cualquier recorrido del árbol de accesibilidad. */
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODES_PER_SCAN = 2_500

        /** Cada cuánto se puede re-armar la escalera de reintentos de WebView. */
        private const val WEBVIEW_REARM_MS = 2_000L

        /**
         * Antirrebote del detector estructural de navegadores embebidos (B.60).
         *
         * Es alto a propósito. El detector recorre el árbol de vistas, y `B.13` pasó
         * una sesión entera sacando del camino caliente cosas razonables en sí mismas
         * que mataban la fluidez del equipo. Además solo corre en cambio de ventana:
         * un usuario no abre un navegador embebido nuevo cada segundo y medio.
         */
        private const val IAB_REARM_MS = 1_500L

        /**
         * Cuántas cadenas como mucho junta el retrato de cada tipo (ids, editables,
         * descripciones). Sin este tope, una pantalla con doscientos campos armaría
         * doscientas cadenas en el hilo principal en cada cambio de ventana. Con
         * treinta alcanza: la barra de direcciones y los controles de un navegador
         * están arriba de todo, y el recorrido es en profundidad desde la raíz.
         */
        private const val MAX_IAB_STRINGS = 30

        /** Pausa tras un rebote fallido en Mercado Pago, para no encadenar "atrás". */
        private const val MP_BACKOFF_MS = 4_000L

        /** Pausa tras tener que forzar HOME desde el menú de Accesibilidad de Ajustes. */
        private const val ACC_BOUNCE_BACKOFF_MS = 3_000L

        // ── Flujo de actualización por Play Store ──
        /** Mínimo entre dos relanzamientos de Play Store si el usuario se va a otra app. */
        private const val STORE_RELAUNCH_MIN_MS = 1_500L
        /** Mínimo entre dos clics automáticos, para no encadenar toques sobre la misma pantalla. */
        private const val UPDATE_CLICK_COOLDOWN_MS = 1_500L
        /** Con "Abrir" en pantalla y sin haber hecho nada, se da por ya actualizada pasado esto. */
        private const val UPDATE_UP_TO_DATE_GRACE_MS = 3_000L
        /** Sin haber podido clickear ni ver progreso pasado esto, se aborta y se destapa la pantalla. */
        private const val UPDATE_STALL_MS = 75_000L
        /** Cada cuánto el flujo vuelve a mirar la pantalla, sin depender de eventos. */
        private const val UPDATE_TICK_MS = 700L
        /** Cuánto se espera tras apretar un candidato antes de probar el siguiente. */
        private const val CANDIDATE_WAIT_MS = 2_500L
        /** Cuántos candidatos distintos se prueban antes de rendirse. */
        private const val MAX_CANDIDATES = 6
        /** Máximo de avisos emergentes/hojas (ej. Asistente IA, Play Points) que se descartan por sesión. */
        private const val MAX_DISMISSALS = 3
        /** Cuántos padres se suben como máximo buscando quién acepta el clic. */
        private const val CLICK_PARENT_DEPTH = 6

        // ── Correcciones del 3/9/2026 (CAT S22 Flip / Android 11) ──
        //
        // El equipo del reporte es un Snapdragon 215 con 2 GB de RAM y una pantalla
        // de 480×640. Los tres números de abajo son los que estaban calibrados
        // contra un teléfono normal y no contra ese.

        /**
         * Cuánto se espera a que la ficha de Play Store TERMINE DE DIBUJARSE antes
         * de sacar cualquier conclusión.
         *
         * Antes había un solo número, 3,5 s, contados desde que aparecía la VENTANA
         * de Play Store. Pero la ventana existe apenas arranca la Activity, cuando
         * la ficha todavía es una pantalla en blanco: en un equipo lento la ficha
         * tarda entre 4 y 8 s en tener botones. O sea que el flujo concluía "no hay
         * ningún botón" sobre una pantalla que todavía no había dibujado ninguno, y
         * cerraba informando "la app ya está actualizada". Un falso éxito, y encima
         * el que hace que el dueño busque el problema donde no está.
         *
         * Ahora hay dos condiciones y las dos tienen que cumplirse: que haya pasado
         * este tiempo Y que la ficha tenga contenido dibujado (`Result.rendered`).
         */
        private const val UPDATE_CARD_RENDER_MS = 9_000L

        /**
         * Tope duro para dejar de esperar a que la ficha se dibuje. Si pasó esto y
         * la pantalla sigue vacía, el problema no es la lentitud: es que Play Store
         * no está mostrando la ficha (sin red, sin cuenta, ficha inexistente).
         */
        private const val UPDATE_CARD_GIVEUP_MS = 30_000L

        /**
         * Cuánto puede quedarse la descarga sin avanzar un solo punto porcentual
         * antes de darla por trabada. Cubre el caso "Play Store dejó la descarga
         * esperando Wi-Fi" y el caso "no hay espacio y el sistema abortó la sesión".
         * Antes no existía ningún tope acá: el ciclo salía por un `return` que
         * estaba ANTES del freno por estancamiento, así que la única salida era el
         * watchdog de 10 minutos.
         */
        private const val DOWNLOAD_STALL_MS = 150_000L

        /**
         * En una pantalla chica la fila de botones puede quedar debajo del borde
         * visible, y Android no instancia en el árbol de accesibilidad lo que no
         * está dibujado: el botón "Actualizar" simplemente no existe para nosotros.
         * Cuando la ficha ya está dibujada y no aparece ningún candidato, se baja
         * un poco antes de rendirse. Tope bajo a propósito: bajar de más lleva a
         * los carruseles de "apps similares", que es el bug 4 de B.9.
         */
        private const val MAX_UPDATE_SCROLLS = 3
        private const val SCROLL_COOLDOWN_MS = 1_200L

        /**
         * 26/10/2026 (B.97) — cuánto puede quedarse Google Play con la descarga "en
         * cola" (barra en la fila de botones que NO avanza, y sin sesión de instalación)
         * antes de cerrar diciendo eso. Antes no había tope: cualquier barra de progreso
         * en pantalla se leía como "descargando" y la única salida era el watchdog de 10
         * minutos. Una barra CON valor que avanza reinicia la cuenta: es una descarga de
         * verdad (versiones de Play Store que bajan antes de abrir la sesión).
         */
        private const val QUEUED_STALL_MS = 240_000L

        /**
         * B.97 — cuánto tiene que SOSTENERSE "Esperando Wi-Fi / esperando red" en la
         * descarga para cerrar diciéndolo. Un cambio de red hace parpadear ese cartel en
         * una descarga que sigue sola; cerrar al primer cuadro la cortaba.
         */
        private const val WAITING_NETWORK_CONFIRM_MS = 10_000L

        // Paquetes que actúan como renderizadores de WebView del sistema
        private val WEBVIEW_PROVIDER_PACKAGES = setOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
            "com.google.android.apps.chrome"
        )

        // Paquetes de navegadores conocidos
        private val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "org.mozilla.focus",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.android.browser",
            "com.UCMobile.intl",
            "com.kiwibrowser.browser",
            "com.android.htmlviewer",
            "com.google.android.googlequicksearchbox" // App de Google / Asistente / Lens (Punto 1)
        )

        private const val PKG_MERCADOPAGO = "com.mercadopago.wallet"

        // ──────────────────────────────────────────────────────────────
        // Detección de la sección "Ofertas" de Mercado Pago
        //
        // 16/9/2026: las listas y la decisión se mudaron a `mdm/MercadoPagoOffersPolicy`,
        // que es una función pura con banco de pruebas. Acá quedó solo el recorrido del
        // árbol, que es lo único que necesita el servicio.
        //
        // El porqué de la mudanza, resumido (el detalle largo está en ese archivo): la
        // regla *"pantalla WebView de Mercado Pago + UNA palabra débil → bloquear"*
        // sacaba al usuario del asistente (Mago) y de cobros de ANSES, porque casi toda
        // Mercado Pago es una pantalla WebView y palabras como "beneficio" o "descuento"
        // aparecen sueltas en pantallas perfectamente legítimas. Se estaba tratando la
        // MENCIÓN de una palabra como si fuera la SECCIÓN.
        //
        // Si se escapa una pantalla de ofertas: agregar su título o su id de vista a las
        // listas de ese archivo — que ahora el panel te dice cuáles son (`layer3Audit`).
        // **No volver a la regla de una sola palabra débil.**
        // ──────────────────────────────────────────────────────────────

        /**
         * Tope de cadenas que junta el retrato de Mercado Pago. Sin esto, una pantalla
         * con doscientos nodos de texto armaría doscientas cadenas en el hilo principal
         * — el multiplicador del camino caliente que B.13 pasó una sesión entera sacando
         * de este archivo.
         */
        private const val MAX_MP_CADENAS = 60
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCheckedAt = 0L
    /** Último reposicionamiento de recuadros de imágenes por scroll (elapsedRealtime). */
    private var lastImageScrollAt = 0L

    private val whatsappScanRunnable = Runnable {
        // 7/11/2026 (B.112): hilo principal; una excepción acá mataba el proceso con el servicio enlazado.
        com.ejemplo.locksuite.util.Safe.guard("a11y.whatsappScan") {
            val f = flags()
            if (f.waStatus || f.waChannels) {
                scanWhatsAppTree(f.waStatus, f.waChannels, lastWaPkgName, lastWaWindowClassName)
            }
        }
    }

    // Stack de paquetes activos: [0] = el más reciente que NO es browser/webview-provider
    private val appPackageStack = ArrayDeque<String>(5)

    // Guardas de rebote separadas por función. Antes había UNA sola compartida, así que
    // un bloqueo de WhatsApp en curso hacía que se perdiera en silencio un bloqueo de
    // Mercado Pago o de WebView que ocurriera en esos 700 ms.
    @Volatile private var webViewBlockInProgress = false
    @Volatile private var waBlockInProgress = false
    @Volatile private var mpBlockInProgress = false

    // Overlay de actualización de apps y tapado de imágenes en portales cautivos
    lateinit var overlayManager: BlockOverlayManager

    // ──────────────────────────────────────────────
    // Servicios del sistema cacheados
    //
    // getSystemService() no es gratis: hace una búsqueda por nombre y, la primera vez,
    // una llamada al ServiceManager. Antes se llamaba en cada evento.
    // ──────────────────────────────────────────────
    private val powerManager: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /**
     * Instancia única de PolicyManager. Antes se construía una nueva
     * (`PolicyManager(applicationContext)`) en CADA evento y en cada runnable de
     * escaneo — hasta cuatro por evento. Su constructor resuelve el DevicePolicyManager
     * y arma un ComponentName, así que eran decenas de objetos por segundo tirados a la
     * basura, con la presión de GC que eso implica.
     *
     * Cachear la instancia NO congela la configuración: PolicyManager lee las
     * SharedPreferences en cada getter, así que sigue viendo los cambios al instante.
     */
    private val policyManager: PolicyManager by lazy { PolicyManager(applicationContext) }

    private val mdmPrefs: SharedPreferences by lazy { PrefsHelper.getMdmPrefs(applicationContext) }

    // ──────────────────────────────────────────────
    // Snapshot de configuración
    //
    // Cada evento leía entre 3 y 8 booleanos de SharedPreferences. Ahora se leen una
    // vez y se guardan; el snapshot se invalida solo cuando algo escribe en las prefs
    // (listener) y, por las dudas, se vuelve a leer si tiene más de FLAGS_MAX_AGE_MS.
    // ──────────────────────────────────────────────
    private class Flags(
        val suspended: Boolean,
        val waStatus: Boolean,
        val waChannels: Boolean,
        val mpOffers: Boolean,
        val settingsEvasion: Boolean,
        val playStoreAccessibilityBlocked: Boolean,
        val updateInProgress: Boolean,
        val updatingPkg: String?,
        /**
         * Ruta del flujo de actualización (B.97). Todo lo que este servicio hace por una
         * actualización —tapar, manejar Play Store, relanzarla, dejarla abierta, no seguir
         * el scroll— es SOLO para la ruta de Google Play. La de la Tienda es silenciosa:
         * el usuario sigue con el teléfono y los filtros tienen que seguir andando.
         */
        val updateRoute: String,
        /** Rebotar al usuario si entra al menú de Accesibilidad de Ajustes. */
        val accSettingsBounce: Boolean,
        /** Rebotar las pantallas web de la cuenta de Google (historial, actividad). */
        val googleAccountWeb: Boolean,
        /** Modo estricto: rebota TAMBIÉN "Gestionar tu cuenta de Google" entera. */
        val googleAccountStrict: Boolean,
        /** Rebotar el selector de foto de contacto / Google Illustrations / Google Fotos. */
        val contactPhotoPicker: Boolean,
        val geminiMessages: Boolean,
        val santanderBlock: Boolean,
        /** Vigilar la ventana de "Iniciar sesión en la red" (portal cautivo). */
        val captivePortalGuard: Boolean,
        /** Tapar las imágenes DE esa ventana. Se puede apagar sin apagar el guard. */
        val captivePortalCoverImages: Boolean,
        /** Detector estructural de navegadores embebidos (B.60). APAGADO de fábrica. */
        val iabFinder: Boolean,
        /** El detector solo anota y no bloquea. ENCENDIDO de fábrica: ver B.60. */
        val iabSimulation: Boolean,
        /** Bloquear cambio de idioma del sistema o por app (Android 13+). */
        val localeChangeBlocked: Boolean,
        /** Ventana temporal para permitir instalar apps y abrir Play Store. */
        val tempInstallActive: Boolean,
        val takenAt: Long
    ) {
        /** ¿Hay un flujo de actualización POR GOOGLE PLAY en curso? */
        val playUpdateActive: Boolean
            get() = updateInProgress && !updatingPkg.isNullOrBlank() &&
                updateRoute == UpdateFlowManager.ROUTE_PLAY_STORE
    }

    @Volatile private var cachedFlags: Flags? = null

    // Se guarda como campo porque SharedPreferences mantiene los listeners con
    // referencias débiles: si no lo retenemos, el recolector se lo lleva y dejamos
    // de enterarnos de los cambios de configuración.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cachedFlags = null }

    private fun flags(): Flags {
        val cached = cachedFlags
        if (cached != null && SystemClock.elapsedRealtime() - cached.takenAt < FLAGS_MAX_AGE_MS) {
            return cached
        }
        val p = mdmPrefs
        val fresh = Flags(
            suspended = policyManager.isLockSuiteSuspended(),
            waStatus = policyManager.isWhatsAppBlockStatusEnabled(),
            waChannels = policyManager.isWhatsAppBlockChannelsEnabled(),
            mpOffers = policyManager.isMercadoPagoBlockOffersAccessibilityEnabled(),
            settingsEvasion = p.getBoolean("settings_evasion_enabled", false),
            playStoreAccessibilityBlocked = policyManager.isPlayStoreAccessibilityBlocked(),
            updateInProgress = p.getBoolean("mdm_install_in_progress", false),
            updatingPkg = p.getString("updating_package", null),
            // Ausente = Google Play: es lo que deja un flujo de una versión anterior.
            updateRoute = p.getString(UpdateFlowManager.KEY_ROUTE, UpdateFlowManager.ROUTE_PLAY_STORE)
                ?: UpdateFlowManager.ROUTE_PLAY_STORE,
            accSettingsBounce = p.getBoolean("acc_protect_bounce_settings", false),
            // Encendido por defecto: ver el comentario de PolicyManager sobre por qué
            // este interruptor es la excepción a "todo apagado de fábrica".
            googleAccountWeb = p.getBoolean("block_google_account_web", true),
            // Por defecto NORMAL: el modo estricto rebota la pantalla entera de la
            // cuenta y eso dejó al dueño sin poder administrarla (ver GoogleAccountWebPolicy).
            googleAccountStrict = GoogleAccountWebPolicy.isStrict(
                p.getString(GoogleAccountWebPolicy.KEY_MODE, GoogleAccountWebPolicy.MODE_NORMAL)
            ),
            contactPhotoPicker = p.getBoolean("block_contact_photo_picker", true),
            geminiMessages = p.getBoolean("block_gemini_messages", true),
            santanderBlock = p.getBoolean(com.ejemplo.locksuite.mdm.SantanderBlockPolicy.KEY_ENABLED, true),
            captivePortalGuard = p.getBoolean(CaptivePortalPolicy.KEY_ENABLED, true),
            // Encendido por defecto, igual que el guard. Es la válvula de escape del
            // 8/9: si un portal real queda inusable con las imágenes tapadas, el
            // administrador apaga SOLO esto desde el panel y el resto del guard sigue.
            captivePortalCoverImages = p.getBoolean(CaptivePortalPolicy.KEY_COVER_IMAGES, true),
            // B.60 — APAGADO de fábrica, y en SIMULACIÓN de fábrica. Los dos valores
            // por omisión son deliberados y van en direcciones distintas a propósito:
            // encender el detector no puede alcanzar para que empiece a bloquear. El
            // falso positivo de un detector estructural rompe una app y nadie sabe por
            // qué — este proyecto ya lo pagó en B.43, B.50 y B.15.
            iabFinder = p.getBoolean(EmbeddedBrowserDetector.KEY_ENABLED, false),
            iabSimulation = p.getBoolean(EmbeddedBrowserDetector.KEY_SIMULATION, true),
            localeChangeBlocked = policyManager.isLocaleChangeBlocked(),
            tempInstallActive = p.getBoolean(com.ejemplo.locksuite.mdm.TempInstallWindowManager.KEY_ACTIVE, false),
            takenAt = SystemClock.elapsedRealtime()
        )
        cachedFlags = fresh
        return fresh
    }

    // ──────────────────────────────────────────────
    // Cache de clasificación de paquetes
    //
    // isBrowserPackage() hacía un packageManager.queryIntentActivities(MATCH_ALL) —
    // una llamada IPC que enumera TODAS las actividades capaces de abrir https en el
    // equipo — por cada paquete desconocido. Y se la llamaba desde trackPackage(), o
    // sea EN CADA EVENTO DE ACCESIBILIDAD. Era, de lejos, el mayor consumo de CPU del
    // servicio. Ahora se consulta una sola vez cada BROWSER_CACHE_TTL_MS y se guarda
    // el conjunto completo; la comprobación por evento pasa a ser un lookup O(1).
    // ──────────────────────────────────────────────
    @Volatile private var browserPkgCache: Set<String> = emptySet()
    private var browserPkgCacheAt = 0L

    private val sysInputCache = HashMap<String, Boolean>(32)

    private fun browserPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (browserPkgCacheAt != 0L && now - browserPkgCacheAt < BROWSER_CACHE_TTL_MS) {
            return browserPkgCache
        }
        val resolved = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
            list.mapTo(HashSet()) { it.activityInfo.packageName }
        } catch (e: Exception) {
            emptySet<String>()
        }
        browserPkgCache = resolved
        browserPkgCacheAt = now
        return resolved
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        if (KNOWN_BROWSER_PACKAGES.contains(packageName)) return true
        return browserPackages().contains(packageName)
    }

    private val updateTickRunnable = object : Runnable {
        override fun run() {
            // 7/11/2026 (B.112): hilo principal; una excepción acá mataba el proceso con el servicio enlazado.
            com.ejemplo.locksuite.util.Safe.guard("a11y.updateTick") {
                val ctx = applicationContext
                val pkg = UpdateFlowManager.currentPackage(ctx)
                // B.97: solo la ruta de Google Play tiene ciclo; la de la Tienda no toca la pantalla.
                if (pkg.isNullOrBlank() || !UpdateFlowManager.isPlayStoreFlowRunning(ctx)) {
                    return   // sin re-encolar: el flujo terminó (o no es de Google Play)
                }
                try {
                    scanAndAct(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "tick de actualización: ${e.message}")
                }
                mainHandler.postDelayed(this, UPDATE_TICK_MS)
            }
        }
    }

    /** La llama UpdateFlowManager.start(). */
    fun startUpdateTicker() {
        mainHandler.removeCallbacks(updateTickRunnable)
        mainHandler.post(updateTickRunnable)
    }

    /** La llama UpdateFlowManager.finish(). Tiene que estar en TODOS los caminos de salida. */
    fun stopUpdateTicker() {
        mainHandler.removeCallbacks(updateTickRunnable)
    }

    // ──────────────────────────────────────────────
    // Configuración del servicio
    // ──────────────────────────────────────────────
    override fun onServiceConnected() {
        // 19/11/2026 (B.125, T2.2): quién arrancó este proceso, si fue el enlace de accesibilidad.
        com.ejemplo.locksuite.diag.DiagLog.firstEntry("enlace de accesibilidad")
        // 6/11/2026 (B.110): rastro para el panel de que el servicio se enlazó.
        com.ejemplo.locksuite.diag.DiagLog.i("a11y", "Servicio de accesibilidad conectado")
        // 7/11/2026 (B.112) — LA CARRERA DEL ARRANQUE. Al desbloquear, Android enlaza este
        // servicio ANTES de mandar ACTION_USER_UNLOCKED: si el proceso nació con el equipo
        // bloqueado, a esta altura todavía no corrió la inicialización completa (Firebase,
        // idioma, receptores…). Se la completa acá. Ver LockSuiteApplication.ensureUnlockedInit.
        com.ejemplo.locksuite.LockSuiteApplication.ensureUnlockedInit(this, "servicio de accesibilidad conectado")
        connectServiceWithRetry(1)
    }

    /**
     * 7/11/2026 (B.112) — red de seguridad de la conexión. Una excepción en `onServiceConnected`
     * mataba el proceso EN EL MOMENTO MISMO en que Android acababa de enlazar el servicio: la
     * receta exacta del zombi. Ahora se anota y se reintenta (3 intentos, cada 3 s); mientras no
     * se complete, `onAccessibilityEvent` sale temprano porque `overlayManager` no existe.
     */
    private fun connectServiceWithRetry(intento: Int) {
        try {
            connectService()
        } catch (t: Throwable) {
            com.ejemplo.locksuite.util.Safe.report("a11y.onServiceConnected (intento $intento)", t)
            if (intento < 3) {
                mainHandler.postDelayed({ connectServiceWithRetry(intento + 1) }, 3_000L)
            }
        }
    }

    private fun connectService() {
        // LockSuite purgado: el equipo ya fue liberado y la app está por desinstalarse.
        // No se arranca nada y el servicio se saca solo de la lista de activos. La marca
        // la ponen DeviceRelease/EmergencyActivity (locksuite_purged).
        if (PrefsHelper.getMdmPrefs(this).getBoolean("locksuite_purged", false)) {
            try { disableSelf() } catch (_: Exception) {}
            return
        }
        // Configurar capacidades del servicio de accesibilidad
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                          AccessibilityEvent.TYPE_VIEW_SELECTED or
                          // Necesario para actividad de scroll y seguimiento de imágenes en portales cautivos.
                          AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                     AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100
        serviceInfo = info

        // IMPORTANTE: overlayManager se inicializa ANTES de publicar `instance`.
        // PackageReceiver hace `instance?.overlayManager`, y si `instance` quedaba
        // publicado primero había una ventana real en la que ese acceso reventaba con
        // UninitializedPropertyAccessException.
        overlayManager = BlockOverlayManager(this)

        mdmPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        cachedFlags = null

        val p = PrefsHelper.getMdmPrefs(this)
        if (!p.contains("accessibility_protection_enabled")) {
            p.edit().putBoolean("accessibility_protection_enabled", true).apply()
            try { policyManager.applyAccessibilityProtection(true) } catch (e: Exception) { }
        }

        instance = this

        // Resolver ya las señales que dependen del equipo y del idioma (componente de
        // la pantalla de Accesibilidad y su título localizado). Ver
        // refreshAccessibilitySignals().
        try { refreshAccessibilitySignals(force = true) } catch (e: Exception) { }

        // Aviso INSTANTÁNEO y certero de que la accesibilidad volvió.
        //
        // Este es el momento exacto en que el servicio empieza a funcionar, así que no
        // hace falta esperar a que ningún ciclo lo descubra ni pasar por el antirrebote:
        // se reconcilia acá mismo y las apps se liberan al toque. Junto con el aviso de
        // onDestroy() y el ContentObserver del Watchdog, el ciclo periódico queda como
        // red de seguridad y no como el mecanismo principal.
        try {
            com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(applicationContext)
            com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Reconciliación al conectar: ${e.message}")
        }

        // 9/9/2026 (B.54) — Si el servicio se re-vincula EN MEDIO de una
        // actualizacion (el sistema mata y recrea los servicios de accesibilidad
        // seguido, sobre todo en equipos con poca RAM como el CAT S22 Flip), el
        // ticker del flujo quedo cortado junto con el instance viejo y nadie lo
        // vuelve a arrancar: onServiceConnected() no lo miraba. Resultado: el flujo
        // seguia marcado "en curso" con Play Store destapada y la instalacion
        // habilitada, pero SIN pantalla y SIN nadie que lo llevara a un cierre con
        // motivo. En Android 12+ ni siquiera lo rescataba la alarma watchdog (ver
        // UpdateFlowManager.armWatchdog / B.54). Se re-arma el ticker y se redibuja
        // el overlay para que el flujo retome y termine diciendo en que quedo.
        try {
            val ctx = applicationContext
            if (com.ejemplo.locksuite.util.UpdateFlowManager.isPlayStoreFlowRunning(ctx)) {
                val pkg = com.ejemplo.locksuite.util.UpdateFlowManager.currentPackage(ctx)
                if (!pkg.isNullOrBlank()) {
                    com.ejemplo.locksuite.util.UpdateFlowManager.showOverlay(
                        ctx, pkg,
                        com.ejemplo.locksuite.util.UpdateFlowManager.currentStage(ctx)
                    )
                    startUpdateTicker()
                    Log.i(TAG, "Flujo de actualizacion en curso al reconectar: ticker re-armado para $pkg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo re-armar el flujo de actualizacion al reconectar: ${e.message}")
        }

        Log.i(TAG, "✅ LockSuiteAccessibilityService conectado (Programmatic config + XML capabilities)")
    }

    // ──────────────────────────────────────────────
    // Evento principal
    //
    // Orden deliberado: primero lo que descarta el evento con el menor costo posible,
    // después lo caro. Cada `return` temprano ahorra trabajo diez veces por segundo.
    // ──────────────────────────────────────────────
    /**
     * 7/11/2026 (B.112) — RED DE SEGURIDAD. Este método corre en el HILO PRINCIPAL del proceso
     * y el cuerpo es de ~320 líneas sin un `try` general. Una excepción cualquiera (nodo
     * reciclado, preferencia nula, estado inconsistente) no rompe un evento: MATA EL PROCESO, y
     * si el proceso muere con este servicio enlazado Android lo deja en `mCrashedServices`
     * («Activado» en Ajustes pero sin enlazar) hasta que se actualice la app o alguien apague y
     * prenda el interruptor. Ver B.109 y B.112. Perder un evento es barato; perder la
     * accesibilidad del equipo no. Va afuera del cuerpo a propósito: no se tocó ninguna línea de
     * la lógica, solo se renombró (`handleAccessibilityEvent`).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (t: Throwable) {
            try {
                com.ejemplo.locksuite.diag.DiagLog.e(
                    "a11y", "Excepción en onAccessibilityEvent (atrapada para no matar el proceso)", t
                )
            } catch (_: Throwable) { }
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlayManager.isInitialized) return
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        // Ignorar nuestra propia app
        if (packageName == LOCKSUITE_PKG) return

        // Filtro de tipo de evento adelantado: es una comparación de enteros y descarta
        // el evento antes de tocar SharedPreferences o el PackageManager.
        val eventType = ev.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        // LockSuite purgado: el equipo fue liberado y la app está por desinstalarse. El
        // servicio no bloquea, no rebota y no dibuja nada, y se apaga solo (disableSelf)
        // para no quedar a medio andar si la desinstalación se demora. Va después del
        // filtro barato de tipo de evento, y se lee directo (sin la caché de flags) porque
        // es un estado terminal: no tiene que esperar a que la caché venza.
        if (mdmPrefs.getBoolean("locksuite_purged", false)) {
            try {
                if (overlayManager.isBlockingMessageVisible()) overlayManager.hideBlockingMessageOverlay()
                overlayManager.clearAll()
            } catch (_: Exception) {}
            try { disableSelf() } catch (_: Exception) {}
            return
        }

        val f = flags()

        // ──────────────────────────────────────────────
        // LockSuite SUSPENDIDO
        //
        // El administrador levantó todas las restricciones a propósito (modo
        // "como si LockSuite no estuviera"). Mientras dure la suspensión, este
        // servicio no bloquea, no rebota, no automatiza y no dibuja nada: lo
        // único que hace es asegurarse de no dejar ninguna ventana negra suya
        // colgada en pantalla de antes de la suspensión.
        // ──────────────────────────────────────────────
        if (f.suspended) {
            if (overlayManager.isBlockingMessageVisible()) {
                overlayManager.hideBlockingMessageOverlay()
            }
            if (overlayManager.hasRegions("")) {
                overlayManager.clearAll()
            }
            return
        }

        // Bloqueo y automatización durante actualización de apps (UPDATE_APP)
        // NOTA: este check va ANTES del check de pantalla apagada, porque UPDATE_APP
        // puede llegar con la pantalla apagada y necesitamos procesar los eventos.
        val updatingPkg = f.updatingPkg
        if (f.playUpdateActive && !updatingPkg.isNullOrBlank()) {
            // B.97: con la pantalla de bloqueo puesta no se fuerza la pantalla prendida
            // (quedaría encendida en el bolsillo hasta el watchdog) ni se relanza Play
            // Store (se abriría detrás del keyguard, sin que nadie la vea). El flujo espera
            // a que desbloqueen: ver scanAndAct.
            val bloqueado = UpdateFlowManager.isKeyguardLocked(applicationContext)
            // Asegurar que la pantalla esté encendida durante la actualización
            if (!bloqueado) ensureScreenOnForUpdate()
            if (packageName == PKG_PLAY_STORE) {
                handlePlayStoreAutoUpdate(ev, updatingPkg)
                return
            } else if (packageName != "com.google.android.gms" &&
                packageName != "com.google.android.packageinstaller" &&
                packageName != "com.android.packageinstaller" &&
                packageName != "com.android.systemui") {
                // La actualización sigue en curso pero al frente hay otra app.
                //
                // Dos correcciones respecto de la versión anterior:
                //
                //  1. El overlay se dibuja TAMBIÉN acá. Antes solo se dibujaba
                //     dentro de handlePlayStoreAutoUpdate(), o sea únicamente
                //     cuando el evento venía de Play Store: si el usuario salía a
                //     otra app veía esa app sin tapar.
                //
                //  2. El relanzamiento de Play Store está limitado a uno cada
                //     STORE_RELAUNCH_MIN_MS. Antes se relanzaba en CADA evento
                //     (el sistema los manda hasta diez veces por segundo) y encima
                //     con FLAG_ACTIVITY_CLEAR_TASK, así que la tarea de Play Store
                //     se destruía y recreaba sin parar y la descarga no llegaba a
                //     arrancar nunca. Es la causa más probable del síntoma "la app
                //     no se actualiza". Ahora openStore() usa solo NEW_TASK, que
                //     trae la tarea existente al frente en vez de recrearla.
                UpdateFlowManager.showOverlay(
                    applicationContext,
                    updatingPkg,
                    UpdateFlowManager.currentStage(applicationContext)
                )
                // B.99: misma puerta que el ciclo (relaunchStoreIfDue): rápida si Google Play
                // se venía viendo, cada vez más espaciada si no llega a aparecer. Y el motivo
                // dice qué apareció, sin suponer que fue el usuario: en el QEMAY-QM01 era el
                // cartel del sistema de Play Store cerrándose.
                if (!bloqueado) {
                    relaunchStoreIfDue(
                        applicationContext, updatingPkg, SystemClock.elapsedRealtime(),
                        "al frente apareció ${frenteTexto(applicationContext, packageName, false)}"
                    )
                }
                return
            }
        }

        // Garantizar que no consuma batería si la pantalla está inactiva (apagada)
        // Esto va DESPUÉS del check de UPDATE_APP, que sí necesita funcionar con pantalla apagada
        if (!powerManager.isInteractive) {
            return
        }

        // ── Scroll: mantener los recuadros de imágenes pegados al contenido ──
        // TYPE_VIEW_SCROLLED se emite muchísimas veces durante un desplazamiento y no
        // dispara los demás bloqueos (WhatsApp/MP/WebView/Ajustes), así que se maneja
        // aparte y barato. Detalle en handleScrollEvent().
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // 22/9/2026 — el scroll dentro de la ventana del portal cautivo es SEÑAL DE VIDA
            // (el usuario está leyendo los términos del Wi-Fi del avión). B.70 lo quiso
            // contar, pero este `return` salía antes de llegar al código del portal: solo
            // contaba CONTENT_CHANGED, y la ventana se podía cerrar con el usuario en
            // plena lectura. Es una comparación de paquete: gratis en el camino caliente.
            if (f.captivePortalGuard && captiveOpenedAt != 0L &&
                CaptivePortalPolicy.isCaptivePortalWindow(packageName, null)
            ) {
                captiveLastActivityAt = SystemClock.elapsedRealtime()
            }
            handleScrollEvent(ev, packageName, f)
            return
        }

        // Bloqueo de Google Play Store por Accesibilidad fuera del flujo de actualización
        // y fuera de la ventana temporal de instalación.
        // (B.97: la ruta de la Tienda no abre Play Store, así que no la exime).
        val allowPlayTemp = f.tempInstallActive &&
            com.ejemplo.locksuite.mdm.TempInstallWindowManager.isActive(applicationContext)
        if (!f.playUpdateActive && !allowPlayTemp && packageName == PKG_PLAY_STORE && f.playStoreAccessibilityBlocked) {
            Log.w(TAG, "🚫 Bloqueo de Google Play Store por Accesibilidad activo. Bloqueando y regresando a Home...")
            Layer3Audit.anotar(
                applicationContext, "play-store", packageName, null,
                "Bloqueo de Play Store por Accesibilidad activo", true
            )
            overlayManager.hideBlockingMessageOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
            // Con una instalación silenciosa en curso (la Tienda, o la ruta de la Tienda
            // de una actualización) NO se re-imponen las restricciones: le cortaría la
            // instalación a mitad. Esa ventana la cierra quien la abrió.
            if (!f.updateInProgress) policyManager.restoreInstallRestrictions()
            return
        }

        // ── Actualizar stack de paquetes de apps reales ──
        trackPackage(packageName)

        // ── Bloqueo de Estados y Canales en WhatsApp ──
        if ((f.waStatus || f.waChannels) &&
            (packageName == PKG_WHATSAPP || packageName == PKG_WHATSAPP_BUSINESS)) {
            handleWhatsAppBlocking(eventType, ev, packageName, f.waStatus, f.waChannels)
        }

        // ── Bloqueo de Ofertas en Mercado Pago ──
        if (f.mpOffers && packageName == PKG_MERCADOPAGO) {
            handleMercadoPagoBlocking(eventType)
        }

        // ── Ajustes de la cuenta de Google (historial de YouTube, Mi Actividad) ──
        //
        // Costo en el camino caliente: un booleano ya cacheado, una comparación de
        // enteros y a lo sumo tres comparaciones de string sobre el nombre del
        // paquete. Solo al CAMBIAR DE VENTANA, que es cuando aparece una pantalla
        // nueva — no en los CONTENT_CHANGED, que son los que llegan diez por segundo.
        if (f.googleAccountWeb &&
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            GoogleAccountWebPolicy.isCandidatePackage(packageName)
        ) {
            if (handleGoogleAccountWebBounce(ev, packageName, f.googleAccountStrict)) return
        }

        // ── Bloqueo de Gemini en Google Mensajes (switch: block_gemini_messages) ──
        if (f.geminiMessages && packageName == com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.PKG_MESSAGING) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                ev.className?.toString()?.takeIf { it.isNotBlank() }?.let {
                    lastGeminiMessagingWindowClass = it
                }
            }
            handleGeminiMessagesBlocking(eventType)
        }

        // ── Bloqueo quirúrgico de navegación externa en Santander Argentina (switch: block_santander_external) ──
        if (f.santanderBlock && (
                packageName == com.ejemplo.locksuite.mdm.SantanderBlockPolicy.PKG_SANTANDER ||
                (WEBVIEW_PROVIDER_PACKAGES.contains(packageName) &&
                    getOriginApp() == com.ejemplo.locksuite.mdm.SantanderBlockPolicy.PKG_SANTANDER)
            )
        ) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                ev.className?.toString()?.takeIf { it.isNotBlank() }?.let {
                    lastSantanderWindowClass = it
                }
            }
            handleSantanderBlocking(eventType)
        }

        // ── Selector de foto de contacto / Google Illustrations (switch: block_contact_photo_picker) ──
        if (f.contactPhotoPicker && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (handleContactPhotoPickerBounce(ev.className?.toString(), packageName)) return
        }

        // ── Ventana de "Iniciar sesión en la red" (portal cautivo) ──
        // Ver mdm/CaptivePortalPolicy.kt: esta ventana esquiva la VPN por diseño, así
        // que la Capa 2 no puede hacer NADA ahí. Esto es lo único que puede.
        if (f.captivePortalGuard) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                updateCaptivePortalState(packageName, ev.className?.toString())
            } else if (captiveOpenedAt != 0L &&
                (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                 eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                 eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
                CaptivePortalPolicy.isCaptivePortalWindow(packageName, ev.className?.toString())
            ) {
                // Señal de vida de la ventana: la página cambió, o el usuario interactuó
                // (click, scroll), o sea que alguien la está usando. Reinicia el reloj de
                // inactividad (ver IDLE_CLOSE_MS).
                // Va ANTES del antirrebote de CONTENT_CHANGED de abajo a propósito:
                // no queremos que un trámite lento se cierre porque el antirrebote se
                // comió justo los eventos que probaban que el usuario estaba ahí.
                captiveLastActivityAt = SystemClock.elapsedRealtime()
            }
        }

        // Debounce para CONTENT_CHANGED (se dispara muy seguido)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheckedAt < DEBOUNCE_MS) return
            lastCheckedAt = now
        }

        if (VERBOSE) {
            Log.d(TAG, "EVENT pkg=$packageName type=${eventType.toEventName()} stack=${appPackageStack.toList()}")
        }

        // ── Bloqueo de WebView ──
        handleWebViewBlocking(packageName, eventType)

        // ── Rebote de Licencias y Términos Legales en Ajustes (Punto D) ──
        //
        // 4/9 (tarde): tenía un GLOBAL_ACTION_BACK a ciegas, sin antirrebote, sin
        // excepción para el administrador y sin verificar nada. Eso es exactamente la
        // forma del bug de B.15 punto 1 ("rebota mucho más cosas, casi no se puede
        // abrir Ajustes"), que costó una sesión entera. Ahora tiene las dos guardas
        // mínimas que tienen todos los demás rebotes del archivo.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (isSettingsPackage(packageName) || packageName == GoogleAccountWebPolicy.PKG_GMS)
        ) {
            val cls = ev.className?.toString() ?: ""
            if (isLegalOrLicenseScreen(cls) &&
                SystemClock.elapsedRealtime() >= legalBounceBackoffUntil &&
                !com.ejemplo.locksuite.security.SessionManager.isActive()
            ) {
                legalBounceBackoffUntil = SystemClock.elapsedRealtime() + LEGAL_BOUNCE_BACKOFF_MS
                Log.w(TAG, "🚫 Pantalla legal/licencias detectada: rebotando al usuario ($cls).")
                Layer3Audit.anotar(
                    applicationContext, "legales", packageName, cls,
                    "pantalla de licencias o términos legales", true
                )
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }

        // ── Rebote de cambio de idioma por aplicación (Android 13+ / API 33+) ──
        if (f.localeChangeBlocked &&
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            isSettingsPackage(packageName)
        ) {
            val cls = ev.className?.toString() ?: ""
            if ((cls.contains("AppLocalePicker", ignoreCase = true) ||
                 cls.contains("AppLocaleDetails", ignoreCase = true) ||
                 cls.contains("LocalePicker", ignoreCase = true)) &&
                !com.ejemplo.locksuite.security.SessionManager.isActive()
            ) {
                Log.w(TAG, "🚫 Selector de idioma detectado ($cls): rebotando al usuario.")
                Layer3Audit.anotar(
                    applicationContext, "idioma", packageName, cls,
                    "selector de idioma del sistema o por aplicación bloqueado", true
                )
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }

        // ── Rebote selectivo de LockSuite dentro de Accesibilidad en Ajustes ──
        if (!isSettingsPackage(packageName)) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !packageName.contains("inputmethod", ignoreCase = true)
            ) {
                inAccessibilityContext = false
                lastSettingsWindowTitleFolded = ""
            }
        } else if (f.accSettingsBounce) {
            if (handleAccessibilitySettingsBounce(ev, eventType)) return
        }

        // ── Anti-evasión en Ajustes ──
        if (f.settingsEvasion && packageName == SETTINGS_PKG) {
            handleSettingsAntiEvasion()
        }

        // ── Códigos secretos en el marcador ──
        if (packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("phone", ignoreCase = true) ||
            packageName.contains("contact", ignoreCase = true)) {
            handleDialerIntercept()
        }

        // ── Tapado de imágenes en portales cautivos Wi-Fi (Opción B, B.126) ──
        handleCaptivePortalImageCover(packageName)
    }

    // ──────────────────────────────────────────────
    // Camino rápido de scroll (portales cautivos Wi-Fi)
    // ──────────────────────────────────────────────
    private fun handleScrollEvent(ev: AccessibilityEvent, packageName: String, f: Flags) {
        if (f.playUpdateActive) return
        // Chequeo O(1): si no hay nada tapado, este evento no nos interesa.
        if (overlayManager.regionCount() == 0) return

        // 1. Adelanto por delta del propio evento.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dx = ev.scrollDeltaX
            val dy = ev.scrollDeltaY
            // -1 es el valor centinela de "el emisor no informó el delta".
            if (dx != -1 || dy != -1) {
                overlayManager.translateRegions(
                    if (dx == -1) 0 else dx,
                    if (dy == -1) 0 else dy
                )
            }
        }

        // 2. Corrección real, con debounce.
        val nowScroll = SystemClock.elapsedRealtime()
        if (nowScroll - lastImageScrollAt < IMAGE_SCROLL_DEBOUNCE_MS) return
        lastImageScrollAt = nowScroll
        handleCaptivePortalImageCover(packageName)
    }

    // ──────────────────────────────────────────────
    // Tapado de imágenes en portales cautivos Wi-Fi (B.50 / B.126 Opción B)
    // ──────────────────────────────────────────────
    private fun handleCaptivePortalImageCover(packageName: String) {
        if (captiveOpenedAt == 0L) {
            if (overlayManager.hasRegions("captive:")) {
                overlayManager.clearStaleRegions("captive:", emptySet())
            }
            return
        }

        val isProvider = packageName == "com.google.android.webview" || packageName == "com.android.webview"
        val activePkg = if (isProvider) (getOriginApp() ?: packageName) else packageName

        // Mientras la ventana de portal cautivo está al frente se tapan sus <img> no clicables;
        // los controles clicables/editables (Conectar, captcha, planes) quedan visibles.
        val portalCautivo = flags().captivePortalCoverImages &&
            (CaptivePortalPolicy.isCaptivePortalWindow(activePkg, null) || isSystemOrInputPackage(activePkg))

        if (portalCautivo) {
            runCaptivePortalImageCover(activePkg)
        } else if (overlayManager.hasRegions("captive:")) {
            overlayManager.clearStaleRegions("captive:", emptySet())
        }
    }

    private val pathStack = IntArray(MAX_TREE_DEPTH + 2)
    private var nodeBudget = 0

    private fun runCaptivePortalImageCover(activePkg: String) {
        val root = rootInActiveWindow ?: return
        try {
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg == LOCKSUITE_PKG || (rootPkg != activePkg && isSystemOrInputPackage(rootPkg))) {
                // Ignorar escaneo si la ventana activa es nuestra, del sistema o teclado, para no borrar overlays
                return
            }
            val foundKeys = mutableSetOf<String>()
            nodeBudget = MAX_NODES_PER_SCAN
            scanCaptivePortalNode(root, 0, foundKeys)
            overlayManager.clearStaleRegions("captive:", foundKeys)
        } finally {
            root.recycle()
        }
    }

    private fun buildCaptivePortalKey(depth: Int): String {
        val sb = StringBuilder(16)
        sb.append("captive:")
        for (i in 0 until depth) {
            sb.append(pathStack[i])
            sb.append('.')
        }
        return sb.toString()
    }

    private fun scanCaptivePortalNode(node: AccessibilityNodeInfo, depth: Int, foundKeys: MutableSet<String>) {
        if (depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        val className = node.className?.toString()
        if (className == CaptivePortalPolicy.WEB_CONTAINER_CLASS) {
            // El WebView NO se tapa: se baja por sus hijos.
        } else if (className != null && className in CaptivePortalPolicy.WEB_IMAGE_CLASS_NAMES) {
            // Una imagen que además es un control es el botón de "Conectar", el
            // captcha o la tarjeta del plan. Taparla es impedir el login.
            if (!node.isClickable && !node.isEditable) {
                val rectPortal = Rect()
                node.getBoundsInScreen(rectPortal)
                if (!rectPortal.isEmpty && node.isVisibleToUser) {
                    val keyPortal = buildCaptivePortalKey(depth)
                    foundKeys.add(keyPortal)
                    overlayManager.blockRegion(keyPortal, rectPortal)
                }
                return
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                pathStack[depth] = i
                scanCaptivePortalNode(child, depth + 1, foundKeys)
            } finally {
                child.recycle()
            }
        }
    }

    // ──────────────────────────────────────────────
    // Rastreo de paquetes
    // ──────────────────────────────────────────────
    private fun isSystemOrInputPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        // Memorizado: la respuesta para un paquete dado nunca cambia, y calcularla
        // implicaba un lowercase() (que asigna un String nuevo) más ocho contains().
        sysInputCache[pkg]?.let { return it }
        val lower = pkg.lowercase()
        val result = pkg == LOCKSUITE_PKG ||
               pkg == "com.android.systemui" ||
               lower.contains("captiveportal") ||
               lower.contains("inputmethod") ||
               lower.contains("honeyboard") ||
               lower.contains("latin") ||
               lower.contains("gboard") ||
               lower.contains("swiftkey") ||
               lower.contains("keyboard") ||
               lower.contains("ime")
        if (sysInputCache.size < 256) sysInputCache[pkg] = result
        return result
    }

    private fun trackPackage(packageName: String) {
        // Orden a propósito: primero los dos chequeos que son lookups en HashSet en
        // memoria, y recién después el que puede tocar el cache de navegadores.
        if (WEBVIEW_PROVIDER_PACKAGES.contains(packageName)) return
        if (isSystemOrInputPackage(packageName)) return
        if (appPackageStack.firstOrNull() == packageName) return
        if (isBrowserPackage(packageName)) return

        appPackageStack.addFirst(packageName)
        if (appPackageStack.size > 5) appPackageStack.removeLast()
    }

    private fun getOriginApp(): String? = appPackageStack.firstOrNull()

    // ──────────────────────────────────────────────
    // Lógica principal de bloqueo de WebView
    // ──────────────────────────────────────────────
    private fun handleWebViewBlocking(packageName: String, eventType: Int) {
        if (WEBVIEW_PROVIDER_PACKAGES.contains(packageName)) {
            val originApp = getOriginApp()
            if (VERBOSE) Log.d(TAG, "WebView provider detectado. originApp=$originApp")
            if (originApp != null && WebViewBlockManager.isBlocked(this, originApp)) {
                Log.w(TAG, "🚫 Bloqueando WebView de $originApp (provider=$packageName)")
                triggerBlock(originApp)
            }
            return
        }

        if (isBrowserPackage(packageName)) {
            val originApp = getOriginApp()
            if (VERBOSE) Log.d(TAG, "Browser detectado. originApp=$originApp")
            if (originApp != null && originApp != packageName && WebViewBlockManager.isBlocked(this, originApp)) {
                Log.w(TAG, "🚫 Bloqueando Custom Tab/Browser de $originApp (browser=$packageName)")
                triggerBlock(originApp)
            }
            return
        }

        if (!WebViewBlockManager.isBlocked(this, packageName)) {
            // ── B.60 — DETECTOR ESTRUCTURAL DE NAVEGADORES EMBEBIDOS ──
            //
            // Acá abajo está el agujero que B.44 dejó anotado: si la app no está
            // marcada en WebViewBlockManager, hasta hoy se volvía sin mirar nada. O
            // sea que la postura por omisión era PERMITIR, y cada app nueva con un
            // navegador adentro era "una en la que no pensamos".
            //
            // El detector va JUSTO ACÁ y no antes, y eso importa: las dos ramas de
            // arriba (proveedor de WebView y navegador declarado) siguen mandando
            // igual que siempre, así que esto no puede cambiar el comportamiento de
            // nada que ya funcionaba — solo cubre el camino que antes no hacía nada.
            maybeDetectEmbeddedBrowser(packageName, eventType)
            return
        }
        checkAndBlockWebViewInTree(packageName, eventType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DETECTOR ESTRUCTURAL DE NAVEGADORES EMBEBIDOS (10/9/2026, B.60)
    // Ver mdm/EmbeddedBrowserDetector.kt: toda la decisión vive allá, en una
    // función pura con banco de pruebas. Acá está solo el retrato y el freno.
    // ─────────────────────────────────────────────────────────────────────────

    private var lastIabScanPkg: String? = null
    private var lastIabScanAt = 0L

    private fun maybeDetectEmbeddedBrowser(packageName: String, eventType: Int) {
        val f = flags()
        if (!f.iabFinder) return

        // ⚠️ SOLO en cambio de ventana. `onAccessibilityEvent` corre en el HILO
        // PRINCIPAL y el sistema lo llama hasta diez veces por segundo; un detector
        // que recorra el árbol en cada evento es exactamente lo que B.13 tuvo que
        // sacar de acá. Un navegador embebido aparece al abrirse una ventana.
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val now = SystemClock.elapsedRealtime()
        if (lastIabScanPkg == packageName && now - lastIabScanAt < IAB_REARM_MS) return
        lastIabScanPkg = packageName
        lastIabScanAt = now

        val root = rootInActiveWindow ?: return
        val retrato = try {
            buildIabRetrato(root, packageName)
        } catch (e: Exception) {
            Log.w(TAG, "IAB: no se pudo armar el retrato de $packageName: ${e.message}")
            null
        } finally {
            root.recycle()
        } ?: return

        val veredicto = EmbeddedBrowserDetector.evaluar(retrato)
        if (veredicto != EmbeddedBrowserDetector.Veredicto.NAVEGADOR_EMBEBIDO) return

        val motivo = EmbeddedBrowserDetector.motivo(retrato)

        // Se publica SIEMPRE, bloquee o no. Es la mitad que hizo usable el modo lista
        // blanca en B.53: sin la lista de lo que habría bloqueado, encender esto es
        // apostar a ciegas sobre un universo de apps abierto.
        EmbeddedBrowserDetector.anotar(packageName, motivo, bloqueado = !f.iabSimulation)

        if (f.iabSimulation) {
            Log.i(TAG, "IAB (simulación): $packageName sería bloqueado — $motivo")
            return
        }
        Log.w(TAG, "🚫 IAB: navegador embebido en $packageName — $motivo")
        triggerBlock(packageName, "navegador embebido: $motivo")
    }

    /**
     * Recorre el árbol UNA vez y arma el retrato. Con los mismos topes que el resto
     * de los recorridos del archivo (`MAX_TREE_DEPTH`, `MAX_NODES_PER_SCAN`), más
     * topes propios sobre cuántas cadenas se juntan: sin ellos, una pantalla con
     * doscientos campos armaría doscientas cadenas en el hilo principal.
     */
    private fun buildIabRetrato(
        root: AccessibilityNodeInfo,
        packageName: String
    ): EmbeddedBrowserDetector.Retrato {
        nodeBudget = MAX_NODES_PER_SCAN
        val ids = ArrayList<String>(MAX_IAB_STRINGS)
        val editables = ArrayList<String>(MAX_IAB_STRINGS)
        val descripciones = ArrayList<String>(MAX_IAB_STRINGS)
        val pantalla = Rect()
        root.getBoundsInScreen(pantalla)
        val areaPantalla = (pantalla.width().toLong() * pantalla.height().toLong()).coerceAtLeast(1L)
        val mayorWebView = longArrayOf(0L)
        var hayWebView = false

        hayWebView = walkIab(root, 0, ids, editables, descripciones, mayorWebView)

        return EmbeddedBrowserDetector.Retrato(
            packageName = packageName,
            activityClass = currentActivityClass(root),
            // El guard de portal cautivo ya sabe reconocer esa ventana; se reusa su
            // señal en vez de estrenar una segunda forma de detectarla, que es como
            // se termina con dos detecciones que no coinciden.
            esPortalCautivo = CaptivePortalPolicy.isCaptivePortalWindow(packageName, root.className?.toString()),
            tieneWebView = hayWebView,
            fraccionWebView = (mayorWebView[0].toDouble() / areaPantalla.toDouble()).toFloat(),
            idsDeVista = ids,
            textosEditables = editables,
            descripciones = descripciones
        )
    }

    private fun walkIab(
        node: AccessibilityNodeInfo,
        depth: Int,
        ids: ArrayList<String>,
        editables: ArrayList<String>,
        descripciones: ArrayList<String>,
        mayorWebView: LongArray
    ): Boolean {
        if (depth > MAX_TREE_DEPTH) return false
        if (nodeBudget-- <= 0) return false

        var hayWebView = false
        val clase = node.className?.toString()?.lowercase() ?: ""
        if (clase.contains("webview") || clase.contains("webkit") ||
            clase.contains("chromium") || clase.contains("renderframe") || clase.contains("xwalk")
        ) {
            hayWebView = true
            val r = Rect()
            node.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height().toLong()
            if (area > mayorWebView[0]) mayorWebView[0] = area
        }

        if (ids.size < MAX_IAB_STRINGS) {
            node.viewIdResourceName?.let { ids.add(it.lowercase()) }
        }
        if (descripciones.size < MAX_IAB_STRINGS) {
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) descripciones.add(it.lowercase()) }
        }
        // Solo los EDITABLES. Un texto de solo lectura con una dirección adentro es el
        // subtítulo "fuente: diario.com" que muestra medio catálogo de apps; tomarlo
        // como barra de direcciones sería el cuarto falso positivo caro del proyecto.
        if (node.isEditable && editables.size < MAX_IAB_STRINGS) {
            node.text?.toString()?.let { if (it.isNotBlank()) editables.add(it) }
        }

        for (i in 0 until node.childCount) {
            val hijo = node.getChild(i) ?: continue
            try {
                if (walkIab(hijo, depth + 1, ids, editables, descripciones, mayorWebView)) hayWebView = true
            } finally {
                hijo.recycle()
            }
        }
        return hayWebView
    }

    /** Clase de la actividad en pantalla, si se puede saber. Cadena vacía si no. */
    private fun currentActivityClass(root: AccessibilityNodeInfo): String =
        try {
            // `root.className` de la ventana suele ser la clase de la actividad o del
            // layout raíz. No siempre está, y por eso el detector nunca depende SOLO
            // de esto: las exclusiones tienen además su propia señal (el flag de
            // portal cautivo, y el nombre de paquete).
            root.className?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }

    private val webViewRetryRunnables = mutableListOf<Runnable>()
    private var lastRetryArmPkg: String? = null
    private var lastRetryArmAt = 0L

    private fun checkAndBlockWebViewInTree(packageName: String, eventType: Int) {
        // Verificación inmediata sobre la ventana actual.
        val root = rootInActiveWindow
        if (root != null) {
            if (isWindowFromPackage(root, packageName)) {
                val found = containsWebView(root)
                if (VERBOSE) Log.d(TAG, "Verificación inmediata pkg=$packageName webViewFound=$found")
                if (found) {
                    root.recycle()
                    triggerBlock(packageName)
                    return
                }
            }
            root.recycle()
        }

        // La escalera de reintentos existe para atrapar WebViews que cargan tarde.
        // Antes se cancelaba y se volvía a armar en CADA evento: mientras la pantalla
        // tuviera actividad, se rearmaban tres recorridos completos del árbol una y
        // otra vez. Ahora se arma cuando cambia la ventana, o como mucho una vez cada
        // WEBVIEW_REARM_MS por paquete.
        val now = SystemClock.elapsedRealtime()
        val windowChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val staleArm = lastRetryArmPkg != packageName || now - lastRetryArmAt > WEBVIEW_REARM_MS
        if (!windowChanged && !staleArm) return

        lastRetryArmPkg = packageName
        lastRetryArmAt = now

        // Cancelar reintentos previos acumulados para no sobrecargar la CPU con escaneos redundantes
        webViewRetryRunnables.forEach { mainHandler.removeCallbacks(it) }
        webViewRetryRunnables.clear()

        listOf(250L, 800L, 1800L).forEach { delay ->
            val retryRunnable = object : Runnable {
                override fun run() {
                    // 7/11/2026 (B.112): hilo principal; una excepción acá mataba el proceso con el servicio enlazado.
                    com.ejemplo.locksuite.util.Safe.guard("a11y.webViewRetry") {
                        webViewRetryRunnables.remove(this)
                        if (webViewBlockInProgress) return
                        val current = rootInActiveWindow ?: return
                        val currentPkg = current.packageName?.toString() ?: run {
                            current.recycle()
                            return
                        }
                        val relevantPkg = currentPkg == packageName ||
                                          WEBVIEW_PROVIDER_PACKAGES.contains(currentPkg)

                        if (!relevantPkg) {
                            current.recycle()
                            return
                        }

                        val found = containsWebView(current)
                        current.recycle()
                        if (found) {
                            Log.w(TAG, "🚫 WebView detectado con retraso de ${delay}ms para $packageName")
                            triggerBlock(packageName)
                        }
                    }
                }
            }
            webViewRetryRunnables.add(retryRunnable)
            mainHandler.postDelayed(retryRunnable, delay)
        }
    }

    private fun isWindowFromPackage(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val windowPkg = root.packageName?.toString() ?: return false
        return windowPkg == packageName || WEBVIEW_PROVIDER_PACKAGES.contains(windowPkg)
    }

    private fun triggerBlock(packageName: String, motivo: String = "se detectó un WebView en la app") {
        if (webViewBlockInProgress) return
        webViewBlockInProgress = true

        Log.w(TAG, "🛑 triggerBlock para $packageName — $motivo")
        Layer3Audit.anotar(applicationContext, "webview", packageName, null, motivo, true)
        Toast.makeText(this, "Navegador interno bloqueado por políticas del MDM", Toast.LENGTH_SHORT).show()
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            val current = rootInActiveWindow
            if (current != null) {
                val currentPkg = current.packageName?.toString() ?: ""
                val stillThere = currentPkg == packageName ||
                                 (WEBVIEW_PROVIDER_PACKAGES.contains(currentPkg) && getOriginApp() == packageName)
                val hasWebView = containsWebView(current)

                if (VERBOSE) {
                    Log.d(TAG, "PostBack check: currentPkg=$currentPkg stillThere=$stillThere hasWebView=$hasWebView")
                }

                current.recycle()

                if (stillThere && hasWebView) {
                    Log.w(TAG, "🏠 WebView persiste, forzando HOME")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            webViewBlockInProgress = false
        }, 700)
    }

    private fun containsWebView(node: AccessibilityNodeInfo): Boolean {
        nodeBudget = MAX_NODES_PER_SCAN
        return containsWebViewInternal(node, depth = 0)
    }

    private fun containsWebViewInternal(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30) return false
        if (nodeBudget-- <= 0) return false

        val className = node.className?.toString()?.lowercase() ?: ""
        val nodePkg   = node.packageName?.toString()?.lowercase() ?: ""

        val isWebView =
            className.contains("webview") ||
            className.contains("webkit")  ||
            className.contains("chromium")||
            className.contains("renderframe") ||
            className.contains("xwalk")   ||
            className.contains("smtt")    ||
            className.contains("geckoview") ||
            nodePkg == "com.google.android.webview" ||
            nodePkg == "com.android.webview"        ||
            nodePkg == "com.android.chrome"

        if (isWebView) {
            if (VERBOSE) Log.i(TAG, "  🔍 WebView encontrado: class=$className pkg=$nodePkg depth=$depth")
            return true
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsWebViewInternal(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    // ──────────────────────────────────────────────
    // Anti-evasión en Ajustes
    //
    // Antes se recorría el árbol DOS veces enteras: una buscando "locksuite" y otra
    // buscando las 17 acciones peligrosas. Ahora es un solo recorrido que junta las
    // dos señales y corta apenas tiene ambas.
    // ──────────────────────────────────────────────
    private val dangerousSettingsActions = listOf(
        "desactivar", "turn off", "disable",
        "forzar detencion", "force stop", "deshabilitar",
        "quitar administrador", "quitar admin", "desinstalar", "uninstall",
        "הסר", "אלץ עצירה", "עצירה כפויה", "השבת", "ביטול", "הסרת התקנה",
        // La última palabra en yiddish tenía una "n" latina suelta al final
        // ("אומאינסטאלירn") en vez de la nun final hebrea ("ן"): como la coincidencia
        // es por substring sobre texto real de pantalla, esa entrada nunca podía
        // matchear nada y quedaba muerta.
        "אפשטעלן", "דעאקטיקירן", "אומאינסטאלירן"
    )

    private val lockSuiteSettingsMarkers = listOf("locksuite", "lock suite", LOCKSUITE_PKG)

    private class SettingsScanResult {
        var inLockSuite = false
        var dangerous = false
        val done: Boolean get() = inLockSuite && dangerous
    }

    // ──────────────────────────────────────────────
    // Rebote del menú de Accesibilidad de Ajustes  (switch: acc_protect_bounce_settings)
    //
    // Qué es y qué NO es. Esto NO impide desactivar la Accesibilidad: es la primera
    // barrera, no la última. Alguien que reinicie el equipo y corra a Ajustes tiene
    // una ventana en la que este servicio todavía no está levantado y por lo tanto no
    // puede rebotar nada. Por eso va acompañado de las otras tres protecciones (aviso
    // insistente, suspensión de apps y arranque protegido), que sí cubren esa ventana.
    // Lo que sí logra: que el usuario común no llegue nunca a la pantalla del
    // interruptor, y que llegar ahí sea molesto a propósito.
    //
    // Detección, en orden de costo:
    //   1. Nombre de clase de la ventana con "accessibilit" — gratis. Cubre a los
    //      fabricantes que usan una actividad dedicada (Samsung, Xiaomi, etc.).
    //   2. Título de pantalla contra una lista de palabras en 10 idiomas, con tildes
    //      normalizadas y coincidencia por palabra completa. Hace falta porque el
    //      Android puro mete casi todas las sub-pantallas de Ajustes dentro de una
    //      única clase genérica (`com.android.settings.SubSettings`), así que el
    //      nombre de clase no alcanza. El recorrido está topeado a 12 niveles y 400
    //      nodos: los títulos están arriba de todo, no hace falta bajar más.
    //
    // Con sesión de administrador abierta (PIN ingresado hace menos de 5 minutos) no
    // rebota: si no, el propio administrador no podría apagar el servicio a propósito.
    // ──────────────────────────────────────────────
    // ⚠️ RESCRITO EL 18/8/2026 — la versión anterior TAPABA MUCHÍSIMO DE MÁS.
    //
    // Qué hacía mal: recorría TODO el árbol de la ventana buscando la palabra
    // "Accesibilidad" contra una lista en diez idiomas. Pero la pantalla PRINCIPAL de
    // Ajustes tiene "Accesibilidad" como una fila más de su menú... igual que la
    // pantalla de búsqueda, y que varias sub-pantallas. Resultado: rebotaba al usuario
    // de casi cualquier pantalla de Ajustes. Reporte textual del dueño: *"rebota mucho
    // más cosas, casi no se puede abrir la app de ajustes"*. Exacto.
    //
    // Y además la lista de palabras era inútil justo en el caso que importa: el dueño
    // señaló que *"si el usuario cambia el idioma a uno raro se evade todo"*. Una lista
    // de diez idiomas no cubre los ciento y pico que soporta Android.
    //
    // Ahora la detección NO LEE PALABRAS NUESTRAS. Tres señales, todas independientes
    // del idioma, de más barata a más cara:
    //
    //   1. COMPONENTE EXACTO. Se le pregunta al sistema qué actividad atiende
    //      `Settings.ACTION_ACCESSIBILITY_SETTINGS`. Devuelve el nombre de clase real
    //      de ESTE equipo (AOSP, Samsung, Xiaomi, el que sea) y no cambia con el
    //      idioma. Comparar `ev.className` contra eso es exacto y gratis.
    //
    //   2. TÍTULO LOCALIZADO PEDIDO A LA PROPIA APP DE AJUSTES. Se leen sus recursos
    //      (`getResourcesForApplication` + `getIdentifier("accessibility_settings")`) y
    //      se obtiene el título EXACTO en el idioma que el equipo tenga puesto ahora,
    //      sea el que sea. Es la respuesta al problema del idioma: en vez de que
    //      nosotros traduzcamos, le preguntamos a Ajustes cómo se dice en su idioma.
    //      Y se compara SOLO contra el título de la ventana, nunca contra el árbol.
    //
    //   3. NUESTRA PROPIA ETIQUETA + UN INTERRUPTOR. La pantalla donde realmente se
    //      apaga el servicio muestra el nombre de nuestra app junto a un `Switch`.
    //      Nuestro nombre no se traduce, así que esa combinación identifica la pantalla
    //      en cualquier idioma sin depender de Ajustes.
    //
    // La primera línea de defensa real contra el cambio de idioma, igual, es el
    // interruptor "Bloquear cambio de idioma" (`DISALLOW_CONFIG_LOCALE`) que se agregó
    // el 18/8. Esto es la segunda.

    private var accBounceInProgress = false
    private var accBounceBackoffUntil = 0L

    /** Contexto de navegación dentro de Accesibilidad de Ajustes (permite menú principal y otras apps). */
    private var inAccessibilityContext = false
    private var lastSettingsWindowTitleFolded = ""

    /** Cache de las señales que dependen del equipo y del idioma actual. */
    private var accSettingsClasses: Set<String> = emptySet()
    private var accSettingsTitles: Set<String> = emptySet()
    private var accOwnLabelFolded: String = ""
    private var accOwnDescriptionPrefixFolded: String = ""
    private var accSignalsAt = 0L

    /** Cada cuánto se vuelven a resolver componente y título localizado. */
    private val ACC_SIGNALS_TTL_MS = 10 * 60 * 1000L
    private val ACC_WHITESPACE_REGEX = Regex("\\s+")

    private fun isSettingsPackage(pkg: String): Boolean =
        pkg == SETTINGS_PKG || pkg.endsWith(".settings")

    private fun normalizeSpaces(raw: String): String =
        foldAccents(raw).trim().replace(ACC_WHITESPACE_REGEX, " ")

    private fun matchesOwnLabelFolded(folded: String): Boolean {
        if (folded.isEmpty()) return false
        if (accOwnLabelFolded.isNotEmpty() && containsWholeWord(folded, accOwnLabelFolded)) return true
        return containsWholeWord(folded, "locksuite")
    }

    /**
     * Resuelve —y cachea— el nombre de clase de la pantalla de Accesibilidad y su
     * título en el idioma actual del equipo, además de la descripción propia de
     * `accessibility_description` (exclusiva de la pantalla de LockSuite en Accesibilidad).
     */
    private fun refreshAccessibilitySignals(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && accSignalsAt != 0L && now - accSignalsAt < ACC_SIGNALS_TTL_MS) return
        accSignalsAt = now

        val classes = HashSet<String>(4)
        val titles = HashSet<String>(4)

        // 1. ¿Qué actividad atiende "abrir los ajustes de accesibilidad"?
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
            }
            info?.activityInfo?.let { ai ->
                classes.add(ai.name)
                ai.targetActivity?.let { classes.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo resolver la actividad de Accesibilidad: ${e.message}")
        }

        // 2. El título, en el idioma actual, pedido a la app de Ajustes (para detectar el
        //    menú principal de Accesibilidad y distinguirlo sin rebotarlo).
        for (pkg in listOf(SETTINGS_PKG, "com.samsung.android.settings")) {
            try {
                val res = packageManager.getResourcesForApplication(pkg)
                for (name in listOf("accessibility_settings", "accessibility_settings_title")) {
                    val id = res.getIdentifier(name, "string", pkg)
                    if (id != 0) {
                        val value = res.getString(id)
                        if (!value.isNullOrBlank()) titles.add(normalizeSpaces(value))
                    }
                }
            } catch (e: Exception) {
                // Normal: ese paquete puede no existir en este equipo.
            }
        }

        // 3. Nuestra propia etiqueta y nuestra descripción exclusiva del servicio de Accesibilidad.
        accOwnLabelFolded = try {
            val label = packageManager.getApplicationLabel(applicationInfo)?.toString() ?: ""
            normalizeSpaces(label)
        } catch (e: Exception) {
            "locksuite"
        }

        accOwnDescriptionPrefixFolded = try {
            val full = normalizeSpaces(getString(com.ejemplo.locksuite.R.string.accessibility_description))
            if (full.length > 48) full.substring(0, 48).trim() else full
        } catch (e: Exception) {
            ""
        }

        accSettingsClasses = classes
        accSettingsTitles = titles
        if (VERBOSE) {
            Log.d(TAG, "Señales A11y: clases=$classes titulos=$titles etiqueta=$accOwnLabelFolded")
        }
    }

    /**
     * El usuario cambió el idioma (o el tamaño de fuente, o rotó). Lo que importa acá
     * es el idioma: los títulos cacheados quedaron en el idioma anterior.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshAccessibilitySignals(force = true)
    }

    /**
     * Rebote selectivo: permite recorrer el menú general de Accesibilidad y sus demás
     * opciones (TalkBack, lupa, tamaño de fuente, etc.), y rebota ÚNICAMENTE si entra en la
     * ficha propia de LockSuite dentro de Accesibilidad o su diálogo de detención.
     */
    private fun handleAccessibilitySettingsBounce(ev: AccessibilityEvent, eventType: Int): Boolean {
        val isWindowChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isContentChange = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowChange && !isContentChange) return false

        if (isWindowChange) {
            val evTitle = ev.text?.firstOrNull()?.toString()
            lastSettingsWindowTitleFolded = if (!evTitle.isNullOrBlank()) normalizeSpaces(evTitle) else ""
        } else {
            // En CONTENT_CHANGED solo inspeccionamos si estamos navegando Accesibilidad o el título
            // reciente nombra a LockSuite: costo cero en el resto de Ajustes.
            if (!AccessibilityBouncePolicy.shouldScanOnContentChanged(
                    inAccessibilityContext,
                    matchesOwnLabelFolded(lastSettingsWindowTitleFolded)
                )
            ) {
                return false
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (accBounceInProgress || now < accBounceBackoffUntil) return false
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return false

        refreshAccessibilitySignals()

        val cls = ev.className?.toString() ?: ""
        // Camino rápido sin tocar el árbol de nodos para pantallas fuera de Accesibilidad.
        if (isWindowChange &&
            AccessibilityBouncePolicy.isExplicitlyNonAccessibilityClass(cls) &&
            !matchesOwnLabelFolded(lastSettingsWindowTitleFolded)
        ) {
            inAccessibilityContext = false
            return false
        }

        val root = rootInActiveWindow ?: run {
            if (isWindowChange &&
                AccessibilityBouncePolicy.isMainAccessibilityMenu(
                    AccessibilityBouncePolicy.ScreenSnapshot(
                        className = cls,
                        windowTitleFolded = lastSettingsWindowTitleFolded,
                        inAccessibilityContext = inAccessibilityContext,
                        knownAccMenuClasses = accSettingsClasses,
                        knownAccMenuTitles = accSettingsTitles,
                        hasOwnDescription = false,
                        hasOwnLabelInHeader = matchesOwnLabelFolded(lastSettingsWindowTitleFolded),
                        hasOwnLabelInBody = false,
                        hasSwitch = false,
                        hasAccessibilityViewHints = false,
                        hasExcludedSettingsHints = false,
                        isDialog = cls.contains("Dialog", ignoreCase = true)
                    )
                )
            ) {
                inAccessibilityContext = true
            }
            return false
        }

        val action = try {
            val snapshot = buildAccessibilityBounceSnapshot(root, cls, lastSettingsWindowTitleFolded)
            AccessibilityBouncePolicy.classify(snapshot)
        } finally {
            root.recycle()
        }

        when (action) {
            AccessibilityBouncePolicy.Action.ENTER_ACCESSIBILITY_CONTEXT -> {
                inAccessibilityContext = true
                return false
            }
            AccessibilityBouncePolicy.Action.LEAVE_ACCESSIBILITY_CONTEXT -> {
                inAccessibilityContext = false
                return false
            }
            AccessibilityBouncePolicy.Action.IGNORE -> {
                return false
            }
            AccessibilityBouncePolicy.Action.BOUNCE -> {
                inAccessibilityContext = true
                accBounceInProgress = true
                Log.w(TAG, "🚫 Pantalla de LockSuite en Accesibilidad ($cls): rebotando al usuario.")
                Layer3Audit.anotar(
                    applicationContext, "menu-accesibilidad", SETTINGS_PKG,
                    cls.ifBlank { null }, "pantalla de LockSuite en Accesibilidad", true
                )
                performGlobalAction(GLOBAL_ACTION_BACK)

                mainHandler.postDelayed({
                    try {
                        var stillThere = false
                        val current = rootInActiveWindow
                        if (current != null) {
                            try {
                                val pkg = current.packageName?.toString() ?: ""
                                if (isSettingsPackage(pkg)) {
                                    val currentSnap = buildAccessibilityBounceSnapshot(
                                        current,
                                        "",
                                        ""
                                    )
                                    stillThere = AccessibilityBouncePolicy.classify(currentSnap) ==
                                        AccessibilityBouncePolicy.Action.BOUNCE
                                }
                            } finally {
                                current.recycle()
                            }
                        }
                        if (stillThere) {
                            Log.w(TAG, "🏠 La pantalla de LockSuite en Accesibilidad persiste: forzando HOME.")
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            accBounceBackoffUntil = SystemClock.elapsedRealtime() + ACC_BOUNCE_BACKOFF_MS
                        }
                    } finally {
                        accBounceInProgress = false
                    }
                }, 600)

                return true
            }
        }
    }

    private class BounceTreeScan {
        var hasOwnDescription = false
        var hasOwnLabelInHeader = false
        var hasOwnLabelInBody = false
        var hasSwitch = false
        var hasAccessibilityViewHints = false
        var hasExcludedSettingsHints = false
    }

    private fun buildAccessibilityBounceSnapshot(
        root: AccessibilityNodeInfo,
        cls: String,
        windowTitleFolded: String
    ): AccessibilityBouncePolicy.ScreenSnapshot {
        val scan = BounceTreeScan()
        if (matchesOwnLabelFolded(windowTitleFolded)) {
            scan.hasOwnLabelInHeader = true
        }
        nodeBudget = 600
        scanBounceTree(root, 0, inHeader = false, out = scan)

        return AccessibilityBouncePolicy.ScreenSnapshot(
            className = cls,
            windowTitleFolded = windowTitleFolded,
            inAccessibilityContext = inAccessibilityContext,
            knownAccMenuClasses = accSettingsClasses,
            knownAccMenuTitles = accSettingsTitles,
            hasOwnDescription = scan.hasOwnDescription,
            hasOwnLabelInHeader = scan.hasOwnLabelInHeader,
            hasOwnLabelInBody = scan.hasOwnLabelInBody,
            hasSwitch = scan.hasSwitch,
            hasAccessibilityViewHints = scan.hasAccessibilityViewHints,
            hasExcludedSettingsHints = scan.hasExcludedSettingsHints,
            isDialog = cls.contains("Dialog", ignoreCase = true)
        )
    }

    private fun scanBounceTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        inHeader: Boolean,
        out: BounceTreeScan
    ) {
        if (depth > 14 || nodeBudget-- <= 0) return

        val viewIdLower = node.viewIdResourceName?.lowercase()
        var currentInHeader = inHeader
        if (viewIdLower != null) {
            if (!out.hasExcludedSettingsHints &&
                AccessibilityBouncePolicy.EXCLUDED_VIEW_ID_HINTS.any { viewIdLower.contains(it) }
            ) {
                out.hasExcludedSettingsHints = true
            }
            if (!out.hasAccessibilityViewHints &&
                AccessibilityBouncePolicy.ACCESSIBILITY_VIEW_ID_HINTS.any { viewIdLower.contains(it) }
            ) {
                out.hasAccessibilityViewHints = true
            }
            if (!currentInHeader &&
                AccessibilityBouncePolicy.HEADER_VIEW_ID_HINTS.any { viewIdLower.contains(it) }
            ) {
                currentInHeader = true
            }
            if (!out.hasSwitch &&
                (viewIdLower.contains("switch_bar") ||
                    viewIdLower.contains("main_switch_bar") ||
                    viewIdLower.contains("switchwidget"))
            ) {
                out.hasSwitch = true
            }
        }

        if (!out.hasSwitch) {
            val cn = node.className?.toString()
            if (cn != null &&
                (cn.endsWith("Switch") || cn.endsWith("ToggleButton") || cn.endsWith("SwitchCompat"))
            ) {
                out.hasSwitch = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !out.hasOwnLabelInHeader) {
            val pane = node.paneTitle
            if (pane != null && pane.isNotEmpty() && matchesOwnLabelFolded(normalizeSpaces(pane.toString()))) {
                out.hasOwnLabelInHeader = true
                out.hasOwnLabelInBody = true
            }
        }

        val text = node.text
        if (text != null && text.isNotEmpty()) {
            val folded = normalizeSpaces(text.toString())
            if (!out.hasOwnDescription &&
                accOwnDescriptionPrefixFolded.length >= 20 &&
                folded.contains(accOwnDescriptionPrefixFolded)
            ) {
                out.hasOwnDescription = true
            }
            if (matchesOwnLabelFolded(folded)) {
                out.hasOwnLabelInBody = true
                if (currentInHeader) out.hasOwnLabelInHeader = true
            }
        }

        val desc = node.contentDescription
        if (desc != null && desc.isNotEmpty()) {
            val foldedDesc = normalizeSpaces(desc.toString())
            if (!out.hasOwnDescription &&
                accOwnDescriptionPrefixFolded.length >= 20 &&
                foldedDesc.contains(accOwnDescriptionPrefixFolded)
            ) {
                out.hasOwnDescription = true
            }
            if (matchesOwnLabelFolded(foldedDesc)) {
                out.hasOwnLabelInBody = true
                if (currentInHeader) out.hasOwnLabelInHeader = true
            }
        }

        // Parada temprana si ya reunimos la señal inequívoca de rebote o exclusión.
        if (out.hasOwnDescription || out.hasExcludedSettingsHints) return

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (nodeBudget <= 0) return
            val child = node.getChild(i) ?: continue
            try {
                scanBounceTree(child, depth + 1, currentInHeader, out)
            } finally {
                child.recycle()
            }
            if (out.hasOwnDescription || out.hasExcludedSettingsHints) return
        }
    }

    /**
     * Detección de pantallas de Licencias de Código Abierto y Términos Legales (Punto D).
     * Evita que el usuario abra las listas de licencias con hipervínculos web.
     */
    private var legalBounceBackoffUntil = 0L
    private val LEGAL_BOUNCE_BACKOFF_MS = 3_000L

    private fun isLegalOrLicenseScreen(cls: String): Boolean {
        if (cls.isEmpty()) return false
        val lower = cls.lowercase()
        return lower.contains("license") ||
               lower.contains("legalsettings") ||
               lower.contains("opensource") ||
               lower.contains("copyright")
    }

    // ──────────────────────────────────────────────
    // Ajustes de la cuenta de Google  (switch: block_google_account_web)   [4/9/2026]
    //
    // Hallazgo del dueño: Ajustes → Google → Gestionar tu cuenta → Datos y privacidad
    // → Historial de YouTube muestra los videos vistos, con miniatura, DENTRO de
    // Ajustes. El porqué completo y los límites están en `mdm/GoogleAccountWebPolicy.kt`.
    //
    // Esta es la mitad de Capa 3: rebota antes de que la pantalla se vea. La mitad que
    // de verdad cierra es la de Capa 2 (los dominios), porque no depende ni del idioma,
    // ni del fabricante, ni de que este servicio esté prendido.
    //
    // Detección por NOMBRE DE CLASE de la ventana y nada más. Ni un solo texto de
    // pantalla: cambiar el idioma del equipo (B.19) no evade esto. Es la regla de B.19
    // punto 3 — "si hay una señal estructural, usarla antes que una palabra".
    // ──────────────────────────────────────────────

    private var gAccBounceInProgress = false
    private var gAccBounceBackoffUntil = 0L
    private val gAccBounceBackoffMs = 4_000L

    /** Clases de Play services ya anotadas, para no reescribir la preferencia. */
    private val gAccSeenClasses = HashSet<String>(16)
    private val gAccMaxSeen = 12

    /** Devuelve true si rebotó (y por lo tanto el evento ya está atendido). */
    private fun handleGoogleAccountWebBounce(
        ev: AccessibilityEvent,
        packageName: String,
        strict: Boolean
    ): Boolean {
        val cls = ev.className?.toString()
        if (!GoogleAccountWebPolicy.isAccountWebClass(cls, strict)) {
            recordUnknownGoogleClass(packageName, cls)
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (gAccBounceInProgress || now < gAccBounceBackoffUntil) return false
        // El administrador con sesión abierta entra a propósito (mismo criterio que el
        // rebote del menú de Accesibilidad): si no, no podría ni revisar la cuenta del
        // equipo que administra.
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return false

        gAccBounceInProgress = true
        Log.w(TAG, "🚫 Ajustes/actividad de la cuenta de Google ($cls): rebotando al usuario.")
        Layer3Audit.anotar(
            applicationContext, "cuenta-google", packageName, cls,
            if (strict) "pantalla de la cuenta de Google (modo estricto)"
            else "historial / actividad de la cuenta de Google",
            true
        )
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Ajustes de la cuenta de Google restringidos por LockSuite",
                Toast.LENGTH_SHORT
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            // Verificación, igual que en Mercado Pago: un "atrás" a ciegas encadena
            // rebotes. Y acá hay un motivo extra — esta pantalla es un WebView, así
            // que "atrás" puede navegar DENTRO de la página en vez de cerrarla.
            //
            // Solo se escala a HOME si seguimos dentro de Play services. Si el "atrás"
            // nos devolvió a Ajustes, funcionó: no hay que sacar al usuario de Ajustes
            // por completo, que fue exactamente el sobre-bloqueo de B.15 punto 1.
            var stillInGms = false
            val current = rootInActiveWindow
            if (current != null) {
                stillInGms = current.packageName?.toString() == GoogleAccountWebPolicy.PKG_GMS
                current.recycle()
            }
            if (stillInGms) {
                Log.w(TAG, "🏠 La pantalla de la cuenta persiste: forzando HOME.")
                performGlobalAction(GLOBAL_ACTION_HOME)
                gAccBounceBackoffUntil = SystemClock.elapsedRealtime() + gAccBounceBackoffMs
            }
            gAccBounceInProgress = false
        }, 700)

        return true
    }

    /**
     * Anota las clases de Play services que se vieron y no se supieron clasificar.
     *
     * Play services reparte sus pantallas en módulos que se actualizan solos (Chimera),
     * así que el nombre de clase de hoy puede no ser el de mañana. Sin este dato, un
     * equipo donde el rebote no dispare es una sesión entera de adivinanza — que es
     * exactamente lo que pasó con `debugLabels` en B.41.
     *
     * Acotado a propósito: solo Play services (la app de Ajustes tiene cientos de
     * clases y no aportan nada), solo hasta `gAccMaxSeen` clases nuevas por proceso, y
     * con un HashSet en memoria adelante para que a partir de la segunda vez sea un
     * lookup y no una escritura.
     */
    private fun recordUnknownGoogleClass(packageName: String, className: String?) {
        if (packageName != GoogleAccountWebPolicy.PKG_GMS) return
        if (className.isNullOrEmpty()) return
        if (gAccSeenClasses.size >= gAccMaxSeen) return
        if (!gAccSeenClasses.add(className)) return
        try {
            mdmPrefs.edit()
                .putString("google_account_web_seen", gAccSeenClasses.joinToString(","))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar la clase de Play services: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Selector de fotos e ilustraciones de contactos (switch: block_contact_photo_picker)
    //
    // Permite bloquear el selector de fotos de Google Contacts y Google Profile Photo Picker
    // que da acceso a Google Fotos y al catálogo abierto de Google Illustrations.
    // Viene ENCENDIDO de fábrica.
    // ──────────────────────────────────────────────

    private var contactPhotoBounceInProgress = false
    private var contactPhotoBounceBackoffUntil = 0L
    private val contactPhotoBounceBackoffMs = 1_200L

    /**
     * ¿Alguna de las últimas ventanas fue una app de contactos?
     *
     * Se mira el stack y no solo la anterior porque el camino real puede tener un
     * salto en el medio: Contactos → selector → catálogo de ilustraciones. Con tres
     * niveles alcanza y sigue siendo un recorrido de tres strings en memoria.
     */
    private fun cameFromContacts(): Boolean {
        var i = 0
        for (pkg in appPackageStack) {
            if (PhotoPickerPolicy.isContactsPackage(pkg)) return true
            if (++i >= 3) break
        }
        return false
    }

    /**
     * Devuelve true si rebotó (y por lo tanto el evento ya está atendido).
     *
     * ⚠️ NO tiene excepción por sesión de administrador, y es a propósito: esa era la
     * causa número uno del "a veces no rebota" que reportó el dueño. La sesión dura
     * 5 minutos desde que se ingresa el PIN, que es exactamente lo que hay que hacer
     * para encender el interruptor y después ir a probarlo — o sea que el rebote se
     * apagaba solo justo mientras alguien lo probaba. Es el mismo bug que B.15
     * primera corrección puntos 3 y 4 ("no estaban rotas: estaban calladas"). Y acá
     * no hace falta la excepción: un administrador no necesita abrir un selector de
     * fotos. **No volver a agregarla.**
     */
    private fun handleContactPhotoPickerBounce(className: String?, packageName: String): Boolean {
        if (!PhotoPickerPolicy.shouldBounce(packageName, className, cameFromContacts())) {
            recordUnmatchedPickerClass(packageName, className)
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (contactPhotoBounceInProgress || now < contactPhotoBounceBackoffUntil) return false

        contactPhotoBounceInProgress = true
        contactPhotoBounceBackoffUntil = now + contactPhotoBounceBackoffMs
        Log.w(TAG, "🚫 Selector de foto detectado ($className en $packageName): rebotando.")
        // ⚠️ Acá es donde se cerraba Tefilon sin que nadie pudiera saberlo desde el
        // panel: `recordUnmatchedPickerClass` solo anota paquetes "relevantes", y una
        // app cualquiera que caiga por un marcador mal puesto no lo es. Esto anota lo
        // que efectivamente se rebotó, sin filtro previo. Ver `Layer3Audit`.
        Layer3Audit.anotar(
            applicationContext, "selector-de-foto", packageName, className,
            "coincidió un marcador de selector de foto de perfil", true
        )
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Selección de foto bloqueada por LockSuite",
                Toast.LENGTH_SHORT
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            try {
                val root = rootInActiveWindow
                if (root != null) {
                    val activePkg = root.packageName?.toString() ?: ""
                    val activeCls = root.className?.toString()
                    root.recycle()
                    if (PhotoPickerPolicy.shouldBounce(activePkg, activeCls, cameFromContacts())) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                }
            } catch (_: Exception) {}
            contactPhotoBounceInProgress = false
        }, 700L)
        return true
    }

    /**
     * Anota las clases que se vieron en paquetes de selector/contactos y NO se
     * clasificaron. Es el dato con el que se calibra en un equipo donde el rebote no
     * dispare, en vez de adivinar — la misma idea que `debugLabels` de B.41 y que
     * `googleAccountWebSeenClasses` de B.43. **Sin esto, "a veces no rebota" es una
     * sesión entera de adivinanza**, que es exactamente lo que pasó.
     */
    private val pickerSeenClasses = HashSet<String>(16)
    private val pickerMaxSeen = 12

    private fun recordUnmatchedPickerClass(packageName: String, className: String?) {
        if (className.isNullOrEmpty()) return
        if (!PhotoPickerPolicy.isRelevantPackage(packageName)) return
        if (pickerSeenClasses.size >= pickerMaxSeen) return
        if (!pickerSeenClasses.add(className)) return
        try {
            mdmPrefs.edit()
                .putString("photo_picker_seen", pickerSeenClasses.joinToString(","))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar la clase del selector: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Portal cautivo  (switch: captive_portal_guard)   [5/9/2026]
    //
    // Leer primero el comentario de cabecera de `mdm/CaptivePortalPolicy.kt`: esa
    // ventana llama a `bindProcessToNetwork()` y esquiva la VPN por diseño, así que
    // la Capa 2 no puede filtrar NADA ahí. Esto es lo único que sí puede.
    // ──────────────────────────────────────────────

    /** elapsedRealtime en que se vio la ventana del portal por primera vez, o 0. */
    private var captiveOpenedAt = 0L

    /**
     * elapsedRealtime del último cambio de contenido DENTRO de la ventana del portal.
     *
     * Es la señal de "el usuario está trabajando acá". El tope de tiempo cuenta contra
     * esto y no contra `captiveOpenedAt`: ver la nota del 8/9 en `CaptivePortalPolicy`.
     */
    private var captiveLastActivityAt = 0L

    /**
     * elapsedRealtime en que la red se detectó validada por primera vez durante la sesión, o 0.
     */
    private var captiveValidatedAt = 0L
    private var captiveBounceInProgress = false

    private val captiveTickRunnable = object : Runnable {
        override fun run() {
            // 7/11/2026 (B.112): hilo principal; una excepción acá mataba el proceso con el servicio enlazado.
            com.ejemplo.locksuite.util.Safe.guard("a11y.captiveTick") {
                if (captiveOpenedAt == 0L) return  // sin re-encolar: la ventana ya no está
                val ahora = SystemClock.elapsedRealtime()
                val abierta = ahora - captiveOpenedAt
                val inactiva = ahora - captiveLastActivityAt

                // 1. La red validó: la ventana ya no tiene razón de existir.
                //
                //    Nota para quien venga después: Android hace esto solo. El propio
                //    `CaptivePortalLoginActivity` registra un NetworkCallback y en
                //    `onCapabilitiesChanged`, si la red tiene NET_CAPABILITY_VALIDATED,
                //    llama a `done(Result.DISMISSED)` y se cierra. Esta palanca es un
                //    respaldo, no el mecanismo principal — no le agregues agresividad.
                //
                //    En portales de aviones (KLM, Viasat, etc.) la red puede validar antes de
                //    que el usuario termine de aceptar términos o completar el formulario
                //    de la segunda página ("se abre otra página"). Por eso:
                //    - Medimos la gracia DESDE que validó (captiveValidatedAt), no desde que abrió.
                //    - Exigimos además que el usuario no esté interactuando activamente
                //      (al menos 10 segundos sin tocar la pantalla).
                val validada = isNetworkValidated()
                if (validada) {
                    if (captiveValidatedAt == 0L) {
                        captiveValidatedAt = ahora
                    }
                    val validadaTiempo = ahora - captiveValidatedAt
                    val tiempoSinInteraccion = ahora - captiveLastActivityAt
                    if (validadaTiempo >= CaptivePortalPolicy.VALIDATED_GRACE_MS && tiempoSinInteraccion >= 10_000L) {
                        closeCaptivePortal("la red ya está conectada")
                        return
                    }
                } else {
                    captiveValidatedAt = 0L
                }
                // 2. Ventana abandonada: nadie la tocó en todo ese rato.
                if (inactiva >= CaptivePortalPolicy.IDLE_CLOSE_MS) {
                    closeCaptivePortal("la ventana quedó abierta sin usarse")
                    return
                }
                // 3. Techo absoluto, pase lo que pase.
                if (abierta >= CaptivePortalPolicy.MAX_OPEN_MS) {
                    closeCaptivePortal("se agotó el tiempo de inicio de sesión")
                    return
                }
                mainHandler.postDelayed(this, CaptivePortalPolicy.TICK_MS)
            }
        }
    }

    private fun updateCaptivePortalState(packageName: String, className: String?) {
        val esPortal = CaptivePortalPolicy.isCaptivePortalWindow(packageName, className)
        if (esPortal) {
            if (captiveOpenedAt == 0L) {
                captiveOpenedAt = SystemClock.elapsedRealtime()
                captiveLastActivityAt = captiveOpenedAt
                captiveValidatedAt = 0L
                captiveBounceInProgress = false
                try {
                    val p = mdmPrefs
                    p.edit()
                        .putInt("captive_portal_opens", p.getInt("captive_portal_opens", 0) + 1)
                        .putLong("captive_portal_last_open_at", System.currentTimeMillis())
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo anotar la apertura del portal: ${e.message}")
                }
                Log.i(
                    TAG,
                    "Portal cautivo abierto: vigilando (inactividad " +
                        "${CaptivePortalPolicy.IDLE_CLOSE_MS} ms, techo " +
                        "${CaptivePortalPolicy.MAX_OPEN_MS} ms)."
                )
                mainHandler.removeCallbacks(captiveTickRunnable)
                mainHandler.postDelayed(captiveTickRunnable, CaptivePortalPolicy.TICK_MS)
            }
        } else if (captiveOpenedAt != 0L && !isSystemOrInputPackage(packageName)) {
            // Se fue a otra ventana real (no al teclado ni a SystemUI): cerrar el ciclo y sumar el tiempo que estuvo abierta.
            finishCaptivePortalSession()
        }
    }

    private fun finishCaptivePortalSession() {
        val abierta = if (captiveOpenedAt == 0L) 0L else SystemClock.elapsedRealtime() - captiveOpenedAt
        captiveOpenedAt = 0L
        captiveLastActivityAt = 0L
        captiveValidatedAt = 0L
        captiveBounceInProgress = false
        mainHandler.removeCallbacks(captiveTickRunnable)
        if (::overlayManager.isInitialized && overlayManager.hasRegions("captive:")) {
            overlayManager.clearStaleRegions("captive:", emptySet())
        }
        if (abierta <= 0L) return
        try {
            val p = mdmPrefs
            p.edit()
                .putLong("captive_portal_total_ms", p.getLong("captive_portal_total_ms", 0L) + abierta)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar el tiempo del portal: ${e.message}")
        }
    }

    private fun closeCaptivePortal(motivo: String) {
        if (captiveBounceInProgress) return
        captiveBounceInProgress = true
        Log.w(TAG, "🚫 Cerrando la ventana del portal cautivo: $motivo.")
        Layer3Audit.anotar(
            applicationContext, "portal-cautivo", "(ventana de inicio de sesión de red)",
            null, motivo, true
        )
        // Visibilidad (8/9): un cierre forzado es la forma que tiene este guard de
        // dejar a alguien sin poder conectarse. Si el contador sube en un equipo, hay
        // que ir a mirar POR QUÉ antes de que el usuario lo reporte desde un aeropuerto.
        try {
            val p = mdmPrefs
            p.edit()
                .putInt("captive_portal_forced_closes", p.getInt("captive_portal_forced_closes", 0) + 1)
                .putString("captive_portal_last_close_reason", motivo)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar el cierre forzado del portal: ${e.message}")
        }
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Ventana de la red cerrada por LockSuite: $motivo",
                Toast.LENGTH_LONG
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        finishCaptivePortalSession()
    }

    /**
     * ¿La red Wi-Fi ya está validada (o sea, el login del portal funcionó)?
     *
     * Se recorren las redes y se descartan las de transporte VPN. **No se usa
     * `cm.activeNetwork` a secas, a propósito:** con la VPN levantada eso devuelve la
     * red del propio túnel, que es exactamente la trampa que causó la causa 1 de B.18
     * (el resolutor terminaba siendo `fd00::1` y todo daba timeout). La señal buena
     * son dos banderas del sistema sobre la red física: VALIDATED puesta y
     * CAPTIVE_PORTAL sacada.
     */
    private fun isNetworkValidated(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
                val validada = caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                val siguePortal = caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                )
                if (validada && !siguePortal) return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el estado de la red: ${e.message}")
            false
        }
    }

    private fun handleSettingsAntiEvasion() {
        val root = rootInActiveWindow ?: return
        val result = SettingsScanResult()
        nodeBudget = MAX_NODES_PER_SCAN
        scanSettingsNode(root, 0, result)
        root.recycle()

        if (result.inLockSuite && result.dangerous) {
            Layer3Audit.anotar(
                applicationContext, "anti-evasion", SETTINGS_PKG, null,
                "pantalla de Ajustes sobre LockSuite con una acción peligrosa a la vista", true
            )
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, "Acción denegada por políticas de seguridad de LockSuite MDM", Toast.LENGTH_LONG).show()
            val loginIntent = Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(loginIntent)
        }
    }

    private fun scanSettingsNode(node: AccessibilityNodeInfo, depth: Int, out: SettingsScanResult) {
        if (out.done || depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        val text = node.text?.let { foldAccents(it) }
        val desc = node.contentDescription?.let { foldAccents(it) }

        if (text != null || desc != null) {
            if (!out.inLockSuite) {
                out.inLockSuite = lockSuiteSettingsMarkers.any {
                    (text != null && text.contains(it)) || (desc != null && desc.contains(it))
                }
            }
            if (!out.dangerous) {
                out.dangerous = dangerousSettingsActions.any {
                    (text != null && text.contains(it)) || (desc != null && desc.contains(it))
                }
            }
            if (out.done) return
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanSettingsNode(child, depth + 1, out)
            } finally {
                child.recycle()
            }
            if (out.done) return
        }
    }

    // ──────────────────────────────────────────────
    // Interceptor del marcador (códigos secretos)
    // ──────────────────────────────────────────────
    private fun handleDialerIntercept() {
        val root = rootInActiveWindow ?: return
        // 27/9/2026 (batería): UN recorrido para los dos códigos y sin pasar cada texto a
        // minúscula. Antes eran dos recorridos completos por evento, y esto corre en cada
        // evento de la pantalla de llamada (el cronómetro cambia una vez por segundo). Mismo
        // resultado, probado contra la versión vieja: ver mdm/DialerCodeScan.kt.
        val resultado = try {
            com.ejemplo.locksuite.mdm.DialerCodeScan.buscar(
                root,
                maxProfundidad = MAX_TREE_DEPTH,
                maxNodos = MAX_NODES_PER_SCAN,
                cantidadHijos = { it.childCount },
                hijo = { nodo, i -> nodo.getChild(i) },
                soltar = { it.recycle() },
                texto = { it.text },
                descripcion = { it.contentDescription }
            )
        } finally {
            root.recycle()
        }
        val isOpenCode = resultado == com.ejemplo.locksuite.mdm.DialerCodeScan.Resultado.ABRIR
        val isEmergencyCode = resultado == com.ejemplo.locksuite.mdm.DialerCodeScan.Resultado.EMERGENCIA

        if (isOpenCode) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val intent = Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
        } else if (isEmergencyCode) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val intent = Intent(this, com.ejemplo.locksuite.ui.emergency.EmergencyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
        }
    }

    private fun Int.toEventName(): String = when (this) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   -> "STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED          -> "SCROLLED"
        AccessibilityEvent.TYPE_VIEW_SELECTED          -> "VIEW_SELECTED"
        else -> "OTHER($this)"
    }

    private var lastWaWindowClassName: String? = null
    private var lastWaPkgName: String = PKG_WHATSAPP
    private var waBackoffUntil = 0L
    private var waFailedBounces = 0
    private var lastWaRedirectAt = 0L
    private val waTempRect = Rect()

    private class WaTreeScanState(val rootHeight: Int) {
        val viewIds = HashSet<String>(32)
        val tabs = ArrayList<com.ejemplo.locksuite.mdm.WhatsAppPolicy.TabCandidate>(6)
        var conversationSubtitle: String? = null
        var remainingBudget = 220
    }

    private fun handleWhatsAppBlocking(
        eventType: Int,
        event: AccessibilityEvent,
        pkg: String,
        blockStatus: Boolean,
        blockChannels: Boolean
    ) {
        lastWaPkgName = pkg
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventCls = event.className?.toString()
            if (!eventCls.isNullOrBlank() && eventCls.startsWith("com.whatsapp", ignoreCase = true)) {
                lastWaWindowClassName = eventCls
            }
            val effectiveCls = eventCls?.takeIf { it.startsWith("com.whatsapp", ignoreCase = true) }
                ?: lastWaWindowClassName

            // Paso 1 (costo cero): decisión rápida por nombre de Activity sin recorrer el árbol.
            val actCat = com.ejemplo.locksuite.mdm.WhatsAppPolicy.classifyActivity(effectiveCls)
            when (actCat) {
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.ActivityCategory.SAFE -> return
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.ActivityCategory.STATUS -> {
                    if (blockStatus) {
                        triggerWhatsAppBlock(
                            pkg, effectiveCls,
                            com.ejemplo.locksuite.mdm.WhatsAppPolicy.Decision(
                                com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_STATUS,
                                "activity:status"
                            )
                        )
                        return
                    }
                }
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.ActivityCategory.CHANNEL -> {
                    if (blockChannels) {
                        triggerWhatsAppBlock(
                            pkg, effectiveCls,
                            com.ejemplo.locksuite.mdm.WhatsAppPolicy.Decision(
                                com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_CHANNEL,
                                "activity:channel"
                            )
                        )
                        return
                    }
                }
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.ActivityCategory.CHANNEL_STATUS -> {
                    if (blockChannels || blockStatus) {
                        val action = if (blockChannels) {
                            com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_CHANNEL
                        } else {
                            com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_STATUS
                        }
                        triggerWhatsAppBlock(
                            pkg, effectiveCls,
                            com.ejemplo.locksuite.mdm.WhatsAppPolicy.Decision(action, "activity:channel_status")
                        )
                        return
                    }
                }
                else -> Unit
            }

            // Paso 2: evaluación estructural acotada (Novedades, Canales en Conversation, visores embebidos).
            scanWhatsAppTree(blockStatus, blockChannels, pkg, effectiveCls)
            return
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            scanWhatsAppTree(blockStatus, blockChannels, pkg, lastWaWindowClassName)
            return
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mainHandler.removeCallbacks(whatsappScanRunnable)
            mainHandler.postDelayed(whatsappScanRunnable, 350)
        }
    }

    private fun scanWhatsAppTree(
        blockStatus: Boolean,
        blockChannels: Boolean,
        pkg: String,
        activityClass: String?
    ) {
        if (com.ejemplo.locksuite.mdm.WhatsAppPolicy.classifyActivity(activityClass) ==
            com.ejemplo.locksuite.mdm.WhatsAppPolicy.ActivityCategory.SAFE
        ) {
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg != PKG_WHATSAPP && rootPkg != PKG_WHATSAPP_BUSINESS) return

            val snapshot = buildWhatsAppSnapshot(root, activityClass)
            val decision = com.ejemplo.locksuite.mdm.WhatsAppPolicy.evaluate(
                snapshot, blockStatus, blockChannels
            )
            when (decision.action) {
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.ALLOW -> {
                    waFailedBounces = 0
                }
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.REDIRECT_TO_CHATS -> {
                    redirectAwayFromUpdatesTab(root, rootPkg, decision.reason)
                }
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_STATUS,
                com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_CHANNEL -> {
                    triggerWhatsAppBlock(rootPkg, activityClass, decision)
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun buildWhatsAppSnapshot(
        root: AccessibilityNodeInfo,
        activityClass: String?
    ): com.ejemplo.locksuite.mdm.WhatsAppPolicy.ScreenSnapshot {
        root.getBoundsInScreen(waTempRect)
        val rootHeight = if (waTempRect.height() > 0) waTempRect.height() else resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val state = WaTreeScanState(rootHeight)
        collectWhatsAppNode(root, 0, inNavChrome = false, insideList = false, inSelectedNavTab = false, state = state)
        return com.ejemplo.locksuite.mdm.WhatsAppPolicy.ScreenSnapshot(
            activityClass = activityClass,
            viewIds = state.viewIds,
            tabs = state.tabs,
            conversationSubtitle = state.conversationSubtitle
        )
    }

    private fun collectWhatsAppNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        inNavChrome: Boolean,
        insideList: Boolean,
        inSelectedNavTab: Boolean,
        state: WaTreeScanState
    ) {
        if (depth > 18 || state.remainingBudget-- <= 0) return
        if (depth > 0 && !node.isVisibleToUser) return

        val shortId = com.ejemplo.locksuite.mdm.WhatsAppPolicy.normalizeViewId(node.viewIdResourceName)
        if (shortId.isNotEmpty() && state.viewIds.size < 64) {
            state.viewIds.add(shortId)
        }

        if (shortId == com.ejemplo.locksuite.mdm.WhatsAppPolicy.ID_CONVERSATION_SUBTITLE_RAW &&
            state.conversationSubtitle == null
        ) {
            val sub = node.text?.toString()?.trim()
            if (!sub.isNullOrEmpty()) state.conversationSubtitle = sub
        }

        val cls = node.className?.toString() ?: ""
        val nowInsideList = insideList ||
            cls.endsWith("RecyclerView") ||
            cls.endsWith("ListView") ||
            shortId == "list" ||
            shortId == com.ejemplo.locksuite.mdm.WhatsAppPolicy.normalizeViewId("updates_list")

        val isNavContainer = shortId in com.ejemplo.locksuite.mdm.WhatsAppPolicy.NAV_CONTAINER_IDS ||
            shortId.startsWith("tabbarbutton_") ||
            shortId.startsWith("navigation_bar_item_")
        val nowInNav = inNavChrome || (!nowInsideList && isNavContainer)

        var nowSelectedNavTab = inSelectedNavTab && nowInNav
        if (!nowInsideList && state.tabs.size < 16) {
            val selected = node.isSelected
            val labelText = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            if (nowInNav) {
                if (selected) nowSelectedNavTab = true
                if (selected || shortId.startsWith("tabbarbutton_") || (nowSelectedNavTab && labelText.isNotEmpty())) {
                    state.tabs.add(
                        com.ejemplo.locksuite.mdm.WhatsAppPolicy.TabCandidate(
                            viewId = shortId,
                            label = labelText,
                            isSelected = selected || nowSelectedNavTab,
                            inNavChrome = true
                        )
                    )
                }
            } else if (selected) {
                node.getBoundsInScreen(waTempRect)
                val inChromeBand = !waTempRect.isEmpty && (
                    waTempRect.bottom >= (state.rootHeight * 0.84f).toInt() ||
                        waTempRect.top <= (state.rootHeight * 0.20f).toInt()
                    )
                if (inChromeBand) {
                    nowSelectedNavTab = true
                    if (labelText.isNotEmpty()) {
                        state.tabs.add(
                            com.ejemplo.locksuite.mdm.WhatsAppPolicy.TabCandidate(
                                viewId = shortId,
                                label = labelText,
                                isSelected = true,
                                inNavChrome = true
                            )
                        )
                    }
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (state.remainingBudget <= 0) return
            val child = node.getChild(i) ?: continue
            try {
                collectWhatsAppNode(child, depth + 1, nowInNav, nowInsideList, nowSelectedNavTab, state)
            } finally {
                child.recycle()
            }
        }
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            val parent = current.parent
            var clicked = false
            if (current.isClickable) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current.recycle()
            if (clicked) {
                parent?.recycle()
                return true
            }
            current = parent
            depth++
        }
        current?.recycle()
        return false
    }

    private fun findChatsButtonInNav(node: AccessibilityNodeInfo, depth: Int, inNav: Boolean, insideList: Boolean): AccessibilityNodeInfo? {
        if (depth > 14 || nodeBudget-- <= 0) return null
        val shortId = com.ejemplo.locksuite.mdm.WhatsAppPolicy.normalizeViewId(node.viewIdResourceName)
        val cls = node.className?.toString() ?: ""
        val nowList = insideList || cls.endsWith("RecyclerView") || cls.endsWith("ListView")
        if (nowList) return null

        val nowNav = inNav ||
            shortId in com.ejemplo.locksuite.mdm.WhatsAppPolicy.NAV_CONTAINER_IDS ||
            shortId.startsWith("tabbarbutton_") ||
            shortId.startsWith("navigation_bar_item_")

        if (nowNav) {
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (com.ejemplo.locksuite.mdm.WhatsAppPolicy.isChatsTabLabel(label)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            if (nodeBudget <= 0) return null
            val child = node.getChild(i) ?: continue
            val hit = try {
                findChatsButtonInNav(child, depth + 1, nowNav, nowList)
            } finally {
                child.recycle()
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun redirectAwayFromUpdatesTab(root: AccessibilityNodeInfo, pkg: String, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWaRedirectAt < 500L) return
        lastWaRedirectAt = now

        Layer3Audit.anotar(
            applicationContext, "whatsapp", pkg, "Novedades",
            "pestaña Novedades restringida ($reason)", true
        )

        var chatsNode: AccessibilityNodeInfo? = null
        val byId = try {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/${com.ejemplo.locksuite.mdm.WhatsAppPolicy.ID_TAB_CHATS_RAW}")
        } catch (_: Exception) {
            emptyList()
        }
        for (n in byId) {
            if (chatsNode == null) chatsNode = n else n.recycle()
        }
        if (chatsNode == null) {
            nodeBudget = 180
            chatsNode = findChatsButtonInNav(root, 0, inNav = false, insideList = false)
        }

        val clickOk = clickNodeOrClickableParent(chatsNode)
        if (!clickOk) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun triggerWhatsAppBlock(
        pkg: String,
        activityClass: String?,
        decision: com.ejemplo.locksuite.mdm.WhatsAppPolicy.Decision
    ) {
        val now = SystemClock.elapsedRealtime()
        if (waBlockInProgress || now < waBackoffUntil) return
        waBlockInProgress = true

        val tipoLabel = when (decision.action) {
            com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_CHANNEL -> "Canal"
            else -> "Estado"
        }
        val detail = activityClass?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: tipoLabel
        Layer3Audit.anotar(
            applicationContext, "whatsapp", pkg, detail,
            "sección restringida de WhatsApp ($tipoLabel · ${decision.reason})", true
        )
        mainHandler.post {
            Toast.makeText(applicationContext, "$tipoLabel bloqueado por políticas del MDM", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            try {
                com.ejemplo.locksuite.util.Safe.guard("a11y.waPostBack") {
                    val f = flags()
                    val currentRoot = rootInActiveWindow
                    val stillBlocked = if (currentRoot == null) {
                        false
                    } else {
                        try {
                            val currentPkg = currentRoot.packageName?.toString() ?: ""
                            if (currentPkg != PKG_WHATSAPP && currentPkg != PKG_WHATSAPP_BUSINESS) {
                                false
                            } else {
                                val currentCls = lastWaWindowClassName
                                val snap = buildWhatsAppSnapshot(currentRoot, currentCls)
                                val postDec = com.ejemplo.locksuite.mdm.WhatsAppPolicy.evaluate(
                                    snap, f.waStatus, f.waChannels
                                )
                                postDec.action == com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_STATUS ||
                                    postDec.action == com.ejemplo.locksuite.mdm.WhatsAppPolicy.Action.BLOCK_CHANNEL
                            }
                        } finally {
                            currentRoot.recycle()
                        }
                    }

                    if (stillBlocked) {
                        waFailedBounces++
                        if (waFailedBounces == 1) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        } else if (waFailedBounces >= 2) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            waFailedBounces = 0
                            waBackoffUntil = SystemClock.elapsedRealtime() + 2_500L
                        }
                    } else {
                        waFailedBounces = 0
                    }
                }
            } finally {
                waBlockInProgress = false
            }
        }, 500)
    }

    // ──────────────────────────────────────────────
    // Bloqueo de Ofertas en Mercado Pago
    // ──────────────────────────────────────────────
    private val mercadoPagoScanRunnable = Runnable {
        // 7/11/2026 (B.112): hilo principal; una excepción acá mataba el proceso con el servicio enlazado.
        com.ejemplo.locksuite.util.Safe.guard("a11y.mercadoPagoScan") {
            if (flags().mpOffers) {
                scanForMercadoPagoOffers()
            }
        }
    }

    private var mpFailedBounces = 0
    private var mpBackoffUntil = 0L

    private fun handleMercadoPagoBlocking(eventType: Int) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            scanForMercadoPagoOffers()
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mainHandler.removeCallbacks(mercadoPagoScanRunnable)
            mainHandler.postDelayed(mercadoPagoScanRunnable, 350)
        }
    }

    private fun scanForMercadoPagoOffers() {
        if (mpBlockInProgress) return
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""

        if (rootPkg == PKG_MERCADOPAGO || WEBVIEW_PROVIDER_PACKAGES.contains(rootPkg)) {
            val retrato = buildMpRetrato(root)
            root.recycle()

            val veredicto = MercadoPagoOffersPolicy.evaluar(retrato)
            anotarMpSiCambio(retrato, veredicto)

            if (veredicto == MercadoPagoOffersPolicy.Veredicto.OFERTAS) {
                triggerMercadoPagoBlock(MercadoPagoOffersPolicy.motivo(retrato))
            }
        } else {
            root.recycle()
        }
    }

    /**
     * Arma el retrato de la pantalla en UN recorrido y deja la decisión en
     * `MercadoPagoOffersPolicy` (función pura, con banco de pruebas).
     *
     * Lo que este recorrido hace distinto de la versión anterior, y es el arreglo del
     * sobre-bloqueo del 16/9:
     *
     *  • **Solo junta textos CORTOS.** Un título de sección entra en un renglón; la
     *    respuesta del asistente, la descripción de un movimiento o la letra chica de
     *    una promoción, no. Antes cualquier nodo de texto contaba igual, así que una
     *    frase del asistente que nombrara un descuento valía lo mismo que el título
     *    "Ofertas y descuentos". Los textos largos no se guardan: solo se levanta la
     *    bandera `hayTextoLargo` para la auditoría. Eso además **achica** el trabajo
     *    del camino caliente en vez de agrandarlo.
     *  • **Anota si hay un campo editable.** Es el veto estructural que arregla el
     *    asistente sin depender de qué conteste (regla de B.19 punto 3).
     *
     * Topes: los mismos `MAX_TREE_DEPTH` / `MAX_NODES_PER_SCAN` que el resto de los
     * recorridos del archivo, más un tope propio de cadenas para que una pantalla con
     * doscientos nodos de texto no arme doscientas cadenas en el hilo principal.
     */
    private fun buildMpRetrato(root: AccessibilityNodeInfo): MercadoPagoOffersPolicy.Retrato {
        val estado = MpRetratoState()
        nodeBudget = MAX_NODES_PER_SCAN
        scanMercadoPagoNode(root, 0, estado)
        return MercadoPagoOffersPolicy.Retrato(
            idsDeVista = estado.ids,
            textosCortos = estado.textos,
            hayTextoLargo = estado.hayTextoLargo,
            hayCampoEditable = estado.hayCampoEditable,
            esPantallaWeb = estado.esPantallaWeb
        )
    }

    private class MpRetratoState {
        val ids = ArrayList<String>(MAX_MP_CADENAS)
        val textos = ArrayList<String>(MAX_MP_CADENAS)
        var hayTextoLargo = false
        var hayCampoEditable = false
        var esPantallaWeb = false
    }

    private fun scanMercadoPagoNode(node: AccessibilityNodeInfo, depth: Int, estado: MpRetratoState) {
        if (depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        val className = node.className?.toString()
        if (className != null &&
            (className.contains("WebkitPageActivity", ignoreCase = true) ||
             className.contains("mlwebkit", ignoreCase = true))) {
            // ⚠️ Esto ya NO baja ningún umbral. Era la causa del sobre-bloqueo: casi
            // toda Mercado Pago es una pantalla web, así que "pantalla web + una
            // palabra débil" no era una red de seguridad, era la regla principal.
            // Queda solo como dato para la auditoría del panel.
            estado.esPantallaWeb = true
        }

        if (!estado.hayCampoEditable && node.isEditable) estado.hayCampoEditable = true

        val viewId = node.viewIdResourceName
        if (viewId != null && estado.ids.size < MAX_MP_CADENAS) {
            estado.ids.add(viewId.lowercase())
        }

        val rawText = node.text
        if (rawText != null && rawText.isNotEmpty()) agregarTextoMp(rawText, estado)
        val rawDesc = node.contentDescription
        if (rawDesc != null && rawDesc.isNotEmpty()) agregarTextoMp(rawDesc, estado)

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanMercadoPagoNode(child, depth + 1, estado)
            } finally {
                child.recycle()
            }
        }
    }

    private fun agregarTextoMp(cs: CharSequence, estado: MpRetratoState) {
        // El corte por longitud se hace ANTES de plegar acentos: plegar cuesta una
        // asignación por cadena y no hace falta sobre algo que se va a descartar.
        if (cs.length > MercadoPagoOffersPolicy.MAX_CARACTERES_TITULO) {
            estado.hayTextoLargo = true
            return
        }
        if (estado.textos.size >= MAX_MP_CADENAS) return
        estado.textos.add(MercadoPagoOffersPolicy.plegarAcentos(cs))
    }

    /**
     * Publica al panel qué decidió la política y por qué — bloquee o no.
     *
     * **Es la pieza que faltaba y la razón por la que este reporte costó una sesión
     * entera.** Hasta hoy, "Mercado Pago me saca de una pantalla" no se podía
     * diagnosticar desde el panel: había que adivinar qué palabra la había disparado.
     * Ahora el motivo exacto (la frase, el id de vista o las dos palabras) queda
     * escrito, y agregar o sacar una entrada de las listas deja de ser una discusión.
     *
     * Solo se escribe cuando el motivo CAMBIA: `scanForMercadoPagoOffers()` puede
     * correr cada 350 ms, y escribir preferencias a ese ritmo sería justo la clase de
     * costo por evento que B.13 sacó de este archivo.
     */
    private var mpUltimoMotivo: String? = null

    private fun anotarMpSiCambio(
        retrato: MercadoPagoOffersPolicy.Retrato,
        veredicto: MercadoPagoOffersPolicy.Veredicto
    ) {
        val motivo = MercadoPagoOffersPolicy.motivo(retrato)
        if (motivo == mpUltimoMotivo) return
        mpUltimoMotivo = motivo
        val bloquea = veredicto == MercadoPagoOffersPolicy.Veredicto.OFERTAS
        Layer3Audit.anotar(
            applicationContext,
            origen = "mp-ofertas",
            paquete = PKG_MERCADOPAGO,
            detalle = if (retrato.esPantallaWeb) "pantalla web" else "pantalla nativa",
            motivo = motivo,
            bloqueado = bloquea
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers de texto.
    //
    // 16/9/2026: la implementación canónica se mudó a `MercadoPagoOffersPolicy`, que
    // es puro y tiene banco de pruebas. Acá quedan dos delegaciones de una línea
    // porque el resto del archivo (pestañas de WhatsApp, rebote del menú de
    // Accesibilidad) las usa. **Dos copias del mismo algoritmo es exactamente lo que
    // causó la causa 1 de B.18** (faltaba `fd00::1` en una de las dos listas de
    // exclusión del túnel) — por eso se delega en vez de duplicar.
    // ──────────────────────────────────────────────────────────────────────────

    /** Coincidencia por palabra completa. Ver `MercadoPagoOffersPolicy`. */
    private fun containsWholeWord(haystack: String, word: String): Boolean =
        MercadoPagoOffersPolicy.contienePalabraCompleta(haystack, word)

    /** Minúsculas sin tildes, en un solo recorrido. Ver `MercadoPagoOffersPolicy`. */
    private fun foldAccents(cs: CharSequence): String =
        MercadoPagoOffersPolicy.plegarAcentos(cs)

    /**
     * Rebote de la sección de ofertas.
     *
     * Antes esto era un GLOBAL_ACTION_BACK a ciegas: no se verificaba si había servido,
     * y como el escaneo vuelve a los 350 ms, si el "atrás" no salía de la sección se
     * encadenaban rebotes hasta sacar al usuario de Mercado Pago por completo. Ahora:
     * un "atrás", se verifica, un segundo intento, y recién entonces HOME — más una
     * pausa de MP_BACKOFF_MS para que no quede girando.
     */
    private fun triggerMercadoPagoBlock(motivo: String) {
        val now = SystemClock.elapsedRealtime()
        if (mpBlockInProgress || now < mpBackoffUntil) return
        mpBlockInProgress = true

        Log.w(TAG, "🚫 Ofertas de Mercado Pago: rebotando — $motivo")
        mainHandler.post {
            Toast.makeText(applicationContext, "🚫 Sección de Ofertas restringida por LockSuite", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            val current = rootInActiveWindow
            val stillOnOffers = if (current == null) {
                false
            } else {
                val pkg = current.packageName?.toString() ?: ""
                val relevant = pkg == PKG_MERCADOPAGO || WEBVIEW_PROVIDER_PACKAGES.contains(pkg)
                val offers = relevant &&
                    MercadoPagoOffersPolicy.evaluar(buildMpRetrato(current)) ==
                        MercadoPagoOffersPolicy.Veredicto.OFERTAS
                current.recycle()
                offers
            }

            if (stillOnOffers) {
                mpFailedBounces++
                if (mpFailedBounces >= 2) {
                    Log.w(TAG, "🏠 La sección de ofertas persiste tras dos intentos, forzando HOME")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    mpFailedBounces = 0
                    mpBackoffUntil = SystemClock.elapsedRealtime() + MP_BACKOFF_MS
                }
            } else {
                mpFailedBounces = 0
            }
            mpBlockInProgress = false
        }, 700)
    }

    // ──────────────────────────────────────────────
    // Bloqueo de Gemini en Google Mensajes (Capa 3)
    // ──────────────────────────────────────────────
    private var geminiBlockInProgress = false
    private var geminiFailedBounces = 0
    private var geminiBackoffUntil = 0L
    private var lastGeminiMessagingWindowClass: String? = null
    private val GEMINI_BACKOFF_MS = 2500L

    private val geminiMessagesScanRunnable = Runnable {
        // 7/11/2026 (B.112): hilo principal; una excepción acá mataba el proceso con el servicio enlazado.
        com.ejemplo.locksuite.util.Safe.guard("a11y.geminiMessagesScan") {
            scanForGeminiMessages()
        }
    }

    private fun handleGeminiMessagesBlocking(eventType: Int) {
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED) {
            mainHandler.removeCallbacks(geminiMessagesScanRunnable)
            scanForGeminiMessages()
        }
        if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mainHandler.removeCallbacks(geminiMessagesScanRunnable)
            mainHandler.postDelayed(geminiMessagesScanRunnable, 300)
        }
    }

    private fun scanForGeminiMessages() {
        if (geminiBlockInProgress) return
        val root = rootInActiveWindow ?: return
        try {
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg == com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.PKG_MESSAGING) {
                val retrato = buildGeminiRetrato(root)
                val veredicto = com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.evaluar(retrato)
                if (veredicto == com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.Veredicto.GEMINI) {
                    triggerGeminiMessagesBlock(
                        com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.motivo(retrato),
                        retrato.isConversationScreen
                    )
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun triggerGeminiMessagesBlock(motivo: String, isConversation: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (geminiBlockInProgress || now < geminiBackoffUntil) return
        geminiBlockInProgress = true

        val windowTag = lastGeminiMessagingWindowClass
            ?: if (isConversation) "ConversationActivity" else "GeminiOnboarding"
        android.util.Log.w(TAG, "🚫 Gemini en Google Mensajes: rebotando ($windowTag) — $motivo")
        com.ejemplo.locksuite.mdm.Layer3Audit.anotar(
            applicationContext, "gemini-mensajes", com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.PKG_MESSAGING,
            windowTag, motivo, true
        )
        mainHandler.post {
            android.widget.Toast.makeText(
                applicationContext,
                "🚫 Gemini en Mensajes restringido por LockSuite",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            try {
                com.ejemplo.locksuite.util.Safe.guard("a11y.geminiMessagesVerify") {
                    val current = rootInActiveWindow
                    val stillGemini = if (current == null) {
                        false
                    } else {
                        try {
                            val pkg = current.packageName?.toString() ?: ""
                            pkg == com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.PKG_MESSAGING &&
                                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.evaluar(buildGeminiRetrato(current)) ==
                                    com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.Veredicto.GEMINI
                        } finally {
                            current.recycle()
                        }
                    }

                    if (stillGemini) {
                        geminiFailedBounces++
                        if (geminiFailedBounces >= 2) {
                            android.util.Log.w(TAG, "🏠 Gemini en Mensajes persiste tras dos intentos, forzando HOME")
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            geminiFailedBounces = 0
                            geminiBackoffUntil = android.os.SystemClock.elapsedRealtime() + GEMINI_BACKOFF_MS
                        }
                    } else {
                        geminiFailedBounces = 0
                    }
                }
            } finally {
                geminiBlockInProgress = false
            }
        }, 700)
    }

    private class GeminiRetratoState {
        var isInbox = false
        var isSettings = false
        var isAppWelcomeOrSignIn = false
        var isSystemOrDeleteDialog = false
        var isConversation = false
        var title: String? = null
        var hint: String? = null
        var hasCallButtons = false
        var hasWelcomeTitle = false
        var hasPrivacyDisclaimer = false
        var hasConsentButton = false
        var hasPenpalViewId = false
        var rootTop = 0
        var topBarMaxY = 0
        var composeMinY = 0
        var remainingBudget = MAX_NODES_PER_SCAN
        val tempRect = android.graphics.Rect()
    }

    private fun buildGeminiRetrato(root: android.view.accessibility.AccessibilityNodeInfo): com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.Retrato {
        val estado = GeminiRetratoState()
        val clsLower = lastGeminiMessagingWindowClass?.lowercase() ?: ""
        if (clsLower.contains("settingsactivity") || clsLower.contains("geminisettingsactivity")) {
            estado.isSettings = true
        }
        if (com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esPantallaBienvenidaAppMensajes(
                null, null, lastGeminiMessagingWindowClass
            )
        ) {
            estado.isAppWelcomeOrSignIn = true
        }
        if (com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esDialogoSistemaOBorrado(
                null, lastGeminiMessagingWindowClass
            )
        ) {
            estado.isSystemOrDeleteDialog = true
        }
        root.getBoundsInScreen(estado.tempRect)
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val fallbackHeight = dm.heightPixels.coerceAtLeast(1)
        val winTop = if (!estado.tempRect.isEmpty) estado.tempRect.top else 0
        val winHeight = if (estado.tempRect.height() > 0) estado.tempRect.height() else fallbackHeight
        val maxToolbarPx = (130f * density).toInt()
        val fracToolbarPx = (winHeight * 0.22f).toInt()
        estado.rootTop = winTop
        estado.topBarMaxY = winTop + minOf(maxToolbarPx, fracToolbarPx).coerceAtLeast((88f * density).toInt())
        estado.composeMinY = winTop + (winHeight * 0.25f).toInt()

        scanGeminiNode(root, 0, estado, insideMessageBubble = false, insideEditableBox = false)
        return com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.Retrato(
            packageName = com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.PKG_MESSAGING,
            isInboxScreen = estado.isInbox,
            isSettingsScreen = estado.isSettings,
            isAppWelcomeOrSignInScreen = estado.isAppWelcomeOrSignIn,
            isSystemOrDeleteDialog = estado.isSystemOrDeleteDialog,
            isConversationScreen = estado.isConversation,
            conversationTitle = estado.title,
            composerHint = estado.hint,
            hasCallButtons = estado.hasCallButtons,
            hasWelcomeTitle = estado.hasWelcomeTitle,
            hasPrivacyDisclaimer = estado.hasPrivacyDisclaimer,
            hasConsentButton = estado.hasConsentButton,
            hasPenpalViewId = estado.hasPenpalViewId
        )
    }

    private fun scanGeminiNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        estado: GeminiRetratoState,
        insideMessageBubble: Boolean,
        insideEditableBox: Boolean
    ) {
        if (depth > MAX_TREE_DEPTH) return
        if (estado.remainingBudget-- <= 0) return

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        node.getBoundsInScreen(estado.tempRect)
        val nodeTop = estado.tempRect.top
        val inTopBar = estado.tempRect.isEmpty || nodeTop <= estado.topBarMaxY
        val inComposeArea = estado.tempRect.isEmpty || nodeTop >= estado.composeMinY

        val isBubbleNode = insideMessageBubble ||
            viewId.contains("message_text") ||
            viewId.contains("message_bubble")

        if (viewId.isNotEmpty()) {
            if (viewId.contains("penpal") || viewId.contains("gemini")) {
                estado.hasPenpalViewId = true
            }
            if (com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esMarcadorBandejaPorId(viewId)) {
                estado.isInbox = true
            }
            if (viewId.contains("compose_message_text") || viewId.contains("message_list")) {
                estado.isConversation = true
            }
            if (viewId.contains("conversation_title") && inTopBar && !isBubbleNode) {
                estado.isConversation = true
                val t = node.text?.toString() ?: node.contentDescription?.toString()
                if (!t.isNullOrBlank()) {
                    estado.title = t.trim()
                }
            } else if (viewId.contains("title_container") && inTopBar && !isBubbleNode) {
                val t = node.text?.toString() ?: node.contentDescription?.toString()
                if (!t.isNullOrBlank() && !com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBarraBusquedaBandeja(t.lowercase())) {
                    estado.title = t.trim()
                }
            }
        }

        val className = node.className?.toString() ?: ""
        if (className.endsWith("Switch") || className.endsWith("SwitchCompat") ||
            (node.isCheckable && className.contains("Switch", ignoreCase = true))) {
            estado.isSettings = true
        }

        val desc = node.contentDescription?.toString() ?: ""
        val descLower = desc.lowercase()
        val text = node.text?.toString() ?: ""
        val textLower = text.lowercase()

        if (!isBubbleNode && (
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esPantallaBienvenidaAppMensajes(viewId, text) ||
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esPantallaBienvenidaAppMensajes(null, desc)
            )
        ) {
            estado.isAppWelcomeOrSignIn = true
        }
        if (!isBubbleNode && (
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esDialogoSistemaOBorrado(text) ||
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esDialogoSistemaOBorrado(desc)
            )
        ) {
            estado.isSystemOrDeleteDialog = true
        }

        // 1. Compositor editable de mensajes (solo área inferior, nunca buscador ni destinatario,
        //    y nunca texto tipeado por el usuario para evitar falsos positivos con amigos).
        val isSearchOrRecipientInput = viewId.contains("search") ||
            viewId.contains("recipient") ||
            com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBarraBusquedaBandeja(textLower)

        val isComposerInput = !isBubbleNode && !isSearchOrRecipientInput &&
            (viewId.contains("compose_message_text") || (node.isEditable && inComposeArea))

        if (isComposerInput) {
            estado.isConversation = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val h = node.hintText?.toString()
                val resolvedHint = when {
                    !h.isNullOrBlank() -> h
                    node.isShowingHintText && text.isNotBlank() -> text
                    else -> null
                }
                if (!resolvedHint.isNullOrBlank() &&
                    !com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBarraBusquedaBandeja(resolvedHint.lowercase())
                ) {
                    estado.hint = resolvedHint
                }
            }
        }

        // Placeholder hijo dentro del nodo editable (Jetpack Compose BasicTextField decorationBox)
        if (insideEditableBox && !isBubbleNode && text.isNotBlank() &&
            com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esFraseComposerGemini(textLower)
        ) {
            estado.hint = text
            estado.isConversation = true
        }

        // 2. Botones de llamada / videollamada en barra superior o por viewId explícito
        if (!isBubbleNode) {
            val isCallById = com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBotonLlamadaToolbar(viewId, "")
            val isCallByToolbarDesc = inTopBar && (node.isClickable || descLower.isNotEmpty()) &&
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBotonLlamadaToolbar("", descLower)
            if (isCallById || isCallByToolbarDesc) {
                estado.hasCallButtons = true
            }
        }

        // 3. Señales de bandeja principal y ajustes de Mensajes
        if (!isBubbleNode) {
            val clickableOrFab = node.isClickable || viewId.contains("fab")
            if (clickableOrFab && (
                    com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esFabIniciarChat(textLower) ||
                        com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esFabIniciarChat(descLower)
                )
            ) {
                estado.isInbox = true
            }
            if (inTopBar && (
                    com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBarraBusquedaBandeja(textLower) ||
                        com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBarraBusquedaBandeja(descLower)
                )
            ) {
                estado.isInbox = true
            }
            if (com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esMarcadorAjustes(textLower) ||
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esMarcadorAjustes(descLower)
            ) {
                estado.isSettings = true
            }
        }

        // 4. Título en TopBar, bienvenida, disclaimer y consentimiento (NUNCA dentro de globos de chat)
        if (!isBubbleNode && text.isNotBlank()) {
            if (inTopBar && estado.title == null &&
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esTituloGemini(textLower)
            ) {
                estado.title = text.trim()
            }
            if (com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBienvenidaGemini(textLower)) {
                estado.hasWelcomeTitle = true
            }
            if (com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esDisclaimerGemini(textLower)) {
                estado.hasPrivacyDisclaimer = true
            }
            if (node.isClickable &&
                com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBotonConsentimientoGemini(textLower)
            ) {
                estado.hasConsentButton = true
            }
        } else if (!isBubbleNode && node.isClickable && descLower.isNotEmpty() &&
            com.ejemplo.locksuite.mdm.GeminiMessagesPolicy.esBotonConsentimientoGemini(descLower)
        ) {
            estado.hasConsentButton = true
        }

        val childCount = node.childCount
        val nextInsideEditable = insideEditableBox || isComposerInput
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanGeminiNode(child, depth + 1, estado, isBubbleNode, nextInsideEditable)
            } finally {
                child.recycle()
            }
        }
    }

    // ──────────────────────────────────────────────
    // Bloqueo quirúrgico de navegación externa en Santander Argentina (Capa 3)
    // ──────────────────────────────────────────────
    private var santanderBlockInProgress = false
    private var santanderFailedBounces = 0
    private var santanderBackoffUntil = 0L
    private var lastSantanderWindowClass: String? = null
    private val SANTANDER_BACKOFF_MS = 2500L
    private val MAX_SANTANDER_CADENAS = 160

    private val santanderScanRunnable = Runnable {
        com.ejemplo.locksuite.util.Safe.guard("a11y.santanderScan") {
            if (flags().santanderBlock) {
                scanForSantanderExternal()
            }
        }
    }

    private fun handleSantanderBlocking(eventType: Int) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            mainHandler.removeCallbacks(santanderScanRunnable)
            scanForSantanderExternal()
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mainHandler.removeCallbacks(santanderScanRunnable)
            mainHandler.postDelayed(santanderScanRunnable, 300)
        }
    }

    private fun isSantanderOrChildWebView(pkg: String): Boolean =
        pkg == com.ejemplo.locksuite.mdm.SantanderBlockPolicy.PKG_SANTANDER ||
            (WEBVIEW_PROVIDER_PACKAGES.contains(pkg) &&
                getOriginApp() == com.ejemplo.locksuite.mdm.SantanderBlockPolicy.PKG_SANTANDER)

    private fun scanForSantanderExternal() {
        val now = SystemClock.elapsedRealtime()
        if (santanderBlockInProgress || now < santanderBackoffUntil) return
        val root = rootInActiveWindow ?: return
        try {
            val rootPkg = root.packageName?.toString() ?: ""
            if (isSantanderOrChildWebView(rootPkg)) {
                val retrato = buildSantanderRetrato(root)
                if (com.ejemplo.locksuite.mdm.SantanderBlockPolicy.evaluar(retrato) ==
                    com.ejemplo.locksuite.mdm.SantanderBlockPolicy.Veredicto.BLOQUEAR
                ) {
                    triggerSantanderBlock(com.ejemplo.locksuite.mdm.SantanderBlockPolicy.motivo(retrato))
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun triggerSantanderBlock(motivo: String) {
        val now = SystemClock.elapsedRealtime()
        if (santanderBlockInProgress || now < santanderBackoffUntil) return
        santanderBlockInProgress = true

        val windowTag = lastSantanderWindowClass ?: "SantanderWebView"
        Log.w(TAG, "🚫 Santander navegación externa bloqueada ($windowTag): $motivo")
        com.ejemplo.locksuite.mdm.Layer3Audit.anotar(
            applicationContext,
            "santander",
            com.ejemplo.locksuite.mdm.SantanderBlockPolicy.PKG_SANTANDER,
            windowTag,
            motivo,
            true
        )
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Navegación externa bloqueada en Santander",
                Toast.LENGTH_SHORT
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            try {
                com.ejemplo.locksuite.util.Safe.guard("a11y.santanderVerify") {
                    val current = rootInActiveWindow
                    val stillBlocked = if (current == null) {
                        false
                    } else {
                        try {
                            val pkg = current.packageName?.toString() ?: ""
                            isSantanderOrChildWebView(pkg) &&
                                com.ejemplo.locksuite.mdm.SantanderBlockPolicy.evaluar(
                                    buildSantanderRetrato(current)
                                ) == com.ejemplo.locksuite.mdm.SantanderBlockPolicy.Veredicto.BLOQUEAR
                        } finally {
                            current.recycle()
                        }
                    }

                    if (stillBlocked) {
                        santanderFailedBounces++
                        if (santanderFailedBounces >= 2) {
                            Log.w(TAG, "🏠 Santander externo persiste tras dos intentos, forzando HOME")
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            santanderFailedBounces = 0
                            santanderBackoffUntil = SystemClock.elapsedRealtime() + SANTANDER_BACKOFF_MS
                        }
                    } else {
                        santanderFailedBounces = 0
                    }
                }
            } finally {
                santanderBlockInProgress = false
            }
        }, 700)
    }

    private class SantanderRetratoState {
        var tieneWebView = false
        val ids = ArrayList<String>(48)
        val textos = ArrayList<String>(160)
        var remainingBudget = MAX_NODES_PER_SCAN
    }

    private fun buildSantanderRetrato(root: AccessibilityNodeInfo): com.ejemplo.locksuite.mdm.SantanderBlockPolicy.Retrato {
        val estado = SantanderRetratoState()
        scanSantanderNode(root, 0, estado)
        return com.ejemplo.locksuite.mdm.SantanderBlockPolicy.Retrato(
            packageName = com.ejemplo.locksuite.mdm.SantanderBlockPolicy.PKG_SANTANDER,
            windowClass = lastSantanderWindowClass ?: root.className?.toString() ?: "",
            tieneWebView = estado.tieneWebView,
            idsDeVista = estado.ids,
            textos = estado.textos
        )
    }

    private fun scanSantanderNode(node: AccessibilityNodeInfo, depth: Int, estado: SantanderRetratoState) {
        if (depth > MAX_TREE_DEPTH || estado.remainingBudget-- <= 0) return

        val classLower = node.className?.toString()?.lowercase() ?: ""
        val nodePkg = node.packageName?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (viewId.isNotEmpty() && estado.ids.size < 48) {
            estado.ids.add(viewId)
        }

        if (!estado.tieneWebView && (
                classLower.contains("webview") ||
                classLower.contains("webkit") ||
                classLower.contains("chromium") ||
                classLower.contains("renderframe") ||
                viewId.contains("webview") ||
                viewId.contains("web_view") ||
                WEBVIEW_PROVIDER_PACKAGES.contains(nodePkg)
            )
        ) {
            estado.tieneWebView = true
        }

        node.text?.let { if (it.isNotEmpty()) agregarTextoSantander(it, estado) }
        node.contentDescription?.let { if (it.isNotEmpty()) agregarTextoSantander(it, estado) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanSantanderNode(child, depth + 1, estado)
            } finally {
                child.recycle()
            }
        }
    }

    private fun agregarTextoSantander(cs: CharSequence, estado: SantanderRetratoState) {
        if (cs.length > com.ejemplo.locksuite.mdm.SantanderBlockPolicy.MAX_CARACTERES_UI) return
        if (estado.textos.size >= MAX_SANTANDER_CADENAS) return
        val normalized = com.ejemplo.locksuite.mdm.SantanderBlockPolicy.normalizar(cs)
        if (normalized.isNotEmpty()) estado.textos.add(normalized)
    }

    // ──────────────────────────────────────────────
    // Automatización del flujo de actualización por Google Play
    //
    // Reescrito el 16/8/2026. Qué estaba mal antes (y por qué la app no se
    // actualizaba y la pantalla negra no se iba):
    //
    //  1. El emparejamiento de botones usaba `contains` sobre palabras muy
    //     cortas ("ok", "yes", "sí", "open", "install") y aceptaba CUALQUIER
    //     nodo con texto, clickeable o no. En una pantalla de Play Store eso
    //     matchea decenas de nodos que no son botones: el servicio subía al
    //     primer padre clickeable y hacía clic en cosas al azar. "Installing"
    //     contiene "install", así que mientras descargaba volvía a "hacer clic
    //     en Actualizar" sobre la fila de progreso, que es donde Play Store
    //     pone el botón de cancelar la descarga. Ahora el emparejamiento es por
    //     IGUALDAD exacta contra listas cerradas, con acentos normalizados.
    //
    //  2. No había ninguna detección de "ya está descargando": se seguía
    //     clickeando encima del progreso. Ahora, si se detecta progreso, el
    //     ciclo no toca nada y solo informa la etapa.
    //
    //  3. La única forma de terminar era el paso C (aparece "Abrir" y no hay
    //     "Actualizar"). Si Play Store mostraba cualquier otra cosa —un error,
    //     un pedido de iniciar sesión, una pantalla en blanco— no terminaba
    //     nunca y el overlay quedaba fijo hasta el watchdog de 10 minutos, que
    //     además no sacaba el overlay. Ahora hay tres salidas más: el
    //     versionCode del paquete cambió (la señal más confiable de todas),
    //     el freno por estancamiento de UPDATE_STALL_MS, y el botón Cancelar.
    // ──────────────────────────────────────────────

    private var updateSessionPkg: String? = null
    private var updateSessionStartTime = 0L
    private var updateSessionTreeSeenAt = 0L
    private var updateSessionLastClickAt = 0L
    private var updateSessionCandidatesTried = 0
    private var updateSessionDismissalsCount = 0
    private val updateSessionTriedKeys = HashSet<String>(8)
    private var lastStoreRelaunchAt = 0L
    /** Primera vez que la ficha tuvo contenido dibujado (no una pantalla en blanco). */
    private var updateSessionRenderedAt = 0L
    private var updateSessionScrolls = 0
    private var updateSessionLastScrollAt = 0L
    /** B.97 — desde cuándo el flujo espera con el celular bloqueado (0 = no está esperando). */
    private var updateSessionLockedSince = 0L
    /** B.97 — desde cuándo Google Play muestra la descarga sin sesión de instalación y sin avanzar. */
    private var updateSessionQueuedSince = 0L
    private var updateSessionBandPct = -1
    /** B.97 — los números de la pantalla la última vez que la descarga "se movió". */
    private var updateSessionProgressSig = ""
    /** B.97 — desde cuándo Google Play muestra "Esperando Wi-Fi" sin cortar (0 = no lo muestra). */
    private var updateSessionWaitNetSince = 0L
    private var updateSessionRelaunches = 0
    /** B.99 — veces SEGUIDAS que se abrió Google Play sin llegar a ver su ficha dibujada. */
    private var updateSessionStoreMisses = 0
    /** B.99 — se la reabrió (y por lo tanto se la destapó) desde la última medición. */
    private var updateSessionRelaunchedSinceCheck = false
    /** B.99 — veces que se la encontró bloqueada de nuevo después de destaparla. */
    private var updateSessionReblocks = 0
    private var updateSessionLastBlock: String? = null
    private var updateSessionLastFront: String? = null
    /** B.99 — el último porqué medido de que Google Play no aparezca, para el cierre por tiempo. */
    private var updateSessionAbsenceWhy: String? = null
    /** Paquete de la pantalla de inicio, para nombrarla en el registro. Se averigua una vez. */
    private var homePackageCache: String? = null

    /** Invalida el snapshot de flags. La usa UpdateFlowManager al arrancar/terminar. */
    fun invalidateFlagsCache() {
        cachedFlags = null
    }

    fun resetUpdateSession() {
        updateSessionPkg = null
        updateSessionStartTime = 0L
        updateSessionTreeSeenAt = 0L
        updateSessionLastClickAt = 0L
        updateSessionCandidatesTried = 0
        updateSessionDismissalsCount = 0
        updateSessionTriedKeys.clear()
        lastStoreRelaunchAt = 0L
        updateSessionRenderedAt = 0L
        updateSessionScrolls = 0
        updateSessionLastScrollAt = 0L
        updateSessionLockedSince = 0L
        updateSessionQueuedSince = 0L
        updateSessionBandPct = -1
        updateSessionProgressSig = ""
        updateSessionWaitNetSince = 0L
        updateSessionRelaunches = 0
        updateSessionStoreMisses = 0
        updateSessionRelaunchedSinceCheck = false
        updateSessionReblocks = 0
        updateSessionLastBlock = null
        updateSessionLastFront = null
        updateSessionAbsenceWhy = null
        releaseUpdateWakeLock()
    }

    /** El texto de un nodo para el registro ("Actualizar", "Update"…), corto. */
    private fun nodeLabel(node: AccessibilityNodeInfo?): String {
        val t = try {
            node?.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node?.contentDescription?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
        return (t ?: "sin texto").replace('\n', ' ').take(40)
    }

    /**
     * Anota en el registro de la actualización que se volvió a abrir Play Store (B.97),
     * sin llenarlo: la primera vez y después una de cada cinco. Cada relanzamiento es una
     * pista ("el usuario se iba", "Play Store no aparecía") que antes no quedaba en ningún
     * lado.
     */
    private fun noteStoreRelaunch(motivo: String) {
        updateSessionRelaunches++
        if (updateSessionRelaunches == 1 || updateSessionRelaunches % 5 == 0) {
            UpdateFlowManager.step(
                applicationContext, UpdateStepLog.ACT,
                "Volví a abrir la ficha en Google Play ($motivo; vez $updateSessionRelaunches)."
            )
        }
    }

    /**
     * Vuelve a abrir la ficha en Google Play si ya pasó la espera que corresponde (28/10,
     * B.99). La espera es corta si Google Play se venía viendo (el usuario tocó Inicio) y
     * crece mientras no llegue a aparecer (UpdateRoutePlanner.storeRelaunchGapMs): abrirla
     * cada 1,5 s a una Play Store que algo cierra al instante era un bucle de cierres.
     *
     * @return true si la abrió.
     */
    private fun relaunchStoreIfDue(ctx: Context, updatingPkg: String, now: Long, motivo: String): Boolean {
        val espera = com.ejemplo.locksuite.mdm.UpdateRoutePlanner.storeRelaunchGapMs(updateSessionStoreMisses)
        if (lastStoreRelaunchAt != 0L && now - lastStoreRelaunchAt <= espera) return false
        lastStoreRelaunchAt = now
        updateSessionStoreMisses++
        updateSessionRelaunchedSinceCheck = true
        noteStoreRelaunch(motivo)
        UpdateFlowManager.openStore(ctx, updatingPkg)
        return true
    }

    /**
     * Qué app está al frente, mirando las ventanas de arriba hacia abajo (B.99). Se saltean
     * la pantalla negra propia (overlay de accesibilidad), el teclado, el divisor de pantalla
     * partida y las barras del sistema. Devuelve el paquete y si es una ventana del SISTEMA
     * (un cartel como "Google Play Store se detuvo" es del paquete "android"), o null.
     */
    private fun describeFrontApp(): Pair<String, Boolean>? {
        try {
            for (w in windows) {
                val tipo = w.type
                if (tipo == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    tipo == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    tipo == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                ) continue
                val pkg = w.root?.packageName?.toString() ?: continue
                val esSistema = tipo == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                if (esSistema && pkg == "com.android.systemui") continue   // barras de estado y navegación
                return pkg to esSistema
            }
        } catch (e: Exception) {
            // sigue al camino de abajo
        }
        val pkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        return pkg?.let { it to false }
    }

    /** "la pantalla de inicio", "un cartel del sistema", "«WhatsApp» (com.whatsapp)"… para el registro. */
    private fun frenteTexto(ctx: Context, pkg: String, esSistema: Boolean): String {
        if (pkg == LOCKSUITE_PKG) return "una pantalla de LockSuite"
        if (pkg == "android" || (esSistema && pkg != PKG_PLAY_STORE)) {
            return "un cartel del sistema (puede ser el de «Google Play se detuvo»)"
        }
        val home = homePackageCache ?: try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }?.also { homePackageCache = it }
        if (pkg == home) return "la pantalla de inicio"
        return "«${UpdateFlowManager.appLabel(ctx, pkg)}» ($pkg)"
    }

    /**
     * Google Play no aparece: ¿por qué? (28/10/2026, B.99). Se mide lo que se puede medir
     * —oculta, suspendida, bloqueada por el kiosco— y se dice, en vez de reabrirla a
     * ciegas. Si algo la vuelve a bloquear cada vez que se la destapa (lo que pasaba en el
     * QEMAY-QM01 hasta B.99), se cierra enseguida con ese motivo en vez de dar vueltas 75 s
     * y terminar en "Google Play no se abrió".
     *
     * @return true si cerró el flujo.
     */
    private fun diagnoseMissingStore(ctx: Context, updatingPkg: String): Boolean {
        val st = UpdateFlowManager.playStoreState(ctx)
        val bloqueo = com.ejemplo.locksuite.mdm.UpdateRoutePlanner.playStoreBlockReason(
            st.hidden, st.suspended, st.lockTaskBlocks
        )
        val despuesDeDestaparla = updateSessionRelaunchedSinceCheck
        updateSessionRelaunchedSinceCheck = false
        if (bloqueo != null) {
            if (despuesDeDestaparla) updateSessionReblocks++
            if (bloqueo != updateSessionLastBlock || despuesDeDestaparla) {
                updateSessionLastBlock = bloqueo
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.WARN,
                    if (despuesDeDestaparla) {
                        "Google Play $bloqueo otra vez: algo del equipo la volvió a bloquear apenas se la destapó " +
                            "(vez $updateSessionReblocks). La vuelvo a destapar."
                    } else {
                        "Google Play $bloqueo: la destapo para poder abrirla."
                    }
                )
            }
            if (st.lockTaskBlocks) UpdateFlowManager.refreshKioskForUpdate(ctx)
            updateSessionAbsenceWhy = "Google Play $bloqueo"
            if (updateSessionReblocks >= com.ejemplo.locksuite.mdm.UpdateRoutePlanner.MAX_STORE_REBLOCKS) {
                val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
                finishUpdateWithBestReason(
                    ctx, updatingPkg, null,
                    UpdateFlowManager.RESULT_STORE_ERROR,
                    "No se pudo abrir Google Play para actualizar $label: algo del equipo la vuelve a bloquear " +
                        "($bloqueo) apenas se la destapa. Probá con «Solo Tienda» desde el panel.",
                    "Google Play $bloqueo de nuevo $updateSessionReblocks veces después de destaparla"
                )
                return true
            }
        } else {
            updateSessionLastBlock = null
        }

        val frente = describeFrontApp()
        if (frente != null) {
            val texto = frenteTexto(ctx, frente.first, frente.second)
            if (frente.first != updateSessionLastFront) {
                updateSessionLastFront = frente.first
                UpdateFlowManager.step(ctx, UpdateStepLog.INFO, "Google Play no aparece; al frente está $texto.")
            }
            if (bloqueo == null) updateSessionAbsenceWhy = "al frente estaba $texto"
        }
        return false
    }

    /**
     * Firma estable de un nodo entre escaneos. Los AccessibilityNodeInfo se recrean
     * en cada escaneo, así que no sirve compararlos por identidad para saber si un
     * candidato ya se probó.
     */
    private fun candidateKey(c: PlayButtonFinder.Candidate): String {
        val id = c.node.viewIdResourceName ?: ""
        val txt = c.node.text?.toString() ?: c.node.contentDescription?.toString() ?: ""
        return "$id|${c.top}|${c.left}|$txt"
    }

    /**
     * Busca la ventana de Play Store entre todas las interactivas.
     * `rootInActiveWindow` no sirve solo: con el overlay de accesibilidad arriba
     * puede devolver null o el árbol del overlay en vez del de la tienda.
     */
    private fun findPlayStoreRoot(): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                val windowRoot = window.root ?: continue
                if (windowRoot.packageName?.toString() == PKG_PLAY_STORE) return windowRoot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error buscando la ventana de Play Store: ${e.message}")
        }
        val root = rootInActiveWindow ?: return null
        return if (root.packageName?.toString() == PKG_PLAY_STORE) root else null
    }

    /**
     * Tamaño real de la ventana de Play Store.
     *
     * 3/9/2026: antes se usaba `resources.displayMetrics` del propio servicio, que
     * es lo que el documento de diseño del 16/8 (§1.3) ya marcaba como poco
     * confiable: desde un Service no siempre refleja el área utilizable, y en
     * pantallas raras (un flip de 2,8", una pantalla partida) puede no tener nada
     * que ver con la ventana que estamos escaneando. Las coordenadas de los nodos
     * vienen de `getBoundsInScreen()`, así que lo correcto es medir contra la
     * ventana misma.
     */
    private val storeBoundsRect = Rect()
    private fun playStoreBounds(root: AccessibilityNodeInfo): Pair<Int, Int> {
        try {
            root.getBoundsInScreen(storeBoundsRect)
            val w = storeBoundsRect.width()
            val h = storeBoundsRect.height()
            // Un tamaño absurdo (0, o una franja) significa que el nodo raíz no
            // representa la ventana: se cae a las métricas del sistema.
            if (w > 100 && h > 100) return w to h
        } catch (e: Exception) {
            // sigue al camino de abajo
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * Traduce lo que se reconoció en pantalla a un resultado con motivo.
     * Devuelve null si no hay nada anormal que reportar.
     *
     * Todo esto es nuevo el 3/9/2026: antes, cualquiera de estas pantallas caía en
     * el mismo saco de "no reconocí ningún botón" y el flujo cerraba con
     * RESULT_UP_TO_DATE, o sea informando que la app ya estaba actualizada. Es la
     * peor forma de fallar que puede tener este flujo, porque manda al dueño a
     * buscar el problema a otro lado.
     */
    private fun diagnosisResult(
        ctx: Context,
        updatingPkg: String,
        diagnosis: PlayButtonFinder.Diagnosis,
        evidence: String?
    ): Triple<String, String, String>? {
        val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
        // B.101: el mismo flujo también instala (Tienda Play): "instalar" o "actualizar".
        val verbo = UpdateFlowManager.verb(ctx)
        val ev = if (evidence.isNullOrBlank()) "" else " [$evidence]"
        return when (diagnosis) {
            PlayButtonFinder.Diagnosis.NO_SPACE -> {
                val space = UpdateFlowManager.checkSpace(ctx, updatingPkg)
                val libres = if (space.freeMb == Long.MAX_VALUE) "?" else space.freeMb.toString()
                Triple(
                    UpdateFlowManager.RESULT_NO_SPACE,
                    "No hay espacio para $verbo $label. Quedan $libres MB libres. Liberá espacio y volvé a intentar.",
                    "Play Store informó falta de espacio (libres=${libres}MB)$ev"
                )
            }
            PlayButtonFinder.Diagnosis.NEED_SIGN_IN -> Triple(
                UpdateFlowManager.RESULT_NEEDS_ACCOUNT,
                "Google Play pide iniciar sesión. Este equipo no tiene una cuenta de Google configurada, así que no puede $verbo por la tienda.",
                "Play Store pidió iniciar sesión$ev"
            )
            PlayButtonFinder.Diagnosis.NOT_COMPATIBLE -> Triple(
                UpdateFlowManager.RESULT_NOT_AVAILABLE,
                "Google Play dice que $label no es compatible con este equipo.",
                "Play Store informó incompatibilidad$ev"
            )
            PlayButtonFinder.Diagnosis.NOT_FOUND -> Triple(
                UpdateFlowManager.RESULT_NOT_AVAILABLE,
                "Google Play no encuentra la ficha de $label en este equipo.",
                "Ficha inexistente en Play Store$ev"
            )
            PlayButtonFinder.Diagnosis.NETWORK_ERROR -> Triple(
                UpdateFlowManager.RESULT_STORE_ERROR,
                "Google Play no tiene conexión. Revisá la red y volvé a intentar.",
                "Play Store informó error de red$ev"
            )
            PlayButtonFinder.Diagnosis.WAITING_NETWORK -> Triple(
                UpdateFlowManager.RESULT_STORE_ERROR,
                "Google Play dejó la descarga esperando Wi-Fi. Conectá el equipo a Wi-Fi y volvé a intentar.",
                "Descarga en espera de Wi-Fi$ev"
            )
            PlayButtonFinder.Diagnosis.STORE_ERROR -> Triple(
                UpdateFlowManager.RESULT_STORE_ERROR,
                "Google Play devolvió un error. Probá de nuevo más tarde.",
                "Error de Play Store$ev"
            )
            PlayButtonFinder.Diagnosis.NONE -> null
        }
    }

    /**
     * Cierra el flujo con la mejor explicación disponible. El orden importa: se
     * mide el espacio ANTES de creerle a la pantalla, porque medir es concluyente
     * y leer la pantalla es una heurística.
     */
    private fun finishUpdateWithBestReason(
        ctx: Context,
        updatingPkg: String,
        scan: PlayButtonFinder.Result?,
        fallbackResult: String,
        fallbackMessage: String,
        fallbackReason: String
    ) {
        val label = UpdateFlowManager.appLabel(ctx, updatingPkg)

        // 1. Medición dura: ¿alcanza el espacio? Es la causa que se confirmó en el
        //    equipo del 3/9 y la única que se puede afirmar sin leer la pantalla.
        val space = UpdateFlowManager.checkSpace(ctx, updatingPkg)
        if (!space.ok) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(
                ctx, UpdateFlowManager.RESULT_NO_SPACE,
                "No hay espacio para ${UpdateFlowManager.verb(ctx)} $label. Quedan ${space.freeMb} MB libres y hacen falta ${space.neededMb} MB.",
                "Espacio insuficiente medido al cerrar: libres=${space.freeMb}MB, necesarios=${space.neededMb}MB"
            )
            return
        }

        // 2. Lo que dijo la pantalla de Play Store.
        val diag = scan?.let { diagnosisResult(ctx, updatingPkg, it.diagnosis, it.diagnosisEvidence) }
        if (diag != null) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(ctx, diag.first, diag.second, diag.third)
            return
        }

        // 3. Lo que haya quedado. Se adjuntan las etiquetas vistas, que es lo que
        //    permite diagnosticar el equipo desde el panel sin pedir un ADB.
        val labels = scan?.debugLabels.orEmpty()
        val reason = if (labels.isEmpty()) fallbackReason
        else "$fallbackReason | botones vistos: ${labels.joinToString(" · ").take(200)}"
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
        UpdateFlowManager.finish(ctx, fallbackResult, fallbackMessage, reason)
    }

    /**
     * Un ciclo del flujo. Lo llama el tick cada UPDATE_TICK_MS y también cada evento
     * de accesibilidad de Play Store (el evento es un despertador extra, no la única
     * fuente — ese era el bug).
     */
    private fun scanAndAct(updatingPkg: String) {
        val ctx = applicationContext
        val now = SystemClock.elapsedRealtime()

        if (updateSessionPkg != updatingPkg) {
            resetUpdateSession()
            updateSessionPkg = updatingPkg
            updateSessionStartTime = now
        }

        UpdateFlowManager.showOverlay(
            ctx, updatingPkg,
            UpdateFlowManager.currentStage(ctx)
        )

        // ── 1. ¿Ya se instaló? Señal concluyente, no depende de la pantalla ──
        if (UpdateFlowManager.targetAlreadyUpdated(ctx)) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING,
                if (UpdateFlowManager.isInstallMode(ctx)) "✓ Instalación completada." else "✓ Actualización completada.")
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UPDATED, null)
            return
        }

        // ── 2. ¿Play Store ya está descargando? (por PackageInstaller) ──
        //
        // ⚠️ CORREGIDO EL 3/9/2026 — ACÁ ESTABA EL CUELGUE DE 10 MINUTOS.
        //
        // Este bloque salía con `return` apenas `sawSession` valía true. Y
        // `sawSession` no vuelve nunca a false. O sea que si Play Store creaba la
        // sesión y el sistema después la abortaba —falta de espacio es la causa
        // clásica, y es la que se midió en el equipo el 3/9— el ciclo salía por acá
        // en CADA tick, mostrando "Descargando..." para siempre. El freno por
        // estancamiento del paso 7 está más abajo en esta misma función: no se
        // alcanzaba nunca. La única salida era el watchdog de 10 minutos, con un
        // mensaje genérico que no decía nada.
        //
        // Ahora el estado "descargando" tiene su propio reloj, y no puede quedarse
        // ahí sin avanzar.
        val livePct = PlayUpdateSessionWatcher.currentProgressFor(ctx, updatingPkg)
        val pct = if (livePct >= 0) livePct else PlayUpdateSessionWatcher.lastProgress
        if (pct >= 0 || PlayUpdateSessionWatcher.sawSession) {
            val sessionAlive = livePct >= 0 ||
                PlayUpdateSessionWatcher.hasActiveSessionFor(ctx, updatingPkg)
            val failedAt = PlayUpdateSessionWatcher.sessionFailedAt

            // (a) El sistema abortó la sesión y no hay ninguna otra viva. Se le dan
            //     unos segundos de gracia porque Play Store parte las descargas
            //     grandes en varias sesiones y descarta las intermedias.
            if (failedAt > 0L && !sessionAlive && now - failedAt > 6_000L) {
                Log.w(TAG, "Sesión de instalación abortada y sin reemplazo para $updatingPkg")
                finishUpdateWithBestReason(
                    ctx, updatingPkg, safeScanPlayStore(),
                    UpdateFlowManager.RESULT_ERROR,
                    "La instalación de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} fue interrumpida por el sistema.",
                    "PackageInstaller informó onFinished(success=false) y no quedó ninguna sesión activa"
                )
                return
            }

            // (b) Arrancó pero no avanza. Cubre "esperando Wi-Fi", "descarga
            //     pausada" y el equipo que se quedó sin espacio a mitad de camino.
            val lastMove = maxOf(
                PlayUpdateSessionWatcher.lastProgressAt,
                PlayUpdateSessionWatcher.sawSessionAt
            )
            if (lastMove > 0L && now - lastMove > DOWNLOAD_STALL_MS) {
                Log.w(TAG, "Descarga sin avance por ${(now - lastMove) / 1000}s para $updatingPkg")
                finishUpdateWithBestReason(
                    ctx, updatingPkg, safeScanPlayStore(),
                    UpdateFlowManager.RESULT_ERROR,
                    "La descarga de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} no avanzó. Revisá la conexión y el espacio libre.",
                    "Descarga estancada en ${if (pct >= 0) "$pct%" else "sin progreso"} durante ${(now - lastMove) / 1000}s"
                )
                return
            }

            val progressText = if (pct >= 0) "Descargando... $pct%" else
                (UpdateFlowManager.currentDetail(ctx)
                    ?: if (UpdateFlowManager.isInstallMode(ctx)) "Descargando la app..." else "Descargando actualización...")
            UpdateFlowManager.setStage(
                ctx, UpdateFlowManager.STAGE_DOWNLOADING, progressText,
                progress = if (pct >= 0) pct else null
            )
            return
        }

        // ── 2-bis. ¿El celular está bloqueado? (26/10/2026, B.97) ──
        //
        // Todo lo que sigue necesita VER y TOCAR la ficha de Google Play, y con la
        // pantalla de bloqueo puesta no se puede: Play Store queda detrás del keyguard.
        // Antes el flujo tapaba igual la pantalla —el overlay es una ventana de
        // accesibilidad y queda POR ENCIMA del keyguard y de la barra de navegación (capa
        // 31 contra 17, WindowManagerPolicy de AOSP)—, el usuario no podía desbloquear, y
        // a los 75 s se cerraba con "Google Play no se abrió". Una de las causas de "en
        // algunos celulares no actualiza": dependía de si el teléfono estaba bloqueado
        // cuando llegaba el pedido. Ahora se espera, sin tapar nada, con un aviso en la
        // pantalla de bloqueo, y lo esperado no cuenta para los frenos. Una descarga ya
        // empezada no pasa por acá: sigue sola con el celular bloqueado (paso 2).
        if (UpdateFlowManager.isKeyguardLocked(ctx)) {
            if (updateSessionLockedSince == 0L) {
                updateSessionLockedSince = now
                val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.WARN,
                    "El celular está bloqueado: espero a que lo desbloqueen para seguir con Google Play " +
                        "(la pantalla no se tapa, para que se pueda desbloquear)."
                )
                val gerundio = if (UpdateFlowManager.isInstallMode(ctx)) "Instalando" else "Actualizando"
                UpdateFlowManager.notifyUser(
                    ctx, "$gerundio $label",
                    "Desbloqueá el celular para que LockSuite termine de ${UpdateFlowManager.verb(ctx)} $label.", null, ongoing = true
                )
            }
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_UNLOCK)
            return
        }
        if (updateSessionLockedSince != 0L) {
            val bloqueadoMs = (now - updateSessionLockedSince).coerceAtLeast(0L)
            updateSessionLockedSince = 0L
            updateSessionStartTime += bloqueadoMs
            if (updateSessionTreeSeenAt > 0L) updateSessionTreeSeenAt += bloqueadoMs
            if (updateSessionRenderedAt > 0L) updateSessionRenderedAt += bloqueadoMs
            if (updateSessionQueuedSince > 0L) updateSessionQueuedSince += bloqueadoMs
            UpdateFlowManager.cancelUserNotification(ctx)
            UpdateFlowManager.step(
                ctx, UpdateStepLog.INFO,
                "Desbloqueado después de ${bloqueadoMs / 1000} s: sigo con Google Play."
            )
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_STORE, "Esperando a Google Play...")
            UpdateFlowManager.showOverlay(ctx, updatingPkg, UpdateFlowManager.STAGE_WAITING_STORE)
            lastStoreRelaunchAt = now
            updateSessionRelaunchedSinceCheck = true
            noteStoreRelaunch("se desbloqueó el celular")
            UpdateFlowManager.openStore(ctx, updatingPkg)
            return
        }

        // ── 3. Árbol de Play Store ──
        val root = findPlayStoreRoot()
        if (root == null) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_STORE,
                "Esperando a Google Play...")
            if (now - updateSessionStartTime > 6_000L) {
                // B.99: antes de reabrirla, medir por qué no está (y decirlo). Si algo la
                // vuelve a bloquear cada vez, esto cierra el flujo con ese motivo.
                if (diagnoseMissingStore(ctx, updatingPkg)) return
                relaunchStoreIfDue(ctx, updatingPkg, now, "Google Play no estaba al frente")
            }
            // Si Play Store nunca aparece, el freno por estancamiento tiene que
            // poder actuar igual. Antes este `return` también lo salteaba.
            if (now - updateSessionStartTime > UPDATE_STALL_MS) {
                val porQue = updateSessionAbsenceWhy
                finishUpdateWithBestReason(
                    ctx, updatingPkg, null,
                    UpdateFlowManager.RESULT_STORE_ERROR,
                    "Google Play no se abrió en este equipo." + (if (porQue != null) " ($porQue)" else ""),
                    "La ventana de Play Store nunca apareció en ${UPDATE_STALL_MS / 1000}s" +
                        (if (porQue != null) ": $porQue" else "")
                )
            }
            return
        }
        if (updateSessionTreeSeenAt == 0L) updateSessionTreeSeenAt = now

        // Escanear el árbol de nodos de Play Store, midiendo contra la ventana real
        val (screenW, screenH) = playStoreBounds(root)
        val scan = PlayButtonFinder.scan(root, screenW, screenH, resources.displayMetrics.density)
        UpdateFlowManager.reportDebugLabels(ctx, scan.debugLabels)

        if (scan.rendered && updateSessionRenderedAt == 0L) updateSessionRenderedAt = now
        // B.99: con la ficha dibujada, Google Play está de verdad: la próxima vez que haya
        // que traerla de vuelta (el usuario tocó Inicio) es rápido otra vez.
        if (scan.rendered) {
            updateSessionStoreMisses = 0
            updateSessionLastFront = null
            updateSessionAbsenceWhy = null
        }

        val hasDefiniteUpdate = scan.actions.any { it.score >= 80 }

        // ── 3-ter. ¿Google Play está descargando? (corregido el 26/10/2026, B.97) ──
        //
        // Antes CUALQUIER barra de progreso en pantalla se leía como "ya está
        // descargando": no se apretaba nada y se esperaba. Pero la ficha tiene barras que
        // no son descargas —las capturas y el video de la app mientras cargan, la ficha
        // misma mientras se dibuja—, y si una quedaba girando el flujo mostraba
        // "Descargando…" sin haber apretado nunca "Actualizar", hasta el watchdog de 10
        // minutos. Otra causa de "en algunos celulares no actualiza".
        //
        // Ahora: si hay un "Actualizar" a la vista, NO está descargando (mientras baja, en
        // su lugar está "Cancelar"), y se aprieta. Sin "Actualizar", cuenta como descarga
        // una barra después de haber apretado algo, o una en la fila de botones de una
        // ficha ya dibujada (Play Store actualizando la app por su cuenta). Y una descarga
        // que no avanza tiene su propio tope (QUEUED_STALL_MS), que antes no existía.
        val progresoDeDescarga = when {
            !scan.sawProgressBar -> false
            hasDefiniteUpdate -> false
            updateSessionCandidatesTried > 0 -> true
            else -> scan.progressInBand && scan.rendered
        }
        if (progresoDeDescarga) {
            if (scan.diagnosis == PlayButtonFinder.Diagnosis.WAITING_NETWORK) {
                // "Esperando Wi-Fi": no va a arrancar sola, así que se dice ahora y no en 4
                // minutos. Pero recién cuando se SOSTIENE unos segundos: al cambiar de red,
                // Google Play hace parpadear "esperando red" en una descarga que sigue sola.
                if (updateSessionWaitNetSince == 0L) {
                    updateSessionWaitNetSince = now
                    UpdateFlowManager.step(
                        ctx, UpdateStepLog.WARN,
                        "Google Play muestra «${scan.diagnosisEvidence ?: "esperando red"}». Si sigue así unos segundos, cierro avisando."
                    )
                } else if (now - updateSessionWaitNetSince > WAITING_NETWORK_CONFIRM_MS) {
                    val diag = diagnosisResult(ctx, updatingPkg, scan.diagnosis, scan.diagnosisEvidence)
                    if (diag != null) {
                        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
                        UpdateFlowManager.finish(ctx, diag.first, diag.second, diag.third)
                        return
                    }
                }
            } else {
                updateSessionWaitNetSince = 0L
            }
            val pctFila = scan.bandProgressPct
            // Los números de la pantalla ("12 MB de 30 MB"): hay versiones de Play Store
            // cuya barra no informa el valor a la Accesibilidad, pero el texto avanza.
            val numeros = scan.progressSignature
            var seMovio = false
            if (updateSessionQueuedSince == 0L) {
                updateSessionQueuedSince = now
                updateSessionBandPct = pctFila
                updateSessionProgressSig = numeros
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.INFO,
                    if (updateSessionCandidatesTried > 0) "Después de apretar aparece una barra de progreso: espero la descarga."
                    else "Google Play ya está bajando esta app por su cuenta: espero."
                )
            } else if ((pctFila >= 0 && pctFila != updateSessionBandPct) ||
                (numeros.isNotEmpty() && numeros != updateSessionProgressSig)
            ) {
                // Avanza (la barra o los números): es una descarga de verdad, no una cola
                // trabada. Si algo de la pantalla cambió por otro motivo se espera de más
                // (hasta el watchdog), nunca se corta una descarga que anda.
                updateSessionBandPct = pctFila
                updateSessionProgressSig = numeros
                updateSessionQueuedSince = now
                seMovio = true
            }
            if (pctFila >= 0) {
                UpdateFlowManager.setStage(
                    ctx, UpdateFlowManager.STAGE_DOWNLOADING, "Descargando... $pctFila%", progress = pctFila
                )
            } else if (seMovio || UpdateFlowManager.currentStage(ctx) == UpdateFlowManager.STAGE_DOWNLOADING) {
                // Se mueve pero sin porcentaje legible: se dice "descargando", no "en cola".
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_DOWNLOADING)
            } else {
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_QUEUED)
            }
            if (now - updateSessionQueuedSince > QUEUED_STALL_MS) {
                val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
                finishUpdateWithBestReason(
                    ctx, updatingPkg, scan,
                    UpdateFlowManager.RESULT_STORE_ERROR,
                    "Google Play dejó la descarga de $label en espera y no arrancó (otra descarga en curso, " +
                        "sin Wi-Fi o pausada). Probá de nuevo más tarde.",
                    "Progreso ${if (pctFila >= 0) "detenido en $pctFila%" else "sin valor (en cola)"}, sin cambios en " +
                        "los números de la pantalla y sin sesión de instalación durante ${(now - updateSessionQueuedSince) / 1000}s"
                )
            }
            return
        }
        if (updateSessionQueuedSince != 0L) {
            updateSessionQueuedSince = 0L
            updateSessionBandPct = -1
            updateSessionProgressSig = ""
        }
        updateSessionWaitNetSince = 0L

        // ── 3-bis. Pantalla de error de Play Store reconocida ──
        //
        // Va antes de cualquier clic: en la pantalla de "sin espacio" el único
        // botón grande abre el administrador de almacenamiento del sistema, y
        // apretarlo saca al usuario de la tienda con la pantalla todavía tapada.
        //
        // B.101 — en una INSTALACIÓN, si la ficha muestra un "Instalar" claro y todavía no
        // se apretó nada, un texto corto suelto ("sign in", "not found", "something went
        // wrong") es casi seguro un comentario de otro usuario más abajo en la ficha, no
        // una pantalla de error: en una app nueva se ven opiniones y en una actualización
        // casi no. Una pantalla de error de verdad no tiene el botón Instalar en el
        // encabezado, y lo que aparece DESPUÉS de apretar (sin espacio, sin cuenta) se
        // sigue diagnosticando igual.
        val fichaNormalConInstalar = UpdateFlowManager.isInstallMode(ctx) &&
            hasDefiniteUpdate && updateSessionCandidatesTried == 0
        if (scan.diagnosis != PlayButtonFinder.Diagnosis.NONE && !fichaNormalConInstalar) {
            val diag = diagnosisResult(ctx, updatingPkg, scan.diagnosis, scan.diagnosisEvidence)
            if (diag != null) {
                Log.w(TAG, "Play Store: ${scan.diagnosis} para $updatingPkg (${scan.diagnosisEvidence})")
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
                UpdateFlowManager.finish(ctx, diag.first, diag.second, diag.third)
                return
            }
        }

        val cooledDown = now - updateSessionLastClickAt > CANDIDATE_WAIT_MS

        // ── 4. Diálogo de confirmación ──
        if (updateSessionCandidatesTried > 0 && scan.dialogs.isNotEmpty() && cooledDown) {
            val d = scan.dialogs.first()
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_CONFIRMING,
                "Confirmando descarga...")
            if (performClickOnNode(d.node)) {
                updateSessionLastClickAt = now
                Log.i(TAG, "Clic en diálogo: ${d.reason}")
                UpdateFlowManager.step(ctx, UpdateStepLog.ACT, "Confirmé el diálogo de Google Play: «${nodeLabel(d.node)}» (${d.reason}).")
            } else {
                UpdateFlowManager.step(ctx, UpdateStepLog.WARN, "No pude apretar el botón del diálogo «${nodeLabel(d.node)}».")
            }
            return
        }

        // ── 4b. Descartar avisos u hojas emergentes que tapan la ficha ──
        // (ej. Asistente de IA, Google Play Points, etc. en Play Store v53+)
        if (scan.actions.isEmpty() && scan.dialogs.isEmpty() && scan.dismissals.isNotEmpty() &&
            updateSessionDismissalsCount < MAX_DISMISSALS && cooledDown
        ) {
            val d = scan.dismissals.first()
            if (performClickOnNode(d.node)) {
                updateSessionDismissalsCount++
                updateSessionLastClickAt = now
                Log.i(TAG, "Cerrando aviso emergente de Google Play: ${d.reason}")
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.ACT,
                    "Cerré aviso emergente de Google Play: «${nodeLabel(d.node)}» (${d.reason})."
                )
                return
            } else {
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.WARN,
                    "No pude cerrar aviso emergente «${nodeLabel(d.node)}»."
                )
            }
        }

        // ── 5. Probar el próximo candidato a "Actualizar" ──
        //
        // Este paso SUBIÓ de lugar el 3/9/2026: antes venía después de las dos
        // salidas por "ya estaba al día", así que en una ficha que tardaba en
        // dibujarse el flujo se iba por la salida falsa sin haber apretado nada.
        // Primero se intenta actualizar; recién si no hay nada que apretar se
        // razona sobre por qué.
        if (cooledDown && updateSessionCandidatesTried < MAX_CANDIDATES) {
            val next = scan.actions.firstOrNull { candidateKey(it) !in updateSessionTriedKeys }
            if (next != null) {
                updateSessionTriedKeys.add(candidateKey(next))
                updateSessionCandidatesTried++
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
                    "Iniciando descarga...")
                if (performClickOnNode(next.node)) {
                    updateSessionLastClickAt = now
                    Log.i(TAG, "Candidato ${updateSessionCandidatesTried}/${MAX_CANDIDATES}: ${next.reason}")
                    UpdateFlowManager.step(
                        ctx, UpdateStepLog.ACT,
                        "Apreté «${nodeLabel(next.node)}» (intento $updateSessionCandidatesTried de $MAX_CANDIDATES: ${next.reason})."
                    )
                } else {
                    UpdateFlowManager.step(
                        ctx, UpdateStepLog.WARN,
                        "No pude apretar «${nodeLabel(next.node)}» (intento $updateSessionCandidatesTried de $MAX_CANDIDATES)."
                    )
                }
                return
            }
        }

        val hasOpenButton = scan.opens.isNotEmpty()

        // ── 6. La ficha todavía no terminó de dibujarse ──
        //
        // ⚠️ ESTA ES LA CORRECCIÓN CENTRAL DEL 3/9/2026.
        //
        // Antes, las dos salidas de "ya estaba al día" se disparaban a los 1,5 s y
        // a los 3,5 s contados desde que aparecía la VENTANA de Play Store. Pero la
        // ventana existe apenas arranca la Activity, cuando la ficha todavía está
        // en blanco. En un Snapdragon 215 con 2 GB de RAM, la ficha tarda entre 4 y
        // 8 s en tener botones: el flujo concluía "no hay ningún botón" sobre una
        // pantalla vacía y cerraba diciendo "la app ya está actualizada". El equipo
        // volvía al inicio, sin actualizar y sin ningún error. Es exactamente el
        // síntoma reportado, y explica por qué en un teléfono rápido funcionaba.
        //
        // Ahora no se saca NINGUNA conclusión sobre una pantalla que no terminó de
        // dibujarse: hacen falta contenido dibujado Y tiempo transcurrido.
        val renderedFor = if (updateSessionRenderedAt > 0L) now - updateSessionRenderedAt else 0L
        val waitedForCard = now - updateSessionTreeSeenAt
        if (!scan.rendered && waitedForCard < UPDATE_CARD_GIVEUP_MS) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_STORE,
                "Esperando a Google Play...")
            return
        }

        // ── 7. Bajar la pantalla: en un equipo chico el botón puede estar abajo ──
        //
        // Android no instancia en el árbol de accesibilidad lo que no está
        // dibujado. En una pantalla de 2,8" la fila [Desinstalar] [Actualizar]
        // puede quedar debajo del borde visible, y entonces para nosotros no
        // existe. Un par de desplazamientos cortos la traen. Tope bajo a propósito:
        // seguir bajando lleva a los carruseles de "apps similares", que es el bug
        // 4 de B.9 (apretar el "Instalar" de OTRA app).
        //
        // Solo mientras NO se haya apretado nada todavía: después de un clic, la
        // fila de botones se convierte en una fila de progreso y quedaría igual de
        // "sin candidatos", pero ahí desplazar sería moverle la pantalla a una
        // descarga que ya arrancó.
        if (updateSessionCandidatesTried == 0 &&
            !hasDefiniteUpdate && !hasOpenButton && scan.rendered &&
            updateSessionScrolls < MAX_UPDATE_SCROLLS &&
            now - updateSessionLastScrollAt > SCROLL_COOLDOWN_MS
        ) {
            val scrollable = scan.scrollable
            if (scrollable != null) {
                updateSessionScrolls++
                updateSessionLastScrollAt = now
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
                    if (UpdateFlowManager.isInstallMode(ctx)) "Buscando el botón Instalar..." else "Buscando actualización...")
                try {
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Log.i(TAG, "Desplazando la ficha ($updateSessionScrolls/$MAX_UPDATE_SCROLLS) para encontrar el botón")
                    UpdateFlowManager.step(
                        ctx, UpdateStepLog.ACT,
                        "No veo el botón: bajé la ficha para buscarlo ($updateSessionScrolls de $MAX_UPDATE_SCROLLS)."
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo desplazar la ficha: ${e.message}")
                }
                return
            }
        }

        // ── 8. ¿Ya estaba al día? ──
        //
        // Solo se afirma con la ficha DIBUJADA y estable. "Abrir" sin "Actualizar"
        // en una ficha completa es la señal legítima de que no hay actualización.
        if (!hasDefiniteUpdate && hasOpenButton &&
            updateSessionRenderedAt > 0L && renderedFor > UPDATE_UP_TO_DATE_GRACE_MS
        ) {
            val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
            if (UpdateFlowManager.isInstallMode(ctx)) {
                // B.101 / B.110 — instalando: si la ficha dice "Abrir", Google Play cree que la app
                // YA está instalada. Si el sistema también la ve instalada, el paso 1 ya
                // habría cerrado el flujo con éxito; si llegamos acá, el sistema NO la ve
                // (por ejemplo, está oculta por una regla del administrador).
                // Pero si ya se habían probado candidatos (pudo haberse navegado a otra pantalla
                // por error) y aún no se relanzó la tienda, reabrimos la ficha antes de fallar.
                if (updateSessionCandidatesTried > 0 && updateSessionRelaunches < 2) {
                    noteStoreRelaunch("pantalla con 'Abrir' tras intentos de clic en modo instalación")
                    UpdateFlowManager.openStore(ctx, updatingPkg)
                    updateSessionTreeSeenAt = now
                    updateSessionRenderedAt = 0L
                    return
                }
                Log.w(TAG, "Instalando $updatingPkg: Google Play muestra 'Abrir' pero el sistema no ve el paquete")
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
                UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_ERROR,
                    "Google Play dice que $label ya está instalada, pero el sistema no la encuentra (puede estar oculta por una regla).",
                    "Ficha con 'Abrir' y paquete no visible para el sistema")
                return
            }
            Log.i(TAG, "App $updatingPkg ya está al día (botón Abrir visible, sin Actualizar)")
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UP_TO_DATE,
                "$label ya está actualizada.", "Ficha con 'Abrir' y sin 'Actualizar'")
            return
        }

        // ── 9. La ficha se dibujó y no hay NINGÚN botón que reconozcamos ──
        //
        // Esto ya NO se informa como "ya está actualizada". Una ficha dibujada sin
        // ningún botón no es una app al día: es una pantalla que no entendimos. Se
        // cierra con un error honesto y con las etiquetas de lo que sí se vio, que
        // es lo que permite agregar el idioma o el caso que falte sin pedir un ADB.
        // La condición `candidatesTried == 0` es esencial: si ya se apretó algo, la
        // fila de botones es ahora una fila de progreso y también se ve "sin
        // candidatos" — cerrar acá abortaría una descarga que arrancó bien. Ese
        // caso lo cubre el freno por estancamiento del paso 10, que sí espera.
        if (updateSessionCandidatesTried == 0 && scan.actions.isEmpty() && !hasOpenButton &&
            (renderedFor > UPDATE_CARD_RENDER_MS || waitedForCard > UPDATE_CARD_GIVEUP_MS)
        ) {
            Log.w(TAG, "Ficha de $updatingPkg sin botones reconocibles. Labels: ${scan.debugLabels}")
            finishUpdateWithBestReason(
                ctx, updatingPkg, scan,
                UpdateFlowManager.RESULT_NOT_AVAILABLE,
                if (UpdateFlowManager.isInstallMode(ctx))
                    "No se encontró el botón Instalar de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} en Google Play."
                else
                    "No se encontró el botón de actualizar de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} en Google Play.",
                "Ficha dibujada (${scan.renderedNodes} nodos con texto) sin candidatos reconocibles"
            )
            return
        }

        // ── 10. Freno por estancamiento ──
        if (now - updateSessionStartTime > UPDATE_STALL_MS) {
            Log.w(TAG, "Actualización estancada para $updatingPkg. Labels: ${scan.debugLabels}")
            finishUpdateWithBestReason(
                ctx, updatingPkg, scan,
                UpdateFlowManager.RESULT_ERROR,
                "No se pudo ${UpdateFlowManager.verb(ctx)} ${UpdateFlowManager.appLabel(ctx, updatingPkg)}.",
                "Estancado ${UPDATE_STALL_MS / 1000}s tras probar $updateSessionCandidatesTried candidatos"
            )
            return
        }

        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
            if (UpdateFlowManager.isInstallMode(ctx)) "Buscando el botón Instalar..." else "Buscando actualización...")
    }

    /**
     * Escaneo puntual de la pantalla de Play Store para adjuntar un diagnóstico a
     * un cierre. Devuelve null si la ventana ya no está: cerrar sin diagnóstico es
     * aceptable, quedarse colgado no.
     */
    private fun safeScanPlayStore(): PlayButtonFinder.Result? {
        return try {
            val root = findPlayStoreRoot() ?: return null
            val (w, h) = playStoreBounds(root)
            PlayButtonFinder.scan(root, w, h, resources.displayMetrics.density)
        } catch (e: Exception) {
            null
        }
    }

    private fun handlePlayStoreAutoUpdate(event: AccessibilityEvent, updatingPkg: String) {
        scanAndAct(updatingPkg)
    }

    /**
     * Wake lock de la actualización. Antes se creaba uno NUEVO cada vez que llegaba un
     * evento con la pantalla apagada y nunca se liberaba a mano: quedaban wake locks
     * apilados venciendo solos a los 15 s. Ahora hay uno solo, reutilizado y liberado.
     */
    private var updateWakeLock: PowerManager.WakeLock? = null

    private fun ensureScreenOnForUpdate() {
        try {
            if (powerManager.isInteractive) return
            val existing = updateWakeLock
            if (existing != null && existing.isHeld) return

            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "LockSuite:UpdateWakeLock"
            )
            wakeLock.acquire(15_000L)
            updateWakeLock = wakeLock
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo encender pantalla para actualización: ${e.message}")
        }
    }

    private fun releaseUpdateWakeLock() {
        try {
            updateWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignorado: el wake lock puede haber vencido solo
        }
        updateWakeLock = null
    }

    /**
     * Sube por el árbol buscando un nodo que acepte el clic.
     * El recorrido está topeado en CLICK_PARENT_DEPTH niveles: sin tope, un
     * texto suelto podía terminar clickeando el contenedor de toda la pantalla.
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

            var current: AccessibilityNodeInfo? = node.parent
            var level = 0
            while (current != null && level < CLICK_PARENT_DEPTH) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                current = current.parent
                level++
            }

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        } catch (e: Exception) {
            Log.e(TAG, "Error en performClickOnNode: ${e.message}")
        }
        return false
    }

    /** Que no quede el ciclo del portal cautivo girando si el servicio se va. */
    private fun stopCaptiveWatch() {
        mainHandler.removeCallbacks(captiveTickRunnable)
        captiveOpenedAt = 0L
        captiveLastActivityAt = 0L
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ LockSuiteAccessibilityService interrumpido")
        com.ejemplo.locksuite.diag.DiagLog.w("a11y", "El sistema interrumpió el servicio de accesibilidad (onInterrupt)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 7/11/2026 (B.112): con red; el cuerpo original quedó en handleUnbind().
        return try {
            handleUnbind(intent)
        } catch (t: Throwable) {
            com.ejemplo.locksuite.util.Safe.report("a11y.onUnbind", t)
            false
        }
    }

    private fun handleUnbind(intent: Intent?): Boolean {
        com.ejemplo.locksuite.diag.DiagLog.w("a11y", "Servicio de accesibilidad desvinculado (onUnbind)")
        if (instance === this) instance = null
        val appCtx = applicationContext
        try {
            Thread({
                try {
                    Thread.sleep(1200)
                    com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "Reconciliación al desvincular: ${e.message}")
                }
            }, "LockSuiteAccUnbind").start()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo lanzar la reconciliación de desvinculación: ${e.message}")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 7/11/2026 (B.112): con red; el cuerpo original quedó en handleDestroy().
        com.ejemplo.locksuite.util.Safe.guard("a11y.onDestroy") { handleDestroy() }
    }

    private fun handleDestroy() {
        com.ejemplo.locksuite.diag.DiagLog.w("a11y", "Servicio de accesibilidad destruido (onDestroy)")
        if (instance === this) instance = null
        stopCaptiveWatch()

        // Aviso INSTANTÁNEO de que la accesibilidad se cayó. Es la señal más confiable
        // que existe —la da el propio servicio que se está muriendo— y llega antes que
        // cualquier lectura de preferencias del sistema. Se hace en un hilo aparte
        // porque enumerar y suspender apps no puede correr en el hilo principal de un
        // servicio que se está destruyendo.
        val appCtx = applicationContext
        try {
            Thread({
                try {
                    // Una pausa corta antes de reconciliar. `onDestroy()` también se
                    // llama cuando el sistema simplemente re-vincula el servicio (por
                    // ejemplo al actualizar la app), y en ese caso vuelve solo en unos
                    // cientos de milisegundos. Sin esta pausa, cada re-vinculación
                    // suspendería todas las apps del equipo para liberarlas enseguida:
                    // otra fuente del vaivén, y de las peores. Con la pausa,
                    // `reconcileNow()` vuelve a preguntarle al sistema y, si el servicio
                    // ya volvió, no hace nada.
                    Thread.sleep(800)
                    com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "Reconciliación al destruir: ${e.message}")
                }
            }, "LockSuiteAccOff").start()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo lanzar la reconciliación de salida: ${e.message}")
        }
        try {
            mdmPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            // ignorado
        }
        mainHandler.removeCallbacks(whatsappScanRunnable)
        mainHandler.removeCallbacks(mercadoPagoScanRunnable)
        mainHandler.removeCallbacks(updateTickRunnable)
        webViewRetryRunnables.forEach { mainHandler.removeCallbacks(it) }
        webViewRetryRunnables.clear()
        releaseUpdateWakeLock()
        if (::overlayManager.isInitialized) {
            overlayManager.clearAll()
        }
    }
}
