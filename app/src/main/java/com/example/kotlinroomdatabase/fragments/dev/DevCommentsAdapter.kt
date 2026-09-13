package com.example.kotlinroomdatabase.fragments.dev

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.databinding.ItemDevCommentBinding
import com.example.kotlinroomdatabase.model.DevComment
import com.example.kotlinroomdatabase.util.AvatarManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DevCommentsAdapter : ListAdapter<DevComment, DevCommentsAdapter.CommentViewHolder>(DiffCallback) {

    inner class CommentViewHolder(private val binding: ItemDevCommentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(comment: DevComment) {
            binding.tvCommentAuthor.text = comment.author_name.ifBlank { "Разработчик" }
            binding.tvCommentContent.text = comment.content
            binding.tvCommentTime.text = formatTimestamp(comment.created_at)

            AvatarManager.loadAvatarUrl(
                context = binding.root.context,
                imageView = binding.ivCommentAuthorAvatar,
                avatarUrl = comment.author_avatar
            )
        }

        private fun formatTimestamp(isoDate: String): String {
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemDevCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<DevComment>() {
            override fun areItemsTheSame(oldItem: DevComment, newItem: DevComment): Boolean =
                oldItem.comment_id == newItem.comment_id

            override fun areContentsTheSame(oldItem: DevComment, newItem: DevComment): Boolean =
                oldItem == newItem
        }
    }
}
