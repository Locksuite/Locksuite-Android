#!/usr/bin/env node
"use strict";
/**
 * Prueba de admin-backend/public/diag_logic.js (B.110) — sin red y sin dependencias.
 *
 *     node tools/test_diag_logic.js
 *
 * Es el banco de pruebas del lado del PANEL del registro de errores: ordenar y filtrar los
 * renglones que sube el celular, decir POR QUÉ no se ve nada (apagado / sin versión / error
 * de subida) y armar el informe que el dueño copia y me pega. Las horas se prueban con un
 * desfase explícito (UTC-3 = -180) para que el resultado no dependa de la zona de quien corre.
 */
const assert = require("assert");
const path = require("path");
const D = require(path.join(__dirname, "..", "admin-backend", "public", "diag_logic.js"));

let pass = 0;
function test(name, fn) {
  try { fn(); pass++; } catch (e) {
    console.error("✗ " + name + "\n  " + (e && e.stack || e));
    process.exitCode = 1;
  }
}

const AR = -180;                                     // Buenos Aires, sin horario de verano
const T0 = Date.UTC(2026, 10, 6, 21, 0, 0);          // 06/11/2026 18:00 en Buenos Aires
const MIN = 60000, HORA = 3600000, DIA = 86400000;
const ev = (seq, at, l, g, m, n) => {
  const o = { t: at, l: l, g: g, m: m };
  if (n) o.n = n;
  return ["e" + String(seq).padStart(10, "0"), o];
};
const events = (...items) => Object.fromEntries(items);

// ── horas ────────────────────────────────────────────────────────────────────────
test("fmtStamp / fmtFull con desfase explícito", () => {
  assert.strictEqual(D.fmtStamp(T0 + 3 * MIN + 12000, AR), "06/11 18:03:12");
  assert.strictEqual(D.fmtFull(T0, AR), "06/11/2026 18:00");
  assert.strictEqual(D.fmtStamp(Date.UTC(2026, 0, 2, 2, 5, 9), AR), "01/01 23:05:09", "cruza el día hacia atrás");
  for (const bad of [0, -5, NaN, null, undefined, "x"]) {
    assert.strictEqual(D.fmtStamp(bad, AR), "—", String(bad));
    assert.strictEqual(D.fmtFull(bad, AR), "—", String(bad));
  }
});
test("fmtAgo", () => {
  assert.strictEqual(D.fmtAgo(10 * 1000), "hace unos segundos");
  assert.strictEqual(D.fmtAgo(5 * MIN), "hace 5 min");
  assert.strictEqual(D.fmtAgo(3 * HORA), "hace 3 h");
  assert.strictEqual(D.fmtAgo(3 * DIA), "hace 3 d");
  assert.ok(/futuro/.test(D.fmtAgo(-HORA)), "reloj adelantado: se dice");
  assert.strictEqual(D.fmtAgo(-1000), "hace unos segundos", "un desfase de segundos no alarma");
  assert.strictEqual(D.fmtAgo(NaN), "—");
});

// ── parseEvents ──────────────────────────────────────────────────────────────────
test("parseEvents ordena por hora y desempata por secuencia", () => {
  const out = D.parseEvents(events(
    ev(5, T0 + 3000, "E", "vpn", "c"), ev(3, T0 + 1000, "I", "app", "a"),
    ev(4, T0 + 1000, "W", "cmd", "b"), ev(9, T0 + 2000, "F", "crash", "d")));
  assert.deepStrictEqual(out.map(e => e.msg), ["a", "b", "d", "c"]);
});
test("parseEvents: lo anotado DESPUÉS pero ocurrido ANTES queda en su hora (cierres del sistema)", () => {
  // seq 7 es un cierre del sistema anotado al arrancar, pero ocurrió antes que el seq 3.
  const out = D.parseEvents(events(ev(3, T0 + 5000, "I", "app", "arranque"), ev(7, T0 + 1000, "E", "exit", "ANR")));
  assert.deepStrictEqual(out.map(e => e.tag), ["exit", "app"]);
});
test("parseEvents tolera datos raros sin tirar la pantalla", () => {
  const out = D.parseEvents({
    e0000000001: "texto suelto", e0000000002: null, e0000000003: 7,
    e0000000004: { t: "no es número", l: "x", g: 5, m: null, n: "3" },
    e0000000005: { t: T0, l: "e", g: "vpn", m: "bien" },
    raro: { t: T0 + 1, l: "W", g: "a", m: "clave sin formato" }
  });
  assert.strictEqual(out.length, 3);
  const roto = out.find(e => e.key === "e0000000004");
  assert.strictEqual(roto.at, 0); assert.strictEqual(roto.level, "I"); assert.strictEqual(roto.tag, "");
  assert.strictEqual(roto.msg, ""); assert.strictEqual(roto.count, 3);
  assert.strictEqual(out.find(e => e.key === "e0000000005").level, "E", "nivel en minúscula se normaliza");
  assert.ok(Number.isNaN(out.find(e => e.key === "raro").seq));
  for (const bad of [null, undefined, 5, "x"]) assert.deepStrictEqual(D.parseEvents(bad), []);
});
test("parseEvents: count < 2 o inválido vale 1", () => {
  const out = D.parseEvents({ e1: { t: T0, l: "I", g: "a", m: "x", n: 1 }, e2: { t: T0 + 1, l: "I", g: "a", m: "y", n: 0 },
    e3: { t: T0 + 2, l: "I", g: "a", m: "z", n: 40 } });
  assert.deepStrictEqual(out.map(e => e.count), [1, 1, 40]);
});

// ── filtros ──────────────────────────────────────────────────────────────────────
const SAMPLE = () => D.parseEvents(events(
  ev(1, T0, "I", "app", "Proceso iniciado: v0.6.77"),
  ev(2, T0 + MIN, "W", "cmd", "Comando rechazado (BAD_SIGNATURE)"),
  ev(3, T0 + 2 * MIN, "E", "vpn", "Error al iniciar la VPN\n    at Foo.bar(Foo.kt:10)"),
  ev(4, T0 + 3 * MIN, "F", "crash", "Excepción no atrapada en el hilo 'main'", 2),
  ev(5, T0 + 4 * MIN, "I", "vpn", "Túnel DNS establecido")));
test("filterEvents por nivel: E incluye F; W incluye E y F", () => {
  assert.deepStrictEqual(D.filterEvents(SAMPLE(), { minLevel: "E" }).map(e => e.level), ["E", "F"]);
  assert.deepStrictEqual(D.filterEvents(SAMPLE(), { minLevel: "W" }).map(e => e.level), ["W", "E", "F"]);
  assert.strictEqual(D.filterEvents(SAMPLE(), {}).length, 5);
  assert.strictEqual(D.filterEvents(SAMPLE(), { minLevel: "basura" }).length, 5, "nivel desconocido = todo");
});
test("filterEvents por ventana, etiqueta y texto", () => {
  assert.strictEqual(D.filterEvents(SAMPLE(), { sinceMs: T0 + 2 * MIN }).length, 3);
  assert.strictEqual(D.filterEvents(SAMPLE(), { sinceMs: T0 + 2 * MIN + 1 }).length, 2);
  assert.strictEqual(D.filterEvents(SAMPLE(), { tag: "VPN" }).length, 2, "la etiqueta no distingue mayúsculas");
  assert.strictEqual(D.filterEvents(SAMPLE(), { text: "bad_signature" }).length, 1);
  assert.strictEqual(D.filterEvents(SAMPLE(), { text: "  CRASH " }).length, 1, "busca también en la etiqueta");
  assert.strictEqual(D.filterEvents(SAMPLE(), { tag: "vpn", minLevel: "E" }).length, 1, "los filtros se combinan");
  assert.deepStrictEqual(D.filterEvents(null, {}), []);
});
test("windowStart", () => {
  assert.strictEqual(D.windowStart("24h", T0), T0 - DIA);
  assert.strictEqual(D.windowStart("3d", T0), T0 - 3 * DIA);
  assert.strictEqual(D.windowStart("todo", T0), 0);
  assert.strictEqual(D.windowStart("no existe", T0), 0);
});
test("tagCounts ordena por cantidad y luego por nombre", () => {
  assert.deepStrictEqual(D.tagCounts(SAMPLE()), [{ tag: "vpn", n: 2 }, { tag: "app", n: 1 }, { tag: "cmd", n: 1 }, { tag: "crash", n: 1 }]);
  assert.deepStrictEqual(D.tagCounts([]), []);
});

// ── resumen ──────────────────────────────────────────────────────────────────────
test("summarize cuenta repeticiones y distingue error de caída", () => {
  const s = D.summarize(SAMPLE(), T0 + 10 * MIN);
  assert.strictEqual(s.rows, 5); assert.strictEqual(s.warnRows, 1);
  assert.strictEqual(s.errorRows, 2); assert.strictEqual(s.fatalRows, 1);
  assert.strictEqual(s.errors24h, 1 + 2, "el error (1) + la caída agrupada (×2)");
  assert.strictEqual(s.fatals24h, 2);
  assert.strictEqual(s.lastError.tag, "crash"); assert.strictEqual(s.lastFatal.tag, "crash");
  assert.strictEqual(s.oldestAt, T0); assert.strictEqual(s.newestAt, T0 + 4 * MIN);
});
test("summarize: lo de hace más de 24 h no cuenta como reciente, y la lista vacía no rompe", () => {
  const s = D.summarize(SAMPLE(), T0 + 2 * DIA);
  assert.strictEqual(s.errors24h, 0); assert.strictEqual(s.errorRows, 2);
  const v = D.summarize([], T0);
  assert.strictEqual(v.rows, 0); assert.strictEqual(v.lastError, null); assert.strictEqual(v.lastFatal, null);
});

// ── diagnósticos completos ───────────────────────────────────────────────────────
test("listSnapshots: más nuevo primero, textos siempre string", () => {
  const out = D.listSnapshots({
    s00000001: { t: T0 + 5, at: T0, pkgs: "com.waze", ver: "0.6.77 (140)", diag: 1, system: "S1", exits: "X1", logcat: "L1", packages: "P1" },
    s00000003: { t: T0 + 9, at: T0 + 2 * HORA, pkgs: "", ver: "0.6.77 (140)", diag: 1, system: "S3" },
    s00000002: { t: T0 + 7, system: 5, logcat: null },
    roto: "no es objeto"
  });
  assert.deepStrictEqual(out.map(s => s.key), ["s00000003", "s00000002", "s00000001"]);
  assert.strictEqual(out[0].exits, ""); assert.strictEqual(out[1].system, "5"); assert.strictEqual(out[1].logcat, "");
  assert.strictEqual(out[1].at, T0 + 7, "sin `at`, usa la hora de subida");
  assert.deepStrictEqual(D.listSnapshots(null), []);
});

// ── el celular y su estado ───────────────────────────────────────────────────────
test("pickDevice lee arriba y en info, y conserva el false", () => {
  const d = D.pickDevice({ deviceName: "itzi", info: { model: "Cat", androidVersion: "14", versionName: "0.6.77", versionCode: 140 },
    lastSeen: T0, diagVersion: 1, diagUploadEnabled: false }, "dev1");
  assert.strictEqual(d.name, "itzi"); assert.strictEqual(d.model, "Cat"); assert.strictEqual(d.androidVersion, "14");
  assert.strictEqual(d.versionCode, 140); assert.strictEqual(d.diagVersion, 1);
  assert.strictEqual(d.diagUploadEnabled, false, "false es un dato, no una falta de dato");
  assert.strictEqual(d.diagUploadError, null);
  assert.strictEqual(D.pickDevice({ info: { diagUploadEnabled: true } }, "x").diagUploadEnabled, true);
  assert.strictEqual(D.pickDevice({}, "x").diagUploadEnabled, null);
  assert.strictEqual(D.pickDevice(null, "x").name, "Celular");
  assert.strictEqual(D.pickDevice({ model: "Q1" }, "x").name, "Q1", "sin nombre usa el modelo");
});

test("describeStatus: sin versión del registro", () => {
  const s = D.describeStatus(D.pickDevice({ deviceName: "a" }, "a"), {}, T0);
  assert.strictEqual(s.level, "off"); assert.strictEqual(s.canRequest, false);
  assert.ok(/OTA/.test(s.detail));
});
test("describeStatus: subida apagada gana sobre un error viejo", () => {
  const s = D.describeStatus(D.pickDevice({ diagVersion: 1, diagUploadEnabled: false, diagUploadError: "x" }, "a"), {}, T0);
  assert.strictEqual(s.level, "warn"); assert.strictEqual(s.canRequest, false);
  assert.ok(/APAGADA/.test(s.headline));
});
test("describeStatus: error de subida (reglas sin desplegar)", () => {
  const s = D.describeStatus(D.pickDevice({ diagVersion: 1, diagUploadEnabled: true, diagUploadError: "Permission denied" }, "a"), {}, T0);
  assert.strictEqual(s.level, "bad"); assert.strictEqual(s.canRequest, true);
  assert.ok(/Permission denied/.test(s.detail) && /database/.test(s.detail));
});
test("describeStatus: activo, con y sin envíos; avisa los renglones omitidos", () => {
  const dev = D.pickDevice({ diagVersion: 1, diagUploadEnabled: true }, "a");
  const sin = D.describeStatus(dev, {}, T0);
  assert.strictEqual(sin.level, "ok"); assert.ok(/Todavía no llegó/.test(sin.detail));
  const con = D.describeStatus(dev, { lastUploadAt: T0 - 5 * MIN, skipped: 12 }, T0);
  assert.ok(/hace 5 min/.test(con.detail)); assert.strictEqual(con.notes.length, 1); assert.ok(/12/.test(con.notes[0]));
  assert.strictEqual(D.describeStatus(dev, { skipped: 0 }, T0).notes.length, 0);
  // diagUploadEnabled sin dato (todavía no publicó el campo) se trata como activo
  assert.strictEqual(D.describeStatus(D.pickDevice({ diagVersion: 1 }, "a"), {}, T0).level, "ok");
});

// ── paquetes ─────────────────────────────────────────────────────────────────────
test("normalizePackages", () => {
  let r = D.normalizePackages(" com.whatsapp,com.waze ;  com.whatsapp\ncom.google.android.apps.dynamite ");
  assert.deepStrictEqual(r.list, ["com.whatsapp", "com.waze", "com.google.android.apps.dynamite"]);
  assert.deepStrictEqual(r.invalid, []); assert.strictEqual(r.tooMany, false);
  r = D.normalizePackages("waze, 1com.x, com., com.ok, com.x y");
  assert.deepStrictEqual(r.list, ["com.ok", "com.x"]);
  assert.deepStrictEqual(r.invalid, ["waze", "1com.x", "com.", "y"]);
  r = D.normalizePackages("a.a1,a.a2,a.a3,a.a4,a.a5,a.a6,a.a7");
  assert.strictEqual(r.list.length, D.LAYOUT.MAX_PACKAGES); assert.strictEqual(r.tooMany, true);
  assert.deepStrictEqual(D.normalizePackages(""), { list: [], invalid: [], tooMany: false });
  assert.deepStrictEqual(D.normalizePackages(null), { list: [], invalid: [], tooMany: false });
  assert.deepStrictEqual(D.normalizePackages("constructor, __proto__").list, [], "nombres que son propiedades de Object no cuelan");
  assert.ok(D.normalizePackages("a." + "b".repeat(250)).invalid.length === 1, "más de 200 caracteres no es un paquete");
});

// ── el informe ───────────────────────────────────────────────────────────────────
const DEV = () => D.pickDevice({ deviceName: "itzi", model: "Cat S62", androidVersion: "12", versionName: "0.6.77", versionCode: 140,
  lastSeen: T0 + 4 * MIN, diagVersion: 1, diagUploadEnabled: true }, "dev1");
const SNAP = () => D.listSnapshots({ s00000002: { t: T0 + 6 * MIN, at: T0 + 5 * MIN, pkgs: "com.whatsapp", ver: "0.6.77 (140)", diag: 1,
  system: "Android 12\nBatería 80%", exits: "ANR hace 2 h", logcat: "E/AndroidRuntime: boom", packages: "com.whatsapp: suspendida=no" } })[0];
const REPORT = (extra) => D.buildReport(Object.assign({ device: DEV(), meta: { lastUploadAt: T0 + 3 * MIN }, events: SAMPLE(),
  snapshot: SNAP(), now: T0 + 10 * MIN, offsetMin: AR }, extra || {}));

test("buildReport: encabezado completo", () => {
  const r = REPORT();
  assert.ok(r.startsWith("# Informe de diagnóstico de LockSuite\n"));
  assert.ok(r.includes("Generado: 06/11/2026 18:10"));
  assert.ok(r.includes("Equipo: itzi (id: dev1) — Cat S62, Android 12"));
  assert.ok(r.includes("LockSuite: 0.6.77 (140) · registro de diagnóstico: formato 1 · subida: encendida"));
  assert.ok(r.includes("Último contacto del equipo con la nube: 06/11/2026 18:04 (hace 6 min)"));
  assert.ok(r.includes("Último envío del registro: 06/11/2026 18:03"));
});
test("buildReport: eventos en orden cronológico, con repeticiones y trazas indentadas", () => {
  const r = REPORT();
  const i1 = r.indexOf("[06/11 18:00:00] I app"), i3 = r.indexOf("[06/11 18:02:00] E vpn"), i4 = r.indexOf("[06/11 18:03:00] F crash ×2");
  assert.ok(i1 > 0 && i3 > i1 && i4 > i3, "orden cronológico");
  assert.ok(r.includes("E vpn — Error al iniciar la VPN\n        at Foo.bar(Foo.kt:10)"), "la continuación va indentada 4 espacios más");
  assert.ok(r.includes("## Eventos del celular (5 de 5 guardados)"));
  assert.ok(r.includes("Filtro: nivel: todos. Dentro del filtro: 5 renglones (2 de error o caída, 1 avisos)."));
});
test("buildReport: respeta el filtro de nivel y lo dice", () => {
  const r = REPORT({ eventOptions: { minLevel: "E" } });
  assert.ok(r.includes("nivel: solo errores y caídas"));
  assert.ok(!r.includes("Proceso iniciado") && !r.includes("BAD_SIGNATURE"));
  assert.ok(r.includes("E vpn") && r.includes("F crash"));
  assert.ok(REPORT({ eventOptions: { minLevel: "W" } }).includes("nivel: avisos y errores"));
});
test("buildReport: la nota del usuario, solo si la hay", () => {
  const con = REPORT({ note: "  No me abre WhatsApp, se cortó la llamada  " });
  assert.ok(con.includes("## Qué contó el usuario\nNo me abre WhatsApp, se cortó la llamada\n"));
  assert.ok(!REPORT({ note: "   " }).includes("Qué contó el usuario"));
});
test("buildReport: diagnóstico completo con sus cuatro secciones", () => {
  const r = REPORT();
  assert.ok(r.includes("## Diagnóstico completo pedido desde el panel — 06/11/2026 18:05"));
  assert.ok(r.includes("Apps consultadas: com.whatsapp."));
  for (const h of ["### Sistema\nAndroid 12\nBatería 80%", "### Cierres recientes del proceso (los anotó Android)\nANR hace 2 h",
    "### Errores del logcat de LockSuite\nE/AndroidRuntime: boom", "### Apps consultadas\ncom.whatsapp: suspendida=no"]) {
    assert.ok(r.includes(h), h);
  }
});
test("buildReport: sin diagnóstico, o excluido, lo dice (no inventa)", () => {
  assert.ok(REPORT({ snapshot: null }).includes("Todavía no se pidió ninguno"));
  const ex = REPORT({ includeSnapshot: false });
  assert.ok(ex.includes("(No se incluyó.)") && !ex.includes("### Sistema"));
  const sinApps = REPORT({ snapshot: Object.assign({}, SNAP(), { pkgs: "", packages: "" }) });
  assert.ok(sinApps.includes("(no se pidió ninguna app en particular)"));
  const vacio = REPORT({ snapshot: Object.assign({}, SNAP(), { exits: "" }) });
  assert.ok(vacio.includes("anotó Android)\n(vacío)"));
});
test("buildReport: recorta lo más VIEJO y lo dice", () => {
  const items = []; for (let i = 1; i <= 30; i++) items.push(ev(i, T0 + i * MIN, "I", "app", "evento número " + i));
  const r = D.buildReport({ device: DEV(), events: D.parseEvents(events(...items)), now: T0 + HORA, offsetMin: AR, maxEvents: 10 });
  assert.ok(r.includes("evento número 30") && r.includes("evento número 21") && !r.includes("evento número 20\n") && !r.includes("evento número 1\n"));
  assert.ok(r.includes("(Se omitieron los 20 renglones más viejos"));
  assert.ok(r.includes("## Eventos del celular (10 de 30 guardados)"), "el encabezado dice cuántos se muestran de cuántos hay");
});
test("buildReport: tope por caracteres también deja los más nuevos", () => {
  const items = []; for (let i = 1; i <= 10; i++) items.push(ev(i, T0 + i * MIN, "I", "app", ("x".repeat(100)) + i));
  const r = D.buildReport({ device: DEV(), events: D.parseEvents(events(...items)), now: T0 + HORA, offsetMin: AR, maxEventChars: 400 });
  assert.ok(r.includes("x".repeat(100) + "10") && !r.includes("x".repeat(100) + "1\n"));
  assert.ok(/Se omitieron los \d+ renglones más viejos/.test(r));
});
test("buildReport: sin eventos, o ninguno cumple el filtro", () => {
  assert.ok(D.buildReport({ device: DEV(), events: [], now: T0, offsetMin: AR }).includes("El celular no subió ningún renglón todavía"));
  assert.ok(REPORT({ eventOptions: { text: "no está en ningún lado" } }).includes("Ningún renglón cumple el filtro"));
});
test("buildReport: alerta de error de subida y de renglones omitidos; subida apagada", () => {
  const dev = D.pickDevice({ deviceName: "x", diagVersion: 1, diagUploadEnabled: false, diagUploadError: "Permission denied" }, "x");
  const r = D.buildReport({ device: dev, meta: { skipped: 7 }, events: [], now: T0, offsetMin: AR });
  assert.ok(r.includes("subida: APAGADA") && r.includes("Error de subida informado por el celular: Permission denied"));
  assert.ok(r.includes("omitió 7 renglones viejos"));
  const nuevo = D.buildReport({ device: D.pickDevice({}, "y"), events: [], now: T0, offsetMin: AR });
  assert.ok(nuevo.includes("registro de diagnóstico: no informado") && nuevo.includes("subida: sin dato"));
});
test("buildReport: explica solo las etiquetas que aparecen", () => {
  const r = REPORT({ eventOptions: { tag: "vpn" } });
  assert.ok(r.includes("vpn = filtro DNS (VPN)"));
  assert.ok(!r.includes("crash = caída") && !r.includes("a11y = "));
  assert.ok(REPORT().includes("crash = caída"));
});
test("buildReport: la ventana de tiempo aparece en el filtro", () => {
  const r = REPORT({ eventOptions: { sinceMs: T0 + 2 * MIN } });
  assert.ok(r.includes("desde 06/11/2026 18:02") && !r.includes("Proceso iniciado"));
});

// ── el contrato con el celular ───────────────────────────────────────────────────
test("LAYOUT: valores que el celular también usa", () => {
  assert.strictEqual(D.LAYOUT.ROOT, "deviceDiag");
  assert.deepStrictEqual([D.LAYOUT.EVENT_AT, D.LAYOUT.EVENT_LEVEL, D.LAYOUT.EVENT_TAG, D.LAYOUT.EVENT_MSG, D.LAYOUT.EVENT_COUNT], ["t", "l", "g", "m", "n"]);
  assert.strictEqual(D.LAYOUT.EVENTS_KEEP, 400); assert.strictEqual(D.LAYOUT.SNAPSHOTS_KEEP, 3); assert.strictEqual(D.LAYOUT.MAX_PACKAGES, 6);
  assert.ok(Object.isFrozen(D.LAYOUT));
});

if (process.exitCode) console.error("\nHAY PRUEBAS QUE FALLAN");
else console.log("OK (" + pass + " pruebas)");
