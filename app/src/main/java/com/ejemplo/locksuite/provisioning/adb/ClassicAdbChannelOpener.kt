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
