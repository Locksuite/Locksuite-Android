package com.ejemplo.locksuite.mdm

/**
 * Clasificación pura (sin dependencias de Android) para el rebote selectivo en Ajustes.
 *
 * Objetivo: permitir al usuario navegar libremente todo el menú de Ajustes y de Accesibilidad
 * (lupa, TalkBack, tamaño de texto, accesibilidad de otras apps, etc.) y rebotar ÚNICAMENTE
 * cuando entra a la pantalla del servicio LockSuite dentro de Accesibilidad (o al diálogo de
 * confirmación "¿Detener LockSuite?").
 *
 * Reglas de diseño para cero falsos positivos:
 *  1. Las pantallas explícitas de Info de la app, Notificaciones, Permisos, Batería, Datos y
 *     accesos especiales SIEMPRE salen de contexto de Accesibilidad aunque muestren el nombre
 *     "LockSuite" junto a un Switch.
 *  2. La descripción del servicio (`R.string.accessibility_description`) es exclusiva de la
 *     ficha de LockSuite en Accesibilidad: si aparece en pantalla (sin estar en una pantalla
 *     excluida) se rebota de inmediato, incluso si se entró por el buscador de Ajustes y en
 *     cualquier idioma del sistema.
 *  3. El menú principal de Accesibilidad (`accSettingsClasses` / `accSettingsTitles`) NO se
 *     rebota: solo marca `ENTER_ACCESSIBILITY_CONTEXT` para reconocer el árbol secundario (p. ej.
 *     en pantallas chicas donde la descripción queda por debajo del primer viewport o en el
 *     diálogo de confirmación).
 */
object AccessibilityBouncePolicy {

    enum class Action {
        /** Está en el menú principal de Accesibilidad: NO rebotar, recordar contexto. */
        ENTER_ACCESSIBILITY_CONTEXT,
        /** Salió de Accesibilidad hacia otra sección de Ajustes: limpiar contexto. */
        LEAVE_ACCESSIBILITY_CONTEXT,
        /** Está en la pantalla propia de LockSuite en Accesibilidad (o su diálogo): rebotar. */
        BOUNCE,
        /** Submenú/otra opción dentro o fuera de Accesibilidad sin acción requerida. */
        IGNORE
    }

    data class ScreenSnapshot(
        val className: String,
        val windowTitleFolded: String,
        val inAccessibilityContext: Boolean,
        val knownAccMenuClasses: Set<String>,
        val knownAccMenuTitles: Set<String>,
        val hasOwnDescription: Boolean,
        val hasOwnLabelInHeader: Boolean,
        val hasOwnLabelInBody: Boolean,
        val hasSwitch: Boolean,
        val hasAccessibilityViewHints: Boolean,
        val hasExcludedSettingsHints: Boolean,
        val isDialog: Boolean
    )

    val EXCLUDED_CLASS_MARKERS: List<String> = listOf(
        "SettingsHomepageActivity",
        "TopLevelSettings",
        "Settings\$WifiSettings",
        "WifiSettings",
        "NetworkDashboard",
        "ConnectedDevice",
        "Bluetooth",
        "InstalledAppDetails",
        "AppInfo",
        "ManageApplications",
        "AppNotification",
        "NotificationSettings",
        "ChannelNotification",
        "AppPermission",
        "GrantPermissions",
        "Battery",
        "PowerUsage",
        "AppBattery",
        "Storage",
        "DisplaySettings",
        "SoundSettings",
        "Account",
        "SyncSettings",
        "SecuritySettings",
        "PrivacySettings",
        "LocationSettings",
        "DrawOverlay",
        "UsageAccess",
        "WriteSettings",
        "ManageExternalStorage",
        "DeviceAdmin",
        "DataUsage",
        "SystemDashboard"
    )

    val ACCESSIBILITY_VIEW_ID_HINTS: List<String> = listOf(
        "accessibility",
        "shortcut_preference",
        "toggle_service",
        "use_service",
        "a11y",
        "animated_image"
    )

    val EXCLUDED_VIEW_ID_HINTS: List<String> = listOf(
        "uninstall_button",
        "force_stop",
        "clear_data",
        "clear_cache",
        "entity_header",
        "app_snippet",
        "notification_switch",
        "notifications_sent",
        "battery_header",
        "admin_policies",
        "restricted_action"
    )

    val HEADER_VIEW_ID_HINTS: List<String> = listOf(
        "action_bar",
        "toolbar",
        "collapsing_toolbar",
        "header_title",
        "alertTitle"
    )

    fun classify(s: ScreenSnapshot): Action {
        val isToggleDetailClass = isAccessibilityServiceDetailClass(s.className)

        // 1. Exclusión estricta: Info de la app / Notificaciones / Permisos / Inicio de Ajustes.
        if (!s.hasOwnDescription && (s.hasExcludedSettingsHints || isExplicitlyNonAccessibilityClass(s.className))) {
            return Action.LEAVE_ACCESSIBILITY_CONTEXT
        }

        // 2. Señal primaria: nuestra descripción de accesibilidad (única de nuestra pantalla en Accesibilidad).
        if (s.hasOwnDescription && !s.hasExcludedSettingsHints) {
            return Action.BOUNCE
        }

        // 3. Menú principal de Accesibilidad (no rebotar, habilitar contexto para subpantallas).
        if (!isToggleDetailClass && !s.hasOwnLabelInHeader && isMainAccessibilityMenu(s)) {
            return Action.ENTER_ACCESSIBILITY_CONTEXT
        }

        // 4. Pantalla de detalle de LockSuite en Accesibilidad (cubre pantallas chicas donde la descripción
        //    puede quedar scrolleada debajo del pliegue del primer render).
        val strongAccSignal = s.inAccessibilityContext || s.hasAccessibilityViewHints || isToggleDetailClass
        if (!s.hasExcludedSettingsHints && s.hasOwnLabelInHeader && s.hasSwitch && strongAccSignal) {
            return Action.BOUNCE
        }

        // 5. Diálogo de confirmación "¿Detener LockSuite?" al intentar apagarlo en Accesibilidad.
        if (s.inAccessibilityContext && s.isDialog && s.hasOwnLabelInBody && !s.hasExcludedSettingsHints) {
            return Action.BOUNCE
        }

        // 6. Cualquier otra subpantalla (Apps instaladas, TalkBack, Lupa, Subtítulos, etc.).
        return Action.IGNORE
    }

    fun isExplicitlyNonAccessibilityClass(cls: String): Boolean {
        if (cls.isBlank()) return false
        if (cls.contains("Accessibility", ignoreCase = true)) return false
        return EXCLUDED_CLASS_MARKERS.any { cls.contains(it, ignoreCase = true) } ||
            cls == "com.android.settings.Settings"
    }

    fun isAccessibilityServiceDetailClass(cls: String): Boolean {
        if (cls.isBlank()) return false
        return cls.contains("ToggleAccessibilityService", ignoreCase = true) ||
            cls.contains("AccessibilityServicePreference", ignoreCase = true) ||
            cls.contains("AccessibilityServiceInfo", ignoreCase = true)
    }

    fun isMainAccessibilityMenu(s: ScreenSnapshot): Boolean {
        if (isAccessibilityServiceDetailClass(s.className)) return false
        if (s.className.isNotBlank() && s.className in s.knownAccMenuClasses) return true
        if (s.className.contains("AccessibilitySettings", ignoreCase = true) ||
            s.className.contains("AccessibilityMenu", ignoreCase = true)
        ) {
            return true
        }
        return s.windowTitleFolded.isNotEmpty() && s.windowTitleFolded in s.knownAccMenuTitles
    }

    fun shouldScanOnContentChanged(
        inAccessibilityContext: Boolean,
        lastTitleMatchesOwnLabel: Boolean
    ): Boolean = inAccessibilityContext || lastTitleMatchesOwnLabel
}
