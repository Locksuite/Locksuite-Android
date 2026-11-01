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
