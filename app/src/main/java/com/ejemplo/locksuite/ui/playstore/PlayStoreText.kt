package com.ejemplo.locksuite.ui.playstore

import com.ejemplo.locksuite.mdm.PlayStoreCatalog

/**
 * Textos de la Tienda Play del celular (29/10/2026, B.101), en español, inglés y hebreo.
 *
 * Es Kotlin puro (sin Android) a propósito: así el banco de pruebas puede afirmar que
 * **ninguna clave se queda sin texto en ningún idioma** — la forma concreta de que no
 * vuelva el botón mudo o la pantalla en blanco de B.28/B.42/B.54. Si se agrega una clave
 * nueva al `enum`, el compilador no deja olvidarse de ella: el `when` de [spanish] es
 * exhaustivo, y la prueba revisa que los otros dos idiomas la traigan también.
 *
 * Los mensajes que devuelve el flujo de instalación (`UpdateFlowManager`, por ejemplo
 * "No hay espacio suficiente…") ya vienen escritos en español desde antes y se muestran
 * tal cual, igual que en la pantalla de Actualizar Apps.
 */
object PlayStoreText {

    enum class Key {
        TITLE,
        HINT,
        CLOSED_TITLE,
        CLOSED_BODY,
        EMPTY,
        INSTALL,
        INSTALLING,
        OPEN,
        REQUEST,
        REQUESTED,
        TAG_NO_WEB,
        TAG_DOMAINS,
        TAG_ON_REQUEST,
        TAG_PAUSED,
        BACK,
        OPEN_FAILED,
        WIDGET_LABEL,
        MODE_CLOSED,
        MODE_KOSHER,
        MODE_WORK,
        MODE_LITE,
        POLICY_HINT,
        MODE_CLOSED_DETAIL,
        MODE_KOSHER_DETAIL,
        MODE_WORK_DETAIL,
        MODE_LITE_DETAIL
    }

    /** Idiomas con texto propio. Cualquier otro valor cae en español. */
    const val LANG_ES = "es"
    const val LANG_EN = "en"
    const val LANG_HE = "he"

    val LANGS: List<String> = listOf(LANG_ES, LANG_EN, LANG_HE)

    /** ¿Se escribe de derecha a izquierda? */
    fun isRtl(lang: String?): Boolean = lang == LANG_HE

    fun t(key: Key, lang: String?): String = when (lang) {
        LANG_EN -> english(key)
        LANG_HE -> hebrew(key)
        else -> spanish(key)
    }

    /** Nombre del modo de la tienda para mostrar (el de [PlayStoreCatalog.normalizeMode]). */
    fun modeLabel(mode: String?, lang: String?): String = when (PlayStoreCatalog.normalizeMode(mode)) {
        PlayStoreCatalog.MODE_KOSHER -> t(Key.MODE_KOSHER, lang)
        PlayStoreCatalog.MODE_WORK -> t(Key.MODE_WORK, lang)
        PlayStoreCatalog.MODE_LITE -> t(Key.MODE_LITE, lang)
        else -> t(Key.MODE_CLOSED, lang)
    }

    private fun spanish(key: Key): String = when (key) {
        Key.TITLE -> "Tienda Play"
        Key.HINT -> "Apps que tu administrador permitió. Se instalan desde Google Play."
        Key.CLOSED_TITLE -> "La tienda está cerrada"
        Key.CLOSED_BODY -> "Tu administrador no la habilitó en este equipo."
        Key.EMPTY -> "Todavía no hay apps para mostrar."
        Key.INSTALL -> "Instalar"
        Key.INSTALLING -> "Instalando…"
        Key.OPEN -> "Abrir"
        Key.REQUEST -> "Pedir"
        Key.REQUESTED -> "Pedida"
        Key.TAG_NO_WEB -> "Sin web"
        Key.TAG_DOMAINS -> "Sitios limitados"
        Key.TAG_ON_REQUEST -> "A pedido"
        Key.TAG_PAUSED -> "En pausa hasta que la aprueben"
        Key.BACK -> "Volver"
        Key.OPEN_FAILED -> "No se pudo abrir la app."
        Key.WIDGET_LABEL -> "Tienda"
        Key.MODE_CLOSED -> "Cerrada"
        Key.MODE_KOSHER -> "Kosher"
        Key.MODE_WORK -> "Trabajo"
        Key.MODE_LITE -> "Lite"
        Key.POLICY_HINT -> "Qué apps puede instalar el usuario desde la Tienda Play de este equipo. Las apps de cada nivel las define el administrador en el panel web."
        Key.MODE_CLOSED_DETAIL -> "La tienda no se muestra."
        Key.MODE_KOSHER_DETAIL -> "Pocas apps (nivel 1)."
        Key.MODE_WORK_DETAIL -> "Más apps: nivel 1 + nivel 2."
        Key.MODE_LITE_DETAIL -> "Toda la tienda: niveles 1, 2 y 3."
    }

    private fun english(key: Key): String = when (key) {
        Key.TITLE -> "Play Shop"
        Key.HINT -> "Apps your administrator allowed. They install from Google Play."
        Key.CLOSED_TITLE -> "The shop is closed"
        Key.CLOSED_BODY -> "Your administrator has not turned it on for this device."
        Key.EMPTY -> "There are no apps to show yet."
        Key.INSTALL -> "Install"
        Key.INSTALLING -> "Installing…"
        Key.OPEN -> "Open"
        Key.REQUEST -> "Request"
        Key.REQUESTED -> "Requested"
        Key.TAG_NO_WEB -> "No web"
        Key.TAG_DOMAINS -> "Limited sites"
        Key.TAG_ON_REQUEST -> "On request"
        Key.TAG_PAUSED -> "Paused until approved"
        Key.BACK -> "Back"
        Key.OPEN_FAILED -> "Could not open the app."
        Key.WIDGET_LABEL -> "Shop"
        Key.MODE_CLOSED -> "Closed"
        Key.MODE_KOSHER -> "Kosher"
        Key.MODE_WORK -> "Work"
        Key.MODE_LITE -> "Lite"
        Key.POLICY_HINT -> "Which apps the user can install from this device's Play Shop. The apps in each level are set by the administrator in the web panel."
        Key.MODE_CLOSED_DETAIL -> "The shop is hidden."
        Key.MODE_KOSHER_DETAIL -> "A few apps (level 1)."
        Key.MODE_WORK_DETAIL -> "More apps: level 1 + level 2."
        Key.MODE_LITE_DETAIL -> "The whole shop: levels 1, 2 and 3."
    }

    private fun hebrew(key: Key): String = when (key) {
        Key.TITLE -> "חנות Play"
        Key.HINT -> "אפליקציות שהמנהל אישר. ההתקנה דרך Google Play."
        Key.CLOSED_TITLE -> "החנות סגורה"
        Key.CLOSED_BODY -> "המנהל לא הפעיל אותה במכשיר הזה."
        Key.EMPTY -> "עדיין אין אפליקציות להצגה."
        Key.INSTALL -> "התקנה"
        Key.INSTALLING -> "מתקין…"
        Key.OPEN -> "פתיחה"
        Key.REQUEST -> "בקשה"
        Key.REQUESTED -> "נשלחה בקשה"
        Key.TAG_NO_WEB -> "ללא אינטרנט"
        Key.TAG_DOMAINS -> "אתרים מוגבלים"
        Key.TAG_ON_REQUEST -> "לפי בקשה"
        Key.TAG_PAUSED -> "מושהית עד לאישור"
        Key.BACK -> "חזרה"
        Key.OPEN_FAILED -> "לא ניתן לפתוח את האפליקציה."
        Key.WIDGET_LABEL -> "חנות"
        Key.MODE_CLOSED -> "סגורה"
        Key.MODE_KOSHER -> "כשר"
        Key.MODE_WORK -> "עבודה"
        Key.MODE_LITE -> "Lite"
        Key.POLICY_HINT -> "אילו אפליקציות המשתמש יכול להתקין מחנות Play במכשיר הזה. האפליקציות בכל רמה נקבעות על ידי המנהל בלוח האינטרנט."
        Key.MODE_CLOSED_DETAIL -> "החנות מוסתרת."
        Key.MODE_KOSHER_DETAIL -> "מעט אפליקציות (רמה 1)."
        Key.MODE_WORK_DETAIL -> "יותר אפליקציות: רמה 1 + רמה 2."
        Key.MODE_LITE_DETAIL -> "כל החנות: רמות 1, 2 ו-3."
    }
}
