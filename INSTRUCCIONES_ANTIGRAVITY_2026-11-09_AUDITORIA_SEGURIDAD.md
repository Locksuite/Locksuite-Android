# Instrucciones para Antigravity — 9/11/2026 — Auditoría de seguridad: el PIN, los secretos y el panel (B.113)

Leé primero `LOCKSUITE_CONTEXTO_PARA_IA.md`: sección A (Backend), **B.113 completo** (sobre todo los puntos 2, 3, 4, 7 y 8), B.3, B.3-b, B.7, el párrafo «Cómo se verifica un PIN» de B.104, B.73 y B.110, y la sección C.

## Resumen

- Un amigo del dueño probó la seguridad de LockSuite e hizo un informe de 30 hallazgos. Claude los verificó uno por uno contra el código: **5 se atendieron** (el 2 y el 5 del todo; el 1, el 3 y el 27 en parte) y **25 se dejaron sin tocar a propósito** (ya documentados, no aplican, o los decide el dueño). El criterio del dueño fue explícito: corregir solo lo que seguro es un error o una inseguridad real.
- Lo que sí era un agujero: **cualquier administrador autorizado del panel podía leer `deviceSecrets`** (hash y sal del PIN, `commandSecret`, código de recuperación), **reemplazar el verificador del PIN y ponerse a sí mismo en `trustedAdmins`** (que saltea el PIN). Además la Function no frenaba los intentos de PIN ni miraba si la sesión estaba revocada. Está corregido en reglas + Function + panel. **No se tocó nada de Android.**
- **Nada se desplegó, nada corrió en el emulador real de Firebase y nada se probó en un equipo.** Eso es lo que te toca.
- **Sigue abierto un rodeo** (la causa de fondo de B.3): un administrador puede re-vincular un equipo a una sesión anónima suya y desde ahí reemplazar el verificador del PIN. No se cierra solo con reglas y es decisión del dueño (punto 7, pregunta 7). No lo «arregles» por tu cuenta.

**Regla:** no le digas al dueño que esto está resuelto, y no tachés nada de la sección B, hasta que pasen los puntos 3 y 5 (emulador real y equipo real).

---

## 1. Qué dejó Claude en la carpeta (sin commitear)

Claude no pudo correr git en la carpeta del dueño (el shell de su entorno no la monta). Copió los cambios al árbol de trabajo, partiendo de `cbdaa32` (0.6.81, versionCode 144), y los commiteó en una copia del repositorio en su entorno. En la carpeta quedan **sin commitear**:

- Modificados (16): `LOCKSUITE_CONTEXTO_PARA_IA.md`, `admin-backend/database.rules.json`, `admin-backend/functions/index.js`, `admin-backend/public/{app.js,comun.js,index.html,celular.html,dominios.html,tienda.html}`, `tools/panel_render/{README.md,fake-firebase.js,fake-function.js,test_panel.js,test_ficha.js}`, `tools/rules_tests/{README.md,test_rules.mjs}`.
- Nuevos: `admin-backend/functions/pin_security.js`, `tools/test_pin_security.js`, `tools/test_functions_index.js`, `tools/rules_tests/offline/{shim,register,hooks}.mjs`, este archivo, y en `Claude outputs\`: `COMMIT_MSG_2026-11-09_auditoria_seguridad.txt` y `PARCHE_2026-11-09_auditoria_seguridad.patch`.

`git status` tiene que mostrar solo eso, salvo los dos archivos de `Claude outputs\` (esa carpeta está en `.gitignore`: no aparecen ni se commitean). Si aparece algo más, no es de esta sesión: no lo mezcles.

Para commitear (el mensaje ya está escrito; los archivos de código están con CRLF como el resto de la carpeta):

```
git add LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-11-09_AUDITORIA_SEGURIDAD.md
git add admin-backend/database.rules.json admin-backend/functions/index.js admin-backend/functions/pin_security.js
git add admin-backend/public/app.js admin-backend/public/comun.js admin-backend/public/index.html admin-backend/public/celular.html admin-backend/public/dominios.html admin-backend/public/tienda.html
git add tools/panel_render/README.md tools/panel_render/fake-firebase.js tools/panel_render/fake-function.js tools/panel_render/test_panel.js tools/panel_render/test_ficha.js
git add tools/rules_tests/README.md tools/rules_tests/test_rules.mjs tools/rules_tests/offline
git add tools/test_pin_security.js tools/test_functions_index.js
git commit -F "Claude outputs/COMMIT_MSG_2026-11-09_auditoria_seguridad.txt"
```

- El `.patch` es **solo informativo**: los cambios ya están en la carpeta, no lo apliques encima. Si más adelante algún archivo cambió y querés ver qué hizo Claude, `git apply --check` y `git apply --3way` sirven.
- Hacelo **antes** de `deploy_all.ps1`, que hace `git add .`.

## 2. Pruebas que no necesitan Firebase (corren en la PC)

Desde la raíz del repo, en PowerShell. Tienen que dar todo verde; al 10/11 daban 14, 24 y 90 comprobaciones.

```
node tools/test_pin_security.js
node tools/test_functions_index.js
cd tools/rules_tests
node --import ./offline/register.mjs test_rules.mjs
$env:VALIDATE_ANCESTORS = "1"; node --import ./offline/register.mjs test_rules.mjs; Remove-Item Env:VALIDATE_ANCESTORS
```

- La última línea corre las reglas bajo la otra lectura posible de `.validate`; las dos tienen que dar lo mismo. (En `cmd` no uses `set X=1 && …`: el espacio antes del `&&` queda dentro del valor.)
- Panel con Chromium y base falsa: `bash tools/panel_render/run.sh <carpeta>` (hace falta Git Bash y Playwright; detalle en `tools/panel_render/README.md`). Al 10/11: 287 comprobaciones en 5 tandas. Si en la PC no hay Playwright, este paso es opcional.
- Los seis chequeos de sincronía: `python tools\check_command_sync.py`, `check_diag_sync.py`, `check_panel_commands.py`, `check_profile_sync.py`, `check_store_sync.py`, `check_whitelist_sync.py`.

## 3. Emulador real de las reglas (lo que Claude no pudo hacer)

```
cd tools\rules_tests
npm install
npm test
```

Tiene que terminar con **`TODAS VERDES (90)`** y código 0 (hace falta Java 11+). El evaluador `offline/` lo hizo Claude a mano y **no reemplaza al emulador**: el que manda es el emulador.

- Si una comprobación sale en rojo, no desplegues las reglas. Corregí la regla o la comprobación según lo que diga el emulador, y anotalo en B.113.
- Control negativo (opcional pero útil): con las reglas de antes de la auditoría tiene que dar **18 en rojo**, que es lo que dio en el evaluador sin emulador. En `cmd` (no en PowerShell, que reescribe la codificación): `git show cbdaa32:admin-backend/database.rules.json > tools\rules_tests\viejas.json`, y después, en `tools/rules_tests`, `$env:RULES = "viejas.json"; npm test`. Borrá `viejas.json` al terminar.

## 4. Desplegar solo el servidor

Antes, verificá que **todos los equipos de la flota tengan `devices/<id>/ownerUid`** (la regla nueva de `commandSecret` lo usa; un equipo viejo sin `ownerUid` que todavía no haya publicado su secreto no podría hacerlo):

```
cd admin-backend
firebase database:get /devices -o devices.json
node check_owneruid.js devices.json
```

con este `check_owneruid.js` (guardalo al lado de `devices.json`, no lo agregues al repo; borrá `devices.json` al terminar, tiene datos de la flota):

```js
const d = require("./" + process.argv[2]) || {};
let faltan = 0;
for (const [id, v] of Object.entries(d)) {
  if (!v || !v.ownerUid) {
    faltan++;
    console.log("SIN ownerUid:", id, (v && (v.deviceName || (v.info && v.info.deviceName))) || "");
  }
}
console.log(faltan ? faltan + " equipo(s) sin ownerUid" : "todos los equipos tienen ownerUid (" + Object.keys(d).length + ")");
```

Si falta alguno, el equipo escribe su `ownerUid` en el próximo latido (la regla de `devices` no cambió); esperá a que lo haga o, si no se puede, mirá «Si algo sale mal», más abajo.

Desplegar (**no uses `deploy_all.ps1`**: sube el `versionCode`, arma un APK y hace `git add .`, y esto no toca Android). Desde `admin-backend` en la carpeta del dueño, que es la que tiene el APK vigente, y respetando el orden de la sección A:

```
firebase deploy --only hosting,database
firebase deploy --only functions
```

- Cualquier orden es seguro: la Function vieja ya conoce `VERIFY_PIN` y la nueva sigue escribiendo `trustedAdmins` con el Admin SDK, que no pasa por las reglas.
- Después de desplegar, **recargá con Ctrl+F5 todas las pestañas del panel** que estuvieran abiertas: las viejas intentan leer `deviceSecrets` y escribir `trustedAdmins` y fallan con `PERMISSION_DENIED`.

## 5. Pruebas en equipo real (celular de prueba «Yo», `7073099b55af5fd6`)

Anotá el resultado de cada una. Usá un equipo de prueba, no el de un cliente.

1. **Alta de un equipo nuevo** (instalación limpia): al elegir el PIN, en Firebase Console tienen que aparecer `deviceSecrets/<id>/{pinHash,pinSalt,recoveryCode,ownerUid,commandSecret}` y `devices/<id>/hasPinConfigured: true`.
2. **PIN desde el panel**, con un comando inofensivo: con el PIN correcto funciona; con uno incorrecto dice cuántos intentos quedan; a los **8 fallos** dice «bloqueado, probá en N minutos» (429 `PIN_LOCKED`). Para seguir probando sin esperar, borrá `pinThrottle/<id>` desde la consola.
3. **«Recordar este celular»**: PIN correcto con la casilla marcada, aparece `devices/<id>/trustedAdmins/<tu uid>: true` (lo escribe la Function) y el comando siguiente ya no pide PIN. Revocalo con el botón del panel: vuelve a pedirlo.
4. **Lo que ya no se puede**, desde la consola del navegador con el panel abierto (con un equipo de prueba, no uno de un cliente); las dos tienen que imprimir `DENEGADO permission_denied`:
   ```js
   database.ref("deviceSecrets/<id>").once("value").then(() => console.log("PERMITIDO (mal)"), e => console.log("DENEGADO", e.code))
   database.ref("devices/<id>/trustedAdmins/<tu uid>").set(true).then(() => console.log("PERMITIDO (mal)"), e => console.log("DENEGADO", e.code))
   ```
5. **Regenerar secreto de firma / Re-vincular canal**: borra `deviceSecrets/<id>/commandSecret`; el equipo lo vuelve a publicar en el próximo latido y los comandos vuelven a andar (`commandSecretMismatch` en false). **Esta es la regla con más riesgo de regresión**: si el equipo no logra republicarlo, mirá «Si algo sale mal».
6. **Re-vincular por reclamo**: reinstalá la app en el equipo de prueba (uid anónimo nuevo), aparece el reclamo, «Aprobar», y el equipo vuelve a publicar su verificador.
7. **Archivar y restaurar** un equipo de prueba: al restaurar, `trustedAdmins` **no** vuelve (pide el PIN otra vez); lo demás sí.
8. **Sesión revocada**: deshabilitá un administrador de prueba en Authentication y probá un comando con su pestaña ya abierta: tiene que salir «sesión revocada, volvé a entrar» (401), no un error 500 ni el pedido de PIN.
9. **Varios comandos seguidos** con PIN al mismo equipo (por ejemplo, diez clics rápidos en distintos interruptores): no tiene que aparecer ningún falso 429. El panel hace sus acciones de a una y cada acierto borra el contador; solo una ráfaga de más de 8 comandos **en paralelo** sin «recordar este celular» podría bloquearse.
10. **Sin PIN, como antes**: `UPDATE_LOCKSUITE` a toda la flota, `UPDATE_APP`, `CANCEL_UPDATE_APP` y `COLLECT_DIAGNOSTICS` siguen andando sin pedir PIN.

## 6. Si algo sale mal (marcha atrás)

- **Reglas completas:** `git checkout cbdaa32 -- admin-backend/database.rules.json` y `firebase deploy --only database` (desde `admin-backend`). Eso reabre los agujeros: usalo solo para recuperar el servicio mientras se corrige, y después volvé a la versión nueva con `git checkout HEAD -- admin-backend/database.rules.json`.
- **Solo la regla de `commandSecret`** (si un equipo viejo sin `ownerUid` no logra publicar su secreto): en `deviceSecrets/$device_id/commandSecret/.write`, reemplazar la regla por la anterior:
  `auth != null && (!data.exists() || newData.val() === data.val() || auth.token.admin === true || root.child('authorizedAdminsUids').child(auth.uid).exists())`
- **Function:** `git checkout cbdaa32 -- admin-backend/functions/index.js` y volver a desplegar `functions` (el `pin_security.js` nuevo queda sin usar, no molesta). La versión vieja vuelve a aceptar el `pinHash` suelto de `devices/<id>` y a no frenar intentos. Después, `git checkout HEAD -- admin-backend/functions/index.js`.
- **Panel:** se revierte con `hosting`. Una pestaña vieja con reglas nuevas falla en la verificación del PIN: recargar.

## 7. Decisiones pendientes del dueño (por defecto: no tocar nada)

No las resuelvas sin preguntarle. Detalle en el punto 7 de B.113.

1. **Ventana de sesión de 5 minutos** (`SessionManager.TIMEOUT_MS`): ¿se deja, baja a 60 o 30 s, o también se cierra con «Atrás»? Hoy se cierra al minimizar el Dashboard (`onUserLeaveHint`) y por inactividad. Hay que compilar y probar.
2. **Ver el código de recuperación en el panel.** Hoy se mira en la consola de Firebase. Si lo quiere: un comando de la Function (`GET_RECOVERY_CODE`) que pida el PIN, lea el código con el Admin SDK y lo devuelva, **sin que el cliente lea `deviceSecrets`** y sin escribir el código en `commandLog`.
3. **`globalSettings` y `storeApps` con `.read: true`** → `auth != null`. Probablemente ande con la sesión anónima, pero probalo en un equipo real antes de desplegar: si falla, la flota queda sin configuración.
4. **Knox (`BLOCK_FLASHING`).** Hoy `PolicyManager.setFlashingBlocked` guarda la preferencia y devuelve `true` aunque no bloquee nada (el SDK no está integrado; pasos en el encabezado de `KnoxHardening.kt`). O se integra el SDK, o se avisa en el panel que no hace nada.
5. **`sha256` en `version.json`** publicado por `deploy_all.ps1`. `SelfUpdater` ya lo verifica si está; hoy el script no lo calcula.
6. **Tapjacking** (`filterTouchesWhenObscured`) en las pantallas de PIN: una línea por pantalla, pero hay que compilar y probar con apps que dibujan encima.
7. **Identidad del equipo (causa de fondo de B.3) y el rodeo por re-vinculación.** Esbozo, sin probar: en el alta, el panel genera un código de un solo uso (guardado como hash con vencimiento); el equipo lo canjea en una Function que valida, lo borra y devuelve `createCustomToken("dev_" + deviceId, { deviceId })`; la app entra con `signInWithCustomToken`; las reglas comparan `auth.token.deviceId === $device_id` en vez de `ownerUid`; re-vincular = pedirle a la Function un código nuevo. Alternativa o complemento: App Check. Cualquiera de las dos necesita migración para los equipos ya instalados.
8. **Fondo de pantalla.** Sigue bloqueado por defecto a propósito (G-4 en B.44; B.45 lo revisó). Se puede permitir equipo por equipo desde el panel («Restricciones Adicionales» → «Bloquear cambio de fondo de pantalla»).

## 8. Lo que quedó sin hacer en Android (para la próxima compilación)

Claude no tocó ningún archivo de Android. Quedan dos comentarios desactualizados, sin efecto en el comportamiento:

- `security/PinManager.kt`, `getOrCreateRecoveryCode`: dice que el administrador puede consultar el código desde el panel web.
- `util/FirebaseDeviceSync.kt`, `syncRecoveryCode`: dice «solo lectura para admins autorizados».

Con las reglas nuevas ningún cliente lee `deviceSecrets`. Cuando compiles por otro motivo, corregí los dos textos.

## 9. Cierre

1. Emulador real (punto 3), despliegue (punto 4) y pruebas en equipo (punto 5).
2. Actualizar B.113 y la sección C. **Tachar solo lo confirmado en un equipo real.** Hoy no hay nada tachado.
3. Commit con un mensaje que diga qué se probó y en qué equipos.
4. Preguntarle al dueño las decisiones del punto 7 (las que no respondió quedan como están).
