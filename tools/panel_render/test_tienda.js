// Pruebas de la pantalla «Tienda Play» (tienda.html + tienda_logic.js + tienda.js) con Firebase
// y Cloud Functions FALSOS (ver README.md). Sin red y sin tocar el Firebase de verdad.
//
// Pedido del dueño (30/10/2026): "por defecto por ahora poneme en mi panel web que aparezcan
// para nuestra nueva tienda las apps que ya están en la tienda vieja de locksuite (las q hay
// en play store). Y yo desde ahí pueda habilitar/bloquear, solo a pedido, o eliminar".
// Esta prueba recorre justo eso, como lo usa el administrador:
//
//   A. Primera vez (nada guardado): aparece la lista de la tienda vieja, sin guardar; se
//      verifica contra Google Play; se cambia el estado de cada app; se elimina una; se le
//      elige nivel y acceso; se guarda (y se mira EXACTAMENTE lo que queda en Firebase);
//      «Enviar a todos» manda SYNC_PLAY_STORE a cada celular.
//   B. Ya guardada: se carga lo guardado (NO se vuelve a ofrecer la lista inicial) y
//      «Traer de la tienda vieja» suma solo las que faltan, sin pisar las bloqueadas.
//   C. La función de Google Play caída: la lista no se pierde ni se toca nada.
//   D. Lista vacía: «Agregar una app» por paquete.
//   E. En un teléfono (390 px) la pantalla no se desborda de costado.
//   F. La ficha de UN celular (celular.html, sección «Tienda Play»): modo, lo que ve, apps
//      extra solo para ese usuario, y los pedidos de las apps «a pedido» (aprobar = paquete
//      extra habilitado de ESE equipo; nunca permitirla en toda la flota). Niveles 5 y 6.
//   G. La pestaña 🔔 Pedidos del panel de siempre (index.html + app.js): un pedido de la
//      Tienda Play se aprueba como extra del equipo y NO llama a la lista blanca; uno
//      común sigue como siempre.
const { chromium } = require('playwright');
const path = require('path');
const now = Date.now();

const AA_MAPS_SHA = 'b0d1fc188e88e5a27b366f1d7160488d1b0ebe393ad78e3c69fda96c6692cbd3';
const PNG_1x1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
const k = (pkg) => pkg.replace(/\./g, '_');
const e = (label, pkg, sha) => ({ label, packageName: pkg, apkUrl: 'https://x/' + pkg + '.apk', sha256: sha || '0'.repeat(64) });

function storeAppsA() {
  const list = [
    e('Waze', 'com.waze'), e('WhatsApp', 'com.whatsapp'), e('Traductor de Google', 'com.google.android.apps.translate'),
    e('Moovit', 'com.tranzmate'), e('Cuando SUBO CABA', 'com.ejemplo.cuandosubo'),
    e('App rara', 'com.fake.unknownapp'), e('App otra', 'com.fake.otra'),
    // No tienen que entrar: stub de Android Auto (paquete + huella), Google Play, paquete mal escrito.
    e('Mapas (stub AA)', 'com.google.android.apps.maps', AA_MAPS_SHA), e('Google Play', 'com.android.vending'),
    e('Mal escrita', 'no valido'),
  ];
  const out = {};
  for (const x of list) out[k(x.packageName)] = x;
  return out;
}
const DEVICES = {
  dev1: { deviceName: 'itzi', model: 'Cat', lastSeen: now - 30000, fcmToken: 'tok1' },
  dev2: { deviceName: 'R shaul', model: 'Q1', lastSeen: now - 60000, fcmToken: 'tok2' },
};
const SEED_A = {
  authorizedAdminsUids: { admin1: true },
  globalSettings: {
    allowedPackages: ['com.whatsapp', 'com.waze'],
    whitelist: { custom: { com_fake_otra: { allow: ['otra.example.com'] } } },
  },
  storeApps: storeAppsA(),
  devices: DEVICES,
};
// Lo que contesta «Google Play» (la función falsa): lo que no está acá se contesta «missing».
const PLAY = {
  'com.waze': { status: 'found', title: 'Waze Navigation & Live Traffic', iconUrl: 'https://play-lh.googleusercontent.com/waze=s128-rw' },
  'com.whatsapp': { status: 'found', title: 'WhatsApp Messenger', iconUrl: 'https://play-lh.googleusercontent.com/wa=s128-rw' },
  'com.google.android.apps.translate': { status: 'found', title: 'Google Translate', iconUrl: 'https://play-lh.googleusercontent.com/tr=s128-rw' },
  'com.tranzmate': { status: 'unknown' },
  'com.fake.unknownapp': { status: 'found', title: 'Unknown App' },
  'com.fake.otra': { status: 'found', title: 'Otra' },
  'com.spotify.music': { status: 'found', title: 'Spotify: Music and Podcasts', iconUrl: 'https://play-lh.googleusercontent.com/sp=s128-rw' },
};

let fallas = 0; const ok = (c, m) => { console.log((c ? 'ok   ' : 'FALLA') + ' ' + m); if (!c) fallas++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function crearPagina(b, seed, opts) {
  opts = opts || {};
  const p = await b.newPage({ viewport: opts.viewport || { width: 1300, height: 900 } });
  const errores = [];
  p.on('pageerror', (er) => errores.push(er.message));
  // El ícono de WhatsApp se aborta a propósito (ver más abajo) y el navegador lo registra como
  // «Failed to load resource»: es lo esperado, no un error de la pantalla.
  p.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource: net::ERR_FAILED/.test(m.text())) errores.push(m.text()); });
  p.__nextPrompt = null;
  p.on('dialog', async (d) => {
    if (d.type() === 'prompt') await d.accept(p.__nextPrompt || '');
    else await d.accept();
  });
  // Los íconos de Play: el de WhatsApp "falla" (para probar la letra de reemplazo); el resto carga.
  await p.route('https://play-lh.googleusercontent.com/**', (route) => {
    if (route.request().url().includes('/wa=')) { p.__iconoAbortado = true; return route.abort(); }
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1x1 });
  });
  await p.addInitScript((s) => { window.__SEED__ = s; }, seed);
  await p.addInitScript((o) => { window.__playTable = o.tabla; if (o.modo) window.__playMode = o.modo; }, { tabla: PLAY, modo: opts.playMode || null });
  p.errores = errores;
  return p;
}
async function abrir(b, seed, opts) {
  const p = await crearPagina(b, seed, opts);
  await p.goto('file://' + path.resolve('tienda.html'));
  await p.waitForFunction(() => document.getElementById('t-pend') && document.getElementById('t-pend').textContent !== 'cargando…', null, { timeout: 8000 });
  return p;
}
async function abrirFicha(b, seed, id, opts) {
  const p = await crearPagina(b, seed, opts);
  await p.goto('file://' + path.resolve('celular.html') + '?id=' + id + '#tiendaplay');
  await p.waitForFunction(() => { const el = document.getElementById('c-tp-modo'); return el && el.innerHTML.length > 0; }, null, { timeout: 8000 });
  await sleep(300);
  return p;
}

const filas = (p) => p.evaluate(() => Array.from(document.querySelectorAll('#t-apps [data-app]')).map((r) => r.dataset.app));
const fila = (p, pkg) => p.evaluate((pkg) => {
  const r = document.querySelector('.ls-approw[data-app="' + pkg + '"]');
  return r ? { cls: r.className, text: r.textContent.replace(/\s+/g, ' ').trim() } : null;
}, pkg);
const sel = (pkg, resto) => '.ls-approw[data-app="' + pkg + '"] ' + (resto || '');
const texto = (p, id) => p.evaluate((id) => (document.getElementById(id) || {}).textContent || '', id);
const visible = (p, id) => p.evaluate((id) => { const el = document.getElementById(id); return !!el && el.style.display !== 'none' && el.offsetParent !== null; }, id);
const toasts = (p) => p.evaluate(() => Array.from(document.querySelectorAll('.ls-toast')).map((t) => t.textContent));
const db = (p, ruta) => p.evaluate((r) => __fakeDb.getAt(r), ruta);
const esperarVerif = (p) => p.waitForFunction(() => /Google Play:|No se pudo consultar/.test((document.getElementById('t-verif-estado') || {}).textContent || ''), null, { timeout: 8000 });
const aceptarComandos = (p) => p.evaluate(() => {
  // "Los celulares contestan": cualquier comando que entra al buzón se da por aplicado.
  window.__fnMode = 'fcm_new';
  window.__auto = setInterval(() => {
    const devs = __fakeDb.getAt('devices') || {};
    for (const id of Object.keys(devs)) {
      const acks = devs[id].commandAcks || {};
      for (const cid of Object.keys(acks)) if (acks[cid].status === 'sent') __deviceApplies(id, cid, null);
    }
  }, 100);
});

(async () => {
  const b = await chromium.launch();

  // ══ A. Primera vez: nada guardado ═══════════════════════════════════════════════════
  let p = await abrir(b, SEED_A);
  await esperarVerif(p);

  ok(await visible(p, 't-seed'), 'A1) sin nada guardado aparece el cartel de la lista inicial de la tienda vieja');
  const seedTxt = await texto(p, 't-seed-text');
  ok(/7 apps de la tienda vieja/.test(seedTxt) && /habilitada/.test(seedTxt) && /a pedido/.test(seedTxt),
    'A1) el cartel dice cuántas son (7) y explica el estado con el que arrancan');
  ok(/lista inicial sin guardar/.test(await texto(p, 't-pend')), 'A1) el aviso de arriba dice «lista inicial sin guardar»');
  ok(await db(p, 'globalSettings/playStore') === null,
    'A2) la lista inicial es una PROPUESTA: no se escribió nada en Firebase (los celulares no ven nada hasta «Guardar»)');

  const pk = await filas(p);
  const esperadas = ['com.waze', 'com.whatsapp', 'com.google.android.apps.translate', 'com.tranzmate', 'com.ejemplo.cuandosubo', 'com.fake.unknownapp', 'com.fake.otra'];
  ok(pk.length === 7 && esperadas.every((x) => pk.includes(x)), 'A3) aparecen las 7 apps de la tienda vieja → ' + pk.join(', '));
  ok(!pk.includes('com.google.android.apps.maps') && !pk.includes('com.android.vending') && !pk.includes('no valido'),
    'A3) NO entran el stub de Android Auto, Google Play ni el paquete mal escrito');
  const om = await texto(p, 't-skipped');
  ok(/com\.google\.android\.apps\.maps/.test(om) && /Android Auto/.test(om) && /com\.android\.vending/.test(om) && /no valido/.test(om),
    'A3) y el cartel dice cuáles se dejaron afuera y por qué → ' + om.slice(0, 200));

  ok((await fila(p, 'com.waze')).cls.includes('st-allow') && (await fila(p, 'com.whatsapp')).cls.includes('st-allow'),
    'A4) las que el dueño ya había permitido en la tienda vieja arrancan HABILITADAS');
  ok((await fila(p, 'com.tranzmate')).cls.includes('st-req') && (await fila(p, 'com.ejemplo.cuandosubo')).cls.includes('st-req'),
    'A4) las demás arrancan «A pedido» (ninguna habilitada sin que el dueño ya la hubiera permitido)');
  const nivel1 = await p.evaluate(() => Array.from(document.querySelectorAll('.tp-tier h2')).map((h) => h.textContent.replace(/\s+/g, ' ').trim()));
  ok(nivel1.length === 1 && /Nivel 1/.test(nivel1[0]) && /7/.test(nivel1[0]), 'A4) todas en el nivel 1 (Kosher) → ' + JSON.stringify(nivel1));

  // Verificación contra Google Play (se hizo sola al abrir)
  const llam = await p.evaluate(() => window.__checkCalls || []);
  ok(llam.length === 1 && llam[0].packages.length === 7, 'A5) al abrir, consulta a Google Play por las 7 (una sola llamada)');
  const w = await fila(p, 'com.waze');
  ok(/en Google Play/.test(w.text), 'A5) «Waze» queda marcada «en Google Play»');
  ok(/no aparece en Play/.test((await fila(p, 'com.ejemplo.cuandosubo')).text),
    'A5) la app propia (que no está en Play) queda marcada «no aparece en Play»');
  ok(/Play: sin saber/.test((await fila(p, 'com.tranzmate')).text), 'A5) cuando Play no contesta con claridad dice «sin saber», NO «no aparece»');
  ok(await visible(p, 't-quitar-noplay') && /Quitar las 1 que no aparecen/.test(await texto(p, 't-quitar-noplay')),
    'A5) aparece «Quitar las 1 que no aparecen en Play» (no se quitó nada sola)');
  ok((await filas(p)).length === 7, 'A5) y la lista sigue con las 7');
  const nombreWaze = await p.evaluate(() => document.querySelector('.ls-approw[data-app="com.waze"] .ls-appname').textContent);
  ok(nombreWaze === 'Waze', 'A5) el nombre que ya tenía en la tienda vieja NO se pisa con el título de Play («Waze Navigation & Live Traffic») → ' + nombreWaze);
  ok(/⚠ sin dominios/.test((await fila(p, 'com.fake.unknownapp')).text) && !/⚠ sin dominios/.test((await fila(p, 'com.fake.otra')).text),
    'A6) avisa «sin dominios» en la app que no está en el catálogo ni tiene dominios propios (y no en la que sí los tiene)');
  // Íconos: el de Waze carga; el de WhatsApp falla → letra de reemplazo
  await sleep(300);
  const ico = await p.evaluate(() => ({
    waze: document.querySelector('.ls-approw[data-app="com.waze"] .tp-icon').tagName,
    wa: document.querySelector('.ls-approw[data-app="com.whatsapp"] .tp-icon').tagName + ':' + document.querySelector('.ls-approw[data-app="com.whatsapp"] .tp-icon').textContent,
  }));
  ok(ico.waze === 'IMG' && ico.wa === 'DIV:W', 'A7) ícono de Play si carga, y la letra de la app si no carga → ' + JSON.stringify(ico));

  // «Enviar a todos» antes de guardar: se niega
  await p.evaluate(() => { window.__calls = []; });
  await p.click('#t-enviar');
  await sleep(200);
  ok((await p.evaluate(() => window.__calls.length)) === 0 && (await toasts(p)).some((t) => /Guardá los cambios primero/.test(t)),
    'A8) «Enviar a todos» antes de guardar se niega y no manda nada');

  // Cambiar el estado de cada app
  await p.click(sel('com.whatsapp', 'button[data-st="blocked"]'));
  ok((await fila(p, 'com.whatsapp')).cls.includes('st-block'), 'A9) «Bloqueada» → la fila pasa a bloqueada');
  await p.click(sel('com.waze', 'button[data-st="request"]'));
  ok((await fila(p, 'com.waze')).cls.includes('st-req'), 'A9) «A pedido» → la fila pasa a a pedido');
  await p.click(sel('com.tranzmate', 'button[data-st="enabled"]'));
  ok((await fila(p, 'com.tranzmate')).cls.includes('st-allow'), 'A9) «Habilitada» → la fila pasa a habilitada');
  const resumen = await texto(p, 't-resumen');
  // Quedan: Waze (a pedido), Moovit (habilitada), Traductor, Cuando SUBO, App rara, App otra (a pedido) = 6 no
  // bloqueadas → 1 habilitada y 5 a pedido. WhatsApp (bloqueada) no cuenta.
  ok(/Cerrada[^]*0 apps/.test(resumen) && /Kosher[^]*6 apps \(1 habilitadas, 5 a pedido\)/.test(resumen),
    'A10) «Qué ve cada modo»: Cerrada 0 apps; Kosher = las 6 no bloqueadas (1 habilitada, 5 a pedido) → ' + resumen.replace(/\s+/g, ' ').slice(0, 260));

  // Eliminar
  await p.click(sel('com.ejemplo.cuandosubo', 'button[data-del]'));
  ok(!(await filas(p)).includes('com.ejemplo.cuandosubo') && (await filas(p)).length === 6, 'A11) «Eliminar» saca la app de la lista (confirmando)');
  ok(!(await visible(p, 't-quitar-noplay')), 'A11) y al no quedar apps «no aparece en Play», el botón de quitarlas desaparece');

  // Detalles: nivel y acceso
  await p.click(sel('com.google.android.apps.translate', 'button[data-abrir]'));
  await p.selectOption(sel('com.google.android.apps.translate', '[data-f="tier"]'), '2');
  const tiers = await p.evaluate(() => Array.from(document.querySelectorAll('.tp-tier h2')).map((h) => h.textContent.replace(/\s+/g, ' ').trim()));
  ok(tiers.length === 2 && /Nivel 1/.test(tiers[0]) && /Nivel 2/.test(tiers[1]), 'A12) cambiar el nivel la mueve de grupo → ' + JSON.stringify(tiers));
  await p.selectOption(sel('com.google.android.apps.translate', '[data-f="access"]'), 'domains');
  ok(/sin ningún dominio/.test((await fila(p, 'com.google.android.apps.translate')).text),
    'A13) «Dominios propios» sin dominios avisa que no tendría internet');
  await p.fill(sel('com.google.android.apps.translate', '[data-f="allow"]'), 'translate.google.com\nhttps://www.Gstatic.com/x\nno es un dominio');
  await p.dispatchEvent(sel('com.google.android.apps.translate', '[data-f="allow"]'), 'change');
  await p.fill(sel('com.google.android.apps.translate', '[data-f="block"]'), 'ads.google.com');
  await p.dispatchEvent(sel('com.google.android.apps.translate', '[data-f="block"]'), 'change');
  const dom = await p.evaluate(() => ({
    allow: document.querySelector('.ls-approw[data-app="com.google.android.apps.translate"] [data-f="allow"]').value,
    block: document.querySelector('.ls-approw[data-app="com.google.android.apps.translate"] [data-f="block"]').value,
  }));
  ok(dom.allow === 'translate.google.com\nwww.gstatic.com' && dom.block === 'ads.google.com',
    'A13) los dominios se limpian (https://, mayúsculas) y se descarta lo que no es un dominio → ' + JSON.stringify(dom));
  ok(!/sin ningún dominio/.test((await fila(p, 'com.google.android.apps.translate')).text), 'A13) con dominios cargados el aviso desaparece');

  // «App rara» → sin web (hay que abrir los detalles primero)
  await p.click(sel('com.fake.unknownapp', 'button[data-abrir]'));
  await p.selectOption(sel('com.fake.unknownapp', '[data-f="access"]'), 'noweb');
  ok(/sin web/.test((await fila(p, 'com.fake.unknownapp')).text), 'A14) «Sin web» queda marcada en la fila');
  // Nombre propio: se puede escribir el que se quiera
  await p.click(sel('com.fake.otra', 'button[data-abrir]'));
  await p.fill(sel('com.fake.otra', '[data-f="label"]'), '  Otra   app mía ');
  await p.dispatchEvent(sel('com.fake.otra', '[data-f="label"]'), 'change');
  ok(await p.evaluate(() => document.querySelector('.ls-approw[data-app="com.fake.otra"] [data-f="label"]').value === 'Otra app mía'),
    'A14) el nombre se limpia (espacios de más) al editarlo');

  // Guardar
  ok(await p.evaluate(() => document.getElementById('t-cart').classList.contains('show')), 'A15) la barra «sin guardar» está visible');
  await p.click('#t-guardar');
  await p.waitForFunction(() => document.getElementById('t-pend').textContent === 'guardado', null, { timeout: 5000 });
  const g = await db(p, 'globalSettings/playStore');
  ok(g && g.meta && g.meta.count === 6 && g.meta.savedBy === 'admin@test' && typeof g.meta.savedAt === 'number',
    'A16) quedó guardada con su sello (cantidad, quién, cuándo) → ' + JSON.stringify(g && g.meta));
  const A = (g && g.apps) || {};
  ok(A.com_waze && A.com_waze.status === 'request' && A.com_waze.tier === 1 && A.com_waze.access === 'full' && A.com_waze.packageName === 'com.waze',
    'A16) Waze: «a pedido», nivel 1, completa → ' + JSON.stringify(A.com_waze));
  ok(A.com_whatsapp && A.com_whatsapp.status === 'blocked', 'A16) WhatsApp: «bloqueada» (se guarda, no se pierde)');
  ok(A.com_tranzmate && A.com_tranzmate.status === 'enabled', 'A16) Moovit: «habilitada»');
  ok(!A.com_ejemplo_cuandosubo, 'A16) la eliminada NO está');
  const T = A.com_google_android_apps_translate;
  ok(T && T.tier === 2 && T.access === 'domains' && JSON.stringify(T.allow) === '["translate.google.com","www.gstatic.com"]' && JSON.stringify(T.block) === '["ads.google.com"]',
    'A16) Traductor: nivel 2, dominios propios (permitidos y prohibidos) → ' + JSON.stringify(T));
  ok(A.com_fake_unknownapp && A.com_fake_unknownapp.access === 'noweb' && !A.com_fake_unknownapp.allow, 'A16) «App rara»: sin web, sin dominios');
  ok(A.com_fake_otra && A.com_fake_otra.label === 'Otra app mía', 'A16) el nombre que escribió el dueño se guarda → ' + (A.com_fake_otra && A.com_fake_otra.label));
  ok(A.com_waze.iconUrl && /^https:\/\/play-lh\.googleusercontent\.com\//.test(A.com_waze.iconUrl),
    'A16) el ícono de Play quedó guardado (solo del CDN de Google)');
  ok(!(await visible(p, 't-seed')) && !(await p.evaluate(() => document.getElementById('t-cart').classList.contains('show'))),
    'A17) ya guardada: se va el cartel de la lista inicial y la barra «sin guardar»');
  ok(!/sin guardar/.test(await p.evaluate(() => document.getElementById('t-apps').textContent)), 'A17) ninguna fila dice «sin guardar»');

  // Un cambio después de guardar
  await p.click(sel('com.waze', 'button[data-st="enabled"]'));
  ok(/1 cambio sin guardar/.test(await texto(p, 't-pend')) && /sin guardar/.test((await fila(p, 'com.waze')).text),
    'A18) tocar una app después de guardar dice «1 cambio sin guardar» y marca la fila');
  await p.click('#t-descartar');
  ok((await fila(p, 'com.waze')).cls.includes('st-req') && /guardado/.test(await texto(p, 't-pend')), 'A18) «Descartar» vuelve a lo guardado');

  // Enviar a todos
  await aceptarComandos(p);
  await p.evaluate(() => { window.__calls = []; });
  await p.click('#t-enviar');
  await p.waitForFunction(() => document.querySelectorAll('#t-envio .ok').length === 2, null, { timeout: 8000 });
  const calls = await p.evaluate(() => window.__calls.map((c) => c.deviceId + ':' + c.command));
  ok(calls.length === 2 && calls.includes('dev1:SYNC_PLAY_STORE') && calls.includes('dev2:SYNC_PLAY_STORE'),
    'A19) «Enviar a todos» manda SYNC_PLAY_STORE a cada celular y espera su confirmación → ' + calls.join(', '));
  ok(!calls.some((c) => /SET_PLAY_STORE_MODE/.test(c)), 'A19) y NO cambia el modo de ningún equipo');
  ok(p.errores.length === 0, 'A20) sin errores de consola/página: ' + JSON.stringify(p.errores.slice(0, 4)));

  // ══ E. Teléfono: sin desborde lateral ═══════════════════════════════════════════════
  await p.setViewportSize({ width: 390, height: 844 });
  await p.click(sel('com.google.android.apps.translate', 'button[data-abrir]')).catch(() => {});
  await sleep(150);
  const ancho = await p.evaluate(() => document.documentElement.scrollWidth);
  ok(ancho <= 392, 'E1) a 390 px la pantalla no se desborda de costado (ancho ' + ancho + ')');
  await p.close();

  // ══ B. Ya guardada ═════════════════════════════════════════════════════════════════
  const SEED_B = {
    authorizedAdminsUids: { admin1: true },
    globalSettings: {
      allowedPackages: ['com.whatsapp'],
      playStore: {
        apps: {
          com_waze: { packageName: 'com.waze', label: 'Waze', tier: 2, access: 'noweb', status: 'enabled' },
          com_whatsapp: { packageName: 'com.whatsapp', label: 'WhatsApp', tier: 1, access: 'full', status: 'blocked' },
        },
        meta: { savedAt: now - 86400000, savedBy: 'otro@test', count: 2 },
      },
    },
    storeApps: { com_waze: e('Waze', 'com.waze'), com_whatsapp: e('WhatsApp', 'com.whatsapp'), com_tranzmate: e('Moovit', 'com.tranzmate') },
    devices: DEVICES,
  };
  p = await abrir(b, SEED_B);
  ok(!(await visible(p, 't-seed')) && /guardado/.test(await texto(p, 't-pend')), 'B1) lo guardado se carga tal cual: sin cartel de lista inicial, aviso «guardado»');
  ok((await filas(p)).length === 2, 'B1) y solo con lo guardado (2 apps), aunque la tienda vieja tenga más');
  ok(((await p.evaluate(() => window.__checkCalls || [])).length) === 0, 'B1) no consulta a Google Play por su cuenta al abrir una lista ya guardada');
  const tB = await p.evaluate(() => Array.from(document.querySelectorAll('.tp-tier h2')).map((h) => h.textContent.replace(/\s+/g, ' ').trim()));
  ok(tB.length === 2 && /Nivel 1/.test(tB[0]) && /Nivel 2/.test(tB[1]), 'B2) respeta los niveles guardados → ' + JSON.stringify(tB));
  ok(/sin web/.test((await fila(p, 'com.waze')).text) && (await fila(p, 'com.whatsapp')).cls.includes('st-block'),
    'B2) respeta el acceso «sin web» y el estado «bloqueada» guardados');
  const rB = await texto(p, 't-resumen');
  ok(/Kosher[^]*0 apps/.test(rB) && /Trabajo[^]*1 apps \(1 habilitadas, 0 a pedido\)/.test(rB) && /Lite[^]*1 apps/.test(rB),
    'B3) «Qué ve cada modo»: la bloqueada no cuenta y el nivel 2 solo lo ven Trabajo y Lite → ' + rB.replace(/\s+/g, ' ').slice(0, 240));
  await p.click('#t-vieja');
  await sleep(300);
  const fB = await filas(p);
  ok(fB.length === 3 && fB.includes('com.tranzmate'), 'B4) «Traer de la tienda vieja» suma SOLO la que faltaba (Moovit)');
  ok((await fila(p, 'com.whatsapp')).cls.includes('st-block'), 'B4) y NO le pisa el estado a la que el dueño había bloqueado');
  ok((await fila(p, 'com.tranzmate')).cls.includes('st-req') && /1 cambio sin guardar/.test(await texto(p, 't-pend')),
    'B4) la nueva entra «a pedido» (no estaba permitida) y queda como cambio sin guardar');
  await p.click('#t-vieja');
  await sleep(150);
  ok((await filas(p)).length === 3 && (await toasts(p)).some((t) => /No falta ninguna/.test(t)), 'B4) repetirlo no duplica nada y lo dice');
  ok(p.errores.length === 0, 'B5) sin errores de consola/página: ' + JSON.stringify(p.errores.slice(0, 4)));
  await p.close();

  // ══ C. La función de Google Play caída ═════════════════════════════════════════════
  p = await abrir(b, SEED_A, { playMode: 'down' });
  await esperarVerif(p);
  const eC = await texto(p, 't-verif-estado');
  ok(/No se pudo consultar Google Play/.test(eC), 'C1) si la función no está, lo dice → ' + eC.slice(0, 160));
  ok((await filas(p)).length === 7, 'C1) la lista de 7 queda intacta (un error NUNCA se toma como «la app no existe»)');
  ok(!(await p.evaluate(() => /no aparece en Play|en Google Play/.test(document.getElementById('t-apps').textContent))) && !(await visible(p, 't-quitar-noplay')),
    'C1) ninguna app queda marcada, y no se ofrece quitar nada');
  ok(await p.evaluate(() => !document.getElementById('t-verificar').disabled), 'C1) el botón «Verificar en Play» queda disponible para reintentar');
  await p.close();

  // ══ D. Lista vacía: agregar por paquete ═══════════════════════════════════════════
  const SEED_D = { authorizedAdminsUids: { admin1: true }, globalSettings: {}, devices: DEVICES };
  p = await abrir(b, SEED_D);
  ok(/No hay ninguna app/.test(await p.evaluate(() => document.getElementById('t-apps').textContent)), 'D1) sin tienda vieja ni nada guardado: lista vacía con su explicación');
  p.__nextPrompt = 'esto no es un paquete';
  await p.click('#t-agregar');
  await sleep(150);
  ok((await filas(p)).length === 0 && (await toasts(p)).some((t) => /no parece un nombre de paquete/.test(t)), 'D2) un nombre inválido se rechaza con aviso');
  p.__nextPrompt = 'com.android.vending';
  await p.click('#t-agregar');
  await sleep(150);
  ok((await filas(p)).length === 0 && (await toasts(p)).some((t) => /no se puede ofrecer/.test(t)), 'D2) Google Play mismo no se puede ofrecer');
  p.__nextPrompt = 'com.spotify.music';
  await p.click('#t-agregar');
  await p.waitForFunction(() => /Spotify: Music and Podcasts/.test(document.getElementById('t-apps').innerHTML), null, { timeout: 5000 });
  const nueva = await fila(p, 'com.spotify.music');
  ok(nueva && nueva.cls.includes('st-allow') && /en Google Play/.test(nueva.text), 'D3) la app agregada entra «Habilitada», y se trae su nombre real e ícono de Play');
  ok(await p.evaluate(() => !!document.querySelector('.ls-approw[data-app="com.spotify.music"] [data-f="label"]')), 'D3) y queda con los detalles abiertos para completarla');
  p.__nextPrompt = 'com.spotify.music';
  await p.click('#t-agregar');
  await sleep(150);
  ok((await filas(p)).length === 1 && (await toasts(p)).some((t) => /ya está en la lista/.test(t)), 'D4) agregar una repetida no duplica');
  await p.click('#t-guardar');
  await p.waitForFunction(() => document.getElementById('t-pend').textContent === 'guardado', null, { timeout: 5000 });
  const sD = await db(p, 'globalSettings/playStore/apps/com_spotify_music');
  ok(sD && sD.label === 'Spotify: Music and Podcasts' && sD.tier === 1 && sD.status === 'enabled' && sD.access === 'full',
    'D5) se guarda con el nombre de Play, nivel 1, habilitada, completa → ' + JSON.stringify(sD));
  ok(p.errores.length === 0, 'D6) sin errores de consola/página: ' + JSON.stringify(p.errores.slice(0, 4)));
  await p.close();

  // ══ F. Ficha del celular → sección «Tienda Play» ═══════════════════════════════════
  const global = (pkg, label, tier, status, extra) => Object.assign({ packageName: pkg, label, tier, access: 'full', status }, extra || {});
  const SEED_F = {
    authorizedAdminsUids: { admin1: true },
    globalSettings: {
      playStore: {
        apps: {
          com_waze: global('com.waze', 'Waze', 1, 'enabled'),
          com_tranzmate: global('com.tranzmate', 'Moovit', 1, 'request', { access: 'noweb', iconUrl: 'https://play-lh.googleusercontent.com/mv=s128-rw' }),
          com_google_android_apps_translate: global('com.google.android.apps.translate', 'Traductor', 2, 'enabled'),
          fm_jewishmusic_application: global('fm.jewishmusic.application', 'Zing', 3, 'enabled'),
          com_bloq_app: global('com.bloq.app', 'Bloqueada', 1, 'blocked'),
          com_prohibida_app: global('com.prohibida.app', 'Prohibida', 1, 'enabled'),
        },
        meta: { savedAt: now, savedBy: 'x', count: 6 },
      },
    },
    devices: {
      dev1: {
        deviceName: 'itzi', model: 'Cat', lastSeen: now - 30000, fcmToken: 't1', hasPinConfigured: true, trustedAdmins: { admin1: true },
        info: { playStore: { mode: 'kosher', offered: 2, listed: 4, installedByStore: ['com.waze'], paused: [], updatedAt: now - 60000 } },
        appPolicy: { com_prohibida_app: 'block' },
        playStoreExtras: { com_extra_uno: { packageName: 'com.extra.uno', label: 'Extra uno', access: 'full', status: 'enabled' } },
        appRequests: {
          com_tranzmate: { packageName: 'com.tranzmate', label: 'Moovit', status: 'pending', store: 'play', requestedAt: now - 3600000, note: 'para ir a la yeshivá' },
          com_old_app: { packageName: 'com.old.app', label: 'App vieja', status: 'pending', requestedAt: now - 7200000 },
          com_otra_play: { packageName: 'com.otra.play', label: 'Otra Play', status: 'pending', store: 'play', requestedAt: now - 1000 },
        },
      },
      dev2: { deviceName: 'sin info', model: 'X', lastSeen: now - 30000, fcmToken: 't2', hasPinConfigured: true, trustedAdmins: { admin1: true } },
      dev3: { deviceName: 'cerrada', model: 'Y', lastSeen: now - 30000, fcmToken: 't3', hasPinConfigured: true, trustedAdmins: { admin1: true },
        info: { playStore: { mode: 'cerrada', offered: 0, listed: 0 } },
        playStoreExtras: { com_extra_uno: { packageName: 'com.extra.uno', label: 'Extra uno', access: 'full', status: 'enabled' } } },
    },
  };
  p = await abrirFicha(b, SEED_F, 'dev1');
  await aceptarComandos(p);
  const f1 = await p.evaluate(() => ({
    rail: !!document.querySelector('#c-rail button[data-sec="tiendaplay"]'),
    cnt: document.getElementById('cnt-tp').textContent,
    modoOn: Array.from(document.querySelectorAll('#c-tp-modo [data-tp-modo].on-allow')).map((x) => x.dataset.tpModo),
    cerradaOn: !!document.querySelector('#c-tp-modo [data-tp-modo="cerrada"].on-block'),
    pol: Array.from(document.querySelectorAll('#c-pol-tienda [data-tp-modo]')).map((x) => x.dataset.tpModo),
    seccionVisible: document.querySelector('.ls-section[data-sec="tiendaplay"]').offsetHeight > 0,
  }));
  ok(f1.rail && f1.seccionVisible, 'F1) la ficha tiene la sección «Tienda Play» y se abre sola con #tiendaplay');
  ok(JSON.stringify(f1.modoOn) === '["kosher"]' && !f1.cerradaOn, 'F2) marca el modo que INFORMA el celular (Kosher) → ' + JSON.stringify(f1.modoOn));
  ok(JSON.stringify(f1.pol) === '["cerrada","kosher","trabajo","lite"]', 'F2) y el mismo selector de 4 modos está en la sección Políticas → ' + JSON.stringify(f1.pol));
  const vista = await p.evaluate(() => Array.from(document.querySelectorAll('#c-tp-vista tbody tr')).map((tr) => Array.from(tr.children).map((td) => td.textContent.replace(/\s+/g, ' ').trim())));
  const por = (n) => vista.find((r) => r[0].startsWith(n));
  ok(vista.length === 4 && ['Extra uno', 'Moovit', 'Prohibida', 'Waze'].every((n) => por(n)),
    'F3) con modo Kosher ve: nivel 1 + extras, sin bloqueadas ni niveles 2 y 3 → ' + vista.map((r) => r[0].split(' ')[0]).join(', '));
  ok(/Instalar/.test(por('Waze')[1]) && /Pedir/.test(por('Moovit')[1]), 'F3) las habilitadas con «Instalar» y las «a pedido» con «Pedir»');
  ok(/extra de este celular/.test(por('Extra uno')[2]) && /nivel 1/.test(por('Waze')[2]), 'F3) cada una dice de dónde sale (nivel o extra del celular)');
  ok(/sin web/.test(por('Moovit')[3]), 'F3) y su acceso («sin web»)');
  ok(/instalada por la tienda/.test(por('Waze')[4]), 'F3) y si el celular ya la instaló desde la tienda');
  ok(/prohibida en el filtro/.test(por('Prohibida')[1]), 'F3) una app que la lista blanca PROHÍBE no se ofrece, aunque esté en el catálogo');
  ok(f1.cnt === '3', 'F3) el contador del riel cuenta lo que se puede instalar o pedir (3) → ' + f1.cnt);

  // Cambiar el modo
  await p.evaluate(() => { window.__calls = []; });
  await p.click('#c-tp-modo [data-tp-modo="trabajo"]');
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'SET_PLAY_STORE_MODE'), null, { timeout: 5000 });
  const cm = await p.evaluate(() => window.__calls.filter((c) => c.command === 'SET_PLAY_STORE_MODE')[0]);
  ok(cm.storeMode === 'trabajo' && cm.deviceId === 'dev1', 'F4) elegir «Trabajo» manda SET_PLAY_STORE_MODE con storeMode=trabajo a ese celular → ' + JSON.stringify(cm));

  // Extras: agregar, editar, guardar
  await p.evaluate(() => { window.__calls = []; });
  await p.fill('#c-tp-nuevo', 'esto no es un paquete');
  await p.click('#c-tp-agregar');
  ok((await toasts(p)).some((t) => /no parece un nombre de paquete/.test(t)), 'F5) un paquete inválido se rechaza');
  await p.fill('#c-tp-nuevo', 'com.android.vending');
  await p.click('#c-tp-agregar');
  ok((await toasts(p)).some((t) => /no se puede ofrecer/.test(t)), 'F5) Google Play mismo no se puede agregar');
  await p.fill('#c-tp-nuevo', 'com.spotify.music');
  await p.click('#c-tp-agregar');
  ok(await p.evaluate(() => !!document.querySelector('[data-tp-pkg="com.spotify.music"]')) && await p.evaluate(() => !document.getElementById('c-tp-guardar').disabled),
    'F5) un extra nuevo aparece «sin guardar» y se habilita Guardar');
  await p.fill('#c-tp-nuevo', 'com.google.android.apps.translate');
  await p.click('#c-tp-agregar');
  const copiado = await p.evaluate(() => {
    const r = document.querySelector('[data-tp-pkg="com.google.android.apps.translate"]');
    return r ? { nombre: r.querySelector('.ls-appname').textContent, campo: r.querySelector('[data-tp-f="label"]').value, estado: r.querySelector('[data-tp-f="status"]').value } : null;
  });
  ok(copiado && copiado.nombre === 'Traductor' && copiado.campo === 'Traductor' && copiado.estado === 'enabled', 'F5) si la app ya está en el catálogo global, el extra arranca copiándola → ' + JSON.stringify(copiado));
  // Una app que NO está en el catálogo global trae su nombre real e ícono de Google Play
  await p.waitForFunction(() => { const r = document.querySelector('[data-tp-pkg="com.spotify.music"]'); return r && /Spotify: Music and Podcasts/.test(r.querySelector('.ls-appname').textContent); }, null, { timeout: 5000 });
  ok(true, 'F5) una app que no está en el catálogo trae de Google Play su nombre real («Spotify: Music and Podcasts»), en vez de «Music»');
  // y el dueño puede ponerle el nombre que quiera
  await p.fill('[data-tp-pkg="com.spotify.music"] [data-tp-f="label"]', '  Spotify   para la yeshivá ');
  await p.dispatchEvent('[data-tp-pkg="com.spotify.music"] [data-tp-f="label"]', 'change');
  ok(await p.evaluate(() => document.querySelector('[data-tp-pkg="com.spotify.music"] [data-tp-f="label"]').value === 'Spotify para la yeshivá'),
    'F5) el nombre del extra se puede editar (y se limpia)');
  // pedidos con cambios sin guardar → se niega
  await p.click('[data-tp-aprobar="com_tranzmate"]');
  await sleep(150);
  ok((await db(p, 'devices/dev1/playStoreExtras/com_tranzmate')) === null && (await toasts(p)).some((t) => /cambios sin guardar/.test(t)),
    'F6) con cambios sin guardar en los extras, aprobar un pedido se niega (no mezcla ediciones)');
  await p.selectOption('[data-tp-pkg="com.spotify.music"] [data-tp-f="status"]', 'request');
  await p.selectOption('[data-tp-pkg="com.spotify.music"] [data-tp-f="access"]', 'domains');
  await p.fill('[data-tp-pkg="com.spotify.music"] [data-tp-f="allow"]', 'spotify.com\nhttps://scdn.co/x');
  await p.dispatchEvent('[data-tp-pkg="com.spotify.music"] [data-tp-f="allow"]', 'change');
  await p.fill('[data-tp-pkg="com.spotify.music"] [data-tp-f="block"]', 'ads.spotify.com');
  await p.dispatchEvent('[data-tp-pkg="com.spotify.music"] [data-tp-f="block"]', 'change');
  await p.click('#c-tp-guardar');
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'SYNC_PLAY_STORE'), null, { timeout: 5000 });
  const ex = await db(p, 'devices/dev1/playStoreExtras');
  ok(ex && ex.com_extra_uno && ex.com_extra_uno.status === 'enabled', 'F7) guardar NO pierde los extras que ya estaban');
  ok(ex.com_spotify_music && ex.com_spotify_music.status === 'request' && ex.com_spotify_music.access === 'domains' &&
    JSON.stringify(ex.com_spotify_music.allow) === '["spotify.com","scdn.co"]' && JSON.stringify(ex.com_spotify_music.block) === '["ads.spotify.com"]' &&
    ex.com_spotify_music.tier === undefined && ex.com_spotify_music.label === 'Spotify para la yeshivá' &&
    /^https:\/\/play-lh\.googleusercontent\.com\//.test(ex.com_spotify_music.iconUrl || ''), 'F7) el extra nuevo se guarda con su nombre, ícono de Play, estado, acceso y dominios limpios, SIN nivel → ' + JSON.stringify(ex.com_spotify_music));
  ok(ex.com_google_android_apps_translate && ex.com_google_android_apps_translate.status === 'enabled', 'F7) y el que copió del catálogo');
  ok(await p.evaluate(() => document.getElementById('c-tp-guardar').disabled), 'F7) tras guardar, Guardar vuelve a estar deshabilitado');
  await sleep(300);
  const vista2 = await p.evaluate(() => Array.from(document.querySelectorAll('#c-tp-vista tbody tr')).map((tr) => tr.children[0].textContent.split('com.')[0].trim()));
  ok(vista2.includes('Traductor') && vista2.includes('Spotify para la yeshivá'), 'F7) y la vista de «lo que ve el celular» ya los incluye, con el nombre elegido → ' + vista2.join(', '));

  // Pedidos
  const listas = await p.evaluate(() => ({
    viejos: document.getElementById('c-pedidos').textContent,
    play: document.getElementById('c-tp-pedidos').textContent,
  }));
  ok(/App vieja/.test(listas.viejos) && !/Moovit/.test(listas.viejos) && !/Otra Play/.test(listas.viejos),
    'F8) la lista de pedidos de siempre muestra SOLO los comunes (los de la Tienda Play no se aprueban ahí)');
  ok(/Moovit/.test(listas.play) && /Otra Play/.test(listas.play) && !/App vieja/.test(listas.play) && /para ir a la yeshivá/.test(listas.play),
    'F8) y los de la Tienda Play están en su propia lista, con la nota del usuario');
  await p.evaluate(() => { window.__calls = []; });
  await p.click('[data-tp-aprobar="com_tranzmate"]');
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'SYNC_APP_REQUESTS'), null, { timeout: 8000 });
  const ap = await p.evaluate(() => ({
    extra: __fakeDb.getAt('devices/dev1/playStoreExtras/com_tranzmate'),
    pedido: __fakeDb.getAt('devices/dev1/appRequests/com_tranzmate'),
    permitidas: __fakeDb.getAt('globalSettings/allowedPackages'),
    decisiones: __fakeDb.getAt('globalSettings/whitelist/decisions'),
    politica: __fakeDb.getAt('devices/dev1/appPolicy'),
    cmds: window.__calls.map((c) => c.command),
  }));
  ok(ap.extra && ap.extra.status === 'enabled' && ap.extra.access === 'noweb' && ap.extra.label === 'Moovit' && ap.extra.tier === undefined,
    'F9) aprobar guarda la app como extra HABILITADO de ese celular, conservando su acceso «sin web» del catálogo → ' + JSON.stringify(ap.extra));
  ok(ap.pedido.status === 'approved' && ap.pedido.notified === false && typeof ap.pedido.resolvedAt === 'number', 'F9) y marca el pedido como aprobado, para avisarle al usuario');
  ok(ap.permitidas === null && ap.decisiones === null && JSON.stringify(ap.politica) === '{"com_prohibida_app":"block"}',
    'F9) y NO la permite en la flota (ni allowedPackages, ni decisiones globales, ni el filtro del celular)');
  ok(ap.cmds.includes('SYNC_PLAY_STORE') && ap.cmds.includes('SYNC_APP_REQUESTS'), 'F9) y manda SYNC_PLAY_STORE y SYNC_APP_REQUESTS → ' + ap.cmds.join(', '));
  await p.evaluate(() => { window.__calls = []; });
  await p.click('[data-tp-rechazar="com_otra_play"]');
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'SYNC_APP_REQUESTS'), null, { timeout: 8000 });
  const rj = await p.evaluate(() => ({ pedido: __fakeDb.getAt('devices/dev1/appRequests/com_otra_play'), extra: __fakeDb.getAt('devices/dev1/playStoreExtras/com_otra_play') }));
  ok(rj.pedido.status === 'rejected' && rj.pedido.notified === false && rj.extra === null, 'F10) rechazar marca el pedido, avisa y no guarda ningún extra');
  ok(await p.evaluate(() => !document.querySelector('[data-tp-aprobar]')), 'F10) y ya no quedan pedidos pendientes para resolver');

  // Niveles automáticos nuevos en la ficha
  await p.evaluate(() => { window.__calls = []; });
  await p.click('#c-rail button[data-sec="rapida"]');
  const niveles = await p.evaluate(() => Array.from(document.querySelectorAll('#c-perfiles [data-nivel]')).map((x) => x.dataset.nivel));
  ok(niveles.includes('solo_llamadas') && niveles.includes('lite') && niveles.length === 6, 'F11) la configuración rápida ofrece los seis niveles (incluye «Solo llamadas» y «Lite») → ' + niveles.join(','));
  await p.click('#c-perfiles [data-nivel="lite"]');
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'APPLY_MASTER_PROFILE'), null, { timeout: 5000 });
  const nv = await p.evaluate(() => window.__calls.filter((c) => c.command === 'APPLY_MASTER_PROFILE')[0]);
  ok(nv.level === 'lite', 'F11) aplicar «Nivel 6 — Lite» manda APPLY_MASTER_PROFILE con level=lite → ' + JSON.stringify(nv));
  // 390 px
  await p.setViewportSize({ width: 390, height: 844 });
  await p.click('#c-rail button[data-sec="tiendaplay"]');
  await sleep(150);
  const ancho2 = await p.evaluate(() => document.documentElement.scrollWidth);
  ok(ancho2 <= 392, 'F12) a 390 px la sección no se desborda de costado (ancho ' + ancho2 + ')');
  ok(p.errores.length === 0, 'F13) sin errores de consola/página: ' + JSON.stringify(p.errores.slice(0, 4)));
  await p.close();

  p = await abrirFicha(b, SEED_F, 'dev2');
  ok(/todavía no informó el modo/.test(await texto(p, 'c-tp-vista')) && /todavía no informó el modo/.test(await texto(p, 'c-tp-modo')),
    'F14) un celular que todavía no informó su modo lo dice (no inventa una vista)');
  ok(p.errores.length === 0, 'F14) sin errores de consola/página: ' + JSON.stringify(p.errores.slice(0, 4)));
  await p.close();
  p = await abrirFicha(b, SEED_F, 'dev3');
  ok(/Tienda cerrada/.test(await texto(p, 'c-tp-vista')) && await p.evaluate(() => !!document.querySelector('#c-tp-modo [data-tp-modo="cerrada"].on-block')),
    'F15) con la tienda cerrada no hay tienda, aunque el celular tenga extras');
  await p.close();

  // ══ G. Pestaña 🔔 Pedidos del panel de siempre (index.html + app.js) ═════════════════
  const SEED_G = {
    authorizedAdminsUids: { admin1: true },
    globalSettings: SEED_F.globalSettings,
    devices: {
      dev1: {
        deviceName: 'itzi', model: 'Cat', lastSeen: now - 30000, fcmToken: 't1', hasPinConfigured: true, trustedAdmins: { admin1: true },
        appRequests: {
          com_tranzmate: { packageName: 'com.tranzmate', label: 'Moovit', status: 'pending', store: 'play', requestedAt: now - 3600000 },
          com_old_app: { packageName: 'com.old.app', label: 'App vieja', status: 'pending', requestedAt: now - 7200000 },
        },
      },
    },
  };
  p = await crearPagina(b, SEED_G);
  await p.goto('file://' + path.resolve('index.html'));
  await p.waitForSelector('.device-card', { timeout: 10000 });
  await aceptarComandos(p);
  await p.evaluate(() => {
    window.__wl = [];
    // La lista blanca se reemplaza por un espía: lo que importa es SI se la llama y con qué.
    window.setWhitelistDecision = async (...a) => { window.__wl.push(a); };
  });
  await p.click('#main-tab-requests');
  await sleep(300);
  const tarjetas = await p.evaluate(() => Array.from(document.querySelectorAll('#app-requests-list .group-card')).map((c) => c.textContent.replace(/\s+/g, ' ').trim()));
  ok(tarjetas.length === 2 && tarjetas.some((t) => /Moovit/.test(t) && /Tienda Play/.test(t)) && tarjetas.some((t) => /App vieja/.test(t) && !/Tienda Play/.test(t)),
    'G1) 🔔 Pedidos muestra los dos, y el de la Tienda Play lleva su etiqueta → ' + tarjetas.map((t) => t.slice(0, 40)).join(' | '));
  await p.evaluate(() => { window.__calls = []; });
  await p.evaluate(() => {
    const c = Array.from(document.querySelectorAll('#app-requests-list .group-card')).find((x) => /Moovit/.test(x.textContent));
    Array.from(c.querySelectorAll('button')).find((x) => /Aprobar/.test(x.textContent)).click();
  });
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'SYNC_APP_REQUESTS'), null, { timeout: 15000 });
  const g1 = await p.evaluate(() => ({
    extra: __fakeDb.getAt('devices/dev1/playStoreExtras/com_tranzmate'),
    pedido: __fakeDb.getAt('devices/dev1/appRequests/com_tranzmate/status'),
    wl: window.__wl, cmds: window.__calls.map((c) => c.command),
  }));
  ok(g1.extra && g1.extra.status === 'enabled' && g1.extra.access === 'noweb' && g1.pedido === 'approved',
    'G2) aprobar un pedido de la Tienda Play lo guarda como extra HABILITADO del equipo (con su acceso del catálogo) → ' + JSON.stringify(g1.extra));
  ok(g1.wl.length === 0, 'G2) y NO llama a la lista blanca (no la permite en toda la flota) → ' + JSON.stringify(g1.wl));
  ok(g1.cmds.includes('SYNC_PLAY_STORE') && g1.cmds.includes('SYNC_APP_REQUESTS') && !g1.cmds.includes('SYNC_WHITELIST') && !g1.cmds.includes('UNSUSPEND_APP'),
    'G2) manda SYNC_PLAY_STORE + SYNC_APP_REQUESTS (y nada de SYNC_WHITELIST / UNSUSPEND_APP) → ' + g1.cmds.join(', '));
  await p.evaluate(() => { window.__calls = []; });
  await p.evaluate(() => {
    const c = Array.from(document.querySelectorAll('#app-requests-list .group-card')).find((x) => /App vieja/.test(x.textContent));
    Array.from(c.querySelectorAll('button')).find((x) => /Aprobar/.test(x.textContent)).click();
  });
  await p.waitForFunction(() => window.__calls.some((c) => c.command === 'SYNC_APP_REQUESTS'), null, { timeout: 15000 });
  const g2 = await p.evaluate(() => ({ wl: window.__wl, cmds: window.__calls.map((c) => c.command), extra: __fakeDb.getAt('devices/dev1/playStoreExtras/com_old_app') }));
  ok(g2.wl.length === 1 && g2.wl[0][0] === 'com.old.app' && g2.wl[0][1] === 'allow' && g2.cmds.includes('SYNC_WHITELIST') && g2.extra === null,
    'G3) CONTROL: un pedido común sigue como siempre (lista blanca + SYNC_WHITELIST) y no toca los extras de la Tienda Play → ' + JSON.stringify(g2.wl));
  ok(p.errores.length === 0, 'G4) sin errores de consola/página: ' + JSON.stringify(p.errores.slice(0, 4)));
  await p.close();

  await b.close();
  console.log(fallas ? fallas + ' FALLAS' : 'TODO VERDE');
  process.exit(fallas ? 1 : 0);
})();
