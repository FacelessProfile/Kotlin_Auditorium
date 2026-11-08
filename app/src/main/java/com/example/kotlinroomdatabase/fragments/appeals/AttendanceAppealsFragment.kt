package com.example.kotlinroomdatabase.fragments.appeals

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentAttendanceAppealsBinding
import com.example.kotlinroomdatabase.databinding.ItemAttendanceAppealBinding
import com.example.kotlinroomdatabase.model.AttendanceAppeal
import com.example.kotlinroomdatabase.util.AttendanceAppealRules
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AttendanceAppealsFragment : Fragment() {
    private val model: AttendanceAppealsViewModel by viewModels()
    private var _binding: FragmentAttendanceAppealsBinding? = null
    private val filters = listOf("", "pending", "approved", "rejected")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentAttendanceAppealsBinding.inflate(inflater, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = requireNotNull(_binding)
        val role = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE).getString("user_role", "student")
        if (!AttendanceAppealRules.canView(role)) {
            binding.appealsMessage.setText(R.string.appeal_role_unavailable)
            binding.appealsContent.visibility = View.GONE
            return
        }
        val review = AttendanceAppealRules.canReview(role)
        binding.appealsSubtitle.setText(if (review) R.string.appeals_staff_subtitle else R.string.appeals_student_subtitle)
        binding.appealsNew.visibility = if (review) View.GONE else View.VISIBLE
        binding.appealsNew.setOnClickListener { findNavController().navigate(R.id.historyFragment) }
        val adapter = AppealsAdapter { showDetails(it, review) }
        binding.appealsList.layoutManager = LinearLayoutManager(requireContext())
        binding.appealsList.adapter = adapter
        binding.appealsRefresh.setOnClickListener { model.load() }
        binding.appealsPrevious.setOnClickListener { model.load(model.state.value.page - 1) }
        binding.appealsNext.setOnClickListener { model.load(model.state.value.page + 1) }
        binding.appealsFilters.getTabAt(filters.indexOf(model.state.value.filter))?.select()
        binding.appealsFilters.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { model.load(1, filters[tab.position]) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        parentFragmentManager.setFragmentResultListener(AttendanceAppealFormDialog.RESULT_KEY, viewLifecycleOwner) { _, _ -> model.load() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    adapter.submit(state.items)
                    binding.appealsProgress.visibility = if (state.loading) View.VISIBLE else View.GONE
                    binding.appealsRefresh.isEnabled = !state.loading
                    binding.appealsPrevious.isEnabled = !state.loading && state.page > 1
                    binding.appealsNext.isEnabled = !state.loading && state.hasMore
                    binding.appealsPagination.visibility = if (state.page > 1 || state.hasMore) View.VISIBLE else View.GONE
                    binding.appealsPage.text = getString(R.string.appeals_page, state.page)
                    binding.appealsMessage.visibility = if (!state.loading && (state.error != null || state.items.isEmpty())) View.VISIBLE else View.GONE
                    binding.appealsMessage.text = state.error ?: getString(if (state.filter.isBlank()) (if (review) R.string.appeals_staff_empty else R.string.appeals_empty) else R.string.appeals_filter_empty)
                }
            }
        }
        model.load()
    }

    private fun showDetails(item: AttendanceAppeal, review: Boolean) {
        val text = buildString {
            appendLine("${formatDate(item.lesson_date)} · ${AttendanceAppealRules.statusLabel(item.status)}")
            if (review) appendLine(item.student_name)
            appendLine("\nБыло: ${AttendanceAppealRules.markLabel(item.original_status)}")
            appendLine("Запрошено: ${AttendanceAppealRules.markLabel(item.requested_status)}")
            if (review) appendLine("Сейчас в журнале: ${AttendanceAppealRules.markLabel(item.current_status)}")
            appendLine("\nПричина\n${item.reason}")
            if (!item.decision_note.isNullOrBlank()) appendLine("\nОтвет преподавателя\n${item.decision_note}")
            appendLine("\nИстория обращения")
            item.events.forEach { event ->
                appendLine("\n${AttendanceAppealRules.statusLabel(event.action)} · ${formatDateTime(event.created_at)}")
                if (event.note.isNotBlank()) appendLine(event.note)
            }
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(item.subject_name).setMessage(text)
            .setNegativeButton(R.string.appeal_close, null)
            .apply {
                if (review && item.status == "pending") setPositiveButton(R.string.appeal_review) { _, _ ->
                    if (parentFragmentManager.findFragmentByTag(AttendanceAppealFormDialog.TAG) == null) {
                        AttendanceAppealFormDialog.review(item).show(parentFragmentManager, AttendanceAppealFormDialog.TAG)
                    }
                }
            }.show()
    }

    override fun onDestroyView() {
        _binding?.appealsList?.adapter = null
        _binding = null
        super.onDestroyView()
    }
}

private class AppealsAdapter(private val onClick: (AttendanceAppeal) -> Unit) : RecyclerView.Adapter<AppealsAdapter.Holder>() {
    private var items = emptyList<AttendanceAppeal>()
    class Holder(val binding: ItemAttendanceAppealBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemAttendanceAppealBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            appealSubject.text = item.subject_name
            appealDate.text = formatDate(item.lesson_date)
            appealStatus.text = AttendanceAppealRules.statusLabel(item.status)
            appealMarks.text = "${AttendanceAppealRules.markLabel(item.original_status)} → ${AttendanceAppealRules.markLabel(item.requested_status)}"
            appealReason.text = item.reason
            appealDetails.setOnClickListener { onClick(item) }
        }
    }
    fun submit(value: List<AttendanceAppeal>) { if (items != value) { items = value; notifyDataSetChanged() } }
}

private fun formatDate(value: String): String = runCatching {
    LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))
}.getOrDefault(value)

private fun formatDateTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru")))
}.getOrDefault(value)
