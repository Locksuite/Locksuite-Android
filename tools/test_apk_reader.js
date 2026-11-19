#!/usr/bin/env node
"use strict";
const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const ApkReader = require("../admin-backend/public/apk_reader.js");

(async () => {
  const apkPath = path.join(__dirname, "..", "admin-backend", "public", "locksuite-latest.apk");
  const buf = fs.readFileSync(apkPath);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);

  const info = await ApkReader.parseApkInfo(ab);
  assert.strictEqual(info.packageName, "com.ejemplo.locksuite", "Debe detectar el paquete de LockSuite");
  assert.ok(typeof info.versionCode === "number" && info.versionCode > 0, "Debe detectar versionCode");

  const hex = await ApkReader.sha256Hex(ab);
  const expected = crypto.createHash("sha256").update(buf).digest("hex");
  assert.strictEqual(hex, expected, "SHA-256 debe coincidir byte a byte");

  assert.strictEqual(ApkReader.labelFromFilename("waze_4.102.0.apk"), "Waze");
  assert.strictEqual(ApkReader.labelFromFilename("mi-app-universal-signed.apk"), "Mi App");

  console.log("OK (apk_reader: pkg=" + info.packageName + ", vc=" + info.versionCode + ")");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
