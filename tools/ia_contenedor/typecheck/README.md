# tools/ia_contenedor/typecheck — chequear tipos y correr tests SIN Gradle

**(29/10/2026, B.101.)** Para cuando una IA trabaja en un contenedor donde **Gradle no puede
bajar nada** (los hosts de Maven y del SDK de Android están bloqueados; `tools/ia_contenedor/README.md`
explica la receta completa cuando SÍ hay red). Esto NO reemplaza al build real: es una red de
seguridad barata para no entregar código que ni siquiera cierra los tipos.

| Script | Qué hace | Tarda |
|---|---|---|
| `typecheck.sh` | Compila (sin generar APK) todo el Kotlin de `app/` contra el `android.jar` real de la API 36 + stubs mínimos de Firebase/androidx. Imprime `archivo:línea: error: …` y `TIPOS: N errores`. | ~45 s |
| `run_unit_tests.sh` | Compila con el `kotlinc` de Gradle las clases Kotlin "puras" y corre sus tests con JUnit. 9 archivos de test, **173 pruebas**. | ~17 s |
| `gen_r.py` | Lo usa `typecheck.sh`: arma un `R.java` de mentira desde `app/src/main/res` (si agregás un drawable/layout/id, aparece solo). | — |
| `common.sh` | Detecta `android.jar` y los `.jar` del compilador de Kotlin. | — |
| `stubs/` | Firmas escritas a mano de lo que no se puede bajar (Firebase, WorkManager, androidx, las Activities de Compose). Si el código usa una API nueva de esas librerías y el chequeo se queja, **falta agregarla acá** (no es un bug del código). | — |

## Cómo se usa

```bash
bash tools/ia_contenedor/typecheck/typecheck.sh          # esperado: «TIPOS: 0 errores»
bash tools/ia_contenedor/typecheck/run_unit_tests.sh     # esperado: «OK (173 tests)»
bash tools/ia_contenedor/typecheck/run_unit_tests.sh PlayStoreCatalogTest   # solo una suite
```

Necesita: `java` 17+, `python3`, `git` y dos cosas que se detectan solas (o se indican):

* **`android.jar` de la API 36.** Se busca en `$ANDROID_JAR`, `$ANDROID_HOME/platforms/android-36/` y
  `$TC_WORK/aosp/android-36/`. Si no está: `TC_FETCH=1 bash …/typecheck.sh` lo baja con git de
  `github.com/Reginer/aosp-android-jar` (GitHub suele estar permitido aun sin Maven; son ~50 MB).
* **`.jar` del compilador de Kotlin 2.0.x.** Se toman de `/opt/gradle-*/lib` (el Gradle ya instalado trae
  `kotlin-compiler-embeddable`, `kotlin-stdlib`, `junit`, `hamcrest`). Otra ubicación: `KOTLIN_LIB=<carpeta>`.

`TC_WORK` (por omisión `/tmp/locksuite-typecheck`) es donde se deja todo lo generado; no ensucia el repo.
`FILES_LIST=<archivo>` (una ruta `.kt` por línea) chequea otro conjunto de archivos.

## Qué cubre y qué NO

**Cubre (tipos):** todo el Kotlin de `app/src/main/java` **salvo** lo de abajo — `mdm/`, `service/`,
`util/`, `worker/`, `receiver/`, `widget/`, `dns/`, `security/`, `LockSuiteApplication.kt` y
`ui/playstore/` (la pantalla de la Tienda Play, hecha con Views comunes).
Con eso se encuentran funciones que no existen, parámetros en el orden equivocado, `when` sin cubrir
todas las ramas, `val` que se reasigna, nulos sin chequear, etc.

**NO cubre:**
* **Todo lo de Jetpack Compose** (`ui/auth/`, `ui/dashboard/`, `ui/emergency/`, `ui/launcher/`,
  `ui/update/`, `ui/verification/`): Compose no se puede
  bajar sin red, y sus funciones `@Composable` necesitan el plugin del compilador de Compose. Esos
  archivos los compila solo Gradle en la PC. (A mano se les pasó un chequeo de **sintaxis**, no de tipos.)
* `QrLuminanceDecoder.kt`, `AIFaceDetector.kt`, `AIPersonDetector.kt` (dependen de ML Kit/TFLite; los
  reemplaza `stubs/svc/AIDetectors.kt`).
* **El comportamiento en un equipo real** (Accesibilidad, DPM, VPN, Play Store). Un «0 errores» acá solo
  dice que *compila*, nunca que *funciona*.
* Los tests `CommandMailboxTest`, `DnsPacketParserTest`, `NetworkForwarderTest` y `QrLuminanceDecoderTest`
  (necesitan más entorno Android): esos corren solo con `gradlew testDebugUnitTest`.

## Control negativo (para confiar en el «0 errores»)

Un archivo con `val x: Int = PlayStoreManager.noExiste(ctx)` agregado a la lista da
`error: unresolved reference 'noExiste'` y código de salida 1. Se probó al crear esta carpeta.

## Cuando hay Gradle de verdad

Usá `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` (o `assembleDebug`). Esta carpeta pasa a
ser innecesaria; no hace falta mantenerla al día más que cuando se la use.
