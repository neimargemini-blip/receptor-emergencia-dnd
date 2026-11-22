package com.emergencycall.bypass.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactPickerHelper {

    private const val TAG = "ContactPickerHelper"

    /**
     * Extrai o nome e o telefone a partir de uma URI retornada pelo seletor de contatos do Android.
     * Suporta nativamente tanto Phone.CONTENT_URI (ACTION_PICK com CommonDataKinds.Phone)
     * quanto Contacts.CONTENT_URI (seletor geral).
     */
    fun extractContactInfo(context: Context, contactUri: Uri): Pair<String, String>? {
        val cr = context.contentResolver
        var name = ""
        var phone = ""

        try {
            // 1. Tenta consultar diretamente todas as colunas da URI retornada
            // Se o seletor foi chamado com Phone.CONTENT_URI, as colunas NUMBER e DISPLAY_NAME
            // já estão presentes diretamente nesta linha com permissão concedida pelo Android
            cr.query(contactUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                    if (numIdx >= 0) {
                        phone = cursor.getString(numIdx) ?: ""
                    }
                    if (nameIdx >= 0) {
                        name = cursor.getString(nameIdx) ?: ""
                    } else {
                        val contactNameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        if (contactNameIdx >= 0) {
                            name = cursor.getString(contactNameIdx) ?: ""
                        }
                    }

                    // Se não encontrou o telefone na própria linha (caso de Contacts.CONTENT_URI),
                    // tenta buscar na tabela de telefones usando CONTACT_ID
                    if (phone.isBlank()) {
                        val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                        val contactId = if (idIdx >= 0) cursor.getString(idIdx) else ""

                        if (contactId.isNotBlank()) {
                            try {
                                cr.query(
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                    arrayOf(contactId),
                                    null
                                )?.use { phoneCursor ->
                                    if (phoneCursor.moveToFirst()) {
                                        val pNumIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                        val pNameIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                                        if (pNumIdx >= 0 && phone.isBlank()) {
                                            phone = phoneCursor.getString(pNumIdx) ?: ""
                                        }
                                        if (pNameIdx >= 0 && name.isBlank()) {
                                            name = phoneCursor.getString(pNameIdx) ?: ""
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Permissão READ_CONTACTS ausente ao consultar Phone por CONTACT_ID", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair contato da URI: $contactUri", e)
        }

        name = name.trim()
        phone = phone.trim()

        // Se o nome for vazio mas tiver telefone, usa o telefone como nome
        if (name.isBlank() && phone.isNotBlank()) {
            name = phone
        }

        Log.d(TAG, "Contato extraído com sucesso: nome='$name', telefone='$phone'")
        return if (name.isNotBlank() || phone.isNotBlank()) Pair(name, phone) else null
    }
}
