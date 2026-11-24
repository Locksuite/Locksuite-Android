/* ═══════════════════════════════════════════════════════════════════════════
 * tienda_clasica.js — sección discreta de "Tienda clásica de APKs" en tienda.html.
 *
 * Permite ver las entradas de `storeApps`, eliminarlas, subir un .apk o Split APK
 * (.apks, .xapk, .apkm o varios .apk juntos empaquetados en el navegador) directo
 * desde la PC (sube a Cloud Storage por URL resumible y calcula SHA-256 + packageName
 * con ApkReader) y marcar `blockWebView` / `blockInternet` por app.
 * ═══════════════════════════════════════════════════════════════════════════ */
(function () {
  "use strict";

  const MAX_APK_BYTES = 512 * 1024 * 1024;
  const BUNDLE_EXT_RE = /\.(apk|apks|xapk|apkm)$/i;
  const $ = (id) => document.getElementById(id);
  const esc = LS.esc;
  const T = window.TiendaLogic;

  const S = {
    storeApps: {},
    allowedSet: new Set(),
    devices: {},
    picked: null,
    lastAutoLabel: "",
    busy: false,
  };

  function norm(s) {
    return String(s || "")
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toLowerCase()
      .trim();
  }

  function formatMb(bytes) {
    return (bytes / (1024 * 1024)).toFixed(bytes >= 10 * 1024 * 1024 ? 1 : 2) + " MB";
  }

  function originLabel(app) {
    const url = String((app && app.apkUrl) || "");
    if ((app && app.storagePath) || url.includes("storage.googleapis.com/locksuite-nueva-store-apks")) {
      return "Cloud Storage";
    }
    if (url.includes("github.com/")) return "GitHub";
    return "URL externa";
  }

  function setBusy(busy) {
    S.busy = busy;
    const btn = $("tc-submit");
    if (btn) btn.disabled = busy;
    const f = $("tc-file");
    if (f) f.disabled = busy;
  }

  function setProgress(pct, text) {
    const wrap = $("tc-progress-wrap");
    const bar = $("tc-progress-bar");
    const lbl = $("tc-progress-text");
    if (!wrap || !bar || !lbl) return;
    if (pct == null && !text) {
      wrap.style.display = "none";
      bar.value = 0;
      lbl.textContent = "";
      return;
    }
    wrap.style.display = "";
    if (typeof pct === "number") bar.value = Math.max(0, Math.min(100, pct));
    lbl.textContent = text || "";
  }

  function syncBlockNetVisibility() {
    const pkg = ($("tc-pkg") ? $("tc-pkg").value : "").trim();
    const ok = LS.supportsAppInternetBlock(pkg);
    const wrap = $("tc-block-net-wrap");
    const chk = $("tc-block-net");
    if (wrap) wrap.style.display = ok ? "" : "none";
    if (!ok && chk) chk.checked = false;
  }

  function resetPickedFile() {
    S.picked = null;
    const inp = $("tc-file");
    if (inp) inp.value = "";
    const info = $("tc-file-info");
    if (info) info.textContent = "Elegí un archivo (.apk, .apks, .xapk, .apkm) o varios .apk juntos (se lee paquete y huella SHA-256 automáticamente).";
    const clearBtn = $("tc-clear-file");
    if (clearBtn) clearBtn.style.display = "none";
    syncBlockNetVisibility();
  }

  async function sha256FromUrl(url) {
    const resp = await fetch(url);
    if (!resp.ok) throw new Error("HTTP " + resp.status);
    const ab = await resp.arrayBuffer();
    return window.ApkReader.sha256Hex(ab);
  }

  function inferFormatFromUrl(url) {
    try {
      const pathname = new URL(url).pathname || "";
      const m = /\.(apks|xapk|apkm)$/i.exec(pathname);
      return m ? m[1].toLowerCase() : null;
    } catch (_) {
      const clean = String(url || "").split(/[?#]/)[0];
      const m = /\.(apks|xapk|apkm)$/i.exec(clean);
      return m ? m[1].toLowerCase() : null;
    }
  }

  async function onFilesPicked(fileList) {
    const files = Array.from(fileList || []).filter(Boolean);
    if (!files.length) {
      resetPickedFile();
      return;
    }

    const infoEl = $("tc-file-info");
    const clearBtn = $("tc-clear-file");
    if (files.length === 1) {
      const file = files[0];
      if (!BUNDLE_EXT_RE.test(file.name)) {
        LS.toast("El archivo elegido debe terminar en .apk, .apks, .xapk o .apkm.", "error");
        resetPickedFile();
        return;
      }
      if (file.size <= 0 || file.size > MAX_APK_BYTES) {
        LS.toast("Tamaño de paquete inválido (máximo 512 MB).", "error");
        resetPickedFile();
        return;
      }

      if (infoEl) infoEl.textContent = "Analizando " + file.name + " (" + formatMb(file.size) + ")…";
      if (clearBtn) clearBtn.style.display = "";
      setBusy(true);
      try {
        const ab = await file.arrayBuffer();
        const [sha256, bundleInfo] = await Promise.all([
          window.ApkReader.sha256Hex(ab),
          window.ApkReader.parsePackageBundle(ab, file.name),
        ]);
        if (!bundleInfo || !bundleInfo.ok) {
          LS.toast((bundleInfo && bundleInfo.error) || "No se pudo leer el paquete.", "error");
          resetPickedFile();
          return;
        }

        S.picked = {
          file,
          fileName: file.name,
          displayTitle: file.name,
          sizeBytes: file.size,
          sha256,
          detectedPkg: bundleInfo.packageName || null,
          detectedVersionName: bundleInfo.versionName || null,
          detectedVersionCode: bundleInfo.versionCode != null ? bundleInfo.versionCode : null,
          isSplitBundle: Boolean(bundleInfo.isSplitBundle),
          bundleFormat: bundleInfo.bundleFormat || "apk",
          splitCount: bundleInfo.splitCount || 1,
          abis: Array.isArray(bundleInfo.abis) ? bundleInfo.abis : [],
          hasObb: Boolean(bundleInfo.hasObb),
        };
      } catch (e) {
        LS.toast("No se pudo leer el paquete: " + e.message, "error");
        resetPickedFile();
        setBusy(false);
        return;
      } finally {
        setBusy(false);
      }
    } else {
      for (const f of files) {
        if (!/\.apk$/i.test(f.name)) {
          LS.toast("Al seleccionar varios archivos juntos, todos deben terminar en .apk (" + f.name + ").", "error");
          resetPickedFile();
          return;
        }
      }
      const rawTotal = files.reduce((acc, f) => acc + (f.size || 0), 0);
      if (rawTotal <= 0 || rawTotal > MAX_APK_BYTES) {
        LS.toast("Tamaño total de los APKs inválido (máximo 512 MB).", "error");
        resetPickedFile();
        return;
      }

      if (infoEl) infoEl.textContent = "Empaquetando " + files.length + " APKs divididos en .apks…";
      if (clearBtn) clearBtn.style.display = "";
      setBusy(true);
      try {
        const packed = await window.ApkReader.packSplitApksToZip(files);
        const ab = packed.bytes.buffer.slice(packed.bytes.byteOffset, packed.bytes.byteOffset + packed.bytes.byteLength);
        const sha256 = await window.ApkReader.sha256Hex(ab);
        const safePkgName = String(packed.packageName || "app").replace(/[^a-zA-Z0-9._-]/g, "_");
        const outName = safePkgName + "_splits.apks";
        const outBlob = new Blob([packed.bytes], { type: "application/octet-stream" });

        S.picked = {
          file: outBlob,
          fileName: outName,
          displayTitle: files.length + " .apk → " + outName,
          sizeBytes: packed.bytes.byteLength,
          sha256,
          detectedPkg: packed.packageName || null,
          detectedVersionName: packed.versionName || null,
          detectedVersionCode: packed.versionCode != null ? packed.versionCode : null,
          isSplitBundle: true,
          bundleFormat: "apks",
          splitCount: packed.splitCount || files.length,
          abis: Array.isArray(packed.abis) ? packed.abis : [],
          hasObb: false,
        };
      } catch (e) {
        LS.toast("No se pudieron empaquetar los Split APKs: " + e.message, "error");
        resetPickedFile();
        setBusy(false);
        return;
      } finally {
        setBusy(false);
      }
    }

    const urlInput = $("tc-url");
    if (urlInput) urlInput.value = "";

    if (S.picked.detectedPkg) {
      $("tc-pkg").value = S.picked.detectedPkg;
    }
    syncBlockNetVisibility();
    const labelEl = $("tc-label");
    const baseNameHint = files.length === 1
      ? files[0].name
      : (files.find((f) => !/^(split_|config\.)/i.test(f.name) && !/^base\.apk$/i.test(f.name)) || {}).name ||
        (S.picked.detectedPkg ? S.picked.detectedPkg.split(".").pop() : "");
    const suggested = window.ApkReader.labelFromFilename(baseNameHint);
    if (labelEl && (!labelEl.value.trim() || labelEl.value.trim() === S.lastAutoLabel) && suggested) {
      labelEl.value = suggested;
      S.lastAutoLabel = suggested;
    }

    const existingKey = S.picked.detectedPkg ? LS.pkgKey(S.picked.detectedPkg) : null;
    const existing = existingKey ? S.storeApps[existingKey] : null;
    if (existing && labelEl && existing.label && labelEl.value.trim() === suggested) {
      labelEl.value = existing.label;
    }

    const ver = S.picked.detectedVersionName
      ? " · v" + S.picked.detectedVersionName + (S.picked.detectedVersionCode ? " (" + S.picked.detectedVersionCode + ")" : "")
      : "";
    const pkgText = S.picked.detectedPkg ? S.picked.detectedPkg : "paquete no detectado (ingresalo manual)";
    const splitChip = S.picked.isSplitBundle
      ? ' <span class="ls-chip">Split (' + esc(String(S.picked.splitCount)) + " APKs · " + esc(S.picked.bundleFormat.toUpperCase()) + ")</span>"
      : "";
    const abiText = S.picked.abis.length ? " · ABIs: <code>" + esc(S.picked.abis.join(", ")) + "</code>" : "";
    const obbWarn = S.picked.hasObb
      ? ' <span style="color:var(--warning-yellow)">· ⚠ Incluye archivos OBB (solo se instalan los APKs; los OBB no se copian solos)</span>'
      : "";
    const replaceNote = existing ? " · ya existe en tienda clásica (se reemplazará)" : "";
    if (infoEl) {
      infoEl.innerHTML =
        "<b>" + esc(S.picked.displayTitle) + "</b> (" + esc(formatMb(S.picked.sizeBytes)) + esc(ver) + ")" +
        splitChip + " · " +
        "<code>" + esc(pkgText) + "</code>" + abiText + " · SHA-256 <code>" + esc(S.picked.sha256.slice(0, 12)) + "…</code>" +
        obbWarn +
        (replaceNote ? '<span style="color:var(--warning-yellow)">' + esc(replaceNote) + "</span>" : "");
    }
  }

  async function ensureAllowedPackage(pkg) {
    await LS.db.ref("globalSettings/allowedPackages").transaction((curr) => {
      const list = Array.isArray(curr) ? curr.filter((x) => typeof x === "string" && x) : [];
      if (list.includes(pkg)) return; // sin cambios: no reescribe
      return list.concat([pkg]);
    });
  }

  async function guardarAppClasica() {
    if (S.busy) return;
    const rawPkg = ($("tc-pkg").value || "").trim();
    const rawLabel = ($("tc-label").value || "").trim();
    const rawUrl = ($("tc-url") ? $("tc-url").value : "").trim();
    const blockWebView = $("tc-block-wv").checked;
    const blockInternet = LS.supportsAppInternetBlock(rawPkg) && $("tc-block-net").checked;
    const allowGlobal = $("tc-allow-global").checked;

    if (!T.isValidPackage(rawPkg)) {
      LS.toast("Ingresá un nombre de paquete válido (ej: org.ejemplo.app).", "error");
      return;
    }
    if (T.isNeverOffered(rawPkg)) {
      LS.toast("No se puede ofrecer un paquete del sistema o de LockSuite en la tienda.", "error");
      return;
    }
    const label = rawLabel ? T.cleanLabel(rawLabel, rawPkg) : T.cleanLabel("", rawPkg);

    if (!S.picked && !rawUrl) {
      LS.toast("Elegí un archivo (.apk/.apks/.xapk/.apkm) desde tu PC o pegá una URL HTTPS directa.", "error");
      return;
    }
    if (!S.picked && !/^https:\/\/\S+$/i.test(rawUrl)) {
      LS.toast("La URL externa debe empezar con https://.", "error");
      return;
    }

    const key = LS.pkgKey(rawPkg);
    const existing = S.storeApps[key] || null;
    if (existing) {
      if (!confirm("«" + (existing.label || rawPkg) + "» ya está en la tienda clásica.\n\n¿Reemplazar su paquete y configuración por este nuevo?")) {
        return;
      }
    }

    setBusy(true);
    try {
      let apkUrl = rawUrl;
      let sha256 = "";
      let storagePath = null;
      const prevStoragePath = existing && existing.storagePath ? existing.storagePath : null;

      if (S.picked) {
        setProgress(0, "Preparando subida a Cloud Storage…");
        const init = await LS.classicStoreInitUpload({
          packageName: rawPkg,
          fileName: S.picked.fileName,
          sizeBytes: S.picked.sizeBytes,
        });
        await LS.uploadToSignedUrl(
          init.uploadUrl,
          S.picked.file,
          (pct) => {
            setProgress(pct, (S.picked.isSplitBundle ? "Subiendo Split APK… " : "Subiendo APK… ") + pct + "%");
          },
          init.contentType
        );
        apkUrl = init.downloadUrl;
        sha256 = S.picked.sha256;
        storagePath = init.objectPath;
      } else {
        setProgress(null, "Descargando URL para verificar SHA-256…");
        try {
          sha256 = await sha256FromUrl(rawUrl);
        } catch (err) {
          const manual = window.prompt(
            "El navegador no pudo descargar esa URL directamente (" + err.message + ").\n\n" +
            "Si ya calculaste la huella SHA-256 (64 caracteres hex), pegala acá:",
            ""
          );
          if (!manual || !/^[0-9a-fA-F]{64}$/.test(manual.trim())) {
            LS.toast("Cancelado: sin huella SHA-256 el celular no instala el paquete.", "warn");
            return;
          }
          sha256 = manual.trim().toLowerCase();
        }
      }

      setProgress(100, "Guardando en Firebase…");
      const user = LS.auth.currentUser;
      const record = {
        label,
        packageName: rawPkg,
        apkUrl,
        sha256,
        sha256At: Date.now(),
        blockWebView,
        blockInternet,
        uploadedAt: Date.now(),
        uploadedBy: (user && user.email) || "",
      };
      if (storagePath) record.storagePath = storagePath;
      if (S.picked) {
        if (S.picked.isSplitBundle) {
          record.bundleFormat = S.picked.bundleFormat;
          record.splitCount = S.picked.splitCount;
        }
        if (S.picked.abis && S.picked.abis.length) {
          record.abis = S.picked.abis;
        }
      } else {
        const urlFmt = inferFormatFromUrl(rawUrl);
        if (urlFmt) record.bundleFormat = urlFmt;
      }

      await LS.db.ref("storeApps/" + key).set(record);
      if (allowGlobal) {
        await ensureAllowedPackage(rawPkg);
      }
      if (prevStoragePath && prevStoragePath !== storagePath) {
        LS.classicStoreDeleteObject(prevStoragePath).catch(() => {});
      }

      resetPickedFile();
      $("tc-label").value = "";
      $("tc-pkg").value = "";
      if ($("tc-url")) $("tc-url").value = "";
      $("tc-block-wv").checked = false;
      $("tc-block-net").checked = false;
      $("tc-allow-global").checked = true;
      syncBlockNetVisibility();
      S.lastAutoLabel = "";
      LS.toast("«" + label + "» guardada en la tienda clásica.", "ok");
    } catch (e) {
      LS.toast("No se pudo guardar: " + e.message, "error");
    } finally {
      setProgress(null, "");
      setBusy(false);
    }
  }

  function render() {
    const entries = Object.entries(S.storeApps || {}).sort((a, b) => {
      const la = ((a[1] && a[1].label) || a[0] || "").toLowerCase();
      const lb = ((b[1] && b[1].label) || b[0] || "").toLowerCase();
      return la.localeCompare(lb, "es");
    });

    const countChip = $("tc-count");
    if (countChip) {
      countChip.textContent = entries.length + " app" + (entries.length === 1 ? "" : "s");
    }

    const listEl = $("tc-list");
    if (!listEl) return;

    const q = norm($("tc-buscar") ? $("tc-buscar").value : "");
    const filtered = q
      ? entries.filter(([k, app]) => {
          const pkg = (app && app.packageName) || LS.keyPkg(k);
          return norm(app && app.label).includes(q) || norm(pkg).includes(q);
        })
      : entries;

    if (!entries.length) {
      listEl.innerHTML = '<p class="ls-empty">No hay apps en la tienda clásica.</p>';
      return;
    }
    if (!filtered.length) {
      listEl.innerHTML = '<p class="ls-empty">Ninguna app de la tienda clásica coincide.</p>';
      return;
    }

    listEl.innerHTML = filtered.map(([key, app]) => {
      const a = app || {};
      const pkg = a.packageName || LS.keyPkg(key);
      const label = a.label || pkg;
      const tieneSha = typeof a.sha256 === "string" && /^[0-9a-f]{64}$/i.test(a.sha256);
      const isStub = tieneSha && T.isAaStub(pkg, a.sha256);
      const inAllowed = S.allowedSet.has(pkg);
      const wv = a.blockWebView === true;
      const supportsNet = LS.supportsAppInternetBlock(pkg);
      const net = supportsNet && a.blockInternet === true;
      const splitCount = Number(a.splitCount) || 1;
      const fmt = String(a.bundleFormat || "").toLowerCase();
      const isSplit = splitCount > 1 || (fmt && fmt !== "apk");

      const chips = [
        '<span class="ls-chip">' + esc(originLabel(a)) + "</span>",
        tieneSha
          ? '<span class="ls-chip ok" title="' + esc(a.sha256) + '">✓ SHA-256</span>'
          : '<span class="ls-chip bad">⚠ Sin huella</span>',
      ];
      if (isSplit) {
        const splitText = splitCount > 1
          ? "Split (" + splitCount + " APKs)"
          : "Split (" + fmt.toUpperCase() + ")";
        const abisTip = Array.isArray(a.abis) && a.abis.length
          ? ' title="Arquitecturas: ' + esc(a.abis.join(", ")) + '"'
          : "";
        chips.push('<span class="ls-chip"' + abisTip + ">" + esc(splitText) + "</span>");
      }
      if (isStub) {
        chips.push('<span class="ls-chip">Stub Android Auto</span>');
      } else if (!inAllowed) {
        chips.push('<span class="ls-chip warn" title="No figura en globalSettings/allowedPackages: los celulares no la ven en la tienda clásica">Oculta en tienda</span>');
      }
      if (wv) chips.push('<span class="ls-chip warn">Sin WebView</span>');
      if (net) chips.push('<span class="ls-chip bad">Sin Internet</span>');

      return (
        '<div class="ls-approw tc-row" data-tckey="' + esc(key) + '">' +
          '<div class="ls-apphead">' +
            '<div class="ls-appgrow">' +
              '<div class="ls-appname">' + esc(label) + "</div>" +
              '<div class="ls-apppkg">' + esc(pkg) + "</div>" +
              '<div class="tc-url" title="' + esc(a.apkUrl || "") + '">' + esc(a.apkUrl || "Sin URL") + "</div>" +
              '<div class="ls-chiprow">' + chips.join(" ") + "</div>" +
            "</div>" +
            '<div class="tc-actions">' +
              '<label class="tc-flag"><input type="checkbox" data-tc-flag="blockWebView"' + (wv ? " checked" : "") + ' /><span>Sin WebView</span></label>' +
              (supportsNet
                ? '<label class="tc-flag"><input type="checkbox" data-tc-flag="blockInternet"' + (net ? " checked" : "") + ' /><span>Sin Internet</span></label>'
                : "") +
              (!isStub && !inAllowed
                ? '<button type="button" class="ls-btn ls-btn-sm" data-tc-allow="1">Habilitar</button>'
                : "") +
              (!tieneSha && a.apkUrl
                ? '<button type="button" class="ls-btn ls-btn-sm" data-tc-sha="1">Calcular huella</button>'
                : "") +
              '<button type="button" class="ls-btn ls-btn-sm ls-btn-danger" data-tc-del="1">Eliminar</button>' +
            "</div>" +
          "</div>" +
        "</div>"
      );
    }).join("");
  }

  function bindEvents() {
    const fileInp = $("tc-file");
    if (fileInp) {
      fileInp.addEventListener("change", () => {
        onFilesPicked(fileInp.files);
      });
    }
    const clearBtn = $("tc-clear-file");
    if (clearBtn) {
      clearBtn.addEventListener("click", resetPickedFile);
    }
    const urlToggle = $("tc-toggle-url");
    const urlWrap = $("tc-url-wrap");
    if (urlToggle && urlWrap) {
      urlToggle.addEventListener("click", () => {
        const show = urlWrap.style.display === "none";
        urlWrap.style.display = show ? "" : "none";
        urlToggle.textContent = show ? "Ocultar campo de URL manual" : "O pegar URL externa manualmente";
      });
    }
    const submitBtn = $("tc-submit");
    if (submitBtn) {
      submitBtn.addEventListener("click", guardarAppClasica);
    }
    const pkgInp = $("tc-pkg");
    if (pkgInp) {
      pkgInp.addEventListener("input", syncBlockNetVisibility);
    }
    syncBlockNetVisibility();
    const searchInp = $("tc-buscar");
    if (searchInp) {
      searchInp.addEventListener("input", render);
    }

    const listEl = $("tc-list");
    if (listEl) {
      listEl.addEventListener("change", async (e) => {
        const inp = e.target.closest("input[data-tc-flag]");
        const row = e.target.closest("[data-tckey]");
        if (!inp || !row) return;
        const key = row.dataset.tckey;
        const flag = inp.dataset.tcFlag;
        const val = inp.checked;
        const app = (S.storeApps || {})[key];
        const pkg = (app && app.packageName) || LS.keyPkg(key);
        if (flag === "blockInternet" && val && !LS.supportsAppInternetBlock(pkg)) {
          inp.checked = false;
          LS.toast("El bloqueo de internet por app solo está disponible para Pulsar y Subway Surfers.", "warn");
          return;
        }
        inp.disabled = true;
        try {
          await LS.db.ref("storeApps/" + key).update({ [flag]: val });
          LS.toast("Bloqueo guardado. Si querés empujarlo ya a los celulares encendidos, tocá «Aplicar bloqueos a la flota».", "ok");
        } catch (err) {
          inp.checked = !val;
          LS.toast("No se pudo actualizar: " + err.message, "error");
        } finally {
          inp.disabled = false;
        }
      });

      listEl.addEventListener("click", async (e) => {
        const row = e.target.closest("[data-tckey]");
        if (!row) return;
        const key = row.dataset.tckey;
        const app = (S.storeApps || {})[key];
        if (!app) return;
        const pkg = app.packageName || LS.keyPkg(key);

        const allowBtn = e.target.closest("button[data-tc-allow]");
        if (allowBtn) {
          allowBtn.disabled = true;
          try {
            await ensureAllowedPackage(pkg);
            LS.toast("«" + (app.label || pkg) + "» habilitada en permitidas globales.", "ok");
          } catch (err) {
            LS.toast("No se pudo habilitar: " + err.message, "error");
          } finally {
            allowBtn.disabled = false;
          }
          return;
        }

        const shaBtn = e.target.closest("button[data-tc-sha]");
        if (shaBtn) {
          shaBtn.disabled = true;
          try {
            let sha256;
            try {
              sha256 = await sha256FromUrl(app.apkUrl);
            } catch (err) {
              const manual = window.prompt(
                "No se pudo bajar desde el navegador (" + err.message + "). Pegá el SHA-256 (64 hex):",
                ""
              );
              if (!manual || !/^[0-9a-fA-F]{64}$/.test(manual.trim())) return;
              sha256 = manual.trim().toLowerCase();
            }
            await LS.db.ref("storeApps/" + key).update({ sha256, sha256At: Date.now() });
            LS.toast("Huella SHA-256 guardada.", "ok");
          } catch (err) {
            LS.toast("Error al guardar huella: " + err.message, "error");
          } finally {
            shaBtn.disabled = false;
          }
          return;
        }

        const delBtn = e.target.closest("button[data-tc-del]");
        if (delBtn) {
          if (!confirm("¿Eliminar «" + (app.label || pkg) + "» (" + pkg + ") de la tienda clásica de APKs?\n\nYa no se ofrecerá desde la tienda clásica.")) {
            return;
          }
          delBtn.disabled = true;
          try {
            await LS.db.ref("storeApps/" + key).remove();
            if (app.storagePath) {
              try {
                await LS.classicStoreDeleteObject(app.storagePath);
              } catch (storageErr) {
                LS.toast("Quitada de la lista, pero no se pudo borrar el archivo en Storage: " + storageErr.message, "warn");
                return;
              }
            }
            LS.toast("«" + (app.label || pkg) + "» eliminada de la tienda clásica.", "ok");
          } catch (err) {
            delBtn.disabled = false;
            LS.toast("No se pudo eliminar: " + err.message, "error");
          }
        }
      });
    }

    const syncBtn = $("tc-sync-fleet");
    if (syncBtn) {
      syncBtn.addEventListener("click", async function () {
        const ids = Object.keys(S.devices || {});
        if (!ids.length) {
          LS.toast("No hay celulares registrados.", "error");
          return;
        }
        if (!confirm("¿Avisarles a los " + ids.length + " celulares que apliquen los bloqueos WebView / Internet de la tienda clásica ahora mismo?")) {
          return;
        }
        syncBtn.disabled = true;
        const card = $("tc-envio-card");
        const log = $("tc-envio");
        if (card) card.style.display = "";
        if (log) log.innerHTML = "";
        let ok = 0, pend = 0, mal = 0;
        for (const id of ids) {
          const d = S.devices[id] || {};
          const nombre = d.deviceName || (d.info && d.info.deviceName) || d.model || id.slice(0, 10);
          const line = document.createElement("div");
          line.className = "wait";
          line.textContent = "… " + nombre;
          if (log) log.appendChild(line);
          const r = await LS.sendCommand(id, "SYNC_PLAY_STORE", null, null, { nombre, timeoutMs: 20000 });
          if (r.ok && r.pendiente) {
            pend++;
            line.className = "wait";
            line.textContent = "⏳ " + nombre + " — en su buzón";
          } else if (r.ok) {
            ok++;
            line.className = "ok";
            line.textContent = "✓ " + nombre;
          } else {
            mal++;
            line.className = "bad";
            line.textContent = "✗ " + nombre + " — " + (r.reason || r.status);
          }
        }
        syncBtn.disabled = false;
        LS.toast(ok + " aplicaron" + (pend ? ", " + pend + " en su buzón" : "") + ", " + mal + " no contestaron.", (mal || pend) ? "warn" : "ok");
      });
    }
  }

  LS.requireAuth(function () {
    const sec = $("tc-section");
    if (!sec) return;
    if (location.hash === "#tc-section") {
      sec.open = true;
    }
    bindEvents();
    LS.db.ref("storeApps").on("value", (snap) => {
      S.storeApps = snap.val() || {};
      render();
    });
    LS.db.ref("globalSettings/allowedPackages").on("value", (snap) => {
      const val = snap.val() || [];
      S.allowedSet = new Set(Array.isArray(val) ? val : Object.values(val));
      render();
    });
    LS.db.ref("devices").on("value", (snap) => {
      S.devices = snap.val() || {};
    });
  });
})();
