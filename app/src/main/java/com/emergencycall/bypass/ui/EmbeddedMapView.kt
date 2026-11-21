package com.emergencycall.bypass.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedMapView(
    latitude: Double,
    longitude: Double,
    title: String = "Localização",
    modifier: Modifier = Modifier,
    heightDp: Int = 260,
    showControls: Boolean = true
) {
    val context = LocalContext.current
    var isMapLoaded by remember { mutableStateOf(false) }

    val formattedCoords = remember(latitude, longitude) {
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }

    val mapsUrl = remember(latitude, longitude) {
        "https://www.google.com/maps?q=$latitude,$longitude"
    }

    val leafletHtml = remember(latitude, longitude, title) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                * { box-sizing: border-box; }
                html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #181818; }
                .leaflet-popup-content-wrapper {
                    background: #212121;
                    color: #ffffff;
                    border: 1px solid #D50000;
                    border-radius: 8px;
                    box-shadow: 0 4px 12px rgba(0,0,0,0.6);
                }
                .leaflet-popup-tip { background: #212121; }
                .badge-pulse {
                    width: 22px; height: 22px;
                    background: rgba(213, 0, 0, 0.4);
                    border: 2px solid #D50000;
                    border-radius: 50%;
                    box-shadow: 0 0 10px #D50000;
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                try {
                    var map = L.map('map', {
                        center: [$latitude, $longitude],
                        zoom: 16,
                        zoomControl: true,
                        attributionControl: false
                    });

                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        maxZoom: 19
                    }).addTo(map);

                    var pulseIcon = L.divIcon({
                        className: 'custom-pin',
                        html: '<div style="position:relative;"><div class="badge-pulse"></div><div style="position:absolute;top:5px;left:5px;width:12px;height:12px;background:#D50000;border-radius:50%;"></div></div>',
                        iconSize: [22, 22],
                        iconAnchor: [11, 11]
                    });

                    var marker = L.marker([$latitude, $longitude], { icon: pulseIcon }).addTo(map);
                    marker.bindPopup("<b style='color:#FF5252;'>🚨 $title</b><br><span style='font-size:12px;color:#EEE;'>Lat: $latitude<br>Lng: $longitude</span>").openPopup();

                    L.circle([$latitude, $longitude], {
                        color: '#D50000',
                        fillColor: '#FF1744',
                        fillOpacity: 0.2,
                        radius: 45
                    }).addTo(map);
                } catch(e) {
                    document.body.innerHTML = '<div style="color:white;padding:20px;text-align:center;">Localização: $latitude, $longitude</div>';
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141414))
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
    ) {
        // Cabeçalho com coordenadas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1F1F1F))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "GPS: $formattedCoords",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("GPS", formattedCoords)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Coordenadas copiadas!", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = Color(0xFFA5D6A7), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copiar", color = Color(0xFFA5D6A7), fontSize = 11.sp)
            }
        }

        // Visualização do Mapa Embutido via WebView
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(Color(0xFF181818))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isMapLoaded = true
                            }
                        }
                        loadDataWithBaseURL("https://unpkg.com", leafletHtml, "text/html", "UTF-8", null)
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL("https://unpkg.com", leafletHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!isMapLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212).copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00E676), modifier = Modifier.size(28.dp))
                }
            }
        }

        if (showControls) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(mapIntent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Erro ao abrir Google Maps externo", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Google Maps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Localização de Emergência")
                                putExtra(Intent.EXTRA_TEXT, "Localização: $mapsUrl ($formattedCoords)")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Compartilhar Localização"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Não foi possível compartilhar", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81C784))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Compartilhar", fontSize = 12.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EmbeddedMapDialog(
    latitude: Double,
    longitude: Double,
    title: String = "Localização Recebida",
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                EmbeddedMapView(
                    latitude = latitude,
                    longitude = longitude,
                    title = title,
                    heightDp = 320,
                    showControls = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Fechar Mapa", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
