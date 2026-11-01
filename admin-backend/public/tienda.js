/* ═══════════════════════════════════════════════════════════════════════════
 * tienda.js — pantalla GLOBAL de la Tienda Play (29/10/2026, B.101).
 *
 * Toda la lógica que decide QUÉ se guarda está en tienda_logic.js (con su banco de
 * pruebas: tools/test_tienda_logic.js). Acá solo hay pantalla: leer de Firebase, dibujar,
 * y escribir `globalSettings/playStore`.
 *
 * ── DÓNDE SE GUARDA ───────────────────────────────────────────────────────────────
 *   globalSettings/playStore/apps/<paquete_>  = {packageName,label,tier,access,status,allow?,block?,iconUrl?}
 *   globalSettings/playStore/meta             = {savedAt,savedBy,count}
 * Los celulares lo leen con el comando SYNC_PLAY_STORE (botón «Enviar a todos») y una vez
 * por hora por su cuenta (WatchdogWorker). La base no se toca hasta que se aprieta
 * «Guardar»: lo que se ve acá antes de eso es una propuesta.
 *
 * ── «POR DEFECTO, LAS APPS DE LA TIENDA VIEJA» ────────────────────────────────────
 * Mientras `meta/savedAt` no exista (la tienda nunca se guardó), la lista de trabajo se
 * arma sola desde `storeApps` (la tienda vieja) — ver TiendaLogic.seedFromStoreApps para
 * las reglas — y después se consulta Google Play para marcar cuáles existen de verdad.
 * ═══════════════════════════════════════════════════════════════════════════ */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const esc = LS.esc;
  const T = window.TiendaLogic;

  const TIERS = {
    1: { nombre: "Nivel 1 — Kosher", nota: "Lo ven los modos Kosher, Trabajo y Lite." },
    2: { nombre: "Nivel 2 — Trabajo", nota: "Suma al nivel 1. Lo ven los modos Trabajo y Lite." },
    3: { nombre: "Nivel 3 — Lite", nota: "Suma a los niveles 1 y 2. Solo lo ve el modo Lite." },
  };
  const MOTIVOS = {
    never: "no se puede ofrecer (es del sistema, de Google Play o de LockSuite)",
    "aa-stub": "es un stub de Android Auto, no una app de Google Play",
    invalid: "el nombre de paquete no es válido",
  };

  const S = {
    guardadas: [],       // filas tal como están guardadas en Firebase
    filas: [],           // filas de trabajo (lo que se ve y se edita)
    desdeLaVieja: false, // la lista de trabajo es la inicial, nunca guardada
    omitidas: [],        // lo que la lista inicial dejó afuera, con motivo
    storeApps: {},
    permitidas: null,
    custom: {},          // globalSettings/whitelist/custom (para avisar de apps sin dominios)
    devices: {},
    abiertas: new Set(),
    verificando: false,
    verificadaAlgunaVez: false,
  };

  const clonar = (r) => Object.assign({}, r, { allow: (r.allow || []).slice(), block: (r.block || []).slice() });

  /* ── Diferencias con lo guardado ─────────────────────────────────────── */
  function diff() {
    const a = new Map(S.guardadas.map((r) => [r.pkg, T.signature([r])]));
    const b = new Map(S.filas.map((r) => [r.pkg, T.signature([r])]));
    let add = 0, del = 0, mod = 0;
    b.forEach((v, k) => { if (!a.has(k)) add++; else if (a.get(k) !== v) mod++; });
    a.forEach((v, k) => { if (!b.has(k)) del++; });
    return { add: add, del: del, mod: mod, total: add + del + mod };
  }

  function filaPendiente(r) {
    const g = S.guardadas.find((x) => x.pkg === r.pkg);
    return !g || T.signature([g]) !== T.signature([r]);
  }

  /* ── Avisos de cada fila ─────────────────────────────────────────────── */
  function sinDominios(r) {
    if (r.access !== "full") return false;
    const enCatalogo = (LS.catalogo().apps || []).some((a) => a.pkg === r.pkg);
    const c = S.custom[T.pkgKey(r.pkg)] || {};
    const n = Array.isArray(c.allow) ? c.allow.length : (c.allow ? Object.keys(c.allow).length : 0);
    return !enCatalogo && n === 0;
  }

  function avisosDeFila(r) {
    const out = [];
    if (sinDominios(r)) {
      out.push("Con la lista blanca estricta encendida esta app no tendría ningún dominio abierto: " +
        "no está en el catálogo de «Dominios por app», así que no tendría internet. " +
        "Elegí «Dominios propios» y cargá los suyos, o agregala en Dominios por app.");
    }
    if (r.access === "domains" && !r.allow.length && !r.block.length) {
      out.push("Acceso «dominios propios» sin ningún dominio: bajo el filtro estricto la app no tendría internet.");
    }
    if (r.play === "missing") {
      out.push("Google Play no la encontró desde el servidor del panel. Puede ser una app propia (que no está en Play) " +
        "o una que no está disponible en ese país. Si de verdad no está en Play, la tienda no la va a poder instalar: " +
        "eliminala o dejala bloqueada.");
    }
    return out;
  }

  /* ── Filtros ─────────────────────────────────────────────────────────── */
  function filtradas() {
    const q = $("t-buscar").value.trim().toLowerCase();
    const fn = $("t-f-nivel").value;
    const fe = $("t-f-estado").value;
    return S.filas.filter((r) => {
      if (fn !== "todos" && String(r.tier) !== fn) return false;
      if (fe === "nopaly") { if (r.play !== "missing") return false; }
      else if (fe !== "todos" && r.status !== fe) return false;
      if (q && r.label.toLowerCase().indexOf(q) < 0 && r.pkg.toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
  }

  /* ── Dibujo ──────────────────────────────────────────────────────────── */
  function letra(r) {
    const c = (r.label || r.pkg || "?").trim().charAt(0).toUpperCase();
    return c || "?";
  }

  function iconoHtml(r) {
    return r.iconUrl
      ? '<img class="tp-icon tp-img" src="' + esc(r.iconUrl) + '" alt="" loading="lazy" referrerpolicy="no-referrer" data-letra="' + esc(letra(r)) + '" />'
      : '<div class="tp-icon">' + esc(letra(r)) + "</div>";
  }

  function chipsHtml(r) {
    const c = [];
    if (r.play === "found") c.push('<span class="ls-chip ok" title="Google Play tiene esta app">en Google Play</span>');
    else if (r.play === "missing") c.push('<span class="ls-chip bad" title="Google Play no la encontró">no aparece en Play</span>');
    else if (r.play === "unknown") c.push('<span class="ls-chip" title="No se pudo saber">Play: sin saber</span>');
    if (r.inOldStore) c.push('<span class="ls-chip" title="Está en la tienda vieja de LockSuite (APK propios)">tienda vieja</span>');
    if (r.access === "noweb") c.push('<span class="ls-chip warn" title="Se instala con el navegador interno bloqueado">sin web</span>');
    if (r.access === "domains") c.push('<span class="ls-chip warn" title="Con dominios permitidos / prohibidos propios">dominios</span>');
    if (sinDominios(r)) c.push('<span class="ls-chip warn" title="No está en el catálogo de dominios: con la lista blanca estricta no tendría internet">⚠ sin dominios</span>');
    if (!S.desdeLaVieja && filaPendiente(r)) c.push('<span class="ls-chip warn">sin guardar</span>');
    return c.join(" ");
  }

  function filaHtml(r) {
    const st = r.status;
    const clase = st === "enabled" ? "st-allow" : st === "request" ? "st-req" : "st-block";
    const abierta = S.abiertas.has(r.pkg);
    const seg =
      '<div class="ls-seg" role="group" aria-label="Estado">' +
      '<button data-st="enabled" class="' + (st === "enabled" ? "on-allow" : "") + '" title="Se muestra con «Instalar»">Habilitada</button>' +
      '<button data-st="request" class="' + (st === "request" ? "on-req" : "") + '" title="Se muestra con «Pedir»: se instala solo si aprobás el pedido">A pedido</button>' +
      '<button data-st="blocked" class="' + (st === "blocked" ? "on-block" : "") + '" title="No se muestra ni se instala">Bloqueada</button>' +
      "</div>";
    let cuerpo = "";
    if (abierta) {
      const optTier = [1, 2, 3].map((n) =>
        '<option value="' + n + '"' + (r.tier === n ? " selected" : "") + ">" + esc(TIERS[n].nombre) + "</option>").join("");
      const optAcc = [["full", "Completa"], ["noweb", "Sin web"], ["domains", "Dominios propios"]].map((a) =>
        '<option value="' + a[0] + '"' + (r.access === a[0] ? " selected" : "") + ">" + a[1] + "</option>").join("");
      const explicacion = {
        full: "La app queda permitida completa, con los dominios que ya tenga en el catálogo de «Dominios por app».",
        noweb: "Se instala y se permite, pero con el navegador interno bloqueado: en Aplicaciones aparece como «Sin web».",
        domains: "Solo se abren los dominios de «Permitidos». Los de «Prohibidos» se cierran siempre (le ganan a los permitidos).",
      }[r.access];
      cuerpo =
        '<div class="ls-appbody"><div class="tp-fields">' +
        '<div class="tp-wide"><label>Nombre que ve el usuario</label>' +
        '<input class="ls-input" data-f="label" value="' + esc(r.label) + '" maxlength="60" /></div>' +
        '<div><label>Nivel</label><select class="ls-select" data-f="tier">' + optTier + "</select></div>" +
        '<div><label>Acceso</label><select class="ls-select" data-f="access">' + optAcc + "</select></div>" +
        '<div class="tp-wide"><span class="ls-hint" style="margin:0">' + esc(explicacion) + "</span></div>" +
        (r.access === "domains"
          ? '<div><label>Dominios permitidos (uno por renglón)</label><textarea data-f="allow" placeholder="ejemplo.com">' + esc(r.allow.join("\n")) + "</textarea></div>" +
            '<div><label>Dominios prohibidos (uno por renglón)</label><textarea data-f="block" placeholder="malo.ejemplo.com">' + esc(r.block.join("\n")) + "</textarea></div>"
          : "") +
        "</div>" +
        avisosDeFila(r).map((a) => '<div class="tp-warn">⚠ ' + esc(a) + "</div>").join("") +
        "</div>";
    }
    return '<div class="ls-approw ' + clase + (!S.desdeLaVieja && filaPendiente(r) ? " pending" : "") + '" data-app="' + esc(r.pkg) + '">' +
      '<div class="ls-apphead">' + iconoHtml(r) +
      '<div class="ls-appgrow"><div class="ls-appname">' + esc(r.label) + "</div>" +
      '<div class="ls-apppkg">' + esc(r.pkg) + "</div>" +
      '<div class="ls-chiprow">' + chipsHtml(r) + "</div></div>" +
      seg +
      '<button class="tp-toggle" data-abrir="1">' + (abierta ? "Ocultar ▴" : "Detalles ▾") + "</button>" +
      '<button class="ls-btn ls-btn-sm ls-btn-danger" data-del="1" title="Sacarla de la lista">Eliminar</button>' +
      "</div>" + cuerpo + "</div>";
  }

  function pintar() {
    const lista = filtradas();
    let html = "";
    if (!S.filas.length) {
      html = '<div class="ls-card"><p class="ls-empty">No hay ninguna app en la Tienda Play. ' +
        "Usá «+ Agregar una app» o «↩ Traer de la tienda vieja».</p></div>";
    } else if (!lista.length) {
      html = '<div class="ls-card"><p class="ls-empty">Ninguna app coincide con el filtro.</p></div>';
    } else {
      [1, 2, 3].forEach((n) => {
        const del = lista.filter((r) => r.tier === n);
        if (!del.length) return;
        html += '<div class="tp-tier"><h2>' + esc(TIERS[n].nombre) + " <small>(" + del.length + ")</small></h2>" +
          "<small>" + esc(TIERS[n].nota) + "</small></div>";
        html += del.map(filaHtml).join("");
      });
    }
    $("t-apps").innerHTML = html;
    pintarResumen();
    pintarBarra();
    pintarSemilla();
    pintarQuitarNoPlay();
  }

  function pintarResumen() {
    const modos = [
      ["cerrada", "Cerrada", "no hay tienda"],
      ["kosher", "Kosher", "nivel 1"],
      ["trabajo", "Trabajo", "niveles 1 y 2"],
      ["lite", "Lite", "niveles 1, 2 y 3"],
    ];
    $("t-resumen").innerHTML = modos.map((m) => {
      const v = T.visibleIn(S.filas, m[0]);
      const hab = v.filter((r) => r.status === "enabled").length;
      const ped = v.filter((r) => r.status === "request").length;
      return '<div class="tp-mode"><b>' + m[1] + "</b><small>" + m[2] + "</small><br>" +
        (m[0] === "cerrada" ? "<small>0 apps</small>"
          : "<small>" + v.length + " apps (" + hab + " habilitadas, " + ped + " a pedido)</small>") + "</div>";
    }).join("");
    const s = T.summarize(S.filas);
    $("t-resumen-nota").textContent =
      "Total: " + s.total + " apps — " + s.enabled + " habilitadas, " + s.request + " a pedido, " + s.blocked + " bloqueadas. " +
      "Cuenta lo que vería un celular en cada modo con esta lista. En un equipo puntual, lo que la lista blanca " +
      "prohíba o los extras de ese usuario pueden cambiarlo.";
  }

  function pintarBarra() {
    const d = diff();
    const chip = $("t-pend");
    if (S.desdeLaVieja) {
      chip.textContent = "lista inicial sin guardar";
      chip.className = "ls-chip warn";
    } else if (d.total) {
      chip.textContent = d.total + " cambio" + (d.total === 1 ? "" : "s") + " sin guardar";
      chip.className = "ls-chip warn";
    } else {
      chip.textContent = "guardado";
      chip.className = "ls-chip ok";
    }
    const pend = S.desdeLaVieja || d.total > 0;
    $("t-cart").classList.toggle("show", pend);
    $("t-cart-n").textContent = S.desdeLaVieja
      ? "Lista inicial sin guardar"
      : d.total + " cambio" + (d.total === 1 ? "" : "s") + " sin guardar";
    $("t-cart-detalle").textContent = S.desdeLaVieja
      ? "Los celulares no la ven hasta que la guardes."
      : [d.add ? d.add + " agregada" + (d.add === 1 ? "" : "s") : "", d.mod ? d.mod + " modificada" + (d.mod === 1 ? "" : "s") : "",
        d.del ? d.del + " quitada" + (d.del === 1 ? "" : "s") : ""].filter(Boolean).join(" · ");
  }

  function pintarSemilla() {
    const card = $("t-seed");
    if (!S.desdeLaVieja) { card.style.display = "none"; return; }
    card.style.display = "";
    $("t-seed-text").innerHTML =
      "Como todavía no guardaste nada, te cargué por defecto <b>" + S.filas.length + " apps de la tienda vieja</b> de LockSuite. " +
      "Cada una arranca en <b>nivel 1</b>, con acceso <b>completa</b> y con el estado que ya tenía allá: " +
      "<b>habilitada</b> si ya estaba permitida, <b>a pedido</b> si no. Elegí para cada una si la querés " +
      "habilitada, a pedido, bloqueada o eliminarla, repartilas en los niveles, y tocá <b>Guardar</b>: " +
      "hasta entonces ningún celular ve nada de esto.";
    $("t-skipped").innerHTML = S.omitidas.length
      ? "Se dejaron afuera: " + S.omitidas.map((o) => "<code>" + esc(o.pkg) + "</code> (" + esc(MOTIVOS[o.reason] || o.reason) + ")").join("; ") + "."
      : "";
  }

  function pintarQuitarNoPlay() {
    const n = S.filas.filter((r) => r.play === "missing").length;
    const b = $("t-quitar-noplay");
    b.style.display = n ? "" : "none";
    b.textContent = "Quitar las " + n + " que no aparecen en Play";
  }

  /* ── Acciones sobre una fila ─────────────────────────────────────────── */
  function fila(pkg) { return S.filas.find((r) => r.pkg === pkg); }

  $("t-apps").addEventListener("click", (e) => {
    const card = e.target.closest("[data-app]");
    if (!card) return;
    const pkg = card.dataset.app;
    const r = fila(pkg);
    if (!r) return;
    const st = e.target.closest("button[data-st]");
    if (st) { r.status = st.dataset.st; pintar(); return; }
    if (e.target.closest("button[data-abrir]")) {
      if (S.abiertas.has(pkg)) S.abiertas.delete(pkg); else S.abiertas.add(pkg);
      pintar();
      return;
    }
    if (e.target.closest("button[data-del]")) {
      if (!confirm("¿Eliminar «" + r.label + "» de la Tienda Play?\n\nDeja de aparecer en la lista (para quitarla solo " +
        "de la vista de los usuarios, «Bloqueada» alcanza). Se aplica cuando guardes; mientras tanto podés descartar.")) return;
      S.filas = S.filas.filter((x) => x.pkg !== pkg);
      S.abiertas.delete(pkg);
      pintar();
    }
  });

  $("t-apps").addEventListener("change", (e) => {
    const campo = e.target.closest("[data-f]");
    const card = e.target.closest("[data-app]");
    if (!campo || !card) return;
    const r = fila(card.dataset.app);
    if (!r) return;
    const f = campo.dataset.f;
    if (f === "label") {
      r.label = T.cleanLabel(campo.value, r.pkg);
      r.labelSet = true;
    } else if (f === "tier") {
      r.tier = Number(campo.value);
    } else if (f === "access") {
      r.access = T.parseAccess(campo.value) || "full";
    } else if (f === "allow" || f === "block") {
      const res = T.cleanDomains(T.splitDomainText(campo.value));
      r[f] = res.domains;
      if (res.rejected.length) LS.toast("No se entendió como dominio: " + res.rejected.slice(0, 5).join(", ") + ". Se descartó.", "warn");
    }
    pintar();
  });

  // Si el ícono no carga (red, CDN) se cambia por la letra, igual que en el celular.
  $("t-apps").addEventListener("error", (e) => {
    const img = e.target;
    if (!img || img.tagName !== "IMG" || !img.classList.contains("tp-img")) return;
    const d = document.createElement("div");
    d.className = "tp-icon";
    d.textContent = img.dataset.letra || "?";
    img.replaceWith(d);
  }, true);

  /* ── Barra de herramientas ───────────────────────────────────────────── */
  $("t-buscar").addEventListener("input", pintar);
  $("t-f-nivel").addEventListener("change", pintar);
  $("t-f-estado").addEventListener("change", pintar);

  $("t-agregar").addEventListener("click", () => {
    const crudo = (window.prompt("Nombre del paquete de la app, tal como figura en Google Play\n(por ejemplo com.waze):") || "").trim();
    if (!crudo) return;
    if (!T.isValidPackage(crudo)) { LS.toast("Ese no parece un nombre de paquete válido.", "error"); return; }
    if (T.isNeverOffered(crudo)) { LS.toast("Esa app no se puede ofrecer en la tienda (es del sistema, de Google Play o de LockSuite).", "error"); return; }
    if (fila(crudo)) { S.abiertas.add(crudo); $("t-buscar").value = crudo; pintar(); LS.toast("Esa app ya está en la lista.", "warn"); return; }
    const nivelFiltro = Number($("t-f-nivel").value);
    const r = T.makeRow({
      pkg: crudo, tier: nivelFiltro >= 1 && nivelFiltro <= 3 ? nivelFiltro : 1,
      status: "enabled", access: "full", origin: "new",
    });
    S.filas.push(r);
    S.filas.sort(T.byLabel);
    S.abiertas.add(crudo);
    $("t-f-estado").value = "todos";
    $("t-buscar").value = crudo;
    pintar();
    LS.toast("Agregada. Ya está «Habilitada»; consultando Google Play para traer su nombre e ícono…", "ok");
    verificar([crudo], true);
  });

  $("t-vieja").addEventListener("click", () => {
    const falta = T.missingFromOldStore(S.filas, S.storeApps, S.permitidas);
    if (!falta.rows.length) { LS.toast("No falta ninguna: todas las de la tienda vieja ya están en la lista.", "ok"); return; }
    falta.rows.forEach((r) => S.filas.push(r));
    S.filas.sort(T.byLabel);
    pintar();
    LS.toast("Se sumaron " + falta.rows.length + " de la tienda vieja (nivel 1, con el estado que tenían allá).", "ok");
    verificar(falta.rows.map((r) => r.pkg), true);
  });

  $("t-quitar-noplay").addEventListener("click", () => {
    const faltan = S.filas.filter((r) => r.play === "missing");
    if (!faltan.length) return;
    if (!confirm("Google Play no encontró estas " + faltan.length + " apps desde el servidor del panel:\n\n" +
      faltan.map((r) => "• " + r.label + " (" + r.pkg + ")").join("\n") +
      "\n\nPuede ser que sean apps propias o que no estén en ese país. ¿Quitarlas de la Tienda Play?\n" +
      "(No se tocan en la tienda vieja, y podés descartar los cambios antes de guardar.)")) return;
    S.filas = S.filas.filter((r) => r.play !== "missing");
    pintar();
  });

  /* ── Verificar en Google Play ────────────────────────────────────────── */
  async function verificar(pkgs, silencioso) {
    if (S.verificando) { if (!silencioso) LS.toast("Ya se está consultando Google Play.", "warn"); return; }
    if (!pkgs.length) return;
    S.verificando = true;
    $("t-verificar").disabled = true;
    $("t-verif-estado").textContent = "Consultando Google Play (" + pkgs.length + " app" + (pkgs.length === 1 ? "" : "s") + ")…";
    try {
      const res = await LS.checkPlayApps(pkgs);
      T.applyPlayCheck(S.filas, res);
      S.verificadaAlgunaVez = true;
      const f = res.filter((r) => r.status === "found").length;
      const m = res.filter((r) => r.status === "missing").length;
      const u = res.length - f - m;
      $("t-verif-estado").textContent =
        "Google Play: " + f + " encontrada" + (f === 1 ? "" : "s") + ", " + m + " no aparece" + (m === 1 ? "" : "n") +
        (u ? ", " + u + " sin saber (Play no contestó con claridad)" : "") + "." +
        (m ? " «No aparece» es lo que dijo Play desde el servidor del panel: puede ser una app propia o de otro país, por eso no se quitó nada sola." : "");
    } catch (e) {
      $("t-verif-estado").textContent = "No se pudo consultar Google Play (" + e.message + "). " +
        "La lista sigue sin verificar; podés guardarla igual y verificar más tarde con «Verificar en Play».";
      if (!silencioso) LS.toast("No se pudo consultar Google Play: " + e.message, "error");
    } finally {
      S.verificando = false;
      $("t-verificar").disabled = false;
      pintar();
    }
  }

  $("t-verificar").addEventListener("click", () => verificar(S.filas.map((r) => r.pkg), false));

  /* ── Guardar / descartar ─────────────────────────────────────────────── */
  async function guardar() {
    const p = T.buildPayload(S.filas);
    if (p.problems.length) {
      const lista = p.problems.slice(0, 12).map((x) => "• " + x).join("\n") +
        (p.problems.length > 12 ? "\n… y " + (p.problems.length - 12) + " más" : "");
      if (!confirm("Antes de guardar:\n\n" + lista + "\n\n¿Guardar igual?")) return;
    }
    const user = LS.auth.currentUser;
    await LS.db.ref("globalSettings/playStore").set({
      apps: p.apps,
      meta: {
        savedAt: firebase.database.ServerValue.TIMESTAMP,
        savedBy: (user && user.email) || "",
        count: p.count,
      },
    });
    // Lo guardado pasa a ser la base de comparación; se conserva lo que solo vive en pantalla
    // (si Play la encontró, y que el nombre ya está fijado).
    const play = {};
    S.filas.forEach((r) => { play[r.pkg] = r.play; });
    S.guardadas = T.rowsFromSaved(p.apps);
    S.filas = S.guardadas.map((r) => { const c = clonar(r); c.play = play[r.pkg] || null; return c; });
    S.desdeLaVieja = false;
    S.omitidas = [];
    LS.toast("Guardado (" + p.count + " apps). Los celulares lo leen con «Enviar a todos» o en su próxima " +
      "sincronización (hasta 1 hora).", "ok");
  }

  $("t-guardar").addEventListener("click", async function () {
    this.disabled = true;
    try { await guardar(); } catch (e) { LS.toast("No se pudo guardar: " + e.message, "error"); }
    this.disabled = false;
    pintar();
  });

  $("t-descartar").addEventListener("click", () => {
    if (S.desdeLaVieja) {
      if (!confirm("¿Descartar los cambios y volver a la lista inicial de la tienda vieja?")) return;
      armarDesdeLaVieja();
    } else {
      if (!confirm("¿Descartar los cambios sin guardar?")) return;
      S.filas = S.guardadas.map(clonar);
    }
    S.abiertas.clear();
    pintar();
  });

  /* ── Enviar a la flota ───────────────────────────────────────────────── */
  $("t-enviar").addEventListener("click", async function () {
    if (S.desdeLaVieja || diff().total) { LS.toast("Guardá los cambios primero.", "error"); return; }
    const ids = Object.keys(S.devices);
    if (!ids.length) { LS.toast("No hay celulares.", "error"); return; }
    if (!confirm("¿Avisarles a los " + ids.length + " celulares que relean la Tienda Play?\n\n" +
      "No cambia el MODO de ningún equipo (eso se elige en Políticas o lo fija el perfil automático): " +
      "solo hace que lean el catálogo que acabás de guardar.")) return;
    this.disabled = true;
    $("t-envio-card").style.display = "";
    const log = $("t-envio");
    log.innerHTML = "";
    let ok = 0, mal = 0, pend = 0;
    for (const id of ids) {
      const d = S.devices[id] || {};
      const nombre = d.deviceName || (d.info && d.info.deviceName) || d.model || id.slice(0, 10);
      const l = document.createElement("div");
      l.className = "wait";
      l.textContent = "… " + nombre;
      log.appendChild(l);
      const r = await LS.sendCommand(id, "SYNC_PLAY_STORE", null, null, { nombre: nombre, timeoutMs: 20000 });
      if (r.ok && r.pendiente) { pend++; l.className = "wait"; l.textContent = "⏳ " + nombre + " — en su buzón, sin confirmar todavía"; }
      else if (r.ok) { ok++; l.className = "ok"; l.textContent = "✓ " + nombre; }
      else { mal++; l.className = "bad"; l.textContent = "✗ " + nombre + " — " + (r.reason || r.status); }
    }
    this.disabled = false;
    LS.toast(ok + " aplicaron" + (pend ? ", " + pend + " en su buzón" : "") + ", " + mal + " no contestaron." +
      ((mal || pend) ? " Los que no contestaron lo van a leer igual en su próxima sincronización." : ""),
      (mal || pend) ? "warn" : "ok");
  });

  /* ── Arranque ────────────────────────────────────────────────────────── */
  function armarDesdeLaVieja() {
    const semilla = T.seedFromStoreApps(S.storeApps, S.permitidas);
    S.filas = semilla.rows;
    S.omitidas = semilla.skipped;
    S.desdeLaVieja = true;
  }

  async function cargar() {
    const leer = (ruta) => LS.db.ref(ruta).once("value").then((s) => s.val());
    let apps, meta;
    try {
      const r = await Promise.all([
        leer("globalSettings/playStore/apps"),
        leer("globalSettings/playStore/meta"),
        leer("storeApps"),
        leer("globalSettings/allowedPackages"),
        leer("globalSettings/whitelist/custom"),
      ]);
      apps = r[0]; meta = r[1];
      S.storeApps = r[2] || {};
      S.permitidas = r[3];
      S.custom = r[4] || {};
    } catch (e) {
      $("t-pend").textContent = "no se pudo leer";
      $("t-pend").className = "ls-chip bad";
      LS.toast("No se pudo leer de Firebase: " + e.message, "error");
      return;
    }
    S.guardadas = T.rowsFromSaved(apps);
    const nuncaGuardada = !(meta && meta.savedAt) && S.guardadas.length === 0;
    if (nuncaGuardada) {
      armarDesdeLaVieja();
    } else {
      S.filas = S.guardadas.map(clonar);
      S.desdeLaVieja = false;
    }
    pintar();
    // La lista inicial se verifica sola contra Google Play (mejor esfuerzo: si la función no
    // está desplegada o no contesta, queda "sin verificar" y se puede reintentar a mano).
    if (S.desdeLaVieja && S.filas.length) verificar(S.filas.map((r) => r.pkg), true);
  }

  LS.requireAuth(function () {
    LS.db.ref("devices").on("value", (snap) => { S.devices = snap.val() || {}; });
    cargar();
  });

  window.addEventListener("beforeunload", (e) => {
    if (S.desdeLaVieja || diff().total) { e.preventDefault(); e.returnValue = ""; }
  });
})();
