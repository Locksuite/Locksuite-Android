#!/usr/bin/env bash
# Corre con JUnit, SIN Gradle, los tests unitarios que se pueden compilar sin Android (29/10/2026, B.101).
#
# Compila con el kotlinc de Gradle las clases de Kotlin "puro" que cubren los tests y los ejecuta
# con JUnitCore. Cubre 11 de los 15 archivos de test del proyecto (204 pruebas):
#   PlayStoreCatalogTest (49), EnrollmentProfilesStoreModeTest (11), PlayStoreUiLogicTest (22),
#   UpdateRoutePlannerTest, UpdateStepLogTest, PolicyReconcilerTest, GoogleVerificationPolicyTest,
#   DialerCodeScanTest, AndroidAutoStubsTest (91 entre todas) y, desde B.104 (1/11/2026),
#   PinCloudSyncPolicyTest (18) y SignInGateTest (13).
# NO cubre CommandMailboxTest, DnsPacketParserTest, NetworkForwarderTest ni QrLuminanceDecoderTest
# (necesitan más del entorno Android): esos solo corren con `gradlew testDebugUnitTest`.
#
# Uso:    bash tools/ia_contenedor/typecheck/run_unit_tests.sh [NombreDeTest ...]
#         (sin argumentos corre las 11; con argumentos, solo las que se nombran, p. ej. PlayStoreCatalogTest)
# Código de salida: 0 si todo pasa.
set -u
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh" || exit $?

# Cada línea: <clase de test completa>|<fuentes bajo app/src/main/java/com/ejemplo/locksuite>|<test bajo app/src/test/java/com/ejemplo/locksuite>
SUITES=(
  "com.ejemplo.locksuite.mdm.PlayStoreCatalogTest|mdm/PlayStoreCatalog.kt|mdm/PlayStoreCatalogTest.kt"
  "com.ejemplo.locksuite.mdm.EnrollmentProfilesStoreModeTest|mdm/PlayStoreCatalog.kt mdm/EnrollmentProfiles.kt|mdm/EnrollmentProfilesStoreModeTest.kt"
  "com.ejemplo.locksuite.ui.playstore.PlayStoreUiLogicTest|mdm/PlayStoreCatalog.kt mdm/AppRequestManager.kt ui/playstore/PlayStoreText.kt ui/playstore/PlayStoreRows.kt|ui/playstore/PlayStoreUiLogicTest.kt"
  "com.ejemplo.locksuite.mdm.UpdateRoutePlannerTest|mdm/UpdateRoutePlanner.kt|mdm/UpdateRoutePlannerTest.kt"
  "com.ejemplo.locksuite.mdm.UpdateStepLogTest|mdm/UpdateStepLog.kt|mdm/UpdateStepLogTest.kt"
  "com.ejemplo.locksuite.mdm.PolicyReconcilerTest|mdm/PolicyReconciler.kt|mdm/PolicyReconcilerTest.kt"
  "com.ejemplo.locksuite.mdm.GoogleVerificationPolicyTest|mdm/GoogleVerificationPolicy.kt|mdm/GoogleVerificationPolicyTest.kt"
  "com.ejemplo.locksuite.mdm.DialerCodeScanTest|mdm/DialerCodeScan.kt|mdm/DialerCodeScanTest.kt"
  "com.ejemplo.locksuite.mdm.AndroidAutoStubsTest|mdm/AndroidAutoStubs.kt|mdm/AndroidAutoStubsTest.kt"
  "com.ejemplo.locksuite.mdm.PinCloudSyncPolicyTest|mdm/PinCloudSyncPolicy.kt|mdm/PinCloudSyncPolicyTest.kt"
  "com.ejemplo.locksuite.util.SignInGateTest|util/SignInGate.kt|util/SignInGateTest.kt"
  "com.ejemplo.locksuite.provisioning.MultimediaProvisioningTest|provisioning/adb/AdbProtocol.kt provisioning/adb/AdbFrameStream.kt provisioning/adb/AdbAuthKey.kt provisioning/adb/AccountAuthenticatorGateScript.kt|provisioning/MultimediaProvisioningTest.kt"
)

declare -A SEEN=()
FILES=(); CLASSES=()
for line in "${SUITES[@]}"; do
  IFS='|' read -r cls srcs tst <<< "$line"
  if [ "$#" -gt 0 ]; then
    keep=0; for a in "$@"; do [ "${cls##*.}" = "$a" ] && keep=1; done
    [ "$keep" -eq 1 ] || continue
  fi
  CLASSES+=("$cls")
  for s in $srcs; do
    f="$SRC/com/ejemplo/locksuite/$s"
    [ -n "${SEEN[$f]:-}" ] || { SEEN[$f]=1; FILES+=("$f"); }
  done
  FILES+=("$TESTS/com/ejemplo/locksuite/$tst")
done
[ "${#CLASSES[@]}" -gt 0 ] || { echo "Ningún test coincide con: $*" >&2; exit 2; }

OUT="$TC_WORK/unit-out"
rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$CP_STDLIB:$CP_JUNIT:$ANDROID_JAR" -d "$OUT" "${FILES[@]}" 2>&1 | tc_quiet
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "No compiló." >&2; exit 1; }

java -cp "$OUT:$CP_STDLIB:$CP_JUNIT:$ANDROID_JAR" org.junit.runner.JUnitCore "${CLASSES[@]}" 2>&1 | tc_quiet | tail -40
exit "${PIPESTATUS[0]}"
