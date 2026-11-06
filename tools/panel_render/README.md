# tools/panel_render — el panel web probado SIN red y SIN Firebase

**(26/10/2026, B.95.)** Arma una copia de `admin-backend/public/` con un Firebase
**falso, en memoria** (`fake-firebase.js`) y una Cloud Function falsa
(`fake-function.js`, que escribe en la base falsa lo mismo que `sendCommandV8`: el ack
`sent` y la copia del buzón), la abre en Chromium con Playwright y **ejercita el canal
de comandos como lo usa el administrador**: manda comandos, simula que el celular los
aplica (o los rechaza, o no contesta) y mira qué dice el panel.

Es la receta de renderizado de B.65, convertida en prueba que se puede repetir.

## Cómo se corre

```bash
bash tools/panel_render/run.sh
```

Necesita `node` y el paquete `playwright` con su Chromium (en el contenedor de las
sesiones de IA ya están; en una PC: `npm i -g playwright && npx playwright install chromium`).
No necesita red ni credenciales: nunca toca el Firebase de verdad.

## Qué prueba

`test_panel.js` (barra lateral, `app.js`):

- El badge «Sin FCM» dice el motivo (`fcmTokenError`).
- La tarjeta «📡 Canal de comandos» aparece en un equipo sin FCM y se calla en uno sano.
- Un interruptor con un comando en el buzón muestra lo PEDIDO con «⏳ pendiente», y no
  "vuelve solo" al valor viejo.
- **A** — FCM salió (Function vieja, `queued:true` sin `fcmError`): el panel ESPERA el
  ack y muestra «✓ aplicado». *Con el app.js de B.94 esto decía "encolado, hasta 15 min"
  y deshacía el interruptor: es la regresión que motivó B.95.*
- **B** — equipo sin FCM ni escucha en vivo: avisa que tarda hasta 15 min, NO deshace, y
  cuando el celular lo aplica minutos después, lo dice.
- **C** — equipo sin FCM que escucha en vivo (`mailboxLive`): espera y muestra el ✓.
- **D** — un rechazo real (`BAD_SIGNATURE`) sí deshace el interruptor.
- **E** — FCM salió pero el celular no contesta en 10 s: pendiente, sin deshacer, y el
  ack tardío se muestra.
- **F** — autoactualización a un equipo sin FCM: no promete "se está actualizando".

`test_ficha.js` (ficha completa, `comun.js` + `celular.js`): el contrato de
`LS.sendCommand` (`applied` / `pendiente` / falla) y el «Canal de comandos» del Resumen.
**(26/10, B.98)** Además, la ficha rearmada como la usa el administrador:

- Cada sección del riel se abre; «Terminar alta» manda `FINISH_ENROLLMENT`.
- **Apps del celular:** la lista sale de lo que reporta el equipo (las del sistema y
  críticas aparte, sin controles); «Ocultar» manda `HIDE_APP` y el botón queda en ⏳
  hasta que el celular reporta la app oculta; «Más» (internet, imágenes, navegador
  interno, actualizar); Play Store (`UNSUSPEND_APP`, `SET_HIDE_SUSPENDED_APPS`); la lista
  de permitidas precargada y `UPDATE_ALLOWLIST`.
- **Actualizaciones (B.97):** el flujo en vivo con camino, etapa, barra y el porqué; el
  paso a paso que se agrega en vivo; Cancelar → `CANCEL_UPDATE_APP`; el último resultado
  con versión de → a; «Solo Google Play» viaja como `route=play`, «Automático» NO manda
  `route` (comando idéntico al de siempre) y «Actualizar desde la Tienda» manda
  `route=hosted`.
- Filtro: el detector de navegadores embebidos; Diagnóstico: campos, comandos recientes y
  JSON, **sin el token de FCM entero**; Acciones: «Olvidar la confianza del PIN»;
  Archivar.
- **A 390 px de ancho ninguna sección se desborda de costado** (antes el riel crecía a
  860-1274 px en un teléfono).
- Un equipo con LockSuite anterior a B.97 avisa que actualiza solo por Google Play.

`test_tienda.js` **(29/10/2026, B.101)** — la **Tienda Play**: la página global
(`tienda.html` + `tienda_logic.js` + `tienda.js`) y la sección del celular en la ficha
(`celular.html` + `celular.js`), más la pestaña 🔔 Pedidos de `index.html`. Escenarios:

- **A** — primera vez (nada guardado): la lista nace de la Tienda vieja (`storeApps`; solo
  «habilitada» lo que ya estaba en `allowedPackages`, el resto «a pedido»), se verifica
  contra Google Play (función falsa), los estados Habilitada / A pedido / Bloqueada /
  Eliminar, Detalles (nombre, nivel, acceso, dominios), Guardar escribe
  `globalSettings/playStore` y «Enviar a todos» manda `SYNC_PLAY_STORE` (y se niega a
  mandar antes de guardar).
- **B** — lista ya guardada + «↩ Traer de la tienda vieja» (agrega solo lo que falta).
- **C** — la función de Play caída: la lista sigue sirviendo, nunca se marca nada como
  «no aparece» por un error.
- **D** — lista vacía + «Agregar una app» por nombre de paquete (valida y consulta Play).
- **E** — a 390 px de ancho no se desborda nada de costado.
- **F** — la sección **Tienda Play** de la ficha: modo (`SET_PLAY_STORE_MODE`), qué ve el
  usuario en ese modo, paquetes extra del equipo, pedidos «a pedido» (Aprobar / Rechazar),
  y los niveles automáticos 5 (Solo llamadas) y 6 (Lite).
- **G** — 🔔 Pedidos de `index.html`: un pedido de la Tienda Play se aprueba habilitándolo
  SOLO en ese equipo (`devices/<id>/playStoreExtras`), nunca en `allowedPackages` (que
  abriría la app a toda la flota); un pedido de la tienda vieja sigue igual que siempre.

**(1/11/2026, B.104)** — un celular cuyo PIN la nube no tiene (`DEVICE_PIN_NOT_ENROLLED`, el
caso de «Shuli»). `fake-function.js` imita la puerta del PIN de la Function real con dos
modos nuevos: `pin_no_publicado` (412 `DEVICE_PIN_NOT_ENROLLED` a todo salvo
`UPDATE_LOCKSUITE`/`UPDATE_APP`/`CANCEL_UPDATE_APP`) y `pin_requerido` (el verificador
existe, PIN «1234»: 412 `PIN_REQUIRED` sin PIN, 403 `PIN_INCORRECT` con otro).

- `test_ficha.js`: la ficha dice que falta el PIN en la NUBE (no «el celular no configuró un
  PIN»), muestra el `pinPublishError` que anotó el celular y se redibuja al cambiar; el
  comando vuelve `pin_no_publicado` con texto claro, **sin pedir un PIN** y con **un solo
  pedido**; el botón muestra el aviso claro y no el código crudo; `UPDATE_LOCKSUITE` sigue
  pasando; y dos controles: con el PIN en la nube se sigue pidiendo una vez, y un PIN
  equivocado sigue siendo `PIN_INCORRECT`.
- `test_panel.js`: con el celular sin bandera de PIN (o con la bandera en `true` pero sin
  verificador) la ficha lateral **se abre** con el aviso, en vez de un `alert` que la cerraba;
  un comando devuelve el mensaje claro; la actualización de LockSuite sale.
- ⚠️ `openDeviceSidebar` espera a que alguien escriba el PIN: en un test hay que llamarla SIN
  `return` dentro de `page.evaluate`, o la prueba se cuelga esperando su promesa.

`test_diag.js` **(6/11/2026, B.110)** — el **Registro de errores** de la ficha (tarjeta
`#c-diag-remoto` de `celular.html`: `diag_logic.js` + `diag_remoto.js`). Escenarios:

- **A** — suscripción perezosa: mientras la sección «Diagnóstico» no está abierta NO hay
  ningún listener sobre `deviceDiag/<id>` (ni eventos, ni diagnósticos, ni `meta`); se
  engancha al abrirla y se suelta al irse. Los límites son `limitToLast(400)` y `(3)`.
- **B** — el estado del celular (versión del registro, interruptor, error de subida,
  última subida) y la lista de renglones: más nuevo arriba, nivel, etiqueta, hora, `×N`.
- **C** — texto hostil que viene del celular (`<img onerror>`, `<script>`, comillas): se ve
  como texto, **nada se ejecuta** (todo pasa por `LS.esc` / `textContent`).
- **D / D2** — filtros (nivel, ventana, etiqueta, texto); volver a tocar el filtro que ya
  está elegido NO redibuja la lista, y un renglón con el `<details>` abierto sigue abierto
  cuando llega un renglón nuevo.
- **E** — el visor del diagnóstico completo (Sistema, Cierres, logcat, Apps) y el selector
  de los últimos 3.
- **F** — «Pedir diagnóstico ahora» manda `COLLECT_DIAGNOSTICS` **sin PIN** (con los
  paquetes normalizados, máximo 6, avisando los inválidos); el interruptor manda
  `SET_DIAGNOSTICS_UPLOAD` `{enabled}` **con** PIN y pide confirmación para apagarlo.
- **G** — los demás estados: celular sin registro todavía, interruptor apagado, error de
  subida, y `permission_denied` (las reglas todavía no desplegadas) con su aviso claro.
- **H** — el informe para pegarle a Claude (`DiagLogic.buildReport`): encabezado, lo que contó
  el usuario, renglones del más viejo al más nuevo, secciones del diagnóstico y la leyenda
  «Cómo leer esto» solo con las etiquetas usadas.
- **I** — a 390 px de ancho la tarjeta no se desborda de costado.
- **J** — ningún error en la consola del navegador en todo el recorrido.

`fake-firebase.js` acepta `window.__denyPaths` (rutas para las que `on()` llama al callback de
error con `PERMISSION_DENIED`) y `fake-function.js` trae `COLLECT_DIAGNOSTICS` en `EXENTOS`
(el comando que la Function real no le pide PIN). 61 verificaciones, deben terminar en «TODO VERDE».

`test_diag_borrado.js` **(6/11/2026, B.110)** — `index.html` + `app.js`: «Eliminar dispositivo»
(barra lateral) y «Eliminar definitivamente» (de los archivados) borran TAMBIÉN `deviceDiag/<id>` (los
avisos prometen borrar «todos los datos»: sin esto el registro quedaría huérfano), no tocan el de los
demás equipos, y «Archivar» lo CONSERVA (los datos archivados se pueden restaurar). Se acepta cada
`confirm` desde la prueba. `tools/check_diag_sync.py` vigila lo mismo de forma estática.

## Control negativo

Copiar el `app.js` viejo (`git show <commit>:admin-backend/public/app.js`) a la carpeta
armada y volver a correr `node test_panel.js`: con el de B.94 (`e2b22f2`) fallan 18 de 20
(las dos que pasan son las que no dependen del cambio: la tarjeta que se calla y la
ausencia de errores de consola). Si una
prueba nueva no falla contra el código viejo, la prueba no está mirando lo que cree.

## Límites

El Firebase falso implementa solo lo que el panel usa (`ref/on/off/once/set/update/
remove/push`, `onAuthStateChanged`), notifica a los listeners por prefijo de ruta y
resuelve `ServerValue.TIMESTAMP`. No aplica reglas de seguridad: eso lo prueba
`tools/rules_tests/` contra el emulador real.
