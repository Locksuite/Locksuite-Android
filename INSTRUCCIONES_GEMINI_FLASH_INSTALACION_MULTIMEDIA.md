# Instrucciones Paso a Paso para Gemini Flash — Instalación Fácil en Multimedia de Autos (Sin PC)

> **Contexto:** Ingeniería inversa completa de la función **"התקנה קלה למערכות מולטימדיה" (Instalación fácil para sistemas multimedia)** de **Switch MDM v0.11.3 (`com.secureguard.mdm`)**, extraída del repositorio `https://github.com/imreykodesh/secureguardmdm-private` (issues `#163`, `#210`, `#228`, `#238`) y del APK oficial de producción (`secureguard.apk`, `classes5.dex`).
>
> **Objetivo para Gemini Flash:** Seguir estos pasos **al pie de la letra**, sin omitir ni simplificar ninguna clase ni método, compilar con Gradle, correr los tests unitarios y dejar todo funcionando y commiteado.

---

## 1. Resumen Técnico de Qué Hace Exactamente Esta Función

En una pantalla multimedia de auto (TS10, TS18, Topway, FYT, etc.) o en un celular ya en uso:
1. **No hay cómo enchufar una PC por USB** (los puertos USB de la pantalla son Host para pendrive, no periféricos ADB).
2. **Ya hay cuentas registradas** (Google, fabricante, WhatsApp), por lo que `dpm set-device-owner` normalmente falla con `IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device`.

La arquitectura resuelve ambos problemas **100% desde adentro de la propia app LockSuite**:

1. **Cliente ADB TCP nativo embebido (`127.0.0.1:5555`) y soporte Root (`su`):**
   - Implementa el protocolo binario ADB (`A_CNXN`, `A_AUTH`, `A_OPEN`, `A_OKAY`, `A_WRTE`, `A_CLSE`) sobre un socket TCP local a `127.0.0.1:5555` (`AdbProtocol.kt`, `AdbFrameStream.kt`, `LocalAdbClient.kt`).
   - Genera y almacena un par de claves RSA de 2048 bits en `context.noBackupFilesDir` y exporta la clave pública en la estructura binaria de 524 bytes Little-Endian propia de `adbd` (`AdbAuthKey.kt`).
   - Si el equipo tiene `su` preinstalado (frecuente en multimedias chinas), lo usa directamente sin siquiera pedir cartel de ADB (`LocalProvisioningProbe.kt`).
   - Si no tiene `su`, ejecuta `setprop service.adb.tcp.port 5555` (`ClassicAdbChannelOpener.kt`) y guía al usuario para apagar y encender «Depuración por USB» en Opciones de Desarrollador, detectando automáticamente en cuanto el puerto `5555` abre y mostrando el aviso para aceptar el cartel de autorización RSA en pantalla.

2. **Compuerta de Cuentas (`AccountAuthenticatorGateScript.kt`):**
   - Antes de ejecutar `dpm set-device-owner`, corre una secuencia en `sh` (como `shell` vía ADB local o `root`) que:
     1. Verifica que haya un solo usuario en el sistema (`pm list users`).
     2. Consulta todos los servicios `android.accounts.AccountAuthenticator` (`cmd package query-services -a android.accounts.AccountAuthenticator`).
     3. Guarda la lista de paquetes activos en `/data/local/tmp/.locksuite_authgate` y los congela temporalmente con `pm disable-user --user 0 <pkg>` (excepto `com.ejemplo.locksuite`).
     4. Con los autenticadores congelados, `AccountManager` reporta 0 cuentas a `DevicePolicyManager`.
     5. Otorga `WRITE_SECURE_SETTINGS` y ejecuta `dpm set-device-owner com.ejemplo.locksuite/com.ejemplo.locksuite.receiver.DeviceAdminReceiver`.
     6. **Inmediatamente después (incluso si `dpm` falló)**, restaura todos los paquetes de `/data/local/tmp/.locksuite_authgate` con `pm enable --user 0 <pkg>` y verifica que ninguno haya quedado deshabilitado. Si alguno falló en re-habilitarse, guarda el estado en `SharedPreferences` y reintenta la restauración para que el usuario **jamás** pierda sus cuentas de Google o WhatsApp.

3. **Asistente UI Material Design 3 (`MultimediaProvisioningDialog.kt`):**
   - Accesible desde:
     - `SetupPinActivity.kt` (durante el alta inicial, antes o después de elegir el PIN, si todavía no es Device Owner).
     - `LoginActivity.kt` (botón visible cuando `!dpm.isDeviceOwnerApp(packageName)`).
     - `DashboardActivity.kt` (dentro de la tarjeta roja de advertencia cuando `!isDeviceOwner`).

---

## 2. Reglas de Ubicación de Archivos (¡MUY IMPORTANTE!)

- Los 8 archivos de lógica de aprovisionamiento van en `app/src/main/java/com/ejemplo/locksuite/provisioning/` y `.../provisioning/adb/`. **NO deben importar nada de Jetpack Compose**, porque `tools/ia_contenedor/typecheck/typecheck.sh` compila todo lo que está fuera de `/ui/` sin el plugin de Compose.
- El diálogo de Jetpack Compose **DEBE** ir en `app/src/main/java/com/ejemplo/locksuite/ui/provisioning/MultimediaProvisioningDialog.kt` (bajo `/ui/`), para que `typecheck.sh` lo ignore igual que al resto de pantallas Compose y Gradle lo compile normalmente.

---

## 3. Creación de Archivos Nuevos (Paso 1 a Paso 9)

### Paso 1: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/adb/AdbProtocol.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning.adb

import java.nio.charset.StandardCharsets

object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    const val VERSION = 0x01000001
    const val MAX_DATA = 4096

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    val CONNECT_PAYLOAD: ByteArray =
        "host::features=shell_v2,cmd\u0000".toByteArray(StandardCharsets.UTF_8)

    fun commandName(cmd: Int): String = when (cmd) {
        A_SYNC -> "SYNC"
        A_CNXN -> "CNXN"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_AUTH -> "AUTH"
        else -> "0x${Integer.toHexString(cmd)}"
    }

    fun payloadChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }
}
```

---

### Paso 2: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/adb/AdbFrameStream.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning.adb

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbFrameStream(
    private val input: InputStream,
    private val output: OutputStream
) : Closeable {

    data class Frame(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    @Synchronized
    @Throws(IOException::class)
    fun writeFrame(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(AdbProtocol.payloadChecksum(payload))
            .putInt(command.inv())
            .array()
        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    @Throws(IOException::class)
    fun readFrame(): Frame {
        val headerBytes = readFully(HEADER_BYTES)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        header.int // checksum
        header.int // magic

        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw IOException(
                "Tamaño de trama ADB inválido: $length para ${AdbProtocol.commandName(command)}"
            )
        }
        val payload = if (length == 0) ByteArray(0) else readFully(length)
        return Frame(command, arg0, arg1, payload)
    }

    @Throws(IOException::class)
    private fun readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) {
                throw EOFException("Fin de stream ADB tras $offset/$length bytes")
            }
            offset += read
        }
        return buffer
    }

    override fun close() {
        try {
            input.close()
        } catch (_: Exception) {
        }
        try {
            output.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val HEADER_BYTES = 24
        const val MAX_PAYLOAD_BYTES = 262144
    }
}
```

---

### Paso 3: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/adb/AdbAuthKey.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

class AdbAuthKey private constructor(private val keyPair: KeyPair) {

    fun sign(challenge: ByteArray): ByteArray {
        require(challenge.size == 20) {
            "El desafío ADB SHA-1 debe tener 20 bytes, recibido: ${challenge.size}"
        }
        val signedBlock = ByteArray(KEY_LENGTH_BYTES)
        signedBlock[0] = 0
        signedBlock[1] = 1
        val padEnd = (KEY_LENGTH_BYTES - DIGEST_HEADER.size) - challenge.size - 1
        for (i in 2 until padEnd) {
            signedBlock[i] = 0xFF.toByte()
        }
        signedBlock[padEnd] = 0
        System.arraycopy(DIGEST_HEADER, 0, signedBlock, padEnd + 1, DIGEST_HEADER.size)
        System.arraycopy(
            challenge,
            0,
            signedBlock,
            padEnd + 1 + DIGEST_HEADER.size,
            challenge.size
        )

        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(signedBlock)
    }

    fun encodePublicKey(): ByteArray {
        val rsaPub = keyPair.public as RSAPublicKey
        val structBytes = encodeAndroidRsaPublicKey(rsaPub.modulus, rsaPub.publicExponent)
        val b64 = Base64.encodeToString(structBytes, Base64.NO_WRAP)
        return "$b64 locksuite@device\u0000".toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val KEY_LENGTH_BITS = 2048
        private const val KEY_LENGTH_BYTES = 256
        private const val KEY_LENGTH_WORDS = 64
        private const val PRIVATE_KEY_FILE = "adb_local_private.key"
        private const val PUBLIC_KEY_FILE = "adb_local_public.key"

        private val DIGEST_HEADER = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        fun loadOrCreate(context: Context): AdbAuthKey {
            val dir = context.noBackupFilesDir
            val privFile = File(dir, PRIVATE_KEY_FILE)
            val pubFile = File(dir, PUBLIC_KEY_FILE)
            if (privFile.exists() && pubFile.exists()) {
                try {
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                    return AdbAuthKey(KeyPair(pub, priv))
                } catch (_: Exception) {
                    privFile.delete()
                    pubFile.delete()
                }
            }
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(KEY_LENGTH_BITS)
            val kp = kpg.generateKeyPair()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
            return AdbAuthKey(kp)
        }

        internal fun encodeAndroidRsaPublicKey(
            modulus: BigInteger,
            exponent: BigInteger
        ): ByteArray {
            val r32 = BigInteger.ONE.shiftLeft(32)
            val r = BigInteger.ONE.shiftLeft(KEY_LENGTH_BITS)
            val rr = r.multiply(r).mod(modulus)
            val n0inv = modulus.mod(r32).modInverse(r32).negate().mod(r32)

            val nWords = IntArray(KEY_LENGTH_WORDS)
            val rrWords = IntArray(KEY_LENGTH_WORDS)

            var tempN = modulus
            var tempRr = rr
            for (i in 0 until KEY_LENGTH_WORDS) {
                val nDivRem = tempN.divideAndRemainder(r32)
                tempN = nDivRem[0]
                nWords[i] = nDivRem[1].toInt()

                val rrDivRem = tempRr.divideAndRemainder(r32)
                tempRr = rrDivRem[0]
                rrWords[i] = rrDivRem[1].toInt()
            }

            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(KEY_LENGTH_WORDS)
            buf.putInt(n0inv.toInt())
            for (w in nWords) buf.putInt(w)
            for (w in rrWords) buf.putInt(w)
            buf.putInt(exponent.toInt())
            return buf.array()
        }
    }
}
```

---

### Paso 4: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/adb/AccountAuthenticatorGateScript.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning.adb

object AccountAuthenticatorGateScript {
    const val SNAPSHOT_PATH = "/data/local/tmp/.locksuite_authgate"
    const val SNAPSHOT_TMP_PATH = "/data/local/tmp/.locksuite_authgate.tmp"

    const val MARKER_RECOVERED_PREFIX = "__LS_RECOVERED__:"
    const val MARKER_DISABLED_PREFIX = "__LS_DISABLED__:"
    const val MARKER_ENABLED_PREFIX = "__LS_ENABLED__:"
    const val MARKER_RESTORE_FAILED_PREFIX = "__LS_RESTORE_FAILED__:"
    const val MARKER_DISABLE_ABORTED = "__LS_DISABLE_ABORTED__"
    const val MARKER_MULTI_USER_ABORT = "__LS_MULTI_USER_ABORT__:"
    const val MARKER_DPM_EXIT_PREFIX = "__LS_DPM_EXIT__:"

    data class ParsedOutput(
        val dpmOutput: String,
        val dpmExitCode: Int?,
        val disabledPackages: List<String>,
        val reenabledPackages: List<String>,
        val failedToReenablePackages: List<String>,
        val recoveredFromPreviousRun: List<String>,
        val disableAbortedBeforeDpm: Boolean,
        val multiUserCount: Int?
    ) {
        val restorationClean: Boolean
            get() = failedToReenablePackages.isEmpty() &&
                disabledPackages.all { reenabledPackages.contains(it) }
    }

    private fun validateComponent(packageName: String, adminComponent: String) {
        require(packageName.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
            "Nombre de paquete inválido: $packageName"
        }
        require(adminComponent.matches(Regex("^[a-zA-Z0-9_./\\$]+$"))) {
            "Componente admin inválido: $adminComponent"
        }
    }

    fun buildActivation(packageName: String, adminComponent: String): String {
        validateComponent(packageName, adminComponent)
        return buildString {
            append("SNAPSHOT='").append(SNAPSHOT_PATH).append("'; ")
            append("SNAPSHOT_TMP='").append(SNAPSHOT_TMP_PATH).append("'; ")
            append("SELF='").append(packageName).append("'; ")
            append("ADMIN='").append(adminComponent).append("'; ")
            append(
                "restore_snapshot() { " +
                    "MODE=\"$1\"; " +
                    "ANY_FAIL=0; " +
                    "if [ -f \"\$SNAPSHOT\" ]; then " +
                    "while IFS= read -r p; do " +
                    "[ -z \"\$p\" ] && continue; " +
                    "pm enable --user 0 \"\$p\" >/dev/null 2>&1; " +
                    "if ! pm list packages --user 0 -d 2>/dev/null | grep -Fqx \"package:\$p\"; then " +
                    "if [ \"\$MODE\" = 'recover' ]; then echo '"
            ).append(MARKER_RECOVERED_PREFIX).append("\$p'; else echo '")
                .append(MARKER_ENABLED_PREFIX).append("\$p'; fi; " +
                    "else echo '").append(MARKER_RESTORE_FAILED_PREFIX)
                .append("\$p'; ANY_FAIL=1; fi; " +
                    "done < \"\$SNAPSHOT\"; " +
                    "[ \"\$ANY_FAIL\" -eq 0 ] && rm -f \"\$SNAPSHOT\"; " +
                    "fi; return \"\$ANY_FAIL\"; }; ")
            append("restore_snapshot recover; ")
            append("if [ -f \"\$SNAPSHOT\" ]; then echo '")
                .append(MARKER_DISABLE_ABORTED).append("'; exit 70; fi; ")
            append(
                "USERS=\$(pm list users 2>/dev/null | grep -c 'UserInfo{'); " +
                    "if [ -n \"\$USERS\" ] && [ \"\$USERS\" -gt 1 ]; then " +
                    "echo '"
            ).append(MARKER_MULTI_USER_ABORT).append("\$USERS'; exit 72; fi; ")
            append("rm -f \"\$SNAPSHOT_TMP\"; ")
            append(
                "PKGS=\$(cmd package query-services -a android.accounts.AccountAuthenticator 2>/dev/null " +
                    "| sed -n 's/.*packageName=\\([^ ,}]*\\).*/\\1/p' | sort -u); "
            )
            append(
                "DISABLE_FAIL=0; " +
                    "for p in \$PKGS; do " +
                    "[ -z \"\$p\" ] && continue; " +
                    "[ \"\$p\" = \"\$SELF\" ] && continue; " +
                    "if pm list packages --user 0 -e 2>/dev/null | grep -Fqx \"package:\$p\"; then " +
                    "echo \"\$p\" >> \"\$SNAPSHOT_TMP\"; " +
                    "cp \"\$SNAPSHOT_TMP\" \"\$SNAPSHOT\" 2>/dev/null || true; " +
                    "if pm disable-user --user 0 \"\$p\" >/dev/null 2>&1; then " +
                    "echo '"
            ).append(MARKER_DISABLED_PREFIX).append("\$p'; " +
                    "else DISABLE_FAIL=1; break; fi; " +
                    "fi; done; " +
                    "rm -f \"\$SNAPSHOT_TMP\"; ")
            append(
                "if [ \"\$DISABLE_FAIL\" -ne 0 ]; then " +
                    "echo '"
            ).append(MARKER_DISABLE_ABORTED).append("'; restore_snapshot active; exit 71; fi; ")
            append(
                "pm grant \"\$SELF\" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true; " +
                    "appops set \"\$SELF\" 10008 allow >/dev/null 2>&1 || true; " +
                    "dpm set-device-owner \"\$ADMIN\" 2>&1; " +
                    "DPM_RC=\$?; " +
                    "echo '"
            ).append(MARKER_DPM_EXIT_PREFIX).append("\$DPM_RC'; " +
                    "restore_snapshot active; " +
                    "exit \"\$DPM_RC\"")
        }
    }

    fun buildRestoration(extraPackages: Collection<String> = emptyList()): String {
        val validExtras = extraPackages.filter { it.matches(Regex("^[a-zA-Z0-9_.]+$")) }
        return buildString {
            append("SNAPSHOT='").append(SNAPSHOT_PATH).append("'; ")
            append("ANY_FAIL=0; ")
            append("enable_pkg() { p=\"\$1\"; [ -z \"\$p\" ] && return 0; ")
            append("pm enable --user 0 \"\$p\" >/dev/null 2>&1; ")
            append("if ! pm list packages --user 0 -d 2>/dev/null | grep -Fqx \"package:\$p\"; then ")
            append("echo '").append(MARKER_ENABLED_PREFIX).append("\$p'; ")
            append("else echo '").append(MARKER_RESTORE_FAILED_PREFIX)
                .append("\$p'; ANY_FAIL=1; fi; }; ")
            append(
                "if [ -f \"\$SNAPSHOT\" ]; then " +
                    "while IFS= read -r p; do enable_pkg \"\$p\"; done < \"\$SNAPSHOT\"; " +
                    "fi; "
            )
            for (pkg in validExtras) {
                append("enable_pkg '").append(pkg).append("'; ")
            }
            append("[ \"\$ANY_FAIL\" -eq 0 ] && rm -f \"\$SNAPSHOT\"; ")
            append("exit \"\$ANY_FAIL\"")
        }
    }

    fun parse(rawOutput: String): ParsedOutput {
        val disabled = mutableListOf<String>()
        val enabled = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        val dpmLines = mutableListOf<String>()
        var dpmExit: Int? = null
        var disableAborted = false
        var multiUserCount: Int? = null

        for (rawLine in rawOutput.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith(MARKER_RECOVERED_PREFIX) -> {
                    val pkg = line.removePrefix(MARKER_RECOVERED_PREFIX).trim()
                    if (pkg.isNotEmpty()) recovered.add(pkg)
                }
                line.startsWith(MARKER_DISABLED_PREFIX) -> {
                    val pkg = line.removePrefix(MARKER_DISABLED_PREFIX).trim()
                    if (pkg.isNotEmpty()) disabled.add(pkg)
                }
                line.startsWith(MARKER_ENABLED_PREFIX) -> {
                    val pkg = line.removePrefix(MARKER_ENABLED_PREFIX).trim()
                    if (pkg.isNotEmpty()) enabled.add(pkg)
                }
                line.startsWith(MARKER_RESTORE_FAILED_PREFIX) -> {
                    val pkg = line.removePrefix(MARKER_RESTORE_FAILED_PREFIX).trim()
                    if (pkg.isNotEmpty()) failed.add(pkg)
                }
                line == MARKER_DISABLE_ABORTED -> {
                    disableAborted = true
                }
                line.startsWith(MARKER_MULTI_USER_ABORT) -> {
                    multiUserCount = line.removePrefix(MARKER_MULTI_USER_ABORT).trim().toIntOrNull()
                }
                line.startsWith(MARKER_DPM_EXIT_PREFIX) -> {
                    dpmExit = line.removePrefix(MARKER_DPM_EXIT_PREFIX).trim().toIntOrNull()
                }
                line.isNotEmpty() -> {
                    dpmLines.add(rawLine)
                }
            }
        }

        return ParsedOutput(
            dpmOutput = dpmLines.joinToString("\n").trim(),
            dpmExitCode = dpmExit,
            disabledPackages = disabled.distinct(),
            reenabledPackages = enabled.distinct(),
            failedToReenablePackages = failed.distinct(),
            recoveredFromPreviousRun = recovered.distinct(),
            disableAbortedBeforeDpm = disableAborted,
            multiUserCount = multiUserCount
        )
    }
}
```

---

### Paso 5: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/adb/LocalAdbClient.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class LocalAdbClient(
    private val context: Context,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT
) {

    fun interface Listener {
        fun onAuthorizationPromptShown()
    }

    suspend fun executeShell(
        command: String,
        connectTimeoutMs: Int = 3_000,
        authTimeoutMs: Int = 60_000,
        commandTimeoutMs: Int = 30_000,
        listener: Listener? = null
    ): String = withContext(Dispatchers.IO) {
        val authKey = AdbAuthKey.loadOrCreate(context)
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = authTimeoutMs

            AdbFrameStream(socket.getInputStream(), socket.getOutputStream()).use { stream ->
                handshake(stream, socket, authKey, authTimeoutMs, listener)
                socket.soTimeout = commandTimeoutMs
                runShellStream(stream, command)
            }
        }
    }

    @Throws(IOException::class)
    private fun handshake(
        stream: AdbFrameStream,
        socket: Socket,
        authKey: AdbAuthKey,
        authTimeoutMs: Int,
        listener: Listener?
    ) {
        stream.writeFrame(
            AdbProtocol.A_CNXN,
            AdbProtocol.VERSION,
            AdbProtocol.MAX_DATA,
            AdbProtocol.CONNECT_PAYLOAD
        )

        var sentSignature = false
        var sentPublicKey = false

        while (true) {
            val frame = stream.readFrame()
            when (frame.command) {
                AdbProtocol.A_CNXN -> return
                AdbProtocol.A_AUTH -> {
                    if (frame.arg0 != AdbProtocol.AUTH_TOKEN) {
                        throw IOException("Tipo de desafío ADB AUTH inesperado: ${frame.arg0}")
                    }
                    when {
                        !sentSignature -> {
                            sentSignature = true
                            val sig = authKey.sign(frame.payload)
                            stream.writeFrame(
                                AdbProtocol.A_AUTH,
                                AdbProtocol.AUTH_SIGNATURE,
                                0,
                                sig
                            )
                        }
                        !sentPublicKey -> {
                            sentPublicKey = true
                            listener?.onAuthorizationPromptShown()
                            socket.soTimeout = authTimeoutMs
                            stream.writeFrame(
                                AdbProtocol.A_AUTH,
                                AdbProtocol.AUTH_RSAPUBLICKEY,
                                0,
                                authKey.encodePublicKey()
                            )
                        }
                        else -> {
                            throw IOException(
                                "El demonio ADB rechazó la clave pública RSA tras enviarla."
                            )
                        }
                    }
                }
                else -> {
                    throw IOException(
                        "Trama inesperada durante el handshake ADB: ${AdbProtocol.commandName(frame.command)}"
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun runShellStream(stream: AdbFrameStream, command: String): String {
        val localId = 1
        val openPayload = "shell:$command\u0000".toByteArray(StandardCharsets.UTF_8)
        stream.writeFrame(AdbProtocol.A_OPEN, localId, 0, openPayload)

        var remoteId = 0
        val out = ByteArrayOutputStream()

        while (true) {
            val frame = stream.readFrame()
            when (frame.command) {
                AdbProtocol.A_OKAY -> {
                    if (remoteId == 0) {
                        remoteId = frame.arg0
                    }
                }
                AdbProtocol.A_WRTE -> {
                    if (remoteId == 0) {
                        remoteId = frame.arg0
                    }
                    out.write(frame.payload)
                    stream.writeFrame(AdbProtocol.A_OKAY, localId, remoteId)
                }
                AdbProtocol.A_CLSE -> {
                    if (remoteId != 0) {
                        try {
                            stream.writeFrame(AdbProtocol.A_CLSE, localId, remoteId)
                        } catch (_: Exception) {
                        }
                    }
                    return out.toString(StandardCharsets.UTF_8.name())
                }
                else -> {
                    // Ignorar otras tramas mientras corre el comando
                }
            }
        }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5555
    }
}
```

---

### Paso 6: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/adb/ClassicAdbChannelOpener.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning.adb

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object ClassicAdbChannelOpener {
    const val DEFAULT_PORT = 5555

    private val PORT_PROPERTIES = listOf(
        "service.adb.tcp.port",
        "persist.adb.tcp.port",
        "persist.sys.adb.tcp.port",
        "persist.vendor.adb.tcp.port",
        "ro.adb.tcp.port",
        "persist.service.adb.tcp.port"
    )

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su"
    )

    data class Snapshot(
        val openPort: Int?,
        val configuredPort: Int?,
        val configuredProperty: String?,
        val adbEnabledInSettings: Boolean,
        val suBinaryPresent: Boolean
    )

    sealed class PrepOutcome {
        data class AlreadyOpen(val port: Int) : PrepOutcome()
        data class OpenedViaRoot(val port: Int) : PrepOutcome()
        data class WaitingForUsbDebuggingToggle(
            val port: Int,
            val propertyVerified: Boolean
        ) : PrepOutcome()
    }

    suspend fun inspect(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val adbEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }

        var configuredPort: Int? = null
        var configuredProp: String? = null
        for (prop in PORT_PROPERTIES) {
            val v = readSystemProperty(prop)?.trim()?.toIntOrNull()
            if (v != null && v in 1..65535) {
                configuredPort = v
                configuredProp = prop
                break
            }
        }

        val candidatePorts = linkedSetOf<Int>()
        if (configuredPort != null) candidatePorts.add(configuredPort)
        candidatePorts.add(DEFAULT_PORT)

        var openPort: Int? = null
        for (p in candidatePorts) {
            if (isLocalPortOpen(p, 350)) {
                openPort = p
                break
            }
        }

        val suPresent = SU_PATHS.any { File(it).exists() }

        Snapshot(
            openPort = openPort,
            configuredPort = configuredPort,
            configuredProperty = configuredProp,
            adbEnabledInSettings = adbEnabled,
            suBinaryPresent = suPresent
        )
    }

    suspend fun prepareClassicPort(
        context: Context,
        desiredPort: Int = DEFAULT_PORT
    ): PrepOutcome = withContext(Dispatchers.IO) {
        val snap = inspect(context)
        if (snap.openPort != null) {
            return@withContext PrepOutcome.AlreadyOpen(snap.openPort)
        }

        // Intentar abrir adbd TCP con root si existe binario su
        if (snap.suBinaryPresent) {
            try {
                runCommand(
                    arrayOf(
                        "su",
                        "-c",
                        "setprop service.adb.tcp.port $desiredPort; stop adbd; start adbd"
                    ),
                    4_000L
                )
                delay(500)
                if (isLocalPortOpen(desiredPort, 500)) {
                    return@withContext PrepOutcome.OpenedViaRoot(desiredPort)
                }
            } catch (_: Exception) {
            }
        }

        // Intentar setprop sin root (algunas multimedias permiten setprop service.adb.tcp.port)
        try {
            runCommand(arrayOf("setprop", "service.adb.tcp.port", desiredPort.toString()), 1_500L)
        } catch (_: Exception) {
        }

        val afterProp = readSystemProperty("service.adb.tcp.port")?.trim()?.toIntOrNull()
        PrepOutcome.WaitingForUsbDebuggingToggle(
            port = desiredPort,
            propertyVerified = (afterProp == desiredPort)
        )
    }

    suspend fun waitForLoopbackPort(
        port: Int = DEFAULT_PORT,
        timeoutMs: Long = 300_000L,
        pollIntervalMs: Long = 1_500L
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalPortOpen(port, 400)) {
                return@withContext true
            }
            delay(pollIntervalMs)
        }
        false
    }

    fun isLocalPortOpen(port: Int, timeoutMs: Int = 350): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun readSystemProperty(key: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            val v = m.invoke(null, key, "") as? String
            v?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            try {
                runCommand(arrayOf("getprop", key), 1_000L).trim().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun runCommand(cmd: Array<String>, timeoutMs: Long): String {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ""
        }
        return try {
            proc.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
    }
}
```

---

### Paso 7: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/LocalProvisioningProbe.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.ejemplo.locksuite.provisioning.adb.ClassicAdbChannelOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object LocalProvisioningProbe {

    enum class RecommendedPath {
        ALREADY_ACTIVE,
        ROOT_SU,
        LOCAL_ADB_READY,
        NEEDS_ADB_SETUP
    }

    data class Report(
        val isAlreadyDeviceOwner: Boolean,
        val rootAvailable: Boolean,
        val adbOpenPort: Int?,
        val adbConfiguredPort: Int?,
        val adbEnabledInSettings: Boolean
    ) {
        val recommendedPath: RecommendedPath
            get() = when {
                isAlreadyDeviceOwner -> RecommendedPath.ALREADY_ACTIVE
                rootAvailable -> RecommendedPath.ROOT_SU
                adbOpenPort != null -> RecommendedPath.LOCAL_ADB_READY
                else -> RecommendedPath.NEEDS_ADB_SETUP
            }
    }

    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isDo = dpm.isDeviceOwnerApp(context.packageName)
        val snap = ClassicAdbChannelOpener.inspect(context)
        val rootOk = if (snap.suBinaryPresent) testRootId() else false

        Report(
            isAlreadyDeviceOwner = isDo,
            rootAvailable = rootOk,
            adbOpenPort = snap.openPort,
            adbConfiguredPort = snap.configuredPort,
            adbEnabledInSettings = snap.adbEnabledInSettings
        )
    }

    private fun testRootId(): Boolean {
        return try {
            val proc = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(3_000L, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                val out = proc.inputStream.bufferedReader().readText()
                out.contains("uid=0")
            }
        } catch (_: Exception) {
            false
        }
    }
}
```

---

### Paso 8: Crear `app/src/main/java/com/ejemplo/locksuite/provisioning/MultimediaDeviceOwnerActivator.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.ejemplo.locksuite.mdm.EnrollmentProfiles
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.provisioning.adb.AccountAuthenticatorGateScript
import com.ejemplo.locksuite.provisioning.adb.ClassicAdbChannelOpener
import com.ejemplo.locksuite.provisioning.adb.LocalAdbClient
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object MultimediaDeviceOwnerActivator {
    private const val PREFS_NAME = "locksuite_multimedia_provisioning"
    private const val KEY_PENDING_RESTORE = "pending_restore_packages"

    sealed class Outcome {
        data class Activated(
            val channel: String,
            val temporarilyDisabledPackages: List<String>,
            val failedToReenablePackages: List<String>
        ) : Outcome()

        data class Failed(
            val reason: String,
            val technicalDetail: String = "",
            val failedToReenablePackages: List<String> = emptyList()
        ) : Outcome()
    }

    fun getPendingRestorePackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PENDING_RESTORE, emptySet()) ?: emptySet()
    }

    private fun savePendingRestorePackages(context: Context, packages: Collection<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (packages.isEmpty()) {
            prefs.edit().remove(KEY_PENDING_RESTORE).apply()
        } else {
            prefs.edit().putStringSet(KEY_PENDING_RESTORE, packages.toSet()).apply()
        }
    }

    suspend fun activate(
        context: Context,
        preferredAdbPort: Int? = null,
        onAdbAuthPrompt: (() -> Unit)? = null
    ): Outcome = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pkg = context.packageName
        val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java).flattenToString()

        if (dpm.isDeviceOwnerApp(pkg)) {
            applyDefaultPostActivationProtection(context)
            return@withContext Outcome.Activated(
                channel = "Ya activo",
                temporarilyDisabledPackages = emptyList(),
                failedToReenablePackages = emptyList()
            )
        }

        val probe = LocalProvisioningProbe.run(context)
        val script = AccountAuthenticatorGateScript.buildActivation(pkg, adminComponent)

        var rawOutput = ""
        var channelUsed = ""

        // 1. Intentar por Root (su) si está disponible
        if (probe.rootAvailable) {
            val rootOut = runRootShell(script)
            if (rootOut != null) {
                rawOutput = rootOut
                channelUsed = "Root (su)"
            }
        }

        // 2. Si no hubo Root o falló, intentar por ADB Local (127.0.0.1:puerto)
        if (channelUsed.isEmpty()) {
            val port = preferredAdbPort
                ?: probe.adbOpenPort
                ?: probe.adbConfiguredPort
                ?: ClassicAdbChannelOpener.DEFAULT_PORT

            if (!ClassicAdbChannelOpener.isLocalPortOpen(port, 500)) {
                return@withContext Outcome.Failed(
                    reason = "El puerto ADB local ($port) todavía no está abierto en el dispositivo.",
                    technicalDetail = "Active la Depuración por USB / ADB inalámbrico o use el botón de espera automática."
                )
            }

            try {
                val client = LocalAdbClient(context, "127.0.0.1", port)
                rawOutput = client.executeShell(script) {
                    onAdbAuthPrompt?.invoke()
                }
                channelUsed = "ADB Local (127.0.0.1:$port)"
            } catch (e: Exception) {
                return@withContext Outcome.Failed(
                    reason = "No se pudo conectar al canal ADB local en el puerto $port.",
                    technicalDetail = e.message ?: e.javaClass.simpleName
                )
            }
        }

        val parsed = AccountAuthenticatorGateScript.parse(rawOutput)

        // Si algún paquete de cuentas quedó pendiente de reactivar, reintentar de inmediato
        val stillDisabled = parsed.failedToReenablePackages.toMutableSet()
        for (p in parsed.disabledPackages) {
            if (!parsed.reenabledPackages.contains(p)) {
                stillDisabled.add(p)
            }
        }
        if (stillDisabled.isNotEmpty()) {
            savePendingRestorePackages(context, stillDisabled)
            val retryResult = restoreAccounts(context, preferredAdbPort)
            stillDisabled.clear()
            stillDisabled.addAll(retryResult)
        } else {
            savePendingRestorePackages(context, emptyList())
        }

        // Esperar hasta 2 segundos (10 x 200ms) a que DevicePolicyManager refleje el cambio
        var becameDo = false
        for (i in 0 until 10) {
            if (dpm.isDeviceOwnerApp(pkg)) {
                becameDo = true
                break
            }
            delay(200)
        }

        if (becameDo) {
            applyDefaultPostActivationProtection(context)
            return@withContext Outcome.Activated(
                channel = channelUsed,
                temporarilyDisabledPackages = parsed.disabledPackages,
                failedToReenablePackages = stillDisabled.toList()
            )
        }

        val reason = when {
            parsed.multiUserCount != null && parsed.multiUserCount > 1 ->
                "Hay múltiples usuarios o perfiles en el equipo (${parsed.multiUserCount}). Elimine los usuarios secundarios o invitados desde Ajustes → Sistema → Usuarios e intente de nuevo."
            parsed.disableAbortedBeforeDpm ->
                "El sistema impidió pausar temporalmente uno de los servicios de cuentas antes de activar Device Owner."
            parsed.dpmOutput.contains("Not allowed to set the device owner", ignoreCase = true) ->
                "Android rechazó el cambio porque aún detecta cuentas o usuarios activos."
            else ->
                "El comando dpm set-device-owner no otorgó permisos de Device Owner."
        }

        Outcome.Failed(
            reason = reason,
            technicalDetail = parsed.dpmOutput.ifEmpty { rawOutput.take(400) },
            failedToReenablePackages = stillDisabled.toList()
        )
    }

    suspend fun restoreAccounts(
        context: Context,
        preferredAdbPort: Int? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val pending = getPendingRestorePackages(context)
        val script = AccountAuthenticatorGateScript.buildRestoration(pending)
        val probe = LocalProvisioningProbe.run(context)

        var raw = ""
        if (probe.rootAvailable) {
            raw = runRootShell(script) ?: ""
        }
        if (raw.isEmpty()) {
            val port = preferredAdbPort
                ?: probe.adbOpenPort
                ?: ClassicAdbChannelOpener.DEFAULT_PORT
            if (ClassicAdbChannelOpener.isLocalPortOpen(port, 500)) {
                try {
                    raw = LocalAdbClient(context, "127.0.0.1", port).executeShell(script)
                } catch (_: Exception) {
                }
            }
        }

        val parsed = AccountAuthenticatorGateScript.parse(raw)
        val remaining = parsed.failedToReenablePackages
        savePendingRestorePackages(context, remaining)
        remaining
    }

    private fun applyDefaultPostActivationProtection(context: Context) {
        try {
            val pm = PolicyManager(context)
            if (pm.isDeviceOwner()) {
                // Aplicamos el perfil maestro Nivel 1 recortado (incluirPostAlta = false)
                // igual que en DeviceAdminReceiver.onProfileProvisioningComplete, para no
                // bloquear las cuentas que ya están puestas ni el idioma.
                EnrollmentProfiles.applyMasterProfile(
                    context = context,
                    level = EnrollmentProfiles.LEVEL_1_STRICT,
                    incluirPostAlta = false
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun runRootShell(script: String): String? {
        return try {
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(25_000L, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                null
            } else {
                proc.inputStream.bufferedReader().readText()
            }
        } catch (_: Exception) {
            null
        }
    }
}
```

---

### Paso 9: Crear `app/src/main/java/com/ejemplo/locksuite/ui/provisioning/MultimediaProvisioningDialog.kt`

> **Nota crítica:** Este archivo va bajo `ui/provisioning/` (NO bajo `provisioning/ui/`) para respetar la regla de `tools/ia_contenedor/typecheck/typecheck.sh`.

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.ui.provisioning

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ejemplo.locksuite.provisioning.LocalProvisioningProbe
import com.ejemplo.locksuite.provisioning.MultimediaDeviceOwnerActivator
import com.ejemplo.locksuite.provisioning.adb.ClassicAdbChannelOpener
import kotlinx.coroutines.launch

@Composable
fun MultimediaProvisioningDialog(
    onDismiss: () -> Unit,
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var probeReport by remember { mutableStateOf<LocalProvisioningProbe.Report?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var isWaitingForToggle by remember { mutableStateOf(false) }
    var showAuthPromptNotice by remember { mutableStateOf(false) }
    var statusTitle by remember { mutableStateOf("Analizando el dispositivo…") }
    var statusDetail by remember { mutableStateOf("") }
    var errorDetail by remember { mutableStateOf("") }
    var pendingRestore by remember {
        mutableStateOf(MultimediaDeviceOwnerActivator.getPendingRestorePackages(context))
    }

    val navyDark = Color(0xFF0E141B)
    val navyCard = Color(0xFF1A222C)
    val accentGold = Color(0xFFF5C438)
    val okGreen = Color(0xFF2ECC71)
    val alertRed = Color(0xFFF87171)

    fun refreshProbe() {
        scope.launch {
            isBusy = true
            val rep = LocalProvisioningProbe.run(context)
            probeReport = rep
            pendingRestore = MultimediaDeviceOwnerActivator.getPendingRestorePackages(context)
            statusTitle = when (rep.recommendedPath) {
                LocalProvisioningProbe.RecommendedPath.ALREADY_ACTIVE ->
                    "¡LockSuite ya es Device Owner en este equipo!"
                LocalProvisioningProbe.RecommendedPath.ROOT_SU ->
                    "Acceso Root detectado: activación directa en 1 toque lista."
                LocalProvisioningProbe.RecommendedPath.LOCAL_ADB_READY ->
                    "Canal ADB local detectado en el puerto ${rep.adbOpenPort}: listo para activar."
                LocalProvisioningProbe.RecommendedPath.NEEDS_ADB_SETUP ->
                    "Modo Multimedia / Sin PC: requiere abrir el puerto ADB local (5555)."
            }
            isBusy = false
            if (rep.isAlreadyDeviceOwner) {
                onActivated()
            }
        }
    }

    fun runActivation(port: Int? = null) {
        scope.launch {
            isBusy = true
            errorDetail = ""
            showAuthPromptNotice = false
            statusTitle = "Congelando autenticadores de cuentas temporalmente y activando Device Owner…"
            statusDetail = "Tus cuentas NO se borrarán; se reactivarán automáticamente en unos segundos."

            val outcome = MultimediaDeviceOwnerActivator.activate(
                context = context,
                preferredAdbPort = port,
                onAdbAuthPrompt = {
                    showAuthPromptNotice = true
                }
            )
            pendingRestore = MultimediaDeviceOwnerActivator.getPendingRestorePackages(context)
            when (outcome) {
                is MultimediaDeviceOwnerActivator.Outcome.Activated -> {
                    statusTitle = "¡Activación completada por ${outcome.channel}!"
                    statusDetail = if (outcome.temporarilyDisabledPackages.isEmpty()) {
                        "Device Owner activo."
                    } else {
                        "Device Owner activo. Paquetes de cuentas restaurados (${outcome.temporarilyDisabledPackages.size})."
                    }
                    isBusy = false
                    onActivated()
                }
                is MultimediaDeviceOwnerActivator.Outcome.Failed -> {
                    statusTitle = "No se pudo completar la activación"
                    statusDetail = outcome.reason
                    errorDetail = outcome.technicalDetail
                    isBusy = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshProbe()
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = navyDark
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🚗 Instalación Fácil (Multimedia / Sin PC)",
                    color = accentGold,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Activa LockSuite como administrador total (Device Owner) directamente desde el equipo, sin necesidad de computadora ni de borrar las cuentas de Google existentes.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )

                // Tarjeta de estado actual
                Card(
                    colors = CardDefaults.cardColors(containerColor = navyCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = statusTitle,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (statusDetail.isNotEmpty()) {
                            Text(
                                text = statusDetail,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp
                            )
                        }
                        if (showAuthPromptNotice) {
                            Text(
                                text = "⚠️ ¡ATENCIÓN! Apareció en pantalla el cartel de «¿Permitir depuración por USB?». Marca «Permitir siempre» y toca ACEPTAR ahora.",
                                color = accentGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        if (errorDetail.isNotEmpty()) {
                            Text(
                                text = "Detalle técnico: $errorDetail",
                                color = alertRed,
                                fontSize = 12.sp
                            )
                        }
                        val rep = probeReport
                        if (rep != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• Root (su): ${if (rep.rootAvailable) "Sí" else "No"}   " +
                                    "• Puerto ADB local: ${rep.adbOpenPort?.toString() ?: "Cerrado"}   " +
                                    "• Depuración USB: ${if (rep.adbEnabledInSettings) "Activa" else "Inactiva"}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Alerta de seguridad si algún paquete de cuentas quedó pendiente de restaurar
                if (pendingRestore.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF7B241C)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Restauración de cuentas pendiente (${pendingRestore.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        val rem = MultimediaDeviceOwnerActivator.restoreAccounts(context)
                                        pendingRestore = rem.toSet()
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentGold,
                                    contentColor = navyDark
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reactivar paquetes de cuentas ahora", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (isBusy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = accentGold, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isWaitingForToggle) {
                                "Esperando que abras Depuración USB (puerto 5555)…"
                            } else {
                                "Procesando…"
                            },
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }

                // Botón Principal: Activar Ahora
                Button(
                    onClick = { runActivation() },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentGold,
                        contentColor = navyDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚡ Activar Device Owner Ahora",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                // Paso guiado para abrir el puerto 5555 sin PC
                Card(
                    colors = CardDefaults.cardColors(containerColor = navyCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "¿El puerto 5555 figura Cerrado? Ábrelo sin PC en 2 pasos:",
                            color = accentGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "1. Toca «Preparar puerto 5555 y abrir Opciones de Desarrollador».\n" +
                                "2. En Opciones de Desarrollador, APAGA y vuelve a ENCENDER «Depuración por USB» (o activa «Depuración por red / TCP»). Al volver, LockSuite se activará solo.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    isWaitingForToggle = true
                                    statusTitle = "Preparando propiedad TCP 5555…"
                                    when (val prep = ClassicAdbChannelOpener.prepareClassicPort(context, 5555)) {
                                        is ClassicAdbChannelOpener.PrepOutcome.AlreadyOpen -> {
                                            isWaitingForToggle = false
                                            runActivation(prep.port)
                                        }
                                        is ClassicAdbChannelOpener.PrepOutcome.OpenedViaRoot -> {
                                            isWaitingForToggle = false
                                            runActivation(prep.port)
                                        }
                                        is ClassicAdbChannelOpener.PrepOutcome.WaitingForUsbDebuggingToggle -> {
                                            statusTitle = "Apaga y vuelve a encender «Depuración por USB»"
                                            statusDetail = "Vigilando el puerto local 5555 durante 5 minutos…"
                                            openDeveloperSettingsSafely(context)
                                            val opened = ClassicAdbChannelOpener.waitForLoopbackPort(
                                                port = prep.port,
                                                timeoutMs = 300_000L
                                            )
                                            isWaitingForToggle = false
                                            if (opened) {
                                                runActivation(prep.port)
                                            } else {
                                                statusTitle = "Tiempo de espera agotado"
                                                statusDetail = "El puerto 5555 no se abrió. Verifica en Opciones de Desarrollador si el equipo tiene «ADB sobre red» o «Depuración inalámbrica»."
                                                isBusy = false
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A3647),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🔧 Preparar puerto 5555 y abrir Opciones de Desarrollador",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // Fila inferior de botones
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { refreshProbe() },
                        enabled = !isBusy
                    ) {
                        Text("Volver a comprobar", color = accentGold)
                    }
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isBusy
                    ) {
                        Text("Cerrar", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

private fun openDeveloperSettingsSafely(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}
```

---

## 4. Modificación de Actividades Existentes (Paso 10)

### 10.A — `app/src/main/java/com/ejemplo/locksuite/ui/auth/SetupPinActivity.kt`

En `SetupPinScreen` (`SetupPinActivity.kt`):
1. Agregar arriba junto a los estados de `SetupPinScreen`:
   ```kotlin
   val context = androidx.compose.ui.platform.LocalContext.current
   var isDeviceOwner by remember {
       val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
       mutableStateOf(dpm.isDeviceOwnerApp(context.packageName))
   }
   var showMultimediaDialog by remember { mutableStateOf(false) }
   ```
2. Debajo del teclado numérico (justo antes de cerrar el `Column` principal en la línea ~310), agregar el botón para abrir el asistente cuando `!isDeviceOwner`:
   ```kotlin
   if (!isDeviceOwner) {
       OutlinedButton(
           onClick = { showMultimediaDialog = true },
           colors = ButtonDefaults.outlinedButtonColors(contentColor = accentOrange),
           shape = RoundedCornerShape(12.dp),
           modifier = Modifier.fillMaxWidth()
       ) {
           Text(
               text = "🚗 Instalación fácil (Multimedia / Sin PC)",
               fontWeight = FontWeight.Bold,
               fontSize = 13.sp,
               textAlign = TextAlign.Center
           )
       }
   }

   if (showMultimediaDialog) {
       com.ejemplo.locksuite.ui.provisioning.MultimediaProvisioningDialog(
           onDismiss = { showMultimediaDialog = false },
           onActivated = {
               isDeviceOwner = true
               showMultimediaDialog = false
               Toast.makeText(context, "¡Device Owner activado con éxito!", Toast.LENGTH_LONG).show()
           }
       )
   }
   ```

---

### 10.B — `app/src/main/java/com/ejemplo/locksuite/ui/auth/LoginActivity.kt`

En `LoginScreen` (`LoginActivity.kt`):
1. Junto a los `remember` al principio de `LoginScreen` (alrededor de la línea 160), agregar:
   ```kotlin
   var isDeviceOwner by remember {
       val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
       mutableStateOf(dpm.isDeviceOwnerApp(context.packageName))
   }
   var showMultimediaDialog by remember { mutableStateOf(false) }
   ```
2. En la columna de botones principales de la pantalla previa al PIN (alrededor de la línea 468, justo debajo del botón de «Actualizar Aplicación»), agregar:
   ```kotlin
   if (!isDeviceOwner) {
       Button(
           onClick = { showMultimediaDialog = true },
           colors = ButtonDefaults.buttonColors(
               containerColor = Color(0xFF2A3647),
               contentColor = accentOrange
           ),
           modifier = Modifier.fillMaxWidth(0.85f),
           shape = RoundedCornerShape(12.dp)
       ) {
           Text(
               text = if (LocaleManager.getLang() == "he") "🚗 התקנה קלה (מולטימדיה / ללא מחשב)" else if (LocaleManager.getLang() == "en") "🚗 Easy Setup (Car Multimedia / No PC)" else "🚗 Instalación fácil (Multimedia / Sin PC)",
               fontWeight = FontWeight.Bold,
               textAlign = TextAlign.Center
           )
       }
   }

   if (showMultimediaDialog) {
       com.ejemplo.locksuite.ui.provisioning.MultimediaProvisioningDialog(
           onDismiss = { showMultimediaDialog = false },
           onActivated = {
               isDeviceOwner = true
               showMultimediaDialog = false
               Toast.makeText(context, "¡Device Owner activado con éxito!", Toast.LENGTH_LONG).show()
           }
       )
   }
   ```

---

### 10.C — `app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt`

En `DashboardActivity.kt`:
1. Alrededor de la línea 858, donde dice:
   ```kotlin
   if (!isDeviceOwner) {
       item {
           Card(
               colors = CardDefaults.cardColors(containerColor = Color(0xFF7B241C)),
               modifier = Modifier.fillMaxWidth()
           ) {
               Row(
                   modifier = Modifier.padding(16.dp),
                   verticalAlignment = Alignment.CenterVertically
               ) {
                   Icon(Icons.Default.Info, "Advertencia", tint = Color.White)
                   Spacer(modifier = Modifier.width(12.dp))
                   Text(
                       "ADVERTENCIA: La app no está configurada como Device Owner. Configure mediante ADB para activar los bloqueos.",
                       color = Color.White,
                       fontSize = 13.sp,
                       fontWeight = FontWeight.Bold
                   )
               }
           }
       }
   }
   ```
   Reemplazar ese bloque `if (!isDeviceOwner)` por:
   ```kotlin
   if (!isDeviceOwner) {
       item {
           var showMultimediaDialog by remember { mutableStateOf(false) }
           Card(
               colors = CardDefaults.cardColors(containerColor = Color(0xFF7B241C)),
               modifier = Modifier.fillMaxWidth(),
               shape = RoundedCornerShape(12.dp)
           ) {
               Column(
                   modifier = Modifier.padding(16.dp),
                   verticalArrangement = Arrangement.spacedBy(10.dp)
               ) {
                   Row(verticalAlignment = Alignment.CenterVertically) {
                       Icon(Icons.Default.Info, "Advertencia", tint = Color.White)
                       Spacer(modifier = Modifier.width(12.dp))
                       Text(
                           "ADVERTENCIA: La app no está configurada como Device Owner. Actívelo desde aquí mismo sin PC o mediante ADB.",
                           color = Color.White,
                           fontSize = 13.sp,
                           fontWeight = FontWeight.Bold
                       )
                   }
                   Button(
                       onClick = {
                           DashboardActivity.isLaunchingInternalFlow = true
                           showMultimediaDialog = true
                       },
                       colors = ButtonDefaults.buttonColors(
                           containerColor = Color(0xFFF5C438),
                           contentColor = Color(0xFF0E141B)
                       ),
                       modifier = Modifier.fillMaxWidth()
                   ) {
                       Text("🚗 Activar Device Owner sin PC (Multimedia / Auto)", fontWeight = FontWeight.Bold)
                   }
               }
           }
           if (showMultimediaDialog) {
               com.ejemplo.locksuite.ui.provisioning.MultimediaProvisioningDialog(
                   onDismiss = {
                       DashboardActivity.isLaunchingInternalFlow = false
                       showMultimediaDialog = false
                   },
                   onActivated = {
                       DashboardActivity.isLaunchingInternalFlow = false
                       showMultimediaDialog = false
                       refreshKey++
                       Toast.makeText(context, "¡Device Owner activado con éxito!", Toast.LENGTH_LONG).show()
                   }
               )
           }
       }
   }
   ```

---

## 5. Pruebas Unitarias (Paso 11)

### 11.A — Crear `app/src/test/java/com/ejemplo/locksuite/provisioning/MultimediaProvisioningTest.kt`

Crear el archivo con este contenido exacto:

```kotlin
package com.ejemplo.locksuite.provisioning

import com.ejemplo.locksuite.provisioning.adb.AccountAuthenticatorGateScript
import com.ejemplo.locksuite.provisioning.adb.AdbAuthKey
import com.ejemplo.locksuite.provisioning.adb.AdbFrameStream
import com.ejemplo.locksuite.provisioning.adb.AdbProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class MultimediaProvisioningTest {

    @Test
    fun buildActivation_includesSnapshotDisableDpmAndRestore() {
        val script = AccountAuthenticatorGateScript.buildActivation(
            "com.ejemplo.locksuite",
            "com.ejemplo.locksuite/com.ejemplo.locksuite.receiver.DeviceAdminReceiver"
        )
        assertTrue(script.contains("SNAPSHOT='/data/local/tmp/.locksuite_authgate'"))
        assertTrue(script.contains("cmd package query-services -a android.accounts.AccountAuthenticator"))
        assertTrue(script.contains("pm disable-user --user 0"))
        assertTrue(script.contains("dpm set-device-owner \"\$ADMIN\""))
        assertTrue(script.contains("restore_snapshot active"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildActivation_rejectsShellInjectionInPackage() {
        AccountAuthenticatorGateScript.buildActivation(
            "com.ejemplo.locksuite; rm -rf /",
            "com.ejemplo.locksuite/.Admin"
        )
    }

    @Test
    fun parse_extractsDisabledEnabledAndDpmExitCode() {
        val raw = """
            __LS_DISABLED__:com.google.android.gms
            __LS_DISABLED__:com.whatsapp
            Success: Device owner set to package com.ejemplo.locksuite
            __LS_DPM_EXIT__:0
            __LS_ENABLED__:com.google.android.gms
            __LS_ENABLED__:com.whatsapp
        """.trimIndent()

        val parsed = AccountAuthenticatorGateScript.parse(raw)
        assertEquals(0, parsed.dpmExitCode)
        assertEquals(listOf("com.google.android.gms", "com.whatsapp"), parsed.disabledPackages)
        assertEquals(listOf("com.google.android.gms", "com.whatsapp"), parsed.reenabledPackages)
        assertTrue(parsed.failedToReenablePackages.isEmpty())
        assertTrue(parsed.restorationClean)
        assertTrue(parsed.dpmOutput.contains("Success: Device owner set"))
    }

    @Test
    fun parse_detectsFailedRestorationAndMultiUserAbort() {
        val raw = """
            __LS_DISABLED__:com.google.android.gms
            __LS_DPM_EXIT__:255
            __LS_RESTORE_FAILED__:com.google.android.gms
        """.trimIndent()
        val parsed = AccountAuthenticatorGateScript.parse(raw)
        assertFalse(parsed.restorationClean)
        assertEquals(listOf("com.google.android.gms"), parsed.failedToReenablePackages)

        val multiRaw = "__LS_MULTI_USER_ABORT__:2"
        val multiParsed = AccountAuthenticatorGateScript.parse(multiRaw)
        assertEquals(2, multiParsed.multiUserCount)
    }

    @Test
    fun adbFrameStream_roundTripPreservesCommandArgsAndPayload() {
        val out = ByteArrayOutputStream()
        val payload = "shell:id\u0000".toByteArray(Charsets.UTF_8)
        val writer = AdbFrameStream(ByteArrayInputStream(ByteArray(0)), out)
        writer.writeFrame(AdbProtocol.A_OPEN, 7, 0, payload)

        val reader = AdbFrameStream(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val frame = reader.readFrame()
        assertEquals(AdbProtocol.A_OPEN, frame.command)
        assertEquals(7, frame.arg0)
        assertEquals(0, frame.arg1)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun encodeAndroidRsaPublicKey_produces524ByteLittleEndianStruct() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pub = kpg.generateKeyPair().public as RSAPublicKey

        val bytes = AdbAuthKey.encodeAndroidRsaPublicKey(pub.modulus, pub.publicExponent)
        assertEquals(524, bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val words = buf.int
        assertEquals(64, words)

        val exponentAtEnd = ByteBuffer.wrap(bytes, 520, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(pub.publicExponent.toInt(), exponentAtEnd)
        assertTrue(pub.modulus > BigInteger.ZERO)
    }
}
```

### 11.B — Registrar la suite en `tools/ia_contenedor/typecheck/run_unit_tests.sh`

En `tools/ia_contenedor/typecheck/run_unit_tests.sh`, dentro del arreglo `SUITES=( ... )` (alrededor de la línea 32), agregar una línea más antes del paréntesis de cierre `)`:
```bash
  "com.ejemplo.locksuite.provisioning.MultimediaProvisioningTest|provisioning/adb/AdbProtocol.kt provisioning/adb/AdbFrameStream.kt provisioning/adb/AdbAuthKey.kt provisioning/adb/AccountAuthenticatorGateScript.kt|provisioning/MultimediaProvisioningTest.kt"
```

---

## 6. Verificación, Compilación y Commit (Paso 12)

Una vez creados y editados los archivos anteriores, ejecutar en orden:

1. **Correr los scripts de sincronización del proyecto:**
   ```powershell
   python tools/check_command_sync.py
   python tools/check_panel_commands.py
   python tools/check_profile_sync.py
   python tools/check_whitelist_sync.py
   python tools/check_store_sync.py
   ```
2. **Compilar el código Kotlin y correr todas las pruebas unitarias con Gradle:**
   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug
   ```
   *(Verificar que los tests de `MultimediaProvisioningTest` y todos los demás tests unitarios pasen con 0 errores).*
3. **Actualizar `LOCKSUITE_CONTEXTO_PARA_IA.md`:**
   - En la sección **B.105**, cambiar el estado a `[COMPILADO CON GRADLE Y TESTS 100% VERDES; PENDIENTE DE PRUEBA EN MULTIMEDIA REAL]`.
   - En la sección **C**, actualizar la bitácora con el resultado de la compilación y el hash del commit.
4. **Hacer el commit en Git:**
   ```powershell
   git add -A
   git commit -m "feat(provisioning): instalacion facil sin PC para multimedia de autos (ADB local 5555 + Root + AccountAuthenticatorGate)"
   ```
