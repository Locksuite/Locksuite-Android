#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_store_sync.py — CHEQUEO DE SIMETRÍA DE LA TIENDA PLAY (29/10/2026, B.101).

QUÉ BUG EVITA
─────────────
Las reglas de la Tienda Play viven en TRES lugares que tienen que decir LO MISMO:

    celular   app/.../mdm/PlayStoreCatalog.kt         (lo que el equipo acepta)
    panel     admin-backend/public/tienda_logic.js    (lo que el panel guarda)
    función   admin-backend/functions/index.js        (los modos que valida SET_PLAY_STORE_MODE)
              admin-backend/functions/play_lookup.js  (el CDN de íconos y el nombre de paquete)

Si el panel deja pasar algo que el celular descarta (un nivel fuera de 1..3, un paquete que
la tienda nunca debe ofrecer, un acceso nuevo), el administrador ve la app en su lista y el
usuario no la ve en la tienda, SIN NINGÚN MENSAJE. Es la misma familia de bugs mudos que
este proyecto ya pagó varias veces (B.28, B.40, B.57), y la misma defensa que
`check_profile_sync.py` y `check_whitelist_sync.py`: un script que sale con código 1 si las
copias se desparejan.

Compara: modos, estados, accesos, niveles, topes, lista de paquetes que NUNCA se ofrecen,
hosts de íconos, expresiones de paquete/dominio y las huellas de los stubs de Android Auto.

Uso:    python3 tools/check_store_sync.py        (código 0 = todo igual)
"""
import io
import os
import re
import sys

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
KT = os.path.join(RAIZ, "app/src/main/java/com/ejemplo/locksuite/mdm/PlayStoreCatalog.kt")
KT_AA = os.path.join(RAIZ, "app/src/main/java/com/ejemplo/locksuite/mdm/AndroidAutoStubs.kt")
JS = os.path.join(RAIZ, "admin-backend/public/tienda_logic.js")
FN = os.path.join(RAIZ, "admin-backend/functions/index.js")
LK = os.path.join(RAIZ, "admin-backend/functions/play_lookup.js")

problemas = []
verificados = []


def leer(ruta):
    with io.open(ruta, encoding="utf-8") as fh:
        return fh.read()


def exigir(valor, que, donde):
    """Un chequeo que no encuentra lo que busca NO puede pasar en silencio."""
    if valor is None or valor == [] or valor == set() or valor == {}:
        problemas.append("NO PUDE LEER %s en %s (¿cambió el formato del archivo?)" % (que, donde))
        return False
    return True


def comparar(nombre, kt, js, extra=None):
    if kt != js:
        solo_kt = sorted(set(kt) - set(js)) if isinstance(kt, (set, list)) else kt
        solo_js = sorted(set(js) - set(kt)) if isinstance(js, (set, list)) else js
        problemas.append("%s DISTINTO — solo en celular: %s · solo en panel: %s" % (nombre, solo_kt, solo_js))
    else:
        verificados.append(nombre)
    if extra is not None and kt != extra:
        problemas.append("%s DISTINTO en la función — celular: %s · función: %s" % (nombre, sorted(kt), sorted(extra)))


def strings(bloque):
    return re.findall(r'"([^"]*)"', bloque)


def kt_const_str(src, prefijo):
    return {m.group(2) for m in re.finditer(r'const val (%s_[A-Z_]+) = "([^"]*)"' % prefijo, src)}


def kt_const_int(src, nombre):
    m = re.search(r"const val %s = (\d+)" % nombre, src)
    return int(m.group(1)) if m else None


def js_int(src, nombre):
    m = re.search(r"const %s = (\d+);" % nombre, src)
    return int(m.group(1)) if m else None


def js_objeto_valores(src, nombre):
    m = re.search(r"const %s = \{([^}]*)\};" % nombre, src)
    return set(strings(m.group(1))) if m else None


def js_lista(src, nombre):
    m = re.search(r"const %s = \[(.*?)\];" % nombre, src, re.S)
    return strings(m.group(1)) if m else None


def main():
    getattr(sys.stdout, 'reconfigure', lambda **kw: None)(encoding='utf-8', errors='replace')
    kt, js, fn, lk, aa = leer(KT), leer(JS), leer(FN), leer(LK), leer(KT_AA)

    # ── Modos ──────────────────────────────────────────────────────────────
    k_modos = kt_const_str(kt, "MODE")
    j_modos = set(js_lista(js, "MODES") or [])
    m = re.search(r"const PLAY_STORE_MODES = new Set\(\[(.*?)\]\)", fn, re.S)
    f_modos = set(strings(m.group(1))) if m else None
    if exigir(k_modos, "los MODE_*", "PlayStoreCatalog.kt") and exigir(j_modos, "MODES", "tienda_logic.js") \
            and exigir(f_modos, "PLAY_STORE_MODES", "functions/index.js"):
        comparar("modos (cerrada/kosher/trabajo/lite)", k_modos, j_modos, f_modos)

    # ── Estados y accesos ───────────────────────────────────────────────────
    for nombre, prefijo, objeto in (("estados", "STATUS", "STATUS"), ("accesos", "ACCESS", "ACCESS")):
        k = kt_const_str(kt, prefijo)
        j = js_objeto_valores(js, objeto)
        if exigir(k, "los %s_*" % prefijo, "PlayStoreCatalog.kt") and exigir(j, objeto, "tienda_logic.js"):
            comparar(nombre, k, j)

    # ── Niveles y topes ─────────────────────────────────────────────────────
    k_min, k_max = kt_const_int(kt, "TIER_KOSHER"), kt_const_int(kt, "TIER_LITE")
    j_min, j_max = js_int(js, "TIER_MIN"), js_int(js, "TIER_MAX")
    if None in (k_min, k_max, j_min, j_max):
        problemas.append("NO PUDE LEER los niveles (TIER_*) en alguno de los dos archivos")
    else:
        comparar("rango de niveles", (k_min, k_max), (j_min, j_max))
    for tope in ("MAX_APPS", "MAX_EXTRAS", "MAX_DOMAINS_PER_LIST", "MAX_LABEL_CHARS"):
        k, j = kt_const_int(kt, tope), js_int(js, tope)
        if k is None or j is None:
            problemas.append("NO PUDE LEER %s en alguno de los dos archivos" % tope)
        else:
            comparar("tope " + tope, k, j)

    # ── Lo que nunca se ofrece ──────────────────────────────────────────────
    m = re.search(r"val NEVER_OFFERED: Set<String> = setOf\((.*?)\)\s*\n", kt, re.S)
    k_never = set(strings(m.group(1))) if m else None
    j_never = set(js_lista(js, "NEVER_OFFERED") or [])
    if exigir(k_never, "NEVER_OFFERED", "PlayStoreCatalog.kt") and exigir(j_never, "NEVER_OFFERED", "tienda_logic.js"):
        comparar("paquetes que NUNCA se ofrecen", k_never, j_never)
        # LockSuite y Google Play tienen que estar SIEMPRE (la tienda no puede reinstalarlos).
        for pkg in ("com.ejemplo.locksuite", "com.android.vending", "com.google.android.gms"):
            if pkg not in k_never:
                problemas.append("NEVER_OFFERED ya no incluye %s — la tienda podría ofrecerlo" % pkg)

    # ── Íconos ──────────────────────────────────────────────────────────────
    m = re.search(r"ICON_HOST_SUFFIXES = listOf\((.*?)\)", kt, re.S)
    k_hosts = strings(m.group(1)) if m else None
    j_hosts = js_lista(js, "ICON_HOST_SUFFIXES")
    m = re.search(r"const ICON_HOST_SUFFIXES = \[(.*?)\];", lk, re.S)
    l_hosts = strings(m.group(1)) if m else None
    if exigir(k_hosts, "ICON_HOST_SUFFIXES", "PlayStoreCatalog.kt") and exigir(j_hosts, "ICON_HOST_SUFFIXES", "tienda_logic.js") \
            and exigir(l_hosts, "ICON_HOST_SUFFIXES", "play_lookup.js"):
        comparar("hosts de íconos (celular/panel)", k_hosts, j_hosts)
        comparar("hosts de íconos (celular/función de consulta)", k_hosts, l_hosts)

    # ── Expresiones de paquete y de dominio ─────────────────────────────────
    def kt_regex(nombre):
        mm = re.search(r'val %s = Regex\("(.*?)"\)\n' % nombre, kt)
        return mm.group(1).replace("\\\\", "\\") if mm else None

    def js_regex(src, nombre):
        mm = re.search(r"const %s = /(.*)/;" % nombre, src)
        return mm.group(1) if mm else None

    for etiqueta, k_nom, j_nom, fuente in (
        ("expresión de paquete (celular/panel)", "PACKAGE_REGEX", "PACKAGE_RE", js),
        ("expresión de paquete (celular/función de consulta)", "PACKAGE_REGEX", "PACKAGE_RE", lk),
        ("expresión de dominio (celular/panel)", "DOMAIN_REGEX", "DOMAIN_RE", js),
    ):
        k, j = kt_regex(k_nom), js_regex(fuente, j_nom)
        if k is None or j is None:
            problemas.append("NO PUDE LEER %s" % etiqueta)
        else:
            comparar(etiqueta, k, j)

    # ── Huellas de los stubs de Android Auto (no son apps de Google Play) ─────
    pkgs = dict(re.findall(r'const val (PKG_[A-Z_]+) = "([^"]+)"', aa))
    k_aa = {}
    bloque = re.search(r"val APK_SHA256: Map<String, String> = mapOf\((.*?)\)\s*\n", aa, re.S)
    if bloque:
        for nombre, huella in re.findall(r'(PKG_[A-Z_]+) to "([0-9a-f]{64})"', bloque.group(1)):
            if nombre in pkgs:
                k_aa[pkgs[nombre]] = huella
    m = re.search(r"const AA_STUB_SHA256 = \{(.*?)\};", js, re.S)
    j_aa = dict(re.findall(r'"([^"]+)":\s*"([0-9a-f]{64})"', m.group(1))) if m else None
    if exigir(k_aa, "APK_SHA256", "AndroidAutoStubs.kt") and exigir(j_aa, "AA_STUB_SHA256", "tienda_logic.js"):
        comparar("huellas de los stubs de Android Auto", k_aa, j_aa)

    for v in verificados:
        print("✅ " + v)
    if problemas:
        print()
        for p in problemas:
            print("❌ " + p)
        print("\nLa Tienda Play del celular, la del panel y la función NO dicen lo mismo (B.101).")
        return 1
    print("\nOK — celular, panel y función de la Tienda Play dicen lo mismo (%d comparaciones)." % len(verificados))
    return 0


if __name__ == "__main__":
    sys.exit(main())
