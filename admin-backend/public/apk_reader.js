/* ═══════════════════════════════════════════════════════════════════════════
 * apk_reader.js — lectura local de .apk y Split APKs (.apks/.xapk/.apkm) en el
 * navegador (SHA-256 + AXML + empaquetado ZIP STORE de múltiples .apk sueltos).
 *
 * Lee directamente el `ArrayBuffer` del archivo elegido desde la PC para calcular:
 *   1. Huella SHA-256 en hex minúsculas (mismo formato que exige `SelfUpdater.kt`).
 *   2. `packageName`, `versionName`, `versionCode`, `minSdk` y `abis` desde
 *      `AndroidManifest.xml` (AXML) adentro del ZIP (único o dividido).
 *   3. `packSplitApksToZip(files)`: valida y empaqueta múltiples `.apk` sueltos
 *      en un `.apks` sin compresión dentro del navegador.
 *
 * Funciona tanto en el navegador (`window.ApkReader`) como en Node (`module.exports`).
 * ═══════════════════════════════════════════════════════════════════════════ */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.ApkReader = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const MAX_BUNDLE_BYTES = 512 * 1024 * 1024;
  const MAX_SPLIT_ENTRIES = 128;

  const ATTR_MIN_SDK_VERSION = 0x0101020c;
  const ATTR_VERSION_CODE = 0x0101021b;
  const ATTR_VERSION_NAME = 0x0101021c;
  const ATTR_IS_FEATURE_SPLIT = 0x0101055a;
  const ATTR_CONFIG_FOR_SPLIT = 0x0101055b;
  const ATTR_VERSION_CODE_MAJOR = 0x01010576;

  function toHex(uint8) {
    let out = "";
    for (let i = 0; i < uint8.length; i++) {
      out += uint8[i].toString(16).padStart(2, "0");
    }
    return out;
  }

  async function sha256Hex(arrayBuffer) {
    if (typeof crypto !== "undefined" && crypto.subtle && typeof crypto.subtle.digest === "function") {
      const digest = await crypto.subtle.digest("SHA-256", arrayBuffer);
      return toHex(new Uint8Array(digest));
    }
    if (typeof require === "function") {
      const nodeCrypto = require("crypto");
      return nodeCrypto.createHash("sha256").update(Buffer.from(arrayBuffer)).digest("hex");
    }
    throw new Error("WebCrypto no está disponible en este navegador.");
  }

  function decodeLatin1OrUtf8(bytes) {
    if (typeof TextDecoder !== "undefined") {
      return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
    }
    if (typeof Buffer !== "undefined") {
      return Buffer.from(bytes).toString("utf8");
    }
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return s;
  }

  function decodeUtf16Le(bytes) {
    if (typeof TextDecoder !== "undefined") {
      return new TextDecoder("utf-16le").decode(bytes);
    }
    if (typeof Buffer !== "undefined") {
      return Buffer.from(bytes).toString("utf16le");
    }
    let s = "";
    for (let i = 0; i + 1 < bytes.length; i += 2) {
      s += String.fromCharCode(bytes[i] | (bytes[i + 1] << 8));
    }
    return s;
  }

  function encodeUtf8(str) {
    if (typeof TextEncoder !== "undefined") {
      return new TextEncoder().encode(str);
    }
    if (typeof Buffer !== "undefined") {
      return new Uint8Array(Buffer.from(str, "utf8"));
    }
    const out = [];
    for (let i = 0; i < str.length; i++) {
      const c = str.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
    return new Uint8Array(out);
  }

  function isZipBytes(bytes) {
    if (!bytes || bytes.length < 4) return false;
    if (bytes[0] !== 0x50 || bytes[1] !== 0x4b) return false;
    return (bytes[2] === 0x03 && bytes[3] === 0x04) || (bytes[2] === 0x05 && bytes[3] === 0x06);
  }

  function isSafeZipEntryName(name) {
    if (!name || typeof name !== "string") return false;
    if (name.startsWith("/") || name.startsWith("\\")) return false;
    if (name.includes("\\") || name.includes("\u0000") || name.includes("..")) return false;
    for (let i = 0; i < name.length; i++) {
      if (name.charCodeAt(i) < 0x20) return false;
    }
    const clean = name.endsWith("/") ? name.slice(0, -1) : name;
    const parts = clean.split("/");
    return parts.every((p) => p !== "" && p !== "." && p !== "..");
  }

  function listZipEntries(bytes) {
    if (!isZipBytes(bytes) || bytes.length < 22) {
      return { ok: false, error: "El archivo no tiene firma ZIP válida (si es un .apkm cifrado de APKMirror, convertilo primero a .apks sin cifrar)." };
    }
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const minOff = Math.max(0, bytes.length - 65558);
    let eocd = -1;
    for (let i = bytes.length - 22; i >= minOff; i--) {
      if (dv.getUint32(i, true) === 0x06054b50) {
        const commentLen = dv.getUint16(i + 20, true);
        if (i + 22 + commentLen === bytes.length) {
          eocd = i;
          break;
        }
      }
    }
    if (eocd < 0) {
      return { ok: false, error: "No se encontró el directorio central del archivo ZIP." };
    }

    const totalEntries = dv.getUint16(eocd + 10, true);
    const cdSize = dv.getUint32(eocd + 12, true);
    const cdOffset = dv.getUint32(eocd + 16, true);
    if (totalEntries === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
      return { ok: false, error: "El paquete usa ZIP64 no soportado en el navegador." };
    }
    if (cdOffset + cdSize > eocd) {
      return { ok: false, error: "El directorio central del ZIP está truncado o dañado." };
    }

    const entries = [];
    let pos = cdOffset;
    for (let n = 0; n < totalEntries && pos + 46 <= bytes.length; n++) {
      if (dv.getUint32(pos, true) !== 0x02014b50) break;
      const compressionMethod = dv.getUint16(pos + 10, true);
      const compressedSize = dv.getUint32(pos + 20, true);
      const uncompressedSize = dv.getUint32(pos + 24, true);
      const nameLen = dv.getUint16(pos + 28, true);
      const extraLen = dv.getUint16(pos + 30, true);
      const commentLen = dv.getUint16(pos + 32, true);
      const localOffset = dv.getUint32(pos + 42, true);
      if (pos + 46 + nameLen > bytes.length) break;
      const name = decodeLatin1OrUtf8(bytes.subarray(pos + 46, pos + 46 + nameLen));
      entries.push({
        name,
        isDirectory: name.endsWith("/"),
        compressionMethod,
        compressedSize,
        uncompressedSize,
        localOffset,
      });
      pos += 46 + nameLen + extraLen + commentLen;
    }
    return { ok: true, entries };
  }

  async function extractZipEntry(bytes, entry) {
    if (!entry) return null;
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const lo = entry.localOffset;
    if (lo + 30 > bytes.length || dv.getUint32(lo, true) !== 0x04034b50) {
      throw new Error("Cabecera ZIP local inválida para " + entry.name);
    }
    const lfNameLen = dv.getUint16(lo + 26, true);
    const lfExtraLen = dv.getUint16(lo + 28, true);
    const dataStart = lo + 30 + lfNameLen + lfExtraLen;
    if (dataStart + entry.compressedSize > bytes.length) {
      throw new Error("Entrada ZIP truncada: " + entry.name);
    }
    const raw = bytes.subarray(dataStart, dataStart + entry.compressedSize);
    return inflateRaw(raw, entry.compressionMethod);
  }

  async function inflateRaw(u8, compressionMethod) {
    if (compressionMethod === 0) return u8;
    if (compressionMethod !== 8) {
      throw new Error("Compresión ZIP no soportada: " + compressionMethod);
    }
    if (typeof DecompressionStream === "function" && typeof Response === "function") {
      const ds = new DecompressionStream("deflate-raw");
      const stream = new Blob([u8]).stream().pipeThrough(ds);
      const ab = await new Response(stream).arrayBuffer();
      return new Uint8Array(ab);
    }
    if (typeof require === "function") {
      const zlib = require("zlib");
      return new Uint8Array(zlib.inflateRawSync(u8));
    }
    throw new Error("El navegador no soporta DecompressionStream(deflate-raw).");
  }

  function readUtf8Len(bytes, state) {
    let len = bytes[state.pos++];
    if (len & 0x80) {
      len = ((len & 0x7f) << 8) | bytes[state.pos++];
    }
    return len;
  }

  function readUtf16Len(dv, state) {
    let len = dv.getUint16(state.pos, true);
    state.pos += 2;
    if (len & 0x8000) {
      const low = dv.getUint16(state.pos, true);
      state.pos += 2;
      len = ((len & 0x7fff) << 16) | low;
    }
    return len;
  }

  function parseStringPool(bytes, dv, chunkOff, chunkHdrSize, chunkEnd) {
    const stringCount = dv.getUint32(chunkOff + 8, true);
    const flags = dv.getUint32(chunkOff + 16, true);
    const stringsStart = chunkOff + dv.getUint32(chunkOff + 20, true);
    const isUtf8 = (flags & 0x100) !== 0;
    const offsetsStart = chunkOff + chunkHdrSize;
    const out = new Array(stringCount);

    for (let i = 0; i < stringCount; i++) {
      if (offsetsStart + (i + 1) * 4 > chunkEnd) break;
      const rel = dv.getUint32(offsetsStart + i * 4, true);
      const state = { pos: stringsStart + rel };
      if (state.pos >= chunkEnd) {
        out[i] = "";
        continue;
      }
      if (isUtf8) {
        readUtf8Len(bytes, state); // char count
        const byteLen = readUtf8Len(bytes, state);
        out[i] = decodeLatin1OrUtf8(bytes.subarray(state.pos, Math.min(chunkEnd, state.pos + byteLen)));
      } else {
        const charLen = readUtf16Len(dv, state);
        const end = Math.min(chunkEnd, state.pos + charLen * 2);
        out[i] = decodeUtf16Le(bytes.subarray(state.pos, end));
      }
    }
    return out;
  }

  function parseBinaryManifest(bytes) {
    if (!bytes || bytes.length < 16) return { packageName: null };
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (dv.getUint16(0, true) !== 0x0003) return { packageName: null };

    const headerSize = dv.getUint16(2, true);
    const fileSize = Math.min(bytes.length, dv.getUint32(4, true));
    let strings = [];
    let resourceMap = [];
    let off = headerSize;

    let hasManifest = false;
    let packageName = null;
    let splitName = null;
    let configForSplit = null;
    let isFeatureSplit = false;
    let versionName = null;
    let vcMinor = 0;
    let vcMajor = 0;
    let hasVcMinor = false;
    let hasVcMajor = false;
    let minSdk = 0;

    while (off + 8 <= fileSize) {
      const chunkType = dv.getUint16(off, true);
      const chunkHdrSize = dv.getUint16(off + 2, true);
      const chunkSize = dv.getUint32(off + 4, true);
      if (chunkSize < 8 || off + chunkSize > fileSize) break;
      const chunkEnd = off + chunkSize;

      if (chunkType === 0x0001 && chunkHdrSize >= 28 && off + 28 <= chunkEnd) {
        strings = parseStringPool(bytes, dv, off, chunkHdrSize, chunkEnd);
      } else if (chunkType === 0x0180 && chunkHdrSize >= 8 && off + chunkHdrSize <= chunkEnd) {
        const count = Math.floor((chunkEnd - (off + chunkHdrSize)) / 4);
        resourceMap = new Array(count);
        for (let i = 0; i < count; i++) {
          resourceMap[i] = dv.getUint32(off + chunkHdrSize + i * 4, true);
        }
      } else if (chunkType === 0x0102) {
        const extOff = off + chunkHdrSize;
        if (chunkHdrSize >= 16 && extOff + 20 <= chunkEnd) {
          const nameIdx = dv.getInt32(extOff + 4, true);
          const elName = nameIdx >= 0 ? strings[nameIdx] : null;
          if (elName === "application") break;

          if (elName === "manifest" || elName === "uses-sdk") {
            if (elName === "manifest") hasManifest = true;
            const attrStart = dv.getUint16(extOff + 8, true);
            const attrSize = dv.getUint16(extOff + 10, true);
            const attrCount = dv.getUint16(extOff + 12, true);
            let a = extOff + attrStart;

            for (let i = 0; i < attrCount && attrSize >= 20 && a + 20 <= chunkEnd; i++, a += attrSize) {
              const attrNameIdx = dv.getInt32(a + 4, true);
              const attrName = attrNameIdx >= 0 ? (strings[attrNameIdx] || "") : "";
              const resId = (attrNameIdx >= 0 && attrNameIdx < resourceMap.length) ? resourceMap[attrNameIdx] : 0;
              const rawValIdx = dv.getInt32(a + 8, true);
              const valType = bytes[a + 15];
              const valData = dv.getInt32(a + 16, true);
              const valUint = dv.getUint32(a + 16, true);

              const readStr = () => {
                if (rawValIdx >= 0 && strings[rawValIdx] != null) return strings[rawValIdx];
                if (valType === 0x03 && valData >= 0 && strings[valData] != null) return strings[valData];
                return null;
              };
              const readInt = () => {
                if (valType === 0x10 || valType === 0x11) return valUint;
                const s = readStr();
                if (s && /^\d+$/.test(s.trim())) return parseInt(s.trim(), 10);
                return null;
              };
              const readBool = () => {
                if (valType === 0x12 || valType === 0x10 || valType === 0x11) return valData !== 0;
                const s = readStr();
                return Boolean(s && s.trim().toLowerCase() === "true");
              };

              if (elName === "manifest") {
                if (resId === 0 && attrName === "package") packageName = readStr();
                else if (resId === 0 && attrName === "split") splitName = readStr() || null;
                else if (resId === ATTR_CONFIG_FOR_SPLIT || (resId === 0 && attrName === "configForSplit")) {
                  configForSplit = readStr() || null;
                } else if (resId === ATTR_IS_FEATURE_SPLIT || (resId === 0 && attrName === "isFeatureSplit")) {
                  isFeatureSplit = readBool();
                } else if (resId === ATTR_VERSION_NAME || (resId === 0 && attrName === "versionName")) {
                  versionName = readStr();
                } else if (resId === ATTR_VERSION_CODE || (resId === 0 && attrName === "versionCode")) {
                  const v = readInt();
                  if (v != null) { vcMinor = v; hasVcMinor = true; }
                } else if (resId === ATTR_VERSION_CODE_MAJOR || (resId === 0 && attrName === "versionCodeMajor")) {
                  const v = readInt();
                  if (v != null) { vcMajor = v; hasVcMajor = true; }
                }
              } else if (resId === ATTR_MIN_SDK_VERSION || (resId === 0 && attrName === "minSdkVersion")) {
                const v = readInt();
                if (v != null && v > 0) minSdk = v;
              }
            }
          }
        }
      }
      off += Math.max(chunkSize, chunkHdrSize);
    }

    if (!hasManifest) return { packageName: null };
    const versionCode = (hasVcMinor || hasVcMajor)
      ? (vcMajor * 4294967296 + vcMinor)
      : null;

    return {
      packageName,
      versionName,
      versionCode,
      splitName,
      configForSplit,
      isFeatureSplit,
      minSdk,
    };
  }

  function extractAbisFromEntries(entries) {
    const set = new Set();
    for (const e of entries) {
      if (!e || e.isDirectory) continue;
      const m = /^lib\/([^/]+)\/[^/]+\.so$/.exec(e.name);
      if (m && m[1]) set.add(m[1]);
    }
    return Array.from(set).sort();
  }

  function mapSplitNameAbi(splitName) {
    if (!splitName) return null;
    const q = splitName.startsWith("config.")
      ? splitName.slice("config.".length)
      : splitName.includes(".config.")
        ? splitName.split(".config.").pop()
        : splitName;
    switch (String(q || "").trim().toLowerCase()) {
      case "arm64_v8a":
      case "arm64-v8a":
        return "arm64-v8a";
      case "armeabi_v7a":
      case "armeabi-v7a":
        return "armeabi-v7a";
      case "armeabi":
        return "armeabi";
      case "x86_64":
      case "x86-64":
        return "x86_64";
      case "x86":
        return "x86";
      default:
        return null;
    }
  }

  async function parseSingleApkBuffer(bytes) {
    const listed = listZipEntries(bytes);
    if (!listed.ok) return { ok: false, error: listed.error };
    const manifestEntry = listed.entries.find((e) => e.name === "AndroidManifest.xml" && !e.isDirectory);
    if (!manifestEntry) {
      return { ok: false, error: "Falta AndroidManifest.xml en el APK." };
    }
    const xmlBytes = await extractZipEntry(bytes, manifestEntry);
    const manifest = parseBinaryManifest(xmlBytes);
    if (!manifest || !manifest.packageName) {
      return { ok: false, error: "No se pudo leer packageName en AndroidManifest.xml." };
    }
    return {
      ok: true,
      manifest,
      abis: extractAbisFromEntries(listed.entries),
    };
  }

  async function parseApkInfo(arrayBuffer) {
    try {
      const bytes = arrayBuffer instanceof Uint8Array ? arrayBuffer : new Uint8Array(arrayBuffer);
      const res = await parseSingleApkBuffer(bytes);
      if (!res.ok) return { packageName: null };
      return res.manifest;
    } catch (_) {
      return { packageName: null };
    }
  }

  async function parsePackageBundle(arrayBuffer, fileName) {
    try {
      const bytes = arrayBuffer instanceof Uint8Array ? arrayBuffer : new Uint8Array(arrayBuffer);
      const listed = listZipEntries(bytes);
      if (!listed.ok) return { ok: false, error: listed.error };

      const rootManifest = listed.entries.find((e) => e.name === "AndroidManifest.xml" && !e.isDirectory);
      if (rootManifest) {
        const xmlBytes = await extractZipEntry(bytes, rootManifest);
        const manifest = parseBinaryManifest(xmlBytes);
        if (!manifest || !manifest.packageName) {
          return { ok: false, error: "El APK no tiene un AndroidManifest.xml válido." };
        }
        return {
          ok: true,
          isSplitBundle: false,
          bundleFormat: "apk",
          splitCount: 1,
          packageName: manifest.packageName,
          versionName: manifest.versionName || null,
          versionCode: manifest.versionCode != null ? manifest.versionCode : null,
          minSdk: manifest.minSdk || 0,
          abis: extractAbisFromEntries(listed.entries),
          hasObb: false,
        };
      }

      const apkEntries = [];
      let hasObb = false;
      for (const e of listed.entries) {
        if (e.isDirectory) continue;
        const lower = e.name.toLowerCase();
        if (lower.startsWith("android/obb/") && lower.endsWith(".obb")) {
          hasObb = true;
        }
        if (lower.endsWith(".apk")) {
          if (!isSafeZipEntryName(e.name)) {
            return { ok: false, error: "Ruta insegura dentro del paquete: " + e.name };
          }
          apkEntries.push(e);
        }
      }

      if (apkEntries.length === 0) {
        return { ok: false, error: "El archivo no es un APK ni contiene archivos .apk adentro." };
      }
      if (apkEntries.length > MAX_SPLIT_ENTRIES) {
        return { ok: false, error: "El paquete contiene más de " + MAX_SPLIT_ENTRIES + " APKs internos." };
      }

      const parsed = [];
      for (const e of apkEntries) {
        const innerBytes = await extractZipEntry(bytes, e);
        const info = await parseSingleApkBuffer(innerBytes);
        if (!info.ok) {
          return { ok: false, error: "APK interno inválido (" + e.name + "): " + info.error };
        }
        parsed.push({ entry: e, manifest: info.manifest, abis: info.abis });
      }

      const bases = parsed.filter((x) => !x.manifest.splitName);
      if (bases.length === 0) {
        return { ok: false, error: "El paquete dividido no contiene un APK base." };
      }
      if (bases.length > 1) {
        return {
          ok: false,
          error: "El paquete contiene más de un APK base (" + bases.map((b) => b.entry.name).join(", ") + ").",
        };
      }

      const base = bases[0];
      const basePkg = base.manifest.packageName;
      for (const item of parsed) {
        if (item.manifest.packageName !== basePkg) {
          return {
            ok: false,
            error: "El split " + item.entry.name + " pertenece a " + item.manifest.packageName + " y no a " + basePkg + ".",
          };
        }
      }

      const abiSet = new Set();
      for (const item of parsed) {
        item.abis.forEach((a) => abiSet.add(a));
        const mapped = mapSplitNameAbi(item.manifest.splitName);
        if (mapped) abiSet.add(mapped);
      }

      const extMatch = /\.(apks|xapk|apkm)$/i.exec(String(fileName || "").trim());
      const bundleFormat = extMatch ? extMatch[1].toLowerCase() : "apks";

      return {
        ok: true,
        isSplitBundle: true,
        bundleFormat,
        splitCount: parsed.length,
        packageName: basePkg,
        versionName: base.manifest.versionName || null,
        versionCode: base.manifest.versionCode != null ? base.manifest.versionCode : null,
        minSdk: base.manifest.minSdk || 0,
        abis: Array.from(abiSet).sort(),
        hasObb,
      };
    } catch (e) {
      return { ok: false, error: (e && e.message) || "Error leyendo el paquete." };
    }
  }

  let crcTable = null;
  function getCrcTable() {
    if (crcTable) return crcTable;
    const t = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) {
        c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
      }
      t[i] = c >>> 0;
    }
    crcTable = t;
    return t;
  }

  function crc32(bytes) {
    const t = getCrcTable();
    let crc = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) {
      crc = (crc >>> 8) ^ t[(crc ^ bytes[i]) & 0xff];
    }
    return (crc ^ 0xffffffff) >>> 0;
  }

  async function readFileInputItem(item) {
    if (!item) throw new Error("Archivo inválido.");
    const name = String(item.name || "split.apk");
    if (item.data instanceof Uint8Array) return { name, bytes: item.data };
    if (item.data instanceof ArrayBuffer) return { name, bytes: new Uint8Array(item.data) };
    if (typeof item.arrayBuffer === "function") {
      return { name, bytes: new Uint8Array(await item.arrayBuffer()) };
    }
    throw new Error("No se puede leer el archivo " + name);
  }

  async function packSplitApksToZip(files) {
    if (!Array.isArray(files) || files.length < 2) {
      throw new Error("Seleccioná al menos 2 archivos .apk (base + splits) para empaquetar.");
    }
    if (files.length > MAX_SPLIT_ENTRIES) {
      throw new Error("Demasiados archivos .apk (máximo " + MAX_SPLIT_ENTRIES + ").");
    }

    const parsedItems = [];
    const usedNames = new Set();
    let totalRaw = 0;

    for (let i = 0; i < files.length; i++) {
      const { name, bytes } = await readFileInputItem(files[i]);
      if (!/\.apk$/i.test(name)) {
        throw new Error("Todos los archivos seleccionados deben terminar en .apk (" + name + ").");
      }
      totalRaw += bytes.length;
      if (totalRaw > MAX_BUNDLE_BYTES) {
        throw new Error("Los APKs seleccionados superan el máximo permitido de 512 MB.");
      }

      const info = await parseSingleApkBuffer(bytes);
      if (!info.ok) {
        throw new Error("APK inválido (" + name + "): " + info.error);
      }

      const cleanBase = name.replace(/^.*[/\\]/, "").replace(/[^a-zA-Z0-9._-]/g, "_") || ("split_" + i + ".apk");
      let entryName = info.manifest.splitName ? cleanBase : "base.apk";
      if (usedNames.has(entryName)) {
        entryName = String(i).padStart(2, "0") + "_" + cleanBase;
      }
      usedNames.add(entryName);

      parsedItems.push({
        entryName,
        nameBytes: encodeUtf8(entryName),
        bytes,
        crc: crc32(bytes),
        manifest: info.manifest,
        abis: info.abis,
      });
    }

    const bases = parsedItems.filter((x) => !x.manifest.splitName);
    if (bases.length === 0) {
      throw new Error("Falta el APK base (sin atributo split) entre los archivos elegidos.");
    }
    if (bases.length > 1) {
      throw new Error("Hay más de un APK base entre los archivos elegidos (" + bases.map((b) => b.entryName).join(", ") + ").");
    }

    const base = bases[0];
    const basePkg = base.manifest.packageName;
    for (const item of parsedItems) {
      if (item.manifest.packageName !== basePkg) {
        throw new Error("El archivo " + item.entryName + " pertenece a " + item.manifest.packageName + " y no a " + basePkg + ".");
      }
    }

    // Ordenar con base.apk primero
    parsedItems.sort((a, b) => (a === base ? -1 : b === base ? 1 : 0));

    let totalSize = 22; // EOCD
    for (const item of parsedItems) {
      totalSize += 30 + item.nameBytes.length + item.bytes.length; // Local file header + data
      totalSize += 46 + item.nameBytes.length; // Central directory header
    }
    if (totalSize > MAX_BUNDLE_BYTES) {
      throw new Error("El archivo .apks empaquetado supera el máximo permitido de 512 MB.");
    }

    const out = new Uint8Array(totalSize);
    const dv = new DataView(out.buffer);
    let offset = 0;

    for (const item of parsedItems) {
      item.localOffset = offset;
      dv.setUint32(offset, 0x04034b50, true);
      dv.setUint16(offset + 4, 20, true);      // version needed
      dv.setUint16(offset + 6, 0x0800, true);  // UTF-8 filename flag
      dv.setUint16(offset + 8, 0, true);       // STORE (sin compresión)
      dv.setUint16(offset + 10, 0, true);
      dv.setUint16(offset + 12, 0, true);
      dv.setUint32(offset + 14, item.crc, true);
      dv.setUint32(offset + 18, item.bytes.length, true);
      dv.setUint32(offset + 22, item.bytes.length, true);
      dv.setUint16(offset + 26, item.nameBytes.length, true);
      dv.setUint16(offset + 28, 0, true);
      out.set(item.nameBytes, offset + 30);
      offset += 30 + item.nameBytes.length;
      out.set(item.bytes, offset);
      offset += item.bytes.length;
    }

    const cdOffset = offset;
    for (const item of parsedItems) {
      dv.setUint32(offset, 0x02014b50, true);
      dv.setUint16(offset + 4, 20, true);
      dv.setUint16(offset + 6, 20, true);
      dv.setUint16(offset + 8, 0x0800, true);
      dv.setUint16(offset + 10, 0, true);
      dv.setUint16(offset + 12, 0, true);
      dv.setUint16(offset + 14, 0, true);
      dv.setUint32(offset + 16, item.crc, true);
      dv.setUint32(offset + 20, item.bytes.length, true);
      dv.setUint32(offset + 24, item.bytes.length, true);
      dv.setUint16(offset + 28, item.nameBytes.length, true);
      dv.setUint16(offset + 30, 0, true);
      dv.setUint16(offset + 32, 0, true);
      dv.setUint16(offset + 34, 0, true);
      dv.setUint16(offset + 36, 0, true);
      dv.setUint32(offset + 38, 0, true);
      dv.setUint32(offset + 42, item.localOffset, true);
      out.set(item.nameBytes, offset + 46);
      offset += 46 + item.nameBytes.length;
    }
    const cdSize = offset - cdOffset;

    dv.setUint32(offset, 0x06054b50, true);
    dv.setUint16(offset + 4, 0, true);
    dv.setUint16(offset + 6, 0, true);
    dv.setUint16(offset + 8, parsedItems.length, true);
    dv.setUint16(offset + 10, parsedItems.length, true);
    dv.setUint32(offset + 12, cdSize, true);
    dv.setUint32(offset + 16, cdOffset, true);
    dv.setUint16(offset + 20, 0, true);

    const abiSet = new Set();
    for (const item of parsedItems) {
      item.abis.forEach((a) => abiSet.add(a));
      const mapped = mapSplitNameAbi(item.manifest.splitName);
      if (mapped) abiSet.add(mapped);
    }

    return {
      bytes: out,
      packageName: basePkg,
      versionName: base.manifest.versionName || null,
      versionCode: base.manifest.versionCode != null ? base.manifest.versionCode : null,
      minSdk: base.manifest.minSdk || 0,
      splitCount: parsedItems.length,
      abis: Array.from(abiSet).sort(),
    };
  }

  function labelFromFilename(name) {
    let s = String(name || "").replace(/\.(apk|apks|xapk|apkm)$/i, "");
    s = s.replace(/[_]+/g, " ").replace(/\s*-\s*/g, " ");
    s = s.replace(/\b(arm64-v8a|armeabi-v7a|arm64|armeabi|x86_64|x86|universal|release|debug|signed|splits)\b/gi, " ");
    s = s.replace(/\bv?\d+(\.\d+){1,3}(\s*\(\d+\))?/gi, " ");
    s = s.replace(/\s+/g, " ").trim();
    if (!s) return "";
    return s.split(" ").map((w) => w ? (w.charAt(0).toUpperCase() + w.slice(1)) : "").join(" ");
  }

  return {
    sha256Hex,
    parseApkInfo,
    parsePackageBundle,
    packSplitApksToZip,
    labelFromFilename,
  };
});
