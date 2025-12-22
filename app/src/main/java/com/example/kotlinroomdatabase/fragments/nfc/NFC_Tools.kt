package com.example.kotlinroomdatabase.fragments.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
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
    private val SERVICE_AID = "F14954574F58"

    protected fun startNfcReadingMode(infiniteMode: Boolean = false) {
        if (nfcAdapter == null || isReadingMode) return

        isReadingMode = true
        isInfiniteMode = infiniteMode

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        nfcAdapter?.enableReaderMode(
            requireActivity(),
            nfcReaderCallback,
            flags,
            null
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
        // читаем как HCE устройство(смартфон)
        val hceData = readHcePayload(tag)

        val resultString = if (!hceData.isNullOrBlank()) {
            Log.d("NFC_TOOLS", "HCE detected. Payload: $hceData")
            hceData
        } else {
            // Если не HCE возвращаем физический UID
            val uid = tag.id?.joinToString("") { String.format("%02X", it) } ?: ""
            Log.d("NFC_TOOLS", "Standard Tag detected. UID: $uid")
            uid
        }
        requireActivity().runOnUiThread {
            processNfcTag(resultString)
        }
    }

    private fun readHcePayload(tag: Tag): String? {
        val isoDep = IsoDep.get(tag) ?: return null

        return try {
            isoDep.connect()
            val aidBytes = hexStringToByteArray(SERVICE_AID)
            val selectCommand = buildSelectApdu(aidBytes)

            Log.d("NFC_TOOLS", "Sending APDU: ${selectCommand.joinToString("") { "%02X".format(it) }}")
            val response = isoDep.transceive(selectCommand)

            Log.d("NFC_TOOLS", "Response received: ${response.joinToString("") { "%02X".format(it) }}")
            val responseLength = response.size
            if (responseLength >= 2 &&
                response[responseLength - 2] == 0x90.toByte() &&
                response[responseLength - 1] == 0x00.toByte()
            ) {
                val payloadBytes = response.copyOfRange(0, responseLength - 2)
                String(payloadBytes, Charset.forName("UTF-8"))
            } else {
                null // Ошибка
            }
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