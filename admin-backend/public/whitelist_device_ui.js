/* ═══════════════════════════════════════════════════════════════════════════
 * whitelist_device_ui.js — Lista blanca POR DISPOSITIVO (estilo Lockfox) dentro
 * de `celular.html`, oculta mientras `whitelistEnabled !== true`.
 * ═══════════════════════════════════════════════════════════════════════════ */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const esc = LS.esc;
  const IO = window.DomCatalogoIO;
  const Clasif = window.DomClasif;

  const MODES = [
    {
      id: "simulacion",
      title: "Simulación (solo anota)",
      desc: "No bloquea nada todavía: registra qué dominios quedarían afuera para que completes las listas usando el equipo real."
    },
    {
      id: "estricta",
      title: "Lista blanca — ESTRICTA",
      desc: "Solo resuelven los dominios permitidos de este catálogo. Todo lo demás queda bloqueado en el celular."
    }
  ];

  const WL = {
    deviceId: null,
    getOpc: null,
    device: {},
    installedMap: {},
    deviceName: "Celular",
    cloudRaw: undefined,       // undefined = cargando; null = sin nodo propio (usa default)
    working: null,             // { apps, globalAllow, globalBlock, summary, fingerprint }
    baseline: null,
    hasOwnCloudData: false,
    dirty: false,
    saving: false,
    editingPkg: null,          // null o string; "" = nueva app
    pendingAuditDomain: null,
    listenerRef: null,
    expandedApps: new Set(),
    backups: {},
    backupsRef: null,
    backupsLoaded: false,
    auditSearch: "",
    initialized: false,

    defaultCatalogSnapshot() {
      if (this._defaultSnap) return this._defaultSnap;
      const raw = window.LOCKSUITE_DEFAULT_WHITELIST_TXT || "";
      const parsed = IO.parseCatalogTxt(raw);
      if (parsed && !parsed.error) {
        this._defaultSnap = IO.buildSnapshot(parsed.apps, parsed.globalAllow, parsed.globalBlock);
      } else {
        this._defaultSnap = IO.buildSnapshot([], [], []);
      }
      return this._defaultSnap;
    },

    init(deviceId, getOpc) {
      if (this.initialized) return;
      this.initialized = true;
      this.deviceId = deviceId;
      this.getOpc = getOpc;
      this.working = this.defaultCatalogSnapshot();
      this.baseline = this.working;

      this.bindEvents();

      this.listenerRef = LS.db.ref("deviceWhitelists/" + deviceId);
      this.listenerRef.on("value", (snap) => {
        const val = snap.val();
        this.cloudRaw = val;
        this.hasOwnCloudData = !!(val && typeof val === "object");
        const incoming = this.hasOwnCloudData
          ? IO.buildSnapshot(val.apps || {}, val.globalAllow || [], val.globalBlock || [])
          : this.defaultCatalogSnapshot();
        this.baseline = incoming;
        if (!this.dirty) {
          this.working = incoming;
          this.renderAll();
        } else {
          this.renderToolbarStatus();
        }
      }, (err) => {
        LS.toast("No se pudo leer la lista blanca del celular: " + err.message, "error");
      });
    },

    campo(key, fallback) {
      const d = this.device;
      if (d && d[key] !== undefined && d[key] !== null) return d[key];
      if (d && d.info && d.info[key] !== undefined && d.info[key] !== null) return d.info[key];
      return fallback;
    },

    updateDevice(device, installedMap, deviceName) {
      this.device = device || {};
      this.installedMap = installedMap || {};
      this.deviceName = deviceName || "Celular";
      if (!this.initialized) return;
      this.renderMode();
      this.renderAudit();
      this.renderApps();
      this.renderRailBadge();
    },

    matchesQuery(q) {
      if (!this.campo("whitelistEnabled", false)) return false;
      const snap = this.working || this.defaultCatalogSnapshot();
      return snap.apps.some((a) =>
        a.label.toLowerCase().includes(q) ||
        a.packageName.toLowerCase().includes(q) ||
        a.allow.concat(a.block).some((d) => d.includes(q))) ||
        snap.globalAllow.concat(snap.globalBlock).some((d) => d.includes(q));
    },

    markDirty(nextSnap) {
      this.working = IO.normalizeStoredSnapshot(nextSnap);
      this.dirty = this.baseline ? IO.snapshotsDiffer(this.working, this.baseline) : true;
      this.renderAll();
    },

    renderRailBadge() {
      const badge = $("cnt-wl");
      if (!badge) return;
      const snap = this.working || this.defaultCatalogSnapshot();
      badge.textContent = String(snap.summary.appsCount);
    },

    renderAll() {
      this.renderRailBadge();
      this.renderToolbarStatus();
      this.renderMode();
      this.renderApps();
      this.renderGlobalLists();
      this.renderAudit();
    },

    renderToolbarStatus() {
      const el = $("wl-status-chip");
      const saveBtn = $("wl-save-sync-btn");
      const globalSaveBtn = $("wl-global-save-btn");
      if (el) {
        if (this.dirty) {
          el.className = "ls-chip warn";
          el.textContent = "Cambios sin guardar en el celular";
        } else if (this.hasOwnCloudData) {
          el.className = "ls-chip ok";
          el.textContent = "Configuración propia guardada";
        } else {
          el.className = "ls-chip";
          el.textContent = "Catálogo por defecto precargado";
        }
      }
      if (saveBtn) saveBtn.disabled = this.saving;
      if (globalSaveBtn) globalSaveBtn.disabled = this.saving;
    },

    renderMode() {
      const modCont = $("wl-modes");
      const cdnCont = $("wl-cdn");
      if (!modCont || !cdnCont) return;
      const isSim = this.campo("whitelistSimulation", true) !== false;
      const act = isSim ? "simulacion" : "estricta";
      const cdn = this.campo("whitelistSharedCdn", true) !== false;

      modCont.innerHTML = MODES.map((m) =>
        '<button type="button" class="ls-mode ' + (m.id === act ? "selected" : "") + '" data-wl-modo="' + m.id + '">' +
        '<div class="ls-mode-title">' + esc(m.title) + '</div>' +
        '<div class="ls-mode-desc">' + esc(m.desc) + '</div></button>'
      ).join("");

      cdnCont.innerHTML =
        '<div class="ls-polrow" style="border:1px solid var(--md-sys-color-outline-variant,#2A3647); border-radius:10px; padding:10px 14px">' +
        '<label for="wl-cdn-sw">Permitir CDN compartidos (CloudFront, Akamai, Fastly…)<br>' +
        '<small style="color:var(--text-muted)">Varias apps los usan, pero permitir la base amplia abre mucho tráfico. Apagalo si querés máxima estrictez.</small></label>' +
        '<span class="ls-sw"><input type="checkbox" id="wl-cdn-sw"' + (cdn ? " checked" : "") + '><span></span></span></div>';

      const sw = $("wl-cdn-sw");
      if (sw) {
        sw.addEventListener("change", async function () {
          this.disabled = true;
          const cmd = this.checked ? "ENABLE_WHITELIST_SHARED_CDN" : "DISABLE_WHITELIST_SHARED_CDN";
          const r = await LS.sendCommand(WL.deviceId, cmd, null, null, WL.getOpc());
          this.disabled = false;
          if (!r.ok) this.checked = !this.checked;
          LS.toast(...LS.resultado(r, "Preferencia de CDN aplicada."));
        });
      }
    },

    renderApps() {
      const grid = $("wl-apps-grid");
      const searchEl = $("wl-apps-search");
      const filterEl = $("wl-apps-filter");
      if (!grid) return;

      const snap = this.working || this.defaultCatalogSnapshot();
      const q = (searchEl ? searchEl.value : "").trim().toLowerCase();
      const filtro = filterEl ? filterEl.value : "all";
      const inst = this.installedMap || {};

      let apps = snap.apps.slice().sort((a, b) =>
        a.label.localeCompare(b.label, "es") || a.packageName.localeCompare(b.packageName));

      if (q) {
        apps = apps.filter((a) =>
          a.label.toLowerCase().includes(q) ||
          a.packageName.toLowerCase().includes(q) ||
          a.allow.some((d) => d.includes(q)) ||
          a.block.some((d) => d.includes(q)));
      }
      if (filtro === "enabled") apps = apps.filter((a) => a.status === "enabled");
      else if (filtro === "disabled") apps = apps.filter((a) => a.status === "disabled");
      else if (filtro === "blocked") apps = apps.filter((a) => a.status === "blocked");
      else if (filtro === "noweb") apps = apps.filter((a) => a.access === "noweb");
      else if (filtro === "installed") apps = apps.filter((a) => !!inst[a.packageName]);

      if (!apps.length) {
        grid.innerHTML = '<p class="ls-empty">No hay apps que coincidan con el filtro.</p>';
        return;
      }

      const renderDoms = (pkg, list, kind) => {
        if (!list.length) return '<span class="ls-hint" style="margin:0; font-size:11px">— ninguno —</span>';
        const expanded = this.expandedApps.has(pkg + "|" + kind);
        const shown = expanded ? list : list.slice(0, 8);
        const rest = list.length - shown.length;
        return '<div class="ls-domlist">' +
          shown.map((d) => '<span class="ls-dom ' + kind + '">' + esc(d) + '</span>').join("") +
          (rest > 0
            ? '<button type="button" class="ls-btn ls-btn-sm" data-wl-more="' + esc(pkg + "|" + kind) + '">+' + rest + ' más</button>'
            : (expanded && list.length > 8
              ? '<button type="button" class="ls-btn ls-btn-sm" data-wl-more="' + esc(pkg + "|" + kind) + '">Ver menos</button>'
              : "")) +
          '</div>';
      };

      grid.innerHTML = apps.map((a) => {
        const stCls = a.status === "enabled" ? "ok" : a.status === "blocked" ? "bad" : "";
        const stLbl = a.status === "enabled" ? "Habilitada" : a.status === "blocked" ? "Bloqueada" : "Deshabilitada";
        const isInst = !!inst[a.packageName];
        return '<div class="ls-wl-card st-' + esc(a.status) + '" data-wl-pkg="' + esc(a.packageName) + '">' +
          '<div class="ls-wl-card-head">' +
            '<div class="ls-appgrow">' +
              '<div class="ls-appname">' + esc(a.label) + '</div>' +
              '<div class="ls-apppkg">' + esc(a.packageName) + '</div>' +
            '</div>' +
            '<div class="ls-wl-card-actions">' +
              '<button type="button" class="ls-btn ls-btn-sm" data-wl-toggle="1">' +
                (a.status === "enabled" ? "Deshabilitar" : "Habilitar") + '</button>' +
              '<button type="button" class="ls-btn ls-btn-sm ls-btn-primary" data-wl-edit="1">✏️ Editar</button>' +
            '</div>' +
          '</div>' +
          '<div class="ls-chiprow" style="margin-top:6px">' +
            '<span class="ls-chip ' + stCls + '">' + esc(stLbl) + '</span>' +
            (a.access === "noweb" ? '<span class="ls-chip warn">🚫 Sin web</span>' : "") +
            (isInst ? '<span class="ls-chip">Instalada</span>' : "") +
          '</div>' +
          '<div class="ls-wl-doms">' +
            '<div class="ls-domtitle">✅ Permitidos (' + a.allow.length + ')</div>' +
            renderDoms(a.packageName, a.allow, "allow") +
            '<div class="ls-domtitle">🚫 Prohibidos (' + a.block.length + ')</div>' +
            renderDoms(a.packageName, a.block, "block") +
          '</div>' +
        '</div>';
      }).join("");
    },

    renderGlobalLists() {
      const snap = this.working || this.defaultCatalogSnapshot();
      const allowCnt = $("wl-gallow-count");
      const blockCnt = $("wl-gblock-count");
      if (allowCnt) allowCnt.textContent = String(snap.globalAllow.length);
      if (blockCnt) blockCnt.textContent = String(snap.globalBlock.length);

      const renderBox = (list, searchId, boxId, kind) => {
        const box = $(boxId);
        if (!box) return;
        const q = ($(searchId) ? $(searchId).value : "").trim().toLowerCase();
        const filtered = q ? list.filter((d) => d.includes(q)) : list;
        if (!filtered.length) {
          box.innerHTML = '<p class="ls-empty">' + (list.length ? "Sin coincidencias." : "Lista vacía.") + '</p>';
          return;
        }
        box.innerHTML = filtered.slice(0, 300).map((d) =>
          '<div class="ls-wl-list-row">' +
            '<span class="ls-wl-list-dom">' + esc(d) + '</span>' +
            '<button type="button" class="ls-btn ls-btn-sm ls-btn-danger" data-wl-gdel="' + esc(kind) + '" data-dom="' + esc(d) + '" title="Quitar">✕</button>' +
          '</div>'
        ).join("") + (filtered.length > 300 ? '<p class="ls-hint" style="margin:6px 0 0">…y ' + (filtered.length - 300) + ' más (filtrá arriba).</p>' : "");
      };

      renderBox(snap.globalAllow, "wl-gallow-search", "wl-gallow-items", "allow");
      renderBox(snap.globalBlock, "wl-gblock-search", "wl-gblock-items", "block");
    },

    renderAudit() {
      const cont = $("wl-audit-list");
      const cntEl = $("wl-audit-count");
      if (!cont) return;
      const audit = this.campo("whitelistAudit", {}) || {};
      const q = this.auditSearch.trim().toLowerCase();
      let rows = Object.keys(audit).map((k) => audit[k]).filter((r) => r && r.domain);
      rows.sort((a, b) => (b.hits || 0) - (a.hits || 0));
      if (cntEl) cntEl.textContent = String(rows.length);
      if (q) rows = rows.filter((r) => r.domain.toLowerCase().includes(q));

      if (!rows.length) {
        cont.innerHTML = '<p class="ls-empty">' +
          (this.campo("whitelistEnabled", false)
            ? "Todavía no hay dominios bloqueados o simulados registrados."
            : "Activá el modo lista blanca para registrar dominios.") + "</p>";
        return;
      }

      cont.innerHTML = rows.slice(0, 150).map((r) => {
        const cls = Clasif ? Clasif.clasificar(r.domain) : null;
        const badgeCls = cls ? (cls.accion === "abrir" ? "ok" : cls.accion === "bloquear" ? "bad" : "warn") : "";
        const badgeTxt = cls ? (cls.accion === "abrir" ? "Sugerido: abrir" : cls.accion === "bloquear" ? "Sugerido: prohibir" : "Revisar") : "";
        return '<div class="ls-wl-audit-row" data-audit-dom="' + esc(r.domain) + '">' +
          '<div class="ls-appgrow">' +
            '<div class="ls-wl-audit-dom">' + esc(r.domain) +
              ' <span class="ls-chip">' + esc(String(r.hits || 1)) + 'x</span>' +
              (badgeTxt ? ' <span class="ls-chip ' + badgeCls + '">' + esc(badgeTxt) + ' · ' + esc(cls.categoria) + '</span>' : "") +
            '</div>' +
            (cls && cls.motivo ? '<div class="ls-hint" style="margin:3px 0 0; font-size:11.5px">' + esc(cls.motivo) + '</div>' : "") +
          '</div>' +
          '<div class="ls-wl-audit-actions">' +
            '<button type="button" class="ls-btn ls-btn-sm" data-audit-act="app">+ App</button>' +
            '<button type="button" class="ls-btn ls-btn-sm" data-audit-act="allow">✅ Celular</button>' +
            '<button type="button" class="ls-btn ls-btn-sm ls-btn-danger" data-audit-act="block">🚫 Prohibir</button>' +
          '</div>' +
        '</div>';
      }).join("");
    },

    async saveBackupEntry(snapshotToSave, reason, note, favorite) {
      if (!this.deviceId || !snapshotToSave) return null;
      const norm = IO.normalizeStoredSnapshot(snapshotToSave);
      const user = LS.auth.currentUser;
      const now = Date.now();
      const ref = await LS.db.ref("deviceWhitelistBackups/" + this.deviceId).push({
        createdAt: now,
        createdBy: (user && (user.email || user.uid)) || "admin",
        reason: reason || "auto",
        note: note || "",
        favorite: !!favorite,
        fingerprint: norm.fingerprint,
        summary: norm.summary,
        apps: norm.apps,
        globalAllow: norm.globalAllow,
        globalBlock: norm.globalBlock
      });
      return ref && ref.key ? ref.key : null;
    },

    async pruneExpiredBackups() {
      try {
        const snap = await LS.db.ref("deviceWhitelistBackups/" + this.deviceId).once("value");
        const map = snap.val() || {};
        const expired = IO.findExpiredBackupIds(map, Date.now());
        if (!expired.length) return;
        const updates = {};
        expired.forEach((id) => { updates[id] = null; });
        await LS.db.ref("deviceWhitelistBackups/" + this.deviceId).update(updates);
      } catch (_) {}
    },

    async persistAndSync(nextSnapshot, reason) {
      if (this.saving) return false;
      this.saving = true;
      this.renderToolbarStatus();
      const target = IO.normalizeStoredSnapshot(nextSnapshot);
      const prev = this.baseline || this.defaultCatalogSnapshot();

      try {
        if (this.hasOwnCloudData && IO.snapshotsDiffer(prev, target)) {
          await this.saveBackupEntry(prev, reason || "auto_before_save", "", false);
          this.pruneExpiredBackups();
        }

        const appsMap = {};
        target.apps.forEach((a) => {
          appsMap[LS.pkgKey(a.packageName)] = {
            packageName: a.packageName,
            label: a.label,
            status: a.status,
            enabled: a.status === "enabled",
            access: a.access,
            allow: a.allow,
            block: a.block
          };
        });
        const user = LS.auth.currentUser;
        await LS.db.ref("deviceWhitelists/" + this.deviceId).set({
          apps: appsMap,
          globalAllow: target.globalAllow,
          globalBlock: target.globalBlock,
          updatedAt: Date.now(),
          updatedBy: (user && (user.email || user.uid)) || "admin"
        });

        this.baseline = target;
        this.working = target;
        this.hasOwnCloudData = true;
        this.dirty = false;
        this.renderAll();

        const r = await LS.sendCommand(this.deviceId, "SYNC_WHITELIST", null, null, this.getOpc({ timeoutMs: 25000 }));
        LS.toast(...LS.resultado(r, "Lista blanca guardada y aplicada en " + this.deviceName + "."));
        return true;
      } catch (err) {
        LS.toast("No se pudo guardar la lista blanca: " + err.message, "error");
        return false;
      } finally {
        this.saving = false;
        this.renderToolbarStatus();
      }
    },

    openAppModal(pkg) {
      const snap = this.working || this.defaultCatalogSnapshot();
      const isNew = !pkg;
      const app = isNew
        ? { packageName: "", label: "", status: "enabled", access: "domains", allow: [], block: [] }
        : (snap.apps.find((a) => a.packageName === pkg) || { packageName: pkg, label: pkg, status: "enabled", access: "domains", allow: [], block: [] });

      this.editingPkg = isNew ? "" : app.packageName;
      $("wl-app-modal-title").textContent = isNew ? "➕ Nueva App en Lista blanca" : "✏️ Editar " + app.label;
      $("wl-app-label").value = app.label || "";
      $("wl-app-pkg").value = app.packageName || "";
      $("wl-app-pkg").disabled = !isNew;
      $("wl-app-status").value = app.status || "enabled";
      $("wl-app-noweb").checked = app.access === "noweb";
      $("wl-app-allow").value = (app.allow || []).join("\n");
      $("wl-app-block").value = (app.block || []).join("\n");
      $("wl-app-error").textContent = "";
      $("wl-app-delete").classList.toggle("hidden", isNew);

      const instPicker = $("wl-app-pick-installed");
      const instWrap = $("wl-app-pick-installed-wrap");
      if (instPicker && instWrap) {
        instWrap.classList.toggle("hidden", !isNew);
        const list = Object.values(this.installedMap || {}).sort((a, b) =>
          (a.label || a.packageName).localeCompare(b.label || b.packageName, "es"));
        instPicker.innerHTML = '<option value="">— Elegir app instalada en este celular —</option>' +
          list.map((a) => '<option value="' + esc(a.packageName) + '">' + esc(a.label || a.packageName) + " (" + esc(a.packageName) + ")</option>").join("");
        instPicker.value = "";
      }

      $("wl-app-modal").classList.remove("hidden");
    },

    closeAppModal() {
      $("wl-app-modal").classList.add("hidden");
      this.editingPkg = null;
    },

    async openHistoryModal() {
      $("wl-history-modal").classList.remove("hidden");
      await this.loadBackups();
    },

    closeHistoryModal() {
      $("wl-history-modal").classList.add("hidden");
    },

    async loadBackups() {
      const listEl = $("wl-history-list");
      const stEl = $("wl-history-status");
      const sumEl = $("wl-history-current-summary");
      const snap = this.working || this.defaultCatalogSnapshot();
      if (sumEl) {
        sumEl.textContent = snap.summary.appsCount + " apps · " + snap.summary.appAllowCount + " permitidos app · " +
          snap.summary.globalAllowCount + " permitidos / " + snap.summary.globalBlockCount + " prohibidos globales";
      }
      if (stEl) stEl.textContent = "Cargando historial…";
      try {
        await this.pruneExpiredBackups();
        const fbSnap = await LS.db.ref("deviceWhitelistBackups/" + this.deviceId).once("value");
        this.backups = fbSnap.val() || {};
        this.backupsLoaded = true;
        if (stEl) stEl.textContent = "";
        this.renderHistoryList();
      } catch (err) {
        if (stEl) stEl.textContent = "Error leyendo respaldos: " + err.message;
        if (listEl) listEl.innerHTML = "";
      }
    },

    renderHistoryList() {
      const listEl = $("wl-history-list");
      const cntEl = $("wl-history-count");
      const onlyFav = $("wl-history-only-favs") && $("wl-history-only-favs").checked;
      if (!listEl) return;

      let entries = Object.keys(this.backups).map((id) => Object.assign({ id }, this.backups[id] || {}))
        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
      if (onlyFav) entries = entries.filter((e) => e.favorite === true);
      if (cntEl) cntEl.textContent = String(entries.length);

      if (!entries.length) {
        listEl.innerHTML = '<p class="ls-empty">No hay respaldos guardados para este celular.</p>';
        return;
      }

      listEl.innerHTML = entries.map((b) => {
        const sum = b.summary || IO.buildSnapshot(b.apps || [], b.globalAllow || [], b.globalBlock || []).summary;
        const expDays = IO.daysUntilExpiry(b, Date.now());
        return '<div class="ls-wl-backup-row" data-bk-id="' + esc(b.id) + '">' +
          '<div class="ls-wl-backup-main">' +
            '<div><b>' + esc(LS.fecha(b.createdAt)) + '</b> · <span class="ls-chip">' + esc(b.reason || "copia") + '</span> ' +
              (b.favorite ? '<span class="ls-chip ok">⭐ Favorito</span>' : (expDays !== null ? '<span class="ls-chip">vence en ' + expDays + ' d</span>' : "")) +
            '</div>' +
            '<div class="ls-hint" style="margin:4px 0 0">' +
              esc(String(sum.appsCount || 0)) + ' apps · +' + esc(String(sum.globalAllowCount || 0)) + '/-' + esc(String(sum.globalBlockCount || 0)) + ' globales' +
              (b.createdBy ? ' · por ' + esc(b.createdBy) : "") +
            '</div>' +
            (b.note ? '<div style="font-size:12px; color:var(--accent); margin-top:4px">📝 ' + esc(b.note) + '</div>' : "") +
          '</div>' +
          '<div class="ls-wl-backup-actions">' +
            '<button type="button" class="ls-btn ls-btn-sm" data-bk-act="fav">' + (b.favorite ? "⭐" : "☆") + '</button>' +
            '<button type="button" class="ls-btn ls-btn-sm" data-bk-act="note">📝</button>' +
            '<button type="button" class="ls-btn ls-btn-sm" data-bk-act="download">⬇️</button>' +
            '<button type="button" class="ls-btn ls-btn-sm ls-btn-primary" data-bk-act="restore">♻️ Restaurar</button>' +
            '<button type="button" class="ls-btn ls-btn-sm ls-btn-danger" data-bk-act="delete">🗑️</button>' +
          '</div>' +
        '</div>';
      }).join("");
    },

    downloadText(filename, text) {
      const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => { a.remove(); URL.revokeObjectURL(url); }, 1000);
    },

    openPickAppModal(domain) {
      this.pendingAuditDomain = domain;
      $("wl-pick-dom").textContent = domain;
      $("wl-pick-search").value = "";
      this.renderPickAppList();
      $("wl-pick-app-modal").classList.remove("hidden");
    },

    renderPickAppList() {
      const box = $("wl-pick-app-list");
      const q = ($("wl-pick-search").value || "").trim().toLowerCase();
      const snap = this.working || this.defaultCatalogSnapshot();
      const apps = snap.apps.slice().sort((a, b) => a.label.localeCompare(b.label, "es"))
        .filter((a) => !q || a.label.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q));
      box.innerHTML = apps.length
        ? apps.map((a) =>
            '<button type="button" class="ls-wl-pick-item" data-pick-pkg="' + esc(a.packageName) + '">' +
              '<b>' + esc(a.label) + '</b><br><span class="ls-apppkg">' + esc(a.packageName) + '</span>' +
            '</button>').join("")
        : '<p class="ls-empty">No hay apps que coincidan.</p>';
    },

    bindEvents() {
      // Modo simulación vs estricta
      $("wl-modes").addEventListener("click", async (e) => {
        const b = e.target.closest("[data-wl-modo]");
        if (!b) return;
        const targetMode = b.dataset.wlModo;
        const currentlySim = this.campo("whitelistSimulation", true) !== false;
        if ((targetMode === "simulacion") === currentlySim) return;
        if (targetMode === "estricta") {
          const audCount = Object.keys(this.campo("whitelistAudit", {}) || {}).length;
          if (!confirm(
            "¿Pasar " + this.deviceName + " a Lista blanca ESTRICTA?\n\n" +
            "A partir de ahora solo resolverán los dominios permitidos de este catálogo. " +
            (audCount ? "Hay " + audCount + " dominio(s) registrado(s) en la auditoría." : "⚠️ Todavía no hay dominios en auditoría.") +
            "\n\n¿Continuar?"
          )) return;
        }
        b.disabled = true;
        const r = await LS.sendCommand(this.deviceId, targetMode === "simulacion" ? "SET_WHITELIST_SIMULATION" : "SET_WHITELIST_ENFORCE", null, null, this.getOpc());
        b.disabled = false;
        LS.toast(...LS.resultado(r, "Modo actualizado."));
      });

      $("wl-disable-btn").addEventListener("click", async function () {
        if (!confirm("¿Apagar el modo lista blanca en " + WL.deviceName + "?\n\nEl celular vuelve al modo lista negra normal y esta sección se oculta. Tu catálogo guardado no se pierde.")) return;
        this.disabled = true;
        const r = await LS.sendCommand(WL.deviceId, "DISABLE_WHITELIST_MODE", null, null, WL.getOpc());
        this.disabled = false;
        LS.toast(...LS.resultado(r, "Modo lista blanca apagado."));
      });

      $("wl-add-app-btn").addEventListener("click", () => this.openAppModal(null));
      $("wl-save-sync-btn").addEventListener("click", () => this.persistAndSync(this.working, "save_manual"));
      $("wl-global-save-btn").addEventListener("click", () => this.persistAndSync(this.working, "save_global_lists"));
      $("wl-history-btn").addEventListener("click", () => this.openHistoryModal());

      $("wl-download-btn").addEventListener("click", () => {
        const snap = this.working || this.defaultCatalogSnapshot();
        const txt = IO.serializeCatalogTxt(snap, { deviceName: this.deviceName, reason: "Descarga manual" });
        this.downloadText(IO.formatBackupFilename(Date.now(), this.deviceName), txt);
      });

      $("wl-reset-default-btn").addEventListener("click", async () => {
        if (!confirm("¿Restaurar el catálogo por defecto para " + this.deviceName + "?\n\nSe guardará un respaldo de la configuración actual antes de reemplazar.")) return;
        await this.persistAndSync(this.defaultCatalogSnapshot(), "reset_default");
      });

      $("wl-upload-file").addEventListener("change", async (e) => {
        const file = e.target.files && e.target.files[0];
        e.target.value = "";
        if (!file) return;
        try {
          const text = await IO.readTextFile(file);
          const parsed = IO.parseCatalogTxt(text);
          if (parsed.error) { LS.toast(parsed.error, "error"); return; }
          const next = IO.buildSnapshot(parsed.apps, parsed.globalAllow, parsed.globalBlock);
          const warns = (parsed.warnings.invalid || []).concat(parsed.warnings.rejected || []);
          const msg = "Se cargarán " + next.summary.appsCount + " apps (" + next.summary.appAllowCount +
            " permitidos, " + next.summary.appBlockCount + " prohibidos), " +
            next.summary.globalAllowCount + " permitidos globales y " +
            next.summary.globalBlockCount + " prohibidos globales.\n\n" +
            (warns.length ? "Omitidos por formato/seguridad (" + warns.length + "): " + warns.slice(0, 5).join(", ") + "\n\n" : "") +
            "¿Aplicar a " + this.deviceName + "?";
          if (!confirm(msg)) return;
          await this.persistAndSync(next, "upload_file");
        } catch (err) {
          LS.toast("No se pudo leer el archivo: " + err.message, "error");
        }
      });

      $("wl-apps-search").addEventListener("input", () => this.renderApps());
      $("wl-apps-filter").addEventListener("change", () => this.renderApps());

      $("wl-apps-grid").addEventListener("click", (e) => {
        const more = e.target.closest("[data-wl-more]");
        if (more) {
          const k = more.dataset.wlMore;
          if (this.expandedApps.has(k)) this.expandedApps.delete(k); else this.expandedApps.add(k);
          this.renderApps();
          return;
        }
        const card = e.target.closest("[data-wl-pkg]");
        if (!card) return;
        const pkg = card.dataset.wlPkg;
        if (e.target.closest("[data-wl-edit]")) { this.openAppModal(pkg); return; }
        if (e.target.closest("[data-wl-toggle]")) {
          const snap = this.working || this.defaultCatalogSnapshot();
          const apps = snap.apps.map((a) => {
            if (a.packageName !== pkg) return a;
            const nextSt = a.status === "enabled" ? "disabled" : "enabled";
            return Object.assign({}, a, { status: nextSt, enabled: nextSt === "enabled" });
          });
          this.markDirty({ apps, globalAllow: snap.globalAllow, globalBlock: snap.globalBlock });
        }
      });

      // Modal App
      $("wl-app-close").addEventListener("click", () => this.closeAppModal());
      $("wl-app-cancel").addEventListener("click", () => this.closeAppModal());
      $("wl-app-pick-installed").addEventListener("change", function () {
        if (!this.value) return;
        const a = WL.installedMap[this.value];
        $("wl-app-pkg").value = this.value;
        if (a && a.label && !$("wl-app-label").value.trim()) $("wl-app-label").value = a.label;
      });
      $("wl-app-delete").addEventListener("click", () => {
        const pkg = $("wl-app-pkg").value.trim();
        if (!pkg || !confirm("¿Quitar " + pkg + " del catálogo de este celular?")) return;
        const snap = this.working || this.defaultCatalogSnapshot();
        const next = IO.buildSnapshot(snap.apps.filter((a) => a.packageName !== pkg), snap.globalAllow, snap.globalBlock);
        this.closeAppModal();
        this.persistAndSync(next, "delete_app");
      });
      $("wl-app-save").addEventListener("click", async () => {
        const pkg = $("wl-app-pkg").value.trim();
        const label = $("wl-app-label").value.trim() || pkg;
        if (!/^[a-zA-Z0-9_.]+$/.test(pkg) || pkg.indexOf(".") < 0) {
          $("wl-app-error").textContent = "Ingresá un Package Name válido (ej: com.waze).";
          return;
        }
        const allowParsed = IO.parseDomainListText($("wl-app-allow").value, true);
        const blockParsed = IO.parseDomainListText($("wl-app-block").value, false);
        if (allowParsed.rejected.length) {
          LS.toast("Omitidos de Permitidos: " + allowParsed.rejected.slice(0, 4).map((x) => x.raw + " (" + x.reason + ")").join(", "), "warn");
        }
        const snap = this.working || this.defaultCatalogSnapshot();
        const updatedApp = {
          packageName: pkg,
          label: label,
          status: $("wl-app-status").value,
          access: $("wl-app-noweb").checked ? "noweb" : "domains",
          allow: allowParsed.domains,
          block: blockParsed.domains
        };
        const others = snap.apps.filter((a) => a.packageName !== pkg);
        const next = IO.buildSnapshot(others.concat([updatedApp]), snap.globalAllow, snap.globalBlock);
        this.closeAppModal();
        await this.persistAndSync(next, "save_app_" + pkg);
      });

      // Listas globales por celular
      const addGlobal = (kind) => {
        const input = $(kind === "allow" ? "wl-gallow-input" : "wl-gblock-input");
        const parsed = IO.parseDomainListText(input.value, kind === "allow");
        if (!parsed.domains.length) {
          const r = parsed.rejected[0];
          LS.toast(r ? r.raw + ": " + r.reason : "Escribí uno o más dominios válidos.", "error");
          return;
        }
        if (parsed.rejected.length) {
          LS.toast("Omitidos: " + parsed.rejected.slice(0, 4).map((x) => x.raw + " (" + x.reason + ")").join(", "), "warn");
        }
        const snap = this.working || this.defaultCatalogSnapshot();
        const addedSet = new Set(parsed.domains);
        const gAllow = kind === "allow"
          ? snap.globalAllow.concat(parsed.domains)
          : snap.globalAllow.filter((d) => !addedSet.has(d));
        const gBlock = kind === "block"
          ? snap.globalBlock.concat(parsed.domains)
          : snap.globalBlock.filter((d) => !addedSet.has(d));
        input.value = "";
        this.markDirty({ apps: snap.apps, globalAllow: gAllow, globalBlock: gBlock });
      };

      $("wl-gallow-add").addEventListener("click", () => addGlobal("allow"));
      $("wl-gblock-add").addEventListener("click", () => addGlobal("block"));
      $("wl-gallow-search").addEventListener("input", () => this.renderGlobalLists());
      $("wl-gblock-search").addEventListener("input", () => this.renderGlobalLists());

      const bindListFile = (inputId, kind) => {
        $(inputId).addEventListener("change", async (e) => {
          const file = e.target.files && e.target.files[0];
          e.target.value = "";
          if (!file) return;
          try {
            const txt = await IO.readTextFile(file);
            const parsed = IO.parseDomainListText(txt, kind === "allow");
            if (!confirm("¿Reemplazar toda la lista de " + (kind === "allow" ? "Permitidos" : "Prohibidos") +
              " por los " + parsed.domains.length + " dominios del archivo?" +
              (parsed.rejected.length ? "\n(Se omitieron " + parsed.rejected.length + " por formato o seguridad)" : ""))) return;
            const snap = this.working || this.defaultCatalogSnapshot();
            const next = {
              apps: snap.apps,
              globalAllow: kind === "allow" ? parsed.domains : snap.globalAllow,
              globalBlock: kind === "block" ? parsed.domains : snap.globalBlock
            };
            this.markDirty(next);
          } catch (err) {
            LS.toast("Error leyendo archivo: " + err.message, "error");
          }
        });
      };
      bindListFile("wl-gallow-file", "allow");
      bindListFile("wl-gblock-file", "block");

      $("wl-gallow-export").addEventListener("click", () => {
        const snap = this.working || this.defaultCatalogSnapshot();
        this.downloadText("locksuite-permitidos-" + this.deviceId + ".txt",
          IO.formatDomainListText(snap.globalAllow, "Permitidos para " + this.deviceName));
      });
      $("wl-gblock-export").addEventListener("click", () => {
        const snap = this.working || this.defaultCatalogSnapshot();
        this.downloadText("locksuite-prohibidos-" + this.deviceId + ".txt",
          IO.formatDomainListText(snap.globalBlock, "Prohibidos para " + this.deviceName));
      });

      const handleGlobalRemove = (e) => {
        const b = e.target.closest("[data-wl-gdel]");
        if (!b) return;
        const kind = b.dataset.wlGdel;
        const dom = b.dataset.dom;
        const snap = this.working || this.defaultCatalogSnapshot();
        this.markDirty({
          apps: snap.apps,
          globalAllow: kind === "allow" ? snap.globalAllow.filter((d) => d !== dom) : snap.globalAllow,
          globalBlock: kind === "block" ? snap.globalBlock.filter((d) => d !== dom) : snap.globalBlock
        });
      };
      $("wl-gallow-items").addEventListener("click", handleGlobalRemove);
      $("wl-gblock-items").addEventListener("click", handleGlobalRemove);

      // Auditoría
      $("wl-audit-search").addEventListener("input", (e) => {
        this.auditSearch = e.target.value || "";
        this.renderAudit();
      });
      $("wl-audit-clear").addEventListener("click", async function () {
        if (!confirm("¿Vaciar el registro de dominios bloqueados?")) return;
        this.disabled = true;
        const r = await LS.sendCommand(WL.deviceId, "CLEAR_WHITELIST_AUDIT", null, null, WL.getOpc());
        this.disabled = false;
        LS.toast(...LS.resultado(r, "Registro vaciado."));
      });
      $("wl-audit-list").addEventListener("click", async (e) => {
        const btn = e.target.closest("[data-audit-act]");
        const row = e.target.closest("[data-audit-dom]");
        if (!btn || !row) return;
        const dom = row.dataset.auditDom;
        const act = btn.dataset.auditAct;
        if (act === "app") {
          this.openPickAppModal(dom);
          return;
        }
        const snap = this.working || this.defaultCatalogSnapshot();
        if (act === "allow") {
          const parsed = IO.parseDomainListText(dom, true);
          if (!parsed.domains.length) {
            const r = parsed.rejected[0];
            LS.toast("No se puede permitir: " + (r ? r.reason : "inválido"), "error");
            return;
          }
          const clean = parsed.domains[0];
          const next = IO.buildSnapshot(snap.apps, snap.globalAllow.concat([clean]), snap.globalBlock.filter((d) => d !== clean));
          await this.persistAndSync(next, "audit_global_allow");
        } else if (act === "block") {
          const next = IO.buildSnapshot(snap.apps, snap.globalAllow.filter((d) => d !== dom), snap.globalBlock.concat([dom]));
          await this.persistAndSync(next, "audit_global_block");
        }
      });

      // Modal elegir App desde auditoría
      $("wl-pick-close").addEventListener("click", () => $("wl-pick-app-modal").classList.add("hidden"));
      $("wl-pick-cancel").addEventListener("click", () => $("wl-pick-app-modal").classList.add("hidden"));
      $("wl-pick-search").addEventListener("input", () => this.renderPickAppList());
      $("wl-pick-app-list").addEventListener("click", async (e) => {
        const item = e.target.closest("[data-pick-pkg]");
        if (!item || !this.pendingAuditDomain) return;
        const pkg = item.dataset.pickPkg;
        const dom = this.pendingAuditDomain;
        const parsed = IO.parseDomainListText(dom, true);
        if (!parsed.domains.length) {
          const r = parsed.rejected[0];
          LS.toast("No se puede permitir: " + (r ? r.reason : "inválido"), "error");
          return;
        }
        const clean = parsed.domains[0];
        const snap = this.working || this.defaultCatalogSnapshot();
        const apps = snap.apps.map((a) => {
          if (a.packageName !== pkg) return a;
          return Object.assign({}, a, {
            status: "enabled",
            enabled: true,
            allow: a.allow.concat([clean]),
            block: a.block.filter((d) => d !== clean)
          });
        });
        $("wl-pick-app-modal").classList.add("hidden");
        this.pendingAuditDomain = null;
        await this.persistAndSync(IO.buildSnapshot(apps, snap.globalAllow, snap.globalBlock), "audit_add_app");
      });

      // Modal respaldos DNS
      $("wl-history-close").addEventListener("click", () => this.closeHistoryModal());
      $("wl-history-cancel").addEventListener("click", () => this.closeHistoryModal());
      $("wl-history-only-favs").addEventListener("change", () => this.renderHistoryList());
      $("wl-history-refresh").addEventListener("click", () => this.loadBackups());
      $("wl-history-download-current").addEventListener("click", () => {
        const snap = this.working || this.defaultCatalogSnapshot();
        this.downloadText(IO.formatBackupFilename(Date.now(), this.deviceName),
          IO.serializeCatalogTxt(snap, { deviceName: this.deviceName, reason: "Copia actual" }));
      });
      $("wl-history-manual-save").addEventListener("click", async () => {
        const note = prompt("Nota opcional para este respaldo:", "") || "";
        try {
          await this.saveBackupEntry(this.working || this.defaultCatalogSnapshot(), "manual", note, false);
          LS.toast("Respaldo guardado.", "ok");
          await this.loadBackups();
        } catch (err) {
          LS.toast("Error guardando respaldo: " + err.message, "error");
        }
      });
      $("wl-history-upload-file").addEventListener("change", async (e) => {
        const file = e.target.files && e.target.files[0];
        e.target.value = "";
        if (!file) return;
        try {
          const txt = await IO.readTextFile(file);
          const parsed = IO.parseCatalogTxt(txt);
          if (parsed.error) { LS.toast(parsed.error, "error"); return; }
          const next = IO.buildSnapshot(parsed.apps, parsed.globalAllow, parsed.globalBlock);
          if (!confirm("¿Restaurar archivo en " + this.deviceName + " (" + next.summary.appsCount + " apps)?")) return;
          this.closeHistoryModal();
          await this.persistAndSync(next, "upload_from_backups");
        } catch (err) {
          LS.toast("Error leyendo archivo: " + err.message, "error");
        }
      });
      $("wl-history-list").addEventListener("click", async (e) => {
        const btn = e.target.closest("[data-bk-act]");
        const row = e.target.closest("[data-bk-id]");
        if (!btn || !row) return;
        const id = row.dataset.bkId;
        const bk = this.backups[id];
        if (!bk) return;
        const act = btn.dataset.bkAct;
        const bkRef = LS.db.ref("deviceWhitelistBackups/" + this.deviceId + "/" + id);
        if (act === "fav") {
          const nextFav = !bk.favorite;
          await bkRef.update({ favorite: nextFav });
          bk.favorite = nextFav;
          this.renderHistoryList();
        } else if (act === "note") {
          const note = prompt("Nota:", bk.note || "");
          if (note === null) return;
          await bkRef.update({ note: note.trim() });
          bk.note = note.trim();
          this.renderHistoryList();
        } else if (act === "download") {
          const txt = IO.serializeCatalogTxt(bk, { deviceName: this.deviceName, createdAt: bk.createdAt, reason: bk.reason, note: bk.note });
          this.downloadText(IO.formatBackupFilename(bk.createdAt, this.deviceName), txt);
        } else if (act === "restore") {
          if (!confirm("¿Restaurar este respaldo del " + LS.fecha(bk.createdAt) + " en " + this.deviceName + "?")) return;
          const next = IO.buildSnapshot(bk.apps || [], bk.globalAllow || [], bk.globalBlock || []);
          this.closeHistoryModal();
          await this.persistAndSync(next, "restore_backup");
        } else if (act === "delete") {
          if (!confirm("¿Eliminar este respaldo del historial?")) return;
          await bkRef.remove();
          delete this.backups[id];
          this.renderHistoryList();
        }
      });
    }
  };

  window.LSWhitelistUI = WL;
})();
