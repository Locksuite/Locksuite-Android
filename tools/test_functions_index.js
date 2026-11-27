#!/usr/bin/env node
"use strict";
/**
 * Prueba de admin-backend/functions/index.js CARGADO ENTERO, con un Firebase falso en memoria
 * (auditoría de seguridad del 9/11/2026). Sin red y sin instalar nada:
 *
 *     node tools/test_functions_index.js
 *
 * Qué afirma: el comportamiento de sendCommandV8 / checkPlayApps frente a sesiones revocadas (401),
 * ids de equipo armados a mano, PINs incorrectos y bloqueo por intentos, "recordar este celular"
 * (que lo escribe SOLO la Function), CHANGE_PIN y que la excepción de PIN de UPDATE_LOCKSUITE y
 * compañía siga igual que siempre.
 * Qué NO afirma: nada de lo que hacen de verdad Firebase Auth / RTDB / FCM. Los dobles imitan solo
 * el contrato que usa index.js. Las reglas de la base se prueban aparte (tools/rules_tests).
 */
const assert = require("assert");
// Las Functions registran con console.error cada error que devuelven (también los esperados: PIN
// incorrecto, bloqueo...). Acá se silencian para que la salida de la prueba se lea; `report` imprime lo que importa.
const report = console.error.bind(console);
console.error = () => {};
const crypto = require("crypto");
const path = require("path");
const Module = require("module");

// LS_FN_DIR existe para las pruebas de mutación (apuntar a una copia alterada de las Functions).
const FN_DIR = process.env.LS_FN_DIR || path.join(__dirname, "..", "admin-backend", "functions");
const ps = require(path.join(FN_DIR, "pin_security.js"));

// ── Firebase falso ──────────────────────────────────────────────────────────
const state = {};            // se reinicia en cada prueba (resetWorld)
function resetWorld() {
  state.db = {};
  state.fcm = [];
  state.storageOps = [];
  state.tokenChecks = [];
  state.failFcm = null;
  state.dbReadError = null;
  state.now = 1_700_000_000_000;
}
const TOKENS = {
  "tok-admin": () => ({ uid: "adm1", email: "dueno@example.com" }),
  "tok-admin2": () => ({ uid: "adm2", email: "otro@example.com" }),
  "tok-intruso": () => ({ uid: "intruso" }),
  "tok-revocado": () => { throw Object.assign(new Error("The Firebase ID token has been revoked."), { code: "auth/id-token-revoked" }); },
  "tok-vencido": () => { throw Object.assign(new Error("Firebase ID token has expired. Get a fresh ID token"), { code: "auth/id-token-expired" }); },
  "tok-basura": () => { throw Object.assign(new Error("Decoding Firebase ID token failed."), { code: "auth/argument-error" }); },
};

function segs(p) { return String(p).split("/").filter(Boolean); }
function getAt(root, p) {
  let cur = root;
  for (const s of segs(p)) {
    if (cur === null || typeof cur !== "object" || !(s in cur)) return null;
    cur = cur[s];
  }
  return cur === undefined ? null : cur;
}
// La base real no guarda nulls (ni como valor ni dentro de un objeto): un null "borra".
function stripNulls(v) {
  if (v === null || v === undefined) return null;
  if (typeof v !== "object") return v;
  const out = {};
  for (const k of Object.keys(v)) {
    const x = stripNulls(v[k]);
    if (x !== null) out[k] = x;
  }
  return Object.keys(out).length ? out : null;
}
function setAt(root, p, value) {
  value = stripNulls(value);
  const parts = segs(p);
  if (parts.length === 0) throw new Error("set en la raíz");
  let cur = root;
  for (let i = 0; i < parts.length - 1; i++) {
    if (cur[parts[i]] === null || typeof cur[parts[i]] !== "object") cur[parts[i]] = {};
    cur = cur[parts[i]];
  }
  const last = parts[parts.length - 1];
  if (value === null || value === undefined) delete cur[last];
  else cur[last] = JSON.parse(JSON.stringify(value));
  // limpiar padres vacíos, como la base real
  for (let i = parts.length - 1; i > 0; i--) {
    const parent = getAt(root, parts.slice(0, i).join("/"));
    if (parent && typeof parent === "object" && Object.keys(parent).length === 0) setAt(root, parts.slice(0, i).join("/"), null);
  }
}
function snap(value, key) {
  return {
    key: key || null,
    val: () => (value === undefined ? null : JSON.parse(JSON.stringify(value))),
    exists: () => value !== null && value !== undefined,
    forEach(fn) {
      if (value && typeof value === "object") for (const k of Object.keys(value)) fn(snap(value[k], k));
    },
  };
}
function makeRef(p) {
  const ref = {
    path: p,
    child: (c) => makeRef(p + "/" + c),
    async once() {
      if (state.dbReadError && String(p).includes(state.dbReadError.match)) throw state.dbReadError.error;
      return snap(getAt(state.db, p), segs(p).pop());
    },
    async set(v) { setAt(state.db, p, v); },
    async remove() { setAt(state.db, p, null); },
    async update(obj) { for (const k of Object.keys(obj)) setAt(state.db, p + "/" + k, obj[k]); },
    push(v) {
      const id = "k" + crypto.randomBytes(6).toString("hex");
      setAt(state.db, p + "/" + id, v);
      return makeRef(p + "/" + id);
    },
    async transaction(fn) {
      fn(null);
      const out = fn(getAt(state.db, p));
      if (out === undefined) return { committed: false };
      setAt(state.db, p, out);
      return { committed: true };
    },
  };
  return ref;
}
const fakeAdmin = {
  initializeApp() {},
  auth: () => ({
    async verifyIdToken(token, checkRevoked) {
      state.tokenChecks.push({ token, checkRevoked });
      const f = TOKENS[token];
      if (!f) throw Object.assign(new Error("Decoding Firebase ID token failed."), { code: "auth/argument-error" });
      return f();
    },
  }),
  database: Object.assign(() => ({ ref: (p) => makeRef(p) }), { ServerValue: { TIMESTAMP: { ".sv": "timestamp" } } }),
  messaging: () => ({
    async send(m) {
      if (state.failFcm) throw state.failFcm;
      state.fcm.push(m);
      return "msg-id";
    },
  }),
  storage: () => ({
    bucket(bucketName) {
      return {
        name: bucketName,
        file(objectPath) {
          return {
            name: objectPath,
            async createResumableUpload(opts) {
              state.storageOps.push({ op: "createResumableUpload", bucketName, objectPath, opts });
              return ["https://storage.googleapis.com/upload/resumable/" + encodeURIComponent(objectPath)];
            },
            async delete(opts) {
              state.storageOps.push({ op: "delete", bucketName, objectPath, opts });
            },
          };
        },
        async getFiles() {
          return [[]];
        },
      };
    },
  }),
};
const fakeHttps = { onRequest: (opts, handler) => { handler.__opts = opts; return handler; } };

const origLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === "firebase-admin") return fakeAdmin;
  if (request === "firebase-functions/v2/https") return fakeHttps;
  return origLoad.apply(this, arguments);
};
const fn = require(path.join(FN_DIR, "index.js"));
Module._load = origLoad;

// Date.now controlado, para el bloqueo por tiempo
const realNow = Date.now;
Date.now = () => state.now;

function call(handler, { method = "POST", headers = {}, body = {} } = {}) {
  return new Promise((resolve) => {
    const res = {
      headers: {},
      statusCode: 200,
      set(k, v) { res.headers[k.toLowerCase()] = v; return res; },
      status(c) { res.statusCode = c; return res; },
      json(b) { res.body = b; resolve(res); return res; },
      send(b) { res.body = b; resolve(res); return res; },
    };
    Promise.resolve(handler({ method, headers, body, ip: "198.51.100.7" }, res)).catch((e) => { res.crash = e; resolve(res); });
  });
}
const send = (body, token = "tok-admin", extraHeaders = {}) =>
  call(fn.sendCommandV8, { headers: { authorization: "Bearer " + token, ...extraHeaders }, body });

// ── mundo de prueba ─────────────────────────────────────────
const DEV = "91431ec29bc2fe91";
const SALT = "AAECAwQFBgcICQoLDA0ODw==";
const PIN = "493817";
const SECRET = "s".repeat(40);
const LEGACY = "dTPzBbVQSGugCPTukSejxWbLzDJXoCaos96J1CxmH9o=";       // SHA-256(sal ‖ "493817")
function seedWorld({ versionCode = 144, conPin = true, fcmToken = "fcm-tok", commandSecret = SECRET, trusted = null } = {}) {
  state.db.authorizedAdminsUids = { adm1: true, adm2: true };
  state.db.devices = { [DEV]: { fcmToken, versionCode, deviceName: "Shuli", ...(trusted ? { trustedAdmins: trusted } : {}) } };
  const sec = { ownerUid: "dev-uid", commandSecret, pinSalt: SALT };
  if (conPin) sec.pinHash = LEGACY;
  state.db.deviceSecrets = { [DEV]: sec };
}

let pass = 0;
const pending = [];
function test(name, f) {
  pending.push(async () => {
    resetWorld();
    try { await f(); pass++; }
    catch (e) { report("✗ " + name + "\n  " + (e && e.stack || e)); process.exitCode = 1; }
  });
}

// ── sesión y autorización ───────────────────────────────────
test("sin Authorization → 401", async () => {
  seedWorld();
  const r = await call(fn.sendCommandV8, { body: { deviceId: DEV, command: "VERIFY_PIN" } });
  assert.strictEqual(r.statusCode, 401);
});
test("el ID token se verifica CON checkRevoked=true (sendCommandV8 y checkPlayApps)", async () => {
  seedWorld();
  await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN });
  await call(fn.checkPlayApps, { headers: { authorization: "Bearer tok-admin" }, body: { packages: [] } });
  assert.ok(state.tokenChecks.length >= 2);
  assert.ok(state.tokenChecks.every((c) => c.checkRevoked === true), JSON.stringify(state.tokenChecks));
});
test("token revocado → 401 SESSION_REVOKED (no 500), sin tocar la base", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "LOCK_DEVICE", devicePin: PIN }, "tok-revocado");
  assert.strictEqual(r.statusCode, 401);
  assert.match(r.body.error, /^SESSION_REVOKED/);
  assert.strictEqual(state.fcm.length, 0);
  assert.strictEqual(state.db.commandLog, undefined);
});
test("token vencido o basura → 401 SESSION_INVALID, sin filtrar el texto de la excepción", async () => {
  seedWorld();
  for (const t of ["tok-vencido", "tok-basura", "no-existe"]) {
    const r = await send({ deviceId: DEV, command: "VERIFY_PIN" }, t);
    assert.strictEqual(r.statusCode, 401, t);
    assert.match(r.body.error, /^SESSION_INVALID/);
    assert.ok(!/Decoding|expired. Get/.test(r.body.error), r.body.error);
  }
});
test("checkPlayApps con sesión revocada → 401", async () => {
  const r = await call(fn.checkPlayApps, { headers: { authorization: "Bearer tok-revocado" }, body: { packages: ["com.waze"] } });
  assert.strictEqual(r.statusCode, 401);
});
test("usuario autenticado que NO es administrador → 403", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN }, "tok-intruso");
  assert.strictEqual(r.statusCode, 403);
});
test("deviceId con barras, puntos o vacío → 400 (no arma rutas de la base)", async () => {
  seedWorld();
  for (const id of ["abc/trustedAdmins", "../deviceSecrets", "a.b", "", "x".repeat(200), 5, null]) {
    const r = await send({ deviceId: id, command: "VERIFY_PIN", devicePin: PIN });
    assert.strictEqual(r.statusCode, 400, JSON.stringify(id));
  }
});
test("comando que no está en la lista blanca → 400", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "FORMAT_EVERYTHING", devicePin: PIN });
  assert.strictEqual(r.statusCode, 400);
});

// ── PIN ───────────────────────────────────
test("PIN correcto → VERIFY_PIN ok; sin 'recordar' NO queda confiable", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN });
  assert.strictEqual(r.statusCode, 200);
  assert.strictEqual(r.body.verified, true);
  assert.strictEqual(state.db.devices[DEV].trustedAdmins, undefined);
});
test("PIN incorrecto → 403 PIN_INCORRECT con los intentos que quedan", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: "000111" });
  assert.strictEqual(r.statusCode, 403);
  assert.strictEqual(r.body.error, "PIN_INCORRECT");
  assert.strictEqual(r.body.attemptsLeft, ps.THROTTLE_FREE_ATTEMPTS - 1);
});
test("sin PIN → 412 PIN_REQUIRED; celular sin verificador → 412 DEVICE_PIN_NOT_ENROLLED", async () => {
  seedWorld();
  let r = await send({ deviceId: DEV, command: "LOCK_DEVICE" });
  assert.strictEqual(r.statusCode, 412);
  assert.strictEqual(r.body.error, "PIN_REQUIRED");
  seedWorld({ conPin: false });
  r = await send({ deviceId: DEV, command: "LOCK_DEVICE", devicePin: PIN });
  assert.strictEqual(r.statusCode, 412);
  assert.strictEqual(r.body.error, "DEVICE_PIN_NOT_ENROLLED");
});
test("un pinHash/pinSalt sueltos en devices/<id> NO sirven de verificador (un administrador puede escribir ahí)", async () => {
  seedWorld({ conPin: false });
  state.db.devices[DEV].pinHash = LEGACY;
  state.db.devices[DEV].pinSalt = SALT;
  state.db.devices[DEV].info = { pinHash: LEGACY, pinSalt: SALT };
  const r = await send({ deviceId: DEV, command: "LOCK_DEVICE", devicePin: PIN });
  assert.strictEqual(r.statusCode, 412);
  assert.strictEqual(r.body.error, "DEVICE_PIN_NOT_ENROLLED");
  assert.strictEqual(state.fcm.length, 0);
});
test("el comando NO sale si el PIN es incorrecto (ni FCM ni buzón ni registro)", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "LOCK_DEVICE", devicePin: "000111" });
  assert.strictEqual(r.statusCode, 403);
  assert.strictEqual(state.fcm.length, 0);
  assert.strictEqual(state.db.devices[DEV].pendingCommands, undefined);
  assert.strictEqual(state.db.commandLog, undefined);
});

// ── bloqueo por intentos ────────────────────────────────────
test("tras los intentos libres: 429 PIN_LOCKED, y NI EL PIN CORRECTO pasa mientras dura el bloqueo", async () => {
  seedWorld();
  const FREE = ps.THROTTLE_FREE_ATTEMPTS;
  for (let i = 1; i <= FREE; i++) {
    const r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: "00011" + i });
    assert.strictEqual(r.statusCode, 403, "intento " + i);
  }
  let r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN });
  assert.strictEqual(r.statusCode, 429, "el PIN correcto también se rechaza bloqueado");
  assert.match(r.body.error, /^PIN_LOCKED/);
  assert.ok(r.body.retryAfterSec > 0 && r.body.retryAfterSec <= 300, String(r.body.retryAfterSec));
  // pasados los 5 minutos, el correcto entra
  state.now += 5 * 60 * 1000 + 1000;
  r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN });
  assert.strictEqual(r.statusCode, 200);
  // y el acierto borró el contador
  assert.strictEqual(state.db.pinThrottle, undefined);
});
test("el bloqueo es por (equipo, administrador): otro administrador no queda bloqueado", async () => {
  seedWorld();
  for (let i = 0; i < ps.THROTTLE_FREE_ATTEMPTS; i++) await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: "00011" + i });
  assert.strictEqual((await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN })).statusCode, 429);
  assert.strictEqual((await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN }, "tok-admin2")).statusCode, 200);
});
test("50 intentos en paralelo con PIN incorrecto: solo FREE llegan a calcularse", async () => {
  seedWorld();
  const rs = await Promise.all(Array.from({ length: 50 }, (_, i) =>
    send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: String(100000 + i) })));
  assert.strictEqual(rs.filter((r) => r.statusCode === 403).length, ps.THROTTLE_FREE_ATTEMPTS);
  assert.strictEqual(rs.filter((r) => r.statusCode === 429).length, 50 - ps.THROTTLE_FREE_ATTEMPTS);
});
test("un celular confiable (trustedAdmins) no gasta intentos", async () => {
  seedWorld({ trusted: { adm1: true } });
  const r = await send({ deviceId: DEV, command: "LOCK_DEVICE" });
  assert.strictEqual(r.statusCode, 200);
  assert.strictEqual(state.db.pinThrottle, undefined);
});

// ── recordar este celular ───────────────────────────────────
test("'recordar' con PIN correcto escribe trustedAdmins SOLO desde la Function; después no pide PIN", async () => {
  seedWorld();
  let r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN, rememberDevice: true });
  assert.strictEqual(r.statusCode, 200);
  assert.strictEqual(state.db.devices[DEV].trustedAdmins.adm1, true);
  assert.strictEqual(state.db.devices[DEV].info.trustedAdmins.adm1, true);
  r = await send({ deviceId: DEV, command: "LOCK_DEVICE" });
  assert.strictEqual(r.statusCode, 200);
  assert.strictEqual(state.fcm.length, 1);
});
test("'recordar' con PIN INCORRECTO no deja nada", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: "000111", rememberDevice: true });
  assert.strictEqual(r.statusCode, 403);
  assert.strictEqual(state.db.devices[DEV].trustedAdmins, undefined);
});

// ── CHANGE_PIN ──────────────────────────────────────────────
function canonical(p) {
  return Object.keys(p).filter((k) => k !== "signature").sort().map((k) => `${k}=${p[k] ?? ""}`).join("\n");
}
test("CHANGE_PIN: hash del esquema de siempre, el secreto de comandos sobrevive y la firma cubre el hash", async () => {
  seedWorld();
  const r = await send({ deviceId: DEV, command: "CHANGE_PIN", devicePin: PIN, newPin: "824613" });
  assert.strictEqual(r.statusCode, 200, JSON.stringify(r.body));
  const data = state.fcm[0].data;
  assert.ok(Object.values(data).every((v) => typeof v === "string"), "FCM solo admite strings");
  const sec = state.db.deviceSecrets[DEV];
  assert.strictEqual(sec.pinHash, ps.hashPinLegacy("824613", sec.pinSalt));
  assert.strictEqual(sec.pinKdf, undefined, "el formato del verificador no cambió");
  assert.strictEqual(sec.pinIter, undefined);
  assert.strictEqual(data.pinKdf, undefined);
  assert.strictEqual(sec.commandSecret, SECRET, "el secreto de comandos sobrevive");
  assert.strictEqual(data.pinHash, sec.pinHash);
  assert.strictEqual(data.pinSalt, sec.pinSalt);
  const firma = crypto.createHmac("sha256", SECRET).update(canonical(data), "utf8").digest("base64");
  assert.strictEqual(data.signature, firma);
});
test("CHANGE_PIN: borra los 'confiables' y los intentos fallidos; el PIN nuevo verifica y el viejo no", async () => {
  seedWorld({ trusted: { adm2: true } });
  // fallos de OTRO administrador (adm2): los de quien manda el comando (adm1) los borra su propio acierto,
  // así que para probar que CHANGE_PIN limpia el resto tienen que ser de otra cuenta.
  state.db.pinThrottle = { [DEV]: { adm2: { fails: 3, last: state.now, lockUntil: 0 } } };
  await send({ deviceId: DEV, command: "CHANGE_PIN", devicePin: PIN, newPin: "824613" });
  assert.strictEqual(state.db.devices[DEV].trustedAdmins, undefined);
  assert.strictEqual(state.db.pinThrottle, undefined);
  assert.strictEqual((await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: "824613" })).statusCode, 200);
  assert.strictEqual((await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN })).statusCode, 403);
});
test("CHANGE_PIN rechaza PIN trivial o mal formado", async () => {
  seedWorld();
  for (const bad of ["1234", "0000", "9876", "123", "12ab56", "1".repeat(17), ""]) {
    const r = await send({ deviceId: DEV, command: "CHANGE_PIN", devicePin: PIN, newPin: bad });
    assert.strictEqual(r.statusCode, 400, JSON.stringify(bad));
  }
  assert.strictEqual(state.fcm.length, 0);
  assert.strictEqual(state.db.deviceSecrets[DEV].pinHash, LEGACY, "no se tocó el verificador");
});

// ── excepción de PIN (igual que siempre) ───────────────────
test("UPDATE_LOCKSUITE, CANCEL_UPDATE_APP y COLLECT_DIAGNOSTICS siguen sin pedir PIN; LOCK_DEVICE sí", async () => {
  seedWorld();
  for (const command of ["UPDATE_LOCKSUITE", "CANCEL_UPDATE_APP", "COLLECT_DIAGNOSTICS"]) {
    const r = await send({ deviceId: DEV, command });
    assert.strictEqual(r.statusCode, 200, command + " " + JSON.stringify(r.body));
  }
  const sinPin = await send({ deviceId: DEV, command: "LOCK_DEVICE" });
  assert.strictEqual(sinPin.statusCode, 412);
  assert.strictEqual(sinPin.body.error, "PIN_REQUIRED");
});
test("los comandos firmados llevan HMAC con el secreto del equipo (incluyendo expiresAt)", async () => {
  seedWorld();
  await send({ deviceId: DEV, command: "LOCK_DEVICE", devicePin: PIN });
  const d = state.fcm[0].data;
  assert.ok(/^\d+$/.test(d.expiresAt) && Number(d.expiresAt) > Number(d.timestamp), "expiresAt firmado presente");
  assert.strictEqual(d.signature, crypto.createHmac("sha256", SECRET).update(canonical(d), "utf8").digest("base64"));
  const tampered = { ...d, expiresAt: String(Number(d.expiresAt) + 60_000) };
  assert.notStrictEqual(d.signature, crypto.createHmac("sha256", SECRET).update(canonical(tampered), "utf8").digest("base64"));
});

test("CORS permite orígenes de admin autorizados y omite ACAO para orígenes externos; 500 no filtra detalle interno", async () => {
  seedWorld();
  const okCors = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN }, "tok-admin", { origin: "https://locksuite-nueva.web.app" });
  assert.strictEqual(okCors.headers["access-control-allow-origin"], "https://locksuite-nueva.web.app");
  assert.strictEqual(okCors.headers["vary"], "Origin");

  const badCors = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN }, "tok-admin", { origin: "https://evil.example" });
  assert.strictEqual(badCors.headers["access-control-allow-origin"], undefined);
  assert.strictEqual(badCors.headers["vary"], "Origin");

  state.dbReadError = { match: "devices/", error: new Error("INTERNAL_DB_SECRET_DETAIL_XYZ") };
  const err500 = await send({ deviceId: DEV, command: "VERIFY_PIN", devicePin: PIN });
  assert.strictEqual(err500.statusCode, 500);
  assert.strictEqual(err500.body.error, "Error interno del servidor.");
  assert.ok(!JSON.stringify(err500.body).includes("INTERNAL_DB_SECRET_DETAIL_XYZ"));
});

test("UPDATE_ALLOWLIST permite vaciar paquetes con array vacío []", async () => {
  seedWorld();
  state.db.devices[DEV].allowedPackages = ["com.waze"];
  state.db.devices[DEV].info = { allowedPackages: ["com.waze"] };
  const r = await send({ deviceId: DEV, command: "UPDATE_ALLOWLIST", devicePin: PIN, packages: [] });
  assert.strictEqual(r.statusCode, 200, JSON.stringify(r.body));
  assert.strictEqual(state.fcm[0].data.packages, "");
  assert.strictEqual(state.db.devices[DEV].allowedPackages, undefined);
  assert.strictEqual(state.db.devices[DEV].info, undefined);
});

test("CHANGE_PIN no pisa deviceSecrets/trustedAdmins/pinThrottle si falla la entrega (ni buzón ni FCM)", async () => {
  seedWorld({ fcmToken: null, trusted: { adm2: true } });
  state.db.pinThrottle = { [DEV]: { adm2: { fails: 2, last: state.now, lockUntil: 0 } } };
  // Fuerza error al leer pendingCommands para simular fallo del buzón sin FCM.
  state.dbReadError = { match: "pendingCommands", error: new Error("db down") };
  const r = await send({ deviceId: DEV, command: "CHANGE_PIN", devicePin: PIN, newPin: "824613" });
  assert.strictEqual(r.statusCode, 404);
  assert.strictEqual(state.db.deviceSecrets[DEV].pinHash, LEGACY);
  assert.strictEqual(state.db.deviceSecrets[DEV].pinSalt, SALT);
  assert.strictEqual(state.db.devices[DEV].trustedAdmins.adm2, true);
  assert.strictEqual(state.db.pinThrottle[DEV].adm2.fails, 2);
});

// ── classicStoreApk ──────────────────────────────────────────
test("classicStoreApk rechaza sin token (401) y a no-admins (403) con checkRevoked=true", async () => {
  seedWorld();
  const rNoTok = await call(fn.classicStoreApk, { body: { action: "initUpload" } });
  assert.strictEqual(rNoTok.statusCode, 401);
  const rIntr = await call(fn.classicStoreApk, { headers: { authorization: "Bearer tok-intruso" }, body: { action: "initUpload" } });
  assert.strictEqual(rIntr.statusCode, 403);
  assert.strictEqual(state.tokenChecks[0].checkRevoked, true);
});
test("classicStoreApk initUpload valida paquete, extension y tamanio", async () => {
  seedWorld();
  const cases = [
    { packageName: "no-punto", fileName: "app.apk", sizeBytes: 1000 },
    { packageName: "com.ejemplo.locksuite", fileName: "app.apk", sizeBytes: 1000 },
    { packageName: "com.android.vending", fileName: "app.apk", sizeBytes: 1000 },
    { packageName: "com.waze", fileName: "app.zip", sizeBytes: 1000 },
    { packageName: "com.waze", fileName: "app.apk", sizeBytes: 0 },
    { packageName: "com.waze", fileName: "app.apk", sizeBytes: 600 * 1024 * 1024 },
  ];
  for (const c of cases) {
    const r = await call(fn.classicStoreApk, {
      headers: { authorization: "Bearer tok-admin" },
      body: { action: "initUpload", ...c },
    });
    assert.strictEqual(r.statusCode, 400, JSON.stringify(c));
  }
  assert.strictEqual(state.storageOps.length, 0);
});
test("classicStoreApk initUpload devuelve uploadUrl, objectPath y downloadUrl validos", async () => {
  seedWorld();
  const r = await call(fn.classicStoreApk, {
    headers: { authorization: "Bearer tok-admin", origin: "https://locksuite-nueva.web.app" },
    body: { action: "initUpload", packageName: "org.example.miapp", fileName: "Mi App v2.apk", sizeBytes: 123456 },
  });
  assert.strictEqual(r.statusCode, 200, JSON.stringify(r.body));
  assert.strictEqual(r.body.success, true);
  assert.ok(r.body.objectPath.startsWith("apks/org_example_miapp/") && r.body.objectPath.endsWith(".apk"));
  assert.strictEqual(r.body.downloadUrl, "https://storage.googleapis.com/locksuite-nueva-store-apks/" + r.body.objectPath);
  assert.strictEqual(r.body.contentType, "application/vnd.android.package-archive");
  assert.ok(r.body.uploadUrl.includes("resumable"));
  assert.strictEqual(state.storageOps[0].opts.origin, "https://locksuite-nueva.web.app");
  assert.strictEqual(state.storageOps[0].opts.metadata.contentType, "application/vnd.android.package-archive");
});
test("classicStoreApk initUpload y deleteObject soportan .apks, .xapk y .apkm con application/octet-stream", async () => {
  seedWorld();
  for (const ext of ["apks", "xapk", "apkm"]) {
    const r = await call(fn.classicStoreApk, {
      headers: { authorization: "Bearer tok-admin", origin: "https://locksuite-nueva.web.app" },
      body: { action: "initUpload", packageName: "org.example.split", fileName: "Bundle." + ext, sizeBytes: 223344 },
    });
    assert.strictEqual(r.statusCode, 200, ext + " " + JSON.stringify(r.body));
    assert.ok(r.body.objectPath.endsWith("." + ext), ext);
    assert.strictEqual(r.body.contentType, "application/octet-stream", ext);
    assert.strictEqual(state.storageOps[state.storageOps.length - 1].opts.metadata.contentType, "application/octet-stream", ext);

    const del = await call(fn.classicStoreApk, {
      headers: { authorization: "Bearer tok-admin" },
      body: { action: "deleteObject", objectPath: r.body.objectPath },
    });
    assert.strictEqual(del.statusCode, 200, ext);
  }
});
test("classicStoreApk deleteObject rechaza traversal y borra rutas validas", async () => {
  seedWorld();
  for (const bad of ["", "../etc/passwd.apk", "apks/../secret.apk", "otros/a.apk", "apks/foo.txt", "/apks/a.apk"]) {
    const r = await call(fn.classicStoreApk, {
      headers: { authorization: "Bearer tok-admin" },
      body: { action: "deleteObject", objectPath: bad },
    });
    assert.strictEqual(r.statusCode, 400, bad);
  }
  const ok = await call(fn.classicStoreApk, {
    headers: { authorization: "Bearer tok-admin" },
    body: { action: "deleteObject", objectPath: "apks/org_example_miapp/1700_app.apk" },
  });
  assert.strictEqual(ok.statusCode, 200);
  assert.strictEqual(state.storageOps[0].op, "delete");
  assert.strictEqual(state.storageOps[0].bucketName, "locksuite-nueva-store-apks");
});
test("START_TEMP_INSTALL_WINDOW y STOP_TEMP_INSTALL_WINDOW exigen PIN, validan duracion y comparten familia en buzon", async () => {
  seedWorld();
  const bad = await send({ deviceId: DEV, devicePin: PIN, command: "START_TEMP_INSTALL_WINDOW", durationMs: 5000 });
  assert.strictEqual(bad.statusCode, 400);

  const noPin = await send({ deviceId: DEV, command: "START_TEMP_INSTALL_WINDOW", durationMs: 30 * 60 * 1000 });
  assert.strictEqual(noPin.statusCode, 412);

  const okStart = await send({ deviceId: DEV, devicePin: PIN, command: "START_TEMP_INSTALL_WINDOW", durationMs: 30 * 60 * 1000 });
  assert.strictEqual(okStart.statusCode, 200, JSON.stringify(okStart.body));
  assert.strictEqual(state.fcm[state.fcm.length - 1].data.durationMs, "1800000");

  const pend1 = Object.values(getAt(state.db, `devices/${DEV}/pendingCommands`) || {});
  assert.strictEqual(pend1.filter(x => x.family === "TEMP_INSTALL_WINDOW").length, 1);

  const okStop = await send({ deviceId: DEV, devicePin: PIN, command: "STOP_TEMP_INSTALL_WINDOW" });
  assert.strictEqual(okStop.statusCode, 200, JSON.stringify(okStop.body));
  const pend2 = Object.values(getAt(state.db, `devices/${DEV}/pendingCommands`) || {});
  const fam2 = pend2.filter(x => x.family === "TEMP_INSTALL_WINDOW");
  assert.strictEqual(fam2.length, 1);
  assert.strictEqual(fam2[0].data.command, "STOP_TEMP_INSTALL_WINDOW");
});

test("auroraStore exige admin, enmascara serverSecrets/aurora, rechaza Dispenser privado y valida paquetes", async () => {
  seedWorld();
  const unauth = await call(fn.auroraStore, { body: { action: "getStatus" } });
  assert.strictEqual(unauth.statusCode, 401);

  const nonAdmin = await call(fn.auroraStore, {
    headers: { authorization: "Bearer tok-intruso" },
    body: { action: "getStatus" },
  });
  assert.strictEqual(nonAdmin.statusCode, 403);

  state.db.serverSecrets = {
    aurora: { mode: "aas", email: "burner@example.com", aasToken: "aas_et/TOP_SECRET_VALUE", updatedAt: 12345 },
  };
  state.db.auroraStore = {
    packages: { com_waze: { packageName: "com.waze", versionCode: 55, status: "ready" } },
  };
  const st = await call(fn.auroraStore, {
    headers: { authorization: "Bearer tok-admin" },
    body: { action: "getStatus" },
  });
  assert.strictEqual(st.statusCode, 200);
  assert.strictEqual(st.body.auth.configured, true);
  assert.strictEqual(st.body.auth.hasAasToken, true);
  assert.strictEqual(JSON.stringify(st.body).includes("TOP_SECRET_VALUE"), false);
  assert.strictEqual(st.body.packages.com_waze.versionCode, 55);

  const ssrf = await call(fn.auroraStore, {
    headers: { authorization: "Bearer tok-admin" },
    body: { action: "saveConfig", mode: "dispenser", dispenserUrl: "https://127.0.0.1/token" },
  });
  assert.strictEqual(ssrf.statusCode, 400);

  for (const badPkg of ["invalid", "com.ejemplo.locksuite", "com.android.vending"]) {
    const r = await call(fn.auroraStore, {
      headers: { authorization: "Bearer tok-admin" },
      body: { action: "syncPackage", packageName: badPkg },
    });
    assert.strictEqual(r.statusCode, 400, badPkg);
  }
});

test("INSTALL_APP exige PIN, valida paquete/fuente SHA-256, respeta super-admin y firma HMAC con TTL 24h", async () => {
  seedWorld();
  const sha = "a".repeat(64);
  state.db.auroraStore = {
    packages: {
      com_kiloo_subwaysurf: {
        packageName: "com.kiloo.subwaysurf",
        title: "Subway Surfers",
        status: "ready",
        variants: {
          arm64_v8a: {
            apkUrl: "https://storage.googleapis.com/locksuite-nueva-store-apks/aurora/com_kiloo_subwaysurf/app.apks",
            sha256: sha,
            versionCode: 300,
          },
        },
      },
      com_evil_unsigned: {
        packageName: "com.evil.unsigned",
        status: "ready",
        variants: {
          universal: {
            apkUrl: "https://evil.example.com/app.apk",
            sha256: sha,
            versionCode: 1,
          },
        },
      },
    },
  };

  // Sin PIN -> 412 (NO exento)
  const noPin = await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.kiloo.subwaysurf"] });
  assert.strictEqual(noPin.statusCode, 412);
  assert.strictEqual(noPin.body.error, "PIN_REQUIRED");

  // PIN incorrecto -> 403
  const badPin = await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.kiloo.subwaysurf"], devicePin: "000111" });
  assert.strictEqual(badPin.statusCode, 403);

  // Usuario no admin -> 403
  const intruso = await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.kiloo.subwaysurf"], devicePin: PIN }, "tok-intruso");
  assert.strictEqual(intruso.statusCode, 403);

  // Paquete inválido / múltiples / críticos -> 400
  for (const bad of [[], ["invalid"], ["com.kiloo.subwaysurf", "com.waze"], ["com.ejemplo.locksuite"], ["com.android.vending"], ["com.google.android.apps.maps"]]) {
    const r = await send({ deviceId: DEV, command: "INSTALL_APP", packages: bad, devicePin: PIN });
    assert.strictEqual(r.statusCode, 400, JSON.stringify(bad));
  }

  // Paquete faltante o con URL externa no oficial -> 412
  assert.strictEqual((await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.missing.app"], devicePin: PIN })).statusCode, 412);
  assert.strictEqual((await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.evil.unsigned"], devicePin: PIN })).statusCode, 412);

  // Restricción opcional serverSecrets/remoteInstallAllowedUids
  state.db.serverSecrets = { remoteInstallAllowedUids: { adm1: true } };
  const blockedAdm2 = await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.kiloo.subwaysurf"], devicePin: PIN }, "tok-admin2");
  assert.strictEqual(blockedAdm2.statusCode, 403);

  // Super-admin adm1 con PIN correcto -> 200 + FCM + HMAC + 24h TTL + log + buzon
  const ok = await send({ deviceId: DEV, command: "INSTALL_APP", packages: ["com.kiloo.subwaysurf"], devicePin: PIN }, "tok-admin");
  assert.strictEqual(ok.statusCode, 200, JSON.stringify(ok.body));
  const msg = state.fcm[state.fcm.length - 1];
  assert.strictEqual(msg.data.command, "INSTALL_APP");
  assert.strictEqual(msg.data.packages, "com.kiloo.subwaysurf");
  assert.ok(msg.data.signature);
  assert.strictEqual(Number(msg.data.expiresAt) - Number(msg.data.timestamp), 24 * 60 * 60 * 1000);
  const logEntries = Object.values(state.db.commandLog[DEV] || {});
  assert.ok(logEntries.some((x) => x.command === "INSTALL_APP" && x.sentBy === "adm1"));
});

(async () => {
  for (const t of pending) await t();
  Date.now = realNow;
  if (process.exitCode) report("\nHAY PRUEBAS QUE FALLAN");
  else console.log("OK (" + pass + " pruebas)");
})();
