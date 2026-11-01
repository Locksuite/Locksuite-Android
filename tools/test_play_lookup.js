#!/usr/bin/env node
"use strict";
/**
 * Prueba de admin-backend/functions/play_lookup.js (B.101) — sin red y sin dependencias.
 *
 *     node tools/test_play_lookup.js
 *
 * Qué afirma (y qué NO puede afirmar): que el análisis de páginas de ejemplo y la
 * clasificación found / missing / unknown se comportan como está documentado, y que nada
 * raro (un ícono de otro sitio, un paquete con caracteres de ruta) llega a la respuesta.
 * NO prueba contra Google Play real: el marcado real de la página cambia y se tiene que
 * verificar a mano con un paquete conocido (ver INSTRUCCIONES_ANTIGRAVITY_..._TIENDA_PLAY).
 */
const assert = require("assert");
const path = require("path");
const lookup = require(path.join(__dirname, "..", "admin-backend", "functions", "play_lookup.js"));

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

const PAGE_OK = `<!doctype html><html><head>
<title>Waze Navigation &amp; Live Traffic - Apps on Google Play</title>
<meta property="og:title" content="Waze Navigation &amp; Live Traffic - Apps on Google Play">
<meta content="https://play-lh.googleusercontent.com/AbCdEf123=w480-h960-rw" property="og:image">
</head><body></body></html>`;

// ── paquetes ────────────────────────────────────────────────────────────────
test("paquetes válidos", () => {
  assert.ok(lookup.isValidPackage("com.waze"));
  assert.ok(lookup.isValidPackage("com.google.android.apps.translate"));
  assert.ok(lookup.isValidPackage("life.channel.accurate.local.weather.forecast"));
});
test("paquetes inválidos o con caracteres de ruta", () => {
  for (const bad of ["", "waze", "com.", ".com.waze", "com.waze/../x", "com.waze?x=1", "com.waze#a",
    "com.waze&id=2", "com.wa ze", "1com.waze", "com.1waze", null, undefined, 42, "a".repeat(300) + ".b"]) {
    assert.ok(!lookup.isValidPackage(bad), String(bad));
  }
});

// ── análisis de página ───────────────────────────────────────────────────────
test("título: se decodifican entidades y se quita el sufijo de Google Play", () => {
  const r = lookup.parsePlayPage(PAGE_OK);
  assert.strictEqual(r.title, "Waze Navigation & Live Traffic");
});
test("título: sufijo en español", () => {
  assert.strictEqual(lookup.cleanTitle("WhatsApp Messenger - Aplicaciones en Google Play"), "WhatsApp Messenger");
});
test("título: sin og:title cae al <title>", () => {
  const r = lookup.parsePlayPage("<html><head><title>Moovit - Apps on Google Play</title></head></html>");
  assert.strictEqual(r.title, "Moovit");
});
test("título: una página de consentimiento NO es el nombre de una app", () => {
  const r = lookup.parsePlayPage("<html><head><title>Before you continue to Google</title></head></html>");
  assert.strictEqual(r.title, null);
  assert.strictEqual(lookup.cleanTitle("404"), null);
});
test("meta: el orden de atributos no importa, comillas simples o dobles", () => {
  assert.strictEqual(lookup.metaContent("<meta content='x y' property='og:title'>", "og:title"), "x y");
  assert.strictEqual(lookup.metaContent('<meta property="og:title" content="a">', "og:title"), "a");
  assert.strictEqual(lookup.metaContent("<html></html>", "og:title"), null);
});
test("ícono: del og:image, solo del CDN de Google, y a tamaño chico", () => {
  const r = lookup.parsePlayPage(PAGE_OK);
  assert.strictEqual(r.iconUrl, "https://play-lh.googleusercontent.com/AbCdEf123=s128-rw");
});
test("ícono: la etiqueta 'Icon image' tiene prioridad", () => {
  const html = PAGE_OK + '<img src="https://play-lh.googleusercontent.com/ICON=s64-rw" alt="Icon image">';
  assert.strictEqual(lookup.parsePlayPage(html).iconUrl, "https://play-lh.googleusercontent.com/ICON=s128-rw");
});
test("ícono: se rechaza lo que no es https del CDN de Google", () => {
  for (const bad of [
    "http://play-lh.googleusercontent.com/a", "https://evil.example.com/a.png",
    "https://googleusercontent.com.evil.com/a", "https://user@play-lh.googleusercontent.com/a",
    "https://play-lh.googleusercontent.com/a b", 'https://play-lh.googleusercontent.com/a"onerror=', "",
    "javascript:alert(1)", "data:image/png;base64,AAAA",
  ]) {
    assert.strictEqual(lookup.cleanIconUrl(bad), null, bad);
  }
  assert.ok(lookup.cleanIconUrl("https://play-lh.googleusercontent.com/a=s64"));
  assert.ok(lookup.cleanIconUrl("https://lh3.ggpht.com/a"));
});
test("ícono: una URL sin opciones de tamaño no se toca", () => {
  assert.strictEqual(lookup.iconAtSize("https://play-lh.googleusercontent.com/abc"), "https://play-lh.googleusercontent.com/abc");
});

// ── clasificación ────────────────────────────────────────────────────────────
function fakeFetch(map) {
  return async (url) => {
    const m = /id=([^&]+)/.exec(url);
    const pkg = decodeURIComponent(m[1]);
    const r = map[pkg];
    if (r instanceof Error) throw r;
    if (!r) return { status: 404, text: async () => "not found" };
    return { status: r.status, text: async () => r.body || "" };
  };
}
test("404 → missing; 200 con título → found; otros → unknown (nunca 'missing')", async () => {
  const f = fakeFetch({
    "com.waze": { status: 200, body: PAGE_OK },
    "com.limit": { status: 429 },
    "com.boom": { status: 503 },
    "com.empty": { status: 200, body: "<html><title>Before you continue</title></html>" },
    "com.net": new Error("ECONNRESET"),
  });
  const out = await lookup.lookupMany(["com.waze", "com.gone", "com.limit", "com.boom", "com.empty", "com.net"], f);
  const by = Object.fromEntries(out.map((r) => [r.packageName, r]));
  assert.strictEqual(by["com.waze"].status, "found");
  assert.strictEqual(by["com.waze"].title, "Waze Navigation & Live Traffic");
  assert.strictEqual(by["com.gone"].status, "missing");
  assert.strictEqual(by["com.limit"].status, "unknown");
  assert.strictEqual(by["com.boom"].status, "unknown");
  assert.strictEqual(by["com.empty"].status, "unknown");
  assert.strictEqual(by["com.net"].status, "unknown");
});
test("un timeout es 'unknown', no 'missing'", async () => {
  const slow = (url, opts) => new Promise((resolve, reject) => {
    opts.signal.addEventListener("abort", () => reject(new Error("aborted")));
  });
  const r = await lookup.lookupOne("com.slow.app", slow, 20);
  assert.strictEqual(r.status, "unknown");
});
test("lookupMany: sin repetidos, descarta inválidos, respeta el tope y el orden", async () => {
  const calls = [];
  const f = async (url) => { calls.push(url); return { status: 404, text: async () => "" }; };
  const input = ["com.a.one", "com.a.one", "no valido", "com.b.two", 7, "com.c.three", "com.d.four"];
  const out = await lookup.lookupMany(input, f, { max: 3 });
  assert.deepStrictEqual(out.map((r) => r.packageName), ["com.a.one", "com.b.two", "com.c.three"]);
  assert.strictEqual(calls.length, 3);
  assert.ok(calls.every((u) => u.startsWith("https://play.google.com/store/apps/details?id=")));
});
test("lookupMany: nunca hay más de N consultas a la vez", async () => {
  let active = 0, maxActive = 0;
  const f = async () => {
    active++; maxActive = Math.max(maxActive, active);
    await new Promise((r) => setTimeout(r, 5));
    active--;
    return { status: 404, text: async () => "" };
  };
  const pkgs = Array.from({ length: 12 }, (_, i) => `com.x.app${i}`);
  await lookup.lookupMany(pkgs, f, { concurrency: 3 });
  assert.ok(maxActive <= 3, "máximo simultáneo: " + maxActive);
  assert.ok(maxActive >= 2, "debería usar concurrencia");
});
test("lookupMany: entrada que no es lista → vacío", async () => {
  assert.deepStrictEqual(await lookup.lookupMany(null, fakeFetch({})), []);
  assert.deepStrictEqual(await lookup.lookupMany("com.waze", fakeFetch({})), []);
});

(async () => {
  for (const t of pending) await t();
  if (process.exitCode) {
    console.error("\nHAY PRUEBAS QUE FALLAN");
  } else {
    console.log("OK (" + pass + " pruebas)");
  }
})();
