#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_diag_sync.py — CHEQUEO DE SIMETRÍA DEL REGISTRO DE DIAGNÓSTICO REMOTO (6/11/2026, B.110).

QUÉ BUG EVITA
─────────────
El registro de errores de cada celular viaja por CINCO lugares que tienen que decir LO MISMO:

    celular   app/.../diag/DiagUploader.kt, DiagUploadPolicy.kt, DiagLog.kt, DiagCollector.kt, DiagEvent.kt
    panel     admin-backend/public/diag_logic.js      (LAYOUT: lo que se lee) y diag_remoto.js
    función   admin-backend/functions/index.js        (comandos permitidos, PIN, vencimiento)
    reglas    admin-backend/database.rules.json       (quién escribe y quién lee `deviceDiag`)
    pruebas   tools/rules_tests/test_rules.mjs y tools/panel_render/fake-function.js

Si el celular escribe `events/e…/m` y el panel lee `msg`, la pantalla queda VACÍA sin ningún
error. Si la función no deja pasar `COLLECT_DIAGNOSTICS`, el botón «Pedir diagnóstico» da un
rechazo que el dueño no entiende. Si las reglas no dejan escribir al celular, TODAS las subidas
fallan con «permission denied». Es la misma familia de bugs mudos que este proyecto ya pagó
varias veces (B.28, B.40, B.57) y la misma defensa que `check_store_sync.py`.

Compara: nombre del nodo raíz y de sus hijos, claves de cada renglón y de cada diagnóstico,
campos que el celular publica en `devices/<id>`, campos de `meta` que el panel lee, topes
(renglones, diagnósticos, apps), niveles, los dos comandos (permitidos, de acción, excepción
del PIN, rama `enabled`), las reglas de `deviceDiag` y que las pruebas lo cubran.

Uso:    python3 tools/check_diag_sync.py        (código 0 = todo igual)
"""
import io
import json
import os
import re
import sys

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
KT = os.path.join(RAIZ, "app/src/main/java/com/ejemplo/locksuite/diag")
UPLOADER = os.path.join(KT, "DiagUploader.kt")
POLICY = os.path.join(KT, "DiagUploadPolicy.kt")
DIAGLOG = os.path.join(KT, "DiagLog.kt")
COLLECTOR = os.path.join(KT, "DiagCollector.kt")
EVENT = os.path.join(KT, "DiagEvent.kt")
PROCESSOR = os.path.join(RAIZ, "app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt")
JS = os.path.join(RAIZ, "admin-backend/public/diag_logic.js")
JS_UI = os.path.join(RAIZ, "admin-backend/public/diag_remoto.js")
FN = os.path.join(RAIZ, "admin-backend/functions/index.js")
RULES = os.path.join(RAIZ, "admin-backend/database.rules.json")
RULES_TEST = os.path.join(RAIZ, "tools/rules_tests/test_rules.mjs")
FAKE_FN = os.path.join(RAIZ, "tools/panel_render/fake-function.js")
APP_JS = os.path.join(RAIZ, "admin-backend/public/app.js")
PANEL_RUN = os.path.join(RAIZ, "tools/panel_render/run.sh")
PANEL_BORRADO = os.path.join(RAIZ, "tools/panel_render/test_diag_borrado.js")

problemas = []
verificados = []


def leer(ruta):
    with io.open(ruta, encoding="utf-8") as fh:
        return fh.read()


def exigir(valor, que, donde):
    """Un chequeo que no encuentra lo que busca NO puede pasar en silencio."""
    if valor is None or valor == [] or valor == set() or valor == {} or valor == "":
        problemas.append("NO PUDE LEER %s en %s (¿cambió el formato del archivo?)" % (que, donde))
        return False
    return True


def comparar(nombre, kt, js):
    if kt != js:
        if isinstance(kt, (set, list)) and isinstance(js, (set, list)):
            detalle = "solo en celular: %s · solo en panel: %s" % (sorted(set(kt) - set(js)), sorted(set(js) - set(kt)))
        else:
            detalle = "celular: %r · panel: %r" % (kt, js)
        problemas.append("%s DISTINTO — %s" % (nombre, detalle))
    else:
        verificados.append(nombre)


def requerir(condicion, texto):
    if condicion:
        verificados.append(texto)
    else:
        problemas.append(texto)


def bloque_balanceado(texto, desde, abre="(", cierra=")"):
    """Texto entre el primer `abre` desde `desde` y su `cierra` (balanceando)."""
    i = texto.find(abre, desde)
    if i < 0:
        return None
    prof = 0
    for j in range(i, len(texto)):
        if texto[j] == abre:
            prof += 1
        elif texto[j] == cierra:
            prof -= 1
            if prof == 0:
                return texto[i + 1:j]
    return None


def js_layout(js):
    """Lee el objeto LAYOUT de diag_logic.js (valores simples) sin necesitar Node."""
    m = re.search(r"const LAYOUT = Object\.freeze\(\{(.*?)\n  \}\);", js, re.S)
    if not m:
        return None
    cuerpo = m.group(1)
    cuerpo = re.sub(r"//[^\n]*", "", cuerpo)
    d = {}
    for k, v in re.findall(r'([A-Z_]+):\s*"([^"]*)"', cuerpo):
        d[k] = v
    for k, v in re.findall(r"([A-Z_]+):\s*(\d+)\b", cuerpo):
        d[k] = int(v)
    s = re.search(r"SNAP_FIELDS:\s*Object\.freeze\(\[(.*?)\]\)", cuerpo, re.S)
    if s:
        d["SNAP_FIELDS"] = re.findall(r'"([^"]+)"', s.group(1))
    return d


def main():
    kt_up, kt_pol, kt_log = leer(UPLOADER), leer(POLICY), leer(DIAGLOG)
    kt_col, kt_ev, kt_proc = leer(COLLECTOR), leer(EVENT), leer(PROCESSOR)
    js, js_ui, fn = leer(JS), leer(JS_UI), leer(FN)
    L = js_layout(js)
    if not exigir(L, "el objeto LAYOUT", "diag_logic.js"):
        return fin()

    # ── nodos de Firebase ─────────────────────────────────────────────────────────
    raiz = re.search(r'getReference\("([A-Za-z]+)/\$\{', kt_up)
    if exigir(raiz, "getReference(\"<raíz>/…\")", "DiagUploader.kt"):
        comparar("nodo raíz de Firebase", raiz.group(1), L.get("ROOT"))
    hijos = set(re.findall(r'updates\["(events|snapshots|meta)/', kt_up))
    if exigir(hijos, "updates[\"events|snapshots|meta/…\"]", "DiagUploader.kt"):
        comparar("nodos hijos (events / snapshots / meta)", hijos, {L.get("EVENTS"), L.get("SNAPSHOTS"), L.get("META")})

    # ── claves de cada renglón ────────────────────────────────────────────────────
    em = re.search(r"fun eventMap\(.*?\n    \}", kt_pol, re.S)
    claves_ev = set(re.findall(r'm\["(\w+)"\]', em.group(0))) if em else None
    if exigir(claves_ev, "las claves de eventMap", "DiagUploadPolicy.kt"):
        comparar("claves de cada renglón (t, l, g, m, n)", claves_ev,
                 {L.get("EVENT_AT"), L.get("EVENT_LEVEL"), L.get("EVENT_TAG"), L.get("EVENT_MSG"), L.get("EVENT_COUNT")})

    # ── campos de cada diagnóstico completo ───────────────────────────────────────
    i = kt_up.find('updates["snapshots/"')
    cuerpo = bloque_balanceado(kt_up, kt_up.find("mapOf(", i)) if i >= 0 else None
    claves_snap = re.findall(r'"([a-z]+)" to', cuerpo) if cuerpo else None
    if exigir(claves_snap, "las claves del diagnóstico completo", "DiagUploader.kt"):
        comparar("campos de cada diagnóstico completo", set(claves_snap), set(L.get("SNAP_FIELDS", [])))

    # ── campos del nodo del equipo (devices/<id>) ─────────────────────────────────
    campos_dev = set(re.findall(r'"(diag[A-Za-z]+)" to', kt_log)) | set(re.findall(r'"(diag[A-Za-z]+)" to', kt_up))
    if exigir(campos_dev, "los campos diag* que publica el celular", "DiagLog.kt / DiagUploader.kt"):
        comparar("campos que el celular publica en devices/<id>", campos_dev,
                 {L.get("DEV_VERSION"), L.get("DEV_ENABLED"), L.get("DEV_ERROR")})

    # ── campos de meta que el panel lee ───────────────────────────────────────────
    meta_kt = set(re.findall(r'updates\["meta/(\w+)"\]', kt_up))
    meta_js = set(re.findall(r"\bmeta\.(\w+)", js)) | set(re.findall(r"\bS\.meta\.(\w+)", js_ui))
    if exigir(meta_kt, "los campos meta/* que escribe el celular", "DiagUploader.kt") and exigir(meta_js, "los campos meta.* que lee el panel", "diag_logic.js"):
        faltan = sorted(meta_js - meta_kt)
        requerir(not faltan, "el panel lee de `meta` solo campos que el celular escribe (%s)%s" %
                 (", ".join(sorted(meta_js)), "" if not faltan else " — NO los escribe el celular: %s" % faltan))

    # ── topes ─────────────────────────────────────────────────────────────────────
    for nombre_kt, nombre_js, archivo, texto in (
        ("SERVER_KEEP", "EVENTS_KEEP", "DiagUploadPolicy.kt", kt_pol),
        ("SNAPSHOTS_KEEP", "SNAPSHOTS_KEEP", "DiagUploadPolicy.kt", kt_pol),
        ("MAX_PACKAGES", "MAX_PACKAGES", "DiagCollector.kt", kt_col),
    ):
        m = re.search(r"const val %s\s*=\s*(\d+)" % nombre_kt, texto)
        if exigir(m, nombre_kt, archivo):
            comparar("tope %s ↔ LAYOUT.%s" % (nombre_kt, nombre_js), int(m.group(1)), L.get(nombre_js))

    # ── niveles ───────────────────────────────────────────────────────────────────
    niv_kt = set(re.findall(r"const val LEVEL_\w+\s*=\s*'(\w)'", kt_ev))
    m = re.search(r"const RANK = \{([^}]*)\}", js)
    niv_js = set(re.findall(r"(\w):\s*\d", m.group(1))) if m else None
    if exigir(niv_kt, "los LEVEL_* de DiagEvent", "DiagEvent.kt") and exigir(niv_js, "RANK", "diag_logic.js"):
        comparar("niveles de un renglón (I, W, E, F)", niv_kt, niv_js)

    # ── comandos ──────────────────────────────────────────────────────────────────
    cmd_c, cmd_s = L.get("CMD_COLLECT"), L.get("CMD_SWITCH")
    m = re.search(r"const ALLOWED_COMMANDS\s*=\s*new Set\(\[(.*?)\]\);", fn, re.S)
    permitidos = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', m.group(1))) if m else set()
    m = re.search(r"const ACTION_COMMANDS\s*=\s*new Set\(\[(.*?)\]\);", fn, re.S)
    acciones = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', m.group(1))) if m else set()
    if exigir(permitidos, "ALLOWED_COMMANDS", "functions/index.js"):
        requerir(cmd_c in permitidos and cmd_s in permitidos, "la función permite %s y %s" % (cmd_c, cmd_s))
    if exigir(acciones, "ACTION_COMMANDS", "functions/index.js"):
        requerir(cmd_c in acciones, "%s vence en un día (es una acción puntual: ACTION_COMMANDS)" % cmd_c)
        requerir(cmd_s not in acciones, "%s NO vence (es un estado deseado, no una acción)" % cmd_s)
    exento = re.search(r'command\s*!==\s*"UPDATE_LOCKSUITE".*?command\s*!==\s*"%s"' % re.escape(cmd_c or "?"), fn, re.S)
    requerir(bool(exento), "%s está exento del PIN en la función (es de solo lectura; sin esto no se diagnostica un celular sin PIN publicado)" % cmd_c)
    requerir(not re.search(r'command\s*!==\s*"%s"' % re.escape(cmd_s or "?"), fn), "%s SÍ exige el PIN (cambia el comportamiento del equipo)" % cmd_s)
    requerir(bool(re.search(r'command === "SET_HIDE_SUSPENDED_APPS"\s*\|\|\s*command === "%s"' % re.escape(cmd_s or "?"), fn)),
             "la función lee el booleano `enabled` de %s" % cmd_s)
    requerir('"%s" ->' % cmd_c in kt_proc and '"%s" ->' % cmd_s in kt_proc, "el celular tiene una rama para %s y para %s" % (cmd_c, cmd_s))
    ini = kt_proc.find("val allowedWhileSuspended")
    cuerpo_susp = bloque_balanceado(kt_proc, ini) if ini >= 0 else None
    susp = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', re.sub(r"//[^\n]*", "", cuerpo_susp))) if cuerpo_susp else set()
    if exigir(susp, "allowedWhileSuspended", "CommandProcessor.kt"):
        requerir(cmd_c in susp and cmd_s in susp,
                 "con LockSuite suspendido el celular igual atiende %s y %s (justo ahí hace falta diagnosticar)" % (cmd_c, cmd_s))
    fake = leer(FAKE_FN)
    m = re.search(r"const EXENTOS = \[(.*?)\]", fake, re.S)
    exentos_fake = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', m.group(1))) if m else set()
    if exigir(exentos_fake, "EXENTOS", "fake-function.js"):
        requerir(cmd_c in exentos_fake, "el panel falso de pruebas (fake-function.js) también exime a %s del PIN" % cmd_c)
    requerir(cmd_c in js_ui and cmd_s in js_ui, "la pantalla (diag_remoto.js) manda %s y %s" % (cmd_c, cmd_s))

    # ── reglas de la base ─────────────────────────────────────────────────────────
    reglas = json.loads(leer(RULES)).get("rules", {})
    d = reglas.get(L.get("ROOT"))
    if exigir(d, "el nodo %s" % L.get("ROOT"), "database.rules.json"):
        lee = d.get(".read", "")
        requerir("authorizedAdminsUids" in lee and "admin" in lee, "solo el panel (administradores) LEE %s" % L.get("ROOT"))
        por_equipo = d.get("$device_id", {})
        requerir(".read" not in por_equipo, "el celular NO puede leer el registro (no hay .read por equipo)")
        escribe = por_equipo.get(".write", "")
        requerir("ownerUid" in escribe and "$device_id" in escribe and "auth.uid" in escribe,
                 "el celular escribe SOLO el registro de SU equipo (dueño de devices/$device_id)")
        requerir("authorizedAdminsUids" in escribe, "el panel también puede escribir/borrar el registro de un equipo")
        requerir(".validate" not in json.dumps(d), "sin .validate: el panel y el celular pueden evolucionar el formato sin desplegar reglas")
    pruebas_reglas = leer(RULES_TEST).count('"diag')
    requerir(pruebas_reglas >= 8, "las pruebas del emulador de reglas cubren deviceDiag (%d comprobaciones)" % pruebas_reglas)

    # ── «Eliminar» en el panel borra también el registro; «Archivar» lo conserva ───
    # Los avisos de «Eliminar» prometen borrar «todos los datos»: sin esto el registro queda huérfano.
    app = leer(APP_JS)
    raiz_diag = L.get("ROOT")
    for nombre, ancla in (
        ("«Eliminar dispositivo» (barra lateral)", 'sidebarDeleteBtn.addEventListener("click"'),
        ("«Eliminar definitivamente» de un archivado", "async function deleteArchivedDevicePermanently"),
    ):
        i = app.find(ancla)
        fin_bloque = app.find("\n}\n", i) if i >= 0 else -1
        if i < 0 or fin_bloque < 0:
            problemas.append("NO PUDE LEER %s en app.js (¿cambió el formato del archivo?)" % nombre)
            continue
        cuerpo = app[i:fin_bloque]
        requerir(bool(re.search(r"database\.ref\(`%s/\$\{\w+\}`\)\.remove\(\)" % re.escape(raiz_diag or "?"), cuerpo)),
                 "%s borra también %s/<id> (sin registro huérfano)" % (nombre, raiz_diag))
    i = app.find('sidebarArchiveBtn.addEventListener("click"')
    fin_bloque = app.find("\n}\n", i) if i >= 0 else -1
    if i < 0 or fin_bloque < 0:
        problemas.append("NO PUDE LEER «Archivar dispositivo» en app.js (¿cambió el formato del archivo?)")
    else:
        requerir((raiz_diag or "?") not in app[i:fin_bloque],
                 "«Archivar dispositivo» CONSERVA %s/<id> (los datos archivados se pueden restaurar)" % raiz_diag)
    requerir(os.path.exists(PANEL_BORRADO) and "test_diag_borrado.js" in leer(PANEL_RUN),
             "tools/panel_render/run.sh corre la prueba del borrado (test_diag_borrado.js)")

    return fin()


def fin():
    for v in verificados:
        print("✅ " + v)
    if problemas:
        print()
        for p in problemas:
            print("❌ " + p)
        print("\nEl registro de diagnóstico del celular, el panel, la función y las reglas NO dicen lo mismo (B.110).")
        return 1
    print("\nOK — celular, panel, función y reglas del registro de diagnóstico dicen lo mismo (%d comprobaciones)." % len(verificados))
    return 0


if __name__ == "__main__":
    sys.exit(main())
