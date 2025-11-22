package com.example.kotlinroomdatabase.nfc

import android.app.Service
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HCEservice : HostApduService() {

    companion object {
        const val TAG = "HceService"
        const val STUDENT_AID = "F14954574F58"
        @OptIn(InternalSerializationApi::class)
        var currentStudent: Student? = null
    }

    @OptIn(InternalSerializationApi::class)
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val command = apdu.toHexString()

        return when {
            command.contains(STUDENT_AID) -> {
                currentStudent?.let { student ->
                    val studentData = "${student.id}:${student.studentName}"
                    buildSuccessfulResponse(studentData.toByteArray())
                } ?: buildErrorResponse("there is no such student")
            }
            else -> buildErrorResponse("Unknown command")
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated: $reason")
    }

    private fun buildSuccessfulResponse(data: ByteArray): ByteArray {
        return byteArrayOf(0x90.toByte(), 0x00) + data
    }

    private fun buildErrorResponse(message: String): ByteArray {
        return byteArrayOf(0x6A, 0x82.toByte())
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}