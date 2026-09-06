package com.example.kotlinroomdatabase.fragments.list

import android.Manifest
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.databinding.UserUiBinding
import com.example.kotlinroomdatabase.fragments.schedule.ScheduleAdapter
import com.example.kotlinroomdatabase.nfc.HCEservice
import com.example.kotlinroomdatabase.qr.CustomScannerActivity
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.RoleUtils
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class User_Interface : Fragment() {

    private var _binding: UserUiBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: IStudentRepository
    private lateinit var scheduleAdapter: ScheduleAdapter
    private lateinit var biometricHelper: com.example.kotlinroomdatabase.crypto.BiometricAuthHelper

    private var currentCalendar: Calendar = Calendar.getInstance()
    private var userRole: String = "student"
    private var statusPollingJob: Job? = null
    private var isProcessing = false

    companion object {
        const val PREFS_ATTENDANCE = "student_attendance_state_prefs"
        const val KEY_SERVER_URL = "active_lesson_server_url"
        const val KEY_IS_ATTENDED = "active_lesson_attended"
        const val KEY_LESSON_NAME = "active_lesson_name"
        const val KEY_LESSON_ID = "active_lesson_id"
        const val KEY_ATTENDED_TIME = "active_lesson_attended_time"
        const val KEY_EXPIRES_AT = "active_lesson_expires_at"
        const val ACTION_LESSON_FINISHED = "LESSON_FINISHED_EVENT"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = UserUiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        biometricHelper = com.example.kotlinroomdatabase.crypto.BiometricAuthHelper(this)

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        userRole = prefs.getString("user_role", "student") ?: "student"
        val fullName = prefs.getString("student_name", "Студент") ?: "Студент"
        val groupName = prefs.getString("student_group", "") ?: ""

        val isTeacher = RoleUtils.isTeacherOrHead(userRole)

        // 1. Setup Hero Section
        binding.tvHeroRole.text = RoleUtils.getRoleHeroTitle(userRole)
        binding.tvHeroName.text = RoleUtils.formatShortName(fullName)
        binding.tvHeroGroup.text = if (isTeacher) {
            "Кафедра • СибГУТИ"
        } else if (groupName.isNotBlank()) {
            "Группа $groupName"
        } else {
            "СибГУТИ"
        }

        // Navigate to Profile when clicking the avatar/icon or hero button
        val openProfile = {
            if (findNavController().currentDestination?.id != R.id.profileFragment) {
                findNavController().navigate(R.id.profileFragment)
            }
        }
        binding.btnHeroProfile.setOnClickListener { openProfile() }
        binding.ivHeroIcon.setOnClickListener { openProfile() }

        loadHeroAvatar()

        binding.cardHero.setOnLongClickListener {
            ServerConfig.showServerSwitcherDialog(requireContext()) {
                loadSchedule()
                checkLessonStatusRemote(showToast = false)
            }
            true
        }

        // 2. Setup Quick Actions per Role
        if (isTeacher) {
            binding.layoutStudentActions.visibility = View.GONE
            binding.layoutTeacherActions.visibility = View.VISIBLE

            binding.cardTeacherStartLesson.setOnClickListener {
                findNavController().navigate(R.id.lessonFragment)
            }
            binding.cardTeacherGrades.setOnClickListener {
                findNavController().navigate(R.id.gradesFragment)
            }
            binding.cardTeacherStudents.setOnClickListener {
                findNavController().navigate(R.id.listFragment)
            }
            binding.cardTeacherAnalytics.setOnClickListener {
                findNavController().navigate(R.id.analyticsFragment)
            }
        } else {
            binding.layoutStudentActions.visibility = View.VISIBLE
            binding.layoutTeacherActions.visibility = View.GONE

            binding.cardNfcPass.setOnClickListener {
                showNfcPassDialog()
            }
            binding.cardScanQr.setOnClickListener {
                startScanning()
            }
            binding.cardGrades.setOnClickListener {
                findNavController().navigate(R.id.gradesFragment)
            }
            binding.cardHistory.setOnClickListener {
                findNavController().navigate(R.id.historyFragment)
            }

            // Store student NFC payload if available
            val sId = prefs.getInt("current_student_id", 0)
            if (sId > 0) {
                val authPrefs = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                val token = authPrefs.getString("auth_token", "") ?: ""
                val payload = if (token.isNotEmpty()) token else "STUDENT:$sId:$fullName"
                prefs.edit().putString("nfc_payload", payload).apply()
            }
        }

        // Active Lesson check status listener
        binding.btnCheckLessonStatus.setOnClickListener {
            checkLessonStatusRemote(showToast = true)
        }

        // 3. Setup Integrated Schedule
        scheduleAdapter = ScheduleAdapter(userRole)
        binding.rvSchedule.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSchedule.adapter = scheduleAdapter

        binding.btnPrevDay.setOnClickListener {
            currentCalendar.add(Calendar.DAY_OF_YEAR, -1)
            loadSchedule()
        }

        binding.btnNextDay.setOnClickListener {
            currentCalendar.add(Calendar.DAY_OF_YEAR, 1)
            loadSchedule()
        }

        binding.btnToday.setOnClickListener {
            currentCalendar = Calendar.getInstance()
            loadSchedule()
        }

        binding.layoutDatePicker.setOnClickListener {
            showDatePickerDialog()
        }

        setupWeekdayChips()

        // 4. Setup Swipe Refresh
        binding.swipeRefreshDashboard.setOnRefreshListener {
            loadSchedule()
            checkLessonStatusRemote(showToast = false)
        }

        checkAndUpdateLessonState()
        checkLessonStatusRemote(showToast = false)
        loadSchedule()
    }

    private fun showNfcPassDialog() {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentName = prefs.getString("student_name", "Студент") ?: "Студент"
        val group = prefs.getString("student_group", "СибГУТИ") ?: "СибГУТИ"
        val nfcPayload = prefs.getString("nfc_payload", "Активен")

        AlertDialog.Builder(requireContext())
            .setTitle("Электронный пропуск СибГУТИ")
            .setMessage("Владелец: $studentName\nГруппа: $group\n\nСтатус HCE: Эмуляция карты активна.\nПоднесите заднюю панель смартфона к турникету или валидатору аудитории.")
            .setIcon(R.drawable.ic_nfc)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun setupWeekdayChips() {
        val chips = listOf(
            binding.chipMon to Calendar.MONDAY,
            binding.chipTue to Calendar.TUESDAY,
            binding.chipWed to Calendar.WEDNESDAY,
            binding.chipThu to Calendar.THURSDAY,
            binding.chipFri to Calendar.FRIDAY,
            binding.chipSat to Calendar.SATURDAY
        )

        for ((chip, targetDayOfWeek) in chips) {
            chip.setOnClickListener {
                setDayOfWeek(targetDayOfWeek)
                loadSchedule()
            }
        }
    }

    private fun setDayOfWeek(dayOfWeek: Int) {
        currentCalendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
    }

    private fun updateWeekdayStripSelection() {
        if (_binding == null) return
        val currentDow = currentCalendar.get(Calendar.DAY_OF_WEEK)

        val chips = listOf(
            binding.chipMon to Calendar.MONDAY,
            binding.chipTue to Calendar.TUESDAY,
            binding.chipWed to Calendar.WEDNESDAY,
            binding.chipThu to Calendar.THURSDAY,
            binding.chipFri to Calendar.FRIDAY,
            binding.chipSat to Calendar.SATURDAY
        )

        for ((chip, dow) in chips) {
            if (dow == currentDow) {
                chip.setBackgroundResource(R.drawable.bg_weekday_chip_active)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_white))
            } else {
                chip.setBackgroundResource(R.drawable.bg_weekday_chip_inactive)
                chip.setTextColor(requireContext().getColor(R.color.sib_text_primary))
            }
        }
    }

    private fun showDatePickerDialog() {
        val year = currentCalendar.get(Calendar.YEAR)
        val month = currentCalendar.get(Calendar.MONTH)
        val day = currentCalendar.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(
            requireContext(),
            { _, selectedYear, selectedMonth, selectedDay ->
                currentCalendar.set(selectedYear, selectedMonth, selectedDay)
                loadSchedule()
            },
            year,
            month,
            day
        ).show()
    }

    private fun loadSchedule() {
        if (_binding == null) return
        val apiFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateQuery = apiFormat.format(currentCalendar.time)

        updateDateHeader()
        updateWeekdayStripSelection()
        binding.progressSchedule.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = repository.getScheduleForDay(dateQuery)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefreshDashboard.isRefreshing = false
                    binding.progressSchedule.visibility = View.GONE

                    when (result) {
                        is GenericResult.Success -> {
                            val schedule = result.data
                            val lessons = schedule.lessons

                            if (lessons.isEmpty()) {
                                binding.layoutEmptySchedule.visibility = View.VISIBLE
                                binding.rvSchedule.visibility = View.GONE
                            } else {
                                binding.layoutEmptySchedule.visibility = View.GONE
                                binding.rvSchedule.visibility = View.VISIBLE
                                scheduleAdapter.setData(lessons)
                            }

                            val parityText = if (schedule.week_type % 2 == 1) "1 неделя • Нечётная" else "2 неделя • Чётная"
                            binding.tvWeekParityBadge.text = parityText
                        }
                        is GenericResult.Error -> {
                            binding.layoutEmptySchedule.visibility = View.VISIBLE
                            binding.rvSchedule.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefreshDashboard.isRefreshing = false
                    binding.progressSchedule.visibility = View.GONE
                    binding.layoutEmptySchedule.visibility = View.VISIBLE
                    binding.rvSchedule.visibility = View.GONE
                }
            }
        }
    }

    private fun updateDateHeader() {
        val dateDisplayFormat = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
        binding.tvScheduleDate.text = dateDisplayFormat.format(currentCalendar.time)
        val weekdayRu = getRussianWeekday(currentCalendar.get(Calendar.DAY_OF_WEEK))
        binding.tvScheduleWeekday.text = weekdayRu
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

    private fun loadHeroAvatar() {
        val prefs = context?.getSharedPreferences("student_prefs", Context.MODE_PRIVATE) ?: return
        val avatarPath = prefs.getString("avatar_path", null)
        val defaultPad = (10 * resources.displayMetrics.density).toInt()
        if (avatarPath != null) {
            val avatarFile = java.io.File(avatarPath)
            if (avatarFile.exists()) {
                try {
                    binding.ivHeroIcon.setPadding(0, 0, 0, 0)
                    binding.ivHeroIcon.setImageURI(null)
                    binding.ivHeroIcon.setImageURI(android.net.Uri.fromFile(avatarFile))
                    binding.ivHeroIcon.imageTintList = null
                    return
                } catch (_: Exception) {}
            }
        }
        binding.ivHeroIcon.setPadding(defaultPad, defaultPad, defaultPad, defaultPad)
        binding.ivHeroIcon.setImageResource(R.drawable.ic_person)
        context?.let { ctx ->
            binding.ivHeroIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.sib_blue_primary)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        loadHeroAvatar()
        checkAndUpdateLessonState()
        checkLessonStatusRemote(showToast = false)
        startStatusPolling()

        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
            if (nfcAdapter != null && nfcAdapter.isEnabled) {
                val cardEmulation = CardEmulation.getInstance(nfcAdapter)
                val hceComponent = ComponentName(requireContext(), HCEservice::class.java)
                try {
                    cardEmulation.removeAidsForService(hceComponent, "payment")
                    cardEmulation.removeAidsForService(hceComponent, "other")
                } catch (_: Exception) {}
                cardEmulation.setPreferredService(requireActivity(), hceComponent)
            }
        } catch (e: Exception) {
            Log.e("User_Interface", "Error setting preferred HCE service", e)
        }

        val filter = IntentFilter().apply {
            addAction("NFC_MARK_SUCCESS")
            addAction(ACTION_LESSON_FINISHED)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(broadcastReceiver, filter)
    }

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                checkLessonStatusRemote(showToast = false)
                delay(4000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        statusPollingJob?.cancel()
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
            if (nfcAdapter != null) {
                val cardEmulation = CardEmulation.getInstance(nfcAdapter)
                cardEmulation.unsetPreferredService(requireActivity())
            }
        } catch (_: Exception) {}
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(broadcastReceiver)
        } catch (_: Exception) {}
    }

    private fun checkAndUpdateLessonState() {
        if (!isAdded || _binding == null) return
        val attPrefs = requireContext().getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        val currentServer = ServerConfig.getBaseUrl(requireContext())
        val savedServer = attPrefs.getString(KEY_SERVER_URL, null)

        if (savedServer != null && savedServer != currentServer) {
            clearActiveLessonState()
            setNeutralNfcState()
            binding.attendedCard.visibility = View.GONE
            return
        }

        val isAttended = attPrefs.getBoolean(KEY_IS_ATTENDED, false)
        val expiresAt = attPrefs.getLong(KEY_EXPIRES_AT, 0L)
        val lessonName = attPrefs.getString(KEY_LESSON_NAME, "")
        val attendedTime = attPrefs.getLong(KEY_ATTENDED_TIME, 0L)
        val now = System.currentTimeMillis()

        if (isAttended && !lessonName.isNullOrBlank()) {
            if (expiresAt > 0 && now >= expiresAt) {
                clearActiveLessonState()
                setNeutralNfcState()
                binding.attendedCard.visibility = View.GONE
            } else {
                binding.attendedCard.visibility = View.VISIBLE
                binding.tvActiveLessonName.text = lessonName
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val markedStr = if (attendedTime > 0) "Отметка зафиксирована в ${timeFormat.format(Date(attendedTime))}" else "Присутствие зафиксировано"
                val remainingMin = if (expiresAt > now) ((expiresAt - now) / 60000).toInt() else 0
                val timerStr = if (remainingMin > 0) "Занятие идет (осталось ~${remainingMin} мин)" else "Занятие сейчас идёт"

                binding.tvActiveLessonDetails.text = "$markedStr\n$timerStr"

                binding.statusIcon.setImageResource(R.drawable.ic_check)
                binding.statusIcon.setColorFilter(Color.parseColor("#10B981"))
                binding.statusText.text = "Присутствие подтверждено"
                binding.statusText.setTextColor(Color.parseColor("#10B981"))
            }
        } else {
            binding.attendedCard.visibility = View.GONE
            setNeutralNfcState()
        }
    }

    private fun checkLessonStatusRemote(showToast: Boolean = false) {
        if (!isAdded) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.getActiveStudentLesson()
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                when (result) {
                    is GenericResult.Success -> {
                        val info = result.data
                        if (info.is_active && info.lesson_name.isNotBlank()) {
                            var expiresAtMillis = System.currentTimeMillis() + 90 * 60 * 1000L
                            if (info.expires_at.isNotBlank()) {
                                try {
                                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                                    val parsed = sdf.parse(info.expires_at.substringBefore("Z").substringBefore("+"))?.time
                                    if (parsed != null && parsed > 0) expiresAtMillis = parsed
                                } catch (_: Exception) {}
                            }

                            saveActiveLessonState(
                                lessonName = if (info.lesson_name.isNotBlank()) info.lesson_name else info.subject_name,
                                lessonId = info.session_id,
                                expiresAtMillis = expiresAtMillis
                            )
                            checkAndUpdateLessonState()
                            if (showToast) {
                                val remainingMin = ((expiresAtMillis - System.currentTimeMillis()) / 60000).toInt()
                                Toast.makeText(requireContext(), "Занятие активно. Осталось ~${maxOf(0, remainingMin)} мин.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val wasAttended = requireContext().getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
                                .getBoolean(KEY_IS_ATTENDED, false)
                            clearActiveLessonState()
                            checkAndUpdateLessonState()
                            if (showToast) {
                                if (wasAttended) {
                                    Toast.makeText(requireContext(), "Занятие завершено", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(requireContext(), "Нет активных занятий", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    is GenericResult.Error -> {
                        if (showToast) {
                            Toast.makeText(requireContext(), "Не удалось связаться с сервером", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun clearActiveLessonState() {
        if (!isAdded) return
        val attPrefs = requireContext().getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        attPrefs.edit().clear().apply()
    }

    private fun saveActiveLessonState(lessonName: String, lessonId: Int, expiresAtMillis: Long) {
        if (!isAdded) return
        val currentServer = ServerConfig.getBaseUrl(requireContext())
        val attPrefs = requireContext().getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        attPrefs.edit().apply {
            putString(KEY_SERVER_URL, currentServer)
            putBoolean(KEY_IS_ATTENDED, true)
            putString(KEY_LESSON_NAME, lessonName)
            putInt(KEY_LESSON_ID, lessonId)
            putLong(KEY_ATTENDED_TIME, System.currentTimeMillis())
            putLong(KEY_EXPIRES_AT, expiresAtMillis)
        }.apply()
    }

    fun showSuccessCheck(lessonName: String? = null) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (_binding == null) return@launch
            binding.statusIcon.setImageResource(R.drawable.ic_check)
            binding.statusIcon.setColorFilter(Color.parseColor("#10B981"))
            binding.statusText.setTextColor(Color.parseColor("#10B981"))
            binding.statusText.text = "Присутствие зафиксировано"
            delay(1200)
            checkAndUpdateLessonState()
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "NFC_MARK_SUCCESS" -> {
                    if (!isProcessing) {
                        showSuccessCheck()
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(1200)
                            checkLessonStatusRemote(showToast = false)
                        }
                    }
                }
                ACTION_LESSON_FINISHED -> {
                    clearActiveLessonState()
                    checkAndUpdateLessonState()
                    Toast.makeText(requireContext(), "Преподаватель завершил занятие", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setNeutralNfcState() {
        if (_binding == null) return
        binding.statusIcon.setImageResource(R.drawable.ic_nfc)
        binding.statusIcon.colorFilter = null
        binding.statusText.text = "Поднесите к чекеру"
        binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_secondary))
    }

    private val barcodeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scannedData = result.data?.getStringExtra(CustomScannerActivity.EXTRA_SCAN_RESULT)
            if (!scannedData.isNullOrBlank()) {
                handleScannedUrl(scannedData)
            } else {
                Toast.makeText(requireContext(), "Не удалось распознать QR-код", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "Сканирование отменено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseJwtClaims(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val payloadStr = String(payloadBytes, Charsets.UTF_8)
                JSONObject(payloadStr)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleScannedUrl(scannedText: String) {
        val raw = scannedText.trim()
        var token: String? = null
        var lessonIdStr: String? = null
        var totpCode: String? = null
        var tsStr: String? = null
        var nonce: String? = null

        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            val uri = Uri.parse(raw)
            token = uri.getQueryParameter("token")
            lessonIdStr = uri.getQueryParameter("lesson_id")
            totpCode = uri.getQueryParameter("totp_code")
            tsStr = uri.getQueryParameter("ts")
            nonce = uri.getQueryParameter("nonce")

            val frag = uri.fragment
            if (frag != null) {
                if (token.isNullOrBlank() && frag.contains("token=")) {
                    token = frag.substringAfter("token=").substringBefore("&").substringBefore("#")
                }
                if (lessonIdStr.isNullOrBlank() && frag.contains("lesson_id=")) {
                    lessonIdStr = frag.substringAfter("lesson_id=").substringBefore("&").substringBefore("#")
                }
                if (totpCode.isNullOrBlank() && frag.contains("totp_code=")) {
                    totpCode = frag.substringAfter("totp_code=").substringBefore("&").substringBefore("#")
                }
                if (tsStr.isNullOrBlank() && frag.contains("ts=")) {
                    tsStr = frag.substringAfter("ts=").substringBefore("&").substringBefore("#")
                }
                if (nonce.isNullOrBlank() && frag.contains("nonce=")) {
                    nonce = frag.substringAfter("nonce=").substringBefore("&").substringBefore("#")
                }
            }
        } else if (raw.contains("token=")) {
            token = raw.substringAfter("token=").substringBefore("&").substringBefore("#")
            if (raw.contains("ts=")) tsStr = raw.substringAfter("ts=").substringBefore("&").substringBefore("#")
            if (raw.contains("nonce=")) nonce = raw.substringAfter("nonce=").substringBefore("&").substringBefore("#")
        } else if (raw.length > 20 && !raw.contains(" ")) {
            token = raw
        }

        val parsedLessonId = if (!token.isNullOrBlank()) {
            val claims = parseJwtClaims(token)
            claims?.optString("lesson_id")?.toIntOrNull() ?: claims?.optInt("lesson_id", 0) ?: 0
        } else 0
        val effectiveLessonId = lessonIdStr?.toIntOrNull() ?: parsedLessonId

        val ts = tsStr?.toLongOrNull()
        if (ts != null && ts > 0L) {
            val nowSec = System.currentTimeMillis() / 1000L
            if (Math.abs(nowSec - ts) > 15) {
                Toast.makeText(requireContext(), "QR-код устарел. Наведите камеру на свежий QR-код", Toast.LENGTH_LONG).show()
                return
            }
        }

        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            try {
                val uri = Uri.parse(raw)
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                val scannedServerOrigin = "${uri.scheme}://${uri.host}$portStr"
                val currentServer = ServerConfig.getBaseUrl(requireContext())
                if (!currentServer.equals(scannedServerOrigin, ignoreCase = true)) {
                    ServerConfig.setCustomServerUrl(requireContext(), scannedServerOrigin)
                }
            } catch (e: Exception) {
                Log.w("User_Interface", "Failed to parse scanned origin: ${e.message}")
            }
        }

        if (!token.isNullOrBlank() || effectiveLessonId > 0) {
            markAttendance(effectiveLessonId, token, totpCode, ts, nonce)
        } else {
            Toast.makeText(requireContext(), "QR-код не содержит данных о занятии", Toast.LENGTH_SHORT).show()
        }
    }

    private fun markAttendance(
        lessonId: Int,
        inviteToken: String? = null,
        totpCode: String? = null,
        ts: Long? = null,
        nonce: String? = null
    ) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
                val payloadToSign = "attendance:$lessonId:${nonce ?: ""}:${ts ?: 0}:$deviceId:$lat:$lon"

                biometricHelper.authenticateAndSign(
                    payloadToSign = payloadToSign,
                    title = "Подтверждение присутствия",
                    subtitle = "Приложите палец для подтверждения отметки на занятии",
                    onSuccess = { biometricSig ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val result = repository.markAttendanceViaQr(
                                lessonId,
                                deviceId,
                                lat,
                                lon,
                                inviteToken,
                                totpCode,
                                ts,
                                nonce,
                                biometricSig
                            )

                            withContext(Dispatchers.Main) {
                                if (!isAdded || _binding == null) return@withContext
                                when (result) {
                                    is com.example.kotlinroomdatabase.repository.AttendanceResult.Success -> {
                                        var detectedLessonName = result.lessonName
                                        if (detectedLessonName.isBlank()) {
                                            detectedLessonName = "Учебное занятие"
                                        }
                                        var expiresAtMillis = result.expiresAtMillis
                                        if (expiresAtMillis <= 0L) {
                                            expiresAtMillis = System.currentTimeMillis() + 90 * 60 * 1000L
                                        }

                                        val finalSessionId = if (result.sessionId > 0) result.sessionId else lessonId
                                        saveActiveLessonState(detectedLessonName, finalSessionId, expiresAtMillis)
                                        showSuccessCheck(detectedLessonName)
                                        Toast.makeText(requireContext(), "Вы успешно отметились на занятии!", Toast.LENGTH_LONG).show()
                                    }
                                    is com.example.kotlinroomdatabase.repository.AttendanceResult.Error -> {
                                        val msg = result.message
                                        if (msg.contains("already", ignoreCase = true) || msg.contains("уже", ignoreCase = true)) {
                                            checkLessonStatusRemote(showToast = false)
                                            Toast.makeText(requireContext(), "Вы уже отмечены на этом занятии!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(requireContext(), "Ошибка отметки: $msg", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onError = { err ->
                        Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        } catch (_: SecurityException) {
            Toast.makeText(requireContext(), "Нет разрешения на геолокацию", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanning() {
        val intent = Intent(requireContext(), CustomScannerActivity::class.java).apply {
            putExtra(CustomScannerActivity.EXTRA_PROMPT, "Наведите камеру на QR-код занятия")
        }
        barcodeLauncher.launch(intent)
    }

    fun scrollToTop() {
        _binding?.scrollViewHome?.smoothScrollTo(0, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}