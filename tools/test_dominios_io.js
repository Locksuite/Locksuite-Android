const fs = require("fs");
const vm = require("vm");
const assert = require("assert");

const ctx = { window: {}, console, TextDecoder, DecompressionStream, Blob, Response };
ctx.window = ctx;
vm.createContext(ctx);

for (const rel of [
  "admin-backend/public/default_whitelist_catalog.js",
  "admin-backend/public/dominios_catalogo_io.js",
  "admin-backend/public/dominios_clasificar.js",
]) {
  vm.runInContext(fs.readFileSync(rel, "utf8"), ctx, { filename: rel });
}

const IO = ctx.DomCatalogoIO;
const Clasif = ctx.DomClasif;

assert(typeof ctx.LOCKSUITE_DEFAULT_WHITELIST_TXT === "string" && ctx.LOCKSUITE_DEFAULT_WHITELIST_TXT.length > 1000,
  "default_whitelist_catalog.js debe definir LOCKSUITE_DEFAULT_WHITELIST_TXT");

// 1. Parseo del catálogo por defecto
const parsed = IO.parseCatalogTxt(ctx.LOCKSUITE_DEFAULT_WHITELIST_TXT);
assert(!parsed.error, "No debe dar error al parsear default_whitelist_catalog.txt: " + parsed.error);
const snap = IO.buildSnapshot(parsed.apps, parsed.globalAllow, parsed.globalBlock);
assert.strictEqual(snap.apps.length, 25, "25 apps por defecto");
assert.strictEqual(snap.globalAllow.length, 16, "16 permitidos globales");
assert.strictEqual(snap.globalBlock.length, 242, "242 prohibidos globales");

// Round-trip TXT
const txt = IO.serializeCatalogTxt(snap, { deviceName: "Test" });
const parsed2 = IO.parseCatalogTxt(txt);
assert(!parsed2.error);
const snap2 = IO.buildSnapshot(parsed2.apps, parsed2.globalAllow, parsed2.globalBlock);
assert.strictEqual(snap2.fingerprint, snap.fingerprint, "Round-trip debe preservar fingerprint");

// 2. DomainSafety (allowRejectReason)
assert(IO.allowRejectReason("cloudfront.net"), "Debe rechazar cloudfront.net");
assert(IO.allowRejectReason("com.ar"), "Debe rechazar com.ar");
assert.strictEqual(IO.allowRejectReason("api.waze.com"), null, "Debe aceptar api.waze.com");

// 3. Expiración de respaldos 30 días + ⭐ favoritos
const now = Date.now();
const expired = IO.findExpiredBackupIds({
  b_old: { createdAt: now - 31 * 86400000, favorite: false },
  b_old_fav: { createdAt: now - 60 * 86400000, favorite: true },
  b_fresh: { createdAt: now - 5 * 86400000, favorite: false },
}, now);
assert.deepStrictEqual(Array.from(expired), ["b_old"], "Solo expira b_old, los favoritos no vencen");

// 4. Clasificador heurístico
const cl = Clasif.clasificar("doubleclick.net");
assert.strictEqual(cl.accion, "bloquear");

// 5. ZIP mínimo sin compresión (STORE method = 0) con word/document.xml leído vía readTextFile
function buildMinimalDocx(xmlStr) {
  const name = Buffer.from("word/document.xml", "utf8");
  const data = Buffer.from(xmlStr, "utf8");
  const lfh = Buffer.alloc(30);
  lfh.writeUInt32LE(0x04034b50, 0);
  lfh.writeUInt16LE(20, 4);
  lfh.writeUInt16LE(0, 6);
  lfh.writeUInt16LE(0, 8);
  lfh.writeUInt32LE(data.length, 18);
  lfh.writeUInt32LE(data.length, 22);
  lfh.writeUInt16LE(name.length, 26);
  const localOffset = 0;
  const cdOffset = lfh.length + name.length + data.length;
  const cdh = Buffer.alloc(46);
  cdh.writeUInt32LE(0x02014b50, 0);
  cdh.writeUInt16LE(20, 4);
  cdh.writeUInt16LE(20, 6);
  cdh.writeUInt16LE(0, 10);
  cdh.writeUInt32LE(data.length, 20);
  cdh.writeUInt32LE(data.length, 24);
  cdh.writeUInt16LE(name.length, 28);
  cdh.writeUInt32LE(localOffset, 42);
  const cdSize = cdh.length + name.length;
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(1, 8);
  eocd.writeUInt16LE(1, 10);
  eocd.writeUInt32LE(cdSize, 12);
  eocd.writeUInt32LE(cdOffset, 16);
  return Buffer.concat([lfh, name, data, cdh, name, eocd]);
}

(async () => {
  const xml = '<w:document><w:body><w:p><w:r><w:t># lockfox-dominios-v1</w:t></w:r></w:p><w:p><w:r><w:t>[GLOBAL: PERMITIDOS]</w:t></w:r></w:p><w:p><w:r><w:t>waze.com</w:t></w:r></w:p></w:body></w:document>';
  const buf = buildMinimalDocx(xml);
  const extracted = await IO.readTextFile({
    name: "test.docx",
    arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength)
  });
  const pDocx = IO.parseCatalogTxt(extracted);
  assert(!pDocx.error && pDocx.globalAllow.includes("waze.com"), "Debe extraer waze.com del DOCX");
  console.log("OK test_dominios_io.js: 25 apps, 16 globalAllow, 242 globalBlock, DomainSafety, 30d backups, DOCX STORE OK.");
})();
