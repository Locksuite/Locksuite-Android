package com.ejemplo.locksuite.mdm

/**
 * Lógica pura (sin dependencias de Android) de la ventana temporal de instalación de apps.
 *
 * Modela el doble reloj anti-manipulación (patrón de `GracePeriodManager`):
 * - **Reloj de pared (`deadlineWall`)**: sigue avanzando con el equipo apagado.
 * - **Acumulador monotónico (`accumulatedMs` basado en `elapsedRealtime`)**: sigue
 *   avanzando aunque el usuario atrase la hora del sistema.
 * - Absorbe saltos NTP hacia adelante en el mismo arranque para no cerrar de golpe un
 *   equipo cuyo reloj saltó de fecha de fábrica a hora real al conectarse al Wi-Fi.
 */
object TempInstallWindowClock {

    const val MIN_DURATION_MS: Long = 60_000L
    const val MAX_DURATION_MS: Long = 30L * 24L * 60L * 60L * 1000L
    private const val NTP_JUMP_THRESHOLD_MS: Long = 10L * 60L * 1000L
    private const val MIN_VALID_WALL_MS: Long = 1_700_000_000_000L

    data class WindowState(
        val active: Boolean,
        val startedAtWall: Long,
        val durationMs: Long,
        val deadlineWall: Long,
        val accumulatedMs: Long,
        val lastElapsedMs: Long,
        val lastBootCount: Int,
        val lastWallSample: Long
    )

    data class TickResult(
        val active: Boolean,
        val expired: Boolean,
        val expireReason: String?,
        val remainingMs: Long,
        val rebootDetected: Boolean,
        val updatedState: WindowState
    )

    fun validateDuration(durationMs: Long): Boolean =
        durationMs in MIN_DURATION_MS..MAX_DURATION_MS

    fun createState(
        nowWall: Long,
        elapsedMs: Long,
        bootCount: Int,
        durationMs: Long
    ): WindowState = WindowState(
        active = true,
        startedAtWall = nowWall,
        durationMs = durationMs,
        deadlineWall = nowWall + durationMs,
        accumulatedMs = 0L,
        lastElapsedMs = elapsedMs.coerceAtLeast(0L),
        lastBootCount = bootCount,
        lastWallSample = nowWall
    )

    fun evaluate(
        state: WindowState,
        nowWall: Long,
        elapsedMs: Long,
        bootCount: Int
    ): TickResult {
        if (!state.active || state.deadlineWall <= 0L || state.durationMs <= 0L) {
            return TickResult(
                active = false,
                expired = false,
                expireReason = null,
                remainingMs = 0L,
                rebootDetected = false,
                updatedState = state.copy(active = false)
            )
        }

        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        val rebootDetected = (bootCount != -1 && state.lastBootCount != -1 && bootCount != state.lastBootCount) ||
            safeElapsed < state.lastElapsedMs
        val deltaElapsed = if (!rebootDetected) (safeElapsed - state.lastElapsedMs).coerceAtLeast(0L) else safeElapsed
        val accumulated = state.accumulatedMs + deltaElapsed

        var adjustedDeadline = state.deadlineWall
        if (!rebootDetected && state.lastWallSample in 1L until MIN_VALID_WALL_MS) {
            val wallDelta = nowWall - state.lastWallSample
            if (wallDelta - deltaElapsed > NTP_JUMP_THRESHOLD_MS) {
                val maxDeadline = nowWall + (state.durationMs - accumulated).coerceAtLeast(0L)
                adjustedDeadline = minOf(adjustedDeadline + (wallDelta - deltaElapsed), maxDeadline)
            }
        }

        val expiredByWall = nowWall >= adjustedDeadline
        val expiredByAccum = accumulated >= state.durationMs

        val nextState = state.copy(
            deadlineWall = adjustedDeadline,
            accumulatedMs = accumulated,
            lastElapsedMs = safeElapsed,
            lastBootCount = bootCount,
            lastWallSample = nowWall
        )

        if (expiredByWall || expiredByAccum) {
            val reason = when {
                expiredByWall && expiredByAccum -> "vencido"
                expiredByWall -> "vencido (reloj)"
                else -> "vencido (tiempo de uso; el reloj del equipo no llegó)"
            }
            return TickResult(
                active = false,
                expired = true,
                expireReason = reason,
                remainingMs = 0L,
                rebootDetected = rebootDetected,
                updatedState = nextState.copy(active = false)
            )
        }

        val remainingWall = (adjustedDeadline - nowWall).coerceAtLeast(0L)
        val remainingAccum = (state.durationMs - accumulated).coerceAtLeast(0L)
        val remaining = minOf(remainingWall, remainingAccum)

        return TickResult(
            active = true,
            expired = false,
            expireReason = null,
            remainingMs = remaining,
            rebootDetected = rebootDetected,
            updatedState = nextState
        )
    }

    fun formatRemaining(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
        if (totalSec <= 0L) return "0 s"
        if (totalSec < 60L) return "$totalSec s"
        val totalMin = totalSec / 60L
        if (totalMin < 60L) return "$totalMin min"
        val totalHours = totalMin / 60L
        val remMin = totalMin % 60L
        if (totalHours < 24L) {
            return if (remMin > 0L) "$totalHours h $remMin min" else "$totalHours h"
        }
        val days = totalHours / 24L
        val remHours = totalHours % 24L
        return if (remHours > 0L) "$days d $remHours h" else "$days d"
    }
}
