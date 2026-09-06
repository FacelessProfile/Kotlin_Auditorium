package com.example.kotlinroomdatabase.fragments.profile

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.DialogUserAgreementBinding
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.launch

class UserAgreementDialogFragment : DialogFragment() {

    private var _binding: DialogUserAgreementBinding? = null
    private val binding get() = _binding!!

    private var agreementVersion: String = "2026-08-01"
    private var isCurrentlyAccepted: Boolean = false
    private lateinit var repo: StudentRepositoryHTTPS

    companion object {
        private const val ARG_VERSION = "version"

        fun newInstance(version: String): UserAgreementDialogFragment {
            val fragment = UserAgreementDialogFragment()
            val args = Bundle()
            args.putString(ARG_VERSION, version)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agreementVersion = arguments?.getString(ARG_VERSION) ?: "2026-08-01"
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogUserAgreementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = StudentRepositoryHTTPS(requireContext(), StudentDatabase.getInstance(requireContext()).studentDao())

        binding.tvAgreementVersion.text = "Версия: $agreementVersion"
        binding.btnCloseAgreement.setOnClickListener {
            dismiss()
        }

        loadCurrentAgreementStatus()
    }

    private fun loadCurrentAgreementStatus() {
        lifecycleScope.launch {
            if (!isAdded) return@launch
            val result = repo.getUserAgreementCurrent()
            if (_binding == null || !isAdded) return@launch

            if (result is GenericResult.Success) {
                isCurrentlyAccepted = result.data.accepted
                updateUiForDecision(isCurrentlyAccepted)
            } else {
                updateUiForDecision(false)
            }
        }
    }

    private fun updateUiForDecision(accepted: Boolean) {
        val ctx = context ?: return
        isCurrentlyAccepted = accepted

        if (accepted) {
            binding.tvAgreementStatusBadge.text = "Статус: Принято"
            binding.tvAgreementStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.sib_success))
            binding.tvAgreementStatusBadge.setBackgroundResource(R.drawable.bg_badge_ontime)

            binding.btnToggleDecision.text = "Отозвать согласие"
            binding.btnToggleDecision.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.sib_warning)
            )
        } else {
            binding.tvAgreementStatusBadge.text = "Статус: Требуется решение"
            binding.tvAgreementStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.sib_warning))
            binding.tvAgreementStatusBadge.setBackgroundResource(R.drawable.bg_badge_late)

            binding.btnToggleDecision.text = "Дать согласие"
            binding.btnToggleDecision.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.sib_blue_primary)
            )
        }

        binding.btnToggleDecision.setOnClickListener {
            toggleAgreementDecision()
        }
    }

    private fun toggleAgreementDecision() {
        val targetDecision = if (isCurrentlyAccepted) "declined" else "accepted"

        lifecycleScope.launch {
            if (!isAdded || _binding == null) return@launch
            binding.btnToggleDecision.isEnabled = false

            val result = repo.setUserAgreementDecision(agreementVersion, targetDecision)
            if (_binding == null || !isAdded) return@launch
            binding.btnToggleDecision.isEnabled = true

            if (result is GenericResult.Success && result.data) {
                val newAccepted = (targetDecision == "accepted")
                updateUiForDecision(newAccepted)
                val msg = if (newAccepted) "Согласие успешно предоставлено" else "Согласие отозвано"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Не удалось сохранить решение", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.94).toInt()
            val height = (displayMetrics.heightPixels * 0.85).toInt()
            window.setLayout(width, height)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
