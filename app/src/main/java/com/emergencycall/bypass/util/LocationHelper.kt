package com.emergencycall.bypass.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

object LocationHelper {

    private const val TAG = "LocationHelper"

    /**
     * Verifica se os serviços de localização do dispositivo (GPS / Rede) estão ativados nas configurações do Android.
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Throwable) {
            try {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Retorna instantaneamente (0ms) a melhor e mais recente localização em cache do aparelho
     * consultando FusedLocationProviderClient e todos os provedores nativos (GPS, Rede Celular/Wi-Fi e Passivo).
     */
    @SuppressLint("MissingPermission")
    suspend fun getBestLastKnownLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        if (!PermissionHelper.hasLocationPermissions(context)) {
            Log.w(TAG, "Permissão de localização não concedida para getBestLastKnownLocation")
            return@withContext null
        }

        val candidates = mutableListOf<Location>()

        // 1. Google Play Services Fused Location
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val fusedLast = runCatching { fusedClient.lastLocation.await() }.getOrNull()
            if (fusedLast != null) {
                candidates.add(fusedLast)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao obter lastLocation do FusedLocationProviderClient: ${e.message}")
        }

        // 2. Android LocationManager Nativo (GPS, Rede Celular/Wi-Fi, Passivo)
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                for (provider in providers) {
                    try {
                        val loc = lm.getLastKnownLocation(provider)
                        if (loc != null) {
                            candidates.add(loc)
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao obter lastKnown do LocationManager: ${e.message}")
        }

        if (candidates.isEmpty()) {
            return@withContext null
        }

        // Escolhe a localização mais recente e precisa
        val now = System.currentTimeMillis()
        candidates.maxWithOrNull(
            compareBy<Location> { loc ->
                val ageMs = now - loc.time
                // Se a localização tiver menos de 10 minutos, prioriza fortemente
                if (ageMs < 10 * 60 * 1000L) 1_000_000L - ageMs else -ageMs
            }.thenBy { -it.accuracy }
        )
    }

    /**
     * Retorna a localização de alta precisão atual.
     * Tenta obter fix novo via FusedLocation e LocationManager. Caso expire o timeout,
     * recorre instantaneamente à melhor localização conhecida em cache.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context, timeoutMs: Long = 5_000L): Location? {
        if (!PermissionHelper.hasLocationPermissions(context)) {
            Log.w(TAG, "Permissão de localização não concedida para getCurrentLocation")
            return null
        }

        // 1. Tenta FusedLocationProviderClient com CancellationToken
        val cts = CancellationTokenSource()
        val fusedLoc = try {
            withTimeoutOrNull(timeoutMs) {
                val client = LocationServices.getFusedLocationProviderClient(context)
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Erro no FusedLocationProviderClient: ${e.message}")
            null
        } finally {
            cts.cancel()
        }

        if (fusedLoc != null) {
            Log.d(TAG, "Localização obtida via FusedLocation: ${fusedLoc.latitude}, ${fusedLoc.longitude} (${fusedLoc.accuracy}m)")
            return fusedLoc
        }

        // 2. Se o Fused falhou/expirou, tenta via LocationManager nativo (Network e GPS)
        val nativeLoc = try {
            requestLocationManagerFix(context, 3_000L)
        } catch (e: Throwable) {
            Log.w(TAG, "Erro no LocationManager nativo: ${e.message}")
            null
        }

        if (nativeLoc != null) {
            Log.d(TAG, "Localização obtida via LocationManager nativo: ${nativeLoc.latitude}, ${nativeLoc.longitude} (${nativeLoc.accuracy}m)")
            return nativeLoc
        }

        // 3. Fallback imediato para melhor localização em cache
        val cachedLoc = getBestLastKnownLocation(context)
        if (cachedLoc != null) {
            Log.d(TAG, "Localização obtida via fallback de cache: ${cachedLoc.latitude}, ${cachedLoc.longitude} (${cachedLoc.accuracy}m)")
        } else {
            Log.w(TAG, "Nenhuma localização disponível (nem nova nem em cache)")
        }
        return cachedLoc
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestLocationManagerFix(context: Context, timeoutMs: Long): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (cont.isActive) {
                            try { lm.removeUpdates(this) } catch (_: Throwable) {}
                            cont.resume(loc) {}
                        }
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }

                cont.invokeOnCancellation {
                    try { lm.removeUpdates(listener) } catch (_: Throwable) {}
                }

                var registered = false
                try {
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                        registered = true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Erro ao registrar NETWORK_PROVIDER: ${e.message}")
                }

                try {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                        registered = true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Erro ao registrar GPS_PROVIDER: ${e.message}")
                }

                if (!registered && cont.isActive) {
                    cont.resume(null) {}
                }
            }
        }
    }

    /**
     * Constrói link oficial do Google Maps com coordenadas garantidas em formato internacional (ponto decimal).
     * Exemplo: https://www.google.com/maps?q=-23.550520,-46.633308
     */
    fun buildMapsLink(latitude: Double, longitude: Double): String {
        val latStr = String.format(Locale.US, "%.6f", latitude)
        val lngStr = String.format(Locale.US, "%.6f", longitude)
        return "https://www.google.com/maps?q=$latStr,$lngStr"
    }

    /**
     * Retorna a string formatada das coordenadas numéricas (Lat: -23.550520, Lng: -46.633308).
     */
    fun formatCoordinatesText(latitude: Double, longitude: Double): String {
        val latStr = String.format(Locale.US, "%.6f", latitude)
        val lngStr = String.format(Locale.US, "%.6f", longitude)
        return "Lat: $latStr, Lng: $lngStr"
    }
}
