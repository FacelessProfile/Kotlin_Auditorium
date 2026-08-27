package com.example.kotlinroomdatabase.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.nio.charset.Charset

class HCEservice : HostApduService() {

    companion object {
        const val TAG = "HceService"
        const val STUDENT_AID = "F0010203040506"

        // SharedPrefs keys
        const val PREFS_NAME = "student_prefs"
        const val KEY_NFC_PAYLOAD = "nfc_payload"
        val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        val STATUS_FAILED = byteArrayOf(0x6F, 0x00)
        val STATUS_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED

        val hexCommand = commandApdu.toHexString()
        Log.i(TAG, "Received APDU from Reader: $hexCommand")

        val payload = getStoredNfcPayload()

        return if (!payload.isNullOrBlank()) {
            Log.i(TAG, "Sending HCE Payload to Reader: $payload")
            val payloadBytes = payload.toByteArray(Charset.forName("UTF-8"))
            val response = payloadBytes + STATUS_SUCCESS
            
            // Notify student UI
            val intent = Intent("NFC_MARK_SUCCESS")
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
            response
        } else {
            Log.e(TAG, "HCE payload empty in prefs!")
            byteArrayOf(0x6A, 0x82.toByte())
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
    }

    private fun getStoredNfcPayload(): String? {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val role = prefs.getString("user_role", "student")
        if (role != "student") {
            Log.w(TAG, "HCE triggered for non-student role: $role")
            return null
        }

        var payload = prefs.getString(KEY_NFC_PAYLOAD, null)
        val sId = prefs.getInt("current_student_id", 0)
        val sName = prefs.getString("student_name", "Student") ?: "Student"

        if (payload.isNullOrBlank() || payload.startsWith("STUDENT:50:") || !payload.startsWith("STUDENT:")) {
            if (sId > 0) {
                payload = "STUDENT:$sId:$sName"
                prefs.edit().putString(KEY_NFC_PAYLOAD, payload).apply()
            }
        }
        return payload
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}