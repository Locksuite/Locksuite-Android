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
