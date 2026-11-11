const { chromium } = require('playwright');
const path = require('path');
const now = Date.now();
const SEED = {
  authorizedAdminsUids: { admin1: true },
  globalSettings: {},
  devices: {
    dev1: { deviceName: "itzi", model: "Cat S22 FLIP", lastSeen: now - 30000, fcmToken: "tok1", hasPinConfigured: true, trustedAdmins: { admin1: true }, wifiBlocked: false, versionName: "0.6.66", versionCode: 129 },
    dev2: { deviceName: "R shaul said", model: "Q-Tel Q1", lastSeen: now - 60000, hasPinConfigured: true, trustedAdmins: { admin1: true }, fcmTokenError: "SERVICE_NOT_AVAILABLE", whatsappBlockStatus: false, wifiBlocked: false,
      pendingCommands: { k1: { data: { command: "BLOCK_WHATSAPP_STATUS", commandId: "k1", timestamp: String(now - 120000), signature: "x" }, family: "BLOCK_WHATSAPP_STATUS", queuedAt: now - 120000, expiresAt: now + 86400000 } },
      versionName: "0.6.66", versionCode: 129 },
    dev3: { deviceName: "nuevo sin FCM", model: "X", lastSeen: now - 10000, hasPinConfigured: true, trustedAdmins: { admin1: true }, mailboxLive: true, mailboxLastCheckAt: now - 5000, mailboxLastResult: "sin pendientes", wifiBlocked: false, versionName: "0.6.67", versionCode: 130 },
    // 1/11/2026 (B.104): celulares cuya nube no tiene el PIN. dev5: sin bandera (como «Shuli», el celular
    // nuevo del dueño). dev6: con la bandera en true pero SIN verificador en deviceSecrets (bandera vieja).
    dev5: { deviceName: "Shuli", model: "Google Pixel", lastSeen: now - 10000, fcmToken: "tok5", versionName: "0.6.69", versionCode: 132 },
    dev6: { deviceName: "bandera vieja", model: "Z", lastSeen: now - 10000, fcmToken: "tok6", hasPinConfigured: true, versionName: "0.6.69", versionCode: 132 },
    // 9/11/2026 (auditoría): celulares con PIN y SIN «recordar este celular», para probar la verificación del PIN
    // por la Cloud Function. dev7: se prueba con un PIN malo y uno bueno. dev8: la Function contesta «bloqueado».
    dev7: { deviceName: "con PIN", model: "Z7", lastSeen: now - 10000, fcmToken: "tok7", hasPinConfigured: true, versionName: "0.6.81", versionCode: 144 },
    dev8: { deviceName: "con PIN bloqueado", model: "Z8", lastSeen: now - 10000, fcmToken: "tok8", hasPinConfigured: true, versionName: "0.6.81", versionCode: 144 }
  },
  // Un archivado que se llevó consigo la copia de «recordar este celular» (el panel viejo la escribía a mano).
  archivedDevices: {
    arch1: { deviceName: "archivado", model: "Q", lastSeen: now - 99999, hasPinConfigured: true, trustedAdmins: { admin1: true },
      info: { trustedAdmins: { admin1: true }, nota: "se conserva" }, _archivedAt: "2026-10-01T00:00:00.000Z" }
  }
};
let fallas = 0;
const ok = (c, m) => { console.log((c ? "ok   " : "FALLA") + " " + m); if (!c) fallas++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errores = [];
  page.on('pageerror', e => errores.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errores.push('console: ' + m.text()); });
  await page.addInitScript(s => { window.__SEED__ = s; }, SEED);
  await page.goto('file://' + path.resolve('index.html'));
  await page.waitForSelector('.device-card', { timeout: 10000 });
  await sleep(300);
  // 1) badge Sin FCM con motivo
  const badge = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.device-card')];
    const c = cards.find(x => x.textContent.includes('R shaul said'));
    const b = c && c.querySelector('.device-header .badge');
    return b ? { t: b.textContent, title: b.title } : null;
  });
  ok(badge && badge.t === 'Sin FCM' && /SERVICE_NOT_AVAILABLE/.test(badge.title), 'badge «Sin FCM» con el motivo en el título: ' + JSON.stringify(badge));
  // 2) abrir dev2 → tarjeta canal + interruptor pendiente
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(300);
  const canal = await page.evaluate(() => { const el = document.getElementById('device-command-channel'); return { vis: el && el.style.display, txt: el && el.textContent }; });
  ok(canal.vis === 'block' && /no tiene FCM — SERVICE_NOT_AVAILABLE/.test(canal.txt) && /1 comando\(s\) esperando/.test(canal.txt), 'tarjeta «Canal de comandos» visible para el equipo sin FCM');
  console.log('      texto: ' + canal.txt);
  const pend = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="whatsappBlockStatus"]'); const row = sw.closest('.toggle-row'); const m = row.querySelector('.ls-pendiente'); return { checked: sw.checked, marca: m && m.textContent }; });
  ok(pend.checked === true && pend.marca === '⏳ pendiente', 'interruptor con comando en el buzón: muestra lo pedido con «⏳ pendiente» ' + JSON.stringify(pend));
  // 3) A: Function VIEJA, equipo con FCM → espera el ack (no "encolado")
  await page.evaluate(() => openDeviceSidebar('dev1', currentDevicesData['dev1']));
  await sleep(200);
  const canal1 = await page.evaluate(() => document.getElementById('device-command-channel').style.display);
  ok(canal1 === 'none', 'equipo con FCM y buzón vacío: la tarjeta del canal se calla');
  await page.evaluate(() => { window.__fnMode = 'fcm_old'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); sw.checked = true; runCommandOnDevice('dev1', 'BLOCK_WIFI', null, sw, () => { window.__revert++; sw.checked = false; }); });
  await sleep(400);
  let st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/Esperando respuesta del celular/.test(st) && !/encolado/i.test(st), 'A) FCM salió (Function vieja): espera el ack, NO dice "encolado": ' + st);
  const marcaA = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); const m = sw.closest('.toggle-row').querySelector('.ls-pendiente'); return { c: sw.checked, m: m && m.textContent }; });
  ok(marcaA.c === true && marcaA.m === '⏳ pendiente', 'A) mientras tanto el interruptor queda en lo pedido con «⏳ pendiente» (no vuelve solo): ' + JSON.stringify(marcaA));
  await page.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(st === '✓ Comando aplicado con éxito en el celular', 'A) el ack "applied" se muestra: ' + st);
  const marcaA2 = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); const m = sw.closest('.toggle-row').querySelector('.ls-pendiente'); return { c: sw.checked, m: m && m.textContent }; });
  ok(marcaA2.c === true && marcaA2.m === '⏳ aplicando', 'A) aplicado pero sin reporte nuevo: «⏳ aplicando» ' + JSON.stringify(marcaA2));
  await page.evaluate(() => __fakeDb.setAt('devices/dev1/wifiBlocked', true));
  await sleep(300);
  const marcaA3 = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); const m = sw.closest('.toggle-row').querySelector('.ls-pendiente'); return { c: sw.checked, m: m && m.textContent, rev: window.__revert }; });
  ok(marcaA3.c === true && !marcaA3.m && marcaA3.rev === 0, 'A) cuando el equipo reporta, la marca se va y nunca se deshizo el interruptor ' + JSON.stringify(marcaA3));
  // 4) B: Function vieja, equipo SIN FCM y sin escucha en vivo (dev2) → 1,5 s y segundo plano
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(200);
  await page.evaluate(() => { window.__fnMode = 'notoken_old'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); sw.checked = true; runCommandOnDevice('dev2', 'BLOCK_WIFI', null, sw, () => { window.__revert++; }); });
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/no recibe FCM \(el equipo nunca registró FCM\)/.test(st), 'B) sin FCM: lo dice de entrada: ' + st);
  await sleep(1600);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/En el buzón del celular/.test(st) && /anterior a 0\.6\.67/.test(st), 'B) sin escucha en vivo: avisa que tarda hasta 15 min: ' + st);
  let rev = await page.evaluate(() => window.__revert);
  ok(rev === 0, 'B) "en cola" NO deshace el interruptor');
  await page.evaluate(() => __deviceApplies('dev2', __lastCid, 'wifiBlocked', true));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/BLOCK_WIFI: el celular lo aplicó/.test(st), 'B) cuando el celular lo levanta (minutos después), el panel lo dice: ' + st);
  // 5) C: Function nueva delivered:false, equipo con escucha en vivo (dev3)
  await page.evaluate(() => openDeviceSidebar('dev3', currentDevicesData['dev3']));
  await sleep(200);
  await page.evaluate(() => { window.__fnMode = 'notoken_new'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); sw.checked = true; runCommandOnDevice('dev3', 'BLOCK_WIFI', null, sw, () => { window.__revert++; }); });
  await sleep(1800);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/Esperando que lo levante/.test(st), 'C) en vivo: sigue esperando (no corta a los 1,5 s): ' + st);
  await page.evaluate(() => __deviceApplies('dev3', __lastCid, 'wifiBlocked', true));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(st === '✓ Comando aplicado en el celular (llegó por el buzón)', 'C) aplicado por el buzón en vivo: ' + st);
  // 6) D: rechazo real → deshace
  await page.evaluate(() => { window.__fnMode = 'fcm_new'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); runCommandOnDevice('dev3', 'UNBLOCK_WIFI', null, sw, () => { window.__revert++; }); });
  await sleep(300);
  await page.evaluate(() => __deviceApplies('dev3', __lastCid, null, null, 'rejected'));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  rev = await page.evaluate(() => window.__revert);
  ok(/rechazado por el celular \(BAD_SIGNATURE\)/.test(st) && rev === 1, 'D) un rechazo real sí deshace el interruptor: ' + st);
  // 7) E: FCM salió pero no contesta en 10 s → pendiente, sin deshacer; después llega
  await page.evaluate(() => { window.__fnMode = 'fcm_new'; window.__revert = 0; runCommandOnDevice('dev3', 'BLOCK_SMS', null, null, () => { window.__revert++; }); });
  await sleep(10500);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  rev = await page.evaluate(() => window.__revert);
  ok(/no confirmó en 10 s/.test(st) && /NO se perdió/.test(st) && rev === 0, 'E) sin respuesta en 10 s: pendiente, sin deshacer: ' + st.slice(0, 90));
  await page.evaluate(() => __deviceApplies('dev3', __lastCid, null));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/BLOCK_SMS: el celular lo aplicó/.test(st), 'E) el ack tardío se muestra: ' + st);
  // 8) F: UPDATE_LOCKSUITE a un equipo sin FCM ni escucha en vivo: no dice "actualizando"
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(200);
  await page.evaluate(() => { window.__fnMode = 'notoken_old'; runCommandOnDevice('dev2', 'UPDATE_LOCKSUITE', null, null, null); });
  await sleep(1800);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/En el buzón del celular/.test(st) && !/actualizando en segundo plano/.test(st), 'F) autoactualización a un equipo sin FCM: no promete lo que no sabe: ' + st.slice(0, 80));
  // ── B.104: celular cuya nube no tiene el PIN ──
  // Antes: alert «Este dispositivo no tiene un PIN de administrador configurado» y la ficha lateral se
  // CERRABA, así que ni siquiera se podía mandar la actualización (que no pide PIN) que lo arregla.
  const dialogos = [];
  page.on('dialog', d => { dialogos.push(d.message()); d.dismiss(); });
  await page.evaluate(() => openDeviceSidebar('dev5', currentDevicesData['dev5']));
  await sleep(300);
  const sb5 = await page.evaluate(() => ({ abierta: !document.getElementById('device-sidebar').classList.contains('hidden'), sel: selectedDeviceId, st: document.getElementById('sidebar-status-msg').textContent, modal: !document.getElementById('pin-modal').classList.contains('hidden') }));
  ok(sb5.abierta && sb5.sel === 'dev5', 'B.104) celular sin bandera de PIN: la ficha lateral SE ABRE (antes se cerraba)');
  ok(/todavía no le avisó a la nube su PIN/.test(sb5.st) && /Actualizar LockSuite/.test(sb5.st), 'B.104) …con el aviso claro y la salida (actualizar): ' + sb5.st.slice(0, 80));
  ok(dialogos.length === 0 && !sb5.modal, 'B.104) …sin carteles bloqueantes ni pedido de PIN: ' + JSON.stringify(dialogos));
  await page.evaluate(() => { window.__fnMode = 'pin_no_publicado'; runCommandOnDevice('dev5', 'BLOCK_WIFI', null, null, null); });
  await sleep(400);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/^✗ Este celular todavía no le avisó a la nube su PIN/.test(st) && !/NOT_ENROLLED/.test(st), 'B.104) un comando devuelve el mensaje claro, no «Error: DEVICE_PIN_NOT_ENROLLED»: ' + st.slice(0, 80));
  ok(!(await page.evaluate(() => !document.getElementById('pin-modal').classList.contains('hidden'))), 'B.104) …y no abre el pedido de PIN');
  await page.evaluate(() => { runCommandOnDevice('dev5', 'UPDATE_LOCKSUITE', null, null, null); });
  await sleep(300);
  const llamUpd = await page.evaluate(() => __calls.filter(c => c.deviceId === 'dev5' && c.command === 'UPDATE_LOCKSUITE').length);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(llamUpd === 1 && !/^✗/.test(st), 'B.104) la actualización de LockSuite sale igual (no pide PIN): ' + st.slice(0, 80));
  await page.evaluate(() => closeDeviceSidebar());
  // dev6: la bandera pública dice que hay PIN pero deviceSecrets no tiene el verificador.
  // Sin `return`: openDeviceSidebar queda esperando que se escriba el PIN y page.evaluate esperaría su promesa para siempre.
  await page.evaluate(() => { openDeviceSidebar('dev6', currentDevicesData['dev6']); });
  await page.waitForSelector('#pin-modal:not(.hidden)', { timeout: 3000 });
  await page.fill('#pin-modal-input', '1234');
  await page.click('#pin-modal-confirm');
  await sleep(500);
  const sb6 = await page.evaluate(() => ({ abierta: !document.getElementById('device-sidebar').classList.contains('hidden'), st: document.getElementById('sidebar-status-msg').textContent, modal: !document.getElementById('pin-modal').classList.contains('hidden') }));
  ok(sb6.abierta && !sb6.modal && /todavía no le avisó a la nube su PIN/.test(sb6.st) && dialogos.length === 0,
    'B.104) bandera vieja sin verificador: tras escribir el PIN la ficha se abre con el aviso (antes: cartel y cierre) ' + JSON.stringify({ dialogos, st: sb6.st.slice(0, 60) }));
  await page.evaluate(() => closeDeviceSidebar());

  // ── 9/11/2026 (auditoría de seguridad): el PIN se verifica en la Cloud Function ──
  // Antes el panel leía `deviceSecrets/<id>` (hash y sal del PIN) y escribía `trustedAdmins` directo en la
  // base; ahora las reglas no dejan ni lo uno ni lo otro, y todo pasa por VERIFY_PIN. La base falsa imita
  // esas reglas (devuelve PERMISSION_DENIED), así que si el panel vuelve a hacerlo, esto se pone en rojo.
  const errAntes = errores.length;
  await page.evaluate(() => { window.__dbLog = []; window.__calls.length = 0; window.__fnMode = 'pin_requerido'; });
  await page.evaluate(() => { openDeviceSidebar('dev7', currentDevicesData['dev7']); });
  await page.waitForSelector('#pin-modal:not(.hidden)', { timeout: 3000 });
  await page.fill('#pin-modal-input', '0000');
  await page.click('#pin-modal-confirm');
  await sleep(400);
  const errPin = await page.evaluate(() => document.getElementById('pin-modal-error').textContent);
  ok(/PIN incorrecto/.test(errPin) && /quedan 2 intentos/.test(errPin),
    'PIN) PIN malo: el modal avisa cuántos intentos quedan antes del bloqueo: ' + errPin);
  ok(await page.evaluate(() => !document.getElementById('pin-modal').classList.contains('hidden')),
    'PIN) …y el modal sigue abierto para volver a intentar');
  await page.fill('#pin-modal-input', '1234');
  await page.check('#pin-modal-remember');
  await page.click('#pin-modal-confirm');
  await sleep(500);
  const verif = await page.evaluate(() => __calls.filter(c => c.deviceId === 'dev7' && c.command === 'VERIFY_PIN').map(c => ({ pin: c.devicePin, rec: c.rememberDevice })));
  ok(verif.length === 2 && verif[0].pin === '0000' && verif[0].rec === false && verif[1].pin === '1234' && verif[1].rec === true,
    'PIN) cada intento sale por la Function (VERIFY_PIN) y «recordar» viaja en el pedido, no se escribe en la base: ' + JSON.stringify(verif));
  ok(await page.evaluate(() => !document.getElementById('device-sidebar').classList.contains('hidden') && selectedDeviceId === 'dev7'),
    'PIN) con el PIN bueno la ficha se abre');
  const acc = await page.evaluate(() => window.__dbLog.slice());
  ok(!acc.some(a => /^\/?deviceSecrets(\/|$)/.test(a.path)),
    'PIN) el panel no lee ni escribe deviceSecrets al verificar el PIN: ' + JSON.stringify(acc.filter(a => /deviceSecrets/.test(a.path))));
  ok(!acc.some(a => (a.op !== 'remove') && (/trustedAdmins/.test(a.path) || JSON.stringify(a.v || '').includes('trustedAdmins'))),
    'PIN) el panel no escribe «trustedAdmins» (lo escribe solo la Function): ' + JSON.stringify(acc.filter(a => /trustedAdmins/.test(a.path))));
  await page.evaluate(() => closeDeviceSidebar());
  // Bloqueo por intentos: la Function ya no deja probar y el modal lo dice (no repite «PIN incorrecto»).
  await page.evaluate(() => { window.__fnMode = 'pin_bloqueado'; });
  await page.evaluate(() => { openDeviceSidebar('dev8', currentDevicesData['dev8']); });
  await page.waitForSelector('#pin-modal:not(.hidden)', { timeout: 3000 });
  await page.fill('#pin-modal-input', '1234');
  await page.click('#pin-modal-confirm');
  await sleep(400);
  const errLock = await page.evaluate(() => ({ t: document.getElementById('pin-modal-error').textContent, abierta: !document.getElementById('device-sidebar').classList.contains('hidden') }));
  ok(/demasiados PIN incorrectos/i.test(errLock.t) && /5 min/.test(errLock.t) && !/^PIN_LOCKED/.test(errLock.t) && !/^PIN incorrecto/.test(errLock.t),
    'PIN) bloqueo por intentos: el modal muestra el motivo y cuánto esperar, sin el código interno: ' + errLock.t);
  ok(!errLock.abierta, 'PIN) …y la ficha NO se abre mientras dura el bloqueo');
  await page.click('#pin-modal-cancel');
  await sleep(200);
  ok(await page.evaluate(() => document.getElementById('device-sidebar').classList.contains('hidden')), 'PIN) cancelar el pedido deja la ficha cerrada');
  // Los únicos errores de consola de este bloque son los que el panel registra a propósito por PIN malo/bloqueado.
  const nuevos = errores.splice(errAntes);
  ok(nuevos.every(e => /Error al verificar PIN/.test(e)), 'PIN) sin otros errores (p. ej. PERMISSION_DENIED de la base): ' + JSON.stringify(nuevos.slice(0, 3)));
  // Restaurar un archivado: la copia de «recordar este celular» NO vuelve (las reglas la rechazarían y el equipo no volvería).
  await page.evaluate(() => { window.confirm = () => true; });
  const rest = await page.evaluate(async () => {
    const btn = document.createElement('button');
    window.__alerts = [];
    window.alert = (m) => window.__alerts.push(m);
    await restoreArchivedDevice('arch1', window.__fakeDb.getAt('archivedDevices/arch1'), btn);
    return { dev: window.__fakeDb.getAt('devices/arch1'), arch: window.__fakeDb.getAt('archivedDevices/arch1'), alerts: window.__alerts };
  });
  ok(rest.dev && rest.dev.deviceName === 'archivado' && rest.arch === null && rest.alerts.length === 0,
    'archivados) restaurar devuelve el equipo y vacía el archivo, sin error: ' + JSON.stringify({ alerts: rest.alerts, dev: !!rest.dev }));
  ok(rest.dev && rest.dev.trustedAdmins === undefined && rest.dev.info && rest.dev.info.trustedAdmins === undefined && rest.dev.info.nota === 'se conserva',
    'archivados) …sin «trustedAdmins» (ni el de la raíz ni el de info/) y conservando el resto de info/: ' + JSON.stringify(rest.dev && rest.dev.info));
  await page.screenshot({ path: 'panel_sidebar.png' });
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(300);
  await page.screenshot({ path: 'panel_sin_fcm.png' });
  ok(errores.length === 0, 'sin errores de consola/página: ' + JSON.stringify(errores.slice(0, 5)));
  await browser.close();
  console.log(fallas ? fallas + ' FALLAS' : 'TODO VERDE');
  process.exit(fallas ? 1 : 0);
})();
