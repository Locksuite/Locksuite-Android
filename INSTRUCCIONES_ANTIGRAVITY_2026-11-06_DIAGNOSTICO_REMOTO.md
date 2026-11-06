# Instrucciones para Antigravity — 6/11/2026

## B.110 — «Registro de errores remoto: ver desde el panel el error real de un celular (como tenerlo conectado por USB), sin molestar, sin batería y sin que se vea nada»

Escrito por Claude desde un contenedor **sin Gradle, sin emulador de Firebase, sin red y sin ningún
celular**. Está **commiteado en el clon de la sesión (un solo commit sobre `4c35f8e`, versión 0.6.77 /
código 140), NO en la carpeta del dueño**: a las 23:42 el dueño pidió que no se tocara el código porque
Antigravity estaba trabajando en otra cosa, y que todo se entregara en un zip. **NO está compilado con
Gradle, NO está desplegado y NO se probó en ningún celular.** Lo que sigue es exactamente lo que falta,
en orden. El porqué completo está en `LOCKSUITE_CONTEXTO_PARA_IA.md`, punto **B.110**.

---

## 0. En una línea

Cuando un usuario dice «no me abre…» o «se me cortó…», el dueño no veía el error. Ahora cada celular
lleva su propio registro técnico (errores, avisos, caídas, cierres del proceso) y lo sube **enganchado
en el latido que ya hace** (sin temporizadores, alarmas, wakelocks, permisos ni interfaz), a un nodo
aparte de Firebase (`deviceDiag/<id>`). En la ficha del celular → **Diagnóstico → Registro de errores**
el dueño ve la lista, puede **pedir un diagnóstico completo** (logcat propio, sistema, protecciones,
estado de las apps de la queja) y **copiar un informe** listo para pegárselo a Claude.

**Lo más riesgoso de esta tanda, en orden:** (1) `diag/*.kt` y los ganchos nunca compilaron contra el
Firebase real (el chequeo de tipos usa firmas escritas a mano: §2); (2) **las 11 comprobaciones nuevas de
las reglas jamás corrieron en el emulador** (§3); (3) **nada se probó en un equipo real**: Arranque
Directo, batería, logcat por fabricante, `System.err`, `ApplicationExitInfo` (§5); (4) la privacidad
del registro (`DiagSanitizer`) se verificó con textos inventados, no con renglones reales (§5, prueba 10).

---

## 1. Aplicar — ANTES de `deploy_all.ps1`

`deploy_all.ps1` hace `git add .`: si se corre primero, todo esto queda mezclado en el commit
automático de versión. Aplicar el parche antes.

El zip que dejó Claude trae: `parche\` (el `.patch` y el mensaje de commit), `archivos\` (los archivos completos, por
si `git am` no entra), `documentos\` y `registros_verificacion\`. `<carpeta del zip>` es donde lo descomprimas.

**Ojo: esto se hizo para no pisar un trabajo en paralelo.** Antes de aplicar, mirar el estado:

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git status                  # ¿en main y sin cambios sin commitear?
git log -3 --oneline        # el parche está hecho sobre 4c35f8e (0.6.77 / código 140)
```

* **Si el árbol está limpio** (lo normal cuando el otro trabajo ya se commiteó):

  ```powershell
  git am --3way "<carpeta del zip>\parche\2026-11-06_diagnostico_remoto.patch"
  git show --stat HEAD
  ```

* **Si todavía hay cambios sin commitear de otro trabajo** (no pisarlos): trabajar en una copia aparte
  y unir después.

  ```powershell
  git worktree add "..\LockSuite-diag" -b diag-remoto HEAD
  cd "..\LockSuite-diag"
  git am --3way "<carpeta del zip>\parche\2026-11-06_diagnostico_remoto.patch"
  # copiar a esta carpeta lo que git no versiona y hace falta para compilar:
  #   local.properties  y  app\google-services.json  (si están ignorados)
  ```

  Compilar y probar ahí (§2–§3). Cuando el otro trabajo esté commiteado en `main`:
  `git merge diag-remoto` (o `git cherry-pick <commit>`).

* El parche es **un solo commit** (fin de línea LF; los avisos «LF will be replaced by CRLF» son
  normales con `core.autocrlf`). **No se tocó `versionCode` ni `versionName`**: los sube `deploy_all.ps1`.
* **Conflictos probables** (por ser archivos que otros también tocan):
  `LOCKSUITE_CONTEXTO_PARA_IA.md` (quedarse con las dos partes; la sección C tiene que reflejar ambos
  trabajos), `admin-backend/functions/index.js` (son 3 hunks chicos), `admin-backend/database.rules.json`
  (un bloque nuevo), `admin-backend/public/celular.html` (una tarjeta y dos `<script>`),
  `admin-backend/public/app.js` + `index.html` (2 líneas y el `?v=`),
  `util/FirebaseDeviceSync.kt`, `service/CommandProcessor.kt`, `LockSuiteApplication.kt`.
  Todo lo demás del panel son **archivos nuevos** (`diag_logic.js`, `diag_remoto.js`) que no pueden
  chocar: por eso no se tocó `celular.js`, `comun.js` ni `panel2.css`. Lo único de lo existente que se
  tocó en el panel: **2 líneas en `app.js`** (el borrado del registro al eliminar un equipo) y el
  `?v=45` → `?v=46` de `app.js` en `index.html` (si vos también subiste ese número, quedate con el mayor).
* **Cómo resolver los conflictos (se probó con un trabajo en paralelo simulado):** casi siempre son de **texto**,
  no de lógica. En `functions/index.js` el choque típico es el final de `ALLOWED_COMMANDS` (todos agregan comandos
  ahí): quedarse con **los dos** (nuestro bloque es el comentario `DIAGNÓSTICO REMOTO` + `"COLLECT_DIAGNOSTICS",
  "SET_DIAGNOSTICS_UPLOAD"`). En `LOCKSUITE_CONTEXTO_PARA_IA.md` quedarse con las dos entradas de B y escribir
  **una sola sección C** que cuente ambos trabajos. En `index.html` el `?v=` de `app.js`: quedarse con el mayor.
  Después de resolver: `git add` de esos archivos y `git am --continue`, y correr de nuevo
  `python3 tools/check_command_sync.py`, `check_panel_commands.py` y `check_diag_sync.py` (si falla uno por un
  comando que NO es nuestro, es de la otra tanda: falta su rama en `CommandProcessor.kt`).
* **Si «B.110» ya lo usaste en otro punto del documento de contexto:** no renumeres el código (los
  comentarios `(B.110)` de `diag/*.kt` y del panel apuntan a este punto). Dejá esta entrada como
  **«B.110-b»** en el documento y avisalo en la sección C.
* **Plan B** si `git am` no entra: `git am --abort`; en el zip están los archivos completos en
  `archivos\` (con la misma ruta que en el repo). Los **archivos nuevos** se copian tal cual
  (`app\src\main\java\com\ejemplo\locksuite\diag\*`, `app\src\test\...\diag\*`,
  `admin-backend\public\diag_*.js`, `tools\check_diag_sync.py`, `tools\test_diag_logic.js`,
  `tools\panel_render\test_diag.js`, este documento). Los **archivos modificados** NO se pisan con la
  copia completa si alguien más los tocó: aplicar solo los hunks (`git apply --3way` con el `.patch`,
  o a mano mirando `git show HEAD`). Después `git add` **solo** esos archivos y
  `git commit -F "<carpeta del zip>\parche\2026-11-06_mensaje_commit_diagnostico.txt"`.

---

## 2. Compilar

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug          # si el proyecto lo usa (ver tools\ia_contenedor\README.md)
```

* **Archivos nuevos** (`app/src/main/java/com/ejemplo/locksuite/diag/`, 13): Kotlin puro (sin Android) =
  `DiagEvent`, `DiagSanitizer`, `DiagRing`, `DiagRateLimiter`, `DiagUploadPolicy`, `DiagExits`,
  `DiagLogcatParser`, `StackTraceGrouper`; con Android/Firebase = `DiagStore`, `DiagLog`, `DiagErrTee`,
  `DiagCollector`, `DiagUploader`. **Tocados** (solo líneas agregadas, ver `git show HEAD`):
  `LockSuiteApplication`, `util/FirebaseDeviceSync`, `service/CommandProcessor`, `service/KosherVpnService`,
  `service/LockSuiteAccessibilityService`, `service/WatchdogForegroundService`, `receiver/BootReceiver`.
* **Qué puede fallar con el Firebase real y no con mis stubs** (casi todo en `DiagUploader.kt`):
  `FirebaseDatabase.getInstance().getReference(path)`; `ref.updateChildren(Map<String, Any?>)` con valores
  `null` (para borrar claves) y `ServerValue.TIMESTAMP`; `.addOnSuccessListener { … }.addOnFailureListener { e -> … }`
  sobre `Task<Void>`; `DiagUploader` también usa `FirebaseDeviceSync.runAuthenticated { … }`,
  `FirebaseDeviceSync.deviceId(ctx)` y `FirebaseDeviceSync.reportFields(ctx, mapOf(… to null))` (existían
  antes: lo que se agregó en `FirebaseDeviceSync.kt` son 11 líneas, ver `git show HEAD`). Si algo no cierra,
  arreglar la **firma**, no la lógica.
* **Lint de Android:** los llamados que existen desde Android 11 (`ApplicationExitInfo`) van en funciones
  con `@RequiresApi(Build.VERSION_CODES.R)`, pero el lint de verdad no se corrió. Si marca `NewApi`, es
  una anotación o una guarda de versión que falta.
* **Pruebas unitarias.** Pasan en mi chequeo: **277** (210 que ya había + **67 nuevas** en 6 suites:
  `DiagEventTest` 6, `DiagSanitizerTest` 15, `DiagRingAndLimiterTest` 10, `DiagUploadPolicyTest` 16,
  `DiagExitsAndLogcatTest` 12, `StackTraceGrouperTest` 8). Gradle puede dar otro número total (la
  tanda anterior aclaró que el conteo de `@Test` y el de Gradle no coinciden): anotar el real sumando
  `tests="…"` de `app/build/test-results/testDebugUnitTest/*.xml` y decir si falta alguna clase.

---

## 3. Reglas de la base — con el emulador, ANTES de desplegar

```powershell
cd tools\rules_tests
npm install
npm test            # esperado: TODAS VERDES (55)
```

* Son **55** comprobaciones: 44 de antes + **11 nuevas** (las que empiezan con `diag:`). Se escribieron
  sin emulador: **nunca corrieron**. Si alguna sale `✗ ROJO`, el que manda es el emulador: corregí la regla
  (`deviceDiag` en `admin-backend/database.rules.json`) o la comprobación, no al revés, y dejalo anotado.
* **Control negativo** que conviene hacer una vez: borrar temporalmente el bloque `deviceDiag` de las
  reglas y correr de nuevo; deberían ponerse rojas las 5 comprobaciones que esperan «PERMITIDO»
  (el celular sube / borra / sube un diagnóstico; el panel lee / vacía). Si no, esas comprobaciones no
  están mirando lo que creen. Restaurar el bloque.
* **Ojo: desplegar `database.rules.json` publica el archivo ENTERO.** Antes de desplegar, mirar
  `git log -- admin-backend/database.rules.json` por si hay reglas anteriores que todavía no se
  desplegaron (por ejemplo las de B.73). El emulador corre el archivo completo, así que las 55 verdes
  cubren todo.
* La regla nueva (leer completo, no cambiarla sin entenderla):

  ```
  "deviceDiag": {
    ".read": "<admin | authorizedAdminsUids>",
    "$device_id": { ".write": "<admin | authorizedAdminsUids | root.child('devices').child($device_id).child('ownerUid').val() === auth.uid>" }
  }
  ```

  Solo el panel lee. Escribe el celular dueño de `devices/<id>` (criterio de B.73: dato ya guardado, nunca
  `newData`). Un `deviceId` sin nodo en `devices/` no tiene dueño: nadie (salvo admin) escribe. **Sin
  `.validate` a propósito** (celular y panel pueden evolucionar el formato sin desplegar reglas).

---

## 4. Desplegar

**Orden recomendado: reglas → Functions → (después) Hosting + APK.** `deploy_all.ps1` despliega
`hosting,database` juntos y las Functions al final; para respetar el orden, antes de correrlo:

```powershell
cd "E:\Documentos\Lock Suite segunda version\admin-backend"
$env:GOOGLE_APPLICATION_CREDENTIALS = "$PWD\locksuite-nueva-firebase-adminsdk-fbsvc-dac7996bff.json"
$env:FUNCTIONS_DISCOVERY_TIMEOUT = "120"
firebase deploy --only database
firebase deploy --only functions
```

Después `deploy_all.ps1` como siempre (compila el APK release, sube la versión, hosting y GitHub). Hosting
incluye `diag_logic.js` y `diag_remoto.js` (los `<script … ?v=1>` ya están en `celular.html`; no hace
falta tocarlos). Después de desplegar, abrir el panel con **Ctrl+F5** una vez.

* **Por qué ese orden:** si el APK nuevo llega a un celular ANTES que las reglas, cada subida da
  `permission_denied`: el celular lo anota en `devices/<id>/diagUploadError`, reintenta con retroceso
  (5 min → 6 h) y no rompe nada, pero no hay registro hasta desplegar. Y si el panel (Hosting) sale antes
  que la Function, «Pedir diagnóstico» contesta «comando no permitido» hasta que `sendCommandV8` se actualice.
* **Si `firebase deploy --only functions` falla** (por ejemplo el 409 de la cola de Google Cloud que
  menciona `deploy_all.ps1`): el panel ya muestra la tarjeta pero los dos comandos nuevos no andan.
  Reintentar el despliegue de Functions hasta que salga y verificar que `sendCommandV8` conserva la misma URL.
* **Antes de dar por bueno el despliegue:** comprobar que todos los equipos de la flota tienen
  `devices/<id>/ownerUid` (la regla de escritura lo usa; uno sin él no puede subir registro).

---

## 5. Pruebas en equipo real (lo único que de verdad confirma esto)

Hace falta al menos **un celular con la APK nueva** (lo ideal: uno de cada tipo de la flota — Q-Tel Q1 con
Android 11, el equipo del dueño, y alguno con Android 7–10 si hay). En cada prueba, mirar la tarjeta
**Registro de errores** (ficha → Diagnóstico) y, si algo no cierra, el nodo `deviceDiag/<id>` en la consola
de Firebase.

0. **Estado inicial.** La tarjeta dice «Registro activo» y, pasados unos minutos, «Último envío del
   celular: hace N min». Si dice que el celular **no tiene el registro** → `devices/<id>/diagVersion` no
   existe: es una APK vieja. Si dice **«El panel no pudo leer… permission_denied»** → reglas sin desplegar
   (§4). Si muestra un **error de subida** → ver el texto de `diagUploadError`.
1. **Arranque directo** (la razón de usar el almacenamiento protegido del dispositivo). Reiniciar el
   celular y **no desbloquearlo** durante 1–2 min; después desbloquear. En el registro tienen que aparecer
   renglones `app` («Proceso iniciado: v0.6.77… almacenamiento BLOQUEADO (arranque directo)») y `boot`
   con una hora **anterior** al desbloqueo. Si faltan, el archivo no se está escribiendo antes del primer
   desbloqueo: revisar `DiagStore` (¿`createDeviceProtectedStorageContext`?).
2. **Una caída de verdad.** Con ADB: `adb shell am crash com.ejemplo.locksuite` (debería terminar el proceso
   con una excepción no atrapada). Esperar a la próxima subida (≤ unos minutos): tiene que aparecer un
   renglón **`crash`** («Excepción no atrapada en el hilo 'main'…» con el stack trace) y, en el arranque
   siguiente, un renglón **`exit`** con el motivo del cierre (Android 11+). Si falta `crash` pero está
   `exit`, el gancho de `LockSuiteApplication.installGlobalCrashHandler` no se ejecutó antes de morir.
3. **Errores atrapados (`System.err`).** Tras un día de uso normal tienen que aparecer renglones `err`
   (hay 178 `printStackTrace()` en el código). Para comparar: `adb logcat -s System.err`. Si en 24 h con uso
   real no hay ninguno y el logcat sí los muestra, revisar `DiagErrTee` (¿otro código reemplazó
   `System.err` después?).
4. **Pedir diagnóstico.** «Pedir diagnóstico ahora» eligiendo una app (por ejemplo `com.whatsapp`): en
   menos de ~25 s tiene que decir «Listo: el diagnóstico llegó…». Abrir el diagnóstico y revisar que las
   secciones **Sistema** (batería, red, memoria, accesibilidad, VPN, Device Owner…), **Cierres recientes**,
   **Errores del logcat de LockSuite** y **Apps consultadas** traigan datos legibles. Hacerlo en **cada**
   modelo: algunos fabricantes recortan el `logcat` y la sección de errores puede venir vacía (anotar el
   modelo). Cada línea que el equipo no pueda contestar debe decir «(no disponible)», no romper el resto.
5. **PIN.** En un celular con PIN cargado: «Pedir diagnóstico» **NO** pide el PIN (es de solo lectura);
   «Apagar la subida» / «Encender» **SÍ** lo piden. También con el PIN sin publicar (B.104,
   `DEVICE_PIN_NOT_ENROLLED`): el diagnóstico tiene que poder pedirse igual.
6. **Interruptor.** «Apagar la subida» → el estado pasa a «subida apagada». Provocar un error (prueba 2) y
   verificar que **no llega nada nuevo** a `deviceDiag/<id>` (ni por el latido ni por «Pedir diagnóstico», que
   debe contestar «La subida del registro está APAGADA…»). «Encender» → llegan los renglones pendientes.
7. **LockSuite suspendido.** Con LockSuite suspendido desde el panel, «Pedir diagnóstico» y el interruptor
   tienen que seguir funcionando (están en `allowedWhileSuspended`); cualquier otro comando sigue rechazado.
8. **Batería y recursos** (el pedido central del dueño). Dejar un celular 24 h con uso normal. Comparar con
   otro igual sin la APK nueva (o con la anterior): `adb shell dumpsys batterystats --charged com.ejemplo.locksuite`
   y Ajustes → Batería. El registro **no debe verse** en el consumo ni crear *wakeups* propios. Revisar
   también en `deviceDiag/<id>/meta` que las subidas por día no pasen de 48 y que `lastUploadAt` avance
   cada ~1 h cuando solo hay renglones informativos. Medir el barrido del logcat (`logcat -d`, un proceso
   corto cada 30 min como máximo, plazo de 8 s): si se nota, subir `SWEEP_GAP_MS` en `DiagCollector`.
9. **Cadencia.** Provocar un error y cronometrar cuánto tarda en aparecer en el panel: esperado = el próximo
   latido (hasta ~3 min) y nunca más de una subida cada 2 min; los informativos, cada hora.
10. **Privacidad (la más importante).** Leer **50 renglones reales** y un diagnóstico completo buscando
    datos personales: teléfonos, correos, dominios que visitó el usuario, nombres de contactos, SSID, rutas
    de archivos. Si algo se cuela, agregar el patrón a `DiagSanitizer` **y una prueba** en
    `DiagSanitizerTest`. El archivo del celular **no está cifrado** (almacenamiento privado de la app, como
    el resto); en Firebase solo lo lee el panel.
11. **Android < 11.** No hay `ApplicationExitInfo`: no deben aparecer renglones `exit` ni romperse nada; el
    resto (renglones propios, `err`, logcat) anda igual.
12. **Panel con datos reales.** Con un celular que ya tenga cientos de renglones: la lista se mueve con
    fluidez, los filtros responden, un renglón abierto no se cierra cuando llega uno nuevo, «Copiar informe»
    copia (o muestra el texto si el navegador no deja). Con las herramientas de desarrollo del navegador
    comprobar que **mientras no se abra la sección Diagnóstico no hay ninguna conexión a `deviceDiag`**.
13. **Eliminar un equipo borra su registro.** En un equipo de PRUEBA que ya tenga registro: «🗑️ Eliminar
    dispositivo» en la barra lateral (o «Eliminar definitivamente» desde Archivados) y comprobar en la consola
    de Firebase que `deviceDiag/<id>` desapareció junto con `devices/<id>` y `deviceSecrets/<id>`. «Archivar»,
    en cambio, tiene que dejar `deviceDiag/<id>` intacto.
14. **Pegarle el informe a Claude.** El objetivo final: tomar un problema real, «Copiar informe», pegarlo y
    ver si alcanza para diagnosticar. Lo que falte (una etiqueta, un dato del sistema) se agrega como
    `DiagLog` en el lugar que no registra; **no** se «baja el nivel» del filtro.

---

## 6. Si algo falla (cómo diagnosticar)

| Síntoma | Qué mirar |
|---|---|
| La tarjeta dice que el celular no tiene el registro | `devices/<id>/diagVersion` no existe → APK vieja (o `syncDeviceInfo` todavía no corrió). |
| «El panel no pudo leer…» / `permission_denied` | Reglas sin desplegar (§4) o el usuario del panel no está en `authorizedAdminsUids`. |
| Error de subida del celular | `devices/<id>/diagUploadError`. Si es `permission_denied`: reglas, o ese equipo no tiene `ownerUid` / su uid anónimo cambió (re-vinculación pendiente de «Aprobar»). |
| No sube nada y no hay error | Interruptor apagado (`diagUploadEnabled=false`); o el tope de 48 subidas/día; o el retroceso (`meta`); o el celular sin red/sesión (el latido no sale, y la subida va en el latido). |
| «Pedir diagnóstico» no vuelve | Ack del comando en `devices/<id>/commandAcks`; timeout de 25 s del panel; el celular tarda hasta ~20 s en juntar y subir; ¿FCM/buzón? (equipos sin FCM tardan hasta 15 min, B.95). |
| No hay renglones `exit` | Android < 11, o el cursor `exit_cursor` ya pasó esos cierres. |
| Faltan renglones | Sube solo los 400 últimos; `meta.skipped` dice cuántos se saltearon por exceso de lote. |

---

## 7. Qué NO simplificar

* `DiagLog.init(this)` es lo **PRIMERO** de `LockSuiteApplication.onCreate`: así quedan registradas también
  las fallas del arranque.
* El registro vive en el **almacenamiento protegido del dispositivo**, no en `PrefsHelper` (cifrado, ilegible
  hasta el primer desbloqueo): justo ahí pasan las cosas que el dueño necesita ver.
* **Sin temporizadores, alarmas ni wakelocks propios**; la subida se engancha en el latido existente. Es la
  garantía de «no gasta batería»: no agregar un `Worker` ni un `Handler.postDelayed` periódico.
* El nodo es **aparte** de `devices/<id>` (el oyente principal del panel descarga todo `devices/`) y el
  panel se suscribe **solo cuando la sección Diagnóstico está abierta**.
* **Todo texto que viene del celular se escapa** en el panel (`LS.esc` / `textContent`): un celular
  comprometido no debe poder inyectar HTML.
* El lote sube los **más nuevos** y cuenta los salteados; `seq` nace de `max(archivo, cursor de subida) + 1`
  (si se borra el archivo no vuelve a 1); cada subida borra `seq-400` en la misma escritura (no hace falta
  ninguna función programada).
* Un fallo de subida **no se traga**: queda en `diagUploadError`; el retroceso evita reintentar en cada latido.
* `COLLECT_DIAGNOSTICS` es la excepción de PIN **solo porque solo lee** (B.7, B.104). No copiar la excepción
  a comandos que cambien políticas.
* El sanitizador **no** quita nombres de clase/método ni números de línea (son lo que permite diagnosticar).
* `check_diag_sync.py` y las 32 + 61 pruebas del panel existen para que celular, panel, Function y reglas no
  se desfasen: si se cambia el formato de `deviceDiag`, cambiarlo en los cuatro lugares.

---

## 8. Al terminar

1. En `LOCKSUITE_CONTEXTO_PARA_IA.md`, punto **B.110**: tachar (`~~…~~`, con fecha) **solo lo que se
   confirmó en un equipo real** (pruebas 1 a 14) y dejar visible lo que no. Pasar el título de
   «[IMPLEMENTADO Y VERIFICADO SOLO EN EL CONTENEDOR…]» al estado real.
2. Reemplazar la sección **C** por el resumen de esa sesión (no acumular).
3. Commit con mensaje que diga qué se confirmó y qué cambió (si se corrigió una firma de Firebase o una
   regla, decirlo).

---

## 9. Cómo se verificó en el contenedor (para que sepan qué esperar)

| Chequeo | Resultado esperado |
|---|---|
| `bash tools/ia_contenedor/typecheck/typecheck.sh` | `TIPOS: 0 errores` (111 archivos Kotlin) |
| `bash tools/ia_contenedor/typecheck/run_unit_tests.sh` | `OK (277 tests)` |
| `node tools/test_diag_logic.js` | `OK (32 pruebas)` |
| `bash tools/panel_render/run.sh` | cinco `TODO VERDE` (la pasada «Registro de errores» = 61 verificaciones; «Eliminar / archivar» = 13) |
| `python3 tools/check_diag_sync.py` | `OK … (30 comprobaciones)` |
| `check_command_sync.py`, `check_panel_commands.py`, `check_profile_sync.py`, `check_whitelist_sync.py`, `check_store_sync.py` | todos OK |
| `python3 tools/gen_catalog_js.py --check`, `gen_policies_js.py --check` | OK |
| `node tools/test_tienda_logic.js`, `node tools/test_play_lookup.js` | `OK (48 pruebas)`, `OK (16 pruebas)` |
| `tools/rules_tests` | **NO se pudo correr** (sin emulador): esperado `TODAS VERDES (55)` |

* Pruebas de mutación hechas a mano (se rompió el código a propósito y se verificó que las pruebas
  fallan): la lógica pura del panel, la interfaz, y `check_diag_sync.py` (cada mutante fue detectado). No
  quedaron guardadas en el repo.
* **Lo que NO prueba nada de lo anterior:** que compile con Gradle, que las reglas se comporten en el
  emulador, ni nada que dependa de un celular real (§5). El panel se vio solo con un Firebase falso en
  Chromium (Playwright).

---

## 10. Ideas que se dejaron afuera a propósito (no son pendientes)

* Badge o aviso en la **lista de celulares** («este equipo tuvo una caída hoy»): habría que tocar la lista de
  `app.js`, que otros están usando. Se puede sumar leyendo `deviceDiag/<id>/meta` o publicando un contador en `devices/`.
* Pedir diagnóstico a **varios celulares a la vez** y botón **«Vaciar registro»** del equipo (las reglas ya
  lo permiten a los administradores: `remove(deviceDiag/<id>/events)`).
* Mover el CSS que `diag_remoto.js` inyecta (`<style id="dr-style">`) a `panel2.css`, cuando nadie más lo
  esté tocando.
