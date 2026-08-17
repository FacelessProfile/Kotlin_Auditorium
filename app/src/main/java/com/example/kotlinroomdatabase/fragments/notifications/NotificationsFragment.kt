package com.example.kotlinroomdatabase.fragments.notifications

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.model.UserNotification
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class NotificationsFragment : Fragment() {

    private lateinit var rvNotifications: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var btnMarkAllRead: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnHistory: ImageButton
    private lateinit var tabLayout: TabLayout

    private lateinit var adapter: NotificationsAdapter
    private lateinit var repo: StudentRepositoryHTTPS

    private var allNotifications: List<UserNotification> = emptyList()
    private var showingHistoryTab: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notifications, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = StudentRepositoryHTTPS(requireContext(), StudentDatabase.getInstance(requireContext()).studentDao())

        rvNotifications = view.findViewById(R.id.rvNotifications)
        progressBar = view.findViewById(R.id.progressBar)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnMarkAllRead = view.findViewById(R.id.btnMarkAllRead)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnHistory = view.findViewById(R.id.btnHistory)
        tabLayout = view.findViewById(R.id.tabLayoutNotifications)

        rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        adapter = NotificationsAdapter(
            items = emptyList(),
            onItemClick = { notification ->
                if (!notification.is_read) {
                    markAsRead(notification.realId)
                }
            },
            onDeleteClick = { notification ->
                confirmDeleteNotification(notification)
            }
        )
        rvNotifications.adapter = adapter

        // Updater button (Refresh)
        btnRefresh.setOnClickListener {
            btnRefresh.animate().rotationBy(360f).setDuration(500).start()
            loadNotifications(showToast = true)
        }

        // History quick access button
        btnHistory.setOnClickListener {
            tabLayout.getTabAt(1)?.select()
        }

        // Tab selection listener
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showingHistoryTab = (tab?.position == 1)
                updateDisplayedList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Dialog for Mark All As Read
        btnMarkAllRead.setOnClickListener {
            showMarkAllReadConfirmation()
        }

        loadNotifications()
    }

    private fun loadNotifications(showToast: Boolean = false) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = repo.getUserNotifications()
            progressBar.visibility = View.GONE
            when (result) {
                is com.example.kotlinroomdatabase.repository.GenericResult.Success -> {
                    allNotifications = result.data
                    updateTabCounts()
                    updateDisplayedList()
                    if (showToast) {
                        Toast.makeText(requireContext(), "Уведомления обновлены", Toast.LENGTH_SHORT).show()
                    }
                }
                is com.example.kotlinroomdatabase.repository.GenericResult.Error -> {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Ошибка загрузки: ${result.message}"
                }
            }
        }
    }

    private fun updateTabCounts() {
        val unreadCount = allNotifications.count { !it.is_read }
        val historyCount = allNotifications.count { it.is_read }

        val unreadLabel = if (unreadCount > 0) "Новые ($unreadCount)" else "Новые"
        val historyLabel = if (historyCount > 0) "История ($historyCount)" else "История"

        tabLayout.getTabAt(0)?.text = unreadLabel
        tabLayout.getTabAt(1)?.text = historyLabel
    }

    private fun updateDisplayedList() {
        val filteredList = if (showingHistoryTab) {
            allNotifications.filter { it.is_read }
        } else {
            allNotifications.filter { !it.is_read }
        }

        if (filteredList.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (showingHistoryTab) "История уведомлений пуста" else "Нет новых уведомлений"
            rvNotifications.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvNotifications.visibility = View.VISIBLE
            adapter.updateItems(filteredList, showDeleteButton = showingHistoryTab)
        }
    }

    private fun showMarkAllReadConfirmation() {
        val unreadCount = allNotifications.count { !it.is_read }
        if (unreadCount == 0) {
            Toast.makeText(requireContext(), "Нет новых уведомлений для прочтения", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Прочитать всё")
            .setMessage("Отметить все новые уведомления ($unreadCount) как прочитанные и переместить их в историю?")
            .setPositiveButton("Да") { dialog, _ ->
                dialog.dismiss()
                markAllAsRead()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmDeleteNotification(notification: UserNotification) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Удалить уведомление")
            .setMessage("Вы действительно хотите удалить это уведомление из истории?")
            .setPositiveButton("Удалить") { dialog, _ ->
                dialog.dismiss()
                deleteNotification(notification.realId)
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun markAsRead(id: Long) {
        lifecycleScope.launch {
            repo.markNotificationRead(id)
            loadNotifications()
        }
    }

    private fun deleteNotification(id: Long) {
        lifecycleScope.launch {
            val result = repo.deleteNotification(id)
            if (result is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                Toast.makeText(requireContext(), "Уведомление удалено", Toast.LENGTH_SHORT).show()
                loadNotifications()
            } else {
                Toast.makeText(requireContext(), "Не удалось удалить уведомление", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun markAllAsRead() {
        lifecycleScope.launch {
            val res = repo.markAllNotificationsRead()
            if (res is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                Toast.makeText(requireContext(), "Все уведомления перемещены в историю", Toast.LENGTH_SHORT).show()
                tabLayout.getTabAt(1)?.select()
                loadNotifications()
            }
        }
    }
}

class NotificationsAdapter(
    private var items: List<UserNotification>,
    private var showDeleteButton: Boolean = false,
    private val onItemClick: (UserNotification) -> Unit,
    private val onDeleteClick: (UserNotification) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    private val expandedPositions = mutableSetOf<Int>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvNotificationTitle)
        val tvMessage: TextView = view.findViewById(R.id.tvNotificationMessage)
        val tvDate: TextView = view.findViewById(R.id.tvNotificationDate)
        val tvExpandHint: TextView = view.findViewById(R.id.tvExpandHint)
        val vUnread: View = view.findViewById(R.id.vUnreadIndicator)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteNotification)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvMessage.text = item.message

        val formattedDate = item.created_at.take(19).replace("T", " ")
        holder.tvDate.text = formattedDate
        holder.vUnread.visibility = if (item.is_read) View.GONE else View.VISIBLE

        holder.btnDelete.visibility = if (showDeleteButton) View.VISIBLE else View.GONE
        holder.btnDelete.setOnClickListener {
            onDeleteClick(item)
        }

        val isExpanded = expandedPositions.contains(position)
        if (isExpanded) {
            holder.tvMessage.maxLines = Int.MAX_VALUE
            holder.tvExpandHint.text = "Свернуть"
        } else {
            holder.tvMessage.maxLines = 2
            holder.tvExpandHint.text = "Нажмите, чтобы развернуть"
        }

        holder.itemView.setOnClickListener {
            if (isExpanded) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)

            // Auto copy TOTP 2FA code to clipboard if present
            if (item.message.contains("code is:")) {
                val code = item.message.substringAfter("code is:").trim()
                if (code.isNotBlank()) {
                    val clipboard = holder.itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("2FA Code", code)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(holder.itemView.context, "Код 2FA ($code) скопирован в буфер!", Toast.LENGTH_SHORT).show()
                }
            }

            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<UserNotification>, showDeleteButton: Boolean) {
        items = newItems
        this.showDeleteButton = showDeleteButton
        notifyDataSetChanged()
    }
}
