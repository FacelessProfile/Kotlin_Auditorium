package com.example.kotlinroomdatabase.fragments.feedback

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.databinding.ItemMyFeedbackTicketBinding
import com.example.kotlinroomdatabase.model.FeedbackTicket

class MyFeedbackTicketsAdapter(
    private var items: List<FeedbackTicket> = emptyList(),
    private val onItemClick: (FeedbackTicket) -> Unit
) : RecyclerView.Adapter<MyFeedbackTicketsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemMyFeedbackTicketBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMyFeedbackTicketBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            tvTicketCategoryBadge.text = item.categoryLabel
            tvTicketId.text = "#${item.ticketId}"
            tvTicketTitle.text = item.title
            tvTicketDescPreview.text = item.description

            // Status badge color
            tvTicketStatusBadge.text = item.statusLabel
            val statusColor = when (item.status.lowercase()) {
                "resolved" -> Color.parseColor("#15803D")
                "in_progress" -> Color.parseColor("#B45309")
                "closed" -> Color.parseColor("#6B7280")
                else -> Color.parseColor("#1D4ED8") // open
            }
            val statusBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(statusColor)
            }
            tvTicketStatusBadge.background = statusBg

            // Date
            tvTicketDate.text = item.createdAt.take(16).replace("T", " ")

            // Attachments count
            if (item.attachments.isNotEmpty()) {
                tvAttachmentsCount.visibility = View.VISIBLE
                tvAttachmentsCount.text = "${item.attachments.size} фото"
            } else {
                tvAttachmentsCount.visibility = View.GONE
            }

            root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<FeedbackTicket>) {
        items = newItems
        notifyDataSetChanged()
    }
}
