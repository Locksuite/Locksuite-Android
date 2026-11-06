// 6/11/2026 (B.110) — «Eliminar» en el panel tiene que borrar TAMBIÉN el registro de errores del celular.
//
// El aviso de «Eliminar dispositivo» promete borrar «todos los datos» y el de «Eliminar definitivamente»
// (de los archivados) «todos los registros y credenciales». El registro de errores vive en un nodo aparte
// (`deviceDiag/<id>`): si el panel no lo borra, queda huérfano para siempre. «Archivar», en cambio, conserva
// los datos (se pueden restaurar), así que NO tiene que tocar el registro.
//
// Se corre desde tools/panel_render/run.sh (index.html + app.js con el Firebase falso).
const { chromium } = require('playwright');
const path = require('path');

const now = Date.now();
const dispositivo = (nombre) => ({
  deviceName: nombre, model: 'Q-Tel Q1', lastSeen: now - 30000, fcmToken: 'tok', hasPinConfigured: true,
  trustedAdmins: { admin1: true }, versionName: '0.6.77', versionCode: 140
});
const registro = (marca) => ({
  events: { e0000000001: { t: now - 60000, l: 'E', g: 'vpn', m: 'error de ' + marca } },
  meta: { lastSeq: 1, lastUploadAt: now - 30000 }
});
const SEED = {
  authorizedAdminsUids: { admin1: true },
  globalSettings: {},
  devices: { dev1: dispositivo('para eliminar'), dev2: dispositivo('para archivar'), dev3: dispositivo('queda igual') },
  archivedDevices: { arch1: Object.assign(dispositivo('ya archivado'), { _archivedAt: '2026-10-01T00:00:00.000Z' }) },
  deviceSecrets: {
    dev1: { ownerUid: 'u1', commandSecret: 's1' }, dev2: { ownerUid: 'u2', commandSecret: 's2' },
    dev3: { ownerUid: 'u3', commandSecret: 's3' }, arch1: { ownerUid: 'ua', commandSecret: 'sa' }
  },
  deviceDiag: { dev1: registro('dev1'), dev2: registro('dev2'), dev3: registro('dev3'), arch1: registro('arch1') }
};

let fallas = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FALLA') + ' ' + m); if (!c) fallas++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errores = [];
  page.on('pageerror', (e) => errores.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errores.push('console: ' + m.text()); });
  await page.addInitScript((s) => { window.__SEED__ = s; }, SEED);
  await page.goto('file://' + path.resolve('index.html'));
  await page.waitForSelector('.device-card', { timeout: 10000 });
  await sleep(300);

  const hay = (ruta) => page.evaluate((r) => window.__fakeDb.getAt(r) != null, ruta);
  // Los avisos de confirmación del panel se aceptan solos (son dos seguidos).
  await page.evaluate(() => { window.confirm = () => true; window.alert = () => {}; });

  // ── 0) punto de partida ──
  ok(await hay('deviceDiag/dev1/events') && await hay('deviceDiag/dev2/events') && await hay('deviceDiag/arch1/events'),
    'partida: los cuatro equipos tienen registro de errores');

  // ── 1) Eliminar dispositivo (sidebar) ──
  await page.evaluate(() => { openDeviceSidebar('dev1', currentDevicesData['dev1']); }); // sin return: espera el PIN
  await sleep(400);
  await page.evaluate(() => document.getElementById('sidebar-delete-btn').click());
  await sleep(600);
  ok(!(await hay('devices/dev1')), '«Eliminar dispositivo»: se borró devices/dev1');
  ok(!(await hay('deviceSecrets/dev1')), '«Eliminar dispositivo»: se borró deviceSecrets/dev1');
  ok(!(await hay('deviceDiag/dev1')), '«Eliminar dispositivo»: se borró TAMBIÉN deviceDiag/dev1 (sin registro huérfano)');
  ok(await hay('deviceDiag/dev2/events') && await hay('deviceDiag/dev3/events') && await hay('deviceDiag/arch1/events'),
    '«Eliminar dispositivo»: el registro de los demás equipos sigue');
  const msg = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/eliminado/i.test(msg) || msg === '', 'la barra lateral no quedó con un error: «' + msg + '»');

  // ── 2) Archivar (conserva los datos: NO toca el registro) ──
  await page.evaluate(() => { openDeviceSidebar('dev2', currentDevicesData['dev2']); });
  await sleep(400);
  await page.evaluate(() => document.getElementById('sidebar-archive-btn').click());
  await sleep(600);
  ok(!(await hay('devices/dev2')) && await hay('archivedDevices/dev2'), '«Archivar»: dev2 pasó a archivedDevices');
  ok(await hay('deviceDiag/dev2/events'), '«Archivar»: conserva el registro de errores (se puede restaurar)');

  // ── 3) Eliminar definitivamente un archivado ──
  await page.evaluate(() => { deleteArchivedDevicePermanently('arch1', 'ya archivado', { disabled: false }); });
  await sleep(600);
  ok(!(await hay('archivedDevices/arch1')), 'archivados: se borró archivedDevices/arch1');
  ok(!(await hay('deviceSecrets/arch1')), 'archivados: se borró deviceSecrets/arch1');
  ok(!(await hay('deviceDiag/arch1')), 'archivados: se borró TAMBIÉN deviceDiag/arch1');
  ok(await hay('deviceDiag/dev2/events') && await hay('deviceDiag/dev3/events'),
    'archivados: el registro de los demás sigue (también el del recién archivado)');

  ok(errores.length === 0, 'sin errores de consola/página: ' + JSON.stringify(errores));
  await browser.close();
  console.log(fallas ? '\n' + fallas + ' FALLAS' : '\nTODO VERDE');
  process.exit(fallas ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
