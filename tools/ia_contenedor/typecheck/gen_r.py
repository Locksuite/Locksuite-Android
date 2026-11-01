#!/usr/bin/env python3
"""Genera un R.java de mentira (solo para que el verificador de tipos resuelva `R.xxx.yyy`)
leyendo app/src/main/res. Los valores son inventados; solo importan los nombres.

Uso: gen_r.py <carpeta res> <R.java de salida>
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

res_dir, out_path = sys.argv[1], sys.argv[2]
out = {}


def add(kind, name):
    out.setdefault(kind, set()).add(name.replace('.', '_'))


VALUE_TAGS = {
    'string': 'string', 'color': 'color', 'dimen': 'dimen', 'style': 'style', 'bool': 'bool',
    'integer': 'integer', 'string-array': 'array', 'integer-array': 'array', 'array': 'array',
    'plurals': 'plurals', 'attr': 'attr',
}
FILE_TYPES = ('drawable', 'layout', 'mipmap', 'xml', 'raw', 'anim', 'menu', 'color', 'font',
              'animator', 'navigation', 'transition')

for d in sorted(os.listdir(res_dir)):
    p = os.path.join(res_dir, d)
    if not os.path.isdir(p):
        continue
    typ = d.split('-')[0]
    for f in os.listdir(p):
        fp = os.path.join(p, f)
        base = f.split('.')[0]
        if typ == 'values':
            try:
                root = ET.parse(fp).getroot()
            except Exception:
                continue
            for el in root:
                name = el.get('name')
                if not name:
                    continue
                if el.tag in VALUE_TAGS:
                    add(VALUE_TAGS[el.tag], name)
                elif el.tag == 'item' and el.get('type'):
                    add(el.get('type'), name)
        else:
            if typ in FILE_TYPES:
                add(typ, base)
            if typ in ('layout', 'menu', 'drawable', 'xml'):
                try:
                    txt = open(fp, encoding='utf-8', errors='ignore').read()
                    for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', txt):
                        add('id', m.group(1))
                except OSError:
                    pass

lines = ['package com.ejemplo.locksuite;', 'public final class R {']
n = 0x7f000000
for kind in sorted(out):
    lines.append('  public static final class %s {' % kind)
    for name in sorted(out[kind]):
        n += 1
        lines.append('    public static final int %s = 0x%08x;' % (name, n))
    lines.append('  }')
lines.append('}')
os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
with open(out_path, 'w') as fh:
    fh.write('\n'.join(lines))
print('R.java generado:', {k: len(v) for k, v in sorted(out.items())})
