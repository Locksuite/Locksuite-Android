// Pruebas de admin-backend/database.rules.json contra el EMULADOR REAL de Realtime
// Database (22/9/2026). Cómo correrlas: ver README.md de esta carpeta.
//
// Cada `check` dice qué TIENE que pasar. Si alguien cambia las reglas y se rompe algo
// que el celular necesita (o se abre algo que un atacante no debería poder), sale en
// rojo y el proceso termina con código 1.
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { readFileSync } from "fs";
import { fileURLToPath } from "url";
import { ref, get, set, update, remove, serverTimestamp } from "firebase/database";

const rulesPath = process.env.RULES ||
  fileURLToPath(new URL("../../admin-backend/database.rules.json", import.meta.url));
const rules = readFileSync(rulesPath, "utf8");
// `firebase emulators:exec` deja la dirección del emulador en esta variable.
const [emuHost, emuPort] = (process.env.FIREBASE_DATABASE_EMULATOR_HOST || "127.0.0.1:9000").split(":");
const env = await initializeTestEnvironment({
  projectId: "demo-locksuite",
  database: { rules, host: emuHost, port: Number(emuPort) }
});
await env.clearDatabase();
await env.withSecurityRulesDisabled(async (ctx) => {
  const db = ctx.database();
  await set(ref(db, "authorizedAdminsUids/admin1"), true);
  await set(ref(db, "devices/D1"), { ownerUid: "devA", deviceName: "Celu de Eli", fcmToken: "tokA",
    appPolicy: { com_whatsapp: "block" }, appRequests: { com_waze: { status: "approved" } }, allowedPackages: ["com.waze"],
    pendingCommands: { c1: { command: "BLOCK_WIFI", commandId: "c1", timestamp: "1", signature: "x" } } });
  await set(ref(db, "deviceSecrets/D1"), { ownerUid: "devA", commandSecret: "S".repeat(44), pinHash: "h", pinSalt: "s" });
  await set(ref(db, "globalSettings/whitelist/decisions/com_waze"), "allow");
});
const results = []; let rojos = 0;
async function check(name, p, expectOk) {
  let got;
  try { await p; got = true; } catch (e) { got = false; }
  const ok = got === expectOk; if (!ok) rojos++;
  results.push([name, got ? "PERMITIDO" : "DENEGADO", ok ? "ok" : "✗ ROJO"]);
}
const dev = env.authenticatedContext("devA").database();
const devNew = env.authenticatedContext("devNew").database();
const evil = env.authenticatedContext("evil").database();
const adm = env.authenticatedContext("admin1").database();
const anon = env.unauthenticatedContext().database();

// ── lo que el celular legítimo TIENE que poder hacer ──
await check("cel: lee su appPolicy (B.63)", get(ref(dev, "devices/D1/appPolicy")), true);
await check("cel: lee su deviceName (B.27)", get(ref(dev, "devices/D1/deviceName")), true);
await check("cel: lee sus appRequests (B.59)", get(ref(dev, "devices/D1/appRequests")), true);
await check("cel: lee su allowedPackages (tienda)", get(ref(dev, "devices/D1/allowedPackages")), true);
await check("cel: lee su buzón pendingCommands", get(ref(dev, "devices/D1/pendingCommands")), true);
await check("cel: borra una entrada del buzón", remove(ref(dev, "devices/D1/pendingCommands/c1")), true);
await check("cel: latido (update con ownerUid)", update(ref(dev, "devices/D1"), { lastSeen: serverTimestamp(), ownerUid: "devA", "info/ownerUid": "devA" }), true);
await check("cel: escribe un ack (ruta hija)", set(ref(dev, "devices/D1/commandAcks/abc"), { status: "applied", command: "X", timestamp: serverTimestamp() }), true);
await check("cel: publica auditoría (ruta hija)", set(ref(dev, "devices/D1/whitelistAudit"), { a_com: { domain: "a.com", hits: 3 } }), true);
await check("cel: escribe pinHash/pinSalt", update(ref(dev, "deviceSecrets/D1"), { pinHash: "h2", pinSalt: "s2", ownerUid: "devA" }), true);
await check("cel: reescribe su commandSecret (mismo valor)", set(ref(dev, "deviceSecrets/D1/commandSecret"), "S".repeat(44)), true);
await check("cel: lee el catálogo global", get(ref(dev, "globalSettings/whitelist")), true);
await check("cel: NO lee deviceSecrets", get(ref(dev, "deviceSecrets/D1")), false);
await check("cel: NO puede cederse el nodo a otro uid", update(ref(dev, "devices/D1"), { ownerUid: "otro" }), false);
await check("cel: NO puede borrar su nodo", remove(ref(dev, "devices/D1")), false);

// ── alta de un equipo NUEVO ──
await check("alta: crea devices/D2 con su uid", update(ref(devNew, "devices/D2"), { ownerUid: "devNew", lastSeen: serverTimestamp() }), true);
await check("alta: escribe deviceSecrets/D2", update(ref(devNew, "deviceSecrets/D2"), { ownerUid: "devNew", pinHash: "h", pinSalt: "s" }), true);
await check("alta: publica commandSecret D2", set(ref(devNew, "deviceSecrets/D2/commandSecret"), "N".repeat(44)), true);
await check("alta: secretos ANTES que el nodo (D3)", update(ref(devNew, "deviceSecrets/D3"), { ownerUid: "devNew", pinHash: "h", pinSalt: "s" }), true);
await check("alta: después crea devices/D3", update(ref(devNew, "devices/D3"), { ownerUid: "devNew" }), true);

// ── registro de diagnóstico remoto (6/11/2026, B.110): deviceDiag/<id> ──
// El celular dueño de devices/<id> escribe SU registro; nadie más escribe; solo el panel lee.
await check("diag: el celular sube renglones + meta", update(ref(dev, "deviceDiag/D1"), {
  "events/e0000000001": { t: 1, l: "E", g: "vpn", m: "boom" }, "meta/lastSeq": 1, "meta/lastUploadAt": serverTimestamp() }), true);
await check("diag: el celular borra renglones viejos", update(ref(dev, "deviceDiag/D1"), { "events/e0000000001": null }), true);
await check("diag: el celular sube un diagnóstico completo", set(ref(dev, "deviceDiag/D1/snapshots/s00000001"), {
  t: serverTimestamp(), at: 1, system: "x", logcat: "y" }), true);
await check("diag: el celular NO lee su registro (solo el panel)", get(ref(dev, "deviceDiag/D1")), false);
await check("diag: el celular NO escribe el registro de otro equipo", set(ref(dev, "deviceDiag/D2/events/e1"), { t: 1, l: "I", g: "a", m: "x" }), false);
await check("diag ATAQUE: ajeno no escribe en el registro de D1", set(ref(evil, "deviceDiag/D1/events/e9"), { t: 1, l: "E", g: "a", m: "falso" }), false);
await check("diag ATAQUE: ajeno no lee el registro de D1", get(ref(evil, "deviceDiag/D1")), false);
await check("diag ATAQUE: sin sesión no escribe", set(ref(anon, "deviceDiag/D1/events/e9"), { t: 1 }), false);
await check("diag ATAQUE: equipo sin nodo devices (D9) no tiene dueño", set(ref(evil, "deviceDiag/D9/events/e1"), { t: 1 }), false);
await check("diag: el panel lee el registro", get(ref(adm, "deviceDiag/D1")), true);
await check("diag: el panel puede vaciar el registro de un equipo", remove(ref(adm, "deviceDiag/D1/events")), true);

// ── ataques que TIENEN que fallar ──
await check("ATAQUE: secuestrar devices/D1 (fcmToken)", update(ref(evil, "devices/D1"), { ownerUid: "evil", fcmToken: "robado" }), false);
await check("ATAQUE: escribir pinHash de D1", update(ref(evil, "deviceSecrets/D1"), { ownerUid: "evil", pinHash: "x" }), false);
await check("ATAQUE: pisar commandSecret de D1", set(ref(evil, "deviceSecrets/D1/commandSecret"), "X".repeat(44)), false);
await check("ATAQUE: leer devices/D1", get(ref(evil, "devices/D1")), false);
await check("ATAQUE: leer lista devices", get(ref(evil, "devices")), false);
await check("ATAQUE: inyectar comando en buzón de D1", set(ref(evil, "devices/D1/pendingCommands/zz"), { command: "UNBLOCK_WIFI" }), false);
await check("ATAQUE: trustedAdmins en D1", set(ref(evil, "devices/D1/trustedAdmins/evil"), true), false);
await check("ATAQUE: sin sesión, escribir D1", update(ref(anon, "devices/D1"), { ownerUid: null }), false);
await check("ATAQUE: secretos de equipo EXISTENTE ajeno", update(ref(evil, "deviceSecrets/D2"), { ownerUid: "evil", pinHash: "x" }), false);

// ── re-vinculación tras reinstalar (uid nuevo) ──
const devReinst = env.authenticatedContext("devA2").database();
await check("reinstalado: NO escribe con uid nuevo", update(ref(devReinst, "devices/D1"), { ownerUid: "devA2", lastSeen: 1 }), false);
await check("reinstalado: pide re-vincular (deviceClaims)", set(ref(devReinst, "deviceClaims/D1/devA2"), { at: serverTimestamp(), model: "SM-A065M", versionName: "0.6.55" }), true);
await check("claim: no deja claves extra", set(ref(devReinst, "deviceClaims/D1/devA2"), { at: 1, evil: "x" }), false);
await check("claim: no a nombre de otro uid", set(ref(evil, "deviceClaims/D1/devA2"), { at: 1 }), false);
await check("claim: no para equipo inexistente", set(ref(evil, "deviceClaims/NOPE/evil"), { at: 1 }), false);
await check("claim: el celular no lee los pedidos", get(ref(devReinst, "deviceClaims")), false);
await check("admin: lee pedidos de re-vinculación", get(ref(adm, "deviceClaims")), true);
await check("admin: aprueba (ownerUid → uid nuevo)", update(ref(adm, "/"), {
  "devices/D1/ownerUid": "devA2", "devices/D1/info/ownerUid": "devA2", "deviceSecrets/D1/ownerUid": "devA2",
  "deviceSecrets/D1/commandSecret": null, "deviceClaims/D1": null }), true);
await check("reinstalado: ya escribe", update(ref(devReinst, "devices/D1"), { ownerUid: "devA2", lastSeen: 2 }), true);
await check("reinstalado: publica commandSecret nuevo", set(ref(devReinst, "deviceSecrets/D1/commandSecret"), "Z".repeat(44)), true);
await check("uid viejo: ya no escribe", update(ref(dev, "devices/D1"), { ownerUid: "devA", lastSeen: 3 }), false);

// ── panel (admin) ──
await check("admin: escribe appPolicy", set(ref(adm, "devices/D1/appPolicy/com_waze"), "allow"), true);
await check("admin: borra un equipo", remove(ref(adm, "devices/D3")), true);
await check("admin: escribe catálogo global", set(ref(adm, "globalSettings/whitelist/decisions/com_x"), "block"), true);
await check("no-admin: NO escribe catálogo global", set(ref(evil, "globalSettings/whitelist/decisions/com_x"), "allow"), false);

// ── auditoría de seguridad del 9/11/2026 ──
// Los secretos del equipo ya no los lee NINGÚN cliente (ni siquiera un administrador): la Function,
// que usa el Admin SDK, es la única que compara el PIN y firma comandos. Y `trustedAdmins`
// ("recordar este celular", que saltea el PIN) lo escribe solo la Function.
await env.withSecurityRulesDisabled(async (ctx) => {
  const db = ctx.database();
  await set(ref(db, "devices/D4"), { ownerUid: "devD4", deviceName: "Con confiables", trustedAdmins: { admin1: true }, info: { trustedAdmins: { admin1: true } } });
  await set(ref(db, "deviceSecrets/D4"), { ownerUid: "devD4", commandSecret: "Q".repeat(44), pinHash: "h4", pinSalt: "s4", recoveryCode: "0123456789" });
  await set(ref(db, "deviceSecrets/D5"), { ownerUid: "devD5", pinHash: "h5", pinSalt: "s5" });        // sin devices/D5 ni commandSecret
  await set(ref(db, "devices/D6"), { ownerUid: "devD6" });
  await set(ref(db, "deviceSecrets/D6"), { ownerUid: "devD6", pinHash: "h6", pinSalt: "s6" });         // equipo existente, sin commandSecret
  await set(ref(db, "devices/D7"), { ownerUid: "devD7" });                                              // sin verificador
});
const dev4 = env.authenticatedContext("devD4").database();
const dev6 = env.authenticatedContext("devD6").database();
const dev7 = env.authenticatedContext("devD7").database();

// secretos: ningún cliente los lee
await check("secretos: el administrador NO lee deviceSecrets (lista)", get(ref(adm, "deviceSecrets")), false);
await check("secretos: el administrador NO lee deviceSecrets/D4", get(ref(adm, "deviceSecrets/D4")), false);
await check("secretos: el administrador NO lee el hash del PIN", get(ref(adm, "deviceSecrets/D4/pinHash")), false);
await check("secretos: el administrador NO lee el commandSecret", get(ref(adm, "deviceSecrets/D4/commandSecret")), false);
await check("secretos: el administrador NO lee el código de recuperación", get(ref(adm, "deviceSecrets/D4/recoveryCode")), false);
await check("secretos: el celular dueño NO lee los suyos", get(ref(dev4, "deviceSecrets/D4")), false);
await check("secretos: un ajeno NO lee", get(ref(evil, "deviceSecrets/D4/pinHash")), false);
await check("secretos: sin sesión NO lee", get(ref(anon, "deviceSecrets/D4")), false);
await check("pinThrottle: ni el administrador lo lee", get(ref(adm, "pinThrottle")), false);
await check("pinThrottle: ni el administrador lo escribe", set(ref(adm, "pinThrottle/D4/admin1"), { fails: 0 }), false);
await check("pinThrottle: ni el celular lo escribe", set(ref(dev4, "pinThrottle/D4/admin1"), null), false);

// el verificador del PIN: lo publica el celular; un administrador no lo reemplaza
await check("PIN: el celular dueño publica un verificador nuevo", update(ref(dev4, "deviceSecrets/D4"), { pinHash: "h4b", pinSalt: "s4b", ownerUid: "devD4" }), true);
await check("PIN ATAQUE: un administrador NO reemplaza pinHash+pinSalt", update(ref(adm, "deviceSecrets/D4"), { pinHash: "mio", pinSalt: "mia" }), false);
await check("PIN ATAQUE: un administrador NO cambia solo pinHash", set(ref(adm, "deviceSecrets/D4/pinHash"), "mio"), false);
await check("PIN ATAQUE: un administrador NO crea un verificador en un equipo que no lo tiene (D7)", set(ref(adm, "deviceSecrets/D7"), { ownerUid: "devD7", pinHash: "mio", pinSalt: "mia" }), false);
await check("PIN ATAQUE: un administrador NO crea el verificador con una escritura raíz", update(ref(adm, "/"), { "deviceSecrets/D7/pinHash": "mio", "deviceSecrets/D7/pinSalt": "mia" }), false);
await check("PIN ATAQUE: un administrador NO pisa el código de recuperación", set(ref(adm, "deviceSecrets/D4/recoveryCode"), "1111111111"), false);
await check("PIN: un administrador SÍ re-vincula (ownerUid + borra commandSecret) sin tocar el PIN", update(ref(adm, "/"), { "deviceSecrets/D4/ownerUid": "devD4n", "devices/D4/ownerUid": "devD4n", "deviceSecrets/D4/commandSecret": null }), true);
await check("PIN: un administrador SÍ borra los secretos al eliminar un equipo", remove(ref(adm, "deviceSecrets/D5")), true);
await check("PIN ATAQUE: ... y no puede reponerlos con otro verificador", set(ref(adm, "deviceSecrets/D5"), { ownerUid: "devD5", pinHash: "mio", pinSalt: "mia" }), false);

// commandSecret: lo crea el dueño, no un extraño
await check("commandSecret ATAQUE: un extraño NO lo crea en un equipo existente que no lo tiene", set(ref(evil, "deviceSecrets/D6/commandSecret"), "E".repeat(44)), false);
await check("commandSecret: el dueño sí lo publica", set(ref(dev6, "deviceSecrets/D6/commandSecret"), "D".repeat(44)), true);
await check("commandSecret: el administrador puede borrarlo (Re-vincular canal)", remove(ref(adm, "deviceSecrets/D6/commandSecret")), true);
await check("commandSecret: un equipo que todavía NO existe en devices/ puede publicarlo (alta)", set(ref(env.authenticatedContext("devNew2").database(), "deviceSecrets/D8/commandSecret"), "N".repeat(44)), true);

// "recordar este celular": solo la Function (Admin SDK, que no pasa por estas reglas)
await check("trustedAdmins ATAQUE: un administrador NO se pone de confiable", set(ref(adm, "devices/D7/trustedAdmins/admin1"), true), false);
await check("trustedAdmins ATAQUE: ... ni en info/", set(ref(adm, "devices/D7/info/trustedAdmins/admin1"), true), false);
await check("trustedAdmins ATAQUE: ... ni con un update raíz", update(ref(adm, "/"), { "devices/D7/trustedAdmins/admin1": true }), false);
await check("trustedAdmins ATAQUE: ... ni metiéndolo en un set del equipo entero (restaurar archivado)", set(ref(adm, "devices/D7"), { ownerUid: "devD7", trustedAdmins: { admin1: true } }), false);
await check("trustedAdmins ATAQUE: ... ni como valor suelto", set(ref(adm, "devices/D7/trustedAdmins"), { admin1: true, otro: true }), false);
await check("trustedAdmins ATAQUE: el celular dueño NO se lo pone a un uid", set(ref(dev7, "devices/D7/trustedAdmins/admin1"), true), false);
await check("trustedAdmins ATAQUE: un extraño tampoco", set(ref(evil, "devices/D7/trustedAdmins/evil"), true), false);
await check("trustedAdmins: restaurar un equipo archivado SIN confiables funciona", set(ref(adm, "devices/D9"), { ownerUid: "devD9", deviceName: "Restaurado", model: "X", info: { model: "X" } }), true);
await check("trustedAdmins: el administrador SÍ puede revocar la confianza (borrar)", remove(ref(adm, "devices/D4/trustedAdmins/admin1")), true);
await check("trustedAdmins: ... y la copia de info/", remove(ref(adm, "devices/D4/info/trustedAdmins/admin1")), true);
await check("trustedAdmins: el celular sigue escribiendo el resto de su info", update(ref(dev7, "devices/D7"), { "info/model": "SM-A065M", ownerUid: "devD7", lastSeen: serverTimestamp() }), true);

console.table(results);
console.log(rojos === 0 ? "TODAS VERDES (" + results.length + ")" : "ROJAS: " + rojos);
await env.cleanup();
process.exit(rojos === 0 ? 0 : 1);
