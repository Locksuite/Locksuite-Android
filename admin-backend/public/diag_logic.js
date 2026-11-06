/* ═══════════════════════════════════════════════════════════════════════════
 * diag_logic.js — la lógica PURA del «Registro de errores» de la ficha (6/11/2026, B.110).
 *
 * Es el lado del PANEL de `app/.../diag/` (el celular anota sus errores, los sube a
 * `deviceDiag/<id>` y acá se leen). Sin DOM y sin Firebase: se usa en el navegador
 * (`window.DiagLogic`, lo dibuja `diag_remoto.js`) y en Node (`module.exports`, lo prueba
 * `tools/test_diag_logic.js`).
 *
 * Qué hace:
 *   1. `parseEvents` / `listSnapshots`  — convierte lo que hay en Firebase en listas ordenadas,
 *      tolerando datos raros (un renglón roto no tira abajo la pantalla).
 *   2. `filterEvents` / `summarize`     — filtros de la pantalla y el resumen de arriba.
 *   3. `describeStatus`                 — la frase de estado («apagado», «sin versión», «error
 *      de subida»…) que le dice al dueño POR QUÉ no ve nada, en vez de una lista vacía muda.
 *   4. `buildReport`                    — el informe de texto que el dueño copia y me pega
 *      («te mando el error y diagnosticás con exactitud», pedido del 5/11/2026).
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • `LAYOUT` es el CONTRATO con el celular: nombres de nodos, claves de cada renglón y de
 *    cada diagnóstico. Si se cambia solo de un lado, la pantalla queda vacía SIN ningún
 *    error (el mismo tipo de bug mudo de B.28/B.40/B.57). `tools/check_diag_sync.py` compara
 *    estos valores con los de Kotlin y falla si no coinciden.
 *  • `diagUploadEnabled === false` es un dato, no «falta de dato»: se distingue de `null`.
 *    Usar `||` o `!x` acá haría que un celular con la subida apagada se vea como «sin
 *    versión» y el dueño buscaría el problema donde no está.
 *  • El orden es por HORA del evento (y recién después por número de secuencia): los cierres
 *    del sistema y las líneas del logcat se anotan DESPUÉS de ocurrir, así que el número de
 *    secuencia no sigue el orden en que pasaron las cosas.
 *  • El informe NO inventa nada: si no hay diagnóstico completo, dice que no lo hay; si se
 *    recortaron renglones, dice cuántos. Quien lee el informe (yo, o cualquier IA) tiene que
 *    poder fiarse de que lo que no aparece, no estaba.
 * ═══════════════════════════════════════════════════════════════════════════ */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.DiagLogic = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  /**
   * Contrato con el celular (app/.../diag/DiagUploader.kt y DiagUploadPolicy.kt).
   * ⚠️ `tools/check_diag_sync.py` lo compara con Kotlin: cambiar un valor acá exige cambiarlo allá.
   */
  const LAYOUT = Object.freeze({
    ROOT: "deviceDiag",
    EVENTS: "events",
    SNAPSHOTS: "snapshots",
    META: "meta",
    // claves de cada renglón de `events/e<secuencia>`
    EVENT_AT: "t", EVENT_LEVEL: "l", EVENT_TAG: "g", EVENT_MSG: "m", EVENT_COUNT: "n",
    // campos de cada `snapshots/s<n>`
    SNAP_FIELDS: Object.freeze(["t", "at", "pkgs", "ver", "diag", "system", "exits", "logcat", "packages"]),
    // campos que el celular publica en `devices/<id>`
    DEV_VERSION: "diagVersion", DEV_ENABLED: "diagUploadEnabled", DEV_ERROR: "diagUploadError",
    CMD_COLLECT: "COLLECT_DIAGNOSTICS", CMD_SWITCH: "SET_DIAGNOSTICS_UPLOAD",
    EVENTS_KEEP: 400, SNAPSHOTS_KEEP: 3, MAX_PACKAGES: 6
  });

  const RANK = { I: 0, W: 1, E: 2, F: 3 };
  const LEVEL_LABEL = { I: "info", W: "aviso", E: "error", F: "caída" };
  const PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
  const WINDOWS = { "1h": 3600000, "24h": 86400000, "3d": 259200000, "7d": 604800000, "todo": 0 };

  /** Qué significa cada etiqueta (`tag`) que escribe el celular. Va en el informe para quien lo lea. */
  const TAG_HELP = Object.freeze({
    app: "arranque del proceso de LockSuite",
    crash: "caída: excepción no atrapada que mató el proceso",
    err: "excepción que el código atrapó y dejó impresa (el catch con printStackTrace)",
    exit: "cierre del proceso que anotó Android (ANR, falta de memoria, señal, etc.)",
    logcat: "errores del logcat propio, juntados cada tanto",
    cmd: "comandos del panel rechazados o fallidos",
    vpn: "filtro DNS (VPN)",
    a11y: "servicio de accesibilidad",
    watchdog: "servicio de vigilancia",
    boot: "arranque del equipo y broadcasts del sistema",
    diag: "el propio registro de diagnóstico"
  });

  /* ── formato de horas ───────────────────────────────────────────────────── */

  function two(n) { return (n < 10 ? "0" : "") + n; }

  /** Partes de la fecha local. `offsetMin` (minutos al este de UTC) se inyecta para poder probarlo. */
  function localParts(ms, offsetMin) {
    const off = (typeof offsetMin === "number") ? offsetMin : -new Date(ms).getTimezoneOffset();
    const d = new Date(ms + off * 60000);
    return { y: d.getUTCFullYear(), mo: d.getUTCMonth() + 1, d: d.getUTCDate(),
      h: d.getUTCHours(), mi: d.getUTCMinutes(), s: d.getUTCSeconds() };
  }

  /** «06/11 21:03:12» */
  function fmtStamp(ms, offsetMin) {
    if (typeof ms !== "number" || !isFinite(ms) || ms <= 0) return "—";
    const p = localParts(ms, offsetMin);
    return two(p.d) + "/" + two(p.mo) + " " + two(p.h) + ":" + two(p.mi) + ":" + two(p.s);
  }

  /** «06/11/2026 21:03» */
  function fmtFull(ms, offsetMin) {
    if (typeof ms !== "number" || !isFinite(ms) || ms <= 0) return "—";
    const p = localParts(ms, offsetMin);
    return two(p.d) + "/" + two(p.mo) + "/" + p.y + " " + two(p.h) + ":" + two(p.mi);
  }

  /** «hace 5 min». Una hora futura (reloj del celular adelantado) se dice, no se esconde. */
  function fmtAgo(deltaMs) {
    if (typeof deltaMs !== "number" || !isFinite(deltaMs)) return "—";
    if (deltaMs < -60000) return "en el futuro (el reloj del celular está adelantado)";
    const s = Math.max(0, Math.round(deltaMs / 1000));
    if (s < 45) return "hace unos segundos";
    const m = Math.round(s / 60);
    if (m < 60) return "hace " + m + " min";
    const h = Math.round(m / 60);
    if (h < 48) return "hace " + h + " h";
    return "hace " + Math.round(h / 24) + " d";
  }

  /* ── eventos ────────────────────────────────────────────────────────────── */

  function normLevel(l) {
    const c = String(l == null ? "" : l).charAt(0).toUpperCase();
    return RANK[c] !== undefined ? c : "I";
  }

  function cmpEvents(a, b) {
    if (a.at !== b.at) return a.at - b.at;
    if (isFinite(a.seq) && isFinite(b.seq) && a.seq !== b.seq) return a.seq - b.seq;
    return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
  }

  /**
   * `events/` de Firebase → lista ordenada de más viejo a más nuevo.
   * Un renglón que no es un objeto se ignora; uno con campos raros se normaliza (no se tira).
   */
  function parseEvents(obj) {
    const out = [];
    if (!obj || typeof obj !== "object") return out;
    const keys = Object.keys(obj);
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      const v = obj[key];
      if (!v || typeof v !== "object") continue;
      const m = /^e(\d+)$/.exec(key);
      const at = Number(v[LAYOUT.EVENT_AT]);
      const n = Math.floor(Number(v[LAYOUT.EVENT_COUNT]));
      const msg = v[LAYOUT.EVENT_MSG];
      const tag = v[LAYOUT.EVENT_TAG];
      out.push({
        key: key,
        seq: m ? Number(m[1]) : NaN,
        at: isFinite(at) ? at : 0,
        level: normLevel(v[LAYOUT.EVENT_LEVEL]),
        tag: typeof tag === "string" ? tag : "",
        msg: typeof msg === "string" ? msg : (msg == null ? "" : String(msg)),
        count: n >= 2 ? n : 1
      });
    }
    out.sort(cmpEvents);
    return out;
  }

  /** Marca de tiempo mínima de una ventana («24h», «3d»…). 0 = sin límite. */
  function windowStart(windowKey, now) {
    const ms = WINDOWS[windowKey];
    return ms ? now - ms : 0;
  }

  /**
   * @param opts {minLevel?:"I"|"W"|"E", sinceMs?:number, text?:string, tag?:string}
   *   `minLevel: "E"` deja errores Y caídas (la caída es más grave que el error).
   */
  function filterEvents(events, opts) {
    opts = opts || {};
    const min = RANK[normLevel(opts.minLevel || "I")];
    const since = Number(opts.sinceMs) || 0;
    const q = String(opts.text || "").trim().toLowerCase();
    const tag = opts.tag ? String(opts.tag).toLowerCase() : "";
    return (events || []).filter(function (e) {
      if (RANK[e.level] < min) return false;
      if (since && e.at < since) return false;
      if (tag && e.tag.toLowerCase() !== tag) return false;
      if (q && (e.tag + " " + e.msg).toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
  }

  /** Etiquetas presentes y cuántos renglones tiene cada una (para el selector). */
  function tagCounts(events) {
    const m = Object.create(null);
    (events || []).forEach(function (e) { const t = e.tag || "?"; m[t] = (m[t] || 0) + 1; });
    return Object.keys(m).map(function (t) { return { tag: t, n: m[t] }; })
      .sort(function (a, b) { return (b.n - a.n) || (a.tag < b.tag ? -1 : 1); });
  }

  /**
   * Resumen para la línea de arriba. «Errores» cuenta E y F; las repeticiones agrupadas
   * (`count`) suman: un error que se repitió 40 veces no es «1 error».
   */
  function summarize(events, now) {
    const s = { rows: 0, warnRows: 0, errorRows: 0, fatalRows: 0, errors24h: 0, fatals24h: 0,
      lastError: null, lastFatal: null, oldestAt: 0, newestAt: 0 };
    const day = now - 86400000;
    (events || []).forEach(function (e) {
      s.rows++;
      if (!s.oldestAt || (e.at && e.at < s.oldestAt)) s.oldestAt = e.at;
      if (e.at > s.newestAt) s.newestAt = e.at;
      if (e.level === "W") s.warnRows++;
      if (e.level === "E" || e.level === "F") {
        s.errorRows++;
        if (!s.lastError || e.at >= s.lastError.at) s.lastError = e;
        if (e.at >= day) s.errors24h += e.count;
      }
      if (e.level === "F") {
        s.fatalRows++;
        if (!s.lastFatal || e.at >= s.lastFatal.at) s.lastFatal = e;
        if (e.at >= day) s.fatals24h += e.count;
      }
    });
    return s;
  }

  /* ── diagnósticos completos (snapshots) ─────────────────────────────────── */

  /** `snapshots/` → lista de más nuevo a más viejo, con todos los campos como texto. */
  function listSnapshots(obj) {
    const out = [];
    if (!obj || typeof obj !== "object") return out;
    Object.keys(obj).forEach(function (key) {
      const v = obj[key];
      if (!v || typeof v !== "object") return;
      const str = function (x) { return typeof x === "string" ? x : (x == null ? "" : String(x)); };
      const at = Number(v.at) || Number(v.t) || 0;
      out.push({
        key: key, at: at, uploadedAt: Number(v.t) || 0,
        pkgs: str(v.pkgs), ver: str(v.ver), diag: Number(v.diag) || 0,
        system: str(v.system), exits: str(v.exits), logcat: str(v.logcat), packages: str(v.packages)
      });
    });
    out.sort(function (a, b) { return (b.at - a.at) || (a.key < b.key ? 1 : a.key > b.key ? -1 : 0); });
    return out;
  }

  /* ── el celular y su estado ─────────────────────────────────────────────── */

  /** Campos de `devices/<id>` que importan acá. Igual que `campo()` de celular.js: arriba o en `info`. */
  function pickDevice(node, id) {
    const d = (node && typeof node === "object") ? node : {};
    const info = (d.info && typeof d.info === "object") ? d.info : {};
    const f = function (k, fb) {
      if (d[k] !== undefined && d[k] !== null) return d[k];
      if (info[k] !== undefined && info[k] !== null) return info[k];
      return fb;
    };
    return {
      id: id || "",
      name: f("deviceName", "") || f("model", "") || "Celular",
      model: f("model", ""),
      manufacturer: f("manufacturer", ""),
      androidVersion: f("androidVersion", f("androidSdkInt", "")),
      versionName: f("versionName", f("appVersionName", "")),
      versionCode: f("versionCode", f("appVersionCode", "")),
      lastSeen: Number(f("lastSeen", 0)) || 0,
      diagVersion: f(LAYOUT.DEV_VERSION, null),
      diagUploadEnabled: f(LAYOUT.DEV_ENABLED, null),
      diagUploadError: f(LAYOUT.DEV_ERROR, null)
    };
  }

  /**
   * La frase de estado de arriba de la pantalla.
   * @returns {{level:"ok"|"warn"|"bad"|"off", canRequest:boolean, headline:string, detail:string, notes:string[]}}
   *   `canRequest`: si tiene sentido ofrecer «Pedir diagnóstico ahora».
   */
  function describeStatus(dev, meta, now) {
    meta = meta || {};
    const notes = [];
    const skipped = Number(meta.skipped) || 0;
    if (skipped > 0) {
      notes.push("El celular omitió " + skipped + " renglones viejos en el último envío porque se juntaron demasiados de golpe.");
    }
    if (dev.diagVersion === null || dev.diagVersion === undefined) {
      return { level: "off", canRequest: false, notes: notes,
        headline: "Este celular todavía no informó tener el registro de diagnóstico.",
        detail: "Tiene una versión de LockSuite anterior a esta función, o todavía no sincronizó después de " +
          "actualizarse. Mandale «Actualizar LockSuite (OTA)» y esperá unos minutos." };
    }
    if (dev.diagUploadEnabled === false) {
      return { level: "warn", canRequest: false, notes: notes,
        headline: "La subida del registro está APAGADA en este celular.",
        detail: "Sigue anotando adentro del celular, pero no manda nada. Encendela para volver a ver errores acá." };
    }
    if (dev.diagUploadError) {
      return { level: "bad", canRequest: true, notes: notes,
        headline: "El celular no pudo subir su registro.",
        detail: "Motivo que informó: " + dev.diagUploadError + ". Si dice «permission denied», faltan desplegar " +
          "las reglas de la base (firebase deploy --only database); el celular reintenta solo." };
    }
    const lastUp = Number(meta.lastUploadAt) || 0;
    const detail = lastUp
      ? "Último envío del celular: " + fmtAgo(now - lastUp) + " (" + fmtFull(lastUp) + ")."
      : "Todavía no llegó ningún renglón de este celular (puede faltar el próximo latido, que es cada unos minutos).";
    return { level: "ok", canRequest: true, notes: notes, headline: "Registro activo.", detail: detail };
  }

  /* ── paquetes que el dueño quiere revisar ───────────────────────────────── */

  /**
   * Texto libre («com.whatsapp, com.waze») → lista limpia para `COLLECT_DIAGNOSTICS`.
   * Misma regla que `isValidPackage` de tienda_logic.js; el tope es el del celular (6).
   * @returns {{list:string[], invalid:string[], tooMany:boolean}}
   */
  function normalizePackages(text) {
    const raw = String(text == null ? "" : text).split(/[\s,;]+/).map(function (s) { return s.trim(); }).filter(Boolean);
    const seen = Object.create(null);
    const list = [];
    const invalid = [];
    raw.forEach(function (p) {
      if (p.length > 200 || !PACKAGE_RE.test(p)) { invalid.push(p); return; }
      if (seen[p]) return;
      seen[p] = true;
      list.push(p);
    });
    return { list: list.slice(0, LAYOUT.MAX_PACKAGES), invalid: invalid, tooMany: list.length > LAYOUT.MAX_PACKAGES };
  }

  /* ── el informe para pegarle a la IA ────────────────────────────────────── */

  function eventText(e, off) {
    const head = "[" + fmtStamp(e.at, off) + "] " + e.level + " " + (e.tag || "?") +
      (e.count > 1 ? " ×" + e.count : "") + " — ";
    const parts = String(e.msg).split("\n");
    const out = [head + parts[0]];
    for (let i = 1; i < parts.length; i++) out.push("    " + parts[i]);
    return out.join("\n");
  }

  function block(text) {
    const t = String(text == null ? "" : text).replace(/\s+$/, "");
    return t ? t : "(vacío)";
  }

  /**
   * Arma el informe de texto.
   * @param inp {
   *   device: pickDevice(...), meta?: object, events: parseEvents(...), snapshot?: listSnapshots(...)[i],
   *   note?: string, now?: number, offsetMin?: number,
   *   eventOptions?: {minLevel?, sinceMs?, text?, tag?}, includeSnapshot?: boolean,
   *   maxEvents?: number (150), maxEventChars?: number (40000)
   * }
   */
  function buildReport(inp) {
    const now = inp.now || Date.now();
    const off = inp.offsetMin;
    const dev = inp.device || pickDevice({}, "");
    const meta = inp.meta || {};
    const L = [];

    L.push("# Informe de diagnóstico de LockSuite");
    L.push("Generado: " + fmtFull(now, off) + " (hora del navegador)");
    L.push("Equipo: " + dev.name + " (id: " + dev.id + ")" +
      (dev.model ? " — " + dev.model : "") + (dev.androidVersion !== "" ? ", Android " + dev.androidVersion : ""));
    L.push("LockSuite: " + (dev.versionName || "?") + (dev.versionCode !== "" ? " (" + dev.versionCode + ")" : "") +
      " · registro de diagnóstico: " + (dev.diagVersion == null ? "no informado" : "formato " + dev.diagVersion) +
      " · subida: " + (dev.diagUploadEnabled === false ? "APAGADA" : dev.diagUploadEnabled === true ? "encendida" : "sin dato"));
    L.push("Último contacto del equipo con la nube: " + (dev.lastSeen ? fmtFull(dev.lastSeen, off) + " (" + fmtAgo(now - dev.lastSeen) + ")" : "sin dato"));
    L.push("Último envío del registro: " + (Number(meta.lastUploadAt) ? fmtFull(Number(meta.lastUploadAt), off) : "ninguno"));
    if (dev.diagUploadError) L.push("⚠ Error de subida informado por el celular: " + dev.diagUploadError);
    if (Number(meta.skipped) > 0) L.push("⚠ El celular omitió " + Number(meta.skipped) + " renglones viejos en el último envío (se juntaron demasiados).");

    const note = String(inp.note || "").trim();
    if (note) {
      L.push("");
      L.push("## Qué contó el usuario");
      L.push(note);
    }

    // ── eventos ──
    const all = inp.events || [];
    const opts = inp.eventOptions || {};
    const filtered = filterEvents(all, opts);
    const maxEvents = inp.maxEvents > 0 ? inp.maxEvents : 150;
    const maxChars = inp.maxEventChars > 0 ? inp.maxEventChars : 40000;
    // Se quedan los MÁS NUEVOS: lo viejo se recorta primero.
    let keep = filtered.slice(Math.max(0, filtered.length - maxEvents));
    let texts = keep.map(function (e) { return eventText(e, off); });
    let total = 0;
    texts.forEach(function (t) { total += t.length + 1; });
    while (texts.length > 1 && total > maxChars) { total -= texts[0].length + 1; texts.shift(); keep.shift(); }
    const omitted = filtered.length - keep.length;
    const sum = summarize(filtered, now);

    L.push("");
    L.push("## Eventos del celular (" + keep.length + " de " + all.length + " guardados)");
    const desc = [];
    desc.push("nivel: " + (RANK[normLevel(opts.minLevel || "I")] >= 2 ? "solo errores y caídas" : RANK[normLevel(opts.minLevel || "I")] === 1 ? "avisos y errores" : "todos"));
    if (opts.sinceMs) desc.push("desde " + fmtFull(opts.sinceMs, off));
    if (opts.tag) desc.push("etiqueta: " + opts.tag);
    if (opts.text) desc.push("texto: «" + opts.text + "»");
    L.push("Filtro: " + desc.join(" · ") + ". Dentro del filtro: " + filtered.length + " renglones (" +
      sum.errorRows + " de error o caída, " + sum.warnRows + " avisos).");
    if (omitted > 0) L.push("(Se omitieron los " + omitted + " renglones más viejos para que el informe no sea enorme.)");
    if (!keep.length) {
      L.push(all.length ? "(Ningún renglón cumple el filtro.)" : "(El celular no subió ningún renglón todavía.)");
    } else {
      L.push("");
      texts.forEach(function (t) { L.push(t); });
    }

    // ── diagnóstico completo ──
    const snap = inp.includeSnapshot === false ? null : inp.snapshot;
    L.push("");
    if (snap) {
      L.push("## Diagnóstico completo pedido desde el panel — " + fmtFull(snap.at, off));
      L.push("LockSuite en ese momento: " + (snap.ver || "?") + ". Apps consultadas: " + (snap.pkgs || "ninguna") + ".");
      L.push("");
      L.push("### Sistema");
      L.push(block(snap.system));
      L.push("");
      L.push("### Cierres recientes del proceso (los anotó Android)");
      L.push(block(snap.exits));
      L.push("");
      L.push("### Errores del logcat de LockSuite");
      L.push(block(snap.logcat));
      L.push("");
      L.push("### Apps consultadas");
      L.push(snap.pkgs ? block(snap.packages) : "(no se pidió ninguna app en particular)");
    } else {
      L.push("## Diagnóstico completo");
      L.push(inp.includeSnapshot === false
        ? "(No se incluyó.)"
        : "(Todavía no se pidió ninguno desde el panel, o el celular no lo pudo subir.)");
    }

    // ── cómo leerlo ──
    L.push("");
    L.push("## Cómo leer esto");
    L.push("- Las horas son las del RELOJ DEL CELULAR, mostradas en la hora del navegador. Si el reloj del celular estaba mal, los renglones se ven corridos.");
    L.push("- Nivel: I = información, W = aviso, E = error, F = caída (el proceso murió). «×N» = el mismo mensaje se repitió N veces y se agrupó.");
    const used = {};
    keep.forEach(function (e) { used[e.tag] = true; });
    const helps = Object.keys(TAG_HELP).filter(function (t) { return used[t]; });
    if (helps.length) {
      L.push("- Etiquetas que aparecen arriba: " + helps.map(function (t) { return t + " = " + TAG_HELP[t]; }).join("; ") + ".");
    }
    L.push("- El registro NO guarda lo que el usuario escribe o ve: antes de anotarse se sacan correos, direcciones web con ruta, teléfonos, rutas de archivos, identificadores y tokens.");
    L.push("- Que un error no aparezca NO prueba que no haya ocurrido: el celular sube en lotes y solo guarda los últimos " +
      LAYOUT.EVENTS_KEEP + " renglones en la nube.");
    return L.join("\n") + "\n";
  }

  return {
    LAYOUT: LAYOUT, TAG_HELP: TAG_HELP, LEVEL_LABEL: LEVEL_LABEL, WINDOWS: WINDOWS,
    fmtStamp: fmtStamp, fmtFull: fmtFull, fmtAgo: fmtAgo,
    normLevel: normLevel, parseEvents: parseEvents, windowStart: windowStart,
    filterEvents: filterEvents, tagCounts: tagCounts, summarize: summarize,
    listSnapshots: listSnapshots, pickDevice: pickDevice, describeStatus: describeStatus,
    normalizePackages: normalizePackages, buildReport: buildReport
  };
});
