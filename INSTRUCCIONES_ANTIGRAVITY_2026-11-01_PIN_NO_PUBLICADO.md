# Instrucciones para Antigravity — 1/11/2026

## B.104 — «Celular nuevo: el panel dice que no tiene PIN de administrador (`DEVICE_PIN_NOT_ENROLLED`) y hay que cambiarlo a mano»

Escrito por Claude desde un contenedor **sin Gradle, sin SDK de Android y sin ningún celular ni
base de datos de verdad**. Está **commiteado en el clon de la sesión (un solo commit sobre
`a3b2dd4`), pero NO está compilado con Gradle, NO está desplegado y NO se probó en ningún
celular.** Lo que sigue es exactamente lo que falta, en orden. El porqué completo está en
`LOCKSUITE_CONTEXTO_PARA_IA.md`, punto **B.104**.

---

## 0. En una línea

El panel no conoce el PIN de ningún celular: la Cloud Function lo compara contra
`deviceSecrets/<id>/{pinHash,pinSalt}`, que **escribe el celular**. Hasta ahora lo escribía **una
sola vez**, sin esperar la confirmación del servidor y sin reintentar nunca; si esa única
escritura caía mal (celular nuevo sin internet o sin sesión, varias sesiones anónimas a la vez
con uids distintos, «Eliminar del panel», reinstalación) la nube quedaba sin verificador y el
panel contestaba `DEVICE_PIN_NOT_ENROLLED` hasta que alguien volvía a elegir el PIN a mano. Ahora el
celular **publica en tres pasos confirmados, lo reintenta solo y vuelve a publicar si la nube lo
pierde**, la sesión anónima se abre una sola vez por arranque, y el panel explica el problema en
lugar de pedir un PIN inútil y cerrar la ficha lateral.

**Lo más riesgoso de esta tanda, en orden:** (1) `FirebaseDeviceSync.kt` nunca compiló contra el
Firebase real (el chequeo de tipos usa firmas escritas a mano de `Task`, `DatabaseReference.get()`,
`DataSnapshot.value`: §2); (2) `withAuth` ahora pasa por `SignInGate`, o sea que **toda** la
sincronización con Firebase depende de él (§4, prueba 0); (3) la causa exacta en el Pixel del dueño
**no está confirmada** (§5).

**No cambia:** la Cloud Function, las reglas de la base (`database.rules.json`), el protocolo de
comandos ni el formato de `deviceSecrets`. **No hace falta `firebase deploy --only functions` ni
`--only database`**: alcanza con APK + hosting (`deploy_all.ps1`).

---

## 1. Aplicar — ANTES de `deploy_all.ps1`

`deploy_all.ps1` hace `git add .`: si se corre primero, todo esto queda mezclado en el commit
automático de versión. Aplicar el parche antes.

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git status                  # en main y sin cambios
git log -1 --oneline        # a3b2dd4 feat(panel): modo claro/oscuro M3 y 5 modos de visualizacion … (B.103)
git am --3way "Claude outputs\2026-11-01_pin_no_publicado.patch"
git show --stat HEAD
```

* El parche es **un solo commit** (fin de línea LF; los avisos «LF will be replaced by CRLF» son
  normales con `core.autocrlf`). **No se escribieron archivos sueltos en el disco de la PC a
  propósito.** **No se tocó `versionCode` ni `versionName`**: los sube `deploy_all.ps1`.
* Se hizo sobre `a3b2dd4`. Si en la PC hay commits nuevos, `--3way` suele alcanzar. Si hay
  conflicto, los candidatos son `LOCKSUITE_CONTEXTO_PARA_IA.md` (quedarse con las dos partes; la
  sección C tiene que ser la de esta sesión), `admin-backend/public/app.js`, `comun.js`,
  `celular.js` y `util/FirebaseDeviceSync.kt`.
* **Plan B** si `git am` no entra: `git am --abort`, descomprimir
  `Claude outputs\2026-11-01_pin_no_publicado_archivos.zip` en la raíz del proyecto (trae los
  mismos archivos completos, más `LISTA_DE_ARCHIVOS.txt`), `git add` **solo** esos archivos y
  `git commit -F "Claude outputs\2026-11-01_mensaje_commit_pin.txt"`.

---

## 2. Compilar

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

* Archivos nuevos: `mdm/PinCloudSyncPolicy.kt`, `util/SignInGate.kt` (Kotlin puro, sin Android).
  Tocados: `security/PinManager.kt`, `util/Constants.kt`, `util/FirebaseDeviceSync.kt`.
* **Qué puede fallar con el Firebase real y no con mis stubs** (todo en `FirebaseDeviceSync.kt`):
  `FirebaseDatabase.getInstance().getReference(...).get()` devuelve `Task<DataSnapshot>`;
  `task.result?.value == true` (propiedad `value` = `getValue()`); `addOnCompleteListener { task -> … }`
  con `return@addOnCompleteListener`; las cadenas `.addOnFailureListener { e -> … }.addOnSuccessListener { … }`
  sobre `Task<Void>` (mismo patrón que ya usaba el archivo); `return` desde adentro de
  `synchronized(pinPublishLock) { … }` (función `inline`: es un retorno no local, válido).
  Si algo no cierra, arreglar la **firma**, no la lógica.
* **Pruebas unitarias.** Pasan en mi chequeo: `PinCloudSyncPolicyTest` (18) y `SignInGateTest` (13);
  el repo tiene ahora **224 `@Test`** (193 antes + 31). Gradle dijo «102/102» en la tanda anterior,
  que **no coincide con 193**: por favor anotar el conteo real sumando `tests="…"` de
  `app/build/test-results/testDebugUnitTest/*.xml` y decir si faltan clases (¿alguna no corre?). No
  es de esta tanda, pero conviene aclararlo ahora.

---

## 3. Desplegar

`deploy_all.ps1` como siempre (compila el APK release, sube la versión, hosting y GitHub). Hosting
incluye los cambios del panel: `comun.js?v=6`, `celular.js?v=9`, `app.js?v=44` (los `?v=` ya están
subidos en los HTML; no hace falta tocarlos). Después de desplegar, abrir el panel con
**Ctrl+F5** una vez.

**No desplegar Functions ni reglas**: no cambiaron.

---

## 4. Pruebas en equipo real (lo único que de verdad confirma el arreglo)

Para mirar el celular: `adb logcat -s FirebaseDeviceSync` (tag `FirebaseDeviceSync`). Para mirar la
nube: Firebase Console → Realtime Database → `deviceSecrets/<id>` y `devices/<id>`.

**0. Nada se rompió en la sincronización (`SignInGate`).** Instalar la versión nueva en un celular
que ya anda (o actualizarlo): en un minuto tiene que aparecer «En línea» en el panel, sus campos
(`model`, `versionName`, apps) tienen que actualizarse, y los comandos y sus acks tienen que
seguir funcionando. Si el celular **no sincroniza más**, sospechar de `SignInGate` primero
(`util/SignInGate.kt`, usado por `withAuth`): es lo único que toca a todos los pedidos.

**1. Celular NUEVO (la prueba del dueño).** Dar de alta un equipo de cero (o restablecer uno de
fábrica y provisionarlo), elegir el PIN durante la configuración y **sin tocar nada más**:
* En ≤ 2 min, en el panel, la ficha del celular → Acciones tiene que decir
  «🔒 Las acciones críticas piden el PIN de este celular» (no «⚠️ La nube todavía no tiene el PIN…»),
  y un comando que pide PIN (p. ej. «Bloquear la pantalla ahora») tiene que aceptar ese PIN **sin
  que nadie lo cambie a mano**.
* En la base: `deviceSecrets/<id>` con `pinHash`, `pinSalt`, `recoveryCode`, `commandSecret`,
  `ownerUid`; `devices/<id>/hasPinConfigured = true`; **sin** `pinPublishError`.
* Firebase Console → Authentication → Users: el alta de UN equipo tiene que sumar **un** usuario
  anónimo, no 3 o 5 (si suma varios, `SignInGate` no está funcionando).

**2. PIN elegido SIN internet.** Con Wi-Fi y datos apagados, elegir el PIN; después prender la red.
En el logcat deberían verse líneas «PIN todavía sin publicar (…); se reintenta solo» y, al volver
la red, la publicación; en ≤ 2 min el panel lo detecta. (Si el panel tiene visible la ficha, aparece
y desaparece el aviso «⚠️ La nube todavía no tiene el PIN…», con el último error entre paréntesis
mientras falló.)

**3. «Eliminar del panel» y volver a verlo.** Con un celular que ya tiene el PIN publicado:
ficha → «🗑️ Eliminar del panel». El celular reaparece solo en el siguiente latido; **en ≤ ~7 min**
(5 min del chequeo + el latido) vuelve a tener `hasPinConfigured = true` y `deviceSecrets/<id>`
con el verificador. Logcat: «La nube no tiene registrado el PIN de este equipo (¿lo eliminaron del
panel?): se vuelve a publicar.»

**4. Celular viejo en la calle («Shuli», 0.6.69).** Hoy el panel le dice `DEVICE_PIN_NOT_ENROLLED`.
Desde la ficha → «Actualizar LockSuite (OTA)» (**no pide PIN**: está exceptuado en la Function).
Tras actualizarse y arrancar, tiene que publicar su PIN solo. El aviso del panel ya lo recomienda.

**5. Panel principal (`index.html`).** Un celular sin `hasPinConfigured` (o con la bandera pero sin
verificador) **abre** la ficha lateral con el aviso «⚠️ Este celular todavía no le avisó a la nube
su PIN…»; antes aparecía un `alert` y se cerraba.

**6. Cambios de PIN (que no haya regresión ni ida y vuelta).**
* Cambiar el PIN **desde el celular** (Dashboard): el panel acepta el PIN nuevo; el viejo da
  `PIN_INCORRECT`.
* Cambiarlo **desde el panel** (`CHANGE_PIN`): el celular lo adopta; en el logcat se ve **una**
  republicación de los mismos valores (idempotente) y después silencio. Si se ve una publicación
  cada pocos segundos para siempre, hay un bucle: avisar con el logcat.

**7. Reinstalar LockSuite en un celular con PIN.** Debe pedir la re-vinculación (como siempre); tras
«Aprobar» en el panel, en ≤ 2 min el PIN nuevo queda publicado (antes: se quedaba el verificador del
PIN viejo).

---

## 5. Si todavía falla (cómo diagnosticar)

La causa exacta en el Pixel del dueño **no se pudo confirmar** (no había base ni logcat a mano); el
arreglo está pensado para curar cualquiera de estas cinco, pero si vuelve a pasar:
1. `devices/<id>/pinPublishError` (y `info/pinPublishError`): el motivo del último intento fallido
   (`verificador: …`, `bandera pública: …`, `nodo del equipo: …`, `sesión anónima`). La ficha del
   panel lo muestra.
2. Logcat, tag `FirebaseDeviceSync`: «PIN todavía sin publicar (…)», «Equipo sin permiso sobre su
   nodo: pedido de re-vinculación enviado al panel.»
3. `deviceClaims/<id>/<uid>`: ¿hay un pedido de re-vinculación sin aprobar? Mientras esté sin
   aprobar, el celular **no puede** escribir su nodo ni su PIN (es el comportamiento de B.73).
4. `devices/<id>/ownerUid` vs. el uid anónimo actual del celular.

---

## 6. Qué NO simplificar

* La bandera pública `hasPinConfigured` se escribe **después** de la confirmación del verificador
  (si no, el panel vuelve a decir «tiene PIN» cuando la Function va a contestar que no).
* `commandSecret` sigue en su **propia** escritura (`pushCommandSecret`), nunca dentro de la del PIN:
  un `updateChildren` es atómico y la regla de `commandSecret` rechaza el cambio una vez que el valor
  existe (comentario del 2/9/2026).
* La escritura 0 (`devices/<id>/ownerUid`) va antes de `deviceSecrets`: las reglas solo dejan escribir
  los secretos al dueño del nodo público (o si el nodo todavía no existe).
* `withAuth` pasa por `SignInGate`: volver a «cada uno abre su sesión» reintroduce la carrera de uids.
* `needsPublish` compara contra lo **confirmado por el servidor**, no contra «lo intenté».
* Sin PIN local, nunca se publica (publicar vacío borraría un verificador bueno).
* El panel **no pide PIN** ante `DEVICE_PIN_NOT_ENROLLED` (no hay contra qué compararlo); sí lo sigue
  pidiendo ante `PIN_REQUIRED`.

---

## 7. Al terminar

* Si todo anduvo en equipo real: **recién entonces** tachar B.104 en `LOCKSUITE_CONTEXTO_PARA_IA.md`
  (con la fecha y qué equipo se usó) y reemplazar la sección C con la sesión de ustedes.
* Anotar en el contexto el conteo real de pruebas de Gradle (§2) y cualquier firma de Firebase que
  haya habido que corregir.
* Commit con mensaje que diga qué se cambió y por qué.

---

## 8. Cómo se verificó en el contenedor (para que sepan qué esperar)

`tools/ia_contenedor/typecheck/typecheck.sh` → 0 errores en 89 archivos; `run_unit_tests.sh` → 204
pruebas OK; 12 mutaciones de control sobre las dos clases nuevas, todas detectadas;
`tools/panel_render/run.sh` completo en verde (196 verificaciones) y, contra el panel anterior,
fallan las 6 + 7 verificaciones nuevas (control negativo); los 5 `tools/check_*.py`,
`gen_catalog_js.py --check`, `gen_policies_js.py --check`, `node tools/test_tienda_logic.js` (48),
`node tools/test_play_lookup.js` (16) y `node --check` de todo el JS. Detalle en
`LOCKSUITE_CONTEXTO_PARA_IA.md`, B.104.
