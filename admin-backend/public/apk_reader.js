/* ═══════════════════════════════════════════════════════════════════════════
 * apk_reader.js — lectura local de un .apk en el navegador (SHA-256 + AXML).
 *
 * Lee directamente el `ArrayBuffer` del archivo elegido desde la PC para calcular:
 *   1. Huella SHA-256 en hex minúsculas (mismo formato que exige `SelfUpdater.kt`).
 *   2. `packageName`, `versionName` y `versionCode` desde `AndroidManifest.xml` (AXML)
 *      adentro del ZIP sin subir el archivo primero ni depender de servidores externos.
 *
 * Funciona tanto en el navegador (`window.ApkReader`) como en Node (`module.exports`).
 * ═══════════════════════════════════════════════════════════════════════════ */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.ApkReader = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

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

  function findAndroidManifestEntry(bytes) {
    if (!bytes || bytes.length < 22) return null;
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
    if (eocd < 0) return null;

    const totalEntries = dv.getUint16(eocd + 10, true);
    let pos = dv.getUint32(eocd + 16, true);
    for (let n = 0; n < totalEntries && pos + 46 <= bytes.length; n++) {
      if (dv.getUint32(pos, true) !== 0x02014b50) break;
      const compressionMethod = dv.getUint16(pos + 10, true);
      const compressedSize = dv.getUint32(pos + 20, true);
      const nameLen = dv.getUint16(pos + 28, true);
      const extraLen = dv.getUint16(pos + 30, true);
      const commentLen = dv.getUint16(pos + 32, true);
      const localOffset = dv.getUint32(pos + 42, true);
      if (pos + 46 + nameLen > bytes.length) break;
      const name = decodeLatin1OrUtf8(bytes.subarray(pos + 46, pos + 46 + nameLen));
      if (name === "AndroidManifest.xml") {
        if (localOffset + 30 > bytes.length || dv.getUint32(localOffset, true) !== 0x04034b50) {
          return null;
        }
        const lfNameLen = dv.getUint16(localOffset + 26, true);
        const lfExtraLen = dv.getUint16(localOffset + 28, true);
        const dataStart = localOffset + 30 + lfNameLen + lfExtraLen;
        if (dataStart + compressedSize > bytes.length) return null;
        return {
          compressionMethod,
          data: bytes.subarray(dataStart, dataStart + compressedSize),
        };
      }
      pos += 46 + nameLen + extraLen + commentLen;
    }
    return null;
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

  function parseStringPool(bytes, dv, chunkOff) {
    const stringCount = dv.getUint32(chunkOff + 8, true);
    const flags = dv.getUint32(chunkOff + 16, true);
    const stringsStart = chunkOff + dv.getUint32(chunkOff + 20, true);
    const isUtf8 = (flags & 0x100) !== 0;
    const offsetsStart = chunkOff + 28;
    const out = new Array(stringCount);

    for (let i = 0; i < stringCount; i++) {
      const rel = dv.getUint32(offsetsStart + i * 4, true);
      const state = { pos: stringsStart + rel };
      if (state.pos >= bytes.length) {
        out[i] = "";
        continue;
      }
      if (isUtf8) {
        readUtf8Len(bytes, state); // char count
        const byteLen = readUtf8Len(bytes, state);
        out[i] = decodeLatin1OrUtf8(bytes.subarray(state.pos, Math.min(bytes.length, state.pos + byteLen)));
      } else {
        const charLen = readUtf16Len(dv, state);
        const end = Math.min(bytes.length, state.pos + charLen * 2);
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
    let off = headerSize;

    while (off + 8 <= fileSize) {
      const chunkType = dv.getUint16(off, true);
      const chunkHdrSize = dv.getUint16(off + 2, true);
      const chunkSize = dv.getUint32(off + 4, true);
      if (chunkSize < 8 || off + chunkSize > fileSize) break;

      if (chunkType === 0x0001) {
        strings = parseStringPool(bytes, dv, off);
      } else if (chunkType === 0x0102 && off + 36 <= off + chunkSize) {
        const nameIdx = dv.getInt32(off + 20, true);
        const elName = nameIdx >= 0 ? strings[nameIdx] : null;
        if (elName === "manifest") {
          const attrStart = dv.getUint16(off + 24, true);
          const attrSize = dv.getUint16(off + 26, true);
          const attrCount = dv.getUint16(off + 28, true);
          let packageName = null;
          let versionName = null;
          let versionCode = null;
          let a = off + 16 + attrStart;
          for (let i = 0; i < attrCount && attrSize >= 20 && a + 20 <= off + chunkSize; i++, a += attrSize) {
            const attrNameIdx = dv.getInt32(a + 4, true);
            const attrName = attrNameIdx >= 0 ? strings[attrNameIdx] : "";
            const rawValIdx = dv.getInt32(a + 8, true);
            const valType = bytes[a + 15];
            const valData = dv.getInt32(a + 16, true);
            const readStr = () => {
              if (rawValIdx >= 0 && strings[rawValIdx] != null) return strings[rawValIdx];
              if (valType === 0x03 && valData >= 0 && strings[valData] != null) return strings[valData];
              return null;
            };
            if (attrName === "package") packageName = readStr();
            else if (attrName === "versionName") versionName = readStr();
            else if (attrName === "versionCode" && (valType === 0x10 || valType === 0x11)) {
              versionCode = dv.getUint32(a + 16, true);
            }
          }
          return { packageName, versionName, versionCode };
        }
      }
      off += Math.max(chunkSize, chunkHdrSize);
    }
    return { packageName: null };
  }

  async function parseApkInfo(arrayBuffer) {
    try {
      const bytes = arrayBuffer instanceof Uint8Array ? arrayBuffer : new Uint8Array(arrayBuffer);
      const entry = findAndroidManifestEntry(bytes);
      if (!entry) return { packageName: null };
      const xmlBytes = await inflateRaw(entry.data, entry.compressionMethod);
      return parseBinaryManifest(xmlBytes);
    } catch (_) {
      return { packageName: null };
    }
  }

  function labelFromFilename(name) {
    let s = String(name || "").replace(/\.apk$/i, "");
    s = s.replace(/[_]+/g, " ").replace(/\s*-\s*/g, " ");
    s = s.replace(/\b(arm64-v8a|armeabi-v7a|arm64|armeabi|x86_64|x86|universal|release|debug|signed)\b/gi, " ");
    s = s.replace(/\bv?\d+(\.\d+){1,3}(\s*\(\d+\))?/gi, " ");
    s = s.replace(/\s+/g, " ").trim();
    if (!s) return "";
    return s.split(" ").map((w) => w ? (w.charAt(0).toUpperCase() + w.slice(1)) : "").join(" ");
  }

  return {
    sha256Hex,
    parseApkInfo,
    labelFromFilename,
  };
});
