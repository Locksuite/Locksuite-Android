"use strict";

/**
 * Inspector de APK y bundles divididos (.xapk / .apks) en Node.js puro:
 *   - Lectura por FileHandle del ZIP (sin cargar archivos grandes enteros en RAM)
 *   - Parser AXML (AndroidManifest.xml binario): packageName, versionCode, versionName, split, minSdk
 *   - Extracción de firma SHA-256 (APK Signature Scheme v3 / v2 + fallback PKCS#7 v1 en META-INF)
 *   - Detección real de ABIs (lib/<abi>/*.so y splits config.<abi>)
 *   - Re-empaquetado limpio de .xapk/.apks a .apks STORE verificando expectedPkg
 */
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");
const auroraZip = require("./aurora_zip");
const { MAX_APK_DOWNLOAD_BYTES } = require("./aurora_gplay");

const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
const MAX_CERT_ENTRY_BYTES = 1024 * 1024;
const MAX_SPLIT_ENTRIES = 128;

const ATTR_MIN_SDK_VERSION = 0x0101020c;
const ATTR_VERSION_CODE = 0x0101021b;
const ATTR_VERSION_NAME = 0x0101021c;
const ATTR_IS_FEATURE_SPLIT = 0x0101055a;
const ATTR_CONFIG_FOR_SPLIT = 0x0101055b;
const ATTR_VERSION_CODE_MAJOR = 0x01010576;

const APK_SIG_BLOCK_MAGIC = Buffer.from("APK Sig Block 42", "ascii");
const APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;
const APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0;
const APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;

function isSafeZipEntryName(name) {
  if (!name || typeof name !== "string") return false;
  if (name.startsWith("/") || name.startsWith("\\")) return false;
  if (name.includes("\\") || name.includes("\u0000") || name.includes("..")) return false;
  for (let i = 0; i < name.length; i++) {
    if (name.charCodeAt(i) < 0x20) return false;
  }
  const clean = name.endsWith("/") ? name.slice(0, -1) : name;
  return clean.split("/").every((p) => p !== "" && p !== "." && p !== "..");
}

async function readRange(fh, offset, length) {
  const buf = Buffer.allocUnsafe(length);
  let readTotal = 0;
  while (readTotal < length) {
    const { bytesRead } = await fh.read(buf, readTotal, length - readTotal, offset + readTotal);
    if (bytesRead === 0) break;
    readTotal += bytesRead;
  }
  return readTotal === length ? buf : buf.subarray(0, readTotal);
}

async function readZipDirectory(filePath) {
  const stat = await fs.promises.stat(filePath);
  if (stat.size < 22) {
    throw new Error("El archivo ZIP/APK está truncado o vacío.");
  }
  const fh = await fs.promises.open(filePath, "r");
  try {
    const tailLen = Math.min(stat.size, 65558);
    const tailOffset = stat.size - tailLen;
    const tail = await readRange(fh, tailOffset, tailLen);
    let eocdRel = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) {
        const commentLen = tail.readUInt16LE(i + 20);
        if (i + 22 + commentLen === tail.length) {
          eocdRel = i;
          break;
        }
      }
    }
    if (eocdRel < 0) {
      throw new Error("No se encontró el directorio central del archivo ZIP.");
    }

    const totalEntries = tail.readUInt16LE(eocdRel + 10);
    const cdSize = tail.readUInt32LE(eocdRel + 12);
    const cdOffset = tail.readUInt32LE(eocdRel + 16);
    const eocdOffset = tailOffset + eocdRel;
    if (totalEntries === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
      throw new Error("Formato ZIP64 no soportado.");
    }
    if (cdOffset + cdSize > eocdOffset) {
      throw new Error("Directorio central ZIP inválido o truncado.");
    }

    const cdBuf = await readRange(fh, cdOffset, cdSize);
    const entries = [];
    let pos = 0;
    for (let n = 0; n < totalEntries && pos + 46 <= cdBuf.length; n++) {
      if (cdBuf.readUInt32LE(pos) !== 0x02014b50) break;
      const compressionMethod = cdBuf.readUInt16LE(pos + 10);
      const compressedSize = cdBuf.readUInt32LE(pos + 20);
      const uncompressedSize = cdBuf.readUInt32LE(pos + 24);
      const nameLen = cdBuf.readUInt16LE(pos + 28);
      const extraLen = cdBuf.readUInt16LE(pos + 30);
      const commentLen = cdBuf.readUInt16LE(pos + 32);
      const localOffset = cdBuf.readUInt32LE(pos + 42);
      if (pos + 46 + nameLen > cdBuf.length) break;
      const name = cdBuf.subarray(pos + 46, pos + 46 + nameLen).toString("utf8");
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

    return { size: stat.size, cdOffset, cdSize, eocdOffset, entries };
  } finally {
    await fh.close();
  }
}

async function getEntryDataRange(fh, fileSize, entry) {
  const lh = await readRange(fh, entry.localOffset, 30);
  if (lh.length < 30 || lh.readUInt32LE(0) !== 0x04034b50) {
    throw new Error(`Cabecera ZIP local inválida para ${entry.name}`);
  }
  const lfNameLen = lh.readUInt16LE(26);
  const lfExtraLen = lh.readUInt16LE(28);
  const dataStart = entry.localOffset + 30 + lfNameLen + lfExtraLen;
  if (dataStart + entry.compressedSize > fileSize) {
    throw new Error(`Entrada ZIP truncada: ${entry.name}`);
  }
  return { dataStart, compressedSize: entry.compressedSize };
}

async function extractEntryBuffer(filePath, dirInfo, entry, maxUncompressed = MAX_MANIFEST_BYTES) {
  if (entry.uncompressedSize > maxUncompressed || entry.compressedSize > maxUncompressed * 2) {
    throw new Error(`Entrada ${entry.name} excede el máximo permitido (${maxUncompressed} bytes).`);
  }
  const fh = await fs.promises.open(filePath, "r");
  try {
    const { dataStart, compressedSize } = await getEntryDataRange(fh, dirInfo.size, entry);
    const raw = await readRange(fh, dataStart, compressedSize);
    if (entry.compressionMethod === 0) return raw;
    if (entry.compressionMethod === 8) {
      return zlib.inflateRawSync(raw, { maxOutputLength: maxUncompressed });
    }
    throw new Error(`Método de compresión ZIP no soportado (${entry.compressionMethod}) en ${entry.name}`);
  } finally {
    await fh.close();
  }
}

async function extractEntryToFile(filePath, dirInfo, entry, destPath, maxBytes = MAX_APK_DOWNLOAD_BYTES) {
  if (entry.uncompressedSize > maxBytes || entry.compressedSize > maxBytes) {
    throw new Error(`Entrada ${entry.name} supera el límite de tamaño permitido.`);
  }
  const fh = await fs.promises.open(filePath, "r");
  try {
    const { dataStart, compressedSize } = await getEntryDataRange(fh, dirInfo.size, entry);
    if (entry.compressionMethod !== 0 && entry.compressionMethod !== 8) {
      throw new Error(`Compresión ZIP no soportada (${entry.compressionMethod}) en ${entry.name}`);
    }
    const rs = fs.createReadStream(null, {
      fd: fh.fd,
      autoClose: false,
      start: dataStart,
      end: dataStart + compressedSize - 1,
      highWaterMark: 64 * 1024,
    });
    const ws = fs.createWriteStream(destPath, { highWaterMark: 64 * 1024 });
    let written = 0;

    await new Promise((resolve, reject) => {
      const targetStream = entry.compressionMethod === 8
        ? rs.pipe(zlib.createInflateRaw())
        : rs;
      targetStream.on("data", (chunk) => {
        written += chunk.length;
        if (written > maxBytes) {
          targetStream.destroy(new Error(`Entrada descomprimida ${entry.name} supera ${maxBytes} bytes.`));
          ws.destroy();
        }
      });
      rs.on("error", reject);
      targetStream.on("error", reject);
      ws.on("error", reject);
      ws.on("finish", resolve);
      targetStream.pipe(ws);
    });
    return written;
  } finally {
    await fh.close();
  }
}

function readUtf8Len(bytes, state) {
  let len = bytes[state.pos++];
  if (len & 0x80) {
    len = ((len & 0x7f) << 8) | bytes[state.pos++];
  }
  return len;
}

function readUtf16Len(bytes, state) {
  let len = bytes.readUInt16LE(state.pos);
  state.pos += 2;
  if (len & 0x8000) {
    const low = bytes.readUInt16LE(state.pos);
    state.pos += 2;
    len = ((len & 0x7fff) << 16) | low;
  }
  return len;
}

function parseStringPool(bytes, chunkOff, chunkHdrSize, chunkEnd) {
  const stringCount = bytes.readUInt32LE(chunkOff + 8);
  const flags = bytes.readUInt32LE(chunkOff + 16);
  const stringsStart = chunkOff + bytes.readUInt32LE(chunkOff + 20);
  const isUtf8 = (flags & 0x100) !== 0;
  const offsetsStart = chunkOff + chunkHdrSize;
  const out = new Array(stringCount);

  for (let i = 0; i < stringCount; i++) {
    if (offsetsStart + (i + 1) * 4 > chunkEnd) break;
    const rel = bytes.readUInt32LE(offsetsStart + i * 4);
    const state = { pos: stringsStart + rel };
    if (state.pos >= chunkEnd) {
      out[i] = "";
      continue;
    }
    if (isUtf8) {
      readUtf8Len(bytes, state);
      const byteLen = readUtf8Len(bytes, state);
      out[i] = bytes.subarray(state.pos, Math.min(chunkEnd, state.pos + byteLen)).toString("utf8");
    } else {
      const charLen = readUtf16Len(bytes, state);
      const end = Math.min(chunkEnd, state.pos + charLen * 2);
      out[i] = bytes.subarray(state.pos, end).toString("utf16le");
    }
  }
  return out;
}

function parseBinaryManifest(buf) {
  const bytes = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  if (bytes.length < 16 || bytes.readUInt16LE(0) !== 0x0003) {
    return { packageName: null };
  }
  const headerSize = bytes.readUInt16LE(2);
  const fileSize = Math.min(bytes.length, bytes.readUInt32LE(4));
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
    const chunkType = bytes.readUInt16LE(off);
    const chunkHdrSize = bytes.readUInt16LE(off + 2);
    const chunkSize = bytes.readUInt32LE(off + 4);
    if (chunkSize < 8 || off + chunkSize > fileSize) break;
    const chunkEnd = off + chunkSize;

    if (chunkType === 0x0001 && chunkHdrSize >= 28 && off + 28 <= chunkEnd) {
      strings = parseStringPool(bytes, off, chunkHdrSize, chunkEnd);
    } else if (chunkType === 0x0180 && chunkHdrSize >= 8 && off + chunkHdrSize <= chunkEnd) {
      const count = Math.floor((chunkEnd - (off + chunkHdrSize)) / 4);
      resourceMap = new Array(count);
      for (let i = 0; i < count; i++) {
        resourceMap[i] = bytes.readUInt32LE(off + chunkHdrSize + i * 4);
      }
    } else if (chunkType === 0x0102) {
      const extOff = off + chunkHdrSize;
      if (chunkHdrSize >= 16 && extOff + 20 <= chunkEnd) {
        const nameIdx = bytes.readInt32LE(extOff + 4);
        const elName = nameIdx >= 0 ? strings[nameIdx] : null;
        if (elName === "application") break;

        if (elName === "manifest" || elName === "uses-sdk") {
          if (elName === "manifest") hasManifest = true;
          const attrStart = bytes.readUInt16LE(extOff + 8);
          const attrSize = bytes.readUInt16LE(extOff + 10);
          const attrCount = bytes.readUInt16LE(extOff + 12);
          let a = extOff + attrStart;

          for (let i = 0; i < attrCount && attrSize >= 20 && a + 20 <= chunkEnd; i++, a += attrSize) {
            const attrNameIdx = bytes.readInt32LE(a + 4);
            const attrName = attrNameIdx >= 0 ? (strings[attrNameIdx] || "") : "";
            const resId = (attrNameIdx >= 0 && attrNameIdx < resourceMap.length) ? resourceMap[attrNameIdx] : 0;
            const rawValIdx = bytes.readInt32LE(a + 8);
            const valType = bytes[a + 15];
            const valData = bytes.readInt32LE(a + 16);
            const valUint = bytes.readUInt32LE(a + 16);

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
  const versionCode = (hasVcMinor || hasVcMajor) ? (vcMajor * 4294967296 + vcMinor) : null;
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

/**
 * Normaliza la huella de firmantes idéntica a `ApkSignatureVerifier.signerSetDigest`:
 *   - 1 firmante único: sha256(certDer)
 *   - N firmantes distintos: sha256(sortedHex.join(":"))
 */
function signerSetDigestHex(certBuffers) {
  if (!Array.isArray(certBuffers) || certBuffers.length === 0) return null;
  const perSigner = [];
  const rawByHex = new Map();
  for (const cert of certBuffers) {
    if (!cert || !cert.length) continue;
    const hex = crypto.createHash("sha256").update(cert).digest("hex");
    if (!rawByHex.has(hex)) {
      rawByHex.set(hex, cert);
      perSigner.push(hex);
    }
  }
  if (perSigner.length === 0) return null;
  perSigner.sort();
  if (perSigner.length === 1) return perSigner[0];
  return crypto.createHash("sha256").update(perSigner.join(":"), "utf8").digest("hex");
}

function readLengthPrefixedSlice(buf, pos) {
  if (pos + 4 > buf.length) return null;
  const len = buf.readUInt32LE(pos);
  const start = pos + 4;
  const end = start + len;
  if (end > buf.length) return null;
  return { slice: buf.subarray(start, end), next: end };
}

function parseApkV2OrV3Certificates(blockValue, isV3) {
  const certs = [];
  const signersList = readLengthPrefixedSlice(blockValue, 0);
  if (!signersList) return certs;
  let sPos = 0;
  const sBuf = signersList.slice;
  while (sPos < sBuf.length) {
    const signer = readLengthPrefixedSlice(sBuf, sPos);
    if (!signer) break;
    sPos = signer.next;
    const signedData = readLengthPrefixedSlice(signer.slice, 0);
    if (!signedData) continue;
    const sd = signedData.slice;
    // signedData: [digests (len-prefixed)] [certificates (len-prefixed)] ...
    const digests = readLengthPrefixedSlice(sd, 0);
    if (!digests) continue;
    const certsList = readLengthPrefixedSlice(sd, digests.next);
    if (!certsList) continue;
    // v3/v2: primer certificado del signer (el hoja; la cadena puede traer adicionales)
    if (certsList.slice.length >= 4) {
      const firstCert = readLengthPrefixedSlice(certsList.slice, 0);
      if (firstCert && firstCert.slice.length > 0) {
        try {
          // Verificar que sea un X.509 válido
          new crypto.X509Certificate(firstCert.slice);
          certs.push(firstCert.slice);
        } catch (_) {}
      }
    }
    if (isV3 && certs.length > 0) {
      // Para v3 alcanza con el signer vigente si solo hay rotación; continuamos por si hay múltiples
    }
  }
  return certs;
}

async function extractV2V3CertsFromApk(filePath, cdOffset) {
  if (cdOffset < 32) return [];
  const fh = await fs.promises.open(filePath, "r");
  try {
    const footer = await readRange(fh, cdOffset - 24, 24);
    if (footer.length < 24 || !footer.subarray(8, 24).equals(APK_SIG_BLOCK_MAGIC)) {
      return [];
    }
    const blockSizeFooter = Number(footer.readBigUInt64LE(0));
    if (!Number.isFinite(blockSizeFooter) || blockSizeFooter < 24 || blockSizeFooter > cdOffset || blockSizeFooter > 32 * 1024 * 1024) {
      return [];
    }
    const totalBlockBytes = blockSizeFooter + 8;
    const blockStart = cdOffset - totalBlockBytes;
    const block = await readRange(fh, blockStart, totalBlockBytes);
    if (block.length < totalBlockBytes) return [];
    const blockSizeHeader = Number(block.readBigUInt64LE(0));
    if (blockSizeHeader !== blockSizeFooter) return [];

    let pos = 8;
    const pairsEnd = block.length - 24;
    let v31Certs = [];
    let v3Certs = [];
    let v2Certs = [];

    while (pos + 12 <= pairsEnd) {
      const pairLen = Number(block.readBigUInt64LE(pos));
      if (!Number.isFinite(pairLen) || pairLen < 4 || pos + 8 + pairLen > pairsEnd) break;
      const id = block.readUInt32LE(pos + 8);
      const val = block.subarray(pos + 12, pos + 8 + pairLen);
      if (id === APK_SIGNATURE_SCHEME_V31_BLOCK_ID) {
        v31Certs = parseApkV2OrV3Certificates(val, true);
      } else if (id === APK_SIGNATURE_SCHEME_V3_BLOCK_ID) {
        v3Certs = parseApkV2OrV3Certificates(val, true);
      } else if (id === APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
        v2Certs = parseApkV2OrV3Certificates(val, false);
      }
      pos += 8 + pairLen;
    }
    return v31Certs.length ? v31Certs : v3Certs.length ? v3Certs : v2Certs;
  } finally {
    await fh.close();
  }
}

function readAsn1Tlv(buf, offset) {
  if (offset >= buf.length) return null;
  const tag = buf[offset];
  let lenBytePos = offset + 1;
  if (lenBytePos >= buf.length) return null;
  const firstLen = buf[lenBytePos++];
  let length = 0;
  if ((firstLen & 0x80) === 0) {
    length = firstLen;
  } else {
    const numOctets = firstLen & 0x7f;
    if (numOctets === 0 || numOctets > 4 || lenBytePos + numOctets > buf.length) return null;
    for (let i = 0; i < numOctets; i++) {
      length = (length << 8) | buf[lenBytePos++];
    }
  }
  const valueStart = lenBytePos;
  const end = valueStart + length;
  if (end > buf.length) return null;
  return { tag, valueStart, length, end, total: buf.subarray(offset, end) };
}

function extractCertsFromPkcs7Der(pkcs7Buf) {
  const certs = [];
  // ContentInfo SEQUENCE -> [0] SignedData -> SignedData SEQUENCE -> busca elemento contextual [0] (0xA0)
  const root = readAsn1Tlv(pkcs7Buf, 0);
  if (!root || root.tag !== 0x30) return certs;
  let pos = root.valueStart;
  while (pos < root.end) {
    const child = readAsn1Tlv(pkcs7Buf, pos);
    if (!child) break;
    if (child.tag === 0xa0) {
      const signedData = readAsn1Tlv(pkcs7Buf, child.valueStart);
      if (signedData && signedData.tag === 0x30) {
        let sdPos = signedData.valueStart;
        while (sdPos < signedData.end) {
          const field = readAsn1Tlv(pkcs7Buf, sdPos);
          if (!field) break;
          if (field.tag === 0xa0) {
            // [0] IMPLICIT CertificateSet (contiene uno o más X.509 Certificate SEQUENCE 0x30)
            let cPos = field.valueStart;
            while (cPos < field.end) {
              const certTlv = readAsn1Tlv(pkcs7Buf, cPos);
              if (!certTlv) break;
              if (certTlv.tag === 0x30) {
                try {
                  new crypto.X509Certificate(certTlv.total);
                  certs.push(Buffer.from(certTlv.total));
                } catch (_) {}
              }
              cPos = certTlv.end;
            }
          }
          sdPos = field.end;
        }
      }
    }
    pos = child.end;
  }
  return certs;
}

async function extractApkSignerSha256(filePath, dirInfo = null) {
  const dir = dirInfo || (await readZipDirectory(filePath));
  try {
    const v2v3 = await extractV2V3CertsFromApk(filePath, dir.cdOffset);
    if (v2v3.length > 0) {
      const hex = signerSetDigestHex(v2v3);
      if (hex) return hex;
    }
  } catch (_) {}

  // Fallback APK v1 (META-INF/*.RSA | *.DSA | *.EC)
  const certEntries = dir.entries.filter(
    (e) => !e.isDirectory && /^META-INF\/[^/]+\.(RSA|DSA|EC)$/i.test(e.name)
  );
  const v1Certs = [];
  for (const ce of certEntries) {
    try {
      const der = await extractEntryBuffer(filePath, dir, ce, MAX_CERT_ENTRY_BYTES);
      v1Certs.push(...extractCertsFromPkcs7Der(der));
    } catch (_) {}
  }
  return signerSetDigestHex(v1Certs);
}

function classifyAbiCoverage(abis) {
  const has64 = abis.includes("arm64-v8a");
  const has32 = abis.includes("armeabi-v7a") || abis.includes("armeabi");
  if (abis.length === 0 || (has64 && has32)) {
    return {
      variantKey: "universal",
      abis: ["arm64-v8a", "armeabi-v7a"],
    };
  }
  if (has64) {
    return { variantKey: "arm64_v8a", abis: ["arm64-v8a"] };
  }
  if (has32) {
    return { variantKey: "armeabi_v7a", abis: ["armeabi-v7a"] };
  }
  return {
    variantKey: abis[0].replace(/-/g, "_"),
    abis: abis.slice(),
  };
}

/**
 * Inspecciona un archivo descargado (.apk, .xapk o .apks), valida paquete, ABI real y firma.
 * Si es un bundle split (.xapk / .apks), empaqueta un `.apks` limpio (solo .apk) en `workDir`.
 */
async function inspectAndRepackArtifact(filePath, expectedPkg, workDir) {
  const dir = await readZipDirectory(filePath);
  if (dir.size > MAX_APK_DOWNLOAD_BYTES) {
    throw new Error(`El paquete supera el límite de ${Math.round(MAX_APK_DOWNLOAD_BYTES / (1024 * 1024))} MB.`);
  }

  const rootManifest = dir.entries.find((e) => !e.isDirectory && e.name === "AndroidManifest.xml");
  if (rootManifest) {
    const manifestBuf = await extractEntryBuffer(filePath, dir, rootManifest, MAX_MANIFEST_BYTES);
    const manifest = parseBinaryManifest(manifestBuf);
    if (!manifest.packageName) {
      throw new Error("El APK no tiene AndroidManifest.xml válido.");
    }
    if (expectedPkg && manifest.packageName !== expectedPkg) {
      throw new Error(`El APK pertenece a ${manifest.packageName} y no a ${expectedPkg}.`);
    }
    const rawAbis = extractAbisFromEntries(dir.entries);
    const cov = classifyAbiCoverage(rawAbis);
    const signerSha256 = await extractApkSignerSha256(filePath, dir);
    const sha256 = await computeFileSha256(filePath);

    return {
      packageName: manifest.packageName,
      versionCode: manifest.versionCode || 0,
      versionName: manifest.versionName || "",
      minSdk: manifest.minSdk || 0,
      bundleFormat: "apk",
      ext: "apk",
      splitCount: 0,
      abis: cov.abis,
      rawAbis,
      variantKey: cov.variantKey,
      signerSha256,
      sha256,
      sizeBytes: dir.size,
      filePath,
      hasObb: false,
    };
  }

  // Contenedor .xapk / .apks
  let hasObb = false;
  const apkEntries = [];
  for (const e of dir.entries) {
    if (e.isDirectory) continue;
    const lower = e.name.toLowerCase();
    if (lower.startsWith("android/obb/") && lower.endsWith(".obb")) {
      hasObb = true;
    }
    if (lower.endsWith(".apk")) {
      if (!isSafeZipEntryName(e.name)) {
        throw new Error(`Ruta insegura dentro del paquete: ${e.name}`);
      }
      apkEntries.push(e);
    }
  }
  if (apkEntries.length === 0) {
    throw new Error("El archivo descargado no contiene un APK válido.");
  }
  if (apkEntries.length > MAX_SPLIT_ENTRIES) {
    throw new Error(`Demasiados splits internos (${apkEntries.length}).`);
  }
  if (hasObb) {
    throw new Error("El paquete requiere archivos OBB externos no soportados en instalación silenciosa.");
  }

  const subDir = await fs.promises.mkdtemp(path.join(workDir, "unpack-"));
  const extracted = [];
  try {
    for (let i = 0; i < apkEntries.length; i++) {
      const e = apkEntries[i];
      const safeBase = path.basename(e.name).replace(/[^a-zA-Z0-9._-]/g, "_") || `split_${i}.apk`;
      const tmpApk = path.join(subDir, `${String(i).padStart(2, "0")}_${safeBase}`);
      await extractEntryToFile(filePath, dir, e, tmpApk, MAX_APK_DOWNLOAD_BYTES);
      const innerDir = await readZipDirectory(tmpApk);
      const mEntry = innerDir.entries.find((x) => !x.isDirectory && x.name === "AndroidManifest.xml");
      if (!mEntry) {
        throw new Error(`Falta AndroidManifest.xml en el split ${e.name}`);
      }
      const manifest = parseBinaryManifest(await extractEntryBuffer(tmpApk, innerDir, mEntry, MAX_MANIFEST_BYTES));
      if (!manifest.packageName) {
        throw new Error(`Manifiesto inválido en el split ${e.name}`);
      }
      if (expectedPkg && manifest.packageName !== expectedPkg) {
        throw new Error(`Split ${e.name} pertenece a ${manifest.packageName} y no a ${expectedPkg}.`);
      }
      extracted.push({
        origName: e.name,
        entryName: manifest.splitName ? safeBase : "base.apk",
        filePath: tmpApk,
        dir: innerDir,
        manifest,
        abis: extractAbisFromEntries(innerDir.entries),
      });
    }

    const bases = extracted.filter((x) => !x.manifest.splitName);
    if (bases.length !== 1) {
      throw new Error(`El paquete contiene ${bases.length} APKs base (se esperaba exactamente 1).`);
    }
    const base = bases[0];
    const abiSet = new Set();
    for (const item of extracted) {
      for (const a of item.abis) abiSet.add(a);
      const mapped = mapSplitNameAbi(item.manifest.splitName);
      if (mapped) abiSet.add(mapped);
    }
    const rawAbis = Array.from(abiSet).sort();
    const cov = classifyAbiCoverage(rawAbis);
    const signerSha256 = await extractApkSignerSha256(base.filePath, base.dir);

    // Ordenar con base.apk primero y nombres únicos
    const used = new Set(["base.apk"]);
    const packEntries = [{ name: "base.apk", filePath: base.filePath }];
    for (const item of extracted) {
      if (item === base) continue;
      let name = item.entryName;
      let suffix = 2;
      while (used.has(name)) {
        name = item.entryName.replace(/\.apk$/i, `_${suffix++}.apk`);
      }
      used.add(name);
      packEntries.push({ name, filePath: item.filePath });
    }

    const outApks = path.join(workDir, `repack_${cov.variantKey}_${Date.now()}.apks`);
    const packed = await auroraZip.writeStoreZipToFile(packEntries, outApks);
    if (packed.sizeBytes > MAX_APK_DOWNLOAD_BYTES) {
      await fs.promises.unlink(outApks).catch(() => {});
      throw new Error(`El paquete re-empaquetado supera el tope de ${Math.round(MAX_APK_DOWNLOAD_BYTES / (1024 * 1024))} MB.`);
    }

    return {
      packageName: base.manifest.packageName,
      versionCode: base.manifest.versionCode || 0,
      versionName: base.manifest.versionName || "",
      minSdk: base.manifest.minSdk || 0,
      bundleFormat: "apks",
      ext: "apks",
      splitCount: packEntries.length - 1,
      abis: cov.abis,
      rawAbis,
      variantKey: cov.variantKey,
      signerSha256,
      sha256: packed.sha256,
      sizeBytes: packed.sizeBytes,
      filePath: outApks,
      hasObb: false,
    };
  } finally {
    await fs.promises.rm(subDir, { recursive: true, force: true }).catch(() => {});
  }
}

async function computeFileSha256(filePath) {
  const hash = crypto.createHash("sha256");
  await new Promise((resolve, reject) => {
    const rs = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 });
    rs.on("data", (c) => hash.update(c));
    rs.on("end", resolve);
    rs.on("error", reject);
  });
  return hash.digest("hex");
}

module.exports = {
  readZipDirectory,
  parseBinaryManifest,
  extractAbisFromEntries,
  mapSplitNameAbi,
  signerSetDigestHex,
  extractApkSignerSha256,
  classifyAbiCoverage,
  inspectAndRepackArtifact,
  isSafeZipEntryName,
  computeFileSha256,
};
