const { onRequest } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const admin = require("firebase-admin");
const crypto = require("crypto");
const playLookup = require("./play_lookup");
const pinSecurity = require("./pin_security");
const wakePolicy = require("./wake_policy");
const auroraDevices = require("./aurora_devices");
const auroraGPlay = require("./aurora_gplay");
const auroraSync = require("./aurora_sync");

const FUNCTION_OPTIONS = {
  region: "us-central1",
  cors: false,
  invoker: "public",
};

admin.initializeApp({
  databaseURL: "https://locksuite-nueva-default-rtdb.firebaseio.com"
});

// Orígenes permitidos para los endpoints de administración (panel web y pruebas locales).
const ALLOWED_ADMIN_ORIGINS = new Set([
  "https://locksuite-nueva.web.app",
  "https://locksuite-nueva.firebaseapp.com",
  "http://localhost:5000",
  "http://127.0.0.1:5000",
  "http://localhost:8080",
  "http://127.0.0.1:8080",
]);

function applyAdminCors(req, res) {
  const origin = req && req.headers && typeof req.headers.origin === "string"
    ? req.headers.origin.trim()
    : "";
  res.set("Vary", "Origin");
  if (ALLOWED_ADMIN_ORIGINS.has(origin)) {
    res.set("Access-Control-Allow-Origin", origin);
  }
  res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
}

// El id de un equipo se usa para armar rutas de la base (`devices/<id>`, `deviceSecrets/<id>`). Una
// barra en un id armado a mano apuntaría a OTRO nodo (p. ej. "abc/info"), y como un administrador
// puede escribir cualquier cosa bajo `devices/<id>/...`, podría fabricarse ahí un `trustedAdmins`
// propio y pasar la puerta del PIN. Se acepta solo lo que es UNA clave válida de la base
// (sin `/ . $ # [ ]` ni caracteres de control).
const DEVICE_ID_RE = /^[^/.$#\[\]\u0000-\u001f\u007f]{1,128}$/;

// Errores hacia el panel. Lo que lanzamos nosotros es un objeto plano {status, message[, extra]}
// (`extra` suma campos a la respuesta: retryAfterSec / attemptsLeft del PIN).
// Los errores de AUTENTICACIÓN de Firebase (token vencido, revocado, cuenta deshabilitada) salen como
// 401 para que el panel sepa que tiene que volver a iniciar sesión; antes llegaban como 500.
function sendError(res, e, tag) {
  console.error(`${tag} error:`, e);
  const code = e && typeof e.code === "string" ? e.code : "";
  if (code.startsWith("auth/")) {
    const revocada = code === "auth/id-token-revoked" || code === "auth/user-disabled";
    res.status(401).json({
      error: revocada
        ? "SESSION_REVOKED: tu sesión fue revocada o la cuenta está deshabilitada. Cerrá sesión y volvé a entrar."
        : "SESSION_INVALID: tu sesión venció. Cerrá sesión y volvé a entrar.",
    });
    return;
  }
  const status = (e && Number.isInteger(e.status) && e.status >= 400 && e.status < 600) ? e.status : 500;
  if (status >= 500) {
    res.status(status).json({ error: "Error interno del servidor." });
    return;
  }
  res.status(status).json({ error: (e && e.message) || "Solicitud inválida.", ...((e && e.extra) || {}) });
}

// Verifica el ID token Y que no haya sido revocado (checkRevoked = true). Sin eso, un administrador
// al que se le quita el acceso (o cuya cuenta se roba y se deshabilita/revoca) seguía mandando
// comandos hasta que el token vencía solo, hasta una hora después.
async function verifyAdminToken(req) {
  const authHeader = req.headers.authorization || "";
  if (!authHeader.startsWith("Bearer ")) {
    throw { status: 401, message: "No autorizado: falta token de autenticación." };
  }
  const decoded = await admin.auth().verifyIdToken(authHeader.slice(7), true);
  await checkAdminByUid(decoded.uid, decoded);
  return decoded;
}

// Lista blanca de comandos válidos
const ALLOWED_COMMANDS = new Set([
  "LOCK_DEVICE", "BLOCK_INSTALL_APPS", "UNBLOCK_INSTALL_APPS",
  "BLOCK_UNINSTALL_APPS", "UNBLOCK_UNINSTALL_APPS", "BLOCK_FACTORY_RESET",
  "UNBLOCK_FACTORY_RESET", "BLOCK_ADB", "UNBLOCK_ADB", "BLOCK_USER_SWITCH",
  "UNBLOCK_USER_SWITCH", "BLOCK_MODIFY_ACCOUNTS", "UNBLOCK_MODIFY_ACCOUNTS",
  "BLOCK_SAFE_BOOT", "UNBLOCK_SAFE_BOOT", "BLOCK_UNKNOWN_SOURCES",
  "UNBLOCK_UNKNOWN_SOURCES", "BLOCK_VOLUME", "UNBLOCK_VOLUME",
  "BLOCK_APPS_CONTROL", "UNBLOCK_APPS_CONTROL", "BLOCK_BLUETOOTH_SHARING",
  "UNBLOCK_BLUETOOTH_SHARING", "BLOCK_EXTERNAL_MEDIA", "UNBLOCK_EXTERNAL_MEDIA",
  "BLOCK_TETHERING", "UNBLOCK_TETHERING", "BLOCK_WIFI", "UNBLOCK_WIFI",
  "BLOCK_BLUETOOTH", "UNBLOCK_BLUETOOTH", "BLOCK_VPN", "UNBLOCK_VPN",
  "DISABLE_CAMERA", "ENABLE_CAMERA", "BLOCK_SCREEN_CAPTURE",
  "UNBLOCK_SCREEN_CAPTURE", "DISABLE_STATUSBAR", "ENABLE_STATUSBAR",
  "DISABLE_KEYGUARD", "ENABLE_KEYGUARD", "BLOCK_INTERNET", "UNBLOCK_INTERNET",
  "ENABLE_ADBLOCK", "DISABLE_ADBLOCK", "HIDE_APP", "UNHIDE_APP",
  "SUSPEND_APP", "UNSUSPEND_APP", "BLOCK_WEBVIEW", "UNBLOCK_WEBVIEW",
  "UPDATE_ALLOWLIST",
  "BLOCK_WHATSAPP_STATUS", "UNBLOCK_WHATSAPP_STATUS", "BLOCK_WHATSAPP_CHANNELS",
  "UNBLOCK_WHATSAPP_CHANNELS", "CHANGE_PIN", "ENABLE_STEALTH", "DISABLE_STEALTH",
  "BLOCK_GIFS", "UNBLOCK_GIFS", "UPDATE_APP", "UPDATE_LOCKSUITE", "VERIFY_PIN",
  "BLOCK_APP_INTERNET", "UNBLOCK_APP_INTERNET", "UNSUSPEND_ALL_APPS",
  "SET_HIDE_SUSPENDED_APPS", "APPLY_PRESET_PROFILE",
  // ── PERFILES MAESTROS DE ALTA (9/9/2026) — ver app/mdm/EnrollmentProfiles.kt ──
  // APPLY_PRESET_PROFILE manda el perfil ENTERO dentro del data de FCM y por eso
  // tiene el tope de 3.000 bytes de mas abajo. Esta MEDIDO que por ahi no entra un
  // perfil completo: el de politicas ya pesa 2.009 bytes y con las listas de apps
  // que le faltan (B.28) llega a 3.580. Estos dos comandos no mandan el perfil:
  //   APPLY_MASTER_PROFILE -> el perfil esta dentro del APK, viaja solo el nivel.
  //   APPLY_PROFILE        -> el perfil vive en globalSettings/profiles/<id> y
  //                           viaja solo el id (mismo patron que SYNC_WHITELIST).
  // Los dos EXIGEN PIN: cambian politicas del equipo, asi que no van en la
  // excepcion de UPDATE_* de mas abajo.
  "APPLY_MASTER_PROFILE", "APPLY_PROFILE",
  // Cancelar el periodo de gracia (Nivel 4) sin aplicar el perfil de cierre. Ver
  // app/mdm/GracePeriodManager.kt. Exige PIN como el resto.
  "CANCEL_GRACE_PERIOD",
  "START_TEMP_INSTALL_WINDOW", "STOP_TEMP_INSTALL_WINDOW",
  // ── SOLICITUDES DE APPS (10/9/2026, B.59) — ver app/mdm/AppRequestManager.kt ──
  // Avisa al equipo de que un pedido suyo fue contestado, para que le muestre el
  // aviso al usuario. NO cambia ninguna politica por si mismo: lo que abre una app
  // es `globalSettings/allowedPackages` mas SYNC_WHITELIST y UNSUSPEND_APP/UNHIDE_APP.
  // Exige PIN igual que el resto (no esta en la excepcion de UPDATE_*), porque el
  // panel lo manda dentro del mismo flujo de aprobacion que si cambia politicas y
  // pedir el PIN una sola vez para todo el flujo es lo correcto.
  "SYNC_APP_REQUESTS",
  // ── TIENDA PLAY (29/10/2026, B.101) — ver app/mdm/PlayStoreManager.kt ──
  // La tienda instala desde Google Play OFICIAL con la pantalla tapada (el mismo flujo
  // de UPDATE_APP). Son dos comandos y ninguno lleva la lista de apps:
  //   SET_PLAY_STORE_MODE -> el modo de ESTE equipo (cerrada/kosher/trabajo/lite). Viaja
  //                          en `storeMode` y se valida aca abajo.
  //   SYNC_PLAY_STORE     -> avisa que hay que releer el catalogo global
  //                          (globalSettings/playStore/apps) y los extras de este equipo
  //                          (devices/<id>/playStoreExtras). El catalogo no viaja por FCM:
  //                          el data tiene tope de ~4 KB (misma leccion que SYNC_WHITELIST).
  // Los dos EXIGEN PIN como el resto: abrir la tienda cambia que puede instalar el usuario.
  "SET_PLAY_STORE_MODE", "SYNC_PLAY_STORE",
  // ── DETECTOR DE NAVEGADORES EMBEBIDOS (10/9/2026, B.60) ──
  // Ver app/mdm/EmbeddedBrowserDetector.kt. Son DOS interruptores separados a
  // proposito: encender el detector no alcanza para que bloquee. DISABLE_IAB_SIMULATION
  // es el unico que lo hace bloquear de verdad, y no hay que tocarlo sin haber mirado
  // la auditoria del panel unos dias — un falso positivo rompe una app y nadie sabe
  // por que (B.43, B.50, B.15).
  "ENABLE_IAB_FINDER", "DISABLE_IAB_FINDER",
  "ENABLE_IAB_SIMULATION", "DISABLE_IAB_SIMULATION",
  "CLEAR_IAB_AUDIT",
  // ── ALTA POR QR (10/9/2026, B.61) — ver app/receiver/DeviceAdminReceiver.kt ──
  // Aplica lo que el aprovisionamiento dejo afuera a proposito para que el equipo se
  // pudiera terminar de dar de alta (agregar la cuenta de Google y fijar el idioma).
  "FINISH_ENROLLMENT",
  "BLOCK_MP_OFFERS_ACCESSIBILITY", "UNBLOCK_MP_OFFERS_ACCESSIBILITY",
  "BLOCK_MP_OFFERS_VPN", "UNBLOCK_MP_OFFERS_VPN",
  "BLOCK_MERCADOPAGO_OFFERS", "UNBLOCK_MERCADOPAGO_OFFERS",
  "BLOCK_ML_IN_MP", "UNBLOCK_ML_IN_MP",
  "BLOCK_POPULAR_NON_KOSHER", "UNBLOCK_POPULAR_NON_KOSHER",
  "ENABLE_FRP", "DISABLE_FRP",
  "SUSPEND_BROWSERS", "UNSUSPEND_BROWSERS",
  "SUSPEND_SYSTEM_WEBVIEW", "UNSUSPEND_SYSTEM_WEBVIEW",
  "ENABLE_SETTINGS_EVASION", "DISABLE_SETTINGS_EVASION",
  "ENABLE_HIDE_SUSPENDED_APPS", "DISABLE_HIDE_SUSPENDED_APPS",
  "ENABLE_KOSHER_LAUNCHER", "DISABLE_KOSHER_LAUNCHER",
  // Cancelar una actualizacion de app en curso (contraparte de UPDATE_APP).
  "CANCEL_UPDATE_APP",
  // Suspension temporal de LockSuite: levanta TODAS las restricciones del
  // equipo y desbloquea todas las apps; al reanudar, PolicyManager reconstruye
  // el estado desde las preferencias guardadas. Exige PIN del dispositivo (no
  // esta en la excepcion de UPDATE_*), porque deja el equipo sin proteccion.
  "SUSPEND_LOCKSUITE", "RESUME_LOCKSUITE",
  // Borrar LockSuite del equipo: libera TODO (igual que SUSPEND_LOCKSUITE), ademas
  // saca Device Owner y abre el pedido de desinstalacion en el telefono. Irreversible.
  // Exige PIN del dispositivo (NO esta en la excepcion de UPDATE_* de mas abajo) y
  // firma HMAC, porque es la accion mas destructiva del panel. Ver app/mdm/DeviceRelease.kt.
  "UNINSTALL_LOCKSUITE",
  "PROTECT_ACCESSIBILITY", "UNPROTECT_ACCESSIBILITY",
  // Sub-interruptores de "Protecciones de Accesibilidad" (17/8/2026). Todos
  // exigen PIN del dispositivo: NO estan en la excepcion de UPDATE_* de mas
  // abajo. Cambian el comportamiento del filtro visual, asi que un operador del
  // panel no deberia poder aflojarlos sin conocer el PIN del equipo.
  // Bloquear el cambio de idioma del sistema (DISALLOW_CONFIG_LOCALE). Es la
  // defensa mas barata contra la evasion por idioma: cualquier filtro que compare
  // texto de pantalla queda mudo si el equipo cambia a un idioma no previsto.
  "BLOCK_LOCALE_CHANGE", "UNBLOCK_LOCALE_CHANGE",
  // Ajustes/actividad de la cuenta de Google (4/9/2026). Cierra el camino
  // Ajustes -> Google -> Datos y privacidad -> Historial de YouTube, que mostraba
  // los videos vistos dentro de Ajustes, sin ningun navegador de por medio.
  "BLOCK_GOOGLE_ACCOUNT_WEB", "UNBLOCK_GOOGLE_ACCOUNT_WEB",
  // Modo del bloqueo de la cuenta: normal (deja administrarla, bloquea el
  // historial por dominio) o estricto (no abre ninguna pantalla de la cuenta).
  "SET_GOOGLE_ACCOUNT_MODE_STRICT", "SET_GOOGLE_ACCOUNT_MODE_NORMAL",
  // Portal cautivo: la ventana de "Iniciar sesion en la red" esquiva la VPN por
  // diseno de Android, asi que no se puede filtrar por dominios. El interruptor
  // le tapa las imagenes, la cierra al validar la red y le pone tope de
  // inactividad.
  "ENABLE_CAPTIVE_PORTAL_GUARD", "DISABLE_CAPTIVE_PORTAL_GUARD",
  // 8/9/2026 — solo el tapado de imagenes de esa ventana, sin apagar el guard
  // entero. Es la salida de emergencia cuando un portal real queda inusable y el
  // usuario esta viajando: ver B.50 y mdm/CaptivePortalPolicy.kt.
  "ENABLE_CAPTIVE_PORTAL_IMAGES", "DISABLE_CAPTIVE_PORTAL_IMAGES",
  // ── MODO LISTA BLANCA (8/9/2026) ──
  // El filtro estricto: solo resuelven los dominios de las apps permitidas.
  // Ojo con SET_WHITELIST_ENFORCE: es el que pasa de "solo registrar" a bloquear
  // de verdad, o sea el unico de este grupo que puede dejar un equipo a medio
  // funcionar. Exige PIN como todos los de aca (no esta en la excepcion de
  // UPDATE_*). La configuracion (que apps, que dominios) NO viaja por FCM: va en
  // globalSettings/whitelist y SYNC_WHITELIST solo avisa que hay que releerla —
  // el data de FCM tiene tope de ~4 KB y el catalogo lo pasa (misma leccion de
  // B.28 con los presets).
  "ENABLE_WHITELIST_MODE", "DISABLE_WHITELIST_MODE",
  "SET_WHITELIST_SIMULATION", "SET_WHITELIST_ENFORCE",
  "ENABLE_WHITELIST_SHARED_CDN", "DISABLE_WHITELIST_SHARED_CDN",
  "SYNC_WHITELIST", "CLEAR_WHITELIST_AUDIT",
  // ── Reglas DNS por equipo desde el panel (10/9/2026) ──
  //
  // El dominio viaja en `packages`. Hasta hoy estas reglas solo se podian tocar
  // desde la pantalla del propio celular, y eso dejaba la salida de emergencia
  // del filtro fuera del alcance del administrador: FORCE_ALLOW es lo unico que
  // le gana a TODO —a la lista de dominios no kosher, a la lista negra global y
  // hasta a la infraestructura—, asi que tiene que poder tocarse desde el panel.
  "SET_DOMAIN_RULE_BLOCK", "SET_DOMAIN_RULE_ALLOW",
  "SET_DOMAIN_RULE_FORCE_BLOCK", "SET_DOMAIN_RULE_FORCE_ALLOW", "REMOVE_DOMAIN_RULE",
  // Selector de foto de contactos / Google Illustrations (4/9/2026)
  "BLOCK_CONTACT_PHOTO_PICKER", "UNBLOCK_CONTACT_PHOTO_PICKER",
  // Bloqueo de Gemini en Google Mensajes (5/11/2026, B.108)
  "BLOCK_GEMINI_MESSAGES", "UNBLOCK_GEMINI_MESSAGES",
  // Bloqueo quirurgico de navegacion externa en Santander Argentina (Capa 3)
  "BLOCK_SANTANDER", "UNBLOCK_SANTANDER",
  "BLOCK_PLAY_STORE_APP", "UNBLOCK_PLAY_STORE_APP",
  "BLOCK_PLAY_STORE_ACCESSIBILITY", "UNBLOCK_PLAY_STORE_ACCESSIBILITY",
  "ENABLE_ACC_BOUNCE_SETTINGS", "DISABLE_ACC_BOUNCE_SETTINGS",
  "ENABLE_ACC_NAG", "DISABLE_ACC_NAG",
  "ENABLE_ACC_SUSPEND_ALL", "DISABLE_ACC_SUSPEND_ALL",
  "ENABLE_BOOT_GATE_ACCESSIBILITY", "DISABLE_BOOT_GATE_ACCESSIBILITY",
  // Arranque protegido: cierra la red al bootear hasta que el filtro DNS esta
  // realmente funcionando (ver app/util/BootGate.kt).
  "ENABLE_BOOT_GATE", "DISABLE_BOOT_GATE",
  // Kiosco real del sistema operativo (Lock Task). El equipo SOLO puede abrir los
  // paquetes de la lista blanca del launcher, y lo hace cumplir Android, no LockSuite.
  // OJO: si el marcador telefonico no esta en esa lista, el codigo de recuperacion
  // *#*#9999#*#* no se puede marcar. Ver PolicyManager.applyKioskLockTask().
  "ENABLE_KIOSK_LOCK_TASK", "DISABLE_KIOSK_LOCK_TASK",
  // Modo telefono de teclas (estilo Nokia) y su interruptor de tactil.
  "ENABLE_NOKIA_MODE", "DISABLE_NOKIA_MODE",
  "ENABLE_NOKIA_TOUCH", "DISABLE_NOKIA_TOUCH",
  // ── Restricciones del registro declarativo (app/mdm/PolicySpec.kt) ──
  // Esta lista se genera desde ese archivo: si agregas una restriccion alla, agregala
  // aca tambien o la Cloud Function la va a rechazar con "Comando no reconocido".
  "BLOCK_PRIVATE_DNS", "UNBLOCK_PRIVATE_DNS",
  "BLOCK_SMS", "UNBLOCK_SMS",
  "BLOCK_OUTGOING_CALLS", "UNBLOCK_OUTGOING_CALLS",
  "BLOCK_CONFIG_LOCATION", "UNBLOCK_CONFIG_LOCATION",
  "BLOCK_SHARE_LOCATION", "UNBLOCK_SHARE_LOCATION",
  "BLOCK_AUTOFILL", "UNBLOCK_AUTOFILL",
  "BLOCK_CONTENT_CAPTURE", "UNBLOCK_CONTENT_CAPTURE",
  "BLOCK_PRINTING", "UNBLOCK_PRINTING",
  "BLOCK_USB_FILE_TRANSFER", "UNBLOCK_USB_FILE_TRANSFER",
  "BLOCK_DATA_ROAMING", "UNBLOCK_DATA_ROAMING",
  "BLOCK_AIRPLANE_MODE", "UNBLOCK_AIRPLANE_MODE",
  "BLOCK_AMBIENT_DISPLAY", "UNBLOCK_AMBIENT_DISPLAY",
  "BLOCK_SYSTEM_ERROR_DIALOGS", "UNBLOCK_SYSTEM_ERROR_DIALOGS",
  "BLOCK_SET_WALLPAPER", "UNBLOCK_SET_WALLPAPER",
  "BLOCK_SET_USER_ICON", "UNBLOCK_SET_USER_ICON",
  "BLOCK_CONFIG_CREDENTIALS", "UNBLOCK_CONFIG_CREDENTIALS",
  "BLOCK_CONFIG_CELL_BROADCASTS", "UNBLOCK_CONFIG_CELL_BROADCASTS",
  "BLOCK_OUTGOING_BEAM", "UNBLOCK_OUTGOING_BEAM",
  "BLOCK_UNMUTE_MICROPHONE", "UNBLOCK_UNMUTE_MICROPHONE",
  "BLOCK_REMOVE_MANAGED_PROFILE", "UNBLOCK_REMOVE_MANAGED_PROFILE",
  // ── DIAGNÓSTICO REMOTO (6/11/2026, B.110) — ver app/diag/ ──
  // COLLECT_DIAGNOSTICS: el celular junta su logcat, los últimos cierres del proceso, el
  //   estado del sistema y el de las apps de `packages` (opcional) y lo sube a
  //   deviceDiag/<id>/snapshots. Es de SOLO LECTURA: no cambia ninguna política, por eso
  //   NO exige el PIN del celular (ver la excepción de PIN más abajo): quien lo necesita es un
  //   usuario con un problema, y con el PIN sin publicar (B.104) no se podría diagnosticar
  //   justo el caso que más lo requiere.
  // SET_DIAGNOSTICS_UPLOAD: interruptor de la subida del registro (`enabled`). Sí exige PIN.
  "COLLECT_DIAGNOSTICS", "SET_DIAGNOSTICS_UPLOAD",
]);

function canonicalCommandPayload(payload) {
  return Object.keys(payload)
    .filter((key) => key !== "signature")
    .sort()
    .map((key) => `${key}=${payload[key] ?? ""}`)
    .join("\n");
}

// ─────────────────────────────────────────────────────────────────────────────
// BUZÓN DE COMANDOS (22/9/2026) — ver app/util/CommandMailbox.kt
//
// Además del FCM, cada comando firmado queda en devices/<id>/pendingCommands/<commandId>.
// El celular lo drena solo (Worker de 15 min, arranque, onDeletedMessages), así que un
// comando que FCM perdió —equipo apagado más de un día, token viejo, Play Services
// restringido, más de 100 mensajes en cola— se aplica igual cuando el equipo vuelve.
//
// "Familia": dos comandos que pisan la MISMA política (BLOCK_WIFI / UNBLOCK_WIFI,
// HIDE_APP x / UNHIDE_APP x) comparten familia, y al encolar el nuevo se borra el
// viejo. Así el buzón nunca crece más que la cantidad de políticas distintas, y un
// "bloquear y después desbloquear" con el equipo apagado termina desbloqueado.
// ─────────────────────────────────────────────────────────────────────────────
function commandFamily(command, packages) {
  const reglas = [
    [/^UNBLOCK_/, "BLOCK_"],
    [/^UNSUSPEND_/, "SUSPEND_"],
    [/^UNHIDE_/, "HIDE_"],
    [/^UNPROTECT_/, "PROTECT_"],
    [/^(ENABLE|DISABLE)_/, "TOGGLE_"],
    [/^RESUME_LOCKSUITE$/, "SUSPEND_LOCKSUITE"],
    [/^CANCEL_UPDATE_APP$/, "UPDATE_APP"],
    [/^SET_GOOGLE_ACCOUNT_MODE_.*$/, "SET_GOOGLE_ACCOUNT_MODE"],
    [/^SET_WHITELIST_(SIMULATION|ENFORCE)$/, "SET_WHITELIST_SIM"],
    [/^(SET_DOMAIN_RULE_.*|REMOVE_DOMAIN_RULE)$/, "DOMAIN_RULE"],
    [/^APPLY_(PRESET_PROFILE|MASTER_PROFILE|PROFILE)$/, "APPLY_ANY_PROFILE"],
    [/^(START|STOP)_TEMP_INSTALL_WINDOW$/, "TEMP_INSTALL_WINDOW"],
  ];
  let fam = String(command);
  for (const [re, rep] of reglas) {
    if (re.test(fam)) { fam = fam.replace(re, rep); break; }
  }
  const pk = packages ? String(packages) : "";
  // Las claves de RTDB no admiten . # $ [ ] /
  return (pk ? fam + "|" + pk : fam).replace(/[.#$\[\]\/]/g, "_");
}

// Comandos de ACCIÓN (no de estado): tarde no sirven, vencen en un día. El resto son
// estado deseado y se aplican aunque el equipo vuelva a las semanas.
const ACTION_COMMANDS = new Set([
  "LOCK_DEVICE", "UPDATE_APP", "CANCEL_UPDATE_APP", "UPDATE_LOCKSUITE",
  // Liberar/desinstalar es una ACCIÓN puntual: si llega tarde (equipo que estuvo una
  // semana apagado) ya no tiene sentido ejecutarla sola. Vence en un día como el resto.
  "UNINSTALL_LOCKSUITE",
  "SYNC_WHITELIST", "SYNC_APP_REQUESTS", "CLEAR_WHITELIST_AUDIT", "CLEAR_IAB_AUDIT",
  // "Releé la tienda": el equipo lee el catálogo VIGENTE, no el de cuando se mandó.
  "SYNC_PLAY_STORE",
  "START_TEMP_INSTALL_WINDOW", "STOP_TEMP_INSTALL_WINDOW",
  // Un diagnóstico pedido y no levantado en un día ya no describe el problema que se reportó.
  "COLLECT_DIAGNOSTICS",
]);
// Modos de la Tienda Play (app/mdm/PlayStoreCatalog.kt, MODES). Ver SET_PLAY_STORE_MODE.
const PLAY_STORE_MODES = new Set(["cerrada", "kosher", "trabajo", "lite"]);
const MAILBOX_TTL_ACTION_MS = 24 * 60 * 60 * 1000;
const MAILBOX_TTL_STATE_MS = 30 * 24 * 60 * 60 * 1000;

// Helper para verificar admin por UID (fuente de verdad alineada con database.rules.json)
async function checkAdminByUid(uid, decodedToken) {
  if (!uid) throw { status: 403, message: "Acceso denegado: UID inválido." };
  if (decodedToken && decodedToken.admin === true) return;
  const snap = await admin.database().ref(`authorizedAdminsUids/${uid}`).once("value");
  if (!snap.exists() || snap.val() !== true) {
    throw { status: 403, message: "Acceso denegado: administrador no autorizado." };
  }
}

const DOMAIN_RULE_COMMANDS = new Set([
  "SET_DOMAIN_RULE_BLOCK", "SET_DOMAIN_RULE_ALLOW",
  "SET_DOMAIN_RULE_FORCE_BLOCK", "SET_DOMAIN_RULE_FORCE_ALLOW", "REMOVE_DOMAIN_RULE",
]);

// Helper para leer campo con fallback a info.X
function getDeviceField(device, field, fallback = null) {
  if (device && device[field] !== undefined && device[field] !== null) return device[field];
  if (device && device.info && device.info[field] !== undefined && device.info[field] !== null) return device.info[field];
  return fallback;
}

// Detecta PINs triviales, con la MISMA regla que PinManager.isTrivialPin() en la app:
// todos los dígitos iguales, o una secuencia ascendente/descendente de dígitos
// consecutivos ("1234", "9876"). Existe porque la app rechaza estos PINs al
// configurarlos y al cambiarlos desde el Dashboard, pero la vía remota (comando
// CHANGE_PIN) se salteaba esa validación por completo: el celular recibe el hash ya
// calculado, así que no tiene con qué juzgar la fuerza del PIN. Sin esta comprobación
// del lado del servidor, un PIN fuerte se podía debilitar a "1234" desde el panel.
function isTrivialPin(pin) {
  if (!pin || pin.length === 0) return true;
  if ([...pin].every((c) => c === pin[0])) return true;

  let ascending = true;
  for (let i = 0; i < pin.length - 1; i++) {
    if (pin.charCodeAt(i + 1) - pin.charCodeAt(i) !== 1) { ascending = false; break; }
  }
  if (ascending) return true;

  let descending = true;
  for (let i = 0; i < pin.length - 1; i++) {
    if (pin.charCodeAt(i) - pin.charCodeAt(i + 1) !== 1) { descending = false; break; }
  }
  if (descending) return true;

  return false;
}

// Verificar PIN del dispositivo.
//
// 9/11/2026 (auditoría de seguridad) — qué cambió:
//  • El verificador (`deviceSecrets/<id>/{pinHash,pinSalt}`) se compara en tiempo constante.
//  • Cada intento se RESERVA antes de calcular (`pinThrottle/<id>/<uid>`): THROTTLE_FREE_ATTEMPTS
//    libres y después bloqueo creciente (ver pin_security.js). Antes se podían probar PINs sin freno.
//  • "Recordar este celular" (`trustedAdmins/<uid>`) lo escribe SOLO esta Function. Hasta hoy
//    también lo escribía el panel directo en la base, y las reglas dejaban a cualquier administrador
//    ponerse a sí mismo (o a otro uid) como confiable sin saber el PIN. database.rules.json ahora
//    rechaza esa escritura desde cualquier cliente.
async function verifyDevicePin(deviceId, deviceRef, deviceData, adminUid, devicePin, rememberDevice) {
  const trustedAdmins = getDeviceField(deviceData, "trustedAdmins", {});
  if (trustedAdmins && trustedAdmins[adminUid] === true) return; // ya confiable

  const secretsSnap = await admin.database().ref(`deviceSecrets/${deviceId}`).once("value");
  const secrets = secretsSnap.val() || {};
  // El verificador sale SOLO de `deviceSecrets`. Hasta el 9/11/2026 también se aceptaba un `pinHash` /
  // `pinSalt` sueltos en `devices/<id>` (herencia de versiones < 0.5, que CHANGE_PIN ya borraba): como
  // un administrador puede escribir cualquier cosa en `devices/<id>`, en un celular sin verificador en
  // `deviceSecrets` le alcanzaba con inventarse uno para pasar la puerta del PIN.
  const record = { pinHash: secrets.pinHash, pinSalt: secrets.pinSalt };

  if (!record.pinHash || !record.pinSalt) throw { status: 412, message: "DEVICE_PIN_NOT_ENROLLED" };
  if (!devicePin) throw { status: 412, message: "PIN_REQUIRED" };

  const throttleRef = admin.database().ref(`pinThrottle/${deviceId}/${adminUid}`);
  const turno = await pinSecurity.reservePinAttempt(throttleRef, Date.now());
  if (!turno.allowed) {
    const retryAfterSec = Math.max(1, Math.ceil(turno.retryAfterMs / 1000));
    throw {
      status: 429,
      message: "PIN_LOCKED: demasiados PIN incorrectos para este celular desde tu cuenta. " +
        `Probá de nuevo en ${Math.ceil(retryAfterSec / 60)} min.`,
      extra: { retryAfterSec },
    };
  }

  if (!pinSecurity.verifyPinRecord(record, devicePin)) {
    throw { status: 403, message: "PIN_INCORRECT", extra: { attemptsLeft: turno.attemptsLeft } };
  }
  // Acierto: se borra el contador. Un fallo al borrar no invalida el acierto (el próximo
  // acierto o el vencimiento de 24 h lo limpian).
  await throttleRef.remove().catch(() => {});

  if (rememberDevice) {
    await deviceRef.child(`trustedAdmins/${adminUid}`).set(true);
    await deviceRef.child(`info/trustedAdmins/${adminUid}`).set(true).catch(() => {});
  }
}

exports.sendCommandV8 = onRequest(FUNCTION_OPTIONS, async (req, res) => {
  applyAdminCors(req, res);

  // Responder preflight inmediatamente
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  if (req.method !== "POST") {
    res.status(405).json({ error: "Método no permitido" });
    return;
  }

  try {
    // Verificar el ID token (y que no esté revocado) y que sea un administrador autorizado por UID
    // (fuente de verdad alineada con database.rules.json).
    const decoded = await verifyAdminToken(req);
    const adminUid = decoded.uid;
    const adminEmail = decoded.email;

    const {
      deviceId, command, packages, devicePin, rememberDevice, newPin,
      enabled, presetJson, profileId, level, graceMs, graceTarget, durationMs, route, storeMode
    } = req.body || {};

    if (!deviceId || typeof deviceId !== "string" || !DEVICE_ID_RE.test(deviceId)) {
      res.status(400).json({ error: "Falta deviceId (o no tiene el formato de un id de equipo)." });
      return;
    }
    if (!ALLOWED_COMMANDS.has(command)) {
      res.status(400).json({ error: `Comando no reconocido: ${command}` });
      return;
    }

    const deviceRef = admin.database().ref(`devices/${deviceId}`);
    const deviceSnap = await deviceRef.once("value");
    const deviceData = deviceSnap.val();
    if (!deviceData) {
      res.status(404).json({ error: "Dispositivo no registrado." });
      return;
    }

    // UPDATE_APP y UPDATE_LOCKSUITE quedan afuera de la exigencia de PIN por
    // dispositivo: son acciones de mantenimiento (actualizar una app puntual,
    // o LockSuite mismo) sin el mismo nivel de riesgo que bloquear el equipo,
    // cambiar el PIN o aplicar restricciones - pedir el PIN ahi era friccion
    // pura para un caso de uso muy frecuente.
    // CANCEL_UPDATE_APP entra en la misma excepcion que UPDATE_APP: es su
    // contraparte, y si para cancelar hiciera falta el PIN, un equipo con una
    // pantalla de actualizacion trabada no se podria destrabar desde el panel
    // — justo el caso en que mas urge poder hacerlo.
    // COLLECT_DIAGNOSTICS (6/11/2026, B.110) entra en la misma excepción: es de SOLO LECTURA
    // (el celular sube su registro y su estado; no cambia ninguna política) y se pide justo
    // cuando un usuario tiene un problema —a veces con el PIN todavía sin publicar (B.104)—.
    // SET_DIAGNOSTICS_UPLOAD (el interruptor) NO está acá: sí exige PIN.
    if (command !== "UPDATE_LOCKSUITE" && command !== "UPDATE_APP" &&
        command !== "CANCEL_UPDATE_APP" && command !== "COLLECT_DIAGNOSTICS") {
      await verifyDevicePin(deviceId, deviceRef, deviceData, adminUid, devicePin, rememberDevice);
    }

    if (command === "VERIFY_PIN") {
      res.status(200).json({ success: true, verified: true });
      return;
    }

    const token = getDeviceField(deviceData, "fcmToken");
    const commandId = crypto.randomUUID();
    const commandSecret = (await admin.database().ref(`deviceSecrets/${deviceId}/commandSecret`).once("value")).val();
    const hasCommandSecret = typeof commandSecret === "string" && commandSecret.length >= 32;
    if (!token && !hasCommandSecret) {
      res.status(404).json({ error: "El dispositivo no tiene FCM token ni buzón de comandos activo." });
      return;
    }
    // Migración controlada: un cliente anterior a 0.5.0 no puede verificar la
    // firma por dispositivo. Solo se le permite recibir la actualización; no
    // puede ejecutar ninguna política hasta volver a sincronizar ya actualizado.
    if (!hasCommandSecret && command !== "UPDATE_LOCKSUITE") {
      res.status(412).json({ error: "El dispositivo no tiene credencial de comandos. Actualiza y sincroniza LockSuite antes de administrarlo." });
      return;
    }
    const payload = { command, commandId };
    const pkgRegex = DOMAIN_RULE_COMMANDS.has(command) ? /^[a-zA-Z0-9_.-]+$/ : /^[a-zA-Z0-9_.]+$/;
    let pendingPinChange = null;
    async function commitPendingPinChange() {
      if (!pendingPinChange) return;
      const { pinHash, pinSalt } = pendingPinChange;
      pendingPinChange = null;
      // No reemplazar el nodo completo: contiene la credencial de firma de
      // comandos del dispositivo, que debe sobrevivir a un cambio de PIN.
      await admin.database().ref(`deviceSecrets/${deviceId}`).update({ pinHash, pinSalt });
      // Un PIN nuevo invalida los intentos fallidos del anterior.
      await admin.database().ref(`pinThrottle/${deviceId}`).remove().catch(() => {});
      await deviceRef.child("hasPinConfigured").set(true);
      await deviceRef.child("info/hasPinConfigured").set(true).catch(() => {});
      await deviceRef.child("pinHash").remove().catch(() => {});
      await deviceRef.child("pinSalt").remove().catch(() => {});
      await deviceRef.child("trustedAdmins").remove().catch(() => {});
      await deviceRef.child("info/trustedAdmins").remove().catch(() => {});
    }

    if (command === "CHANGE_PIN") {
      if (!newPin || !/^\d{4,16}$/.test(newPin)) {
        res.status(400).json({ error: "PIN inválido." });
        return;
      }
      if (isTrivialPin(newPin)) {
        res.status(400).json({
          error: "PIN demasiado débil: no uses dígitos repetidos ni secuencias como 1234 o 9876.",
        });
        return;
      }
      const { pinHash, pinSalt } = pinSecurity.newPinRecord(newPin);
      payload.pinHash = pinHash;
      payload.pinSalt = pinSalt;
      // Se difiere la escritura en deviceSecrets hasta confirmar que el comando quedó
      // encolado en el buzón o fue aceptado por FCM; así un fallo de entrega no deja
      // el PIN de la nube desincronizado del celular.
      pendingPinChange = { pinHash, pinSalt };
    } else if (command === "UPDATE_ALLOWLIST") {
      if (!Array.isArray(packages) && typeof packages !== "string") {
        res.status(400).json({ error: "Falta la lista de paquetes." });
        return;
      }
      const pkgList = Array.isArray(packages)
        ? packages
        : (packages.trim().length > 0 ? packages.split(",") : []);
      const clean = pkgList.map(p => String(p).trim()).filter(p => /^[a-zA-Z0-9_.]+$/.test(p));
      payload.packages = clean.join(",");
      await deviceRef.child("allowedPackages").set(clean.length > 0 ? clean : null);
      await deviceRef.child("info/allowedPackages").set(clean.length > 0 ? clean : null).catch(() => {});
    } else if (command === "SET_HIDE_SUSPENDED_APPS" || command === "SET_DIAGNOSTICS_UPLOAD") {
      if (typeof enabled !== "boolean") {
        res.status(400).json({ error: "Falta el valor enabled para la política." });
        return;
      }
      payload.enabled = String(enabled);
    } else if (command === "SET_PLAY_STORE_MODE") {
      // La lista esta escrita a mano aca por la misma razon que LEVELS: la Function no
      // puede leer el Kotlin, y un modo que el celular no reconozca lo CIERRA (falla
      // cerrado: PlayStoreCatalog.normalizeMode). Mejor rechazarlo aca con la causa que
      // dejar un celular con la tienda cerrada sin explicacion. Si se agrega un modo en
      // PlayStoreCatalog.kt hay que agregarlo aca tambien (tools/check_profile_sync.py
      // lo verifica).
      if (typeof storeMode !== "string" || !PLAY_STORE_MODES.has(storeMode)) {
        res.status(400).json({ error: `Modo de la Tienda Play desconocido: ${storeMode}` });
        return;
      }
      payload.storeMode = storeMode;
    } else if (command === "APPLY_PRESET_PROFILE") {
      if (typeof presetJson !== "string" || presetJson.length === 0) {
        res.status(400).json({ error: "Falta el perfil a aplicar." });
        return;
      }
      // El payload data de FCM tiene un límite estricto. Evitamos simular un
      // envío que FCM rechazaría; los perfiles grandes requieren la ruta de
      // sincronización dedicada que se revisará en la siguiente etapa.
      if (Buffer.byteLength(presetJson, "utf8") > 3000) {
        res.status(413).json({ error: "El perfil es demasiado grande para enviarlo de forma remota." });
        return;
      }
      payload.presetJson = presetJson;
    } else if (command === "APPLY_MASTER_PROFILE") {
      // Los tres niveles que trae el APK. La lista esta escrita a mano aca a
      // proposito: la Function no puede leer el Kotlin, y una constante suelta que
      // el celular no reconozca se descarta alla sin decir nada. Si se agrega un
      // nivel en EnrollmentProfiles.kt hay que agregarlo aca tambien, y
      // tools/check_profile_sync.py lo verifica.
      const LEVELS = new Set(["kosher_estricto", "trabajo", "base_minima", "gracia", "solo_llamadas", "lite"]);
      if (typeof level !== "string" || !LEVELS.has(level)) {
        res.status(400).json({ error: `Nivel de perfil desconocido: ${level}` });
        return;
      }
      payload.level = level;

      // El perfil de gracia (Nivel 4) EXIGE una duracion. Sin ella el celular lo
      // rechaza, y esta bien que asi sea: un perfil de gracia sin vencimiento es un
      // equipo que queda mas abierto de lo normal PARA SIEMPRE — literal el riesgo
      // que B.11 dejo anotado para la suspension. Se valida aca tambien para que el
      // panel reciba la causa en vez de un ack de rechazo diez segundos despues.
      if (level === "gracia") {
        const ms = Number(graceMs);
        const UN_MINUTO = 60 * 1000;
        const NOVENTA_DIAS = 90 * 24 * 60 * 60 * 1000;
        if (!Number.isFinite(ms) || ms < UN_MINUTO || ms > NOVENTA_DIAS) {
          res.status(400).json({
            error: "El periodo de gracia necesita una duracion entre 1 minuto y 90 dias.",
          });
          return;
        }
        // El perfil de cierre no puede ser la gracia misma: se reabriria sola para
        // siempre. Es el mismo tipo de rulo que GracePeriodManager evita al limpiar
        // el estado ANTES de aplicar el perfil destino.
        const CIERRES = new Set(["kosher_estricto", "trabajo", "base_minima", "solo_llamadas", "lite"]);
        const destino = typeof graceTarget === "string" ? graceTarget : "kosher_estricto";
        if (!CIERRES.has(destino)) {
          res.status(400).json({ error: `Perfil de cierre invalido: ${destino}` });
          return;
        }
        payload.graceMs = String(Math.round(ms));
        payload.graceTarget = destino;
      }
    } else if (command === "START_TEMP_INSTALL_WINDOW") {
      const ms = Number(durationMs);
      const UN_MINUTO = 60 * 1000;
      const TREINTA_DIAS = 30 * 24 * 60 * 60 * 1000;
      if (!Number.isFinite(ms) || ms < UN_MINUTO || ms > TREINTA_DIAS) {
        res.status(400).json({
          error: "La duracion para permitir instalar apps debe estar entre 1 minuto y 30 dias.",
        });
        return;
      }
      payload.durationMs = String(Math.round(ms));
    } else if (command === "APPLY_PROFILE") {
      // Solo el id: el perfil se lee de globalSettings/profiles/<id> desde el
      // celular. El formato se valida para no armar una ruta de Firebase invalida
      // (las claves no admiten . # $ [ ] ni /).
      if (typeof profileId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(profileId)) {
        res.status(400).json({ error: "Identificador de perfil invalido." });
        return;
      }
      const profileSnap = await admin.database()
        .ref(`globalSettings/profiles/${profileId}/json`).once("value");
      const profileJson = profileSnap.val();
      if (typeof profileJson !== "string" || profileJson.length === 0) {
        // Se verifica ACA y no en el celular porque el modo de falla del otro lado
        // es mudo: el equipo no encuentra el nodo, no aplica nada, y el panel se
        // queda esperando un ack que no dice por que. Mejor un 404 con la causa.
        res.status(404).json({ error: `El perfil '${profileId}' no existe o esta vacio.` });
        return;
      }
      payload.profileId = profileId;
    } else if (typeof packages === "string" && packages.trim().length > 0) {
      const clean = packages.split(",").map(p => p.trim()).filter(p => pkgRegex.test(p));
      if (clean.length > 0) payload.packages = clean.join(",");
    } else if (Array.isArray(packages) && packages.length > 0) {
      const clean = packages.map(p => String(p).trim()).filter(p => pkgRegex.test(p));
      if (clean.length > 0) payload.packages = clean.join(",");
    }

    // 26/10/2026 (B.97) — UPDATE_APP puede elegir el camino: "auto" (lo de siempre),
    // "play" (solo Google Play) o "hosted" (solo la copia de la Tienda de LockSuite, en
    // silencio). Va DENTRO del payload firmado. "auto" no se manda: es lo que hace el
    // celular sin el campo, y así un comando de siempre queda idéntico al de antes. Un
    // celular anterior a B.97 ignora el campo (la firma lo incluye igual de los dos lados,
    // ver canonicalCommandPayload) y actualiza por Google Play como siempre.
    if (command === "UPDATE_APP" && route !== undefined && route !== null && route !== "") {
      const UPDATE_ROUTES = new Set(["auto", "play", "hosted"]);
      if (typeof route !== "string" || !UPDATE_ROUTES.has(route)) {
        res.status(400).json({ error: `Camino de actualización desconocido: ${route}` });
        return;
      }
      if (route !== "auto") payload.route = route;
    }

    const ahoraFirma = Date.now();
    const expiresAtMs = ahoraFirma + (ACTION_COMMANDS.has(command) ? MAILBOX_TTL_ACTION_MS : MAILBOX_TTL_STATE_MS);
    if (hasCommandSecret) {
      payload.timestamp = String(ahoraFirma);
      payload.expiresAt = String(expiresAtMs);
      payload.signature = crypto
        .createHmac("sha256", commandSecret)
        .update(canonicalCommandPayload(payload), "utf8")
        .digest("base64");
    }

    // 22/9/2026 — el ack "sent" se escribe ANTES de mandar el FCM. Antes iba después, con
    // set(): si el celular contestaba muy rápido, su "applied" llegaba primero y la
    // Function lo PISABA con "sent", y el panel esperaba una confirmación que ya había
    // llegado (timeout falso). Ahora el "sent" ya está cuando llega el "applied".
    const ackData = { status: "sent", command, timestamp: admin.database.ServerValue.TIMESTAMP };
    await deviceRef.child(`commandAcks/${commandId}`).set(ackData);

    // Buzón: la copia firmada del comando, por si el FCM no llega (ver commandFamily()).
    // Se encola ANTES del FCM por el mismo motivo que el ack: si el celular lo aplica
    // al instante, su borrado de la copia tiene que encontrarla ya escrita. Sin
    // commandSecret no hay firma, y el celular descartaría la copia: no se encola.
    let queued = false;
    if (hasCommandSecret) {
      try {
        const family = commandFamily(command, payload.packages);
        const pendRef = deviceRef.child("pendingCommands");
        const pendSnap = await pendRef.once("value");
        const updates = {};
        // "Cancelar actualización" va SIN paquete (cancela la que esté en curso, sea
        // cual sea), así que su familia es "UPDATE_APP" y no la "UPDATE_APP|<paquete>"
        // del pedido que cancela. Sin esto, un equipo que vuelve después de ambos
        // arrancaba la actualización y la cancelaba en el mismo segundo.
        const cancelaActualizaciones = command === "CANCEL_UPDATE_APP" && !payload.packages;
        const desuspendeTodas = command === "UNSUSPEND_ALL_APPS";
        pendSnap.forEach((c) => {
          const v = c.val() || {};
          if (v.family === family) updates[c.key] = null;   // el nuevo reemplaza al viejo
          else if (cancelaActualizaciones && typeof v.family === "string" &&
                   v.family.startsWith("UPDATE_APP|")) updates[c.key] = null;
          else if (desuspendeTodas && typeof v.family === "string" &&
                   v.family.startsWith("SUSPEND_APP|")) updates[c.key] = null;
        });
        updates[commandId] = {
          data: payload,
          family,
          queuedAt: ahoraFirma,
          expiresAt: expiresAtMs,
        };
        await pendRef.update(updates);
        queued = true;
      } catch (e) {
        console.warn("No se pudo encolar el comando en el buzón:", e && e.message);
      }
    }

    // 26/10/2026 (B.95) — `delivered` y `channel` en la respuesta.
    //
    // `queued` dice que quedó la copia en el buzón, y eso pasa con CASI TODO comando
    // (siempre que el equipo tenga secreto de firma), haya salido el FCM o no. B.94 hizo
    // que el panel leyera `queued` como "no salió, quedó en cola": desde entonces el
    // panel decía "encolado, hasta 15 min" y devolvía el interruptor a como estaba en
    // TODOS los celulares, aunque el FCM hubiera llegado y el equipo lo aplicara en dos
    // segundos. `delivered` contesta la pregunta que el panel necesita: ¿Google aceptó el
    // FCM? Si sí, hay que esperar el ack; si no, el comando va solo por el buzón.
    if (!token) {
      if (queued) {
        await commitPendingPinChange();
        await deviceRef.child(`commandAcks/${commandId}`).update({ status: "queued", reason: "El equipo aún no registró token FCM; quedó en el buzón del equipo" }).catch(() => {});
        await admin.database().ref(`commandLog/${deviceId}`).push({
          command, commandId, packages: payload.packages || null,
          sentBy: adminUid, sentAt: admin.database.ServerValue.TIMESTAMP, fcmError: "NO_FCM_TOKEN",
        }).catch(() => {});
        res.json({ success: true, commandId, queued: true, delivered: false, channel: "buzon", fcmError: "NO_FCM_TOKEN" });
        return;
      }
      res.status(404).json({ error: "El dispositivo no tiene FCM token." });
      return;
    }

    try {
      await admin.messaging().send({
        token,
        data: payload,
        android: { priority: "high" },
      });
    } catch (e) {
      // Token viejo o inválido: con el buzón el comando igual llega (hasta 15 min). Se
      // informa al panel en vez de tirar un 500 que parece que no pasó nada.
      const code = (e && (e.code || e.errorInfo && e.errorInfo.code)) || "";
      if (queued) {
        await commitPendingPinChange();
        await deviceRef.child(`commandAcks/${commandId}`).update({ status: "queued", reason: "FCM falló (" + code + "): quedó en el buzón del equipo" }).catch(() => {});
        await admin.database().ref(`commandLog/${deviceId}`).push({
          command, commandId, packages: payload.packages || null,
          sentBy: adminUid, sentAt: admin.database.ServerValue.TIMESTAMP, fcmError: String(code),
        }).catch(() => {});
        res.json({ success: true, commandId, queued: true, delivered: false, channel: "buzon", fcmError: String(code) });
        return;
      }
      throw e;
    }

    await commitPendingPinChange();
    await admin.database().ref(`commandLog/${deviceId}`).push({
      command, commandId,
      packages: payload.packages || null,
      sentBy: adminUid, sentAt: admin.database.ServerValue.TIMESTAMP,
    });

    // `queued` sigue viajando (la copia del buzón existe), pero el panel NO lo tiene que
    // leer como "no llegó": `delivered: true` dice que el FCM salió y que hay que
    // esperar el ack del celular. Ver el comentario de arriba.
    res.json({ success: true, commandId, queued, delivered: true, channel: "fcm" });
  } catch (e) {
    sendError(res, e, "sendCommandV8");
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// TIENDA PLAY (29/10/2026, B.101) — "Verificar en Play".
//
// El panel manda hasta 40 paquetes y recibe, por cada uno, si Google Play lo tiene
// (found / missing / unknown), con su nombre e ícono públicos. Solo para administradores
// autorizados, igual que sendCommandV8. No escribe nada en la base: el panel decide qué
// hace con la respuesta. Ver play_lookup.js para qué significa cada estado — "missing" NO
// es una prueba de que la app no exista, y por eso el panel no la borra sola.
// ─────────────────────────────────────────────────────────────────────────────
exports.checkPlayApps = onRequest({ ...FUNCTION_OPTIONS, timeoutSeconds: 60 }, async (req, res) => {
  applyAdminCors(req, res);
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }
  if (req.method !== "POST") {
    res.status(405).json({ error: "Método no permitido" });
    return;
  }
  try {
    await verifyAdminToken(req);

    const { packages } = req.body || {};
    if (!Array.isArray(packages) || packages.length === 0) {
      res.status(400).json({ error: "Falta la lista de paquetes." });
      return;
    }
    if (typeof fetch !== "function") {
      res.status(501).json({ error: "Este entorno no tiene fetch (hace falta Node 18 o más)." });
      return;
    }
    const results = await playLookup.lookupMany(packages, fetch);
    res.json({ success: true, results });
  } catch (e) {
    sendError(res, e, "checkPlayApps");
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// TIENDA CLÁSICA DE APKs — Subida directa desde PC a Cloud Storage (sin límite
// de 32 MB en Cloud Functions; URL pública + borrado autenticado de objetos).
// ─────────────────────────────────────────────────────────────────────────────
const STORE_APKS_BUCKET = "locksuite-nueva-store-apks";
const MAX_STORE_APK_BYTES = 512 * 1024 * 1024;
const CLASSIC_EXT_RE = /\.(apk|apks|xapk|apkm)$/i;
const CLASSIC_PKG_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
const CLASSIC_NEVER_OFFERED = new Set([
  "com.ejemplo.locksuite",
  "com.android.vending",
  "com.google.android.gms",
  "com.google.android.gsf",
  "com.android.settings",
  "com.android.packageinstaller",
  "com.google.android.packageinstaller",
  "com.google.android.permissioncontroller",
  "com.android.providers.downloads",
  "com.google.android.apps.work.clouddpc",
]);
const CLASSIC_UPLOAD_ORIGINS = new Set([
  "https://locksuite-nueva.web.app",
  "https://locksuite-nueva.firebaseapp.com",
  "http://localhost:5000",
  "http://127.0.0.1:5000",
]);

exports.classicStoreApk = onRequest({ ...FUNCTION_OPTIONS, timeoutSeconds: 60 }, async (req, res) => {
  applyAdminCors(req, res);
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }
  if (req.method !== "POST") {
    res.status(405).json({ error: "Método no permitido" });
    return;
  }
  try {
    await verifyAdminToken(req);

    const { action, packageName, fileName, sizeBytes, objectPath } = req.body || {};
    if (action === "initUpload") {
      const pkg = typeof packageName === "string" ? packageName.trim() : "";
      if (!pkg || pkg.length > 200 || !CLASSIC_PKG_RE.test(pkg)) {
        res.status(400).json({ error: "Nombre de paquete inválido." });
        return;
      }
      if (CLASSIC_NEVER_OFFERED.has(pkg)) {
        res.status(400).json({ error: "No se puede subir ese paquete del sistema o de LockSuite." });
        return;
      }
      const rawName = typeof fileName === "string" ? fileName.trim() : "";
      const safeName = rawName.replace(/[^a-zA-Z0-9._-]/g, "_").replace(/^_+|_+$/g, "");
      if (!safeName || safeName.length > 140 || !CLASSIC_EXT_RE.test(safeName)) {
        res.status(400).json({ error: "El archivo debe tener extensión .apk, .apks, .xapk o .apkm." });
        return;
      }
      const size = Number(sizeBytes);
      if (!Number.isFinite(size) || size <= 0 || size > MAX_STORE_APK_BYTES) {
        res.status(400).json({ error: "Tamaño de paquete inválido (máximo 512 MB)." });
        return;
      }

      const contentType = /\.apk$/i.test(safeName)
        ? "application/vnd.android.package-archive"
        : "application/octet-stream";
      const targetPath = `apks/${pkg.replace(/\./g, "_")}/${Date.now()}_${safeName}`;
      const reqOrigin = typeof req.headers.origin === "string" ? req.headers.origin.trim() : "";
      const origin = CLASSIC_UPLOAD_ORIGINS.has(reqOrigin) ? reqOrigin : "https://locksuite-nueva.web.app";

      const [uploadUrl] = await admin.storage().bucket(STORE_APKS_BUCKET).file(targetPath).createResumableUpload({
        origin,
        metadata: {
          contentType,
          cacheControl: "public, max-age=3600",
        },
      });

      res.json({
        success: true,
        uploadUrl,
        objectPath: targetPath,
        downloadUrl: `https://storage.googleapis.com/${STORE_APKS_BUCKET}/${targetPath}`,
        contentType,
      });
      return;
    }

    if (action === "deleteObject") {
      const obj = typeof objectPath === "string" ? objectPath.trim() : "";
      if (!obj || obj.length > 400 || !obj.startsWith("apks/") || !CLASSIC_EXT_RE.test(obj) ||
          obj.includes("..") || obj.startsWith("/") || /[\u0000-\u001f\u007f\\]/.test(obj)) {
        res.status(400).json({ error: "Ruta de objeto inválida." });
        return;
      }
      await admin.storage().bucket(STORE_APKS_BUCKET).file(obj).delete({ ignoreNotFound: true });
      res.json({ success: true });
      return;
    }

    res.status(400).json({ error: "Acción no reconocida." });
  } catch (e) {
    sendError(res, e, "classicStoreApk");
  }
});

async function fetchWithTimeout(url, timeoutMs = 10000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function readShortParam(req, name) {
  const q = req && req.query ? req.query[name] : undefined;
  const b = req && req.body ? req.body[name] : undefined;
  const val = q !== undefined ? q : b;
  if (typeof val !== "string") return null;
  const trimmed = val.trim();
  if (!trimmed || trimmed.length > 64) return null;
  return trimmed;
}

// API de Colectivos CABA (Cuando SUBO Proxy)
exports.colectivosApi = onRequest({ region: "us-central1", cors: true, invoker: "public" }, async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  try {
    const action = readShortParam(req, "action");
    if (!action) {
      res.status(400).json({ error: "Falta el parámetro action (buscarLinea, obtenerParadas, obtenerArribos)." });
      return;
    }

    if (action === "buscarLinea") {
      const query = readShortParam(req, "query");
      if (!query) {
        res.status(400).json({ error: "Falta o es inválido el parámetro query." });
        return;
      }
      const url = `https://cuandosubo.sube.gob.ar/onebusaway-webapp/where/iphone/routes.action?query=${encodeURIComponent(query)}`;
      const response = await fetchWithTimeout(url, 10000);
      if (!response.ok) throw new Error("Error consultando el servidor de lineas.");
      const html = await response.text();

      const routes = [];
      const liRegex = /<li><a href="[^"]*stops-for-route\.action[^"]*id=([^"&;\s]+)[^"]*"><span[^>]*>([^<]+)<\/span><span[^>]*>([^<]+)<\/span><\/a><\/li>/gi;
      let match;
      while ((match = liRegex.exec(html)) !== null) {
        routes.push({
          id: match[1],
          shortName: match[2].replace("-", "").trim(),
          longName: match[3].trim()
        });
      }
      res.status(200).json({ routes });

    } else if (action === "obtenerParadas") {
      const routeId = readShortParam(req, "routeId");
      if (!routeId) {
        res.status(400).json({ error: "Falta o es inválido el parámetro routeId." });
        return;
      }
      const url = `https://cuandosubo.sube.gob.ar/onebusaway-webapp/where/iphone/stops-for-route.action?id=${encodeURIComponent(routeId)}`;
      const response = await fetchWithTimeout(url, 10000);
      if (!response.ok) throw new Error("Error consultando el servidor de paradas.");
      const html = await response.text();

      const stops = [];
      const liRegex = /<li><a href="[^"]*stop\.action[^"]*id=([^"&;\s]+)[^"]*">([^<]+)<\/a><\/li>/gi;
      let match;
      while ((match = liRegex.exec(html)) !== null) {
        const name = match[2].trim();
        if (name.includes("Search by line") || name.includes("Nearby stops")) continue;
        stops.push({
          id: match[1],
          name: name
        });
      }
      res.status(200).json({ stops });

    } else if (action === "obtenerArribos") {
      const stopId = readShortParam(req, "stopId");
      if (!stopId) {
        res.status(400).json({ error: "Falta o es inválido el parámetro stopId." });
        return;
      }
      const url = `https://cuandosubo.sube.gob.ar/onebusaway-webapp/where/iphone/stop.action?id=${encodeURIComponent(stopId)}`;
      const response = await fetchWithTimeout(url, 10000);
      if (!response.ok) throw new Error("Error consultando el servidor de arribos.");
      const html = await response.text();

      const arrivals = [];
      const rowRegex = /<tr class="arrivalsRow">([\s\S]*?)<\/tr>/g;
      let match;
      while ((match = rowRegex.exec(html)) !== null) {
        const rowContent = match[1];

        const routeMatch = rowContent.match(/class="arrivalsRouteEntry"[^>]*><a[^>]*>([^<]+)<\/a>/i);
        const route = routeMatch ? routeMatch[1].trim() : "";

        const destMatch = rowContent.match(/class="arrivalsDestinationEntry"[^>]*><a[^>]*>([^<]+)<\/a>/i);
        const destination = destMatch ? destMatch[1].trim() : "";

        const timeEntryMatch = rowContent.match(/class="arrivalsTimeEntry"[^>]*>([^<]+)<\/span>/i);
        const arrivalTime = timeEntryMatch ? timeEntryMatch[1].trim() : "";

        const minutesMatch = rowContent.match(/class="arrivalsStatusEntry[\s\S]*?">([\s\S]*?)<\/td>/i);
        const minutes = minutesMatch ? minutesMatch[1].trim() : "";

        const isLive = !rowContent.includes("arrivalStatusNoInfo");

        arrivals.push({
          route,
          destination,
          arrivalTime,
          minutes: parseInt(minutes, 10) || 0,
          isLive
        });
      }
      res.status(200).json({ stopId, arrivals });
    } else {
      res.status(400).json({ error: "Acción no reconocida." });
    }
  } catch (err) {
    console.error("colectivosApi error:", err);
    res.status(500).json({ error: "Error interno del servidor." });
  }
});


// ─────────────────────────────────────────────────────────────────────────────────────────────
// 19/11/2026 (B.125, T3.1) — LATIDO INVERSO.
//
// Cada 15 min busca los equipos que llevan más de 25 min sin latir (y menos de 7 días) y les manda
// un FCM de prioridad alta `{ wake: "1" }`. Un FCM crea el proceso aunque esté muerto; el celular
// (LockSuiteFirebaseService) vuelve a levantar el Watchdog y la VPN y avisa que está vivo. Existe
// porque el Qin F21 de Iejiel estuvo 52 h y 4,5 h SIN proceso después de un ANR y nada lo despertó.
//
// NO es un comando: sin firma, sin PIN y sin ACK. La decisión de a quién se le manda (y los topes
// de 1 cada 15 min y 24 por día) vive en `wake_policy.js`, con prueba (`tools/test_wake_policy.js`).
// Deja el rastro en `devices/<id>/wake = { lastSentAt, count, day }` para el panel.
// ─────────────────────────────────────────────────────────────────────────────────────────────
exports.despertarEquiposSilenciosos = onSchedule(
  { region: "us-central1", schedule: "every 15 minutes", timeoutSeconds: 120 },
  async () => {
    const now = Date.now();
    const snap = await admin.database().ref("devices")
      .orderByChild("lastSeen")
      .startAt(now - wakePolicy.WAKE.MAX_AGE_MS)
      .endAt(now - wakePolicy.WAKE.SILENCE_MS)
      .once("value");
    const candidatos = wakePolicy.pickDevicesToWake(snap.val() || {}, now);
    let enviados = 0;
    let fallidos = 0;
    for (const c of candidatos) {
      try {
        await admin.messaging().send({
          token: c.token,
          data: { wake: "1", t: String(now) },
          android: { priority: "high", collapseKey: "wake", ttl: wakePolicy.WAKE.MIN_GAP_MS },
        });
        await admin.database().ref(`devices/${c.id}/wake`).set(c.wake);
        enviados++;
      } catch (e) {
        fallidos++;
        const code = (e && (e.code || (e.errorInfo && e.errorInfo.code))) || String(e);
        console.warn(`despertarEquiposSilenciosos: ${c.id} no se pudo despertar (${code})`);
      }
    }
    console.log(`despertarEquiposSilenciosos: candidatos=${candidatos.length} enviados=${enviados} fallidos=${fallidos}`);
  }
);

// ─────────────────────────────────────────────────────────────────────────────
// TIENDA AURORA — Descarga directa desde Google Play FDFE hacia GCS + RTDB
// para instalación y actualización 100% silenciosa en la flota (B.132).
// ─────────────────────────────────────────────────────────────────────────────
const AURORA_SECRET_PATH = "serverSecrets/aurora";
const AURORA_META_PATH = "auroraStore/meta";

async function testAuroraLogin(secret) {
  if (secret.mode === "dispenser") {
    const creds = await auroraGPlay.fetchFromDispenser({
      dispenserUrl: secret.dispenserUrl,
      profile: auroraDevices.DEVICE_PROFILES.arm64_v8a,
    });
    const client = new auroraGPlay.GPlayClient({
      profile: auroraDevices.DEVICE_PROFILES.arm64_v8a,
      email: creds.email,
      aasToken: creds.aasToken,
      authToken: creds.authToken,
    });
    await client.login();
    return client;
  }
  const client = new auroraGPlay.GPlayClient({
    profile: auroraDevices.DEVICE_PROFILES.arm64_v8a,
    email: secret.email,
    aasToken: secret.aasToken,
  });
  await client.login();
  return client;
}

exports.auroraStore = onRequest(
  { ...FUNCTION_OPTIONS, timeoutSeconds: 300, memory: "1GiB", maxInstances: 4 },
  async (req, res) => {
    applyAdminCors(req, res);
    if (req.method === "OPTIONS") {
      res.status(204).send("");
      return;
    }
    if (req.method !== "POST") {
      res.status(405).json({ error: "Método no permitido" });
      return;
    }
    try {
      await verifyAdminToken(req);

      const db = admin.database();
      const bucket = admin.storage().bucket(STORE_APKS_BUCKET);
      const body = req.body || {};
      const action = typeof body.action === "string" ? body.action.trim() : "";

      if (action === "getStatus") {
        const [secSnap, pkgSnap, metaSnap] = await Promise.all([
          db.ref(AURORA_SECRET_PATH).once("value"),
          db.ref("auroraStore/packages").once("value"),
          db.ref(AURORA_META_PATH).once("value"),
        ]);
        res.json({
          success: true,
          auth: auroraSync.maskSecretStatus(secSnap.val()),
          packages: pkgSnap.val() || {},
          meta: metaSnap.val() || {},
        });
        return;
      }

      if (action === "saveConfig") {
        const mode = typeof body.mode === "string" ? body.mode.trim().toLowerCase() : "";
        const now = Date.now();
        let newSecret;
        try {
          if (mode === "oauth") {
            const email = typeof body.email === "string" ? body.email.trim() : "";
            const oauthToken = typeof body.oauthToken === "string" ? body.oauthToken.trim() : "";
            const aasToken = await auroraGPlay.exchangeOAuthForAas({ email, oauthToken });
            newSecret = { mode: "aas", email, aasToken, updatedAt: now };
            await testAuroraLogin(newSecret);
          } else if (mode === "aas") {
            const email = typeof body.email === "string" ? body.email.trim() : "";
            const aasToken = typeof body.aasToken === "string" ? body.aasToken.trim() : "";
            if (!email || !email.includes("@") || !aasToken) {
              throw new Error("Falta email o token AAS.");
            }
            newSecret = { mode: "aas", email, aasToken, updatedAt: now };
            await testAuroraLogin(newSecret);
          } else if (mode === "dispenser") {
            const dispenserUrl = auroraGPlay.validateDispenserUrl(body.dispenserUrl);
            newSecret = { mode: "dispenser", dispenserUrl, updatedAt: now };
            await testAuroraLogin(newSecret);
          } else {
            throw { status: 400, message: "Modo de configuración inválido (oauth, aas o dispenser)." };
          }
        } catch (err) {
          if (err && err.status) throw err;
          throw { status: 400, message: err && err.message ? err.message : String(err) };
        }

        await db.ref(AURORA_SECRET_PATH).set(newSecret);
        res.json({ success: true, auth: auroraSync.maskSecretStatus(newSecret) });
        return;
      }

      if (action === "clearConfig") {
        await db.ref(AURORA_SECRET_PATH).remove();
        res.json({ success: true, auth: auroraSync.maskSecretStatus(null) });
        return;
      }

      if (action === "testAuth") {
        const secSnap = await db.ref(AURORA_SECRET_PATH).once("value");
        const secret = secSnap.val();
        if (!secret) {
          res.status(412).json({ error: "La conexión con Google Play aún no está configurada." });
          return;
        }
        try {
          const client = await testAuroraLogin(secret);
          const samplePkg = typeof body.packageName === "string" && CLASSIC_PKG_RE.test(body.packageName.trim())
            ? body.packageName.trim()
            : "com.whatsapp";
          const det = await client.details(samplePkg);
          res.json({
            success: true,
            sample: {
              packageName: det.packageName,
              title: det.title,
              versionCode: det.versionCode,
              versionName: det.versionName,
            },
          });
        } catch (err) {
          throw { status: 400, message: err && err.message ? err.message : String(err) };
        }
        return;
      }

      if (action === "syncPackage") {
        const pkg = typeof body.packageName === "string" ? body.packageName.trim() : "";
        if (!pkg || pkg.length > 200 || !CLASSIC_PKG_RE.test(pkg)) {
          res.status(400).json({ error: "Nombre de paquete inválido." });
          return;
        }
        if (CLASSIC_NEVER_OFFERED.has(pkg)) {
          res.status(400).json({ error: "Ese paquete del sistema o de LockSuite nunca se ofrece en la tienda." });
          return;
        }
        const secSnap = await db.ref(AURORA_SECRET_PATH).once("value");
        const secret = secSnap.val();
        if (!secret) {
          res.status(412).json({ error: "Configurá primero la cuenta o Dispenser de Google Play en Tienda Aurora." });
          return;
        }
        let sessions;
        try {
          sessions = await auroraSync.createSessionsForSecret(secret);
        } catch (err) {
          throw { status: 400, message: "Error al iniciar sesión en Google Play: " + (err && err.message ? err.message : String(err)) };
        }
        const result = await auroraSync.syncSinglePackage({
          packageName: pkg,
          sessions,
          db,
          bucket,
          force: !!body.force,
        });
        const savedSnap = await db.ref(`auroraStore/packages/${auroraSync.pkgKey(pkg)}`).once("value");
        res.json({ success: !!result.ok, result, packageData: savedSnap.val() || null });
        return;
      }

      if (action === "syncCatalog") {
        const secSnap = await db.ref(AURORA_SECRET_PATH).once("value");
        const secret = secSnap.val();
        if (!secret) {
          res.status(412).json({ error: "Configurá primero la cuenta o Dispenser de Google Play en Tienda Aurora." });
          return;
        }

        const force = !!body.force;
        const limitRaw = Number(body.limit);
        const limit = Number.isFinite(limitRaw) && limitRaw >= 1 ? Math.min(25, Math.floor(limitRaw)) : 5;
        const offsetRaw = Number(body.offset);
        const offset = Number.isFinite(offsetRaw) && offsetRaw >= 0 ? Math.floor(offsetRaw) : 0;

        let allPkgs;
        if (Array.isArray(body.packages) && body.packages.length > 0) {
          const uniq = new Set();
          for (const raw of body.packages) {
            const p = typeof raw === "string" ? raw.trim() : "";
            if (p && p.length <= 200 && CLASSIC_PKG_RE.test(p) && !CLASSIC_NEVER_OFFERED.has(p)) {
              uniq.add(p);
            }
          }
          allPkgs = Array.from(uniq);
        } else {
          allPkgs = await auroraSync.collectCatalogPackages(db, CLASSIC_NEVER_OFFERED);
        }

        const batch = allPkgs.slice(offset, offset + limit);
        if (batch.length === 0) {
          res.json({
            success: true,
            total: allPkgs.length,
            processed: 0,
            synced: 0,
            upToDate: 0,
            failed: 0,
            nextOffset: offset,
            remaining: 0,
            results: [],
          });
          return;
        }

        let sessions;
        try {
          sessions = await auroraSync.createSessionsForSecret(secret);
        } catch (err) {
          throw { status: 400, message: "Error al iniciar sesión en Google Play: " + (err && err.message ? err.message : String(err)) };
        }

        const results = [];
        let synced = 0;
        let upToDate = 0;
        let failed = 0;
        for (const pkg of batch) {
          let r;
          try {
            r = await auroraSync.syncSinglePackage({
              packageName: pkg,
              sessions,
              db,
              bucket,
              force,
            });
          } catch (pkgErr) {
            r = {
              packageName: pkg,
              ok: false,
              error: pkgErr && pkgErr.message ? pkgErr.message : String(pkgErr),
            };
          }
          results.push(r);
          if (!r.ok) failed++;
          else if (r.upToDate) upToDate++;
          else synced++;
        }

        const nextOffset = offset + batch.length;
        const remaining = Math.max(0, allPkgs.length - nextOffset);
        await db.ref(AURORA_META_PATH).update({
          lastManualSyncAt: Date.now(),
          lastCatalogCount: allPkgs.length,
        });

        res.json({
          success: true,
          total: allPkgs.length,
          processed: batch.length,
          synced,
          upToDate,
          failed,
          nextOffset,
          remaining,
          results,
        });
        return;
      }

      if (action === "deletePackage") {
        const pkg = typeof body.packageName === "string" ? body.packageName.trim() : "";
        if (!pkg || pkg.length > 200 || !CLASSIC_PKG_RE.test(pkg)) {
          res.status(400).json({ error: "Nombre de paquete inválido." });
          return;
        }
        await auroraSync.deletePackageArtifacts({ packageName: pkg, db, bucket });
        res.json({ success: true });
        return;
      }

      res.status(400).json({ error: "Acción no reconocida." });
    } catch (e) {
      sendError(res, e, "auroraStore");
    }
  }
);

exports.auroraSyncCatalogScheduled = onSchedule(
  { region: "us-central1", schedule: "every 12 hours", timeoutSeconds: 540, memory: "1GiB" },
  async () => {
    const db = admin.database();
    const bucket = admin.storage().bucket(STORE_APKS_BUCKET);
    const secSnap = await db.ref(AURORA_SECRET_PATH).once("value");
    const secret = secSnap.val();
    if (!secret) {
      console.log("auroraSyncCatalogScheduled: sin configuración en serverSecrets/aurora, omitido.");
      return;
    }

    const catalogPkgs = await auroraSync.collectCatalogPackages(db, CLASSIC_NEVER_OFFERED);
    const catalogSet = new Set(catalogPkgs);
    let sessions;
    try {
      sessions = await auroraSync.createSessionsForSecret(secret);
    } catch (err) {
      const msg = err && err.message ? err.message : String(err);
      console.error("auroraSyncCatalogScheduled login error:", msg);
      await db.ref(AURORA_META_PATH).update({ lastAutoSyncAt: Date.now(), lastAutoError: msg });
      return;
    }

    let synced = 0;
    let upToDate = 0;
    let failed = 0;
    for (const pkg of catalogPkgs) {
      try {
        const r = await auroraSync.syncSinglePackage({ packageName: pkg, sessions, db, bucket, force: false });
        if (!r.ok) failed++;
        else if (r.upToDate) upToDate++;
        else synced++;
      } catch (e) {
        failed++;
        console.warn(`auroraSyncCatalogScheduled: fallo en ${pkg}:`, e && e.message ? e.message : e);
      }
    }

    // Limpiar paquetes eliminados hace tiempo del catálogo global y de extras
    const existingSnap = await db.ref("auroraStore/packages").once("value");
    const existing = existingSnap.val() || {};
    let removed = 0;
    for (const [key, entry] of Object.entries(existing)) {
      const pkg = (entry && entry.packageName) || key.replace(/_/g, ".");
      if (!catalogSet.has(pkg)) {
        await auroraSync.deletePackageArtifacts({ packageName: pkg, db, bucket });
        removed++;
      }
    }

    await db.ref(AURORA_META_PATH).set({
      lastAutoSyncAt: Date.now(),
      lastAutoSummary: { total: catalogPkgs.length, synced, upToDate, failed, removed },
    });
    console.log(`auroraSyncCatalogScheduled: total=${catalogPkgs.length} synced=${synced} upToDate=${upToDate} failed=${failed} removed=${removed}`);
  }
);
