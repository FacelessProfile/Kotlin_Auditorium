package com.example.kotlinroomdatabase.fragments.dev

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.databinding.ItemDevSprintMemberBinding
import com.example.kotlinroomdatabase.model.MemberSprintActivity
import com.example.kotlinroomdatabase.util.AvatarManager

class DevSprintActivityAdapter : ListAdapter<MemberSprintActivity, DevSprintActivityAdapter.MemberViewHolder>(DiffCallback) {

    inner class MemberViewHolder(private val binding: ItemDevSprintMemberBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(activity: MemberSprintActivity) {
            val user = activity.user
            binding.tvMemberName.text = user.name.ifBlank { user.login }
            binding.tvMemberLogin.text = "@${user.login}"

            AvatarManager.loadAvatarUrl(
                context = binding.root.context,
                imageView = binding.ivMemberAvatar,
                avatarUrl = user.avatar_url
            )

            val tasksCount = activity.completed_tasks.size
            val bugsCount = activity.closed_bugs.size
            val totalCount = tasksCount + bugsCount

            binding.tvTotalCompleted.text = "$totalCount выполнено"
            binding.tvTasksDoneCount.text = "$tasksCount задач"
            binding.tvBugsDoneCount.text = "$bugsCount багов"

            val sb = StringBuilder()
            activity.completed_tasks.forEach {
                sb.append("✓ [Задача] ${it.title}\n")
            }
            activity.closed_bugs.forEach {
                sb.append("✓ [Баг] ${it.title}\n")
            }

            val summaryText = sb.toString().trimEnd()
            if (summaryText.isNotBlank()) {
                binding.tvCompletedItemsList.visibility = View.VISIBLE
                binding.tvCompletedItemsList.text = summaryText
            } else {
                binding.tvCompletedItemsList.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = ItemDevSprintMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MemberViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<MemberSprintActivity>() {
            override fun areItemsTheSame(oldItem: MemberSprintActivity, newItem: MemberSprintActivity): Boolean =
                oldItem.user.user_id == newItem.user.user_id

            override fun areContentsTheSame(oldItem: MemberSprintActivity, newItem: MemberSprintActivity): Boolean =
                oldItem == newItem
        }
    }
}
