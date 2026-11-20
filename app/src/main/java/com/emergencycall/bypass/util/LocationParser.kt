package com.emergencycall.bypass.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import java.util.regex.Pattern

object LocationParser {

    private const val TAG = "LocationParser"

    // Links de serviços de mapa conhecidos
    private val MAPS_URL_PATTERN = Pattern.compile(
        """https?://(?:maps\.google\.com|www\.google\.com/maps|maps\.app\.goo\.gl|goo\.gl/maps|maps\.apple\.com|waze\.com)[^\s,;]+""",
        Pattern.CASE_INSENSITIVE
    )

    // URI geo:latitude,longitude
    private val GEO_URI_PATTERN = Pattern.compile(
        """geo:(-?\d+(?:[.,]\d+)?)[,\s]+(-?\d+(?:[.,]\d+)?)[^\s]*""",
        Pattern.CASE_INSENSITIVE
    )

    // Coordenadas decimais brutas (com ponto ou vírgula): ex: -23.55052, -46.63330 ou -23,55052, -46,63330
    private val COORD_PATTERN = Pattern.compile(
        """(-?\d{1,2}[.,]\d{3,})[,\s]+(-?\d{1,3}[.,]\d{3,})"""
    )

    // Qualquer link HTTP ou HTTPS contido no SMS
    private val GENERIC_HTTP_PATTERN = Pattern.compile(
        """https?://[^\s,;]+""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Extrai ou monta uma URL do Google Maps a partir do texto do SMS ou mensagem.
     */
    fun extractMapsUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null

        // 1. Link direto conhecido de mapas
        val mapsUrlMatcher = MAPS_URL_PATTERN.matcher(text)
        if (mapsUrlMatcher.find()) {
            return mapsUrlMatcher.group(0)
        }

        // 2. URI geo:
        val geoMatcher = GEO_URI_PATTERN.matcher(text)
        if (geoMatcher.find()) {
            val lat = geoMatcher.group(1)?.replace(',', '.')
            val lng = geoMatcher.group(2)?.replace(',', '.')
            return "https://www.google.com/maps?q=$lat,$lng"
        }

        // 3. Coordenadas decimais brutas de latitude e longitude
        val coords = extractCoordinates(text)
        if (coords != null) {
            return "https://www.google.com/maps?q=${coords.first},${coords.second}"
        }

        // 4. Qualquer URL presente no SMS (ex: encurtadores de link de socorro)
        val genericMatcher = GENERIC_HTTP_PATTERN.matcher(text)
        if (genericMatcher.find()) {
            return genericMatcher.group(0)
        }

        return null
    }

    /**
     * Extrai as coordenadas numéricas (Latitude, Longitude) caso presentes.
     */
    fun extractCoordinates(text: String?): Pair<Double, Double>? {
        if (text.isNullOrBlank()) return null

        // Tenta buscar no padrão de coordenadas decimais
        val matcher = COORD_PATTERN.matcher(text)
        while (matcher.find()) {
            val latStr = matcher.group(1)?.replace(',', '.')
            val lngStr = matcher.group(2)?.replace(',', '.')
            val lat = latStr?.toDoubleOrNull()
            val lng = lngStr?.toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return Pair(lat, lng)
            }
        }

        // Tenta extrair de uma URL existente (ex: ?q=lat,lng)
        val qPattern = Pattern.compile("""[?&]q=(-?\d+(?:\.\d+)?)[,\s]+(-?\d+(?:\.\d+)?)""")
        val qMatcher = qPattern.matcher(text)
        if (qMatcher.find()) {
            val lat = qMatcher.group(1)?.toDoubleOrNull()
            val lng = qMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                return Pair(lat, lng)
            }
        }

        return null
    }

    /**
     * Abre intuitivamente o Google Maps diretamente no aplicativo nativo (com fallback no navegador).
     */
    fun openGoogleMaps(context: Context, urlOrCoords: String?) {
        if (urlOrCoords.isNullOrBlank()) return

        val url = if (urlOrCoords.startsWith("http://") || urlOrCoords.startsWith("https://")) {
            urlOrCoords
        } else {
            "https://www.google.com/maps?q=${urlOrCoords.trim()}"
        }

        try {
            // 1. Tenta abrir diretamente no aplicativo oficial do Google Maps
            val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mapsIntent)
            Log.d(TAG, "Localização aberta com sucesso no app do Google Maps")
        } catch (e: Exception) {
            // 2. Se o app específico do Google Maps não abrir, abre em qualquer app de mapas ou navegador
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(genericIntent)
                Log.d(TAG, "Localização aberta com sucesso via navegador/aplicativo padrão")
            } catch (e2: Exception) {
                Log.e(TAG, "Falha ao abrir mapa", e2)
                Toast.makeText(context, "Não foi possível abrir o mapa", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Copia as coordenadas ou link para a área de transferência do celular de forma intuitiva.
     */
    fun copyCoordinates(context: Context, text: String?) {
        if (text.isNullOrBlank()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Coordenadas de Emergência", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Coordenadas copiadas com sucesso!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao copiar coordenadas", e)
        }
    }
}
