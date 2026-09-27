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
        val STATUS_CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
        val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return STATUS_FAILED

        val hexCommand = commandApdu.toHexString()
        Log.i(TAG, "Received APDU from Reader: $hexCommand")

        // KA-06: Reject arbitrary commands; only respond to standard SELECT by DF/AID
        val cla = commandApdu[0]
        val ins = commandApdu[1]
        val p1 = commandApdu[2]

        if (cla != 0x00.toByte()) {
            Log.w(TAG, "Unsupported CLA: ${"%02X".format(cla)}")
            return STATUS_CLA_NOT_SUPPORTED
        }

        if (ins != 0xA4.toByte() || p1 != 0x04.toByte()) {
            Log.w(TAG, "Unsupported instruction: INS=${"%02X".format(ins)}, P1=${"%02X".format(p1)}")
            return STATUS_INS_NOT_SUPPORTED
        }

        // Verify that the command selects our registered STUDENT_AID
        val hexUpper = hexCommand.uppercase()
        if (!hexUpper.contains(STUDENT_AID)) {
            Log.w(TAG, "SELECT command did not target STUDENT_AID: $hexCommand")
            return STATUS_FILE_NOT_FOUND
        }

        val payload = getStoredNfcPayload()

        return if (!payload.isNullOrBlank()) {
            Log.i(TAG, "Sending verified HCE Payload to Reader: $payload")
            val payloadBytes = payload.toByteArray(Charset.forName("UTF-8"))
            val response = payloadBytes + STATUS_SUCCESS
            
            // Notify student UI
            val intent = Intent("NFC_MARK_SUCCESS")
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
            response
        } else {
            Log.e(TAG, "HCE payload empty or user not authenticated!")
            STATUS_FILE_NOT_FOUND
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
    }

    private fun getStoredNfcPayload(): String? {
        val authPrefs = applicationContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val token = authPrefs.getString("auth_token", null)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "HCE rejected: no active authenticated session")
            return null
        }

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