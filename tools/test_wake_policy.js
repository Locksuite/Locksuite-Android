// 19/11/2026 (B.125, T3.1) — a qué equipos se les manda el FCM de despertar.
// Correr:  node tools/test_wake_policy.js
"use strict";
const assert = require("assert");
const path = require("path");
const { WAKE, dayKey, pickDevicesToWake } = require(path.join(__dirname, "..", "admin-backend", "functions", "wake_policy.js"));

const MIN = 60 * 1000;
const NOW = Date.UTC(2026, 10, 19, 15, 0, 0);
const TOKEN = "t".repeat(40);
let n = 0;
function t(nombre, fn) { fn(); n++; console.log("  ok  " + nombre); }

t("despierta al que lleva más de 180 min sin latir", () => {
  const r = pickDevicesToWake({ a: { lastSeen: NOW - 200 * MIN, fcmToken: TOKEN } }, NOW);
  assert.strictEqual(r.length, 1);
  assert.strictEqual(r[0].id, "a");
  assert.strictEqual(r[0].token, TOKEN);
  assert.deepStrictEqual(r[0].wake, { lastSentAt: NOW, count: 1, day: dayKey(NOW) });
});

t("no despierta al que latió hace poco", () => {
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: NOW - 40 * MIN, fcmToken: TOKEN } }, NOW).length, 0);
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: NOW - 179 * MIN, fcmToken: TOKEN } }, NOW).length, 0);
});

t("no despierta a un equipo con más de 7 días sin señal", () => {
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: NOW - WAKE.MAX_AGE_MS - MIN, fcmToken: TOKEN } }, NOW).length, 0);
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: NOW - WAKE.MAX_AGE_MS + MIN, fcmToken: TOKEN } }, NOW).length, 1);
});

t("sin token FCM no hay a quién mandar", () => {
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: NOW - 200 * MIN } }, NOW).length, 0);
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: NOW - 200 * MIN, fcmToken: "corto" } }, NOW).length, 0);
});

t("lee lastSeen y fcmToken también desde info/", () => {
  const r = pickDevicesToWake({ a: { info: { lastSeen: NOW - 200 * MIN, fcmToken: TOKEN } } }, NOW);
  assert.strictEqual(r.length, 1);
});

t("un lastSeen que no es número se ignora", () => {
  assert.strictEqual(pickDevicesToWake({ a: { lastSeen: "ayer", fcmToken: TOKEN } }, NOW).length, 0);
  assert.strictEqual(pickDevicesToWake({ a: { fcmToken: TOKEN } }, NOW).length, 0);
});

t("respeta 1 mensaje cada 60 min", () => {
  const dev = (ago) => ({ lastSeen: NOW - 240 * MIN, fcmToken: TOKEN, wake: { lastSentAt: NOW - ago, count: 1, day: dayKey(NOW) } });
  assert.strictEqual(pickDevicesToWake({ a: dev(50 * MIN) }, NOW).length, 0);
  assert.strictEqual(pickDevicesToWake({ a: dev(61 * MIN) }, NOW).length, 1);
});

t("tope de 8 por día y se reinicia al día siguiente", () => {
  const hoy = { lastSeen: NOW - 240 * MIN, fcmToken: TOKEN, wake: { lastSentAt: NOW - 70 * MIN, count: WAKE.MAX_PER_DAY, day: dayKey(NOW) } };
  assert.strictEqual(pickDevicesToWake({ a: hoy }, NOW).length, 0);
  const ayer = { lastSeen: NOW - 240 * MIN, fcmToken: TOKEN, wake: { lastSentAt: NOW - 70 * MIN, count: WAKE.MAX_PER_DAY, day: dayKey(NOW) - 1 } };
  const r = pickDevicesToWake({ a: ayer }, NOW);
  assert.strictEqual(r.length, 1);
  assert.strictEqual(r[0].wake.count, 1, "un día nuevo arranca la cuenta de nuevo");
});

t("el contador sube y no se pasa de lo guardado", () => {
  const dev = { lastSeen: NOW - 240 * MIN, fcmToken: TOKEN, wake: { lastSentAt: NOW - 70 * MIN, count: 5, day: dayKey(NOW) } };
  assert.strictEqual(pickDevicesToWake({ a: dev }, NOW)[0].wake.count, 6);
});

t("entradas raras no rompen nada", () => {
  assert.deepStrictEqual(pickDevicesToWake(null, NOW), []);
  assert.deepStrictEqual(pickDevicesToWake({ a: null, b: 5, c: "x" }, NOW), []);
});

console.log(`\n${n} pruebas OK`);
