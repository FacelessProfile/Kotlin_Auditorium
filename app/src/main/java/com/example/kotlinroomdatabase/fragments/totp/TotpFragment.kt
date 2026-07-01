package com.example.kotlinroomdatabase.fragments.totp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.utils.TotpUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TotpFragment : Fragment() {

    private lateinit var pbTotpTime: ProgressBar
    private lateinit var tvTotpCode: TextView
    private lateinit var tvNotEnabled: TextView
    private var updateJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_totp, container, false)
        pbTotpTime = view.findViewById(R.id.pbTotpTime)
        tvTotpCode = view.findViewById(R.id.tvTotpCode)
        tvNotEnabled = view.findViewById(R.id.tvNotEnabled)
        return view
    }

    override fun onResume() {
        super.onResume()
        startTotpUpdates()
    }

    override fun onPause() {
        super.onPause()
        updateJob?.cancel()
    }

    private fun startTotpUpdates() {
        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        val sharedPreferences = EncryptedSharedPreferences.create(
            requireContext(),
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        val totpSecret = sharedPreferences.getString("totp_secret", null)

        if (totpSecret.isNullOrBlank()) {
            tvNotEnabled.visibility = View.VISIBLE
            pbTotpTime.visibility = View.GONE
            tvTotpCode.visibility = View.GONE
            return
        }

        tvNotEnabled.visibility = View.GONE
        pbTotpTime.visibility = View.VISIBLE
        tvTotpCode.visibility = View.VISIBLE

        updateJob = lifecycleScope.launch {
            while (true) {
                val code = TotpUtils.generateCurrentCode(totpSecret, 30, 6)
                val remaining = TotpUtils.getRemainingSeconds(30)
                
                tvTotpCode.text = code.substring(0, 3) + " " + code.substring(3)
                pbTotpTime.progress = remaining
                
                delay(1000) // Update progress every second
            }
        }
    }
}
