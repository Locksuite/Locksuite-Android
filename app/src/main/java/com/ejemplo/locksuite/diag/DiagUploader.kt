package com.ejemplo.locksuite.diag

import android.content.Context
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * LA SUBIDA A FIREBASE del registro de diagnóstico (6/11/2026, B.110).
 *
 * Dos caminos, los dos hacia `deviceDiag/<id>` (nodo APARTE de `devices/<id>` a propósito:
 * el panel descarga TODO `devices/` en su oyente principal y no puede cargarse con el
 * registro de cada celular):
 *
 *  1. [flushIfDue] — lo llama el latido (`FirebaseDeviceSync.syncLastSeenOnly`, a lo sumo
 *     cada 3 min). Sube los renglones nuevos si [DiagUploadPolicy] lo permite. Casi siempre
 *     responde «nada que subir» con unas comparaciones en memoria, sin tocar disco ni red.
 *  2. [collectAndUpload] — lo llama el comando `COLLECT_DIAGNOSTICS` del panel. Junta el
 *     diagnóstico completo ([DiagCollector.snapshot]) y lo sube JUNTO con los renglones
 *     pendientes en una sola escritura atómica.
 *
 * Estructura en Firebase:
 *
 *     deviceDiag/<id>/events/e0000000123 = { t, l, g, m, n? }   (los últimos 400)
 *     deviceDiag/<id>/snapshots/s00000004 = { t, at, pkgs, ver, system, exits, logcat, packages }  (los últimos 3)
 *     deviceDiag/<id>/meta = { lastUploadAt, lastCollectAt, lastSeq, appVersion, diagVersion, skipped? }
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • Un fallo de subida NO se traga en silencio: se anota en `devices/<id>/diagUploadError`
 *    (que el panel muestra) y se reintenta con retroceso. El caso real: la app se actualiza
 *    ANTES de desplegar las reglas de la base y todas las escrituras dan «permission denied».
 *  • Una sola subida a la vez (`uploading`). Si el callback nunca vuelve (sin red, Firebase
 *    encola la escritura) el cerrojo se libera solo a los 10 min en vez de quedar tomado
 *    hasta que muera el proceso.
 *  • Los renglones viejos se borran en la MISMA escritura (`events/<seq-400> = null`): la
 *    base no crece sin tope y no hace falta ninguna función programada.
 *  • Con el interruptor apagado (`SET_DIAGNOSTICS_UPLOAD`) NADA sale del teléfono: ni el
 *    latido ni `COLLECT_DIAGNOSTICS`. El pedido manual contesta con el motivo.
 */
object DiagUploader {

    private const val STALE_LOCK_MS = 10 * 60_000L
    private const val COLLECT_TIMEOUT_S = 20L

    private val uploading = AtomicBoolean(false)
    @Volatile private var uploadingSince = 0L

    private fun claim(now: Long): Boolean {
        if (uploading.compareAndSet(false, true)) { uploadingSince = now; return true }
        if (now - uploadingSince > STALE_LOCK_MS || now < uploadingSince) { uploadingSince = now; return true }
        return false
    }

    private fun release() { uploading.set(false) }

    private fun versionString(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        "${pi.versionName} (${pi.versionCode})"
    } catch (_: Throwable) { "?" }

    private fun ref(ctx: Context) =
        FirebaseDatabase.getInstance().getReference("deviceDiag/${FirebaseDeviceSync.deviceId(ctx)}")

    /** Escrituras comunes: los renglones del lote, el borrado de los viejos y `meta`. */
    private fun putBatch(updates: MutableMap<String, Any?>, batch: DiagUploadPolicy.Batch, ctx: Context) {
        for (e in batch.events) updates["events/" + DiagUploadPolicy.eventKey(e.seq)] = DiagUploadPolicy.eventMap(e)
        for (k in DiagUploadPolicy.staleKeys(batch.events)) updates["events/$k"] = null
        if (batch.events.isNotEmpty()) {
            updates["meta/lastUploadAt"] = ServerValue.TIMESTAMP
            updates["meta/lastSeq"] = batch.events.last().seq
        }
        updates["meta/skipped"] = if (batch.skipped > 0) batch.skipped else null
        updates["meta/appVersion"] = versionString(ctx)
        updates["meta/diagVersion"] = DiagLog.VERSION
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // 1. EN EL LATIDO
    // ═════════════════════════════════════════════════════════════════════════════════

    /**
     * Llamar desde el HILO DE SINCRONIZACIÓN, cuando un latido acaba de salir bien (o sea:
     * hay sesión y red). No bloquea: la escritura es asíncrona.
     */
    fun flushIfDue(context: Context) {
        val ctx = context.applicationContext ?: context
        try {
            if (!DiagLog.isReady) return
            val now = System.currentTimeMillis()
            if (!DiagStore.isEnabled()) return

            // Los errores del logcat y los cierres del proceso se anotan de pasada (como mucho
            // cada 30 min); si encuentran algo, quedan pendientes para esta misma pasada.
            DiagCollector.sweepLogcatIfDue(ctx, now)

            val st = DiagStore.policyState(now)
            if (DiagUploadPolicy.decide(st, now) != DiagUploadPolicy.Decision.UPLOAD) return
            if (!claim(now)) return

            val batch = DiagUploadPolicy.nextBatch(DiagStore.readAfter(DiagStore.uploadedSeq()))
            if (batch.events.isEmpty()) { release(); return }
            val lastSeq = batch.events.last().seq
            val hadFailures = st.failCount > 0

            DiagStore.markAttempt(now)
            val updates = HashMap<String, Any?>()
            putBatch(updates, batch, ctx)
            ref(ctx).updateChildren(updates)
                .addOnSuccessListener {
                    DiagStore.markUploaded(lastSeq, System.currentTimeMillis())
                    release()
                    if (hadFailures) {
                        try { FirebaseDeviceSync.reportFields(ctx, mapOf("diagUploadError" to null)) } catch (_: Throwable) { }
                    }
                }
                .addOnFailureListener { e ->
                    DiagStore.markFailure(System.currentTimeMillis())
                    release()
                    try {
                        FirebaseDeviceSync.reportFields(
                            ctx, mapOf("diagUploadError" to DiagSanitizer.sanitize(e.message ?: e.toString(), 160))
                        )
                    } catch (_: Throwable) { }
                }
        } catch (_: Throwable) {
            release()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // 2. A PEDIDO DEL PANEL
    // ═════════════════════════════════════════════════════════════════════════════════

    /**
     * Junta el diagnóstico completo y lo sube con los renglones pendientes. BLOQUEA hasta 20 s
     * esperando a Firebase: llamar desde una corrutina de `Dispatchers.IO` (así lo hace
     * `CommandProcessor`), nunca desde el hilo principal.
     *
     * @return `true` si quedó subido; si no, `false` y el motivo para el ack del panel.
     */
    fun collectAndUpload(context: Context, packages: List<String>): Pair<Boolean, String?> {
        val ctx = context.applicationContext ?: context
        if (!DiagLog.isReady) return false to "El registro de diagnóstico no está iniciado en este celular."
        if (!DiagStore.isEnabled()) {
            return false to "La subida del registro está APAGADA en este celular (se enciende desde el panel)."
        }
        val now = System.currentTimeMillis()
        try {
            DiagCollector.sweepLogcatIfDue(ctx, now, force = true)
            val snap = DiagCollector.snapshot(ctx, packages)
            val batch = DiagUploadPolicy.nextBatch(DiagStore.readAfter(DiagStore.uploadedSeq()))

            val n = DiagStore.nextSnapshotNumber()
            val updates = HashMap<String, Any?>()
            putBatch(updates, batch, ctx)
            updates["snapshots/" + DiagUploadPolicy.snapshotKey(n)] = mapOf(
                "t" to ServerValue.TIMESTAMP,
                "at" to now,
                "pkgs" to packages.joinToString(","),
                "ver" to versionString(ctx),
                "diag" to DiagLog.VERSION,
                "system" to snap.system,
                "exits" to snap.exits,
                "logcat" to snap.logcat,
                "packages" to snap.packages
            )
            if (n > DiagUploadPolicy.SNAPSHOTS_KEEP) {
                updates["snapshots/" + DiagUploadPolicy.snapshotKey(n - DiagUploadPolicy.SNAPSHOTS_KEEP)] = null
            }
            updates["meta/lastCollectAt"] = ServerValue.TIMESTAMP

            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val error = AtomicReference<String?>(null)
            DiagStore.markAttempt(now)
            FirebaseDeviceSync.runAuthenticated {
                try {
                    ref(ctx).updateChildren(updates)
                        .addOnSuccessListener { ok.set(true); latch.countDown() }
                        .addOnFailureListener { e -> error.set(e.message ?: e.toString()); latch.countDown() }
                } catch (t: Throwable) {
                    error.set(t.message ?: t.toString())
                    latch.countDown()
                }
            }
            val finished = latch.await(COLLECT_TIMEOUT_S, TimeUnit.SECONDS)
            return when {
                finished && ok.get() -> {
                    if (batch.events.isNotEmpty()) DiagStore.markUploaded(batch.events.last().seq, System.currentTimeMillis())
                    true to null
                }
                finished -> {
                    DiagStore.markFailure(System.currentTimeMillis())
                    false to ("Firebase rechazó la subida: " + DiagSanitizer.sanitize(error.get(), 160))
                }
                else -> false to "Firebase no contestó en ${COLLECT_TIMEOUT_S} s (sin red o sin sesión). Si el equipo tiene red, el diagnóstico puede aparecer solo más tarde."
            }
        } catch (t: Throwable) {
            return false to ("No se pudo armar el diagnóstico: " + DiagSanitizer.headline(t))
        }
    }
}
