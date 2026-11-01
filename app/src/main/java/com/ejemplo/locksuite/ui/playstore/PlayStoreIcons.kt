package com.ejemplo.locksuite.ui.playstore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.ejemplo.locksuite.mdm.PlayStoreCatalog
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Íconos de la pantalla de la Tienda Play (29/10/2026, B.101).
 *
 *  - App **instalada**: el ícono REAL de la app, tal como lo ve el sistema.
 *  - App **todavía no instalada**: el ícono que el panel cargó (`iconUrl`, un https del CDN
 *    de Google que ya validó `PlayStoreCatalog.cleanIconUrl`), bajado una sola vez y
 *    guardado en el caché del equipo.
 *  - Sin ícono (sin dirección, sin red, imagen rota): no devuelve nada y la pantalla deja
 *    el recuadro de color con la inicial. Nunca se queda esperando ni muestra un hueco.
 *
 * ## Por qué es seguro bajar la imagen desde acá
 *  - LockSuite está EXCLUIDA del túnel DNS (`KosherVpnService`), así que este pedido no
 *    pasa por el filtro; la dirección ya viene limitada a hosts de Google y acá se vuelve a
 *    comprobar.
 *  - Sin redirecciones (una redirección podría salir del dominio validado), con tope de
 *    tamaño y de tiempo, y la imagen se decodifica reducida (nunca a su tamaño completo).
 *  - Una descarga fallida se anota 2 minutos: sin red no se reintenta 30 veces por cada
 *    vez que se redibuja la pantalla.
 */
internal object PlayStoreIcons {

    private const val TAG = "PlayStoreIcons"

    /** Una imagen más grande que esto no es un ícono: se descarta. */
    private const val MAX_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val CACHE_DIR = "play_store_icons"
    private const val RETRY_AFTER_MS = 2 * 60 * 1000L

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Por cantidad, no por bytes: son ~48 imágenes chicas. */
    private val memory = LruCache<String, Bitmap>(48)

    /** Quién espera cada ícono que se está cargando (así una clave se carga una sola vez). */
    private val waiting = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    /** Cuándo falló por última vez cada descarga (milisegundos de reloj). */
    private val failedAt = HashMap<String, Long>()

    /** Clave del caché; `null` si no hay de dónde sacar un ícono. */
    fun keyFor(packageName: String, installed: Boolean, iconUrl: String?): String? = when {
        installed -> "pkg:$packageName"
        iconUrl != null -> "url:$iconUrl"
        else -> null
    }

    /**
     * Pide el ícono. Si ya está en memoria llama a [onReady] en el acto (hilo principal);
     * si no, lo carga en segundo plano y llama a [onReady] en el hilo principal cuando está.
     * Si no se pudo cargar, [onReady] no se llama nunca.
     */
    fun load(
        context: Context,
        packageName: String,
        installed: Boolean,
        iconUrl: String?,
        sizePx: Int,
        onReady: (Bitmap) -> Unit
    ) {
        val key = keyFor(packageName, installed, iconUrl) ?: return
        memory.get(key)?.let {
            onReady(it)
            return
        }
        if (!installed) {
            val failed = synchronized(failedAt) { failedAt[key] }
            if (failed != null && System.currentTimeMillis() - failed in 0..RETRY_AFTER_MS) return
        }
        synchronized(waiting) {
            val others = waiting[key]
            if (others != null) {
                others.add(onReady)
                return
            }
            waiting[key] = mutableListOf(onReady)
        }
        val app = context.applicationContext
        executor.execute {
            val bitmap: Bitmap? = try {
                if (installed) installedIcon(app, packageName, sizePx)
                else if (iconUrl != null) remoteIcon(app, iconUrl, sizePx)
                else null
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo cargar el ícono de $packageName: ${e.message}")
                null
            }
            if (bitmap != null) {
                memory.put(key, bitmap)
            } else if (!installed) {
                synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
            }
            val callbacks = synchronized(waiting) { waiting.remove(key) } ?: emptyList()
            if (bitmap != null) {
                mainHandler.post {
                    for (cb in callbacks) cb(bitmap)
                }
            }
        }
    }

    // ── Ícono de una app instalada ──────────────────────────────────────────────

    private fun installedIcon(context: Context, packageName: String, sizePx: Int): Bitmap? {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val size = sizePx.coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    // ── Ícono bajado de la red (con caché en disco) ─────────────────────────────

    private fun remoteIcon(context: Context, url: String, sizePx: Int): Bitmap? {
        // Defensa en profundidad: aunque el catálogo ya la validó, esta función es la que
        // abre la conexión, así que vuelve a comprobar que sea https hacia Google.
        if (PlayStoreCatalog.cleanIconUrl(url) == null) return null
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.mkdirs()
        val file = File(dir, sha1(url))
        if (file.isFile && file.length() in 1..MAX_BYTES.toLong()) {
            val cached = decode(file.readBytes(), sizePx)
            if (cached != null) return cached
            file.delete()
        }
        val bytes = download(url) ?: return null
        val bitmap = decode(bytes, sizePx) ?: return null
        try {
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            // Sin lugar en disco no pasa nada: el ícono queda en memoria.
            Log.w(TAG, "No se pudo guardar el ícono en disco: ${e.message}")
        }
        return bitmap
    }

    private fun download(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declared = connection.contentLength
            if (declared > MAX_BYTES) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(if (declared > 0) declared else 16 * 1024)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Descarga de ícono fallida: ${e.message}")
            return null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Decodifica reducida: la mitad del tamaño pedido ya es suficiente, nunca el original entero. */
    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val target = sizePx.coerceAtLeast(16)
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
