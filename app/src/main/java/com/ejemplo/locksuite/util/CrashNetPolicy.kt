package com.ejemplo.locksuite.util

/**
 * 7/11/2026 (B.112) — ¿Se puede atrapar una excepción NO atrapada sin matar el proceso?
 * Lógica pura; la usa el manejador global de `LockSuiteApplication`.
 *
 * Hasta acá, CUALQUIER excepción no atrapada en CUALQUIER hilo mataba el proceso entero (es lo
 * que hace el manejador por omisión de Android). En esta app eso es carísimo: si el proceso muere
 * con la accesibilidad enlazada, puede quedar «zombi» (ver `Safe.kt` y B.112). Pero no siempre se
 * puede seguir como si nada:
 *
 *  - **Hilo principal: NUNCA.** La excepción ya desarmó el `Looper.loop()` del proceso; no hay
 *    forma sana de seguir. Para el hilo principal están las redes puestas a mano (`Safe.guard`)
 *    y la autorreparación del zombi.
 *  - **Hilo con `Looper` propio (un `HandlerThread`, como el `ConnectivityThread` que le avisa a la
 *    VPN de los cambios de red): NUNCA.** Si se traga la excepción, ese hilo termina igual, su cola
 *    queda muerta y TODO lo que se le mande después se pierde en silencio — por ejemplo, la VPN
 *    dejaría de enterarse de los cambios de Wi-Fi a datos. Morir y reiniciar es mejor que eso.
 *  - **Errores de la máquina virtual (`OutOfMemoryError`, `InternalError`…): NUNCA.** Seguir con el
 *    proceso sin memoria es peor que reiniciarlo. (`StackOverflowError` sí: la pila ya se
 *    desarmó y el hilo de trabajo queda sano.)
 *  - **Ráfagas:** si en 10 minutos ya se tragaron [MAX_BURST], algo está muy mal; se deja morir
 *    al proceso para que arranque limpio.
 *  - **Todo lo demás —hilos de trabajo de corrutinas (`Dispatchers.IO`), de ejecutores y hilos
 *    sueltos sin `Looper`—: SÍ.** Ahí la tarea que falló se pierde y el resto sigue. En una
 *    corrutina el hilo ni siquiera muere (la librería atrapa y llama al manejador).
 */
object CrashNetPolicy {

    const val MAX_BURST = 20
    const val BURST_WINDOW_MS = 10 * 60_000L

    fun shouldSwallow(
        isMainThread: Boolean,
        threadHasLooper: Boolean,
        isFatalVmError: Boolean,
        swallowedInWindow: Int,
    ): Boolean = !isMainThread && !threadHasLooper && !isFatalVmError && swallowedInWindow < MAX_BURST

    /** `OutOfMemoryError`, `InternalError` y demás `VirtualMachineError`, salvo `StackOverflowError`. */
    fun isFatalVmError(t: Throwable): Boolean = t is VirtualMachineError && t !is StackOverflowError

    /** Cuántas marcas de tiempo caen dentro de la ventana (para el tope de ráfagas). */
    fun countInWindow(timestamps: List<Long>, nowElapsed: Long): Int =
        timestamps.count { nowElapsed - it in 0 until BURST_WINDOW_MS }
}
