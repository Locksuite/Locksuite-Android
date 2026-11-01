package com.ejemplo.locksuite.mdm

/**
 * Cuándo hay que (re)publicar en la nube el verificador del PIN de administrador (1/11/2026, B.104).
 *
 * EL PROBLEMA QUE RESUELVE. El panel no conoce el PIN de un celular: la Cloud Function lo
 * verifica contra `deviceSecrets/<id>/{pinHash,pinSalt}`, que el CELULAR escribe. Hasta hoy lo
 * escribía UNA sola vez, en el instante en que se elegía el PIN, sin esperar confirmación y
 * sin volver a intentarlo nunca. Si esa única escritura caía mal —el celular nuevo todavía
 * sin internet o sin sesión anónima, la sesión recién creada con otro uid que el dueño del
 * nodo, un "Eliminar del panel" que se llevó `deviceSecrets/<id>`— el celular seguía creyendo
 * que estaba todo bien (el PIN local funciona) y el panel contestaba
 * `DEVICE_PIN_NOT_ENROLLED` hasta que alguien cambiaba el PIN a mano para forzar otra escritura.
 *
 * Ahora la publicación se lleva con tres reglas, que son lo que decide este objeto:
 *   1. Se anota QUÉ credenciales confirmó el servidor (`ackedKey`). Si lo que hay en el equipo
 *      no es lo último confirmado, falta publicar.
 *   2. Si falla, se reintenta sola con espera creciente (no martilla la red ni la batería).
 *   3. Aunque esté todo confirmado, de vez en cuando se mira la bandera pública de la nube: si
 *      el panel borró el equipo, la bandera ya no está y se vuelve a publicar.
 *
 * Funciones puras (sin Android, sin Firebase): las prueba `PinCloudSyncPolicyTest`.
 */
object PinCloudSyncPolicy {

    /** Espera tras el primer fallo; se duplica en cada fallo seguido. */
    const val BASE_BACKOFF_MS = 10_000L
    /** Tope de la espera entre intentos fallidos. */
    const val MAX_BACKOFF_MS = 5 * 60_000L
    /** Cada cuánto se mira la bandera pública cuando todo figura confirmado (una lectura de una hoja, mínima). */
    const val CLOUD_CHECK_EVERY_MS = 5 * 60_000L
    /**
     * Una publicación sin respuesta tras este tiempo se da por colgada y se permite otra. Es largo
     * a propósito: sin internet la escritura queda en cola y contesta recién al reconectar, y no
     * hay que apilar intentos mientras tanto. Es solo la red de seguridad por si nunca contesta.
     */
    const val IN_FLIGHT_STALE_MS = 10 * 60_000L

    /** Lo que se pudo saber de la nube sobre este equipo. */
    enum class CloudFlag {
        /** No se pudo leer (sin internet, sin permiso): no se decide nada con esto. */
        UNKNOWN,
        /** `devices/<id>/hasPinConfigured` es `true`. */
        PRESENT,
        /** Se leyó y no es `true`: la nube no sabe que este equipo tiene PIN. */
        ABSENT
    }

    /**
     * Identifica el conjunto de credenciales publicadas: el verificador del PIN (hash+sal) y el
     * código de recuperación. Es solo para COMPARAR "lo del equipo" contra "lo último que el
     * servidor confirmó"; no es un secreto nuevo (el hash ya vive en el equipo y en la nube).
     * Vacío cuando no hay PIN en el equipo.
     */
    fun credentialKey(pinHash: String?, pinSalt: String?, recoveryCode: String?): String {
        if (pinHash.isNullOrEmpty() || pinSalt.isNullOrEmpty()) return ""
        return "v1|$pinSalt|$pinHash|${recoveryCode.orEmpty()}"
    }

    /**
     * ¿Hay que publicar?
     *
     * - Sin PIN en el equipo ([localKey] vacío): nunca. No hay nada que decir, y publicar vacío
     *   borraría un verificador bueno.
     * - Lo del equipo no es lo último que el servidor confirmó: sí.
     * - Estaba confirmado, pero la nube dice que no tiene PIN: sí (se perdió del lado del servidor).
     * - Si no se pudo leer la nube: se confía en la confirmación.
     */
    fun needsPublish(localKey: String?, ackedKey: String?, cloudFlag: CloudFlag): Boolean {
        if (localKey.isNullOrEmpty()) return false
        if (ackedKey != localKey) return true
        return cloudFlag == CloudFlag.ABSENT
    }

    /** Espera antes del próximo intento tras [failures] fallos seguidos (0 = ninguno). */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        // 10 s, 20 s, 40 s, 80 s, 160 s, 300 s… El desplazamiento se acota para no desbordar el Long.
        val shift = (failures - 1).coerceAtMost(20)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * ¿Se puede intentar ya? Sin fallos previos, siempre. Con fallos, solo pasada la espera.
     * Un reloj movido hacia atrás nunca bloquea: ante la duda se intenta.
     */
    fun mayAttempt(nowMs: Long, lastAttemptMs: Long, failures: Int): Boolean {
        if (failures <= 0 || lastAttemptMs <= 0L) return true
        val elapsed = nowMs - lastAttemptMs
        if (elapsed < 0L) return true
        return elapsed >= backoffMs(failures)
    }

    /** ¿Toca volver a mirar la bandera pública de la nube? */
    fun shouldCheckCloudFlag(nowMs: Long, lastCheckMs: Long): Boolean {
        if (lastCheckMs <= 0L) return true
        val elapsed = nowMs - lastCheckMs
        return elapsed < 0L || elapsed >= CLOUD_CHECK_EVERY_MS
    }

    /**
     * ¿Se puede lanzar una publicación nueva?
     *
     * - No hay ninguna en curso: sí.
     * - Hay una en curso con OTRAS credenciales (se cambió el PIN mientras tanto): sí. Lo que
     *   está en vuelo ya es viejo y no hay que esperarlo para mandar el PIN vigente.
     * - Hay una en curso con las mismas credenciales: no, salvo que esté colgada.
     */
    fun mayStartPublish(inFlight: Boolean, sameCredentials: Boolean, nowMs: Long, startedMs: Long): Boolean {
        if (!inFlight) return true
        if (!sameCredentials) return true
        return isInFlightStale(nowMs, startedMs)
    }

    /** ¿La publicación en curso lleva tanto sin contestar que hay que darla por colgada? */
    fun isInFlightStale(nowMs: Long, startedMs: Long): Boolean {
        if (startedMs <= 0L) return true
        val elapsed = nowMs - startedMs
        return elapsed < 0L || elapsed >= IN_FLIGHT_STALE_MS
    }
}
