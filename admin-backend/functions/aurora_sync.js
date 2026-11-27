"use strict";

/**
 * Orquestador multi-fuente de sincronización de Tienda LockSuite:
 *   Prioridad de descarga: APKPure -> F-Droid -> Aptoide (validado) -> Aurora (Google Play FDFE)
 *
 *   - Inspecciona todo paquete descargado (.apk o .xapk/.apks) en apk_inspect_node.js:
 *     verifica packageName, versionCode real, ABIs reales (nunca etiqueta arm64-only como universal),
 *     re-empaqueta splits en .apks STORE limpio y extrae la huella SHA-256 del certificado firmante.
 *   - Invoca Aurora/Google Play SOLO cuando las fuentes 1-3 no cubren la app o falta alguna ABI (ej. 32-bit).
 *   - Sube artefactos a GCS (`locksuite-nueva-store-apks` bajo `aurora/<pkg_>/`) y actualiza `auroraStore/packages/<pkg_>`.
 *   - Recolecta catálogo activo + Android Auto + apps de usuario no bloqueadas ni críticas instaladas en la flota.
 */
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { DEVICE_PROFILES } = require("./aurora_devices");
const { MAX_APK_DOWNLOAD_BYTES } = require("./aurora_gplay");
const { writeStoreZipToFile } = require("./aurora_zip");
const apkInspect = require("./apk_inspect_node");
const storeSources = require("./store_sources");

const STORE_APKS_BUCKET = "locksuite-nueva-store-apks";
const PROFILE_ORDER = ["arm64_v8a", "armeabi_v7a"];
const PKG_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
const ANDROID_AUTO_PKG = "com.google.android.projection.gearhead";

/**
 * Apps preinstaladas / del sistema seguras para actualizar desde la tienda
 * (coincide con UnifiedStoreLogic.UPDATABLE_SYSTEM_ALLOWLIST en Android).
 * NUNCA incluir Google Play Services (com.google.android.gms) ni stubs ni críticas.
 */
const UPDATABLE_SYSTEM_ALLOWLIST = new Set([
  ANDROID_AUTO_PKG,
  "com.google.android.inputmethod.latin",
  "com.google.android.apps.messaging",
  "com.google.android.contacts",
  "com.google.android.dialer",
  "com.google.android.calendar",
  "com.google.android.deskclock",
  "com.google.android.calculator",
  "com.google.android.apps.translate",
  "com.google.android.apps.docs",
  "com.google.android.apps.tachyon",
  "com.google.android.apps.adm",
  "com.google.android.tts",
  "com.google.android.marvin.talkback",
]);

const ANDROID_AUTO_STUB_PACKAGES = new Set([
  "com.google.android.apps.maps",
  "com.google.android.googlequicksearchbox",
]);

const SYSTEM_EXCLUDE_PACKAGES = new Set([
  "com.google.android.webview",
  "com.android.webview",
]);

const pkgKey = (pkg) => String(pkg).replace(/\./g, "_");

function sanitizeSplitEntryName(splitName, usedNames) {
  const base = String(splitName || "split")
    .replace(/[^a-zA-Z0-9._-]/g, "_")
    .replace(/\.apk$/i, "")
    .replace(/^_+/, "") || "split";
  let candidate = `${base}.apk`;
  let i = 2;
  while (usedNames.has(candidate) || candidate === "base.apk") {
    candidate = `${base}_${i++}.apk`;
  }
  usedNames.add(candidate);
  return candidate;
}

function maskEmail(email) {
  const s = String(email || "").trim();
  const at = s.indexOf("@");
  if (at <= 1) return s ? "***" : "";
  return `${s.slice(0, 2)}***${s.slice(at)}`;
}

function maskSecretStatus(secret) {
  if (!secret || typeof secret !== "object") {
    return { configured: false, mode: null, emailMasked: "", hasAasToken: false, dispenserUrl: "", updatedAt: 0 };
  }
  return {
    configured: !!(
      (secret.mode === "dispenser" && secret.dispenserUrl) ||
      (secret.email && secret.aasToken)
    ),
    mode: secret.mode || null,
    emailMasked: secret.email ? maskEmail(secret.email) : "",
    hasAasToken: !!secret.aasToken,
    dispenserUrl: secret.mode === "dispenser" ? (secret.dispenserUrl || "") : "",
    updatedAt: secret.updatedAt || 0,
  };
}

async function createSessionsForSecret(secret, { fetchImpl = globalThis.fetch, gplayFactory = null } = {}) {
  if (!secret || typeof secret !== "object") {
    throw new Error("La conexión con Google Play para Tienda Aurora no está configurada.");
  }
  const { GPlayClient, fetchFromDispenser } = require("./aurora_gplay");
  const sessions = {};
  for (const abiKey of PROFILE_ORDER) {
    const profile = DEVICE_PROFILES[abiKey];
    let client;
    if (gplayFactory) {
      client = await gplayFactory(profile, secret);
    } else if (secret.mode === "dispenser") {
      if (!secret.dispenserUrl) throw new Error("Falta dispenserUrl en serverSecrets/aurora.");
      const creds = await fetchFromDispenser({ dispenserUrl: secret.dispenserUrl, profile, fetchImpl });
      client = new GPlayClient({
        profile,
        email: creds.email,
        aasToken: creds.aasToken,
        authToken: creds.authToken,
        fetchImpl,
      });
      await client.login();
    } else {
      if (!secret.email || !secret.aasToken) {
        throw new Error("Faltan email y aasToken en serverSecrets/aurora.");
      }
      client = new GPlayClient({
        profile,
        email: secret.email,
        aasToken: secret.aasToken,
        fetchImpl,
      });
      await client.login();
    }
    sessions[abiKey] = client;
  }
  return sessions;
}

async function fileSha256(filePath) {
  const hash = crypto.createHash("sha256");
  await new Promise((resolve, reject) => {
    const rs = fs.createReadStream(filePath);
    rs.on("data", (c) => hash.update(c));
    rs.on("end", resolve);
    rs.on("error", reject);
  });
  return hash.digest("hex");
}

async function buildVariantArtifact(client, pkg, det, workDir, abiKey) {
  const delivery = await client.getDelivery(pkg, det.versionCode);
  const maxMb = Math.round(MAX_APK_DOWNLOAD_BYTES / (1024 * 1024));
  const totalEstimated =
    (delivery.downloadSize || 0) +
    delivery.splits.reduce((acc, s) => acc + (s.downloadSize || 0), 0);
  if (totalEstimated > MAX_APK_DOWNLOAD_BYTES) {
    throw new Error(`El tamaño total supera el máximo permitido de ${maxMb} MB (${Math.round(totalEstimated / (1024 * 1024))} MB).`);
  }

  const subDir = path.join(workDir, `aurora_${abiKey}`);
  await fs.promises.mkdir(subDir, { recursive: true });
  const basePath = path.join(subDir, "base.apk");
  const baseDl = await client.downloadToFile(delivery.downloadUrl, delivery.cookies, basePath, {
    expectedSha256: delivery.sha256Hex,
    expectedSha1: delivery.sha1Hex,
  });

  let signerSha256 = null;
  try {
    signerSha256 = await apkInspect.extractApkSignerSha256(basePath);
  } catch (_) {}

  if (delivery.splits.length === 0) {
    return {
      abiKey,
      source: "aurora",
      filePath: basePath,
      bundleFormat: "apk",
      ext: "apk",
      splitCount: 0,
      sizeBytes: baseDl.sizeBytes,
      sha256: baseDl.sha256,
      signerSha256,
      versionCode: delivery.versionCode,
      versionName: det.versionName || "",
      title: det.title || pkg,
      abis: DEVICE_PROFILES[abiKey].abis,
      hasObb: !!delivery.hasObb,
    };
  }

  const entries = [{ name: "base.apk", filePath: basePath }];
  const used = new Set(["base.apk"]);
  let idx = 0;
  for (const sp of delivery.splits) {
    idx += 1;
    const entryName = sanitizeSplitEntryName(sp.name, used);
    const spPath = path.join(subDir, `split_${idx}.apk`);
    await client.downloadToFile(sp.downloadUrl, delivery.cookies, spPath, {
      expectedSha256: sp.sha256Hex,
      expectedSha1: sp.sha1Hex,
    });
    entries.push({ name: entryName, filePath: spPath });
  }

  const apksPath = path.join(subDir, `${abiKey}.apks`);
  const packed = await writeStoreZipToFile(entries, apksPath);
  await Promise.all(entries.map((e) => fs.promises.unlink(e.filePath).catch(() => {})));
  if (packed.sizeBytes > MAX_APK_DOWNLOAD_BYTES) {
    throw new Error(`El paquete .apks generado supera ${maxMb} MB (${Math.round(packed.sizeBytes / (1024 * 1024))} MB).`);
  }

  return {
    abiKey,
    source: "aurora",
    filePath: apksPath,
    bundleFormat: "apks",
    ext: "apks",
    splitCount: delivery.splits.length,
    sizeBytes: packed.sizeBytes,
    sha256: packed.sha256,
    signerSha256,
    versionCode: delivery.versionCode,
    versionName: det.versionName || "",
    title: det.title || pkg,
    abis: DEVICE_PROFILES[abiKey].abis,
    hasObb: !!delivery.hasObb,
  };
}

async function uploadToBucket(bucket, objectPath, localPath, ext) {
  const file = bucket.file(objectPath);
  const metadata = {
    contentType: ext === "apk" ? "application/vnd.android.package-archive" : "application/zip",
    cacheControl: "public, max-age=31536000, immutable",
  };
  await new Promise((resolve, reject) => {
    const rs = fs.createReadStream(localPath);
    const ws = file.createWriteStream({ resumable: true, metadata });
    rs.on("error", reject);
    ws.on("error", reject);
    ws.on("finish", resolve);
    rs.pipe(ws);
  });
  return `https://storage.googleapis.com/${bucket.name || STORE_APKS_BUCKET}/${objectPath}`;
}

async function deleteObsoleteObjects(bucket, prefix, keepPaths) {
  if (!bucket || typeof bucket.getFiles !== "function") return;
  try {
    const [files] = await bucket.getFiles({ prefix });
    for (const f of files || []) {
      if (!keepPaths.has(f.name)) {
        await f.delete({ ignoreNotFound: true });
      }
    }
  } catch (_) {
    // best effort cleanup
  }
}

function hasValidVariantPayload(v) {
  return !!(
    v &&
    typeof v.sha256 === "string" &&
    v.sha256.length === 64 &&
    typeof v.apkUrl === "string" &&
    v.apkUrl.length > 0 &&
    Number(v.versionCode) > 0
  );
}

function isExistingVariantUpToDate(existingNode, abiKey, remoteVc) {
  if (!existingNode || !existingNode.variants) return false;
  const v = existingNode.variants[abiKey] || existingNode.variants.universal;
  return !!(hasValidVariantPayload(v) && Number(v.versionCode) >= Number(remoteVc));
}

function storagePathFromVariant(v, key, variantKey) {
  if (v && typeof v.storagePath === "string" && v.storagePath) return v.storagePath;
  if (v && typeof v.apkUrl === "string") {
    const idx = v.apkUrl.indexOf(`aurora/${key}/`);
    if (idx >= 0) return v.apkUrl.slice(idx);
  }
  const ext = (v && v.bundleFormat) === "apk" ? "apk" : "apks";
  return `aurora/${key}/${v.versionCode}_${variantKey}.${ext}`;
}

/**
 * Sincroniza un paquete usando prioridad multi-fuente:
 *   APKPure -> F-Droid -> Aptoide -> Aurora (Play FDFE).
 */
async function syncSinglePackage({
  packageName,
  sessions = null,
  getSessions = null,
  db,
  bucket,
  force = false,
  now = Date.now(),
  mirrorProviders = storeSources.DEFAULT_MIRRORS,
  fetchImpl = globalThis.fetch,
}) {
  const key = pkgKey(packageName);
  const pkgRef = db.ref(`auroraStore/packages/${key}`);
  const existingSnap = await pkgRef.once("value");
  const existing = existingSnap.exists() ? existingSnap.val() : null;
  const existingVc = existing && existing.status === "ready" ? Number(existing.versionCode || 0) : 0;

  const mirrors = Array.isArray(mirrorProviders) ? mirrorProviders : [];
  const errorsBySource = {};
  const foundProbes = [];

  for (let i = 0; i < mirrors.length; i++) {
    const prov = mirrors[i];
    try {
      const pr = await prov.probe(packageName, fetchImpl, now);
      if (pr && pr.found) {
        foundProbes.push({ priority: i, provider: prov, probe: pr });
      } else if (pr && pr.error) {
        errorsBySource[prov.name] = pr.error;
      }
    } catch (e) {
      errorsBySource[prov.name] = e && e.message ? e.message : String(e);
    }
  }

  // Ordenar candidatos espejo: mayor versionCode primero; empate (o vc=0 sin cabecera) por prioridad
  foundProbes.sort((a, b) => {
    const av = Number(a.probe.versionCode || 0);
    const bv = Number(b.probe.versionCode || 0);
    if (av > 0 && bv > 0 && av !== bv) return bv - av;
    return a.priority - b.priority;
  });

  const knownMirrorVc = foundProbes.reduce((mx, x) => Math.max(mx, Number(x.probe.versionCode || 0)), 0);
  const knownMirrorVn = (foundProbes.find((x) => Number(x.probe.versionCode || 0) === knownMirrorVc) || {}).probe?.versionName || "";

  // Si un espejo ya cubrió ambas ABIs y coincide la versión cacheada en GCS (y no forzamos), salir sin bajar nada ni gastar cuota Aurora.
  if (!force && existing && existing.status === "ready" && existingVc > 0) {
    const coversAll =
      hasValidVariantPayload(existing.variants && existing.variants.universal) ||
      (hasValidVariantPayload(existing.variants && existing.variants.arm64_v8a) &&
        hasValidVariantPayload(existing.variants && existing.variants.armeabi_v7a));
    if (coversAll && mirrors.length > 0 && knownMirrorVc > 0 && knownMirrorVc <= existingVc) {
      return {
        packageName,
        ok: true,
        upToDate: true,
        source: existing.primarySource || (existing.variants.universal && existing.variants.universal.source) || "mirror",
        versionCode: existing.versionCode,
        versionName: existing.versionName,
      };
    }
  }

  const workDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "store-sync-"));
  try {
    const built = {};       // universal | arm64_v8a | armeabi_v7a
    const reused = {};      // variante existente en GCS reutilizable
    let highestMirrorBuiltVc = 0;

    for (const cand of foundProbes) {
      const prVc = Number(cand.probe.versionCode || 0);
      if (highestMirrorBuiltVc > 0 && prVc > 0 && prVc < highestMirrorBuiltVc) continue;
      if (!force && existingVc > 0 && prVc > 0 && prVc < existingVc) continue;

      // ¿Ya teníamos esta variante de esa misma versión en caché?
      const provName = cand.provider.name;
      try {
        const dl = await cand.provider.download(cand.probe, workDir, fetchImpl);
        if (!dl || !dl.sha256 || !dl.versionCode) {
          errorsBySource[provName] = "Artefacto descargado sin metadatos completos.";
          continue;
        }
        if (!force && existingVc > 0 && dl.versionCode < existingVc) {
          errorsBySource[provName] = `Versión ${dl.versionCode} anterior a la cacheada (${existingVc}).`;
          await fs.promises.unlink(dl.filePath).catch(() => {});
          continue;
        }
        if (dl.variantKey === "universal") {
          built.universal = dl;
          highestMirrorBuiltVc = Math.max(highestMirrorBuiltVc, dl.versionCode);
          break;
        }
        if (!built[dl.variantKey]) {
          built[dl.variantKey] = dl;
          highestMirrorBuiltVc = Math.max(highestMirrorBuiltVc, dl.versionCode);
        } else {
          await fs.promises.unlink(dl.filePath).catch(() => {});
        }
        if (built.arm64_v8a && built.armeabi_v7a) break;
      } catch (e) {
        errorsBySource[provName] = e && e.message ? e.message : String(e);
      }
    }

    // Si algún mirror nos dio p.ej. arm64_v8a y ya teníamos en GCS la armeabi_v7a de EXACTAMENTE la misma versión y firma, reutilizarla.
    if (!built.universal) {
      for (const abiKey of PROFILE_ORDER) {
        if (built[abiKey]) continue;
        const prevV = existing && existing.variants && existing.variants[abiKey];
        if (hasValidVariantPayload(prevV) && highestMirrorBuiltVc > 0 && Number(prevV.versionCode) === highestMirrorBuiltVc) {
          const other = built.arm64_v8a || built.armeabi_v7a;
          const sigCompatible = !other || !other.signerSha256 || !prevV.signerSha256 || other.signerSha256 === prevV.signerSha256;
          if (sigCompatible) {
            reused[abiKey] = prevV;
          }
        }
      }
    }

    // Determinar si necesitamos consultar a Aurora (fuente #4):
    //   - Si mirrors.length === 0 (modo Aurora directo / tests Aurora-only), siempre consultar Aurora.
    //   - Si no tenemos variante "universal" y falta cubrir arm64_v8a o armeabi_v7a.
    const missingAbis = built.universal
      ? []
      : PROFILE_ORDER.filter((abi) => !built[abi] && !reused[abi]);

    const needAuroraDetails =
      mirrors.length === 0 ||
      missingAbis.length > 0;

    let activeSessions = sessions;
    if (needAuroraDetails && !activeSessions && typeof getSessions === "function") {
      try {
        activeSessions = await getSessions();
      } catch (e) {
        errorsBySource.aurora = e && e.message ? e.message : String(e);
      }
    }

    const detailsByAbi = {};
    if (needAuroraDetails && activeSessions) {
      const targetAbis = mirrors.length === 0 ? PROFILE_ORDER : missingAbis;
      for (const abiKey of targetAbis) {
        const client = activeSessions[abiKey];
        if (!client) continue;
        try {
          detailsByAbi[abiKey] = await client.details(packageName);
        } catch (e) {
          errorsBySource.aurora = e && e.message ? e.message : String(e);
        }
      }

      // Modo clásico up-to-date cuando mirrors=[] y ya está todo al día en GCS
      if (!force && mirrors.length === 0 && existing && existing.status === "ready") {
        const detAbis = Object.keys(detailsByAbi);
        if (detAbis.length > 0 && detAbis.every((abi) => isExistingVariantUpToDate(existing, abi, detailsByAbi[abi].versionCode))) {
          return {
            packageName,
            ok: true,
            upToDate: true,
            source: existing.primarySource || "aurora",
            versionCode: existing.versionCode,
            versionName: existing.versionName,
          };
        }
      }

      for (const abiKey of Object.keys(detailsByAbi)) {
        const det = detailsByAbi[abiKey];
        if (!force && existing && existing.status === "ready" && isExistingVariantUpToDate(existing, abiKey, det.versionCode)) {
          const v = existing.variants[abiKey] || existing.variants.universal;
          if (hasValidVariantPayload(v) && abiKey !== "universal" && existing.variants[abiKey]) {
            reused[abiKey] = existing.variants[abiKey];
            continue;
          }
        }
        try {
          built[abiKey] = await buildVariantArtifact(activeSessions[abiKey], packageName, det, workDir, abiKey);
        } catch (e) {
          errorsBySource.aurora = e && e.message ? e.message : String(e);
        }
      }
    }

    const auroraMaxVc = Object.values(detailsByAbi).reduce((mx, d) => Math.max(mx, Number(d.versionCode || 0)), 0);
    const auroraVn = (Object.values(detailsByAbi).find((d) => Number(d.versionCode || 0) === auroraMaxVc) || {}).versionName || "";
    const latestKnownVc = Math.max(knownMirrorVc, auroraMaxVc, existingVc);
    const latestKnownVn = auroraMaxVc >= knownMirrorVc && auroraVn ? auroraVn : (knownMirrorVn || (existing && existing.versionName) || "");

    const builtKeys = Object.keys(built);
    const reusedKeys = Object.keys(reused);
    if (builtKeys.length === 0 && reusedKeys.length === 0) {
      const errMsg =
        errorsBySource.apkpure ||
        errorsBySource.aptoide ||
        errorsBySource.fdroid ||
        errorsBySource.aurora ||
        "No se pudo descargar el APK desde ningún servidor.";
      if (existing && existing.variants && Object.keys(existing.variants).length > 0) {
        const upd = { lastSyncError: errMsg, lastSyncAttemptAt: now };
        if (latestKnownVc > 0) {
          upd.latestVersionCode = latestKnownVc;
          if (latestKnownVn) upd.latestVersionName = latestKnownVn;
        }
        await pkgRef.update(upd);
      } else if (latestKnownVc > 0) {
        // Sin binario en servidor pero sabemos versión nueva -> permitir aviso y fallback a Google Play
        await pkgRef.set({
          packageName,
          title: (Object.values(detailsByAbi)[0] && Object.values(detailsByAbi)[0].title) || (existing && existing.title) || packageName,
          versionCode: latestKnownVc,
          latestVersionCode: latestKnownVc,
          versionName: latestKnownVn,
          latestVersionName: latestKnownVn,
          status: "play_fallback",
          error: errMsg,
          updatedAt: now,
        });
      } else {
        await pkgRef.set({
          packageName,
          title: (existing && existing.title) || packageName,
          status: "error",
          error: errMsg,
          updatedAt: now,
        });
      }
      return {
        packageName,
        ok: false,
        error: errMsg,
        errorsBySource,
        latestVersionCode: latestKnownVc,
        preservedPrevious: !!(existing && existing.variants),
      };
    }

    const variants = {};
    const keepPaths = new Set();

    if (built.universal) {
      const u = built.universal;
      const objectPath = `aurora/${key}/${u.versionCode}_universal.${u.ext}`;
      const apkUrl = await uploadToBucket(bucket, objectPath, u.filePath, u.ext);
      keepPaths.add(objectPath);
      variants.universal = {
        apkUrl,
        storagePath: objectPath,
        sha256: u.sha256,
        sizeBytes: u.sizeBytes,
        versionCode: u.versionCode,
        versionName: u.versionName,
        splitCount: u.splitCount,
        bundleFormat: u.bundleFormat,
        abis: u.abis && u.abis.length ? u.abis : ["arm64-v8a", "armeabi-v7a"],
        source: u.source || "mirror",
        ...(u.signerSha256 ? { signerSha256: u.signerSha256 } : {}),
      };
    } else if (
      built.arm64_v8a &&
      built.armeabi_v7a &&
      built.arm64_v8a.sha256 === built.armeabi_v7a.sha256 &&
      built.arm64_v8a.versionCode === built.armeabi_v7a.versionCode
    ) {
      const a64 = built.arm64_v8a;
      const objectPath = `aurora/${key}/${a64.versionCode}_universal.${a64.ext}`;
      const apkUrl = await uploadToBucket(bucket, objectPath, a64.filePath, a64.ext);
      keepPaths.add(objectPath);
      variants.universal = {
        apkUrl,
        storagePath: objectPath,
        sha256: a64.sha256,
        sizeBytes: a64.sizeBytes,
        versionCode: a64.versionCode,
        versionName: a64.versionName,
        splitCount: a64.splitCount,
        bundleFormat: a64.bundleFormat,
        abis: ["arm64-v8a", "armeabi-v7a"],
        source: a64.source || "aurora",
        ...(a64.signerSha256 ? { signerSha256: a64.signerSha256 } : {}),
      };
    } else {
      for (const abiKey of PROFILE_ORDER) {
        if (built[abiKey]) {
          const b = built[abiKey];
          const objectPath = `aurora/${key}/${b.versionCode}_${abiKey}.${b.ext}`;
          const apkUrl = await uploadToBucket(bucket, objectPath, b.filePath, b.ext);
          keepPaths.add(objectPath);
          variants[abiKey] = {
            apkUrl,
            storagePath: objectPath,
            sha256: b.sha256,
            sizeBytes: b.sizeBytes,
            versionCode: b.versionCode,
            versionName: b.versionName,
            splitCount: b.splitCount,
            bundleFormat: b.bundleFormat,
            abis: b.abis && b.abis.length ? b.abis : DEVICE_PROFILES[abiKey].abis,
            source: b.source || "aurora",
            ...(b.signerSha256 ? { signerSha256: b.signerSha256 } : {}),
          };
        } else if (reused[abiKey]) {
          const rv = reused[abiKey];
          const sp = storagePathFromVariant(rv, key, abiKey);
          keepPaths.add(sp);
          variants[abiKey] = { ...rv, storagePath: sp };
        }
      }
    }

    const variantValues = Object.values(variants);
    const primaryVariant = variants.universal || variants.arm64_v8a || variants.armeabi_v7a || variantValues[0];
    const primarySource = (primaryVariant && primaryVariant.source) || "aurora";
    const resolvedTitle =
      (built.universal && built.universal.title) ||
      (built.arm64_v8a && built.arm64_v8a.title) ||
      (built.armeabi_v7a && built.armeabi_v7a.title) ||
      (foundProbes[0] && foundProbes[0].probe.title) ||
      (existing && existing.title) ||
      packageName;

    const node = {
      packageName,
      title: resolvedTitle,
      versionCode: primaryVariant.versionCode,
      versionName: primaryVariant.versionName || "",
      latestVersionCode: Math.max(latestKnownVc, primaryVariant.versionCode),
      latestVersionName: latestKnownVn || primaryVariant.versionName || "",
      primarySource,
      status: "ready",
      updatedAt: now,
      hasObb: Object.values(built).some((b) => !!b.hasObb),
      variants,
    };
    await pkgRef.set(node);
    await deleteObsoleteObjects(bucket, `aurora/${key}/`, keepPaths);
    return {
      packageName,
      ok: true,
      upToDate: false,
      source: primarySource,
      versionCode: node.versionCode,
      versionName: node.versionName,
      variants: Object.keys(variants),
    };
  } finally {
    await fs.promises.rm(workDir, { recursive: true, force: true }).catch(() => {});
  }
}

async function deletePackageArtifacts({ packageName, db, bucket }) {
  const key = pkgKey(packageName);
  await db.ref(`auroraStore/packages/${key}`).remove();
  await deleteObsoleteObjects(bucket, `aurora/${key}/`, new Set());
}

/**
 * Recolecta todos los paquetes activos que el servidor sincroniza cada 48h:
 *   1. Apps activas (no bloqueadas) en globalSettings/playStore/apps
 *   2. Apps extras (no bloqueadas) en devices/<id>/playStoreExtras
 *   3. Android Auto (com.google.android.projection.gearhead) salvo que esté bloqueado
 *   4. Apps de usuario no bloqueadas ni críticas (y allowlist de sistema actualizables) instaladas en la flota
 */
async function collectCatalogPackages(
  db,
  neverOffered = new Set(),
  { includeFleetInstalled = true, maxFleetExtras = 80 } = {}
) {
  const catalogPkgs = new Set();
  const blockedPkgs = new Set();

  const [globalSnap, devicesSnap, wlCustomSnap] = await Promise.all([
    db.ref("globalSettings/playStore/apps").once("value"),
    db.ref("devices").once("value"),
    db.ref("globalSettings/whitelist/customApps").once("value"),
  ]);

  const globalApps = globalSnap.val() || {};
  for (const [k, entry] of Object.entries(globalApps)) {
    if (!entry || typeof entry !== "object") continue;
    const pkg = typeof entry.packageName === "string" && entry.packageName.trim() ? entry.packageName.trim() : k.replace(/_/g, ".");
    const status = String(entry.status || "enabled").trim().toLowerCase();
    if (status === "blocked") {
      blockedPkgs.add(pkg);
      continue;
    }
    if (PKG_RE.test(pkg) && !neverOffered.has(pkg)) catalogPkgs.add(pkg);
  }

  // Paquetes prohibidos globalmente en lista blanca nunca se pre-sincronizan para la flota como extras
  const wlCustom = wlCustomSnap.val() || {};
  const wlGloballyBlocked = new Set();
  for (const [k, entry] of Object.entries(wlCustom)) {
    if (entry && entry.state === "block") {
      wlGloballyBlocked.add(entry.packageName || k.replace(/_/g, "."));
    }
  }

  const devices = devicesSnap.val() || {};
  const fleetCounts = new Map();

  for (const dev of Object.values(devices)) {
    if (!dev || typeof dev !== "object") continue;

    const extras = dev.playStoreExtras;
    if (extras && typeof extras === "object") {
      for (const [k, entry] of Object.entries(extras)) {
        if (!entry || typeof entry !== "object") continue;
        const pkg = typeof entry.packageName === "string" && entry.packageName.trim() ? entry.packageName.trim() : k.replace(/_/g, ".");
        const status = String(entry.status || "enabled").trim().toLowerCase();
        if (status === "blocked") continue;
        if (PKG_RE.test(pkg) && !neverOffered.has(pkg)) catalogPkgs.add(pkg);
      }
    }

    if (includeFleetInstalled && dev.apps && typeof dev.apps === "object") {
      const seenInDev = new Set();
      for (const [k, app] of Object.entries(dev.apps)) {
        if (!app || typeof app !== "object") continue;
        const pkg = typeof app.packageName === "string" && app.packageName.trim()
          ? app.packageName.trim()
          : k.replace(/_/g, ".");
        if (!PKG_RE.test(pkg) || seenInDev.has(pkg)) continue;
        seenInDev.add(pkg);

        if (neverOffered.has(pkg) || ANDROID_AUTO_STUB_PACKAGES.has(pkg) || SYSTEM_EXCLUDE_PACKAGES.has(pkg)) continue;
        if (pkg.startsWith("com.google.android.trichromelibrary")) continue;
        if (blockedPkgs.has(pkg) || wlGloballyBlocked.has(pkg)) continue;
        if (app.isHidden || app.isSuspended || app.isCritical) continue;

        const isUserApp = app.appType === "Usuario";
        const isAllowedSys = UPDATABLE_SYSTEM_ALLOWLIST.has(pkg);
        if (!isUserApp && !isAllowedSys) continue;

        fleetCounts.set(pkg, (fleetCounts.get(pkg) || 0) + 1);
      }
    }
  }

  // Android Auto disponible universalmente salvo bloqueo explícito global
  if (!neverOffered.has(ANDROID_AUTO_PKG) && !blockedPkgs.has(ANDROID_AUTO_PKG) && !wlGloballyBlocked.has(ANDROID_AUTO_PKG)) {
    catalogPkgs.add(ANDROID_AUTO_PKG);
  }

  const primarySorted = Array.from(catalogPkgs).sort();
  if (!includeFleetInstalled || fleetCounts.size === 0) {
    return primarySorted;
  }

  const fleetExtras = Array.from(fleetCounts.entries())
    .filter(([pkg]) => !catalogPkgs.has(pkg))
    .sort((a, b) => (b[1] - a[1]) || a[0].localeCompare(b[0]))
    .slice(0, Math.max(0, maxFleetExtras))
    .map(([pkg]) => pkg);

  return primarySorted.concat(fleetExtras);
}

module.exports = {
  STORE_APKS_BUCKET,
  PROFILE_ORDER,
  ANDROID_AUTO_PKG,
  UPDATABLE_SYSTEM_ALLOWLIST,
  ANDROID_AUTO_STUB_PACKAGES,
  pkgKey,
  sanitizeSplitEntryName,
  maskEmail,
  maskSecretStatus,
  createSessionsForSecret,
  fileSha256,
  syncSinglePackage,
  deletePackageArtifacts,
  collectCatalogPackages,
};
