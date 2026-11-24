package com.emergencycall.bypass.util

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

object SmsSenderHelper {
    private const val TAG = "SmsSenderHelper"

    /**
     * Envia mensagem SMS multipart para garantir que mensagens longas com link do mapa e coordenadas não sejam cortadas.
     */
    fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            if (cleanNumber.isBlank()) {
                Log.w(TAG, "Número de telefone vazio/inválido para envio de SMS: '$phoneNumber'")
                return false
            }

            val smsManager: SmsManager = getSmsManager(context)
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanNumber, null, message, null, null)
            }
            Log.d(TAG, "SMS de SOS enviado com sucesso para $cleanNumber (${parts.size} partes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar SMS via gerenciador principal para $phoneNumber: ${e.message}", e)
            // Tentativa de fallback com SmsManager.getDefault()
            try {
                @Suppress("DEPRECATION")
                val fallbackManager = SmsManager.getDefault()
                val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
                val parts = fallbackManager.divideMessage(message)
                if (parts.size > 1) {
                    fallbackManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
                } else {
                    fallbackManager.sendTextMessage(cleanNumber, null, message, null, null)
                }
                Log.d(TAG, "SMS de SOS enviado com sucesso via fallback padrão para $cleanNumber")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Falha definitiva no envio de SMS para $phoneNumber", e2)
                false
            }
        }
    }

    private fun getSmsManager(context: Context): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
                if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    val systemManager = context.getSystemService(SmsManager::class.java)
                    if (systemManager != null) {
                        return systemManager.createForSubscriptionId(subscriptionId)
                    }
                }
                val systemManager = context.getSystemService(SmsManager::class.java)
                if (systemManager != null) return systemManager
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao obter SmsManager via getSystemService: ${e.message}")
            }
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }
}
