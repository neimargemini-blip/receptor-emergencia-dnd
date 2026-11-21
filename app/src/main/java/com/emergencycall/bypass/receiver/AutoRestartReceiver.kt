package com.emergencycall.bypass.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.service.EmergencyMonitorService

/**
 * Receiver responsável por manter o aplicativo vivo e reiniciá-lo automaticamente
 * caso o processo seja finalizado, o app seja fechado na lista de recentes,
 * o celular seja reiniciado ou a tela seja desbloqueada.
 */
class AutoRestartReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AutoRestartReceiver"
        const val ACTION_AUTO_RESTART = "com.emergencycall.bypass.action.AUTO_RESTART"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Evento de sistema recebido para watchdog/auto-reinício: $action")

        val prefs = PreferencesManager.getInstance(context)

        // Se o monitoramento estiver ativo e a opção de auto-reinício habilitada
        if (prefs.isMonitoringActive && prefs.isAutoRestartEnabled) {
            try {
                Log.i(TAG, "Reiniciando serviço EmergencyMonitorService automaticamente...")
                EmergencyMonitorService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao auto-reiniciar serviço de emergência", e)
            }
        } else {
            Log.d(TAG, "Auto-reinício ignorado pois monitoramento ou auto-reinício estão desativados pelo usuário")
        }
    }
}
