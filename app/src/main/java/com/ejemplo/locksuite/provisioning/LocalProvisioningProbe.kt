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
