package com.ejemplo.locksuite.ui.dashboard

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ejemplo.locksuite.dns.DomainSafety
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.WhitelistBackupStore
import com.ejemplo.locksuite.mdm.WhitelistCatalogIo
import com.ejemplo.locksuite.mdm.WhitelistCatalogIo.DeviceWhitelistCatalog
import com.ejemplo.locksuite.mdm.WhitelistCatalogIo.WhitelistAppEntry
import com.ejemplo.locksuite.mdm.WhitelistManager
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pestaña «Lista blanca» por dispositivo (estilo Lockfox):
 * Solo visible cuando el modo lista blanca está encendido desde Inicio.
 */
@Composable
fun WhitelistTabContent(
    context: android.content.Context,
    onDisableWhitelist: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val wl = remember { WhitelistManager(context) }
    val backupStore = remember { WhitelistBackupStore(context) }

    var simulation by remember { mutableStateOf(wl.isSimulation()) }
    var sharedCdn by remember { mutableStateOf(wl.isSharedCdnAllowed()) }
    var catalog by remember { mutableStateOf(wl.getDeviceCatalog()) }
    var hasCustomCatalog by remember { mutableStateOf(wl.hasSavedDeviceCatalog()) }
    var domainCount by remember { mutableIntStateOf(wl.ruleCount()) }
    var audit by remember { mutableStateOf(WhitelistManager.auditSnapshot()) }

    var innerTab by remember { mutableIntStateOf(0) } // 0 = Apps, 1 = Celular, 2 = Bloqueados
    var searchQuery by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf("all") } // all | enabled | disabled | blocked | noweb | installed
    var expandedApps by remember { mutableStateOf(setOf<String>()) }

    var globalAllowSearch by remember { mutableStateOf("") }
    var globalBlockSearch by remember { mutableStateOf("") }
    var globalAllowInput by remember { mutableStateOf("") }
    var globalBlockInput by remember { mutableStateOf("") }
    var auditSearch by remember { mutableStateOf("") }

    var editingApp by remember { mutableStateOf<WhitelistAppEntry?>(null) }
    var isCreatingApp by remember { mutableStateOf(false) }
    var showBackupsDialog by remember { mutableStateOf(false) }
    var confirmStrictDialog by remember { mutableStateOf(false) }
    var confirmResetDialog by remember { mutableStateOf(false) }
    var confirmDisableDialog by remember { mutableStateOf(false) }
    var auditPickAppDomain by remember { mutableStateOf<String?>(null) }
    var pendingCatalogImport by remember { mutableStateOf<WhitelistCatalogIo.CatalogParseResult?>(null) }
    var pendingListImport by remember { mutableStateOf<Pair<Boolean, WhitelistCatalogIo.DomainListParseResult>?>(null) }

    val navyDark = Color(0xFF0E141B)
    val navyMedium = Color(0xFF1A222C)
    val accentOrange = Color(0xFFF5C438)
    val greenOk = Color(0xFF34D399)
    val redBad = Color(0xFFF87171)

    val installedApps = remember {
        try {
            AppController(context).getUserApps(loadIcon = false)
                .sortedBy { it.label.lowercase(Locale.ROOT) }
        } catch (_: Exception) {
            emptyList()
        }
    }
    val installedPkgs = remember(installedApps) { installedApps.map { it.packageName }.toSet() }

    fun refreshLocalState() {
        catalog = wl.getDeviceCatalog()
        hasCustomCatalog = wl.hasSavedDeviceCatalog()
        domainCount = wl.ruleCount()
        simulation = wl.isSimulation()
        sharedCdn = wl.isSharedCdnAllowed()
        audit = WhitelistManager.auditSnapshot()
    }

    fun persistCatalog(next: DeviceWhitelistCatalog, reason: String, toastMsg: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                wl.saveDeviceCatalog(next, reason = reason, createBackup = true, pushToCloud = true)
                FirebaseDeviceSync.syncWhitelistState(context, force = true)
            }
            refreshLocalState()
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                audit = WhitelistManager.auditSnapshot()
                delay(3000)
            }
        }
    }

    fun readUriText(uri: Uri): String {
        val name = uri.toString().lowercase(Locale.ROOT)
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: ""
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val isDocx = name.endsWith(".docx") || mime.contains("wordprocessingml") ||
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        return if (isDocx) WhitelistCatalogIo.extractTextFromDocx(bytes) else bytes.toString(Charsets.UTF_8)
    }

    // SAF launchers (marcar isLaunchingInternalFlow = true antes de abrir para no cerrar sesión)
    val importCatalogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        DashboardActivity.isLaunchingInternalFlow = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    WhitelistCatalogIo.parseCatalogText(readUriText(uri))
                } catch (e: Exception) {
                    WhitelistCatalogIo.CatalogParseResult(false, null, error = e.message ?: "Error leyendo archivo")
                }
            }
            if (!res.ok || res.catalog == null) {
                Toast.makeText(context, res.error ?: "No se pudo leer el catálogo", Toast.LENGTH_LONG).show()
            } else {
                pendingCatalogImport = res
            }
        }
    }

    val exportCatalogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        DashboardActivity.isLaunchingInternalFlow = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val txt = WhitelistCatalogIo.formatCatalogText(catalog, reason = "Exportado desde LockSuite")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            Toast.makeText(context, if (ok) "Catálogo descargado." else "Error al guardar el archivo.", Toast.LENGTH_SHORT).show()
        }
    }

    val importGlobalAllowLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        DashboardActivity.isLaunchingInternalFlow = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    WhitelistCatalogIo.parseDomainListText(readUriText(uri), isAllow = true)
                } catch (e: Exception) {
                    null
                }
            }
            if (res == null) {
                Toast.makeText(context, "No se pudo leer el archivo", Toast.LENGTH_LONG).show()
            } else {
                pendingListImport = true to res
            }
        }
    }

    val importGlobalBlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        DashboardActivity.isLaunchingInternalFlow = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    WhitelistCatalogIo.parseDomainListText(readUriText(uri), isAllow = false)
                } catch (e: Exception) {
                    null
                }
            }
            if (res == null) {
                Toast.makeText(context, "No se pudo leer el archivo", Toast.LENGTH_LONG).show()
            } else {
                pendingListImport = false to res
            }
        }
    }

    val exportGlobalAllowLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        DashboardActivity.isLaunchingInternalFlow = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val txt = WhitelistCatalogIo.formatDomainListText(catalog.globalAllow, "Permitidos para todo el celular")
                context.contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
            }
            Toast.makeText(context, "Lista exportada.", Toast.LENGTH_SHORT).show()
        }
    }

    val exportGlobalBlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        DashboardActivity.isLaunchingInternalFlow = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val txt = WhitelistCatalogIo.formatDomainListText(catalog.globalBlock, "Prohibidos para todo el celular")
                context.contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
            }
            Toast.makeText(context, "Lista exportada.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Diálogos de confirmación / edición ───────────────────────────────────

    if (confirmStrictDialog) {
        AlertDialog(
            onDismissRequest = { confirmStrictDialog = false },
            containerColor = navyMedium,
            title = { Text("¿Pasar a Lista blanca ESTRICTA?", color = accentOrange, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Solo resolverán los dominios permitidos de este catálogo. Todo lo demás dejará de funcionar en el dispositivo." +
                        if (audit.isEmpty()) "\n\n⚠️ Todavía no hay dominios anotados en simulación." else "\n\nHay ${audit.size} dominio(s) anotado(s) en la auditoría.",
                    color = Color.White,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmStrictDialog = false
                        simulation = false
                        wl.setSimulation(false)
                        domainCount = wl.ruleCount()
                        scope.launch(Dispatchers.IO) { FirebaseDeviceSync.syncWhitelistState(context, force = true) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redBad, contentColor = navyDark)
                ) { Text("Pasar a estricto", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStrictDialog = false }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    if (confirmDisableDialog) {
        AlertDialog(
            onDismissRequest = { confirmDisableDialog = false },
            containerColor = navyMedium,
            title = { Text("¿Apagar modo lista blanca?", color = accentOrange, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "El dispositivo volverá al modo lista negra normal y esta pestaña se ocultará hasta que la reactives desde Inicio. Tu catálogo personalizado se conserva.",
                    color = Color.White,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDisableDialog = false
                        onDisableWhitelist()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                ) { Text("Apagar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisableDialog = false }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    if (confirmResetDialog) {
        AlertDialog(
            onDismissRequest = { confirmResetDialog = false },
            containerColor = navyMedium,
            title = { Text("¿Restaurar catálogo por defecto?", color = accentOrange, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Se guardará un respaldo del catálogo actual y se cargará el archivo base de fábrica (25 apps y listas globales base) solo para este dispositivo.",
                    color = Color.White,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResetDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                wl.resetDeviceCatalogToDefault(pushToCloud = true)
                                FirebaseDeviceSync.syncWhitelistState(context, force = true)
                            }
                            refreshLocalState()
                            Toast.makeText(context, "Catálogo restaurado al de fábrica.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                ) { Text("Restaurar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetDialog = false }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    pendingCatalogImport?.let { imp ->
        val c = imp.catalog!!
        val st = WhitelistCatalogIo.stats(c)
        AlertDialog(
            onDismissRequest = { pendingCatalogImport = null },
            containerColor = navyMedium,
            title = { Text("Importar catálogo completo", color = accentOrange, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Se respaldará el catálogo actual y se reemplazará por este archivo:\n" +
                            "• ${st.appCount} apps (${st.appAllow} permitidos, ${st.appBlock} prohibidos)\n" +
                            "• ${st.globalAllow} permitidos globales · ${st.globalBlock} prohibidos globales",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    if (imp.warnings.isNotEmpty()) {
                        Text(
                            "Avisos (${imp.warnings.size} omitidos): ${imp.warnings.take(4).joinToString(", ")}",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCatalogImport = null
                        persistCatalog(c, "import_txt_docx", "Catálogo importado y aplicado.")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                ) { Text("Reemplazar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCatalogImport = null }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    pendingListImport?.let { (isAllow, res) ->
        AlertDialog(
            onDismissRequest = { pendingListImport = null },
            containerColor = navyMedium,
            title = {
                Text(
                    if (isAllow) "Reemplazar Permitidos del celular" else "Reemplazar Prohibidos del celular",
                    color = accentOrange,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Se cargarán ${res.domains.size} dominio(s) válidos (respaldando el estado anterior).",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    if (res.rejected.isNotEmpty()) {
                        Text(
                            "Descartados (${res.rejected.size}): ${res.rejected.take(4).joinToString { "${it.first} (${it.second})" }}",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingListImport = null
                        val next = if (isAllow) catalog.copy(globalAllow = res.domains) else catalog.copy(globalBlock = res.domains)
                        persistCatalog(next, if (isAllow) "import_global_allow" else "import_global_block", "Lista reemplazada.")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                ) { Text("Reemplazar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingListImport = null }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    editingApp?.let { target ->
        AppWhitelistEditorDialog(
            initial = target,
            isNew = isCreatingApp,
            installedApps = installedApps,
            onDismiss = {
                editingApp = null
                isCreatingApp = false
            },
            onDelete = if (isCreatingApp) null else {
                {
                    editingApp = null
                    val next = catalog.copy(apps = catalog.apps.filterNot { it.packageName == target.packageName })
                    persistCatalog(next, "delete_app_${target.packageName}", "App quitada del catálogo.")
                }
            },
            onSave = { updatedApp, rejectedMsgs ->
                editingApp = null
                isCreatingApp = false
                val others = catalog.apps.filterNot { it.packageName == updatedApp.packageName }
                val next = catalog.copy(apps = others + updatedApp)
                persistCatalog(next, "edit_app_${updatedApp.packageName}", "App guardada.")
                if (rejectedMsgs.isNotEmpty()) {
                    Toast.makeText(context, "Omitidos por seguridad: ${rejectedMsgs.take(3).joinToString()}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showBackupsDialog) {
        WhitelistBackupsDialog(
            backupStore = backupStore,
            currentCatalog = catalog,
            onDismiss = { showBackupsDialog = false },
            onRestore = { restoredCat, meta ->
                showBackupsDialog = false
                persistCatalog(restoredCat, "restore_${meta.id}", "Respaldo restaurado.")
            },
            onToast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        )
    }

    auditPickAppDomain?.let { domToAssign ->
        AuditPickAppDialog(
            domain = domToAssign,
            apps = catalog.apps,
            onDismiss = { auditPickAppDomain = null },
            onSelectApp = { app ->
                auditPickAppDomain = null
                val (clean, why) = DomainSafety.cleanAndValidateAllow(domToAssign)
                if (clean == null) {
                    Toast.makeText(context, "No se puede permitir ($why)", Toast.LENGTH_LONG).show()
                    return@AuditPickAppDialog
                }
                val updatedApp = app.copy(
                    status = WhitelistCatalogIo.STATUS_ENABLED,
                    allow = (app.allow + clean).distinct().sorted(),
                    block = app.block.filterNot { it == clean }
                )
                val next = catalog.copy(apps = catalog.apps.map { if (it.packageName == app.packageName) updatedApp else it })
                persistCatalog(next, "audit_add_${app.packageName}", "Agregado a ${app.label}")
            }
        )
    }

    // ── Contenido principal ──────────────────────────────────────────────────

    val catStats = WhitelistCatalogIo.stats(catalog)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(navyDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tarjeta de control del modo y acciones Lockfox
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🛡️ Lista blanca del dispositivo",
                                color = accentOrange,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                "${catStats.enabledAppCount}/${catStats.appCount} apps · " +
                                    "${catStats.globalAllow} permitidos / ${catStats.globalBlock} prohibidos globales · " +
                                    "$domainCount reglas en DNS",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp
                            )
                            Text(
                                if (hasCustomCatalog) "Configuración propia de este celular"
                                else "Usando catálogo por defecto precargado",
                                color = Color.White.copy(alpha = 0.52f),
                                fontSize = 11.sp
                            )
                        }
                        TextButton(onClick = { confirmDisableDialog = true }) {
                            Text("Apagar", color = redBad, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (simulation) "Simulación activa (solo anota)" else "Modo ESTRICTO activo (bloquea todo lo no listado)",
                                color = if (simulation) accentOrange else redBad,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                if (simulation) "Anota en «Bloqueados» sin cortar internet. Probá unos días antes de pasar a estricto."
                                else "Todo lo que no figure permitido acá queda bloqueado en el túnel DNS.",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = !simulation,
                            onCheckedChange = { wantStrict ->
                                if (wantStrict) {
                                    confirmStrictDialog = true
                                } else {
                                    simulation = true
                                    wl.setSimulation(true)
                                    scope.launch(Dispatchers.IO) { FirebaseDeviceSync.syncWhitelistState(context, force = true) }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = navyDark, checkedTrackColor = redBad)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Permitir CDN compartidos", color = Color.White, fontSize = 13.sp)
                            Text(
                                "CloudFront, Akamai, Fastly. Apagarlo endurece más pero puede requerir hosts exactos.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = sharedCdn,
                            onCheckedChange = {
                                sharedCdn = it
                                wl.setSharedCdnAllowed(it)
                                domainCount = wl.ruleCount()
                                scope.launch(Dispatchers.IO) { FirebaseDeviceSync.syncWhitelistState(context, force = true) }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = navyDark, checkedTrackColor = accentOrange)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isCreatingApp = true
                                editingApp = WhitelistAppEntry(packageName = "", label = "")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                        ) { Text("➕ Nueva App", fontSize = 12.sp, fontWeight = FontWeight.Bold) }

                        OutlinedButton(onClick = { showBackupsDialog = true }) {
                            Text("🗂️ Respaldos", color = Color.White, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            DashboardActivity.isLaunchingInternalFlow = true
                            importCatalogLauncher.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*"))
                        }) { Text("📤 Subir .txt/.docx", color = Color.White, fontSize = 12.sp) }

                        OutlinedButton(onClick = {
                            DashboardActivity.isLaunchingInternalFlow = true
                            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                            exportCatalogLauncher.launch("locksuite-dominios-$ts.txt")
                        }) { Text("⬇️ Descargar .txt", color = Color.White, fontSize = 12.sp) }

                        OutlinedButton(onClick = { confirmResetDialog = true }) {
                            Text("♻️ Por defecto", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Sub-navegación interna (Apps / Celular / Bloqueados)
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf(
                    0 to "Apps (${catalog.apps.size})",
                    1 to "Todo el celular (${catalog.globalAllow.size}/${catalog.globalBlock.size})",
                    2 to "Bloqueados (${audit.size})"
                )
                tabs.forEach { (idx, title) ->
                    FilterChip(
                        selected = innerTab == idx,
                        onClick = { innerTab = idx },
                        label = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = navyMedium,
                            labelColor = Color.White,
                            selectedContainerColor = accentOrange,
                            selectedLabelColor = navyDark
                        )
                    )
                }
            }
        }

        if (innerTab == 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar app, paquete o dominio...", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = accentOrange) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentOrange,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = navyMedium,
                            unfocusedContainerColor = navyMedium
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "all" to "Todas",
                            "enabled" to "Habilitadas",
                            "disabled" to "Deshabilitadas",
                            "noweb" to "Sin web",
                            "installed" to "Instaladas"
                        ).forEach { (id, lbl) ->
                            FilterChip(
                                selected = appFilter == id,
                                onClick = { appFilter = id },
                                label = { Text(lbl, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = navyMedium,
                                    labelColor = Color.White.copy(alpha = 0.85f),
                                    selectedContainerColor = accentOrange,
                                    selectedLabelColor = navyDark
                                )
                            )
                        }
                    }
                }
            }

            val q = searchQuery.trim().lowercase(Locale.ROOT)
            val filteredApps = catalog.apps.filter { app ->
                val matchesSearch = q.isEmpty() ||
                    app.label.lowercase(Locale.ROOT).contains(q) ||
                    app.packageName.lowercase(Locale.ROOT).contains(q) ||
                    app.allow.any { it.contains(q) } ||
                    app.block.any { it.contains(q) }
                val matchesFilter = when (appFilter) {
                    "enabled" -> app.status == WhitelistCatalogIo.STATUS_ENABLED
                    "disabled" -> app.status != WhitelistCatalogIo.STATUS_ENABLED
                    "noweb" -> app.access == WhitelistCatalogIo.ACCESS_NOWEB
                    "installed" -> app.packageName in installedPkgs
                    else -> true
                }
                matchesSearch && matchesFilter
            }.sortedWith(compareBy<WhitelistAppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })

            if (filteredApps.isEmpty()) {
                item {
                    Text(
                        "Ninguna app coincide con el filtro.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isExp = app.packageName in expandedApps
                    val isEnabled = app.status == WhitelistCatalogIo.STATUS_ENABLED
                    val isInstalled = app.packageName in installedPkgs
                    Card(
                        colors = CardDefaults.cardColors(containerColor = navyMedium),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        app.packageName,
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        Text(
                                            if (isEnabled) "✓ Habilitada" else "✕ Deshabilitada",
                                            color = if (isEnabled) greenOk else redBad,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (app.access == WhitelistCatalogIo.ACCESS_NOWEB) {
                                            Text("🚫 Sin web", color = accentOrange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        if (isInstalled) {
                                            Text("• Instalada", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                                        }
                                    }
                                }
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        val nextStatus = if (checked) WhitelistCatalogIo.STATUS_ENABLED else WhitelistCatalogIo.STATUS_DISABLED
                                        val next = catalog.copy(apps = catalog.apps.map {
                                            if (it.packageName == app.packageName) it.copy(status = nextStatus) else it
                                        })
                                        persistCatalog(next, "toggle_${app.packageName}", if (checked) "App habilitada" else "App deshabilitada")
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = navyDark, checkedTrackColor = accentOrange)
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "✅ ${app.allow.size} permitidos · 🚫 ${app.block.size} prohibidos",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    expandedApps = if (isExp) expandedApps - app.packageName else expandedApps + app.packageName
                                }) {
                                    Text(if (isExp) "Ocultar" else "Dominios", color = Color.LightGray, fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    isCreatingApp = false
                                    editingApp = app
                                }) {
                                    Text("✏️ Editar", color = accentOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (isExp) {
                                if (app.allow.isNotEmpty()) {
                                    Text("Permitidos:", color = greenOk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        app.allow.joinToString(", "),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                                if (app.block.isNotEmpty()) {
                                    Text("Prohibidos:", color = redBad, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        app.block.joinToString(", "),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (innerTab == 1) {
            item {
                GlobalDomainBoxCard(
                    title = "✅ Permitidos para todo el celular (${catalog.globalAllow.size})",
                    hint = "Se abren en todo este celular. Se rechazan TLDs, sufijos públicos y bases CDN compartidas.",
                    accentColor = greenOk,
                    domains = catalog.globalAllow,
                    inputValue = globalAllowInput,
                    onInputChange = { globalAllowInput = it },
                    searchValue = globalAllowSearch,
                    onSearchChange = { globalAllowSearch = it },
                    onAdd = {
                        val res = WhitelistCatalogIo.parseDomainListText(globalAllowInput, isAllow = true)
                        if (res.domains.isEmpty()) {
                            val why = res.rejected.firstOrNull()?.let { "${it.first}: ${it.second}" } ?: "Escribí al menos un dominio válido"
                            Toast.makeText(context, why, Toast.LENGTH_LONG).show()
                        } else {
                            val added = res.domains
                            val next = catalog.copy(
                                globalAllow = (catalog.globalAllow + added).distinct().sorted(),
                                globalBlock = catalog.globalBlock.filterNot { it in added }
                            )
                            globalAllowInput = ""
                            persistCatalog(next, "add_global_allow", "${added.size} dominio(s) permitido(s).")
                            if (res.rejected.isNotEmpty()) {
                                Toast.makeText(context, "Omitidos: ${res.rejected.joinToString { it.first }}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onRemove = { dom ->
                        val next = catalog.copy(globalAllow = catalog.globalAllow.filterNot { it == dom })
                        persistCatalog(next, "remove_global_allow", "Quitado: $dom")
                    },
                    onImportFile = {
                        DashboardActivity.isLaunchingInternalFlow = true
                        importGlobalAllowLauncher.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*"))
                    },
                    onExportFile = {
                        DashboardActivity.isLaunchingInternalFlow = true
                        exportGlobalAllowLauncher.launch("locksuite-permitidos-celular.txt")
                    }
                )
            }
            item {
                GlobalDomainBoxCard(
                    title = "🚫 Prohibidos para todo el celular (${catalog.globalBlock.size})",
                    hint = "Bloqueados en todo el celular incluso si una app los tiene permitidos.",
                    accentColor = redBad,
                    domains = catalog.globalBlock,
                    inputValue = globalBlockInput,
                    onInputChange = { globalBlockInput = it },
                    searchValue = globalBlockSearch,
                    onSearchChange = { globalBlockSearch = it },
                    onAdd = {
                        val res = WhitelistCatalogIo.parseDomainListText(globalBlockInput, isAllow = false)
                        if (res.domains.isEmpty()) {
                            Toast.makeText(context, "Escribí al menos un dominio válido", Toast.LENGTH_SHORT).show()
                        } else {
                            val added = res.domains
                            val next = catalog.copy(
                                globalBlock = (catalog.globalBlock + added).distinct().sorted(),
                                globalAllow = catalog.globalAllow.filterNot { it in added }
                            )
                            globalBlockInput = ""
                            persistCatalog(next, "add_global_block", "${added.size} dominio(s) prohibido(s).")
                        }
                    },
                    onRemove = { dom ->
                        val next = catalog.copy(globalBlock = catalog.globalBlock.filterNot { it == dom })
                        persistCatalog(next, "remove_global_block", "Quitado: $dom")
                    },
                    onImportFile = {
                        DashboardActivity.isLaunchingInternalFlow = true
                        importGlobalBlockLauncher.launch(arrayOf("text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*"))
                    },
                    onExportFile = {
                        DashboardActivity.isLaunchingInternalFlow = true
                        exportGlobalBlockLauncher.launch("locksuite-prohibidos-celular.txt")
                    }
                )
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = navyMedium),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (simulation) "Se habría bloqueado (${audit.size})" else "Bloqueados (${audit.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                WhitelistManager.clearAudit()
                                audit = emptyList()
                                scope.launch(Dispatchers.IO) { FirebaseDeviceSync.syncWhitelistState(context, force = true) }
                            }) { Text("Limpiar", color = redBad, fontSize = 12.sp) }
                        }
                        OutlinedTextField(
                            value = auditSearch,
                            onValueChange = { auditSearch = it },
                            placeholder = { Text("Filtrar dominio...", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentOrange,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            val aq = auditSearch.trim().lowercase(Locale.ROOT)
            val filteredAudit = audit.filter { aq.isEmpty() || it.first.contains(aq) }
            if (filteredAudit.isEmpty()) {
                item {
                    Text(
                        "Nada registrado todavía. Usá las apps del equipo un rato para capturar los dominios que piden.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(filteredAudit.take(150), key = { it.first }) { (dom, hits) ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = navyMedium),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(dom, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("$hits intento(s)", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { auditPickAppDomain = dom }) {
                                    Text("+ App", color = accentOrange, fontSize = 11.sp)
                                }
                                OutlinedButton(onClick = {
                                    val (clean, why) = DomainSafety.cleanAndValidateAllow(dom)
                                    if (clean == null) {
                                        Toast.makeText(context, "No se puede permitir ($why)", Toast.LENGTH_LONG).show()
                                    } else {
                                        val next = catalog.copy(
                                            globalAllow = (catalog.globalAllow + clean).distinct().sorted(),
                                            globalBlock = catalog.globalBlock.filterNot { it == clean }
                                        )
                                        persistCatalog(next, "audit_global_allow", "Permitido: $clean")
                                    }
                                }) { Text("✅ Celular", color = greenOk, fontSize = 11.sp) }
                                OutlinedButton(onClick = {
                                    val next = catalog.copy(
                                        globalBlock = (catalog.globalBlock + dom).distinct().sorted(),
                                        globalAllow = catalog.globalAllow.filterNot { it == dom }
                                    )
                                    persistCatalog(next, "audit_global_block", "Prohibido: $dom")
                                }) { Text("🚫 Prohibir", color = redBad, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GlobalDomainBoxCard(
    title: String,
    hint: String,
    accentColor: Color,
    domains: List<String>,
    inputValue: String,
    onInputChange: (String) -> Unit,
    searchValue: String,
    onSearchChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onImportFile: () -> Unit,
    onExportFile: () -> Unit
) {
    val navyMedium = Color(0xFF1A222C)
    val q = searchValue.trim().lowercase(Locale.ROOT)
    val visible = remember(domains, q) {
        if (q.isEmpty()) domains else domains.filter { it.contains(q) }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = navyMedium),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(hint, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onInputChange,
                    placeholder = { Text("ej: api.ejemplo.com, otro.com", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color(0xFF0E141B))
                ) { Text("+", fontWeight = FontWeight.Bold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) {
                    Text("📂 Subir .txt/.docx", color = Color.White, fontSize = 11.sp)
                }
                OutlinedButton(onClick = onExportFile, modifier = Modifier.weight(1f)) {
                    Text("⬇️ Exportar .txt", color = Color.White, fontSize = 11.sp)
                }
            }
            OutlinedTextField(
                value = searchValue,
                onValueChange = onSearchChange,
                placeholder = { Text("Buscar en esta lista...", color = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                visible.take(150).forEach { dom ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF0E141B), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(dom, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "✕",
                            color = Color(0xFFF87171),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onRemove(dom) }.padding(horizontal = 6.dp)
                        )
                    }
                }
                if (visible.size > 150) {
                    Text("…y ${visible.size - 150} más (filtrá para buscar).", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AppWhitelistEditorDialog(
    initial: WhitelistAppEntry,
    isNew: Boolean,
    installedApps: List<com.ejemplo.locksuite.mdm.AppInfoData>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (WhitelistAppEntry, List<String>) -> Unit
) {
    val navyMedium = Color(0xFF1A222C)
    val accentOrange = Color(0xFFF5C438)
    var label by remember { mutableStateOf(initial.label) }
    var pkg by remember { mutableStateOf(initial.packageName) }
    var enabled by remember { mutableStateOf(initial.status == WhitelistCatalogIo.STATUS_ENABLED) }
    var noWeb by remember { mutableStateOf(initial.access == WhitelistCatalogIo.ACCESS_NOWEB) }
    var allowText by remember { mutableStateOf(initial.allow.joinToString("\n")) }
    var blockText by remember { mutableStateOf(initial.block.joinToString("\n")) }
    var errorMsg by remember { mutableStateOf("") }
    var pickInstalledMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = navyMedium,
        title = {
            Text(
                if (isNew) "Nueva App en Lista blanca" else "Editar ${initial.label}",
                color = accentOrange,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isNew && installedApps.isNotEmpty()) {
                    OutlinedButton(onClick = { pickInstalledMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("📱 Elegir app instalada en este equipo", color = Color.White, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = pickInstalledMenu,
                        onDismissRequest = { pickInstalledMenu = false },
                        modifier = Modifier.background(navyMedium).heightIn(max = 280.dp)
                    ) {
                        installedApps.take(80).forEach { ia ->
                            DropdownMenuItem(
                                text = { Text("${ia.label} (${ia.packageName})", color = Color.White, fontSize = 12.sp) },
                                onClick = {
                                    pickInstalledMenu = false
                                    pkg = ia.packageName
                                    if (label.isBlank()) label = ia.label
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Nombre", color = Color.LightGray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentOrange, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    enabled = isNew,
                    label = { Text("Package Name (ej: com.waze)", color = Color.LightGray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentOrange, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it }, colors = CheckboxDefaults.colors(checkedColor = accentOrange))
                    Text("Habilitada (dominios activos en Lista blanca)", color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = noWeb, onCheckedChange = { noWeb = it }, colors = CheckboxDefaults.colors(checkedColor = accentOrange))
                    Text("🚫 Sin web (bloquear WebView interno)", color = Color.White, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = allowText,
                    onValueChange = { allowText = it },
                    label = { Text("✅ Dominios permitidos (1 por línea)", color = Color(0xFF34D399)) },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentOrange, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = blockText,
                    onValueChange = { blockText = it },
                    label = { Text("🚫 Dominios prohibidos (1 por línea)", color = Color(0xFFF87171)) },
                    minLines = 2,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentOrange, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = Color(0xFFF87171), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("🗑️ Quitar app del catálogo", color = Color(0xFFF87171), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanPkg = pkg.trim()
                    if (!Regex("^[a-zA-Z0-9_.]+$").matches(cleanPkg) || '.' !in cleanPkg) {
                        errorMsg = "Ingresá un Package Name válido (ej: com.waze)."
                        return@Button
                    }
                    val allowRes = WhitelistCatalogIo.parseDomainListText(allowText, isAllow = true)
                    val blockRes = WhitelistCatalogIo.parseDomainListText(blockText, isAllow = false)
                    val entry = WhitelistAppEntry(
                        packageName = cleanPkg,
                        label = label.trim().ifEmpty { cleanPkg },
                        status = if (enabled) WhitelistCatalogIo.STATUS_ENABLED else WhitelistCatalogIo.STATUS_DISABLED,
                        access = if (noWeb) WhitelistCatalogIo.ACCESS_NOWEB else WhitelistCatalogIo.ACCESS_DOMAINS,
                        allow = allowRes.domains,
                        block = blockRes.domains
                    )
                    onSave(entry, allowRes.rejected.map { "${it.first} (${it.second})" })
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0E141B))
            ) { Text("Guardar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.LightGray) }
        }
    )
}

@Composable
private fun WhitelistBackupsDialog(
    backupStore: WhitelistBackupStore,
    currentCatalog: DeviceWhitelistCatalog,
    onDismiss: () -> Unit,
    onRestore: (DeviceWhitelistCatalog, WhitelistBackupStore.BackupMeta) -> Unit,
    onToast: (String) -> Unit
) {
    val navyMedium = Color(0xFF1A222C)
    val accentOrange = Color(0xFFF5C438)
    var backups by remember { mutableStateOf(backupStore.list()) }
    var onlyFavs by remember { mutableStateOf(false) }
    var noteEditing by remember { mutableStateOf<WhitelistBackupStore.BackupMeta?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    val sdf = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }

    if (noteEditing != null) {
        AlertDialog(
            onDismissRequest = { noteEditing = null },
            containerColor = navyMedium,
            title = { Text("Nota del respaldo", color = accentOrange, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = { Text("ej: Antes de agregar banco...", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentOrange, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val m = noteEditing!!
                        backupStore.setNote(m.id, noteDraft)
                        backups = backupStore.list()
                        noteEditing = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0E141B))
                ) { Text("Guardar", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { noteEditing = null }) { Text("Cancelar", color = Color.LightGray) } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = navyMedium,
        title = { Text("🗂️ Respaldos locales (30 días)", color = accentOrange, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            backupStore.saveSnapshot(currentCatalog, reason = "manual")
                            backups = backupStore.list()
                            onToast("Copia guardada.")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0E141B))
                    ) { Text("💾 Guardar ahora", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onlyFavs = !onlyFavs }) {
                        Checkbox(checked = onlyFavs, onCheckedChange = { onlyFavs = it }, colors = CheckboxDefaults.colors(checkedColor = accentOrange))
                        Text("⭐ Favs", color = Color.White, fontSize = 12.sp)
                    }
                }
                val visible = if (onlyFavs) backups.filter { it.favorite } else backups
                if (visible.isEmpty()) {
                    Text("Todavía no hay copias guardadas.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                } else {
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visible.forEach { m ->
                            Column(
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF0E141B), RoundedCornerShape(10.dp)).padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "${sdf.format(Date(m.createdAt))} · ${m.reason}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "${m.appCount} apps · +${m.globalAllow}/-${m.globalBlock} globales",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                                if (m.note.isNotBlank()) {
                                    Text("📝 ${m.note}", color = accentOrange, fontSize = 11.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        backupStore.setFavorite(m.id, !m.favorite)
                                        backups = backupStore.list()
                                    }) { Text(if (m.favorite) "⭐" else "☆", fontSize = 14.sp) }
                                    TextButton(onClick = {
                                        noteDraft = m.note
                                        noteEditing = m
                                    }) { Text("📝", fontSize = 12.sp) }
                                    TextButton(onClick = {
                                        val raw = backupStore.readText(m.id) ?: ""
                                        val parsed = WhitelistCatalogIo.parseCatalogText(raw)
                                        if (parsed.ok && parsed.catalog != null) onRestore(parsed.catalog, m)
                                        else onToast("Respaldo ilegible")
                                    }) { Text("♻️ Restaurar", color = Color(0xFF34D399), fontSize = 11.sp) }
                                    TextButton(onClick = {
                                        backupStore.delete(m.id)
                                        backups = backupStore.list()
                                    }) { Text("🗑️", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar", color = accentOrange, fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun AuditPickAppDialog(
    domain: String,
    apps: List<WhitelistAppEntry>,
    onDismiss: () -> Unit,
    onSelectApp: (WhitelistAppEntry) -> Unit
) {
    val navyMedium = Color(0xFF1A222C)
    val accentOrange = Color(0xFFF5C438)
    var q by remember { mutableStateOf("") }
    val filtered = apps.filter {
        q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
    }.sortedBy { it.label.lowercase(Locale.ROOT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = navyMedium,
        title = { Text("Agregar a app: $domain", color = accentOrange, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    placeholder = { Text("Buscar app...", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentOrange, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    filtered.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(0xFF0E141B), RoundedCornerShape(8.dp))
                                .clickable { onSelectApp(app) }
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(app.packageName, color = Color.White.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.LightGray) } }
    )
}
