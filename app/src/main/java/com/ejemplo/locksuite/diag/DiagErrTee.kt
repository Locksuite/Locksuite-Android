package com.ejemplo.locksuite.diag

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ENVOLTORIO DE `System.err` (6/11/2026, B.110).
 *
 * `Throwable.printStackTrace()` escribe en `System.err`. LockSuite tiene cientos de
 * `catch (e: Exception) { e.printStackTrace() }`: el error se imprime en el logcat (que
 * nadie está mirando) y el programa sigue. Este envoltorio deja pasar TODO a la salida
 * original —el logcat se ve exactamente igual que antes— y además le pasa cada línea a
 * [DiagLog], que reconoce las excepciones y las registra. Es la forma de ver los errores
 * tragados de TODA la app sin editar un solo `catch`.
 *
 * ── LO QUE NO HAY QUE "SIMPLIFICAR" ──────────────────────────────────────────────────
 *  • Lo que se escribe pasa SIEMPRE primero a la salida original, y un fallo de nuestro lado
 *    se traga: este envoltorio no puede perder ni romper la salida de nadie.
 *  • No hay recursión posible: ni [DiagLog] ni [DiagStore] escriben en `System.err`
 *    (por eso no usan `printStackTrace()`; si alguien lo agrega ahí, el registro se
 *    realimenta a sí mismo).
 *  • Los bytes se juntan por línea y se decodifican como UTF-8 recién al terminar la
 *    línea: decodificar byte a byte rompería los acentos. Las líneas se cortan en 2.000
 *    bytes para que un `print` sin saltos no acumule memoria sin fin.
 *  • Se instala UNA vez (`AtomicBoolean`); instalarlo dos veces anidaría dos envoltorios.
 */
internal object DiagErrTee {

    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        try {
            val original: PrintStream? = System.err
            System.setErr(PrintStream(TeeStream(original), true, "UTF-8"))
        } catch (_: Throwable) {
            installed.set(false)
        }
    }

    private class TeeStream(private val original: PrintStream?) : OutputStream() {
        private val line = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            try { original?.write(b) } catch (_: Throwable) { }
            feed(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            try { original?.write(b, off, len) } catch (_: Throwable) { }
            for (i in off until off + len) feed(b[i].toInt())
        }

        override fun flush() {
            try { original?.flush() } catch (_: Throwable) { }
        }

        private fun feed(v: Int) {
            try {
                val c = v and 0xFF
                if (c == '\n'.code) {
                    val s = String(line.toByteArray(), Charsets.UTF_8)
                    line.reset()
                    DiagLog.onErrLine(s)
                } else if (c != '\r'.code && line.size() < 2000) {
                    line.write(c)
                }
            } catch (_: Throwable) { }
        }
    }
}
