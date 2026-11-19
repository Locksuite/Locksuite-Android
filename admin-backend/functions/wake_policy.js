"use strict";
/**
 * 19/11/2026 (B.125, T3.1) — LATIDO INVERSO: A QUÉ EQUIPOS SE LES MANDA UN FCM DE DESPERTAR.
 *
 * Lógica PURA (sin Firebase), para probarla con `tools/test_wake_policy.js`. La función
 * programada `despertarEquiposSilenciosos` (index.js) solo lee la base, llama a
 * `pickDevicesToWake` y manda los mensajes.
 *
 * Por qué existe: en el Qin F21 de Iejiel LockSuite estuvo muerto 52 h y 4,5 h después de un ANR
 * y nada lo despertó. Un FCM de prioridad alta crea el proceso aunque esté muerto; el celular
 * (LockSuiteFirebaseService) vuelve a levantar el Watchdog y la VPN y avisa que está vivo.
 *
 * REGLAS (no «simplificar»):
 *  • Solo equipos con `lastSeen` entre hace 7 días y hace 25 min. Más de 7 días sin señal es un
 *    equipo apagado o entregado: despertarlo cada 15 min para siempre es gastar mensajes.
 *  • Tope de 1 mensaje por equipo cada 15 min y de 24 por día (se reinicia por día UTC).
 *  • El mensaje NO es un comando: `{ wake: "1" }`, sin firma ni PIN, y el celular no ejecuta
 *    ninguna acción del panel por recibirlo.
 */

const WAKE = Object.freeze({
  SILENCE_MS: 25 * 60 * 1000,
  MAX_AGE_MS: 7 * 24 * 3600 * 1000,
  MIN_GAP_MS: 15 * 60 * 1000,
  MAX_PER_DAY: 24,
  DAY_MS: 24 * 3600 * 1000,
});

function dayKey(now) {
  return Math.floor(now / WAKE.DAY_MS);
}

function field(dev, name) {
  if (dev && dev[name] !== undefined && dev[name] !== null) return dev[name];
  if (dev && dev.info && dev.info[name] !== undefined && dev.info[name] !== null) return dev.info[name];
  return null;
}

/**
 * @param {Object<string, Object>} devices contenido de `devices` (id → ficha).
 * @param {number} now milisegundos.
 * @returns {Array<{id:string, token:string, wake:{lastSentAt:number, count:number, day:number}}>}
 *   `wake` es el registro que hay que guardar en `devices/<id>/wake` si el mensaje salió.
 */
function pickDevicesToWake(devices, now) {
  const out = [];
  if (!devices || typeof devices !== "object") return out;
  const today = dayKey(now);
  for (const id of Object.keys(devices)) {
    const dev = devices[id];
    if (!dev || typeof dev !== "object") continue;
    const lastSeen = field(dev, "lastSeen");
    if (typeof lastSeen !== "number" || !isFinite(lastSeen)) continue;
    const age = now - lastSeen;
    if (age < WAKE.SILENCE_MS || age > WAKE.MAX_AGE_MS) continue;
    const token = field(dev, "fcmToken");
    if (typeof token !== "string" || token.length < 20) continue;

    const w = dev.wake && typeof dev.wake === "object" ? dev.wake : {};
    const lastSentAt = typeof w.lastSentAt === "number" ? w.lastSentAt : 0;
    if (lastSentAt && now - lastSentAt < WAKE.MIN_GAP_MS) continue;
    const countToday = w.day === today && typeof w.count === "number" ? w.count : 0;
    if (countToday >= WAKE.MAX_PER_DAY) continue;

    out.push({ id, token, wake: { lastSentAt: now, count: countToday + 1, day: today } });
  }
  return out;
}

module.exports = { WAKE, dayKey, pickDevicesToWake };
