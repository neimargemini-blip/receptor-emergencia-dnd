package com.emergencycall.bypass

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

class EmergencyApplication : Application() {

    companion object {
        const val CHANNEL_ID_EMERGENCY_CALL = "emergency_call_critical_channel"
        const val CHANNEL_ID_SERVICE = "emergency_call_service_channel"
        const val CHANNEL_ID_MONITOR = "emergency_persistent_monitor_channel"
        val EMERGENCY_VIBRATION_PATTERN = longArrayOf(0, 1000, 500, 1000, 500, 1000)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            // 1. Canal Crítico de Chamada de Emergência (com Bypass DND)
            val emergencyChannel = NotificationChannel(
                CHANNEL_ID_EMERGENCY_CALL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_description)
                setSound(alarmSound, audioAttributes)
                enableVibration(true)
                vibrationPattern = EMERGENCY_VIBRATION_PATTERN
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(emergencyChannel)

            // 2. Canal para o serviço persistente de monitoramento 24/7 (segundo plano sem fechar)
            val monitorChannel = NotificationChannel(
                CHANNEL_ID_MONITOR,
                "Monitor de Segundo Plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o app ativo em segundo plano sem fechar para receber chamadas e SMS"
                setShowBadge(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(monitorChannel)
        }
    }
}
