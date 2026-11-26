package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.AccessibilityBouncePolicy.Action
import com.ejemplo.locksuite.mdm.AccessibilityBouncePolicy.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityBouncePolicyTest {

    private val accClasses = setOf(
        "com.android.settings.Settings\$AccessibilitySettingsActivity",
        "com.android.settings.accessibility.AccessibilitySettings"
    )
    private val accTitles = setOf("accesibilidad", "accessibility")

    private fun snap(
        className: String = "com.android.settings.SubSettings",
        windowTitleFolded: String = "",
        inAccessibilityContext: Boolean = false,
        hasOwnDescription: Boolean = false,
        hasOwnLabelInHeader: Boolean = false,
        hasOwnLabelInBody: Boolean = false,
        hasSwitch: Boolean = false,
        hasAccessibilityViewHints: Boolean = false,
        hasExcludedSettingsHints: Boolean = false,
        isDialog: Boolean = false
    ) = ScreenSnapshot(
        className = className,
        windowTitleFolded = windowTitleFolded,
        inAccessibilityContext = inAccessibilityContext,
        knownAccMenuClasses = accClasses,
        knownAccMenuTitles = accTitles,
        hasOwnDescription = hasOwnDescription,
        hasOwnLabelInHeader = hasOwnLabelInHeader,
        hasOwnLabelInBody = hasOwnLabelInBody,
        hasSwitch = hasSwitch,
        hasAccessibilityViewHints = hasAccessibilityViewHints,
        hasExcludedSettingsHints = hasExcludedSettingsHints,
        isDialog = isDialog
    )

    @Test
    fun menuPrincipalAccesibilidadNoRebotaYActivaContexto() {
        // Incluso si el cuerpo lista "LockSuite" en "Apps descargadas" y hay switches de "Texto alto contraste"
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.accessibility.AccessibilitySettings",
                windowTitleFolded = "accesibilidad",
                hasOwnLabelInBody = true,
                hasSwitch = true
            )
        )
        assertEquals(Action.ENTER_ACCESSIBILITY_CONTEXT, res)
    }

    @Test
    fun listaAppsDescargadasDentroDeAccesibilidadNoRebota() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.SubSettings",
                windowTitleFolded = "aplicaciones descargadas",
                inAccessibilityContext = true,
                hasOwnLabelInHeader = false,
                hasOwnLabelInBody = true,
                hasSwitch = false
            )
        )
        assertEquals(Action.IGNORE, res)
    }

    @Test
    fun otraAppDeAccesibilidadComoTalkBackNoRebota() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.SubSettings",
                windowTitleFolded = "talkback",
                inAccessibilityContext = true,
                hasOwnLabelInHeader = false,
                hasSwitch = true,
                hasAccessibilityViewHints = true
            )
        )
        assertEquals(Action.IGNORE, res)
    }

    @Test
    fun pantallaDeLockSuiteConDescripcionRebotaSiempre() {
        // Incluso sin haber pasado por el menú principal (p. ej. entrando desde el buscador de Ajustes)
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.SubSettings",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = false,
                hasOwnDescription = true,
                hasOwnLabelInHeader = true,
                hasSwitch = true
            )
        )
        assertEquals(Action.BOUNCE, res)
    }

    @Test
    fun pantallaChicaDeLockSuiteEnAccesibilidadRebotaAunqueNoSeVeaLaDescripcion() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.SubSettings",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = true,
                hasOwnDescription = false,
                hasOwnLabelInHeader = true,
                hasSwitch = true
            )
        )
        assertEquals(Action.BOUNCE, res)
    }

    @Test
    fun pantallaDetalleToggleSinContextoPeroConHintA11yRebota() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = false,
                hasOwnLabelInHeader = true,
                hasSwitch = true,
                hasAccessibilityViewHints = true
            )
        )
        assertEquals(Action.BOUNCE, res)
    }

    @Test
    fun dialogoDetenerLockSuiteDentroDeAccesibilidadRebota() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "android.app.AlertDialog",
                inAccessibilityContext = true,
                hasOwnLabelInBody = true,
                isDialog = true
            )
        )
        assertEquals(Action.BOUNCE, res)
    }

    @Test
    fun dialogoNoRelacionadoFueraDeAccesibilidadNoRebota() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "android.app.AlertDialog",
                inAccessibilityContext = false,
                hasOwnLabelInBody = true,
                isDialog = true
            )
        )
        assertEquals(Action.IGNORE, res)
    }

    @Test
    fun infoDeAppNotificacionesYPermisosNuncaRebotanYSalenDeContexto() {
        val infoApp = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.applications.InstalledAppDetails",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = true,
                hasOwnLabelInHeader = true,
                hasSwitch = true,
                hasExcludedSettingsHints = true
            )
        )
        assertEquals(Action.LEAVE_ACCESSIBILITY_CONTEXT, infoApp)

        val notif = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.notification.AppNotificationSettings",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = true,
                hasOwnLabelInHeader = true,
                hasSwitch = true
            )
        )
        assertEquals(Action.LEAVE_ACCESSIBILITY_CONTEXT, notif)

        val homeAjustes = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.homepage.SettingsHomepageActivity",
                inAccessibilityContext = true
            )
        )
        assertEquals(Action.LEAVE_ACCESSIBILITY_CONTEXT, homeAjustes)
    }

    @Test
    fun shouldScanOnContentChangedSoloCuandoCorresponde() {
        assertFalse(AccessibilityBouncePolicy.shouldScanOnContentChanged(false, false))
        assertTrue(AccessibilityBouncePolicy.shouldScanOnContentChanged(true, false))
        assertTrue(AccessibilityBouncePolicy.shouldScanOnContentChanged(false, true))
    }

    @Test
    fun isImePackageBySegmentEvitaFalsosPositivosDeSubstring() {
        assertTrue(AccessibilityBouncePolicy.isImePackageBySegment("com.google.android.inputmethod.latin"))
        assertTrue(AccessibilityBouncePolicy.isImePackageBySegment("com.sec.android.inputmethod"))
        assertTrue(AccessibilityBouncePolicy.isImePackageBySegment("com.samsung.android.honeyboard"))
        assertTrue(AccessibilityBouncePolicy.isImePackageBySegment("com.touchtype.swiftkey"))
        assertTrue(AccessibilityBouncePolicy.isImePackageBySegment("com.vendor.ime"))
        assertTrue(AccessibilityBouncePolicy.isImePackageBySegment("com.example.custom_keyboard"))

        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment(null))
        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment(""))
        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment("com.whatsapp.time"))
        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment("com.example.prime"))
        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment("com.example.anime"))
        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment("com.chime.app"))
        assertFalse(AccessibilityBouncePolicy.isImePackageBySegment("com.android.settings"))
    }

    @Test
    fun isTransientWindowPackageConservaContextoParaSystemUiImeYBuscador() {
        assertTrue(AccessibilityBouncePolicy.isTransientWindowPackage("com.android.systemui"))
        assertTrue(AccessibilityBouncePolicy.isTransientWindowPackage("com.android.settings.intelligence"))
        assertTrue(AccessibilityBouncePolicy.isTransientWindowPackage("com.google.android.settings.intelligence"))
        assertTrue(AccessibilityBouncePolicy.isTransientWindowPackage("com.oem.settings.intelligence"))
        assertTrue(AccessibilityBouncePolicy.isTransientWindowPackage("com.samsung.android.honeyboard"))

        assertFalse(AccessibilityBouncePolicy.isTransientWindowPackage(null))
        assertFalse(AccessibilityBouncePolicy.isTransientWindowPackage(""))
        assertFalse(AccessibilityBouncePolicy.isTransientWindowPackage("com.android.settings"))
        assertFalse(AccessibilityBouncePolicy.isTransientWindowPackage("com.whatsapp"))
    }

    @Test
    fun dialogoDetectadoPorEstructuraDeVistasRebotaDentroDeAccesibilidad() {
        assertTrue("button1" in AccessibilityBouncePolicy.DIALOG_VIEW_ID_HINTS)
        assertTrue("alerttitle" in AccessibilityBouncePolicy.DIALOG_VIEW_ID_HINTS)
        assertTrue("parentpanel" in AccessibilityBouncePolicy.DIALOG_VIEW_ID_HINTS)

        val inside = AccessibilityBouncePolicy.classify(
            ScreenSnapshot(
                className = "android.widget.FrameLayout",
                windowTitleFolded = "",
                inAccessibilityContext = true,
                knownAccMenuClasses = accClasses,
                knownAccMenuTitles = accTitles,
                hasOwnDescription = false,
                hasOwnLabelInHeader = false,
                hasOwnLabelInBody = true,
                hasSwitch = false,
                hasAccessibilityViewHints = false,
                hasExcludedSettingsHints = false,
                isDialog = false,
                hasDialogStructure = true
            )
        )
        assertEquals(Action.BOUNCE, inside)

        val outside = AccessibilityBouncePolicy.classify(
            ScreenSnapshot(
                className = "android.widget.FrameLayout",
                windowTitleFolded = "",
                inAccessibilityContext = false,
                knownAccMenuClasses = accClasses,
                knownAccMenuTitles = accTitles,
                hasOwnDescription = false,
                hasOwnLabelInHeader = false,
                hasOwnLabelInBody = true,
                hasSwitch = false,
                hasAccessibilityViewHints = false,
                hasExcludedSettingsHints = false,
                isDialog = false,
                hasDialogStructure = true
            )
        )
        assertEquals(Action.IGNORE, outside)
    }

    @Test
    fun filaInlineConSwitchDeLockSuiteRebotaEnListaDeAccesibilidadPeroNoFuera() {
        val inAccList = AccessibilityBouncePolicy.classify(
            ScreenSnapshot(
                className = "com.android.settings.SubSettings",
                windowTitleFolded = "apps descargadas",
                inAccessibilityContext = true,
                knownAccMenuClasses = accClasses,
                knownAccMenuTitles = accTitles,
                hasOwnDescription = false,
                hasOwnLabelInHeader = false,
                hasOwnLabelInBody = true,
                hasSwitch = true,
                hasAccessibilityViewHints = false,
                hasExcludedSettingsHints = false,
                isDialog = false,
                hasInlineOwnSwitchRow = true
            )
        )
        assertEquals(Action.BOUNCE, inAccList)

        val excludedWins = AccessibilityBouncePolicy.classify(
            ScreenSnapshot(
                className = "com.android.settings.notification.AppNotificationSettings",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = true,
                knownAccMenuClasses = accClasses,
                knownAccMenuTitles = accTitles,
                hasOwnDescription = false,
                hasOwnLabelInHeader = true,
                hasOwnLabelInBody = true,
                hasSwitch = true,
                hasAccessibilityViewHints = false,
                hasExcludedSettingsHints = true,
                isDialog = false,
                hasInlineOwnSwitchRow = true
            )
        )
        assertEquals(Action.LEAVE_ACCESSIBILITY_CONTEXT, excludedWins)

        val outsideAcc = AccessibilityBouncePolicy.classify(
            ScreenSnapshot(
                className = "com.android.settings.SubSettings",
                windowTitleFolded = "uso de bateria",
                inAccessibilityContext = false,
                knownAccMenuClasses = accClasses,
                knownAccMenuTitles = accTitles,
                hasOwnDescription = false,
                hasOwnLabelInHeader = false,
                hasOwnLabelInBody = true,
                hasSwitch = true,
                hasAccessibilityViewHints = false,
                hasExcludedSettingsHints = false,
                isDialog = false,
                hasInlineOwnSwitchRow = true
            )
        )
        assertEquals(Action.IGNORE, outsideAcc)
    }

    @Test
    fun hasOwnDescriptionTienePrioridadSobreHasExcludedSettingsHints() {
        val res = AccessibilityBouncePolicy.classify(
            snap(
                className = "com.android.settings.applications.InstalledAppDetails",
                windowTitleFolded = "locksuite",
                inAccessibilityContext = false,
                hasOwnDescription = true,
                hasExcludedSettingsHints = true
            )
        )
        assertEquals(Action.BOUNCE, res)
    }
}
