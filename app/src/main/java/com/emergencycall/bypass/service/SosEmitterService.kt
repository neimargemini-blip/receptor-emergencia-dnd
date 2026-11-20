package com.emergencycall.bypass.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emergencycall.bypass.EmergencyApplication
import com.emergencycall.bypass.data.local.ContactStorage
import com.emergencycall.bypass.ui.MainActivity
import com.emergencycall.bypass.util.LocationHelper
import com.emergencycall.bypass.util.PermissionHelper
import com.emergencycall.bypass.util.SmsSenderHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

class SosEmitterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var sosJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null

    companion object {
        private const val TAG = "SosEmitterService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID_SOS = "emergency_sos_emitter_channel"

        const val ACTION_START_SOS = "com.emergencycall.bypass.action.START_SOS"
        const val ACTION_STOP_SOS = "com.emergencycall.bypass.action.STOP_SOS"

        // Estado do SOS Observável pela Interface em tempo real
        private val _isSosRunning = MutableStateFlow(false)
        val isSosRunning: StateFlow<Boolean> = _isSosRunning.asStateFlow()

        private val _sosStartedAt = MutableStateFlow<Long?>(null)
        val sosStartedAt: StateFlow<Long?> = _sosStartedAt.asStateFlow()

        private val _lastMapsUrl = MutableStateFlow<String?>(null)
        val lastMapsUrl: StateFlow<String?> = _lastMapsUrl.asStateFlow()

        private val _totalSmsSent = MutableStateFlow(0)
        val totalSmsSent: StateFlow<Int> = _totalSmsSent.asStateFlow()

        private val _secondsToNextSms = MutableStateFlow(60)
        val secondsToNextSms: StateFlow<Int> = _secondsToNextSms.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SosEmitterService::class.java).apply {
                action = ACTION_START_SOS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SosEmitterService::class.java).apply {
                action = ACTION_STOP_SOS
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EmergencyCallBypass:SosWakeLock"
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SOS -> startSosLoop()
            ACTION_STOP_SOS -> stopSosLoop()
            else -> stopSosLoop()
        }
        return START_STICKY
    }

    private fun startSosLoop() {
        if (_isSosRunning.value) return

        _isSosRunning.value = true
        _sosStartedAt.value = System.currentTimeMillis()
        _totalSmsSent.value = 0
        _secondsToNextSms.value = 60

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60 * 1000L /* 1 hora max */)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(NOTIFICATION_ID, createNotification("SOS ATIVADO", "Enviando localização para contatos VIP..."), fgsType)
            } else {
                startForeground(NOTIFICATION_ID, createNotification("SOS ATIVADO", "Enviando localização para contatos VIP..."))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao iniciar startForeground no SosEmitter", e)
            try {
                startForeground(NOTIFICATION_ID, createNotification("SOS ATIVADO", "Enviando localização para contatos VIP..."))
            } catch (e2: Throwable) {
                Log.e(TAG, "Falha no fallback de startForeground do SOS", e2)
            }
        }

        // 1. Tenta realizar a chamada com viva-voz para o primeiro contato ativo
        makePhoneCallWithSpeakerphone()

        // 2. Inicia loop de envio de localização via SMS a cada 1 minuto (60 segundos)
        sosJob = serviceScope.launch {
            while (isActive && _isSosRunning.value) {
                // Dispara o envio imediato da localização atual
                sendLocationSmsToAllContacts()

                // Contagem regressiva de 60 segundos até o próximo envio
                for (sec in 60 downTo 1) {
                    _secondsToNextSms.value = sec
                    delay(1000L)
                    if (!_isSosRunning.value) break
                }
            }
        }
    }

    private suspend fun sendLocationSmsToAllContacts() {
        val contacts = ContactStorage.getInstance(this).getSenderContacts().filter { it.isEnabled }
        if (contacts.isEmpty()) {
            Log.w(TAG, "Nenhum contato cadastrado na lista de envio para receber SMS de SOS")
            return
        }

        // Obtém localização GPS
        val location = LocationHelper.getCurrentLocation(this)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = timeFormat.format(Date())

        val mapsUrl = if (location != null) {
            LocationHelper.buildMapsLink(location.latitude, location.longitude)
        } else {
            _lastMapsUrl.value ?: "https://www.google.com/maps"
        }
        _lastMapsUrl.value = mapsUrl

        val message = "🚨 SOS DE EMERGÊNCIA! Preciso de ajuda imediata! Minha localização em tempo real: $mapsUrl (às $timeStr)"

        var sentThisRound = 0
        for (contact in contacts) {
            val ok = SmsSenderHelper.sendSms(this, contact.phoneNumber, message)
            if (ok) sentThisRound++
        }

        _totalSmsSent.value += sentThisRound

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(
            NOTIFICATION_ID,
            createNotification(
                "🚨 SOS ATIVO - Localização Enviada",
                "SMS enviado para $sentThisRound contatos. Próximo envio em 1 minuto."
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun makePhoneCallWithSpeakerphone() {
        val contacts = ContactStorage.getInstance(this).getSenderContacts().filter { it.isEnabled }
        val primaryContact = contacts.firstOrNull() ?: return

        serviceScope.launch {
            try {
                // Ativa o viva-voz
                audioManager?.mode = AudioManager.MODE_IN_CALL
                audioManager?.isSpeakerphoneOn = true

                delay(500L)

                val phoneUri = Uri.parse("tel:${primaryContact.phoneNumber}")
                val callIntent = if (PermissionHelper.hasCallPhonePermission(this@SosEmitterService)) {
                    Intent(Intent.ACTION_CALL, phoneUri)
                } else {
                    Intent(Intent.ACTION_DIAL, phoneUri)
                }.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                startActivity(callIntent)

                // Re-afirma viva-voz após a chamada iniciar
                delay(2000L)
                audioManager?.isSpeakerphoneOn = true
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao iniciar chamada com viva-voz", e)
            }
        }
    }

    private fun stopSosLoop() {
        Log.d(TAG, "Encerrando modo SOS")
        _isSosRunning.value = false
        _sosStartedAt.value = null
        sosJob?.cancel()
        sosJob = null

        try {
            audioManager?.isSpeakerphoneOn = false
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao restaurar audio", e)
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this,
            201,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SosEmitterService::class.java).apply {
            action = ACTION_STOP_SOS
        }
        val pStop = PendingIntent.getService(
            this,
            202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SOS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(pOpenApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCELAR SOS", pStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_SOS,
                "Transmissor de SOS de Emergência",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Canal de alerta persistente durante o envio periódico de localização"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopSosLoop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
