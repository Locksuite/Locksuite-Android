#!/usr/bin/env node
"use strict";
/**
 * Prueba de admin-backend/public/tienda_logic.js (B.101) — sin red y sin dependencias.
 *
 *     node tools/test_tienda_logic.js
 *
 * Es el banco de pruebas de la parte del panel que decide QUÉ se guarda en
 * `globalSettings/playStore/apps` y qué apps aparecen "por defecto" desde la tienda vieja
 * (pedido del dueño del 30/10/2026). Los casos de validación son los mismos de
 * PlayStoreCatalogTest.kt: el panel y el celular tienen que aceptar y rechazar LO MISMO.
 */
const assert = require("assert");
const path = require("path");
const T = require(path.join(__dirname, "..", "admin-backend", "public", "tienda_logic.js"));

let pass = 0;
function test(name, fn) {
  try { fn(); pass++; } catch (e) {
    console.error("✗ " + name + "\n  " + (e && e.stack || e));
    process.exitCode = 1;
  }
}

// ── La tienda vieja tal como la describe DOC_CLAUDE_TIENDA_APKS_2026-09-10.md ──────
function oldStore() {
  const e = (label, pkg, sha) => ({ label: label, packageName: pkg, apkUrl: "https://example.invalid/" + pkg + ".apk", sha256: sha || "0".repeat(64) });
  const list = [
    e("Cuando SUBO CABA", "com.ejemplo.cuandosubo"),
    e("SimpleCallApp", "com.simplecallapp"),
    e("סידור תפילון", "tfilon.tfilon"),
    e("Traductor de Google", "com.google.android.apps.translate"),
    e("Gboard (Teclado Google)", "com.google.android.inputmethod.latin"),
    e("Clima (מרכז המידע Lomdaat)", "life.channel.accurate.local.weather.forecast"),
    e("HebDate (Calendario hebreo)", "com.lionscribe.hebdate"),
    e("Ajdut Kosher", "com.beatmobile.ak"),
    e("Waze", "com.waze"),
    e("Moovit", "com.tranzmate"),
    e("Google Chat", "com.google.android.apps.dynamite"),
    e("WhatsApp", "com.whatsapp"),
    e("Zing Music", "fm.jewishmusic.application"),
    e("Jusic (ג׳וזיק)", "com.lomdaat.apps.music"),
    e("Mesira", "net.mesira.app"),
    e("Rav-Kav Online", "com.pcentra.ravkavonlinemobile"),
  ];
  const out = {};
  for (const x of list) out[T.pkgKey(x.packageName)] = x;
  return out;
}

// ── Lista inicial ────────────────────────────────────────────────────────────────
test("la lista inicial trae las 16 apps de la tienda vieja", () => {
  const { rows, skipped } = T.seedFromStoreApps(oldStore(), []);
  assert.strictEqual(rows.length, 16);
  assert.strictEqual(skipped.length, 0);
});

test("todas arrancan en nivel 1, acceso completa, sin guardar y marcadas como de la tienda vieja", () => {
  const { rows } = T.seedFromStoreApps(oldStore(), []);
  for (const r of rows) {
    assert.strictEqual(r.tier, 1, r.pkg);
    assert.strictEqual(r.access, "full", r.pkg);
    assert.strictEqual(r.origin, "seed", r.pkg);
    assert.strictEqual(r.inOldStore, true, r.pkg);
  }
});

test("el estado inicial copia lo que el dueño ya había decidido: permitida → habilitada, el resto → a pedido", () => {
  const { rows } = T.seedFromStoreApps(oldStore(), ["com.waze", "com.whatsapp"]);
  const by = Object.fromEntries(rows.map((r) => [r.pkg, r]));
  assert.strictEqual(by["com.waze"].status, "enabled");
  assert.strictEqual(by["com.whatsapp"].status, "enabled");
  assert.strictEqual(by["com.tranzmate"].status, "request");
  assert.strictEqual(rows.filter((r) => r.status === "enabled").length, 2);
  // Ninguna arranca bloqueada: bloquear es una decisión del dueño, no un valor por defecto.
  assert.strictEqual(rows.filter((r) => r.status === "blocked").length, 0);
});

test("con CERO apps permitidas en la tienda vieja, ninguna arranca habilitada", () => {
  const { rows } = T.seedFromStoreApps(oldStore(), null);
  assert.strictEqual(rows.filter((r) => r.status === "enabled").length, 0);
  assert.strictEqual(rows.filter((r) => r.status === "request").length, 16);
});

test("allowedPackages puede venir como array o como objeto de Realtime Database", () => {
  const a = T.seedFromStoreApps(oldStore(), ["com.waze"]).rows.find((r) => r.pkg === "com.waze");
  const b = T.seedFromStoreApps(oldStore(), { 0: "com.waze" }).rows.find((r) => r.pkg === "com.waze");
  assert.strictEqual(a.status, "enabled");
  assert.strictEqual(b.status, "enabled");
});

test("los stubs de Android Auto NO entran; la Maps REAL (otra huella) sí", () => {
  const s = oldStore();
  s["com_google_android_apps_maps"] = { label: "Google Maps", packageName: "com.google.android.apps.maps", sha256: T.AA_STUB_SHA256["com.google.android.apps.maps"] };
  s["com_google_android_googlequicksearchbox"] = { label: "Google", packageName: "com.google.android.googlequicksearchbox", sha256: T.AA_STUB_SHA256["com.google.android.googlequicksearchbox"].toUpperCase() };
  let seed = T.seedFromStoreApps(s, []);
  assert.strictEqual(seed.rows.length, 16);
  assert.deepStrictEqual(seed.skipped.map((x) => x.reason).sort(), ["aa-stub", "aa-stub"]);

  // Misma clave pero con una huella distinta: es la app de verdad, no el stub.
  s["com_google_android_apps_maps"].sha256 = "f".repeat(64);
  seed = T.seedFromStoreApps(s, []);
  assert.strictEqual(seed.rows.length, 17);
  assert.ok(seed.rows.some((r) => r.pkg === "com.google.android.apps.maps"));
});

test("lo que la tienda nunca ofrece (Google Play, LockSuite…) no entra", () => {
  const s = oldStore();
  s["com_android_vending"] = { label: "Play Store", packageName: "com.android.vending" };
  s["com_ejemplo_locksuite"] = { label: "LockSuite", packageName: "com.ejemplo.locksuite" };
  const seed = T.seedFromStoreApps(s, ["com.android.vending"]);
  assert.strictEqual(seed.rows.length, 16);
  assert.deepStrictEqual(seed.skipped.map((x) => x.reason).sort(), ["never", "never"]);
});

test("un paquete mal escrito se saltea, y se avisa", () => {
  const s = oldStore();
  s["roto"] = { label: "Roto", packageName: "esto no es un paquete" };
  const seed = T.seedFromStoreApps(s, []);
  assert.strictEqual(seed.rows.length, 16);
  assert.strictEqual(seed.skipped.length, 1);
  assert.strictEqual(seed.skipped[0].reason, "invalid");
});

test("sin campo packageName se recupera de la clave", () => {
  const seed = T.seedFromStoreApps({ com_waze: { label: "Waze" } }, []);
  assert.strictEqual(seed.rows[0].pkg, "com.waze");
});

test("el paquete real sale del campo, no de la clave (los '_' no son reversibles)", () => {
  const seed = T.seedFromStoreApps({ life_channel_my_app: { label: "X", packageName: "life.channel.my_app" } }, []);
  assert.strictEqual(seed.rows[0].pkg, "life.channel.my_app");
});

test("la lista inicial sale ordenada por nombre y sin repetidos", () => {
  const s = oldStore();
  s["dup"] = { label: "Waze otra vez", packageName: "com.waze" };
  const { rows } = T.seedFromStoreApps(s, []);
  assert.strictEqual(rows.filter((r) => r.pkg === "com.waze").length, 1);
  const labels = rows.map((r) => r.label.toLowerCase());
  assert.deepStrictEqual(labels, labels.slice().sort());
});

test("storeApps vacío, nulo o raro → lista vacía, sin romperse", () => {
  for (const bad of [null, undefined, {}, [], "x", 5]) {
    const r = T.seedFromStoreApps(bad, []);
    assert.deepStrictEqual(r.rows, []);
  }
  assert.deepStrictEqual(T.seedFromStoreApps({ a: null, b: 7 }, []).rows, []);
});

test("«Traer de la tienda vieja» solo suma las que faltan", () => {
  const have = T.seedFromStoreApps(oldStore(), []).rows.slice(0, 10);
  const falta = T.missingFromOldStore(have, oldStore(), []);
  assert.strictEqual(falta.rows.length, 6);
  const todas = T.missingFromOldStore(T.seedFromStoreApps(oldStore(), []).rows, oldStore(), []);
  assert.strictEqual(todas.rows.length, 0);
});

// ── Guardar ──────────────────────────────────────────────────────────────────────
test("guardar: la lista inicial entera produce un payload sin problemas", () => {
  const { rows } = T.seedFromStoreApps(oldStore(), ["com.waze"]);
  const p = T.buildPayload(rows);
  assert.strictEqual(p.count, 16);
  assert.deepStrictEqual(p.problems, []);
  const waze = p.apps["com_waze"];
  assert.deepStrictEqual(waze, { packageName: "com.waze", label: "Waze", access: "full", status: "enabled", tier: 1 });
});

test("guardar: BLOQUEADA se guarda como bloqueada (no se pierde); ELIMINAR es quitar la fila", () => {
  const { rows } = T.seedFromStoreApps(oldStore(), []);
  rows.find((r) => r.pkg === "com.waze").status = "blocked";
  const sinMoovit = rows.filter((r) => r.pkg !== "com.tranzmate");
  const p = T.buildPayload(sinMoovit);
  assert.strictEqual(p.apps["com_waze"].status, "blocked");
  assert.strictEqual(p.apps["com_tranzmate"], undefined);
  assert.strictEqual(p.count, 15);
});

test("guardar: los cuatro estados de la pantalla llegan tal cual", () => {
  const rows = [
    T.makeRow({ pkg: "com.a.uno", status: "enabled" }),
    T.makeRow({ pkg: "com.a.dos", status: "request" }),
    T.makeRow({ pkg: "com.a.tres", status: "blocked" }),
  ];
  const p = T.buildPayload(rows);
  assert.deepStrictEqual(["com_a_uno", "com_a_dos", "com_a_tres"].map((k) => p.apps[k].status), ["enabled", "request", "blocked"]);
});

test("guardar: acceso por dominios limpia, deduplica y no escribe arrays vacíos", () => {
  const r = T.makeRow({ pkg: "com.a.dom", access: "domains", allow: ["https://A.com/x", "a.com", "b.com", "roto"], block: [] });
  const p = T.buildPayload([r]);
  const o = p.apps["com_a_dom"];
  assert.deepStrictEqual(o.allow, ["a.com", "b.com"]);
  assert.strictEqual("block" in o, false);
  assert.ok(p.problems.some((x) => x.includes("roto")), "avisa del dominio descartado");
});

test("guardar: los dominios solo viajan con acceso «dominios»", () => {
  const r = T.makeRow({ pkg: "com.a.full", access: "full", allow: ["a.com"], block: ["b.com"] });
  const o = T.buildPayload([r]).apps["com_a_full"];
  assert.strictEqual("allow" in o, false);
  assert.strictEqual("block" in o, false);
});

test("guardar: «dominios» sin ningún dominio avisa (la app quedaría sin internet)", () => {
  const p = T.buildPayload([T.makeRow({ pkg: "com.a.vacia", access: "domains" })]);
  assert.ok(p.problems.some((x) => /sin ning/.test(x)));
});

test("guardar: un dominio en permitidos y prohibidos avisa quién gana", () => {
  const p = T.buildPayload([T.makeRow({ pkg: "com.a.dos", access: "domains", allow: ["a.com"], block: ["a.com"] })]);
  assert.ok(p.problems.some((x) => /gana «prohibido»/.test(x)));
});

test("guardar: nada inválido llega al payload", () => {
  const rows = [
    T.makeRow({ pkg: "no es paquete" }),
    T.makeRow({ pkg: "com.android.vending" }),
    T.makeRow({ pkg: "com.a.nivel0", tier: 0 }),
    T.makeRow({ pkg: "com.a.nivel4", tier: 4 }),
    T.makeRow({ pkg: "com.a.nivelx", tier: "x" }),
    T.makeRow({ pkg: "com.a.estado", status: "otro" }),
    T.makeRow({ pkg: "com.a.acceso", access: "otro" }),
    T.makeRow({ pkg: "com.a.bien", tier: 3 }),
  ];
  const p = T.buildPayload(rows);
  assert.deepStrictEqual(Object.keys(p.apps), ["com_a_bien"]);
  assert.strictEqual(p.problems.length, 7);
});

test("guardar: un ícono que no es del CDN de Google se descarta", () => {
  const ok = T.buildPayload([T.makeRow({ pkg: "com.a.ico", iconUrl: "https://play-lh.googleusercontent.com/abc=s64" })]);
  assert.strictEqual(ok.apps["com_a_ico"].iconUrl, "https://play-lh.googleusercontent.com/abc=s64");
  const mal = T.buildPayload([T.makeRow({ pkg: "com.a.ico", iconUrl: "https://evil.example.com/x.png" })]);
  assert.strictEqual("iconUrl" in mal.apps["com_a_ico"], false);
});

test("guardar: el tope de apps se respeta y se avisa", () => {
  const rows = [];
  for (let i = 0; i < T.MAX_APPS + 5; i++) rows.push(T.makeRow({ pkg: "com.t.app" + i }));
  const p = T.buildPayload(rows);
  assert.strictEqual(p.count, T.MAX_APPS);
  assert.strictEqual(p.problems.length, 5);
});

test("extras de un equipo: sin nivel y con tope propio", () => {
  const p = T.buildPayload([T.makeRow({ pkg: "com.e.uno", tier: 0, status: "enabled" })], { extra: true });
  assert.deepStrictEqual(p.apps["com_e_uno"], { packageName: "com.e.uno", label: "Uno", access: "full", status: "enabled" });
  const rows = [];
  for (let i = 0; i < T.MAX_EXTRAS + 3; i++) rows.push(T.makeRow({ pkg: "com.e.app" + i, tier: 0 }));
  assert.strictEqual(T.buildPayload(rows, { extra: true }).count, T.MAX_EXTRAS);
});

test("guardar → leer → guardar da lo mismo (no se pierde ni se inventa nada)", () => {
  const rows = T.seedFromStoreApps(oldStore(), ["com.waze"]).rows;
  rows.find((r) => r.pkg === "com.tranzmate").access = "domains";
  rows.find((r) => r.pkg === "com.tranzmate").allow = ["moovitapp.com"];
  rows.find((r) => r.pkg === "com.whatsapp").tier = 2;
  rows.find((r) => r.pkg === "com.whatsapp").status = "blocked";
  const p1 = T.buildPayload(rows);
  // Realtime Database devuelve los arrays como arrays (o como objetos con índices).
  const viaFirebase = JSON.parse(JSON.stringify(p1.apps));
  viaFirebase["com_tranzmate"].allow = { 0: "moovitapp.com" };
  const leidas = T.rowsFromSaved(viaFirebase);
  const p2 = T.buildPayload(leidas);
  assert.deepStrictEqual(p2.apps, p1.apps);
  assert.ok(leidas.every((r) => r.origin === "saved"));
});

test("leer: una entrada con estado ausente cuenta como habilitada, igual que en el celular", () => {
  const r = T.rowsFromSaved({ com_a_b: { packageName: "com.a.b", label: "B", tier: 2 } })[0];
  assert.strictEqual(r.status, "enabled");
  assert.strictEqual(r.access, "full");
  assert.strictEqual(r.tier, 2);
});

// ── Verificar en Play ────────────────────────────────────────────────────────────
test("«Verificar en Play»: marca, completa el ícono y el nombre SOLO si nadie lo había escrito", () => {
  const rows = [
    T.makeRow({ pkg: "com.waze", label: "Waze" }),
    T.makeRow({ pkg: "com.nuevo.app" }),          // sin nombre escrito: se completa con el de Play
    T.makeRow({ pkg: "com.gone.app", label: "Gone" }),
    T.makeRow({ pkg: "com.dudosa.app", label: "Dudosa" }),
  ];
  const n = T.applyPlayCheck(rows, [
    { packageName: "com.waze", status: "found", title: "Waze Navigation", iconUrl: "https://play-lh.googleusercontent.com/w=s128-rw" },
    { packageName: "com.nuevo.app", status: "found", title: "Nueva App Linda", iconUrl: "https://evil.example.com/x.png" },
    { packageName: "com.gone.app", status: "missing" },
    { packageName: "com.dudosa.app", status: "unknown" },
    { packageName: "com.otra.app", status: "found" },
  ]);
  assert.strictEqual(n, 4);
  assert.strictEqual(rows[0].label, "Waze", "no pisa el nombre que escribió el dueño");
  assert.strictEqual(rows[0].iconUrl, "https://play-lh.googleusercontent.com/w=s128-rw");
  assert.strictEqual(rows[0].play, "found");
  assert.strictEqual(rows[1].label, "Nueva App Linda");
  assert.strictEqual(rows[1].iconUrl, null, "ícono de otro sitio rechazado");
  assert.strictEqual(rows[2].play, "missing");
  assert.strictEqual(rows[3].play, "unknown");
});

test("«Verificar en Play»: un estado raro se toma como desconocido, nunca como 'no existe'", () => {
  const rows = [T.makeRow({ pkg: "com.x.y" })];
  T.applyPlayCheck(rows, [{ packageName: "com.x.y", status: "lo-que-sea" }]);
  assert.strictEqual(rows[0].play, "unknown");
});

// ── Qué ve cada modo ─────────────────────────────────────────────────────────────
test("visibleIn: igual que PlayStoreCatalog.listed (niveles acumulativos, bloqueadas fuera, a pedido dentro)", () => {
  const rows = [
    T.makeRow({ pkg: "com.k.uno", tier: 1, status: "enabled" }),
    T.makeRow({ pkg: "com.k.pedida", tier: 1, status: "request" }),
    T.makeRow({ pkg: "com.k.bloq", tier: 1, status: "blocked" }),
    T.makeRow({ pkg: "com.t.dos", tier: 2, status: "enabled" }),
    T.makeRow({ pkg: "com.l.tres", tier: 3, status: "enabled" }),
  ];
  const names = (m) => T.visibleIn(rows, m).map((r) => r.pkg).sort();
  assert.deepStrictEqual(names("cerrada"), []);
  assert.deepStrictEqual(names("kosher"), ["com.k.pedida", "com.k.uno"]);
  assert.deepStrictEqual(names("trabajo"), ["com.k.pedida", "com.k.uno", "com.t.dos"]);
  assert.deepStrictEqual(names("lite"), ["com.k.pedida", "com.k.uno", "com.l.tres", "com.t.dos"]);
  assert.deepStrictEqual(names("modo-roto"), [], "modo desconocido = cerrada");
});

test("summarize cuenta por estado y nivel", () => {
  const s = T.summarize([
    T.makeRow({ pkg: "com.a.uno", tier: 1, status: "enabled" }),
    T.makeRow({ pkg: "com.a.dos", tier: 2, status: "request" }),
    T.makeRow({ pkg: "com.a.tres", tier: 2, status: "blocked" }),
  ]);
  assert.deepStrictEqual(s, { total: 3, enabled: 1, request: 1, blocked: 1, tiers: { 1: 1, 2: 2, 3: 0 } });
});

test("signature detecta cualquier cambio visible", () => {
  const a = [T.makeRow({ pkg: "com.a.b", label: "B" })];
  const base = T.signature(a);
  const cambios = [
    (r) => { r.label = "C"; }, (r) => { r.tier = 2; }, (r) => { r.access = "noweb"; },
    (r) => { r.status = "blocked"; }, (r) => { r.status = "request"; },
    (r) => { r.iconUrl = "https://play-lh.googleusercontent.com/x"; },
  ];
  for (const c of cambios) {
    const b = [T.makeRow({ pkg: "com.a.b", label: "B" })];
    c(b[0]);
    assert.notStrictEqual(T.signature(b), base);
  }
  assert.strictEqual(T.signature([T.makeRow({ pkg: "com.a.b", label: "B" })]), base);
});

test("signature: los dominios cuentan solo con acceso «dominios» (los restos no son un cambio)", () => {
  // Con acceso «dominios», editar permitidos o prohibidos SÍ es un cambio.
  const dom = () => T.makeRow({ pkg: "com.a.b", label: "B", access: "domains" });
  const base = T.signature([dom()]);
  const a = dom(); a.allow = ["a.com"];
  const b = dom(); b.block = ["a.com"];
  assert.notStrictEqual(T.signature([a]), base);
  assert.notStrictEqual(T.signature([b]), base);
  // Con cualquier otro acceso, buildPayload no los escribe → tampoco cambian la firma.
  for (const acceso of ["full", "noweb"]) {
    const limpia = T.makeRow({ pkg: "com.a.b", label: "B", access: acceso });
    const conRestos = T.makeRow({ pkg: "com.a.b", label: "B", access: acceso, allow: ["a.com"], block: ["b.com"] });
    assert.strictEqual(T.signature([conRestos]), T.signature([limpia]), acceso);
    assert.deepStrictEqual(T.buildPayload([conRestos]).apps, T.buildPayload([limpia]).apps, acceso);
  }
});

// ── Lo que ve UN celular (espejo de PlayStoreCatalog.listed) ─────────────────────
function catalogo() {
  const r = (pkg, label, tier, status, extra) => T.makeRow(Object.assign({ pkg: pkg, label: label, tier: tier, status: status }, extra || {}));
  return [
    r("com.k.uno", "Beta", 1, "enabled"),
    r("com.k.dos", "Alfa", 1, "request"),
    r("com.k.tres", "Gamma", 1, "blocked"),
    r("com.t.uno", "Delta", 2, "enabled"),
    r("com.l.uno", "Epsilon", 3, "enabled"),
  ];
}
const nombres = (rows) => rows.map((x) => x.label);

test("maxTier y normalizeMode: lo desconocido, vacío o ausente es CERRADA (nunca se abre por un dato roto)", () => {
  assert.deepStrictEqual(["cerrada", "kosher", "trabajo", "lite"].map(T.maxTier), [0, 1, 2, 3]);
  assert.strictEqual(T.maxTier("  LITE "), 3);
  for (const raro of [null, undefined, "", "pro", "todo", 3, {}, "kosher2"]) {
    assert.strictEqual(T.maxTier(raro), 0, String(raro));
    assert.strictEqual(T.normalizeMode(raro), "cerrada", String(raro));
  }
  assert.strictEqual(T.normalizeMode(" Trabajo "), "trabajo");
});

test("listedFor: niveles ACUMULATIVOS por modo, ordenadas por nombre, sin bloqueadas, con las «a pedido»", () => {
  const g = catalogo();
  assert.deepStrictEqual(nombres(T.listedFor("kosher", g, [])), ["Alfa", "Beta"]);                       // Gamma (bloqueada) no
  assert.deepStrictEqual(nombres(T.listedFor("trabajo", g, [])), ["Alfa", "Beta", "Delta"]);
  assert.deepStrictEqual(nombres(T.listedFor("lite", g, [])), ["Alfa", "Beta", "Delta", "Epsilon"]);
  assert.strictEqual(T.listedFor("lite", g, []).find((x) => x.label === "Alfa").status, "request");     // se muestra con «Pedir»
});

test("listedFor: con la tienda cerrada no hay tienda, tenga extras o no", () => {
  const extras = [T.makeRow({ pkg: "com.x.extra", label: "Extra", tier: 0, status: "enabled" })];
  for (const modo of ["cerrada", "", null, undefined, "raro"]) {
    assert.deepStrictEqual(T.listedFor(modo, catalogo(), extras), [], String(modo));
  }
});

test("listedFor: un extra entra en cualquier modo abierto y REEMPLAZA a la entrada global del mismo paquete", () => {
  const g = catalogo();
  // Aprobar una «a pedido» (Alfa): el extra la deja habilitada solo para este equipo.
  const aprobada = T.makeRow({ pkg: "com.k.dos", label: "Alfa", tier: 0, status: "enabled" });
  const kosher = T.listedFor("kosher", g, [aprobada]);
  assert.strictEqual(kosher.find((x) => x.label === "Alfa").status, "enabled");
  assert.strictEqual(kosher.length, 2, "no se duplica");
  // Bloquear una global SOLO para este usuario.
  const bloqueada = T.makeRow({ pkg: "com.k.uno", label: "Beta", tier: 0, status: "blocked" });
  assert.deepStrictEqual(nombres(T.listedFor("kosher", g, [bloqueada])), ["Alfa"]);
  // Un extra de una app que no está en el catálogo aparece aunque ningún nivel la cubra.
  const propia = T.makeRow({ pkg: "com.x.solo", label: "Solo mío", tier: 0, status: "enabled" });
  assert.deepStrictEqual(nombres(T.listedFor("kosher", g, [propia])), ["Alfa", "Beta", "Solo mío"]);
});

test("listedFor: lo que la tienda nunca ofrece no sale ni por un extra", () => {
  const malo = T.makeRow({ pkg: "com.android.vending", label: "Play", tier: 0, status: "enabled" });
  assert.deepStrictEqual(T.listedFor("lite", [], [malo]), []);
});

test("approveRequest: aprobar copia lo que el dueño ya decidió (acceso, dominios, nombre, ícono) y habilita", () => {
  const icono = "https://play-lh.googleusercontent.com/x=s128-rw";
  const g = [T.makeRow({ pkg: "com.k.dos", label: "Alfa", tier: 2, status: "request", access: "domains",
    allow: ["a.example.com"], block: ["b.example.com"], iconUrl: icono })];
  const out = T.approveRequest([], g, "com.k.dos", "lo que mandó el celular");
  assert.strictEqual(out.length, 1);
  const x = out[0];
  assert.strictEqual(x.status, "enabled");
  assert.strictEqual(x.tier, 0, "los extras no llevan nivel");
  assert.strictEqual(x.access, "domains");
  assert.deepStrictEqual(x.allow, ["a.example.com"]);
  assert.deepStrictEqual(x.block, ["b.example.com"]);
  assert.strictEqual(x.label, "Alfa");
  assert.strictEqual(x.iconUrl, icono);
  // «sin web» también se conserva
  const sw = T.approveRequest([], [T.makeRow({ pkg: "com.k.dos", label: "Alfa", tier: 1, status: "request", access: "noweb" })], "com.k.dos", "x");
  assert.strictEqual(sw[0].access, "noweb");
});

test("approveRequest: si ya había un extra de esa app se parte de él; los otros extras no se tocan; no muta lo que recibe", () => {
  const otro = T.makeRow({ pkg: "com.x.otro", label: "Otro", tier: 0, status: "enabled" });
  const viejo = T.makeRow({ pkg: "com.k.dos", label: "Alfa", tier: 0, status: "request", access: "noweb" });
  const antes = JSON.stringify([otro, viejo]);
  const out = T.approveRequest([otro, viejo], catalogo(), "com.k.dos", "x");
  assert.strictEqual(JSON.stringify([otro, viejo]), antes, "no muta");
  assert.strictEqual(out.length, 2);
  assert.strictEqual(out.find((r) => r.pkg === "com.k.dos").status, "enabled");
  assert.strictEqual(out.find((r) => r.pkg === "com.k.dos").access, "noweb");
  assert.strictEqual(out.find((r) => r.pkg === "com.x.otro").status, "enabled");
});

test("approveRequest: una app que no está en el catálogo global entra con acceso completo y nombre derivado o el del pedido", () => {
  const out = T.approveRequest([], [], "com.nuevo.app_ejemplo", "Mi App");
  assert.strictEqual(out[0].access, "full");
  assert.strictEqual(out[0].label, "Mi App");
  assert.strictEqual(T.approveRequest([], [], "com.nuevo.app_ejemplo", "")[0].label, "App Ejemplo");
});

test("approveRequest → buildPayload → leer → listedFor: la app pasa de «Pedir» a «Instalar» solo en ese equipo", () => {
  const g = catalogo();
  assert.strictEqual(T.listedFor("kosher", g, []).find((x) => x.pkg === "com.k.dos").status, "request");
  const extras = T.approveRequest([], g, "com.k.dos", "Alfa");
  const p = T.buildPayload(extras, { extra: true });
  assert.deepStrictEqual(p.problems, []);
  assert.strictEqual(p.apps.com_k_dos.status, "enabled");
  assert.strictEqual(p.apps.com_k_dos.tier, undefined, "un extra no guarda nivel");
  const leidas = T.rowsFromSaved(p.apps, { extra: true });
  assert.strictEqual(T.listedFor("kosher", g, leidas).find((x) => x.pkg === "com.k.dos").status, "enabled");
  assert.strictEqual(T.listedFor("kosher", g, []).find((x) => x.pkg === "com.k.dos").status, "request", "el catálogo global no cambió");
});

// ── Mismas reglas de validación que PlayStoreCatalogTest.kt ──────────────────────
test("cleanDomain acepta lo que un humano pega", () => {
  assert.strictEqual(T.cleanDomain("https://www.Ejemplo.com/ruta?x=1#y"), "www.ejemplo.com");
  assert.strictEqual(T.cleanDomain("*.ejemplo.com"), "ejemplo.com");
  assert.strictEqual(T.cleanDomain(".ejemplo.com."), "ejemplo.com");
  assert.strictEqual(T.cleanDomain("  EJEMPLO.COM  "), "ejemplo.com");
  assert.strictEqual(T.cleanDomain("http://user@ejemplo.com:8080/x"), "ejemplo.com");
  assert.strictEqual(T.cleanDomain("a-b.ejemplo.co.il"), "a-b.ejemplo.co.il");
});
test("cleanDomain rechaza lo que no es un dominio", () => {
  for (const bad of [null, "", "com", "localhost", "1.2.3.4", "-malo.com", "malo-.com", "ma lo.com", "a..com", "a_b.com", "x".repeat(260) + ".com"]) {
    assert.strictEqual(T.cleanDomain(bad), null, String(bad).slice(0, 30));
  }
});
test("cleanDomains: sin repetidos y con tope", () => {
  assert.deepStrictEqual(T.cleanDomains(["A.com", "https://a.com/", "b.com", "roto", ""]).domains, ["a.com", "b.com"]);
  const many = Array.from({ length: 100 }, (_, i) => "d" + (i + 1) + ".com");
  assert.strictEqual(T.cleanDomains(many).domains.length, T.MAX_DOMAINS_PER_LIST);
  assert.deepStrictEqual(T.cleanDomains(null).domains, []);
});
test("splitDomainText separa por comas, espacios y renglones", () => {
  assert.deepStrictEqual(T.splitDomainText("a.com, b.com\nc.com;d.com  e.com"), ["a.com", "b.com", "c.com", "d.com", "e.com"]);
  assert.deepStrictEqual(T.splitDomainText(""), []);
  assert.deepStrictEqual(T.splitDomainText(null), []);
});
test("cleanLabel", () => {
  assert.strictEqual(T.cleanLabel("  Waze  ", "com.waze"), "Waze");
  assert.strictEqual(T.cleanLabel("Mi\n\tApp   Buena", "com.x.y"), "Mi App Buena");
  assert.strictEqual(T.cleanLabel(null, "com.waze"), "Waze");
  assert.strictEqual(T.cleanLabel("  ", "com.x.mi_app"), "Mi App");
  assert.strictEqual(T.cleanLabel("a".repeat(200), "com.x.y").length, 60);
});
test("cleanIconUrl: solo https del CDN de Google", () => {
  const ok = "https://play-lh.googleusercontent.com/abc=w96-h96";
  assert.strictEqual(T.cleanIconUrl(ok), ok);
  assert.strictEqual(T.cleanIconUrl(" https://lh3.googleusercontent.com/x "), "https://lh3.googleusercontent.com/x");
  for (const bad of [null, "http://play-lh.googleusercontent.com/abc", "https://evil.com/abc",
    "https://evil.com/x.googleusercontent.com", "https://play-lh.googleusercontent.com.evil.com/abc",
    "https://play-lh.googleusercontent.com@evil.com/abc", "https://evil.com@play-lh.googleusercontent.com/abc",
    "https://play-lh.googleusercontent.com/a b", "javascript:alert(1)", "https://googleusercontent.com/abc"]) {
    assert.strictEqual(T.cleanIconUrl(bad), null, String(bad));
  }
});
test("isValidPackage / isNeverOffered", () => {
  assert.ok(T.isValidPackage("com.waze") && T.isValidPackage("tfilon.tfilon"));
  for (const bad of ["", "waze", "com.", "1com.x", "com.x y", null, 7]) assert.ok(!T.isValidPackage(bad), String(bad));
  assert.ok(T.isNeverOffered("com.android.vending") && T.isNeverOffered("com.ejemplo.locksuite"));
  assert.ok(!T.isNeverOffered("com.waze"));
});

if (process.exitCode) console.error("\nHAY PRUEBAS QUE FALLAN");
else console.log("OK (" + pass + " pruebas)");
