package com.example.kotlinroomdatabase.fragments.dev

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentDevSprintBinding
import com.example.kotlinroomdatabase.model.DevSprint
import com.example.kotlinroomdatabase.model.DevSprintReport
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DevSprintFragment : Fragment() {

    private var _binding: FragmentDevSprintBinding? = null
    private val binding get() = _binding!!

    private val memberActivityAdapter = DevSprintActivityAdapter()
    private var sprintList = listOf<DevSprint>()
    private var selectedSprint: DevSprint? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevSprintBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSprintMembers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = memberActivityAdapter
        }

        binding.swipeRefreshSprint.setOnRefreshListener {
            loadSprints()
        }

        binding.btnCloseSprint.setOnClickListener {
            confirmCloseSprint()
        }

        loadSprints()
    }

    private fun loadSprints() {
        binding.swipeRefreshSprint.isRefreshing = true
        val repo = RepositoryZMQ.getStudentRepository(requireContext())

        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.getDevSprints()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.swipeRefreshSprint.isRefreshing = false

                if (result is GenericResult.Success<*>) {
                    sprintList = result.data as List<DevSprint>
                    setupSprintSpinner()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSprintSpinner() {
        if (sprintList.isEmpty()) {
            binding.tvEmptySprintMembers.visibility = View.VISIBLE
            return
        }

        val sprintNames = sprintList.map {
            val statusTag = if (it.status == "active") " (Активен)" else " (Завершен)"
            it.name + statusTag
        }

        val spinnerAdapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, sprintNames).apply {
            setDropDownViewResource(R.layout.dropdown_item)
        }
        binding.spSprints.adapter = spinnerAdapter

        // Default to active sprint
        val activeIndex = sprintList.indexOfFirst { it.status == "active" }.takeIf { it >= 0 } ?: 0
        binding.spSprints.setSelection(activeIndex)

        binding.spSprints.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in sprintList.indices) {
                    selectedSprint = sprintList[position]
                    loadSprintReport(sprintList[position].sprint_id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        selectedSprint = sprintList[activeIndex]
        loadSprintReport(sprintList[activeIndex].sprint_id)
    }

    private fun loadSprintReport(sprintId: Int) {
        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val reportResult = repo.getDevSprintReport(sprintId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (reportResult is GenericResult.Success<*>) {
                    bindSprintReport(reportResult.data as DevSprintReport)
                } else if (reportResult is GenericResult.Error) {
                    Toast.makeText(requireContext(), reportResult.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindSprintReport(report: DevSprintReport) {
        val sprint = report.sprint
        val startFormatted = formatIsoToNsk(sprint.starts_at)
        val endFormatted = formatIsoToNsk(sprint.ends_at)
        binding.tvSprintTimeInfo.text = "Период: $startFormatted — $endFormatted (НСК)"

        val velocity = report.velocity
        binding.tvVelocityCount.text = velocity.toString()

        val stats = sprint.stats
        val compRate = stats?.completion_rate ?: 0
        binding.tvCompletionRate.text = "$compRate%"

        val members = report.members
        memberActivityAdapter.submitList(members)
        binding.tvEmptySprintMembers.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE

        // Only show close button if sprint is currently active
        binding.btnCloseSprint.visibility = if (sprint.status == "active") View.VISIBLE else View.GONE
    }

    private fun confirmCloseSprint() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Завершить спринт?")
            .setMessage("Текущий спринт будет закрыт. Все незавершенные задачи и баги автоматически перенесутся в следующий недельный спринт (Rollover). Начать новый спринт?")
            .setPositiveButton("Да, завершить") { _, _ ->
                closeSprint()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun closeSprint() {
        binding.swipeRefreshSprint.isRefreshing = true
        val repo = RepositoryZMQ.getStudentRepository(requireContext())

        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.closeDevSprint()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.swipeRefreshSprint.isRefreshing = false
                if (result is GenericResult.Success<*>) {
                    Toast.makeText(requireContext(), "Спринт успешно завершён и запущен новый!", Toast.LENGTH_LONG).show()
                    loadSprints()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatIsoToNsk(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outputFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("Asia/Novosibirsk")
            }
            val date = inputFormat.parse(isoDate.substringBefore("Z").substringBefore("+"))
            if (date != null) outputFormat.format(date) else isoDate
        } catch (_: Exception) {
            isoDate
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
