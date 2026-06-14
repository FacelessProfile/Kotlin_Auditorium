package com.example.kotlinroomdatabase.fragments.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.HistoryItem

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items = listOf<HistoryItem>()

    fun setData(newItems: List<HistoryItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.history_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDate.text = item.date
        holder.tvLesson.text = item.lesson_name ?: "Занятие"
        
        if (item.status == "attended" || (item.count ?: 0) > 0) {
            holder.ivStatus.setImageResource(R.drawable.ic_check)
            holder.ivStatus.setColorFilter(android.graphics.Color.GREEN)
        } else {
            holder.ivStatus.setImageResource(R.drawable.ic_nfc)
            holder.ivStatus.setColorFilter(android.graphics.Color.GRAY)
        }
        
        holder.tvCount.text = if (item.count != null) "Посещений: ${item.count}" else item.status ?: ""
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivStatus: ImageView = view.findViewById(R.id.ivStatus)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvLesson: TextView = view.findViewById(R.id.tvLesson)
        val tvCount: TextView = view.findViewById(R.id.tvCount)
    }
}
