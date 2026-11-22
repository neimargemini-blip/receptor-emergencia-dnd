package com.emergencycall.bypass.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var vibrator: Vibrator? = null

    // Lanterna / Flash Strobe
    private var cameraManager: CameraManager? = null
    private var flashCameraId: String? = null
    private var flashJob: Job? = null

    // Alarme Sonoro Crítico (Sirene em Loop)
    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var soundWatchdogJob: Job? = null
    private var previousAlarmVolume: Int? = null
    private var previousRingerMode: Int? = null
    private var previousDndFilter: Int? = null

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

        // Coordenadas exatas em tempo real para visualização do mapa dentro do app
        private val _lastCoordinates = MutableStateFlow<Pair<Double, Double>?>(null)
        val lastCoordinates: StateFlow<Pair<Double, Double>?> = _lastCoordinates.asStateFlow()

        private val _totalSmsSent = MutableStateFlow(0)
        val totalSmsSent: StateFlow<Int> = _totalSmsSent.asStateFlow()

        // Envio periódico a cada 10 segundos
        private val _secondsToNextSms = MutableStateFlow(10)
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

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EmergencyCallBypass:SosWakeLock"
        )

        initFlash()
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
        _secondsToNextSms.value = 10

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60 * 1000L /* 1 hora max */)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                              ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                              ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIFICATION_ID, createNotification("🚨 SOS ATIVADO", "Sirene e Flash ativos. Enviando localização a cada 10s..."), fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                              ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIFICATION_ID, createNotification("🚨 SOS ATIVADO", "Sirene e Flash ativos. Enviando localização a cada 10s..."), fgsType)
            } else {
                startForeground(NOTIFICATION_ID, createNotification("🚨 SOS ATIVADO", "Sirene e Flash ativos. Enviando localização a cada 10s..."))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao iniciar startForeground no SosEmitter", e)
            try {
                startForeground(NOTIFICATION_ID, createNotification("🚨 SOS ATIVADO", "Sirene e Flash ativos. Enviando localização a cada 10s..."))
            } catch (e2: Throwable) {
                Log.e(TAG, "Falha no fallback de startForeground do SOS", e2)
            }
        }

        // 1. Inicia som de alerta crítico (sirene) até ser parado
        startCriticalAlarmSound()

        // 2. Inicia piscar do flash (lanterna estroboscópica) até ser parado
        startFlashStrobe()

        // 3. Inicia vibração contínua em padrão de emergência
        startSosVibration()

        // 4. Tenta realizar a chamada com viva-voz para o primeiro contato ativo
        makePhoneCallWithSpeakerphone()

        // 5. Inicia loop de envio de localização nativa via SMS a cada 10 segundos
        sosJob = serviceScope.launch {
            while (isActive && _isSosRunning.value) {
                // Dispara o envio imediato da localização atual
                sendLocationSmsToAllContacts()

                // Contagem regressiva de 10 segundos até o próximo envio
                for (sec in 10 downTo 1) {
                    _secondsToNextSms.value = sec
                    delay(1000L)
                    if (!_isSosRunning.value) break
                }
            }
        }
    }

    // ==================== CONTROLE DO FLASH (LANTERNA ESTROBOSCÓPICA) ====================

    private fun initFlash() {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.let { cm ->
                for (id in cm.cameraIdList) {
                    val characteristics = cm.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        flashCameraId = id
                        break
                    } else if (hasFlash && flashCameraId == null) {
                        flashCameraId = id
                    }
                }
            }
            Log.d(TAG, "Flash inicializado com câmera ID: $flashCameraId")
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao inicializar CameraManager para flash", e)
        }
    }

    private fun startFlashStrobe() {
        if (cameraManager == null || flashCameraId == null) {
            initFlash()
        }
        val targetId = flashCameraId
        if (targetId == null) {
            Log.w(TAG, "Nenhuma câmera com flash disponível no dispositivo")
            return
        }

        flashJob?.cancel()
        flashJob = serviceScope.launch(Dispatchers.Default) {
            var torchState = false
            while (isActive && _isSosRunning.value) {
                try {
                    torchState = !torchState
                    cameraManager?.setTorchMode(targetId, torchState)
                } catch (e: Throwable) {
                    Log.w(TAG, "Erro ao alternar flash: ${e.message}")
                }
                delay(200L) // Pisca rápido a cada 200ms
            }
            // Ao sair do loop, garante flash desligado
            try {
                cameraManager?.setTorchMode(targetId, false)
            } catch (_: Throwable) {}
        }
    }

    private fun stopFlashStrobe() {
        flashJob?.cancel()
        flashJob = null
        try {
            flashCameraId?.let { id ->
                cameraManager?.setTorchMode(id, false)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao desligar flash no encerramento: ${e.message}")
        }
    }

    // ==================== CONTROLE DO ALARME SONORO CRÍTICO ====================

    private fun startCriticalAlarmSound() {
        // 1. Tira do Não Perturbe se tiver permissão
        if (PermissionHelper.hasDndAccess(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                previousDndFilter = notificationManager.currentInterruptionFilter
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao desativar DND para SOS", e)
            }
        }

        // 2. Coloca em RINGER_MODE_NORMAL e volume no MÁXIMO
        try {
            audioManager?.let { am ->
                previousRingerMode = am.ringerMode
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL

                previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao ajustar volume do alarme para SOS", e)
        }

        // 3. Toca som de alerta crítico em loop contínuo
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    val afd = resources.openRawResourceFd(com.emergencycall.bypass.R.raw.critical_alarm)
                    if (afd != null) {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    } else {
                        val rawUri = Uri.parse("android.resource://$packageName/${com.emergencycall.bypass.R.raw.critical_alarm}")
                        setDataSource(applicationContext, rawUri)
                    }
                    isLooping = true
                    prepare()
                    start()
                }
                Log.d(TAG, "Alarme crítico SOS (R.raw.critical_alarm) iniciado em loop com sucesso")
            } catch (e: Throwable) {
                Log.e(TAG, "Falha ao iniciar MediaPlayer com R.raw.critical_alarm, usando RingtoneManager fallback", e)
                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                fallbackRingtone = RingtoneManager.getRingtone(applicationContext, defaultUri)?.apply {
                    this.audioAttributes = audioAttributes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }

            // Loop de verificação de garantia: se o som parar enquanto SOS estiver ativo, religa imediatamente
            soundWatchdogJob?.cancel()
            soundWatchdogJob = serviceScope.launch {
                while (isActive && _isSosRunning.value) {
                    delay(2000L)
                    if (_isSosRunning.value) {
                        try {
                            if (mediaPlayer != null && mediaPlayer?.isPlaying == false) {
                                mediaPlayer?.start()
                            } else if (fallbackRingtone != null && fallbackRingtone?.isPlaying == false) {
                                fallbackRingtone?.play()
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Watchdog som SOS: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro fatal ao iniciar som crítico", e)
        }
    }

    private fun stopCriticalAlarmSound() {
        soundWatchdogJob?.cancel()
        soundWatchdogJob = null

        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao liberar MediaPlayer", e)
        }

        try {
            if (fallbackRingtone?.isPlaying == true) {
                fallbackRingtone?.stop()
            }
            fallbackRingtone = null
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao parar Ringtone", e)
        }

        // Restaura volume anterior do alarme
        try {
            audioManager?.let { am ->
                previousAlarmVolume?.let { vol ->
                    am.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
                }
                previousRingerMode?.let { mode ->
                    am.ringerMode = mode
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao restaurar volume do alarme", e)
        }

        // Restaura DND se havia sido alterado
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && previousDndFilter != null) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                previousDndFilter?.let { filter ->
                    notificationManager.setInterruptionFilter(filter)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao restaurar filtro DND", e)
            }
        }
    }

    // ==================== VIBRAÇÃO DE SOS ====================

    private fun startSosVibration() {
        try {
            val pattern = EmergencyApplication.EMERGENCY_VIBRATION_PATTERN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao iniciar vibração SOS", e)
        }
    }

    private fun stopSosVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao parar vibração SOS", e)
        }
    }

    // ==================== ENVIO DE SMS PERIÓDICO COM LOCALIZAÇÃO ====================

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
            _lastCoordinates.value = Pair(location.latitude, location.longitude)
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
                "Sirene e Flash ativos. SMS enviado para $sentThisRound contatos. Próximo envio em 10s."
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

        // Para Flash, Sirene e Vibração imediatamente
        stopFlashStrobe()
        stopCriticalAlarmSound()
        stopSosVibration()

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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "PARAR SOS AGORA", pStop)
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
