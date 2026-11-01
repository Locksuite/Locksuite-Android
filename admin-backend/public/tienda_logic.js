/* ═══════════════════════════════════════════════════════════════════════════
 * tienda_logic.js — la lógica PURA de la pantalla "Tienda Play" (29/10/2026, B.101).
 *
 * Es el espejo en JavaScript de `mdm/PlayStoreCatalog.kt`: las mismas reglas de
 * validación, para que lo que el panel guarda sea EXACTAMENTE lo que el celular acepta.
 * Si el panel dejara pasar algo que el celular descarta (un dominio mal escrito, un
 * nivel fuera de 1..3), el administrador vería la app en su lista y el usuario no la
 * vería en la tienda, sin ningún mensaje: la misma familia de bugs mudos que este
 * proyecto ya pagó varias veces (B.28, B.40, B.57). Por eso la validación vive acá, una
 * sola vez, y tiene su banco de pruebas (tools/test_tienda_logic.js).
 *
 * Sin DOM y sin Firebase: se usa en el navegador (`window.TiendaLogic`) y en Node
 * (`module.exports`).
 *
 * ── LO QUE PIDIÓ EL DUEÑO (30/10/2026) ────────────────────────────────────────────
 * "por defecto por ahora poneme en mi panel web que aparezcan para nuestra nueva tienda
 *  las apps que ya están en la tienda vieja de locksuite (las q hay en play store). Y yo
 *  desde ahí pueda habilitar/bloquear, solo a pedido, o eliminar."
 *
 * De ahí salen tres cosas, todas acá:
 *   1. `seedFromStoreApps`  — arma la lista inicial desde `storeApps` (la tienda vieja).
 *   2. el ESTADO de cada app: `enabled` (habilitada) · `request` (solo a pedido) ·
 *      `blocked` (bloqueada). "Eliminar" saca la fila.
 *   3. `buildPayload`       — lo que se guarda en `globalSettings/playStore/apps`.
 * ═══════════════════════════════════════════════════════════════════════════ */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.TiendaLogic = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const STATUS = { ENABLED: "enabled", REQUEST: "request", BLOCKED: "blocked" };
  const STATUSES = [STATUS.ENABLED, STATUS.REQUEST, STATUS.BLOCKED];
  const ACCESS = { FULL: "full", NOWEB: "noweb", DOMAINS: "domains" };
  const ACCESS_TYPES = [ACCESS.FULL, ACCESS.NOWEB, ACCESS.DOMAINS];
  const MODES = ["cerrada", "kosher", "trabajo", "lite"];

  const TIER_MIN = 1;
  const TIER_MAX = 3;
  const MAX_APPS = 400;
  const MAX_EXTRAS = 100;
  const MAX_DOMAINS_PER_LIST = 60;
  const MAX_LABEL_CHARS = 60;

  /** Espejo de PlayStoreCatalog.NEVER_OFFERED. */
  const NEVER_OFFERED = [
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
  ];

  /**
   * Los stubs de Android Auto de la tienda vieja (mdm/AndroidAutoStubs.kt). Llevan el
   * nombre de paquete de la Maps y de la App de Google REALES pero son APK vacíos propios:
   * NO son apps de Google Play y no se ofrecen en la Tienda Play. Se reconocen igual que
   * en el celular: paquete + huella exacta del archivo.
   */
  const AA_STUB_SHA256 = {
    "com.google.android.apps.maps": "b0d1fc188e88e5a27b366f1d7160488d1b0ebe393ad78e3c69fda96c6692cbd3",
    "com.google.android.googlequicksearchbox": "d81901b6c544df6a2e16cc3e8c96cd83677225c5073eefb64ff2331666bb6152",
  };

  const PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
  const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
  const ICON_HOST_SUFFIXES = [".googleusercontent.com", ".ggpht.com", ".gstatic.com"];

  /* ── Claves de Firebase ─────────────────────────────────────────────────── */
  /** Las claves de Firebase no admiten `.`. El paquete real se lee SIEMPRE del campo `packageName`. */
  const pkgKey = (pkg) => String(pkg).replace(/\./g, "_");
  const keyPkg = (key) => String(key).replace(/_/g, ".");

  /* ── Validación (espejo de PlayStoreCatalog) ────────────────────────────── */
  function isValidPackage(pkg) {
    return typeof pkg === "string" && pkg.length <= 200 && PACKAGE_RE.test(pkg);
  }

  function isNeverOffered(pkg) {
    return NEVER_OFFERED.indexOf(pkg) >= 0;
  }

  function parseStatus(raw) {
    const v = typeof raw === "string" ? raw.trim().toLowerCase() : "";
    return STATUSES.indexOf(v) >= 0 ? v : null;
  }

  function parseAccess(raw) {
    const v = typeof raw === "string" ? raw.trim().toLowerCase() : "";
    return ACCESS_TYPES.indexOf(v) >= 0 ? v : null;
  }

  /**
   * Dominio limpio o null. Acepta lo que un humano pega en el panel
   * (`https://www.Ejemplo.com/ruta`, `*.ejemplo.com`, `ejemplo.com.`) y lo reduce a
   * `www.ejemplo.com`. Exige al menos un punto: una etiqueta sola (`com`) cerraría o
   * abriría un TLD entero. Igual que PlayStoreCatalog.cleanDomain.
   */
  function cleanDomain(raw) {
    if (typeof raw !== "string") return null;
    let d = raw.trim().toLowerCase();
    if (!d) return null;
    const scheme = d.indexOf("://");
    if (scheme >= 0) d = d.substring(scheme + 3);
    d = d.split("/")[0].split("?")[0].split("#")[0];
    d = d.substring(d.lastIndexOf("@") + 1);
    d = d.split(":")[0];
    while (d.startsWith("*.") || d.startsWith(".")) d = d.startsWith("*.") ? d.slice(2) : d.slice(1);
    d = d.replace(/\.+$/, "");
    if (d.length < 4 || d.length > 253) return null;
    if (!DOMAIN_RE.test(d)) return null;
    // La última etiqueta (TLD) tiene que tener una letra: "1.2.3.4" no es un dominio.
    return /[a-z]/.test(d.substring(d.lastIndexOf(".") + 1)) ? d : null;
  }

  /** Lista limpia, sin repetidos, con tope. `rejected` lleva lo que se descartó, para avisarlo. */
  function cleanDomains(list) {
    const out = [];
    const rejected = [];
    const seen = new Set();
    for (const item of Array.isArray(list) ? list : []) {
      if (item == null || String(item).trim() === "") continue;
      const d = cleanDomain(String(item));
      if (!d) { rejected.push(String(item)); continue; }
      if (seen.has(d)) continue;
      if (out.length >= MAX_DOMAINS_PER_LIST) { rejected.push(String(item)); continue; }
      seen.add(d);
      out.push(d);
    }
    return { domains: out, rejected: rejected };
  }

  /** Texto libre (pegado en un cuadro, separado por comas, espacios o renglones) → lista. */
  function splitDomainText(text) {
    return String(text == null ? "" : text).split(/[\s,;]+/).filter(Boolean);
  }

  function fallbackLabel(pkg) {
    const last = String(pkg).substring(String(pkg).lastIndexOf(".") + 1) || String(pkg);
    const words = last.split("_").filter(Boolean);
    const text = words.map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
    return text || String(pkg);
  }

  function cleanLabel(raw, pkg) {
    const cleaned = typeof raw === "string"
      // eslint-disable-next-line no-control-regex
      ? raw.replace(/[\u0000-\u001F\u007F]/g, " ").trim().replace(/\s+/g, " ").slice(0, MAX_LABEL_CHARS).trim()
      : "";
    return cleaned || fallbackLabel(pkg);
  }

  /** Solo https hacia el CDN de Google; cualquier otra cosa → null. */
  function cleanIconUrl(raw) {
    if (typeof raw !== "string") return null;
    const url = raw.trim();
    if (!url || url.length > 600 || !url.startsWith("https://")) return null;
    if (/[\s"'<>]/.test(url)) return null;
    const authority = url.slice("https://".length).split("/")[0].split("?")[0];
    if (authority.indexOf("@") >= 0) return null;
    const host = authority.split(":")[0].toLowerCase();
    if (!host) return null;
    return ICON_HOST_SUFFIXES.some((s) => host.endsWith(s)) ? url : null;
  }

  /* ── Filas ──────────────────────────────────────────────────────────────── */
  /**
   * Una fila de trabajo del panel. `origin` solo sirve para mostrar un cartelito:
   *   "saved" (ya guardada) · "seed" (sugerida desde la tienda vieja, sin guardar) · "new".
   */
  function makeRow(o) {
    return {
      pkg: o.pkg,
      label: o.label || fallbackLabel(o.pkg),
      // ¿El nombre lo escribió una persona (o viene de la tienda vieja / de lo guardado)?
      // Si no, «Verificar en Play» puede reemplazarlo por el nombre real de la app. Se lleva
      // aparte y NO se deduce de «el nombre es igual al derivado del paquete»: para
      // com.waze esos dos coinciden y no se puede saber quién lo puso.
      labelSet: o.labelSet != null ? !!o.labelSet : !!o.label,
      tier: o.tier == null ? 1 : o.tier,
      access: o.access || ACCESS.FULL,
      status: o.status || STATUS.ENABLED,
      allow: Array.isArray(o.allow) ? o.allow.slice() : [],
      block: Array.isArray(o.block) ? o.block.slice() : [],
      iconUrl: o.iconUrl || null,
      origin: o.origin || "new",
      inOldStore: !!o.inOldStore,
      play: o.play || null,        // "found" | "missing" | "unknown" | null (sin verificar)
    };
  }

  /** Los paquetes de `globalSettings/allowedPackages` (array u objeto de RTDB) → Set. */
  function toPackageSet(raw) {
    const set = new Set();
    if (raw == null) return set;
    const items = Array.isArray(raw) ? raw : (typeof raw === "object" ? Object.keys(raw).map((k) => raw[k]) : []);
    for (const v of items) if (typeof v === "string" && v.trim()) set.add(v.trim());
    return set;
  }

  /**
   * LISTA INICIAL — las apps de la tienda vieja (`storeApps`) para la tienda nueva.
   *
   * Reglas (todas por decisión, y se dicen en la pantalla para que no sorprendan):
   *  · Entran todas las de `storeApps` menos las que no pueden estar en la Tienda Play:
   *      - las de NEVER_OFFERED (LockSuite, Google Play, Play Services…),
   *      - los stubs de Android Auto (paquete + huella exacta; ver AA_STUB_SHA256),
   *      - las de nombre de paquete inválido.
   *  · Nivel 1 (Kosher) para todas: el dueño las reparte después entre los 3 niveles.
   *  · Acceso "completa".
   *  · ESTADO inicial = lo que ya decidió el dueño en la tienda vieja: si la app está en
   *    `globalSettings/allowedPackages` arranca HABILITADA; si no, arranca SOLO A PEDIDO
   *    (en la tienda vieja esas se ven con el botón «Pedir»). Ninguna arranca habilitada
   *    por defecto sin que el dueño ya la hubiera habilitado antes.
   *  · Nada de esto llega a los celulares hasta que el dueño toque «Guardar».
   *
   * @param storeApps   `storeApps` tal cual viene de Firebase (clave `pkg_` → {label, packageName, apkUrl, sha256…}).
   * @param allowedRaw  `globalSettings/allowedPackages` (array u objeto).
   * @returns {{rows: Array, skipped: Array<{pkg:string,label:string,reason:string}>}}
   */
  function seedFromStoreApps(storeApps, allowedRaw) {
    const allowed = toPackageSet(allowedRaw);
    const rows = [];
    const skipped = [];
    const seen = new Set();
    const entries = storeApps && typeof storeApps === "object" ? Object.keys(storeApps) : [];
    entries.sort();
    for (const key of entries) {
      const e = storeApps[key];
      if (!e || typeof e !== "object") continue;
      const pkg = (typeof e.packageName === "string" && e.packageName.trim()) || keyPkg(key);
      const label = cleanLabel(e.label, pkg);
      if (!isValidPackage(pkg)) { skipped.push({ pkg: pkg, label: label, reason: "invalid" }); continue; }
      if (isNeverOffered(pkg)) { skipped.push({ pkg: pkg, label: label, reason: "never" }); continue; }
      const stubHash = AA_STUB_SHA256[pkg];
      if (stubHash && typeof e.sha256 === "string" && e.sha256.trim().toLowerCase() === stubHash) {
        skipped.push({ pkg: pkg, label: label, reason: "aa-stub" });
        continue;
      }
      if (seen.has(pkg)) continue;
      seen.add(pkg);
      rows.push(makeRow({
        pkg: pkg,
        label: label,
        labelSet: typeof e.label === "string" && e.label.trim() !== "",
        tier: 1,
        access: ACCESS.FULL,
        status: allowed.has(pkg) ? STATUS.ENABLED : STATUS.REQUEST,
        origin: "seed",
        inOldStore: true,
      }));
    }
    rows.sort(byLabel);
    return { rows: rows, skipped: skipped };
  }

  /** Apps de la tienda vieja que todavía no están en `rows` (para «Traer de la tienda vieja»). */
  function missingFromOldStore(rows, storeApps, allowedRaw) {
    const have = new Set(rows.map((r) => r.pkg));
    const seed = seedFromStoreApps(storeApps, allowedRaw);
    return {
      rows: seed.rows.filter((r) => !have.has(r.pkg)),
      skipped: seed.skipped,
    };
  }

  function byLabel(a, b) {
    const la = String(a.label).toLowerCase();
    const lb = String(b.label).toLowerCase();
    return la < lb ? -1 : la > lb ? 1 : (a.pkg < b.pkg ? -1 : a.pkg > b.pkg ? 1 : 0);
  }

  /**
   * Mezcla el resultado de «Verificar en Play» (Cloud Function `checkPlayApps`) en las filas.
   * `found` trae nombre e ícono públicos: el ícono se guarda si la fila no tenía; el nombre
   * SOLO se reemplaza si nadie lo escribió (`labelSet` en falso: una fila agregada solo por
   * paquete), así que no se le pisa nada al dueño. `missing` y `unknown` solo marcan la fila.
   * @returns cantidad de filas tocadas.
   */
  function applyPlayCheck(rows, results) {
    const by = {};
    for (const r of Array.isArray(results) ? results : []) if (r && r.packageName) by[r.packageName] = r;
    let touched = 0;
    for (const row of rows) {
      const r = by[row.pkg];
      if (!r) continue;
      const status = r.status === "found" || r.status === "missing" ? r.status : "unknown";
      row.play = status;
      touched++;
      if (status === "found") {
        const icon = cleanIconUrl(r.iconUrl);
        if (icon && !row.iconUrl) row.iconUrl = icon;
        if (typeof r.title === "string" && r.title.trim() && !row.labelSet) {
          row.label = cleanLabel(r.title, row.pkg);
          row.labelSet = true;
        }
      }
    }
    return touched;
  }

  /* ── Guardar ────────────────────────────────────────────────────────────── */
  /**
   * Valida las filas y arma lo que se escribe en `globalSettings/playStore/apps`.
   * Devuelve `problems` con CADA cosa que se descartó o corrigió: guardar en silencio algo
   * distinto de lo que se ve en pantalla es justo el bug que este módulo existe para evitar.
   *
   * @param opts.extra  true para los paquetes extra de UN equipo (sin nivel, tope menor).
   * @returns {{apps: Object, problems: string[], count: number}}
   */
  function buildPayload(rows, opts) {
    const extra = !!(opts && opts.extra);
    const limit = extra ? MAX_EXTRAS : MAX_APPS;
    const apps = {};
    const problems = [];
    let count = 0;
    for (const row of rows) {
      const pkg = row.pkg;
      if (!isValidPackage(pkg)) { problems.push("«" + pkg + "»: nombre de paquete inválido, no se guarda."); continue; }
      if (isNeverOffered(pkg)) { problems.push(pkg + ": no se puede ofrecer en la tienda, no se guarda."); continue; }
      const status = parseStatus(row.status);
      const access = parseAccess(row.access);
      if (!status) { problems.push(pkg + ": estado desconocido (" + row.status + "), no se guarda."); continue; }
      if (!access) { problems.push(pkg + ": acceso desconocido (" + row.access + "), no se guarda."); continue; }
      let tier = null;
      if (!extra) {
        tier = Number(row.tier);
        if (!Number.isInteger(tier) || tier < TIER_MIN || tier > TIER_MAX) {
          problems.push(pkg + ": nivel fuera de 1 a 3 (" + row.tier + "), no se guarda.");
          continue;
        }
      }
      if (count >= limit) { problems.push(pkg + ": se pasa del tope de " + limit + " apps, no se guarda."); continue; }

      const o = { packageName: pkg, label: cleanLabel(row.label, pkg), access: access, status: status };
      if (!extra) o.tier = tier;
      if (access === ACCESS.DOMAINS) {
        const a = cleanDomains(row.allow);
        const b = cleanDomains(row.block);
        for (const bad of a.rejected) problems.push(pkg + ": dominio permitido descartado (" + bad + ").");
        for (const bad of b.rejected) problems.push(pkg + ": dominio prohibido descartado (" + bad + ").");
        // Un dominio en las dos listas es una contradicción: el celular le da prioridad a
        // «prohibido» (igual que el resto de la lista blanca), así que se avisa.
        const both = a.domains.filter((d) => b.domains.indexOf(d) >= 0);
        for (const d of both) problems.push(pkg + ": " + d + " está en permitidos y en prohibidos; gana «prohibido».");
        // Realtime Database descarta los arrays vacíos: no se escriben vacíos.
        if (a.domains.length) o.allow = a.domains;
        if (b.domains.length) o.block = b.domains;
        if (!a.domains.length && !b.domains.length) {
          problems.push(pkg + ": acceso «dominios» sin ningún dominio; la app no tendría internet bajo el filtro estricto.");
        }
      }
      const icon = cleanIconUrl(row.iconUrl);
      if (icon) o.iconUrl = icon;
      apps[pkgKey(pkg)] = o;
      count++;
    }
    return { apps: apps, problems: problems, count: count };
  }

  /** `globalSettings/playStore/apps` (o `devices/<id>/playStoreExtras`) → filas. */
  function rowsFromSaved(node, opts) {
    const extra = !!(opts && opts.extra);
    const rows = [];
    if (!node || typeof node !== "object") return rows;
    for (const key of Object.keys(node)) {
      const v = node[key];
      if (!v || typeof v !== "object") continue;
      const pkg = (typeof v.packageName === "string" && v.packageName.trim()) || keyPkg(key);
      if (!isValidPackage(pkg)) continue;
      const list = (x) => (Array.isArray(x) ? x : (x && typeof x === "object" ? Object.keys(x).map((k) => x[k]) : []))
        .filter((s) => typeof s === "string");
      rows.push(makeRow({
        pkg: pkg,
        label: cleanLabel(v.label, pkg),
        labelSet: true,
        tier: extra ? 0 : (Number.isInteger(v.tier) ? v.tier : 1),
        access: parseAccess(v.access) || ACCESS.FULL,
        status: parseStatus(v.status) || STATUS.ENABLED,
        allow: list(v.allow),
        block: list(v.block),
        iconUrl: cleanIconUrl(v.iconUrl),
        origin: "saved",
      }));
    }
    rows.sort(byLabel);
    return rows;
  }

  /**
   * Firma estable de un conjunto de filas, para saber si hay cambios sin guardar.
   * Los dominios solo cuentan con acceso «dominios»: con cualquier otro acceso `buildPayload`
   * no los escribe, así que dejar restos escritos en la fila (alguien probó «dominios» y
   * volvió a «completa») no es un cambio y no debe dejar la pantalla en «sin guardar».
   */
  function signature(rows) {
    return rows
      .map((r) => {
        const dom = r.access === ACCESS.DOMAINS;
        return [r.pkg, r.label, r.tier, r.access, r.status,
          dom ? (r.allow || []).join(",") : "", dom ? (r.block || []).join(",") : "",
          r.iconUrl || ""].join("\u0001");
      })
      .sort()
      .join("\u0002");
  }

  /** Cuántas filas hay por nivel y estado (para el resumen de arriba de la pantalla). */
  function summarize(rows) {
    const s = { total: rows.length, enabled: 0, request: 0, blocked: 0, tiers: { 1: 0, 2: 0, 3: 0 } };
    for (const r of rows) {
      if (r.status === STATUS.ENABLED) s.enabled++;
      else if (r.status === STATUS.REQUEST) s.request++;
      else if (r.status === STATUS.BLOCKED) s.blocked++;
      if (s.tiers[r.tier] != null) s.tiers[r.tier]++;
    }
    return s;
  }

  /**
   * ¿Qué verá un celular en cada modo? Cuenta lo MISMO que `PlayStoreCatalog.listed`:
   * nivel ≤ tope del modo, estado distinto de bloqueada.
   */
  function visibleIn(rows, mode) {
    const max = maxTier(mode);
    return rows.filter((r) => r.tier <= max && r.status !== STATUS.BLOCKED);
  }

  /** Hasta qué nivel llega un modo. 0 = cerrada y TODO lo desconocido (igual que PlayStoreCatalog.maxTier). */
  function maxTier(mode) {
    const v = typeof mode === "string" ? mode.trim().toLowerCase() : "";
    return { cerrada: 0, kosher: 1, trabajo: 2, lite: 3 }[v] || 0;
  }

  /** El modo tal como lo entiende el celular: lo desconocido, vacío o ausente es «cerrada». */
  function normalizeMode(mode) {
    const v = typeof mode === "string" ? mode.trim().toLowerCase() : "";
    return MODES.indexOf(v) >= 0 ? v : "cerrada";
  }

  /**
   * Lo que la tienda de UN celular MUESTRA, calculado en el panel: el espejo de
   * `PlayStoreCatalog.listed` (merged + sin bloqueadas + sin NEVER_OFFERED + por nombre).
   *  · Con la tienda cerrada no hay nada, tenga extras o no.
   *  · Del catálogo global entran los niveles 1..tope del modo; los extras del equipo entran
   *    siempre (sin nivel) y REEMPLAZAN a la entrada global del mismo paquete.
   *  · «Bloqueada» no se muestra. «A pedido» sí (con «Pedir»); «habilitada» con «Instalar».
   * Es una vista informativa: el que decide es el celular (que además aplica la lista blanca).
   */
  function listedFor(mode, globalRows, extraRows) {
    const max = maxTier(mode);
    if (max <= 0) return [];
    const by = new Map();
    for (const r of globalRows || []) if (r.tier >= TIER_MIN && r.tier <= max) by.set(r.pkg, r);
    for (const r of extraRows || []) by.set(r.pkg, r);
    return Array.from(by.values())
      .filter((r) => r.status !== STATUS.BLOCKED && !isNeverOffered(r.pkg))
      .sort(byLabel);
  }

  /**
   * APROBAR el pedido de una app «a pedido» para UN equipo = guardar un paquete extra
   * HABILITADO en ese equipo (`devices/<id>/playStoreExtras`). Devuelve la lista nueva de
   * extras (no toca la que recibe).
   *
   * Un extra REEMPLAZA entero a la entrada global del mismo paquete (PlayStoreCatalog.merged),
   * así que se copia de ahí todo lo que el dueño ya había decidido —acceso «sin web»,
   * dominios, nombre, ícono—: aprobar un pedido no puede cambiar el tipo de acceso de la app.
   * Si ya había un extra de ese paquete, se parte de él. Si la app no está en el catálogo
   * global (un pedido viejo), entra con acceso completo.
   *
   * @param extraRows  filas de los extras actuales del equipo (`rowsFromSaved(..., {extra:true})`).
   * @param globalRows filas del catálogo global.
   */
  function approveRequest(extraRows, globalRows, pkg, label) {
    const rows = (extraRows || []).map((r) => Object.assign({}, r, { allow: (r.allow || []).slice(), block: (r.block || []).slice() }));
    const idx = rows.findIndex((r) => r.pkg === pkg);
    const base = idx >= 0 ? rows[idx] : ((globalRows || []).find((r) => r.pkg === pkg) || null);
    const row = makeRow({
      pkg: pkg,
      label: (base && base.label) || cleanLabel(label, pkg),
      labelSet: true,
      tier: 0,
      access: (base && base.access) || ACCESS.FULL,
      status: STATUS.ENABLED,
      allow: base ? base.allow : [],
      block: base ? base.block : [],
      iconUrl: base ? base.iconUrl : null,
      origin: "saved",
    });
    if (idx >= 0) rows[idx] = row; else rows.push(row);
    rows.sort(byLabel);
    return rows;
  }

  return {
    STATUS: STATUS, STATUSES: STATUSES, ACCESS: ACCESS, ACCESS_TYPES: ACCESS_TYPES, MODES: MODES,
    NEVER_OFFERED: NEVER_OFFERED, AA_STUB_SHA256: AA_STUB_SHA256,
    MAX_APPS: MAX_APPS, MAX_EXTRAS: MAX_EXTRAS, MAX_DOMAINS_PER_LIST: MAX_DOMAINS_PER_LIST,
    pkgKey: pkgKey, keyPkg: keyPkg,
    isValidPackage: isValidPackage, isNeverOffered: isNeverOffered,
    parseStatus: parseStatus, parseAccess: parseAccess,
    cleanDomain: cleanDomain, cleanDomains: cleanDomains, splitDomainText: splitDomainText,
    fallbackLabel: fallbackLabel, cleanLabel: cleanLabel, cleanIconUrl: cleanIconUrl,
    makeRow: makeRow, toPackageSet: toPackageSet,
    seedFromStoreApps: seedFromStoreApps, missingFromOldStore: missingFromOldStore,
    applyPlayCheck: applyPlayCheck,
    buildPayload: buildPayload, rowsFromSaved: rowsFromSaved,
    signature: signature, summarize: summarize, visibleIn: visibleIn,
    maxTier: maxTier, normalizeMode: normalizeMode, listedFor: listedFor, approveRequest: approveRequest,
    byLabel: byLabel,
  };
});
