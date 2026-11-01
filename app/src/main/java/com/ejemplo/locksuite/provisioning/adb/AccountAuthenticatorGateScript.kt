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
