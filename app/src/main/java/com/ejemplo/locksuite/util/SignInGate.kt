package com.ejemplo.locksuite.util

/**
 * Abre la sesión anónima de Firebase UNA sola vez aunque la pidan varios a la vez (1/11/2026, B.104).
 *
 * POR QUÉ EXISTE. `FirebaseDeviceSync.withAuth` hacía: "si no hay usuario, `signInAnonymously()` y
 * después corro la acción". Al arrancar un celular nuevo lo piden a la vez el latido de
 * `LockSuiteApplication`, la sincronización completa, el token de FCM, el buzón de comandos y,
 * si el dueño ya está eligiendo el PIN, la publicación del PIN. Como ninguno sabía de los otros,
 * cada uno abría SU PROPIA sesión anónima: el SDK crea una cuenta nueva por cada llamada que
 * encuentra sin usuario, y el usuario "actual" termina siendo el de la última que contesta.
 *
 * Eso no es inocuo. Las reglas atan `devices/<id>` y `deviceSecrets/<id>` al `ownerUid` que los
 * creó. Si el latido crea el nodo como uid A y un segundo después el usuario actual pasa a ser
 * uid B, todas las escrituras siguientes (el PIN incluido) las rechaza la base por permisos, y
 * el equipo queda pidiendo "re-vincularse" a un panel que acaba de ver nacer. Con una sola
 * sesión por arranque ese desencuentro no puede producirse.
 *
 * CÓMO FUNCIONA. Las acciones que llegan sin sesión se encolan. Solo la primera dispara el
 * inicio de sesión; cuando termina —bien o mal— se ejecutan todas, en el orden en que llegaron.
 * Se ejecutan también si falló, igual que antes: algunas solo dejan un rastro en el log, y las
 * que escriben vuelven a revisar si hay usuario antes de confiar.
 *
 * Si un inicio de sesión se cuelga (sin red no siempre contesta), pasado [staleAfterMs] el
 * siguiente pedido abre otro en vez de esperar para siempre: antes cada llamada reintentaba, y
 * eso no se puede perder.
 *
 * Sin dependencias de Android ni de Firebase: se prueba con `SignInGateTest`.
 *
 * @param isSignedIn   ¿ya hay usuario? (en producción: `FirebaseAuth.currentUser != null`).
 * @param startSignIn  inicia la sesión y avisa el resultado una vez (`true` = hay usuario).
 * @param now          reloj en milisegundos (inyectable para las pruebas).
 * @param onError      qué hacer si una acción lanza una excepción (no frena a las demás).
 */
class SignInGate(
    private val isSignedIn: () -> Boolean,
    private val startSignIn: (onDone: (Boolean) -> Unit) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val staleAfterMs: Long = DEFAULT_STALE_MS,
    private val onError: (Exception) -> Unit = {}
) {
    companion object {
        /** Un inicio de sesión sin respuesta tras este tiempo se considera colgado. */
        const val DEFAULT_STALE_MS = 45_000L
    }

    private val lock = Any()
    private val waiting = ArrayList<() -> Unit>()
    /** Hora de inicio del intento en curso; 0 = no hay ninguno. */
    private var inFlightSince = 0L
    /** Número del último intento lanzado: un aviso de un intento viejo fallido se ignora. */
    private var attempt = 0

    /** Corre [action] cuando haya sesión, o cuando el intento de abrirla haya terminado. */
    fun run(action: () -> Unit) {
        if (isSignedIn()) {
            action()
            return
        }
        var myAttempt = 0
        var startNow = false
        var alreadySignedIn = false
        synchronized(lock) {
            // Se vuelve a mirar adentro del cerrojo: la sesión pudo abrirse entre la primera
            // pregunta y acá, y encolar la acción entonces la dejaría esperando a un intento
            // que ya contestó.
            if (isSignedIn()) {
                alreadySignedIn = true
            } else {
                waiting.add(action)
                val t = now()
                val stale = inFlightSince > 0L && (t < inFlightSince || t - inFlightSince >= staleAfterMs)
                if (inFlightSince == 0L || stale) {
                    inFlightSince = t.coerceAtLeast(1L)
                    attempt++
                    myAttempt = attempt
                    startNow = true
                }
            }
        }
        if (alreadySignedIn) {
            action()
            return
        }
        if (startNow) {
            try {
                startSignIn { ok -> finish(myAttempt, ok) }
            } catch (e: Exception) {
                finish(myAttempt, false)
            }
        }
    }

    private fun finish(finishedAttempt: Int, ok: Boolean) {
        val toRun: List<() -> Unit>
        synchronized(lock) {
            // El aviso de un intento viejo que falló no dice nada del vigente: se descarta.
            // Si en cambio abrió sesión, sirve para todos los que esperan.
            if (!ok && finishedAttempt != attempt) return
            toRun = ArrayList(waiting)
            waiting.clear()
            inFlightSince = 0L
        }
        // Fuera del cerrojo: una acción puede pedir otra sesión (re-entrada) sin trabarse.
        for (a in toRun) {
            try {
                a()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
