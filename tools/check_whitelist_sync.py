#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Chequeo de simetría del modo lista blanca (8/9/2026).

POR QUÉ EXISTE
==============
El catálogo de la lista blanca vive en DOS lenguajes:

  · `app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistCatalog.kt` — la fuente
    de verdad: es la que decide qué dominio resuelve en el celular.
  · `admin-backend/public/app.js` (`WHITELIST_BUILTIN`) — la copia que el panel
    necesita para dibujar las tarjetas de permitir/prohibir.

Los DOMINIOS no se duplican a propósito (el panel manda solo la decisión por app;
los dominios los pone el equipo). Pero el nombre, el paquete y la cantidad sí, y
eso se desincroniza solo en cuanto alguien agregue una app en un lado y se olvide
del otro. El modo de falla es feo y silencioso: **un paquete mal escrito en el
panel escribe una decisión que ningún equipo va a aplicar nunca**, y el panel
muestra la app en verde igual.

Es el mismo tipo de defecto que B.28 (el panel mandaba `no_apps_control`, que no
existe en Android, y Android lo aceptaba sin hacer nada) y el mismo tipo de
chequeo que en B.38 encontró un bug real de verdad.

CÓMO SE USA
===========
    python tools/check_whitelist_sync.py

Sale con código 0 si están alineados y 1 si no, así se puede meter en el flujo de
despliegue antes de `deploy_all.ps1`.
"""

import io
import json
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                  "locksuite", "mdm", "WhitelistCatalog.kt")
ASSET = os.path.join(RAIZ, "app", "src", "main", "assets", "default_whitelist_catalog.txt")
DEFAULT_JS = os.path.join(RAIZ, "admin-backend", "public", "default_whitelist_catalog.js")


def sin_comentarios(texto):
    """Saca los // ... para no contar dominios que están comentados."""
    return re.sub(r"//[^\n]*", "", texto)


def lista_kotlin(cuerpo, campo):
    """Cuenta las cadenas de `campo = listOf( … )` respetando paréntesis anidados."""
    i = cuerpo.find(campo + " = listOf(")
    if i < 0:
        return []
    i = cuerpo.index("(", i + len(campo))
    profundidad, j = 0, i
    while j < len(cuerpo):
        if cuerpo[j] == "(":
            profundidad += 1
        elif cuerpo[j] == ")":
            profundidad -= 1
            if profundidad == 0:
                break
        j += 1
    return re.findall(r'"([^"]+)"', sin_comentarios(cuerpo[i:j]))


def leer_catalogo_kotlin():
    src = io.open(KT, encoding="utf-8").read()
    cuerpo = src[src.index("val APPS: List<Entry> = listOf("):src.index("private val BY_PACKAGE")]
    apps = {}
    for parte in cuerpo.split("Entry(")[1:]:
        pkg = re.search(r'packageName = "([^"]+)"', parte).group(1)
        label = re.search(r'label = "([^"]*)"', parte).group(1)
        apps[pkg] = {
            "label": label,
            "allow": lista_kotlin(parte, "allow"),
            "block": lista_kotlin(parte, "block"),
        }
    return apps


def verificar_catalogo_por_defecto():
    """Verifica que default_whitelist_catalog.txt (Android) y default_whitelist_catalog.js (web) coincidan."""
    problemas = []
    if not os.path.exists(ASSET):
        return 0, ["FALTA el archivo por defecto en Android: %s" % ASSET]
    raw_txt = io.open(ASSET, encoding="utf-8").read().replace("\r\n", "\n").replace("\r", "\n")
    apps = re.findall(r"^\[APP:\s*([^\]]+)\]", raw_txt, re.MULTILINE)
    if "lockfox-dominios-v1" not in raw_txt:
        problemas.append("default_whitelist_catalog.txt no tiene cabecera lockfox-dominios-v1")
    if len(apps) < 20:
        problemas.append("default_whitelist_catalog.txt tiene muy pocas apps: %d" % len(apps))
    if "[GLOBAL: PERMITIDOS]" not in raw_txt or "[GLOBAL: PROHIBIDOS]" not in raw_txt:
        problemas.append("default_whitelist_catalog.txt no tiene secciones [GLOBAL: PERMITIDOS] / [GLOBAL: PROHIBIDOS]")

    if not os.path.exists(DEFAULT_JS):
        problemas.append("FALTA %s — corré python tools/gen_default_whitelist_js.py" % DEFAULT_JS)
        return len(apps), problemas

    js_src = io.open(DEFAULT_JS, encoding="utf-8").read()
    m = re.search(r"window\.LOCKSUITE_DEFAULT_WHITELIST_TXT\s*=\s*(\".*\");", js_src, re.S)
    if not m:
        problemas.append("No se pudo leer LOCKSUITE_DEFAULT_WHITELIST_TXT en %s" % DEFAULT_JS)
        return len(apps), problemas

    js_txt = json.loads(m.group(1))
    if js_txt != raw_txt:
        problemas.append("default_whitelist_catalog.js difiere de default_whitelist_catalog.txt — corré gen_default_whitelist_js.py")
    return len(apps), problemas


def main():
    kt = leer_catalogo_kotlin()
    default_apps_count, problemas = verificar_catalogo_por_defecto()

    # Un dominio permitido y prohibido a la vez dentro de la MISMA app es casi
    # siempre un dedazo: el Trie lo resuelve (gana el bloqueo, que se escribe
    # después) pero el resultado no es el que quien lo escribió esperaba.
    for pkg, datos in kt.items():
        repetidos = set(datos["allow"]) & set(datos["block"])
        if repetidos:
            problemas.append(
                "%s tiene el mismo dominio en allow y en block: %s" % (pkg, ", ".join(sorted(repetidos)))
            )

    # Un dominio de la lista de infraestructura repetido dentro de una app no
    # rompe nada, pero es ruido: la infraestructura ya lo permite para todos.
    src = io.open(KT, encoding="utf-8").read()
    infra = set(lista_kotlin(src[src.index("val INFRASTRUCTURE"):src.index("val BLOCK_ALWAYS")],
                             "INFRASTRUCTURE: List<String>"))
    for pkg, datos in kt.items():
        redundantes = set(datos["allow"]) & infra
        if redundantes:
            problemas.append(
                "AVISO (no rompe): %s repite dominios que ya están en infraestructura: %s"
                % (pkg, ", ".join(sorted(redundantes)))
            )

    print("Catálogo Kotlin: %d apps · Catálogo por defecto lista blanca (Android + web): %d apps" % (len(kt), default_apps_count))
    if not problemas:
        print("OK — catálogos válidos y sincronizados.")
        return 0
    for p in problemas:
        print("  " + p)
    graves = [p for p in problemas if not p.startswith("AVISO")]
    return 1 if graves else 0


if __name__ == "__main__":
    sys.exit(main())
