// Evaluador de reglas de Realtime Database SIN emulador (9/11/2026).
//
// ⚠️ NO reemplaza al emulador real (`npm test` en tools/rules_tests). Existe porque en un
// contenedor sin red no se puede instalar firebase-tools: sirve para no desplegar reglas a ciegas.
// Imita la semántica documentada de RTDB para el subconjunto de reglas que usa este proyecto:
//
//   • `.read` / `.write` CASCADAN hacia abajo: alcanza con que UNA regla del camino (raíz → nodo)
//     dé verdadero, y una regla hija no puede quitar lo que dio una regla del padre.
//   • `update()` se evalúa camino por camino (cada clave es una escritura distinta), pero
//     `newData` es el dato COMPLETO que queda tras toda la operación (atómica).
//   • `.validate` NO cascada: se evalúa en el nodo escrito y en cada descendiente del dato nuevo
//     que tenga regla; no se evalúa en un borrado (valor null). Con VALIDATE_ANCESTORS=1 también
//     se evalúa en los ANCESTROS del camino escrito, para comprobar que las reglas nuevas son
//     correctas bajo cualquiera de las dos lecturas posibles de la documentación.
//   • Variables `$x` en las reglas, `auth` (null si no hay sesión), `root`, `data`, `newData`,
//     `now`; métodos val/exists/child/parent/hasChild/hasChildren/isString/isNumber/isBoolean.
//
// Se usa con el MISMO test_rules.mjs que el emulador real: register.mjs redirige
// `@firebase/rules-unit-testing` y `firebase/database` a este archivo.
// Calibración (9/11/2026, con las 90 comprobaciones de hoy; las dos lecturas de .validate dan lo mismo):
//   • reglas de `fd76ccc` (anteriores a B.73): 40 en rojo = las 17 que anota el README (B.73)
//     + 5 de `diag:` (B.110) + 18 de B.113;
//   • reglas de `cbdaa32` (vigentes antes de la auditoría de B.113): solo las 18 de B.113 en rojo;
//   • reglas nuevas: 90 verdes.
// Si alguna de estas tres cuentas cambia sin motivo, el evaluador o las reglas se rompieron.

const VALIDATE_ANCESTORS = process.env.VALIDATE_ANCESTORS === "1";
const TS = Symbol("serverTimestamp");

const clone = (v) => (v === undefined ? null : JSON.parse(JSON.stringify(v)));
const segsOf = (p) => String(p).split("/").filter((s) => s.length > 0);

// ── árbol de datos ──────────────────────────────────────────────────────────
function getAt(tree, segs) {
  let cur = tree;
  for (const s of segs) {
    if (cur === null || typeof cur !== "object" || !(s in cur)) return null;
    cur = cur[s];
  }
  return cur === undefined ? null : cur;
}
function prune(v) {
  if (v === null || v === undefined) return null;
  if (typeof v !== "object") return v;
  const out = {};
  for (const k of Object.keys(v)) {
    const x = prune(v[k]);
    if (x !== null) out[k] = x;
  }
  return Object.keys(out).length ? out : null;
}
function setAt(tree, segs, value) {
  value = prune(value);
  if (segs.length === 0) return value;
  const root = tree && typeof tree === "object" ? tree : {};
  let cur = root;
  for (let i = 0; i < segs.length - 1; i++) {
    if (cur[segs[i]] === null || typeof cur[segs[i]] !== "object" || cur[segs[i]] === undefined) cur[segs[i]] = {};
    cur = cur[segs[i]];
  }
  const last = segs[segs.length - 1];
  if (value === null) delete cur[last]; else cur[last] = value;
  return prune(root);
}
function resolveTs(v) {
  if (v === TS) return Date.now();
  if (v && typeof v === "object") { const o = {}; for (const k of Object.keys(v)) o[k] = resolveTs(v[k]); return o; }
  return v;
}

// ── snapshot que ven las reglas ─────────────────────────────────────────────
class RuleSnap {
  constructor(tree, segs) { this.tree = tree; this.segs = segs; }
  _v() { return getAt(this.tree, this.segs); }
  val() { const v = this._v(); return v !== null && typeof v === "object" ? clone(v) : v; }
  exists() { return this._v() !== null; }
  child(p) { return new RuleSnap(this.tree, this.segs.concat(segsOf(p))); }
  parent() { return new RuleSnap(this.tree, this.segs.slice(0, -1)); }
  hasChild(n) { const v = this._v(); return !!v && typeof v === "object" && n in v; }
  hasChildren(list) {
    const v = this._v();
    if (!v || typeof v !== "object") return false;
    return Array.isArray(list) ? list.every((n) => n in v) : Object.keys(v).length > 0;
  }
  isString() { return typeof this._v() === "string"; }
  isNumber() { return typeof this._v() === "number"; }
  isBoolean() { return typeof this._v() === "boolean"; }
  getPriority() { return null; }
}

// ── reglas ──────────────────────────────────────────────────────────────────
// Devuelve, para cada prefijo del camino, el nodo de reglas que le corresponde (o null si ya no hay).
function ruleChain(rules, segs) {
  const chain = [{ node: rules.rules, vars: {} }];
  let node = rules.rules, vars = {};
  for (const s of segs) {
    if (!node) { chain.push({ node: null, vars }); continue; }
    let next = null;
    if (Object.prototype.hasOwnProperty.call(node, s) && !s.startsWith(".")) next = node[s];
    else {
      const wild = Object.keys(node).find((k) => k.startsWith("$"));
      if (wild) { next = node[wild]; vars = { ...vars, [wild]: s }; }
    }
    node = next && typeof next === "object" ? next : null;
    chain.push({ node, vars });
  }
  return chain;
}
function evalRule(expr, ctx, vars) {
  if (typeof expr === "boolean") return expr;
  if (expr === undefined) return false;
  const names = Object.keys(vars);
  // eslint-disable-next-line no-new-func
  const f = new Function("auth", "root", "data", "newData", "now", ...names, `"use strict"; return (${expr});`);
  try {
    return f(ctx.auth, ctx.root, ctx.data, ctx.newData, Date.now(), ...names.map((n) => vars[n])) === true;
  } catch (e) {
    return false;   // una regla que lanza se trata como falsa, igual que la base
  }
}
const mkCtx = (auth, before, after, segs) => ({
  auth, root: new RuleSnap(before, []), data: new RuleSnap(before, segs), newData: new RuleSnap(after, segs),
});

function allowedRead(rules, auth, store, segs) {
  const chain = ruleChain(rules, segs);
  for (let d = 0; d < chain.length; d++) {
    const n = chain[d].node;
    if (n && n[".read"] !== undefined && evalRule(n[".read"], mkCtx(auth, store, store, segs.slice(0, d)), chain[d].vars)) return true;
  }
  return false;
}
function allowedWrite(rules, auth, before, after, segs) {
  const chain = ruleChain(rules, segs);
  for (let d = 0; d < chain.length; d++) {
    const n = chain[d].node;
    if (n && n[".write"] !== undefined && evalRule(n[".write"], mkCtx(auth, before, after, segs.slice(0, d)), chain[d].vars)) return true;
  }
  return false;
}
function validNode(rules, auth, before, after, segs) {
  const chain = ruleChain(rules, segs);
  const n = chain[segs.length] && chain[segs.length].node;
  if (!n || n[".validate"] === undefined) return true;
  if (getAt(after, segs) === null) return true;       // los borrados no se validan
  return evalRule(n[".validate"], mkCtx(auth, before, after, segs), chain[segs.length].vars);
}
function validSubtree(rules, auth, before, after, segs) {
  if (!validNode(rules, auth, before, after, segs)) return false;
  const v = getAt(after, segs);
  if (v && typeof v === "object") {
    for (const k of Object.keys(v)) if (!validSubtree(rules, auth, before, after, segs.concat(k))) return false;
  }
  return true;
}

// ── "base" con identidad ────────────────────────────────────────────────────
class Store { constructor(rules) { this.rules = rules; this.tree = null; } }
function makeDb(store, auth, bypass) { return { store, auth, bypass }; }

function applyWrites(db, writes) {
  // writes: [{segs, value}] — se aplican todas sobre una copia; se comprueba cada camino contra el resultado final.
  const { store, auth, bypass } = db;
  const before = store.tree;
  let after = clone(before);
  for (const w of writes) after = setAt(after, w.segs, w.value);
  if (!bypass) {
    for (const w of writes) {
      if (!allowedWrite(store.rules, auth, before, after, w.segs)) {
        const e = new Error("PERMISSION_DENIED: write " + w.segs.join("/")); e.code = "PERMISSION_DENIED"; throw e;
      }
    }
    for (const w of writes) {
      const bad = () => { const e = new Error("PERMISSION_DENIED: validate " + w.segs.join("/")); e.code = "PERMISSION_DENIED"; throw e; };
      if (!validSubtree(store.rules, auth, before, after, w.segs)) bad();
      if (VALIDATE_ANCESTORS) {
        for (let d = 0; d < w.segs.length; d++) if (!validNode(store.rules, auth, before, after, w.segs.slice(0, d))) bad();
      }
    }
  }
  store.tree = after;
}

// ── API tipo firebase/database ──────────────────────────────────────────────
export function ref(db, path = "/") { return { db, segs: segsOf(path) }; }
export function serverTimestamp() { return TS; }
export async function get(r) {
  const { store, auth, bypass } = r.db;
  if (!bypass && !allowedRead(store.rules, auth, store.tree, r.segs)) {
    const e = new Error("PERMISSION_DENIED: read " + r.segs.join("/")); e.code = "PERMISSION_DENIED"; throw e;
  }
  const v = getAt(store.tree, r.segs);
  return { val: () => clone(v), exists: () => v !== null };
}
export async function set(r, value) { applyWrites(r.db, [{ segs: r.segs, value: resolveTs(value) }]); }
export async function remove(r) { applyWrites(r.db, [{ segs: r.segs, value: null }]); }
export async function update(r, obj) {
  const writes = Object.keys(obj).map((k) => ({ segs: r.segs.concat(segsOf(k)), value: resolveTs(obj[k]) }));
  applyWrites(r.db, writes);
}

export async function initializeTestEnvironment({ database }) {
  const rules = JSON.parse(database.rules);
  const store = new Store(rules);
  return {
    async clearDatabase() { store.tree = null; },
    async withSecurityRulesDisabled(fn) { await fn({ database: () => makeDb(store, null, true) }); },
    authenticatedContext(uid, token = {}) { return { database: () => makeDb(store, { uid, token }, false) }; },
    unauthenticatedContext() { return { database: () => makeDb(store, null, false) }; },
    async cleanup() {},
  };
}
