#!/usr/bin/env node
"use strict";
/**
 * Prueba de admin-backend/functions/pin_security.js (auditoría de seguridad del 9/11/2026).
 * Sin red y sin dependencias:
 *
 *     node tools/test_pin_security.js
 *
 * Qué afirma: que el hash del PIN (SHA-256(sal ‖ PIN), el de siempre) da el mismo resultado que una
 * implementación INDEPENDIENTE (el vector de abajo se generó con hashlib de Python, no con este
 * código), que la comparación rechaza lo que tiene que rechazar sin lanzar excepciones, y que el
 * límite de intentos deja pasar FREE intentos, se duplica, tiene tope y no se salta con pedidos en
 * paralelo.
 * NO prueba contra Firebase real: la transacción de la base se imita con un doble.
 */
const assert = require("assert");
const path = require("path");
// LS_FN_DIR existe para las pruebas de mutación (apuntar a una copia alterada de las Functions).
const FN_DIR = process.env.LS_FN_DIR || path.join(__dirname, "..", "admin-backend", "functions");
const ps = require(path.join(FN_DIR, "pin_security.js"));

let pass = 0;
const pending = [];
function test(name, fn) {
  pending.push(async () => {
    try {
      await fn();
      pass++;
    } catch (e) {
      console.error("✗ " + name + "\n  " + (e && e.stack || e));
      process.exitCode = 1;
    }
  });
}

// Vector generado con Python (hashlib.sha256), no con este código.
const SALT_B64 = "AAECAwQFBgcICQoLDA0ODw=="; // bytes 0..15
const PIN = "493817";
const LEGACY = "dTPzBbVQSGugCPTukSejxWbLzDJXoCaos96J1CxmH9o=";

// ── hash contra el vector externo ───────────────────────────
test("SHA-256(sal ‖ PIN) igual que Python", () => {
  assert.strictEqual(ps.hashPinLegacy(PIN, SALT_B64), LEGACY);
});

// ── verificación ────────────────────────────────────────────
test("verifyPinRecord: PIN correcto entra, incorrecto no", () => {
  assert.strictEqual(ps.verifyPinRecord({ pinHash: LEGACY, pinSalt: SALT_B64 }, PIN), true);
  assert.strictEqual(ps.verifyPinRecord({ pinHash: LEGACY, pinSalt: SALT_B64 }, "493818"), false);
});
test("verifyPinRecord: PIN vacío, enorme o que no es string → false sin calcular", () => {
  const rec = { pinHash: LEGACY, pinSalt: SALT_B64 };
  assert.strictEqual(ps.verifyPinRecord(rec, ""), false);
  assert.strictEqual(ps.verifyPinRecord(rec, "9".repeat(65)), false);
  for (const bad of [null, undefined, 123456, {}, ["4", "9"]]) {
    assert.strictEqual(ps.verifyPinRecord(rec, bad), false);
  }
});
test("verifyPinRecord: hash guardado de otro largo, ausente o registro vacío no lanza y da false", () => {
  for (const h of ["", "abc", null, undefined, 42, LEGACY + "A"]) {
    assert.strictEqual(ps.verifyPinRecord({ pinHash: h, pinSalt: SALT_B64 }, PIN), false);
  }
  for (const rec of [null, undefined, {}, { pinHash: LEGACY }, { pinSalt: SALT_B64 }]) {
    assert.strictEqual(ps.verifyPinRecord(rec, PIN), false);
  }
});
test("constantTimeEquals: iguales, distintos, de distinto largo", () => {
  assert.strictEqual(ps.constantTimeEquals("abc", "abc"), true);
  assert.strictEqual(ps.constantTimeEquals("abc", "abd"), false);
  assert.strictEqual(ps.constantTimeEquals("abc", "abcd"), false);
  assert.strictEqual(ps.constantTimeEquals("", ""), true);
});

// ── registros nuevos (CHANGE_PIN) ───────────────────────────
test("newPinRecord: sal de 16 bytes, verifica su propio PIN y solo ese", () => {
  const r = ps.newPinRecord("824613");
  assert.deepStrictEqual(Object.keys(r).sort(), ["pinHash", "pinSalt"]);
  assert.ok(Buffer.from(r.pinSalt, "base64").length === 16);
  assert.strictEqual(ps.verifyPinRecord(r, "824613"), true);
  assert.strictEqual(ps.verifyPinRecord(r, "824614"), false);
});
test("newPinRecord: cada llamada usa una sal distinta", () => {
  const a = ps.newPinRecord("824613");
  const b = ps.newPinRecord("824613");
  assert.notStrictEqual(a.pinSalt, b.pinSalt);
  assert.notStrictEqual(a.pinHash, b.pinHash);
});

// ── límite de intentos ──────────────────────────────────────
const MIN = 60 * 1000;
const FREE = ps.THROTTLE_FREE_ATTEMPTS;
test("throttleAttempt: los primeros intentos son libres, el último libre se evalúa y deja el bloqueo armado", () => {
  assert.strictEqual(FREE, 8);
  let st = null;
  for (let i = 1; i < FREE; i++) {
    const r = ps.throttleAttempt(st, 1000 + i);
    assert.strictEqual(r.allowed, true);
    assert.strictEqual(r.state.lockUntil, 0, "intento " + i);
    assert.strictEqual(r.attemptsLeft, FREE - i);
    st = r.state;
  }
  const ultimo = ps.throttleAttempt(st, 2000);
  assert.strictEqual(ultimo.allowed, true, "el último intento libre todavía se evalúa");
  assert.strictEqual(ultimo.state.lockUntil, 2000 + 5 * MIN);
  assert.strictEqual(ultimo.attemptsLeft, 0);
  // y el siguiente ya no pasa
  assert.strictEqual(ps.throttleAttempt(ultimo.state, 2001).allowed, false);
});
test("throttleAttempt: con el bloqueo armado se rechaza y dice cuánto falta", () => {
  const st = { fails: FREE, last: 2000, lockUntil: 2000 + 5 * MIN };
  const r = ps.throttleAttempt(st, 2000 + 2 * MIN);
  assert.strictEqual(r.allowed, false);
  assert.strictEqual(r.retryAfterMs, 3 * MIN);
  assert.strictEqual(r.state, st, "no se modifica el estado");
});
test("throttleAttempt: vencido el bloqueo se duplica con cada fallo (5, 10, 20, 40, tope 60 min)", () => {
  let st = { fails: FREE, last: 0, lockUntil: 5 * MIN };
  let now = 5 * MIN + 1;
  const esperados = [10, 20, 40, 60, 60, 60];
  for (const min of esperados) {
    const r = ps.throttleAttempt(st, now);
    assert.strictEqual(r.allowed, true);
    assert.strictEqual(r.state.lockUntil - now, min * MIN);
    st = r.state;
    now = st.lockUntil + 1;
  }
});
test("throttleAttempt: tras 24 h sin intentos el contador vuelve a cero", () => {
  const st = { fails: 9, last: 1000, lockUntil: 1000 + 60 * MIN };
  const r = ps.throttleAttempt(st, 1000 + 24 * 60 * MIN + 1);
  assert.strictEqual(r.allowed, true);
  assert.strictEqual(r.state.fails, 1);
  assert.strictEqual(r.state.lockUntil, 0);
});
test("throttleAttempt: estado corrupto no lanza ni regala intentos de más", () => {
  for (const st of [{}, { fails: "x" }, { fails: -4, last: 5 }, { fails: NaN, last: NaN, lockUntil: NaN }, "basura", 42]) {
    const r = ps.throttleAttempt(st, 10 * MIN);
    assert.strictEqual(typeof r.allowed, "boolean");
    if (r.allowed) assert.ok(r.state.fails >= 1);
  }
  // fails enorme: el exponente no explota y el tope se respeta
  const r = ps.throttleAttempt({ fails: 1e9, last: 10 * MIN, lockUntil: 0 }, 10 * MIN + 1);
  assert.strictEqual(r.allowed, true);
  assert.strictEqual(r.state.lockUntil - (10 * MIN + 1), 60 * MIN);
});

// Doble de una Reference de RTDB: transaction(fn) con el mismo contrato (aborta si fn devuelve undefined).
function fakeRef(initial) {
  const ref = {
    value: initial === undefined ? null : initial,
    calls: 0,
    async transaction(fn) {
      ref.calls++;
      // La base real llama primero con null (caché vacía) y reintenta con el valor verdadero.
      fn(null);
      const out = fn(ref.value);
      if (out === undefined) return { committed: false };
      ref.value = out;
      return { committed: true };
    },
  };
  return ref;
}
test("reservePinAttempt: 20 pedidos EN PARALELO dejan pasar exactamente FREE; los demás, no", async () => {
  const ref = fakeRef(null);
  const now = 5_000_000;
  const rs = await Promise.all(Array.from({ length: 20 }, () => ps.reservePinAttempt(ref, now)));
  assert.strictEqual(rs.filter((r) => r.allowed).length, FREE);
  assert.strictEqual(rs.filter((r) => !r.allowed).length, 20 - FREE);
  assert.ok(rs.filter((r) => !r.allowed).every((r) => r.retryAfterMs > 0));
  assert.strictEqual(ref.value.fails, FREE);
});
test("reservePinAttempt: bloqueado no escribe nada (el estado queda tal cual)", async () => {
  const ref = fakeRef({ fails: FREE, last: 100, lockUntil: 100 + 5 * MIN });
  const antes = JSON.stringify(ref.value);
  const r = await ps.reservePinAttempt(ref, 200);
  assert.strictEqual(r.allowed, false);
  assert.strictEqual(JSON.stringify(ref.value), antes);
});

(async () => {
  for (const t of pending) await t();
  if (process.exitCode) {
    console.error("\nHAY PRUEBAS QUE FALLAN");
  } else {
    console.log("OK (" + pass + " pruebas)");
  }
})();
