package com.emergencycall.bypass.data.local

import android.content.Context
import android.content.SharedPreferences
import com.emergencycall.bypass.data.model.EmergencyContact
import com.emergencycall.bypass.util.PhoneNormalizer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContactStorage(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emergency_contacts_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 1. Contatos de quem você RECEBE (podem furar o Não Perturbe)
    private val _receiverContactsFlow = MutableStateFlow<List<EmergencyContact>>(emptyList())
    val receiverContactsFlow: StateFlow<List<EmergencyContact>> = _receiverContactsFlow.asStateFlow()

    // 2. Contatos para quem você ENVIA SOS (recebem SMS a cada 1m com sua localização)
    private val _senderContactsFlow = MutableStateFlow<List<EmergencyContact>>(emptyList())
    val senderContactsFlow: StateFlow<List<EmergencyContact>> = _senderContactsFlow.asStateFlow()

    init {
        migrateOldContactsIfNeeded()
        loadAllContacts()
    }

    private fun migrateOldContactsIfNeeded() {
        val oldJson = prefs.getString(KEY_OLD_CONTACTS, null)
        if (!oldJson.isNullOrBlank() && !prefs.contains(KEY_RECEIVER_CONTACTS)) {
            prefs.edit()
                .putString(KEY_RECEIVER_CONTACTS, oldJson)
                .putString(KEY_SENDER_CONTACTS, oldJson)
                .remove(KEY_OLD_CONTACTS)
                .apply()
        }
    }

    // ==================== CONTATOS DO RECEPTOR ====================

    @Synchronized
    fun getReceiverContacts(): List<EmergencyContact> {
        val json = prefs.getString(KEY_RECEIVER_CONTACTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<EmergencyContact>>() {}.type
            gson.fromJson<List<EmergencyContact>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveReceiverContact(contact: EmergencyContact) {
        val current = getReceiverContacts().toMutableList()
        val index = current.indexOfFirst { it.id == contact.id }
        if (index >= 0) {
            current[index] = contact
        } else {
            current.add(contact)
        }
        persistReceiver(current)
    }

    @Synchronized
    fun removeReceiverContact(contactId: String) {
        val current = getReceiverContacts().toMutableList()
        current.removeAll { it.id == contactId }
        persistReceiver(current)
    }

    @Synchronized
    fun toggleReceiverContact(contactId: String) {
        val current = getReceiverContacts().toMutableList()
        val index = current.indexOfFirst { it.id == contactId }
        if (index >= 0) {
            val item = current[index]
            current[index] = item.copy(isEnabled = !item.isEnabled)
            persistReceiver(current)
        }
    }

    /**
     * Verifica se o número ou nome de quem está ligando pertence a algum contato ativo do receptor.
     */
    fun findMatchingReceiverContact(incomingNumberOrName: String?): EmergencyContact? {
        if (incomingNumberOrName.isNullOrBlank()) return null
        val activeContacts = getReceiverContacts().filter { it.isEnabled }

        // 1. Match por número de telefone normalizado
        val phoneMatch = activeContacts.firstOrNull { contact ->
            PhoneNormalizer.matches(incomingNumberOrName, contact.phoneNumber)
        }
        if (phoneMatch != null) return phoneMatch

        // 2. Match por nome: igualdade exata ou nome inteiro como palavra dentro do texto
        val trimmed = incomingNumberOrName.trim()
        return activeContacts.firstOrNull { contact ->
            val name = contact.name.trim()
            if (name.isEmpty()) return@firstOrNull false
            name.equals(trimmed, ignoreCase = true) ||
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(trimmed)
        }
    }

    private fun persistReceiver(list: List<EmergencyContact>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_RECEIVER_CONTACTS, json).apply()
        _receiverContactsFlow.value = list
    }

    // ==================== CONTATOS DO EMISSOR (ENVIAR SOS) ====================

    @Synchronized
    fun getSenderContacts(): List<EmergencyContact> {
        val json = prefs.getString(KEY_SENDER_CONTACTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<EmergencyContact>>() {}.type
            gson.fromJson<List<EmergencyContact>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveSenderContact(contact: EmergencyContact) {
        val current = getSenderContacts().toMutableList()
        val index = current.indexOfFirst { it.id == contact.id }
        if (index >= 0) {
            current[index] = contact
        } else {
            current.add(contact)
        }
        persistSender(current)
    }

    @Synchronized
    fun removeSenderContact(contactId: String) {
        val current = getSenderContacts().toMutableList()
        current.removeAll { it.id == contactId }
        persistSender(current)
    }

    @Synchronized
    fun toggleSenderContact(contactId: String) {
        val current = getSenderContacts().toMutableList()
        val index = current.indexOfFirst { it.id == contactId }
        if (index >= 0) {
            val item = current[index]
            current[index] = item.copy(isEnabled = !item.isEnabled)
            persistSender(current)
        }
    }

    private fun persistSender(list: List<EmergencyContact>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_SENDER_CONTACTS, json).apply()
        _senderContactsFlow.value = list
    }

    private fun loadAllContacts() {
        _receiverContactsFlow.value = getReceiverContacts()
        _senderContactsFlow.value = getSenderContacts()
    }

    companion object {
        private const val KEY_OLD_CONTACTS = "key_emergency_contacts_json"
        private const val KEY_RECEIVER_CONTACTS = "key_receiver_contacts_json"
        private const val KEY_SENDER_CONTACTS = "key_sender_contacts_json"

        @Volatile
        private var instance: ContactStorage? = null

        fun getInstance(context: Context): ContactStorage {
            return instance ?: synchronized(this) {
                instance ?: ContactStorage(context.applicationContext).also { instance = it }
            }
        }
    }
}
