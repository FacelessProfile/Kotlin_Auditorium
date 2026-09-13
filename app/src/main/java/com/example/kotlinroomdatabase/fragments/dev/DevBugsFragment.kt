package com.example.kotlinroomdatabase.fragments.dev

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.DialogCreateDevTaskBinding
import com.example.kotlinroomdatabase.databinding.FragmentDevBugsBinding
import com.example.kotlinroomdatabase.model.DevSprint
import com.example.kotlinroomdatabase.model.DevTask
import com.example.kotlinroomdatabase.model.DevTeamMember
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevBugsFragment : Fragment() {

    private var _binding: FragmentDevBugsBinding? = null
    private val binding get() = _binding!!

    private lateinit var bugsAdapter: DevBugsAdapter
    private var allBugs = listOf<DevTask>()
    private var devTeam = listOf<DevTeamMember>()
    private var activeSprint: DevSprint? = null
    private var filterMode = "all" // "all", "open", "resolved"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevBugsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bugsAdapter = DevBugsAdapter(
            onItemClick = { openBugDetails(it) },
            onToggleStatus = { bug, isChecked -> updateBugStatus(bug, if (isChecked) "done" else "todo") }
        )

        binding.rvBugs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = bugsAdapter
        }

        binding.swipeRefreshBugs.setOnRefreshListener {
            loadBugs()
        }

        binding.btnFilterAllBugs.setOnClickListener {
            filterMode = "all"
            updateFilterTabs()
            filterAndDisplayBugs()
        }

        binding.btnFilterOpenBugs.setOnClickListener {
            filterMode = "open"
            updateFilterTabs()
            filterAndDisplayBugs()
        }

        binding.btnFilterResolvedBugs.setOnClickListener {
            filterMode = "resolved"
            updateFilterTabs()
            filterAndDisplayBugs()
        }

        binding.fabAddBug.setOnClickListener {
            showCreateBugDialog()
        }

        updateFilterTabs()
        loadBugs()
    }

    private fun updateFilterTabs() {
        val activeBg = ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
        val activeText = ContextCompat.getColor(requireContext(), R.color.white)
        val inactiveBg = ContextCompat.getColor(requireContext(), R.color.sib_card_surface)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.sib_text_primary)
        val strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_border)

        binding.btnFilterAllBugs.backgroundTintList = ColorStateList.valueOf(if (filterMode == "all") activeBg else inactiveBg)
        binding.btnFilterAllBugs.setTextColor(if (filterMode == "all") activeText else inactiveText)
        binding.btnFilterAllBugs.strokeColor = ColorStateList.valueOf(if (filterMode == "all") Color.TRANSPARENT else strokeColor)

        binding.btnFilterOpenBugs.backgroundTintList = ColorStateList.valueOf(if (filterMode == "open") activeBg else inactiveBg)
        binding.btnFilterOpenBugs.setTextColor(if (filterMode == "open") activeText else inactiveText)
        binding.btnFilterOpenBugs.strokeColor = ColorStateList.valueOf(if (filterMode == "open") Color.TRANSPARENT else strokeColor)

        binding.btnFilterResolvedBugs.backgroundTintList = ColorStateList.valueOf(if (filterMode == "resolved") activeBg else inactiveBg)
        binding.btnFilterResolvedBugs.setTextColor(if (filterMode == "resolved") activeText else inactiveText)
        binding.btnFilterResolvedBugs.strokeColor = ColorStateList.valueOf(if (filterMode == "resolved") Color.TRANSPARENT else strokeColor)
    }

    fun loadBugs() {
        binding.swipeRefreshBugs.isRefreshing = true
        val repo = RepositoryZMQ.getStudentRepository(requireContext())

        lifecycleScope.launch(Dispatchers.IO) {
            val teamResult = repo.getDevTeam()
            if (teamResult is GenericResult.Success<*>) {
                @Suppress("UNCHECKED_CAST")
                devTeam = teamResult.data as List<DevTeamMember>
            }

            val sprintResult = repo.getActiveDevSprint()
            if (sprintResult is GenericResult.Success<*>) {
                activeSprint = sprintResult.data as DevSprint
            }

            val bugsResult = repo.getDevTasks(itemType = "bug")

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.swipeRefreshBugs.isRefreshing = false

                if (bugsResult is GenericResult.Success<*>) {
                    @Suppress("UNCHECKED_CAST")
                    allBugs = bugsResult.data as List<DevTask>
                    filterAndDisplayBugs()
                } else if (bugsResult is GenericResult.Error) {
                    Toast.makeText(requireContext(), bugsResult.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun filterAndDisplayBugs() {
        val filtered = when (filterMode) {
            "open" -> allBugs.filter { !it.status.equals("done", ignoreCase = true) }
            "resolved" -> allBugs.filter { it.status.equals("done", ignoreCase = true) }
            else -> allBugs
        }

        bugsAdapter.submitList(filtered)
        binding.tvEmptyBugs.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openBugDetails(bug: DevTask) {
        val sheet = DevTaskDetailBottomSheet.newInstance(bug.task_id)
        sheet.onTaskUpdated = {
            loadBugs()
        }
        sheet.show(parentFragmentManager, DevTaskDetailBottomSheet.TAG)
    }

    private fun updateBugStatus(bug: DevTask, newStatus: String) {
        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.updateDevTaskStatus(bug.task_id, newStatus)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (result is GenericResult.Success<*>) {
                    loadBugs()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateBugDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogCreateDevTaskBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogBinding.tvDialogTitle.text = "Завести баг (Bug Tracker)"
        dialogBinding.rbTypeBug.isChecked = true

        // Priority / Severity
        val priorities = arrayOf("critical", "high", "medium", "low")
        val priorityLabels = arrayOf("Критический (Critical)", "Высокий (High)", "Средний (Medium)", "Низкий (Low)")
        val priorityAdapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, priorityLabels).apply {
            setDropDownViewResource(R.layout.dropdown_item)
        }
        dialogBinding.spPriority.adapter = priorityAdapter

        // Assignee
        val assigneeLabels = mutableListOf("Не назначен")
        val assigneeIds = mutableListOf<Int?>(null)
        devTeam.forEach { member ->
            val label = if (member.name.isNotBlank()) "${member.name} (@${member.login})" else "@${member.login}"
            assigneeLabels.add(label)
            assigneeIds.add(member.user_id)
        }
        val assigneeAdapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, assigneeLabels).apply {
            setDropDownViewResource(R.layout.dropdown_item)
        }
        dialogBinding.spAssignee.adapter = assigneeAdapter

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnCreate.setOnClickListener {
            val title = dialogBinding.etTaskTitle.text.toString().trim()
            if (title.isBlank()) {
                dialogBinding.etTaskTitle.error = "Введите описание проблемы"
                return@setOnClickListener
            }

            val desc = dialogBinding.etTaskDescription.text.toString().trim()
            val priority = priorities[dialogBinding.spPriority.selectedItemPosition]
            val assigneeId = assigneeIds[dialogBinding.spAssignee.selectedItemPosition]
            val sprintId = activeSprint?.sprint_id

            dialog.dismiss()

            val repo = RepositoryZMQ.getStudentRepository(requireContext())
            lifecycleScope.launch(Dispatchers.IO) {
                val result = repo.createDevTask(
                    itemType = "bug",
                    title = title,
                    description = desc,
                    priority = priority,
                    assigneeId = assigneeId,
                    sprintId = sprintId
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (result is GenericResult.Success<*>) {
                        Toast.makeText(requireContext(), "Баг успешно заведён!", Toast.LENGTH_SHORT).show()
                        loadBugs()
                    } else if (result is GenericResult.Error) {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
