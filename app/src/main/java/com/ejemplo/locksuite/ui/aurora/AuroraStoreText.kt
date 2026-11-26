package com.ejemplo.locksuite.ui.aurora

import com.ejemplo.locksuite.mdm.PlayStoreCatalog

/**
 * Textos en español, inglés y hebreo para Tienda Aurora (B.132).
 * Sin dependencias de Android para poder verificarlos en JVM (`AuroraStoreUiLogicTest`).
 */
object AuroraStoreText {

    const val LANG_ES = "es"
    const val LANG_EN = "en"
    const val LANG_HE = "he"

    enum class Key {
        TITLE,
        WIDGET_LABEL,
        BACK,
        REFRESH,
        HINT,
        SEARCH_HINT,
        CLOSED_TITLE,
        CLOSED_BODY,
        EMPTY,
        INSTALL,
        UPDATE,
        UPDATE_ALL_FMT,
        OPEN,
        REQUEST,
        REQUESTED,
        INSTALLING,
        INSTALLING_PCT_FMT,
        UNAVAILABLE,
        UNAVAILABLE_TOAST,
        OPEN_FAILED,
        UPDATE_STARTED_FMT,
        UPDATE_ALL_STARTED_FMT,
        MODE_KOSHER,
        MODE_TRABAJO,
        MODE_LITE,
        MODE_CERRADA,
        INSTALLED_UP_TO_DATE
    }

    fun normalizeLang(raw: String?): String = when (raw?.trim()?.lowercase()) {
        LANG_EN -> LANG_EN
        LANG_HE, "iw" -> LANG_HE
        else -> LANG_ES
    }

    fun isRtl(lang: String?): Boolean = normalizeLang(lang) == LANG_HE

    fun t(key: Key, lang: String?): String {
        val l = normalizeLang(lang)
        return when (key) {
            Key.TITLE -> when (l) {
                LANG_EN -> "Aurora Store"
                LANG_HE -> "חנות אורורה"
                else -> "Tienda Aurora"
            }
            Key.WIDGET_LABEL -> when (l) {
                LANG_EN -> "Aurora Store"
                LANG_HE -> "אורורה"
                else -> "Tienda Aurora"
            }
            Key.BACK -> when (l) {
                LANG_EN -> "Back"
                LANG_HE -> "חזרה"
                else -> "Volver"
            }
            Key.REFRESH -> when (l) {
                LANG_EN -> "Refresh"
                LANG_HE -> "רענון"
                else -> "Actualizar lista"
            }
            Key.HINT -> when (l) {
                LANG_EN -> "Direct silent install and updates, no Play Store screen."
                LANG_HE -> "התקנה ועדכונים ישירים ושקטים ללא פתיחת חנות Google Play."
                else -> "Instalación y actualizaciones directas en silencio, sin abrir Play Store."
            }
            Key.SEARCH_HINT -> when (l) {
                LANG_EN -> "Search app…"
                LANG_HE -> "חיפוש אפליקציה…"
                else -> "Buscar app…"
            }
            Key.CLOSED_TITLE -> when (l) {
                LANG_EN -> "Store closed"
                LANG_HE -> "החנות סגורה"
                else -> "Tienda cerrada"
            }
            Key.CLOSED_BODY -> when (l) {
                LANG_EN -> "This profile does not allow installing apps right now."
                LANG_HE -> "הפרופיל הנוכחי לא מאפשר להתקין אפליקציות כרגע."
                else -> "Tu perfil actual no tiene habilitada la instalación de aplicaciones."
            }
            Key.EMPTY -> when (l) {
                LANG_EN -> "No apps configured for this profile yet."
                LANG_HE -> "עדיין לא הוגדרו אפליקציות עבור פרופיל זה."
                else -> "Todavía no hay aplicaciones configuradas para este perfil."
            }
            Key.INSTALL -> when (l) {
                LANG_EN -> "Install"
                LANG_HE -> "התקן"
                else -> "Instalar"
            }
            Key.UPDATE -> when (l) {
                LANG_EN -> "Update"
                LANG_HE -> "עדכן"
                else -> "Actualizar"
            }
            Key.UPDATE_ALL_FMT -> when (l) {
                LANG_EN -> "Update all (%d)"
                LANG_HE -> "עדכן הכל (%d)"
                else -> "Actualizar todas (%d)"
            }
            Key.OPEN -> when (l) {
                LANG_EN -> "Open"
                LANG_HE -> "פתח"
                else -> "Abrir"
            }
            Key.REQUEST -> when (l) {
                LANG_EN -> "Request"
                LANG_HE -> "בקש"
                else -> "Pedir"
            }
            Key.REQUESTED -> when (l) {
                LANG_EN -> "Requested"
                LANG_HE -> "נשלחה בקשה"
                else -> "Pedida"
            }
            Key.INSTALLING -> when (l) {
                LANG_EN -> "Installing…"
                LANG_HE -> "מתקין…"
                else -> "Instalando…"
            }
            Key.INSTALLING_PCT_FMT -> when (l) {
                LANG_EN -> "%d%%"
                LANG_HE -> "%d%%"
                else -> "%d%%"
            }
            Key.UNAVAILABLE -> when (l) {
                LANG_EN -> "Pending"
                LANG_HE -> "ממתין"
                else -> "Pendiente"
            }
            Key.UNAVAILABLE_TOAST -> when (l) {
                LANG_EN -> "This app is not yet cached on the server for your device architecture."
                LANG_HE -> "האפליקציה עדיין לא הורדה לשרת עבור מכשיר זה."
                else -> "Esta app todavía no se sincronizó en el servidor para tu equipo."
            }
            Key.OPEN_FAILED -> when (l) {
                LANG_EN -> "Could not open app."
                LANG_HE -> "לא ניתן לפתוח את האפליקציה."
                else -> "No se pudo abrir la aplicación."
            }
            Key.UPDATE_STARTED_FMT -> when (l) {
                LANG_EN -> "Downloading %s silently in background…"
                LANG_HE -> "מוריד את %s בשקט ברקע…"
                else -> "Descargando %s en silencio…"
            }
            Key.UPDATE_ALL_STARTED_FMT -> when (l) {
                LANG_EN -> "Queued %d silent updates in background."
                LANG_HE -> "%d עדכונים שקטים נוספו לתור ברקע."
                else -> "Se iniciaron %d actualizaciones silenciosas en segundo plano."
            }
            Key.MODE_KOSHER -> when (l) {
                LANG_EN -> "Kosher"
                LANG_HE -> "כשר"
                else -> "Kosher"
            }
            Key.MODE_TRABAJO -> when (l) {
                LANG_EN -> "Work"
                LANG_HE -> "עבודה"
                else -> "Trabajo"
            }
            Key.MODE_LITE -> when (l) {
                LANG_EN -> "Lite"
                LANG_HE -> "Lite"
                else -> "Lite"
            }
            Key.MODE_CERRADA -> when (l) {
                LANG_EN -> "Closed"
                LANG_HE -> "סגור"
                else -> "Cerrada"
            }
            Key.INSTALLED_UP_TO_DATE -> when (l) {
                LANG_EN -> "Up to date"
                LANG_HE -> "מעודכן"
                else -> "Al día"
            }
        }
    }

    fun modeLabel(mode: String?, lang: String?): String = when (mode) {
        PlayStoreCatalog.MODE_KOSHER -> t(Key.MODE_KOSHER, lang)
        PlayStoreCatalog.MODE_WORK -> t(Key.MODE_TRABAJO, lang)
        PlayStoreCatalog.MODE_LITE -> t(Key.MODE_LITE, lang)
        else -> t(Key.MODE_CERRADA, lang)
    }
}
