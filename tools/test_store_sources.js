#!/usr/bin/env node
"use strict";
/**
 * Pruebas unitarias offline para la sincronización multi-fuente (B.134):
 *   - apk_inspect_node.js (certificado SHA-256 sobre stubs reales + clasificación ABI real arm64 vs universal)
 *   - store_sources.js (APKPure winudf parser + F-Droid probe + filtros estrictos anti-fake/malware de Aptoide)
 *   - aurora_sync.js (cadena APKPure -> F-Droid -> Aptoide -> Aurora, arm64-only fallback 32-bit, collectCatalogPackages flota + Android Auto)
 *   - index.js (frecuencia programada cada 48 horas)
 *
 * Uso: node tools/test_store_sources.js
 */
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { Writable } = require("stream");

const ROOT = path.join(__dirname, "..");
const FN_DIR = path.join(ROOT, "admin-backend", "functions");
const apkInspect = require(path.join(FN_DIR, "apk_inspect_node.js"));
const storeSources = require(path.join(FN_DIR, "store_sources.js"));
const auroraSync = require(path.join(FN_DIR, "aurora_sync.js"));
const auroraZip = require(path.join(FN_DIR, "aurora_zip.js"));

let pass = 0;
const pending = [];
function test(name, fn) {
  pending.push(async () => {
    try {
      await fn();
      pass++;
    } catch (e) {
      console.error(`✗ ${name}\n  ${(e && e.stack) || e}`);
      process.exitCode = 1;
    }
  });
}

function makeDb(initial = {}) {
  const dbRoot = JSON.parse(JSON.stringify(initial));
  return {
    root: dbRoot,
    ref(p) {
      const segs = p.split("/").filter(Boolean);
      const getAt = () => segs.reduce((acc, s) => (acc && typeof acc === "object" ? acc[s] : null), dbRoot);
      const setAt = (val) => {
        let cur = dbRoot;
        for (let i = 0; i < segs.length - 1; i++) {
          if (!cur[segs[i]] || typeof cur[segs[i]] !== "object") cur[segs[i]] = {};
          cur = cur[segs[i]];
        }
        if (val === null) delete cur[segs[segs.length - 1]];
        else cur[segs[segs.length - 1]] = JSON.parse(JSON.stringify(val));
      };
      return {
        async once() {
          const v = getAt();
          return { exists: () => v !== null && v !== undefined, val: () => (v == null ? null : JSON.parse(JSON.stringify(v))) };
        },
        async set(v) { setAt(v); },
        async update(obj) {
          const cur = getAt() || {};
          Object.assign(cur, obj);
          setAt(cur);
        },
        async remove() { setAt(null); },
      };
    },
  };
}

function makeBucket(gcsObjects = {}) {
  return {
    name: auroraSync.STORE_APKS_BUCKET,
    objects: gcsObjects,
    file(objectPath) {
      return {
        name: objectPath,
        createWriteStream() {
          const chunks = [];
          return new Writable({
            write(chunk, _enc, cb) { chunks.push(chunk); cb(); },
            final(cb) { gcsObjects[objectPath] = Buffer.concat(chunks); cb(); },
          });
        },
        async delete() { delete gcsObjects[objectPath]; },
      };
    },
    async getFiles({ prefix }) {
      const list = Object.keys(gcsObjects)
        .filter((k) => k.startsWith(prefix))
        .map((name) => ({ name, async delete() { delete gcsObjects[name]; } }));
      return [list];
    },
  };
}

test("apk_inspect_node extracts exact cert SHA-256 and manifest on real Android Auto stub APKs", async () => {
  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "inspect-"));
  try {
    const maps = await apkInspect.inspectAndRepackArtifact(
      path.join(ROOT, "tools/aa_stubs/apk/aa-stub-maps.apk"),
      "com.google.android.apps.maps",
      dir
    );
    assert.strictEqual(maps.packageName, "com.google.android.apps.maps");
    assert.strictEqual(maps.versionCode, 2100000000);
    assert.strictEqual(maps.variantKey, "universal");
    assert.strictEqual(maps.signerSha256, "62786d1c3bfaa36cac1d71ec943227c2ace1f622c805c9affc44e39f81bf52de");

    const google = await apkInspect.inspectAndRepackArtifact(
      path.join(ROOT, "tools/aa_stubs/apk/aa-stub-google.apk"),
      "com.google.android.googlequicksearchbox",
      dir
    );
    assert.strictEqual(google.signerSha256, "62786d1c3bfaa36cac1d71ec943227c2ace1f622c805c9affc44e39f81bf52de");
    assert.strictEqual(google.sha256, "d81901b6c544df6a2e16cc3e8c96cd83677225c5073eefb64ff2331666bb6152");
  } finally {
    await fs.promises.rm(dir, { recursive: true, force: true });
  }
});

test("parseWinudfUrlInfo extracts package versionCode and filename versionName", () => {
  const token = Buffer.from("com.whatsapp_264608200_1234abcd").toString("base64");
  const info = storeSources.parseWinudfUrlInfo(
    `https://data.winudf.com/b/XAPK/${token}`,
    "com.whatsapp",
    'attachment; filename="WhatsApp_2.26.46.82_APKPure.xapk"'
  );
  assert.strictEqual(info.versionCode, 264608200);
  assert.strictEqual(info.versionName, "2.26.46.82");
});

test("validateAptoideMeta enforces TRUSTED malware rank, sane vercode, and age", () => {
  const now = Date.UTC(2026, 10, 27);
  const ok = storeSources.validateAptoideMeta({
    info: { status: "OK" },
    data: {
      package: "com.waze",
      name: "Waze",
      file: {
        vercode: 1030752,
        vername: "5.28.0.0",
        added: "2026-11-20 12:00:00",
        path: "https://pool.apk.aptoide.com/repo/com-waze.apk",
        md5sum: "abc123",
        malware: { rank: "TRUSTED" },
      },
    },
  }, "com.waze", now);
  assert.strictEqual(ok.ok, true);
  assert.strictEqual(ok.versionCode, 1030752);

  // Reject bogus 999999999
  const bogus = storeSources.validateAptoideMeta({
    info: { status: "OK" },
    data: {
      package: "com.google.android.inputmethod.latin",
      file: {
        vercode: 999999999,
        vername: "899.9999.999",
        added: "2026-11-20 12:00:00",
        path: "https://pool.apk.aptoide.com/repo/gboard.apk",
        malware: { rank: "TRUSTED" },
      },
    },
  }, "com.google.android.inputmethod.latin", now);
  assert.strictEqual(bogus.ok, false);

  // Reject WARNING / UNKNOWN malware rank
  const warn = storeSources.validateAptoideMeta({
    info: { status: "OK" },
    data: {
      package: "net.osmand",
      file: {
        vercode: 5411,
        vername: "5.4.11",
        added: "2026-11-01 00:00:00",
        path: "https://pool.apk.aptoide.com/repo/osmand.apk",
        malware: { rank: "WARNING" },
      },
    },
  }, "net.osmand", now);
  assert.strictEqual(warn.ok, false);

  // Reject too-old build (>3 years)
  const old = storeSources.validateAptoideMeta({
    info: { status: "OK" },
    data: {
      package: "com.old.app",
      file: {
        vercode: 10,
        vername: "1.0",
        added: "2018-01-01 00:00:00",
        path: "https://pool.apk.aptoide.com/repo/old.apk",
        malware: { rank: "TRUSTED" },
      },
    },
  }, "com.old.app", now);
  assert.strictEqual(old.ok, false);
});

test("multi-source priority uses APKPure universal without touching Aurora", async () => {
  const db = makeDb();
  const bucket = makeBucket();
  let auroraCalled = false;

  const mirrorProviders = [
    {
      name: "apkpure",
      async probe(pkg) {
        return { found: true, source: "apkpure", packageName: pkg, versionCode: 500, versionName: "5.0" };
      },
      async download(_pr, workDir) {
        const fp = path.join(workDir, "app.apk");
        await fs.promises.writeFile(fp, Buffer.from("UNIVERSAL-APKPURE-DATA"));
        return {
          source: "apkpure",
          title: "Mi App",
          versionCode: 500,
          versionName: "5.0",
          variantKey: "universal",
          abis: ["arm64-v8a", "armeabi-v7a"],
          bundleFormat: "apk",
          ext: "apk",
          splitCount: 0,
          sizeBytes: 22,
          sha256: crypto.createHash("sha256").update("UNIVERSAL-APKPURE-DATA").digest("hex"),
          signerSha256: "f".repeat(64),
          filePath: fp,
          hasObb: false,
        };
      },
    },
  ];

  const r = await auroraSync.syncSinglePackage({
    packageName: "com.miapp.test",
    getSessions: async () => { auroraCalled = true; throw new Error("should not call aurora"); },
    db,
    bucket,
    mirrorProviders,
  });

  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.source, "apkpure");
  assert.strictEqual(auroraCalled, false);
  const saved = db.root.auroraStore.packages.com_miapp_test;
  assert.strictEqual(saved.primarySource, "apkpure");
  assert.strictEqual(saved.variants.universal.source, "apkpure");
  assert.strictEqual(saved.variants.universal.signerSha256, "f".repeat(64));
});

test("arm64-only APKPure never lies as universal and triggers Aurora ONLY for 32-bit armeabi_v7a", async () => {
  const db = makeDb();
  const bucket = makeBucket();
  const auroraAbisCalled = [];

  const mirrorProviders = [
    {
      name: "apkpure",
      async probe(pkg) {
        return { found: true, source: "apkpure", packageName: pkg, versionCode: 900, versionName: "9.0" };
      },
      async download(_pr, workDir) {
        const fp = path.join(workDir, "arm64.apks");
        await fs.promises.writeFile(fp, Buffer.from("ARM64-ONLY-APKPURE"));
        return {
          source: "apkpure",
          title: "Android Auto",
          versionCode: 900,
          versionName: "9.0",
          variantKey: "arm64_v8a",
          abis: ["arm64-v8a"],
          bundleFormat: "apks",
          ext: "apks",
          splitCount: 1,
          sizeBytes: 18,
          sha256: crypto.createHash("sha256").update("ARM64-ONLY-APKPURE").digest("hex"),
          signerSha256: "e".repeat(64),
          filePath: fp,
          hasObb: false,
        };
      },
    },
  ];

  const mock32 = {
    async details(pkg) { return { packageName: pkg, title: "Android Auto", versionCode: 900, versionName: "9.0" }; },
    async getDelivery(pkg, vc) {
      auroraAbisCalled.push("armeabi_v7a");
      return {
        versionCode: vc,
        downloadUrl: "dl://base32",
        downloadSize: 10,
        sha256Hex: "",
        sha1Hex: "",
        cookies: [],
        splits: [],
        hasObb: false,
      };
    },
    async downloadToFile(_u, _c, dest) {
      const b = Buffer.from("AURORA-32");
      await fs.promises.writeFile(dest, b);
      return { sizeBytes: b.length, sha256: crypto.createHash("sha256").update(b).digest("hex"), sha1: "" };
    },
  };

  const r = await auroraSync.syncSinglePackage({
    packageName: "com.google.android.projection.gearhead",
    sessions: {
      arm64_v8a: { details: async () => { throw new Error("arm64 must NOT hit Aurora"); } },
      armeabi_v7a: mock32,
    },
    db,
    bucket,
    mirrorProviders,
  });

  assert.strictEqual(r.ok, true);
  assert.deepStrictEqual(auroraAbisCalled, ["armeabi_v7a"]);
  const saved = db.root.auroraStore.packages.com_google_android_projection_gearhead;
  assert.strictEqual(saved.variants.universal, undefined);
  assert.strictEqual(saved.variants.arm64_v8a.source, "apkpure");
  assert.strictEqual(saved.variants.armeabi_v7a.source, "aurora");
});

test("collectCatalogPackages includes catalog, Android Auto, fleet user apps, safe system allowlist; excludes GMS/blocked/stubs", async () => {
  const db = makeDb({
    globalSettings: {
      playStore: {
        apps: {
          com_waze: { packageName: "com.waze", label: "Waze", tier: 1, status: "enabled" },
          com_bad_blocked: { packageName: "com.bad.blocked", label: "Bad", tier: 1, status: "blocked" },
        },
      },
      whitelist: {
        customApps: {
          com_wl_blocked: { packageName: "com.wl.blocked", state: "block" },
        },
      },
    },
    devices: {
      dev1: {
        playStoreExtras: {
          com_extra_ok: { packageName: "com.extra.ok", label: "Extra", status: "enabled" },
        },
        apps: {
          com_whatsapp: { packageName: "com.whatsapp", appType: "Usuario", isHidden: false, isSuspended: false, isCritical: false },
          com_wl_blocked: { packageName: "com.wl.blocked", appType: "Usuario", isHidden: false, isSuspended: false, isCritical: false },
          com_hidden_app: { packageName: "com.hidden.app", appType: "Usuario", isHidden: true, isSuspended: false, isCritical: false },
          com_google_android_gms: { packageName: "com.google.android.gms", appType: "Sistema", isHidden: false, isSuspended: false, isCritical: true },
          com_google_android_apps_maps: { packageName: "com.google.android.apps.maps", appType: "Usuario", isHidden: false, isSuspended: false, isCritical: true },
          com_google_android_inputmethod_latin: { packageName: "com.google.android.inputmethod.latin", appType: "Preinstalada", isHidden: false, isSuspended: false, isCritical: false },
          com_samsung_sys_junk: { packageName: "com.samsung.sys.junk", appType: "Sistema", isHidden: false, isSuspended: false, isCritical: false },
        },
      },
    },
  });

  const neverOffered = new Set(["com.google.android.gms", "com.android.vending", "com.ejemplo.locksuite"]);
  const list = await auroraSync.collectCatalogPackages(db, neverOffered);

  assert.ok(list.includes("com.waze"));
  assert.ok(list.includes("com.extra.ok"));
  assert.ok(list.includes("com.google.android.projection.gearhead"));
  assert.ok(list.includes("com.whatsapp"));
  assert.ok(list.includes("com.google.android.inputmethod.latin"));

  assert.ok(!list.includes("com.bad.blocked"));
  assert.ok(!list.includes("com.wl.blocked"));
  assert.ok(!list.includes("com.hidden.app"));
  assert.ok(!list.includes("com.google.android.gms"));
  assert.ok(!list.includes("com.google.android.apps.maps"));
  assert.ok(!list.includes("com.samsung.sys.junk"));
});

test("auroraSyncCatalogScheduled uses every 48 hours schedule", () => {
  const indexSrc = fs.readFileSync(path.join(FN_DIR, "index.js"), "utf8");
  assert.ok(indexSrc.includes('schedule: "every 48 hours"'), "Cron debe ser cada 48 horas");
});

(async () => {
  for (const fn of pending) await fn();
  if (!process.exitCode) console.log(`✅ test_store_sources: ${pass} pruebas pasaron.`);
})();
