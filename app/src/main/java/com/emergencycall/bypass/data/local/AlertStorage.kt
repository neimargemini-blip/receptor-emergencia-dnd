package com.emergencycall.bypass.data.local

import android.content.Context
import android.content.SharedPreferences
import com.emergencycall.bypass.data.model.EmergencyAlert
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlertStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emergency_alerts_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _alertsFlow = MutableStateFlow<List<EmergencyAlert>>(emptyList())
    val alertsFlow: StateFlow<List<EmergencyAlert>> = _alertsFlow.asStateFlow()

    init {
        loadAlerts()
    }

    @Synchronized
    fun getAlerts(): List<EmergencyAlert> {
        val json = prefs.getString(KEY_ALERTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<EmergencyAlert>>() {}.type
            gson.fromJson<List<EmergencyAlert>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addAlert(alert: EmergencyAlert) {
        val current = getAlerts().toMutableList()
        current.add(0, alert) // Mais recente primeiro
        if (current.size > 50) {
            current.removeAt(current.size - 1)
        }
        persist(current)
    }

    @Synchronized
    fun clearAlerts() {
        persist(emptyList())
    }

    private fun persist(list: List<EmergencyAlert>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_ALERTS, json).apply()
        _alertsFlow.value = list
    }

    private fun loadAlerts() {
        _alertsFlow.value = getAlerts()
    }

    companion object {
        private const val KEY_ALERTS = "key_alerts_list_json"

        @Volatile
        private var instance: AlertStorage? = null

        fun getInstance(context: Context): AlertStorage {
            return instance ?: synchronized(this) {
                instance ?: AlertStorage(context.applicationContext).also { instance = it }
            }
        }
    }
}
