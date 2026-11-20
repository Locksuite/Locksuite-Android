package com.ejemplo.locksuite.mdm

import android.os.UserManager
import org.json.JSONObject

/**
 * EnrollmentProfiles — PERFILES MAESTROS DE ALTA (9/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ PROBLEMA RESUELVE, Y DE DÓNDE SALIÓ
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * El dueño comparó LockSuite con MB Smart y con los MDM comerciales, y la diferencia
 * que sintió no es de potencia sino de PUESTA EN MARCHA: allá el instalador enrola el
 * equipo en un perfil pre-armado y el celular queda listo; acá hay que ir interruptor
 * por interruptor —más de sesenta— y acordarse de todos, en cada equipo.
 *
 * Esto es esa mitad: tres perfiles escritos **dentro del APK**, aplicables de un toque,
 * sin panel, sin red y sin cuenta configurada. Es a propósito que estén en el binario y
 * no en la nube: el momento en que más falta hacen es el alta, que es exactamente cuando
 * el equipo todavía no tiene ni cuenta de Google ni Wi-Fi configurado. Un perfil que
 * necesita internet para aplicarse no sirve para dar de alta un equipo.
 *
 * Los perfiles guardados por el administrador (los `.locksuite` y los de `presets/`)
 * siguen existiendo y no cambian: esto NO los reemplaza, les pone un piso.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ ESTE ARCHIVO NO TOCA NADA (Y POR QUÉ ESO IMPORTA)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Acá no hay `Context`, ni `SharedPreferences`, ni `DevicePolicyManager`: solo datos y
 * funciones puras que devuelven un `JSONObject`. Quien aplica es
 * `PolicyManager.applyMasterProfile()`, que arma este objeto, lo firma y se lo pasa a
 * `importPolicyPresetJson()` — **el mismo y único camino de aplicación que ya usaban los
 * presets**.
 *
 * Las dos razones:
 *
 * 1. **Un solo camino de aplicación.** Si este archivo aplicara por su cuenta, habría
 *    dos lugares que saben "cómo se aplica un perfil" y se irían separando solos. Ese
 *    es, literalmente, el modo de falla que este proyecto ya pagó cuatro veces (la clave
 *    `no_apps_control` de B.28, `DISALLOW_CONFIG_DATE_TIME` de B.38, los nombres de
 *    clave del perfil en B.40 punto 8, y `captivePortalCoverImages` de B.57).
 * 2. **Se puede probar afuera del equipo.** Al ser puro, `buildData()` se ejercita en el
 *    contenedor con aserciones reales, que es lo que un type-check no puede dar. Es la
 *    misma decisión que se tomó con `WhitelistManager.buildRules()` en B.53, y es donde
 *    se agarran los errores que importan.
 *
 * ⚠️ **Las claves de abajo tienen que existir en `PolicyManager.importPolicyPresetJson()`.**
 * Una clave que el importador no lee se acepta en silencio y no hace nada: el perfil se
 * aplica "con éxito" y la protección nunca se pone. Para que eso no se pueda volver a
 * escapar, `tools/check_profile_sync.py` compara este archivo contra el importador y
 * falla si alguna clave de acá no se lee allá. **Correrlo después de tocar cualquiera de
 * los dos.**
 */
object EnrollmentProfiles {

    const val LEVEL_STRICT = "kosher_estricto"
    const val LEVEL_WORK = "trabajo"
    const val LEVEL_BASE = "base_minima"

    /**
     * Período de gracia con cierre automático (10/9/2026). Pedido del dueño: bloquear lo
     * claramente no kosher, dejar lo dudoso a criterio del usuario por un tiempo, con la
     * tienda abierta, y que después se cierre solo. Ver `mdm/GracePeriodManager.kt`.
     */
    const val LEVEL_GRACE = "gracia"

    /**
     * Nivel 5 — "Solo llamadas" (29/10/2026, B.101). Pedido del dueño: un nivel automático
     * que CIERRA la Tienda Play. Hoy lleva las mismas protecciones que el Nivel 1; lo único
     * que lo distingue es la tienda (cerrada, no kosher). Si más adelante tiene que hacer
     * algo más (por ejemplo ocultar todo salvo el teléfono), se agrega acá, en sus propios
     * mapas, sin tocar los del Nivel 1.
     */
    const val LEVEL_CALLS_ONLY = "solo_llamadas"

    /**
     * Nivel 6 — "Lite" (29/10/2026, B.101). Pedido del dueño: abre la Tienda Play COMPLETA
     * (nivel 3 del catálogo: Kosher + Trabajo + Lite). Las protecciones son las del Nivel 2.
     */
    const val LEVEL_LITE = "lite"

    /**
     * @param id            Clave estable. Viaja por FCM y se guarda en preferencias.
     * @param label         Nombre visible en la app y en el panel.
     * @param summary       Una línea: qué deja hacer y qué no.
     * @param warning       Lo que hay que saber ANTES de aplicarlo. Se muestra en la
     *                      confirmación, no escondido en la documentación. `null` si no hay.
     * @param restrictions  Restricciones `DISALLOW_*` (constantes reales de `UserManager`).
     * @param switches      El resto de los interruptores, con las claves EXACTAS que lee
     *                      `PolicyManager.importPolicyPresetJson()`.
     * @param storeMode     Modo de la Tienda Play que fija el perfil (`cerrada`, `kosher`,
     *                      `trabajo` o `lite`; ver `PlayStoreCatalog`). `null` = el perfil no
     *                      toca la tienda ("clave ausente = no se toca"). Viaja en la clave
     *                      `playStoreMode` y la lee `importPolicyPresetJson`.
     */
    data class MasterProfile(
        val id: String,
        val label: String,
        val summary: String,
        val warning: String?,
        val restrictions: Map<String, Boolean>,
        val switches: Map<String, Boolean>,
        val storeMode: String? = null
    ) {
        /**
         * Las restricciones de este perfil que NO se pueden aplicar durante el
         * aprovisionamiento por QR (B.61). Se calcula solo sobre restricciones en `true`:
         * las que van explícitamente en `false` no bloquean el alta.
         */
        val postAlta: Set<String> get() = restrictions.filterValues { it }.keys.intersect(POST_ALTA)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LO QUE NO SE PUEDE APLICAR DURANTE EL ALTA (10/9/2026, B.61)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // El QR de aprovisionamiento aplica el perfil apenas termina de instalarse
    // LockSuite, o sea ANTES de que el instalador haya agregado la cuenta de Google
    // y ANTES de haber dejado el equipo en su idioma definitivo. Estas dos, aplicadas
    // en ese momento, **dejan un equipo que no se puede terminar de dar de alta**:
    //
    //  · `DISALLOW_MODIFY_ACCOUNTS` — no se puede agregar la cuenta de Google. Es el
    //    mismo error de razonamiento que ya costó B.41 punto 3 y B.43: LockSuite se
    //    instala con el equipo SIN cuenta, así que "todavía no hay cuenta" es el
    //    ESTADO DE FÁBRICA del procedimiento, no un caso raro.
    //  · `DISALLOW_CONFIG_LOCALE` — no se puede dejar el equipo en el idioma
    //    definitivo, y el aviso de los perfiles pide justamente hacerlo antes.
    //
    // Las dos se aplican después, con el botón "Terminar alta" del panel (comando
    // `FINISH_ENROLLMENT`). Mientras tanto el equipo YA está protegido por todo el
    // resto del perfil, incluido el piso anti-manipulación entero: el hueco es
    // exactamente "puede agregar cuentas y cambiar el idioma", que es lo que hay que
    // poder hacer para terminar el alta.
    //
    // ⚠️ Si mañana se agrega una restricción que también bloquee un paso del alta,
    // va acá. `check_profile_sync.py` verifica que todo lo que está en esta lista
    // exista de verdad en algún perfil, para que no quede una entrada muerta.
    val POST_ALTA: Set<String> = setOf(
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_CONFIG_LOCALE
    )

    // ─────────────────────────────────────────────────────────────────────────
    // LO QUE NINGÚN PERFIL DE ALTA ENCIENDE, Y POR QUÉ
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Estas cuatro NO están en ningún perfil de abajo. No es olvido: cada una puede
    // dejar un equipo inservible o irrecuperable, y ninguna se puede deshacer desde el
    // propio equipo si sale mal. Van a mano, con el equipo a la vista.
    //
    //  · `kioskLockTask` (B.35) — si el marcador telefónico no está en la lista blanca
    //    del launcher, en ese equipo NO se puede marcar `*#*#9999#*#*`, que es LA vía de
    //    recuperación. Un perfil de alta que lo encienda puede dejar un equipo sin
    //    salida antes de que nadie lo pruebe.
    //  · `nokiaTouchEnabled=false` (B.36) — apagar el táctil en un equipo sin teclas
    //    físicas lo deja manejable solo desde el panel.
    //  · `accSuspendAll` (B.15) — costo "alto" por decisión explícita del dueño: con la
    //    accesibilidad caída el equipo no sirve para nada hasta reactivarla.
    //  · Los tres interruptores de la lista blanca (B.53) — el modo estricto tiene un
    //    despliegue por etapas (simulación → bloquear de verdad) que depende de haber
    //    completado el catálogo con el equipo en la mano. Un perfil de alta que mueva un
    //    equipo entre esas etapas en silencio es exactamente lo que B.53 pidió evitar.
    //    Al no nombrarlas, un perfil no las toca: clave ausente = no se toca.
    //
    // El perfil que el administrador guarda desde un equipo bien configurado SÍ las
    // lleva (`exportPolicyPresetJson`), y está bien que sea así: ahí hubo una persona
    // mirando el equipo. La diferencia entre los dos casos es deliberada.

    /** Restricciones anti-manipulación. Es el piso de todos los perfiles. */
    private val ANTI_TAMPER: Map<String, Boolean> = mapOf(
        UserManager.DISALLOW_FACTORY_RESET to true,
        UserManager.DISALLOW_SAFE_BOOT to true,
        UserManager.DISALLOW_DEBUGGING_FEATURES to true,
        // LockSuite sigue protegido contra desinstalación como Device Owner (setUninstallBlocked),
        // pero el usuario puede desinstalar aplicaciones normales.
        UserManager.DISALLOW_UNINSTALL_APPS to false,
        UserManager.DISALLOW_USER_SWITCH to true,
        UserManager.DISALLOW_CONFIG_VPN to true
    )

    /**
     * Restricciones unificadas para todos los perfiles operativos (18/11/2026):
     * base del modo Trabajo, dejando habilitados Zona WiFi, fondo de pantalla, idioma,
     * desinstalación de apps, cuentas y medios físicos. Lo único que distingue a los
     * perfiles operativos entre sí es el modo de la Tienda Play (`storeMode`).
     */
    private val UNIFIED_RESTRICTIONS: Map<String, Boolean> = ANTI_TAMPER + mapOf(
        UserManager.DISALLOW_INSTALL_APPS to true,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to true,
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS to true,
        UserManager.DISALLOW_CONFIG_TETHERING to false,
        UserManager.DISALLOW_SET_WALLPAPER to false,
        UserManager.DISALLOW_CONFIG_LOCALE to false,
        UserManager.DISALLOW_MODIFY_ACCOUNTS to false,
        UserManager.DISALLOW_APPS_CONTROL to false,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA to false
    )

    private val UNIFIED_SWITCHES: Map<String, Boolean> = mapOf(
        "adBlockingEnabled" to true,
        "gifsBlocked" to true,
        "whatsappBlockStatus" to true,
        "whatsappBlockChannels" to true,
        "mercadoPagoBlockOffersAccessibility" to true,
        "mercadoPagoBlockOffersVpn" to true,
        "blockMlInMp" to true,
        "blockPopularNonKosher" to true,
        "googleAccountWebBlocked" to true,
        "googleAccountBlockStrict" to false,
        "captivePortalGuard" to true,
        "captivePortalCoverImages" to true,
        "contactPhotoPickerBlocked" to true,
        "geminiMessagesBlocked" to true,
        "santanderBlocked" to true,
        // Sin launcher kosher en ningún perfil (decisión 18/11/2026).
        "kosherLauncherEnabled" to false,
        "hideSuspendedApps" to true,
        "accessibilityProtection" to true,
        "accBounceSettings" to true,
        "accNag" to true,
        "bootGateEnabled" to true,
        // Activadas en todos los perfiles operativos (18/11/2026).
        "bootGateWaitAccessibility" to true,
        "accSuspendAll" to true,
        "kioskLockTask" to false,
        "nokiaKeypadMode" to false,
        "nokiaTouchEnabled" to true,
        "internetBlocked" to false,
        "cameraDisabled" to false,
        "screenCaptureBlocked" to false,
        "statusBarDisabled" to false,
        "keyguardDisabled" to false
    )

    private val BASE_RESTRICTIONS: Map<String, Boolean> = ANTI_TAMPER

    private val BASE_SWITCHES: Map<String, Boolean> = mapOf(
        "accessibilityProtection" to true,
        "geminiMessagesBlocked" to true,
        "santanderBlocked" to true,
        "bootGateEnabled" to true,
        "captivePortalGuard" to true,
        "captivePortalCoverImages" to true,
        "internetBlocked" to false,
        "accSuspendAll" to false,
        "kioskLockTask" to false,
        "nokiaKeypadMode" to false,
        "nokiaTouchEnabled" to true,
        "statusBarDisabled" to false,
        "keyguardDisabled" to false
    )

    /**
     * Los perfiles, en el orden en que se muestran.
     *
     * El orden es de más cerrado a más abierto a propósito: el caso frecuente —dar de
     * alta un equipo kosher— está arriba, y el que hay que pensar dos veces está abajo.
     * Los números del nombre ("Nivel 5", "Nivel 6") son los que usa el dueño y NO siguen
     * este orden: se agregaron después y los viejos no se renumeran.
     *
     * **Políticas unificadas (18/11/2026):**
     * Los 5 perfiles operativos (Niveles 5, 1, 2, 6 y 4) aplican exactamente las mismas
     * restricciones e interruptores (sin launcher kosher, permitiendo zona WiFi, fondo de
     * pantalla, idioma, desinstalar apps y cuentas, con suspensión sin accesibilidad y espera
     * de accesibilidad en arranque protegido activas). Solo cambia `storeMode`:
     *  · Nivel 5 Solo llamadas → `cerrada` · Nivel 1 Kosher → `kosher` · Nivel 2 Trabajo →
     *    `trabajo` · Nivel 6 Lite → `lite` · Nivel 4 Gracia → `lite` mientras dure y `kosher`
     *    al terminar (al aplicar Nivel 1).
     *  · Nivel 3 Base mínima → `null`: perfil diagnóstico sin filtros de contenido; no toca la tienda.
     */
    val ALL: List<MasterProfile> = listOf(
        MasterProfile(
            id = LEVEL_CALLS_ONLY,
            label = "Nivel 5 — Solo llamadas",
            summary = "Mismas protecciones unificadas que el resto de los niveles (sin launcher kosher; " +
                "permite zona WiFi, fondo, idioma y desinstalar apps), con la Tienda Play CERRADA.",
            warning = "Activa la suspensión de apps si la Accesibilidad se apaga y la espera de " +
                "Accesibilidad en el arranque protegido: dejá el servicio de Accesibilidad activo.",
            restrictions = UNIFIED_RESTRICTIONS,
            switches = UNIFIED_SWITCHES,
            storeMode = PlayStoreCatalog.MODE_CLOSED
        ),
        MasterProfile(
            id = LEVEL_STRICT,
            label = "Nivel 1 — Kosher estricto",
            summary = "Mismas protecciones unificadas (sin launcher kosher; permite zona WiFi, fondo, " +
                "idioma y desinstalar apps; sin navegador, ofertas ni estados). Lo único que cambia " +
                "es la Tienda Play en modo kosher (nivel 1).",
            warning = "Activa la suspensión de apps si la Accesibilidad se apaga y la espera de " +
                "Accesibilidad en el arranque protegido: dejá el servicio de Accesibilidad activo.",
            restrictions = UNIFIED_RESTRICTIONS,
            switches = UNIFIED_SWITCHES,
            storeMode = PlayStoreCatalog.MODE_KOSHER
        ),
        MasterProfile(
            id = LEVEL_WORK,
            label = "Nivel 2 — Trabajo",
            summary = "Mismas protecciones unificadas (sin launcher kosher; permite zona WiFi, fondo, " +
                "idioma y desinstalar apps). La Tienda Play queda en modo trabajo (niveles 1 y 2).",
            warning = "Activa la suspensión de apps si la Accesibilidad se apaga y la espera de " +
                "Accesibilidad en el arranque protegido: dejá el servicio de Accesibilidad activo.",
            restrictions = UNIFIED_RESTRICTIONS,
            switches = UNIFIED_SWITCHES,
            storeMode = PlayStoreCatalog.MODE_WORK
        ),
        MasterProfile(
            id = LEVEL_LITE,
            label = "Nivel 6 — Lite",
            summary = "Mismas protecciones unificadas (sin launcher kosher; permite zona WiFi, fondo, " +
                "idioma y desinstalar apps), con la Tienda Play abierta COMPLETA: niveles 1, 2 y 3.",
            warning = "Activa la suspensión de apps si la Accesibilidad se apaga y la espera de " +
                "Accesibilidad en el arranque protegido: dejá el servicio de Accesibilidad activo.",
            restrictions = UNIFIED_RESTRICTIONS,
            switches = UNIFIED_SWITCHES,
            storeMode = PlayStoreCatalog.MODE_LITE
        ),
        MasterProfile(
            id = LEVEL_GRACE,
            label = "Nivel 4 — Período de gracia",
            summary = "Mismas protecciones unificadas con la Tienda Play abierta COMPLETA por un plazo " +
                "elegido. Al vencer se aplica el Nivel 1, pasando la Tienda Play a modo kosher.",
            warning = "Mientras dure, la Tienda Play queda abierta completa (niveles 1, 2 y 3) y al vencer " +
                "pasa a modo kosher. Activa la suspensión de apps sin Accesibilidad y su espera en arranque. " +
                "El plazo se elige al aplicarlo.",
            restrictions = UNIFIED_RESTRICTIONS,
            switches = UNIFIED_SWITCHES,
            storeMode = PlayStoreCatalog.MODE_LITE
        ),
        MasterProfile(
            id = LEVEL_BASE,
            label = "Nivel 3 — Base mínima",
            summary = "Solo el piso anti-manipulación: no se puede formatear, ni entrar " +
                "en modo seguro, ni desinstalar LockSuite. Ningún filtro de contenido. " +
                "No toca la Tienda Play.",
            // Este es el único que AFLOJA. Decirlo en el aviso y no en la
            // descripción: la confirmación es lo único que la persona lee seguro.
            warning = "Este perfil APAGA los filtros de contenido (anuncios, GIFs, estados " +
                "de WhatsApp, ofertas, cuenta de Google, launcher kosher). Usalo para " +
                "diagnosticar, no para entregar un equipo.",
            restrictions = BASE_RESTRICTIONS,
            switches = BASE_SWITCHES,
            storeMode = null
        )
    )

    fun byId(id: String?): MasterProfile? = ALL.firstOrNull { it.id == id }

    /**
     * Arma el objeto `data` del perfil, con la misma forma que consume
     * `PolicyManager.importPolicyPresetJson()`.
     *
     * Función pura: no toca preferencias, ni el sistema, ni la red. Se puede ejercitar
     * fuera del equipo, que es donde se agarran los errores de esta clase (una clave mal
     * escrita no da error en ningún lado: se acepta y no hace nada).
     *
     * @return el objeto `data`, o `null` si el id no existe.
     */
    @JvmOverloads
    fun buildData(profileId: String?, incluirPostAlta: Boolean = true): JSONObject? {
        val profile = byId(profileId) ?: return null
        val data = JSONObject()

        val restrictionsObj = JSONObject()
        for ((key, value) in profile.restrictions) {
            // B.61 — durante el aprovisionamiento por QR se omiten las de [POST_ALTA] en true.
            // Si están en false no bloquean el alta y se conservan explícitas.
            if (!incluirPostAlta && value && key in POST_ALTA) continue
            restrictionsObj.put(key, value)
        }
        data.put("restrictions", restrictionsObj)

        for ((key, value) in profile.switches) {
            data.put(key, value)
        }

        // Tienda Play (B.101). Solo se escribe si el perfil decide sobre la tienda: sin la
        // clave, el importador no la toca. Es TEXTO (el modo), no un booleano.
        profile.storeMode?.let { data.put("playStoreMode", it) }

        // Nunca se manda un array vacío: Realtime Database los descarta y la clave
        // desaparece, que fue exactamente el bug de B.28 (el perfil firmado y el perfil
        // guardado dejaban de ser el mismo objeto y la firma no coincidía nunca). Acá el
        // perfil no pasa por la base, pero la regla se respeta igual para que las dos
        // rutas produzcan objetos con la misma forma y una sola firma sirva para las dos.
        return data
    }

    /**
     * Nombre de perfil que queda guardado en el equipo. Lleva la fecha para que en el
     * panel se pueda distinguir un equipo dado de alta hoy de uno de hace tres meses.
     */
    /**
     * Lo que quedó pendiente de aplicar tras un alta por QR. Vacío si el perfil no
     * toca ninguna de las de [POST_ALTA] — en ese caso el alta ya quedó completa y el
     * panel no tiene por qué mostrar el botón "Terminar alta".
     */
    fun pendientesDeAlta(profileId: String?): Set<String> =
        byId(profileId)?.postAlta ?: emptySet()

    fun presetNameFor(profileId: String?): String {
        val profile = byId(profileId) ?: return "Perfil LockSuite"
        return profile.label
    }
}
