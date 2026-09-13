package com.example.kotlinroomdatabase.fragments.dev

import android.app.Dialog
import android.content.Context
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
import com.example.kotlinroomdatabase.databinding.FragmentDevTasksBinding
import com.example.kotlinroomdatabase.model.DevSprint
import com.example.kotlinroomdatabase.model.DevTask
import com.example.kotlinroomdatabase.model.DevTeamMember
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevTasksFragment : Fragment() {

    private var _binding: FragmentDevTasksBinding? = null
    private val binding get() = _binding!!

    private lateinit var todoAdapter: DevTasksAdapter
    private lateinit var doneAdapter: DevTasksAdapter

    private var activeSprint: DevSprint? = null
    private var allTasks = listOf<DevTask>()
    private var devTeam = listOf<DevTeamMember>()
    private var filterOnlyMyTasks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        todoAdapter = DevTasksAdapter(
            onItemClick = { openTaskDetails(it) },
            onToggleStatus = { task, isChecked -> updateTaskStatus(task, if (isChecked) "done" else "todo") }
        )

        doneAdapter = DevTasksAdapter(
            onItemClick = { openTaskDetails(it) },
            onToggleStatus = { task, isChecked -> updateTaskStatus(task, if (isChecked) "done" else "todo") }
        )

        binding.rvTodoTasks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = todoAdapter
        }

        binding.rvDoneTasks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = doneAdapter
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadData()
        }

        binding.btnFilterAll.setOnClickListener {
            filterOnlyMyTasks = false
            updateFilterButtons()
            filterAndDisplayTasks()
        }

        binding.btnFilterMy.setOnClickListener {
            filterOnlyMyTasks = true
            updateFilterButtons()
            filterAndDisplayTasks()
        }

        binding.fabAddTask.setOnClickListener {
            showCreateTaskDialog()
        }

        updateFilterButtons()
        loadData()
    }

    private fun updateFilterButtons() {
        val activeBg = ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
        val activeText = ContextCompat.getColor(requireContext(), R.color.white)
        val inactiveBg = ContextCompat.getColor(requireContext(), R.color.sib_card_surface)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.sib_text_primary)
        val strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_border)

        if (filterOnlyMyTasks) {
            binding.btnFilterAll.backgroundTintList = ColorStateList.valueOf(inactiveBg)
            binding.btnFilterAll.setTextColor(inactiveText)
            binding.btnFilterAll.strokeColor = ColorStateList.valueOf(strokeColor)

            binding.btnFilterMy.backgroundTintList = ColorStateList.valueOf(activeBg)
            binding.btnFilterMy.setTextColor(activeText)
            binding.btnFilterMy.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
        } else {
            binding.btnFilterAll.backgroundTintList = ColorStateList.valueOf(activeBg)
            binding.btnFilterAll.setTextColor(activeText)
            binding.btnFilterAll.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)

            binding.btnFilterMy.backgroundTintList = ColorStateList.valueOf(inactiveBg)
            binding.btnFilterMy.setTextColor(inactiveText)
            binding.btnFilterMy.strokeColor = ColorStateList.valueOf(strokeColor)
        }
    }

    fun loadData() {
        binding.swipeRefresh.isRefreshing = true
        val repo = RepositoryZMQ.getStudentRepository(requireContext())

        lifecycleScope.launch(Dispatchers.IO) {
            // Fetch team members
            val teamResult = repo.getDevTeam()
            if (teamResult is GenericResult.Success<*>) {
                @Suppress("UNCHECKED_CAST")
                devTeam = teamResult.data as List<DevTeamMember>
            }

            // Fetch active sprint
            val sprintResult = repo.getActiveDevSprint()
            if (sprintResult is GenericResult.Success<*>) {
                activeSprint = sprintResult.data as DevSprint
            }

            // Fetch tasks (type = task)
            val tasksResult = repo.getDevTasks(itemType = "task")

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.swipeRefresh.isRefreshing = false

                bindSprintBanner(activeSprint)

                if (tasksResult is GenericResult.Success<*>) {
                    @Suppress("UNCHECKED_CAST")
                    allTasks = tasksResult.data as List<DevTask>
                    filterAndDisplayTasks()
                } else if (tasksResult is GenericResult.Error) {
                    Toast.makeText(requireContext(), tasksResult.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindSprintBanner(sprint: DevSprint?) {
        if (sprint == null) {
            binding.tvSprintName.text = "Спринт не активен"
            binding.tvSprintDates.text = "Спринты сбрасываются по понедельникам в 12:00 НСК"
            binding.pbSprintCompletion.progress = 0
            binding.tvSprintPercent.text = "0%"
            binding.tvSprintStatsSummary.text = "Нет активных задач"
            return
        }

        binding.tvSprintName.text = sprint.name
        binding.tvSprintDates.text = "Цикл спринта: Пн 12:00 НСК — Пн 11:59 НСК"

        val stats = sprint.stats
        if (stats != null) {
            val rate = stats.completion_rate
            binding.pbSprintCompletion.progress = rate
            binding.tvSprintPercent.text = "$rate%"
            binding.tvSprintStatsSummary.text =
                "Задач: ${stats.done_tasks}/${stats.total_tasks} выполнено • Багов: ${stats.done_bugs}/${stats.total_bugs} закрыто"
        }
    }

    private fun filterAndDisplayTasks() {
        val prefs = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val currentUserId = prefs.getInt("user_id", 0)

        val filtered = if (filterOnlyMyTasks) {
            allTasks.filter { it.assignee_id == currentUserId }
        } else {
            allTasks
        }

        val todoTasks = filtered.filter { !it.status.equals("done", ignoreCase = true) }
        val doneTasks = filtered.filter { it.status.equals("done", ignoreCase = true) }

        todoAdapter.submitList(todoTasks)
        doneAdapter.submitList(doneTasks)

        binding.tvTodoCountBadge.text = todoTasks.size.toString()
        binding.tvDoneCountBadge.text = doneTasks.size.toString()

        binding.tvEmptyTodo.visibility = if (todoTasks.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyDone.visibility = if (doneTasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openTaskDetails(task: DevTask) {
        val sheet = DevTaskDetailBottomSheet.newInstance(task.task_id)
        sheet.onTaskUpdated = {
            loadData()
        }
        sheet.show(parentFragmentManager, DevTaskDetailBottomSheet.TAG)
    }

    private fun updateTaskStatus(task: DevTask, newStatus: String) {
        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.updateDevTaskStatus(task.task_id, newStatus)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (result is GenericResult.Success<*>) {
                    loadData()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateTaskDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogCreateDevTaskBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Priority Spinner
        val priorities = arrayOf("medium", "low", "high", "critical")
        val priorityLabels = arrayOf("Средний (Medium)", "Низкий (Low)", "Высокий (High)", "Критический (Critical)")
        val priorityAdapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, priorityLabels).apply {
            setDropDownViewResource(R.layout.dropdown_item)
        }
        dialogBinding.spPriority.adapter = priorityAdapter

        // Assignee Spinner
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
                dialogBinding.etTaskTitle.error = "Введите название задачи"
                return@setOnClickListener
            }

            val desc = dialogBinding.etTaskDescription.text.toString().trim()
            val itemType = if (dialogBinding.rbTypeBug.isChecked) "bug" else "task"
            val priority = priorities[dialogBinding.spPriority.selectedItemPosition]
            val assigneeId = assigneeIds[dialogBinding.spAssignee.selectedItemPosition]
            val sprintId = activeSprint?.sprint_id

            dialog.dismiss()

            val repo = RepositoryZMQ.getStudentRepository(requireContext())
            lifecycleScope.launch(Dispatchers.IO) {
                val result = repo.createDevTask(
                    itemType = itemType,
                    title = title,
                    description = desc,
                    priority = priority,
                    assigneeId = assigneeId,
                    sprintId = sprintId
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (result is GenericResult.Success<*>) {
                        Toast.makeText(requireContext(), "Успешно создано!", Toast.LENGTH_SHORT).show()
                        loadData()
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
