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
