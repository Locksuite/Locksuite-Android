# Pruebas de las reglas de Realtime Database

`test_rules.mjs` prueba `admin-backend/database.rules.json` contra el **emulador real**
de Realtime Database (no es una simulación a mano). Son 90 comprobaciones:

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
- **(9/11/2026, B.113) el PIN y los secretos** (35 comprobaciones, las que empiezan con
  `secretos:`, `PIN`, `commandSecret`, `trustedAdmins` y `pinThrottle`): ningún cliente (ni
  siquiera un administrador) lee `deviceSecrets`; un administrador NO reemplaza ni crea el
  verificador del PIN ni pisa el código de recuperación, pero SÍ re-vincula un equipo; nadie
  CREA `trustedAdmins` desde un cliente (ni `info/trustedAdmins`, ni con un `update` raíz, ni dentro
  de un `set` del equipo entero; solo la Function, con el Admin SDK) aunque el administrador SÍ puede
  revocarlo (borrar), y el celular dueño sigue escribiendo el resto de su `info`; un extraño no crea
  el `commandSecret` de un equipo existente; `pinThrottle` no lo toca nadie.
  ⚠️ **Limitación conocida (no figura entre las comprobaciones):** un administrador puede escribir
  `devices/<id>/ownerUid` con el uid de una sesión anónima suya y, desde esa sesión, reemplazar
  `pinHash`/`pinSalt`; ninguna regla actual lo impide (B.113, final del punto 2). Es la causa de fondo de
  B.3; cuando se cierre, ese rodeo tiene que pasar a la lista de ataques que fallan.

## Cómo correrlas

Hace falta Node 18+ y Java 11+ (el emulador es un `.jar`).

```bash
cd tools/rules_tests
npm install
npm test
```

Tiene que terminar con `TODAS VERDES (90)` y código 0. Cualquier `✗ ROJO` es un
cambio de reglas que rompe algo: no se despliega hasta entender por qué.

> ⚠️ **Estado al 9/11/2026:** las 90 comprobaciones corrieron SOLO en el evaluador sin emulador
> (abajo), no en el emulador real. Hasta que alguien corra `npm test` con el emulador, las
> reglas de B.113 están verificadas contra la documentación de RTDB, no contra la base.
>
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

Control negativo del 9/11/2026 (B.113): con las reglas de antes de la auditoría (`git show
cbdaa32:admin-backend/database.rules.json`) salen **18 en rojo**: un administrador leía todo
`deviceSecrets` (hash y sal del PIN, `commandSecret`, código de recuperación), podía reemplazar el
verificador del PIN y podía ponerse a sí mismo en `trustedAdmins`. Con las reglas nuevas, todas verdes.

## Sin emulador: `offline/` (9/11/2026)

Si no hay Java ni red para instalar `firebase-tools` (como en el contenedor de las sesiones de IA):

```bash
cd tools/rules_tests
node --import ./offline/register.mjs test_rules.mjs                         # lectura 1
VALIDATE_ANCESTORS=1 node --import ./offline/register.mjs test_rules.mjs    # lectura 2
RULES=/ruta/a/otras.rules.json node --import ./offline/register.mjs test_rules.mjs
```

`offline/shim.mjs` es un evaluador de reglas hecho a mano que reemplaza a `@firebase/rules-unit-testing`
y a `firebase/database` (ver el encabezado del archivo para saber qué imita: `.read`/`.write` que
cascadan, `update()` camino por camino, `.validate` que no se evalúa en los borrados, variables `$x`,
`root`/`data`/`newData`). Las dos lecturas existen porque la documentación de `.validate` se puede
leer de dos maneras (solo el nodo escrito y sus descendientes, o también sus ancestros): las reglas
tienen que dar lo mismo con las dos.

**No reemplaza al emulador real.** Se calibró contra lo conocido (las 90 comprobaciones de hoy, con las
dos lecturas): con las reglas de antes de B.73 (`fd76ccc`) salen **40** en rojo = las 17 que anota este
README + 5 de `diag:` (B.110, posteriores a esas reglas) + 18 de B.113; con las vigentes antes de esta
auditoría (`cbdaa32`), solo las 18 de B.113; con las nuevas, 90 verdes. Sirve para no desplegar reglas a
ciegas; el que manda sigue siendo el emulador.
