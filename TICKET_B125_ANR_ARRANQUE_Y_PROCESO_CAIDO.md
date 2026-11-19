# B.125 — ANR al actualizar o reiniciar, y LockSuite muerto horas después de «Cerrar app»

Ticket para implementar. Lo escribió Claude (Opus) el 19/11/2026 en **solo lectura**. Parte del
diagnóstico del celular **Iejiel** (`e1939eb56647c24f`, DuoQin F21, Android 11, LockSuite 0.6.89 /
152) y del código de `main` en `ef7fbe7`. Las líneas citadas son de ese commit. No se compiló ni
se tocó código.

---

## 0. Veredicto

1. **Los 4 ANR tienen una sola causa, y es de LockSuite, no del celular.**
   - `BootReceiver` retiene el broadcast del sistema con `goAsync()` mientras corre
     `reapplyAllRestrictions()` completa.
   - Esa pasada además tiene que esperar a OTRA pasada idéntica que lanzó
     `LockSuiteApplication` al arrancar.
   - Android da 60 s para terminar un broadcast en segundo plano (`MY_PACKAGE_REPLACED`,
     `BOOT_COMPLETED`). En un equipo lento, recién instalado o recién encendido, no alcanza → ANR.
   - Pasa en toda la flota. Se nota en los equipos lentos (Qin F21, Q-Tel Q1) y justo cuando se
     empuja una OTA. Ya había pasado el 6/11 en el Q-Tel Q1 de R Shaul Said (B.112, nota final:
     ANR 90 s después de instalar, «user request after error»).
2. **Lo grave es lo que vino después.**
   - El ANR le muestra al usuario «LockSuite no responde → Cerrar app», y las 4 veces alguien lo
     tocó.
   - Dos veces LockSuite volvió solo (1 s y 42 s).
   - **Dos veces quedó muerto: 52 h 25 min (13/11 16:30 → 15/11 20:55) y 4 h 26 min (16/11 11:55
     → 16:21).** En ese lapso no hubo filtro DNS ni accesibilidad. Solo siguieron las políticas de
     Device Owner, porque viven en el sistema.
   - Por qué no revivió solo: **NO verificado**. En Android puro, los servicios `START_STICKY` y
     el `WatchdogWorker` lo tendrían que haber levantado en segundos o minutos. Hipótesis: esta ROM
     detiene el paquete o bloquea los arranques en segundo plano después de «Cerrar app».
   - En la práctica, **el cartel de ANR es un botón para apagar LockSuite al alcance del usuario.**
3. **«Fondos» no se instaló (16/11 09:35).** La descarga corría en el scope de la pantalla
   (`rememberCoroutineScope`) y se canceló al salir de ella. Es error de LockSuite y pasa en toda
   la flota.
4. **FCM no puede renovar su token desde el 18/11 08:13** (`SERVICE_NOT_AVAILABLE` cada hora).
   - Los comandos igual llegan por el buzón en vivo (B.95). El `COLLECT_DIAGNOSTICS` llegó por
     ahí, y las copias por FCM quedaron como `REPLAY`.
   - La causa es probablemente del equipo o de Google Play Services (**NO verificado**).
   - Lo que sí es de LockSuite: anota dos trazas por hora. Eso se come el registro de 400
     renglones, y por eso el informe no llega más atrás del 11/11.
5. **El resto es ruido o no es error:**
   - Los `E logcat` de arranque (`jdwp`, `ion: ioctl`, `No package ID 6b`,
     `ro.vendor.pref_scale_resolution`) son del sistema del fabricante.
   - Los 6 `USER_REQUESTED` son instalaciones.
   - Que el equipo saltara 0.6.85, 0.6.87 y 0.6.88 es normal: solo se instala con
     `UPDATE_LOCKSUITE` o con el botón del celular.

---

## 1. Evidencia

### 1.1 Cada ANR cae ~60 s después del broadcast

| Evento | Proceso nuevo | Broadcast | ANR declarado (calculado: broadcast + 60 s) | «Cerrar app» (`user request after error`) | LockSuite volvió |
|---|---|---|---|---|---|
| 11/11 → 0.6.82 | 12:17:11 | `MY_PACKAGE_REPLACED` 12:17:13 | no hubo | — | — |
| 13/11 → 0.6.83 | 16:28:22 | `MY_PACKAGE_REPLACED` 16:28:23 | ≈ 16:29:23 | 16:30:07 | **15/11 20:55:26 (52 h 25 min)** |
| 15/11 → 0.6.84 | 20:55:47 | `MY_PACKAGE_REPLACED` 20:55:48 | ≈ 20:56:48 | 20:56:53 | 20:57:35 (42 s) |
| 16/11 reinicio | 11:54:11 bloqueado; inicialización al desbloquear 11:54:21 | `BOOT_COMPLETED` 11:54:28 | ≈ 11:55:28 | 11:55:33 | **16:21:06 (4 h 26 min)** |
| 18/11 → 0.6.86 | 08:13:27 | `MY_PACKAGE_REPLACED` 08:13:28 | ≈ 08:14:28 | 08:14:41 | 08:14:42 (1 s) |
| 19/11 → 0.6.89 | 08:25:52 | `MY_PACKAGE_REPLACED` 08:25:54 | no hubo | — | — |

- **Ningún arranque sin esos broadcasts tuvo ANR.** No lo tuvieron los de 15/11 20:55:27,
  15/11 20:57:35 ni 16/11 16:21:06.
- **El hilo principal está ocioso en las 4 trazas** (`nativePollOnce`), o sea que no estaba
  trabado. Es la firma de un broadcast retenido con `goAsync()` sin `finish()`, no de código lento
  en el hilo principal.
- Importancia 125 (servicio en primer plano). Con un servicio en primer plano, Android **muestra**
  el cartel de ANR; no lo resuelve en silencio.
- `user request after error` es lo que anota Android cuando alguien toca «Cerrar app» en ese
  cartel.
- **Descartado: ANR por `startForegroundService()` sin `startForeground()`** (plazo de 10 s en
  Android 11).
  - `WatchdogForegroundService` llama a `startForeground` lo primero en `onCreate`
    (`WatchdogForegroundService.kt:367-383`).
  - Los `return` tempranos de `KosherVpnService.startVpn()` (`KosherVpnService.kt:546-560`) ocurren
    con el servicio ya en primer plano, porque `scheduleRestart` usa `keepForeground = true`.

### 1.2 El proceso no existió durante 52 h y 4,5 h

- Dos fuentes independientes coinciden:
  - El registro no tiene ni una línea en ese lapso: ni `Proceso iniciado`, ni latidos, ni
    errores.
  - `ApplicationExitInfo` no anota ninguna muerte de proceso entre el ANR y el arranque
    siguiente. Si hubiera existido un proceso, su muerte figuraría.
- El equipo no se apagó: no hay `LOCKED_BOOT_COMPLETED` y el último encendido es el del 16/11
  11:54.
- Indicio (**NO verificado**) de que las dos veces lo revivió una persona abriendo la app:
  - Solo en esos dos arranques aparecen errores de dibujo de pantalla (`ion: ioctl`,
    `No package ID 6b`).
  - A los 15 s del primero se instaló la OTA pendiente.

### 1.3 Antecedente en otro modelo

B.112, nota final: Q-Tel Q1 `058d4e4ac999401a`, 6/11 a las 14:50:42. ANR 90 s después de
instalar, con «user request after error». Es el mismo mecanismo en otro fabricante, así que no es
un problema de este celular.

---

## 2. Causa raíz en el código

- **`receiver/BootReceiver.kt:106-125`** usa `goAsync()` y lanza
  `CoroutineScope(Dispatchers.IO).launch { reapplyAllRestrictions(); GracePeriodManager.checkAndHarden(); FirebaseDeviceSync.requestSync() }`,
  con `finish()` en el `finally`.
  - El comentario de la línea 110 es el malentendido de fondo: **`goAsync()` no amplía el
    plazo.** Android sigue exigiendo `finish()` en 10 s (broadcast de primer plano) o 60 s
    (segundo plano).
  - Sirve para hacer trabajo corto fuera del hilo principal, no para trabajo largo.
- **`LockSuiteApplication.kt:365-379`**: cada arranque del proceso ya lanza otra
  `reapplyAllRestrictions()` en IO.
- **`mdm/PolicyManager.kt:2562-2563`**: `synchronized(reapplyLock)` pone las dos en fila. El
  receptor espera a que termine la pasada del arranque y después hace la suya, completa. Son **dos
  pasadas enteras antes de `finish()`**.
- `reapplyAllRestrictions()` (`PolicyManager.kt:2562-2965`) es pesada. Incluye:
  - permisos y restricciones;
  - kiosco y VPN permanente;
  - lista blanca, Tienda Play y tienda clásica;
  - recorrido de 200 a 400 paquetes, ocultando y suspendiendo;
  - Android Auto y accesibilidad.
- Cuánto tarda: B.90 midió 15 a 20 s por pasada en frío en Qin F21 y CAT S22 Flip. Recién
  instalado o encendido, además, el sistema está ocupado (optimización del APK, arranque de todo).
  Dos pasadas más esa carga llegan a los 60 s.
- **El mismo patrón está en `receiver/DeviceAdminReceiver.kt:81`**: `goAsync()` +
  `applyMasterProfile()` en el alta por QR. Hay riesgo de ANR en el alta de equipos lentos.
- Historia: B.90 sacó esta pasada del hilo principal por un ANR de servicio. El cambio a
  `goAsync()` creó un ANR de otro tipo, de broadcast.

---

## 3. ¿Todo LockSuite o este celular?

| Hallazgo | Alcance | Por qué |
|---|---|---|
| ANR al actualizar o reiniciar | **Todo LockSuite** | El código es común y ya pasó en un Q-Tel Q1. Aparece en equipos lentos y en cada OTA empujada a la flota. |
| El cartel «Cerrar app» apaga LockSuite | **Todo LockSuite** | Cualquier ANR lo muestra en un equipo con servicio en primer plano. |
| No revive solo después de «Cerrar app» | **Probablemente la ROM de este equipo** (NO verificado) | En Android puro vuelve en segundos; acá, 2 de 4 veces no volvió. El ticket lo blinda igual para toda la flota (T3). |
| «Fondos» cancelado | **Todo LockSuite** | La descarga está atada a la pantalla. Lo mismo pasa con los botones de autoactualización. |
| Token FCM con `SERVICE_NOT_AVAILABLE` | **Probablemente el equipo o Google Play Services** (NO verificado) | La lista blanca ya permite `googleapis.com`, `android.clients.google.com`, `mtalk*` y `firebaseinstallations`. El ruido en el registro sí es de LockSuite. |
| `E logcat` de arranque | Equipo (ROM) | Son mensajes del sistema del fabricante. |

---

## 4. Arreglos

Orden: **T1 y T2 juntos, en la misma versión**: T2 confirma T1 y responde lo del punto 1.2.
Después T4, T5, T3 y T6.

### T1 (P0) — Ningún receptor retiene un broadcast con trabajo pesado

**1. Nuevo `util/ReconcileScheduler.kt` (ejecuta) + `util/ReconcilePolicy.kt` (lógica pura).**
`ReconcilePolicy` lleva pruebas JUnit, con el mismo patrón que `ZombieHealPolicy` y
`CrashNetPolicy`.

- `ReconcileScheduler.request(context, reason: String, startup: Boolean, afterBoot: Boolean = false)`
  **no bloquea nunca**.
  - Corre en un hilo propio: `Executors.newSingleThreadExecutor` con hilo daemon
    `LockSuiteReconcile` y `Process.THREAD_PRIORITY_BACKGROUND`.
- Reglas de `ReconcilePolicy.decide(...)`:
  - Sin pasada en curso y sin estar «cubierto» → **RUN**.
  - Un pedido `startup = true` (arranque del proceso, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`)
    está **cubierto** si hay una pasada en curso, o si empezó otra hace menos de 30 s **en este
    mismo proceso**.
  - Cubierto quiere decir que no se hace otra pasada completa. Si trae `afterBoot`, sus extras
    se agregan al final de la pasada en curso, o se corren solos si esa pasada ya terminó.
  - Un pedido que no es de arranque, con una pasada en curso → **QUEUE**. Se marca pendiente y, al
    terminar, se hace UNA pasada más, sin importar cuántos pedidos se juntaron.
  - Los extras `afterBoot` son `GracePeriodManager.checkAndHarden(ctx)` y después
    `FirebaseDeviceSync.requestSync(ctx)`, a continuación de la pasada.
- Cada pasada se cronometra:
  - si tarda más de 10 s → `DiagLog.w("reapply", "reapplyAllRestrictions tardó %d s (motivo: %s)")`;
  - si tarda más de 30 s → además el desglose por paso (T2.5).
- Nunca lanza: `try/catch (Throwable)` por pasada, y el hilo sigue vivo.

**2. `BootReceiver.onReceive`.**
- Borrar `goAsync()` y la corrutina (líneas 106-125).
- En su lugar:
  `ReconcileScheduler.request(context, "broadcast $action", startup = true, afterBoot = true)`.
- Lo demás queda igual, porque es corto: `DiagLog`, `ensureUnlockedInit`, alarma del arranque
  protegido, `BootGate`, reglas DNS, arrancar Watchdog, VPN y marca de agua.
- Lo que mantiene vivo el proceso es arrancar el Watchdog (servicio en primer plano) antes de
  soltar el broadcast. Ya no hace falta `goAsync()`.
- Corregir el comentario de la línea 110 y dejar esta regla en la cabecera del archivo:

  > `goAsync()` solo para trabajo acotado (< 5 s) con `finish()` en `finally`. Todo lo que pueda
  > tardar más va a `ReconcileScheduler`, al Watchdog o a WorkManager. Android no amplía el plazo
  > por usar `goAsync()`: 10 s (primer plano) / 60 s (segundo plano) y después ANR.

**3. `LockSuiteApplication.initializeUnlocked()` (líneas 365-379).** La corrutina pasa a ser
`ReconcileScheduler.request(this, "arranque del proceso", startup = true)`.

**4. `DeviceAdminReceiver.kt:81`.** Mismo arreglo:
- arrancar el Watchdog en primer plano;
- correr `applyMasterProfile` y los avisos en un hilo de fondo (puede ser el mismo ejecutor del
  scheduler, como una tarea aparte);
- **no** retener el broadcast.

**5. Lo que queda como está.** `WatchdogWorker` y el resto de las llamadas a
`reapplyAllRestrictions()` corren en su propio hilo, y el `reapplyLock` las ordena.

**Aceptación de T1**
- `ReconcilePolicyTest`:
  - dos pedidos de arranque juntos → 1 pasada;
  - un pedido de arranque y otro que no es de arranque durante la pasada → 2 pasadas;
  - 5 pedidos que no son de arranque durante una pasada → 2 pasadas en total;
  - pedido de arranque 10 s después de terminar → cubierto;
  - pedido de arranque 40 s después → pasada nueva;
  - `afterBoot` cubierto → los extras corren igual, y una sola vez.
- `grep -rn "goAsync" app/src/main/java` → ningún receptor retiene trabajo largo.
- En equipo, con OK del dueño para la OTA: 3 instalaciones y 3 reinicios en el Qin F21 y en un
  Q-Tel Q1.
  - «Cierres recientes» sin ningún ANR.
  - `reapply tardó N s` en el registro cuando corresponda.
  - `adb logcat -b events | findstr am_anr` vacío.

### T2 (P1) — Instrumentación para cerrar lo que quedó NO verificado (va con T1)

**1. Traza del ANR con el motivo.**
- `diag/DiagCollector.kt:103-114` (`readAnrHead`) hoy salta directo al hilo `"main"` y descarta la
  cabecera.
- En la cabecera Android escribe el motivo exacto del ANR:
  - `Subject: Broadcast of Intent { act=…MY_PACKAGE_REPLACED … }`;
  - `Input dispatching timed out…`;
  - `executing service…`.
- Extraer una función pura
  `diag/AnrTraceHead.extract(lines: List<String>, ownPackage: String = "com.ejemplo.locksuite"): String`
  que devuelva:
  - la línea `Subject:` si existe; si no, las primeras 3 líneas no vacías antes del primer
    `----- pid`;
  - el bloque `"main"`, hasta 18 líneas como hoy;
  - hasta 2 bloques de hilo más que tengan algún marco de `com.ejemplo.locksuite`, 10 líneas cada
    uno;
  - todo con un tope de 2.500 caracteres.
- Usar la misma función en `FirebaseDeviceSync.getLatestProcessExitInfoApi30()`
  (`lastExitTrace`). Así la ficha del panel también muestra el motivo.
- Prueba: `AnrTraceHeadTest` con una traza de ejemplo de Android 11, con cabecera y sin ella.

**2. Quién arrancó el proceso.**
- `DiagLog.firstEntry(source: String)` anota UNA vez por proceso, con un `AtomicBoolean`:
  `El proceso lo arrancó: <source> (+N ms desde el inicio)`, con
  `N = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()`.
- Llamarlo al principio de cada punto de entrada:

| Punto de entrada | `source` |
|---|---|
| `BootReceiver.onReceive` | `"broadcast <action>"` |
| `LockSuiteFirebaseService.onMessageReceived` / `onNewToken` | `"FCM"` |
| `WatchdogWorker.doWork` | `"WatchdogWorker (JobScheduler)"` |
| `WatchdogForegroundService.onStartCommand` | `intent == null` ? `"reinicio START_STICKY del Watchdog"` : `"inicio del Watchdog"` |
| `KosherVpnService.onStartCommand` | `intent == null` ? `"reinicio START_STICKY de la VPN"` : (`intent.action == VpnService.SERVICE_INTERFACE` ? `"VPN siempre activa (sistema)"` : `"inicio de la VPN"`) |
| `LockSuiteAccessibilityService.onServiceConnected` | `"enlace de accesibilidad"` |
| `onCreate` de las pantallas lanzables (`LoginActivity`, `DashboardActivity`, lanzador kosher, `BlockAccessibilityActivity`) | `"pantalla <Nombre>"` |
| `ReviveReceiver` (T3.2) | `"alarma de rescate"` |
| Mensaje WAKE (T3.1) | `"FCM WAKE del servidor"` |

- En API 35+, sumar `ActivityManager.getHistoricalProcessStartReasons(1)`: `startType`, `reason` y
  `wasForceStopped()`.

**3. ¿Lo detuvieron a la fuerza?**
- Al arrancar el proceso, chequear una sentinela con el mismo truco que `ForceStopRunnable` de
  WorkManager:
  `PendingIntent.getBroadcast(ctx, REQ_SENTINEL, Intent(ctx, ReviveReceiver::class.java).setAction(ACTION_SENTINEL), FLAG_NO_CREATE or FLAG_IMMUTABLE)`.
- Si devuelve `null`, Android borró los `PendingIntent` del paquete: hubo **detención forzada**.
- Una actualización también es detención forzada (`stop … due to installPackageLI`). Para
  distinguirlas, comparar `PackageInfo.lastUpdateTime` con el guardado en `DiagStore`:
  - si cambió → fue una actualización;
  - si no cambió →
    `DiagLog.w("app", "Arranque después de una DETENCIÓN FORZADA del paquete (se cancelaron alarmas y trabajos)")`.
- Después, recrear la sentinela con `AlarmManager.set(RTC, now + 365 días, pi)`.
- Lógica pura con prueba: `ForceStopVerdict.decide(sentinelPresent, lastUpdateTimeNow, lastUpdateTimeSaved)`.

**4. Cuánto estuvo caído.**
- Va en `DiagCollector.recordExitsApi30`, que ya lee los cierres al arrancar. Tomar el cierre más
  reciente.
- Si ocurrió **después del último encendido** (`timestamp >= now - SystemClock.elapsedRealtime()`):
  - `inicio = now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())`;
  - `gap = inicio - timestamp`.
- Si `gap > 2 min`:
  - `DiagLog.w("app", "LockSuite estuvo CAÍDO <X h Y min>: murió el <fecha> por <MOTIVO> y recién volvió ahora")`;
  - publicar `lastOutageMs`, `lastOutageFrom`, `lastOutageTo` y `lastOutageReason` con
    `FirebaseDeviceSync.reportFields`.
- Es exacto y gratis: no necesita latidos.
- Panel: chip rojo en la ficha si `lastOutageMs > 10 min` en los últimos 7 días.
- Lógica pura con prueba, `OutageMath.classify(...)`:
  - el caso del 13/11 tiene que dar 52 h 25 min;
  - con un reinicio en el medio no es caída.

**5. Desglose de `reapplyAllRestrictions()`.**
- Medir con `SystemClock.elapsedRealtime()` los bloques grandes: permisos, restricciones,
  kiosco/VPN, lista blanca, Tienda Play, tienda clásica, recorrido de paquetes, Android Auto,
  instalación y accesibilidad.
- Loguear el desglose solo si la pasada total pasó de 30 s.
- Da el dato real para la próxima optimización. **No optimizar a ciegas en este ticket.**

### T3 (P1/P2) — Revivir sin depender de la ROM

**1. (P1) Latido inverso desde el servidor.**
- Nueva función programada en `admin-backend/functions/index.js`, `despertarEquiposSilenciosos`,
  cada 15 min, con `const { onSchedule } = require("firebase-functions/v2/scheduler")`.
- Qué equipos toma:
  - Consultar `devices` ordenado por `lastSeen` entre `now - 7 días` y `now - 25 min`.
  - Agregar `".indexOn": ["lastSeen"]` en `devices` en `database.rules.json`.
  - Verificar que `lastSeen` está en la raíz del nodo; lo escribe `syncLastSeenOnly`,
    `FirebaseDeviceSync.kt:135-140`.
  - Saltear los purgados o suspendidos.
- A cada uno con token (`getDeviceField(deviceData, "fcmToken")`, como `sendCommandV8` en la línea
  491), enviarle:
  `admin.messaging().send({ token, data: { wake: "1", t: String(Date.now()) }, android: { priority: "high", collapseKey: "wake", ttl: 15 * 60 * 1000 } })`.
- Tope: 1 por equipo cada 15 min y 24 por día. Anotar `devices/<id>/wake/{lastSentAt,count,day}`.
- En el celular, en `LockSuiteFirebaseService.onMessageReceived`, **antes** de `CommandProcessor`:
  si `message.data["wake"] == "1"`:
  - `DiagLog.firstEntry("FCM WAKE del servidor")`;
  - arrancar el Watchdog;
  - `BootReceiver.ensureVpnRunning(...)`;
  - `FirebaseDeviceSync.syncLastSeenOnly(...)`;
  - `return`.
  Este mensaje no lleva firma, PIN ni ACK, y no ejecuta ninguna acción.
- Panel: en la lista y en la ficha, «Sin señal hace X · se intentó despertar a las HH:MM».
- **El deploy de functions y de reglas es producción: necesita OK del dueño.**

**2. (P2) Alarma de rescate en el celular.**
- Nuevo `receiver/ReviveReceiver.kt`, con `exported=false`.
- El Watchdog (en su ciclo) y el Worker la re-arman, como mucho cada 5 min:
  `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, elapsed + 20 min, pi)`.
- Mientras LockSuite vive, nunca suena, porque se corre siempre para adelante.
- Si el proceso muere sin detención forzada, suena a los 20 min o antes:
  `firstEntry("alarma de rescate")`, arrancar el Watchdog y `ensureVpnRunning`, sin `goAsync()`.
- Es otro subsistema que JobScheduler (WorkManager): diversidad barata, un IPC cada 5 min.
- Device Owner está exento de la restricción de Android 12+ para arrancar servicios en primer
  plano desde segundo plano.

**3. (P2, opcional) `dpm.setUserControlDisabledPackages(admin, listOf(packageName))`** (API 30+).
- El usuario no puede forzar la detención ni borrar datos.
- En Android 12+, además, exime de standby y de las restricciones de batería.
- Comparar antes de escribir.
- **Simetría obligatoria** (lección de B.11 / S-3):
  - levantarlo en `liftAllForSuspension()` y en la purga;
  - sumarlo en las cuatro listas: aplicar, suspender, purgar y perfil.
- Esto no saca el «Cerrar app» del cartel de ANR. Eso solo se evita no teniendo ANR.

### T4 (P1) — Instalaciones de la tienda fuera de la pantalla («Fondos»)

1. **Nuevo `util/StoreInstallManager.kt`.**
   - Scope de proceso: `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
   - `install(context, app)`, con una sola instalación a la vez por paquete.
   - Estado en `StateFlow<Map<String, InstallState>>`: `Downloading(progress)`, `Installing`,
     `Done`, `Error(msg)`.
   - Escribe `devices/<id>/updateProgress` igual que hoy.
   - Si termina bien, llama a `ClassicStorePolicyManager.applyOnInstall(...)`.
   - El aviso (toast) se muestra con `applicationContext`, en el hilo principal.
2. **`ui/auth/LoginActivity.kt:1012-1046`.**
   - El botón llama a `StoreInstallManager.install(...)`.
   - `storeDownloadingPackage` y `storeProgress` salen del `StateFlow`, con `collectAsState`.
   - Salir de la pantalla ya no cancela nada.
3. **Mismo cambio para la autoactualización desde el celular.** `LoginActivity.kt:474` y
   `DashboardActivity.kt:1461` llaman a `SelfUpdater.checkAndPerformUpdate` dentro de
   `rememberCoroutineScope`. Pasan a `StoreInstallManager.selfUpdate(context)`, en el scope de
   proceso.
4. **`util/SelfUpdater.kt:179`** (`checkAndPerformUpdate`) **y `:382`** (`downloadAndInstallApk`).
   **Antes** del `catch (e: Exception)`, agregar:

   ```kotlin
   catch (e: kotlin.coroutines.cancellation.CancellationException) {
       // misma limpieza que el catch de abajo
       // (restaurar restricciones si no hubo commit, borrar el temporal)
       throw e
   }
   ```

   Una cancelación nunca se informa como «Error al instalar».
5. **Verificar** que `UpdateFlowManager.closeOrphanedAfterRestart` (B.97) también cubra la tienda
   clásica si el proceso muere a mitad de una instalación.

**Aceptación de T4**
- Prueba JVM de `StoreInstallManager` con un descargador falso: no duplica instalaciones, y la
  instalación sigue aunque se cancele quien la estaba mirando.
- En equipo: empezar a instalar desde la tienda, tocar Inicio y volver → la app quedó instalada.
  Nunca aparece «left the composition».

### T5 (P2) — FCM: menos ruido, y averiguar la causa

1. **`util/FirebaseDeviceSync.kt:795-799`**: sacar `task.exception?.printStackTrace()`.
   - Anotar `DiagLog.w("fcm", "No se pudo obtener el token FCM: <motivo> (comandos por buzón en vivo)")`
     **solo al cambiar de estado** (OK → error y error → OK). Usar el estado que ya guarda
     `markFcmState`.
   - Sumar un conteo diario.
   - Hoy son unas 2 trazas por hora: en 29 h fueron unos 60 de los 400 renglones del registro en
     la nube.
2. **Fondo del problema.**
   - El SDK pide token nuevo en cada cambio de versión de la app, y cada 7 días. O sea que **cada
     OTA obliga a volver a registrarse con Google**.
   - En este equipo funcionó del 15/11 al 18/11, y falla desde la instalación del 18/11 08:13.
   - Para averiguarlo (el dueño o la próxima sesión, en solo lectura):
     - en el panel, ver si otros equipos tienen `fcmTokenError` después de la 0.6.86;
     - en este celular, durante una falla, mirar el registro de DNS bloqueados (¿algún dominio de
       Google?);
     - por ADB: `adb shell pm dump com.google.android.gsf | findstr /i suspended`, y lo mismo con
       `com.google.android.gms`.

### T6 (P3) — Ruido del registro

En `diag/DiagLogcatParser`, no guardar estas líneas conocidas del fabricante:
- `Not starting debugger since process cannot load the jdwp agent`;
- `Access denied finding property "ro.vendor.`;
- `ion: ioctl`;
- `No package ID [0-9a-f]+ found for ID`.

Usar una lista cerrada de expresiones, con su prueba.

---

## 5. Qué NO hacer

- No volver a poner trabajo pesado en un receptor «porque tiene `goAsync()`».
- No «simplificar» `ReconcilePolicy` a «si está ocupado, ignorar». Un pedido que no es de arranque
  y llega durante una pasada tiene que garantizar UNA pasada completa después.
- No `android:persistent`, no lockdown, no túnel completo: son decisiones firmes.
- No publicar nada sin OK:
  - `deploy_all.ps1`, `UPDATE_LOCKSUITE` y `firebase deploy` necesitan el OK explícito del dueño
    en cada caso;
  - sí correr `.\gradlew.bat testDebugUnitTest`, los `node --check` y las pruebas JS.
- No tachar B.125 hasta probarlo en equipo real (Qin F21 y Q-Tel Q1).

---

## 6. Pruebas en equipo (cuando haya OK)

1. **Instalación.** Instalar la versión nueva por el panel en el Qin F21 (`e1939eb56647c24f`) y en
   un Q-Tel Q1.
   - «Cierres recientes» sin ANR.
   - En el registro: `El proceso lo arrancó: broadcast android.intent.action.MY_PACKAGE_REPLACED`.
   - Si tardó: `reapply tardó N s`.
2. **Reinicio.** Reiniciar cada uno 3 veces: sin ANR.
3. **Detención forzada.** `adb shell am force-stop com.ejemplo.locksuite` y abrir la app → el
   registro dice `Arranque después de una DETENCIÓN FORZADA`.
4. **Revivido.**
   - Con root: `adb shell kill -9 <pid>`, como si Android lo matara por memoria.
   - Sin root: `adb shell am crash com.ejemplo.locksuite`. Ojo: un CRASH deja la accesibilidad
     zombi (B.109/B.112), que se repara sola.
   - Ver en el registro qué lo revivió y en cuánto (`El proceso lo arrancó: …`,
     `LockSuite estuvo CAÍDO …`).
5. **Latido inverso.** Dejar un equipo 30 min en modo avión y volver a conectarlo.
   - En la base debe aparecer `wake/lastSentAt`.
   - Si el proceso estaba muerto, el registro dice `FCM WAKE del servidor`.

---

## 7. Mensajes de commit sugeridos (por tanda)

- `fix(boot): no retener MY_PACKAGE_REPLACED/BOOT_COMPLETED con goAsync; reconciliación única fuera del broadcast (B.125 T1)`
- `feat(diag): motivo del ANR, quién arrancó el proceso, detención forzada y tiempo caído (B.125 T2)`
- `fix(tienda): instalaciones y autoactualización en scope de proceso; no informar cancelaciones como error (B.125 T4)`
- `chore(fcm): el token FCM fallido se anota solo al cambiar de estado (B.125 T5)`
- `feat(functions): despertar por FCM de alta prioridad a los equipos sin latido (B.125 T3.1)`
  *(solo con OK para el deploy)*
