package com.example.kotlinroomdatabase.fragments.feedback

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.databinding.ItemFeedbackAttachmentBinding
import com.example.kotlinroomdatabase.model.FeedbackAttachmentItem

class FeedbackAttachmentAdapter(
    private val items: MutableList<FeedbackAttachmentItem>,
    private val onRemove: (FeedbackAttachmentItem, Int) -> Unit
) : RecyclerView.Adapter<FeedbackAttachmentAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemFeedbackAttachmentBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeedbackAttachmentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            progressUpload.visibility = if (item.isUploading) View.VISIBLE else View.GONE

            try {
                val bitmap = BitmapFactory.decodeFile(item.file.absolutePath)
                if (bitmap != null) {
                    ivThumbnail.setImageBitmap(bitmap)
                } else {
                    ivThumbnail.setImageDrawable(null)
                }
            } catch (_: Exception) {
                ivThumbnail.setImageDrawable(null)
            }

            btnRemoveAttachment.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    onRemove(item, currentPos)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
