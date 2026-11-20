package com.emergencycall.bypass.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactPickerHelper {

    /**
     * Extrai o nome e o telefone a partir de uma URI retornada pelo seletor de contatos do Android.
     */
    fun extractContactInfo(context: Context, contactUri: Uri): Pair<String, String>? {
        val cr = context.contentResolver
        var name = ""
        var phone = ""

        try {
            cr.query(contactUri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else ""
                    name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""

                    if (id.isNotBlank()) {
                        cr.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )?.use { phoneCursor ->
                            if (phoneCursor.moveToFirst()) {
                                val numberIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                if (numberIndex >= 0) {
                                    phone = phoneCursor.getString(numberIndex) ?: ""
                                }
                            }
                        }
                    }
                }
            }

            // Fallback caso a URI retorne direto de CommonDataKinds.Phone
            if (phone.isBlank()) {
                cr.query(contactUri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        if (numIdx >= 0) phone = cursor.getString(numIdx) ?: ""
                        if (name.isBlank() && nameIdx >= 0) name = cursor.getString(nameIdx) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (name.isNotBlank() || phone.isNotBlank()) Pair(name, phone) else null
    }
}
