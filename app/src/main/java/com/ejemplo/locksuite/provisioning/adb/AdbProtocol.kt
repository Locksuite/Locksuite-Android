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
