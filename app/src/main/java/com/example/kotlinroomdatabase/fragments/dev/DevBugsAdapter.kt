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
import com.example.kotlinroomdatabase.databinding.ItemDevBugBinding
import com.example.kotlinroomdatabase.model.DevTask
import com.example.kotlinroomdatabase.util.AvatarManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DevBugsAdapter(
    private val onItemClick: (DevTask) -> Unit,
    private val onToggleStatus: (DevTask, Boolean) -> Unit
) : ListAdapter<DevTask, DevBugsAdapter.BugViewHolder>(DiffCallback) {

    inner class BugViewHolder(private val binding: ItemDevBugBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(bug: DevTask) {
            binding.tvBugTitle.text = bug.title
            binding.tvBugDescription.text = bug.description
            binding.tvBugDescription.visibility = if (bug.description.isBlank()) View.GONE else View.VISIBLE

            // Assignee Avatar & Name
            val displayName = bug.assignee_name.ifBlank { "Не назначен" }
            binding.tvAssigneeName.text = displayName
            AvatarManager.loadAvatarUrl(
                context = binding.root.context,
                imageView = binding.ivAssigneeAvatar,
                avatarUrl = bug.assignee_avatar
            )

            // Status Checkbox (Resolved / Done)
            val isDone = bug.status.equals("done", ignoreCase = true)
            binding.cbBugDone.setOnCheckedChangeListener(null)
            binding.cbBugDone.isChecked = isDone

            if (isDone) {
                binding.tvBugTitle.paintFlags = binding.tvBugTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.tvBugTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_text_secondary))
                binding.root.alpha = 0.75f
            } else {
                binding.tvBugTitle.paintFlags = binding.tvBugTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.tvBugTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.sib_text_primary))
                binding.root.alpha = 1.0f
            }

            binding.cbBugDone.setOnCheckedChangeListener { _, isChecked ->
                onToggleStatus(bug, isChecked)
            }

            // Severity styling using semantic theme tokens for proper contrast
            when (bug.priority.lowercase()) {
                "critical" -> {
                    binding.tvSeverity.text = "Критический"
                    binding.llSeverityBadge.setBackgroundResource(R.drawable.bg_priority_critical)
                    val color = ContextCompat.getColor(binding.root.context, R.color.sib_error_vibrant)
                    binding.tvSeverity.setTextColor(color)
                    binding.ivBugIcon.setColorFilter(color)
                }
                "high" -> {
                    binding.tvSeverity.text = "Высокий"
                    binding.llSeverityBadge.setBackgroundResource(R.drawable.bg_priority_high)
                    val color = ContextCompat.getColor(binding.root.context, R.color.sib_warning)
                    binding.tvSeverity.setTextColor(color)
                    binding.ivBugIcon.setColorFilter(color)
                }
                "low" -> {
                    binding.tvSeverity.text = "Низкий"
                    binding.llSeverityBadge.setBackgroundResource(R.drawable.bg_priority_low)
                    val color = ContextCompat.getColor(binding.root.context, R.color.sib_text_secondary)
                    binding.tvSeverity.setTextColor(color)
                    binding.ivBugIcon.setColorFilter(color)
                }
                else -> {
                    binding.tvSeverity.text = "Средний"
                    binding.llSeverityBadge.setBackgroundResource(R.drawable.bg_priority_medium)
                    val color = ContextCompat.getColor(binding.root.context, R.color.sib_blue_primary)
                    binding.tvSeverity.setTextColor(color)
                    binding.ivBugIcon.setColorFilter(color)
                }
            }

            // Comments count badge
            if (bug.comments_count > 0) {
                binding.llCommentsBadge.visibility = View.VISIBLE
                binding.tvCommentsCount.text = bug.comments_count.toString()
            } else {
                binding.llCommentsBadge.visibility = View.GONE
            }

            // Creation date for traceability
            binding.tvCreatedAt.text = formatShortDate(bug.created_at)

            // Click listener
            binding.root.setOnClickListener {
                onItemClick(bug)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BugViewHolder {
        val binding = ItemDevBugBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BugViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BugViewHolder, position: Int) {
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
