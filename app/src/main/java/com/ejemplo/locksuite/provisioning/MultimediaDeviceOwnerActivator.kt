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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

        val appCtx = context.applicationContext
        val probe = LocalProvisioningProbe.run(appCtx)
        val script = AccountAuthenticatorGateScript.buildActivation(pkg, adminComponent)

        var rawOutput = ""
        var channelUsed = ""
        var portForCleanup: Int? = preferredAdbPort
            ?: probe.adbOpenPort
            ?: probe.adbConfiguredPort
        var scriptStarted = false
        var restorationDone = false

        try {
            // 1. Intentar por Root (su) si está disponible
            if (probe.rootAvailable) {
                scriptStarted = true
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
                portForCleanup = port

                if (!ClassicAdbChannelOpener.isLocalPortOpen(port, 500)) {
                    return@withContext Outcome.Failed(
                        reason = "El puerto ADB local ($port) todavía no está abierto en el dispositivo.",
                        technicalDetail = "Active la Depuración por USB / ADB inalámbrico o use el botón de espera automática."
                    )
                }

                try {
                    scriptStarted = true
                    val client = LocalAdbClient(appCtx, "127.0.0.1", port)
                    rawOutput = client.executeShell(script) {
                        onAdbAuthPrompt?.invoke()
                    }
                    channelUsed = "ADB Local (127.0.0.1:$port)"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val pendingAfterFail = try {
                        restoreAccounts(appCtx, port)
                    } catch (_: Exception) {
                        getPendingRestorePackages(appCtx).toList()
                    }
                    restorationDone = true
                    return@withContext Outcome.Failed(
                        reason = "No se pudo conectar al canal ADB local en el puerto $port.",
                        technicalDetail = e.message ?: e.javaClass.simpleName,
                        failedToReenablePackages = pendingAfterFail
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
                savePendingRestorePackages(appCtx, stillDisabled)
                val retryResult = restoreAccounts(appCtx, portForCleanup)
                stillDisabled.clear()
                stillDisabled.addAll(retryResult)
            } else {
                savePendingRestorePackages(appCtx, emptyList())
            }
            restorationDone = true

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
                closeWirelessAdbSafely(appCtx, portForCleanup, probe.rootAvailable)
                applyDefaultPostActivationProtection(appCtx)
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

            return@withContext Outcome.Failed(
                reason = reason,
                technicalDetail = parsed.dpmOutput.ifEmpty { rawOutput.take(400) },
                failedToReenablePackages = stillDisabled.toList()
            )
        } finally {
            if (scriptStarted && !restorationDone) {
                withContext(NonCancellable) {
                    try {
                        restoreAccounts(appCtx, portForCleanup)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    suspend fun restoreAccounts(
        context: Context,
        preferredAdbPort: Int? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val pending = getPendingRestorePackages(appCtx)
        val script = AccountAuthenticatorGateScript.buildRestoration(pending)
        val probe = LocalProvisioningProbe.run(appCtx)

        var raw = ""
        if (probe.rootAvailable) {
            raw = runRootShell(script) ?: ""
        }
        if (raw.isEmpty()) {
            val port = preferredAdbPort
                ?: probe.adbOpenPort
                ?: probe.adbConfiguredPort
                ?: ClassicAdbChannelOpener.DEFAULT_PORT
            if (ClassicAdbChannelOpener.isLocalPortOpen(port, 500)) {
                try {
                    raw = LocalAdbClient(appCtx, "127.0.0.1", port).executeShell(script)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }

        if (raw.isBlank()) {
            return@withContext pending.toList()
        }

        val parsed = AccountAuthenticatorGateScript.parse(raw)
        val remaining = LinkedHashSet<String>(parsed.failedToReenablePackages)
        for (p in pending) {
            if (!parsed.reenabledPackages.contains(p)) {
                remaining.add(p)
            }
        }
        val remainingList = remaining.toList()
        savePendingRestorePackages(appCtx, remainingList)
        remainingList
    }

    private suspend fun closeWirelessAdbSafely(
        context: Context,
        adbPort: Int?,
        preferRoot: Boolean
    ) {
        val resetScript =
            "setprop service.adb.tcp.port -1 >/dev/null 2>&1 || true; " +
                "setprop persist.adb.tcp.port -1 >/dev/null 2>&1 || true; " +
                "settings put global adb_wifi_enabled 0 >/dev/null 2>&1 || true; " +
                "settings put global adb_enabled 0 >/dev/null 2>&1 || true; " +
                "stop adbd >/dev/null 2>&1 || true"
        var clearedViaShell = false
        if (preferRoot) {
            if (runRootShell(resetScript) != null) {
                clearedViaShell = true
            }
        }
        if (!clearedViaShell) {
            val port = adbPort ?: ClassicAdbChannelOpener.DEFAULT_PORT
            if (ClassicAdbChannelOpener.isLocalPortOpen(port, 250)) {
                try {
                    LocalAdbClient(context, "127.0.0.1", port).executeShell(
                        command = resetScript,
                        connectTimeoutMs = 1_500,
                        authTimeoutMs = 5_000,
                        commandTimeoutMs = 3_000
                    )
                } catch (_: Exception) {
                }
            }
        }
        try {
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        } catch (_: Exception) {
        }
        try {
            android.provider.Settings.Global.putInt(
                context.contentResolver,
                android.provider.Settings.Global.ADB_ENABLED,
                0
            )
        } catch (_: Exception) {
        }
    }

    private fun applyDefaultPostActivationProtection(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                // Aplicamos el perfil maestro Nivel 1 recortado (incluirPostAlta = false)
                // igual que en DeviceAdminReceiver.onProfileProvisioningComplete, para no
                // bloquear las cuentas que ya están puestas ni el idioma.
                val pm = PolicyManager(context)
                pm.applyMasterProfile(
                    profileId = EnrollmentProfiles.LEVEL_STRICT,
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
