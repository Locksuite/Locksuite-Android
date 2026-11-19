package com.ejemplo.locksuite.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 19/11/2026 (B.125, T4) — NÚCLEO DE «UNA INSTALACIÓN A LA VEZ, FUERA DE LA PANTALLA».
 *
 * Sin Android, para probarlo con JUnit (`StoreInstallCoreTest`) con un descargador falso.
 * Lo usa [StoreInstallManager], que le pasa un scope DE PROCESO.
 *
 * Por qué existe: la tienda clásica y el botón «Actualizar aplicación» corrían su descarga en
 * `rememberCoroutineScope()`, que se CANCELA cuando la pantalla sale de la composición. El 16/11
 * a las 09:35 «Fondos» no se instaló por eso: `rememberCoroutineScope left the composition`, que
 * `SelfUpdater` tragaba con `catch (e: Exception)` y mostraba como «Error al instalar». Ver B.125.
 *
 * ── REGLAS ───────────────────────────────────────────────────────────────────────────
 *  • UNA instalación a la vez en todo el proceso: pedir otra mientras corre devuelve `false` y
 *    no hace nada (no se duplica, no se pisa).
 *  • Quien MIRA la instalación (la pantalla) no es dueño de ella: mirar es leer [state]; cerrar
 *    la pantalla no cancela nada.
 *  • Cualquier excepción del trabajo se convierte en un texto de error; nunca queda la compuerta
 *    tomada para siempre.
 */
class StoreInstallCore(private val scope: CoroutineScope) {

    /** `key == null` = no hay ninguna instalación en curso. */
    data class State(val key: String? = null, val progress: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val busy = AtomicBoolean(false)

    fun isBusy(): Boolean = busy.get()

    /**
     * @param key quién se instala (el paquete, o una clave fija para la autoactualización).
     * @param work la instalación; recibe el informe de progreso y devuelve null si salió bien o
     *   el texto del error.
     * @param onFinished se llama UNA vez, al terminar, con el error o null. Corre en el mismo
     *   hilo del trabajo.
     * @return true si arrancó; false si ya había una en curso.
     */
    fun start(
        key: String,
        work: suspend ((Int) -> Unit) -> String?,
        onFinished: (String?) -> Unit
    ): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        _state.value = State(key, 0)
        scope.launch {
            var error: String? = null
            try {
                error = work { p -> _state.value = State(key, p.coerceIn(0, 100)) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                error = "La instalación se canceló"
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                _state.value = State()
                busy.set(false)
            }
            try {
                onFinished(error)
            } catch (_: Throwable) {
            }
        }
        return true
    }
}
