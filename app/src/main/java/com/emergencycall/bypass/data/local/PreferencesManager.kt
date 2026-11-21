package com.emergencycall.bypass.data.local

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emergency_settings_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MONITORING_ACTIVE = "monitoring_active"
        private const val KEY_VOLUME_PERCENT = "target_volume_percent"
        private const val KEY_SPEAK_CALLER_NAME = "speak_caller_name"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_RINGTONE_URI = "selected_ringtone_uri"
        private const val KEY_RINGTONE_TITLE = "selected_ringtone_title"
        private const val KEY_AUTO_RESTART_ENABLED = "auto_restart_enabled"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var isMonitoringActive: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ACTIVE, value).apply()

    // Volume alvo ao sair do Não Perturbe (padrão 50%)
    var targetVolumePercent: Int
        get() = prefs.getInt(KEY_VOLUME_PERCENT, 50)
        set(value) = prefs.edit().putInt(KEY_VOLUME_PERCENT, value).apply()

    // Falar o nome do contato repetidamente até atender
    var isSpeakCallerNameEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAK_CALLER_NAME, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK_CALLER_NAME, value).apply()

    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    // Toque escolhido pelo usuário (URI e Título amigável)
    var selectedRingtoneUri: String?
        get() = prefs.getString(KEY_RINGTONE_URI, null)
        set(value) = prefs.edit().putString(KEY_RINGTONE_URI, value).apply()

    var selectedRingtoneTitle: String?
        get() = prefs.getString(KEY_RINGTONE_TITLE, "Alarme Crítico de Emergência (Sirene)")
        set(value) = prefs.edit().putString(KEY_RINGTONE_TITLE, value).apply()

    // Reiniciar automaticamente o serviço de monitoramento caso o app seja fechado ou removido dos recentes
    var isAutoRestartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTART_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTART_ENABLED, value).apply()
}
