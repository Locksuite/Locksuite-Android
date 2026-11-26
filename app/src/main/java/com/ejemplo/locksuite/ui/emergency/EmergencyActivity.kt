package com.ejemplo.locksuite.ui.emergency

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.DeviceRelease
import com.ejemplo.locksuite.security.PinManager
import com.ejemplo.locksuite.util.LocaleManager
import kotlinx.coroutines.delay
import com.ejemplo.locksuite.security.LockoutState
import com.ejemplo.locksuite.security.LockoutStatus
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicBoolean

class EmergencyActivity : ComponentActivity() {

    private val purging = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Proteger actividad contra capturas de pantalla/grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            EmergencyScreen(
                onPurgeSuccess = {
                    executeFullPurge()
                }
            )
        }
    }

    private fun executeFullPurge() {
        if (!purging.compareAndSet(false, true)) return
        val appCtx = applicationContext

        Toast.makeText(appCtx, "Iniciando purga total de políticas...", Toast.LENGTH_LONG).show()

        Thread({
            val released = try {
                DeviceRelease.releaseAndPrepareUninstall(appCtx, promptUninstall = false)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

            runOnUiThread {
                val msg = if (released) {
                    "Purga de emergencia completa. Dispositivo liberado."
                } else {
                    "Purga ejecutada (revise estado de Device Owner)."
                }
                Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing && !isDestroyed) {
                    finishAffinity()
                }
            }
        }, "EmergencyPurge").start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(onPurgeSuccess: () -> Unit) {
    val context = LocalContext.current
    var inputPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var lockoutTimeRemaining by remember { mutableLongStateOf(0L) }

    fun updateLockoutState() {
        when (val state = PinManager.getLockoutState(context)) {
            is LockoutState.Locked -> {
                lockoutTimeRemaining = state.remainingMs
                errorMessage = LocaleManager.t("entry_blocked") + " ${state.remainingMs / 1000}s"
            }
            LockoutState.Open -> {
                lockoutTimeRemaining = 0
                if (errorMessage.startsWith(LocaleManager.t("entry_blocked"))) {
                    errorMessage = ""
                }
            }
        }
    }

    // 27/9/2026 (batería): solo mientras la pantalla se ve. Mismo motivo que en
    // LoginActivity: un `while (true)` dentro de `LaunchedEffect(Unit)` sigue corriendo con
    // la Activity detenida en segundo plano.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                updateLockoutState()
                if (lockoutTimeRemaining > 0) {
                    delay(1000L)
                } else {
                    delay(3000L)
                }
            }
        }
    }

    val alertRed = Color(0xFFC0392B)
    val alertDarkRed = Color(0xFF7B241C)
    val accentYellow = Color(0xFFF5C438)

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(alertDarkRed)
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Encabezado
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = LocaleManager.t("emerg_title"),
                color = accentYellow,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (LocaleManager.getLang() == "he") "אזהרה" else if (LocaleManager.getLang() == "en") "WARNING" else "ADVERTENCIA",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = LocaleManager.t("emerg_warning"),
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        // Campo de entrada de contraseña QWERTY seguro
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = inputPassword,
                onValueChange = { 
                    inputPassword = it 
                    errorMessage = ""
                },
                enabled = lockoutTimeRemaining <= 0,
                label = { Text(LocaleManager.t("emerg_pin"), color = Color.White.copy(alpha = 0.8f)) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentYellow,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    focusedLabelColor = accentYellow,
                    unfocusedLabelColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color.Gray,
                    disabledBorderColor = Color.White.copy(alpha = 0.2f),
                    disabledLabelColor = Color.Gray
                ),
                trailingIcon = {
                    val label = if (passwordVisible) {
                        if (LocaleManager.getLang() == "he") "הסתר" else if (LocaleManager.getLang() == "en") "HIDE" else "OCULTAR"
                    } else {
                        if (LocaleManager.getLang() == "he") "הצג" else if (LocaleManager.getLang() == "en") "SHOW" else "MOSTRAR"
                    }
                    TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = lockoutTimeRemaining <= 0) {
                        Text(
                            text = label,
                            color = if (lockoutTimeRemaining <= 0) accentYellow else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = accentYellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Botón de ejecución
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (inputPassword.isNotEmpty() && lockoutTimeRemaining <= 0) {
                        if (PinManager.verifyMasterPassword(context, inputPassword)) {
                            PinManager.resetAttempts(context)
                            onPurgeSuccess()
                        } else {
                            inputPassword = ""
                            when (val status = PinManager.recordFailedAttempt(context)) {
                                is LockoutStatus.LockedOut -> {
                                    updateLockoutState()
                                }
                                is LockoutStatus.Warning -> {
                                    errorMessage = LocaleManager.t("incorrect_pin") + "${status.remainingAttempts}"
                                }
                            }
                        }
                    }
                },
                enabled = lockoutTimeRemaining <= 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentYellow,
                    contentColor = alertDarkRed,
                    disabledContainerColor = Color.Gray,
                    disabledContentColor = Color.DarkGray
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = LocaleManager.t("emerg_wipe"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = {
                    try {
                        val dialIntent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(dialIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Error al abrir el marcador", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text(
                    text = "📞 " + LocaleManager.t("emergency_call"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
