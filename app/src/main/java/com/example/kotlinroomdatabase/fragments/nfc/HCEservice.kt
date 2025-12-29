package com.example.kotlinroomdatabase.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import android.content.Context
import java.nio.charset.Charset
import java.util.Arrays

class HCEservice : HostApduService() {

    companion object {
        const val TAG = "HceService"
        const val STUDENT_AID = "F14954574F58"

        // Ключи для SharedPrefs
        const val PREFS_NAME = "student_prefs"
        const val KEY_NFC_PAYLOAD = "nfc_payload"
        val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        val STATUS_FAILED = byteArrayOf(0x6F, 0x00)
        val STATUS_CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
        val STATUS_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val SELECT_INS = 0xA4.toByte()
        val DEFAULT_CLA = 0x00.toByte()
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED

        val hexCommand = commandApdu.toHexString()
        Log.d(TAG, "Received APDU: $hexCommand")
        if (hexCommand.startsWith("00A40400") && hexCommand.contains(STUDENT_AID)) {

            val payload = getStoredNfcPayload()

            return if (!payload.isNullOrBlank()) {
                Log.d(TAG, "Sending Payload to Reader: $payload")
                val payloadBytes = payload.toByteArray(Charset.forName("UTF-8"))
                payloadBytes + STATUS_SUCCESS
            } else {
                Log.e(TAG, "Payload is empty in SharedPreferences!")
                byteArrayOf(0x6A, 0x82.toByte())
            }
        }
        return STATUS_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
    }

    private fun getStoredNfcPayload(): String? {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NFC_PAYLOAD, null)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}