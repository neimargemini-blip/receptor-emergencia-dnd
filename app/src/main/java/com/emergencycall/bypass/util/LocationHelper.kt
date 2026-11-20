package com.emergencycall.bypass.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object LocationHelper {

    /**
     * Retorna a localização de alta precisão atual, ou a última conhecida caso demore mais de 5s.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val current = withTimeoutOrNull(5_000L) {
                client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).await()
            }
            current ?: runCatching { client.lastLocation.await() }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun buildMapsLink(latitude: Double, longitude: Double): String =
        "https://www.google.com/maps?q=$latitude,$longitude"
}
