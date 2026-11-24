#!/usr/bin/env node
"use strict";
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const ApkReader = require("../admin-backend/public/apk_reader.js");

function makeZipStore(entries) {
  let offset = 0;
  const locals = [];
  const centrals = [];
  for (const e of entries) {
    const nameBuf = Buffer.from(e.name, "utf8");
    const dataBuf = Buffer.isBuffer(e.data) ? e.data : Buffer.from(e.data);
    // CRC32 via standard table
    let crc = 0xffffffff;
    for (let i = 0; i < dataBuf.length; i++) {
      crc ^= dataBuf[i];
      for (let k = 0; k < 8; k++) crc = (crc & 1) ? (0xedb88320 ^ (crc >>> 1)) : (crc >>> 1);
    }
    crc = (crc ^ 0xffffffff) >>> 0;

    const lfh = Buffer.alloc(30 + nameBuf.length);
    lfh.writeUInt32LE(0x04034b50, 0);
    lfh.writeUInt16LE(20, 4);
    lfh.writeUInt16LE(0x0800, 6);
    lfh.writeUInt16LE(0, 8);
    lfh.writeUInt32LE(crc, 14);
    lfh.writeUInt32LE(dataBuf.length, 18);
    lfh.writeUInt32LE(dataBuf.length, 22);
    lfh.writeUInt16LE(nameBuf.length, 26);
    nameBuf.copy(lfh, 30);

    const cdh = Buffer.alloc(46 + nameBuf.length);
    cdh.writeUInt32LE(0x02014b50, 0);
    cdh.writeUInt16LE(20, 4);
    cdh.writeUInt16LE(20, 6);
    cdh.writeUInt16LE(0x0800, 8);
    cdh.writeUInt16LE(0, 10);
    cdh.writeUInt32LE(crc, 16);
    cdh.writeUInt32LE(dataBuf.length, 20);
    cdh.writeUInt32LE(dataBuf.length, 24);
    cdh.writeUInt16LE(nameBuf.length, 28);
    cdh.writeUInt32LE(offset, 42);
    nameBuf.copy(cdh, 46);

    locals.push(lfh, dataBuf);
    centrals.push(cdh);
    offset += lfh.length + dataBuf.length;
  }
  const cdBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cdBuf, eocd]);
}

function buildAxml(attrs, minSdk) {
  const strings = [];
  const resIds = [];
  const addStr = (s, resId = 0) => {
    const idx = strings.length;
    strings.push(s);
    resIds.push(resId);
    return idx;
  };
  const mTag = addStr("manifest");
  const uTag = minSdk != null ? addStr("uses-sdk") : -1;
  const prep = attrs.map((a) => {
    const nIdx = addStr(a.name, a.resId || 0);
    if (a.str != null) {
      const vIdx = addStr(a.str, 0);
      return { nIdx, raw: vIdx, type: 0x03, data: vIdx };
    }
    return { nIdx, raw: -1, type: a.type || 0x10, data: a.num || 0 };
  });
  const sdkAttr = minSdk != null ? [{ nIdx: addStr("minSdkVersion", 0x0101020c), raw: -1, type: 0x10, data: minSdk }] : [];

  const strParts = [];
  const offsets = [];
  let cursor = 0;
  for (const s of strings) {
    offsets.push(cursor);
    const u = Buffer.from(s, "utf8");
    const hdr = Buffer.from([s.length & 0x7f, u.length & 0x7f]);
    const nul = Buffer.from([0]);
    strParts.push(hdr, u, nul);
    cursor += hdr.length + u.length + 1;
  }
  while (cursor % 4 !== 0) {
    strParts.push(Buffer.from([0]));
    cursor++;
  }
  const spHeaderSize = 28;
  const spStringsStart = spHeaderSize + strings.length * 4;
  const spSize = spStringsStart + cursor;
  const sp = Buffer.alloc(spSize);
  sp.writeUInt16LE(0x0001, 0);
  sp.writeUInt16LE(spHeaderSize, 2);
  sp.writeUInt32LE(spSize, 4);
  sp.writeUInt32LE(strings.length, 8);
  sp.writeUInt32LE(0x100, 16);
  sp.writeUInt32LE(spStringsStart, 20);
  offsets.forEach((o, i) => sp.writeUInt32LE(o, spHeaderSize + i * 4));
  Buffer.concat(strParts).copy(sp, spStringsStart);

  const rm = Buffer.alloc(8 + resIds.length * 4);
  rm.writeUInt16LE(0x0180, 0);
  rm.writeUInt16LE(8, 2);
  rm.writeUInt32LE(rm.length, 4);
  resIds.forEach((r, i) => rm.writeUInt32LE(r >>> 0, 8 + i * 4));

  const mkElem = (tagIdx, list) => {
    const b = Buffer.alloc(36 + list.length * 20);
    b.writeUInt16LE(0x0102, 0);
    b.writeUInt16LE(16, 2);
    b.writeUInt32LE(b.length, 4);
    b.writeInt32LE(-1, 16);
    b.writeInt32LE(tagIdx, 20);
    b.writeUInt16LE(20, 24);
    b.writeUInt16LE(20, 26);
    b.writeUInt16LE(list.length, 28);
    list.forEach((p, i) => {
      const a = 36 + i * 20;
      b.writeInt32LE(-1, a);
      b.writeInt32LE(p.nIdx, a + 4);
      b.writeInt32LE(p.raw, a + 8);
      b.writeUInt16LE(8, a + 12);
      b.writeUInt8(p.type, a + 15);
      b.writeInt32LE(p.data, a + 16);
    });
    return b;
  };
  const mc = mkElem(mTag, prep);
  const uc = uTag >= 0 ? mkElem(uTag, sdkAttr) : Buffer.alloc(0);
  const total = 8 + sp.length + rm.length + mc.length + uc.length;
  const hdr = Buffer.alloc(8);
  hdr.writeUInt16LE(0x0003, 0);
  hdr.writeUInt16LE(8, 2);
  hdr.writeUInt32LE(total, 4);
  return Buffer.concat([hdr, sp, rm, mc, uc]);
}

(async () => {
  const apkPath = path.join(__dirname, "..", "admin-backend", "public", "locksuite-latest.apk");
  const buf = fs.readFileSync(apkPath);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);

  const info = await ApkReader.parseApkInfo(ab);
  assert.strictEqual(info.packageName, "com.ejemplo.locksuite", "Debe detectar el paquete de LockSuite");
  assert.ok(typeof info.versionCode === "number" && info.versionCode > 0, "Debe detectar versionCode");

  const singleBundle = await ApkReader.parsePackageBundle(ab, "locksuite-latest.apk");
  assert.strictEqual(singleBundle.ok, true);
  assert.strictEqual(singleBundle.isSplitBundle, false);
  assert.strictEqual(singleBundle.bundleFormat, "apk");
  assert.strictEqual(singleBundle.packageName, "com.ejemplo.locksuite");

  const hex = await ApkReader.sha256Hex(ab);
  const expected = crypto.createHash("sha256").update(buf).digest("hex");
  assert.strictEqual(hex, expected, "SHA-256 debe coincidir byte a byte");

  assert.strictEqual(ApkReader.labelFromFilename("waze_4.102.0.apk"), "Waze");
  assert.strictEqual(ApkReader.labelFromFilename("mi-app-universal-signed.apk"), "Mi App");
  assert.strictEqual(ApkReader.labelFromFilename("Transit_6.3.7.xapk"), "Transit");
  assert.strictEqual(ApkReader.labelFromFilename("WhatsApp_splits.apks"), "WhatsApp");
  assert.strictEqual(ApkReader.labelFromFilename("App_1.0.apkm"), "App");

  // Bundle sintético (.xapk con base + splits + OBB)
  const baseApk = makeZipStore([
    {
      name: "AndroidManifest.xml",
      data: buildAxml([
        { name: "package", str: "com.test.splitapp" },
        { name: "versionName", resId: 0x0101021c, str: "6.3.7" },
        { name: "versionCode", resId: 0x0101021b, num: 60307 },
      ], 26),
    },
  ]);
  const arm64Apk = makeZipStore([
    {
      name: "AndroidManifest.xml",
      data: buildAxml([
        { name: "package", str: "com.test.splitapp" },
        { name: "split", str: "config.arm64_v8a" },
      ]),
    },
    { name: "lib/arm64-v8a/libtest.so", data: Buffer.from("so") },
  ]);
  const esApk = makeZipStore([
    {
      name: "AndroidManifest.xml",
      data: buildAxml([
        { name: "package", str: "com.test.splitapp" },
        { name: "split", str: "config.es" },
      ]),
    },
  ]);

  const xapkBuf = makeZipStore([
    { name: "base.apk", data: baseApk },
    { name: "config.arm64_v8a.apk", data: arm64Apk },
    { name: "config.es.apk", data: esApk },
    { name: "Android/obb/com.test.splitapp/main.1.com.test.splitapp.obb", data: Buffer.from("obb") },
  ]);
  const parsedXapk = await ApkReader.parsePackageBundle(xapkBuf, "TestApp.xapk");
  assert.strictEqual(parsedXapk.ok, true);
  assert.strictEqual(parsedXapk.isSplitBundle, true);
  assert.strictEqual(parsedXapk.bundleFormat, "xapk");
  assert.strictEqual(parsedXapk.splitCount, 3);
  assert.strictEqual(parsedXapk.packageName, "com.test.splitapp");
  assert.strictEqual(parsedXapk.versionName, "6.3.7");
  assert.strictEqual(parsedXapk.versionCode, 60307);
  assert.strictEqual(parsedXapk.minSdk, 26);
  assert.deepStrictEqual(parsedXapk.abis, ["arm64-v8a"]);
  assert.strictEqual(parsedXapk.hasObb, true);

  // Empaquetado en navegador de múltiples .apk sueltos -> round-trip parsePackageBundle
  const packed = await ApkReader.packSplitApksToZip([
    { name: "config.es.apk", data: esApk },
    { name: "base.apk", data: baseApk },
    { name: "config.arm64_v8a.apk", data: arm64Apk },
  ]);
  assert.strictEqual(packed.packageName, "com.test.splitapp");
  assert.strictEqual(packed.splitCount, 3);
  assert.deepStrictEqual(packed.abis, ["arm64-v8a"]);
  const reparsed = await ApkReader.parsePackageBundle(packed.bytes, "com_test_splitapp_splits.apks");
  assert.strictEqual(reparsed.ok, true);
  assert.strictEqual(reparsed.isSplitBundle, true);
  assert.strictEqual(reparsed.splitCount, 3);
  assert.strictEqual(reparsed.packageName, "com.test.splitapp");
  assert.strictEqual(reparsed.hasObb, false);

  // Controles negativos
  const nonZip = await ApkReader.parsePackageBundle(Buffer.from("APKM_ENCRYPTED"), "app.apkm");
  assert.strictEqual(nonZip.ok, false);

  const noBase = makeZipStore([{ name: "config.es.apk", data: esApk }]);
  assert.strictEqual((await ApkReader.parsePackageBundle(noBase, "nobase.apks")).ok, false);

  const otherSplit = makeZipStore([
    {
      name: "AndroidManifest.xml",
      data: buildAxml([
        { name: "package", str: "com.other.pkg" },
        { name: "split", str: "config.es" },
      ]),
    },
  ]);
  const mismatch = makeZipStore([
    { name: "base.apk", data: baseApk },
    { name: "config.es.apk", data: otherSplit },
  ]);
  assert.strictEqual((await ApkReader.parsePackageBundle(mismatch, "mismatch.apks")).ok, false);

  await assert.rejects(
    () => ApkReader.packSplitApksToZip([
      { name: "base.apk", data: baseApk },
      { name: "other.apk", data: otherSplit },
    ]),
    /pertenece a/
  );

  console.log("OK (apk_reader: pkg=" + info.packageName + ", vc=" + info.versionCode + ", splits OK)");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
