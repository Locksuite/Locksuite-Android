package com.ejemplo.locksuite.diag

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * EL ARCHIVO DEL REGISTRO EN EL CELULAR y los números que lo acompañan (6/11/2026, B.110).
 *
 * Un archivo de texto (`diag_ring.log`, un renglón por evento, ver [DiagEvent]) más unas
 * preferencias (`diag_state`) con el cursor de subida, el interruptor y algunos marcadores.
 *
 * ── POR QUÉ ESTÁ EN EL ALMACENAMIENTO «PROTEGIDO DEL DISPOSITIVO» ────────────────────
 * Tras un reinicio, hasta que el usuario desbloquea por primera vez, el almacenamiento
 * normal de la app (donde están las `SharedPreferences` cifradas de LockSuite) NO se puede
 * leer. Justo en esa ventana pasan cosas que el dueño necesita ver («el celular se reinició
 * y LockSuite no volvió»). Este registro vive en `createDeviceProtectedStorageContext()`,
 * que SÍ está disponible desde el arranque. Por lo mismo NO usa `PrefsHelper`.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • `seq` nace del archivo Y del cursor de subida: `max(último seq del archivo, último seq
 *    subido) + 1`. Si alguien borra el archivo (limpiar datos, un error) el contador NO
 *    vuelve a 1: los renglones nuevos seguirían numerados por debajo del cursor y no
 *    subirían nunca.
 *  • Antes de agregar se asegura que el archivo termine en salto de línea: un corte de
 *    energía a mitad de escritura deja una línea sin cerrar y el renglón siguiente se
 *    pegaría a ella, perdiendo los dos.
 *  • Cada escritura abre y cierra el archivo (sin buffer propio): si el proceso muere un
 *    milisegundo después, el renglón ya está en el kernel. Son decenas de renglones por día,
 *    no hay nada que optimizar acá. Solo los FATAL (el proceso se está muriendo) hacen
 *    además `fsync`.
 *  • Compactar (reescribir dejando lo más nuevo, ver [DiagRing]) usa un archivo temporal y
 *    `renameTo`: si algo falla en el medio, queda el archivo viejo intacto.
 *  • Todo está envuelto en try/catch y NUNCA lanza hacia afuera: el registro es una ayuda
 *    para diagnosticar, jamás puede ser la causa de una falla de la app. Si el disco
 *    falla 5 veces seguidas se deja de intentar en este proceso.
 *  • Los contadores que consulta cada latido (`pendingCount`, `pendingHasImportant`) están
 *    en memoria: responder «nada que subir» no toca el disco.
 */
internal object DiagStore {

    private const val FILE_NAME = "diag_ring.log"
    private const val TMP_NAME = "diag_ring.tmp"
    private const val PREFS = "diag_state"
    private const val MAX_FILE_BYTES = 160 * 1024L
    private const val MAX_IO_FAILURES = 5

    private const val K_UPLOADED_SEQ = "uploaded_seq"
    private const val K_LAST_UPLOAD_AT = "last_upload_at"
    private const val K_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val K_FAILS = "fail_count"
    private const val K_DAY = "day_key"
    private const val K_UPLOADS_TODAY = "uploads_today"
    private const val K_ENABLED = "upload_enabled"
    private const val K_SNAPSHOT_N = "snapshot_n"

    private val lock = Any()
    private var dir: File? = null
    private var prefs: SharedPreferences? = null
    private var loaded = false
    private var latestSeq = 0L
    private var lastErrorSeq = 0L
    private var fileBytes = 0L
    private var ioFailures = 0

    fun init(context: Context) {
        synchronized(lock) {
            if (dir != null) return
            try {
                val app = context.applicationContext ?: context
                val de = app.createDeviceProtectedStorageContext()
                dir = de.filesDir
                prefs = de.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                dir = null
                prefs = null
            }
        }
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    // ── lectura / escritura del archivo ──────────────────────────────────────────────

    /** Agrega un renglón. Devuelve su `seq`, o 0 si no se pudo. */
    fun append(at: Long, level: Char, tag: String, msg: String, count: Int, sync: Boolean): Long {
        synchronized(lock) {
            try {
                val f = file() ?: return 0L
                if (ioFailures >= MAX_IO_FAILURES) return 0L
                ensureLoadedLocked(f)
                val seq = latestSeq + 1
                val ev = DiagEvent(seq, at, level, tag, msg, count)
                val bytes = (ev.encode() + "\n").toByteArray(Charsets.UTF_8)
                FileOutputStream(f, true).use { out ->
                    out.write(bytes)
                    if (sync) out.fd.sync()
                }
                latestSeq = seq
                fileBytes += bytes.size
                if (ev.isImportant) lastErrorSeq = seq
                if (fileBytes > MAX_FILE_BYTES) compactLocked(f)
                ioFailures = 0
                return seq
            } catch (_: Throwable) {
                ioFailures++
                return 0L
            }
        }
    }

    /** Todos los renglones legibles del archivo, de más viejo a más nuevo. */
    fun readAll(): List<DiagEvent> {
        synchronized(lock) {
            return try {
                val f = file() ?: return emptyList()
                if (!f.exists()) return emptyList()
                f.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.mapNotNull { DiagEvent.decode(it) }.toList() }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun readAfter(seq: Long): List<DiagEvent> = readAll().filter { it.seq > seq }

    fun readLast(n: Int): List<DiagEvent> = readAll().takeLast(n)

    private fun ensureLoadedLocked(f: File) {
        if (loaded) return
        var maxSeq = 0L
        var maxErr = 0L
        var bytes = 0L
        try {
            if (f.exists()) {
                bytes = f.length()
                f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val e = DiagEvent.decode(line) ?: continue
                        if (e.seq > maxSeq) maxSeq = e.seq
                        if (e.isImportant && e.seq > maxErr) maxErr = e.seq
                    }
                }
                // Si el último renglón quedó sin cerrar (corte de energía), se cierra.
                if (bytes > 0) {
                    RandomAccessFile(f, "r").use { raf ->
                        raf.seek(bytes - 1)
                        if (raf.read() != '\n'.code) {
                            FileOutputStream(f, true).use { it.write('\n'.code) }
                            bytes++
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        latestSeq = maxOf(maxSeq, prefs?.getLong(K_UPLOADED_SEQ, 0L) ?: 0L)
        lastErrorSeq = maxErr
        fileBytes = bytes
        loaded = true
    }

    private fun compactLocked(f: File) {
        try {
            val all = f.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.mapNotNull { DiagEvent.decode(it) }.toList() }
            val keep = DiagRing.select(all)
            val tmp = File(f.parentFile, TMP_NAME)
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (e in keep) { w.write(e.encode()); w.write("\n") }
            }
            if (tmp.renameTo(f)) {
                fileBytes = f.length()
            } else {
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    // ── cursor de subida e interruptor ───────────────────────────────────────────────

    fun isEnabled(): Boolean = synchronized(lock) { prefs?.getBoolean(K_ENABLED, true) ?: true }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) { prefs?.edit()?.putBoolean(K_ENABLED, enabled)?.apply() }
    }

    fun uploadedSeq(): Long = synchronized(lock) { prefs?.getLong(K_UPLOADED_SEQ, 0L) ?: 0L }

    /** Foto de los números para [DiagUploadPolicy.decide]. No toca el disco salvo la primera vez. */
    fun policyState(nowMs: Long): DiagUploadPolicy.State {
        synchronized(lock) {
            val f = file()
            if (f != null) ensureLoadedLocked(f)
            val p = prefs
            val uploaded = p?.getLong(K_UPLOADED_SEQ, 0L) ?: 0L
            return DiagUploadPolicy.State(
                enabled = p?.getBoolean(K_ENABLED, true) ?: true,
                pendingCount = (latestSeq - uploaded).coerceAtLeast(0L),
                pendingHasImportant = lastErrorSeq > uploaded,
                lastUploadAt = p?.getLong(K_LAST_UPLOAD_AT, 0L) ?: 0L,
                lastAttemptAt = p?.getLong(K_LAST_ATTEMPT_AT, 0L) ?: 0L,
                failCount = p?.getInt(K_FAILS, 0) ?: 0,
                dayKey = p?.getLong(K_DAY, 0L) ?: 0L,
                uploadsToday = p?.getInt(K_UPLOADS_TODAY, 0) ?: 0
            )
        }
    }

    fun markAttempt(nowMs: Long) {
        synchronized(lock) { prefs?.edit()?.putLong(K_LAST_ATTEMPT_AT, nowMs)?.apply() }
    }

    fun markUploaded(lastSeq: Long, nowMs: Long) {
        synchronized(lock) {
            val p = prefs ?: return
            val today = DiagUploadPolicy.dayKeyOf(nowMs)
            val sameDay = p.getLong(K_DAY, 0L) == today
            p.edit()
                .putLong(K_UPLOADED_SEQ, maxOf(lastSeq, p.getLong(K_UPLOADED_SEQ, 0L)))
                .putLong(K_LAST_UPLOAD_AT, nowMs)
                .putLong(K_LAST_ATTEMPT_AT, nowMs)
                .putInt(K_FAILS, 0)
                .putLong(K_DAY, today)
                .putInt(K_UPLOADS_TODAY, (if (sameDay) p.getInt(K_UPLOADS_TODAY, 0) else 0) + 1)
                .apply()
        }
    }

    fun markFailure(nowMs: Long) {
        synchronized(lock) {
            val p = prefs ?: return
            p.edit()
                .putLong(K_LAST_ATTEMPT_AT, nowMs)
                .putInt(K_FAILS, (p.getInt(K_FAILS, 0) + 1).coerceAtMost(30))
                .apply()
        }
    }

    /** Número del próximo diagnóstico completo (correlativo, sobrevive reinicios). */
    fun nextSnapshotNumber(): Long {
        synchronized(lock) {
            val p = prefs ?: return System.currentTimeMillis() / 1000
            val n = p.getLong(K_SNAPSHOT_N, 0L) + 1
            p.edit().putLong(K_SNAPSHOT_N, n).apply()
            return n
        }
    }

    // ── marcadores varios (cursores de logcat y de cierres) ─────────────────────────

    fun getLong(key: String, def: Long = 0L): Long = synchronized(lock) { prefs?.getLong("m_$key", def) ?: def }
    fun putLong(key: String, v: Long) { synchronized(lock) { prefs?.edit()?.putLong("m_$key", v)?.apply() } }
    fun getString(key: String): String? = synchronized(lock) { prefs?.getString("m_$key", null) }
    fun putString(key: String, v: String?) { synchronized(lock) { prefs?.edit()?.putString("m_$key", v)?.apply() } }
}
