package com.ejemplo.locksuite.ui.provisioning

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ejemplo.locksuite.provisioning.LocalProvisioningProbe
import com.ejemplo.locksuite.provisioning.MultimediaDeviceOwnerActivator
import com.ejemplo.locksuite.provisioning.adb.ClassicAdbChannelOpener
import kotlinx.coroutines.launch

@Composable
fun MultimediaProvisioningDialog(
    onDismiss: () -> Unit,
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var probeReport by remember { mutableStateOf<LocalProvisioningProbe.Report?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var isWaitingForToggle by remember { mutableStateOf(false) }
    var showAuthPromptNotice by remember { mutableStateOf(false) }
    var statusTitle by remember { mutableStateOf("Analizando el dispositivo…") }
    var statusDetail by remember { mutableStateOf("") }
    var errorDetail by remember { mutableStateOf("") }
    var pendingRestore by remember {
        mutableStateOf(MultimediaDeviceOwnerActivator.getPendingRestorePackages(context))
    }

    val navyDark = Color(0xFF0E141B)
    val navyCard = Color(0xFF1A222C)
    val accentGold = Color(0xFFF5C438)
    val okGreen = Color(0xFF2ECC71)
    val alertRed = Color(0xFFF87171)

    fun refreshProbe() {
        scope.launch {
            isBusy = true
            val rep = LocalProvisioningProbe.run(context)
            probeReport = rep
            pendingRestore = MultimediaDeviceOwnerActivator.getPendingRestorePackages(context)
            statusTitle = when (rep.recommendedPath) {
                LocalProvisioningProbe.RecommendedPath.ALREADY_ACTIVE ->
                    "¡LockSuite ya es Device Owner en este equipo!"
                LocalProvisioningProbe.RecommendedPath.ROOT_SU ->
                    "Acceso Root detectado: activación directa en 1 toque lista."
                LocalProvisioningProbe.RecommendedPath.LOCAL_ADB_READY ->
                    "Canal ADB local detectado en el puerto ${rep.adbOpenPort}: listo para activar."
                LocalProvisioningProbe.RecommendedPath.NEEDS_ADB_SETUP ->
                    "Modo Multimedia / Sin PC: requiere abrir el puerto ADB local (5555)."
            }
            isBusy = false
            if (rep.isAlreadyDeviceOwner) {
                onActivated()
            }
        }
    }

    fun runActivation(port: Int? = null) {
        scope.launch {
            isBusy = true
            errorDetail = ""
            showAuthPromptNotice = false
            statusTitle = "Congelando autenticadores de cuentas temporalmente y activando Device Owner…"
            statusDetail = "Tus cuentas NO se borrarán; se reactivarán automáticamente en unos segundos."

            val outcome = MultimediaDeviceOwnerActivator.activate(
                context = context,
                preferredAdbPort = port,
                onAdbAuthPrompt = {
                    showAuthPromptNotice = true
                }
            )
            pendingRestore = MultimediaDeviceOwnerActivator.getPendingRestorePackages(context)
            when (outcome) {
                is MultimediaDeviceOwnerActivator.Outcome.Activated -> {
                    statusTitle = "¡Activación completada por ${outcome.channel}!"
                    statusDetail = if (outcome.temporarilyDisabledPackages.isEmpty()) {
                        "Device Owner activo."
                    } else {
                        "Device Owner activo. Paquetes de cuentas restaurados (${outcome.temporarilyDisabledPackages.size})."
                    }
                    isBusy = false
                    onActivated()
                }
                is MultimediaDeviceOwnerActivator.Outcome.Failed -> {
                    statusTitle = "No se pudo completar la activación"
                    statusDetail = outcome.reason
                    errorDetail = outcome.technicalDetail
                    isBusy = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshProbe()
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = navyDark
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🚗 Instalación Fácil (Multimedia / Sin PC)",
                    color = accentGold,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Activa LockSuite como administrador total (Device Owner) directamente desde el equipo, sin necesidad de computadora ni de borrar las cuentas de Google existentes.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )

                // Tarjeta de estado actual
                Card(
                    colors = CardDefaults.cardColors(containerColor = navyCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = statusTitle,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (statusDetail.isNotEmpty()) {
                            Text(
                                text = statusDetail,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp
                            )
                        }
                        if (showAuthPromptNotice) {
                            Text(
                                text = "⚠️ ¡ATENCIÓN! Apareció en pantalla el cartel de «¿Permitir depuración por USB?». Marca «Permitir siempre» y toca ACEPTAR ahora.",
                                color = accentGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        if (errorDetail.isNotEmpty()) {
                            Text(
                                text = "Detalle técnico: $errorDetail",
                                color = alertRed,
                                fontSize = 12.sp
                            )
                        }
                        val rep = probeReport
                        if (rep != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• Root (su): ${if (rep.rootAvailable) "Sí" else "No"}   " +
                                    "• Puerto ADB local: ${rep.adbOpenPort?.toString() ?: "Cerrado"}   " +
                                    "• Depuración USB: ${if (rep.adbEnabledInSettings) "Activa" else "Inactiva"}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Alerta de seguridad si algún paquete de cuentas quedó pendiente de restaurar
                if (pendingRestore.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF7B241C)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Restauración de cuentas pendiente (${pendingRestore.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        val rem = MultimediaDeviceOwnerActivator.restoreAccounts(context)
                                        pendingRestore = rem.toSet()
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentGold,
                                    contentColor = navyDark
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reactivar paquetes de cuentas ahora", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (isBusy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = accentGold, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isWaitingForToggle) {
                                "Esperando que abras Depuración USB (puerto 5555)…"
                            } else {
                                "Procesando…"
                            },
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }

                // Botón Principal: Activar Ahora
                Button(
                    onClick = { runActivation() },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentGold,
                        contentColor = navyDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚡ Activar Device Owner Ahora",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                // Paso guiado para abrir el puerto 5555 sin PC
                Card(
                    colors = CardDefaults.cardColors(containerColor = navyCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "¿El puerto 5555 figura Cerrado? Ábrelo sin PC en 2 pasos:",
                            color = accentGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "1. Toca «Preparar puerto 5555 y abrir Opciones de Desarrollador».\n" +
                                "2. En Opciones de Desarrollador, APAGA y vuelve a ENCENDER «Depuración por USB» (o activa «Depuración por red / TCP»). Al volver, LockSuite se activará solo.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    isWaitingForToggle = true
                                    statusTitle = "Preparando propiedad TCP 5555…"
                                    when (val prep = ClassicAdbChannelOpener.prepareClassicPort(context, 5555)) {
                                        is ClassicAdbChannelOpener.PrepOutcome.AlreadyOpen -> {
                                            isWaitingForToggle = false
                                            runActivation(prep.port)
                                        }
                                        is ClassicAdbChannelOpener.PrepOutcome.OpenedViaRoot -> {
                                            isWaitingForToggle = false
                                            runActivation(prep.port)
                                        }
                                        is ClassicAdbChannelOpener.PrepOutcome.WaitingForUsbDebuggingToggle -> {
                                            statusTitle = "Apaga y vuelve a encender «Depuración por USB»"
                                            statusDetail = "Vigilando el puerto local 5555 durante 5 minutos…"
                                            openDeveloperSettingsSafely(context)
                                            val opened = ClassicAdbChannelOpener.waitForLoopbackPort(
                                                port = prep.port,
                                                timeoutMs = 300_000L
                                            )
                                            isWaitingForToggle = false
                                            if (opened) {
                                                runActivation(prep.port)
                                            } else {
                                                statusTitle = "Tiempo de espera agotado"
                                                statusDetail = "El puerto 5555 no se abrió. Verifica en Opciones de Desarrollador si el equipo tiene «ADB sobre red» o «Depuración inalámbrica»."
                                                isBusy = false
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A3647),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🔧 Preparar puerto 5555 y abrir Opciones de Desarrollador",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // Fila inferior de botones
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { refreshProbe() },
                        enabled = !isBusy
                    ) {
                        Text("Volver a comprobar", color = accentGold)
                    }
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isBusy
                    ) {
                        Text("Cerrar", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

private fun openDeveloperSettingsSafely(context: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}
