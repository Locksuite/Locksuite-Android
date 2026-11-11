// Cloud Function FALSA (sendCommandV8) para la prueba: escribe en la base falsa lo mismo
// que la real (ack "sent" + copia en el buzón) y contesta según window.__fnMode.
(function () {
  const realFetch = window.fetch;
  window.__fnMode = "fcm_old";
  window.__calls = [];
  window.fetch = async (url, opts) => {
    // Tienda Play (B.101): «Verificar en Play». `window.__playTable` dice qué contesta Play por
    // paquete ({status, title, iconUrl}); lo que no está en la tabla se contesta como «missing».
    // `window.__playMode = "down"` imita una función no desplegada (HTTP 404).
    if (String(url).includes("checkplayapps")) {
      const body = JSON.parse(opts.body);
      window.__checkCalls = (window.__checkCalls || []).concat([body]);
      if (window.__playMode === "down") {
        return new Response(JSON.stringify({ error: "Not Found" }), { status: 404, headers: { "Content-Type": "application/json" } });
      }
      const table = window.__playTable || {};
      const results = body.packages.map((pk) => Object.assign({ packageName: pk, status: "missing" }, table[pk] || {}));
      return new Response(JSON.stringify({ success: true, results }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    if (String(url).includes("sendcommandv8")) {
      const body = JSON.parse(opts.body);
      window.__calls.push(body);
      // Puerta del PIN de la Function real (B.104): salvo UPDATE_LOCKSUITE, UPDATE_APP,
      // CANCEL_UPDATE_APP y (6/11/2026, B.110) COLLECT_DIAGNOSTICS —de solo lectura—, todo
      // comando pasa por verifyDevicePin ANTES de escribir nada.
      //   "pin_no_publicado": la nube no tiene el verificador → 412 DEVICE_PIN_NOT_ENROLLED.
      //   "pin_requerido":    tiene verificador (PIN "1234") → 412 PIN_REQUIRED sin PIN, 403 PIN_INCORRECT si no coincide.
      // Pasada la puerta se contesta como "fcm_new".
      const EXENTOS = ["UPDATE_LOCKSUITE", "UPDATE_APP", "CANCEL_UPDATE_APP", "COLLECT_DIAGNOSTICS"];
      const modo0 = window.__fnMode;
      // 9/11/2026 (auditoría): lo que la Function real contesta ahora en dos casos nuevos.
      //   "sesion_revocada": 401 SESSION_REVOKED (el ID token se verifica con checkRevoked).
      //   "pin_bloqueado":   429 PIN_LOCKED (demasiados PIN incorrectos de esta cuenta para ese celular).
      const respuesta = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
      if (modo0 === "sesion_revocada") return respuesta(401, { error: "SESSION_REVOKED: tu sesión fue revocada o la cuenta está deshabilitada. Cerrá sesión y volvé a entrar." });
      if (modo0 === "pin_bloqueado" && !EXENTOS.includes(body.command)) return respuesta(429, { error: "PIN_LOCKED: demasiados PIN incorrectos para este celular desde tu cuenta. Probá de nuevo en 5 min.", retryAfterSec: 300 });
      if ((modo0 === "pin_no_publicado" || modo0 === "pin_requerido") && !EXENTOS.includes(body.command)) {
        const fallo = (status, error) => new Response(JSON.stringify({ error }), { status, headers: { "Content-Type": "application/json" } });
        if (modo0 === "pin_no_publicado") return fallo(412, "DEVICE_PIN_NOT_ENROLLED");
        if (!body.devicePin) return fallo(412, "PIN_REQUIRED");
        if (body.devicePin !== "1234") return respuesta(403, { error: "PIN_INCORRECT", attemptsLeft: 2 });
      }
      const cid = "c" + Math.random().toString(36).slice(2, 8);
      window.__lastCid = cid;
      const d = "devices/" + body.deviceId;
      __fakeDb.setAt(d + "/commandAcks/" + cid, { status: "sent", command: body.command, timestamp: Date.now() });
      __fakeDb.setAt(d + "/pendingCommands/" + cid, { data: { command: body.command, commandId: cid, timestamp: String(Date.now()), signature: "s" }, family: body.command, queuedAt: Date.now(), expiresAt: Date.now() + 86400000 });
      const m = (modo0 === "pin_no_publicado" || modo0 === "pin_requerido") ? "fcm_new" : modo0;
      let resp;
      if (m === "fcm_old") resp = { success: true, commandId: cid, queued: true };
      else if (m === "fcm_new") resp = { success: true, commandId: cid, queued: true, delivered: true, channel: "fcm" };
      else if (m === "notoken_old") resp = { success: true, commandId: cid, queued: true, fcmError: "NO_FCM_TOKEN" };
      else if (m === "notoken_new") resp = { success: true, commandId: cid, queued: true, delivered: false, channel: "buzon", fcmError: "NO_FCM_TOKEN" };
      if (m.startsWith("notoken")) __fakeDb.setAt(d + "/commandAcks/" + cid, { status: "queued", command: body.command, timestamp: Date.now(), reason: "sin token" });
      return new Response(JSON.stringify(resp), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    return realFetch(url, opts);
  };
  // "El celular aplica": ack applied, se borra la copia del buzón y (opcional) reporta el estado.
  window.__deviceApplies = (deviceId, cid, campo, valor, status) => {
    const d = "devices/" + deviceId;
    const ack = __fakeDb.getAt(d + "/commandAcks/" + cid) || {};
    __fakeDb.setAt(d + "/commandAcks/" + cid, { status: status || "applied", command: ack.command, timestamp: Date.now(), reason: status === "rejected" ? "BAD_SIGNATURE" : undefined });
    __fakeDb.setAt(d + "/pendingCommands/" + cid, null);
    if (campo) __fakeDb.setAt(d + "/" + campo, valor);
  };
})();
