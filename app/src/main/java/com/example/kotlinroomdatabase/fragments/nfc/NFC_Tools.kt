package com.example.kotlinroomdatabase.fragments.nfc

import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.serialization.InternalSerializationApi

abstract class  NFC_Tools : Fragment() {
    protected var nfcAdapter: NfcAdapter? = null
    protected var isReadingMode = false
    protected var isInfiniteMode = false
    protected val nfcTimeoutHandler = Handler(Looper.getMainLooper())
    protected val NFC_READ_TIMEOUT = 15000L

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

    protected val nfcReaderCallback = NfcAdapter.ReaderCallback { tag ->
        val nfcId = tag.id?.joinToString("") { String.format("%02X", it) } ?: ""
        requireActivity().runOnUiThread {
            processNfcTag(nfcId)
        }
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