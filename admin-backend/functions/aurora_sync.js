"use strict";

/**
 * Orquestador de sincronización Tienda Aurora:
 *   - Autentica sesiones Play FDFE por perfil ABI (arm64_v8a y armeabi_v7a)
 *   - Descarga APKs / Split APKs oficiales, empaqueta en .apks (ZIP STORE) cuando hay splits
 *   - Deduplica variantes idénticas en `universal`
 *   - Sube a GCS (`locksuite-nueva-store-apks` bajo `aurora/<pkg_>/`) y actualiza `auroraStore/packages/<pkg_>`
 *   - Borra artefactos obsoletos de versiones previas
 */
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { DEVICE_PROFILES } = require("./aurora_devices");
const { GPlayClient, fetchFromDispenser, MAX_APK_DOWNLOAD_BYTES } = require("./aurora_gplay");
const { writeStoreZipToFile } = require("./aurora_zip");

const STORE_APKS_BUCKET = "locksuite-nueva-store-apks";
const PROFILE_ORDER = ["arm64_v8a", "armeabi_v7a"];
const PKG_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;

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

  const subDir = path.join(workDir, abiKey);
  await fs.promises.mkdir(subDir, { recursive: true });
  const basePath = path.join(subDir, "base.apk");
  const baseDl = await client.downloadToFile(delivery.downloadUrl, delivery.cookies, basePath, {
    expectedSha256: delivery.sha256Hex,
    expectedSha1: delivery.sha1Hex,
  });

  if (delivery.splits.length === 0) {
    return {
      abiKey,
      filePath: basePath,
      bundleFormat: "apk",
      ext: "apk",
      splitCount: 0,
      sizeBytes: baseDl.sizeBytes,
      sha256: baseDl.sha256,
      versionCode: delivery.versionCode,
      versionName: det.versionName || "",
      title: det.title || pkg,
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
  // Liberar de inmediato los splits crudos en /tmp (en Cloud Run viven en RAM).
  await Promise.all(entries.map((e) => fs.promises.unlink(e.filePath).catch(() => {})));
  if (packed.sizeBytes > MAX_APK_DOWNLOAD_BYTES) {
    throw new Error(`El paquete .apks generado supera ${maxMb} MB (${Math.round(packed.sizeBytes / (1024 * 1024))} MB).`);
  }

  return {
    abiKey,
    filePath: apksPath,
    bundleFormat: "apks",
    ext: "apks",
    splitCount: delivery.splits.length,
    sizeBytes: packed.sizeBytes,
    sha256: packed.sha256,
    versionCode: delivery.versionCode,
    versionName: det.versionName || "",
    title: det.title || pkg,
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
    // El bucket locksuite-nueva-store-apks tiene Uniform Bucket-Level Access (ya es público por IAM):
    // no pasar `public: true` ni `predefinedAcl` o GCS rechaza con HTTP 400.
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

function isExistingVariantUpToDate(existingNode, abiKey, remoteVc) {
  if (!existingNode || !existingNode.variants) return false;
  const v = existingNode.variants[abiKey] || existingNode.variants.universal;
  return !!(
    v &&
    Number(v.versionCode) === Number(remoteVc) &&
    typeof v.sha256 === "string" &&
    v.sha256.length === 64 &&
    typeof v.apkUrl === "string" &&
    v.apkUrl.length > 0
  );
}

/**
 * Sincroniza un paquete específico contra Google Play -> GCS -> auroraStore/packages/<pkg_>.
 */
async function syncSinglePackage({
  packageName,
  sessions,
  db,
  bucket,
  force = false,
  now = Date.now(),
}) {
  const key = pkgKey(packageName);
  const pkgRef = db.ref(`auroraStore/packages/${key}`);
  const existingSnap = await pkgRef.once("value");
  const existing = existingSnap.exists() ? existingSnap.val() : null;

  const detailsByAbi = {};
  const errorsByAbi = {};

  for (const abiKey of PROFILE_ORDER) {
    const client = sessions[abiKey];
    if (!client) continue;
    try {
      detailsByAbi[abiKey] = await client.details(packageName);
    } catch (e) {
      errorsByAbi[abiKey] = e && e.message ? e.message : String(e);
    }
  }

  const availableAbis = Object.keys(detailsByAbi);
  if (availableAbis.length === 0) {
    const errMsg = errorsByAbi.arm64_v8a || errorsByAbi.armeabi_v7a || "La app no está disponible en Google Play.";
    if (existing && existing.variants && Object.keys(existing.variants).length > 0) {
      await pkgRef.update({ lastSyncError: errMsg, lastSyncAttemptAt: now });
    } else {
      await pkgRef.set({
        packageName,
        title: (existing && existing.title) || packageName,
        status: "error",
        error: errMsg,
        updatedAt: now,
      });
    }
    return { packageName, ok: false, error: errMsg, preservedPrevious: !!(existing && existing.variants) };
  }

  if (!force && existing && existing.status === "ready") {
    const allMatch = availableAbis.every((abi) => isExistingVariantUpToDate(existing, abi, detailsByAbi[abi].versionCode));
    if (allMatch) {
      return {
        packageName,
        ok: true,
        upToDate: true,
        versionCode: existing.versionCode,
        versionName: existing.versionName,
      };
    }
  }

  const workDir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "aurora-sync-"));
  try {
    const built = {};
    for (const abiKey of availableAbis) {
      try {
        built[abiKey] = await buildVariantArtifact(sessions[abiKey], packageName, detailsByAbi[abiKey], workDir, abiKey);
      } catch (e) {
        errorsByAbi[abiKey] = e && e.message ? e.message : String(e);
      }
    }

    const builtAbis = Object.keys(built);
    if (builtAbis.length === 0) {
      const errMsg = errorsByAbi.arm64_v8a || errorsByAbi.armeabi_v7a || "No se pudo descargar el APK desde Google Play.";
      if (existing && existing.variants && Object.keys(existing.variants).length > 0) {
        await pkgRef.update({ lastSyncError: errMsg, lastSyncAttemptAt: now });
      } else {
        await pkgRef.set({
          packageName,
          title: (detailsByAbi[availableAbis[0]] && detailsByAbi[availableAbis[0]].title) || packageName,
          status: "error",
          error: errMsg,
          updatedAt: now,
        });
      }
      return { packageName, ok: false, error: errMsg, preservedPrevious: !!(existing && existing.variants) };
    }

    const variants = {};
    const keepPaths = new Set();

    const a64 = built.arm64_v8a;
    const a32 = built.armeabi_v7a;
    if (a64 && a32 && a64.sha256 === a32.sha256 && a64.versionCode === a32.versionCode) {
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
      };
    } else {
      for (const abiKey of builtAbis) {
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
          abis: DEVICE_PROFILES[abiKey].abis,
        };
      }
    }

    const primary = built.arm64_v8a || built.armeabi_v7a;
    const node = {
      packageName,
      title: primary.title || packageName,
      versionCode: primary.versionCode,
      versionName: primary.versionName,
      status: "ready",
      updatedAt: now,
      hasObb: Object.values(built).some((b) => b.hasObb),
      variants,
    };
    await pkgRef.set(node);
    await deleteObsoleteObjects(bucket, `aurora/${key}/`, keepPaths);
    return {
      packageName,
      ok: true,
      upToDate: false,
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
 * Recolecta todos los paquetes activos (no bloqueados) en globalSettings/playStore/apps
 * y en devices/<id>/playStoreExtras.
 */
async function collectCatalogPackages(db, neverOffered = new Set()) {
  const pkgs = new Set();
  const [globalSnap, devicesSnap] = await Promise.all([
    db.ref("globalSettings/playStore/apps").once("value"),
    db.ref("devices").once("value"),
  ]);

  const globalApps = globalSnap.val() || {};
  for (const [k, entry] of Object.entries(globalApps)) {
    if (!entry || typeof entry !== "object") continue;
    const pkg = typeof entry.packageName === "string" && entry.packageName.trim() ? entry.packageName.trim() : k.replace(/_/g, ".");
    const status = String(entry.status || "enabled").trim().toLowerCase();
    if (status === "blocked") continue;
    if (PKG_RE.test(pkg) && !neverOffered.has(pkg)) pkgs.add(pkg);
  }

  const devices = devicesSnap.val() || {};
  for (const dev of Object.values(devices)) {
    const extras = dev && dev.playStoreExtras;
    if (!extras || typeof extras !== "object") continue;
    for (const [k, entry] of Object.entries(extras)) {
      if (!entry || typeof entry !== "object") continue;
      const pkg = typeof entry.packageName === "string" && entry.packageName.trim() ? entry.packageName.trim() : k.replace(/_/g, ".");
      const status = String(entry.status || "enabled").trim().toLowerCase();
      if (status === "blocked") continue;
      if (PKG_RE.test(pkg) && !neverOffered.has(pkg)) pkgs.add(pkg);
    }
  }

  return Array.from(pkgs).sort();
}

module.exports = {
  STORE_APKS_BUCKET,
  PROFILE_ORDER,
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
