package com.ejemplo.locksuite.diag

import android.content.Context
import android.os.Build
import android.os.UserManager
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * LA PUERTA DE ENTRADA al registro de diagnóstico remoto (6/11/2026, B.110).
 *
 * Todo el código de LockSuite que quiera dejar un rastro para el panel llama acá:
 *
 *     DiagLog.i("vpn", "Túnel DNS establecido")
 *     DiagLog.w("cmd", "Comando rechazado (BAD_SIGNATURE)")
 *     DiagLog.e("a11y", "Se desenlazó el servicio de accesibilidad", throwable)
 *
 * Más lo que se captura SIN tocar el código existente: las excepciones que el código se
 * traga con `e.printStackTrace()` (envoltorio de `System.err`, ver [DiagErrTee]), la caída
 * que mata el proceso (`fatal`, enganchada en el manejador global de `LockSuiteApplication`)
 * y los cierres que el sistema le hace al proceso (`DiagCollector.recordPreviousExits`).
 *
 * ── GARANTÍAS (el pedido del dueño: «que no moleste, que no gaste batería, que no se vea») ──
 *  • NO muestra nada: ni notificación, ni toast, ni pantalla. No pide ningún permiso nuevo.
 *  • NO tiene temporizadores ni alarmas ni wakelocks propios. La escritura ocurre en el
 *    momento del evento, en un hilo propio que se APAGA solo a los 10 s de ocioso; la
 *    subida se engancha en el latido que el equipo ya hace (ver [DiagUploader]).
 *  • NUNCA lanza una excepción hacia quien lo llama, ni bloquea el hilo que lo llama
 *    (salvo `fatal`, que escribe en el acto porque el proceso se está muriendo).
 *  • NO registra contenido del usuario: lo que entra pasa por [DiagSanitizer], y quienes
 *    llaman evitan pasar dominios, textos de pantalla o nombres de contactos.
 *  • El freno de volumen ([DiagRateLimiter]) agrupa repetidos y limita ráfagas: un bucle de
 *    errores no llena el archivo ni la red.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • `init` se llama como PRIMERA cosa en `LockSuiteApplication.onCreate`, antes de
 *    cualquier otra inicialización: así quedan registradas también las fallas del
 *    arranque. Funciona en Arranque Directo (no usa el almacenamiento cifrado).
 *  • El mensaje se limpia y se arma en el hilo escritor, no en quien llama: llamar a
 *    `DiagLog` desde el hilo principal cuesta unos microsegundos.
 *  • `setUploadEnabled(false)` apaga solo la SUBIDA. El archivo local sigue registrando
 *    (no sale del teléfono): así, si se vuelve a encender, hay historia para mirar.
 */
object DiagLog {

    /** Versión del formato del registro; se informa al panel como `diagVersion`. */
    const val VERSION = 1

    @Volatile private var ctx: Context? = null
    private val limiter = DiagRateLimiter()

    /** Solo se toca desde el hilo escritor. */
    private val errGrouper = StackTraceGrouper { text -> storeSwallowed(text) }
    @Volatile private var errBlockOpen = false
    private var errFlush: ScheduledFuture<*>? = null

    private val executor: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1, ThreadFactory { r ->
            Thread(r, "LockSuiteDiag").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }).apply {
            setKeepAliveTime(10, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            setRemoveOnCancelPolicy(true)
        }
    }

    val isReady: Boolean get() = ctx != null

    fun init(context: Context) {
        if (ctx != null) return
        synchronized(this) {
            if (ctx != null) return
            try {
                val app = context.applicationContext ?: context
                DiagStore.init(app)
                ctx = app
            } catch (_: Throwable) {
                return
            }
        }
        try { DiagErrTee.install() } catch (_: Throwable) { }
        post {
            logStart()
            DiagCollector.recordPreviousExits(ctx ?: return@post)
        }
    }

    fun i(tag: String, msg: String) = log(DiagEvent.LEVEL_INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(DiagEvent.LEVEL_WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(DiagEvent.LEVEL_ERROR, tag, msg, t)

    private fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        if (ctx == null) return
        try {
            val now = System.currentTimeMillis()
            val cleanTag = DiagEvent.cleanTag(tag)
            val key = if (t == null) msg else msg + "|" + t.javaClass.name
            val v = limiter.check(level, cleanTag, key.take(300), now)
            if (!v.keep) return
            post {
                val text = if (t == null) DiagSanitizer.sanitize(msg)
                else DiagSanitizer.sanitize(msg, 200) + "\n" + DiagSanitizer.describe(t)
                if (v.droppedBefore > 0) {
                    DiagStore.append(now, DiagEvent.LEVEL_WARN, "diag",
                        "Se descartaron ${v.droppedBefore} eventos por exceso de volumen", 1, false)
                }
                DiagStore.append(now, level, cleanTag, text, v.count, false)
            }
        } catch (_: Throwable) { }
    }

    /** Un evento con la hora que ya tiene (cierres del sistema, líneas del logcat). Sin freno. */
    internal fun recordAt(at: Long, level: Char, tag: String, text: String, count: Int = 1) {
        if (ctx == null) return
        post { DiagStore.append(at, level, DiagEvent.cleanTag(tag), text, count, false) }
    }

    /**
     * La caída que está matando el proceso. Se escribe EN EL ACTO y con `fsync`, en el hilo
     * que cae: no hay un "después". Lo llama el manejador global de excepciones.
     */
    fun fatal(thread: Thread?, t: Throwable) {
        if (ctx == null) return
        try {
            val name = thread?.name ?: "?"
            val text = DiagSanitizer.sanitizeTrace("Excepción no atrapada en el hilo '$name'\n" + DiagSanitizer.describe(t, 12))
            DiagStore.append(System.currentTimeMillis(), DiagEvent.LEVEL_FATAL, "crash", text, 1, true)
        } catch (_: Throwable) { }
    }

    // ── interruptor de la subida ─────────────────────────────────────────────────────

    fun isUploadEnabled(): Boolean = DiagStore.isEnabled()

    /** Campos que `syncDeviceInfo` publica en `devices/<id>` para que el panel sepa cómo está esto. */
    fun deviceFields(): Map<String, Any> = try {
        mapOf("diagVersion" to VERSION, "diagUploadEnabled" to DiagStore.isEnabled())
    } catch (_: Throwable) {
        emptyMap()
    }

    /** Lo llama el comando `SET_DIAGNOSTICS_UPLOAD`. Deja constancia en el registro y en el panel. */
    fun setUploadEnabled(context: Context, enabled: Boolean) {
        DiagStore.setEnabled(enabled)
        i("diag", "La subida del registro al panel quedó " + (if (enabled) "ENCENDIDA" else "APAGADA") + " (comando del panel)")
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.reportFields(context, mapOf("diagUploadEnabled" to enabled))
        } catch (_: Throwable) { }
    }

    // ── System.err ───────────────────────────────────────────────────────────────────

    /** Una línea que alguien escribió en `System.err`. La llama [DiagErrTee] desde cualquier hilo. */
    internal fun onErrLine(line: String) {
        if (ctx == null) return
        // Filtro barato en el hilo que escribe: casi ninguna línea de System.err es de una excepción.
        val candidate = errBlockOpen || line.contains("Exception") || line.contains("Error") ||
            line.contains("Throwable") || line.startsWith("Caused by")
        if (!candidate) return
        post {
            errGrouper.feed(line)
            errBlockOpen = errGrouper.hasOpenBlock
            errFlush?.cancel(false)
            errFlush = null
            if (errBlockOpen) {
                // Un único vaciado puntual (no periódico) por si esta era la última línea del trace.
                errFlush = try {
                    executor.schedule(Runnable {
                        try { errGrouper.flush(); errBlockOpen = false } catch (_: Throwable) { }
                    }, 800, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) { null }
            }
        }
    }

    private fun storeSwallowed(text: String) {
        try {
            val clean = DiagSanitizer.sanitizeTrace(text)
            val head = clean.lineSequence().firstOrNull() ?: return
            val now = System.currentTimeMillis()
            val v = limiter.check(DiagEvent.LEVEL_WARN, "err", head, now)
            if (!v.keep) return
            DiagStore.append(now, DiagEvent.LEVEL_WARN, "err", clean, v.count, false)
        } catch (_: Throwable) { }
    }

    // ── internos ─────────────────────────────────────────────────────────────────────

    private fun post(block: () -> Unit) {
        try {
            executor.execute {
                try { block() } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    private fun logStart() {
        val app = ctx ?: return
        try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            @Suppress("DEPRECATION")
            val code = pi.versionCode
            val unlocked = try {
                (app.getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked ?: true
            } catch (_: Throwable) { true }
            val msg = "Proceso iniciado: v${pi.versionName} ($code), Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, almacenamiento " +
                (if (unlocked) "desbloqueado" else "BLOQUEADO (arranque directo)")
            DiagStore.append(System.currentTimeMillis(), DiagEvent.LEVEL_INFO, "app", DiagSanitizer.sanitize(msg), 1, false)
        } catch (_: Throwable) { }
    }
}
