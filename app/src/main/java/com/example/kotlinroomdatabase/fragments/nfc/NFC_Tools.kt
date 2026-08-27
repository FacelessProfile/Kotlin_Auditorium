package com.example.kotlinroomdatabase.fragments.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.serialization.InternalSerializationApi
import java.io.IOException
import java.nio.charset.Charset

abstract class  NFC_Tools : Fragment() {
    protected var nfcAdapter: NfcAdapter? = null
    protected var isReadingMode = false
    protected var isInfiniteMode = false
    protected val nfcTimeoutHandler = Handler(Looper.getMainLooper())
    protected val NFC_READ_TIMEOUT = 15000L
    private val SERVICE_AID = "F0010203040506"

    protected fun startNfcReadingMode(infiniteMode: Boolean = false) {
        if (nfcAdapter == null || isReadingMode) return

        isReadingMode = true
        isInfiniteMode = infiniteMode

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300)
        }

        nfcAdapter?.enableReaderMode(
            requireActivity(),
            nfcReaderCallback,
            flags,
            options
        )

        if (!infiniteMode) {
            nfcTimeoutHandler.postDelayed({ stopNfcReadingModeByTimeout() }, NFC_READ_TIMEOUT)
        }

        showNfcReadingStartedMessage()
    }

    protected fun setupNfcReading() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        if (nfcAdapter == null) {
            showNfcNotSupportedMessage()
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(requireContext(), "Включите NFC в настройках", Toast.LENGTH_LONG).show()
        }
    }

    protected fun stopNfcReadingModeByTimeout() {
        if (!isReadingMode || isInfiniteMode) return
        isReadingMode = false
        nfcAdapter?.disableReaderMode(requireActivity())
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
        showNfcReadingStoppedMessage()
    }

    protected fun stopNfcReadingMode() {
        if (!isReadingMode) return
        isReadingMode = false
        nfcAdapter?.disableReaderMode(requireActivity())
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
    }

    // Callback при подносе метки
    protected val nfcReaderCallback = NfcAdapter.ReaderCallback { tag ->
        val uid = tag.id?.joinToString("") { String.format("%02X", it) } ?: ""
        val techs = tag.techList?.joinToString(", ") { it.substringAfterLast(".") } ?: "none"
        Log.i("NFC_TOOLS", "Tag detected! UID: $uid, Techs: [$techs]")

        val hceData = readHcePayload(tag)

        val resultString = if (!hceData.isNullOrBlank()) {
            Log.i("NFC_TOOLS", "HCE SUCCESS detected. Payload: $hceData")
            hceData
        } else {
            Log.w("NFC_TOOLS", "Fallback to raw UID: $uid")
            uid
        }
        requireActivity().runOnUiThread {
            processNfcTag(resultString)
        }
    }

    private val CANDIDATE_AIDS = listOf("F0010203040506", "F14954574F58", "F222222222", "F000000001020304", "A0000000041010")

    private fun readHcePayload(tag: Tag): String? {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.w("NFC_TOOLS", "Tag is not IsoDep! Cannot read HCE.")
            return null
        }

        return try {
            isoDep.connect()
            isoDep.timeout = 5000

            for (aidStr in CANDIDATE_AIDS) {
                val aidBytes = hexStringToByteArray(aidStr)
                
                val commands = listOf(
                    byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aidBytes.size.toByte()) + aidBytes + byteArrayOf(0x00),
                    byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aidBytes.size.toByte()) + aidBytes,
                    byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, aidBytes.size.toByte()) + aidBytes
                )

                for (cmd in commands) {
                    val cmdHex = cmd.joinToString("") { "%02X".format(it) }
                    Log.i("NFC_TOOLS", "Sending APDU: $cmdHex")
                    val response = try {
                        isoDep.transceive(cmd)
                    } catch (e: Exception) {
                        Log.w("NFC_TOOLS", "Transceive exception for cmd $cmdHex: ${e.message}")
                        continue
                    }

                    val respHex = response.joinToString("") { "%02X".format(it) }
                    Log.i("NFC_TOOLS", "Response for cmd $cmdHex: $respHex")

                    val responseLength = response.size
                    if (responseLength >= 2 &&
                        response[responseLength - 2] == 0x90.toByte() &&
                        response[responseLength - 1] == 0x00.toByte()
                    ) {
                        val payloadBytes = response.copyOfRange(0, responseLength - 2)
                        val payloadStr = String(payloadBytes, Charset.forName("UTF-8")).trim()
                        if (payloadStr.isNotEmpty()) {
                            Log.i("NFC_TOOLS", "SUCCESS Parsed HCE Payload: $payloadStr")
                            return payloadStr
                        }
                    }
                }
            }
            null
        } catch (e: IOException) {
            Log.e("NFC_TOOLS", "IsoDep connection failed: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e("NFC_TOOLS", "General error: ${e.message}")
            null
        } finally {
            try {
                isoDep.close()
            } catch (e: Exception){}
        }
    }

    private fun buildSelectApdu(aid: ByteArray): ByteArray {
        val header = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
        return header + aid.size.toByte() + aid
    }
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    @OptIn(InternalSerializationApi::class)
    protected abstract fun processNfcTag(nfcId: String)

    protected abstract fun showNfcNotSupportedMessage()

    protected abstract fun showNfcReadingStartedMessage()

    protected abstract fun showNfcReadingStoppedMessage()

    override fun onPause() {
        super.onPause()
        if (isReadingMode) {
            stopNfcReadingMode()
        }
    }
}