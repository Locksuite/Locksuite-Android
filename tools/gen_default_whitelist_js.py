#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GENERADOR DE `admin-backend/public/default_whitelist_catalog.js`.

Lee `app/src/main/assets/default_whitelist_catalog.txt` (el catálogo por defecto
para cada equipo al activar el modo lista blanca) y genera una copia JS exacta para
el panel web.

    python tools/gen_default_whitelist_js.py            # escribe default_whitelist_catalog.js
    python tools/gen_default_whitelist_js.py --check    # sale 1 si falta o difiere
"""

import json
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(RAIZ, "app", "src", "main", "assets", "default_whitelist_catalog.txt")
SALIDA = os.path.join(RAIZ, "admin-backend", "public", "default_whitelist_catalog.js")


def construir():
    if not os.path.exists(ASSET):
        raise SystemExit("FALTA %s" % ASSET)
    raw = open(ASSET, encoding="utf-8").read().replace("\r\n", "\n").replace("\r", "\n")
    if "lockfox-dominios-v1" not in raw:
        raise SystemExit("default_whitelist_catalog.txt no tiene cabecera lockfox-dominios-v1")
    apps = re.findall(r"^\[APP:\s*([^\]]+)\]", raw, re.MULTILINE)
    if len(apps) < 20:
        raise SystemExit("Solo se encontraron %d apps en %s" % (len(apps), ASSET))
    if "[GLOBAL: PERMITIDOS]" not in raw or "[GLOBAL: PROHIBIDOS]" not in raw:
        raise SystemExit("Faltan bloques [GLOBAL: PERMITIDOS] / [GLOBAL: PROHIBIDOS] en %s" % ASSET)

    return (
        "// ARCHIVO GENERADO — NO EDITAR A MANO.\n"
        "//\n"
        "// Lo genera `tools/gen_default_whitelist_js.py` desde\n"
        "// `app/src/main/assets/default_whitelist_catalog.txt`, para que Android y panel web\n"
        "// arranquen con exactamente el mismo catálogo por defecto.\n"
        "//\n"
        "// Para regenerarlo:  python tools/gen_default_whitelist_js.py\n"
        "// Para verificarlo:  python tools/gen_default_whitelist_js.py --check\n"
        "window.LOCKSUITE_DEFAULT_WHITELIST_TXT = " + json.dumps(raw, ensure_ascii=False) + ";\n"
    )


def main():
    nuevo = construir()
    if "--check" in sys.argv:
        if not os.path.exists(SALIDA):
            print("FALTA %s — corré: python tools/gen_default_whitelist_js.py" % SALIDA)
            return 1
        actual = open(SALIDA, encoding="utf-8").read()
        if actual != nuevo:
            print("default_whitelist_catalog.js NO coincide con default_whitelist_catalog.txt.")
            print("Regeneralo con: python tools/gen_default_whitelist_js.py")
            return 1
        print("OK: default_whitelist_catalog.js coincide con default_whitelist_catalog.txt")
        return 0

    os.makedirs(os.path.dirname(SALIDA), exist_ok=True)
    with open(SALIDA, "w", encoding="utf-8", newline="\n") as f:
        f.write(nuevo)
    print("Escrito %s (%d bytes)" % (SALIDA, len(nuevo)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
