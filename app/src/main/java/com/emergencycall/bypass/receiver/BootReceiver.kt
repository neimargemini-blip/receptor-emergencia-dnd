package com.emergencycall.bypass.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.service.EmergencyMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d("BootReceiver", "Aparelho reiniciado. Reiniciando monitor de emergência.")
            val prefs = PreferencesManager.getInstance(context)
            if (prefs.isMonitoringActive) {
                EmergencyMonitorService.start(context)
            }
        }
    }
}
