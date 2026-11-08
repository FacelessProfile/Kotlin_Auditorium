package com.example.kotlinroomdatabase.fragments.appeals

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.DialogAttendanceAppealBinding
import com.example.kotlinroomdatabase.model.AttendanceAppeal
import com.example.kotlinroomdatabase.model.HistoryItem
import com.example.kotlinroomdatabase.util.AttendanceAppealRules
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AttendanceAppealFormDialog : DialogFragment() {
    private val model: AttendanceAppealFormViewModel by viewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAttendanceAppealBinding.inflate(layoutInflater)
        val review = requireArguments().getLong("appealId") > 0
        binding.appealContext.text = requireArguments().getString("context")
        binding.appealMarkChoices.visibility = if (review) View.GONE else View.VISIBLE
        binding.appealMarkLabel.visibility = if (review) View.GONE else View.VISIBLE
        binding.appealNoteLayout.hint = getString(if (review) R.string.appeal_decision_note else R.string.appeal_reason)
        binding.appealNoteLayout.helperText = getString(if (review) R.string.appeal_note_help else R.string.appeal_reason_help)
        binding.appealNote.setText(model.draft)
        binding.appealNote.doAfterTextChanged { model.draft = it?.toString().orEmpty() }
        val choices = AttendanceAppealRules.requestedStatuses.filter { it != requireArguments().getString("currentStatus") }
        choices.forEachIndexed { index, value ->
            val button = android.widget.RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = AttendanceAppealRules.markLabel(value)
                tag = value
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
            binding.appealMarkChoices.addView(button)
            if (value == model.selected || (index == 0 && model.selected !in choices)) {
                binding.appealMarkChoices.check(button.id)
                model.selected = value
            }
        }
        binding.appealMarkChoices.setOnCheckedChangeListener { group, checkedId ->
            model.selected = group.findViewById<View>(checkedId).tag as String
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (review) R.string.appeal_review else R.string.appeal_create)
            .setView(binding.root)
            .setPositiveButton(if (review) R.string.appeal_approve else R.string.appeal_send, null)
            .setNegativeButton(R.string.appeal_cancel, null)
            .apply { if (review) setNeutralButton(R.string.appeal_reject, null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { model.submit(if (review) "approved" else null) }
            if (review) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { model.submit("rejected") }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    isCancelable = !state.busy
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !state.busy
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !state.busy
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !state.busy
                    binding.appealNote.isEnabled = !state.busy
                    for (i in 0 until binding.appealMarkChoices.childCount) binding.appealMarkChoices.getChildAt(i).isEnabled = !state.busy
                    binding.appealSending.visibility = if (state.busy) View.VISIBLE else View.GONE
                    binding.appealFormError.visibility = if (state.error != null) View.VISIBLE else View.GONE
                    binding.appealFormError.text = state.error
                    if (state.done) {
                        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
                        dismiss()
                    }
                }
            }
        }
        return dialog
    }

    companion object {
        const val RESULT_KEY = "attendanceAppealChanged"
        const val TAG = "AttendanceAppealForm"

        fun create(item: HistoryItem) = AttendanceAppealFormDialog().apply {
            arguments = bundleOf("entryId" to item.entry_id, "currentStatus" to item.status,
                "context" to "${item.subject_name ?: item.lesson_name}\n${item.date}\n${AttendanceAppealRules.markLabel(item.status)}")
        }

        fun review(item: AttendanceAppeal) = AttendanceAppealFormDialog().apply {
            arguments = bundleOf("appealId" to item.appeal_id, "currentStatus" to item.current_status,
                "context" to "${item.subject_name}\n${item.student_name} · ${item.lesson_date.take(10)}\nСейчас: ${AttendanceAppealRules.markLabel(item.current_status)}\nЗапрошено: ${AttendanceAppealRules.markLabel(item.requested_status)}\n\n${item.reason}")
        }
    }
}
