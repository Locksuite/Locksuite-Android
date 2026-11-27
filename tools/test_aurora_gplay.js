#!/usr/bin/env node
"use strict";
/**
 * Pruebas unitarias offline para los módulos de Tienda Aurora en el backend:
 *   - aurora_proto.js (roundtrip Protobuf + unsigned 64-bit GSF ID)
 *   - aurora_zip.js   (empaquetado .apks STORE + CRC-32 + SHA-256)
 *   - aurora_gplay.js (OAuth->AAS, Dispenser SSRF, checkin, login+ToS, details, purchase/delivery, descarga+SHA-256)
 *   - aurora_sync.js  (deduplicación universal vs multi-ABI, skip si al día, limpieza GCS, preservación ante fallo)
 *
 * Uso: node tools/test_aurora_gplay.js
 */
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { Writable } = require("stream");

const FN_DIR = path.join(__dirname, "..", "admin-backend", "functions");
const proto = require(path.join(FN_DIR, "aurora_proto.js"));
const { DEVICE_PROFILES, buildUserAgent } = require(path.join(FN_DIR, "aurora_devices.js"));
const auroraZip = require(path.join(FN_DIR, "aurora_zip.js"));
const auroraGPlay = require(path.join(FN_DIR, "aurora_gplay.js"));
const auroraSync = require(path.join(FN_DIR, "aurora_sync.js"));

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

test("proto roundtrip AndroidCheckinResponse with high-bit fixed64 unsigned hex", () => {
  const unsigned = "9223372036854775809"; // 2^63 + 1 = 0x8000000000000001
  const buf = proto.encode("AndroidCheckinResponse", {
    statsOk: true,
    marketOk: true,
    androidId: unsigned,
    securityToken: "123456789",
    deviceCheckinConsistencyToken: "cons-1",
  });
  const dec = proto.decode("AndroidCheckinResponse", buf);
  assert.strictEqual(proto.longToUnsignedHex(dec.androidId), "8000000000000001");
  assert.strictEqual(dec.deviceCheckinConsistencyToken, "cons-1");
});

test("proto roundtrip ResponseWrapper Details + Delivery", () => {
  const sha256B64 = Buffer.from("a".repeat(64), "hex").toString("base64url");
  const buf = proto.encode("ResponseWrapper", {
    payload: {
      deliveryResponse: {
        status: 1,
        appDeliveryData: {
          downloadSize: "1024",
          downloadUrl: "https://cdn.example/base.apk",
          sha256: sha256B64,
          downloadAuthCookie: [{ name: "NID", value: "abc" }],
          splitDeliveryData: [
            { name: "config.arm64_v8a", downloadSize: "512", downloadUrl: "https://cdn.example/s1.apk", sha256: sha256B64 },
          ],
        },
      },
    },
  });
  const dec = proto.decode("ResponseWrapper", buf);
  const dd = dec.payload.deliveryResponse.appDeliveryData;
  assert.strictEqual(dd.downloadUrl, "https://cdn.example/base.apk");
  assert.strictEqual(dd.splitDeliveryData.length, 1);
  assert.strictEqual(dd.splitDeliveryData[0].name, "config.arm64_v8a");
  assert.strictEqual(auroraGPlay.decodeHashToHex(dd.sha256), "a".repeat(64));
});

test("validateDispenserUrl blocks SSRF / non-https / private IPs and accepts valid https", () => {
  assert.strictEqual(auroraGPlay.validateDispenserUrl("https://disp.example.org/token"), "https://disp.example.org/token");
  for (const bad of [
    "http://disp.example.org/token",
    "https://127.0.0.1/auth",
    "https://169.254.169.254/latest/meta-data",
    "https://10.0.0.5/auth",
    "https://192.168.1.1/auth",
    "https://localhost/auth",
    "https://user:pass@disp.example.org/auth",
    "https://disp.example.org:8443/auth",
  ]) {
    assert.throws(() => auroraGPlay.validateDispenserUrl(bad), bad);
  }
});

test("aurora_zip builds valid STORE zip file and matches SHA-256", async () => {
  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "aurora-zip-"));
  try {
    const f1 = path.join(dir, "base.apk");
    const f2 = path.join(dir, "split.apk");
    const d1 = Buffer.from("PK\x03\x04dummy-base-content-" + "x".repeat(500));
    const d2 = Buffer.from("PK\x03\x04dummy-split-content-" + "y".repeat(300));
    await fs.promises.writeFile(f1, d1);
    await fs.promises.writeFile(f2, d2);
    const outZip = path.join(dir, "bundle.apks");
    const res = await auroraZip.writeStoreZipToFile(
      [{ name: "base.apk", filePath: f1 }, { name: "config.arm64_v8a.apk", filePath: f2 }],
      outZip
    );
    const raw = await fs.promises.readFile(outZip);
    assert.strictEqual(res.sizeBytes, raw.length);
    assert.strictEqual(raw.readUInt32LE(0), 0x04034b50);
    assert.strictEqual(crypto.createHash("sha256").update(raw).digest("hex"), res.sha256);
    assert.ok(raw.includes(Buffer.from("base.apk")));
    assert.ok(raw.includes(Buffer.from("config.arm64_v8a.apk")));
  } finally {
    await fs.promises.rm(dir, { recursive: true, force: true });
  }
});

test("GPlayClient full flow: exchangeOAuthForAas -> login (with acceptTos) -> details -> delivery -> downloadToFile", async () => {
  const apkBytes = Buffer.from("APK-REAL-DATA-0123456789");
  const apkSha256Hex = crypto.createHash("sha256").update(apkBytes).digest("hex");
  const apkSha256B64 = Buffer.from(apkSha256Hex, "hex").toString("base64url");
  let tocCalls = 0;
  let acceptTosSeen = false;
  let cookieSeenOnDownload = "";

  const mockFetch = async (url, init = {}) => {
    const u = String(url);
    if (u === "https://android.clients.google.com/auth") {
      return { ok: true, status: 200, text: async () => "Token=aas_et/TESTAAS\nEmail=b@example.com\n" };
    }
    if (u.startsWith("https://android.clients.google.com/auth?")) {
      assert.ok(u.includes("Token=aas_et%2FTESTAAS") || u.includes("Token=aas_et/TESTAAS"));
      return { ok: true, status: 200, text: async () => "Auth=ya29.TESTAUTH\n" };
    }
    if (u === "https://android.clients.google.com/checkin") {
      const buf = proto.encode("AndroidCheckinResponse", {
        statsOk: true,
        marketOk: true,
        androidId: "3987654321012345",
        deviceCheckinConsistencyToken: "chk-tok-1",
      });
      return { ok: true, status: 200, arrayBuffer: async () => buf };
    }
    if (u === "https://android.clients.google.com/fdfe/uploadDeviceConfig") {
      const buf = proto.encode("ResponseWrapper", {
        payload: { uploadDeviceConfigResponse: { uploadDeviceConfigToken: "udc-tok-1" } },
      });
      return { ok: true, status: 200, arrayBuffer: async () => buf };
    }
    if (u === "https://android.clients.google.com/fdfe/toc") {
      tocCalls++;
      const buf = tocCalls === 1
        ? proto.encode("ResponseWrapper", { payload: { tocResponse: { tosToken: "tos-1", tosContent: "Accept?" } } })
        : proto.encode("ResponseWrapper", { payload: { tocResponse: { cookie: "dfe-cookie-1" } } });
      return { ok: true, status: 200, arrayBuffer: async () => buf };
    }
    if (u === "https://android.clients.google.com/fdfe/acceptTos") {
      acceptTosSeen = true;
      return { ok: true, status: 200, arrayBuffer: async () => proto.encode("ResponseWrapper", { payload: { acceptTosResponse: {} } }) };
    }
    if (u.startsWith("https://android.clients.google.com/fdfe/details?")) {
      const buf = proto.encode("ResponseWrapper", {
        payload: {
          detailsResponse: {
            item: {
              id: "com.waze",
              title: "Waze",
              details: { appDetails: { packageName: "com.waze", versionCode: "1022555", versionString: "4.108.0" } },
            },
          },
        },
      });
      return { ok: true, status: 200, arrayBuffer: async () => buf };
    }
    if (u.startsWith("https://android.clients.google.com/fdfe/purchase?")) {
      const buf = proto.encode("ResponseWrapper", {
        payload: { buyResponse: { encodedDeliveryToken: "dtok-99" } },
      });
      return { ok: true, status: 200, arrayBuffer: async () => buf };
    }
    if (u.startsWith("https://android.clients.google.com/fdfe/delivery?")) {
      const buf = proto.encode("ResponseWrapper", {
        payload: {
          deliveryResponse: {
            status: 1,
            appDeliveryData: {
              downloadSize: String(apkBytes.length),
              downloadUrl: "https://cdn.play.test/waze/base.apk",
              sha256: apkSha256B64,
              downloadAuthCookie: [{ name: "AUTH_C", value: "xyz" }],
              splitDeliveryData: [],
            },
          },
        },
      });
      return { ok: true, status: 200, arrayBuffer: async () => buf };
    }
    if (u === "https://cdn.play.test/waze/base.apk") {
      cookieSeenOnDownload = (init.headers && init.headers.cookie) || "";
      return { ok: true, status: 200, arrayBuffer: async () => apkBytes };
    }
    throw new Error(`Unexpected URL: ${u}`);
  };

  const aas = await auroraGPlay.exchangeOAuthForAas({
    email: "b@example.com",
    oauthToken: "oauth2_4/abc",
    fetchImpl: mockFetch,
  });
  assert.strictEqual(aas, "aas_et/TESTAAS");

  const client = new auroraGPlay.GPlayClient({
    profile: DEVICE_PROFILES.arm64_v8a,
    email: "b@example.com",
    aasToken: aas,
    fetchImpl: mockFetch,
  });
  assert.ok(buildUserAgent(DEVICE_PROFILES.arm64_v8a).includes("arm64-v8a"));
  const loginInfo = await client.login();
  assert.strictEqual(acceptTosSeen, true);
  assert.strictEqual(loginInfo.dfeCookie, "dfe-cookie-1");

  const det = await client.details("com.waze");
  assert.strictEqual(det.versionCode, 1022555);
  const del = await client.getDelivery("com.waze", det.versionCode);
  assert.strictEqual(del.downloadUrl, "https://cdn.play.test/waze/base.apk");

  const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), "aurora-dl-"));
  try {
    const out = path.join(dir, "waze.apk");
    const dl = await client.downloadToFile(del.downloadUrl, del.cookies, out, { expectedSha256: del.sha256Hex });
    assert.strictEqual(dl.sha256, apkSha256Hex);
    assert.strictEqual(cookieSeenOnDownload, "AUTH_C=xyz");
    await assert.rejects(() =>
      client.downloadToFile(del.downloadUrl, del.cookies, out, { expectedSha256: "0".repeat(64) })
    );
  } finally {
    await fs.promises.rm(dir, { recursive: true, force: true });
  }
});

test("aurora_sync deduplicates identical universal APK, splits multi-ABI APKs, skips upToDate, cleans obsolete GCS objects", async () => {
  const dbRoot = {};
  const gcsObjects = {};
  const makeDb = () => ({
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
  });
  const bucket = {
    name: auroraSync.STORE_APKS_BUCKET,
    file(objectPath) {
      return {
        name: objectPath,
        createWriteStream(opts = {}) {
          assert.ok(!opts.public && !opts.predefinedAcl, "No legacy ACL under Uniform Bucket-Level Access");
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

  function mockSession(abiTag, sameBytes, splits = []) {
    return {
      async details(pkg) {
        return { packageName: pkg, title: "App " + pkg, versionCode: 200, versionName: "2.0.0" };
      },
      async getDelivery(pkg, vc) {
        return {
          versionCode: vc,
          downloadUrl: `dl://base/${abiTag}`,
          downloadSize: sameBytes.length,
          sha256Hex: "",
          sha1Hex: "",
          cookies: [],
          splits: splits.map((s) => ({ name: s, downloadUrl: `dl://split/${abiTag}/${s}`, downloadSize: 10, sha256Hex: "", sha1Hex: "" })),
          hasObb: false,
        };
      },
      async downloadToFile(url, _cookies, destPath) {
        const buf = Buffer.from(url.startsWith("dl://split/") ? "SPLIT-" + url : sameBytes);
        await fs.promises.writeFile(destPath, buf);
        return { sizeBytes: buf.length, sha256: crypto.createHash("sha256").update(buf).digest("hex"), sha1: "" };
      },
    };
  }

  const db = makeDb();
  gcsObjects["aurora/com_universal/100_universal.apk"] = Buffer.from("old");
  const r1 = await auroraSync.syncSinglePackage({
    packageName: "com.universal",
    sessions: { arm64_v8a: mockSession("64", Buffer.from("SAME-UNIVERSAL-APK")), armeabi_v7a: mockSession("32", Buffer.from("SAME-UNIVERSAL-APK")) },
    db,
    bucket,
  });
  assert.strictEqual(r1.ok, true);
  assert.deepStrictEqual(r1.variants, ["universal"]);
  assert.strictEqual(gcsObjects["aurora/com_universal/100_universal.apk"], undefined);
  assert.ok(gcsObjects["aurora/com_universal/200_universal.apk"]);

  // Second run without version change must skip
  const r1Skip = await auroraSync.syncSinglePackage({
    packageName: "com.universal",
    sessions: {
      arm64_v8a: { details: async () => ({ versionCode: 200, versionName: "2.0.0" }), getDelivery: async () => { throw new Error("should not call"); } },
      armeabi_v7a: { details: async () => ({ versionCode: 200, versionName: "2.0.0" }), getDelivery: async () => { throw new Error("should not call"); } },
    },
    db,
    bucket,
  });
  assert.strictEqual(r1Skip.upToDate, true);

  // Split multi-ABI case
  const r2 = await auroraSync.syncSinglePackage({
    packageName: "com.waze",
    sessions: {
      arm64_v8a: mockSession("64", Buffer.from("BASE-64"), ["config.arm64_v8a"]),
      armeabi_v7a: mockSession("32", Buffer.from("BASE-32"), ["config.armeabi_v7a"]),
    },
    db,
    bucket,
  });
  assert.strictEqual(r2.ok, true);
  assert.deepStrictEqual(r2.variants.sort(), ["arm64_v8a", "armeabi_v7a"]);
  assert.ok(gcsObjects["aurora/com_waze/200_arm64_v8a.apks"]);
  assert.ok(gcsObjects["aurora/com_waze/200_armeabi_v7a.apks"]);
  assert.strictEqual(dbRoot.auroraStore.packages.com_waze.variants.arm64_v8a.bundleFormat, "apks");

  // Failed subsequent sync preserves working variants
  const rFail = await auroraSync.syncSinglePackage({
    packageName: "com.waze",
    sessions: {
      arm64_v8a: { details: async () => { throw new Error("Play timeout"); } },
      armeabi_v7a: { details: async () => { throw new Error("Play timeout"); } },
    },
    db,
    bucket,
  });
  assert.strictEqual(rFail.ok, false);
  assert.strictEqual(rFail.preservedPrevious, true);
  assert.ok(dbRoot.auroraStore.packages.com_waze.variants.arm64_v8a);
  assert.strictEqual(dbRoot.auroraStore.packages.com_waze.lastSyncError, "Play timeout");
});

(async () => {
  for (const fn of pending) await fn();
  if (!process.exitCode) console.log(`✅ test_aurora_gplay: ${pass} pruebas pasaron.`);
})();
