/* ═══════════════════════════════════════════════════════════════════════════
 * tienda_aurora.js — Panel web de Tienda Aurora (B.132).
 *
 * Complementa a tienda.js sin alterar su flujo ni sus selectores:
 *   - Configura la cuenta Google dedicada (OAuth/AAS) o Dispenser del backend.
 *   - Muestra el estado de caché GCS/RTDB de cada app de Tienda Play.
 *   - Permite sincronizar manualmente una app o todo el catálogo en lotes.
 *   - Cuando se guarda `globalSettings/playStore` en tienda.js, dispara
 *     automáticamente la sincronización de las apps nuevas o pendientes.
 * ═══════════════════════════════════════════════════════════════════════════ */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const esc = (window.LS && LS.esc) || ((s) => String(s == null ? "" : s));

  const state = {
    auth: { configured: false },
    meta: {},
    packages: {},     // auroraStore/packages
    playApps: {},     // globalSettings/playStore/apps
    busy: false,
    loadedStatus: false,
  };

  function pkgKey(pkg) {
    return String(pkg || "").replace(/\./g, "_");
  }

  function formatBytes(bytes) {
    const n = Number(bytes || 0);
    if (!n) return "—";
    if (n < 1024 * 1024) return Math.round(n / 1024) + " KB";
    return (n / (1024 * 1024)).toFixed(1) + " MB";
  }

  function updateModeFields() {
    const modeEl = $("au-mode");
    if (!modeEl) return;
    const mode = modeEl.value;
    if ($("au-field-email")) $("au-field-email").style.display = mode === "dispenser" ? "none" : "";
    if ($("au-field-oauth")) $("au-field-oauth").style.display = mode === "oauth" ? "" : "none";
    if ($("au-field-aas")) $("au-field-aas").style.display = mode === "aas" ? "" : "none";
    if ($("au-field-dispenser")) $("au-field-dispenser").style.display = mode === "dispenser" ? "" : "none";
    if ($("au-oauth-help")) $("au-oauth-help").style.display = mode === "oauth" ? "" : "none";
  }

  function renderAuth() {
    const chip = $("au-auth-chip");
    const metaEl = $("au-meta");
    if (!chip) return;
    if (!state.loadedStatus) {
      chip.className = "ls-chip warn";
      chip.textContent = "verificando…";
    } else if (state.auth && state.auth.configured) {
      chip.className = "ls-chip ok";
      chip.textContent = state.auth.mode === "dispenser"
        ? "Conectada (Dispenser)"
        : "Conectada (" + (state.auth.emailMasked || "AAS") + ")";
      const cfgDetails = $("au-config-details");
      if (cfgDetails && !cfgDetails.dataset.userToggled) cfgDetails.open = false;
    } else {
      chip.className = "ls-chip warn";
      chip.textContent = "Sin configurar";
    }

    if (metaEl) {
      const parts = [];
      if (state.meta && state.meta.lastAutoSyncAt && LS.fecha) {
        const sum = state.meta.lastAutoSummary || {};
        parts.push(
          "Última auto-sincronización: " + LS.fecha(state.meta.lastAutoSyncAt) +
          (sum.total != null ? ` (${sum.synced || 0} actualizadas, ${sum.upToDate || 0} al día, ${sum.failed || 0} errores)` : "")
        );
      }
      if (state.meta && state.meta.lastAutoError) {
        parts.push("Último aviso automático: " + state.meta.lastAutoError);
      }
      metaEl.textContent = parts.join(" · ");
    }

    if ($("au-sync-all")) $("au-sync-all").disabled = state.busy;
    if ($("au-sync-force")) $("au-sync-force").disabled = state.busy;
  }

  function renderList() {
    const container = $("au-list");
    if (!container) return;
    const appsMap = state.playApps || {};
    const rows = Object.entries(appsMap)
      .filter(([, e]) => e && typeof e === "object" && String(e.status || "enabled").toLowerCase() !== "blocked")
      .map(([k, e]) => ({
        key: k,
        pkg: (typeof e.packageName === "string" && e.packageName) || k.replace(/_/g, "."),
        label: e.label || k,
        tier: e.tier || 1,
        status: String(e.status || "enabled").toLowerCase(),
        cached: (state.packages && state.packages[k]) || null,
      }))
      .sort((a, b) => String(a.label).localeCompare(String(b.label)));

    if (!rows.length) {
      container.innerHTML = '<p class="ls-hint">No hay apps activas ni a pedido guardadas todavía en Tienda Play.</p>';
      return;
    }

    let html = '<div style="display:flex; flex-direction:column; gap:8px;">';
    for (const r of rows) {
      const c = r.cached;
      let statusChip = '<span class="ls-chip warn">Pendiente en servidor</span>';
      let detailText = "Todavía no descargada al almacenamiento de LockSuite.";

      if (c && c.status === "ready" && c.variants && Object.keys(c.variants).length > 0) {
        const vKeys = Object.keys(c.variants);
        const firstV = c.variants[vKeys[0]];
        const abiLabel = vKeys.includes("universal")
          ? "Universal"
          : vKeys.map((k) => k === "arm64_v8a" ? "64-bit" : k === "armeabi_v7a" ? "32-bit" : k).join(" + ");
        const totalSize = Object.values(c.variants).map((v) => v.sizeBytes || 0);
        const maxSize = totalSize.length ? Math.max(...totalSize) : 0;
        statusChip = `<span class="ls-chip ok">Lista v${esc(c.versionName || c.versionCode)} (${esc(abiLabel)})</span>`;
        detailText = `VersionCode ${esc(c.versionCode)} · ${esc(formatBytes(maxSize))}` +
          (firstV && firstV.splitCount ? ` · ${firstV.splitCount + 1} APKs (.apks)` : " · APK único") +
          (c.updatedAt && LS.fecha ? ` · ${esc(LS.fecha(c.updatedAt))}` : "") +
          (c.lastSyncError ? ` · ⚠ último reintento: ${esc(c.lastSyncError)}` : "");
      } else if (c && c.status === "error") {
        statusChip = '<span class="ls-chip bad">Error al descargar</span>';
        detailText = esc(c.error || "Error desconocido");
      }

      html +=
        `<div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap; background:var(--navy-dark); border:1px solid var(--navy-border); border-radius:10px; padding:10px 12px;">` +
        `<div style="min-width:190px; flex:1;">` +
        `<div><b>${esc(r.label)}</b> <code style="font-size:11.5px; color:var(--text-muted)">${esc(r.pkg)}</code> ` +
        (r.status === "request" ? '<span class="ls-chip warn">A pedido</span>' : "") +
        `</div>` +
        `<div class="ls-hint" style="margin:2px 0 0 0">${detailText}</div>` +
        `</div>` +
        `${statusChip}` +
        `<button type="button" class="ls-btn ls-btn-sm" data-au-sync="${esc(r.pkg)}" ${state.busy ? "disabled" : ""}>↻ Sincronizar</button>` +
        `</div>`;
    }
    html += "</div>";
    container.innerHTML = html;
  }

  function setProgress(msg) {
    const el = $("au-progress");
    if (!el) return;
    if (!msg) {
      el.style.display = "none";
      el.textContent = "";
    } else {
      el.style.display = "";
      el.textContent = msg;
    }
  }

  async function refreshStatus(silent) {
    if (!LS.auroraGetStatus) return;
    try {
      const res = await LS.auroraGetStatus();
      state.auth = (res && res.auth) || { configured: false };
      state.packages = (res && res.packages) || state.packages || {};
      state.meta = (res && res.meta) || {};
      state.loadedStatus = true;
      renderAuth();
      renderList();
    } catch (e) {
      state.loadedStatus = true;
      renderAuth();
      if (!silent) LS.toast("Tienda Aurora: " + e.message, "warn");
    }
  }

  async function runBatchSync({ packages = null, force = false, quietIfUnconfigured = false } = {}) {
    if (state.busy) return;
    state.busy = true;
    renderAuth();
    renderList();
    let offset = 0;
    let totalSynced = 0;
    let totalUpToDate = 0;
    let totalFailed = 0;
    const errors = [];

    try {
      while (true) {
        setProgress("Sincronizando apps de Tienda Aurora desde Google Play (lote desde #" + (offset + 1) + ")…");
        const res = await LS.auroraSyncCatalog({
          packages: packages || undefined,
          force: !!force,
          limit: 5,
          offset,
        });
        totalSynced += res.synced || 0;
        totalUpToDate += res.upToDate || 0;
        totalFailed += res.failed || 0;
        for (const r of (res.results || [])) {
          if (r && !r.ok && r.error) errors.push(`${r.packageName}: ${r.error}`);
        }
        if (!res.remaining || res.processed === 0) break;
        offset = res.nextOffset || (offset + res.processed);
      }

      const summary = `Tienda Aurora sincronizada: ${totalSynced} descargada(s), ${totalUpToDate} ya al día` +
        (totalFailed ? `, ${totalFailed} con error` : "") + ".";
      LS.toast(summary + (errors.length ? " " + errors.slice(0, 2).join(" | ") : ""), totalFailed ? "warn" : "ok");
      await refreshStatus(true);
    } catch (e) {
      if (!(quietIfUnconfigured && /Configurá primero|no está configurada/i.test(e.message))) {
        LS.toast("No se pudo sincronizar Tienda Aurora: " + e.message, "error");
      }
    } finally {
      state.busy = false;
      setProgress("");
      renderAuth();
      renderList();
    }
  }

  function wireUi() {
    if (!$("secAurora")) return;
    updateModeFields();

    const cfgDetails = $("au-config-details");
    if (cfgDetails) {
      cfgDetails.addEventListener("toggle", () => { cfgDetails.dataset.userToggled = "1"; });
    }
    if ($("au-mode")) $("au-mode").addEventListener("change", updateModeFields);
    if ($("au-refresh")) $("au-refresh").addEventListener("click", () => refreshStatus(false));
    if ($("au-sync-all")) $("au-sync-all").addEventListener("click", () => runBatchSync({ force: false }));
    if ($("au-sync-force")) {
      $("au-sync-force").addEventListener("click", () => {
        if (!confirm("¿Volver a descargar desde Google Play todas las apps del catálogo aunque ya coincida la versión?")) return;
        runBatchSync({ force: true });
      });
    }

    if ($("au-save-config")) {
      $("au-save-config").addEventListener("click", async function () {
        const mode = $("au-mode").value;
        const payload = { mode };
        if (mode === "oauth") {
          payload.email = ($("au-email").value || "").trim();
          payload.oauthToken = ($("au-oauth").value || "").trim();
        } else if (mode === "aas") {
          payload.email = ($("au-email").value || "").trim();
          payload.aasToken = ($("au-aas").value || "").trim();
        } else {
          payload.dispenserUrl = ($("au-dispenser").value || "").trim();
        }
        this.disabled = true;
        setProgress("Autenticando con Google Play y guardando credenciales en servidor…");
        try {
          const res = await LS.auroraSaveConfig(payload);
          state.auth = res.auth || { configured: true };
          state.loadedStatus = true;
          if ($("au-oauth")) $("au-oauth").value = "";
          if ($("au-aas")) $("au-aas").value = "";
          renderAuth();
          LS.toast("Conexión con Google Play guardada y verificada en el servidor.", "ok");
        } catch (e) {
          LS.toast("No se pudo conectar: " + e.message, "error");
        } finally {
          this.disabled = false;
          setProgress("");
        }
      });
    }

    if ($("au-test-config")) {
      $("au-test-config").addEventListener("click", async function () {
        this.disabled = true;
        setProgress("Probando inicio de sesión en Google Play…");
        try {
          const res = await LS.auroraTestAuth();
          const s = res.sample || {};
          LS.toast("Conexión OK (" + (s.packageName || "com.whatsapp") + " v" + (s.versionName || s.versionCode || "?") + ").", "ok");
        } catch (e) {
          LS.toast("Fallo de conexión: " + e.message, "error");
        } finally {
          this.disabled = false;
          setProgress("");
        }
      });
    }

    if ($("au-clear-config")) {
      $("au-clear-config").addEventListener("click", async function () {
        if (!confirm("¿Borrar las credenciales de Google Play guardadas en el servidor?")) return;
        this.disabled = true;
        try {
          await LS.auroraClearConfig();
          state.auth = { configured: false };
          state.loadedStatus = true;
          renderAuth();
          LS.toast("Configuración borrada.", "ok");
        } catch (e) {
          LS.toast("Error al borrar: " + e.message, "error");
        } finally {
          this.disabled = false;
        }
      });
    }

    if ($("au-list")) {
      $("au-list").addEventListener("click", async (ev) => {
        const btn = ev.target.closest("[data-au-sync]");
        if (!btn || state.busy) return;
        const pkg = btn.getAttribute("data-au-sync");
        if (!pkg) return;
        state.busy = true;
        renderAuth();
        renderList();
        setProgress("Descargando " + pkg + " desde Google Play al servidor…");
        try {
          const res = await LS.auroraSyncPackage(pkg, true);
          if (res.packageData) state.packages[pkgKey(pkg)] = res.packageData;
          if (res.result && res.result.ok) {
            LS.toast(pkg + " lista en Tienda Aurora (v" + (res.result.versionName || res.result.versionCode) + ").", "ok");
          } else {
            LS.toast("No se pudo sincronizar " + pkg + ": " + ((res.result && res.result.error) || "error"), "error");
          }
        } catch (e) {
          LS.toast("Error sincronizando " + pkg + ": " + e.message, "error");
        } finally {
          state.busy = false;
          setProgress("");
          renderAuth();
          renderList();
        }
      });
    }
  }

  window.TiendaAurora = {
    onPlayStoreSaved(savedAppsMap) {
      state.playApps = savedAppsMap || {};
      renderList();
      if (!state.auth || !state.auth.configured) {
        if ($("secAurora")) {
          LS.toast("Tienda Aurora compartirá estas apps en cuanto configures la cuenta Google / Dispenser abajo.", "info");
        }
        return;
      }
      const missing = Object.entries(state.playApps)
        .filter(([k, e]) => {
          if (!e || typeof e !== "object") return false;
          if (String(e.status || "enabled").toLowerCase() === "blocked") return false;
          const cached = state.packages && state.packages[k];
          return !cached || cached.status !== "ready" || !cached.variants;
        })
        .map(([k, e]) => (typeof e.packageName === "string" && e.packageName) || k.replace(/_/g, "."));

      if (missing.length > 0) {
        runBatchSync({ packages: missing, force: false, quietIfUnconfigured: true });
      }
    },
  };

  if (window.LS && LS.requireAuth) {
    LS.requireAuth(() => {
      wireUi();
      LS.db.ref("globalSettings/playStore/apps").on("value", (snap) => {
        state.playApps = snap.val() || {};
        renderList();
      });
      LS.db.ref("auroraStore/packages").on("value", (snap) => {
        state.packages = snap.val() || {};
        renderList();
      });
      LS.db.ref("auroraStore/meta").on("value", (snap) => {
        state.meta = snap.val() || {};
        renderAuth();
      });
      refreshStatus(true);
    });
  }
})();
