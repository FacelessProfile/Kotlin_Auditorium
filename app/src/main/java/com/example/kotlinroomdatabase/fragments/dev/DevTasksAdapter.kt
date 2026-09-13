package com.example.kotlinroomdatabase.fragments.dev

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.ItemDevTaskBinding
import com.example.kotlinroomdatabase.model.DevTask
import com.example.kotlinroomdatabase.util.AvatarManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DevTasksAdapter(
    private val onItemClick: (DevTask) -> Unit,
    private val onToggleStatus: (DevTask, Boolean) -> Unit
) : ListAdapter<DevTask, DevTasksAdapter.TaskViewHolder>(DiffCallback) {

    inner class TaskViewHolder(private val binding: ItemDevTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(task: DevTask) {
            binding.tvTaskTitle.text = task.title
            binding.tvTaskDescription.text = task.description
            binding.tvTaskDescription.visibility = if (task.description.isBlank()) View.GONE else View.VISIBLE

            // Assignee Avatar & Name
            val displayName = task.assignee_name.ifBlank { "Не назначен" }
            binding.tvAssigneeName.text = displayName
            AvatarManager.loadAvatarUrl(
                context = binding.root.context,
                imageView = binding.ivAssigneeAvatar,
                avatarUrl = task.assignee_avatar
            )

            // Status Checkbox
            val isDone = task.status.equals("done", ignoreCase = true)
            binding.cbTaskDone.setOnCheckedChangeListener(null)
            binding.cbTaskDone.isChecked = isDone

            if (isDone) {
                binding.tvTaskTitle.paintFlags = binding.tvTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.tvTaskTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_text_secondary))
                binding.root.alpha = 0.75f
            } else {
                binding.tvTaskTitle.paintFlags = binding.tvTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.tvTaskTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_text_primary))
                binding.root.alpha = 1.0f
            }

            binding.cbTaskDone.setOnCheckedChangeListener { _, isChecked ->
                onToggleStatus(task, isChecked)
            }

            // Priority badge styling using semantic theme tokens
            when (task.priority.lowercase()) {
                "critical" -> {
                    binding.tvPriority.text = "Критический"
                    binding.tvPriority.setBackgroundResource(R.drawable.bg_priority_critical)
                    binding.tvPriority.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_error_vibrant))
                }
                "high" -> {
                    binding.tvPriority.text = "Высокий"
                    binding.tvPriority.setBackgroundResource(R.drawable.bg_priority_high)
                    binding.tvPriority.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_warning))
                }
                "low" -> {
                    binding.tvPriority.text = "Низкий"
                    binding.tvPriority.setBackgroundResource(R.drawable.bg_priority_low)
                    binding.tvPriority.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_text_secondary))
                }
                else -> {
                    binding.tvPriority.text = "Средний"
                    binding.tvPriority.setBackgroundResource(R.drawable.bg_priority_medium)
                    binding.tvPriority.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_blue_primary))
                }
            }

            // Comments count badge
            if (task.comments_count > 0) {
                binding.llCommentsBadge.visibility = View.VISIBLE
                binding.tvCommentsCount.text = task.comments_count.toString()
            } else {
                binding.llCommentsBadge.visibility = View.GONE
            }

            // Creation date for traceability
            binding.tvCreatedAt.text = formatShortDate(task.created_at)

            // Click listener
            binding.root.setOnClickListener {
                onItemClick(task)
            }
        }

        private fun formatShortDate(isoDate: String): String {
            if (isoDate.isBlank()) return ""
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val outputFormat = SimpleDateFormat("dd.MM", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Novosibirsk")
                }
                val date = inputFormat.parse(isoDate.substringBefore("Z").substringBefore("+"))
                if (date != null) outputFormat.format(date) else ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemDevTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<DevTask>() {
            override fun areItemsTheSame(oldItem: DevTask, newItem: DevTask): Boolean =
                oldItem.task_id == newItem.task_id

            override fun areContentsTheSame(oldItem: DevTask, newItem: DevTask): Boolean =
                oldItem == newItem
        }
    }
}
