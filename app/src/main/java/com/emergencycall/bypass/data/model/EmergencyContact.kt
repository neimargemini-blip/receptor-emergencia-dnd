package com.emergencycall.bypass.data.model

data class EmergencyContact(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val phoneNumber: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
