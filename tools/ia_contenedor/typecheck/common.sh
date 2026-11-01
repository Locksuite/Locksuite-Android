# Detección de herramientas compartida por typecheck.sh y run_unit_tests.sh (se usa con `source`).
# No hace falta tocar nada si el entorno es el de siempre (/opt/gradle-*/lib + android.jar bajado de Reginer).
#
#   REPO         raíz del repo (se deduce de la ubicación de este archivo)
#   TC_WORK      carpeta de trabajo (por omisión $TMPDIR/locksuite-typecheck)
#   ANDROID_JAR  android.jar de la API 36 (por omisión se busca en el SDK y en $TC_WORK/aosp)
#   KOTLIN_LIB   carpeta con los .jar del compilador de Kotlin (por omisión /opt/gradle-*/lib)
#   TC_FETCH=1   si falta el android.jar, lo baja con git de github.com/Reginer/aosp-android-jar

TC_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${REPO:-$(cd "$TC_HERE/../../.." && pwd)}"
SRC="$REPO/app/src/main/java"
TESTS="$REPO/app/src/test/java"
RES="$REPO/app/src/main/res"
TC_WORK="${TC_WORK:-${TMPDIR:-/tmp}/locksuite-typecheck}"
mkdir -p "$TC_WORK"

# El proxy del contenedor a veces inyecta "Picked up JAVA_TOOL_OPTIONS…" en stderr: se filtra.
tc_quiet() { grep -v '^Picked up '; }

find_android_jar() {
  local c
  for c in "${ANDROID_JAR:-}" \
           "${ANDROID_HOME:-/nonexistent}/platforms/android-36/android.jar" \
           "${ANDROID_SDK_ROOT:-/nonexistent}/platforms/android-36/android.jar" \
           "$TC_WORK/aosp/android-36/android.jar"; do
    if [ -n "$c" ] && [ -f "$c" ]; then echo "$c"; return 0; fi
  done
  if [ "${TC_FETCH:-0}" = "1" ]; then
    echo "Bajando android.jar (API 36) con git…" >&2
    rm -rf "$TC_WORK/aosp"
    git clone --quiet --filter=blob:none --sparse https://github.com/Reginer/aosp-android-jar "$TC_WORK/aosp" >&2 \
      && git -C "$TC_WORK/aosp" sparse-checkout set android-36 >&2 \
      && [ -f "$TC_WORK/aosp/android-36/android.jar" ] && { echo "$TC_WORK/aosp/android-36/android.jar"; return 0; }
  fi
  return 1
}

ANDROID_JAR="$(find_android_jar)" || {
  cat >&2 <<MSG
No encuentro el android.jar de la API 36. Opciones:
  - exportar ANDROID_JAR=/ruta/al/android.jar
  - o correr con TC_FETCH=1 (lo baja con git de github.com/Reginer/aosp-android-jar)
  - o, a mano:
      git clone --filter=blob:none --sparse https://github.com/Reginer/aosp-android-jar "$TC_WORK/aosp"
      git -C "$TC_WORK/aosp" sparse-checkout set android-36
MSG
  return 2 2>/dev/null || exit 2
}

if [ -z "${KOTLIN_LIB:-}" ]; then
  for d in /opt/gradle-*/lib "$HOME"/.gradle/wrapper/dists/*/*/gradle-*/lib; do
    if ls "$d"/kotlin-compiler-embeddable-[0-9]*.jar >/dev/null 2>&1; then KOTLIN_LIB="$d"; break; fi
  done
fi
if [ -z "${KOTLIN_LIB:-}" ]; then
  echo "No encuentro los .jar del compilador de Kotlin. Exportá KOTLIN_LIB=<carpeta lib de un Gradle>." >&2
  return 2 2>/dev/null || exit 2
fi

kjar() { ls "$KOTLIN_LIB"/$1-[0-9]*.jar 2>/dev/null | head -1; }
CP_COMPILER="$(kjar kotlin-compiler-embeddable):$(kjar kotlin-stdlib):$(kjar kotlin-reflect):$(kjar kotlin-script-runtime):$(kjar kotlin-daemon-embeddable):$(kjar trove4j):$(kjar annotations):$(kjar kotlinx-coroutines-core-jvm)"
CP_STDLIB="$(kjar kotlin-stdlib):$(kjar kotlinx-coroutines-core-jvm)"
CP_JUNIT="$(kjar junit):$(kjar hamcrest-core)"
