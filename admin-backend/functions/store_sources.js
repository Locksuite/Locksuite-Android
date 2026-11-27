"use strict";

/**
 * Proveedores de descarga de APK/Split-APK en orden de prioridad:
 *   1. apkpure — APKPure (`d.apkpure.com` -> `data.winudf.com`), con inspección real de ABI y firma
 *   2. fdroid  — F-Droid oficial (`f-droid.org/api/v1/packages/<pkg>`)
 *   3. aptoide — Aptoide (`ws75.aptoide.com/api/7/app/getMeta`) con filtros estrictos anti-malware y anti-versiones falsas
 *   (4. aurora — Google Play FDFE, integrado en aurora_sync.js cuando fuentes 1-3 no cubren el paquete/ABI)
 *
 * Todo I/O de red usa `fetchImpl` inyectable para pruebas unitarias offline determinísticas.
 */
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { Readable } = require("stream");
const auroraZip = require("./aurora_zip");
const { MAX_APK_DOWNLOAD_BYTES } = require("./aurora_gplay");
const apkInspect = require("./apk_inspect_node");

const SOURCE_PRIORITY = ["apkpure", "fdroid", "aptoide", "aurora"];
const SOURCE_LABELS = {
  apkpure: "APKPure",
  fdroid: "F-Droid",
  aptoide: "Aptoide",
  aurora: "Aurora / Play",
};

const PROBE_TIMEOUT_MS = 18000;
const DOWNLOAD_TIMEOUT_MS = 180000;
const MAX_REDIRECTS = 5;
const MAX_BOGUS_VERCODE = 900_000_000;
const APTOIDE_MAX_AGE_MS = 3 * 365 * 24 * 60 * 60 * 1000;

const ANDROID_BROWSER_UA =
  "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

function isAllowedHost(hostname, allowedPatterns) {
  const h = String(hostname || "").toLowerCase();
  if (!h) return false;
  return allowedPatterns.some((p) => (p.startsWith(".") ? h.endsWith(p) || h === p.slice(1) : h === p));
}

const APKPURE_HOSTS = ["d.apkpure.com", ".winudf.com", ".pureapk.com", ".apkpure.com"];
const FDROID_HOSTS = ["f-droid.org", ".f-droid.org"];
const APTOIDE_HOSTS = ["ws75.aptoide.com", ".aptoide.com"];

function validateHttpsUrl(raw, allowedHosts) {
  let u;
  try {
    u = new URL(String(raw || ""));
  } catch (_) {
    throw new Error("URL de descarga inválida.");
  }
  if (u.protocol !== "https:") {
    throw new Error(`Protocolo no permitido: ${u.protocol}`);
  }
  if (u.username || u.password) {
    throw new Error("La URL no puede contener credenciales.");
  }
  if (allowedHosts && !isAllowedHost(u.hostname, allowedHosts)) {
    throw new Error(`Host no permitido para descarga: ${u.hostname}`);
  }
  return u.toString();
}

async function fetchWithTimeout(fetchImpl, url, init = {}, timeoutMs = PROBE_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetchImpl(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function followRedirectManually(fetchImpl, startUrl, method, headers, allowedHosts, timeoutMs = PROBE_TIMEOUT_MS) {
  let currentUrl = validateHttpsUrl(startUrl, allowedHosts);
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const res = await fetchWithTimeout(
      fetchImpl,
      currentUrl,
      { method, redirect: "manual", headers },
      timeoutMs
    );
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers && typeof res.headers.get === "function" ? res.headers.get("location") : null;
      if (!loc || hop === MAX_REDIRECTS) {
        return { res, finalUrl: currentUrl };
      }
      const nextUrl = new URL(loc, currentUrl).toString();
      currentUrl = validateHttpsUrl(nextUrl, allowedHosts);
      continue;
    }
    return { res, finalUrl: currentUrl };
  }
  throw new Error("Demasiadas redirecciones.");
}

async function downloadUrlToFile(
  url,
  destPath,
  {
    fetchImpl = globalThis.fetch,
    allowedHosts = null,
    headers = { "user-agent": ANDROID_BROWSER_UA },
    maxBytes = MAX_APK_DOWNLOAD_BYTES,
    expectedMd5 = "",
    timeoutMs = DOWNLOAD_TIMEOUT_MS,
  } = {}
) {
  let currentUrl = validateHttpsUrl(url, allowedHosts);
  let res = null;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    res = await fetchWithTimeout(
      fetchImpl,
      currentUrl,
      { method: "GET", redirect: "manual", headers },
      timeoutMs
    );
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers && typeof res.headers.get === "function" ? res.headers.get("location") : null;
      if (!loc || hop === MAX_REDIRECTS) {
        throw new Error(`Demasiadas redirecciones al descargar (${res.status}).`);
      }
      currentUrl = validateHttpsUrl(new URL(loc, currentUrl).toString(), allowedHosts);
      continue;
    }
    break;
  }
  if (!res || !res.ok) {
    throw new Error(`Descarga fallida (HTTP ${res ? res.status : "?"}).`);
  }

  const contentType = res.headers && typeof res.headers.get === "function"
    ? String(res.headers.get("content-type") || "").toLowerCase()
    : "";
  if (contentType.includes("text/html")) {
    throw new Error("El servidor devolvió HTML en lugar de un paquete APK/XAPK.");
  }

  const clRaw = res.headers && typeof res.headers.get === "function" ? res.headers.get("content-length") : null;
  if (clRaw && Number(clRaw) > maxBytes) {
    throw new Error(`El archivo excede el tope de ${Math.round(maxBytes / (1024 * 1024))} MB.`);
  }

  const sha256Hash = crypto.createHash("sha256");
  const md5Hash = expectedMd5 ? crypto.createHash("md5") : null;
  let totalBytes = 0;

  await new Promise((resolve, reject) => {
    const ws = fs.createWriteStream(destPath);
    if (res.body && typeof res.body.getReader === "function") {
      const nodeStream = Readable.fromWeb(res.body);
      nodeStream.on("data", (chunk) => {
        totalBytes += chunk.length;
        if (totalBytes > maxBytes) {
          nodeStream.destroy(new Error(`El archivo excede el tope de ${Math.round(maxBytes / (1024 * 1024))} MB.`));
          ws.destroy();
          return;
        }
        sha256Hash.update(chunk);
        if (md5Hash) md5Hash.update(chunk);
      });
      nodeStream.on("error", (err) => {
        ws.destroy();
        reject(err);
      });
      ws.on("error", reject);
      ws.on("finish", resolve);
      nodeStream.pipe(ws);
    } else {
      Promise.resolve(res.arrayBuffer())
        .then((ab) => {
          const buf = Buffer.from(ab);
          totalBytes = buf.length;
          if (totalBytes > maxBytes) {
            ws.destroy();
            reject(new Error(`El archivo excede el tope de ${Math.round(maxBytes / (1024 * 1024))} MB.`));
            return;
          }
          sha256Hash.update(buf);
          if (md5Hash) md5Hash.update(buf);
          ws.end(buf, resolve);
        })
        .catch((err) => {
          ws.destroy();
          reject(err);
        });
    }
  });

  if (totalBytes < 100) {
    throw new Error("El archivo descargado está vacío o demasiado pequeño.");
  }
  const md5 = md5Hash ? md5Hash.digest("hex") : "";
  if (expectedMd5 && md5 !== String(expectedMd5).trim().toLowerCase()) {
    throw new Error(`MD5 inválido (esperado ${expectedMd5}, real ${md5}).`);
  }
  return {
    finalUrl: currentUrl,
    sizeBytes: totalBytes,
    sha256: sha256Hash.digest("hex"),
    md5,
  };
}

/**
 * Decodifica `<pkg>_<versionCode>_<hex>` de los redirects de data.winudf.com.
 */
function parseWinudfUrlInfo(urlStr, expectedPkg, contentDisposition = "") {
  let versionCode = 0;
  let versionName = "";
  try {
    const u = new URL(urlStr);
    const seg = u.pathname.split("/").filter(Boolean).pop() || "";
    if (seg) {
      const b64 = seg.replace(/-/g, "+").replace(/_/g, "/");
      const decoded = Buffer.from(b64, "base64").toString("utf8");
      // Ejemplo: com.whatsapp_264608200_a1b2c3d4
      const escPkg = expectedPkg.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const m = new RegExp(`^${escPkg}_(\\d+)_`).exec(decoded);
      if (m) {
        const vc = Number(m[1]);
        if (Number.isSafeInteger(vc) && vc > 0 && vc < MAX_BOGUS_VERCODE) {
          versionCode = vc;
        }
      }
    }
  } catch (_) {}

  if (contentDisposition) {
    // filename="WhatsApp Messenger_2.26.46.82_APKPure.xapk"
    const mFn = /filename\*?=(?:UTF-8''|")?([^";\r\n]+)/i.exec(contentDisposition);
    if (mFn) {
      const fn = decodeURIComponent(mFn[1]).replace(/["']/g, "");
      const parts = fn.split("_");
      if (parts.length >= 2 && /^\d/.test(parts[1])) {
        versionName = parts[1].trim();
      }
    }
  }
  return { versionCode, versionName };
}

// ── Proveedor 1: APKPure ─────────────────────────────────────────────────────

async function probeApkPure(pkg, fetchImpl = globalThis.fetch) {
  const urls = [
    `https://d.apkpure.com/b/XAPK/${encodeURIComponent(pkg)}?version=latest`,
    `https://d.apkpure.com/b/APK/${encodeURIComponent(pkg)}?version=latest`,
  ];
  const headers = {
    "user-agent": ANDROID_BROWSER_UA,
    accept: "*/*",
  };
  let lastErr = "No encontrado en APKPure";
  for (const startUrl of urls) {
    try {
      const { res, finalUrl } = await followRedirectManually(
        fetchImpl,
        startUrl,
        "HEAD",
        headers,
        APKPURE_HOSTS,
        PROBE_TIMEOUT_MS
      );
      const ct = res.headers && res.headers.get ? String(res.headers.get("content-type") || "").toLowerCase() : "";
      if (res.status === 200 && !ct.includes("text/html")) {
        const cd = res.headers && res.headers.get ? String(res.headers.get("content-disposition") || "") : "";
        const cl = res.headers && res.headers.get ? Number(res.headers.get("content-length") || 0) : 0;
        const parsed = parseWinudfUrlInfo(finalUrl, pkg, cd);
        return {
          found: true,
          source: "apkpure",
          packageName: pkg,
          versionCode: parsed.versionCode,
          versionName: parsed.versionName,
          downloadUrl: finalUrl,
          sizeBytes: cl > 0 ? cl : 0,
        };
      }
      lastErr = `APKPure HTTP ${res.status}`;
    } catch (e) {
      lastErr = e && e.message ? e.message : String(e);
    }
  }
  return { found: false, source: "apkpure", error: lastErr };
}

async function downloadApkPure(probe, workDir, fetchImpl = globalThis.fetch) {
  const rawPath = path.join(workDir, `apkpure_${probe.packageName}.bin`);
  await downloadUrlToFile(probe.downloadUrl, rawPath, {
    fetchImpl,
    allowedHosts: APKPURE_HOSTS,
  });
  const inspected = await apkInspect.inspectAndRepackArtifact(rawPath, probe.packageName, workDir);
  if (inspected.filePath !== rawPath) {
    await fs.promises.unlink(rawPath).catch(() => {});
  }
  return {
    source: "apkpure",
    ...inspected,
  };
}

// ── Proveedor 2: F-Droid ─────────────────────────────────────────────────────

async function probeFDroid(pkg, fetchImpl = globalThis.fetch) {
  try {
    const apiUrl = `https://f-droid.org/api/v1/packages/${encodeURIComponent(pkg)}`;
    const res = await fetchWithTimeout(fetchImpl, apiUrl, {
      method: "GET",
      redirect: "error",
      headers: { "user-agent": ANDROID_BROWSER_UA, accept: "application/json" },
    }, PROBE_TIMEOUT_MS);
    if (!res.ok) {
      return { found: false, source: "fdroid", error: `F-Droid HTTP ${res.status}` };
    }
    const json = await res.json().catch(() => null);
    if (!json || !Array.isArray(json.packages) || json.packages.length === 0) {
      return { found: false, source: "fdroid", error: "Sin paquetes en F-Droid" };
    }
    const suggested = Number(json.suggestedVersionCode || 0);
    let best = json.packages.find((p) => Number(p.versionCode) === suggested) || null;
    if (!best) {
      best = json.packages
        .slice()
        .sort((a, b) => Number(b.versionCode || 0) - Number(a.versionCode || 0))[0];
    }
    const vc = Number(best && best.versionCode ? best.versionCode : 0);
    if (!Number.isSafeInteger(vc) || vc <= 0) {
      return { found: false, source: "fdroid", error: "versionCode inválido en F-Droid" };
    }
    return {
      found: true,
      source: "fdroid",
      packageName: pkg,
      versionCode: vc,
      versionName: String((best && best.versionName) || ""),
      downloadUrl: `https://f-droid.org/repo/${encodeURIComponent(pkg)}_${vc}.apk`,
    };
  } catch (e) {
    return { found: false, source: "fdroid", error: e && e.message ? e.message : String(e) };
  }
}

async function downloadFDroid(probe, workDir, fetchImpl = globalThis.fetch) {
  const rawPath = path.join(workDir, `fdroid_${probe.packageName}.apk`);
  await downloadUrlToFile(probe.downloadUrl, rawPath, {
    fetchImpl,
    allowedHosts: FDROID_HOSTS,
  });
  const inspected = await apkInspect.inspectAndRepackArtifact(rawPath, probe.packageName, workDir);
  if (inspected.filePath !== rawPath) {
    await fs.promises.unlink(rawPath).catch(() => {});
  }
  return {
    source: "fdroid",
    ...inspected,
  };
}

// ── Proveedor 3: Aptoide (validación estricta) ───────────────────────────────

function validateAptoideMeta(json, expectedPkg, now = Date.now()) {
  if (!json || !json.info || json.info.status !== "OK" || !json.data) {
    return { ok: false, reason: "Aptoide no devolvió metadatos OK" };
  }
  const data = json.data;
  if (data.package !== expectedPkg) {
    return { ok: false, reason: `Aptoide devolvió otro paquete (${data.package})` };
  }
  const file = data.file;
  if (!file || typeof file !== "object") {
    return { ok: false, reason: "Falta file en Aptoide" };
  }
  const rank = file.malware && file.malware.rank ? String(file.malware.rank).toUpperCase() : "";
  if (rank !== "TRUSTED") {
    return { ok: false, reason: `Malware rank no es TRUSTED (${rank || "sin rank"})` };
  }
  const vc = Number(file.vercode || 0);
  const vn = String(file.vername || "").trim();
  if (!Number.isSafeInteger(vc) || vc <= 0 || vc >= MAX_BOGUS_VERCODE) {
    return { ok: false, reason: `versionCode sospechoso o inválido en Aptoide (${vc})` };
  }
  if (/^899\.999/.test(vn) || /99999/.test(vn)) {
    return { ok: false, reason: `versionName sospechoso en Aptoide (${vn})` };
  }
  if (file.added) {
    const addedMs = Date.parse(String(file.added).replace(" ", "T") + "Z");
    if (Number.isFinite(addedMs) && now - addedMs > APTOIDE_MAX_AGE_MS) {
      return { ok: false, reason: `Compilación demasiado vieja en Aptoide (${file.added})` };
    }
  }
  let downloadUrl;
  try {
    downloadUrl = validateHttpsUrl(file.path, APTOIDE_HOSTS);
  } catch (e) {
    return { ok: false, reason: e.message };
  }

  const splits = [];
  if (data.aab && Array.isArray(data.aab.splits)) {
    for (const sp of data.aab.splits) {
      if (!sp || !sp.path) continue;
      const spUrl = validateHttpsUrl(sp.path, APTOIDE_HOSTS);
      splits.push({
        name: String(sp.name || `split_${splits.length + 1}`),
        path: spUrl,
        md5sum: typeof sp.md5sum === "string" ? sp.md5sum.trim() : "",
      });
    }
  }

  return {
    ok: true,
    versionCode: vc,
    versionName: vn,
    title: typeof data.name === "string" && data.name.trim() ? data.name.trim() : expectedPkg,
    downloadUrl,
    md5sum: typeof file.md5sum === "string" ? file.md5sum.trim() : "",
    splits,
  };
}

async function probeAptoide(pkg, fetchImpl = globalThis.fetch, now = Date.now()) {
  try {
    const apiUrl = `https://ws75.aptoide.com/api/7/app/getMeta?package_name=${encodeURIComponent(pkg)}`;
    const res = await fetchWithTimeout(fetchImpl, apiUrl, {
      method: "GET",
      redirect: "error",
      headers: { "user-agent": ANDROID_BROWSER_UA, accept: "application/json" },
    }, PROBE_TIMEOUT_MS);
    if (!res.ok) {
      return { found: false, source: "aptoide", error: `Aptoide HTTP ${res.status}` };
    }
    const json = await res.json().catch(() => null);
    const val = validateAptoideMeta(json, pkg, now);
    if (!val.ok) {
      return { found: false, source: "aptoide", error: val.reason };
    }
    return {
      found: true,
      source: "aptoide",
      packageName: pkg,
      title: val.title,
      versionCode: val.versionCode,
      versionName: val.versionName,
      downloadUrl: val.downloadUrl,
      md5sum: val.md5sum,
      splits: val.splits,
    };
  } catch (e) {
    return { found: false, source: "aptoide", error: e && e.message ? e.message : String(e) };
  }
}

async function downloadAptoide(probe, workDir, fetchImpl = globalThis.fetch) {
  const basePath = path.join(workDir, `aptoide_base_${probe.packageName}.apk`);
  await downloadUrlToFile(probe.downloadUrl, basePath, {
    fetchImpl,
    allowedHosts: APTOIDE_HOSTS,
    expectedMd5: probe.md5sum || "",
  });

  if (!Array.isArray(probe.splits) || probe.splits.length === 0) {
    const inspected = await apkInspect.inspectAndRepackArtifact(basePath, probe.packageName, workDir);
    if (inspected.filePath !== basePath) {
      await fs.promises.unlink(basePath).catch(() => {});
    }
    return {
      source: "aptoide",
      title: probe.title || probe.packageName,
      ...inspected,
    };
  }

  // Combinar base + splits en un .apks temporal e inspeccionar como bundle unificado
  const used = new Set(["base.apk"]);
  const packEntries = [{ name: "base.apk", filePath: basePath }];
  for (let i = 0; i < probe.splits.length; i++) {
    const sp = probe.splits[i];
    const cleanBase = String(sp.name || `split_${i + 1}`)
      .replace(/[^a-zA-Z0-9._-]/g, "_")
      .replace(/\.apk$/i, "") || `split_${i + 1}`;
    let entryName = `${cleanBase}.apk`;
    let c = 2;
    while (used.has(entryName)) {
      entryName = `${cleanBase}_${c++}.apk`;
    }
    used.add(entryName);
    const spPath = path.join(workDir, `aptoide_sp_${i}.apk`);
    await downloadUrlToFile(sp.path, spPath, {
      fetchImpl,
      allowedHosts: APTOIDE_HOSTS,
      expectedMd5: sp.md5sum || "",
    });
    packEntries.push({ name: entryName, filePath: spPath });
  }
  const combinedPath = path.join(workDir, `aptoide_combined_${probe.packageName}.apks`);
  await auroraZip.writeStoreZipToFile(packEntries, combinedPath);
  await Promise.all(packEntries.map((e) => fs.promises.unlink(e.filePath).catch(() => {})));
  const inspected = await apkInspect.inspectAndRepackArtifact(combinedPath, probe.packageName, workDir);
  if (inspected.filePath !== combinedPath) {
    await fs.promises.unlink(combinedPath).catch(() => {});
  }
  return {
    source: "aptoide",
    title: probe.title || probe.packageName,
    ...inspected,
  };
}

const DEFAULT_MIRRORS = [
  { name: "apkpure", probe: probeApkPure, download: downloadApkPure },
  { name: "fdroid", probe: probeFDroid, download: downloadFDroid },
  { name: "aptoide", probe: probeAptoide, download: downloadAptoide },
];

module.exports = {
  SOURCE_PRIORITY,
  SOURCE_LABELS,
  APKPURE_HOSTS,
  FDROID_HOSTS,
  APTOIDE_HOSTS,
  DEFAULT_MIRRORS,
  parseWinudfUrlInfo,
  validateAptoideMeta,
  probeApkPure,
  downloadApkPure,
  probeFDroid,
  downloadFDroid,
  probeAptoide,
  downloadAptoide,
  downloadUrlToFile,
  validateHttpsUrl,
};
