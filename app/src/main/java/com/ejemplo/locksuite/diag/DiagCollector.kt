package com.ejemplo.locksuite.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LO QUE EL CELULAR JUNTA CUANDO SE LE PIDE UN DIAGNÓSTICO (6/11/2026, B.110).
 *
 * Es el equivalente a conectar el equipo por USB y mirar: el logcat propio, por qué murió el
 * proceso las últimas veces, el estado del sistema (batería, memoria, red, Doze), el estado
 * de las protecciones de LockSuite (accesibilidad, túnel DNS, administrador del equipo) y,
 * si el usuario se quejó de una app puntual, el estado de ESA app (¿está oculta?
 * ¿suspendida? ¿tiene pantalla de inicio? ¿LockSuite le bloqueó internet?).
 *
 * Solo corre cuando alguien lo pide —el comando `COLLECT_DIAGNOSTICS` del panel— o, en el
 * caso del barrido de errores del logcat y de los cierres del proceso, de pasada en el
 * latido (a lo sumo cada 30 min, y son unos pocos milisegundos).
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • CADA línea del informe va envuelta en su propio try/catch ([safe]): un servicio del
 *    sistema que no contesta, o un campo que este Android no tiene, deja «(no disponible)»
 *    en esa línea y NO tira abajo el resto. Un diagnóstico a medias vale más que ninguno.
 *  • El logcat se lee con un hilo lector y un plazo ([LOGCAT_TIMEOUT_MS]): `logcat -d`
 *    termina solo, pero un equipo con el comando bloqueado podría colgar al lector. Al
 *    vencer el plazo se mata el proceso y se devuelve lo que alcanzó a leer.
 *  • El logcat que ve una app es SOLO el suyo (Android filtra por uid desde 4.1). No hace
 *    falta —ni se pide— el permiso READ_LOGS.
 *  • Los llamados que existen desde Android 11 (cierres del proceso) van en funciones
 *    aparte con `@RequiresApi`, igual que el resto del proyecto, para que el chequeo de
 *    versión de Android no marque error.
 *  • Todo texto que sale de acá pasa por [DiagSanitizer].
 */
internal object DiagCollector {

    private const val SWEEP_GAP_MS = 30 * 60_000L
    private const val LOGCAT_TIMEOUT_MS = 8_000L
    private const val MAX_PACKAGES = 6

    class Snapshot(val system: String, val exits: String, val logcat: String, val packages: String)

    // ═════════════════════════════════════════════════════════════════════════════════
    // 1. CIERRES DEL PROCESO (Android 11+)
    // ═════════════════════════════════════════════════════════════════════════════════

    /**
     * Anota en el registro los cierres del proceso que todavía no se vieron. Se llama al
     * arrancar cada proceso: lo que mató al anterior ya pasó y ahora es el momento de
     * contarlo. La primera vez mira los últimos 3 días.
     */
    fun recordPreviousExits(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try { recordExitsApi30(ctx) } catch (_: Throwable) { }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitsApi30(ctx: Context) {
        val now = System.currentTimeMillis()
        val cursor = DiagStore.getLong("exit_cursor", 0L)
        val horizon = if (cursor == 0L) now - 3 * 24 * 3600_000L else cursor
        val fresh = DiagExits.newerThan(readExits(ctx, 12), horizon)
        if (fresh.isEmpty()) return
        for (r in fresh) {
            DiagStore.append(r.at, DiagExits.levelFor(r.reason), "exit", DiagExits.describe(r), 1, false)
        }
        DiagStore.putLong("exit_cursor", fresh.maxOf { it.at })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExits(ctx: Context, max: Int): List<DiagExits.Record> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, max) ?: return emptyList()
        return infos.map { info ->
            val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) readAnrHead(info) else null
            DiagExits.Record(
                info.timestamp, info.reason, info.importance, info.status,
                info.pss, info.rss, info.description, trace
            )
        }
    }

    /** De la traza de un ANR, el hilo principal: es donde se ve qué lo tenía trabado. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readAnrHead(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            val lines = stream.bufferedReader(Charsets.UTF_8).useLines { it.take(600).toList() }
            val i = lines.indexOfFirst { it.startsWith("\"main\"") }
            val from = if (i >= 0) i else 0
            lines.drop(from).take(18).joinToString("\n")
        }
    } catch (_: Throwable) {
        null
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // 2. BARRIDO DE ERRORES DEL LOGCAT
    // ═════════════════════════════════════════════════════════════════════════════════

    private class Group(val first: DiagLogcatParser.Burst) {
        var last: DiagLogcatParser.Burst = first
        var count = 0
    }

    /**
     * Pasa a ser eventos del registro los `Log.e(...)` nuevos del logcat propio. Se llama
     * desde el latido y se ejecuta, como mucho, cada [SWEEP_GAP_MS]. Los errores iguales
     * se agrupan (con su cantidad) y se anotan como máximo 20 grupos por barrido.
     */
    fun sweepLogcatIfDue(ctx: Context, now: Long, force: Boolean = false) {
        try {
            val last = DiagStore.getLong("last_sweep_at", 0L)
            if (!force && last in 1..now && now - last < SWEEP_GAP_MS) return
            DiagStore.putLong("last_sweep_at", now)

            val lines = readLogcat('E', 400_000) ?: return
            val entries = DiagLogcatParser.parse(lines)
            val cursor = DiagStore.getString("logcat_cursor")
            val fresh = DiagLogcatParser.newerThan(entries, cursor)
            DiagLogcatParser.newestStamp(entries)?.let { DiagStore.putString("logcat_cursor", it) }
            if (fresh.isEmpty()) return

            val groups = LinkedHashMap<String, Group>()
            for (b in DiagLogcatParser.bursts(fresh, 'E')) {
                val key = b.tag + "|" + (b.text.lineSequence().firstOrNull() ?: "")
                val g = groups.getOrPut(key) { Group(b) }
                g.count++
                g.last = b
            }
            for (g in groups.values.toList().takeLast(20)) {
                val at = DiagLogcatParser.stampToEpoch(g.last.stamp, now) ?: now
                val level = if (g.last.level == 'F' || g.last.level == 'A') DiagEvent.LEVEL_FATAL else DiagEvent.LEVEL_ERROR
                DiagStore.append(at, level, "logcat", DiagSanitizer.sanitizeTrace(g.last.tag + ": " + g.last.text), g.count, false)
            }
        } catch (_: Throwable) { }
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // 3. LOGCAT PROPIO
    // ═════════════════════════════════════════════════════════════════════════════════

    /** Líneas del logcat propio con nivel `>= level`, las más nuevas dentro de [maxBytes]. */
    fun readLogcat(level: Char, maxBytes: Int): List<String>? {
        val filtro = "*:$level"
        var r = runLogcat(arrayOf("logcat", "-d", "-v", "threadtime", "-b", "main,crash", filtro), maxBytes)
        if (r == null || r.isEmpty()) {
            r = runLogcat(arrayOf("logcat", "-d", "-v", "threadtime", filtro), maxBytes)
        }
        return r
    }

    private fun runLogcat(cmd: Array<String>, maxBytes: Int): List<String>? {
        var proc: Process? = null
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            proc = p
            val ring = java.util.ArrayDeque<String>()
            var bytes = 0
            val reader = Thread({
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                        while (true) {
                            val l = br.readLine() ?: break
                            synchronized(ring) {
                                ring.addLast(l)
                                bytes += l.length + 1
                                while (bytes > maxBytes && ring.size > 1) bytes -= ring.removeFirst().length + 1
                            }
                        }
                    }
                } catch (_: Throwable) { }
            }, "LockSuiteLogcat")
            reader.isDaemon = true
            reader.start()
            reader.join(LOGCAT_TIMEOUT_MS)
            if (reader.isAlive) {
                p.destroy()
                reader.join(500)
            }
            synchronized(ring) { ArrayList(ring) }
        } catch (_: Throwable) {
            null
        } finally {
            try { proc?.destroy() } catch (_: Throwable) { }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // 4. EL DIAGNÓSTICO COMPLETO
    // ═════════════════════════════════════════════════════════════════════════════════

    fun snapshot(ctx: Context, packages: List<String>): Snapshot {
        val recent = DiagStore.readLast(400)
        return Snapshot(
            system = DiagSanitizer.sanitizeBlock(safeBlock("sistema") { systemSection(ctx) }, 4_000),
            exits = DiagSanitizer.sanitizeBlock(safeBlock("cierres") { exitsSection(ctx) }, 6_000),
            logcat = DiagSanitizer.sanitizeBlock(safeBlock("logcat") { logcatSection() }, 24_000),
            packages = if (packages.isEmpty()) "" else
                DiagSanitizer.sanitizeBlock(safeBlock("apps") { packagesSection(ctx, packages, recent) }, 6_000)
        )
    }

    private inline fun safeBlock(name: String, block: () -> String): String =
        try { block() } catch (t: Throwable) { "($name no disponible: ${t.javaClass.simpleName})" }

    private inline fun safe(label: String, block: () -> String): String =
        try { "$label: ${block()}" } catch (t: Throwable) { "$label: (no disponible: ${t.javaClass.simpleName})" }

    private fun yesNo(b: Boolean) = if (b) "sí" else "no"

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    private fun fmtDuration(ms: Long): String {
        val min = ms / 60_000
        val d = min / 1440
        val h = (min % 1440) / 60
        val m = min % 60
        return if (d > 0) "$d d $h h" else if (h > 0) "$h h $m min" else "$m min"
    }

    // ── sistema ──────────────────────────────────────────────────────────────────────

    private fun systemSection(ctx: Context): String {
        val sb = StringBuilder()
        fun line(label: String, block: () -> String) { sb.append(safe(label, block)).append('\n') }

        line("App") {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            "LockSuite v${pi.versionName} (${pi.versionCode}) · registro v${DiagLog.VERSION} · ${ctx.packageName}"
        }
        line("Equipo") {
            "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}) · parche de seguridad ${Build.VERSION.SECURITY_PATCH}"
        }
        line("Hora del equipo") {
            val now = System.currentTimeMillis()
            val up = SystemClock.elapsedRealtime()
            val proc = up - android.os.Process.getStartElapsedRealtime()
            "${fmtTime(now)} · encendido hace ${fmtDuration(up)} · proceso de LockSuite iniciado hace ${fmtDuration(proc)}"
        }
        line("Almacenamiento del usuario desbloqueado") {
            yesNo((ctx.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked)
        }
        line("Batería") {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            "${if (level >= 0) level * 100 / scale else -1}% · enchufado: ${yesNo(plugged)} · temperatura $temp °C"
        }
        line("Energía") {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            "ahorro de batería: ${yesNo(pm.isPowerSaveMode)} · modo Doze ahora: ${yesNo(pm.isDeviceIdleMode)} · " +
                "pantalla encendida: ${yesNo(pm.isInteractive)} · LockSuite exenta de optimización de batería: " +
                yesNo(pm.isIgnoringBatteryOptimizations(ctx.packageName))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            line("Restricciones de segundo plano") { backgroundRestrictions(ctx) }
        }
        line("Memoria") {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val rt = Runtime.getRuntime()
            "libre ${mi.availMem / 1048576} MB de ${mi.totalMem / 1048576} MB (umbral de poca memoria " +
                "${mi.threshold / 1048576} MB, en poca memoria: ${yesNo(mi.lowMemory)}) · heap de LockSuite " +
                "${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB"
        }
        line("Almacenamiento") {
            val st = StatFs(Environment.getDataDirectory().path)
            "libre ${st.availableBytes / 1048576} MB de ${st.totalBytes / 1048576} MB"
        }
        line("Red") { networkState(ctx) }
        line("Administrador del equipo (Device Owner)") {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(ctx, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
            "propietario: ${yesNo(dpm.isDeviceOwnerApp(ctx.packageName))} · administrador activo: ${yesNo(dpm.isAdminActive(admin))}"
        }
        line("Accesibilidad") {
            val running = com.ejemplo.locksuite.util.AccessibilityEnforcer.isServiceRunning(ctx)
            val zombie = com.ejemplo.locksuite.util.AccessibilityEnforcer.isZombie(ctx)
            val inst = com.ejemplo.locksuite.service.LockSuiteAccessibilityService.instance != null
            "corriendo: ${yesNo(running)} · instancia viva en este proceso: ${yesNo(inst)} · " +
                "«zombi» (habilitada en Ajustes pero sin enlazar): ${yesNo(zombie)}"
        }
        line("Filtro DNS (VPN de LockSuite)") {
            "túnel corriendo: ${yesNo(com.ejemplo.locksuite.service.KosherVpnService.isTunnelRunning)} · " +
                "auto-reparaciones en este proceso: ${com.ejemplo.locksuite.service.KosherVpnService.tunnelHeals} · " +
                "permiso de VPN concedido: ${yesNo(VpnService.prepare(ctx) == null)}"
        }
        return sb.toString().trimEnd()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun backgroundRestrictions(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val bucket = when (usm.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            else -> "otro (${usm.appStandbyBucket})"
        }
        return "standby bucket: $bucket · segundo plano restringido por el usuario: ${yesNo(am.isBackgroundRestricted)}"
    }

    private fun networkState(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "sin red activa"
        val caps = cm.getNetworkCapabilities(net) ?: return "red activa sin datos"
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("datos móviles")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }.joinToString("+").ifEmpty { "desconocida" }
        return "$transports · con internet: ${yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))} · " +
            "validada por Android: ${yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))} · " +
            "portal cautivo: ${yesNo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))}"
    }

    // ── cierres ──────────────────────────────────────────────────────────────────────

    private fun exitsSection(ctx: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) no guarda el historial de cierres del proceso (hace falta Android 11 o más)."
        }
        return exitsSectionApi30(ctx)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitsSectionApi30(ctx: Context): String {
        val list = readExits(ctx, 10).sortedByDescending { it.at }
        if (list.isEmpty()) return "El sistema no registra cierres recientes del proceso de LockSuite."
        val sb = StringBuilder("Resumen: ").append(DiagExits.summary(list)).append('\n')
        for (r in list) {
            sb.append("• ").append(fmtTime(r.at)).append(" · ").append(DiagExits.describe(r)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ── logcat ───────────────────────────────────────────────────────────────────────

    private fun logcatSection(): String {
        val lines = readLogcat('W', 600_000)
            ?: return "No se pudo leer el logcat (el sistema bloqueó el comando)."
        if (lines.isEmpty()) return "El logcat propio está vacío (se reinició el buffer del sistema o el proceso es nuevo)."
        val entries = DiagLogcatParser.parse(lines)
        return DiagLogcatParser.tail(entries, 350, 24_000)
    }

    // ── apps puntuales ───────────────────────────────────────────────────────────────

    private fun packagesSection(ctx: Context, packages: List<String>, recent: List<DiagEvent>): String {
        val pm = ctx.packageManager
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(ctx, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
        val unlocked = com.ejemplo.locksuite.LockSuiteApplication.isStorageUnlocked(ctx)
        val sb = StringBuilder()
        for (pkg in packages.distinct().take(MAX_PACKAGES)) {
            sb.append("── ").append(pkg).append(" ──\n")
            val pi = try {
                pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            if (pi == null) {
                sb.append("No está instalada en este equipo.\n")
            } else {
                val ai = pi.applicationInfo
                sb.append(safe("Versión") {
                    @Suppress("DEPRECATION")
                    "${pi.versionName} (${pi.versionCode}) · actualizada el ${fmtTime(pi.lastUpdateTime)}"
                }).append('\n')
                if (ai != null) {
                    sb.append(safe("Estado") {
                        "instalada para el usuario: ${yesNo((ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0)} · " +
                            "de sistema: ${yesNo((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)} · " +
                            "habilitada: ${yesNo(ai.enabled)} · suspendida (flag): ${yesNo((ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0)}"
                    }).append('\n')
                }
                sb.append(safe("Políticas de LockSuite") {
                    val hidden = dpm?.isApplicationHidden(admin, pkg) ?: false
                    val suspended = dpm?.isPackageSuspended(admin, pkg) ?: false
                    "oculta: ${yesNo(hidden)} · suspendida: ${yesNo(suspended)}"
                }).append('\n')
                sb.append(safe("Pantalla de inicio") {
                    if (pm.getLaunchIntentForPackage(pkg) != null) "tiene actividad de lanzamiento"
                    else "NO tiene actividad de lanzamiento visible (oculta, suspendida o es un servicio)"
                }).append('\n')
                sb.append(safe("Instalada por") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pm.getInstallSourceInfo(pkg).installingPackageName ?: "(desconocido)"
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkg) ?: "(desconocido)"
                    }
                }).append('\n')
                if (unlocked) {
                    sb.append(safe("Internet bloqueado por LockSuite") {
                        yesNo(com.ejemplo.locksuite.mdm.PolicyManager(ctx).isPerAppInternetBlocked(pkg))
                    }).append('\n')
                }
            }
            val mentions = recent.filter { it.msg.contains(pkg) }.takeLast(6)
            if (mentions.isNotEmpty()) {
                sb.append("Eventos del registro que la mencionan:\n")
                for (e in mentions) {
                    sb.append("  ").append(fmtTime(e.at)).append(" ").append(e.level).append('/').append(e.tag).append(": ")
                        .append(e.msg.lineSequence().firstOrNull() ?: "").append('\n')
                }
            }
        }
        return sb.toString().trimEnd()
    }
}
