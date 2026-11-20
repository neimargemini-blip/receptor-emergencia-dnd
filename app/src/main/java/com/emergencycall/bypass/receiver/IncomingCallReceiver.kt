package com.emergencycall.bypass.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.emergencycall.bypass.data.local.AlertStorage
import com.emergencycall.bypass.data.local.ContactStorage
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.data.model.AlertSource
import com.emergencycall.bypass.data.model.EmergencyAlert
import com.emergencycall.bypass.service.EmergencyCallRingerService

class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCallReceiver"
        private var isRingingTriggered = false
        private var lastTriggeredNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val prefs = PreferencesManager.getInstance(context)
        if (!prefs.isMonitoringActive) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?: intent.getStringExtra("incoming_number")

        Log.d(TAG, "Telephony broadcast recebido: state=$state, incomingNumber=$incomingNumber, isTriggered=$isRingingTriggered")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (incomingNumber.isNullOrBlank()) {
                    Log.d(TAG, "Chamada tocando (RINGING), mas número ainda ausente neste broadcast. Aguardando o próximo...")
                    return
                }

                // Evita disparar repetido para o mesmo número na mesma chamada em andamento
                if (isRingingTriggered && incomingNumber == lastTriggeredNumber) {
                    Log.d(TAG, "Alarme já ativo para o número $incomingNumber")
                    return
                }

                val contactStorage = ContactStorage.getInstance(context)
                val matchingContact = contactStorage.findMatchingReceiverContact(incomingNumber)

                if (matchingContact != null) {
                    Log.i(TAG, ">>> Contato VIP detectado: ${matchingContact.name} ($incomingNumber). Disparando alarme com bypass DND! <<<")
                    isRingingTriggered = true
                    lastTriggeredNumber = incomingNumber

                    AlertStorage.getInstance(context).addAlert(
                        EmergencyAlert(
                            senderName = matchingContact.name,
                            senderNumber = matchingContact.phoneNumber,
                            messageBody = "Ligação Telefônica Recebida",
                            mapsUrl = null,
                            source = AlertSource.PHONE_CALL
                        )
                    )

                    EmergencyCallRingerService.start(
                        context = context,
                        callerName = matchingContact.name,
                        callerNumber = matchingContact.phoneNumber,
                        triggerType = "CALL"
                    )
                } else {
                    Log.d(TAG, "Número recebido não está na lista VIP de recebimento: $incomingNumber")
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "Chamada finalizada ou atendida ($state). Resetando estado e parando som de emergência.")
                isRingingTriggered = false
                lastTriggeredNumber = null
                EmergencyCallRingerService.stop(context)
            }
        }
    }
}
