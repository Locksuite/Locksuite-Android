# Pruebas de las reglas de Realtime Database

`test_rules.mjs` prueba `admin-backend/database.rules.json` contra el **emulador real**
de Realtime Database (no es una simulación a mano). Son 55 comprobaciones:

- lo que el celular legítimo TIENE que poder hacer (leer su `appPolicy`, su nombre,
  sus pedidos, su buzón `pendingCommands`, escribir el latido y los acks, etc.);
- el alta de un equipo nuevo, incluso si escribe `deviceSecrets` antes que `devices`;
- los ataques que TIENEN que fallar (secuestrar un equipo ajeno, pisar su
  `commandSecret`, leer la lista de equipos, inyectar comandos en el buzón…);
- la re-vinculación de un equipo reinstalado (`deviceClaims` → Aprobar en el panel);
- lo que hace el panel (admin);
- **(6/11/2026, B.110) el registro de errores remoto `deviceDiag/<id>`** (11 comprobaciones,
  las que empiezan con `diag:`): el celular sube sus renglones, su `meta` y un diagnóstico
  completo, y borra los viejos; **NO** lee su propio registro (solo el panel) y **NO**
  escribe el de otro equipo; un celular ajeno, uno sin sesión o un `deviceId` sin nodo
  en `devices/` no escriben ni leen nada; el panel lo lee y puede vaciarlo.

## Cómo correrlas

Hace falta Node 18+ y Java 11+ (el emulador es un `.jar`).

```bash
cd tools/rules_tests
npm install
npm test
```

Tiene que terminar con `TODAS VERDES (55)` y código 0. Cualquier `✗ ROJO` es un
cambio de reglas que rompe algo: no se despliega hasta entender por qué.

> ⚠️ Las 11 comprobaciones `diag:` (B.110) se escribieron en un contenedor **sin emulador**:
> nunca corrieron de verdad. Si alguna sale en rojo, el que manda es el emulador, no esta
> nota: corregí la regla (`deviceDiag` en `database.rules.json`) o la comprobación, y avisá.

`emulator_start.rules.json` solo sirve para que el emulador arranque (niega todo); la
prueba carga las reglas de verdad sola, desde `admin-backend/database.rules.json`.

Para probar OTRO archivo de reglas (por ejemplo, para comparar con las viejas):

```bash
RULES=/ruta/a/otras.rules.json npm test
```

Control negativo del 22/9/2026: con las reglas anteriores (commit `fd76ccc`) salen
**17 en rojo**, entre ellas que cualquier sesión anónima podía secuestrar un equipo
ajeno y que el celular no podía leer su propia `appPolicy`.
