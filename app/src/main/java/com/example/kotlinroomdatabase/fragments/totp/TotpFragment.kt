package com.example.kotlinroomdatabase.fragments.totp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentTotpBinding
import com.example.kotlinroomdatabase.utils.TotpUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TotpFragment : Fragment() {

    private var _binding: FragmentTotpBinding? = null
    private val binding get() = _binding!!
    private var updateJob: Job? = null
    private var currentRawCode: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTotpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGoToSettings.setOnClickListener {
            findNavController().navigate(R.id.settingsFragment)
        }

        binding.btnCopyTotpCode.setOnClickListener {
            if (currentRawCode.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("TOTP Code", currentRawCode)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Код скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startTotpUpdates()
    }

    override fun onPause() {
        super.onPause()
        updateJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateJob?.cancel()
        _binding = null
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
            binding.cardTotpActive.visibility = View.GONE
            binding.cardTotpDisabled.visibility = View.VISIBLE
            return
        }

        binding.cardTotpActive.visibility = View.VISIBLE
        binding.cardTotpDisabled.visibility = View.GONE

        updateJob = lifecycleScope.launch {
            while (true) {
                val code = TotpUtils.generateCurrentCode(totpSecret, 30, 6)
                val remaining = TotpUtils.getRemainingSeconds(30)
                currentRawCode = code

                binding.tvTotpCode.text = if (code.length >= 6) {
                    "${code.substring(0, 3)} ${code.substring(3)}"
                } else {
                    code
                }
                binding.pbTotpTime.progress = remaining
                binding.tvRemainingSeconds.text = "Обновится через: $remaining сек"

                delay(1000)
            }
        }
    }
}
