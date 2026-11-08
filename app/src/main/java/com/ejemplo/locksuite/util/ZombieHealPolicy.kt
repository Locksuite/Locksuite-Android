package com.ejemplo.locksuite.util

/**
 * 7/11/2026 (B.112) — CUÁNDO Y CÓMO reparar sola la accesibilidad «zombi». Lógica pura (sin
 * Android) para poder probarla: la ejecuta `AccessibilityZombieHealer`.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * QUÉ ES UN ZOMBI Y POR QUÉ SE PUEDE REPARAR SIN TOCAR EL CELULAR
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * Verificado contra el código fuente de Android 11 (`android-11.0.0_r48`, clases
 * `AccessibilityManagerService` y `AccessibilityUserState`):
 *
 *  - Cuando el proceso de un servicio de accesibilidad muere enlazado, Android lo anota en
 *    `mCrashedServices`, y `updateServicesLocked()` SALTEA todo componente que esté ahí (o en
 *    `mBindingServices`). Sigue en `ENABLED_ACCESSIBILITY_SERVICES` («Activado» en Ajustes) pero
 *    nadie lo vuelve a enlazar: eso es el zombi.
 *  - Dos cosas lo sacan de esa lista sin intervención del usuario:
 *      1. `onPackageUpdateFinished()` hace `mCrashedServices.removeIf(paquete)` y
 *         `getBindingServicesLocked().removeIf(paquete)` y vuelve a enlazar. Es lo que pasó el
 *         6/11 a las 14:49 al instalar la 0.6.79. **Reinstalar el MISMO APK también es una
 *         actualización del paquete** (Android permite reemplazar con el mismo versionCode;
 *         solo rechaza bajar de versión). → acción REINSTALL.
 *      2. `AccessibilityUserState.updateCrashedServicesIfNeededLocked()` lo saca cuando deja de
 *         estar en los servicios habilitados («users toggle the switch bar to retry»): escribir
 *         el ajuste sin nuestro componente y volver a ponerlo. Necesita `WRITE_SECURE_SETTINGS`.
 *         Es más liviano (no reinicia el proceso) pero solo limpia `mCrashedServices`, no
 *         `mBindingServices`. → acción TOGGLE_SETTING, y si no alcanza, REINSTALL.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * LÍMITES (lo que impide un bucle de reinstalaciones)
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *  - Solo con el equipo encendido hace ≥ 4 min y el zombi sostenido ≥ 3 min: al arrancar, Android
 *    tarda en enlazar y eso NO es un zombi.
 *  - Reinstalación: como mucho 3 por día y una cada 30 min. El intento se anota ANTES de empezar,
 *    así que un proceso que muere a mitad igual lo cuenta.
 *  - Reloj del equipo: un intento con hora «del futuro» (menos de un día) cuenta como reciente,
 *    o sea que un reloj que se atrasa nunca habilita más intentos. Más de un día en el futuro se
 *    descarta como basura (si no, un reloj que saltó un año atrás apagaría la reparación un año).
 */
object ZombieHealPolicy {

    const val MIN_UPTIME_MS = 4 * 60_000L
    const val MIN_ZOMBIE_MS = 3 * 60_000L
    /** Después de un ciclo del ajuste, cuánto esperar a que haga efecto antes de reinstalar. */
    const val TOGGLE_SETTLE_MS = 90_000L
    const val MIN_GAP_MS = 30 * 60_000L
    const val MAX_REINSTALLS_PER_DAY = 3
    const val DAY_MS = 24 * 60 * 60_000L
    /** Cuántos intentos se guardan en el historial persistido. */
    const val HISTORY_KEEP = 10

    enum class Action { NONE, TOGGLE_SETTING, REINSTALL }

    data class Decision(val action: Action, val why: String)

    data class Input(
        /** `elapsedRealtime` desde que se ve el zombi sin interrupción; null = no está zombi. */
        val zombieSinceElapsed: Long?,
        val nowElapsed: Long,
        val nowWall: Long,
        /** Horas de pared de las reinstalaciones intentadas (persistidas). */
        val reinstallAttemptsWall: List<Long>,
        val canWriteSecureSettings: Boolean,
        /** `elapsedRealtime` del último ciclo del ajuste en este proceso; null = nunca. */
        val lastToggleElapsed: Long?,
        /** No null = no tocar nada (suspendido, purgado, instalación en curso…). */
        val blockedReason: String?,
    )

    fun decide(i: Input): Decision {
        i.blockedReason?.let { return Decision(Action.NONE, "no corresponde: $it") }
        val since = i.zombieSinceElapsed ?: return Decision(Action.NONE, "no está zombi")
        if (i.nowElapsed < MIN_UPTIME_MS) {
            return Decision(Action.NONE, "equipo encendido hace menos de ${MIN_UPTIME_MS / 60_000} min")
        }
        if (i.nowElapsed - since < MIN_ZOMBIE_MS) {
            return Decision(Action.NONE, "zombi hace menos de ${MIN_ZOMBIE_MS / 60_000} min")
        }
        if (i.canWriteSecureSettings) {
            val toggle = i.lastToggleElapsed
            // Un ciclo del ajuste por cada zombi (el anterior a `since` era de otro episodio).
            if (toggle == null || toggle < since) {
                return Decision(Action.TOGGLE_SETTING, "apagar y prender el servicio por ajuste seguro")
            }
            if (i.nowElapsed - toggle < TOGGLE_SETTLE_MS) {
                return Decision(Action.NONE, "esperando que el ciclo del ajuste haga efecto")
            }
        }
        val recientes = recentAttempts(i.reinstallAttemptsWall, i.nowWall)
        if (recientes.size >= MAX_REINSTALLS_PER_DAY) {
            return Decision(Action.NONE, "ya hubo $MAX_REINSTALLS_PER_DAY reinstalaciones en 24 h")
        }
        val ultimo = recientes.maxOrNull()
        if (ultimo != null && i.nowWall - ultimo < MIN_GAP_MS) {
            return Decision(Action.NONE, "la última reinstalación fue hace menos de ${MIN_GAP_MS / 60_000} min")
        }
        return Decision(Action.REINSTALL, "reinstalar el propio APK (limpia la cuarentena de Android)")
    }

    /** Intentos que cuentan para los límites: últimas 24 h, más los «del futuro» de hasta un día. */
    fun recentAttempts(attemptsWall: List<Long>, nowWall: Long): List<Long> =
        attemptsWall.filter { it > nowWall - DAY_MS && it < nowWall + DAY_MS }

    // ── Historial persistido como "t1,t2,…" (tolerante a basura) ───────────────────────────────

    fun parseHistory(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.takeLast(HISTORY_KEEP) ?: emptyList()

    fun appendHistory(raw: String?, attemptWall: Long): String =
        (parseHistory(raw) + attemptWall).takeLast(HISTORY_KEEP).joinToString(",")

    // ── `ENABLED_ACCESSIBILITY_SERVICES`: "pkg/clase:pkg2/.Corta:…" ─────────────────────────────

    /** ¿La entrada `paquete/clase` (forma larga o con «.» corta) es nuestro componente? */
    fun isComponent(entry: String, pkg: String, cls: String): Boolean {
        val e = entry.trim()
        val slash = e.indexOf('/')
        if (slash <= 0) return false
        if (!e.substring(0, slash).equals(pkg, ignoreCase = true)) return false
        val c = e.substring(slash + 1)
        return c.equals(cls, ignoreCase = true) ||
            (c.startsWith(".") && (pkg + c).equals(cls, ignoreCase = true))
    }

    fun containsComponent(enabled: String?, pkg: String, cls: String): Boolean =
        splitEntries(enabled).any { isComponent(it, pkg, cls) }

    /** La lista sin nuestro componente; los demás servicios quedan como estaban y en su orden. */
    fun withoutComponent(enabled: String?, pkg: String, cls: String): String =
        splitEntries(enabled).filterNot { isComponent(it, pkg, cls) }.joinToString(":")

    /** La lista con nuestro componente (una sola vez, al final si no estaba). */
    fun withComponent(enabled: String?, pkg: String, cls: String): String {
        val entries = splitEntries(enabled)
        if (entries.any { isComponent(it, pkg, cls) }) return entries.joinToString(":")
        return (entries + "$pkg/$cls").joinToString(":")
    }

    private fun splitEntries(enabled: String?): List<String> =
        enabled?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
