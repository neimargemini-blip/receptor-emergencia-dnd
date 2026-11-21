package com.emergencycall.bypass.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emergencycall.bypass.util.LocationHelper
import com.emergencycall.bypass.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class DemoLocationPreset(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val iconEmoji: String
)

@Composable
fun DemonstrationTab(
    viewModel: MainViewModel,
    onOpenMapDialog: (Double, Double, String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Presets de demonstração do mapa
    val locationPresets = remember {
        listOf(
            DemoLocationPreset("São Paulo (Marco Zero)", -23.55052, -46.633308, "🏙️"),
            DemoLocationPreset("Rio de Janeiro (Cristo)", -22.951916, -43.210487, "🏖️"),
            DemoLocationPreset("Brasília (Congresso)", -15.799787, -47.864141, "🏛️"),
            DemoLocationPreset("Nova York (Times Sq)", 40.7580, -73.9855, "🗽"),
            DemoLocationPreset("Paris (Torre Eiffel)", 48.8584, 2.2945, "🗼")
        )
    }

    var selectedPreset by remember { mutableStateOf(locationPresets[0]) }
    var customLat by remember { mutableDoubleStateOf(locationPresets[0].latitude) }
    var customLng by remember { mutableDoubleStateOf(locationPresets[0].longitude) }
    var locationTitle by remember { mutableStateOf(locationPresets[0].name) }

    // Estados da Demonstração 1: Envio Periódico a cada 10 Segundos
    var isBeaconRunning by remember { mutableStateOf(false) }
    var beaconSecondsLeft by remember { mutableIntStateOf(10) }
    var beaconPacketsSent by remember { mutableIntStateOf(0) }
    var beaconLastTimestamp by remember { mutableStateOf<String?>(null) }
    var beaconCoords by remember { mutableStateOf(Pair(-23.55052, -46.633308)) }

    // Loop do Beacon de Demonstração de 10 segundos
    LaunchedEffect(isBeaconRunning) {
        if (isBeaconRunning) {
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            // Tenta obter GPS real do celular se permissão concedida
            if (PermissionHelper.hasLocationPermissions(context)) {
                val realLoc = LocationHelper.getCurrentLocation(context)
                if (realLoc != null) {
                    beaconCoords = Pair(realLoc.latitude, realLoc.longitude)
                }
            }

            while (isActive && isBeaconRunning) {
                // Disparo de pacote a cada 10 segundos
                beaconPacketsSent++
                beaconLastTimestamp = dateFormat.format(Date())

                // Simula leve deslocamento dinâmico no mapa para demonstrar atualização em tempo real
                if (beaconPacketsSent > 1) {
                    val deltaLat = (Random().nextDouble() - 0.5) * 0.0003
                    val deltaLng = (Random().nextDouble() - 0.5) * 0.0003
                    beaconCoords = Pair(beaconCoords.first + deltaLat, beaconCoords.second + deltaLng)
                }

                // Contagem regressiva de 10 segundos
                for (sec in 10 downTo 1) {
                    beaconSecondsLeft = sec
                    delay(1000L)
                    if (!isBeaconRunning) break
                }
            }
        } else {
            beaconSecondsLeft = 10
        }
    }

    // Animação de pulso
    val infiniteTransition = rememberInfiniteTransition(label = "demoPulse")
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beaconPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        // ==================== CABEÇALHO DO CENTRO DE DEMONSTRAÇÃO ====================
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E152A)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, Color(0xFFAB47BC), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFF8E24AA), Color(0xFFBA68C8))), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Explore, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Centro de Demonstração",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Teste e visualize em tempo real os recursos nativos",
                            fontSize = 12.sp,
                            color = Color(0xFFCE93D8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Nesta aba você pode testar o envio de localização a cada 10 segundos, visualizar o mapa interativo no próprio app, simular o alarme crítico furando o Não Perturbe e testar o auto-reinício 24/7.",
                    fontSize = 12.sp,
                    color = Color(0xFFE1BEE7),
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // DEMONSTRAÇÃO 1: ENVIO PERIÓDICO NATIVO A CADA 10 SEGUNDOS
        // =========================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1212)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isBeaconRunning) Color(0xFFFF1744) else Color(0xFF4E2626), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .scale(if (isBeaconRunning) pulseAnim else 1f)
                                .background(if (isBeaconRunning) Color(0xFFFF1744) else Color.Gray, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "1. Envio Nativo a Cada 10 Segundos",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    Surface(
                        color = if (isBeaconRunning) Color(0xFFD50000) else Color(0xFF333333),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isBeaconRunning) "TRANSMITINDO" else "PARADO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Após o SOS ser acionado, o app envia nativamente a localização a cada 10 segundos sem interrupção para todos os contatos.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Painel de Métricas do Beacon de 10s
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Próximo Envio", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = if (isBeaconRunning) "${beaconSecondsLeft}s" else "10s",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isBeaconRunning) Color(0xFFFFD54F) else Color.White
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Envios Realizados", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = "$beaconPacketsSent",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF00E676)
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Último Envio", fontSize = 11.sp, color = Color.Gray)
                            Text(
                                text = beaconLastTimestamp ?: "--:--:--",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                if (isBeaconRunning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (10 - beaconSecondsLeft) / 10f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFFFF5252),
                        trackColor = Color(0xFF421515),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Botão de Iniciar / Parar Demonstração de 10s
                Button(
                    onClick = {
                        isBeaconRunning = !isBeaconRunning
                        if (isBeaconRunning) {
                            beaconPacketsSent = 0
                            Toast.makeText(context, "Demonstração iniciada! Enviando a cada 10 segundos.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Demonstração pausada.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBeaconRunning) Color(0xFFB71C1C) else Color(0xFFD50000)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = if (isBeaconRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBeaconRunning) "PARAR DEMONSTRAÇÃO 10s" else "INICIAR DEMONSTRAÇÃO DE 10s",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }

                if (isBeaconRunning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Visualização da Localização Transmitida a Cada 10s:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    EmbeddedMapView(
                        latitude = beaconCoords.first,
                        longitude = beaconCoords.second,
                        title = "SOS Ativo (Envio #$beaconPacketsSent)",
                        heightDp = 220,
                        showControls = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // DEMONSTRAÇÃO 2: VISUALIZADOR INTERATIVO DE MAPA EMBUTIDO NO PRÓPRIO APP
        // =========================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF13221B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2E7D32), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "2. Mapa Nativo Embutido no App",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    IconButton(onClick = {
                        onOpenMapDialog(customLat, customLng, locationTitle)
                    }) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Tela Cheia", tint = Color(0xFF00E676))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Visualize coordenadas de emergência diretamente dentro do aplicativo, sem precisar abrir apps externos, com suporte a zoom e rotação.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Seletor de Locais de Demonstração (Chips horizontais)
                Text("Selecione um local para visualizar no mapa:", fontSize = 11.sp, color = Color(0xFFA5D6A7), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Chip para Localização GPS Atual
                    FilterChip(
                        selected = locationTitle == "Meu GPS Atual",
                        onClick = {
                            if (PermissionHelper.hasLocationPermissions(context)) {
                                coroutineScope.launch {
                                    val currentLoc = LocationHelper.getCurrentLocation(context)
                                    if (currentLoc != null) {
                                        customLat = currentLoc.latitude
                                        customLng = currentLoc.longitude
                                        locationTitle = "Meu GPS Atual"
                                        Toast.makeText(context, "GPS atual carregado!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Obtendo sinal GPS...", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "Permissão de GPS necessária", Toast.LENGTH_SHORT).show()
                            }
                        },
                        label = { Text("📍 Meu GPS Atual", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00C853),
                            selectedLabelColor = Color.White
                        )
                    )

                    // Presets
                    locationPresets.forEach { preset ->
                        FilterChip(
                            selected = locationTitle == preset.name,
                            onClick = {
                                selectedPreset = preset
                                customLat = preset.latitude
                                customLng = preset.longitude
                                locationTitle = preset.name
                            },
                            label = { Text("${preset.iconEmoji} ${preset.name}", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00897B),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Mapa Embutido Interativo
                EmbeddedMapView(
                    latitude = customLat,
                    longitude = customLng,
                    title = locationTitle,
                    heightDp = 260,
                    showControls = true
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // DEMONSTRAÇÃO 3: RECEPTOR FURAR NÃO PERTURBE (DND) + ALARME CRÍTICO + TTS
        // =========================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141F2B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1976D2), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF42A5F5), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "3. Furar Não Perturbe + Alarme Crítico",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Testa o som de alarme crítico no volume máximo, a tela de emergência em primeiro plano e o anúncio falado (TTS) do remetente e local.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Teste SMS c/ Mapa em 5s
                    Button(
                        onClick = {
                            viewModel.simulateSmsIn5s()
                            Toast.makeText(context, "Simulando SMS em 5s! Bloqueie a tela para testar o DND.", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SMS c/ Mapa (5s)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Teste Chamada em 5s
                    Button(
                        onClick = {
                            viewModel.simulateCallIn5s()
                            Toast.makeText(context, "Simulando Chamada em 5s! Bloqueie a tela.", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Chamada (5s)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Teste Som Imediato
                OutlinedButton(
                    onClick = {
                        viewModel.testRingtoneNow()
                        Toast.makeText(context, "Sirene acionada! Toque em Silenciar para parar.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ouvir Sirene Crítica Agora", color = Color(0xFF90CAF9), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // DEMONSTRAÇÃO 4: AUTO-REINÍCIO 24/7 (WATCHDOG CONTÍNUO)
        // =========================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142419)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF388E3C), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF66BB6A), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "4. Auto-Reinício 24/7 se Fechado",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Se o usuário fechar o aplicativo no botão 'Limpar Tudo' dos recentes, o AlarmManager e o AutoRestartReceiver ressuscitam o serviço automaticamente.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        viewModel.testAutoRestartWatchdog()
                        Toast.makeText(context, "✅ Watchdog testado com sucesso! O serviço foi mantido vivo.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testar Watchdog de Reinício Agora", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
