package com.example.kotlinroomdatabase.fragments.profile

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.launch

class UserAgreementDialogFragment : DialogFragment() {

    private var agreementVersion: String = "2026-08-01"

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
        isCancelable = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_user_agreement, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvVersion = view.findViewById<TextView>(R.id.tvAgreementVersion)
        val btnAccept = view.findViewById<Button>(R.id.btnAccept)
        val btnDecline = view.findViewById<Button>(R.id.btnDecline)

        tvVersion.text = "Версия: $agreementVersion"

        val repo = StudentRepositoryHTTPS(requireContext(), StudentDatabase.getInstance(requireContext()).studentDao())

        btnAccept.setOnClickListener {
            lifecycleScope.launch {
                btnAccept.isEnabled = false
                btnDecline.isEnabled = false
                val result = repo.setUserAgreementDecision(agreementVersion, "accepted")
                if (result is com.example.kotlinroomdatabase.repository.GenericResult.Success && result.data) {
                    Toast.makeText(requireContext(), "Соглашение принято", Toast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    btnAccept.isEnabled = true
                    btnDecline.isEnabled = true
                    Toast.makeText(requireContext(), "Ошибка сохранения решения", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDecline.setOnClickListener {
            lifecycleScope.launch {
                btnAccept.isEnabled = false
                btnDecline.isEnabled = false
                val result = repo.setUserAgreementDecision(agreementVersion, "declined")
                if (result is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                    Toast.makeText(requireContext(), "Соглашение отклонено", Toast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    btnAccept.isEnabled = true
                    btnDecline.isEnabled = true
                    Toast.makeText(requireContext(), "Ошибка сохранения решения", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
