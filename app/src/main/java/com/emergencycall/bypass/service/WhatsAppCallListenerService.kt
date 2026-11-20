package com.emergencycall.bypass.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.emergencycall.bypass.data.local.AlertStorage
import com.emergencycall.bypass.data.local.ContactStorage
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.data.model.AlertSource
import com.emergencycall.bypass.data.model.EmergencyAlert
import com.emergencycall.bypass.data.model.EmergencyContact
import com.emergencycall.bypass.util.PermissionHelper
import com.emergencycall.bypass.util.PhoneNormalizer
import java.text.Normalizer

class WhatsAppCallListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsAppCallListener"

        val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.gbwhatsapp",
            "com.whatsapp.plus"
        )

        private var isCallRinging = false
        private var activeCallKey: String? = null

        fun rebindIfNecessary(context: Context) {
            if (PermissionHelper.hasNotificationListenerAccess(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        val component = ComponentName(context, WhatsAppCallListenerService::class.java)
                        requestRebind(component)
                        Log.d(TAG, "requestRebind chamado para reativar o serviço ouvinte")
                    } catch (e: Throwable) {
                        Log.d(TAG, "Tentativa de requestRebind: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, ">>> WhatsAppCallListenerService CONECTADO COM SUCESSO ao sistema Android! <<<")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "WhatsAppCallListenerService desconectado. Solicitando reconexão...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, WhatsAppCallListenerService::class.java))
            } catch (e: Throwable) {
                Log.e(TAG, "Erro ao solicitar requestRebind na desconexão", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        if (!WHATSAPP_PACKAGES.contains(pkg)) return

        val prefs = PreferencesManager.getInstance(this)
        if (!prefs.isMonitoringActive) return

        // 1. Verifica se a notificação é uma chamada de voz/vídeo ativa
        if (!isIncomingCallNotification(sbn)) {
            Log.d(TAG, "Notificação do WhatsApp recebida, mas não identificada como chamada: key=${sbn.key}")
            return
        }

        Log.d(TAG, "Notificação de Chamada WhatsApp detectada! Tag=${sbn.tag}, Key=${sbn.key}")

        // 2. Busca o contato autorizado na lista do RECEBER
        val contactStorage = ContactStorage.getInstance(this)
        val matchedContact = findMatchingContactFromNotification(contactStorage, sbn)

        if (matchedContact != null) {
            Log.i(TAG, ">>> Contato VIP identificado na Chamada WhatsApp: ${matchedContact.name} (${matchedContact.phoneNumber})! Disparando alarme crítico! <<<")
            activeCallKey = sbn.key
            isCallRinging = true

            AlertStorage.getInstance(this).addAlert(
                EmergencyAlert(
                    senderName = matchedContact.name,
                    senderNumber = matchedContact.phoneNumber,
                    messageBody = "Chamada de Voz/Vídeo recebida pelo WhatsApp",
                    mapsUrl = null,
                    source = AlertSource.WHATSAPP_CALL
                )
            )

            // Dispara som de alarme crítico a 50% e anúncio por voz até atender
            EmergencyCallRingerService.start(
                context = this,
                callerName = matchedContact.name,
                callerNumber = matchedContact.phoneNumber,
                triggerType = "WHATSAPP_CALL"
            )
        } else {
            Log.d(TAG, "Chamada WhatsApp recebida, mas contato não pertence à lista de autorizados a furar DND")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        if (!WHATSAPP_PACKAGES.contains(pkg)) return

        if (sbn.key == activeCallKey || (isCallRinging && isIncomingCallNotification(sbn))) {
            Log.d(TAG, "Chamada do WhatsApp atendida ou encerrada. Parando toque de emergência.")
            activeCallKey = null
            isCallRinging = false
            EmergencyCallRingerService.stop(this)
        }
    }

    private fun isIncomingCallNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val extras = notification.extras ?: Bundle()

        val category = notification.category?.lowercase().orEmpty()
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId?.lowercase().orEmpty()
        } else ""

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().lowercase()
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty().lowercase()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().lowercase()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().lowercase()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().lowercase()
        val ticker = notification.tickerText?.toString().orEmpty().lowercase()

        val fullText = "$title $titleBig $text $bigText $subText $ticker"

        // Descarta notificações de chamadas perdidas ou encerradas
        if (fullText.contains("perdida") || fullText.contains("missed") ||
            fullText.contains("encerrada") || fullText.contains("ended") ||
            fullText.contains("ocupado") || fullText.contains("busy")) {
            return false
        }

        // Categoria de chamada
        if (category == Notification.CATEGORY_CALL || category == "call") {
            return true
        }

        // Canal de chamadas dedicado do WhatsApp
        if (channelId.contains("call") || channelId.contains("chamada") || channelId.contains("voip")) {
            return true
        }

        // Ações de chamada (Atender / Recusar / Rejeitar)
        val hasCallActions = notification.actions?.any { action ->
            val at = action.title?.toString()?.lowercase().orEmpty()
            at.contains("atender") || at.contains("recusar") ||
            at.contains("answer") || at.contains("decline") ||
            at.contains("rejeitar") || at.contains("ignorar") ||
            at.contains("responder")
        } == true
        if (hasCallActions) {
            return true
        }

        // Palavras-chave no texto da notificação (em português e inglês)
        val keywords = listOf(
            "chamada", "chamando", "ligação", "ligando", "tocando",
            "videochamada", "vídeo chamada", "áudio chamada",
            "incoming call", "voice call", "video call", "ringing"
        )
        if (keywords.any { fullText.contains(it) }) {
            return true
        }

        // Notificação contínua (ongoing) de alta prioridade do WhatsApp
        if (sbn.isOngoing && notification.priority >= Notification.PRIORITY_DEFAULT) {
            return true
        }

        return false
    }

    private fun findMatchingContactFromNotification(
        contactStorage: ContactStorage,
        sbn: StatusBarNotification
    ): EmergencyContact? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle()

        val candidates = mutableListOf<String>()

        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.let { candidates.add(it) }
        extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let { candidates.add(it) }
        notification.tickerText?.toString()?.let { candidates.add(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)?.forEach { person ->
                    person.name?.toString()?.let { candidates.add(it) }
                }
            } catch (e: Throwable) {
                // ignore
            }
        }

        val activeContacts = contactStorage.getReceiverContacts().filter { it.isEnabled }
        if (activeContacts.isEmpty()) return null

        val combinedText = candidates.joinToString(" ")
        Log.d(TAG, "Textos extraídos da notificação: '$combinedText'")

        // 1. Tenta correspondência direta em cada campo individual
        for (candidate in candidates) {
            val match = contactStorage.findMatchingReceiverContact(candidate)
            if (match != null) return match
        }

        // 2. Tenta correspondência no texto combinado
        val matchCombined = contactStorage.findMatchingReceiverContact(combinedText)
        if (matchCombined != null) return matchCombined

        // 3. Correspondência inteligente (sem acentos, por palavras e por dígitos de telefone)
        for (contact in activeContacts) {
            if (matchesSmart(contact, combinedText)) {
                return contact
            }
        }

        return null
    }

    private fun matchesSmart(contact: EmergencyContact, text: String): Boolean {
        val normContact = normalize(contact.name)
        val normText = normalize(text)

        if (normContact.isBlank() || normText.isBlank()) return false

        // Match direto sem acento
        if (normText.contains(normContact) || normContact.contains(normText)) {
            return true
        }

        // Match por dígitos de telefone presentes no texto
        val textDigits = PhoneNormalizer.extractDigits(text)
        if (textDigits.length >= 8 && PhoneNormalizer.matches(textDigits, contact.phoneNumber)) {
            return true
        }

        // Match por palavras significativas (ex: primeiro nome do contato com pelo menos 3 caracteres)
        val contactWords = normContact.split(" ").filter { it.length >= 3 }
        val textWords = normText.split(" ").toSet()

        for (word in contactWords) {
            if (textWords.contains(word)) {
                return true
            }
        }

        return false
    }

    private fun normalize(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
