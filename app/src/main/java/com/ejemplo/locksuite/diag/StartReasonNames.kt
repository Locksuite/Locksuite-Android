package com.ejemplo.locksuite.diag

/**
 * 19/11/2026 (B.125, T2.2) — NOMBRES DE `ApplicationStartInfo.START_REASON_*` y `START_TYPE_*`
 * (Android 15+). Con los CÓDIGOS numéricos, como `DiagExits`, para probar con JUnit puro y no
 * depender de que la clase exista en el equipo.
 */
object StartReasonNames {

    fun reason(code: Int): String = when (code) {
        0 -> "ALARM (una alarma)"
        1 -> "BACKUP"
        2 -> "BOOT_COMPLETE (el arranque del equipo)"
        3 -> "BROADCAST (un broadcast)"
        4 -> "CONTENT_PROVIDER"
        5 -> "JOB (un trabajo programado, p. ej. el Worker)"
        6 -> "LAUNCHER (el usuario abrió la app)"
        7 -> "OTHER"
        8 -> "PUSH (un mensaje FCM)"
        10 -> "SERVICE (un servicio)"
        11 -> "START_ACTIVITY"
        12 -> "LAUNCHER_RECENTS (el usuario abrió la app desde recientes)"
        else -> "código $code"
    }

    fun startType(code: Int): String = when (code) {
        0 -> "tipo desconocido"
        1 -> "arranque en frío"
        2 -> "arranque templado"
        3 -> "arranque en caliente"
        else -> "tipo $code"
    }
}
