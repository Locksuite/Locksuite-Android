/* ═══════════════════════════════════════════════════════════════════════════
 * diag_remoto.js — «Registro de errores» en la sección Diagnóstico de la ficha (6/11/2026, B.110)
 *
 * El pedido del dueño (5/11/2026): «los usuarios me mandan que tuvieron un error ("no me
 * abre…", "se me cortó…") y yo desde acá no sé bien el error… algo como tener el celular
 * conectado por depuración USB, pero sin que a los usuarios les moleste, sin gastar batería
 * y sin que se vea nada en el celular. Así te mando el error y diagnosticás con exactitud».
 *
 * El celular (app/.../diag/) anota sus errores y los sube solo a `deviceDiag/<id>`. Esta
 * pantalla los muestra y arma un informe de texto para copiar y pegarle a la IA. Toda la
 * lógica que se puede probar sin navegador está en `diag_logic.js` (window.DiagLogic).
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • NADA de lo que viene del celular entra al HTML sin pasar por `LS.esc`. Los mensajes los
 *    escribe el celular (o una app que lo hizo fallar): son datos NO confiables. El informe
 *    y los bloques de texto se muestran con esc() dentro de <pre>, nunca como HTML.
 *  • Los oyentes de `deviceDiag` se abren recién cuando se entra a la sección Diagnóstico
 *    (no al abrir la ficha): son ~200 KB por celular y casi nadie los mira a cada rato.
 *  • Un error de lectura (permission_denied) se DICE. El caso real: el panel se despliega
 *    antes que las reglas de la base y la lista se vería vacía sin explicación.
 *  • Re-dibujar la lista en cada latido cerraría los renglones que el dueño abrió con el
 *    dedo: por eso la lista se redibuja solo si cambió, y recuerda cuáles estaban abiertos.
 *  • «Pedir diagnóstico» NO pide PIN (es de solo lectura, ver index.js): la Function lo
 *    exime a propósito. «Apagar/Encender la subida» sí lo pide.
 *  • Los comandos se mandan con LS.sendCommand y el nombre escrito LITERAL (entre comillas),
 *    sin armarlo con variables: tools/check_panel_commands.py los lee de este archivo.
 *    (Este comentario no lleva ejemplos con esa forma a propósito: el chequeo los tomaría
 *    por comandos de verdad.)
 * ═══════════════════════════════════════════════════════════════════════════ */
(function () {
  "use strict";

  const raiz = document.getElementById("c-diag-remoto");
  if (!raiz) return;
  const DL = window.DiagLogic;
  if (!DL) {
    raiz.innerHTML = '<p class="ls-empty">Falta diag_logic.js: no se puede mostrar el registro de errores.</p>';
    return;
  }
  const deviceId = LS.qs("id");
  if (!deviceId) return;

  const esc = LS.esc;
  const $ = (id) => document.getElementById(id);

  const S = {
    dev: DL.pickDevice({}, deviceId),
    meta: {},
    events: [],
    snaps: [],
    loaded: { dev: false, events: false, snaps: false },
    err: null,
    activo: false,
    escuchas: [],
    ui: { nivel: "W", ventana: "24h", etiqueta: "", texto: "", mostrar: 100, snapKey: "", pidiendo: false, abiertos: {} }
  };

  const firmas = {};
  /** ¿Cambió lo que dibuja esta parte? (mismo criterio que celular.js: no redibujar de más). */
  function cambio(clave, datos) {
    const f = JSON.stringify(datos);
    if (firmas[clave] === f) return false;
    firmas[clave] = f;
    return true;
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * ESTILOS (propios: no se toca panel2.css)
   * ═══════════════════════════════════════════════════════════════════════ */
  if (!$("dr-style")) {
    const st = document.createElement("style");
    st.id = "dr-style";
    st.textContent =
      "#c-diag-remoto{overflow-wrap:anywhere}" +
      "#c-diag-remoto h4{margin:20px 0 8px;font-size:13.5px}" +
      "#c-diag-remoto .dr-lista{border:1px solid var(--md-sys-color-outline-variant,#2A3647);border-radius:8px;overflow:hidden}" +
      ".dr-ev{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;padding:6px 10px;font-size:12.5px;" +
        "border-bottom:1px solid var(--md-sys-color-outline-variant,#2A3647);border-left:3px solid transparent}" +
      ".dr-ev:last-child{border-bottom:none}" +
      ".dr-ev.l-W{border-left-color:#FBBF24}.dr-ev.l-E{border-left-color:#F87171}" +
      ".dr-ev.l-F{border-left-color:#F87171;background:rgba(76,20,27,.28)}" +
      "details.dr-ev{display:block}" +
      "details.dr-ev>summary{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap;cursor:pointer}" +
      "details.dr-ev>pre{margin-top:8px}" +
      ".dr-t{font-family:ui-monospace,Consolas,monospace;font-size:11.5px;white-space:nowrap;" +
        "color:var(--md-sys-color-on-surface-variant,#94A3B8)}" +
      ".dr-g{font-family:ui-monospace,Consolas,monospace;font-weight:700}" +
      ".dr-m{flex:1 1 280px;min-width:0;word-break:break-word;white-space:pre-wrap}" +
      "#c-diag-remoto .ls-pre{max-width:100%}" +
      // En un teléfono (390 px) un <select> con opciones largas ensancharía toda la tarjeta.
      "#c-diag-remoto select,#c-diag-remoto input,#c-diag-remoto textarea{max-width:100%;box-sizing:border-box}" +
      "#dr-snap-sel{width:100%}" +
      "#c-diag-remoto label.dr-chk{display:inline-flex;align-items:center;gap:6px;font-size:12.5px}";
    document.head.appendChild(st);
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * ESQUELETO (se arma una vez; después solo se actualizan las partes que cambian)
   * ═══════════════════════════════════════════════════════════════════════ */
  raiz.innerHTML =
    "<h3>🔬 Registro de errores del celular</h3>" +
    '<p class="ls-hint">Es como tener el celular conectado por USB con la depuración encendida, pero sin cable: ' +
    "el celular anota sus errores y los sube solo, en silencio (sin avisos ni pantallas, sin permisos nuevos y sin " +
    "gastar batería aparte: viaja con el latido que ya hace). Si un usuario cuenta un problema, pedí el diagnóstico, " +
    "copiá el informe de abajo y pegámelo.</p>" +
    '<div id="dr-estado"></div>' +
    '<div class="ls-row" style="margin:10px 0 6px">' +
      '<button class="ls-btn ls-btn-primary" id="dr-pedir">🩺 Pedir diagnóstico completo ahora</button>' +
      '<button class="ls-btn" id="dr-interruptor">Apagar la subida del registro</button>' +
    "</div>" +
    '<div class="ls-row" style="margin:8px 0">' +
      '<input class="ls-input" id="dr-pkgs" placeholder="Apps a revisar (opcional): com.whatsapp, com.waze" style="flex:1 1 280px" />' +
      '<select class="ls-select" id="dr-pkg-sel" style="flex:0 1 240px"><option value="">＋ elegir una app del celular…</option></select>' +
    "</div>" +
    '<div class="ls-hint" id="dr-pedir-msg" style="min-height:1.2em"></div>' +

    "<h4>Qué pasó en el celular</h4>" +
    '<div class="ls-chips" id="dr-nivel">' +
      '<button data-n="I">Todo</button><button data-n="W" class="on">Avisos y errores</button><button data-n="E">Solo errores</button>' +
    "</div>" +
    '<div class="ls-row" style="margin-bottom:8px">' +
      '<select class="ls-select" id="dr-ventana">' +
        '<option value="1h">Última hora</option><option value="24h" selected>Últimas 24 h</option>' +
        '<option value="3d">Últimos 3 días</option><option value="7d">Últimos 7 días</option><option value="todo">Todo lo guardado</option>' +
      "</select>" +
      '<select class="ls-select" id="dr-etiqueta"><option value="">Todas las etiquetas</option></select>' +
      '<input class="ls-input" id="dr-texto" type="search" placeholder="Buscar en los mensajes…" style="flex:1 1 200px" />' +
    "</div>" +
    '<div class="ls-hint" id="dr-resumen"></div>' +
    '<div id="dr-lista"></div>' +
    '<div id="dr-mas" style="margin-top:8px"></div>' +

    "<h4>Diagnósticos completos</h4>" +
    '<p class="ls-hint">Lo que el celular junta cuando se lo pedís: estado del sistema, cierres recientes del programa, ' +
    "errores del logcat y el estado de las apps que elijas. Se guardan los últimos 3.</p>" +
    '<select class="ls-select" id="dr-snap-sel" disabled><option value="">(ninguno todavía)</option></select>' +
    '<div id="dr-snap" style="margin-top:10px"></div>' +

    "<h4>📋 Para pasárselo a Claude</h4>" +
    '<textarea class="ls-input ls-wide" id="dr-nota" rows="3" ' +
      'placeholder="Qué contó el usuario, con sus palabras y la hora aproximada (ej.: «No me abre WhatsApp, se me cortó la llamada a las 21:00»)"></textarea>' +
    '<div class="ls-row" style="margin:8px 0">' +
      '<select class="ls-select" id="dr-inf-nivel"><option value="W" selected>Avisos y errores</option><option value="E">Solo errores</option><option value="I">Todo (con información)</option></select>' +
      '<select class="ls-select" id="dr-inf-ventana"><option value="24h">Últimas 24 h</option><option value="3d" selected>Últimos 3 días</option>' +
        '<option value="7d">Últimos 7 días</option><option value="todo">Todo lo guardado</option></select>' +
      '<label class="dr-chk"><input type="checkbox" id="dr-inc-snap" checked /> Incluir el diagnóstico completo que estoy mirando</label>' +
    "</div>" +
    '<div class="ls-row">' +
      '<button class="ls-btn ls-btn-primary" id="dr-copiar">📋 Copiar informe</button>' +
      '<button class="ls-btn" id="dr-ver">Ver el informe</button>' +
    "</div>" +
    '<pre class="ls-pre hidden" id="dr-informe" style="margin-top:10px"></pre>';

  /* ═════════════════════════════════════════════════════════════════════════
   * PINTAR
   * ═══════════════════════════════════════════════════════════════════════ */
  const nombre = () => S.dev.name || "este celular";

  function alerta(clase, titulo, detalle) {
    return '<div class="ls-alert ' + clase + '"><div class="ls-alert-body"><b>' + esc(titulo) + "</b>" +
      (detalle ? "<small>" + esc(detalle) + "</small>" : "") + "</div></div>";
  }

  function pintarEstado(forzar) {
    const ahora = Date.now();
    const st = DL.describeStatus(S.dev, S.meta, ahora);
    const sum = DL.summarize(S.events, ahora);
    const firma = [S.loaded, st, S.err && (S.err.code || S.err.message), sum.rows, sum.errors24h, sum.newestAt, sum.lastFatal && sum.lastFatal.key];
    // Los botones dependen del estado, así que se actualizan siempre; el HTML solo si cambió.
    pintarBotones(st);
    if (!forzar && !cambio("estado", firma)) return;
    let h = "";
    if (S.err) {
      h += alerta("bad", "El panel no pudo leer el registro de este celular.",
        "Lo que contestó Firebase: " + (S.err.message || S.err.code || "error desconocido") + ". Si dice «permission_denied», " +
        "faltan desplegar las reglas de la base de datos (firebase deploy --only database).");
    }
    if (!S.loaded.dev) {
      h += alerta("", "Cargando…", "");
    } else {
      h += alerta(st.level === "off" ? "" : st.level, st.headline, st.detail);
      st.notes.forEach((n) => { h += '<p class="ls-hint">' + esc(n) + "</p>"; });
    }
    if (S.loaded.events) {
      let r;
      if (!sum.rows) r = "Todavía no hay renglones guardados de este celular.";
      else {
        r = sum.rows + " renglones guardados en la nube (del " + DL.fmtFull(sum.oldestAt) + " al " + DL.fmtFull(sum.newestAt) +
          "). Errores y caídas en las últimas 24 h: " + sum.errors24h + ".";
        if (sum.lastFatal) r += " Última caída del programa: " + DL.fmtAgo(ahora - sum.lastFatal.at) + ".";
      }
      h += '<p class="ls-hint" style="margin-top:8px">' + esc(r) + "</p>";
    }
    $("dr-estado").innerHTML = h;
  }

  function pintarBotones(st) {
    st = st || DL.describeStatus(S.dev, S.meta, Date.now());
    const puede = S.loaded.dev && st.canRequest && !S.ui.pidiendo;
    $("dr-pedir").disabled = !puede;
    $("dr-pedir").title = S.loaded.dev && !st.canRequest ? st.headline : "";
    const apagada = S.dev.diagUploadEnabled === false;
    $("dr-interruptor").textContent = apagada ? "Encender la subida del registro" : "Apagar la subida del registro";
    $("dr-interruptor").disabled = !S.loaded.dev || S.dev.diagVersion === null || S.dev.diagVersion === undefined;
  }

  const nivelChip = (l) => '<span class="ls-chip ' + (l === "E" || l === "F" ? "bad" : l === "W" ? "warn" : "") + '">' +
    esc(DL.LEVEL_LABEL[l] || l) + "</span>";

  function filaEvento(e) {
    const primera = e.msg.split("\n")[0];
    const larga = e.msg.indexOf("\n") >= 0 || e.msg.length > 180;
    const cab = '<span class="dr-t" title="' + esc(DL.fmtFull(e.at) + " · renglón " + e.key) + '">' + esc(DL.fmtStamp(e.at)) + "</span>" +
      nivelChip(e.level) + '<span class="dr-g">' + esc(e.tag || "?") + "</span>" +
      (e.count > 1 ? '<span class="ls-chip" title="Se repitió y se agrupó">×' + e.count + "</span>" : "");
    if (!larga) return '<div class="dr-ev l-' + esc(e.level) + '">' + cab + '<span class="dr-m">' + esc(e.msg) + "</span></div>";
    const corta = primera.length > 180 ? primera.slice(0, 180) + "…" : primera;
    return '<details class="dr-ev l-' + esc(e.level) + '" data-k="' + esc(e.key) + '"' + (S.ui.abiertos[e.key] ? " open" : "") + "><summary>" +
      cab + '<span class="dr-m">' + esc(corta) + '</span></summary><pre class="ls-pre">' + esc(e.msg) + "</pre></details>";
  }

  function pintarLista() {
    const ahora = Date.now();
    const lista = DL.filterEvents(S.events, {
      minLevel: S.ui.nivel, sinceMs: DL.windowStart(S.ui.ventana, ahora), text: S.ui.texto, tag: S.ui.etiqueta
    }).slice().reverse();                                   // lo más nuevo arriba
    const vis = lista.slice(0, S.ui.mostrar);
    const firma = [S.loaded.events, !!S.err, S.events.length, S.ui.nivel, S.ui.ventana, S.ui.etiqueta, S.ui.texto, S.ui.mostrar, lista.length,
      vis.length ? vis[0].key + "/" + vis[0].count : "", vis.length ? vis[vis.length - 1].key : ""];
    if (!cambio("lista", firma)) return;

    if (!S.loaded.events) {
      $("dr-lista").innerHTML = S.err
        ? '<p class="ls-empty">No se pudo leer el registro (mirá el aviso de arriba).</p>'
        : '<p class="ls-empty">Cargando…</p>';
      $("dr-resumen").textContent = "";
      $("dr-mas").innerHTML = "";
      return;
    }
    $("dr-resumen").textContent = S.events.length
      ? "Mostrando " + vis.length + " de " + lista.length + " renglones que cumplen el filtro (hay " + S.events.length + " guardados). Lo más nuevo, arriba."
      : "";
    if (!vis.length) {
      $("dr-lista").innerHTML = '<p class="ls-empty">' + (S.events.length
        ? "Ningún renglón cumple este filtro. Probá con «Todo» o con un período más largo."
        : "El celular todavía no subió ningún renglón. Es normal en un equipo recién actualizado, sin red, o con la subida apagada.") + "</p>";
    } else {
      $("dr-lista").innerHTML = '<div class="dr-lista">' + vis.map(filaEvento).join("") + "</div>";
    }
    $("dr-mas").innerHTML = lista.length > vis.length
      ? '<button class="ls-btn ls-btn-sm" id="dr-mas-btn">Mostrar 100 más (quedan ' + (lista.length - vis.length) + ")</button>"
      : "";
  }

  function pintarEtiquetas() {
    const tags = DL.tagCounts(S.events);
    if (!cambio("etiquetas", [tags, S.ui.etiqueta])) return;
    const sel = $("dr-etiqueta");
    sel.innerHTML = '<option value="">Todas las etiquetas</option>' + tags.map((t) =>
      '<option value="' + esc(t.tag) + '"' + (t.tag === S.ui.etiqueta ? " selected" : "") + ">" + esc(t.tag) + " (" + t.n + ")</option>").join("");
  }

  const snapElegido = () => S.snaps.find((s) => s.key === S.ui.snapKey) || S.snaps[0] || null;

  function pintarSnap() {
    const s = snapElegido();
    if (!cambio("snap", [S.loaded.snaps, S.snaps.map((x) => x.key + "/" + x.uploadedAt), s && s.key])) return;
    const sel = $("dr-snap-sel");
    sel.innerHTML = S.snaps.length
      ? S.snaps.map((x) => '<option value="' + esc(x.key) + '"' + (s && x.key === s.key ? " selected" : "") + ">" +
          esc(DL.fmtFull(x.at) + (x.pkgs ? " · " + x.pkgs : "") + (x.ver ? " · LockSuite " + x.ver : "")) + "</option>").join("")
      : '<option value="">(ninguno todavía)</option>';
    sel.disabled = !S.snaps.length;
    if (!s) {
      $("dr-snap").innerHTML = '<p class="ls-empty">' + (S.loaded.snaps
        ? "Todavía no hay ningún diagnóstico completo. Tocá «Pedir diagnóstico completo ahora»."
        : "Cargando…") + "</p>";
      return;
    }
    const secciones = [
      ["Sistema", s.system], ["Cierres recientes del proceso (los anotó Android)", s.exits],
      ["Errores del logcat de LockSuite", s.logcat], ["Apps consultadas", s.packages]
    ];
    $("dr-snap").innerHTML =
      '<p class="ls-hint">Pedido el ' + esc(DL.fmtFull(s.at)) + (s.uploadedAt ? " · llegó " + esc(DL.fmtAgo(Date.now() - s.uploadedAt)) : "") +
      (s.ver ? " · LockSuite " + esc(s.ver) : "") + " · apps: " + esc(s.pkgs || "ninguna en particular") + "</p>" +
      secciones.map((x) => {
        const txt = x[1] || "";
        const n = txt ? txt.split("\n").length : 0;
        return '<details class="ls-card ls-more" open style="margin-bottom:8px"><summary><b>' + esc(x[0]) + "</b> <small>(" +
          (n ? n + (n === 1 ? " línea" : " líneas") : "vacío") + ')</small></summary><pre class="ls-pre" style="margin-top:8px">' +
          esc(txt || "(vacío)") + "</pre></details>";
      }).join("");
  }

  function pintarTodo() {
    try {
      pintarEstado();
      pintarEtiquetas();
      pintarLista();
      pintarSnap();
    } catch (e) {
      // Una falla al dibujar no puede tirar la ficha: se dice acá y listo.
      $("dr-estado").innerHTML = alerta("bad", "No se pudo dibujar el registro.", String((e && e.message) || e));
    }
  }

  /* ═════════════════════════════════════════════════════════════════════════
   * SUSCRIPCIONES (recién al entrar a la sección)
   * ═══════════════════════════════════════════════════════════════════════ */
  function escuchar(query, cb) {
    const h = query.on("value", (snap) => { try { cb(snap); } catch (e) { console.error("diag_remoto:", e); } },
      (err) => { S.err = err || { message: "error desconocido" }; pintarEstado(true); pintarLista(); });
    S.escuchas.push([query, h]);
  }

  function suscribir() {
    if (S.activo) return;
    S.activo = true;
    const base = DL.LAYOUT.ROOT + "/" + deviceId + "/";
    escuchar(LS.db.ref("devices/" + deviceId), (snap) => {
      S.dev = DL.pickDevice(snap.val(), deviceId);
      S.loaded.dev = true;
      pintarEstado();
    });
    escuchar(LS.db.ref(base + DL.LAYOUT.META), (snap) => {
      S.meta = snap.val() || {};
      pintarEstado();
    });
    escuchar(LS.db.ref(base + DL.LAYOUT.EVENTS).limitToLast(DL.LAYOUT.EVENTS_KEEP), (snap) => {
      S.events = DL.parseEvents(snap.val());
      S.loaded.events = true;
      S.err = null;
      pintarTodo();
    });
    escuchar(LS.db.ref(base + DL.LAYOUT.SNAPSHOTS).limitToLast(DL.LAYOUT.SNAPSHOTS_KEEP), (snap) => {
      S.snaps = DL.listSnapshots(snap.val());
      S.loaded.snaps = true;
      pintarSnap();
    });
    cargarApps();
  }

  /** Las apps del celular, para elegir cuáles revisar sin escribir el nombre del paquete. */
  function cargarApps() {
    LS.db.ref("devices/" + deviceId + "/apps").once("value").then((snap) => {
      const v = snap.val() || {};
      const apps = Object.keys(v).map((k) => {
        const a = v[k] || {};
        return { pkg: typeof a.packageName === "string" && a.packageName ? a.packageName : LS.keyPkg(k),
          label: typeof a.label === "string" && a.label ? a.label : "", usuario: a.appType === "Usuario" };
      }).filter((a) => DL.normalizePackages(a.pkg).list.length === 1);
      apps.sort((a, b) => (b.usuario - a.usuario) || (a.label || a.pkg).localeCompare(b.label || b.pkg));
      $("dr-pkg-sel").innerHTML = '<option value="">＋ elegir una app del celular…</option>' + apps.map((a) =>
        '<option value="' + esc(a.pkg) + '">' + esc((a.label ? a.label + " — " : "") + a.pkg) + "</option>").join("");
    }).catch(() => { /* sin la lista se escribe a mano: no es grave */ });
  }

  const sec = document.querySelector('.ls-section[data-sec="diag"]');
  let autenticado = false;
  function chequear() {
    if (autenticado && sec && sec.classList.contains("active")) suscribir();
  }
  if (sec) new MutationObserver(chequear).observe(sec, { attributes: true, attributeFilter: ["class"] });
  LS.requireAuth(() => { autenticado = true; chequear(); });
  // «hace 5 min» se va quedando viejo si nadie toca nada: se refresca una vez por minuto, y solo si se está mirando.
  setInterval(() => { if (S.activo && sec && sec.classList.contains("active")) pintarEstado(true); }, 60000);

  /* ═════════════════════════════════════════════════════════════════════════
   * ACCIONES
   * ═══════════════════════════════════════════════════════════════════════ */
  const msg = (t) => { $("dr-pedir-msg").textContent = t || ""; };

  $("dr-pedir").addEventListener("click", async () => {
    const p = DL.normalizePackages($("dr-pkgs").value);
    if (p.invalid.length) {
      LS.toast("Estos nombres de app no son válidos (tienen que ser como com.whatsapp): " + p.invalid.join(", "), "error");
      return;
    }
    if (p.tooMany) LS.toast("Se revisan como mucho " + DL.LAYOUT.MAX_PACKAGES + " apps por pedido: se mandan las primeras " + DL.LAYOUT.MAX_PACKAGES + ".", "warn");
    S.ui.pidiendo = true;
    pintarBotones();
    msg("Pidiéndole el diagnóstico a " + nombre() + "… puede tardar hasta 25 segundos.");
    const r = await LS.sendCommand(deviceId, "COLLECT_DIAGNOSTICS", p.list.length ? p.list.join(",") : null, null,
      { nombre: nombre(), timeoutMs: 25000 });
    S.ui.pidiendo = false;
    pintarBotones();
    const [texto, tipo] = LS.resultado(r, "Listo: el diagnóstico llegó y está más abajo.");
    LS.toast(texto, tipo);
    msg(texto);
    if (r.ok && !r.pendiente) {
      S.ui.snapKey = "";                                   // seguir al más nuevo
      pintarSnap();
      try { $("dr-snap").scrollIntoView({ behavior: "smooth", block: "nearest" }); } catch (e) { /* da igual */ }
    }
  });

  $("dr-interruptor").addEventListener("click", async function () {
    const apagar = S.dev.diagUploadEnabled !== false;      // lo que se quiere hacer ahora
    if (apagar && !confirm("¿Apagar la subida del registro de " + nombre() + "?\n\n" +
      "El celular sigue anotando adentro, pero no manda nada: no vas a ver errores nuevos acá hasta que la vuelvas a encender.")) return;
    this.disabled = true;
    const r = await LS.sendCommand(deviceId, "SET_DIAGNOSTICS_UPLOAD", null, { enabled: !apagar }, { nombre: nombre() });
    this.disabled = false;
    LS.toast(...LS.resultado(r, apagar ? "Subida del registro apagada." : "Subida del registro encendida."));
    pintarBotones();
  });

  $("dr-pkg-sel").addEventListener("change", function () {
    if (!this.value) return;
    const actual = DL.normalizePackages($("dr-pkgs").value).list;
    if (actual.indexOf(this.value) < 0) actual.push(this.value);
    $("dr-pkgs").value = actual.join(", ");
    this.value = "";
  });

  // filtros de la lista
  $("dr-nivel").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-n]");
    if (!b) return;
    S.ui.nivel = b.dataset.n;
    S.ui.mostrar = 100;
    document.querySelectorAll("#dr-nivel button").forEach((x) => x.classList.toggle("on", x === b));
    pintarLista();
  });
  $("dr-ventana").addEventListener("change", function () { S.ui.ventana = this.value; S.ui.mostrar = 100; pintarLista(); });
  $("dr-etiqueta").addEventListener("change", function () { S.ui.etiqueta = this.value; S.ui.mostrar = 100; firmas.etiquetas = null; pintarLista(); });
  $("dr-texto").addEventListener("input", function () { S.ui.texto = this.value; S.ui.mostrar = 100; pintarLista(); });
  $("dr-mas").addEventListener("click", (e) => {
    if (!e.target.closest("#dr-mas-btn")) return;
    S.ui.mostrar += 100;
    pintarLista();
  });
  // Recordar qué renglones están abiertos (el evento `toggle` no burbujea: se escucha en captura).
  $("dr-lista").addEventListener("toggle", (e) => {
    const d = e.target;
    if (d && d.dataset && d.dataset.k) S.ui.abiertos[d.dataset.k] = d.open;
  }, true);

  $("dr-snap-sel").addEventListener("change", function () {
    S.ui.snapKey = this.value;
    firmas.snap = null;
    pintarSnap();
  });

  /* ── informe para la IA ── */
  function armarInforme() {
    const ahora = Date.now();
    return DL.buildReport({
      device: S.dev, meta: S.meta, events: S.events, snapshot: snapElegido(), note: $("dr-nota").value, now: ahora,
      eventOptions: { minLevel: $("dr-inf-nivel").value, sinceMs: DL.windowStart($("dr-inf-ventana").value, ahora) },
      includeSnapshot: $("dr-inc-snap").checked
    });
  }

  function mostrarInforme(texto) {
    const pre = $("dr-informe");
    pre.textContent = texto;                                // textContent: el informe es texto, nunca HTML
    pre.classList.remove("hidden");
  }

  $("dr-ver").addEventListener("click", () => {
    const pre = $("dr-informe");
    if (!pre.classList.contains("hidden")) { pre.classList.add("hidden"); return; }
    mostrarInforme(armarInforme());
  });

  $("dr-copiar").addEventListener("click", async () => {
    const texto = armarInforme();
    try {
      await navigator.clipboard.writeText(texto);
      LS.toast("Informe copiado (" + texto.length + " caracteres). Pegalo en el chat con Claude.", "ok");
    } catch (e) {
      mostrarInforme(texto);
      LS.toast("El navegador no dejó copiar: el informe quedó abajo, seleccionalo a mano.", "warn");
    }
  });

  pintarTodo();
})();
