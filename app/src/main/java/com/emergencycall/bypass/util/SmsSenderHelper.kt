package com.emergencycall.bypass.util

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsSenderHelper {
    private const val TAG = "SmsSenderHelper"

    /**
     * Envia mensagem SMS multipart para garantir que mensagens longas com link do mapa não sejam cortadas.
     */
    fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            Log.d(TAG, "SMS de SOS enviado com sucesso para $cleanNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar SMS para $phoneNumber", e)
            false
        }
    }
}
