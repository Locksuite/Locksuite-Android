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
