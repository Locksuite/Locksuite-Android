package com.ejemplo.locksuite.ui.playstore

import com.ejemplo.locksuite.mdm.PlayStoreCatalog

/**
 * Textos de la Tienda unificada de LockSuite (B.101 / B.134), en español, inglés y hebreo.
 *
 * Es Kotlin puro (sin Android) para verificar en JVM (`PlayStoreUiLogicTest`) que ninguna
 * clave quede vacía ni sin traducir.
 */
object PlayStoreText {

    enum class Key {
        TITLE,
        HINT,
        CLOSED_TITLE,
        CLOSED_BODY,
        CLOSED_UPDATES_ONLY,
        EMPTY,
        INSTALL,
        UPDATE,
        UPDATE_ALL_FMT,
        UPDATING,
        INSTALLING,
        OPEN,
        UP_TO_DATE,
        REQUEST,
        REQUESTED,
        TAG_NO_WEB,
        TAG_DOMAINS,
        TAG_ON_REQUEST,
        TAG_PAUSED,
        BACK,
        REFRESH,
        REFRESHING,
        SYNC_DONE,
        SEARCH_HINT,
        VIA_GOOGLE_PLAY,
        PLAY_FALLBACK_TITLE,
        PLAY_FALLBACK_BODY,
        PLAY_FALLBACK_NO_ACCOUNT,
        PLAY_FALLBACK_ACCOUNT_BLOCKED,
        PLAY_FALLBACK_CONTINUE,
        PLAY_FALLBACK_ADD_ACCOUNT,
        CANCEL,
        SILENT_FAILED_TITLE,
        SILENT_FAILED_PLAY_PROMPT,
        OK,
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

    const val LANG_ES = "es"
    const val LANG_EN = "en"
    const val LANG_HE = "he"

    val LANGS: List<String> = listOf(LANG_ES, LANG_EN, LANG_HE)

    fun isRtl(lang: String?): Boolean = lang == LANG_HE

    fun t(key: Key, lang: String?): String = when (lang) {
        LANG_EN -> english(key)
        LANG_HE -> hebrew(key)
        else -> spanish(key)
    }

    fun modeLabel(mode: String?, lang: String?): String = when (PlayStoreCatalog.normalizeMode(mode)) {
        PlayStoreCatalog.MODE_KOSHER -> t(Key.MODE_KOSHER, lang)
        PlayStoreCatalog.MODE_WORK -> t(Key.MODE_WORK, lang)
        PlayStoreCatalog.MODE_LITE -> t(Key.MODE_LITE, lang)
        else -> t(Key.MODE_CLOSED, lang)
    }

    private fun spanish(key: Key): String = when (key) {
        Key.TITLE -> "Tienda"
        Key.HINT -> "Instalá apps permitidas y mantené tus apps al día."
        Key.CLOSED_TITLE -> "La tienda está cerrada"
        Key.CLOSED_BODY -> "Tu administrador no la habilitó en este equipo."
        Key.CLOSED_UPDATES_ONLY -> "Modo cerrada · solo actualizaciones de apps instaladas."
        Key.EMPTY -> "Todavía no hay apps para mostrar."
        Key.INSTALL -> "Instalar"
        Key.UPDATE -> "Actualizar"
        Key.UPDATE_ALL_FMT -> "Actualizar todas (%d)"
        Key.UPDATING -> "Actualizando…"
        Key.INSTALLING -> "Instalando…"
        Key.OPEN -> "Abrir"
        Key.UP_TO_DATE -> "Sin actualizaciones"
        Key.REQUEST -> "Pedir"
        Key.REQUESTED -> "Pedida"
        Key.TAG_NO_WEB -> "Sin web"
        Key.TAG_DOMAINS -> "Sitios limitados"
        Key.TAG_ON_REQUEST -> "A pedido"
        Key.TAG_PAUSED -> "En pausa hasta que la aprueben"
        Key.BACK -> "Volver"
        Key.REFRESH -> "Recargar"
        Key.REFRESHING -> "Actualizando catálogo…"
        Key.SYNC_DONE -> "Lista actualizada"
        Key.SEARCH_HINT -> "Buscar aplicación…"
        Key.VIA_GOOGLE_PLAY -> "Por Google Play"
        Key.PLAY_FALLBACK_TITLE -> "Descargar desde Google Play"
        Key.PLAY_FALLBACK_BODY -> "Esta app no está disponible en los servidores directos ahora, así que se descargará desde Google Play con la pantalla tapada mientras dura el proceso.\n\nRecordá que es necesario tener una cuenta de Google iniciada en el celular."
        Key.PLAY_FALLBACK_NO_ACCOUNT -> "⚠ No detectamos ninguna cuenta de Google iniciada en este celular. Sin una cuenta, Google Play no va a poder descargar ni actualizar la app."
        Key.PLAY_FALLBACK_ACCOUNT_BLOCKED -> "⚠ Este equipo tiene bloqueado agregar cuentas desde Ajustes. Pedile ayuda al administrador si Play pide sesión."
        Key.PLAY_FALLBACK_CONTINUE -> "Continuar con Google Play"
        Key.PLAY_FALLBACK_ADD_ACCOUNT -> "Agregar cuenta Google"
        Key.CANCEL -> "Cancelar"
        Key.SILENT_FAILED_TITLE -> "No se pudo descargar directo"
        Key.SILENT_FAILED_PLAY_PROMPT -> "¿Querés descargarla desde Google Play? Se cubrirá la pantalla mientras dura y requiere una cuenta de Google en el celular."
        Key.OK -> "Entendido"
        Key.OPEN_FAILED -> "No se pudo abrir la app."
        Key.WIDGET_LABEL -> "Tienda"
        Key.MODE_CLOSED -> "Cerrada"
        Key.MODE_KOSHER -> "Kosher"
        Key.MODE_WORK -> "Trabajo"
        Key.MODE_LITE -> "Lite"
        Key.POLICY_HINT -> "Qué apps puede instalar el usuario desde la Tienda de este equipo. Las apps de cada nivel las define el administrador en el panel web."
        Key.MODE_CLOSED_DETAIL -> "La tienda no se muestra."
        Key.MODE_KOSHER_DETAIL -> "Pocas apps (nivel 1)."
        Key.MODE_WORK_DETAIL -> "Más apps: nivel 1 + nivel 2."
        Key.MODE_LITE_DETAIL -> "Toda la tienda: niveles 1, 2 y 3."
    }

    private fun english(key: Key): String = when (key) {
        Key.TITLE -> "App Store"
        Key.HINT -> "Install allowed apps and keep your apps up to date."
        Key.CLOSED_TITLE -> "The store is closed"
        Key.CLOSED_BODY -> "Your administrator has not turned it on for this device."
        Key.CLOSED_UPDATES_ONLY -> "Closed mode · updates only for installed apps."
        Key.EMPTY -> "There are no apps to show yet."
        Key.INSTALL -> "Install"
        Key.UPDATE -> "Update"
        Key.UPDATE_ALL_FMT -> "Update all (%d)"
        Key.UPDATING -> "Updating…"
        Key.INSTALLING -> "Installing…"
        Key.OPEN -> "Open"
        Key.UP_TO_DATE -> "No updates"
        Key.REQUEST -> "Request"
        Key.REQUESTED -> "Requested"
        Key.TAG_NO_WEB -> "No web"
        Key.TAG_DOMAINS -> "Limited sites"
        Key.TAG_ON_REQUEST -> "On request"
        Key.TAG_PAUSED -> "Paused until approved"
        Key.BACK -> "Back"
        Key.REFRESH -> "Reload"
        Key.REFRESHING -> "Refreshing catalog…"
        Key.SYNC_DONE -> "Store updated"
        Key.SEARCH_HINT -> "Search app…"
        Key.VIA_GOOGLE_PLAY -> "Via Google Play"
        Key.PLAY_FALLBACK_TITLE -> "Download from Google Play"
        Key.PLAY_FALLBACK_BODY -> "This app is not currently available from direct servers, so it will download from Google Play with the screen covered while it runs.\n\nA signed-in Google account on the device is required."
        Key.PLAY_FALLBACK_NO_ACCOUNT -> "⚠ No Google account was found on this device. Google Play cannot download or update without an account."
        Key.PLAY_FALLBACK_ACCOUNT_BLOCKED -> "⚠ Adding accounts is restricted on this device. Contact your administrator if Play asks to sign in."
        Key.PLAY_FALLBACK_CONTINUE -> "Continue with Google Play"
        Key.PLAY_FALLBACK_ADD_ACCOUNT -> "Add Google account"
        Key.CANCEL -> "Cancel"
        Key.SILENT_FAILED_TITLE -> "Direct download failed"
        Key.SILENT_FAILED_PLAY_PROMPT -> "Do you want to download it via Google Play? The screen will stay covered and a Google account is required."
        Key.OK -> "OK"
        Key.OPEN_FAILED -> "Could not open the app."
        Key.WIDGET_LABEL -> "Store"
        Key.MODE_CLOSED -> "Closed"
        Key.MODE_KOSHER -> "Kosher"
        Key.MODE_WORK -> "Work"
        Key.MODE_LITE -> "Lite"
        Key.POLICY_HINT -> "Which apps the user can install from this device's Store. The apps in each level are set by the administrator in the web panel."
        Key.MODE_CLOSED_DETAIL -> "The store is hidden."
        Key.MODE_KOSHER_DETAIL -> "A few apps (level 1)."
        Key.MODE_WORK_DETAIL -> "More apps: level 1 + level 2."
        Key.MODE_LITE_DETAIL -> "The whole store: levels 1, 2 and 3."
    }

    private fun hebrew(key: Key): String = when (key) {
        Key.TITLE -> "חנות"
        Key.HINT -> "התקן אפליקציות מאושרות ושמור על האפליקציות מעודכנות."
        Key.CLOSED_TITLE -> "החנות סגורה"
        Key.CLOSED_BODY -> "המנהל לא הפעיל אותה במכשיר הזה."
        Key.CLOSED_UPDATES_ONLY -> "מצב סגור · עדכונים לאפליקציות מותקנות בלבד."
        Key.EMPTY -> "עדיין אין אפליקציות להצגה."
        Key.INSTALL -> "התקנה"
        Key.UPDATE -> "עדכון"
        Key.UPDATE_ALL_FMT -> "עדכן הכל (%d)"
        Key.UPDATING -> "מעדכן…"
        Key.INSTALLING -> "מתקין…"
        Key.OPEN -> "פתיחה"
        Key.UP_TO_DATE -> "אין עדכונים"
        Key.REQUEST -> "בקשה"
        Key.REQUESTED -> "נשלחה בקשה"
        Key.TAG_NO_WEB -> "ללא אינטרנט"
        Key.TAG_DOMAINS -> "אתרים מוגבלים"
        Key.TAG_ON_REQUEST -> "לפי בקשה"
        Key.TAG_PAUSED -> "מושהית עד לאישור"
        Key.BACK -> "חזרה"
        Key.REFRESH -> "רענון"
        Key.REFRESHING -> "מרענן קטלוג…"
        Key.SYNC_DONE -> "הרשימה עודכנה"
        Key.SEARCH_HINT -> "חיפוש אפליקציה…"
        Key.VIA_GOOGLE_PLAY -> "דרך Google Play"
        Key.PLAY_FALLBACK_TITLE -> "הורדה מ-Google Play"
        Key.PLAY_FALLBACK_BODY -> "אפליקציה זו אינה זמינה כרגע בשרתים הישירים, ולכן תורד דרך Google Play כשהמסך מכוסה בזמן התהליך.\n\nנדרש חשבון Google מחובר במכשיר."
        Key.PLAY_FALLBACK_NO_ACCOUNT -> "⚠ לא זוהה חשבון Google במכשיר זה. ללא חשבון, Google Play לא יוכל להוריד או לעדכן."
        Key.PLAY_FALLBACK_ACCOUNT_BLOCKED -> "⚠ הוספת חשבונות חסומה במכשיר זה. פנה למנהל אם תתבקש כניסה."
        Key.PLAY_FALLBACK_CONTINUE -> "המשך דרך Google Play"
        Key.PLAY_FALLBACK_ADD_ACCOUNT -> "הוסף חשבון Google"
        Key.CANCEL -> "ביטול"
        Key.SILENT_FAILED_TITLE -> "ההורדה הישירה נכשלה"
        Key.SILENT_FAILED_PLAY_PROMPT -> "האם להמשיך להורדה דרך Google Play? המסך יכוסה בזמן ההורדה ונדרש חשבון Google במכשיר."
        Key.OK -> "הבנתי"
        Key.OPEN_FAILED -> "לא ניתן לפתוח את האפליקציה."
        Key.WIDGET_LABEL -> "חנות"
        Key.MODE_CLOSED -> "סגורה"
        Key.MODE_KOSHER -> "כשר"
        Key.MODE_WORK -> "עבודה"
        Key.MODE_LITE -> "Lite"
        Key.POLICY_HINT -> "אילו אפליקציות המשתמש יכול להתקין מהחנות במכשיר הזה. האפליקציות בכל רמה נקבעות על ידי המנהל בלוח האינטרנט."
        Key.MODE_CLOSED_DETAIL -> "החנות מוסתרת."
        Key.MODE_KOSHER_DETAIL -> "מעט אפליקציות (רמה 1)."
        Key.MODE_WORK_DETAIL -> "יותר אפליקציות: רמה 1 + רמה 2."
        Key.MODE_LITE_DETAIL -> "כל החנות: רמות 1, 2 ו-3."
    }
}
