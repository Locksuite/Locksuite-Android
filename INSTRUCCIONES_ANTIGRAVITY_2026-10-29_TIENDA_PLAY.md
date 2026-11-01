# Instrucciones para Antigravity — 29/10 al 1/11/2026

## Tienda Play (B.101): apps permitidas que se instalan desde Google Play oficial, con el MISMO tapado de pantalla de la actualización

Escrito por Claude, trabajando por el puente al dispositivo desde un contenedor **sin Gradle,
sin SDK de Android y sin acceso a Google Play**. Está **commiteado en el clon de la sesión
(un solo commit sobre `b6e687b`), pero NO está compilado con Gradle, NO está desplegado y NO
se probó en ningún celular.** Lo que sigue es exactamente lo que falta, en orden. El porqué
completo está en `LOCKSUITE_CONTEXTO_PARA_IA.md`, punto **B.101**.

---

## 0. En una línea

El dueño pidió una tienda de apps que se instale desde Google Play **oficial** sin que se pueda
evadir el filtro: el usuario nunca ve Play ni elige qué instalar. Se reusa exactamente el flujo
de actualizar apps (pantalla negra con progreso, Play abierto por debajo, instalación permitida
solo para ese paquete) con un modo nuevo, «instalar». El administrador permite paquetes en tres
niveles acumulativos (1 Kosher ⊂ 2 Trabajo ⊂ 3 Lite) desde el panel; cada equipo tiene un modo
(cerrada / kosher / trabajo / lite) que fija el administrador o un perfil automático (Estricto →
kosher, Nivel 2 → trabajo, gracia → toda la tienda y después kosher, nuevos Nivel 5 «Solo
llamadas» → cerrada y Nivel 6 «Lite» → toda). Por app: acceso completa / sin web / dominios, y
estado habilitada / solo a pedido / bloqueada. Por defecto el panel arma la lista con las apps de
la tienda vieja.

**Qué es lo más riesgoso de esta tanda, en orden:** (1) se refactorizó `UpdateFlowManager.startFlow`
para el modo instalar — hay que comprobar que **actualizar apps sigue andando igual que antes**
(§4, prueba 0); (2) los archivos de Compose y los recursos nuevos nunca pasaron por Gradle (§2);
(3) `checkPlayApps` nunca corrió contra Google Play de verdad (§3).

---

## 1. Aplicar — ANTES de `deploy_all.ps1`

`deploy_all.ps1` hace `git add .`: si se corre primero, todo esto queda mezclado en el commit
automático de versión. Aplicar el parche antes.

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git status                  # en main y sin cambios (salvo, quizá, styles.css: ver abajo)
git log -1 --oneline        # b6e687b docs: actualizar estado de despliegue a version 0.6.69 (132) …
git am --3way "Claude outputs\2026-11-01_tienda_play.patch"
git show --stat HEAD
```

* El parche es **un solo commit** de 110 archivos (escritos con fin de línea LF; los avisos
  «LF will be replaced by CRLF» son normales con `core.autocrlf`). **No se escribieron archivos
  sueltos en el disco de la PC a propósito**: la sesión no puede correr `git` ahí, y pisar
  archivos sin poder ver si alguien los tocó es peor que aplicar un parche que verifica su contexto.
* Se hizo sobre `b6e687b` (HEAD de la PC al 28/10 a las 02:52, verificado leyendo `.git/logs/HEAD`).
  Si en la PC hay commits nuevos, `--3way` suele alcanzar. Si hay conflicto, los candidatos son
  `LOCKSUITE_CONTEXTO_PARA_IA.md` (quedarse con las dos partes; la sección C tiene que ser la de
  esta sesión), `admin-backend/public/app.js`, `celular.js` y `functions/index.js`.
* ⚠️ `admin-backend/public/styles.css` en la PC pesa 1 byte más que en el commit (22183 contra
  22182 bytes, con fecha del 10/9). Si `git status` lo muestra modificado, **no es de esta sesión**
  y el parche no lo toca: dejalo como está y no lo metas en este commit.
* **Plan B** si `git am` no entra: `git am --abort`, descomprimir
  `Claude outputs\2026-11-01_tienda_play_archivos.zip` en la raíz del proyecto (trae los mismos
  archivos completos, más `LISTA_DE_ARCHIVOS.txt`), `git add` **solo** esos archivos y
  `git commit -F "Claude outputs\2026-11-01_mensaje_commit.txt"`.
* **No subas la versión a mano**: `deploy_all.ps1` la sube sola (132 → 133).

---

## 2. Compilar y probar en la PC

```powershell
.\gradlew :app:compileDebugKotlin
.\gradlew :app:testDebugUnitTest
.\gradlew :app:assembleRelease
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/check_store_sync.py
python tools/gen_catalog_js.py --check
python tools/gen_policies_js.py --check
node tools/test_tienda_logic.js
node tools/test_play_lookup.js
bash tools/panel_render/run.sh          # si hay node + playwright (Git Bash / WSL)
```

En la sesión todo lo que no es Gradle dio verde (ver §7). **Gradle no se corrió nunca.** Las
pruebas de JUnit nuevas son `PlayStoreCatalogTest` (49), `EnrollmentProfilesStoreModeTest` (11) y
`PlayStoreUiLogicTest` (22): se corrieron con el `kotlinc` de Gradle 8.14.3 (Kotlin 2.0.21) sin
Android, y el proyecto compila con Kotlin 2.3.20, así que `testDebugUnitTest` es la primera vez
que corren con el compilador real.

**Si Gradle se queja, estos son los lugares, con el cambio que NO altera el comportamiento:**

| Dónde | Qué podría fallar | Cómo cambiarlo |
|---|---|---|
| `ui/dashboard/PlayStoreModeGroup.kt` (Compose, **nunca compilado**) | firmas de Material3: `RadioButtonDefaults.colors(selectedColor =, unselectedColor =)`, `PolicyGroupCard(title) { … }` (es pública y del mismo paquete, no necesita import) | Ajustar a la firma real; no hay lógica ahí: llama a `PlayStoreManager.setMode` |
| `ui/dashboard/PlayStoreModeGroup.kt` | la fila entera es `clickable` **y** el `RadioButton` tiene su `onClick`: un toque sobre el botón redondo puede llamar a `choose()` dos veces. Es inocuo (`choose` ignora lo que ya está elegido) | Si molesta: `onClick = null` en el `RadioButton` |
| `ui/launcher/PlayStoreTile.kt` (Compose, **nunca compilado**) | `KosherAppIcon(label, icon, gradientStart, gradientEnd)` (es una `data class` de `AppIconMapper.kt`) e `Icons.Filled.ShoppingCart` (es de `material-icons-core`) | Ajustar a la firma real; no hay lógica ahí, solo arma la baldosa |
| `KosherLauncherActivity.kt` / `NokiaIconSet.kt` / `DashboardActivity.kt` | los 4 agregados son de 3–6 líneas y se leyeron contra las declaraciones reales (las listas son `toMutableList()`, `refreshKey` está en el `LazyColumn` de `PoliciesTabContent`); aun así Compose no se compiló | — |
| Recursos `ic_play_store_*.xml`, `widget_play_store.xml`, `play_store_widget_info.xml` | nunca pasaron por `aapt2`. `play_store_widget_info.xml` copia el patrón de `google_verify_widget_info.xml` | Si el ícono sale en blanco o con el recuadro sin la bolsa en un lanzador real: reemplazar el `layer-list` de `ic_play_store_tile.xml` por UN solo `<vector>` (recuadro redondeado + los paths de `ic_play_store_glyph.xml`) |
| `ui/playstore/PlayStoreActivity.kt`, `PlayStoreIcons.kt`, `PlayStoreRows.kt`, `PlayStoreText.kt` | son Views comunes y se type-checkearon contra el `android.jar` real (API 36) con 0 errores; lo esperable es que compilen. Lint puede sumar avisos | — |

Lint puede avisar de los textos de `PlayStoreText` (no están en `strings.xml` a propósito: son
es/en/he en Kotlin puro para poder probar que ninguna clave quede sin texto). No son errores.

---

## 3. Desplegar

```powershell
.\deploy_all.ps1 -VersionName "0.6.70"
```

Sube a **0.6.70 / 133**, publica hosting + database + functions. Las reglas
(`database.rules.json`) **no cambiaron**: `globalSettings/playStore/*` y
`devices/<id>/playStore*` caen bajo las reglas que ya existen.

* **Functions:** `checkPlayApps` es una función **nueva** y `sendCommandV8` aprende dos comandos
  (`SET_PLAY_STORE_MODE`, `SYNC_PLAY_STORE`). Si el despliegue de functions falla, el panel
  nuevo todavía sirve, pero los dos comandos nuevos darían «comando no permitido».
* **Confirmar la URL de `checkPlayApps`.** En `admin-backend/public/comun.js`
  (`URL_CHECK_PLAY`) está `https://checkplayapps-687828714595.us-central1.run.app`, que sale de
  aplicar el patrón de `sendCommandV8` (`<nombre en minúsculas>-<número de proyecto>.<región>.run.app`).
  **Es una suposición hasta ver el despliegue.** Comprobarla con
  `curl.exe -i -X OPTIONS https://checkplayapps-687828714595.us-central1.run.app` (tiene que dar
  204 con cabeceras CORS) y, sin token, un `POST` tiene que dar 401 «No autorizado». Si la URL
  real es otra: corregirla en `comun.js`, subir `comun.js?v=5` en `celular.html`, `dominios.html`
  y `tienda.html`, y volver a desplegar hosting.
* Después de desplegar, abrir el panel con **Ctrl+F5** (cache-busters nuevos: `app.js?v=43`,
  `comun.js?v=4`, `celular.js?v=8`, `tienda_logic.js?v=2`, `tienda.js?v=1`).
* **Primera prueba real de `checkPlayApps`** (nunca corrió contra Google Play): panel → Tienda
  Play → «Verificar en Play». Esperable: las apps reales dicen «en Google Play» y traen nombre e
  ícono. Si todas dicen «sin saber»: Google puede estar sirviendo una página de consentimiento o
  bloqueando las IP de Cloud Run; mirar `firebase functions:log --only checkPlayApps` y
  `parsePlayPage` en `admin-backend/functions/play_lookup.js`. **Nada se rompe**: la tienda
  funciona igual sin verificación (el celular muestra un cuadrito con la letra inicial si no hay
  ícono).

---

## 4. Pruebas en el celular, en este orden

Usar un equipo de prueba con ≥400 MB libres, la Accesibilidad activa y una cuenta de Google
configurada (es lo mismo que pide actualizar apps).

**0. ★ Que actualizar apps sigue andando (regresión).** `startFlow` se refactorizó. Mandar
«Actualizar» a una app con actualización pendiente desde el panel: pantalla negra con progreso,
Play por debajo, termina con «✓ Actualización completada» (y NO dice «instalación»). Probar
también «Cancelar» y el caso «ya está al día». Si esto falla, parar acá: el culpable es el
refactor de `UpdateFlowManager`.

1. **Panel → Tienda Play** (`tienda.html`). Tiene que aparecer la lista inicial de la tienda
   vieja (sin los stubs de Android Auto ni LockSuite/Play). Tocar «Verificar en Play», repartir
   los niveles 1/2/3, dejar alguna «A pedido» y otra «Bloqueada», «Guardar» y «Enviar a todos».
2. **Ficha de un celular de prueba → Tienda Play → modo Kosher.** En el celular: Dashboard →
   Políticas → grupo «Tienda Play» con «Kosher» marcado; aparece el ícono (widget en un lanzador
   común; baldosa «Tienda» en el lanzador kosher y entrada en el modo teclas); al tocarlo, la
   lista muestra SOLO el nivel 1, con íconos y nombres reales. La ficha del panel debe mostrar
   `offered/listed` coherentes (viene de `devices/<id>/playStore`).
3. ★ **Instalar** una app liviana: pantalla negra con «Instalando <nombre de la app>» (aunque la
   app todavía no exista en el equipo), Google Play por debajo, la app se instala, y al volver la
   tienda dice «Abrir». Revisar el registro paso a paso (pantalla
   negra y ficha) y que **al terminar quede todo como antes**: restricciones de instalación
   puestas, `mdm_install_in_progress` en falso, Play de nuevo oculta/suspendida si el equipo la
   tenía así (B.99).
4. **Cancelar** una instalación a la mitad (botón de la pantalla negra): vuelve a la tienda y no
   queda ninguna restricción levantada.
5. **Sin web** y **dominios**: una app con «sin web» aparece como «Sin web» en la sección
   Aplicaciones y no navega; una con dominios solo abre los permitidos (y lo prohibido no).
6. **Solo a pedido:** «Pedir» → 🔔 Pedidos del panel (con la etiqueta «Tienda Play») → Aprobar →
   la app pasa a «Instalar» SOLO en ese celular. Comprobar que **no** quedó permitida en otro
   celular ni en `globalSettings/allowedPackages`.
7. **Bajar el modo** (Kosher → Cerrada) con una app instalada por la tienda: queda **suspendida,
   no desinstalada**; volver a Kosher la reanuda.
8. **Perfiles:** aplicar «Nivel 5 — Solo llamadas» (tienda cerrada: el ícono gris/desaparece) y
   «Nivel 6 — Lite» (toda la tienda); iniciar una **gracia** corta: la tienda se abre completa y,
   al vencer, queda en Kosher.
9. **Alta por QR** de un equipo nuevo con el perfil Estricto: queda con la tienda en Kosher.
10. Hebreo/inglés: cambiar el idioma de LockSuite y mirar la pantalla (RTL en hebreo).

---

## 5. Si algo falla, dónde mirar

* **«Instalar» no arranca y muestra un aviso:** el texto del aviso es el motivo. Salen de
  `PlayStoreManager.install` («La tienda está cerrada en este equipo.», «Esta aplicación ya no
  está disponible en la tienda.», «… ya está instalada.») y de `UpdateFlowManager.startFlow`
  («Ya hay una instalacion en curso…», «El servicio de accesibilidad de LockSuite no esta activo…»,
  «La aplicacion … no esta disponible en la tienda de este equipo.») y, después, de las
  verificaciones previas de ese mismo flujo (poco espacio, Google Play deshabilitada, sin cuenta
  de Google).
* **«Google Play dice que X ya está instalada, pero el sistema no la encuentra»:** la ficha dijo
  «Abrir» y el paquete no es visible (oculto por una regla del administrador).
* **La tienda aparece vacía:** `globalSettings/playStore/meta/savedAt` tiene que existir (si no,
  nadie guardó la lista) y el modo del equipo no puede ser `cerrada`. En la ficha, `devices/<id>/playStore`
  dice `mode`, `offered`, `listed`, `installedByStore` y `paused`. Para forzar la relectura:
  `SYNC_PLAY_STORE` desde «Enviar a todos».
* **Aprobé un pedido y no pasa nada:** debe existir `devices/<id>/playStoreExtras/<paquete_>` con
  `status: "enabled"`, y mandarse `SYNC_PLAY_STORE`.

---

## 6. Cosas que NO hay que "simplificar"

1. **La red de seguridad `PlayStoreManager.isOffered` dentro de `UpdateFlowManager.startFlow`.**
   `install()` ya comprueba, pero una pantalla vieja o un intent ajeno no deben poder instalar
   algo que ya no se ofrece.
2. **`listed` ≠ `effective`.** Una app «solo a pedido» se MUESTRA pero no se INSTALA; todo lo
   demás (lista blanca, kiosco, pausa, instalar) mira `effective`.
3. **Aprobar un pedido guarda un extra del equipo, nunca `allowedPackages`.** Lo contrario abre
   la app a toda la flota.
4. **La pausa es SUSPENDER, nunca desinstalar.** Se borrarían datos del usuario por un cambio de
   modo.
5. **«Modo desconocido = cerrada» y «clave ausente en un perfil = no se toca».** Un dato roto no
   abre nada; un perfil viejo no cierra ni abre la tienda.
6. **`SYNC_PLAY_STORE` no lleva datos.** El catálogo no viaja por FCM (tope de 3000 bytes); el
   celular lo lee de la base.
7. **`NEVER_OFFERED` está en dos copias (celular y panel).** `tools/check_store_sync.py` las vigila
   junto con modos, estados, accesos, niveles, topes, hosts de íconos, expresiones y huellas de
   los stubs de Android Auto: correrlo antes de cada despliegue.
8. **El ícono remoto pasa por `cleanIconUrl`** (https, solo CDN de Google, sin redirecciones, tope
   de 512 KB). La pantalla nunca baja una imagen de un sitio cualquiera.
9. **`PlayStoreActivity` lee en un hilo aparte y falla cerrado** (ante una excepción muestra la
   tienda cerrada, nunca una abierta).
10. **El lanzador kosher propio NO aloja widgets** (igual que con Verificar Google): por eso la
    entrada ahí es una baldosa (`PlayStoreTile`), no un widget.

---

## 7. Lo que no se pudo hacer desde la sesión, y lo que queda para decidir

**Verificado en el contenedor (sin Gradle):** 82 pruebas JUnit nuevas y 91 previas en verde;
0 errores de tipos en 87 archivos no-Compose (`tools/ia_contenedor/typecheck/typecheck.sh`, con
control negativo); 48 + 16 pruebas de Node; los cinco `check_*.py`; `gen_catalog_js.py --check` y
`gen_policies_js.py --check`; `node --check`; el panel completo en Playwright con Firebase y
Function falsos (tres pasadas «TODO VERDE»).

**No verificado:** Gradle (Compose y recursos), lint, cualquier equipo real, Google Play real
(`checkPlayApps`), y el tapado de pantalla de una instalación de verdad.

**Decisiones que se tomaron sin poder preguntar (el dueño las revisa):**

* «Solo a pedido» = se ve con «Pedir» y no se instala hasta que el administrador apruebe el pedido
  de ese usuario.
* La lista inicial pone **todo en nivel 1**, acceso completa; el estado inicial es lo que el dueño
  ya había decidido en la tienda vieja (en `allowedPackages` → habilitada; si no → a pedido).
  Nada llega a los equipos hasta «Guardar».
* «Las que hay en Play Store» se resuelve **verificando** y con un botón «Quitar las N que no
  aparecen en Play», no borrando solas: una app de otro país también da 404 desde el servidor.
* Gracia = `lite` (toda la tienda) mientras dura; al vencer aplica el perfil destino (Estricto
  por omisión), o sea kosher.
* Los niveles nuevos se llaman «Nivel 5 — Solo llamadas» y «Nivel 6 — Lite».
* La tienda vieja (`storeApps`, la Tienda de LockSuite) **no se tocó**.

**Para tener presente (seguridad):** `devices/<id>/playStoreExtras` está bajo `devices/$id`, que la
regla de la base deja escribir al dueño del equipo (su `ownerUid` anónimo). Igual que `appPolicy`
(B.63), no resiste a alguien que corra código con la identidad de Firebase de la app (necesita
root/ADB, algo que LockSuite ya impide). No se tocaron las reglas porque eso pide
`tools/rules_tests/`. Aun así, un extra nunca puede saltarse `NEVER_OFFERED`, la lista blanca ni
«bloquear populares», y nada se instala sin pasar por `startFlow`.

**Se arregló de paso** un bug viejo del panel: la etiqueta de la pestaña 🔔 Pedidos en `app.js`
leía `querySelectorAll("div")[1]` en vez de `[2]`.

---

## 8. Mensaje de commit

Está en `Claude outputs\2026-11-01_mensaje_commit.txt` (es el mismo que lleva el parche). Si se
usa el plan B, `git commit -F` con ese archivo.
