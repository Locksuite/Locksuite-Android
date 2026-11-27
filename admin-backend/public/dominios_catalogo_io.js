/**
 * LockSuite — Exportación, importación, validación DomainSafety, lectura .txt/.docx e historial
 * de respaldos del catálogo de lista blanca por dispositivo (`lockfox-dominios-v1`).
 *
 * Funciona sin librerías externas (compatible con CSP y WebView de :admin-app) y se comparte
 * entre `whitelist_device_ui.js` y `tools/test_dominios_io.js`.
 */
(function (root) {
  "use strict";

  var FORMAT_VERSION = "lockfox-dominios-v1";
  var TTL_DAYS = 30;
  var DAY_MS = 24 * 60 * 60 * 1000;
  var TTL_MS = TTL_DAYS * DAY_MS;

  var DOMAIN_FORMAT_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
  var HOSTING_BASES = new Set([
    "googleapis.com", "storage.googleapis.com", "commondatastorage.googleapis.com",
    "firebasestorage.googleapis.com", "googleusercontent.com", "ggpht.com",
    "translate.goog", "sites.google.com", "script.google.com", "appspot.com",
    "web.app", "firebaseapp.com", "cloudfunctions.net", "run.app", "a.run.app",
    "blogspot.com", "firebaseio.com", "firebasedatabase.app",
    "amazonaws.com", "s3.amazonaws.com", "cloudfront.net", "elasticbeanstalk.com",
    "amplifyapp.com", "on.aws",
    "azurewebsites.net", "azureedge.net", "blob.core.windows.net", "web.core.windows.net",
    "cloudapp.net", "azurestaticapps.net", "trafficmanager.net",
    "akamai.net", "akamaihd.net", "akamaized.net", "akamaiedge.net", "edgekey.net",
    "edgesuite.net", "fastly.net", "fastlylb.net", "global.ssl.fastly.net", "edgecompute.app",
    "b-cdn.net", "bunnycdn.com", "cdn77.org", "rsc.cdn77.org", "cdn77.com",
    "vercel.app", "now.sh", "netlify.app", "netlify.com", "github.io",
    "githubusercontent.com", "gitlab.io", "bitbucket.io", "pages.dev", "workers.dev",
    "r2.dev", "trycloudflare.com", "herokuapp.com", "onrender.com", "railway.app",
    "fly.dev", "glitch.me", "repl.co", "replit.app", "replit.dev", "surge.sh",
    "deno.dev", "csb.app", "codesandbox.io", "stackblitz.io", "ngrok.io", "ngrok-free.app",
    "ngrok.app", "loca.lt", "duckdns.org", "wixsite.com", "wordpress.com", "weebly.com",
    "squarespace.com", "myshopify.com", "webflow.io", "framer.app", "framer.website",
    "notion.site", "carrd.co", "tumblr.com", "000webhostapp.com", "neocities.org",
    "pythonanywhere.com", "ipfs.io", "dweb.link", "cloudflare-ipfs.com"
  ]);
  var GENERIC_SLD = new Set([
    "com", "net", "org", "gob", "gov", "edu", "ac", "co", "mil", "int", "nom",
    "ltd", "plc", "sch", "or", "ne", "go", "muni", "tur"
  ]);

  function cleanDomainDetailed(raw) {
    var d = String(raw == null ? "" : raw).trim().toLowerCase();
    if (!d) return { reason: "vacío" };
    if (d.indexOf("*") >= 0 && d.indexOf("*.") !== 0) return { reason: "no se admiten wildcards (*)" };
    var scheme = d.indexOf("://");
    if (scheme >= 0) d = d.slice(scheme + 3);
    d = d.split("/")[0].split("?")[0].split("#")[0];
    d = d.slice(d.lastIndexOf("@") + 1);
    d = d.split(":")[0];
    for (;;) {
      if (d.indexOf("*.") === 0) d = d.slice(2);
      else if (d.charAt(0) === ".") d = d.slice(1);
      else break;
    }
    d = d.replace(/\.+$/, "");
    if (!d) return { reason: "vacío" };
    if (d.indexOf(".") < 0) return { reason: "es un TLD o una sola palabra" };
    if (d.length < 4 || d.length > 253) return { reason: "largo inválido" };
    if (!DOMAIN_FORMAT_RE.test(d)) return { reason: "formato inválido" };
    if (!/[a-z]/.test(d.slice(d.lastIndexOf(".") + 1))) return { reason: "no es un dominio" };
    return { domain: d };
  }

  function allowRejectReason(raw) {
    var d = String(raw == null ? "" : raw).trim().replace(/\.+$/, "").toLowerCase();
    if (!d) return "vacío";
    if (/[^a-z0-9.-]/.test(d)) return "caracteres inválidos";
    var labels = d.split(".");
    if (labels.some(function (l) { return l.length === 0; })) return "etiqueta vacía";
    if (labels.length < 2) return "es un TLD";
    if (labels.length === 2 && GENERIC_SLD.has(labels[0]) && labels[1].length === 2) {
      return "sufijo público genérico";
    }
    if (labels.length === 2 && labels[0].indexOf("akamai") === 0) return "base de CDN (Akamai)";
    if (HOSTING_BASES.has(d)) return "base de hosting/CDN compartida";
    var iter = HOSTING_BASES.values();
    for (var step = iter.next(); !step.done; step = iter.next()) {
      if (step.value.endsWith("." + d)) return "contiene una base de hosting";
    }
    return null;
  }

  function compareLex(a, b) {
    return a < b ? -1 : (a > b ? 1 : 0);
  }

  function cleanList(list) {
    var arr = Array.isArray(list) ? list : [];
    var set = new Set();
    for (var i = 0; i < arr.length; i++) {
      var s = String(arr[i] == null ? "" : arr[i]).trim().toLowerCase().replace(/\.+$/, "");
      if (s) set.add(s);
    }
    return Array.from(set).sort(compareLex);
  }

  function stripAccentsLower(s) {
    return String(s || "").trim().toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
  }

  function normStatus(statusRaw, enabledRaw) {
    if (statusRaw !== undefined && statusRaw !== null && statusRaw !== "") {
      var v = stripAccentsLower(statusRaw);
      if (v === "disabled" || v === "deshabilitada" || v === "deshabilitado" || v === "false" || v === "no") return "disabled";
      if (v === "blocked" || v === "bloqueada" || v === "bloqueado" || v === "prohibida" || v === "block") return "blocked";
      return "enabled";
    }
    return enabledRaw === false ? "disabled" : "enabled";
  }

  function normAccess(a) {
    var v = stripAccentsLower(a);
    if (v === "noweb" || v === "sin web" || v === "sin_web") return "noweb";
    if (v === "full" || v === "completa" || v === "completo") return "full";
    return "domains";
  }

  function fnv1a32(str, seed) {
    var h = seed >>> 0;
    for (var i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(16).padStart(8, "0");
  }

  function computeFingerprint(apps, globalAllow, globalBlock) {
    var canonical = JSON.stringify({
      apps: apps.map(function (a) { return [a.packageName, a.status, a.access, a.allow, a.block]; }),
      globalAllow: globalAllow,
      globalBlock: globalBlock
    });
    return fnv1a32(canonical, 0x811c9dc5) + fnv1a32(canonical, 0x9e3779b9);
  }

  function buildSnapshot(appsRaw, globalAllowRaw, globalBlockRaw) {
    var rawApps = Array.isArray(appsRaw) ? appsRaw : Object.values(appsRaw || {});
    var byPkg = Object.create(null);

    for (var i = 0; i < rawApps.length; i++) {
      var app = rawApps[i];
      if (!app || typeof app !== "object") continue;
      var pkg = String(app.packageName || "").trim();
      if (!pkg) continue;
      var st = normStatus(app.status, app.enabled);
      byPkg[pkg] = {
        packageName: pkg,
        label: String(app.label || pkg).trim() || pkg,
        status: st,
        enabled: st === "enabled",
        access: normAccess(app.access),
        allow: cleanList(app.allow),
        block: cleanList(app.block)
      };
    }

    var apps = Object.keys(byPkg).sort(compareLex).map(function (pkg) { return byPkg[pkg]; });
    var globalBlock = cleanList(globalBlockRaw);
    var blockSet = new Set(globalBlock);
    var globalAllow = cleanList(globalAllowRaw).filter(function (d) { return !blockSet.has(d); });

    var appAllowCount = 0;
    var appBlockCount = 0;
    var enabledAppCount = 0;
    for (var j = 0; j < apps.length; j++) {
      appAllowCount += apps[j].allow.length;
      appBlockCount += apps[j].block.length;
      if (apps[j].status === "enabled") enabledAppCount++;
    }

    return {
      apps: apps,
      globalAllow: globalAllow,
      globalBlock: globalBlock,
      summary: {
        appsCount: apps.length,
        enabledAppCount: enabledAppCount,
        appAllowCount: appAllowCount,
        appBlockCount: appBlockCount,
        globalAllowCount: globalAllow.length,
        globalBlockCount: globalBlock.length
      },
      fingerprint: computeFingerprint(apps, globalAllow, globalBlock)
    };
  }

  function normalizeStoredSnapshot(snapshot) {
    var raw = snapshot && typeof snapshot === "object" ? snapshot : {};
    return buildSnapshot(raw.apps || [], raw.globalAllow || [], raw.globalBlock || []);
  }

  function snapshotsDiffer(a, b) {
    return Boolean(a && b && a.fingerprint !== b.fingerprint);
  }

  function pad2(n) {
    return n < 10 ? "0" + n : String(n);
  }

  function formatBackupFilename(tsMs, deviceSlug) {
    var ms = typeof tsMs === "number" && Number.isFinite(tsMs) && tsMs > 0 ? tsMs : Date.now();
    var d = new Date(ms);
    var slug = String(deviceSlug || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    return (
      "locksuite-dominios-" + (slug ? slug + "-" : "") +
      d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) + "_" +
      pad2(d.getHours()) + "-" + pad2(d.getMinutes()) + "-" + pad2(d.getSeconds()) + ".txt"
    );
  }

  function cleanSingleLineMeta(s) {
    return String(s == null ? "" : s).replace(/[\r\n]+/g, " ").trim();
  }

  function pushDomainBlockLines(lines, list) {
    if (!list || list.length === 0) {
      lines.push("# (ninguno)");
      return;
    }
    for (var i = 0; i < list.length; i++) lines.push(list[i]);
  }

  function serializeCatalogTxt(snapshot, meta) {
    var snap = normalizeStoredSnapshot(snapshot);
    var m = meta && typeof meta === "object" ? meta : {};
    var dateIso = typeof m.createdAt === "number" && Number.isFinite(m.createdAt) && m.createdAt > 0
      ? new Date(m.createdAt).toISOString()
      : new Date().toISOString();

    var lines = [
      "# LockSuite — Catálogo de dominios de lista blanca",
      "# Formato: " + FORMAT_VERSION,
      "# Fecha: " + dateIso
    ];
    if (m.deviceName) lines.push("# Celular: " + cleanSingleLineMeta(m.deviceName));
    lines.push(
      "# Apps: " + snap.summary.appsCount +
      " | Permitidos app: " + snap.summary.appAllowCount +
      " | Prohibidos app: " + snap.summary.appBlockCount +
      " | Globales permitidos: " + snap.summary.globalAllowCount +
      " | Globales prohibidos: " + snap.summary.globalBlockCount
    );
    if (m.reason) lines.push("# Motivo: " + cleanSingleLineMeta(m.reason));
    if (m.note) lines.push("# Nota: " + cleanSingleLineMeta(m.note));
    lines.push("# En Permitidos/Prohibidos vale uno por línea o separados por coma; líneas con # se ignoran.");
    lines.push("");

    var sortedApps = snap.apps.slice().sort(function (a, b) {
      var la = String(a.label || a.packageName).toLowerCase();
      var lb = String(b.label || b.packageName).toLowerCase();
      return compareLex(la, lb) || compareLex(a.packageName, b.packageName);
    });

    for (var i = 0; i < sortedApps.length; i++) {
      var app = sortedApps[i];
      var est = app.status === "disabled" ? "deshabilitada" : app.status === "blocked" ? "bloqueada" : "habilitada";
      lines.push("[APP: " + app.packageName + "]");
      lines.push("Nombre: " + cleanSingleLineMeta(app.label || app.packageName));
      lines.push("Estado: " + est);
      lines.push("Acceso: " + normAccess(app.access));
      lines.push("Permitidos:");
      pushDomainBlockLines(lines, app.allow);
      lines.push("Prohibidos:");
      pushDomainBlockLines(lines, app.block);
      lines.push("");
    }

    lines.push("[GLOBAL: PERMITIDOS]");
    pushDomainBlockLines(lines, snap.globalAllow);
    lines.push("");
    lines.push("[GLOBAL: PROHIBIDOS]");
    pushDomainBlockLines(lines, snap.globalBlock);
    lines.push("");
    return lines.join("\n");
  }

  function stripComment(rawLine) {
    return String(rawLine || "").replace(/(?:^|\s)(?:#|\/\/).*$/, "").trim();
  }

  function parseDomainListText(rawText, isAllow) {
    var raw = String(rawText == null ? "" : rawText);
    if (raw.charCodeAt(0) === 0xfeff) raw = raw.slice(1);
    raw = raw.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    var accepted = [];
    var seen = new Set();
    var rejected = [];
    var lines = raw.split("\n");
    for (var i = 0; i < lines.length; i++) {
      var line = stripComment(lines[i]);
      if (!line) continue;
      var tokens = line.split(/[\s,;]+/);
      for (var t = 0; t < tokens.length; t++) {
        var tok = tokens[t];
        if (!tok) continue;
        var res = cleanDomainDetailed(tok);
        if (!res || !res.domain) {
          rejected.push({ raw: tok.slice(0, 60), reason: (res && res.reason) || "inválido" });
          continue;
        }
        var dom = res.domain;
        if (isAllow) {
          var why = allowRejectReason(dom);
          if (why) {
            rejected.push({ raw: dom, reason: why });
            continue;
          }
        }
        if (!seen.has(dom)) {
          seen.add(dom);
          accepted.push(dom);
        }
      }
    }
    accepted.sort(compareLex);
    return { domains: accepted, rejected: rejected };
  }

  function formatDomainListText(domains, title) {
    var list = cleanList(domains);
    return ["# " + (title || "Dominios"), "# Total: " + list.length].concat(list).join("\n") + "\n";
  }

  function parseCatalogTxt(text) {
    var raw = String(text == null ? "" : text);
    if (raw.charCodeAt(0) === 0xfeff) raw = raw.slice(1);
    raw = raw.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

    var lines = raw.split("\n");
    var appsMap = Object.create(null);
    var appOrder = [];
    var globalAllowSeen = new Set();
    var globalAllowList = [];
    var globalBlockSeen = new Set();
    var globalBlockList = [];
    var hasGlobalAllow = false;
    var hasGlobalBlock = false;
    var foundAnySection = false;

    var sectionType = null;
    var currentApp = null;
    var currentList = null;

    var warnings = { invalid: [], rejected: [], duplicates: 0 };

    function addTokens(tokensText, kind, targetSet, targetArr) {
      var tokens = String(tokensText || "").split(/[\s,;]+/);
      for (var t = 0; t < tokens.length; t++) {
        var tok = tokens[t];
        if (!tok) continue;
        var res = cleanDomainDetailed(tok);
        if (!res || !res.domain) {
          warnings.invalid.push((tok.length > 60 ? tok.slice(0, 60) + "…" : tok) + " (" + ((res && res.reason) || "inválido") + ")");
          continue;
        }
        var dom = res.domain;
        if (targetSet.has(dom)) { warnings.duplicates++; continue; }
        targetSet.add(dom);
        if (kind === "allow") {
          var why = allowRejectReason(dom);
          if (why) { warnings.rejected.push(dom + " (" + why + ")"); continue; }
        }
        targetArr.push(dom);
      }
    }

    for (var i = 0; i < lines.length; i++) {
      var line = stripComment(lines[i]);
      if (!line) continue;

      if (line.charAt(0) === "[" && line.charAt(line.length - 1) === "]") {
        var inner = line.slice(1, -1).trim();
        var appMatch = /^app\s*:\s*(.+)$/i.exec(inner);
        if (appMatch) {
          var pkg = appMatch[1].trim();
          if (!/^[a-zA-Z0-9_.]+$/.test(pkg) || pkg.indexOf(".") < 0) {
            warnings.invalid.push("[APP: " + pkg + "] (paquete inválido)");
            sectionType = null; currentApp = null; currentList = null;
            continue;
          }
          foundAnySection = true;
          sectionType = "app";
          currentList = null;
          if (!appsMap[pkg]) {
            appsMap[pkg] = {
              packageName: pkg, label: "", status: "enabled", access: "domains",
              allowSet: new Set(), allow: [], blockSet: new Set(), block: []
            };
            appOrder.push(pkg);
          }
          currentApp = appsMap[pkg];
          continue;
        }

        var normHeader = stripAccentsLower(inner).replace(/\s+/g, " ");
        if (/^global(?:es)?\s*:\s*(?:permitidos?|allow)$/.test(normHeader)) {
          foundAnySection = true; sectionType = "global_allow"; currentApp = null; currentList = null; hasGlobalAllow = true;
          continue;
        }
        if (/^global(?:es)?\s*:\s*(?:prohibidos?|block)$/.test(normHeader)) {
          foundAnySection = true; sectionType = "global_block"; currentApp = null; currentList = null; hasGlobalBlock = true;
          continue;
        }
      }

      if (sectionType === "app" && currentApp) {
        var kvMatch = /^([A-Za-zÁÉÍÓÚáéíóúÑñ_]+)\s*:\s*(.*)$/.exec(line);
        if (kvMatch) {
          var key = stripAccentsLower(kvMatch[1]);
          var val = kvMatch[2].trim();
          if (key === "nombre" || key === "label") { if (val) currentApp.label = val; continue; }
          if (key === "estado" || key === "status") { currentApp.status = normStatus(val); continue; }
          if (key === "acceso" || key === "access") { currentApp.access = normAccess(val); continue; }
          if (key === "permitidos" || key === "permitido" || key === "allow") {
            currentList = "allow"; if (val) addTokens(val, "allow", currentApp.allowSet, currentApp.allow); continue;
          }
          if (key === "prohibidos" || key === "prohibido" || key === "block") {
            currentList = "block"; if (val) addTokens(val, "block", currentApp.blockSet, currentApp.block); continue;
          }
        }
        if (currentList === "allow") addTokens(line, "allow", currentApp.allowSet, currentApp.allow);
        else if (currentList === "block") addTokens(line, "block", currentApp.blockSet, currentApp.block);
        continue;
      }

      if (sectionType === "global_allow") addTokens(line, "allow", globalAllowSeen, globalAllowList);
      else if (sectionType === "global_block") addTokens(line, "block", globalBlockSeen, globalBlockList);
    }

    if (!foundAnySection) {
      return { error: "El archivo no tiene el formato de catálogo de dominios (faltan bloques [APP: ...] o [GLOBAL: ...])." };
    }

    var apps = appOrder.map(function (pkg) {
      var a = appsMap[pkg];
      return {
        packageName: a.packageName,
        label: a.label || a.packageName,
        status: a.status,
        enabled: a.status === "enabled",
        access: a.access,
        allow: a.allow.slice().sort(compareLex),
        block: a.block.slice().sort(compareLex)
      };
    });

    var finalGlobalBlock = hasGlobalBlock ? globalBlockList.slice().sort(compareLex) : [];
    var gBlockSet = new Set(finalGlobalBlock);
    var finalGlobalAllow = hasGlobalAllow ? globalAllowList.filter(function (d) { return !gBlockSet.has(d); }).sort(compareLex) : [];

    return {
      apps: apps,
      globalAllow: finalGlobalAllow,
      globalBlock: finalGlobalBlock,
      hasGlobalAllow: hasGlobalAllow,
      hasGlobalBlock: hasGlobalBlock,
      warnings: warnings
    };
  }

  /* ── Lectura pura JS de .txt y .docx (ZIP + DEFLATE-RAW sin CDNs) ─────── */
  function readU16(bytes, off) { return bytes[off] | (bytes[off + 1] << 8); }
  function readU32(bytes, off) { return (bytes[off] | (bytes[off + 1] << 8) | (bytes[off + 2] << 16) | (bytes[off + 3] << 24)) >>> 0; }

  async function extractZipEntryBytes(zipBytes, entryName) {
    var eocdOffset = -1;
    var minOff = Math.max(0, zipBytes.length - 65557);
    for (var i = zipBytes.length - 22; i >= minOff; i--) {
      if (readU32(zipBytes, i) === 0x06054b50) { eocdOffset = i; break; }
    }
    if (eocdOffset < 0) throw new Error("El archivo .docx no tiene una estructura ZIP válida.");
    var totalEntries = readU16(zipBytes, eocdOffset + 10);
    var cdOffset = readU32(zipBytes, eocdOffset + 16);
    var dec = new TextDecoder("utf-8");
    var ptr = cdOffset;
    for (var e = 0; e < totalEntries; e++) {
      if (ptr + 46 > zipBytes.length || readU32(zipBytes, ptr) !== 0x02014b50) break;
      var compMethod = readU16(zipBytes, ptr + 10);
      var compSize = readU32(zipBytes, ptr + 20);
      var nameLen = readU16(zipBytes, ptr + 28);
      var extraLen = readU16(zipBytes, ptr + 30);
      var commentLen = readU16(zipBytes, ptr + 32);
      var localHeaderOff = readU32(zipBytes, ptr + 42);
      var name = dec.decode(zipBytes.subarray(ptr + 46, ptr + 46 + nameLen));
      ptr += 46 + nameLen + extraLen + commentLen;
      if (name === entryName) {
        if (localHeaderOff + 30 > zipBytes.length || readU32(zipBytes, localHeaderOff) !== 0x04034b50) {
          throw new Error("Cabecera local ZIP corrupta.");
        }
        var lhNameLen = readU16(zipBytes, localHeaderOff + 26);
        var lhExtraLen = readU16(zipBytes, localHeaderOff + 28);
        var dataStart = localHeaderOff + 30 + lhNameLen + lhExtraLen;
        var compressed = zipBytes.subarray(dataStart, dataStart + compSize);
        if (compMethod === 0) return compressed;
        if (compMethod === 8) {
          if (typeof DecompressionStream === "undefined") {
            throw new Error("Este navegador no soporta leer .docx directamente. Guardalo como .txt.");
          }
          var ds = new DecompressionStream("deflate-raw");
          var ab = await new Response(new Blob([compressed]).stream().pipeThrough(ds)).arrayBuffer();
          return new Uint8Array(ab);
        }
        throw new Error("Compresión ZIP no soportada en .docx (" + compMethod + ").");
      }
    }
    return null;
  }

  function docxXmlToText(xml) {
    return String(xml || "")
      .replace(/<\/w:p>/g, "\n")
      .replace(/<w:(?:br|cr)\b[^>]*\/>/g, "\n")
      .replace(/<w:tab\b[^>]*\/>/g, " ")
      .replace(/<[^>]+>/g, "")
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&#(\d+);/g, function (_, n) { return String.fromCharCode(Number(n)); })
      .replace(/&#x([0-9a-fA-F]+);/g, function (_, h) { return String.fromCharCode(parseInt(h, 16)); });
  }

  async function readTextFile(file) {
    if (!file) throw new Error("No se seleccionó ningún archivo.");
    var buf = await file.arrayBuffer();
    var bytes = new Uint8Array(buf);
    var isDocx = /\.docx$/i.test(file.name || "") ||
      (bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04);
    if (!isDocx) return new TextDecoder("utf-8").decode(bytes);
    var docXmlBytes = await extractZipEntryBytes(bytes, "word/document.xml");
    if (!docXmlBytes) throw new Error("El archivo .docx no contiene word/document.xml.");
    return docxXmlToText(new TextDecoder("utf-8").decode(docXmlBytes));
  }

  function findExpiredBackupIds(backupsMap, nowMs, ttlMs) {
    if (!backupsMap || typeof backupsMap !== "object") return [];
    var now = typeof nowMs === "number" && Number.isFinite(nowMs) ? nowMs : Date.now();
    var ttl = typeof ttlMs === "number" && Number.isFinite(ttlMs) && ttlMs > 0 ? ttlMs : TTL_MS;
    var expired = [];
    var keys = Object.keys(backupsMap);
    for (var i = 0; i < keys.length; i++) {
      var id = keys[i];
      var entry = backupsMap[id];
      if (!entry || typeof entry !== "object") continue;
      if (entry.favorite === true) continue;
      var created = Number(entry.createdAt);
      if (!Number.isFinite(created) || created <= 0) continue;
      if ((now - created) >= ttl) expired.push(id);
    }
    return expired;
  }

  function daysUntilExpiry(backup, nowMs, ttlMs) {
    if (!backup || typeof backup !== "object" || backup.favorite === true) return null;
    var created = Number(backup.createdAt);
    if (!Number.isFinite(created) || created <= 0) return null;
    var now = typeof nowMs === "number" && Number.isFinite(nowMs) ? nowMs : Date.now();
    var ttl = typeof ttlMs === "number" && Number.isFinite(ttlMs) && ttlMs > 0 ? ttlMs : TTL_MS;
    return Math.max(0, Math.ceil((created + ttl - now) / DAY_MS));
  }

  var api = {
    FORMAT_VERSION: FORMAT_VERSION,
    TTL_DAYS: TTL_DAYS,
    TTL_MS: TTL_MS,
    cleanDomainDetailed: cleanDomainDetailed,
    allowRejectReason: allowRejectReason,
    cleanList: cleanList,
    buildSnapshot: buildSnapshot,
    normalizeStoredSnapshot: normalizeStoredSnapshot,
    snapshotsDiffer: snapshotsDiffer,
    formatBackupFilename: formatBackupFilename,
    serializeCatalogTxt: serializeCatalogTxt,
    parseCatalogTxt: parseCatalogTxt,
    parseDomainListText: parseDomainListText,
    formatDomainListText: formatDomainListText,
    readTextFile: readTextFile,
    docxXmlToText: docxXmlToText,
    findExpiredBackupIds: findExpiredBackupIds,
    daysUntilExpiry: daysUntilExpiry
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.DomCatalogoIO = api;
})(typeof window !== "undefined" ? window : this);
