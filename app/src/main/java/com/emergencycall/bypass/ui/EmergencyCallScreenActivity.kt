package com.emergencycall.bypass.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.emergencycall.bypass.service.EmergencyCallRingerService
import com.emergencycall.bypass.ui.theme.EmergencyCallTheme

class EmergencyCallScreenActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EmergencyCallScreenAct"
        const val EXTRA_TRIGGER_TYPE = "trigger_type"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_MESSAGE_BODY = "message_body"
        const val EXTRA_MAPS_URL = "maps_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setupLockScreenFlags()
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao configurar flags de lockscreen", e)
        }

        val triggerType = intent.getStringExtra(EXTRA_TRIGGER_TYPE) ?: "CALL"
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Contato de Emergência"
        val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
        val messageBody = intent.getStringExtra(EXTRA_MESSAGE_BODY)
        val mapsUrl = intent.getStringExtra(EXTRA_MAPS_URL)

        setContent {
            EmergencyCallTheme {
                EmergencyCallScreen(
                    triggerType = triggerType,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    messageBody = messageBody,
                    mapsUrl = mapsUrl,
                    onSilence = {
                        EmergencyCallRingerService.stop(this)
                        finish()
                    }
                )
            }
        }
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }
}
