#!/usr/bin/env bash
# Verificador de tipos de LockSuite SIN Gradle (29/10/2026, B.101).
#
# Compila (solo para chequear tipos, no deja nada instalable) todo el Kotlin del módulo `app`
# contra el android.jar REAL de la API 36 más unos stubs mínimos de las librerías que no se pueden
# bajar sin red (Firebase, WorkManager, androidx…). Si algo no cierra (una función que no existe,
# un tipo equivocado, un `when` incompleto) lo dice con archivo:línea, igual que Gradle.
#
# Uso:    bash tools/ia_contenedor/typecheck/typecheck.sh
# Salida: líneas "archivo:línea:col: error: …" y al final «TIPOS: N errores» (código de salida 1 si N>0).
# Tarda ~45 s.
#
# Qué NO cubre (ver README.md): los archivos de Jetpack Compose (todo lo de `/ui/`, salvo
# `ui/playstore/` que son Views comunes), porque Compose no se puede bajar sin red. Eso lo compila
# solo Gradle en la PC.
#
# Variables opcionales: FILES_LIST=<archivo con una ruta .kt por línea> para chequear otro conjunto.
set -u
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh" || exit $?

OUT="$TC_WORK/tc-out"
STUBS_OUT="$TC_WORK/stubs-out"
GEN="$TC_WORK/gen"
rm -rf "$OUT" "$STUBS_OUT" "$GEN"; mkdir -p "$OUT" "$STUBS_OUT" "$GEN"

# 1) R.java de mentira a partir de res/
python3 "$TC_HERE/gen_r.py" "$RES" "$GEN/R.java" || exit 3

# 2) stubs Java (los .kt de stubs entran directo a la compilación de Kotlin)
find "$TC_HERE/stubs" -name '*.java' > "$TC_WORK/stubs.list"
echo "$GEN/R.java" >> "$TC_WORK/stubs.list"
javac -nowarn -source 17 -target 17 -cp "$ANDROID_JAR" -d "$STUBS_OUT" @"$TC_WORK/stubs.list" 2>&1 \
  | tc_quiet | grep -v '^Note:\|warning:' | head -50

# 3) lista de fuentes
if [ -n "${FILES_LIST:-}" ]; then
  cp "$FILES_LIST" "$TC_WORK/files.list"
else
  # Todo lo que no es Compose: se saltea /ui/ (menos ui/playstore) y los detectores de IA
  # (dependen de ML Kit / TFLite; los reemplaza stubs/svc/AIDetectors.kt).
  find "$SRC" -name '*.kt' | grep -v '/ui/' | sort > "$TC_WORK/files.list"
  find "$SRC/com/ejemplo/locksuite/ui/playstore" -name '*.kt' 2>/dev/null | sort >> "$TC_WORK/files.list"
  find "$TC_HERE/stubs" -name '*.kt' >> "$TC_WORK/files.list"
  grep -v 'QrLuminanceDecoder.kt\|AIFaceDetector.kt\|AIPersonDetector.kt' "$TC_WORK/files.list" > "$TC_WORK/files.tmp" \
    && mv "$TC_WORK/files.tmp" "$TC_WORK/files.list"
fi
echo "Chequeando $(wc -l < "$TC_WORK/files.list") archivos Kotlin…" >&2

# 4) compilar (solo errores; -nowarn calla los miles de avisos de APIs viejas)
LOG="$TC_WORK/typecheck.log"
java -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 \
  -Xskip-metadata-version-check -cp "$CP_STDLIB:$ANDROID_JAR:$STUBS_OUT" -d "$OUT" @"$TC_WORK/files.list" 2>&1 \
  | tc_quiet > "$LOG"

grep -E ': error: ' "$LOG" | sed "s#$REPO/##" | head -80
ERRS=$(grep -cE ': error: ' "$LOG")
echo "TIPOS: $ERRS errores (log completo en $LOG)"
[ "$ERRS" -eq 0 ]
