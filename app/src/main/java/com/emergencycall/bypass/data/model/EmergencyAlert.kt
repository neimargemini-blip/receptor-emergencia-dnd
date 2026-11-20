package com.emergencycall.bypass.data.model

import java.util.UUID

enum class AlertSource(val label: String) {
    PHONE_CALL("Ligação Telefônica"),
    SMS("Mensagem SMS"),
    WHATSAPP_CALL("Chamada WhatsApp"),
    WHATSAPP_MESSAGE("Mensagem WhatsApp")
}

data class EmergencyAlert(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val senderNumber: String,
    val messageBody: String? = null,
    val mapsUrl: String? = null,
    val source: AlertSource = AlertSource.SMS,
    val timestamp: Long = System.currentTimeMillis()
)
