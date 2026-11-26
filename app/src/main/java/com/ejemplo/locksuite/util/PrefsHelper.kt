package com.ejemplo.locksuite.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PrefsHelper {

    /**
     * La instancia cifrada se arma UNA vez por proceso y se reutiliza.
     *
     * ⚠️ 27/9/2026 (batería y CPU) — POR QUÉ, Y POR QUÉ NO HAY QUE VOLVER A "ABRIRLA" CADA VEZ.
     *
     * Antes cada llamada construía un `MasterKey` y un `EncryptedSharedPreferences` nuevos.
     * Eso no es abrir un archivo: Tink descifra con la clave maestra del Android Keystore los
     * dos juegos de claves (el de los nombres y el de los valores) y además prueba cada uno
     * cifrando y descifrando un mensaje de control (`AndroidKeystoreKmsClient.validateAead`).
     * Son unas seis operaciones en el chip de seguridad (TEE) y una decena de llamadas al
     * Keystore POR LLAMADA — decenas a cientos de milisegundos en un equipo de gama baja.
     *
     * Y se llamaba seguido, sin que se viera:
     *  • `PinManager.isPinConfigured()`, desde la vigilancia de accesibilidad
     *    (`AccessibilityEnforcer.evaluate`): cada 60 s, y cada 5 s con la accesibilidad caída.
     *  • `PinManager.getLockoutState()`, desde la pantalla de PIN (`LoginActivity`) y la de
     *    emergencia: cada 1-3 s, en el hilo principal, y SEGUÍA corriendo con esa pantalla en
     *    segundo plano (el bucle de Compose no se frena al apretar Inicio).
     *  • Cada comando del panel (`CommandProcessor`) y cada sincronización completa.
     *
     * Reusar la instancia no cambia qué se lee ni qué se escribe: todas las instancias de un
     * mismo archivo comparten por debajo el MISMO `SharedPreferences` del sistema, y las
     * primitivas de Tink son seguras entre hilos. Es el uso que recomienda la propia librería.
     *
     * El respaldo sin cifrar NO se guarda: si el Keystore falla una vez (arranque directo,
     * keystore reiniciándose), la llamada siguiente vuelve a intentar el cifrado, igual que
     * antes.
     */
    @Volatile private var cifradas: SharedPreferences? = null
    private val candadoCifradas = Any()

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        cifradas?.let { return it }
        val appCtx = context.applicationContext ?: context
        synchronized(candadoCifradas) {
            cifradas?.let { return it }
            return try {
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    appCtx,
                    Constants.PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateFallbackIfNeeded(appCtx, prefs)
                cifradas = prefs
                prefs
            } catch (e: Exception) {
                // Fallback en caso de que falle la inicialización del keystore del sistema.
                // Se usa un archivo separado para NUNCA mezclar claves en texto plano dentro
                // del XML cifrado de Tink (`Constants.PREFS_NAME`), lo cual corrompería el Keyset.
                // No se guarda en `cifradas`: el próximo llamado reintenta.
                appCtx.getSharedPreferences("${Constants.PREFS_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun migrateFallbackIfNeeded(context: Context, encryptedPrefs: SharedPreferences) {
        try {
            val fallbackName = "${Constants.PREFS_NAME}_fallback"
            val sharedPrefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            val fallbackFile = java.io.File(sharedPrefsDir, "$fallbackName.xml")
            if (!fallbackFile.exists()) return

            val fallback = context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
            val entries = fallback.all
            if (entries.isNotEmpty()) {
                val editor = encryptedPrefs.edit()
                var changed = false
                for ((key, value) in entries) {
                    if (encryptedPrefs.contains(key)) continue
                    when (value) {
                        is String -> { editor.putString(key, value); changed = true }
                        is Boolean -> { editor.putBoolean(key, value); changed = true }
                        is Int -> { editor.putInt(key, value); changed = true }
                        is Long -> { editor.putLong(key, value); changed = true }
                        is Float -> { editor.putFloat(key, value); changed = true }
                        is Set<*> -> {
                            editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                            changed = true
                        }
                    }
                }
                if (changed) editor.commit()
            }
            fallback.edit().clear().commit()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(fallbackName)
            }
            if (fallbackFile.exists()) {
                fallbackFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun getMdmPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(Constants.MDM_STATE_PREFS, Context.MODE_PRIVATE)
    }
}
