package com.emergencycall.bypass.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.widget.Toast
import com.emergencycall.bypass.data.model.AlertSource
import com.emergencycall.bypass.data.model.EmergencyAlert
import com.emergencycall.bypass.data.model.EmergencyContact
import com.emergencycall.bypass.ui.theme.*
import com.emergencycall.bypass.util.AutoStartHelper
import com.emergencycall.bypass.util.ContactPickerHelper
import com.emergencycall.bypass.util.PermissionHelper
import java.text.SimpleDateFormat
import java.util.*

enum class ContactDialogTarget {
    RECEIVER,
    SENDER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Listas de contatos separadas
    val receiverContacts by viewModel.receiverContacts.collectAsState()
    val senderContacts by viewModel.senderContacts.collectAsState()

    val alerts by viewModel.alerts.collectAsState()
    val isMonitoringActive by viewModel.isMonitoringActive.collectAsState()
    val permissionsStatus by viewModel.permissionsStatus.collectAsState()

    // Estados do Modo Emissor de SOS
    val isSosRunning by viewModel.isSosRunning.collectAsState()
    val lastMapsUrl by viewModel.lastMapsUrl.collectAsState()
    val lastCoordinates by viewModel.lastCoordinates.collectAsState()
    val totalSmsSent by viewModel.totalSmsSent.collectAsState()
    val secondsToNextSms by viewModel.secondsToNextSms.collectAsState()
    val sosCountdown by viewModel.sosCountdown.collectAsState()

    // Configuração de Toque
    val selectedRingtoneTitle by viewModel.selectedRingtoneTitle.collectAsState()
    val selectedRingtoneUri by viewModel.selectedRingtoneUri.collectAsState()

    // Simulação / Testes
    val simulationCountdown by viewModel.simulationCountdown.collectAsState()
    val simulationType by viewModel.simulationType.collectAsState()

    // Auto-Reinício se for fechado
    val isAutoRestartEnabled by viewModel.isAutoRestartEnabled.collectAsState()

    // 0 = ENVIAR SOS | 1 = RECEBER | 2 = DEMONSTRAÇÃO
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var dialogTarget by remember { mutableStateOf(ContactDialogTarget.RECEIVER) }
    var showSmsHelpDialog by remember { mutableStateOf(false) }
    var showBackgroundSettingsDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }

    // Diálogo de Mapa Embutido no App
    var mapDialogCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapDialogTitle by remember { mutableStateOf("") }

    // Atualiza permissões sempre que o app volta para o primeiro plano
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.refreshPermissions()
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            showSmsHelpDialog = true
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }

    // Seletor de Contatos da Agenda do Aparelho
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        contactUri?.let { uri ->
            val info = ContactPickerHelper.extractContactInfo(context, uri)
            if (info != null) {
                newName = info.first
                newPhone = info.second
            }
        }
    }

    // Seletor do Toque Oficial do Sistema Android
    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                try {
                    val ringtone = RingtoneManager.getRingtone(context, uri)
                    val title = ringtone?.getTitle(context) ?: "Toque Personalizado"
                    viewModel.setSelectedRingtone(uri.toString(), title)
                } catch (e: Exception) {
                    viewModel.setSelectedRingtone(uri.toString(), "Toque Personalizado")
                }
            } else {
                viewModel.resetDefaultRingtone()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Central de Emergência SOS", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { showBackgroundSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Configurações de Auto-Início e Segundo Plano",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1B0505),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F0F0F))
        ) {
            // ==================== AS TRÊS GRANDES ABAS NA TELA PRINCIPAL ====================
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // ABA 0: ENVIAR SOS (Destaque Visual Máximo)
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selectedTab == 0) {
                                    Brush.horizontalGradient(listOf(Color(0xFFD50000), Color(0xFFFF1744)))
                                } else {
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF2A2A2A)))
                                }
                            )
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isSosRunning) Icons.Default.Warning else Icons.Default.Sos,
                                contentDescription = null,
                                tint = if (selectedTab == 0) Color.White else Color.Gray,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = "ENVIAR SOS",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = if (selectedTab == 0) Color.White else Color.LightGray
                                )
                                Text(
                                    text = if (isSosRunning) "🚨 10s ATIVO" else if (sosCountdown != null) "⏳ ${sosCountdown}s" else "A cada 10s",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectedTab == 0) Color(0xFFFFEB3B) else Color.Gray
                                )
                            }
                        }
                    }

                    // ABA 1: RECEBER (Receptor Não Perturbe)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selectedTab == 1) {
                                    Brush.horizontalGradient(listOf(Color(0xFF1565C0), Color(0xFF1E88E5)))
                                } else {
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF2A2A2A)))
                                }
                            )
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CallReceived,
                                contentDescription = null,
                                tint = if (selectedTab == 1) Color.White else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = "RECEBER",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = if (selectedTab == 1) Color.White else Color.LightGray
                                )
                                Text(
                                    text = if (isMonitoringActive) "Furar DND" else "Pausado",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectedTab == 1) Color(0xFF80D8FF) else Color.Gray
                                )
                            }
                        }
                    }

                    // ABA 2: DEMONSTRAÇÃO (Testes e Demonstrações ao Vivo)
                    Box(
                        modifier = Modifier
                            .weight(1.05f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selectedTab == 2) {
                                    Brush.horizontalGradient(listOf(Color(0xFF6A1B9A), Color(0xFF8E24AA)))
                                } else {
                                    Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF2A2A2A)))
                                }
                            )
                            .clickable { selectedTab = 2 }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Explore,
                                contentDescription = null,
                                tint = if (selectedTab == 2) Color.White else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = "DEMONSTRAÇÃO",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    color = if (selectedTab == 2) Color.White else Color.LightGray
                                )
                                Text(
                                    text = "Testes & Mapa",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectedTab == 2) Color(0xFFE1BEE7) else Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // ==================== CORPO DA TELA ====================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Banner de Simulação em Contagem Regressiva (se ativo)
                if (simulationCountdown != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = RedDark),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SIMULANDO $simulationType EM ${simulationCountdown}s",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Bloqueie a tela e ative o 'Não Perturbe' agora para testar o alarme!",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD54F),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(onClick = { viewModel.cancelSimulation() }) {
                                Text("Cancelar Teste", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                when (selectedTab) {
                    0 -> {
                        // ==========================================
                        // ABA 0: ENVIAR SOS (COM CONTAGEM E LOOP DE 10s)
                        // ==========================================
                        SosHeroSection(
                            isSosRunning = isSosRunning,
                            sosCountdown = sosCountdown,
                            totalSmsSent = totalSmsSent,
                            secondsToNextSms = secondsToNextSms,
                            lastMapsUrl = lastMapsUrl,
                            lastCoordinates = lastCoordinates,
                            contactsCount = senderContacts.filter { it.isEnabled }.size,
                            hasGpsPermission = permissionsStatus.hasLocationPermissions,
                            hasSmsPermission = permissionsStatus.hasSendSmsPermission,
                            onRequestGps = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            onRequestSms = {
                                smsPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.SEND_SMS,
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_SMS
                                    )
                                )
                            },
                            onOpenSettings = {
                                context.startActivity(PermissionHelper.getAppSettingsIntent(context))
                            },
                            onTriggerSos = { viewModel.triggerSosButtonClick() },
                            onCancelCountdown = { viewModel.cancelSosCountdown() },
                            onStopSos = { viewModel.stopEmergencySos() },
                            onViewInAppMap = { lat, lng, title ->
                                mapDialogCoords = Pair(lat, lng)
                                mapDialogTitle = title
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // LISTA SEPARADA: CONTATOS QUE RECEBERÃO O MEU SOS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Contatos que Receberão meu SOS (${senderContacts.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Receberão SMS a cada 10s com sua localização e ligação viva-voz",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFD54F),
                                    fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                dialogTarget = ContactDialogTarget.SENDER
                                showAddDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Adicionar", fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (senderContacts.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Nenhum contato cadastrado para envio de SOS",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = "Adicione quem deve receber suas coordenadas por SMS e sua ligação quando você acionar o SOS.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        senderContacts.forEach { contact ->
                            SimpleContactItemCard(
                                contact = contact,
                                onToggle = { viewModel.toggleSenderContact(contact.id) },
                                onDelete = { viewModel.removeSenderContact(contact.id) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    }
                    1 -> {
                        // ==========================================
                        // ABA 1: RECEBER (RECEPTOR NÃO PERTURBE)
                        // ==========================================

                    // Switch Mestre do Modo Receptor com status de segundo plano
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMonitoringActive) Color(0xFF1E281E) else Color(0xFF262020)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            if (isMonitoringActive) StatusGreen else Color.Gray,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isMonitoringActive) "Receptor Ativo em Segundo Plano" else "Receptor Pausado",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (isMonitoringActive)
                                            "Mantém serviço persistente 24/7 para não fechar e furar o Não Perturbe"
                                        else
                                            "Monitoramento desligado",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Switch(
                                checked = isMonitoringActive,
                                onCheckedChange = { viewModel.toggleMonitoring(it) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ========================================================
                    // PAINEL DE AUTO-INÍCIO E PROTEÇÃO 24/7 (ANTI-ENCERRAMENTO)
                    // ========================================================
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF142018)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF2E7D32).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        Icons.Default.PowerSettingsNew,
                                        contentDescription = null,
                                        tint = Color(0xFF66BB6A),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Auto-Reinício se Fechado",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Reinicia sozinho se você fechar o app nos recentes",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFA5D6A7),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Switch(
                                    checked = isAutoRestartEnabled,
                                    onCheckedChange = { viewModel.toggleAutoRestart(it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Status da Bateria (Irrestrito)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0D1610))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        if (permissionsStatus.isIgnoringBattery) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (permissionsStatus.isIgnoringBattery) Color(0xFF66BB6A) else Color(0xFFFFB74D),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (permissionsStatus.isIgnoringBattery) "Bateria: Sem Restrições (Irrestrito)" else "Bateria: Otimizada (Pode suspender)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (permissionsStatus.isIgnoringBattery) Color(0xFF81C784) else Color(0xFFFFCC80)
                                        )
                                        Text(
                                            text = if (permissionsStatus.isIgnoringBattery) "Protegido contra fechamento pelo Android" else "O Android pode matar o app em segundo plano",
                                            fontSize = 10.sp,
                                            color = Color.LightGray
                                        )
                                    }
                                }

                                if (!permissionsStatus.isIgnoringBattery) {
                                    Button(
                                        onClick = {
                                            try {
                                                context.startActivity(PermissionHelper.getBatteryOptimizationSettingsIntent(context))
                                            } catch (e: Exception) {
                                                try {
                                                    context.startActivity(PermissionHelper.getAppSettingsIntent(context))
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Liberar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Botões de Ação
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        AutoStartHelper.openAutoStartSettings(context)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF66BB6A))
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF81C784))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Autostart (${AutoStartHelper.deviceBrand})", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        showBackgroundSettingsDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) {
                                    Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Como Configurar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ========================================================
                    // PAINEL DE TESTES, SIMULAÇÃO E ESCOLHA DE TOQUE DO ALARME
                    // ========================================================
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2430)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF2196F3).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF42A5F5),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Painel de Testes & Simulação de Chamadas",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Verifique o toque a 50%, anúncio por voz e bypass do DND",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF90CAF9),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Color(0xFF2C394F))
                            Spacer(modifier = Modifier.height(12.dp))

                            // Seletor de Toque do Alarme
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Toque do Alarme:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = selectedRingtoneTitle,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFD54F),
                                        fontSize = 13.sp
                                    )
                                }

                                Row {
                                    Button(
                                        onClick = {
                                            val currentUri = selectedRingtoneUri?.let { Uri.parse(it) }
                                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

                                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_ALARM)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Escolha o Toque do Alarme")
                                            }
                                            ringtonePickerLauncher.launch(intent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Escolher Toque", fontSize = 11.sp)
                                    }

                                    if (selectedRingtoneUri != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = { viewModel.resetDefaultRingtone() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text("Padrão", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Botões de Teste
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.testRingtoneNow(receiverContacts.firstOrNull()) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Testar Toque Agora", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { viewModel.stopTestRinger() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF546E7A)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Parar Som", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.simulateCallIn5s(receiverContacts.firstOrNull()) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Simular Chamada (em 5s)", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { viewModel.simulateSmsIn5s(receiverContacts.firstOrNull()) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Sms, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Simular SMS (em 5s)", fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "💡 Dica: Ao tocar em 'Simular em 5s', bloqueie a tela do celular e ative o 'Não Perturbe' para validar que a tela acende, fura o silêncio a 50% de volume e fala o nome do contato.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0BEC5),
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Permissões do Sistema
                    Text(
                        text = "Permissões de Recepção",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 1. Acesso ao Não Perturbe (DND)
                    PermissionRow(
                        title = "Acesso ao Não Perturbe (DND)",
                        description = "Necessário para furar o silêncio e tocar em volume máximo",
                        isGranted = permissionsStatus.hasDndAccess,
                        actionLabel = "Autorizar DND",
                        onAction = { context.startActivity(PermissionHelper.getDndSettingsIntent()) }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // 2. Leitura e Envio de SMS
                    PermissionRow(
                        title = "Ler e Enviar SMS de Emergência",
                        description = "Detecta SMS com mapa e envia suas coordenadas",
                        isGranted = permissionsStatus.hasSmsPermissions && permissionsStatus.hasSendSmsPermission,
                        actionLabel = "Permitir SMS",
                        onAction = {
                            smsPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECEIVE_SMS,
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.SEND_SMS
                                )
                            )
                        },
                        secondaryActionLabel = "Configurações",
                        onSecondaryAction = {
                            context.startActivity(PermissionHelper.getAppSettingsIntent(context))
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // 3. Chamadas Telefônicas & Agenda
                    PermissionRow(
                        title = "Identificar Chamadas & Agenda",
                        description = "Identifica número do chamador e busca contatos da agenda",
                        isGranted = permissionsStatus.hasPhonePermissions && permissionsStatus.hasReadContactsPermission,
                        actionLabel = "Permitir",
                        onAction = {
                            phonePermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.READ_CONTACTS
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // 4. Otimização de Bateria (Sem restrições para não fechar)
                    PermissionRow(
                        title = "Desativar Otimização de Bateria",
                        description = "Evita que o Android suspenda o aplicativo em segundo plano",
                        isGranted = permissionsStatus.isIgnoringBattery,
                        actionLabel = "Sem Restrições",
                        onAction = {
                            try {
                                context.startActivity(PermissionHelper.getBatteryOptimizationSettingsIntent(context))
                            } catch (e: Exception) {
                                context.startActivity(PermissionHelper.getAppSettingsIntent(context))
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Destaque das Novas Funcionalidades: Volume 50% e Fala do Nome
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF142414)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VolumeDown, contentDescription = null, tint = Color(0xFF69F0AE), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Volume Controlado (50%)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                            }
                            Text(
                                text = "Ao furar o Não Perturbe, o som e o aviso sonoro tocam a 50% do volume para conforto auditivo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = Color(0xFF69F0AE), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Anúncio do Contato por Voz", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                            }
                            Text(
                                text = "O celular fala repetidamente 'Ligação de [Nome]' até que você atenda ou encerre a chamada.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // LISTA SEPARADA: CONTATOS AUTORIZADOS A FURAR MEU NÃO PERTURBE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Contatos VIP para Receber (${receiverContacts.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Quem estiver nesta lista poderá furar seu Não Perturbe",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF80D8FF),
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                dialogTarget = ContactDialogTarget.RECEIVER
                                showAddDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Adicionar", fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (receiverContacts.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Nenhum contato autorizado a receber",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Adicione pessoas para que possam furar seu modo Não Perturbe por chamada ou SMS.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        receiverContacts.forEach { contact ->
                            ContactItemCard(
                                contact = contact,
                                onToggle = { viewModel.toggleReceiverContact(contact.id) },
                                onDelete = { viewModel.removeReceiverContact(contact.id) },
                                onTestCall = { viewModel.simulateEmergencyCall(contact) },
                                onTestSms = { viewModel.simulateEmergencySms(contact) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Histórico de Alertas Recebidos com Mapa
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Histórico de Alertas Recebidos (${alerts.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        if (alerts.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearAlerts() }) {
                                Text("Limpar", color = Color(0xFFFF8A80), fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (alerts.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Nenhum alerta recebido ainda.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    } else {
                        alerts.forEach { alert ->
                            AlertItemCard(alert = alert, onOpenMap = { mapsUrl ->
                                try {
                                    val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(mapIntent)
                                } catch (e: Exception) {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(webIntent)
                                }
                            }, onViewInAppMap = { lat, lng, title ->
                                mapDialogCoords = Pair(lat, lng)
                                mapDialogTitle = title
                            })
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
                2 -> {
                    // ==========================================
                    // ABA 2: DEMONSTRAÇÃO EXCLUSIVA E MAPA
                    // ==========================================
                    DemonstrationTab(
                        viewModel = viewModel,
                        onOpenMapDialog = { lat, lng, title ->
                            mapDialogCoords = Pair(lat, lng)
                            mapDialogTitle = title
                        }
                    )
                }
            }
        }
    }
}

    // Diálogo de Mapa Embutido no App
    if (mapDialogCoords != null) {
        EmbeddedMapDialog(
            latitude = mapDialogCoords!!.first,
            longitude = mapDialogCoords!!.second,
            title = mapDialogTitle,
            onDismiss = { mapDialogCoords = null }
        )
    }

    // Diálogo de Orientação caso o Android tenha bloqueado o SMS
    if (showSmsHelpDialog) {
        AlertDialog(
            onDismissRequest = { showSmsHelpDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Permissão de SMS Bloqueada")
                }
            },
            text = {
                Text(
                    text = "O sistema Android bloqueou a concessão direta de SMS por motivos de segurança.\n\n" +
                            "Para que o app consiga ler alertas de emergência e enviar sua localização no SOS:\n\n" +
                            "1. Toque em 'Abrir Configurações' abaixo.\n" +
                            "2. Toque em 'Permissões'.\n" +
                            "3. Selecione 'SMS' e marque 'Permitir'.\n" +
                            "4. Se houver opção 'Permitir configurações restritas' nos 3 pontinhos do topo, autorize.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSmsHelpDialog = false
                        context.startActivity(PermissionHelper.getAppSettingsIntent(context))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedPrimary)
                ) {
                    Text("Abrir Configurações")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSmsHelpDialog = false }) {
                    Text("Entendi")
                }
            }
        )
    }

    // Diálogo de Orientação e Configurações de Auto-Início e Segundo Plano
    if (showBackgroundSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color(0xFF66BB6A))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto-Início e Segundo Plano", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    // Switch de Ativação
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E281E))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Reinício se Fechado", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            Text("O serviço acorda sozinho se o app for fechado nos recentes.", fontSize = 11.sp, color = Color(0xFFA5D6A7))
                        }
                        Switch(
                            checked = isAutoRestartEnabled,
                            onCheckedChange = { viewModel.toggleAutoRestart(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Para que seu celular NUNCA encerre o aplicativo e ele funcione 24 horas por dia, siga estes 3 passos simples:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Passo 1: Cadeado nos Recentes
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Passo 1: Travar com Cadeado nos Recentes", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Abra a tela de multitarefa (aplicativos recentes), segure o dedo sobre a janela deste aplicativo e toque no ícone do Cadeado 🔒 para travá-lo. Isso impede que o botão 'Limpar Tudo' feche o app.",
                                fontSize = 11.sp,
                                color = Color(0xFFE0E0E0)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Passo 2: Início Automático (Autostart do Fabricante)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF42A5F5), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Passo 2: Início Automático (${AutoStartHelper.deviceBrand})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Em celulares ${AutoStartHelper.deviceBrand}, ative a permissão 'Início Automático' para que o monitor inicie sempre que o celular ligar ou for reiniciado.",
                                fontSize = 11.sp,
                                color = Color(0xFFE0E0E0)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    AutoStartHelper.openAutoStartSettings(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Text("Abrir Início Automático (${AutoStartHelper.deviceBrand})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Passo 3: Bateria Irrestrita
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF212121)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (permissionsStatus.isIgnoringBattery) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (permissionsStatus.isIgnoringBattery) Color(0xFF66BB6A) else Color(0xFFFFB74D),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Passo 3: Bateria Sem Restrições",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (permissionsStatus.isIgnoringBattery)
                                    "✅ Seu celular já está configurado como 'Sem Restrições'. O Android não suspenderá este app!"
                                else
                                    "Otimização de bateria ativa! O Android pode suspender o app com a tela apagada. Toque abaixo e escolha 'Sem restrições'.",
                                fontSize = 11.sp,
                                color = if (permissionsStatus.isIgnoringBattery) Color(0xFF81C784) else Color(0xFFFFCC80)
                            )
                            if (!permissionsStatus.isIgnoringBattery) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        try {
                                            context.startActivity(PermissionHelper.getBatteryOptimizationSettingsIntent(context))
                                        } catch (e: Exception) {
                                            try {
                                                context.startActivity(PermissionHelper.getAppSettingsIntent(context))
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) {
                                    Text("Configurar Bateria sem Restrições", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Teste do Watchdog de Auto-Reinício
                    OutlinedButton(
                        onClick = {
                            viewModel.testAutoRestartWatchdog()
                            Toast.makeText(context, "Watchdog testado! O serviço foi mantido vivo.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF66BB6A))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Testar Auto-Reinício Agora", fontSize = 12.sp, color = Color(0xFFA5D6A7), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showBackgroundSettingsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Concluir")
                }
            }
        )
    }

    // Diálogo de Adicionar Contato (Agenda ou Digitação)
    if (showAddDialog) {
        // Telefone precisa ter ao menos 8 dígitos e o nome não pode ser vazio
        val phoneDigits = newPhone.count { it.isDigit() }
        val isPhoneInvalid = newPhone.isNotBlank() && phoneDigits < 8
        val canSave = newName.isNotBlank() && phoneDigits >= 8

        val targetTitle = if (dialogTarget == ContactDialogTarget.SENDER) {
            "Novo Contato para Enviar SOS"
        } else {
            "Novo Contato VIP para Receber"
        }

        val targetExplanation = if (dialogTarget == ContactDialogTarget.SENDER) {
            "Este contato receberá seus SMS com localização GPS e sua ligação viva-voz quando você acionar o botão SOS."
        } else {
            "As ligações e SMS deste contato farão seu aparelho tocar com 50% de volume furando o Não Perturbe e falando o nome dele."
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(targetTitle) },
            text = {
                Column {
                    Text(
                        text = targetExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { contactPickerLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buscar da Agenda do Aparelho")
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nome do Contato (ex: Mãe)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPhone,
                        onValueChange = { newPhone = it },
                        label = { Text("Telefone com DDD (ex: 11988887777)") },
                        singleLine = true,
                        isError = isPhoneInvalid,
                        supportingText = {
                            Text(
                                text = if (isPhoneInvalid) "Informe DDD + número (mínimo 8 dígitos)"
                                       else "Use o mesmo número que enviará o SMS",
                                fontSize = 11.sp
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        if (canSave) {
                            if (dialogTarget == ContactDialogTarget.SENDER) {
                                viewModel.addSenderContact(newName, newPhone)
                            } else {
                                viewModel.addReceiverContact(newName, newPhone)
                            }
                            newName = ""
                            newPhone = ""
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedPrimary)
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * SEÇÃO COM SUPER DESTAQUE VISUAL PARA O BOTÃO DE ENVIAR SOS
 * COM CONTAGEM REGRESSIVA DE 10 SEGUNDOS SE NÃO FOR INTERROMPIDO
 */
@Composable
fun SosHeroSection(
    isSosRunning: Boolean,
    sosCountdown: Int?,
    totalSmsSent: Int,
    secondsToNextSms: Int,
    lastMapsUrl: String?,
    lastCoordinates: Pair<Double, Double>? = null,
    contactsCount: Int,
    hasGpsPermission: Boolean,
    hasSmsPermission: Boolean,
    onRequestGps: () -> Unit,
    onRequestSms: () -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerSos: () -> Unit,
    onCancelCountdown: () -> Unit,
    onStopSos: () -> Unit,
    onViewInAppMap: ((Double, Double, String) -> Unit)? = null
) {
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulseHero")
    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSosRunning || sosCountdown != null) 1.35f else 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosRunning || sosCountdown != null) 450 else 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "p1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSosRunning || sosCountdown != null) 1.2f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosRunning || sosCountdown != null) 550 else 1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "p2"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSosRunning) Color(0xFF2E0505) else if (sosCountdown != null) Color(0xFF2E1B05) else Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = if (isSosRunning) Color.Red else if (sosCountdown != null) Color(0xFFFF9800) else Color(0xFF333333),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isSosRunning) "🚨 SOS EM TRANSMISSÃO ATIVA"
                       else if (sosCountdown != null) "⚠️ INICIANDO SOS EM ${sosCountdown}s"
                       else "DISPARADOR DE SOS",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = if (isSosRunning) Color.Red else if (sosCountdown != null) Color(0xFFFF9800) else Color.White,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = if (isSosRunning) "Localização sendo enviada continuamente"
                       else if (sosCountdown != null) "Toque no botão para ABORTAR se foi por engano!"
                       else "Contagem de 10s para segurança contra toques acidentais",
                fontSize = 12.sp,
                color = if (sosCountdown != null) Color(0xFFFFD54F) else TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(26.dp))

            // ==================== O BOTÃO GIGANTE DE SOS COM EFEITO MULTI-ANEL ====================
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(220.dp)
            ) {
                // Anel Externo 1
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(pulse1)
                        .background(
                            if (isSosRunning) Color(0xFFFF1744).copy(alpha = 0.25f)
                            else if (sosCountdown != null) Color(0xFFFF9800).copy(alpha = 0.3f)
                            else Color(0xFFD50000).copy(alpha = 0.15f),
                            CircleShape
                        )
                )

                // Anel Médio 2
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .scale(pulse2)
                        .background(
                            if (isSosRunning) Color(0xFFFF1744).copy(alpha = 0.45f)
                            else if (sosCountdown != null) Color(0xFFFF9800).copy(alpha = 0.5f)
                            else Color(0xFFD50000).copy(alpha = 0.3f),
                            CircleShape
                        )
                )

                // Botão Central de Impacto
                Button(
                    onClick = {
                        if (sosCountdown != null) {
                            onCancelCountdown()
                        } else if (isSosRunning) {
                            onStopSos()
                        } else {
                            onTriggerSos()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSosRunning) Color(0xFFB71C1C)
                                         else if (sosCountdown != null) Color(0xFFE65100)
                                         else Color(0xFFD50000)
                    ),
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 14.dp, pressedElevation = 6.dp),
                    modifier = Modifier
                        .size(140.dp)
                        .shadow(16.dp, CircleShape)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (sosCountdown != null) {
                            Text(
                                text = "$sosCountdown",
                                fontWeight = FontWeight.Black,
                                fontSize = 44.sp,
                                color = Color.White
                            )
                            Text(
                                text = "CANCELAR",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFFFFEB3B)
                            )
                        } else {
                            Icon(
                                imageVector = if (isSosRunning) Icons.Default.Stop else Icons.Default.Warning,
                                contentDescription = "SOS",
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isSosRunning) "PARAR" else "SOS",
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp,
                                letterSpacing = 2.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Se estiver em contagem regressiva de 10s, mostra botão bem visível de ABORTAR
            if (sosCountdown != null) {
                Button(
                    onClick = onCancelCountdown,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CANCELAR SOS AGORA",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (isSosRunning) {
                // Painel de Status Ativo
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1111)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total de SMS enviados:", color = TextSecondary, fontSize = 14.sp)
                            Text("$totalSmsSent", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Próximo envio em:", color = TextSecondary, fontSize = 14.sp)
                            Text("${secondsToNextSms}s", fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F), fontSize = 16.sp)
                        }
                    }
                }

                if (lastCoordinates != null || !lastMapsUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (lastCoordinates != null && onViewInAppMap != null) {
                            Button(
                                onClick = {
                                    onViewInAppMap(lastCoordinates.first, lastCoordinates.second, "Minha Localização de SOS")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ver no App", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        if (!lastMapsUrl.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lastMapsUrl))
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(if (lastCoordinates != null && onViewInAppMap != null) 1.2f else 1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Google Maps", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onStopSos,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CANCELAR SOS / ESTOU SEGURO", fontWeight = FontWeight.Black, fontSize = 15.sp)
                }

            } else if (sosCountdown == null) {
                // Permissões de Disparo (se faltar GPS ou SMS)
                if (!hasGpsPermission || !hasSmsPermission) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1919)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Permissões Necessárias para Disparar:",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF8A80),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (!hasGpsPermission) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("GPS (Localização)", color = Color.White, fontSize = 13.sp)
                                    Button(onClick = onRequestGps, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                        Text("Permitir GPS", fontSize = 11.sp)
                                    }
                                }
                            }
                            if (!hasSmsPermission) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("SMS (Envio Automático)", color = Color.White, fontSize = 13.sp)
                                    Row {
                                        Button(onClick = onRequestSms, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                            Text("Permitir", fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        OutlinedButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                            Text("Config", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Como Funciona o Disparo SOS:",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• Ao tocar no botão SOS, inicia uma contagem regressiva de 10 segundos.\n" +
                                    "• Se você não cancelar durante os 10s, o envio real é iniciado automaticamente.\n" +
                                    "• Captura sua localização GPS em tempo real e envia SMS para $contactsCount contato(s) a cada 10 segundos continuamente.\n" +
                                    "• Inicia chamada telefônica em viva-voz para o 1º contato da lista de envio.\n" +
                                    "• A transmissão contínua segue até você tocar em PARAR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "⚠️ Este app é um apoio emergencial e não substitui os canais oficiais de socorro 190 (Polícia), 192 (SAMU) e 193 (Bombeiros).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SimpleContactItemCard(
    contact: EmergencyContact,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFD50000).copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = contact.phoneNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF8A80)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = contact.isEnabled,
                    onCheckedChange = { onToggle() }
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Excluir",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isGranted) StatusGreen else StatusAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!isGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(text = actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(text = secondaryActionLabel, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ativo",
                        color = StatusGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = secondaryActionLabel, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItemCard(
    contact: EmergencyContact,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onTestCall: () -> Unit,
    onTestSms: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(RedPrimary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = RedPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = contact.phoneNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF8A80)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = contact.isEnabled,
                        onCheckedChange = { onToggle() }
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Excluir",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C))
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Simular Alerta em 5s (teste com tela bloqueada e Não Perturbe):",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onTestCall,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Chamada", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onTestSms,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SMS Mapa", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun AlertItemCard(
    alert: EmergencyAlert,
    onOpenMap: (String) -> Unit,
    onViewInAppMap: ((Double, Double, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
    val timeFormatted = remember(alert.timestamp) { dateFormat.format(Date(alert.timestamp)) }

    val (badgeColor, badgeIcon) = when (alert.source) {
        AlertSource.SMS -> Pair(Color(0xFFFF9800), Icons.Default.Sms)
        AlertSource.WHATSAPP_CALL -> Pair(Color(0xFF25D366), Icons.Default.PhoneInTalk)
        AlertSource.WHATSAPP_MESSAGE -> Pair(Color(0xFF25D366), Icons.AutoMirrored.Filled.Chat)
        AlertSource.PHONE_CALL -> Pair(RedPrimary, Icons.Default.Call)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(badgeIcon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = alert.source.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }

                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${alert.senderName} (${alert.senderNumber})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (!alert.messageBody.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = alert.messageBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFEEEEEE)
                )
            }

            val effectiveMapsUrl = alert.mapsUrl?.takeIf { it.isNotBlank() }
                ?: com.emergencycall.bypass.util.LocationParser.extractMapsUrl(alert.messageBody)
            val coords = com.emergencycall.bypass.util.LocationParser.extractCoordinates(effectiveMapsUrl ?: alert.messageBody)

            if (!effectiveMapsUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF142B1A)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (coords != null) "📍 ${String.format(Locale.US, "%.5f, %.5f", coords.first, coords.second)}" else "Localização GPS Anexada",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA5D6A7)
                                )
                            }
                            if (coords != null) {
                                IconButton(
                                    onClick = {
                                        com.emergencycall.bypass.util.LocationParser.copyCoordinates(
                                            context,
                                            "${coords.first}, ${coords.second}"
                                        )
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copiar",
                                        tint = Color(0xFFA5D6A7),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (coords != null && onViewInAppMap != null) {
                                OutlinedButton(
                                    onClick = {
                                        onViewInAppMap(coords.first, coords.second, "Alerta de ${alert.senderName}")
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E676))
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("VER NO APP", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                                }
                            }

                            Button(
                                onClick = {
                                    com.emergencycall.bypass.util.LocationParser.openGoogleMaps(context, effectiveMapsUrl)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(if (coords != null && onViewInAppMap != null) 1.2f else 1f)
                                    .height(46.dp)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("GOOGLE MAPS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
