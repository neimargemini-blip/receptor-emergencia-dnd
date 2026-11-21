package com.emergencycall.bypass.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.receiver.AutoRestartReceiver
import com.emergencycall.bypass.receiver.IncomingCallReceiver
import com.emergencycall.bypass.ui.MainActivity

class EmergencyMonitorService : Service() {

    companion object {
        private const val TAG = "EmergencyMonitorService"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID_MONITOR = "emergency_persistent_monitor_channel"

        const val ACTION_START_MONITOR = "com.emergencycall.bypass.action.START_MONITOR"
        const val ACTION_STOP_MONITOR = "com.emergencycall.bypass.action.STOP_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, EmergencyMonitorService::class.java).apply {
                action = ACTION_START_MONITOR
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar EmergencyMonitorService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EmergencyMonitorService::class.java).apply {
                action = ACTION_STOP_MONITOR
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao parar EmergencyMonitorService", e)
            }
        }
    }

    private var internalCallReceiver: IncomingCallReceiver? = null
    private var isExplicitlyStopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isExplicitlyStopped = false
        createNotificationChannel()
        registerInternalCallReceiver()
    }

    private fun registerInternalCallReceiver() {
        try {
            if (internalCallReceiver == null) {
                internalCallReceiver = IncomingCallReceiver()
                val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(internalCallReceiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(internalCallReceiver, filter)
                }
                Log.d(TAG, "Receiver dinâmico de chamadas registrado no serviço persistente")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar receiver dinâmico de chamadas", e)
        }
    }

    private fun unregisterInternalCallReceiver() {
        try {
            internalCallReceiver?.let {
                unregisterReceiver(it)
                internalCallReceiver = null
                Log.d(TAG, "Receiver dinâmico desregistrado")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desregistrar receiver dinâmico", e)
        }
    }

    /**
     * Chamado pelo Android quando o usuário desliza o app para fora da lista de aplicativos recentes (fecha o app).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "onTaskRemoved: Aplicativo fechado na lista de recentes. Agendando reinício imediato...")
        scheduleWatchdogRestart()
    }

    override fun onDestroy() {
        unregisterInternalCallReceiver()
        if (!isExplicitlyStopped) {
            Log.w(TAG, "onDestroy inesperado no serviço de monitoramento. Acionando watchdog de recuperação...")
            scheduleWatchdogRestart()
        }
        super.onDestroy()
    }

    /**
     * Agenda o reinício do serviço através do AlarmManager com PendingIntent de broadcast.
     * Funciona mesmo com o app fechado e em modo Doze/Standby.
     */
    private fun scheduleWatchdogRestart() {
        val prefs = PreferencesManager.getInstance(this)
        if (!prefs.isMonitoringActive || !prefs.isAutoRestartEnabled) {
            Log.d(TAG, "Watchdog cancelado: monitoramento ou auto-reinício desativado nas preferências")
            return
        }

        try {
            val restartIntent = Intent(applicationContext, AutoRestartReceiver::class.java).apply {
                action = AutoRestartReceiver.ACTION_AUTO_RESTART
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                1999,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + 1200 // Dispara em 1.2 segundos

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.i(TAG, "Watchdog agendado com sucesso para acordar e reiniciar o serviço em 1.2s")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao agendar auto-reinício no AlarmManager", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = PreferencesManager.getInstance(this)

        if (intent?.action == ACTION_STOP_MONITOR || !prefs.isMonitoringActive) {
            Log.d(TAG, "Parando serviço persistente de monitoramento por solicitação explícita")
            isExplicitlyStopped = true
            unregisterInternalCallReceiver()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isExplicitlyStopped = false
        Log.d(TAG, "Iniciando serviço persistente para manter app ativo 24/7 em segundo plano")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildMonitorNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildMonitorNotification())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao iniciar startForeground no monitor", e)
            try {
                startForeground(NOTIFICATION_ID, buildMonitorNotification())
            } catch (e2: Throwable) {
                Log.e(TAG, "Falha no fallback de startForeground no monitor", e2)
            }
        }

        return START_STICKY
    }

    private fun buildMonitorNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Receptor de Emergência Ativo (24/7)")
            .setContentText("Monitorando chamadas e SMS VIP em segundo plano.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_MONITOR,
                "Monitor de Segundo Plano 24/7",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o receptor de emergência vivo em segundo plano"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
