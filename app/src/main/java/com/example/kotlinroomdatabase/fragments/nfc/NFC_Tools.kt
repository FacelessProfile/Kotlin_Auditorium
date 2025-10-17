package com.example.kotlinroomdatabase.fragments.nfc

import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.fragment.app.Fragment
import kotlinx.serialization.InternalSerializationApi

abstract class NFC_Tools : Fragment() {
    protected var nfcAdapter: NfcAdapter? = null  // вынес все основные функции и переменные в TOOLS
    protected var isReadingMode = false
    protected val nfcTimeoutHandler = Handler(Looper.getMainLooper())
    protected val NFC_READ_TIMEOUT = 15000L

    protected fun setupNfcButton(button: Button) {
        button.setOnClickListener {
            setupNfcReading()
            startNfcReadingMode()
        }
    }
    protected fun setupNfcReading() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        if (nfcAdapter == null) {
            showNfcNotSupportedMessage()
        }
    }

    protected fun startNfcReadingMode() {
        if (nfcAdapter == null || isReadingMode) return
        isReadingMode = true

        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B
        nfcAdapter?.enableReaderMode(
            requireActivity(),
            nfcReaderCallback,
            flags,
            null
        )

        nfcTimeoutHandler.postDelayed({ stopNfcReadingModeByTimeout() }, NFC_READ_TIMEOUT)
        showNfcReadingStartedMessage()
    }

    protected fun stopNfcReadingModeByTimeout() {
        if (!isReadingMode) return
        isReadingMode = false
        nfcAdapter?.disableReaderMode(requireActivity())
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
        showNfcReadingStoppedMessage()
    }

    protected fun stopNfcReadingModeAfterScan() {
        if (!isReadingMode) return
        isReadingMode = false
        nfcAdapter?.disableReaderMode(requireActivity())
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
    }

    protected val nfcReaderCallback = NfcAdapter.ReaderCallback { tag -> //работа с коллбеком при считывании NFC TAG
        val nfcId = tag.id?.joinToString("") { String.format("%02X", it) } ?: ""
        requireActivity().runOnUiThread {
            processNfcTag(nfcId)
        }
    }

    @OptIn(InternalSerializationApi::class) // Определяем сообщения уже на месте требования
    protected abstract fun processNfcTag(nfcId: String)

    protected abstract fun showNfcNotSupportedMessage()
    protected abstract fun showNfcReadingStartedMessage()
    protected abstract fun showNfcReadingStoppedMessage()

    override fun onPause() {
        super.onPause()
        if (isReadingMode) {
            stopNfcReadingModeByTimeout()
        }
    }

    override fun onResume(){
        super.onResume()
        if (isReadingMode) {
            startNfcReadingMode()
        }
    }
}