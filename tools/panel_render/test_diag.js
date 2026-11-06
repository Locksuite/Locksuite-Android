// Pruebas del «Registro de errores» de la ficha (celular.html + diag_logic.js + diag_remoto.js)
// con Firebase y Cloud Function FALSOS (ver README.md). Sin red y sin tocar el Firebase de verdad.
//
// 6/11/2026 (B.110). El dueño pidió ver desde la web los errores de cada celular «como si estuviera
// conectado por depuración USB» para mandarle el error exacto a la IA. Acá se verifica lo que ve
// y hace el dueño:
//   A. los oyentes de `deviceDiag` se abren recién al entrar a la sección (no al abrir la ficha);
//   B. el estado, la lista (nivel / período / etiqueta / texto), el orden y los renglones largos;
//   C. que NADA que venga del celular se interprete como HTML (texto malicioso);
//   D. que un latido no cierre los renglones que el dueño abrió;
//   E. el visor de diagnósticos completos;
//   F. «Pedir diagnóstico ahora» (paquetes, sin PIN) y «Apagar/Encender la subida» (con PIN);
//   G. los estados: equipo viejo, subida apagada, error de subida, y reglas sin desplegar;
//   H. el informe para la IA; I. 390 px sin desbordes; J. cero errores de consola.
const { chromium } = require('playwright');
const path = require('path');
const now = Date.now();
const MIN = 60000, H = 3600000;

const key = (seq) => 'e' + String(seq).padStart(10, '0');
const ev = (seq, edad, l, g, m, n) => ({ [key(seq)]: Object.assign({ t: now - edad, l: l, g: g, m: m }, n ? { n: n } : {}) });
const EVENTS = Object.assign({},
  ev(1, 5 * H, 'I', 'app', 'Proceso iniciado: v0.6.77 (140), Android 12 (API 31), Cat S62, almacenamiento desbloqueado'),
  ev(2, 3 * H, 'W', 'cmd', 'Comando rechazado (BAD_SIGNATURE): BLOCK_WIFI, vía FCM'),
  ev(3, 2 * H, 'E', 'vpn', 'Error al iniciar la VPN\njava.lang.IllegalStateException: boom\n    at com.ejemplo.locksuite.service.KosherVpnService.start(KosherVpnService.kt:640)'),
  ev(4, 10 * MIN, 'F', 'crash', "Excepción no atrapada en el hilo 'main'\njava.lang.NullPointerException\n    at com.ejemplo.locksuite.ui.Foo.bar(Foo.kt:12)", 2),
  ev(5, 5 * MIN, 'I', 'vpn', 'Túnel DNS establecido'),
  // Texto hostil: el celular (o una app que lo hizo fallar) puede escribir cualquier cosa.
  ev(6, 4 * MIN, 'W', '<b>x</b>', '<img src=x onerror="window.__xss=1"> <script>window.__xss=2</script>'),
  ev(7, 30 * H, 'E', 'err', 'Un error de hace 30 horas'));

const SNAPS = {
  s00000001: { t: now - 2 * H, at: now - 2 * H, pkgs: 'com.waze', ver: '0.6.77 (140)', diag: 1,
    system: 'Android 12\nBatería 90%', exits: 'sin cierres', logcat: 'E/AndroidRuntime: viejo', packages: 'com.waze: ok' },
  s00000002: { t: now - 3 * MIN, at: now - 3 * MIN, pkgs: 'com.whatsapp', ver: '0.6.77 (140)', diag: 1,
    system: 'Android 12\nBatería 77%\n<script>window.__xss=3</script>', exits: 'ANR hace 1 h', logcat: 'E/AndroidRuntime: boom', packages: 'com.whatsapp: suspendida=sí' }
};

const APPS = {
  com_whatsapp: { packageName: 'com.whatsapp', label: 'WhatsApp', appType: 'Usuario', isHidden: false, isSuspended: false },
  com_waze: { packageName: 'com.waze', label: 'Waze', appType: 'Usuario', isHidden: false, isSuspended: false },
  com_android_phone: { packageName: 'com.android.phone', label: 'Teléfono', appType: 'Sistema', isHidden: false, isSuspended: false }
};

const SEED = {
  authorizedAdminsUids: { admin1: true }, globalSettings: {}, storeApps: {},
  devices: {
    dev1: { deviceName: 'itzi', model: 'Cat S62', androidVersion: '12', lastSeen: now - 30000, fcmToken: 'tok1', hasPinConfigured: true,
      versionName: '0.6.77', versionCode: 140, apps: APPS, diagVersion: 1, diagUploadEnabled: true },
    dev2: { deviceName: 'viejo', model: 'Q1', lastSeen: now - 30000, fcmToken: 'tok2', versionName: '0.6.60', versionCode: 120 },
    dev3: { deviceName: 'apagado', model: 'X', lastSeen: now - 30000, fcmToken: 'tok3', versionName: '0.6.77', versionCode: 140, diagVersion: 1, diagUploadEnabled: false },
    dev4: { deviceName: 'sinreglas', model: 'Y', lastSeen: now - 30000, fcmToken: 'tok4', versionName: '0.6.77', versionCode: 140, diagVersion: 1, diagUploadEnabled: true,
      diagUploadError: 'Permission denied' }
  },
  deviceDiag: {
    dev1: { events: EVENTS, snapshots: SNAPS, meta: { lastUploadAt: now - 2 * MIN, lastSeq: 7, appVersion: '0.6.77 (140)', diagVersion: 1, skipped: 5 } }
  }
};

let fallas = 0; const ok = (c, m) => { console.log((c ? 'ok   ' : 'FALLA') + ' ' + m); if (!c) fallas++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const URL = (id, hash) => 'file://' + path.resolve('celular.html') + '?id=' + id + (hash ? '#' + hash : '');

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1300, height: 900 } });
  const errores = []; p.on('pageerror', (e) => errores.push(e.message)); p.on('console', (m) => { if (m.type() === 'error') errores.push(m.text()); });
  p.on('dialog', (d) => d.accept());      // los confirm() se aceptan
  await p.addInitScript((s) => { window.__SEED__ = s; }, SEED);

  const txt = (sel) => p.evaluate((s) => { const e = document.querySelector(s); return e ? e.textContent : null; }, sel);
  const filas = () => p.evaluate(() => [...document.querySelectorAll('#dr-lista .dr-ev')].map((e) => e.querySelector('.dr-g').textContent));
  const escuchas = () => p.evaluate(() => __fakeDb.listeners.map((l) => l.path).filter((x) => x.startsWith('deviceDiag')));

  // ══ A. Los oyentes se abren recién al entrar a la sección ═══════════════════════
  await p.goto(URL('dev1'));
  await sleep(900);
  ok((await escuchas()).length === 0, 'A: al abrir la ficha NO se abre ningún oyente de deviceDiag → ' + JSON.stringify(await escuchas()));
  await p.click('#c-rail button[data-sec="diag"]');
  await sleep(500);
  const L1 = await escuchas();
  ok(L1.length === 3 && ['deviceDiag/dev1/events', 'deviceDiag/dev1/snapshots', 'deviceDiag/dev1/meta'].every((x) => L1.includes(x)),
    'A: al entrar a Diagnóstico se abren los tres oyentes (events, snapshots, meta) → ' + JSON.stringify(L1));
  await p.click('#c-rail button[data-sec="resumen"]'); await p.click('#c-rail button[data-sec="diag"]');
  await sleep(200);
  ok((await escuchas()).length === 3, 'A: ir y volver a la sección no duplica los oyentes');

  // ══ B. Estado y lista (dev1) ═══════════════════════════════════════════════════
  await p.goto(URL('dev1', 'diag'));
  await sleep(900);
  const estado = await txt('#dr-estado');
  ok(/Registro activo/.test(estado) && /Último envío del celular: hace 2 min/.test(estado), 'B: estado «Registro activo» con el último envío → ' + estado.replace(/\s+/g, ' ').slice(0, 160));
  ok(/omitió 5 renglones/.test(estado), 'B: avisa los renglones que el celular omitió (meta.skipped)');
  ok(/Errores y caídas en las últimas 24 h: 3\./.test(estado) && /Última caída del programa: hace 10 min/.test(estado),
    'B: cuenta 3 errores/caídas en 24 h (la caída agrupada ×2 suma 2; el de 30 h no) y la última caída → ' + estado.replace(/\s+/g, ' ').slice(-150));
  ok(!(await p.evaluate(() => document.getElementById('dr-pedir').disabled)), 'B: «Pedir diagnóstico» habilitado');
  ok((await txt('#dr-interruptor')) === 'Apagar la subida del registro', 'B: el interruptor ofrece «Apagar» cuando está encendida');
  const f0 = await filas();
  ok(JSON.stringify(f0) === JSON.stringify(['<b>x</b>', 'crash', 'vpn', 'cmd']),
    'B: por omisión (avisos y errores, 24 h) salen 4 renglones, lo más nuevo arriba → ' + JSON.stringify(f0));
  const crashFila = await p.evaluate(() => { const e = [...document.querySelectorAll('#dr-lista .dr-ev')].find((x) => x.querySelector('.dr-g').textContent === 'crash'); return e.textContent; });
  ok(/caída/.test(crashFila) && /×2/.test(crashFila), 'B: la caída se marca como «caída» y con ×2 → ' + crashFila.replace(/\s+/g, ' ').slice(0, 90));
  const largos = await p.evaluate(() => document.querySelectorAll('#dr-lista details.dr-ev').length);
  ok(largos === 2, 'B: los renglones con varias líneas (error de VPN y caída) son desplegables → ' + largos);

  // ══ C. Texto hostil ═══════════════════════════════════════════════════════════
  ok((await p.evaluate(() => window.__xss)) === undefined, 'C: el texto del celular no ejecutó nada (window.__xss indefinido)');
  const hostil = await p.evaluate(() => ({ img: document.querySelectorAll('#c-diag-remoto img').length, b: document.querySelectorAll('#c-diag-remoto .dr-g b').length,
    scripts: document.querySelectorAll('#c-diag-remoto script').length, tag: [...document.querySelectorAll('.dr-g')].map((x) => x.textContent).includes('<b>x</b>') }));
  ok(hostil.img === 0 && hostil.b === 0 && hostil.scripts === 0 && hostil.tag, 'C: <img>, <b> y <script> aparecen como TEXTO, no como elementos → ' + JSON.stringify(hostil));

  // ══ D. Filtros ═════════════════════════════════════════════════════════════════
  await p.click('#dr-nivel button[data-n="I"]'); await sleep(100);
  ok((await filas()).length === 6, 'D: «Todo» suma los de información (6 en 24 h) → ' + JSON.stringify(await filas()));
  await p.click('#dr-nivel button[data-n="E"]'); await sleep(100);
  ok(JSON.stringify(await filas()) === JSON.stringify(['crash', 'vpn']), 'D: «Solo errores» deja la caída y el error de VPN → ' + JSON.stringify(await filas()));
  await p.selectOption('#dr-ventana', 'todo'); await sleep(100);
  ok(JSON.stringify(await filas()) === JSON.stringify(['crash', 'vpn', 'err']), 'D: «Todo lo guardado» suma el error de hace 30 h → ' + JSON.stringify(await filas()));
  await p.selectOption('#dr-ventana', '1h'); await p.click('#dr-nivel button[data-n="I"]'); await sleep(100);
  ok(JSON.stringify(await filas()) === JSON.stringify(['<b>x</b>', 'vpn', 'crash']), 'D: «Última hora» con todo → 3 renglones → ' + JSON.stringify(await filas()));
  await p.selectOption('#dr-ventana', '24h'); await sleep(100);
  const opcionesEtiqueta = await p.evaluate(() => [...document.querySelectorAll('#dr-etiqueta option')].map((o) => o.value));
  ok(['', 'vpn', 'cmd', 'crash', 'app', 'err', '<b>x</b>'].every((t) => opcionesEtiqueta.includes(t)), 'D: el selector de etiquetas lista las que hay → ' + JSON.stringify(opcionesEtiqueta));
  await p.selectOption('#dr-etiqueta', 'vpn'); await sleep(100);
  ok(JSON.stringify(await filas()) === JSON.stringify(['vpn', 'vpn']), 'D: filtrar por etiqueta «vpn» → 2 renglones');
  await p.selectOption('#dr-etiqueta', ''); await p.fill('#dr-texto', 'boom'); await sleep(100);
  ok(JSON.stringify(await filas()) === JSON.stringify(['vpn']), 'D: buscar «boom» encuentra solo el error de VPN → ' + JSON.stringify(await filas()));
  await p.fill('#dr-texto', 'zzzz'); await sleep(100);
  ok(/Ningún renglón cumple este filtro/.test(await txt('#dr-lista')), 'D: sin coincidencias lo dice');
  await p.fill('#dr-texto', ''); await p.click('#dr-nivel button[data-n="W"]'); await sleep(100);

  // ══ D2. Un latido no cierra lo abierto ═══════════════════════════════════════════
  await p.click('#dr-lista details.dr-ev >> nth=0 >> summary');          // el de la caída (más nuevo de los largos)
  await sleep(100);
  await p.evaluate(() => { window.__nodo = document.querySelector('#dr-lista details.dr-ev[open]'); window.__nodo.__marca = 'mismo'; });
  ok(await p.evaluate(() => !!window.__nodo), 'D2: se abrió un renglón largo');
  await p.evaluate(() => __fakeDb.setAt('devices/dev1/lastSeen', Date.now()));
  await sleep(200);
  ok(await p.evaluate(() => { const n = document.querySelector('#dr-lista details.dr-ev[open]'); return !!n && n.__marca === 'mismo'; }),
    'D2: un latido del celular NO redibuja la lista (el renglón abierto sigue siendo el mismo nodo y sigue abierto)');
  // volver a tocar el filtro que ya está elegido tampoco redibuja (no se pierde lo abierto ni la selección de texto)
  await p.click('#dr-nivel button[data-n="W"]'); await sleep(100);
  ok(await p.evaluate(() => { const n = document.querySelector('#dr-lista details.dr-ev[open]'); return !!n && n.__marca === 'mismo'; }),
    'D2: volver a tocar el filtro ya elegido NO redibuja la lista');
  await p.evaluate(() => __fakeDb.setAt('deviceDiag/dev1/events/e0000000099', { t: Date.now(), l: 'W', g: 'cmd', m: 'renglón nuevo' }));
  await sleep(250);
  ok(JSON.stringify((await filas())[0]) === '"cmd"' && /renglón nuevo/.test(await txt('#dr-lista')), 'D2: un renglón nuevo aparece arriba sin recargar');
  ok(await p.evaluate(() => !!document.querySelector('#dr-lista details.dr-ev[open]')), 'D2: y el renglón que estaba abierto sigue abierto después de redibujar');
  await p.evaluate(() => __fakeDb.setAt('deviceDiag/dev1/events/e0000000099', null)); await sleep(150);

  // ══ E. Visor de diagnósticos completos ═══════════════════════════════════════════
  const opcSnap = await p.evaluate(() => [...document.querySelectorAll('#dr-snap-sel option')].map((o) => o.textContent));
  ok(opcSnap.length === 2 && /com\.whatsapp/.test(opcSnap[0]) && /com\.waze/.test(opcSnap[1]), 'E: dos diagnósticos, el más nuevo primero → ' + JSON.stringify(opcSnap));
  const sec = await p.evaluate(() => [...document.querySelectorAll('#dr-snap details')].map((d) => d.querySelector('summary b').textContent));
  ok(sec.length === 4 && /Sistema/.test(sec[0]) && /Cierres/.test(sec[1]) && /logcat/.test(sec[2]) && /Apps/.test(sec[3]), 'E: cuatro secciones → ' + JSON.stringify(sec));
  const sistema = await p.evaluate(() => document.querySelector('#dr-snap pre').textContent);
  ok(/Batería 77%/.test(sistema) && /<script>window\.__xss=3<\/script>/.test(sistema) && (await p.evaluate(() => window.__xss)) === undefined,
    'E: el sistema del diagnóstico más nuevo se ve como texto (el <script> no corre)');
  await p.selectOption('#dr-snap-sel', 's00000001'); await sleep(100);
  ok(/Batería 90%/.test(await p.evaluate(() => document.querySelector('#dr-snap pre').textContent)), 'E: se puede elegir el diagnóstico anterior');
  await p.selectOption('#dr-snap-sel', 's00000002'); await sleep(100);

  // ══ F. Pedir diagnóstico y el interruptor ═══════════════════════════════════════
  const opcApps = await p.evaluate(() => [...document.querySelectorAll('#dr-pkg-sel option')].map((o) => o.value));
  ok(opcApps.includes('com.whatsapp') && opcApps.includes('com.waze') && opcApps.indexOf('com.waze') < opcApps.indexOf('com.android.phone'),
    'F: el selector de apps trae las del celular, las del usuario primero → ' + JSON.stringify(opcApps));
  await p.selectOption('#dr-pkg-sel', 'com.whatsapp'); await p.selectOption('#dr-pkg-sel', 'com.waze'); await p.selectOption('#dr-pkg-sel', 'com.whatsapp');
  ok((await p.inputValue('#dr-pkgs')) === 'com.whatsapp, com.waze', 'F: elegir apps las agrega al campo sin repetir → ' + (await p.inputValue('#dr-pkgs')));

  await p.evaluate(() => { window.__fnMode = 'fcm_new'; });
  let n = await p.evaluate(() => __calls.length);
  await p.click('#dr-pedir');
  await sleep(250);
  const pedido = await p.evaluate((n) => __calls.slice(n), n);
  ok(pedido.length === 1 && pedido[0].command === 'COLLECT_DIAGNOSTICS' && pedido[0].packages === 'com.whatsapp,com.waze' && pedido[0].deviceId === 'dev1',
    'F: «Pedir diagnóstico» manda COLLECT_DIAGNOSTICS con las apps → ' + JSON.stringify(pedido));
  ok(await p.evaluate(() => document.getElementById('dr-pedir').disabled) && /Pidiéndole el diagnóstico a itzi/.test(await txt('#dr-pedir-msg')),
    'F: mientras espera, el botón queda deshabilitado y lo dice');
  await p.evaluate(() => { __fakeDb.setAt('deviceDiag/dev1/snapshots/s00000003', { t: Date.now(), at: Date.now(), pkgs: 'com.whatsapp,com.waze', ver: '0.6.77 (140)', diag: 1,
    system: 'S3', exits: 'X3', logcat: 'L3', packages: 'P3' }); __deviceApplies('dev1', __lastCid, null); });
  await sleep(400);
  ok(/Listo: el diagnóstico llegó/.test(await txt('#dr-pedir-msg')) && !(await p.evaluate(() => document.getElementById('dr-pedir').disabled)),
    'F: cuando el celular confirma, avisa y vuelve a habilitar → ' + (await txt('#dr-pedir-msg')));
  const opc3 = await p.evaluate(() => [...document.querySelectorAll('#dr-snap-sel option')].map((o) => o.textContent));
  ok(opc3.length === 3 && /com\.whatsapp,com\.waze/.test(opc3[0]) && (await p.evaluate(() => document.querySelector('#dr-snap pre').textContent)) === 'S3',
    'F: el diagnóstico nuevo aparece primero y se muestra solo');

  // paquetes inválidos: no se manda nada
  await p.fill('#dr-pkgs', 'waze, com.ok');
  n = await p.evaluate(() => __calls.length);
  await p.click('#dr-pedir'); await sleep(200);
  ok((await p.evaluate(() => __calls.length)) === n && await p.evaluate(() => [...document.querySelectorAll('.ls-toast.error')].some((t) => /no son válidos/.test(t.textContent) && /waze/.test(t.textContent))),
    'F: un nombre de app inválido se avisa y NO manda el comando');
  // sin apps: packages null
  await p.fill('#dr-pkgs', '');
  n = await p.evaluate(() => __calls.length);
  await p.click('#dr-pedir'); await sleep(250);
  const sinApps = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(sinApps && sinApps.command === 'COLLECT_DIAGNOSTICS' && !sinApps.packages, 'F: sin apps elegidas el comando va sin paquetes');
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null)); await sleep(300);

  // PIN: el diagnóstico NO lo pide; el interruptor SÍ
  await p.evaluate(() => { window.__prompts = 0; window.prompt = () => { window.__prompts++; return '1234'; }; window.__fnMode = 'pin_requerido'; delete LS.pins['dev1']; });
  n = await p.evaluate(() => __calls.length);
  await p.click('#dr-pedir'); await sleep(250);
  ok((await p.evaluate(() => __prompts)) === 0 && (await p.evaluate((n) => __calls.slice(n).filter((c) => c.command === 'COLLECT_DIAGNOSTICS').length, n)) === 1,
    'F: con la puerta del PIN activa, «Pedir diagnóstico» pasa SIN pedir el PIN (es de solo lectura)');
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null)); await sleep(300);

  n = await p.evaluate(() => __calls.length);
  await p.click('#dr-interruptor'); await sleep(500);
  const apagar = await p.evaluate((n) => __calls.slice(n), n);
  ok((await p.evaluate(() => __prompts)) === 1 && apagar.length === 2 && apagar.every((c) => c.command === 'SET_DIAGNOSTICS_UPLOAD' && c.enabled === false) && apagar[1].devicePin === '1234',
    'F: «Apagar la subida» pide el PIN (1 vez), y manda SET_DIAGNOSTICS_UPLOAD con enabled=false → ' + JSON.stringify(apagar.map((c) => [c.command, c.enabled, !!c.devicePin])));
  await p.evaluate(() => { window.__fnMode = 'fcm_new'; __deviceApplies('dev1', __lastCid, 'diagUploadEnabled', false); });
  await sleep(300);
  ok(/APAGADA/.test(await txt('#dr-estado')) && (await txt('#dr-interruptor')) === 'Encender la subida del registro' && await p.evaluate(() => document.getElementById('dr-pedir').disabled),
    'F: cuando el celular confirma, el estado pasa a APAGADA, el botón ofrece «Encender» y «Pedir diagnóstico» se deshabilita');
  n = await p.evaluate(() => __calls.length);
  await p.click('#dr-interruptor'); await sleep(250);
  const encender = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(encender && encender.command === 'SET_DIAGNOSTICS_UPLOAD' && encender.enabled === true, 'F: «Encender» manda enabled=true (sin pedir confirmación) → ' + JSON.stringify(encender));
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, 'diagUploadEnabled', true)); await sleep(300);
  ok(/Registro activo/.test(await txt('#dr-estado')) && (await txt('#dr-interruptor')) === 'Apagar la subida del registro', 'F: vuelve a «Registro activo»');

  // ══ H. Informe para la IA ══════════════════════════════════════════════════════
  await p.fill('#dr-nota', 'No me abre WhatsApp, se me cortó la llamada');
  await p.click('#dr-ver'); await sleep(100);
  const inf = await txt('#dr-informe');
  ok(inf && /# Informe de diagnóstico de LockSuite/.test(inf) && /Equipo: itzi \(id: dev1\) — Cat S62, Android 12/.test(inf) && /LockSuite: 0\.6\.77 \(140\)/.test(inf),
    'H: el informe trae el encabezado del equipo');
  ok(/## Qué contó el usuario\nNo me abre WhatsApp, se me cortó la llamada/.test(inf), 'H: y lo que contó el usuario');
  ok(/E vpn — Error al iniciar la VPN/.test(inf) && /F crash ×2 — Excepción no atrapada/.test(inf) && /W cmd — Comando rechazado \(BAD_SIGNATURE\)/.test(inf),
    'H: los errores y avisos, con su hora y etiqueta');
  ok(!/Túnel DNS establecido/.test(inf) && !/Proceso iniciado/.test(inf), 'H: por omisión NO trae las líneas de información (solo avisos y errores)');
  ok(/## Diagnóstico completo pedido desde el panel/.test(inf) && /### Sistema\nS3/.test(inf) && /### Apps consultadas\nP3/.test(inf), 'H: incluye el diagnóstico completo que se está mirando');
  ok(/<b>x<\/b>/.test(inf) && (await p.evaluate(() => document.querySelectorAll('#dr-informe b, #dr-informe img').length)) === 0, 'H: el texto hostil va como texto también en el informe');
  await p.selectOption('#dr-inf-nivel', 'I'); await p.click('#dr-ver'); await p.click('#dr-ver'); await sleep(100);
  ok(/Túnel DNS establecido/.test(await txt('#dr-informe')), 'H: con «Todo (con información)» el informe suma las líneas de información');
  await p.click('#dr-ver'); await sleep(50);
  ok(await p.evaluate(() => document.getElementById('dr-informe').classList.contains('hidden')), 'H: «Ver el informe» alterna mostrar/ocultar');
  await p.uncheck('#dr-inc-snap'); await p.click('#dr-ver'); await sleep(50);
  ok(/\(No se incluyó\.\)/.test(await txt('#dr-informe')) && !/### Sistema/.test(await txt('#dr-informe')), 'H: se puede sacar el diagnóstico completo del informe');
  await p.check('#dr-inc-snap'); await p.click('#dr-ver');
  // Copiar: si el navegador deja, queda en el portapapeles; si no, queda a la vista para copiar a mano.
  await p.evaluate(() => { window.__copiado = null; navigator.clipboard.writeText = (t) => { window.__copiado = t; return Promise.resolve(); }; });
  await p.click('#dr-copiar'); await sleep(200);
  const copiado = await p.evaluate(() => window.__copiado);
  ok(copiado && /# Informe de diagnóstico de LockSuite/.test(copiado) && /Informe copiado/.test(await p.evaluate(() => [...document.querySelectorAll('.ls-toast')].map((t) => t.textContent).join('|'))),
    'H: «Copiar informe» deja el informe en el portapapeles y avisa');
  await p.evaluate(() => { navigator.clipboard.writeText = () => Promise.reject(new Error('NotAllowed')); document.getElementById('dr-informe').classList.add('hidden'); });
  await p.click('#dr-copiar'); await sleep(200);
  ok(await p.evaluate(() => !document.getElementById('dr-informe').classList.contains('hidden') && document.getElementById('dr-informe').textContent.length > 100) &&
    (await p.evaluate(() => [...document.querySelectorAll('.ls-toast.warn')].some((t) => /no dejó copiar/.test(t.textContent)))),
    'H: si el navegador no deja copiar, el informe queda a la vista para copiarlo a mano');

  // ══ I. 390 px ═════════════════════════════════════════════════════════════════
  await p.setViewportSize({ width: 390, height: 844 });
  await p.click('#dr-ver'); await sleep(150);
  const ancho = await p.evaluate(() => ({ doc: document.documentElement.scrollWidth, card: document.getElementById('c-diag-remoto').scrollWidth, cardW: document.getElementById('c-diag-remoto').clientWidth }));
  ok(ancho.doc <= 391 && ancho.card <= ancho.cardW + 1, 'I: a 390 px el registro no se desborda de costado → ' + JSON.stringify(ancho));
  await p.setViewportSize({ width: 1300, height: 900 });

  // ══ G. Los demás estados ════════════════════════════════════════════════════════
  await p.goto(URL('dev2', 'diag')); await sleep(900);
  ok(/todavía no informó tener el registro/.test(await txt('#dr-estado')) && await p.evaluate(() => document.getElementById('dr-pedir').disabled && document.getElementById('dr-interruptor').disabled),
    'G: un celular con LockSuite viejo (sin diagVersion) lo dice y deshabilita los dos botones');
  ok(/no subió ningún renglón/.test(await txt('#dr-lista')), 'G: y la lista dice que no hay renglones');
  await p.goto(URL('dev3', 'diag')); await sleep(900);
  ok(/APAGADA/.test(await txt('#dr-estado')) && (await txt('#dr-interruptor')) === 'Encender la subida del registro' &&
    await p.evaluate(() => document.getElementById('dr-pedir').disabled && !document.getElementById('dr-interruptor').disabled),
    'G: subida apagada: avisa, ofrece «Encender» y no deja pedir diagnóstico');
  await p.goto(URL('dev4', 'diag')); await sleep(900);
  ok(/no pudo subir su registro/.test(await txt('#dr-estado')) && /Permission denied/.test(await txt('#dr-estado')) && /firebase deploy --only database/.test(await txt('#dr-estado')) &&
    !(await p.evaluate(() => document.getElementById('dr-pedir').disabled)), 'G: error de subida del celular: muestra el motivo y la pista de las reglas');

  // reglas sin desplegar: el panel no puede leer deviceDiag
  const p2 = await b.newPage({ viewport: { width: 1300, height: 900 } });
  p2.on('pageerror', (e) => errores.push('p2: ' + e.message));
  await p2.addInitScript((s) => { window.__SEED__ = s; window.__denyPaths = ['deviceDiag']; }, SEED);
  await p2.goto(URL('dev1', 'diag')); await sleep(900);
  const den = await p2.evaluate(() => ({ estado: document.getElementById('dr-estado').textContent, lista: document.getElementById('dr-lista').textContent }));
  ok(/no pudo leer el registro/.test(den.estado) && /permission_denied/.test(den.estado) && /firebase deploy --only database/.test(den.estado) && /No se pudo leer el registro/.test(den.lista),
    'G: con las reglas sin desplegar (permission_denied) lo dice con la solución, y la lista no queda en «Cargando…» → ' + den.estado.replace(/\s+/g, ' ').slice(0, 120));
  await p2.close();

  ok(errores.length === 0, 'J: sin errores de consola/página: ' + JSON.stringify(errores.slice(0, 5)));
  await b.close(); console.log(fallas ? fallas + ' FALLAS' : 'TODO VERDE'); process.exit(fallas ? 1 : 0);
})();
