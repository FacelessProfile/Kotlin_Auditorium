package com.example.kotlinroomdatabase.fragments.schedule

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleFragment : Fragment() {

    private lateinit var repository: IStudentRepository
    private lateinit var adapter: ScheduleAdapter

    private var currentCalendar: Calendar = Calendar.getInstance()
    private var userRole: String = "student"

    private lateinit var tvSelectedDate: TextView
    private lateinit var tvSelectedWeekday: TextView
    private lateinit var btnPrevDay: ImageButton
    private lateinit var btnNextDay: ImageButton
    private lateinit var btnToday: MaterialButton
    private lateinit var layoutDatePicker: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvScheduleLessons: RecyclerView
    private lateinit var layoutEmptySchedule: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        userRole = prefs.getString("user_role", "student") ?: "student"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_schedule, container, false)

        tvSelectedDate = view.findViewById(R.id.tvSelectedDate)
        tvSelectedWeekday = view.findViewById(R.id.tvSelectedWeekday)
        btnPrevDay = view.findViewById(R.id.btnPrevDay)
        btnNextDay = view.findViewById(R.id.btnNextDay)
        btnToday = view.findViewById(R.id.btnToday)
        layoutDatePicker = view.findViewById(R.id.layoutDatePicker)
        swipeRefresh = view.findViewById(R.id.swipeRefreshSchedule)
        rvScheduleLessons = view.findViewById(R.id.rvScheduleLessons)
        layoutEmptySchedule = view.findViewById(R.id.layoutEmptySchedule)

        adapter = ScheduleAdapter(userRole)
        rvScheduleLessons.layoutManager = LinearLayoutManager(requireContext())
        rvScheduleLessons.adapter = adapter

        btnPrevDay.setOnClickListener {
            currentCalendar.add(Calendar.DAY_OF_YEAR, -1)
            loadSchedule()
        }

        btnNextDay.setOnClickListener {
            currentCalendar.add(Calendar.DAY_OF_YEAR, 1)
            loadSchedule()
        }

        btnToday.setOnClickListener {
            currentCalendar = Calendar.getInstance()
            loadSchedule()
        }

        layoutDatePicker.setOnClickListener {
            showDatePickerDialog()
        }

        swipeRefresh.setOnRefreshListener {
            loadSchedule()
        }

        loadSchedule()

        return view
    }

    private fun showDatePickerDialog() {
        val year = currentCalendar.get(Calendar.YEAR)
        val month = currentCalendar.get(Calendar.MONTH)
        val day = currentCalendar.get(Calendar.DAY_OF_MONTH)

        val dpd = DatePickerDialog(
            requireContext(),
            { _, selectedYear, selectedMonth, selectedDay ->
                currentCalendar.set(selectedYear, selectedMonth, selectedDay)
                loadSchedule()
            },
            year,
            month,
            day
        )
        dpd.show()
    }

    private fun loadSchedule() {
        val apiFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateQuery = apiFormat.format(currentCalendar.time)

        updateDateHeader()
        swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                val result = repository.getScheduleForDay(dateQuery)
                swipeRefresh.isRefreshing = false

                when (result) {
                    is GenericResult.Success -> {
                        val schedule = result.data
                        val lessons = schedule.lessons

                        if (lessons.isEmpty()) {
                            layoutEmptySchedule.visibility = View.VISIBLE
                            rvScheduleLessons.visibility = View.GONE
                        } else {
                            layoutEmptySchedule.visibility = View.GONE
                            rvScheduleLessons.visibility = View.VISIBLE
                            adapter.setData(lessons)
                        }

                        // Update week parity if returned
                        val weekParity = if (schedule.week_type % 2 == 1) "1 неделя (Нечётная)" else "2 неделя (Чётная)"
                        val weekdayRu = getRussianWeekday(currentCalendar.get(Calendar.DAY_OF_WEEK))
                        tvSelectedWeekday.text = "$weekdayRu • $weekParity"
                    }
                    is GenericResult.Error -> {
                        layoutEmptySchedule.visibility = View.VISIBLE
                        rvScheduleLessons.visibility = View.GONE
                        Toast.makeText(requireContext(), "Не удалось загрузить расписание", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                swipeRefresh.isRefreshing = false
                layoutEmptySchedule.visibility = View.VISIBLE
                rvScheduleLessons.visibility = View.GONE
                Toast.makeText(requireContext(), "Ошибка соединения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDateHeader() {
        val dateDisplayFormat = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
        tvSelectedDate.text = dateDisplayFormat.format(currentCalendar.time)

        val weekdayRu = getRussianWeekday(currentCalendar.get(Calendar.DAY_OF_WEEK))
        tvSelectedWeekday.text = weekdayRu
    }

    private fun getRussianWeekday(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.MONDAY -> "Понедельник"
            Calendar.TUESDAY -> "Вторник"
            Calendar.WEDNESDAY -> "Среда"
            Calendar.THURSDAY -> "Четверг"
            Calendar.FRIDAY -> "Пятница"
            Calendar.SATURDAY -> "Суббота"
            Calendar.SUNDAY -> "Воскресенье"
            else -> ""
        }
    }
}
