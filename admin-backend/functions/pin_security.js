"use strict";
/**
 * Seguridad del PIN del dispositivo, del lado del SERVIDOR (9/11/2026, auditoría de seguridad).
 *
 * Este módulo no importa firebase-admin a propósito: son funciones puras (más una que recibe la
 * referencia a la base por parámetro) para poder probarlas con `node` sin instalar nada.
 * `index.js` las usa; `tools/test_pin_security.js` las prueba.
 *
 * QUÉ RESUELVE
 *  1. Comparación en tiempo constante (`crypto.timingSafeEqual`). Antes era `!==` entre strings.
 *  2. Límite de intentos por (equipo, administrador). Antes la Function dejaba probar PINs sin
 *     ningún freno: con una cuenta de administrador, 10.000 pedidos recorrían un PIN de 4 dígitos.
 *
 * QUÉ NO HACE (a propósito)
 *  El formato del verificador NO cambia: sigue siendo SHA-256(sal ‖ PIN), igual que en
 *  PinManager.kt. Se evaluó pasarlo a PBKDF2 y se descartó: con un PIN corto (4 dígitos son 10^4
 *  combinaciones) las iteraciones solo encarecen un ataque contra un hash filtrado, no lo evitan.
 *  Lo que de verdad protege es que NADIE pueda leer el verificador (database.rules.json ya no deja
 *  que ningún cliente lea `deviceSecrets`) y que los intentos de adivinar el PIN estén limitados
 *  (abajo). Cambiar el formato además obligaría a tocar el celular y a migrar los PIN ya guardados,
 *  con riesgo de dejar a alguien sin poder entrar a su propio equipo.
 */
const crypto = require("crypto");

const SALT_BYTES = 16;
const MAX_PIN_LENGTH = 64;              // el PIN real tiene 4 a 16 dígitos; el resto es ruido que no se calcula

/** SHA-256(sal ‖ PIN) en base64: el esquema de siempre (igual que PinManager.kt). */
function hashPinLegacy(pin, saltBase64) {
  const hash = crypto.createHash("sha256");
  hash.update(Buffer.from(String(saltBase64), "base64"));
  hash.update(Buffer.from(String(pin), "utf8"));
  return hash.digest("base64");
}

/** Comparación en tiempo constante de dos strings. Si el largo difiere, igual hace un compare de relleno. */
function constantTimeEquals(a, b) {
  const ba = Buffer.from(String(a), "utf8");
  const bb = Buffer.from(String(b), "utf8");
  if (ba.length !== bb.length) {
    crypto.timingSafeEqual(ba, ba);
    return false;
  }
  return crypto.timingSafeEqual(ba, bb);
}

/**
 * @param {{pinHash:string, pinSalt:string}} record  verificador guardado en deviceSecrets/<id>
 * @returns {boolean} true si `pin` es el PIN del registro
 */
function verifyPinRecord(record, pin) {
  if (!record || typeof record.pinHash !== "string" || typeof record.pinSalt !== "string") return false;
  if (typeof pin !== "string" || pin.length === 0 || pin.length > MAX_PIN_LENGTH) return false;
  return constantTimeEquals(hashPinLegacy(pin, record.pinSalt), record.pinHash);
}

/** Registro nuevo para un PIN (lo usa CHANGE_PIN): sal aleatoria + SHA-256(sal ‖ PIN). */
function newPinRecord(pin) {
  const pinSalt = crypto.randomBytes(SALT_BYTES).toString("base64");
  return { pinHash: hashPinLegacy(pin, pinSalt), pinSalt };
}

// ── Límite de intentos ──────────────────────────────────────────────────────
//
// Estado por (equipo, administrador) en `pinThrottle/<deviceId>/<adminUid>` = { fails, last, lockUntil }.
// Ese nodo NO tiene reglas en database.rules.json: ningún cliente lo lee ni lo escribe, solo el Admin SDK.
//
// Se RESERVA el intento ANTES de calcular el hash (transacción) y se borra el estado si el PIN era
// bueno. Contarlo después del cálculo deja una carrera: 80 pedidos en paralelo leen "0 fallos", los
// 80 prueban un PIN distinto y recién después se anotan. Con la reserva previa, la transacción
// serializa los pedidos y el noveno ya encuentra el bloqueo.
//
// 8 intentos libres (alcanza para un administrador que se equivoca al tipear y para ráfagas de
// comandos legítimos en paralelo, que cuentan un intento cada uno hasta que el acierto los borra);
// el octavo deja puesto un bloqueo de 5 min y cada intento siguiente lo DUPLICA (10, 20, 40, y
// tope de 1 h). Un acierto lo borra todo. Si pasan 24 h sin intentos el contador vuelve a cero.
// Para quien adivina: 8 intentos de entrada y después uno cada 5, 10, 20 y 40 min, y luego uno por hora.
const THROTTLE_FREE_ATTEMPTS = 8;
const THROTTLE_BASE_LOCK_MS = 5 * 60 * 1000;
const THROTTLE_MAX_LOCK_MS = 60 * 60 * 1000;
const THROTTLE_RESET_MS = 24 * 60 * 60 * 1000;

/**
 * Decide si se deja intentar ahora y cuál es el estado nuevo. Pura.
 * @returns {{allowed:boolean, state:object|null, retryAfterMs:number, attemptsLeft:number}}
 */
function throttleAttempt(state, now) {
  if (state && Number(state.lockUntil) > now) {
    return { allowed: false, state, retryAfterMs: Number(state.lockUntil) - now, attemptsLeft: 0 };
  }
  const vigente = state && Number.isFinite(Number(state.last)) && (now - Number(state.last)) <= THROTTLE_RESET_MS;
  const fails = (vigente ? Math.max(0, Math.floor(Number(state.fails) || 0)) : 0) + 1;
  let lockUntil = 0;
  if (fails >= THROTTLE_FREE_ATTEMPTS) {
    // 2^(fails-FREE) crece rápido: se corta el exponente antes de que sea un número absurdo.
    const exp = Math.min(fails - THROTTLE_FREE_ATTEMPTS, 10);
    lockUntil = now + Math.min(THROTTLE_BASE_LOCK_MS * Math.pow(2, exp), THROTTLE_MAX_LOCK_MS);
  }
  return {
    allowed: true,
    state: { fails, last: now, lockUntil },
    retryAfterMs: 0,
    attemptsLeft: Math.max(0, THROTTLE_FREE_ATTEMPTS - fails),
  };
}

/**
 * Reserva un intento en la base (transacción). `ref` es una Reference de RTDB (o un doble de prueba
 * con el mismo `transaction(fn, cb, applyLocally)`).
 * @returns {Promise<{allowed:boolean, retryAfterMs:number, attemptsLeft:number}>}
 */
async function reservePinAttempt(ref, now) {
  let decision = { allowed: false, retryAfterMs: 0, attemptsLeft: 0 };
  await ref.transaction((current) => {
    const r = throttleAttempt(current, now);
    decision = r;
    if (!r.allowed) return undefined;   // abortar: no se escribe nada
    return r.state;
  }, undefined, false);
  return { allowed: decision.allowed, retryAfterMs: decision.retryAfterMs, attemptsLeft: decision.attemptsLeft };
}

module.exports = {
  SALT_BYTES, MAX_PIN_LENGTH,
  THROTTLE_FREE_ATTEMPTS, THROTTLE_BASE_LOCK_MS, THROTTLE_MAX_LOCK_MS, THROTTLE_RESET_MS,
  hashPinLegacy, constantTimeEquals, verifyPinRecord, newPinRecord,
  throttleAttempt, reservePinAttempt,
};
