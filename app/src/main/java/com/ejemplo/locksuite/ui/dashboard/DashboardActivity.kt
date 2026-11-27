package com.ejemplo.locksuite.ui.dashboard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.AppInfoData
import com.ejemplo.locksuite.mdm.EnrollmentProfiles
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.mdm.PolicySpec
import com.ejemplo.locksuite.security.SessionManager
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.LocaleManager
import com.google.firebase.database.FirebaseDatabase
import com.ejemplo.locksuite.dns.*
import com.ejemplo.locksuite.LockSuiteApplication
import java.text.Normalizer
import java.util.Locale

class DashboardActivity : ComponentActivity() {

    companion object {
        private const val INTERNAL_FLOW_TTL_MS = 60_000L

        @Volatile
        private var internalFlowActive: Boolean = false

        @Volatile
        private var internalFlowSetAtElapsedMs: Long = 0L

        var isLaunchingInternalFlow: Boolean
            get() {
                if (!internalFlowActive) return false
                val elapsed = android.os.SystemClock.elapsedRealtime() - internalFlowSetAtElapsedMs
                if (elapsed < 0L || elapsed > INTERNAL_FLOW_TTL_MS) {
                    internalFlowActive = false
                    internalFlowSetAtElapsedMs = 0L
                    return false
                }
                return true
            }
            set(value) {
                internalFlowActive = value
                internalFlowSetAtElapsedMs = if (value) android.os.SystemClock.elapsedRealtime() else 0L
            }
    }

    private val vpnUiRefreshTick = mutableIntStateOf(0)

    private fun bumpVpnUiRefresh() {
        vpnUiRefreshTick.intValue++
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { vpnUiRefreshTick.intValue++ },
                850L
            )
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 19/11/2026 (B.125, T2.2): quién arrancó este proceso (pantalla DashboardActivity).
        com.ejemplo.locksuite.diag.DiagLog.firstEntry("pantalla DashboardActivity")

        // Proteger actividad contra capturas de pantalla/grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        
        // Verificar que la sesión esté activa, de lo contrario volver a LoginActivity
        if (!SessionManager.isActive()) {
            finish()
            return
        }

        setContent {
            DashboardScreen(
                vpnRefreshTick = vpnUiRefreshTick.intValue,
                onBumpVpnRefresh = { bumpVpnUiRefresh() },
                onLogout = {
                    SessionManager.closeSession()
                    finish()
                }
            )
        }
        
        // Sincronizar estado actual a Firebase
        syncStateToFirebase()

        // Solicitar ignorar optimizaciones de batería para asegurar el watchdog (H19)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    isLaunchingInternalFlow = true
                    @Suppress("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    isLaunchingInternalFlow = false
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1024) {
            try {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(this)
                val granted = resultCode == Activity.RESULT_OK
                policyManager.finishTemporaryVpnUnlock(granted = granted, lockAfterOnSuccess = true)
                bumpVpnUiRefresh()

                if (granted) {
                    Toast.makeText(
                        this,
                        "VPN activa y configuración de red bloqueada automáticamente (Lockdown).",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_VPN)) {
                    Toast.makeText(
                        this,
                        "Permiso VPN cancelado; bloqueo de configuración VPN restaurado.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error al configurar VPN permanente: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isLaunchingInternalFlow = false
        try {
            com.ejemplo.locksuite.mdm.PolicyManager(this).restoreTemporaryVpnUnlockIfPending()
        } catch (_: Exception) { }
        bumpVpnUiRefresh()
        if (!SessionManager.isActive()) {
            finish()
        } else {
            SessionManager.updateInteraction()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionManager.updateInteraction()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLaunchingInternalFlow) {
            isLaunchingInternalFlow = false
            return
        }
        // Cerrar sesión solo cuando el usuario minimiza intencionalmente la app
        // (no cuando rota la pantalla ni cambia a un selector de archivos o permiso interno)
        if (!isChangingConfigurations) {
            SessionManager.closeSession()
            finish()
        }
    }

    private fun syncStateToFirebase() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        val data = mapOf(
            "model" to Build.MODEL,
            "isDeviceOwner" to isDeviceOwner,
            "lastSeen" to System.currentTimeMillis()
        )
        try {
            FirebaseDatabase.getInstance()
                .getReference("devices/$deviceId/info")
                .updateChildren(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vpnRefreshTick: Int = 0,
    onBumpVpnRefresh: () -> Unit = {},
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val initialWhitelistOn = remember { PolicyManager(context).isWhitelistModeEnabled() }
    var whitelistModeEnabled by remember { mutableStateOf(initialWhitelistOn) }
    var selectedTab by remember { mutableStateOf(DashboardTab.HOME) }
    val visibleTabs = remember(whitelistModeEnabled) {
        if (whitelistModeEnabled) DashboardTab.entries else DashboardTab.entries.filter { it != DashboardTab.WHITELIST }
    }
    LaunchedEffect(whitelistModeEnabled) {
        if (!whitelistModeEnabled && selectedTab == DashboardTab.WHITELIST) {
            selectedTab = DashboardTab.HOME
        }
    }

    LaunchedEffect(Unit) {
        try {
            val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("devices/$deviceId/deviceName")
                .get()
                .addOnSuccessListener { snap ->
                    val fbName = snap.getValue(String::class.java)
                    if (!fbName.isNullOrEmpty()) {
                        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                        if (prefs.getString("device_name", "") != fbName) {
                            prefs.edit().putString("device_name", fbName).apply()
                            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                        }
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val dashboardScope = rememberCoroutineScope()
    var showReauthDialogForPermissions by remember { mutableStateOf(false) }
    var showReauthDialogForUninstall by remember { mutableStateOf(false) }
    var reauthPinInput by remember { mutableStateOf("") }
    var reauthError by remember { mutableStateOf("") }
    var reauthBusy by remember { mutableStateOf(false) }

    val navyDark = Color(0xFF0E141B)
    val navyMedium = Color(0xFF1A222C)
    val accentOrange = Color(0xFFF5C438)

    if (showReauthDialogForPermissions || showReauthDialogForUninstall) {
        val actionText = if (showReauthDialogForPermissions) "revocar todos los privilegios" else "desinstalar la aplicación"
        AlertDialog(
            onDismissRequest = {
                if (!reauthBusy) {
                    showReauthDialogForPermissions = false
                    showReauthDialogForUninstall = false
                    reauthPinInput = ""
                    reauthError = ""
                }
            },
            title = {
                Text("Re-autenticación Requerida", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Para $actionText, ingrese el PIN de administrador para confirmar su identidad.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = reauthPinInput,
                        onValueChange = {
                            reauthPinInput = it
                            reauthError = ""
                        },
                        enabled = !reauthBusy,
                        label = { Text("PIN de Administrador", color = Color.White.copy(alpha = 0.8f)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentOrange,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedLabelColor = accentOrange,
                            unfocusedLabelColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (reauthError.isNotEmpty()) {
                        Text(reauthError, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !reauthBusy,
                    onClick = {
                        if (com.ejemplo.locksuite.security.PinManager.verifyPin(context, reauthPinInput)) {
                            com.ejemplo.locksuite.security.PinManager.resetAttempts(context)
                            val isRevokeOnly = showReauthDialogForPermissions
                            val isUninstall = showReauthDialogForUninstall
                            val appCtx = context.applicationContext
                            reauthPinInput = ""
                            reauthBusy = true
                            dashboardScope.launch {
                                try {
                                    val released = withContext(Dispatchers.IO) {
                                        com.ejemplo.locksuite.mdm.DeviceRelease.releaseAndPrepareUninstall(
                                            context = appCtx,
                                            promptUninstall = isUninstall
                                        )
                                    }
                                    reauthBusy = false
                                    showReauthDialogForPermissions = false
                                    showReauthDialogForUninstall = false
                                    if (isRevokeOnly) {
                                        val msg = if (released) {
                                            "Permisos MDM revocados con éxito."
                                        } else {
                                            "Restricciones levantadas (verifique estado de Device Owner)."
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        (context as? Activity)?.finishAffinity()
                                    } else if (isUninstall) {
                                        (context as? Activity)?.finish()
                                    }
                                } catch (e: Exception) {
                                    reauthBusy = false
                                    reauthError = "Error: ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                        } else {
                            reauthPinInput = ""
                            when (val status = com.ejemplo.locksuite.security.PinManager.recordFailedAttempt(context)) {
                                is com.ejemplo.locksuite.security.LockoutStatus.LockedOut -> {
                                    showReauthDialogForPermissions = false
                                    showReauthDialogForUninstall = false
                                    com.ejemplo.locksuite.security.SessionManager.closeSession()
                                    (context as? Activity)?.finish()
                                }
                                is com.ejemplo.locksuite.security.LockoutStatus.Warning -> {
                                    reauthError = "PIN incorrecto. Intentos restantes: ${status.remainingAttempts}"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                ) {
                    Text("Confirmar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReauthDialogForPermissions = false
                        showReauthDialogForUninstall = false
                        reauthPinInput = ""
                        reauthError = ""
                    }
                ) {
                    Text("Cancelar", color = Color.White)
                }
            },
            containerColor = navyMedium
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        LocaleManager.t("Panel de Administración"), 
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = navyDark,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Cerrar Sesión",
                            tint = accentOrange
                        )
                    }
                }
            )
        },
        containerColor = navyDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val selectedIndex = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = navyDark,
                contentColor = accentOrange,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    if (selectedIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                            color = accentOrange
                        )
                    }
                }
            ) {
                visibleTabs.forEach { tab ->
                    val isSel = selectedTab == tab
                    Tab(
                        selected = isSel,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                LocaleManager.t(tab.label),
                                color = if (isSel) accentOrange else Color.Gray
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                DashboardTab.HOME -> HomeTabContent(
                    context = context,
                    whitelistModeEnabled = whitelistModeEnabled,
                    onWhitelistModeChanged = { enabled -> whitelistModeEnabled = enabled },
                    onIrA = { destino -> selectedTab = destino }
                )
                DashboardTab.WHITELIST -> WhitelistTabContent(
                    context = context,
                    onDisableWhitelist = {
                        PolicyManager(context).setWhitelistModeEnabled(false)
                        whitelistModeEnabled = false
                        selectedTab = DashboardTab.HOME
                        dashboardScope.launch(Dispatchers.IO) {
                            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncWhitelistState(context, force = true)
                            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                        }
                    }
                )
                DashboardTab.APPS -> AppManagerTabContent(context)
                DashboardTab.POLICIES -> PoliciesTabContent(context)
                DashboardTab.DNS -> DnsActivityTabContent(context)
                DashboardTab.SERVICES -> ServicesTabContent(
                    context = context,
                    vpnRefreshTick = vpnRefreshTick,
                    onBumpVpnRefresh = onBumpVpnRefresh,
                    onTriggerPermissionsReauth = { showReauthDialogForPermissions = true },
                    onTriggerUninstallReauth = { showReauthDialogForUninstall = true }
                )
                DashboardTab.PROFILES -> PresetsTabContent(context)
            }
        }
    }
}

enum class DashboardTab(val label: String) {
    HOME("Inicio"),
    WHITELIST("Lista blanca"),
    APPS("Aplicaciones"),
    POLICIES("Políticas"),
    DNS("DNS"),
    SERVICES("Servicios"),
    PROFILES("Perfiles")
}

@Composable
fun HomeTabContent(
    context: Context,
    whitelistModeEnabled: Boolean,
    onWhitelistModeChanged: (Boolean) -> Unit,
    onIrA: (DashboardTab) -> Unit
) {
    val policyManager = remember { PolicyManager(context) }
    val prefs = remember { PrefsHelper.getMdmPrefs(context) }
    val homeScope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }

    val accentOrange = Color(0xFFF5C438)
    val navyMedium = Color(0xFF1A222C)
    val rojo = Color(0xFFEF4444)
    val verde = Color(0xFF10B981)
    val amarillo = Color(0xFFF59E0B)

    // Se releen en cada refresco y no en un ciclo propio: esta pantalla se mira unos
    // segundos, no se deja abierta. Un `LaunchedEffect` con `delay` acá sería un
    // despertar más por nada — el criterio de batería que dejó B.30.
    val suspendido = remember(refreshKey) { policyManager.isLockSuiteSuspended() }
    val accesibilidadOk = remember(refreshKey) {
        try { com.ejemplo.locksuite.util.AccessibilityEnforcer.isServiceRunning(context) }
        catch (e: Exception) { true }
    }
    val saludTunel = remember(refreshKey) {
        try { com.ejemplo.locksuite.service.KosherVpnService.tunnelHealth(context) }
        catch (e: Exception) { com.ejemplo.locksuite.service.KosherVpnService.HEALTH_UNKNOWN }
    }
    val listaBlancaOn = whitelistModeEnabled
    val simulando = remember(refreshKey, whitelistModeEnabled) { policyManager.isWhitelistSimulation() }
    val graciaActiva = remember(refreshKey) {
        try { com.ejemplo.locksuite.mdm.GracePeriodManager.isActive(context) }
        catch (e: Exception) { false }
    }
    val graciaRestanteMs = remember(refreshKey) {
        try { com.ejemplo.locksuite.mdm.GracePeriodManager.remainingMs(context) }
        catch (e: Exception) { 0L }
    }
    val nombreEquipo = remember(refreshKey) { prefs.getString("device_name", "") ?: "" }

    // Los avisos: SOLO lo que está mal ahora, ordenado por gravedad.
    val avisos = buildList {
        if (suspendido) add(Triple(rojo, "LockSuite está suspendido",
            "No hay ninguna protección activa. Se puede desinstalar y restaurar de fábrica."))
        if (!accesibilidadOk) add(Triple(rojo, "El filtro visual está caído",
            "El servicio de Accesibilidad no está corriendo: no se tapan imágenes ni se rebota nada."))
        if (saludTunel == com.ejemplo.locksuite.service.KosherVpnService.HEALTH_NO_CAPTURE ||
            saludTunel == com.ejemplo.locksuite.service.KosherVpnService.HEALTH_NO_UPSTREAM) {
            add(Triple(amarillo, "El túnel DNS reporta «$saludTunel»",
                "El filtro de dominios puede no estar filtrando. Probá apagar y prender la VPN."))
        }
        if (saludTunel == com.ejemplo.locksuite.service.KosherVpnService.HEALTH_OFF) {
            add(Triple(rojo, "El túnel DNS está apagado", "Ningún dominio se está filtrando."))
        }
        if (listaBlancaOn && simulando) add(Triple(amarillo, "La lista blanca está en simulación",
            "Registra qué bloquearía, pero todavía no bloquea nada."))
        if (graciaActiva) {
            val horas = graciaRestanteMs / 3_600_000L
            add(Triple(accentOrange, "Período de gracia activo",
                "Faltan aproximadamente $horas h para que se cierre solo."))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                if (nombreEquipo.isNotBlank()) nombreEquipo else LocaleManager.t("Este celular"),
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
        }

        // ── Estado ──
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(LocaleManager.t("Estado"), color = accentOrange,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (avisos.isEmpty()) {
                        Text(
                            "✓ " + LocaleManager.t("Todo en orden: filtro visual activo, túnel DNS filtrando y ninguna protección levantada."),
                            color = verde, fontSize = 13.sp
                        )
                    } else {
                        avisos.forEach { (color, titulo, detalle) ->
                            Column {
                                Text("• " + LocaleManager.t(titulo), color = color,
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(LocaleManager.t(detalle),
                                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }
                    TextButton(onClick = { refreshKey++ }) {
                        Text(LocaleManager.t("Volver a chequear"), color = accentOrange, fontSize = 13.sp)
                    }
                }
            }
        }

        // ── Modo Lista blanca (oculto hasta que se active acá) ──
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🛡️ Activar modo lista blanca",
                                color = accentOrange,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                if (listaBlancaOn) {
                                    if (simulando) "Activo (simulación). La pestaña Lista blanca está habilitada para este equipo."
                                    else "Activo (estricto). Solo resuelven los dominios de tu Lista blanca."
                                } else {
                                    "Apagado: el celular funciona en modo lista negra normal y la sección Lista blanca permanece oculta."
                                },
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = listaBlancaOn,
                            onCheckedChange = { enabled ->
                                policyManager.setWhitelistModeEnabled(enabled)
                                onWhitelistModeChanged(enabled)
                                refreshKey++
                                homeScope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncWhitelistState(context, force = true)
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(
                                    context,
                                    if (enabled) "Modo lista blanca activado" else "Modo lista blanca desactivado (modo lista negra)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0E141B),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }
                    if (listaBlancaOn) {
                        TextButton(onClick = { onIrA(DashboardTab.WHITELIST) }) {
                            Text("Abrir Lista blanca →", color = accentOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ── Configuración rápida (la MISMA tarjeta de la pestaña Perfiles) ──
        item {
            MasterProfilesCard(policyManager = policyManager, onApplied = { refreshKey++ })
        }

        // ── A dónde ir ──
        item {
            Text(LocaleManager.t("Configurar"), color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        val destinos = buildList {
            if (listaBlancaOn) {
                add(Triple(DashboardTab.WHITELIST, "🛡️ " + LocaleManager.t("Lista blanca"),
                    LocaleManager.t("Apps y dominios permitidos o prohibidos en este equipo, listas globales, respaldos y auditoría.")))
            }
            add(Triple(DashboardTab.APPS, "🧩 " + LocaleManager.t("Aplicaciones"),
                LocaleManager.t("Ocultar, suspender o desinstalar apps una por una, y ver el inventario del equipo.")))
            add(Triple(DashboardTab.POLICIES, "🔒 " + LocaleManager.t("Políticas"),
                LocaleManager.t("Los interruptores del sistema: cámara, wifi, instalación, restauración de fábrica y filtros.")))
            add(Triple(DashboardTab.DNS, "🌐 " + LocaleManager.t("DNS"),
                LocaleManager.t("Forzar permitir o prohibir un dominio puntual. Le gana a todo lo demás.")))
            add(Triple(DashboardTab.SERVICES, "⚙️ " + LocaleManager.t("Servicios"),
                LocaleManager.t("Accesibilidad, VPN, PIN de administrador y estado de los servicios de fondo.")))
            add(Triple(DashboardTab.PROFILES, "📋 " + LocaleManager.t("Perfiles"),
                LocaleManager.t("Exportar e importar la configuración completa de este equipo.")))
        }

        items(destinos) { (destino, titulo, detalle) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onIrA(destino) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(titulo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(detalle, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoliciesTabContent(context: Context) {
    val policyManager = remember { PolicyManager(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    
    val prefs = remember { com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context) }
    var deviceNameInput by remember { mutableStateOf(prefs.getString("device_name", "") ?: "") }

    val isSuspendedNow = remember(refreshKey) { policyManager.isLockSuiteSuspended() }
    val suspendedSince = remember(refreshKey) { policyManager.getLockSuiteSuspendedAt() }
    var pendingSuspendConfirm by remember { mutableStateOf(false) }
    var pendingKioskConfirm by remember { mutableStateOf(false) }
    var pendingNokiaTouchOffConfirm by remember { mutableStateOf(false) }
    var pendingIabEnforceConfirm by remember { mutableStateOf(false) }
    var policySearchQuery by rememberSaveable { mutableStateOf("") }
    var onlyEnabledPolicies by rememberSaveable { mutableStateOf(false) }

    val allSections = remember(refreshKey) {
        listOf(
            PolicySection(
                title = "Bloqueo de Play Store",
                items = listOf(
                    PolicySwitchItem(
                        key = "playStoreAppBlocked",
                        label = "Bloqueo de Play Store (Bloquear app)",
                        isChecked = policyManager.isPlayStoreAppBlocked(),
                        onCheckedChange = { policyManager.setPlayStoreAppBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "playStoreAccessibilityBlocked",
                        label = "Bloqueo de Play Store (Por accesibilidad)",
                        isChecked = policyManager.isPlayStoreAccessibilityBlocked(),
                        onCheckedChange = { policyManager.setPlayStoreAccessibilityBlocked(it).also { refreshKey++ } }
                    )
                )
            ),
            PolicySection(
                title = "Políticas de Sistema (Device Owner)",
                items = listOf(
                    PolicySwitchItem(
                        key = "factoryResetBlocked",
                        label = "Bloquear Restauración de Fábrica",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_FACTORY_RESET),
                        onCheckedChange = { policyManager.setFactoryResetBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "frpEnabled",
                        label = "Protección de Restablecimiento (FRP)",
                        isChecked = policyManager.isFrpEnabled(),
                        onCheckedChange = { policyManager.setFrpPolicy(policyManager.getFrpAccounts(), policyManager.useDefaultFrp(), it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "installAppsBlocked",
                        label = "Bloquear Instalación de Apps",
                        isChecked = policyManager.isInstallAppsBlocked(),
                        onCheckedChange = { policyManager.setInstallAppsBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "uninstallAppsBlocked",
                        label = "Bloquear Desinstalación de Apps",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_UNINSTALL_APPS),
                        onCheckedChange = { policyManager.setUninstallAppsBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "adbBlocked",
                        label = "Bloquear ADB y Opciones de Desarrollador",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES),
                        onCheckedChange = { policyManager.setDebuggingFeaturesBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "userSwitchBlocked",
                        label = "Bloquear Cambio de Usuario",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_USER_SWITCH),
                        onCheckedChange = { policyManager.setUserSwitchBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "modifyAccountsBlocked",
                        label = "Bloquear Modificación de Cuentas",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS),
                        onCheckedChange = { policyManager.setModifyAccountsBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "safeBootBlocked",
                        label = "Bloquear Reinicio Seguro (Safe Boot)",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_SAFE_BOOT),
                        onCheckedChange = { policyManager.setSafeBootBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "unknownSourcesBlocked",
                        label = "Bloquear Orígenes Desconocidos (APK)",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
                        onCheckedChange = { policyManager.setUnknownSourcesBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "wifiBlocked",
                        label = "Bloquear Ajustes de Red / WiFi / Datos",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_WIFI),
                        onCheckedChange = { policyManager.setWifiConfigBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "vpnBlocked",
                        label = "Bloquear Ajustes de VPN",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_VPN),
                        onCheckedChange = { policyManager.setVpnConfigBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "appsControlBlocked",
                        label = "Bloquear Control de Apps",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_APPS_CONTROL),
                        onCheckedChange = { policyManager.setAppsControlBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "adjustVolumeBlocked",
                        label = "Bloquear Ajuste de Volumen",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_ADJUST_VOLUME),
                        onCheckedChange = { policyManager.setAdjustVolumeBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "accessibilityProtected",
                        label = "Proteger Servicio de Accesibilidad",
                        isChecked = policyManager.isAccessibilityProtectionEnabled(),
                        onCheckedChange = { policyManager.setAccessibilityProtection(it).also { refreshKey++ } }
                    )
                )
            ),
            PolicySection(
                title = "Restricciones Adicionales",
                items = PolicySpec.EXTRA_RESTRICTIONS.map { spec ->
                    PolicySwitchItem(
                        key = spec.reportField,
                        label = spec.label,
                        subtitle = if (spec.supportedHere) null
                        else "No soportado en Android ${Build.VERSION.RELEASE} (requiere API ${spec.minSdk}).",
                        isChecked = policyManager.isExtraRestrictionEnabled(spec),
                        enabled = spec.supportedHere,
                        onCheckedChange = { enabled ->
                            policyManager.setExtraRestriction(spec, enabled).also { refreshKey++ }
                        }
                    )
                }
            ),
            PolicySection(
                title = "Modo Teléfono de Teclas",
                items = listOf(
                    PolicySwitchItem(
                        key = "kioskLockTaskEnabled",
                        label = "Modo kiosco (anclar al launcher kosher)",
                        isChecked = policyManager.isKioskLockTaskEnabled(),
                        onCheckedChange = { wantKiosk ->
                            if (wantKiosk) {
                                pendingKioskConfirm = true
                                false
                            } else {
                                policyManager.setKioskLockTaskEnabled(false).also { refreshKey++ }
                            }
                        }
                    ),
                    PolicySwitchItem(
                        key = "nokiaKeypadMode",
                        label = "Activar modo teléfono de teclas (Nokia)",
                        isChecked = policyManager.isNokiaKeypadMode(),
                        onCheckedChange = { isChecked ->
                            if (isChecked && !policyManager.isKosherLauncherEnabled()) {
                                policyManager.setKosherLauncherEnabled(true)
                            }
                            policyManager.setNokiaKeypadMode(isChecked).also { refreshKey++ }
                        }
                    ),
                    PolicySwitchItem(
                        key = "nokiaTouchEnabled",
                        label = "  ↳ Táctil del launcher de teclas",
                        isChecked = policyManager.isNokiaTouchEnabled(),
                        onCheckedChange = { wantTouch ->
                            if (!wantTouch) {
                                pendingNokiaTouchOffConfirm = true
                                false
                            } else {
                                policyManager.setNokiaTouchEnabled(true).also { refreshKey++ }
                            }
                        }
                    )
                )
            ),
            PolicySection(
                title = "Protecciones de Accesibilidad",
                items = listOf(
                    PolicySwitchItem(
                        key = "googleAccountWebBlocked",
                        label = "Bloquear ajustes de la cuenta de Google (historial y actividad)",
                        isChecked = policyManager.isGoogleAccountWebBlocked(),
                        onCheckedChange = { policyManager.setGoogleAccountWebBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "googleAccountBlockStrict",
                        label = "  ↳ Modo estricto (no abrir NINGUNA pantalla de la cuenta)",
                        isChecked = policyManager.isGoogleAccountBlockStrict(),
                        onCheckedChange = { policyManager.setGoogleAccountBlockStrict(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "captivePortalGuard",
                        label = "Vigilar la ventana de inicio de sesión de Wi-Fi (portal cautivo)",
                        isChecked = policyManager.isCaptivePortalGuardEnabled(),
                        onCheckedChange = { policyManager.setCaptivePortalGuard(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "captivePortalCoverImages",
                        label = "  ↳ Tapar imágenes del portal cautivo (apagar si no deja iniciar sesión)",
                        isChecked = policyManager.isCaptivePortalCoverImagesEnabled(),
                        onCheckedChange = { policyManager.setCaptivePortalCoverImages(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "contactPhotoPickerBlocked",
                        label = "Bloquear selector de fotos e ilustraciones de contactos",
                        isChecked = policyManager.isContactPhotoPickerBlocked(),
                        onCheckedChange = { policyManager.setContactPhotoPickerBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "geminiMessagesBlocked",
                        label = "Bloqueo de gemini en mensajes",
                        isChecked = policyManager.isGeminiMessagesBlocked(),
                        onCheckedChange = { policyManager.setGeminiMessagesBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "santanderBlocked",
                        label = "Bloqueo de Santander",
                        isChecked = policyManager.isSantanderBlocked(),
                        onCheckedChange = { policyManager.setSantanderBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "localeChangeBlocked",
                        label = "Bloquear cambio de idioma del sistema",
                        isChecked = policyManager.isLocaleChangeBlocked(),
                        onCheckedChange = { policyManager.setLocaleChangeBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "settingsEvasionEnabled",
                        label = "Auto Evasión en Ajustes",
                        isChecked = policyManager.isSettingsEvasionEnabled(),
                        onCheckedChange = { policyManager.setSettingsEvasionEnabled(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "accBounceSettings",
                        label = "Rebotar el menú de Accesibilidad de Ajustes",
                        isChecked = policyManager.isAccBounceSettingsEnabled(),
                        onCheckedChange = { policyManager.setAccBounceSettings(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "accNag",
                        label = "Aviso insistente cada ~18 s si está apagada",
                        isChecked = policyManager.isAccNagEnabled(),
                        onCheckedChange = { policyManager.setAccNag(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "accSuspendAll",
                        label = "Suspender TODAS las apps mientras esté apagada",
                        isChecked = policyManager.isAccSuspendAllEnabled(),
                        onCheckedChange = { policyManager.setAccSuspendAll(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "bootGateWaitAccessibility",
                        label = "Arranque protegido: esperar también a la Accesibilidad",
                        isChecked = policyManager.isBootGateWaitAccessibilityEnabled(),
                        onCheckedChange = { policyManager.setBootGateWaitAccessibility(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "iabFinderEnabled",
                        label = "Detector de navegadores embebidos (IAB)",
                        isChecked = policyManager.isEmbeddedBrowserFinderEnabled(),
                        onCheckedChange = { policyManager.setEmbeddedBrowserFinder(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "iabFinderEnforce",
                        label = "  ↳ Bloquear de verdad navegadores embebidos (apagado = solo simular)",
                        isChecked = !policyManager.isEmbeddedBrowserSimulation(),
                        onCheckedChange = { enforce ->
                            if (enforce) {
                                pendingIabEnforceConfirm = true
                                false
                            } else {
                                policyManager.setEmbeddedBrowserSimulation(true).also { refreshKey++ }
                            }
                        }
                    )
                )
            ),
            PolicySection(
                title = "Arranque protegido",
                items = listOf(
                    PolicySwitchItem(
                        key = "bootGateEnabled",
                        label = "Arranque protegido (cerrar la red hasta que el filtro esté listo)",
                        isChecked = policyManager.isBootGateEnabled(),
                        onCheckedChange = { policyManager.setBootGateEnabled(it).also { refreshKey++ } }
                    )
                )
            ),
            PolicySection(
                title = "Control de Hardware y Pantalla",
                items = listOf(
                    PolicySwitchItem(
                        key = "cameraDisabled",
                        label = "Deshabilitar Cámara Física",
                        isChecked = policyManager.isCameraDisabled(),
                        onCheckedChange = { policyManager.setCameraDisabled(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "screenCaptureBlocked",
                        label = "Bloquear Capturas de Pantalla (Screenshots)",
                        isChecked = policyManager.isScreenCaptureBlocked(),
                        onCheckedChange = { policyManager.setScreenCaptureBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "statusBarDisabled",
                        label = "Bloquear Barra de Notificaciones (Android 9+)",
                        isChecked = policyManager.isStatusBarDisabled(),
                        onCheckedChange = { policyManager.setStatusBarDisabled(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "keyguardDisabled",
                        label = "Deshabilitar Bloqueo de Pantalla (Keyguard)",
                        isChecked = policyManager.isKeyguardDisabled(),
                        onCheckedChange = { policyManager.setKeyguardDisabled(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "kosherLauncherEnabled",
                        label = "Activar Modo Launcher MP3 Kosher",
                        isChecked = policyManager.isKosherLauncherEnabled(),
                        onCheckedChange = { isChecked ->
                            if (isChecked && !android.provider.Settings.canDrawOverlays(context)) {
                                try {
                                    DashboardActivity.isLaunchingInternalFlow = true
                                    val intent = Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                    Toast.makeText(context, "Por favor, concede el permiso de superposición para la marca de agua", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    try {
                                        DashboardActivity.isLaunchingInternalFlow = true
                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        DashboardActivity.isLaunchingInternalFlow = false
                                        ex.printStackTrace()
                                    }
                                }
                            }
                            policyManager.setKosherLauncherEnabled(isChecked).also { refreshKey++ }
                        }
                    )
                )
            ),
            PolicySection(
                title = "Conectividad y Filtros",
                items = listOf(
                    PolicySwitchItem(
                        key = "internetBlocked",
                        label = "Bloquear Todo el Internet (WiFi y Datos)",
                        isChecked = policyManager.isInternetBlocked(),
                        onCheckedChange = { policyManager.setInternetBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "browsersSuspended",
                        label = "Deshabilitar Navegadores de Internet (Chrome, Firefox, etc.)",
                        isChecked = policyManager.areBrowsersSuspended(),
                        onCheckedChange = { policyManager.setBrowsersSuspended(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "systemWebViewSuspended",
                        label = "Deshabilitar WebView del Sistema (Bloqueo Global)",
                        isChecked = policyManager.isSystemWebViewSuspended(),
                        onCheckedChange = { policyManager.setSystemWebViewSuspended(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "bluetoothBlocked",
                        label = "Bloquear Bluetooth",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH),
                        onCheckedChange = { policyManager.setBluetoothBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "bluetoothSharingBlocked",
                        label = "Bloquear Envío de Archivos Bluetooth",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH_SHARING),
                        onCheckedChange = { policyManager.setBluetoothSharingBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "externalMediaBlocked",
                        label = "Bloquear Montaje de Medios Externos (USB OTG/SD)",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA),
                        onCheckedChange = { policyManager.setExternalMediaBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "tetheringBlocked",
                        label = "Bloquear Zona WiFi / Tethering",
                        isChecked = policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_TETHERING),
                        onCheckedChange = { policyManager.setTetheringBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "adBlockingEnabled",
                        label = "Filtro de Anuncios DNS (AdBlock)",
                        isChecked = policyManager.isAdBlockingEnabled(),
                        onCheckedChange = { policyManager.setAdBlockingEnabled(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "gifsBlocked",
                        label = "Bloquear GIFs y Stickers en Gboard (Tenor)",
                        isChecked = policyManager.isGifsBlocked(),
                        onCheckedChange = { policyManager.setGifsBlocked(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "stealthModeEnabled",
                        label = "Modo Oculto (Stealth Mode)",
                        isChecked = isStealthModeEnabled(context),
                        onCheckedChange = { setStealthModeEnabled(context, it).also { refreshKey++ } }
                    )
                )
            ),
            PolicySection(
                title = "Filtros de Servicios",
                items = listOf(
                    PolicySwitchItem(
                        key = "whatsappBlockStatus",
                        label = "Bloquear Estados (WhatsApp)",
                        isChecked = policyManager.isWhatsAppBlockStatusEnabled(),
                        onCheckedChange = {
                            policyManager.setWhatsAppBlockStatus(it)
                            refreshKey++
                            true
                        }
                    ),
                    PolicySwitchItem(
                        key = "whatsappBlockChannels",
                        label = "Bloquear Canales (WhatsApp)",
                        isChecked = policyManager.isWhatsAppBlockChannelsEnabled(),
                        onCheckedChange = {
                            policyManager.setWhatsAppBlockChannels(it)
                            refreshKey++
                            true
                        }
                    ),
                    PolicySwitchItem(
                        key = "mercadoPagoBlockOffersAccessibility",
                        label = "Mercado Pago: Bloquear Ofertas (Accesibilidad)",
                        isChecked = policyManager.isMercadoPagoBlockOffersAccessibilityEnabled(),
                        onCheckedChange = {
                            policyManager.setMercadoPagoBlockOffersAccessibility(it)
                            refreshKey++
                            true
                        }
                    ),
                    PolicySwitchItem(
                        key = "mercadoPagoBlockOffersVpn",
                        label = "Mercado Pago: Bloquear Ofertas (por VPN)",
                        isChecked = policyManager.isMercadoPagoBlockOffersVpnEnabled(),
                        onCheckedChange = {
                            policyManager.setMercadoPagoBlockOffersVpn(it)
                            refreshKey++
                            true
                        }
                    ),
                    PolicySwitchItem(
                        key = "blockMlInMp",
                        label = "Bloqueo de Mercado Libre en Mercado Pago",
                        isChecked = policyManager.isMercadoLibreInMpBlocked(),
                        onCheckedChange = {
                            policyManager.setMercadoLibreInMpBlocked(it)
                            refreshKey++
                            true
                        }
                    ),
                    PolicySwitchItem(
                        key = "blockPopularNonKosher",
                        label = "Bloquear apps populares no kosher",
                        isChecked = policyManager.isBlockPopularNonKosherEnabled(),
                        onCheckedChange = { policyManager.setBlockPopularNonKosher(it).also { refreshKey++ } }
                    ),
                    PolicySwitchItem(
                        key = "hideSuspendedApps",
                        label = "Ocultar icono al suspender aplicaciones",
                        isChecked = policyManager.isHideSuspendedApps(),
                        onCheckedChange = {
                            policyManager.setHideSuspendedApps(it)
                            refreshKey++
                            true
                        }
                    )
                )
            ),
            PolicySection(
                title = "Diagnóstico remoto",
                items = listOf(
                    PolicySwitchItem(
                        key = "diagUploadEnabled",
                        label = "Subir diagnóstico remoto al panel",
                        isChecked = com.ejemplo.locksuite.diag.DiagLog.isUploadEnabled(),
                        onCheckedChange = {
                            com.ejemplo.locksuite.diag.DiagLog.setUploadEnabled(context, it)
                            refreshKey++
                            true
                        }
                    )
                )
            )
        )
    }

    val normalizedQuery = normalizePolicySearch(policySearchQuery.trim())
    val activeCount = allSections.sumOf { s -> s.items.count { it.isChecked } } + if (isSuspendedNow) 1 else 0
    val totalCount = allSections.sumOf { it.items.size } + 1
    val filteredSections = remember(allSections, normalizedQuery, onlyEnabledPolicies) {
        allSections.mapNotNull { section ->
            val sectionMatches = normalizedQuery.isNotBlank() && (
                normalizePolicySearch(section.title).contains(normalizedQuery) ||
                    normalizePolicySearch(LocaleManager.t(section.title)).contains(normalizedQuery)
                )
            val matchingItems = section.items.filter { item ->
                val matchesEnabled = !onlyEnabledPolicies || item.isChecked
                val matchesQuery = normalizedQuery.isBlank() ||
                    sectionMatches ||
                    normalizePolicySearch(item.label).contains(normalizedQuery) ||
                    normalizePolicySearch(LocaleManager.t(item.label)).contains(normalizedQuery) ||
                    normalizePolicySearch(item.key).contains(normalizedQuery)
                matchesEnabled && matchesQuery
            }
            if (matchingItems.isEmpty()) null else section.copy(items = matchingItems)
        }
    }
    val isTempInstallActiveNow = com.ejemplo.locksuite.mdm.TempInstallWindowManager.isActive(context)
    val showTempInstallCard = isTempInstallActiveNow || (
        !onlyEnabledPolicies && (
            normalizedQuery.isBlank() ||
                normalizePolicySearch("Permitir instalar apps temporal Play Store tiempo desbloquear")
                    .contains(normalizedQuery)
            )
        )
    val showSuspendCard = (normalizedQuery.isBlank() ||
        normalizePolicySearch("Suspender LockSuite Suspensión activa locksuiteSuspended").contains(normalizedQuery)) &&
        (!onlyEnabledPolicies || isSuspendedNow)
    val showSetupCards = normalizedQuery.isBlank() && !onlyEnabledPolicies

    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

    LaunchedEffect(refreshKey) {
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "policy_search_bar") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = policySearchQuery,
                    onValueChange = { policySearchQuery = it },
                    placeholder = { Text("Buscar política...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFF5C438)) },
                    trailingIcon = {
                        if (policySearchQuery.isNotEmpty()) {
                            IconButton(onClick = { policySearchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda", tint = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF5C438),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1A222C),
                        unfocusedContainerColor = Color(0xFF1A222C)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = onlyEnabledPolicies,
                        onClick = { onlyEnabledPolicies = !onlyEnabledPolicies },
                        label = { Text("Solo encendidas", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF1A222C),
                            labelColor = Color.White.copy(alpha = 0.85f),
                            selectedContainerColor = Color(0xFFF5C438),
                            selectedLabelColor = Color(0xFF0E141B)
                        )
                    )
                    Text(
                        text = "$activeCount/$totalCount encendidas",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ──────────────────────────────────────────────
        // Suspensión temporal de LockSuite
        //
        // Va primero y con colores propios a propósito: es el único interruptor
        // de esta pantalla que apaga TODO lo demás, y un equipo que quedó
        // suspendido por olvido es un equipo sin ninguna protección.
        // ──────────────────────────────────────────────
        if (showSuspendCard) {
            item(key = "suspend_locksuite_card") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuspendedNow) Color(0xFF7F3B12) else Color(0xFF1A222C)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (isSuspendedNow) "⚠ LockSuite SUSPENDIDO" else "Suspender LockSuite",
                            color = if (isSuspendedNow) Color.White else Color(0xFFF5C438),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            if (isSuspendedNow)
                                "Todas las restricciones están levantadas y todas las aplicaciones desbloqueadas. El equipo está funcionando como si LockSuite no estuviera instalado — incluido poder desinstalarlo o restaurarlo de fábrica. Desactivá la suspensión para que todo vuelva exactamente a como estaba."
                            else
                                "Levanta temporalmente TODAS las restricciones y desbloquea todas las aplicaciones, como si LockSuite no estuviera instalado. Al desactivarla, todo vuelve exactamente a como estaba: no hace falta reconfigurar nada.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        if (isSuspendedNow && suspendedSince > 0L) {
                            Text(
                                "Suspendido desde: " + java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(suspendedSince)),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                        PolicySwitchRow(
                            label = "Suspensión activa",
                            isChecked = isSuspendedNow,
                            onCheckedChange = { wantSuspend ->
                                if (wantSuspend) {
                                    // Confirmación obligatoria: es destructivo en el
                                    // sentido de que deja el equipo completamente abierto.
                                    pendingSuspendConfirm = true
                                    false // el switch se mueve recién si el admin confirma
                                } else {
                                    val ok = policyManager.setLockSuiteSuspended(false)
                                    if (ok) {
                                        Toast.makeText(context, "Suspensión desactivada. Restricciones restauradas.", Toast.LENGTH_LONG).show()
                                    }
                                    refreshKey++
                                    ok
                                }
                            }
                        )
                    }
                }
            }
        }

        if (showSetupCards) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Identificación del Dispositivo", color = Color(0xFFF5C438), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = deviceNameInput,
                            onValueChange = { deviceNameInput = it },
                            placeholder = { Text("Nombre del dispositivo", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFF5C438),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                // pushDeviceName() en vez de "guardar la preferencia y
                                // sincronizar": desde el 2/9/2026 la sincronización normal
                                // ya NO manda el nombre (mandarlo era lo que borraba el
                                // nombre puesto desde el panel — ver
                                // FirebaseDeviceSync.reconcileDeviceName). Este botón es
                                // el único lugar donde el celular tiene que ganarle al
                                // panel, así que usa la vía explícita.
                                com.ejemplo.locksuite.util.FirebaseDeviceSync.pushDeviceName(context, deviceNameInput)
                                android.widget.Toast.makeText(context, "Nombre guardado con éxito", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5C438), contentColor = Color(0xFF0E141B))
                        ) {
                            Text("Guardar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (!isDeviceOwner) {
            item {
                var showMultimediaDialog by remember { mutableStateOf(false) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7B241C)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, "Advertencia", tint = Color.White)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "ADVERTENCIA: La app no está configurada como Device Owner. Actívelo desde aquí mismo sin PC o mediante ADB.",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                showMultimediaDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF5C438),
                                contentColor = Color(0xFF0E141B)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🚗 Activar Device Owner sin PC (Multimedia / Auto)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (showMultimediaDialog) {
                    com.ejemplo.locksuite.ui.provisioning.MultimediaProvisioningDialog(
                        onDismiss = {
                            showMultimediaDialog = false
                        },
                        onActivated = {
                            showMultimediaDialog = false
                            refreshKey++
                            Toast.makeText(context, "¡Device Owner activado con éxito!", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
        }

        if (filteredSections.isEmpty() && !showSuspendCard && !showTempInstallCard) {
            item(key = "policy_empty_state") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Ninguna política coincide.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (showTempInstallCard) {
            item(key = "temp_install_window") {
                TempInstallWindowCard(refreshKey = refreshKey, onChanged = { refreshKey++ })
            }
        }

        items(filteredSections, key = { it.title }) { section ->
            PolicyGroupCard(title = section.title) {
                section.items.forEach { switchItem ->
                    key(switchItem.key) {
                        PolicySwitchRow(
                            label = switchItem.label,
                            isChecked = switchItem.isChecked,
                            subtitle = switchItem.subtitle,
                            enabled = switchItem.enabled,
                            onCheckedChange = switchItem.onCheckedChange
                        )
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Grupo 5: TIENDA PLAY  (29/10/2026, B.101)
        // Qué modo de la tienda tiene este equipo (cerrada / kosher / trabajo / lite).
        // Las apps de cada nivel las define el administrador en el panel web.
        // ─────────────────────────────────────────────────────────────────────
        if (!onlyEnabledPolicies && (normalizedQuery.isBlank() ||
                normalizePolicySearch("Tienda Play Modo Cerrada Kosher Trabajo Lite").contains(normalizedQuery))) {
            item {
                PlayStoreModeGroup(refreshKey = refreshKey, onChanged = { refreshKey++ })
            }
        }
    }

    if (pendingSuspendConfirm) {
        AlertDialog(
            onDismissRequest = { pendingSuspendConfirm = false },
            containerColor = Color(0xFF0E141B),
            title = { Text("¿Suspender LockSuite?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Se van a levantar TODAS las restricciones y a desbloquear todas las aplicaciones de este equipo, incluidas las protecciones que impiden desinstalar LockSuite y restaurar de fábrica.\n\n" +
                        "Mientras dure la suspensión el equipo queda sin ninguna protección. Al desactivarla, todo vuelve exactamente a como estaba.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSuspendConfirm = false
                    val ok = policyManager.setLockSuiteSuspended(true)
                    Toast.makeText(
                        context,
                        if (ok) "LockSuite suspendido. El equipo quedó sin restricciones." else "No se pudo suspender LockSuite.",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshKey++
                }) {
                    Text("Sí, suspender", color = Color(0xFFE67E22), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSuspendConfirm = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    if (pendingKioskConfirm) {
        AlertDialog(
            onDismissRequest = { pendingKioskConfirm = false },
            containerColor = Color(0xFF0E141B),
            title = { Text("¿Anclar equipo al launcher kosher?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Android va a permitir abrir ÚNICAMENTE las apps de la lista permitida. Ninguna notificación ni enlace va a poder abrir otra cosa.\n\n" +
                        "⚠️ IMPORTANTE: si el marcador telefónico NO está en la lista permitida, en este equipo no se va a poder marcar el código de recuperación *#*#9999#*#*.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingKioskConfirm = false
                    val ok = policyManager.setKioskLockTaskEnabled(true)
                    if (!ok) {
                        Toast.makeText(context, "No se pudo activar el modo kiosco.", Toast.LENGTH_LONG).show()
                    }
                    refreshKey++
                }) {
                    Text("Sí, anclar", color = Color(0xFFE67E22), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKioskConfirm = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    if (pendingNokiaTouchOffConfirm) {
        AlertDialog(
            onDismissRequest = { pendingNokiaTouchOffConfirm = false },
            containerColor = Color(0xFF0E141B),
            title = { Text("¿Apagar el táctil del launcher?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "La pantalla de inicio va a responder únicamente a las teclas físicas.\n\n" +
                        "⚠️ Si este celular NO tiene cruceta ni teclas de navegación, la pantalla de inicio va a quedar manejable solo desde el panel.\n\n" +
                        "Salida de emergencia en el equipo: mantener 3 segundos el dedo en la esquina superior derecha.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingNokiaTouchOffConfirm = false
                    policyManager.setNokiaTouchEnabled(false)
                    refreshKey++
                }) {
                    Text("Sí, apagar táctil", color = Color(0xFFE67E22), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingNokiaTouchOffConfirm = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    if (pendingIabEnforceConfirm) {
        AlertDialog(
            onDismissRequest = { pendingIabEnforceConfirm = false },
            containerColor = Color(0xFF0E141B),
            title = { Text("¿Bloquear navegadores embebidos?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "A partir de ahora el detector de navegadores embebidos (IAB) va a BLOQUEAR de verdad en este celular.\n\n" +
                        "Si venís probando apps, revisá primero la auditoría en el panel: un falso positivo puede dejar una app sin funcionar sin mensaje que lo explique.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingIabEnforceConfirm = false
                    policyManager.setEmbeddedBrowserSimulation(false)
                    refreshKey++
                }) {
                    Text("Sí, bloquear", color = Color(0xFFE67E22), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingIabEnforceConfirm = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

private data class PolicySwitchItem(
    val key: String,
    val label: String,
    val isChecked: Boolean,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val onCheckedChange: (Boolean) -> Boolean
)

private data class PolicySection(
    val title: String,
    val items: List<PolicySwitchItem>
)

private fun normalizePolicySearch(text: String): String {
    val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)
}

private fun isStealthModeEnabled(context: Context): Boolean {
    return try {
        val aliasComponent = ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
        context.packageManager.getComponentEnabledSetting(aliasComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) {
        false
    }
}

private fun setStealthModeEnabled(context: Context, enabled: Boolean): Boolean {
    return try {
        val aliasComponent = ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        context.packageManager.setComponentEnabledSetting(
            aliasComponent,
            state,
            PackageManager.DONT_KILL_APP
        )
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}


@Composable
fun PolicyGroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = LocaleManager.t(title),
                color = Color(0xFFF5C438),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun PolicySwitchRow(
    label: String,
    isChecked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Boolean   // devuelve true si el DPM aceptó el cambio
) {
    // Estado local para dar feedback visual instantáneo; se revierte si el DPM rechaza
    var checked by remember(isChecked) { mutableStateOf(isChecked) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = LocaleManager.t(label),
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
                fontSize = 14.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = if (enabled) 0.6f else 0.35f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { newValue ->
                checked = newValue          // movimiento optimista
                val accepted = onCheckedChange(newValue)
                if (!accepted) checked = !newValue  // revertir si falló
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0E141B),
                checkedTrackColor = Color(0xFFF5C438),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color(0xFF0E141B),
                disabledCheckedThumbColor = Color(0xFF0E141B).copy(alpha = 0.5f),
                disabledCheckedTrackColor = Color(0xFFF5C438).copy(alpha = 0.4f),
                disabledUncheckedThumbColor = Color.Gray.copy(alpha = 0.4f),
                disabledUncheckedTrackColor = Color(0xFF0E141B).copy(alpha = 0.5f)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerTabContent(context: Context) {
    val appController = remember { AppController(context) }
    val policyManager = remember { PolicyManager(context) }
    var appsList by remember { mutableStateOf<List<AppInfoData>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    // Arranca en true: así la primera pasada muestra el spinner y no un "no hay apps"
    // de un instante, antes de que LaunchedEffect llegue a pedir la lista.
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf("Todas") }
    var appToUninstall by remember { mutableStateOf<AppInfoData?>(null) }
    var blockPopularState by remember { mutableStateOf(policyManager.isBlockPopularNonKosherEnabled()) }
    val scope = rememberCoroutineScope()

    fun refreshApps() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            policyManager.healRestrictedEssentialApps()
            val list = appController.getUserApps()
            val popularEnabled = policyManager.isBlockPopularNonKosherEnabled()
            withContext(Dispatchers.Main) {
                appsList = list
                blockPopularState = popularEnabled
                isLoading = false
                // 26/10/2026: por la vía coalescida y fuera del hilo principal. Antes era
                // un syncDeviceInfo() completo (enumera TODAS las apps y arma ~80 campos)
                // en el hilo de la pantalla, después de cada interruptor: en un equipo de
                // 2 GB eso es la lista trabada justo cuando uno la está usando.
                com.ejemplo.locksuite.util.FirebaseDeviceSync.requestSync(context)
            }
        }
    }

    fun ensureVpnActiveAfterBlock() {
        try {
            val activity = context as? Activity
            when (policyManager.configureAndStartVpn(activity, 1024, lockAfterOnSuccess = true)) {
                com.ejemplo.locksuite.mdm.PolicyManager.VpnConfigureOutcome.STARTED_DIRECTLY -> {
                    Toast.makeText(
                        context,
                        "VPN activa y configuración bloqueada automáticamente (Lockdown).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                com.ejemplo.locksuite.mdm.PolicyManager.VpnConfigureOutcome.CONSENT_LAUNCHED -> Unit
                com.ejemplo.locksuite.mdm.PolicyManager.VpnConfigureOutcome.FAILED -> Unit
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(Unit) {
        refreshApps()
    }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshApps()
            }
        }
        val filter = android.content.IntentFilter("com.ejemplo.locksuite.ACTION_APP_UNINSTALLED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Filtrar por texto Y por categoría
    val filteredApps = remember(appsList, searchQuery, selectedFilter) {
        appsList.filter { app ->
            val matchesSearch = app.label.lowercase().contains(searchQuery.lowercase()) ||
                    app.packageName.lowercase().contains(searchQuery.lowercase())
            val matchesFilter = when (selectedFilter) {
                "Todas" -> true
                "Bloqueadas" -> app.isHidden || app.isSuspended || app.isWebViewBlocked ||
                    (app.isInternetBlocked && com.ejemplo.locksuite.mdm.PerAppInternetBlockPolicy.isSupported(app.packageName))
                "Usuario" -> app.appType == "Usuario"
                "Sistema" -> app.appType == "Sistema"
                "Preinstaladas" -> app.appType == "Preinstalada"
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    // Modal de confirmación de desinstalación
    if (appToUninstall != null) {
        AlertDialog(
            onDismissRequest = { appToUninstall = null },
            title = { Text("Desinstalar Aplicación") },
            text = { Text("¿Está seguro de que desea desinstalar ${appToUninstall?.label} (${appToUninstall?.packageName}) de forma remota y silenciosa?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        appToUninstall?.let { app ->
                            scope.launch(Dispatchers.IO) {
                                val success = appController.uninstallApp(app.packageName)
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        Toast.makeText(context, "Iniciando desinstalación...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Fallo al iniciar desinstalación", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        appToUninstall = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Desinstalar")
                }
            },
            dismissButton = {
                TextButton(onClick = { appToUninstall = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // ── LAYOUT DE LA PESTAÑA (26/10/2026, B.96) ─────────────────────────────────
    //
    // Reporte del dueño: "en la sección Aplicaciones se tapa bastante pantalla y casi no
    // se ve la lista de apps, y en pantallas pequeñas no se ve ninguna y no puedo hacer
    // scroll". La causa: la tarjeta de "apps populares", el buscador y los filtros
    // vivían en una Column FIJA, y la lista era una LazyColumn con weight(1f) DEBAJO; o
    // sea que la lista recibía solo lo que sobraba. En un CAT S22 Flip o un Qin F21 Pro
    // (≈ 320 × 427 dp, menos la barra de arriba y la fila de pestañas) no sobraba nada:
    // la cabecera ocupaba toda la altura, la lista quedaba en cero, y como la cabecera no
    // se desplazaba, no había forma de llegar a ninguna app.
    //
    // Ahora TODO es una sola LazyColumn: la cabecera son sus primeros ítems y se va con
    // el scroll, así que la lista usa la pantalla entera en cuanto uno baja. En pantallas
    // chicas además la cabecera y cada fila se compactan (`compacto`).
    //
    // ⚠️ No volver a poner la cabecera afuera de la LazyColumn "para que el buscador
    // quede fijo": es exactamente lo que dejó la lista sin altura.
    //
    // De paso: el recargo después de cada interruptor ya no reemplaza la lista por un
    // spinner a pantalla completa. Eso la sacaba de la composición y devolvía el scroll
    // al principio cada vez que se tocaba algo, justo en las apps de más abajo. El
    // spinner grande queda para la PRIMERA carga; las siguientes muestran una barrita y
    // la lista se queda donde estaba.
    // "Pantalla chica" se decide por el TAMAÑO DE LA PANTALLA y no por el espacio que
    // le toca a esta pestaña: si no, al abrir el teclado del buscador el alto bajaba, el
    // modo compacto se prendía solo y la lista cambiaba de forma mientras uno escribía.
    val configuracion = LocalConfiguration.current
    val compacto = configuracion.screenWidthDp < 360 || configuracion.screenHeightDp < 600
    run {
        val margen = if (compacto) 8.dp else 16.dp
        var isUpdatingPopular by remember { mutableStateOf(false) }
        val normalApps = remember(filteredApps) { filteredApps.filter { !it.isCritical } }
        val criticalApps = remember(filteredApps) { filteredApps.filter { it.isCritical } }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = margen, vertical = margen),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Interruptor maestro: Bloquear apps populares no kosher
            item(key = "cab_populares") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (compacto) 10.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Bloquear apps populares no kosher",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (compacto) 13.sp else 14.sp
                            )
                            Text(
                                text = if (compacto)
                                    "Oculta navegadores, tiendas, redes y bloatware. Después podés desbloquear una por una."
                                else
                                    "Oculta en el momento navegadores, tiendas no kosher, redes y bloatware. Podés desbloquear individualmente las que necesites.",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                        if (isUpdatingPopular) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFFF5C438),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = blockPopularState,
                                onCheckedChange = { checked ->
                                    isUpdatingPopular = true
                                    scope.launch(Dispatchers.IO) {
                                        val ok = policyManager.setBlockPopularNonKosher(checked)
                                        withContext(Dispatchers.Main) {
                                            isUpdatingPopular = false
                                            if (ok) {
                                                blockPopularState = checked
                                                refreshApps()
                                                val msg = if (checked) "Apps populares no kosher bloqueadas" else "Apps populares no kosher desbloqueadas"
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Error al actualizar política", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF0E141B),
                                    checkedTrackColor = Color(0xFFF5C438)
                                )
                            )
                        }
                    }
                }
            }

            // Buscador
            item(key = "cab_buscador") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar aplicación...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, "Buscar", tint = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF5C438),
                        unfocusedBorderColor = Color(0xFF1A222C),
                        focusedContainerColor = Color(0xFF1A222C).copy(alpha = 0.3f),
                        unfocusedContainerColor = Color(0xFF1A222C).copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Fila de chips de filtro (Scrolleable horizontalmente)
            item(key = "cab_filtros") {
                val blockedCount = remember(appsList) {
                    appsList.count {
                        it.isHidden || it.isSuspended || it.isWebViewBlocked ||
                            (it.isInternetBlocked && com.ejemplo.locksuite.mdm.PerAppInternetBlockPolicy.isSupported(it.packageName))
                    }
                }
                val filterOptions = remember(blockedCount) {
                    listOf(
                        "Todas" to "Todas",
                        "Bloqueadas" to "Bloqueadas ($blockedCount)",
                        "Usuario" to "Usuario",
                        "Sistema" to "Sistema",
                        "Preinstaladas" to "Preinstaladas"
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filterOptions.forEach { (filterKey, filterLabel) ->
                        val isSelected = selectedFilter == filterKey
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = filterKey },
                            label = { Text(filterLabel, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFF5C438),
                                selectedLabelColor = Color(0xFF0E141B),
                                containerColor = Color(0xFF1A222C).copy(alpha = 0.5f),
                                labelColor = Color.White
                            )
                        )
                    }
                }
            }

            if (isLoading && appsList.isEmpty()) {
                // Primera carga: todavía no hay nada que mostrar.
                item(key = "cargando") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFFF5C438))
                    }
                }
            } else {
                if (isLoading) {
                    // Recargando después de un cambio: la lista se queda donde está.
                    item(key = "recargando") {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF5C438)
                        )
                    }
                }

                if (filteredApps.isEmpty()) {
                    item(key = "vacia") {
                        Text(
                            text = if (searchQuery.isNotBlank() || selectedFilter != "Todas")
                                "No hay aplicaciones que coincidan con la búsqueda o el filtro."
                            else
                                "No se encontraron aplicaciones.",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }

                // Sección de Apps Administrables
                if (normalApps.isNotEmpty()) {
                    item(key = "sec_administrables") {
                        Text(
                            text = "Aplicaciones Administrables (${normalApps.size})",
                            color = Color(0xFFF5C438),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(normalApps, key = { it.packageName }) { app ->
                        AppRowItem(
                            app = app,
                            compacto = compacto,
                            onHideChange = { hide, onComplete ->
                                scope.launch(Dispatchers.IO) {
                                    val success = appController.hideApp(app.packageName, hide)
                                    withContext(Dispatchers.Main) {
                                        onComplete(success)
                                        if (success) {
                                            refreshApps()
                                        } else {
                                            Toast.makeText(context, "Fallo al ocultar aplicación", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onSuspendChange = { suspend, onComplete ->
                                scope.launch(Dispatchers.IO) {
                                    val success = appController.suspendApp(app.packageName, suspend)
                                    withContext(Dispatchers.Main) {
                                        onComplete(success)
                                        if (success) {
                                            refreshApps()
                                        } else {
                                            Toast.makeText(context, "Fallo al suspender aplicación", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onWebViewChange = { block, onComplete ->
                                scope.launch(Dispatchers.IO) {
                                    val pkg = app.packageName
                                    val prefs = PrefsHelper.getMdmPrefs(context)
                                    if (block) {
                                        prefs.edit().remove(PolicyManager.KEY_WEBVIEW_MANUAL_UNBLOCK_PREFIX + pkg).apply()
                                    } else if (pkg in com.ejemplo.locksuite.mdm.WebViewBlockManager.DEFAULT_PROFILE_BLOCKED_PACKAGES) {
                                        prefs.edit().putBoolean(PolicyManager.KEY_WEBVIEW_MANUAL_UNBLOCK_PREFIX + pkg, true).apply()
                                    }
                                    val success = com.ejemplo.locksuite.mdm.WebViewBlockManager.setBlocked(context, pkg, block)
                                    if (success && block) {
                                        com.ejemplo.locksuite.mdm.PlayStoreManager.releaseWebViewOwnership(context, setOf(pkg))
                                        com.ejemplo.locksuite.mdm.ClassicStorePolicyManager.releaseWebViewOwnership(context, setOf(pkg))
                                    }
                                    withContext(Dispatchers.Main) {
                                        onComplete(success)
                                        if (success && block) {
                                            ensureVpnActiveAfterBlock()
                                        }
                                        refreshApps()
                                    }
                                }
                            },
                            onInternetChange = { block ->
                                scope.launch(Dispatchers.IO) {
                                    val success = policyManager.setPerAppInternetBlocked(app.packageName, block)
                                    withContext(Dispatchers.Main) {
                                        if (success && block) {
                                            ensureVpnActiveAfterBlock()
                                        }
                                        refreshApps()
                                    }
                                }
                            },
                            onUninstallClick = {
                                appToUninstall = app
                            }
                        )
                    }
                }

                // Sección de Apps Críticas
                if (criticalApps.isNotEmpty()) {
                    item(key = "sec_criticas") {
                        Text(
                            text = "Herramientas Críticas del Sistema (No Bloqueables)",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    items(criticalApps, key = { it.packageName }) { app ->
                        AppRowItem(
                            app = app,
                            compacto = compacto,
                            onHideChange = { _, onComplete -> onComplete(false) },
                            onSuspendChange = { _, onComplete -> onComplete(false) },
                            onWebViewChange = { _, onComplete -> onComplete(false) },
                            onInternetChange = { _ -> },
                            onUninstallClick = {}
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una app de la pestaña Aplicaciones.
 *
 * `compacto` (26/10/2026, B.96): en pantallas chicas (CAT S22 Flip, Qin F21 Pro) la fila
 * de tres interruptores con su texto no entraba en ~300 dp de ancho —el último quedaba
 * aplastado o cortado— y el ícono de 48 dp se comía media fila. En modo compacto el ícono
 * baja a 36 dp, el nombre y el paquete van en una línea con "…", y los tres interruptores
 * pasan a ser chips que se pueden deslizar de costado: nunca se cortan, y el estado se lee
 * igual (amarillo con ✓ = activo). En pantallas normales se ve exactamente como antes.
 */
@Composable
fun AppRowItem(
    app: AppInfoData,
    compacto: Boolean = false,
    onHideChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onSuspendChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onWebViewChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onInternetChange: (Boolean) -> Unit,
    onUninstallClick: () -> Unit
) {
    var hideState by remember { mutableStateOf(app.isHidden) }
    var suspendState by remember { mutableStateOf(app.isSuspended) }
    var webviewState by remember { mutableStateOf(app.isWebViewBlocked) }
    var perAppNetState by remember { mutableStateOf(app.isInternetBlocked) }
    var expanded by remember { mutableStateOf(false) }
    val supportsInternetBlock = com.ejemplo.locksuite.mdm.PerAppInternetBlockPolicy.isSupported(app.packageName)
    val context = LocalContext.current

    LaunchedEffect(app) {
        hideState = app.isHidden
        suspendState = app.isSuspended
        webviewState = app.isWebViewBlocked
        perAppNetState = app.isInternetBlocked
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        val iconoDp = if (compacto) 36.dp else 48.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compacto) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono de la App (Ya es un Bitmap precargado en background)
            if (app.icon != null) {
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(iconoDp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(iconoDp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = app.label,
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(if (compacto) 8.dp else 12.dp))

            // Información
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compacto) 14.sp else 15.sp,
                    maxLines = if (compacto) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    color = Color.LightGray,
                    fontSize = if (compacto) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Botón de desinstalar y Configuración Avanzada (solo bloqueo por dominios para apps soportadas)
            if (!app.isCritical) {
                if (supportsInternetBlock) {
                    IconButton(onClick = {
                        // Se quitó un Toast de depuración ("Engranaje presionado: $expanded")
                        // que quedó de pruebas: le mostraba al admin un mensaje interno cada
                        // vez que tocaba el engranaje de cada app en la lista.
                        expanded = !expanded
                    }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configuración Avanzada",
                            tint = if (perAppNetState) Color(0xFFF5C438) else Color.White
                        )
                    }
                }

                IconButton(onClick = onUninstallClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Desinstalar",
                        tint = Color.Red.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        if (!app.isCritical) {
            HorizontalDivider(color = Color(0xFF0E141B), thickness = 1.dp)

            if (compacto) {
                // Pantalla chica: chips deslizables de costado (ver el KDoc de arriba).
                // Mismo contrato que los Switch de abajo: si el cambio falla, vuelve al
                // valor anterior.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InterruptorCompacto("Ocultar", hideState) { nuevo ->
                        onHideChange(nuevo) { ok -> hideState = if (ok) nuevo else !nuevo }
                    }
                    InterruptorCompacto("Suspender", suspendState) { nuevo ->
                        onSuspendChange(nuevo) { ok -> suspendState = if (ok) nuevo else !nuevo }
                    }
                    InterruptorCompacto("WebView", webviewState) { nuevo ->
                        onWebViewChange(nuevo) { ok -> webviewState = if (ok) nuevo else !nuevo }
                    }
                }
            } else Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Ocultar
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ocultar", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Switch(
                        checked = hideState,
                        onCheckedChange = { newValue ->
                            onHideChange(newValue) { success ->
                                if (success) {
                                    hideState = newValue
                                } else {
                                    hideState = !newValue
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0E141B),
                            checkedTrackColor = Color(0xFFF5C438)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }

                // Suspender
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Suspender", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Switch(
                        checked = suspendState,
                        onCheckedChange = { newValue ->
                            onSuspendChange(newValue) { success ->
                                if (success) {
                                    suspendState = newValue
                                } else {
                                    suspendState = !newValue
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0E141B),
                            checkedTrackColor = Color(0xFFF5C438)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }

                // Bloquear WebView
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("WebView", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Switch(
                        checked = webviewState,
                        onCheckedChange = { newValue ->
                            onWebViewChange(newValue) { success ->
                                if (success) {
                                    webviewState = newValue
                                } else {
                                    webviewState = !newValue
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0E141B),
                            checkedTrackColor = Color(0xFFF5C438)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }
            }

            if (expanded && supportsInternetBlock) {
                HorizontalDivider(color = Color(0xFF0E141B), thickness = 1.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloqueo de Internet (por dominios)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Bloquea los dominios de esta app en el filtro DNS (y datos móviles en Android 9+).", color = Color.LightGray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = perAppNetState,
                            onCheckedChange = { enabled ->
                                perAppNetState = enabled
                                onInternetChange(enabled)
                                Toast.makeText(context, if (enabled) "Internet bloqueado en ${app.label}." else "Internet permitido en ${app.label}.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0E141B),
                                checkedTrackColor = Color(0xFFF5C438)
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Un interruptor de una app en modo compacto (26/10/2026, B.96): un chip que ocupa lo
 * que su texto, en vez de texto + Switch. Amarillo con ✓ = activo.
 */
@Composable
private fun InterruptorCompacto(etiqueta: String, activo: Boolean, alCambiar: (Boolean) -> Unit) {
    FilterChip(
        selected = activo,
        onClick = { alCambiar(!activo) },
        label = { Text((if (activo) "✓ " else "") + etiqueta, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFF5C438),
            selectedLabelColor = Color(0xFF0E141B),
            containerColor = Color(0xFF0E141B),
            labelColor = Color.White
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TempInstallWindowCard(refreshKey: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var status by remember(refreshKey) {
        mutableStateOf(com.ejemplo.locksuite.mdm.TempInstallWindowManager.statusForSync(context))
    }
    var remainingMs by remember(status.active, status.deadlineWall, refreshKey) {
        mutableLongStateOf(status.remainingMs)
    }
    var amountText by remember { mutableStateOf("30") }
    var unitMultiplierMs by remember { mutableLongStateOf(60_000L) } // 60_000 = Min, 3_600_000 = H, 86_400_000 = D

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(status.active, status.deadlineWall, refreshKey, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (status.active) {
                val rem = com.ejemplo.locksuite.mdm.TempInstallWindowManager.remainingMs(context)
                remainingMs = rem
                if (rem <= 0L) {
                    com.ejemplo.locksuite.mdm.TempInstallWindowManager.checkAndExpire(context)
                    status = com.ejemplo.locksuite.mdm.TempInstallWindowManager.statusForSync(context)
                    onChanged()
                    break
                }
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (status.active) Color(0xFF1D2C24) else Color(0xFF1A222C)
        ),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (status.active) "⏱️ Instalación temporal ACTIVA" else "⏱️ Permitir instalar apps (temporal)",
                    color = if (status.active) Color(0xFF58D68D) else Color(0xFFF5C438),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "Desbloquea Play Store y desactiva las restricciones de instalación durante el tiempo elegido. Al finalizar, todo vuelve automáticamente a su configuración anterior.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp
            )

            if (status.active) {
                val formattedDeadline = remember(status.deadlineWall) {
                    java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                        .format(java.util.Date(status.deadlineWall))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF27AE60).copy(alpha = 0.18f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Restante: ${com.ejemplo.locksuite.mdm.TempInstallWindowClock.formatRemaining(remainingMs)}",
                        color = Color(0xFF82E0AA),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Se vuelve a bloquear: $formattedDeadline",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = {
                        com.ejemplo.locksuite.mdm.TempInstallWindowManager.stop(context, "cancelado desde el celular")
                        Toast.makeText(context, "Instalación temporal cerrada. Restricciones restauradas.", Toast.LENGTH_SHORT).show()
                        onChanged()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔒 Bloquear ahora", fontWeight = FontWeight.Bold)
                }
            } else if (status.lastCloseReason.isNotBlank()) {
                Text(
                    text = "Último cierre: ${status.lastCloseReason}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }

            // Presets rápidos
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val presets = listOf(
                    Triple("15 min", "15", 60_000L),
                    Triple("30 min", "30", 60_000L),
                    Triple("1 h", "1", 3_600_000L),
                    Triple("2 h", "2", 3_600_000L),
                    Triple("24 h", "24", 3_600_000L)
                )
                presets.forEach { (label, amt, mult) ->
                    val selected = amountText == amt && unitMultiplierMs == mult
                    FilterChip(
                        selected = selected,
                        onClick = {
                            amountText = amt
                            unitMultiplierMs = mult
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF0E141B),
                            labelColor = Color.White.copy(alpha = 0.85f),
                            selectedContainerColor = Color(0xFFF5C438),
                            selectedLabelColor = Color(0xFF0E141B)
                        )
                    )
                }
            }

            // Cantidad + Unidad
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }
                        if (digits.length <= 5) amountText = digits
                    },
                    label = { Text("Tiempo", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF5C438),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
                listOf(
                    "Min" to 60_000L,
                    "Hs" to 3_600_000L,
                    "Días" to 86_400_000L
                ).forEach { (uLabel, mult) ->
                    FilterChip(
                        selected = unitMultiplierMs == mult,
                        onClick = { unitMultiplierMs = mult },
                        label = { Text(uLabel, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF0E141B),
                            labelColor = Color.White.copy(alpha = 0.85f),
                            selectedContainerColor = Color(0xFFF5C438),
                            selectedLabelColor = Color(0xFF0E141B)
                        )
                    )
                }
            }

            Button(
                onClick = {
                    val amount = amountText.toLongOrNull() ?: 0L
                    val durationMs = amount * unitMultiplierMs
                    if (!com.ejemplo.locksuite.mdm.TempInstallWindowClock.validateDuration(durationMs)) {
                        Toast.makeText(
                            context,
                            "Elegí una duración entre 1 minuto y 30 días.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val ok = com.ejemplo.locksuite.mdm.TempInstallWindowManager.start(context, durationMs, "celular")
                        if (ok) {
                            Toast.makeText(
                                context,
                                "🔓 Play Store e instalación habilitados por ${com.ejemplo.locksuite.mdm.TempInstallWindowClock.formatRemaining(durationMs)}.",
                                Toast.LENGTH_LONG
                            ).show()
                            onChanged()
                        } else {
                            Toast.makeText(
                                context,
                                "No se pudo habilitar (¿LockSuite está suspendido?).",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5C438), contentColor = Color(0xFF0E141B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (status.active) "🔄 Reiniciar con este tiempo" else "🔓 Permitir instalar apps",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * ⚡ Configuración rápida — los tres perfiles de alta (9/9/2026).
 *
 * Ver `mdm/EnrollmentProfiles.kt` para el porqué completo. En una línea: dar de alta un
 * equipo eran más de sesenta interruptores y acordarse de todos; acá son tres botones.
 *
 * Dos decisiones de esta pantalla que no hay que "simplificar":
 *
 * 1. **La confirmación muestra el aviso del perfil, no un "¿estás seguro?".** Dos de los
 *    tres niveles hacen algo que puede arruinar un alta si se aplica en el momento
 *    equivocado (el Nivel 1 bloquea agregar cuentas de Google; el Nivel 3 APAGA los
 *    filtros). El diálogo es el único texto que la persona lee seguro.
 * 2. **El perfil aplicado queda marcado en la tarjeta.** Sin eso, "¿este equipo con qué
 *    nivel quedó?" se contesta comparando sesenta interruptores a mano.
 */
@Composable
fun MasterProfilesCard(policyManager: PolicyManager, onApplied: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isApplying by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<EnrollmentProfiles.MasterProfile?>(null) }
    var appliedId by remember { mutableStateOf(policyManager.getAppliedMasterProfileId()) }
    // Solo lo usa el Nivel 4. Índice dentro de GracePeriodManager.DURACIONES; arranca en
    // "2 días", que es el ejemplo que dio el dueño al pedirlo.
    var graceIdx by remember {
        mutableIntStateOf(
            com.ejemplo.locksuite.mdm.GracePeriodManager.DURACIONES
                .indexOfFirst { it.first == "2 días" }.coerceAtLeast(0)
        )
    }
    var graceActive by remember {
        mutableStateOf(com.ejemplo.locksuite.mdm.GracePeriodManager.isActive(context))
    }
    val graceRestante = remember(graceActive, appliedId) {
        com.ejemplo.locksuite.mdm.GracePeriodManager.remainingMs(context)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "⚡ Configuración rápida",
                color = Color(0xFFF5C438),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "${EnrollmentProfiles.ALL.size} configuraciones listas. Un toque deja el celular configurado entero, " +
                    "en vez de ir interruptor por interruptor. Después se puede ajustar lo que haga falta.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )

            // Si hay un período de gracia corriendo, se dice arriba de todo y con el
            // tiempo que falta. Es el dato que decide si hay algo que hacer o hay que
            // esperar, y sin él el equipo "se cierra solo" sin que nadie sepa cuándo.
            if (graceActive) {
                val restante = com.ejemplo.locksuite.mdm.GracePeriodManager.DURACIONES.let {
                    val min = (graceRestante / 60000L).toInt()
                    when {
                        min >= 1440 -> "${min / 1440} d ${(min % 1440) / 60} h"
                        min >= 60 -> "${min / 60} h ${min % 60} min"
                        else -> "$min min"
                    }
                }
                Text(
                    "⏳ Período de gracia activo: faltan $restante y el equipo se cierra solo.",
                    color = Color(0xFF8FC4E8),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3498DB).copy(alpha = 0.12f))
                        .padding(10.dp)
                )
            }

            EnrollmentProfiles.ALL.forEach { profile ->
                val isApplied = profile.id == appliedId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isApplied) Color(0xFF27AE60).copy(alpha = 0.16f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.label,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (isApplied) {
                            Text("✓ aplicado", color = Color(0xFF2ECC71), fontSize = 11.sp)
                        }
                    }
                    Text(
                        profile.summary,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                    // El Nivel 4 es el único que pide un dato más: cuánto dura. Se elige
                    // de una lista cerrada y no con un campo libre, porque un número mal
                    // puesto no da error en ningún lado — el equipo se cerraría al
                    // instante o no se cerraría nunca.
                    if (profile.id == EnrollmentProfiles.LEVEL_GRACE) {
                        val duraciones = com.ejemplo.locksuite.mdm.GracePeriodManager.DURACIONES
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Se cierra en:", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            TextButton(onClick = {
                                graceIdx = (graceIdx + 1) % duraciones.size
                            }) {
                                Text(
                                    "${duraciones[graceIdx].first}  ▸",
                                    color = Color(0xFFF5C438),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { pendingProfile = profile },
                        enabled = !isApplying,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isApplying) "Aplicando..." else "Aplicar",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    pendingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { if (!isApplying) pendingProfile = null },
            containerColor = Color(0xFF0B2447),
            title = {
                Text(profile.label, color = Color(0xFFF5C438), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(profile.summary, color = Color.White, fontSize = 13.sp)
                    if (profile.id == EnrollmentProfiles.LEVEL_GRACE) {
                        val d = com.ejemplo.locksuite.mdm.GracePeriodManager.DURACIONES[graceIdx]
                        Text(
                            "Dura ${d.first}, y al vencer se aplica solo el Nivel 1 — Kosher estricto " +
                                "(la Tienda Play pasa de toda la tienda al modo Kosher).",
                            color = Color(0xFF8FC4E8),
                            fontSize = 12.sp
                        )
                    }
                    profile.warning?.let { warning ->
                        Text(
                            "⚠️ $warning",
                            color = Color(0xFFF5C438),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isApplying,
                    onClick = {
                        val duracionMs = if (profile.id == EnrollmentProfiles.LEVEL_GRACE) {
                            com.ejemplo.locksuite.mdm.GracePeriodManager.DURACIONES[graceIdx].second
                        } else 0L
                        isApplying = true
                        pendingProfile = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                policyManager.applyMasterProfile(profile.id, duracionMs)
                            }
                            isApplying = false
                            if (ok) {
                                appliedId = policyManager.getAppliedMasterProfileId()
                                graceActive = com.ejemplo.locksuite.mdm.GracePeriodManager.isActive(context)
                                onApplied()
                                Toast.makeText(context, "✅ ${profile.label} aplicado.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "❌ No se pudo aplicar el perfil.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Aplicar", color = Color(0xFF2ECC71), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isApplying,
                    onClick = { pendingProfile = null }
                ) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsTabContent(context: Context) {
    val policyManager = remember { PolicyManager(context) }
    val scope = rememberCoroutineScope()
    var presetNameInput by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    val presetsMap = remember(refreshKey) { policyManager.getLocalPresets() }

    var pendingExportJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportName by rememberSaveable { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            val jsonToWrite = pendingExportJson
            val exportName = pendingExportName
            if (!jsonToWrite.isNullOrEmpty()) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                                outputStream.bufferedWriter().use { writer ->
                                    writer.write(jsonToWrite)
                                }
                            }
                        }
                        Toast.makeText(context, "✅ Perfil '$exportName' exportado con éxito.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "❌ Error al exportar perfil: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val jsonString = withContext(Dispatchers.IO) {
                        val inputStream = context.contentResolver.openInputStream(it)
                        inputStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                    }
                    if (jsonString.isNotEmpty()) {
                        val success = withContext(Dispatchers.IO) {
                            policyManager.importPolicyPresetJson(jsonString)
                        }
                        if (success) {
                            val importedName = try {
                                val obj = org.json.JSONObject(jsonString)
                                obj.optString("presetName", "Perfil Importado")
                            } catch (e: Exception) {
                                "Perfil Importado"
                            }
                            withContext(Dispatchers.IO) {
                                policyManager.saveLocalPreset(importedName, jsonString)
                            }
                            Toast.makeText(context, "✅ Backup (.locksuite) importado, guardado y aplicado con éxito.", Toast.LENGTH_LONG).show()
                            refreshKey++
                        } else {
                            Toast.makeText(context, "❌ Error al aplicar el backup.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: SecurityException) {
                    Toast.makeText(context, "🚨 ALERTA: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Error al leer archivo: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Va ARRIBA de "guardar preset" a propósito: el caso frecuente en esta pantalla
        // es dar de alta un equipo nuevo, no cargar un respaldo.
        item {
            MasterProfilesCard(
                policyManager = policyManager,
                onApplied = { refreshKey++ }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Guardar Configuración Actual (Preset)", color = Color(0xFFF5C438), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Guarda todas las políticas y restricciones activas con un nombre personalizado.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        placeholder = { Text("Ej: Bloqueo A Fuerte", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF5C438),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (presetNameInput.isBlank()) {
                                    Toast.makeText(context, "Ingresa un nombre para el perfil", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val nameToSave = presetNameInput.trim()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val jsonStr = policyManager.exportPolicyPresetJson(nameToSave)
                                        policyManager.saveLocalPreset(nameToSave, jsonStr)
                                    }
                                    presetNameInput = ""
                                    refreshKey++
                                    Toast.makeText(context, "Perfil guardado con éxito.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5C438), contentColor = Color(0xFF0E141B))
                        ) {
                            Text("Guardar Local", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val name = if (presetNameInput.isNotBlank()) presetNameInput.trim() else "Perfil_LockSuite"
                                scope.launch {
                                    val jsonStr = withContext(Dispatchers.IO) {
                                        policyManager.exportPolicyPresetJson(name)
                                    }
                                    pendingExportJson = jsonStr
                                    pendingExportName = name
                                    try {
                                        DashboardActivity.isLaunchingInternalFlow = true
                                        exportLauncher.launch("${name.replace("\\s+".toRegex(), "_")}.locksuite")
                                    } catch (e: Exception) {
                                        DashboardActivity.isLaunchingInternalFlow = false
                                        Toast.makeText(context, "Error al abrir selector: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2980B9), contentColor = Color.White)
                        ) {
                            Text("Exportar a Archivo", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Importar Copia de Seguridad (.locksuite)", color = Color(0xFFF5C438), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Selecciona un archivo de respaldo .locksuite. El archivo se verificará criptográficamente mediante HMAC SHA-256 para evitar alteraciones.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            try {
                                DashboardActivity.isLaunchingInternalFlow = true
                                importLauncher.launch("*/*")
                            } catch (e: Exception) {
                                DashboardActivity.isLaunchingInternalFlow = false
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5C438), contentColor = Color(0xFF0E141B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cargar Archivo .locksuite", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text("Perfiles Guardados Localmente", color = Color(0xFFF5C438), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        if (presetsMap.isEmpty()) {
            item {
                Text("No hay perfiles guardados aún.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            items(presetsMap.keys.toList()) { name ->
                val jsonStr = presetsMap[name] ?: ""
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222C)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val success = withContext(Dispatchers.IO) {
                                                policyManager.importPolicyPresetJson(jsonStr)
                                            }
                                            if (success) {
                                                Toast.makeText(context, "Perfil '$name' aplicado con éxito.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "❌ Error al aplicar el perfil '$name'.", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: SecurityException) {
                                            Toast.makeText(context, "🚨 ALERTA: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Aplicar", fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    pendingExportJson = jsonStr
                                    pendingExportName = name
                                    try {
                                        DashboardActivity.isLaunchingInternalFlow = true
                                        exportLauncher.launch("${name.replace("\\s+".toRegex(), "_")}.locksuite")
                                    } catch (e: Exception) {
                                        DashboardActivity.isLaunchingInternalFlow = false
                                        Toast.makeText(context, "Error al exportar: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2980B9)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Exportar", fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    policyManager.deleteLocalPreset(name)
                                    refreshKey++
                                    Toast.makeText(context, "Perfil eliminado.", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Eliminar", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusLabelRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
fun ServicesTabContent(
    context: Context,
    vpnRefreshTick: Int = 0,
    onBumpVpnRefresh: () -> Unit = {},
    onTriggerPermissionsReauth: () -> Unit,
    onTriggerUninstallReauth: () -> Unit
) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
    val policyManager = remember { com.ejemplo.locksuite.mdm.PolicyManager(context) }

    val aliasComponent = ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
    val isStealthActive = context.packageManager.getComponentEnabledSetting(aliasComponent) ==
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    // Antes el estado del Watchdog se mostraba como "ACTIVO" en un string fijo,
    // sin verificar nada real: si el servicio moría (p.ej. un OEM agresivo con la
    // batería lo mata) el panel seguía mostrando "ACTIVO" igual, dando una falsa
    // sensación de seguridad justo sobre el mecanismo que reaplica las
    // restricciones. Ahora se consulta el estado real del servicio (sin remember,
    // igual que las comprobaciones de Accesibilidad y VPN de más abajo: se
    // reevalúa en cada recomposición para reflejar el estado actual).
    val isWatchdogRunning = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == com.ejemplo.locksuite.service.WatchdogForegroundService::class.java.name
        }
    } catch (e: Exception) {
        false
    }

    val navyMedium = Color(0xFF1A222C)
    val accentOrange = Color(0xFFF5C438)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tarjeta de Estado
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Estado de LockSuite MDM",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    StatusLabelRow(label = "Licencia de Propietario (Device Owner)", value = if (isDeviceOwner) "ACTIVO (Seguridad de Sistema)" else "INACTIVO")
                    StatusLabelRow(
                        label = "Servicio Watchdog (Persistencia)",
                        value = if (isWatchdogRunning) "ACTIVO (Servicio de Primer Plano)" else "⚠️ INACTIVO — reabra la app para reiniciarlo"
                    )
                    StatusLabelRow(label = "Canal FCM de Control Remoto", value = "LISTO (Firebase Cloud Messaging)")
                    StatusLabelRow(label = "Modo Stealth (Launcher Oculto)", value = if (isStealthActive) "ACTIVADO" else "DESACTIVADO")
                }
            }
        }

        // Configuración de Seguridad
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    var showChangePinDialog by remember { mutableStateOf(false) }

                    if (showChangePinDialog) {
                        var newPin by remember { mutableStateOf("") }
                        var confirmPin by remember { mutableStateOf("") }
                        var errorMsg by remember { mutableStateOf("") }

                        AlertDialog(
                            onDismissRequest = { showChangePinDialog = false },
                            containerColor = Color(0xFF0E141B),
                            title = {
                                Text(
                                    "Cambiar PIN de Administrador",
                                    color = accentOrange,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "El nuevo PIN debe tener entre 4 y 16 dígitos numéricos. Esto actualizará el control web de forma automática.",
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                    OutlinedTextField(
                                        value = newPin,
                                        onValueChange = { if (it.all { c -> c.isDigit() }) newPin = it },
                                        label = { Text("Nuevo PIN", color = Color.White.copy(alpha = 0.8f)) },
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                        ),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = accentOrange,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = confirmPin,
                                        onValueChange = { if (it.all { c -> c.isDigit() }) confirmPin = it },
                                        label = { Text("Confirmar PIN", color = Color.White.copy(alpha = 0.8f)) },
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                        ),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = accentOrange,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (errorMsg.isNotEmpty()) {
                                        Text(errorMsg, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (newPin.length < 4 || newPin.length > 16) {
                                            errorMsg = "El PIN debe tener entre 4 y 16 dígitos."
                                        } else if (com.ejemplo.locksuite.security.PinManager.isTrivialPin(newPin)) {
                                            // Antes este diálogo no validaba PINs triviales: un
                                            // admin podía crear un PIN fuerte en el setup inicial
                                            // y luego cambiarlo acá a algo como "1234" sin ningún
                                            // aviso, debilitando la protección real del equipo.
                                            errorMsg = "PIN muy débil (no use secuencias o dígitos idénticos)."
                                        } else if (newPin != confirmPin) {
                                            errorMsg = "Los PINs no coinciden."
                                        } else {
                                            try {
                                                com.ejemplo.locksuite.security.PinManager.saveAdminPin(context, newPin)
                                                Toast.makeText(context, "PIN de Administrador cambiado con éxito", Toast.LENGTH_SHORT).show()
                                                showChangePinDialog = false
                                            } catch (e: Exception) {
                                                errorMsg = "Error al guardar el PIN: ${e.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0E141B))
                                ) {
                                    Text("Guardar", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showChangePinDialog = false }) {
                                    Text("Cancelar", color = Color.LightGray)
                                }
                            }
                        )
                    }

                    Text(
                        "Opciones Avanzadas",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Botón para Cambiar PIN
                    Button(
                        onClick = { showChangePinDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0E141B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Cambiar PIN de Administrador", fontWeight = FontWeight.Bold)
                    }

                    // Configuración de Accesibilidad (Enlace directo al sistema)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Comprobar dinámicamente si el servicio de accesibilidad está activo o en estado zombie
                        val accRunning = com.ejemplo.locksuite.util.AccessibilityEnforcer.isServiceRunning(context)
                        val accZombie = !accRunning && com.ejemplo.locksuite.util.AccessibilityEnforcer.isZombie(context)
                        val badgeColor = when {
                            accRunning -> Color(0xFF27AE60)
                            accZombie -> Color(0xFFD35400)
                            else -> Color(0xFFE74C3C)
                        }
                        val badgeLabel = when {
                            accRunning -> "ACTIVO"
                            accZombie -> "ZOMBIE"
                            else -> "INACTIVO"
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Servicio de Accesibilidad", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(badgeColor)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = badgeLabel,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "Requerido para bloquear WebViews y evitar evasiones. Toque el botón para abrir los Ajustes del sistema.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        
                        val navyDark = Color(0xFF0E141B)
                        Button(
                            onClick = {
                                try {
                                    DashboardActivity.isLaunchingInternalFlow = true
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    DashboardActivity.isLaunchingInternalFlow = false
                                    Toast.makeText(context, "No se pudo abrir los Ajustes de Accesibilidad. Vaya manualmente.", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Configurar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Configuración de VPN DNS (Capa 3 de red)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val vpnActive = remember(vpnRefreshTick) {
                            var isVpnOn = com.ejemplo.locksuite.service.KosherVpnService.isTunnelRunning
                            if (!isVpnOn) {
                                try {
                                    val nis = java.net.NetworkInterface.getNetworkInterfaces()
                                    if (nis != null) {
                                        for (ni in nis) {
                                            if (ni.isUp && (ni.name.contains("tun") || ni.name.contains("ppp") || ni.name.contains("p2p"))) {
                                                isVpnOn = true
                                                break
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            isVpnOn
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Filtrado de Red (VPN DNS)", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (vpnActive) Color(0xFF27AE60) else Color(0xFFE74C3C))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (vpnActive) "ACTIVO" else "INACTIVO",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "Filtro DNS para bloquear dominios no kosher de Waze y DiDi. Pulse para configurar o activar la VPN.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        val navyDark = Color(0xFF0E141B)
                        Button(
                            onClick = {
                                val activity = context as? Activity
                                when (policyManager.configureAndStartVpn(activity, 1024, lockAfterOnSuccess = false)) {
                                    com.ejemplo.locksuite.mdm.PolicyManager.VpnConfigureOutcome.STARTED_DIRECTLY -> {
                                        onBumpVpnRefresh()
                                        Toast.makeText(context, "Filtro VPN DNS activado con éxito.", Toast.LENGTH_SHORT).show()
                                    }
                                    com.ejemplo.locksuite.mdm.PolicyManager.VpnConfigureOutcome.CONSENT_LAUNCHED -> Unit
                                    com.ejemplo.locksuite.mdm.PolicyManager.VpnConfigureOutcome.FAILED -> {
                                        onBumpVpnRefresh()
                                        Toast.makeText(context, "Error al preparar o iniciar la VPN.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Configurar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Configuración de Factory Reset Protection (FRP)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Protección de Restablecimiento (FRP)",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Si el dispositivo es restablecido de fábrica por el menú de recuperación (Recovery), exigirá iniciar sesión con una cuenta de Google del administrador para poder activarse.",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    val policyManager = remember { PolicyManager(context) }
                    var frpEnabled by remember { mutableStateOf(policyManager.isFrpEnabled()) }
                    var useDefaultFrp by remember { mutableStateOf(policyManager.useDefaultFrp()) }
                    var frpAccountsText by remember { 
                        mutableStateOf(policyManager.getFrpAccounts().joinToString(", ")) 
                    }

                    // Fila 1: Activar FRP
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Activar Bloqueo de FRP", color = Color.White, fontSize = 14.sp)
                        Switch(
                            checked = frpEnabled,
                            onCheckedChange = { enabled ->
                                val accountsList = if (useDefaultFrp) emptyList() else frpAccountsText.split(",")
                                    .map { it.trim() }
                                    .filter { it.length == 21 && it.all { c -> c.isDigit() } }
                                
                                if (enabled && !useDefaultFrp && accountsList.isEmpty()) {
                                    Toast.makeText(context, "Ingrese al menos un ID de Google de 21 dígitos válido", Toast.LENGTH_LONG).show()
                                } else {
                                    val success = policyManager.setFrpPolicy(accountsList, useDefaultFrp, enabled)
                                    if (success) {
                                        frpEnabled = enabled
                                        Toast.makeText(context, if (enabled) "FRP Activado" else "FRP Desactivado", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Error al configurar FRP.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0E141B),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    // Fila 2: Usar ID por defecto u otro
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Usar ID del Administrador por Defecto", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Mantiene el ID predeterminado protegido y oculto sin mostrarlo en pantalla.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = useDefaultFrp,
                            onCheckedChange = { useDef ->
                                useDefaultFrp = useDef
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0E141B),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    if (useDefaultFrp) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Cuenta activa: [ID Predeterminado Protegido y Oculto] 🔒",
                                color = accentOrange,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = frpAccountsText,
                            onValueChange = { frpAccountsText = it },
                            label = { Text("IDs de Google Personalizados (21 dígitos, separados por comas)", color = Color.White.copy(alpha = 0.8f)) },
                            placeholder = { Text("Ej: ID_1, ID_2", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentOrange,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                focusedLabelColor = accentOrange,
                                unfocusedLabelColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            val accountsList = if (useDefaultFrp) emptyList() else frpAccountsText.split(",")
                                .map { it.trim() }
                                .filter { it.length == 21 && it.all { c -> c.isDigit() } }
                            
                            if (!useDefaultFrp && accountsList.isEmpty()) {
                                Toast.makeText(context, "Ingrese al menos un ID de Google de 21 dígitos válido", Toast.LENGTH_LONG).show()
                            } else {
                                val success = policyManager.setFrpPolicy(accountsList, useDefaultFrp, frpEnabled)
                                if (success) {
                                    Toast.makeText(context, "Configuración de FRP guardada correctamente", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al guardar la configuración de FRP.", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0E141B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Guardar Configuración de FRP", fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "Nota: Google exige el ID numérico de la cuenta (21 dígitos) y no el email. Para obtenerlo, inicie sesión y vaya a get.google.com/albumarchive; el ID estará en la URL.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Desinstalación y Desvinculación
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Desinstalación / Control de Permisos",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Text(
                        "Advertencia: Estas acciones son irreversibles. Al quitar los privilegios, el dispositivo dejará de estar protegido por LockSuite.",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Botón A: Solo quitar permisos MDM
                        Button(
                            onClick = {
                                onTriggerPermissionsReauth()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE67E22)),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Quitar Permisos", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }

                        // Botón B: Desinstalar completamente
                        Button(
                            onClick = {
                                onTriggerUninstallReauth()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Desinstalar App", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsActivityTabContent(context: Context) {
    val engine = remember { LockSuiteApplication.domainRuleEngine }
    val buffer = remember { LockSuiteApplication.dnsActivityBuffer }
    val ruleManager = remember { LockSuiteApplication.domainRuleManager }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    var windowMinutes by remember { mutableIntStateOf(5) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var newDomainText by remember { mutableStateOf("") }

    LaunchedEffect(windowMinutes, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                refreshKey++
            }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        var lastBumpMs = 0L
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            buffer.events.collect {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBumpMs >= 750L) {
                    lastBumpMs = now
                    refreshKey++
                }
            }
        }
    }

    // Reglas guardadas (persistentes): NO dependen de la ventana de 1h. Aca
    // aparecen TODAS aunque el dominio no se haya vuelto a consultar, y se
    // pueden quitar o forzar en cualquier momento. Arregla "no aparecen los
    // bloqueados / no los puedo desbloquear despues de una hora".
    val customRules = remember(refreshKey) {
        ruleManager.getAllRules().entries.sortedBy { it.key }
    }

    val activity = remember(refreshKey, windowMinutes, searchQuery) {
        val snapshot = buffer.snapshot(windowMinutes * 60_000L)
        val filtered = if (searchQuery.isBlank()) snapshot
                       else snapshot.filter { it.domain.contains(searchQuery, ignoreCase = true) }
        filtered.groupBy { it.domain }
            .map { (domain, hits) ->
                val sorted = hits.sortedBy { it.timestampMillis }
                DomainActivitySummary(
                    domain = domain,
                    firstSeenMillis = sorted.first().timestampMillis,
                    lastSeenMillis = sorted.last().timestampMillis,
                    hitCount = sorted.size,
                    lastAction = sorted.last().action,
                    explicitRule = engine.explicitRule(domain),
                    effectiveRule = engine.effectiveRule(domain)
                )
            }
            .sortedByDescending { it.lastSeenMillis }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Reglas DNS personalizadas",
                    color = Color(0xFFFF6D00),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Forzar bloqueo/permiso le gana a cualquier otra configuracion " +
                    "(WebView, AdBlocker, etc.). Bloquear/Permitir normal solo aplica " +
                    "si ninguna otra politica ya decide sobre ese dominio.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newDomainText,
                    onValueChange = { newDomainText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ej: ejemplo.com") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF6D00),
                        cursorColor = Color(0xFFFF6D00)
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.BLOCK)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("Bloquear", fontSize = 10.sp) }
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.FORCE_BLOCK)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("Forzar bloq.", fontSize = 10.sp) }
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.ALLOW)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("Permitir", fontSize = 10.sp) }
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.FORCE_ALLOW)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("Forzar perm.", fontSize = 10.sp) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (customRules.isEmpty()) "No hay reglas guardadas todavia."
                    else "${customRules.size} regla(s) guardada(s):",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        items(customRules, key = { "rule_" + it.key }) { entry ->
            DnsCustomRuleRow(
                domain = entry.key,
                rule = entry.value,
                onForce = {
                    val forced = if (entry.value == RuleType.BLOCK) RuleType.FORCE_BLOCK else RuleType.FORCE_ALLOW
                    ruleManager.setRule(entry.key, forced)
                    refreshKey++
                },
                onRemove = {
                    ruleManager.clearRule(entry.key)
                    refreshKey++
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Actividad reciente",
                color = Color(0xFFFF6D00),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 5, 15, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = minutes == windowMinutes,
                        onClick = { windowMinutes = minutes; refreshKey++ },
                        label = { Text(if (minutes == 60) "1h" else "${minutes}m") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF6D00),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Buscar dominio...") },
                leadingIcon = { Icon(Icons.Default.Search, "Buscar") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF6D00),
                    cursorColor = Color(0xFFFF6D00)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${activity.size} dominios en los ultimos ${if (windowMinutes == 60) "60 min" else "$windowMinutes min"}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (activity.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Sin actividad DNS en esta ventana de tiempo.\n" +
                        "Abri una app y volve aca para ver sus dominios.",
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            }
        } else {
            items(activity, key = { "act_" + it.domain }) { summary ->
                DnsActivityRow(
                    summary = summary,
                    onBlock = {
                        ruleManager.setRule(summary.domain, RuleType.FORCE_BLOCK)
                        refreshKey++
                    },
                    onAllow = {
                        ruleManager.setRule(summary.domain, RuleType.FORCE_ALLOW)
                        refreshKey++
                    },
                    onClearRule = {
                        ruleManager.clearRule(summary.domain)
                        refreshKey++
                    }
                )
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

@Composable
private fun DnsCustomRuleRow(
    domain: String,
    rule: RuleType,
    onForce: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (estado, color) = when (rule) {
            RuleType.FORCE_BLOCK -> "🔒 Forzado: bloqueado" to Color(0xFFFF1744)
            RuleType.BLOCK -> "🔒 Bloqueado (normal)" to Color(0xFFFF5252)
            RuleType.FORCE_ALLOW -> "✅ Forzado: permitido" to Color(0xFF00E676)
            RuleType.ALLOW -> "✅ Permitido (normal)" to Color(0xFF69F0AE)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(domain, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp, maxLines = 1)
            Text(estado, color = color, style = MaterialTheme.typography.labelSmall)
        }
        if (rule == RuleType.BLOCK || rule == RuleType.ALLOW) {
            TextButton(onClick = onForce) {
                Text("Forzar", color = Color(0xFFFFD180), fontSize = 11.sp)
            }
        }
        TextButton(onClick = onRemove) {
            Text("Quitar", color = Color(0xFFFF5252), fontSize = 11.sp)
        }
    }
}

@Composable
private fun DnsActivityRow(
    summary: DomainActivitySummary,
    onBlock: () -> Unit,
    onAllow: () -> Unit,
    onClearRule: () -> Unit
) {
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val indicatorColor = when (summary.effectiveRule) {
            RuleType.BLOCK, RuleType.FORCE_BLOCK -> Color(0xFFFF1744)
            RuleType.ALLOW, RuleType.FORCE_ALLOW -> Color(0xFF00E676)
            else -> Color(0xFFFF6D00)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(indicatorColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                summary.domain,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1
            )

            val rango = if (summary.firstSeenMillis == summary.lastSeenMillis) {
                fmt.format(java.util.Date(summary.lastSeenMillis))
            } else {
                "${fmt.format(java.util.Date(summary.firstSeenMillis))} → ${fmt.format(java.util.Date(summary.lastSeenMillis))}"
            }
            Text(
                "$rango  ·  ${summary.hitCount}x",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            val (estado, color) = when {
                summary.effectiveRule == RuleType.FORCE_BLOCK ->
                    "🔒 Forzado: bloqueado" to Color(0xFFFF5252)
                summary.effectiveRule == RuleType.FORCE_ALLOW ->
                    "✅ Forzado: permitido" to Color(0xFF69F0AE)
                summary.effectiveRule == RuleType.BLOCK && summary.explicitRule == null ->
                    "🔒 Bloqueado (heredado)" to Color(0xFFFF5252)
                summary.effectiveRule == RuleType.BLOCK ->
                    "🔒 Bloqueado (normal)" to Color(0xFFFF5252)
                summary.effectiveRule == RuleType.ALLOW ->
                    "✅ Permitido (normal)" to Color(0xFF69F0AE)
                else ->
                    "" to Color.Transparent
            }
            if (estado.isNotEmpty()) {
                Text(estado, color = color, style = MaterialTheme.typography.labelSmall)
            }
        }

        when {
            (summary.effectiveRule == RuleType.BLOCK || summary.effectiveRule == RuleType.FORCE_BLOCK) && summary.explicitRule == null ->
                TextButton(onClick = onAllow) {
                    Text("Permitir", color = Color(0xFF69F0AE), fontSize = 11.sp)
                }
            summary.explicitRule != null ->
                TextButton(onClick = onClearRule) {
                    Text("Quitar", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            else ->
                TextButton(onClick = onBlock) {
                    Text("Bloquear", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
        }
    }
}

