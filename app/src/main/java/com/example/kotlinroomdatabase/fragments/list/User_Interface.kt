package com.example.kotlinroomdatabase.fragments.list

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class User_Interface : Fragment() {
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var tvWelcome: TextView
    private var isProcessing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.user_ui, container, false)
        rootLayout = view.findViewById(R.id.rootLayout)
        statusIcon = view.findViewById(R.id.statusIcon)
        statusText = view.findViewById(R.id.statusText)
        tvWelcome = view.findViewById(R.id.tvWelcome)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val fullName = prefs.getString("student_name", "Студент")
        var displayName = "Студент"

        if (!fullName.isNullOrBlank() && fullName != "Студент") {
            val parts = fullName.trim().split(" ")

            displayName = when {
                parts.size >= 3 -> "${parts[1]} ${parts[2]}"
                parts.size == 2 -> parts[1]
                else -> parts[0]
            }
        }

        tvWelcome.text = "Добро пожаловать,\n$displayName"
        setNeutralState()
    }

    override fun onResume() {
        super.onResume()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(nfcReceiver, IntentFilter("NFC_MARK_SUCCESS"))
    }

    fun showSuccessCheck() {
        lifecycleScope.launch(Dispatchers.Main) {
            setSuccessState()
            statusText.text = "Успешная отметка!"
            delay(3000)
            setNeutralState()
            statusText.text = "Смартфон готов к считыванию"
        }
    }

    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isProcessing) {
                showSuccessCheck()
            }
        }
    }

    private fun setSuccessState() {
        rootLayout.setBackgroundColor(Color.GREEN)
        statusIcon.setImageResource(R.drawable.ic_check)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    private fun setNeutralState() {
        rootLayout.setBackgroundColor(Color.WHITE)
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.setColorFilter(Color.parseColor("#6200EE"))
        statusText.text = "Поднесите к чекеру"
        statusText.setTextColor(Color.BLACK)
    }
}