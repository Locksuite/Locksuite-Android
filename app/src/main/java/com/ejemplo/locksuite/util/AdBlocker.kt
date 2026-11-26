package com.ejemplo.locksuite.util

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

object AdBlocker {

    @Volatile private var blockedSet: Set<String> = emptySet()
    @Volatile var isReady: Boolean = false
        private set
    @Volatile private var isLoading: Boolean = false
    @Volatile private var lastFailedLoadAtMs: Long = 0L
    private const val RETRY_COOLDOWN_MS = 60_000L
    private val WS_REGEX = Regex("\\s+")
    // Permite etiquetas válidas con guiones internos y TLDs punycode (ej. xn--p1ai)
    private val DOMAIN_REGEX =
        Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")

    private const val ASSET_FILENAME = "hagezi-light.txt"
    private const val LOCAL_FILENAME = "adblock_list.txt" // Permite actualizarla en caliente en el futuro

    /**
     * Parsea una línea de archivo hosts / blocklist.
     * Elimina comentarios en línea `#`, prefijos de sintaxis adblock y valida el dominio
     * incluyendo TLDs con guiones/punycode (`xn--...`).
     */
    internal fun parseHostLine(rawLine: String): String? {
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty() || line.startsWith("!")) return null

        // Si la línea contiene espacios (ej. "0.0.0.0 adserver.com"), extraemos el dominio (la última parte)
        var domain = line.split(WS_REGEX).lastOrNull()?.lowercase()?.trim()
        if (domain.isNullOrEmpty()) return null

        domain = domain
            .removePrefix("||")
            .removePrefix("*.")
            .removePrefix(".")
            .substringBefore("^")
            .substringBefore("/")
            .removeSuffix(".")

        if (domain == "0.0.0.0" || domain == "127.0.0.1" || domain == "localhost") return null
        return if (DOMAIN_REGEX.matches(domain)) domain else null
    }

    /**
     * Carga la lista de dominios bloqueados en segundo plano de forma perezosa:
     * solo se carga si `global_ad_blocking` está activo, evitando consumir RAM cuando
     * está apagado, y reintenta con enfriamiento si falló una carga previa.
     */
    fun loadAsync(context: Context) {
        if (isReady || isLoading) return
        synchronized(this) {
            if (isReady || isLoading) return
            val appContext = context.applicationContext
            val enabled = try {
                PrefsHelper.getMdmPrefs(appContext).getBoolean("global_ad_blocking", false)
            } catch (_: Exception) {
                false
            }
            if (!enabled) return

            val now = try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
            if (lastFailedLoadAtMs != 0L && now - lastFailedLoadAtMs < RETRY_COOLDOWN_MS) return

            isLoading = true
            Thread({
                try {
                    val localFile = File(appContext.filesDir, LOCAL_FILENAME)
                    val reader: BufferedReader = if (localFile.exists()) {
                        BufferedReader(FileReader(localFile))
                    } else {
                        BufferedReader(InputStreamReader(appContext.assets.open(ASSET_FILENAME)))
                    }

                    // Cargamos con un tamaño inicial estimado de ~60,000 dominios para evitar redimensionar el set en runtime
                    val parsed = HashSet<String>(60_000)
                    reader.useLines { lines ->
                        lines.forEach { rawLine ->
                            parseHostLine(rawLine)?.let { parsed.add(it) }
                        }
                    }

                    blockedSet = parsed // Publicación atómica (cambio instantáneo una vez cargado)
                    isReady = true
                    lastFailedLoadAtMs = 0L
                    android.util.Log.i("AdBlocker", "Lista de bloqueo de anuncios cargada: ${parsed.size} dominios.")
                } catch (e: Exception) {
                    lastFailedLoadAtMs = try {
                        android.os.SystemClock.elapsedRealtime()
                    } catch (_: Throwable) {
                        System.currentTimeMillis()
                    }
                    android.util.Log.e("AdBlocker", "Error cargando lista de bloqueo de anuncios: ${e.message}")
                } finally {
                    isLoading = false
                }
            }, "LockSuiteAdBlockerLoad").start()
        }
    }

    /**
     * Comprueba en tiempo de ejecución si un dominio de consulta DNS debe ser bloqueado.
     * Implementa la búsqueda eficiente de sufijos en HashSet (O(1)) para no ralentizar el celular.
     */
    fun isBlocked(domain: String): Boolean {
        if (!isReady) return false
        
        val clean = domain.lowercase().removeSuffix(".")
        if (clean.length < 3 || !clean.contains('.')) return false

        // 1. Comprobación directa del dominio completo
        if (blockedSet.contains(clean)) return true

        // 2. Comprobar sufijos progresivamente sin instanciar colecciones ni joinToString
        var dotIndex = clean.indexOf('.')
        while (dotIndex != -1) {
            val suffix = clean.substring(dotIndex + 1)
            // Excluimos si solo queda el TLD (sin ningún otro punto, ej: "com")
            if (!suffix.contains('.')) {
                break
            }
            if (blockedSet.contains(suffix)) {
                return true
            }
            dotIndex = clean.indexOf('.', dotIndex + 1)
        }
        return false
    }
}
