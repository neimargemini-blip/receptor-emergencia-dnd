package com.emergencycall.bypass.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emergencycall.bypass.EmergencyApplication
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.ui.EmergencyCallScreenActivity
import com.emergencycall.bypass.util.PermissionHelper
import kotlinx.coroutines.*
import java.util.*

class EmergencyCallRingerService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var ringtone: Ringtone? = null
    private var fallbackPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null

    private var previousVolume: Int? = null
    private var previousRingVolume: Int? = null
    private var previousDndFilter: Int? = null
    private var previousRingerMode: Int? = null

    // Text-to-Speech para falar o nome do contato até atender
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speakingJob: Job? = null
    private var ringtoneLoopJob: Job? = null
    private var isRinging = false
    private var currentCallerName = ""

    companion object {
        private const val TAG = "EmergencyCallRinger"
        const val ACTION_START_RINGER = "com.emergencycall.bypass.action.START_RINGER"
        const val ACTION_STOP_RINGER = "com.emergencycall.bypass.action.STOP_RINGER"

        const val EXTRA_TRIGGER_TYPE = "trigger_type" // CALL, SMS, WHATSAPP_CALL
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_MESSAGE_BODY = "message_body"
        const val EXTRA_MAPS_URL = "maps_url"

        fun start(
            context: Context,
            callerName: String,
            callerNumber: String,
            triggerType: String = "CALL",
            messageBody: String? = null,
            mapsUrl: String? = null
        ) {
            val intent = Intent(context, EmergencyCallRingerService::class.java).apply {
                action = ACTION_START_RINGER
                putExtra(EXTRA_TRIGGER_TYPE, triggerType)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_NUMBER, callerNumber)
                putExtra(EXTRA_MESSAGE_BODY, messageBody)
                putExtra(EXTRA_MAPS_URL, mapsUrl)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Falha ao iniciar EmergencyCallRingerService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EmergencyCallRingerService::class.java).apply {
                action = ACTION_STOP_RINGER
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Falha ao parar EmergencyCallRingerService", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
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
                "EmergencyCallBypass:CallWakeLock"
            )

            // Inicializa o sintetizador de voz (TTS)
            tts = TextToSpeech(this, this)
        } catch (e: Throwable) {
            Log.e(TAG, "Falha no onCreate do ringer service", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }

                // Áudio do TTS no canal de alarme (furando o silêncio com prioridade alta)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)

                isTtsReady = true

                if (isRinging && currentCallerName.isNotBlank()) {
                    startSpeakingCallerNameLoop(currentCallerName)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao configurar TTS no onInit", e)
            }
        } else {
            Log.e(TAG, "Inicialização do TTS falhou com código: $status")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RINGER -> {
                val triggerType = intent.getStringExtra(EXTRA_TRIGGER_TYPE) ?: "CALL"
                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Contato de Emergência"
                val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
                val messageBody = intent.getStringExtra(EXTRA_MESSAGE_BODY)
                val mapsUrl = intent.getStringExtra(EXTRA_MAPS_URL)

                startEmergencyRinger(triggerType, callerName, callerNumber, messageBody, mapsUrl)
            }
            ACTION_STOP_RINGER -> {
                stopEmergencyRinger()
            }
            else -> stopEmergencyRinger()
        }
        return START_NOT_STICKY
    }

    private fun startEmergencyRinger(
        triggerType: String,
        callerName: String,
        callerNumber: String,
        messageBody: String?,
        mapsUrl: String?
    ) {
        try {
            val prefs = PreferencesManager.getInstance(this)
            isRinging = true
            currentCallerName = callerName

            Log.d(TAG, "Iniciando alarme de emergência ($triggerType) para $callerName")

            // 1. Notificação de primeiro plano segura (compatível com Android 14/15)
            val notification = createCallNotification(triggerType, callerName, callerNumber, messageBody, mapsUrl)
            startForegroundSafely(notification)

            // 2. Acorda a tela e processador
            try {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(2 * 60 * 1000L /* 2 min max de toque */)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao adquirir wakeLock", e)
            }

            // 3. SAI DO MODO NÃO PERTURBE (DND) se houver permissão
            if (PermissionHelper.hasDndAccess(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    previousDndFilter = notificationManager.currentInterruptionFilter
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    Log.d(TAG, "Filtro DND alterado para ALL (silêncio desativado)")
                } catch (e: Throwable) {
                    Log.e(TAG, "Erro ao alterar filtro DND", e)
                }
            }

            // 4. FORÇA MODO NORMAL DE TOQUE E VOLUME A 50% (em blocos isolados)
            if (audioManager != null) {
                try {
                    previousRingerMode = audioManager?.ringerMode
                    audioManager?.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (e: Throwable) {
                    Log.e(TAG, "Erro ao alterar ringerMode para NORMAL", e)
                }

                // Ajusta volume do ALARME para 50%
                try {
                    previousVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM)
                    val maxAlarm = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 15
                    val targetAlarm = (maxAlarm * (prefs.targetVolumePercent / 100.0f)).toInt().coerceAtLeast(1)
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
                    Log.d(TAG, "Volume do Alarme ajustado para $targetAlarm / $maxAlarm")
                } catch (e: Throwable) {
                    Log.e(TAG, "Erro ao ajustar volume do alarme", e)
                }

                // Ajusta volume do TOQUE para 50%
                try {
                    previousRingVolume = audioManager?.getStreamVolume(AudioManager.STREAM_RING)
                    val maxRing = audioManager?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 15
                    val targetRing = (maxRing * (prefs.targetVolumePercent / 100.0f)).toInt().coerceAtLeast(1)
                    audioManager?.setStreamVolume(AudioManager.STREAM_RING, targetRing, 0)
                    Log.d(TAG, "Volume do Toque ajustado para $targetRing / $maxRing")
                } catch (e: Throwable) {
                    Log.e(TAG, "Erro ao ajustar volume do toque", e)
                }
            }

            // 5. TOCA O TOQUE ESCOLHIDO (OU PADRÃO DO SISTEMA)
            startRingtonePlayback(prefs)

            // 6. FALA O NOME DO CONTATO REPETIDAMENTE ATÉ ATENDER
            if (prefs.isSpeakCallerNameEnabled && isTtsReady) {
                startSpeakingCallerNameLoop(callerName)
            }

            // 7. VIBRAÇÃO CONTÍNUA EM PADRÃO LONGO
            if (prefs.isVibrationEnabled) {
                try {
                    val pattern = EmergencyApplication.EMERGENCY_VIBRATION_PATTERN
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(pattern, 0)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Falha ao vibrar", e)
                }
            }

            // 8. Tenta abrir a tela de alerta sobre a interface
            try {
                val fullScreenIntent = Intent(this, EmergencyCallScreenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EmergencyCallScreenActivity.EXTRA_TRIGGER_TYPE, triggerType)
                    putExtra(EmergencyCallScreenActivity.EXTRA_CALLER_NAME, callerName)
                    putExtra(EmergencyCallScreenActivity.EXTRA_CALLER_NUMBER, callerNumber)
                    putExtra(EmergencyCallScreenActivity.EXTRA_MESSAGE_BODY, messageBody)
                    putExtra(EmergencyCallScreenActivity.EXTRA_MAPS_URL, mapsUrl)
                }
                startActivity(fullScreenIntent)
            } catch (e: Throwable) {
                Log.d(TAG, "Abertura direta da activity opcional (será mostrada via notification fullScreenIntent): ${e.message}")
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Erro crítico ao executar startEmergencyRinger", e)
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(2001, notification, fgsType)
            } else {
                startForeground(2001, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao iniciar startForeground com serviceType, tentando fallback simples", e)
            try {
                startForeground(2001, notification)
            } catch (e2: Throwable) {
                Log.e(TAG, "Falha no fallback de startForeground", e2)
            }
        }
    }

    private fun startRingtonePlayback(prefs: PreferencesManager) {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (prefs.selectedRingtoneUri.isNullOrBlank()) {
                // Toca o alarme crítico embutido (sirene / alerta máximo de emergência)
                try {
                    fallbackPlayer?.release()
                    fallbackPlayer = MediaPlayer().apply {
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
                    Log.d(TAG, "Alarme Crítico de Emergência (R.raw.critical_alarm) tocando com sucesso!")
                } catch (e: Throwable) {
                    Log.e(TAG, "Falha ao tocar R.raw.critical_alarm, tentando toque padrão", e)
                    val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(applicationContext, defaultUri)?.apply {
                        this.audioAttributes = audioAttributes
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                        play()
                    }
                }
            } else {
                // O usuário escolheu um toque específico no seletor
                val ringtoneUri = Uri.parse(prefs.selectedRingtoneUri)
                var played = false
                try {
                    ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)?.apply {
                        this.audioAttributes = audioAttributes
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                        play()
                    }
                    played = ringtone?.isPlaying == true
                } catch (e: Throwable) {
                    Log.e(TAG, "Falha no RingtoneManager para toque escolhido", e)
                }

                if (!played) {
                    try {
                        fallbackPlayer?.release()
                        fallbackPlayer = MediaPlayer().apply {
                            setAudioAttributes(audioAttributes)
                            setDataSource(applicationContext, ringtoneUri)
                            isLooping = true
                            prepare()
                            start()
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Falha no MediaPlayer para toque escolhido", e)
                    }
                }
            }

            // Coroutine de garantia: re-toca caso o som pare antes de atender
            ringtoneLoopJob?.cancel()
            ringtoneLoopJob = serviceScope.launch {
                while (isActive && isRinging) {
                    delay(3000L)
                    if (isRinging) {
                        try {
                            if (fallbackPlayer != null && fallbackPlayer?.isPlaying == false) {
                                fallbackPlayer?.start()
                            } else if (ringtone != null && ringtone?.isPlaying == false) {
                                ringtone?.play()
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Erro no loop de garantia do alarme", e)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro geral ao iniciar som do alarme", e)
        }
    }

    private fun startSpeakingCallerNameLoop(callerName: String) {
        speakingJob?.cancel()
        speakingJob = serviceScope.launch {
            val announcement = "Ligação de $callerName. $callerName está ligando."
            while (isActive && isRinging) {
                try {
                    if (isTtsReady && tts != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val params = Bundle().apply {
                                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                            }
                            tts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, params, "CALLER_NAME_UTTERANCE")
                        } else {
                            @Suppress("DEPRECATION")
                            val params = HashMap<String, String>().apply {
                                put(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM.toString())
                            }
                            tts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, params)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Erro ao falar nome no TTS", e)
                }
                delay(3500L)
            }
        }
    }

    private fun stopEmergencyRinger() {
        Log.d(TAG, "Parando toque de emergência e restaurando configurações")
        isRinging = false
        currentCallerName = ""

        speakingJob?.cancel()
        speakingJob = null

        ringtoneLoopJob?.cancel()
        ringtoneLoopJob = null

        try {
            tts?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao parar TTS", e)
        }

        try {
            ringtone?.stop()
            ringtone = null
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao parar Ringtone", e)
        }

        try {
            fallbackPlayer?.stop()
            fallbackPlayer?.release()
            fallbackPlayer = null
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao parar FallbackPlayer", e)
        }

        try {
            vibrator?.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao cancelar vibração", e)
        }

        // Restaura volumes e modo de toque
        if (audioManager != null) {
            try {
                if (previousVolume != null) {
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume!!, 0)
                    previousVolume = null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao restaurar volume do alarme", e)
            }

            try {
                if (previousRingVolume != null) {
                    audioManager?.setStreamVolume(AudioManager.STREAM_RING, previousRingVolume!!, 0)
                    previousRingVolume = null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao restaurar volume do toque", e)
            }

            try {
                if (previousRingerMode != null) {
                    audioManager?.ringerMode = previousRingerMode!!
                    previousRingerMode = null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao restaurar ringerMode", e)
            }
        }

        // Restaura o modo Não Perturbe original do usuário
        if (previousDndFilter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (PermissionHelper.hasDndAccess(this)) {
                    notificationManager.setInterruptionFilter(previousDndFilter!!)
                    previousDndFilter = null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao restaurar filtro DND", e)
            }
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao liberar wakeLock", e)
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao parar foreground", e)
        }
        stopSelf()
    }

    private fun createCallNotification(
        triggerType: String,
        callerName: String,
        callerNumber: String,
        messageBody: String?,
        mapsUrl: String?
    ): Notification {
        val fullScreenIntent = Intent(this, EmergencyCallScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EmergencyCallScreenActivity.EXTRA_TRIGGER_TYPE, triggerType)
            putExtra(EmergencyCallScreenActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(EmergencyCallScreenActivity.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(EmergencyCallScreenActivity.EXTRA_MESSAGE_BODY, messageBody)
            putExtra(EmergencyCallScreenActivity.EXTRA_MAPS_URL, mapsUrl)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (triggerType) {
            "SMS" -> "ALERTA SMS: $callerName"
            else -> "CHAMADA TELEFÔNICA: $callerName"
        }

        val text = "Volume 50% ativo. Falando: $callerName. Toque para abrir."

        val effectiveMapsUrl = mapsUrl?.takeIf { it.isNotBlank() }
            ?: com.emergencycall.bypass.util.LocationParser.extractMapsUrl(messageBody)

        val builder = NotificationCompat.Builder(this, EmergencyApplication.CHANNEL_ID_EMERGENCY_CALL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)

        if (!effectiveMapsUrl.isNullOrBlank()) {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(effectiveMapsUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val mapPendingIntent = PendingIntent.getActivity(
                this,
                202,
                mapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_dialog_map,
                "ABRIR NO MAPS",
                mapPendingIntent
            )
        }

        return builder.build()
    }

    override fun onDestroy() {
        stopEmergencyRinger()
        try {
            tts?.shutdown()
            tts = null
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao encerrar TTS", e)
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
