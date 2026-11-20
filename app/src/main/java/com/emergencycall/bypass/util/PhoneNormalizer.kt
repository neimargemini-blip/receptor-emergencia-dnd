package com.emergencycall.bypass.util

object PhoneNormalizer {

    /**
     * Remove todos os caracteres que não sejam dígitos.
     */
    fun extractDigits(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        return phone.replace(Regex("[^0-9]"), "")
    }

    /**
     * Verifica se o número que está ligando corresponde a um contato cadastrado.
     * Considera variações comuns de operadora no Brasil:
     * - Presença ou ausência do código de país +55
     * - Presença ou ausência do DDD
     * - Presença ou ausência do 9º dígito
     * Compara os últimos 8 ou 9 dígitos para garantir correspondência precisa.
     */
    fun matches(incomingNumber: String?, storedNumber: String?): Boolean {
        val incomingDigits = extractDigits(incomingNumber)
        val storedDigits = extractDigits(storedNumber)

        if (incomingDigits.isBlank() || storedDigits.isBlank()) {
            return false
        }

        // Correspondência exata de dígitos
        if (incomingDigits == storedDigits) {
            return true
        }

        // Compara os últimos 8 dígitos (número fixo ou celular sem 9)
        val minLen = 8
        if (incomingDigits.length >= minLen && storedDigits.length >= minLen) {
            val incomingTail8 = incomingDigits.takeLast(minLen)
            val storedTail8 = storedDigits.takeLast(minLen)
            if (incomingTail8 == storedTail8) {
                // Se ambos tiverem DDD (>= 10 dígitos), valida se o DDD também bate
                if (incomingDigits.length >= 10 && storedDigits.length >= 10) {
                    val incomingDdd = incomingDigits.takeLast(10).take(2)
                    val storedDdd = storedDigits.takeLast(10).take(2)
                    return incomingDdd == storedDdd
                }
                return true
            }
        }

        // Compara os últimos 9 dígitos (celulares com 9º dígito)
        if (incomingDigits.length >= 9 && storedDigits.length >= 9) {
            val incomingTail9 = incomingDigits.takeLast(9)
            val storedTail9 = storedDigits.takeLast(9)
            if (incomingTail9 == storedTail9) {
                return true
            }
        }

        return false
    }
}
