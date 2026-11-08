package com.ejemplo.locksuite.util

/**
 * 7/11/2026 (B.112) — RED DE SEGURIDAD PARA LOS PUNTOS DE ENTRADA QUE ANDROID LLAMA DIRECTO.
 *
 * Por qué existe, en una línea: **en esta app, que el proceso muera es mucho más caro que perder
 * un evento.** Si el proceso muere con una excepción no atrapada mientras el servicio de
 * accesibilidad está enlazado, Android puede dejarlo en `mCrashedServices` (figura «Activado» en
 * Ajustes pero no corre) y el equipo queda con la pantalla roja hasta que alguien apague y
 * prenda el interruptor o se actualice la app. Eso le pasó al celular de R Shaul Said (B.109,
 * B.112).
 *
 * `guard` envuelve el cuerpo de un callback (`onReceive`, `onChange`, un `Runnable` del
 * Handler…): una excepción se anota en el registro de diagnóstico —que el dueño ve en el panel— y
 * el proceso sigue vivo. Es `inline`, así que un `return` adentro del bloque sigue saliendo de la
 * función que lo contiene, igual que antes de envolverlo.
 *
 * Qué NO hay que hacer con esto: envolver lógica que tiene que fallar para que alguien se
 * entere. Va solo en el borde, donde la alternativa es que muera el proceso.
 */
object Safe {

    inline fun guard(lugar: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            report(lugar, t)
        }
    }

    /** Anota la excepción sin lanzar nunca. Pública para los `catch` escritos a mano. */
    fun report(lugar: String, t: Throwable) {
        try {
            com.ejemplo.locksuite.diag.DiagLog.e(
                "safe", "Excepción atrapada en $lugar (no se dejó morir el proceso)", t
            )
        } catch (_: Throwable) {
        }
        try {
            android.util.Log.e("LockSuiteSafe", "Excepción atrapada en $lugar", t)
        } catch (_: Throwable) {
        }
    }
}
