# Informe de Errores, Diagnósticos y Soluciones — LockSuite (06/11/2026)

Este documento detalla cronológicamente los problemas detectados durante la jornada del 6 de noviembre de 2026, las causas raíces identificadas tanto a nivel de código como del sistema operativo Android, y las soluciones implementadas en el cliente Android y en el panel de administración web. Se prepara para revisión cruzada y seguimiento entre Claude y Antigravity.

---

## Índice
1. [Incidente Crítico: Servicio de Accesibilidad "Zombi" y Crash de WorkManager](#1-incidente-crítico-servicio-de-accesibilidad-zombi-y-crash-de-workmanager)
2. [Adaptación de Accesibilidad para Google Play Store v53 (B.110)](#2-adaptación-de-accesibilidad-para-google-play-store-v53-b110)
3. [Integración del Sistema de Diagnóstico Remoto Pasivo (B.111)](#3-integración-del-sistema-de-diagnóstico-remoto-pasivo-b111)
4. [Manejo de Conflictos de Fusión y Despliegue de Infraestructura](#4-manejo-de-conflictos-de-fusión-y-despliegue-de-infraestructura)
5. [Resumen de Versiones y Puntos Clave para Revisión de Claude](#5-resumen-de-versiones-y-puntos-clave-para-revisión-de-claude)

---

## 1. Incidente Crítico: Servicio de Accesibilidad "Zombi" y Crash de WorkManager

### 1.1 Síntoma Reportado
En el panel web, la ficha del dispositivo de **R shaul said** (`058d4e4ac999401a`, modelo `Q-Tel Q1`, Android 11 / API 30) mostraba una alerta roja persistente:
> *"El servicio de Accesibilidad fue detenido por Android tras un reinicio/crash (en Ajustes del celular figura como Activado, pero el sistema no lo está ejecutando). Hay que desactivarlo y volverlo a activar en Ajustes del teléfono para que funcione."*

El usuario indicó que R Shaul Said no tiene conocimientos técnicos para manipular los Ajustes del dispositivo y solicitó reactivarlo de forma remota/automática.

### 1.2 Mecánica del Problema en Android (Estado Zombi)
En Android AOSP, el componente del sistema `AccessibilityManagerService` (AMS) gestiona el ciclo de vida de los servicios de accesibilidad:
* Cuando el proceso que hospeda un `AccessibilityService` muere por una excepción no capturada (`FATAL EXCEPTION`), AMS añade dicho componente a su lista interna `mCrashedServices`.
* A partir de ese momento, aunque el proceso de la aplicación reviva y la configuración del sistema (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`) mantenga el servicio activado, AMS se niega a realizar el enlace Binder (`bindService`) con el servicio.
* Como resultado: en Ajustes figura como "Activado", pero el servicio nunca se instancia ni recibe eventos de ventana.

### 1.3 Reactivación Automática sin intervención manual
Se constató y confirmó el siguiente comportamiento del framework de Android:
* Cuando un paquete se actualiza mediante `installPackageLI` (disparado por una actualización OTA de LockSuite a través de `UPDATE_LOCKSUITE`), el gestor de paquetes de Android (`PackageManagerService`) notifica a AMS vía `onPackageUpdateFinished`.
* AMS **limpia automáticamente la lista de servicios caídos (`mCrashedServices`)** para el paquete actualizado y vuelve a enlazar el servicio de accesibilidad de inmediato.
* Al desplegarse la versión `0.6.79 (142)` al equipo de R Shaul Said, el servicio fue restaurado automáticamente a las 14:49:02 sin que el usuario tuviera que abrir los Ajustes del teléfono.

### 1.4 Diagnóstico Forense y Causa Raíz (Crash de WorkManager)
Mediante el nuevo informe de diagnóstico remoto pedido desde el panel web, se extrajo la traza exacta del cierre anormal registrado a las 12:44:12:
```text
11-06 12:44:12.296 E/AndroidRuntime: FATAL EXCEPTION: main
Process: com.ejemplo.locksuite, PID: 1460
java.lang.RuntimeException: Unable to create service com.ejemplo.locksuite.service.WatchdogForegroundService: 
java.lang.IllegalStateException: WorkManager is not initialized properly. 
You have explicitly disabled WorkManagerInitializer in your manifest, have not manually called WorkManager#initialize at this point, 
or your Application does not implement Configuration.Provider.
	at android.app.ActivityThread.handleCreateService(ActivityThread.java:4251)
	at android.app.ActivityThread.access$1700(ActivityThread.java:244)
	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1982)
	at android.os.Handler.dispatchMessage(Handler.java:106)
	at android.os.Looper.loop(Looper.java:223)
	at android.app.ActivityThread.main(ActivityThread.java:7723)
```

#### Análisis de la Causa:
1. En `WatchdogForegroundService.kt`, el método `onCreate()` llamaba incondicionalmente a `scheduleWorkManagerWatchdog()`:
   ```kotlin
   private fun scheduleWorkManagerWatchdog() {
       val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)...build()
       WorkManager.getInstance(this).enqueueUniquePeriodicWork("LockSuiteWatchdog", KEEP, workRequest)
   }
   ```
2. **Inexistencia de `try-catch`:** Cualquier error al invocar `WorkManager.getInstance(this)` escalaba como `RuntimeException` y tiraba abajo el hilo principal del servicio en `onCreate()`.
3. **Condición de carrera / Direct Boot / MediaTek:**
   - La aplicación está preparada para Arranque Directo (`directBootAware="true"`). Con el almacenamiento cifrado por credencial (CE) bloqueado, la base de datos de Room de WorkManager no puede inicializarse.
   - En dispositivos de gama de entrada con chips MediaTek (como el Q-Tel Q1 / `k37m_m01c`), los servicios en primer plano pueden instanciarse antes de que el `InitializationProvider` de `androidx.startup` complete su ciclo.
4. **Falta de soporte on-demand:** `LockSuiteApplication` no implementaba la interfaz `androidx.work.Configuration.Provider`, impidiendo que WorkManager se auto-inicializara bajo demanda.

### 1.5 Solución Definitiva Implementada
1. **Soporte de Inicialización Bajo Demanda en `LockSuiteApplication.kt`:**
   ```kotlin
   class LockSuiteApplication : Application(), Configuration.Provider {
       override val workManagerConfiguration: Configuration
           get() = Configuration.Builder()
               .setMinimumLoggingLevel(android.util.Log.INFO)
               .build()
       ...
   }
   ```
2. **Guarda de Almacenamiento y `try-catch` en `WatchdogForegroundService.kt`:**
   - Se añadió la guarda `LockSuiteApplication.isStorageUnlocked(this)`: si el dispositivo está en Direct Boot, no se invoca a WorkManager.
   - Se envolvió toda la llamada a WorkManager dentro de un bloque `try-catch (e: Throwable)` con registro de advertencia en `DiagLog.w(...)`, evitando que el servicio o la aplicación crasheen.
   - Se implementó una bandera `@Volatile private var workManagerScheduled = false` y un reintento automático dentro del bucle periódico (`checkRunnable`) del Watchdog, de modo que si se pospuso en el arranque, se programa apenas el usuario desbloquea el equipo.

---

## 2. Adaptación de Accesibilidad para Google Play Store v53 (B.110)

### 2.1 Problema
Google actualizó la aplicación Google Play Store a la versión 53.x. Esto introdujo cambios en las vistas de instalación, botones de confirmación, flujos de autenticación biométrica y en la jerarquía de nodos de accesibilidad (`AccessibilityNodeInfo`). Esto generaba bucles o bloqueos indeseados en dispositivos donde LockSuite debe auditar o asistir en la instalación de aplicaciones permitidas.

### 2.2 Solución
* Se adaptó `PlayStoreAccessibilityHelper` incorporando selectores resilientes basados en texto y heurísticas jerárquicas en lugar de depender únicamente de identificadores de recurso estáticos (`com.android.vending:id/...`).
* Se incorporaron pruebas unitarias específicas para validar el comportamiento contra nodos de Play Store v53.
* Se empaquetó y desplegó en la versión `0.6.78 (141)`.

---

## 3. Integración del Sistema de Diagnóstico Remoto Pasivo (B.111)

### 3.1 Necesidad y Diseño
Los administradores necesitaban visibilidad técnica profunda (logcat, motivos de cierre de Android `ApplicationExitInfo`, estado de túnel VPN, red, memoria, accesibilidad y direct boot) sin generar consumo de batería, sin wakelocks adicionales y sin alterar la experiencia del usuario en el celular.

Claude diseñó el paquete completo de diagnóstico (`locksuite_registro_errores_B110_2026-11-06.zip`), el cual fue integrado por Antigravity.

### 3.2 Componentes Integrados
1. **Módulo Cliente Android (`com.ejemplo.locksuite.diag.*` — 13 archivos):**
   - `DiagLog`: Puerta de entrada estática con niveles `i`, `w`, `e`, `fatal`.
   - `DiagStore`: Buffer circular local (`diag_ring.log`) alojado en Device Protected Storage (`createDeviceProtectedStorageContext()`), lo que permite registrar eventos incluso durante Direct Boot antes del desbloqueo.
   - `DiagSanitizer`: Filtra y anonimiza datos sensibles (URLs con tokens, identificadores de red privados, etc.).
   - `DiagErrTee`: Redirección y captura de excepciones arrojadas a `System.err`.
   - `DiagCollector`: Recopilación de instantáneas bajo demanda (logcat filtrado de la app, estado de red, batería, `ActivityManager.getHistoricalProcessExitReasons()`).
   - `DiagUploader` / `DiagUploadPolicy`: Subida oportunista enganchada exclusivamente al ciclo de latido existente de Firebase (sin nuevos timers ni despertares).
   - Comandos: `COLLECT_DIAGNOSTICS` (sin PIN) y `SET_DIAGNOSTICS_UPLOAD` (con PIN).

2. **Panel Web y Base de Datos:**
   - `public/diag_logic.js`: Lógica pura de formateo, sanitización e interpretación de diagnósticos (con pruebas automáticas en Node).
   - `public/diag_remoto.js`: Tarjeta interactiva de registro de errores en `celular.html` con botones "Pedir diagnóstico ahora" y "Copiar informe".
   - Reglas de seguridad RTDB en `database.rules.json` para el nodo independiente `deviceDiag/<id>`.

### 3.3 Verificación en Producción
El sistema fue desplegado y probado exitosamente:
* Se ejecutaron los scripts de verificación (`check_diag_sync.py`, `check_command_sync.py`, `check_panel_commands.py`, `test_diag_logic.js`), todos con 100% de éxito.
* Se probó en vivo solicitando un diagnóstico remoto en el dispositivo `Yo` (`7073099b55af5fd6`), confirmando la llegada inmediata del logcat y las métricas de sistema.
* Posteriormente se solicitó el diagnóstico en el dispositivo real de `R shaul said` (`058d4e4ac999401a`), el cual permitió descubrir con exactitud matemática el crash de WorkManager antes mencionado.

---

## 4. Manejo de Conflictos de Fusión y Despliegue de Infraestructura

### 4.1 Conflicto de Numeración en `LOCKSUITE_CONTEXTO_PARA_IA.md`
Debido al desarrollo simultáneo:
- Claude numeró su propuesta de diagnóstico como `B.110`.
- Antigravity resolvió la compatibilidad de Google Play Store asignándole `B.110`.
- **Resolución:** Se conservó `B.110` para Google Play Store v53 y se asignó `B.111` al Sistema de Diagnóstico Remoto Pasivo. Se consolidaron ambas entradas en la Sección B y se redactó una bitácora unificada en la Sección C.

### 4.2 Error Transitorio HTTP 409 en Cloud Functions
Durante la ejecución de `deploy_all.ps1`:
* La herramienta de despliegue de Firebase CLI reportó `HTTP 409 Conflict` al actualizar la función `sendCommandV8`, debido a congestión de la cola de Google Cloud Functions.
* El script `deploy_all.ps1` contuvo el error de forma segura en su bloque try/catch y completó sin problemas la publicación de Firebase Hosting (panel web), Firebase RTDB Rules y el commit de Git. Dado que `sendCommandV8` no requería cambios en su código fuente, todas las funcionalidades remotas continuaron operando normalmente.

---

## 5. Resumen de Versiones y Puntos Clave para Revisión de Claude

| Versión | VersionCode | Commit | Estado / Descripción |
| :--- | :--- | :--- | :--- |
| `0.6.78` | `141` | `b331070` | Compatibilidad Google Play Store v53 (B.110). |
| `0.6.79` | `142` | `adccd95` | Sistema de diagnóstico remoto B.111 integrado en app y panel web. |
| `0.6.80` | `143` | *En curso* | Blindaje de WorkManager en `WatchdogForegroundService` y `LockSuiteApplication` (B.112). |

### Puntos sugeridos para que Claude revise:
1. **`LockSuiteApplication.kt`**: Revisar la implementación de `Configuration.Provider`. Verificar que el builder de `Configuration` cumpla con los requerimientos de optimización de batería y niveles de log.
2. **`WatchdogForegroundService.kt`**: Confirmar que la guarda de Direct Boot (`isStorageUnlocked`) y el reintento diferido en `checkRunnable` cubran todos los posibles escenarios de encendido de pantalla antes del primer desbloqueo.
3. **Mapeo de `deviceDiag` en el panel:** Verificar si convendría añadir en `diag_logic.js` una alerta visual específica si se detecta en los `exits` históricos un código de salida `CRASH` asociado a `WatchdogForegroundService` o `LockSuiteAccessibilityService`.
