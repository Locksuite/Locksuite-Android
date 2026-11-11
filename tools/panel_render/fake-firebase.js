// Firebase compat FALSO, en memoria, para renderizar el panel sin red (receta de B.65).
(function () {
  const TS = { ".sv": "timestamp" };
  const root = { v: (window.__SEED__ && JSON.parse(JSON.stringify(window.__SEED__))) || {} };
  const listeners = []; // {path, cb, event}
  const split = p => String(p || "").split("/").filter(Boolean);
  function getAt(path) {
    let n = root.v;
    for (const k of split(path)) { if (n == null || typeof n !== "object") return null; n = n[k]; }
    return n === undefined ? null : n;
  }
  function resolveTs(v) {
    if (v && typeof v === "object") {
      if (v[".sv"] === "timestamp") return Date.now();
      const o = Array.isArray(v) ? [] : {};
      for (const k of Object.keys(v)) o[k] = resolveTs(v[k]);
      return o;
    }
    return v;
  }
  function setAt(path, val) {
    const ks = split(path);
    if (!ks.length) { root.v = resolveTs(val) || {}; return; }
    let n = root.v;
    for (let i = 0; i < ks.length - 1; i++) {
      if (n[ks[i]] == null || typeof n[ks[i]] !== "object") n[ks[i]] = {};
      n = n[ks[i]];
    }
    const last = ks[ks.length - 1];
    if (val === null || val === undefined) delete n[last]; else n[last] = resolveTs(val);
  }
  function snap(path) {
    const v = getAt(path);
    const ks = split(path);
    return {
      key: ks.length ? ks[ks.length - 1] : null,
      val: () => (v == null ? null : JSON.parse(JSON.stringify(v))),
      exists: () => v != null,
      forEach: (fn) => { if (v && typeof v === "object") for (const k of Object.keys(v)) if (fn(snap(path + "/" + k)) === true) return true; return false; },
      child: (p) => snap(path + "/" + p),
      hasChildren: () => !!(v && typeof v === "object" && Object.keys(v).length)
    };
  }
  function notify(changedPath) {
    const c = split(changedPath).join("/");
    for (const l of listeners.slice()) {
      const p = split(l.path).join("/");
      if (c === p || c.startsWith(p + "/") || p.startsWith(c + "/") || p === "" ) {
        try { l.cb(snap(l.path)); } catch (e) { console.error("listener error", e); }
      }
    }
  }
  function ref(path) {
    path = split(path).join("/");
    // 9/11/2026 (auditoría de seguridad) — las reglas nuevas de database.rules.json, imitadas:
    //   • NINGÚN cliente lee `deviceSecrets` (solo la Function, con el Admin SDK);
    //   • NINGÚN cliente escribe `trustedAdmins` / `info/trustedAdmins` (borrar sí; crear, no).
    // Si el panel vuelve a hacer cualquiera de las dos, la base falsa devuelve PERMISSION_DENIED igual
    // que la real y la prueba se pone en rojo. `window.__dbLog` anota cada lectura/escritura.
    const log = (op, p, v) => { (window.__dbLog = window.__dbLog || []).push({ op, path: p, v }); };
    const readDenied = () => split(path)[0] === "deviceSecrets";
    const hasTrusted = (p, v) => {
      if (v === null || v === undefined) return false;
      // Igual que database.rules.json: solo `devices/<id>/trustedAdmins` y `devices/<id>/info/trustedAdmins`.
      // (`archivedDevices/<id>` guarda la copia entera del equipo, trustedAdmins incluido, y eso sí se puede escribir.)
      if (/^\/?devices\/[^/]+\/(info\/)?trustedAdmins(\/|$)/.test(p)) return true;
      if (typeof v === "object") return Object.keys(v).some((k) => hasTrusted(p + "/" + k, v[k]));
      return false;
    };
    const denyErr = (what) => Object.assign(new Error("PERMISSION_DENIED: " + what + " at /" + path), { code: "PERMISSION_DENIED" });
    const r = {
      key: split(path).slice(-1)[0] || null,
      path,
      child: (p) => ref(path + "/" + p),
      on: (ev, cb, err) => {
        log("on", path);
        // 6/11/2026 (B.110): `window.__denyPaths = ["deviceDiag"]` imita las reglas de la base
        // negando la lectura (el oyente se cancela y avisa por el callback de error, como el real).
        if (readDenied() || (window.__denyPaths || []).some((d) => path === d || path.startsWith(d + "/"))) {
          setTimeout(() => { if (err) err({ code: "PERMISSION_DENIED", message: "permission_denied at /" + path + ": Client doesn't have permission to access the desired data." }); }, 0);
          return cb;
        }
        listeners.push({ path, cb, ev }); setTimeout(() => cb(snap(path)), 0); return cb;
      },
      off: (ev, cb) => { for (let i = listeners.length - 1; i >= 0; i--) if (listeners[i].path === path && (!cb || listeners[i].cb === cb)) listeners.splice(i, 1); },
      once: (ev) => { log("read", path); return readDenied() ? Promise.reject(denyErr("read")) : Promise.resolve(snap(path)); },
      set: (v) => { log("set", path, v); if (hasTrusted(path, v)) return Promise.reject(denyErr("write trustedAdmins")); setAt(path, v); notify(path); return Promise.resolve(); },
      update: (obj) => { log("update", path, obj); if (Object.keys(obj).some((k) => hasTrusted(path + "/" + k, obj[k]))) return Promise.reject(denyErr("write trustedAdmins")); for (const k of Object.keys(obj)) setAt(path + "/" + k, obj[k]); notify(path); return Promise.resolve(); },
      remove: () => { log("remove", path); setAt(path, null); notify(path); return Promise.resolve(); },
      push: (v) => { log("push", path, v); const k = "p" + Math.random().toString(36).slice(2, 10); setAt(path + "/" + k, v); notify(path); return Promise.resolve(ref(path + "/" + k)); },
      orderByChild: () => r, limitToLast: () => r, equalTo: () => r
    };
    return r;
  }
  const user = { uid: "admin1", email: "admin@test", getIdToken: () => Promise.resolve("TOKEN") };
  const authObj = {
    currentUser: user,
    onAuthStateChanged: (cb) => { setTimeout(() => cb(user), 0); return () => {}; },
    signOut: () => Promise.resolve(), signInWithEmailAndPassword: () => Promise.resolve(),
    getRedirectResult: () => Promise.resolve(null), signInWithPopup: () => Promise.resolve(), signInWithRedirect: () => Promise.resolve()
  };
  const db = { ref };
  window.firebase = {
    initializeApp: () => ({}),
    apps: [1],
    auth: Object.assign(() => authObj, { GoogleAuthProvider: function () {} }),
    database: Object.assign(() => db, { ServerValue: { TIMESTAMP: TS } }),
    functions: () => ({ httpsCallable: () => () => Promise.resolve({ data: {} }) })
  };
  // Acceso para la prueba
  window.__fakeDb = { getAt, setAt: (p, v) => { setAt(p, v); notify(p); }, listeners };
})();
