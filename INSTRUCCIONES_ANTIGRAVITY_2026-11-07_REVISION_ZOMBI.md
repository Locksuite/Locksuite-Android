# Instrucciones para Antigravity — 7/11/2026 — Blindaje contra el zombi de accesibilidad (B.112)

Leé primero `LOCKSUITE_CONTEXTO_PARA_IA.md`: sección A, B.15 (la nota del 7/11 sobre `WRITE_SECURE_SETTINGS`), B.16, B.20, B.109 y **B.112 completo, sobre todo la «Revisión de Claude»**, y la sección C.

## Resumen

- Tu arreglo de WorkManager (0.6.80) **está bien hecho y corrige el crash del 6/11**. No hay que rehacerlo.
- La causa de fondo es más amplia: una **carrera del arranque**. El proceso nace con el equipo bloqueado (por `LOCKED_BOOT_COMPLETED`), sin los `ContentProvider` de WorkManager y Firebase, y Android le enlaza la accesibilidad antes de que la app termine de inicializarse.
- Además, **cualquier** crash con la accesibilidad enlazada deja el zombi, y **no había forma de que se curara solo**.
- Claude escribió el blindaje completo. Está **sin compilar con Gradle y sin probar en un equipo**: eso es lo que te toca.

**Regla:** no le digas al dueño que el problema está resuelto hasta que pasen las pruebas 4 y 5.

---

## 1. Antes de aplicar nada

1. `git status` tiene que estar limpio salvo **tres archivos que dejó Claude sin commitear**:
   - `LOCKSUITE_CONTEXTO_PARA_IA.md` (modificado);
   - `INSTRUCCIONES_ANTIGRAVITY_2026-11-07_REVISION_ZOMBI.md` (este, nuevo);
   - `Claude outputs/` con los dos `.patch`.
2. Commiteá primero esos documentos, con este mensaje:
   ```
   docs: revisión de B.112 (zombi de accesibilidad) e instrucciones

   Análisis de la causa de fondo (carrera del arranque: el proceso nace con el
   equipo bloqueado y la accesibilidad se enlaza antes de la inicialización),
   mecanismo de AOSP verificado, decisiones y pruebas pendientes.
   ```
3. Si el repo tiene commits posteriores a `8d942b3` que tocan los mismos archivos, `git am --3way` debería resolverlos. Si queda un conflicto, conservá los dos lados y avisá en la sección C.

## 2. Aplicar el parche

```
git am --3way "Claude outputs\2026-11-07_B112_blindaje_accesibilidad.patch"
```

- Son **2 commits** (`9e49b72` y `a559a22` en el clon de Claude).
- **No apliques también `2026-11-07_red_de_seguridad_accesibilidad.patch`**: ese es el primer commit de esta misma serie, entregado antes.
- Hacelo **antes** de `deploy_all.ps1`, que hace `git add .`.

Qué cambia (detalle y porqué en B.112):

| Archivo | Cambio |
|---|---|
| `LockSuiteApplication.kt` | `ensureUnlockedInit()`: la inicialización completa corre una vez, la pida quien la pida primero, con candado. Re-chequeo del desbloqueo después de registrar el receptor. Red para hilos de trabajo en el manejador global. Llama a `AccessibilityZombieHealer.onProcessStart()`. |
| `util/AccessibilityZombieHealer.kt` (nuevo) | Autorreparación: ciclo del ajuste si hay `WRITE_SECURE_SETTINGS`, si no reinstalación del propio APK. |
| `util/ZombieHealPolicy.kt` (nuevo) | Cuándo y cómo (pura, con `ZombieHealPolicyTest`). |
| `util/CrashNetPolicy.kt` (nuevo) | Qué excepciones de hilos de trabajo no matan el proceso (pura, con `CrashNetPolicyTest`). |
| `util/Safe.kt` (nuevo) | `Safe.guard`, red para callbacks del hilo principal. |
| `service/LockSuiteAccessibilityService.kt` | Redes en `onAccessibilityEvent`, `onServiceConnected` (con 3 reintentos), `onUnbind`, `onDestroy` y 7 `Runnable`. Llama a `ensureUnlockedInit` al conectarse. |
| `service/WatchdogForegroundService.kt` | Redes en el `ContentObserver`, el aviso y la revisión inmediata. El ciclo de 20 s se re-encola en un `finally`. Llama al reparador en el ciclo de accesibilidad y a `ensureUnlockedInit` en `onCreate`. |
| `receiver/BootReceiver.kt`, `PackageReceiver.kt`, `PackageInstallStatusReceiver.kt` | `onReceive` con red. `BootReceiver` además llama a `ensureUnlockedInit`. |
| `service/LockSuiteFirebaseService.kt`, `service/KosherVpnService.kt`, `util/CommandMailbox.kt` | `ensureUnlockedInit` y redes puntuales. |
| `util/LocaleManager.kt` | La pantalla roja del zombi dice que se repara sola (es/en/he). |
| `AndroidManifest.xml` | `WRITE_SECURE_SETTINGS` declarado (`tools:ignore="ProtectedPermissions"`). |
| `tools/ia_contenedor/typecheck/…` | Stub de `androidx.work.Configuration` y las dos suites nuevas en `run_unit_tests.sh`. |

## 3. Compilar y probar en la PC

1. `gradlew :app:compileDebugKotlin :app:testDebugUnitTest`: tienen que pasar todas, incluidas `ZombieHealPolicyTest` y `CrashNetPolicyTest`.
2. `gradlew :app:lintDebug`: el `tools:ignore` debería evitar el error `ProtectedPermissions`. Si aparece otro error nuevo, anotalo.
3. Si algo no compila, **no lo «arregles» borrando la red de seguridad**: pegá el error en la sesión con Claude.

## 4. Prueba del zombi fabricado (en el celular de prueba «Yo», `7073099b55af5fd6`)

Es la prueba que demuestra la autorreparación. Con la versión nueva instalada, la accesibilidad funcionando y el celular conectado por ADB:

1. `adb shell am crash com.ejemplo.locksuite`: provoca un crash real del proceso.
   - **No uses `am force-stop` ni «Forzar detención».** Android trata eso distinto: SACA el servicio de la lista de habilitados (`onHandleForceStop`), así que la accesibilidad queda apagada de verdad, no zombi. El reparador no la vuelve a encender, a propósito.
2. Enseguida: `adb shell dumpsys accessibility | findstr /i "crashed binding enabled"`. Anotá si LockSuite figura en «Crashed services» o en «Binding services».
   - Si **no** figura en ninguno y la accesibilidad volvió sola, el equipo no produce zombi con un solo crash. Repetí `am crash` 2 veces seguidas, con menos de 60 s entre una y otra.
3. Si quedó zombi, esperá. A los **4–5 min** desde que el celular se encendió y 3 desde que se vio el zombi, tiene que pasar esto:
   - en el panel, el registro de diagnóstico muestra `a11y-heal: Reparando zombi: se reinstala el propio APK…`;
   - el proceso se reinicia (`lastExitDetail` dice `installPackageLI`);
   - la accesibilidad vuelve (`accessibilityRunning: true`, `accessibilityZombie: false`);
   - en `devices/<id>`: `a11yHealCount` 1, `a11yHealLastResult` «reinstalación: reparada en N s».
4. Confirmá que después de la reinstalación las restricciones de instalación volvieron: la app no tiene que quedar con `DISALLOW_INSTALL_APPS` levantado. `PackageInstallStatusReceiver` y la alarma de seguridad de 2 min son las mismas que usa `UPDATE_LOCKSUITE`.
5. Confirmá el tope: tres zombis fabricados seguidos producen **como mucho 3** reinstalaciones en el día, y nunca dos en menos de 30 min.
6. Si la reinstalación falla, el registro dice `reinstalación falló: <motivo>`. Pegale el motivo a Claude. Lo más probable sería que Android no acepte reinstalar con el mismo `versionCode`; Claude lo verificó en el código de Android, pero no en un equipo.

## 5. Prueba de reinicio (en «Yo» 3 veces, y en el celular de R Shaul Said 1 vez)

1. Verificá en el panel que el celular de R Shaul Said (`058d4e4ac999401a`) tenga la versión nueva. Si no la tiene, mandale `UPDATE_LOCKSUITE`, que no pide PIN.
2. Apagá y prendé el celular. No alcanza con forzar la detención de la app.
3. Después del desbloqueo: **Diagnóstico → Pedir diagnóstico ahora → Copiar informe**.
4. Aprobado si se cumplen las tres cosas:
   - no hay ningún CRASH nuevo después del reinicio;
   - la accesibilidad está corriendo y no figura como zombi;
   - si el proceso nació bloqueado, aparece `boot: Proceso nacido con el equipo bloqueado: inicialización completa disparada por …`, lo que confirma que la carrera existe y que ahora se cubre.

## 6. Panel (`admin-backend/public/celular.js` y la ficha)

- Mostrá `a11yHealCount`, `a11yHealLastAt` y `a11yHealLastResult` cerca del chip de Accesibilidad, por ejemplo «Se reparó sola N veces · última: <fecha> · <resultado>».
- Si `accessibilityZombie` es true, agregá al aviso actual: «LockSuite la está reparando solo; si en 10 min sigue, apagar y prender en Ajustes».
- En el registro de diagnóstico, las etiquetas nuevas son `a11y-heal`, `safe` y `crash-net`. Si `diag_logic.js` tiene una lista de etiquetas conocidas, sumalas.

## 7. Lo que quedó sin red (a propósito, para más adelante)

- Unos 40 callbacks de Firebase en `FirebaseDeviceSync`, casi todos una línea de log.
- La interfaz Compose, incluido el lanzador kosher (`KosherLauncherActivity`).

Un crash ahí todavía puede dejar un zombi, que ahora se repara solo. Si el registro muestra crashes reales ahí, se les pone `Safe.guard`.

## 8. Mejora posible (pedile la decisión al dueño, no la hagas sin preguntar)

Con `WRITE_SECURE_SETTINGS`, que solo tendrán los equipos dados de alta por ADB de ahora en adelante, LockSuite podría **volver a encender la accesibilidad sola si el usuario la apaga** en Ajustes. Hoy B.15 dice que es imposible, y la nota del 7/11 explica por qué ya no lo es. El cambio es de comportamiento, así que lo decide el dueño.

## 9. Cierre

1. Compilar, desplegar y probar (puntos 3 a 5).
2. Actualizar B.112 y la sección C. **Tachar solo lo confirmado en un equipo real con el informe de diagnóstico.**
3. Commit con un mensaje que diga qué se probó y en qué equipos.
