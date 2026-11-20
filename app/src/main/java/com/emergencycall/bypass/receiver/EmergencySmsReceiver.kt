package com.emergencycall.bypass.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.emergencycall.bypass.data.local.AlertStorage
import com.emergencycall.bypass.data.local.ContactStorage
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.data.model.AlertSource
import com.emergencycall.bypass.data.model.EmergencyAlert
import com.emergencycall.bypass.service.EmergencyCallRingerService
import com.emergencycall.bypass.util.LocationParser

class EmergencySmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EmergencySmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = PreferencesManager.getInstance(context)
        if (!prefs.isMonitoringActive) {
            Log.d(TAG, "Monitoramento desativado, ignorando SMS")
            return
        }

        try {
            val messages: Array<SmsMessage> = try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } catch (e: Exception) {
                null
            } ?: extractMessagesFromPdus(intent) ?: return

            if (messages.isEmpty()) return

            val senderNumber = messages[0].displayOriginatingAddress
                ?: messages[0].originatingAddress
                ?: return

            val messageBody = messages.joinToString("") {
                it.displayMessageBody ?: it.messageBody ?: ""
            }

            Log.d(TAG, "SMS recebido de $senderNumber: $messageBody")

            val contactStorage = ContactStorage.getInstance(context)
            // Busca especificamente na lista de contatos do RECEBER
            val matchedContact = contactStorage.findMatchingReceiverContact(senderNumber)

            if (matchedContact != null) {
                Log.i(TAG, "SMS de contato VIP identificado: ${matchedContact.name} ($senderNumber)")

                val mapsUrl = LocationParser.extractMapsUrl(messageBody)

                val alert = EmergencyAlert(
                    senderName = matchedContact.name,
                    senderNumber = senderNumber,
                    messageBody = messageBody,
                    mapsUrl = mapsUrl,
                    source = AlertSource.SMS
                )
                AlertStorage.getInstance(context).addAlert(alert)

                EmergencyCallRingerService.start(
                    context = context,
                    callerName = matchedContact.name,
                    callerNumber = senderNumber,
                    triggerType = "SMS",
                    messageBody = messageBody,
                    mapsUrl = mapsUrl
                )
            } else {
                Log.d(TAG, "SMS ignorado (remetente não está na lista de contatos autorizados a receber: $senderNumber)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar SMS de emergência", e)
        }
    }

    private fun extractMessagesFromPdus(intent: Intent): Array<SmsMessage>? {
        val bundle = intent.extras ?: return null
        val pdus = bundle.get("pdus") as? Array<*> ?: return null
        val format = bundle.getString("format")
        return pdus.mapNotNull { pdu ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }
            } catch (e: Exception) {
                null
            }
        }.toTypedArray()
    }
}
