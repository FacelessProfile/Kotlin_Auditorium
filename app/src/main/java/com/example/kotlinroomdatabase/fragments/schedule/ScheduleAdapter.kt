package com.example.kotlinroomdatabase.fragments.schedule

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.LessonScheduleItem
import com.example.kotlinroomdatabase.model.ScheduleSlot
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

class ScheduleAdapter(private val userRole: String) : RecyclerView.Adapter<ScheduleAdapter.SlotViewHolder>() {

    private var slots = listOf<ScheduleSlot>()

    companion object {
        fun getStandardLessonTimes(lessonNum: Int): Pair<String, String> {
            return when (lessonNum) {
                1 -> Pair("08:00", "09:35")
                2 -> Pair("09:50", "11:25")
                3 -> Pair("11:40", "13:15")
                4 -> Pair("13:45", "15:20")
                5 -> Pair("15:35", "17:10")
                6 -> Pair("17:25", "19:00")
                7 -> Pair("19:15", "20:50")
                else -> Pair("", "")
            }
        }

        fun buildScheduleSlots(newItems: List<LessonScheduleItem>, userRole: String): List<ScheduleSlot> {
            if (newItems.isEmpty()) return emptyList()

            // Group lessons into slots by lesson_num (or fallback to time interval)
            val grouped = newItems.groupBy { item ->
                if (item.lesson_num > 0) {
                    item.lesson_num.toString()
                } else {
                    "${item.start_time}_${item.end_time}"
                }
            }

            val slotList = mutableListOf<ScheduleSlot>()

            for ((_, lessonsInSlot) in grouped) {
                // Sort: Current subgroup first, other subgroup second
                val sortedLessons = if (userRole != "teacher") {
                    lessonsInSlot.sortedWith(
                        compareBy<LessonScheduleItem> { it.is_other_subgroup }
                            .thenBy { it.subgroup }
                            .thenBy { it.subject_name }
                    )
                } else {
                    lessonsInSlot.sortedBy { it.subgroup }
                }

                val first = sortedLessons.first()
                slotList.add(
                    ScheduleSlot(
                        lessonNum = first.lesson_num,
                        startTime = first.start_time,
                        endTime = first.end_time,
                        lessons = sortedLessons,
                        isWindow = false
                    )
                )
            }

            // Insert empty window tiles ("Окно") for gaps between scheduled pairs
            if (userRole != "teacher") {
                val validNums = slotList.map { it.lessonNum }.filter { it in 1..8 }
                if (validNums.isNotEmpty()) {
                    val minNum = validNums.minOrNull() ?: 1
                    val maxNum = validNums.maxOrNull() ?: 1
                    val existingNums = validNums.toSet()

                    for (num in (minNum + 1) until maxNum) {
                        if (!existingNums.contains(num)) {
                            val times = getStandardLessonTimes(num)
                            val windowItem = LessonScheduleItem(
                                lesson_num = num,
                                start_time = times.first,
                                end_time = times.second,
                                subject_name = "Окно",
                                lesson_type = "Свободное время",
                                is_other_subgroup = true,
                                is_current_subgroup = false,
                                is_window = true
                            )
                            slotList.add(
                                ScheduleSlot(
                                    lessonNum = num,
                                    startTime = times.first,
                                    endTime = times.second,
                                    lessons = listOf(windowItem),
                                    isWindow = true
                                )
                            )
                        }
                    }
                }
            }

            return slotList.sortedWith(
                compareBy<ScheduleSlot> { if (it.lessonNum > 0) it.lessonNum else 999 }
                    .thenBy { it.startTime }
            )
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newItems: List<LessonScheduleItem>) {
        this.slots = buildScheduleSlots(newItems, userRole)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_slot, parent, false)
        return SlotViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        val slot = slots[position]

        // 1. Lesson Number Badge
        holder.tvLessonNumBadge.text = if (slot.lessonNum in 1..8) "${slot.lessonNum} пара" else "Пара"

        // 2. Time Slot Interval
        val timeText = if (slot.startTime.isNotBlank() && slot.endTime.isNotBlank()) {
            "${slot.startTime} – ${slot.endTime}"
        } else if (slot.startTime.isNotBlank()) {
            slot.startTime
        } else {
            "Время по расписанию"
        }
        holder.tvScheduleTime.text = timeText

        // 3. Adapter for child lessons
        val pagerAdapter = SlotLessonsPagerAdapter(slot.lessons, userRole)
        holder.rvSlotLessons.adapter = pagerAdapter
        holder.rvSlotLessons.scrollToPosition(0)

        // 4. Smooth touch handling
        setupTouchInterceptor(holder.rvSlotLessons)

        // 5. Dots Indicator & Opacity handling
        val lessonsCount = slot.lessons.size
        holder.clearScrollListeners()

        if (lessonsCount > 1) {
            holder.layoutDotsContainer.visibility = View.VISIBLE
            renderDots(holder.layoutDotsList, lessonsCount, 0, holder.rvSlotLessons)

            var lastSnappedIndex = 0
            val scrollListener = object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val snapView = holder.snapHelper.findSnapView(lm) ?: return
                        val snappedPos = lm.getPosition(snapView)
                        if (snappedPos != RecyclerView.NO_POSITION && snappedPos != lastSnappedIndex) {
                            lastSnappedIndex = snappedPos
                            renderDots(holder.layoutDotsList, lessonsCount, snappedPos, holder.rvSlotLessons)
                            val lesson = slot.lessons.getOrNull(snappedPos)
                            val isOther = (lesson?.is_other_subgroup == true) && (userRole != "teacher")
                            holder.cardScheduleSlot.animate()
                                .alpha(if (isOther) 0.52f else 1.0f)
                                .setDuration(180)
                                .start()
                        }
                    }
                }
            }
            holder.rvSlotLessons.addOnScrollListener(scrollListener)
            holder.activeScrollListener = scrollListener

            // Initial alpha for page 0
            val initialLesson = slot.lessons.firstOrNull()
            val initialOther = (initialLesson?.is_other_subgroup == true || slot.isWindow) && (userRole != "teacher")
            holder.cardScheduleSlot.alpha = if (initialOther) 0.52f else 1.0f
        } else {
            holder.layoutDotsContainer.visibility = View.GONE
            val singleLesson = slot.lessons.firstOrNull()
            val isOther = (singleLesson?.is_other_subgroup == true || slot.isWindow) && (userRole != "teacher")
            holder.cardScheduleSlot.alpha = if (isOther) 0.52f else 1.0f
        }
    }

    override fun getItemCount(): Int = slots.size

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchInterceptor(recyclerView: RecyclerView) {
        var startX = 0f
        var startY = 0f

        recyclerView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > dy && dx > 8f) {
                        // User is swiping horizontally: keep touch in horizontal recycler
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    } else if (dy > dx && dy > 8f) {
                        // User is scrolling vertically: let parent list scroll
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private fun renderDots(container: LinearLayout, totalCount: Int, selectedIndex: Int, targetRecycler: RecyclerView) {
        container.removeAllViews()
        val context = container.context
        val density = context.resources.displayMetrics.density

        for (i in 0 until totalCount) {
            val dot = View(context)
            val isSelected = (i == selectedIndex)
            val widthDp = if (isSelected) 14 else 5
            val heightDp = 5
            val params = LinearLayout.LayoutParams(
                (widthDp * density).toInt(),
                (heightDp * density).toInt()
            ).apply {
                marginStart = if (i == 0) 0 else (4 * density).toInt()
            }
            dot.layoutParams = params
            dot.setBackgroundResource(
                if (isSelected) R.drawable.dot_indicator_active else R.drawable.dot_indicator_inactive
            )
            dot.setOnClickListener {
                targetRecycler.smoothScrollToPosition(i)
            }
            container.addView(dot)
        }
    }

    class SlotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardScheduleSlot: MaterialCardView = view.findViewById(R.id.cardScheduleSlot)
        val tvLessonNumBadge: TextView = view.findViewById(R.id.tvLessonNumBadge)
        val tvScheduleTime: TextView = view.findViewById(R.id.tvScheduleTime)
        val layoutDotsContainer: LinearLayout = view.findViewById(R.id.layoutDotsContainer)
        val layoutDotsList: LinearLayout = view.findViewById(R.id.layoutDotsList)
        val rvSlotLessons: RecyclerView = view.findViewById(R.id.rvSlotLessons)
        val snapHelper = PagerSnapHelper()
        var activeScrollListener: RecyclerView.OnScrollListener? = null

        init {
            rvSlotLessons.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            snapHelper.attachToRecyclerView(rvSlotLessons)
        }

        fun clearScrollListeners() {
            activeScrollListener?.let {
                rvSlotLessons.removeOnScrollListener(it)
                activeScrollListener = null
            }
        }
    }

    /**
     * Internal adapter for swiping between parallel lessons in a slot
     */
    class SlotLessonsPagerAdapter(
        private val lessons: List<LessonScheduleItem>,
        private val userRole: String
    ) : RecyclerView.Adapter<SlotLessonsPagerAdapter.LessonPageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LessonPageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_slot_page, parent, false)
            // Each page fills the full width of the card
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return LessonPageViewHolder(view)
        }

        override fun onBindViewHolder(holder: LessonPageViewHolder, position: Int) {
            val item = lessons[position]
            val context = holder.itemView.context

            if (item.is_window) {
                holder.tvScheduleSubject.text = "Окно"
                holder.ivTeacherGroupIcon.visibility = View.GONE
                holder.tvTeacherOrGroup.text = "Свободное время между парами"
                holder.tvScheduleLessonType.text = "Окно"
                holder.tvScheduleRoom.visibility = View.GONE

                holder.tvScheduleSubgroupBadge.visibility = View.VISIBLE
                holder.tvScheduleSubgroupBadge.text = "Окно"
                holder.tvScheduleSubgroupBadge.setBackgroundResource(R.drawable.bg_badge_other_subgroup)
                holder.tvScheduleSubgroupBadge.setTextColor(ContextCompat.getColor(context, R.color.sib_warning))

                holder.viewLessonColorStrip.setBackgroundColor(ContextCompat.getColor(context, R.color.sib_warning))
                return
            }

            // Normal lesson styling
            holder.ivTeacherGroupIcon.visibility = View.VISIBLE

            // Subject Name
            holder.tvScheduleSubject.text = item.subject_name.ifBlank { "Занятие" }

            // Teacher vs Group
            if (userRole == "teacher" || userRole == "admin") {
                val group = item.group_name?.takeIf { it.isNotBlank() } ?: "Группа"
                holder.tvTeacherOrGroup.text = "Группа: $group"
                holder.ivTeacherGroupIcon.setImageResource(R.drawable.ic_person)
            } else {
                val teacher = item.teacher_name?.takeIf { it.isNotBlank() } ?: "Преподаватель кафедры"
                holder.tvTeacherOrGroup.text = teacher
                holder.ivTeacherGroupIcon.setImageResource(R.drawable.ic_person)
            }

            // Lesson Type
            val rawType = item.lesson_type.ifBlank { "Практика" }
            holder.tvScheduleLessonType.text = rawType

            // Room Info
            if (item.room_info.isNotBlank()) {
                holder.tvScheduleRoom.visibility = View.VISIBLE
                holder.tvScheduleRoom.text = if (item.room_info.startsWith("Ауд", ignoreCase = true) || item.room_info.startsWith("Лаб", ignoreCase = true)) {
                    item.room_info
                } else {
                    "Ауд. ${item.room_info}"
                }
            } else {
                holder.tvScheduleRoom.visibility = View.VISIBLE
                holder.tvScheduleRoom.text = "Аудитория кафедры"
            }

            // Subgroup Badge & Styling
            val isOther = item.is_other_subgroup && (userRole != "teacher")
            if (isOther) {
                holder.tvScheduleSubgroupBadge.visibility = View.VISIBLE
                val sgName = if (item.subgroup.isNotBlank() && item.subgroup != "_") item.subgroup else "Окно"
                holder.tvScheduleSubgroupBadge.text = "Чужая ($sgName)"
                holder.tvScheduleSubgroupBadge.setBackgroundResource(R.drawable.bg_badge_other_subgroup)
                holder.tvScheduleSubgroupBadge.setTextColor(ContextCompat.getColor(context, R.color.sib_warning))
            } else if (item.subgroup.isNotBlank() && item.subgroup != "_") {
                holder.tvScheduleSubgroupBadge.visibility = View.VISIBLE
                holder.tvScheduleSubgroupBadge.text = "Ваша (${item.subgroup})"
                holder.tvScheduleSubgroupBadge.setBackgroundResource(R.drawable.bg_badge_subgroup_own)
                holder.tvScheduleSubgroupBadge.setTextColor(ContextCompat.getColor(context, R.color.uni_blue_primary))
            } else {
                holder.tvScheduleSubgroupBadge.visibility = View.VISIBLE
                holder.tvScheduleSubgroupBadge.text = "Вся группа"
                holder.tvScheduleSubgroupBadge.setBackgroundResource(R.drawable.bg_badge_type)
                holder.tvScheduleSubgroupBadge.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            }

            // Left Color Strip
            val stripColorRes = when {
                isOther -> R.color.sib_warning
                rawType.contains("Лекция", ignoreCase = true) -> R.color.uni_blue_primary
                rawType.contains("Лаб", ignoreCase = true) -> R.color.badge_late_icon
                rawType.contains("Факульт", ignoreCase = true) -> R.color.badge_ontime_icon
                rawType.contains("Практ", ignoreCase = true) -> R.color.badge_ontime_icon
                else -> R.color.uni_blue_accent
            }
            holder.viewLessonColorStrip.setBackgroundColor(ContextCompat.getColor(context, stripColorRes))
        }

        override fun getItemCount(): Int = lessons.size

        class LessonPageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val viewLessonColorStrip: View = view.findViewById(R.id.viewLessonColorStrip)
            val tvScheduleSubject: TextView = view.findViewById(R.id.tvScheduleSubject)
            val ivTeacherGroupIcon: ImageView = view.findViewById(R.id.ivTeacherGroupIcon)
            val tvTeacherOrGroup: TextView = view.findViewById(R.id.tvTeacherOrGroup)
            val tvScheduleRoom: TextView = view.findViewById(R.id.tvScheduleRoom)
            val tvScheduleLessonType: TextView = view.findViewById(R.id.tvScheduleLessonType)
            val tvScheduleSubgroupBadge: TextView = view.findViewById(R.id.tvScheduleSubgroupBadge)
        }
    }
}
