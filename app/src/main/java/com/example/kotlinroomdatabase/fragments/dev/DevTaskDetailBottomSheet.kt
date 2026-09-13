package com.example.kotlinroomdatabase.fragments.dev

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.BottomSheetDevTaskDetailBinding
import com.example.kotlinroomdatabase.model.DevComment
import com.example.kotlinroomdatabase.model.DevTask
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.example.kotlinroomdatabase.util.AvatarManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevTaskDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDevTaskDetailBinding? = null
    private val binding get() = _binding!!

    private var taskId: Int = 0
    private var currentTask: DevTask? = null
    private var isFollowing: Boolean = true

    private val commentsAdapter = DevCommentsAdapter()
    var onTaskUpdated: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = arguments?.getInt(ARG_TASK_ID) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDevTaskDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvComments.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = commentsAdapter
        }

        binding.btnSendComment.setOnClickListener {
            val text = binding.etCommentInput.text.toString().trim()
            if (text.isNotBlank()) {
                sendComment(text)
            }
        }

        binding.btnFollowToggle.setOnClickListener {
            toggleFollow()
        }

        binding.btnToggleStatus.setOnClickListener {
            toggleStatus()
        }

        loadTaskDetails()
    }

    private fun loadTaskDetails() {
        if (taskId == 0) return

        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.getDevTaskDetails(taskId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (result is GenericResult.Success<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val pair = result.data as Pair<DevTask, List<DevComment>>
                    val task = pair.first
                    val comments = pair.second
                    currentTask = task
                    isFollowing = task.is_following
                    bindTask(task)
                    commentsAdapter.submitList(comments)
                    binding.tvCommentsHeaderCount.text = "(${comments.size})"
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindTask(task: DevTask) {
        val isBug = task.item_type.equals("bug", ignoreCase = true)
        binding.tvDetailType.text = if (isBug) "Баг" else "Задача"
        binding.tvDetailType.setBackgroundResource(
            if (isBug) R.drawable.bg_priority_critical else R.drawable.bg_priority_medium
        )
        binding.tvDetailType.setTextColor(
            if (isBug) ContextCompat.getColor(requireContext(), R.color.sib_error_vibrant)
            else ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
        )

        binding.tvDetailTitle.text = task.title
        binding.tvDetailDescription.text = task.description.ifBlank { "Нет дополнительного описания" }

        // Priority
        binding.tvDetailPriority.text = task.priority.uppercase()
        when (task.priority.lowercase()) {
            "critical" -> {
                binding.tvDetailPriority.setBackgroundResource(R.drawable.bg_priority_critical)
                binding.tvDetailPriority.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_error_vibrant))
            }
            "high" -> {
                binding.tvDetailPriority.setBackgroundResource(R.drawable.bg_priority_high)
                binding.tvDetailPriority.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_warning))
            }
            "low" -> {
                binding.tvDetailPriority.setBackgroundResource(R.drawable.bg_priority_low)
                binding.tvDetailPriority.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_secondary))
            }
            else -> {
                binding.tvDetailPriority.setBackgroundResource(R.drawable.bg_priority_medium)
                binding.tvDetailPriority.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_blue_primary))
            }
        }

        // Assignee
        val assigneeName = task.assignee_name.ifBlank { "Не назначен" }
        binding.tvDetailAssigneeName.text = assigneeName
        AvatarManager.loadAvatarUrl(
            context = requireContext(),
            imageView = binding.ivDetailAssigneeAvatar,
            avatarUrl = task.assignee_avatar
        )

        // Status Button with theme colors
        val isDone = task.status.equals("done", ignoreCase = true)
        if (isDone) {
            binding.btnToggleStatus.text = "Закрыто ✓"
            val successBg = ContextCompat.getColor(requireContext(), R.color.sib_success_bg)
            val successColor = ContextCompat.getColor(requireContext(), R.color.sib_success)
            binding.btnToggleStatus.backgroundTintList = ColorStateList.valueOf(successBg)
            binding.btnToggleStatus.setTextColor(successColor)
            binding.btnToggleStatus.iconTint = ColorStateList.valueOf(successColor)
        } else {
            binding.btnToggleStatus.text = "В работе"
            val blueBg = ContextCompat.getColor(requireContext(), R.color.sib_blue_active_bg)
            val blueColor = ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
            binding.btnToggleStatus.backgroundTintList = ColorStateList.valueOf(blueBg)
            binding.btnToggleStatus.setTextColor(blueColor)
            binding.btnToggleStatus.iconTint = ColorStateList.valueOf(blueColor)
        }

        updateFollowButtonUI()
    }

    private fun updateFollowButtonUI() {
        val primaryBlue = ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
        val textSecondary = ContextCompat.getColor(requireContext(), R.color.sib_text_secondary)
        val borderSubtle = ContextCompat.getColor(requireContext(), R.color.sib_border)

        if (isFollowing) {
            binding.btnFollowToggle.text = "Отписаться"
            binding.btnFollowToggle.setIconResource(R.drawable.ic_bell_off)
            binding.btnFollowToggle.setTextColor(textSecondary)
            binding.btnFollowToggle.iconTint = ColorStateList.valueOf(textSecondary)
            binding.btnFollowToggle.strokeColor = ColorStateList.valueOf(borderSubtle)
        } else {
            binding.btnFollowToggle.text = "Следить"
            binding.btnFollowToggle.setIconResource(R.drawable.ic_bell_ring)
            binding.btnFollowToggle.setTextColor(primaryBlue)
            binding.btnFollowToggle.iconTint = ColorStateList.valueOf(primaryBlue)
            binding.btnFollowToggle.strokeColor = ColorStateList.valueOf(primaryBlue)
        }
    }

    private fun toggleFollow() {
        val targetFollow = !isFollowing
        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.toggleDevTaskFollow(taskId, targetFollow)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (result is GenericResult.Success<*>) {
                    isFollowing = targetFollow
                    updateFollowButtonUI()
                    val msg = if (targetFollow) "Вы подписались на уведомления темы" else "Вы отписались от уведомлений темы"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleStatus() {
        val task = currentTask ?: return
        val newStatus = if (task.status.equals("done", ignoreCase = true)) "todo" else "done"
        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.updateDevTaskStatus(task.task_id, newStatus)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (result is GenericResult.Success<*>) {
                    val updated = result.data as DevTask
                    currentTask = updated
                    bindTask(updated)
                    onTaskUpdated?.invoke()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendComment(content: String) {
        val repo = RepositoryZMQ.getStudentRepository(requireContext())
        binding.btnSendComment.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result = repo.addDevComment(taskId, content)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.btnSendComment.isEnabled = true
                if (result is GenericResult.Success<*>) {
                    binding.etCommentInput.setText("")
                    loadTaskDetails()
                    onTaskUpdated?.invoke()
                } else if (result is GenericResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DevTaskDetailBottomSheet"
        private const val ARG_TASK_ID = "arg_task_id"

        fun newInstance(taskId: Int): DevTaskDetailBottomSheet {
            return DevTaskDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TASK_ID, taskId)
                }
            }
        }
    }
}
