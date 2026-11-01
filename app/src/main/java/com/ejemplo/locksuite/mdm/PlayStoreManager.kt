package com.ejemplo.locksuite.mdm

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ejemplo.locksuite.mdm.PlayStoreCatalog.StoreApp
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.UpdateFlowManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * PlayStoreManager — la "Tienda Play" en el equipo (29/10/2026, B.101).
 *
 * Es la ÚNICA capa que toca Android alrededor del modelo puro [PlayStoreCatalog]. Guarda la
 * configuración que llega de la nube, decide qué se ofrece AHORA en este equipo y conecta
 * la tienda con lo que ya existía:
 *
 *  - **Instalar** = `UpdateFlowManager.startInstall` (el MISMO flujo de actualización de
 *    apps: pantalla negra con progreso, Google Play abierto por debajo, instalación
 *    permitida solo para ese paquete). Acá no hay otro camino de instalación.
 *  - **Lista blanca (DNS)**: las apps ofrecidas cuentan como permitidas y las de acceso
 *    "dominios" aportan sus dominios (ver [whitelistContribution]).
 *  - **"Sin web"**: las de acceso "noweb" quedan con el bloqueo de WebView de siempre
 *    (`WebViewBlockManager`), que la sección Aplicaciones ya muestra como "Sin web".
 *  - **Kiosco**: las apps ofrecidas e instaladas entran a la lista de Lock Task
 *    ([kioskPackages]); si no, no se podrían abrir con el kiosco encendido.
 *  - **Pausa**: lo que la tienda instaló y deja de estar ofrecido (se cerró la tienda, bajó
 *    el modo, terminó la gracia) se SUSPENDE, nunca se desinstala ([reconcile]).
 *
 * ## Qué guarda (SharedPreferences MDM)
 *  - `play_store_mode`      modo de este equipo (cerrada/kosher/trabajo/lite).
 *  - `play_store_global`    catálogo global ya validado (JSON, con nivel).
 *  - `play_store_extras`    paquetes extra de ESTE equipo (JSON, sin nivel).
 *  - `play_store_installed` paquetes que instaló la tienda y siguen en el equipo.
 *  - `play_store_paused`    paquetes que la tienda pausó.
 *  - `play_store_wv_owned`  paquetes cuyo bloqueo de WebView encendió la tienda.
 *  - `play_store_pending_*` instalación terminada cuyo aviso del sistema todavía no llegó.
 *
 * ## Reglas
 *  - Fallar cerrado: modo desconocido = cerrada; configuración ilegible = se conserva la
 *    anterior (nunca se abre por un dato roto).
 *  - Todo es idempotente y barato (decenas de entradas): se puede llamar desde cualquier
 *    hilo y cuantas veces haga falta. No hay candados a propósito: dos corridas a la vez
 *    calculan el mismo plan y aplican lo mismo.
 *  - Mientras LockSuite está suspendido (el administrador levantó las restricciones) la
 *    tienda no pausa ni reanuda nada.
 */
object PlayStoreManager {

    private const val TAG = "PlayStoreManager"

    private const val KEY_MODE = "play_store_mode"
    private const val KEY_GLOBAL = "play_store_global"
    private const val KEY_EXTRAS = "play_store_extras"
    private const val KEY_UPDATED_AT = "play_store_updated_at"
    private const val KEY_INSTALLED = "play_store_installed"
    private const val KEY_PAUSED = "play_store_paused"
    private const val KEY_WV_OWNED = "play_store_wv_owned"
    private const val KEY_PENDING_PKG = "play_store_pending_pkg"
    private const val KEY_PENDING_AT = "play_store_pending_at"
    private const val KEY_DNS_SIG = "play_store_dns_sig"
    private const val KEY_KIOSK_SIG = "play_store_kiosk_sig"

    /**
     * Cuánto se espera el aviso PACKAGE_ADDED después de que el flujo dijo "instalada".
     * El flujo puede cerrarse por la sesión de instalación un instante ANTES de que el
     * sistema avise que el paquete existe; ese hueco se cubre con esta marca.
     */
    private const val PENDING_TTL_MS = 3 * 60 * 1000L

    /** Lo que mira `launcher_packages_version` el launcher kosher para volver a armarse. */
    private const val KEY_LAUNCHER_VERSION = "launcher_packages_version"

    private fun prefs(context: Context) = PrefsHelper.getMdmPrefs(context.applicationContext)

    // ─────────────────────────────────────────────────────────────────────────
    // MODO DE ESTE EQUIPO
    // ─────────────────────────────────────────────────────────────────────────

    /** Modo vigente. Lo que no se reconozca, o no esté guardado, es CERRADA. */
    fun mode(context: Context): String =
        PlayStoreCatalog.normalizeMode(prefs(context).getString(KEY_MODE, null))

    fun isOpen(context: Context): Boolean = PlayStoreCatalog.isOpen(mode(context))

    /**
     * Fija el modo. Lo llaman: el comando del panel (política por usuario), la política de
     * la pantalla del celular, los perfiles automáticos y la gracia (vía
     * [applyProfileMode]). Un valor desconocido CIERRA la tienda.
     */
    fun setMode(context: Context, rawMode: String?): Boolean {
        val ctx = context.applicationContext
        val mode = PlayStoreCatalog.normalizeMode(rawMode)
        val before = mode(ctx)
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
        Log.i(TAG, "Modo de la Tienda Play: $before -> $mode")
        reconcile(ctx)
        if (before != mode) notifyChanged(ctx)
        return true
    }

    /**
     * Modo que trae un perfil/preset. `null` o un valor que no se reconoce = NO se toca
     * (la regla de siempre de `importPolicyPresetJson`: clave ausente = no se toca; un
     * perfil viejo que no habla de la tienda no la abre ni la cierra).
     *
     * @return true si el perfil traía un modo válido y se aplicó.
     */
    fun applyProfileMode(context: Context, raw: String?): Boolean {
        val mode = PlayStoreCatalog.modeFromProfile(raw) ?: return false
        return setMode(context, mode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIGURACIÓN (viene de Firebase; ver FirebaseDeviceSync.pullPlayStoreConfig)
    // ─────────────────────────────────────────────────────────────────────────

    private class Cached(val raw: String?, val apps: List<StoreApp>)

    @Volatile private var globalCache: Cached? = null
    @Volatile private var extrasCache: Cached? = null

    /** Catálogo global (con nivel), ya validado. */
    fun globalApps(context: Context): List<StoreApp> {
        val raw = prefs(context).getString(KEY_GLOBAL, null)
        globalCache?.let { if (it.raw == raw) return it.apps }
        val apps = parseApps(raw, extra = false) ?: emptyList()
        globalCache = Cached(raw, apps)
        return apps
    }

    /** Paquetes extra de ESTE equipo (sin nivel), ya validados. */
    fun extraApps(context: Context): List<StoreApp> {
        val raw = prefs(context).getString(KEY_EXTRAS, null)
        extrasCache?.let { if (it.raw == raw) return it.apps }
        val apps = parseApps(raw, extra = true) ?: emptyList()
        extrasCache = Cached(raw, apps)
        return apps
    }

    /**
     * Reemplaza el catálogo global entero con lo que leyó el equipo de la nube
     * (`globalSettings/playStore/apps`). Reemplaza y no fusiona, por la misma razón que
     * `WhitelistManager.replaceCustomApps`: fusionar haría imposible BORRAR una app desde
     * el panel.
     *
     * @param json arreglo JSON de objetos `{packageName,label,tier,access,status,allow,block,iconUrl}`.
     * @return false si el JSON no se puede leer (se conserva lo anterior).
     */
    fun replaceGlobal(context: Context, json: String): Boolean = replaceConfig(context, json, null)

    /** Igual que [replaceGlobal] para los paquetes extra de este equipo (`devices/<id>/playStoreExtras`). */
    fun replaceExtras(context: Context, json: String): Boolean = replaceConfig(context, null, json)

    /**
     * Reemplaza el catálogo global y/o los extras de este equipo y reconcilia UNA sola vez
     * al final. Es el camino que usa la lectura de Firebase: aplicar primero uno y después
     * el otro dejaría un instante con un catálogo nuevo y extras viejos (por ejemplo, una
     * app que el administrador bloqueó para este usuario se vería habilitada hasta que
     * llegara el segundo), y cada reconciliación pausa/reanuda apps de verdad.
     *
     * `null` en un parámetro = "no se pudo leer ese nodo: se conserva lo que hay". Un
     * arreglo vacío (`"[]"`) es una lectura buena que dice "no hay apps".
     *
     * @return false si no se aplicó nada (los dos `null`, o un JSON que no se puede leer).
     */
    fun replaceConfig(context: Context, globalJson: String?, extrasJson: String?): Boolean {
        val ctx = context.applicationContext
        val global = if (globalJson != null) (parseApps(globalJson, extra = false) ?: return false) else null
        val extras = if (extrasJson != null) (parseApps(extrasJson, extra = true) ?: return false) else null
        if (global == null && extras == null) return false
        val edit = prefs(ctx).edit()
        if (global != null) edit.putString(KEY_GLOBAL, serialize(global))
        if (extras != null) edit.putString(KEY_EXTRAS, serialize(extras))
        edit.putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
        if (global != null) globalCache = null
        if (extras != null) extrasCache = null
        Log.i(TAG, "Tienda Play: catálogo global=${global?.size ?: "sin cambios"}, extras=${extras?.size ?: "sin cambios"}")
        reconcile(ctx)
        notifyChanged(ctx)
        return true
    }

    /**
     * JSON → apps validadas. `null` = el texto no es un arreglo JSON (error de lectura);
     * un arreglo vacío es válido y significa "no hay apps".
     */
    internal fun parseApps(json: String?, extra: Boolean): List<StoreApp>? {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val raws = ArrayList<PlayStoreCatalog.RawApp>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                raws.add(rawFrom(o))
            }
            PlayStoreCatalog.buildAll(raws, extra)
        } catch (e: Exception) {
            Log.w(TAG, "JSON de la Tienda Play ilegible: ${e.message}")
            null
        }
    }

    private fun rawFrom(o: JSONObject): PlayStoreCatalog.RawApp = PlayStoreCatalog.RawApp(
        packageName = textOrNull(o, "packageName"),
        label = textOrNull(o, "label"),
        tier = if (o.has("tier") && !o.isNull("tier")) o.optInt("tier", 0) else null,
        // Acceso ausente = completo; acceso presente y desconocido = la entrada se descarta
        // (lo decide PlayStoreCatalog.build). Por eso acá un texto vacío cuenta como ausente.
        access = textOrNull(o, "access"),
        allow = stringList(o.optJSONArray("allow")),
        block = stringList(o.optJSONArray("block")),
        iconUrl = textOrNull(o, "iconUrl"),
        // Estado ausente = habilitada; presente y desconocido = la entrada se descarta.
        status = textOrNull(o, "status")
    )

    private fun textOrNull(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optString(key, "").trim()
        return v.ifEmpty { null }
    }

    private fun stringList(arr: JSONArray?): List<String>? {
        if (arr == null) return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun serialize(apps: List<StoreApp>): String {
        val arr = JSONArray()
        for (a in apps) {
            val o = JSONObject()
            o.put("packageName", a.packageName)
            o.put("label", a.label)
            if (!a.extra) o.put("tier", a.tier)
            o.put("access", a.access)
            o.put("status", a.status)
            if (a.allow.isNotEmpty()) o.put("allow", JSONArray(a.allow))
            if (a.block.isNotEmpty()) o.put("block", JSONArray(a.block))
            if (a.iconUrl != null) o.put("iconUrl", a.iconUrl)
            arr.put(o)
        }
        return arr.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QUÉ SE OFRECE AHORA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lo que este equipo NO puede ofrecer aunque el catálogo lo tenga:
     *  - lo que la lista blanca PROHIBIÓ (global o de este equipo): si el administrador
     *    dijo "Prohibir" para una app, la tienda no la reabre por la puerta de atrás;
     *  - los populares no kosher, SOLO si esa política está encendida en este equipo.
     * Ante un error leyendo cualquiera de las dos, no se ofrece nada que se pueda dudar:
     * se devuelve el verificador que cierra todo (fallar cerrado).
     */
    private fun blockedChecker(context: Context): (String) -> Boolean {
        return try {
            val decisions = WhitelistManager(context).allDecisions()
            val popularOn = PolicyManager(context).isBlockPopularNonKosherEnabled()
            val checker: (String) -> Boolean = { pkg ->
                decisions[pkg] == WhitelistManager.STATE_BLOCK ||
                    (popularOn && PopularNonKosherApps.isPopularNonKosher(pkg))
            }
            checker
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer las reglas del equipo; la tienda no ofrece nada: ${e.message}")
            val closed: (String) -> Boolean = { true }
            closed
        }
    }

    /**
     * Las apps que la tienda ofrece AHORA para INSTALAR en este equipo (habilitadas),
     * ordenadas por nombre. Es lo que cuenta para instalar, la lista blanca, el kiosco y la
     * pausa. Las "solo a pedido" no están acá hasta que se aprueben (ver [listed]).
     */
    fun offered(context: Context): List<StoreApp> {
        val ctx = context.applicationContext
        val mode = mode(ctx)
        if (!PlayStoreCatalog.isOpen(mode)) return emptyList()
        return PlayStoreCatalog.effective(mode, globalApps(ctx), extraApps(ctx), blockedChecker(ctx))
    }

    /**
     * Lo que la pantalla de la tienda MUESTRA: las habilitadas (con "Instalar") y las
     * "solo a pedido" (con "Pedir"). Las bloqueadas no se muestran.
     */
    fun listed(context: Context): List<StoreApp> {
        val ctx = context.applicationContext
        val mode = mode(ctx)
        if (!PlayStoreCatalog.isOpen(mode)) return emptyList()
        return PlayStoreCatalog.listed(mode, globalApps(ctx), extraApps(ctx), blockedChecker(ctx))
    }

    fun offeredPackages(context: Context): Set<String> =
        offered(context).map { it.packageName }.toSet()

    /** ¿La tienda ofrece este paquete ahora mismo? (la comprobación de seguridad de instalar). */
    fun isOffered(context: Context, packageName: String?): Boolean =
        packageName != null && PlayStoreCatalog.isOffered(packageName, offered(context))

    /**
     * Para el receptor de paquetes: un paquete que aparece en un equipo con instalación
     * bloqueada NO se desinstala si la tienda lo ofrece. Es la red de seguridad del hueco
     * entre "la sesión de instalación terminó" y "el flujo se cerró" (el flujo ya deja
     * pasar su paquete por `updating_package`).
     */
    fun isAllowedByStore(context: Context, packageName: String): Boolean =
        isOffered(context, packageName)

    // ─────────────────────────────────────────────────────────────────────────
    // ESTADO DE CADA APP (para la pantalla de la tienda)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * NOT_INSTALLED → "Instalar" · INSTALLING → "Instalando…" · INSTALLED → "Abrir" ·
     * REQUESTABLE → "Pedir" / "Pedida": la app es "solo a pedido" y todavía no se puede
     * usar (no está instalada, o la instaló la tienda y quedó pausada hasta que se apruebe).
     */
    enum class InstallState { NOT_INSTALLED, INSTALLING, INSTALLED, REQUESTABLE }

    /** Una fila de la pantalla de la tienda. */
    data class StoreItem(
        val app: StoreApp,
        /** El nombre real: el de la app instalada si ya está, el cargado en el panel si no. */
        val label: String,
        val state: InstallState
    )

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.applicationContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** ¿Se está instalando este paquete por la tienda en este momento? */
    fun isInstalling(context: Context, packageName: String): Boolean {
        val ctx = context.applicationContext
        return try {
            UpdateFlowManager.isInstallMode(ctx) &&
                UpdateFlowManager.hasLiveFlow(ctx) &&
                UpdateFlowManager.currentPackage(ctx) == packageName
        } catch (e: Exception) {
            false
        }
    }

    /** Nombre real de una app instalada, o `null` si no está instalada / no se puede leer. */
    fun installedLabel(context: Context, packageName: String): String? {
        return try {
            val pm = context.applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Lo que muestra la pantalla de la tienda, ya con nombre real y estado. */
    fun items(context: Context): List<StoreItem> {
        val ctx = context.applicationContext
        val pausedByStore = prefs(ctx).getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()
        return listed(ctx).map { app ->
            val installed = isInstalled(ctx, app.packageName)
            val soloAPedido = app.status == PlayStoreCatalog.STATUS_REQUEST
            val state = when {
                // "Solo a pedido": sin instalar, o instalada por la tienda y pausada hasta
                // que se apruebe. Una app que ya estaba en el equipo por otro camino se
                // muestra instalada: la tienda no manda sobre lo que no instaló.
                soloAPedido && (!installed || app.packageName in pausedByStore) -> InstallState.REQUESTABLE
                installed -> InstallState.INSTALLED
                isInstalling(ctx, app.packageName) -> InstallState.INSTALLING
                else -> InstallState.NOT_INSTALLED
            }
            val label = if (installed) installedLabel(ctx, app.packageName) ?: app.label else app.label
            StoreItem(app, label, state)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.app.packageName }))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INSTALAR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * El botón "Instalar". Vuelve a comprobar TODO contra lo que se ofrece ahora (el
     * paquete que llega puede venir de una pantalla vieja) y arranca el flujo de
     * actualización en modo instalación: pantalla tapada, Google Play por debajo, solo ese
     * paquete.
     *
     * @return `null` si arrancó; si no, un mensaje listo para mostrar al usuario.
     */
    fun install(context: Context, packageName: String): String? {
        val ctx = context.applicationContext
        if (!isOpen(ctx)) return "La tienda está cerrada en este equipo."
        val app = offered(ctx).firstOrNull { it.packageName == packageName }
            ?: return "Esta aplicación ya no está disponible en la tienda."
        if (isInstalled(ctx, packageName)) return "${app.label} ya está instalada."
        return UpdateFlowManager.startInstall(ctx, packageName, app.label)
    }

    /**
     * Lo llama `UpdateFlowManager.finish()` al cerrar un flujo en modo INSTALAR (con
     * cualquier resultado). Anota qué instaló la tienda y aplica sus reglas a esa app.
     *
     * Si el flujo dice "instalada" pero el sistema todavía no avisó que el paquete existe
     * (la sesión de instalación puede terminar un instante antes de PACKAGE_ADDED), queda
     * una marca pendiente que [onPackageAdded] reclama cuando llega el aviso.
     * Si el flujo terminó con error pero la app SÍ aparece (por ejemplo, terminó de
     * instalarse justo cuando venció el tiempo), también la instaló la tienda: no estaba
     * al empezar —instalar se rechaza si ya está—, así que no hay otra explicación.
     */
    fun afterInstallFlow(context: Context, packageName: String, result: String) {
        val ctx = context.applicationContext
        val installedNow = isInstalled(ctx, packageName)
        val success = result == UpdateFlowManager.RESULT_UPDATED ||
            result == UpdateFlowManager.RESULT_UP_TO_DATE
        if (installedNow) {
            claim(ctx, packageName)
        } else if (success) {
            prefs(ctx).edit()
                .putString(KEY_PENDING_PKG, packageName)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .apply()
        }
        reconcile(ctx)
        notifyChanged(ctx)
    }

    /** PACKAGE_ADDED (instalación nueva, no reemplazo). Reclama una instalación pendiente. */
    fun onPackageAdded(context: Context, packageName: String) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val pending = p.getString(KEY_PENDING_PKG, null)
        val pendingAt = p.getLong(KEY_PENDING_AT, 0L)
        val dentroDelTiempo = pendingAt > 0L && System.currentTimeMillis() - pendingAt in 0..PENDING_TTL_MS
        val porFlujoEnCurso = try {
            UpdateFlowManager.isInstallMode(ctx) && UpdateFlowManager.currentPackage(ctx) == packageName
        } catch (e: Exception) {
            false
        }
        if ((pending == packageName && dentroDelTiempo) || porFlujoEnCurso) {
            claim(ctx, packageName)
            if (pending == packageName) {
                p.edit().remove(KEY_PENDING_PKG).remove(KEY_PENDING_AT).apply()
            }
            reconcile(ctx)
            notifyChanged(ctx)
        }
    }

    /** PACKAGE_REMOVED (desinstalación, no reemplazo): suelta lo que la tienda tenía anotado. */
    fun onPackageRemoved(context: Context, packageName: String) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val installed = p.getStringSet(KEY_INSTALLED, emptySet()) ?: emptySet()
        val paused = p.getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()
        if (packageName in installed || packageName in paused) {
            p.edit()
                .putStringSet(KEY_INSTALLED, installed - packageName)
                .putStringSet(KEY_PAUSED, paused - packageName)
                .apply()
            reconcile(ctx)
        }
    }

    private fun claim(ctx: Context, packageName: String) {
        val p = prefs(ctx)
        val installed = p.getStringSet(KEY_INSTALLED, emptySet()) ?: emptySet()
        if (packageName !in installed) {
            // Copia nueva: el set que devuelve getStringSet no se debe modificar.
            p.edit().putStringSet(KEY_INSTALLED, installed + packageName).apply()
        }
    }

    /** Paquetes que instaló la tienda y siguen en el equipo. */
    fun installedByStore(context: Context): Set<String> {
        val ctx = context.applicationContext
        val saved = prefs(ctx).getStringSet(KEY_INSTALLED, emptySet()) ?: emptySet()
        return saved.filter { isInstalled(ctx, it) }.toSet()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTEGRACIONES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lo que la tienda aporta a la lista blanca: las apps ofrecidas cuentan como
     * permitidas (sus dominios de catálogo se abren) y las de acceso "dominios" suman los
     * suyos. `WhitelistManager.reload()` y `ruleCount()` lo fusionan con la config de
     * fábrica y la del panel por un solo camino. Con la tienda cerrada no aporta nada.
     */
    fun whitelistContribution(context: Context): PlayStoreCatalog.DnsContribution =
        PlayStoreCatalog.dnsContribution(offered(context))

    /**
     * Paquetes que el kiosco (Lock Task) tiene que dejar abrir: las apps ofrecidas que
     * están instaladas. Sin esto, con el kiosco encendido, una app recién instalada por la
     * tienda no se podría abrir (el mismo síntoma que ya documentó UPDATE_ALLOWLIST).
     */
    fun kioskPackages(context: Context): Set<String> {
        val ctx = context.applicationContext
        return offered(ctx).map { it.packageName }.filter { isInstalled(ctx, it) }.toSet()
    }

    /**
     * Pone todo en el estado que corresponde al modo y la configuración de ahora:
     *  1. suelta lo anotado de apps que ya no están en el equipo;
     *  2. "sin web": enciende/apaga el bloqueo de WebView de las apps con ese acceso;
     *  3. pausa (suspende) lo que la tienda instaló y ya no ofrece, y reanuda lo que
     *     vuelve a ofrecer;
     *  4. si cambió lo que aporta a la lista blanca o al kiosco, los vuelve a armar.
     *
     * Se llama al cambiar el modo o la configuración, al terminar una instalación y en el
     * re-aplicado general (Watchdog, cada 15 minutos): así una pausa que algo deshizo se
     * corrige sola.
     */
    fun reconcile(context: Context) {
        val ctx = context.applicationContext
        try {
            val policy = PolicyManager(ctx)
            // El administrador levantó las restricciones a propósito: no se pausa ni se
            // reanuda nada. Al quitar la suspensión se vuelve a aplicar todo.
            if (policy.isLockSuiteSuspended()) return

            val p = prefs(ctx)
            val offeredApps = offered(ctx)
            val offeredSet = offeredApps.map { it.packageName }.toSet()

            val installedByStore = installedByStore(ctx)
            val savedInstalled = p.getStringSet(KEY_INSTALLED, emptySet()) ?: emptySet()

            // ── "Sin web" ──
            val webOwnedSaved = p.getStringSet(KEY_WV_OWNED, emptySet()) ?: emptySet()
            val webPlan = PlayStoreCatalog.planWebBlocks(
                desired = PlayStoreCatalog.noWebPackages(offeredApps),
                currentlyBlocked = WebViewBlockManager.getBlockedPackages(ctx),
                owned = webOwnedSaved
            )
            for (pkg in webPlan.toBlock) WebViewBlockManager.setBlocked(ctx, pkg, true)
            for (pkg in webPlan.toUnblock) WebViewBlockManager.setBlocked(ctx, pkg, false)

            // ── Pausa de lo que la tienda instaló y ya no ofrece ──
            val pausedSaved = p.getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()
            val controller = AppController(ctx)
            val plan = PlayStoreCatalog.planPause(
                offered = offeredSet,
                installedByStore = installedByStore,
                pausedByStore = pausedSaved
            )
            val pausedAfter = LinkedHashSet(plan.pausedAfter)
            for (pkg in plan.toPause) {
                // Si ya estaba suspendida por el administrador no se la "reclama": cuando
                // la tienda vuelva a ofrecerla, esa suspensión del administrador se queda.
                val yaSuspendida = try { controller.isAppSuspended(pkg) } catch (e: Exception) { false }
                if (yaSuspendida) {
                    pausedAfter.remove(pkg)
                    continue
                }
                val ok = try { controller.suspendApp(pkg, true) } catch (e: Exception) { false }
                if (!ok) {
                    // No se pudo: se reintenta en la próxima pasada (no figura pausada).
                    pausedAfter.remove(pkg)
                    Log.w(TAG, "No se pudo pausar $pkg")
                }
            }
            for (pkg in plan.toResume) {
                val ok = try { controller.suspendApp(pkg, false) } catch (e: Exception) { false }
                if (!ok) {
                    pausedAfter.add(pkg)
                    Log.w(TAG, "No se pudo reanudar $pkg")
                }
            }

            // ── Persistir lo que la tienda es dueña de ──
            val edit = p.edit()
            var dirty = false
            if (savedInstalled != installedByStore) { edit.putStringSet(KEY_INSTALLED, installedByStore); dirty = true }
            if (webOwnedSaved != webPlan.ownedAfter) { edit.putStringSet(KEY_WV_OWNED, webPlan.ownedAfter); dirty = true }
            if (pausedSaved != pausedAfter) { edit.putStringSet(KEY_PAUSED, pausedAfter); dirty = true }
            if (dirty) edit.apply()

            // ── Lista blanca y kiosco: solo si cambió lo que la tienda aporta ──
            val dnsSig = offeredApps.joinToString("|") {
                it.packageName + ":" + it.access + ":" + it.allow.joinToString(",") + ":" + it.block.joinToString(",")
            }
            if (dnsSig != p.getString(KEY_DNS_SIG, null)) {
                p.edit().putString(KEY_DNS_SIG, dnsSig).apply()
                try { WhitelistManager(ctx).reload() } catch (e: Exception) {
                    Log.w(TAG, "No se pudo recargar la lista blanca: ${e.message}")
                }
            }
            val kioskSig = kioskPackages(ctx).sorted().joinToString(",")
            if (kioskSig != p.getString(KEY_KIOSK_SIG, null)) {
                p.edit().putString(KEY_KIOSK_SIG, kioskSig).apply()
                try {
                    if (policy.isKioskLockTaskEnabled()) policy.applyKioskLockTask(true)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo actualizar la lista del kiosco: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconcile falló: ${e.message}", e)
        }
    }

    /**
     * Avisa que cambió algo visible: el launcher kosher se vuelve a armar (el ícono de la
     * tienda aparece o desaparece según el modo) y el panel recibe el estado nuevo.
     */
    private fun notifyChanged(ctx: Context) {
        try {
            prefs(ctx).edit().putLong(KEY_LAUNCHER_VERSION, System.currentTimeMillis()).apply()
        } catch (_: Exception) {
        }
        // El ícono de la tienda en la pantalla de inicio se apaga (tienda cerrada) o se
        // enciende (abierta) sin esperar a la actualización diaria del sistema.
        try {
            com.ejemplo.locksuite.widget.PlayStoreWidgetProvider.refreshAll(ctx)
        } catch (_: Exception) {
        }
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.requestSync(ctx)
        } catch (_: Exception) {
        }
    }

    /**
     * Estado para el panel (`info.playStore`). Solo datos chicos: el modo, cuántas apps se
     * ofrecen y qué instaló la tienda (para que el panel muestre la pausa).
     */
    fun statusForSync(context: Context): Map<String, Any> {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        return mapOf(
            "mode" to mode(ctx),
            "offered" to offered(ctx).size,
            "listed" to listed(ctx).size,
            "installedByStore" to installedByStore(ctx).sorted(),
            "paused" to (p.getStringSet(KEY_PAUSED, emptySet()) ?: emptySet()).sorted(),
            "updatedAt" to p.getLong(KEY_UPDATED_AT, 0L)
        )
    }
}
