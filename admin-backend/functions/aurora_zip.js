"use strict";

/**
 * Empaquetador ZIP puro en Node.js (método STORE = 0, sin recompresión).
 * Arma archivos `.apks` compatibles con SplitApkBundle.kt en Android sin depender
 * de binarios externos ni paquetes npm adicionales.
 */
const fs = require("fs");
const crypto = require("crypto");

const CRC_TABLE = (() => {
  const tbl = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    tbl[i] = c >>> 0;
  }
  return tbl;
})();

function crc32Update(crc, buf) {
  let c = (crc ^ 0xffffffff) >>> 0;
  for (let i = 0; i < buf.length; i++) {
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function crc32Buffer(buf) {
  return crc32Update(0, buf);
}

function assertSafeEntryName(name) {
  if (typeof name !== "string" || !name || name.length > 240) {
    throw new Error("Nombre de entrada ZIP inválido.");
  }
  if (name.startsWith("/") || name.startsWith("\\") || name.includes("\\") || name.includes("..") || /[\u0000-\u001f]/.test(name)) {
    throw new Error(`Ruta ZIP insegura: ${name}`);
  }
}

function dosTimeDate(date = new Date()) {
  const year = Math.max(1980, date.getUTCFullYear());
  const dosTime =
    ((date.getUTCHours() & 0x1f) << 11) |
    ((date.getUTCMinutes() & 0x3f) << 5) |
    ((date.getUTCSeconds() >> 1) & 0x1f);
  const dosDate =
    (((year - 1980) & 0x7f) << 9) |
    (((date.getUTCMonth() + 1) & 0x0f) << 5) |
    (date.getUTCDate() & 0x1f);
  return { dosTime, dosDate };
}

function makeLocalHeader(nameBuf, crc, size, dosTime, dosDate) {
  const h = Buffer.alloc(30 + nameBuf.length);
  h.writeUInt32LE(0x04034b50, 0);
  h.writeUInt16LE(20, 4);          // version needed
  h.writeUInt16LE(0x0800, 6);      // UTF-8 flag
  h.writeUInt16LE(0, 8);           // compression = STORE
  h.writeUInt16LE(dosTime, 10);
  h.writeUInt16LE(dosDate, 12);
  h.writeUInt32LE(crc >>> 0, 14);
  h.writeUInt32LE(size >>> 0, 18);
  h.writeUInt32LE(size >>> 0, 22);
  h.writeUInt16LE(nameBuf.length, 26);
  h.writeUInt16LE(0, 28);
  nameBuf.copy(h, 30);
  return h;
}

function makeCentralHeader(nameBuf, crc, size, offset, dosTime, dosDate) {
  const h = Buffer.alloc(46 + nameBuf.length);
  h.writeUInt32LE(0x02014b50, 0);
  h.writeUInt16LE(20, 4);          // version made by
  h.writeUInt16LE(20, 6);          // version needed
  h.writeUInt16LE(0x0800, 8);      // UTF-8 flag
  h.writeUInt16LE(0, 10);          // compression = STORE
  h.writeUInt16LE(dosTime, 12);
  h.writeUInt16LE(dosDate, 14);
  h.writeUInt32LE(crc >>> 0, 16);
  h.writeUInt32LE(size >>> 0, 20);
  h.writeUInt32LE(size >>> 0, 24);
  h.writeUInt16LE(nameBuf.length, 28);
  h.writeUInt16LE(0, 30);
  h.writeUInt16LE(0, 32);
  h.writeUInt16LE(0, 34);
  h.writeUInt16LE(0, 36);
  h.writeUInt32LE(0, 38);
  h.writeUInt32LE(offset >>> 0, 42);
  nameBuf.copy(h, 46);
  return h;
}

function makeEndOfCentralDirectory(count, cdSize, cdOffset) {
  const h = Buffer.alloc(22);
  h.writeUInt32LE(0x06054b50, 0);
  h.writeUInt16LE(0, 4);
  h.writeUInt16LE(0, 6);
  h.writeUInt16LE(count, 8);
  h.writeUInt16LE(count, 10);
  h.writeUInt32LE(cdSize >>> 0, 12);
  h.writeUInt32LE(cdOffset >>> 0, 16);
  h.writeUInt16LE(0, 20);
  return h;
}

function buildStoreZipBuffer(entries) {
  const { dosTime, dosDate } = dosTimeDate();
  const chunks = [];
  const cd = [];
  let offset = 0;
  for (const e of entries) {
    assertSafeEntryName(e.name);
    const data = Buffer.isBuffer(e.buffer) ? e.buffer : Buffer.from(e.buffer || "");
    const nameBuf = Buffer.from(e.name, "utf8");
    const crc = crc32Buffer(data);
    const lh = makeLocalHeader(nameBuf, crc, data.length, dosTime, dosDate);
    cd.push(makeCentralHeader(nameBuf, crc, data.length, offset, dosTime, dosDate));
    chunks.push(lh, data);
    offset += lh.length + data.length;
  }
  const cdBuf = Buffer.concat(cd);
  const eocd = makeEndOfCentralDirectory(entries.length, cdBuf.length, offset);
  return Buffer.concat([...chunks, cdBuf, eocd]);
}

async function computeFileCrc32AndSize(filePath) {
  const stat = await fs.promises.stat(filePath);
  let crc = 0;
  await new Promise((resolve, reject) => {
    const rs = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 });
    rs.on("data", (chunk) => { crc = crc32Update(crc, chunk); });
    rs.on("end", resolve);
    rs.on("error", reject);
  });
  return { size: stat.size, crc };
}

/**
 * Escribe un .apks (ZIP STORE) leyendo cada APK desde disco en streaming (sin cargar todo el
 * paquete en RAM) y retorna { sizeBytes, sha256 } del archivo destino.
 * @param {Array<{name: string, filePath: string}>} entries
 * @param {string} destPath
 */
async function writeStoreZipToFile(entries, destPath) {
  const { dosTime, dosDate } = dosTimeDate();
  const hash = crypto.createHash("sha256");
  const ws = fs.createWriteStream(destPath, { highWaterMark: 64 * 1024 });
  let offset = 0;
  const cd = [];

  const writeChunk = (buf) => new Promise((resolve, reject) => {
    hash.update(buf);
    offset += buf.length;
    if (offset > 0xfffffffe) {
      reject(new Error("El paquete .apks excede el límite ZIP32 de 4 GB."));
      return;
    }
    if (ws.write(buf)) resolve();
    else ws.once("drain", resolve);
  });

  try {
    for (const e of entries) {
      assertSafeEntryName(e.name);
      const { size, crc } = await computeFileCrc32AndSize(e.filePath);
      const nameBuf = Buffer.from(e.name, "utf8");
      const entryOffset = offset;
      const lh = makeLocalHeader(nameBuf, crc, size, dosTime, dosDate);
      await writeChunk(lh);

      await new Promise((resolve, reject) => {
        const rs = fs.createReadStream(e.filePath, { highWaterMark: 64 * 1024 });
        rs.on("data", async (chunk) => {
          rs.pause();
          try {
            await writeChunk(chunk);
            rs.resume();
          } catch (err) {
            rs.destroy(err);
          }
        });
        rs.on("end", resolve);
        rs.on("error", reject);
      });

      cd.push(makeCentralHeader(nameBuf, crc, size, entryOffset, dosTime, dosDate));
    }

    const cdOffset = offset;
    const cdBuf = Buffer.concat(cd);
    await writeChunk(cdBuf);
    const eocd = makeEndOfCentralDirectory(entries.length, cdBuf.length, cdOffset);
    await writeChunk(eocd);

    await new Promise((resolve, reject) => {
      ws.on("finish", resolve);
      ws.on("error", reject);
      ws.end();
    });
    return { sizeBytes: offset, sha256: hash.digest("hex") };
  } catch (err) {
    try { ws.destroy(); } catch (_) {}
    throw err;
  }
}

module.exports = {
  crc32Buffer,
  buildStoreZipBuffer,
  writeStoreZipToFile,
  assertSafeEntryName,
};
