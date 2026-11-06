package com.ejemplo.locksuite.diag

/**
 * CUÁNDO Y CUÁNTO SUBE EL CELULAR de su registro (6/11/2026, B.110).
 *
 * La regla de oro del pedido del dueño fue «que no consuma batería». Por eso el celular
 * NO tiene un temporizador propio para esto: la subida se engancha en el latido que el
 * equipo YA hace (`FirebaseDeviceSync.syncLastSeenOnly`, a lo sumo cada 3 minutos) y esta
 * política decide, con unos pocos números en memoria, si ESE latido aprovecha para subir.
 * La mayoría de los latidos responden «nada que subir» sin leer ni un archivo.
 *
 * ── LAS REGLAS ───────────────────────────────────────────────────────────────────────
 *  • Interruptor apagado (`enabled = false`): no sube nada, nunca.
 *  • Hay algo IMPORTANTE pendiente (error o caída): sube en el próximo latido, pero no más
 *    de una vez cada [URGENT_GAP_MS] (2 min). Así un error se ve en el panel en minutos.
 *  • Solo hay renglones informativos: sube a lo sumo cada [ROUTINE_GAP_MS] (1 h).
 *  • Tope diario de subidas ([MAX_UPLOADS_PER_DAY]): una racha de errores no puede
 *    convertirse en tráfico y batería sin límite.
 *  • Si la última subida FALLÓ (por ejemplo las reglas de Firebase todavía no se
 *    desplegaron y devuelven «permission denied»), se espera [backoffMs] antes de
 *    reintentar: 5 min, 10, 20… hasta 6 h. Sin esto, un fallo permanente se reintentaría
 *    en cada latido para siempre.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • El lote tiene tope de cantidad Y de caracteres, y cuando hay más pendientes se suben
 *    los MÁS NUEVOS (lo viejo ya no importa tanto) y se informa cuántos se saltearon. El
 *    cursor avanza igual: no se reintenta el atraso eterno.
 *  • En Firebase se conservan los últimos [SERVER_KEEP] renglones: cada subida borra la
 *    clave `seq - SERVER_KEEP` de cada renglón que sube, así que la base no crece sin
 *    límite y NO hace falta ninguna función programada que limpie.
 *  • Reloj hacia atrás: si `now < lastUploadAt` se trata como «hace mucho», no como «recién».
 *
 * Kotlin puro: lo prueba `DiagUploadPolicyTest`.
 */
object DiagUploadPolicy {

    const val URGENT_GAP_MS = 2 * 60_000L
    const val ROUTINE_GAP_MS = 60 * 60_000L
    const val MAX_UPLOADS_PER_DAY = 48
    const val MAX_BATCH_EVENTS = 200
    const val MAX_BATCH_CHARS = 40_000
    /** Renglones que se dejan en Firebase por celular. */
    const val SERVER_KEEP = 400L
    /** Diagnósticos completos que se dejan en Firebase por celular. */
    const val SNAPSHOTS_KEEP = 3L

    private const val BACKOFF_BASE_MS = 5 * 60_000L
    private const val BACKOFF_MAX_MS = 6 * 60 * 60_000L
    private const val DAY_MS = 86_400_000L

    enum class Decision { UPLOAD, DISABLED, NOTHING_PENDING, WAIT_GAP, WAIT_BACKOFF, DAILY_CAP }

    /** Lo que la política necesita saber. Todo sale de contadores en memoria / preferencias. */
    class State(
        val enabled: Boolean,
        val pendingCount: Long,
        val pendingHasImportant: Boolean,
        val lastUploadAt: Long,
        val lastAttemptAt: Long,
        val failCount: Int,
        val dayKey: Long,
        val uploadsToday: Int
    )

    fun decide(s: State, nowMs: Long): Decision {
        if (!s.enabled) return Decision.DISABLED
        if (s.pendingCount <= 0) return Decision.NOTHING_PENDING
        val uploads = if (s.dayKey == dayKeyOf(nowMs)) s.uploadsToday else 0
        if (uploads >= MAX_UPLOADS_PER_DAY) return Decision.DAILY_CAP
        if (s.failCount > 0 && s.lastAttemptAt > 0) {
            val since = nowMs - s.lastAttemptAt
            if (since in 0 until backoffMs(s.failCount)) return Decision.WAIT_BACKOFF
        }
        val gap = if (s.lastUploadAt <= 0 || nowMs < s.lastUploadAt) Long.MAX_VALUE else nowMs - s.lastUploadAt
        val need = if (s.pendingHasImportant) URGENT_GAP_MS else ROUTINE_GAP_MS
        return if (gap >= need) Decision.UPLOAD else Decision.WAIT_GAP
    }

    /** 5 min, 10, 20, 40… con tope de 6 h. */
    fun backoffMs(failCount: Int): Long {
        if (failCount <= 0) return 0L
        val shift = (failCount - 1).coerceAtMost(10)
        return (BACKOFF_BASE_MS shl shift).coerceAtMost(BACKOFF_MAX_MS)
    }

    /** Día UTC como número entero: alcanza para «¿es el mismo día que la última vez?». */
    fun dayKeyOf(nowMs: Long): Long = nowMs / DAY_MS

    class Batch(val events: List<DiagEvent>, val skipped: Int)

    /**
     * Recorta los pendientes (de más viejo a más nuevo) al tope del lote. Si no entran
     * todos se quedan los más nuevos y se cuenta cuántos se saltearon.
     */
    fun nextBatch(pending: List<DiagEvent>): Batch {
        if (pending.isEmpty()) return Batch(emptyList(), 0)
        var chars = 0
        var from = pending.size
        while (from > 0) {
            val e = pending[from - 1]
            val cost = e.msg.length + 64
            if (pending.size - from + 1 > MAX_BATCH_EVENTS || chars + cost > MAX_BATCH_CHARS) break
            chars += cost
            from--
        }
        // Siempre sube al menos el más nuevo, por largo que sea (el mensaje ya viene recortado).
        if (from == pending.size) from = pending.size - 1
        return Batch(pending.subList(from, pending.size).toList(), from)
    }

    /** Clave del renglón en Firebase: `e` + 10 dígitos, para que ordene como texto. */
    fun eventKey(seq: Long): String = "e" + seq.toString().padStart(10, '0')

    /** Clave de un diagnóstico completo. */
    fun snapshotKey(n: Long): String = "s" + n.toString().padStart(8, '0')

    /** Claves de renglones viejos que hay que borrar de Firebase al subir este lote. */
    fun staleKeys(batch: List<DiagEvent>): List<String> =
        batch.map { it.seq - SERVER_KEEP }.filter { it >= 1 }.map { eventKey(it) }

    /** Lo que se escribe en Firebase para un renglón (claves cortas para ahorrar datos). */
    fun eventMap(e: DiagEvent): Map<String, Any> {
        val m = HashMap<String, Any>(6)
        m["t"] = e.at
        m["l"] = e.level.toString()
        m["g"] = e.tag
        m["m"] = e.msg
        if (e.count > 1) m["n"] = e.count
        return m
    }
}
