package com.example.kotlinroomdatabase.fragments.list

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.provider.Settings
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.getColorFromAttr
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.repository.GenericResult
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
//QR
import android.net.Uri
import android.util.Base64
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.content.ComponentName
import com.example.kotlinroomdatabase.nfc.HCEservice

class User_Interface : Fragment() {
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var tvWelcome: TextView
    private lateinit var attendedCard: MaterialCardView
    private lateinit var tvActiveLessonName: TextView
    private lateinit var tvActiveLessonDetails: TextView
    private lateinit var btnCheckLessonStatus: MaterialButton
    private lateinit var btnScan: MaterialButton
    private lateinit var btnHistory: MaterialButton
    private lateinit var btnSchedule: MaterialButton
    private lateinit var biometricHelper: com.example.kotlinroomdatabase.crypto.BiometricAuthHelper
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.user_ui, container, false)
        rootLayout = view.findViewById(R.id.rootLayout)
        statusIcon = view.findViewById(R.id.statusIcon)
        statusText = view.findViewById(R.id.statusText)
        tvWelcome = view.findViewById(R.id.tvWelcome)
        attendedCard = view.findViewById(R.id.attendedCard)
        tvActiveLessonName = view.findViewById(R.id.tvActiveLessonName)
        tvActiveLessonDetails = view.findViewById(R.id.tvActiveLessonDetails)
        btnCheckLessonStatus = view.findViewById(R.id.btnCheckLessonStatus)
        btnScan = view.findViewById(R.id.btnScan)
        btnHistory = view.findViewById(R.id.btnHistory)
        btnSchedule = view.findViewById(R.id.btnSchedule)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        biometricHelper = com.example.kotlinroomdatabase.crypto.BiometricAuthHelper(this)

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val fullName = prefs.getString("student_name", "Студент")
        var displayName = "Студент"

        if (!fullName.isNullOrBlank() && fullName != "Студент") {
            val parts = fullName.trim().split(" ")
            displayName = when {
                parts.size >= 3 -> "${parts[1]} ${parts[2]}"
                parts.size == 2 -> parts[1]
                else -> parts[0]
            }
        }

        val sId = prefs.getInt("current_student_id", 0)
        val sName = prefs.getString("student_name", "Student") ?: "Student"
        if (sId > 0) {
            prefs.edit().putString("nfc_payload", "STUDENT:$sId:$sName").apply()
        }

        tvWelcome.text = "Добро пожаловать,\n$displayName"
        tvWelcome.setOnLongClickListener {
            com.example.kotlinroomdatabase.config.ServerConfig.showServerSwitcherDialog(requireContext()) {
                checkLessonStatusRemote(showToast = false)
            }
            true
        }

        val appPrefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val primaryColorHex = appPrefs.getString("button_color", "#C48E17")
        primaryColorHex?.let {
            val color = Color.parseColor(it)
            btnScan.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        }

        btnScan.setOnClickListener {
            startScanning()
        }

        btnHistory.setOnClickListener {
            findNavController().navigate(R.id.action_userHome_to_history)
        }

        btnSchedule.setOnClickListener {
            findNavController().navigate(R.id.action_userHome_to_schedule)
        }

        btnCheckLessonStatus.setOnClickListener {
            checkLessonStatusRemote(showToast = true)
        }

        checkAndUpdateLessonState()
        checkLessonStatusRemote(showToast = false)
    }

    private var statusPollingJob: Job? = null

    override fun onResume() {
        super.onResume()
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
                } catch (e: Exception) {}
                val setPrefResult = cardEmulation.setPreferredService(requireActivity(), hceComponent)
                android.util.Log.i("User_Interface", "setPreferredService result: $setPrefResult for HCEservice")
            }
        } catch (e: Exception) {
            android.util.Log.e("User_Interface", "Error setting preferred HCE service", e)
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
        statusPollingJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                checkLessonStatusRemote(showToast = false)
                delay(3500)
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
        } catch (e: Exception) {}
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {}
    }

    private fun checkAndUpdateLessonState() {
        if (!isAdded) return
        val context = context ?: return
        val attPrefs = context.getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        val currentServer = ServerConfig.getBaseUrl(context)
        val savedServer = attPrefs.getString(KEY_SERVER_URL, null)

        // If server changed, clear stale attendance state immediately
        if (savedServer != null && savedServer != currentServer) {
            clearActiveLessonState()
            setNeutralState()
            attendedCard.visibility = View.GONE
            btnScan.visibility = View.VISIBLE
            return
        }

        val isAttended = attPrefs.getBoolean(KEY_IS_ATTENDED, false)
        val expiresAt = attPrefs.getLong(KEY_EXPIRES_AT, 0L)
        val lessonName = attPrefs.getString(KEY_LESSON_NAME, "")
        val attendedTime = attPrefs.getLong(KEY_ATTENDED_TIME, 0L)
        val now = System.currentTimeMillis()

        if (isAttended && !lessonName.isNullOrBlank()) {
            if (expiresAt > 0 && now >= expiresAt) {
                // Lesson expired automatically
                clearActiveLessonState()
                setNeutralState()
                attendedCard.visibility = View.GONE
                btnScan.visibility = View.VISIBLE
            } else {
                // Student is currently in active lesson
                attendedCard.visibility = View.VISIBLE
                btnScan.visibility = View.GONE

                tvActiveLessonName.text = lessonName
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val markedStr = if (attendedTime > 0) "Отметка зафиксирована в ${timeFormat.format(Date(attendedTime))}" else "Присутствие зафиксировано"
                val remainingMin = if (expiresAt > now) ((expiresAt - now) / 60000).toInt() else 0
                val timerStr = if (remainingMin > 0) "Занятие идет (осталось ~${remainingMin} мин)" else "Занятие сейчас идёт"

                tvActiveLessonDetails.text = "$markedStr\n$timerStr"

                statusIcon.setImageResource(R.drawable.ic_check)
                statusIcon.setColorFilter(Color.parseColor("#10B981"))
                statusText.text = "Вы присутствуете на паре"
                statusText.setTextColor(Color.parseColor("#10B981"))
            }
        } else {
            attendedCard.visibility = View.GONE
            btnScan.visibility = View.VISIBLE
            setNeutralState()
        }
    }

    private fun checkLessonStatusRemote(showToast: Boolean = false) {
        if (!isAdded) return
        val context = context ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val repository = com.example.kotlinroomdatabase.settings.RepositoryHTTPS.getStudentRepository(context)
            val result = repository.getActiveStudentLesson()

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
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
                                } catch (e: Exception) {}
                            }

                            saveActiveLessonState(
                                lessonName = if (info.lesson_name.isNotBlank()) info.lesson_name else info.subject_name,
                                lessonId = info.session_id,
                                expiresAtMillis = expiresAtMillis
                            )
                            checkAndUpdateLessonState()
                            if (showToast) {
                                val remainingMin = ((expiresAtMillis - System.currentTimeMillis()) / 60000).toInt()
                                Toast.makeText(context, "Занятие активно. Осталось ~${maxOf(0, remainingMin)} мин.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Server says no active lesson!
                            val wasAttended = context.getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
                                .getBoolean(KEY_IS_ATTENDED, false)
                            clearActiveLessonState()
                            checkAndUpdateLessonState()
                            if (showToast) {
                                if (wasAttended) {
                                    Toast.makeText(context, "Занятие завершено. Сканер разблокирован!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Нет активных занятий", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    is GenericResult.Error -> {
                        if (showToast) {
                            Toast.makeText(context, "Не удалось связаться с сервером", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun clearActiveLessonState() {
        val context = context ?: return
        val attPrefs = context.getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        attPrefs.edit().clear().apply()
    }

    private fun saveActiveLessonState(lessonName: String, lessonId: Int, expiresAtMillis: Long) {
        val context = context ?: return
        val currentServer = ServerConfig.getBaseUrl(context)
        val attPrefs = context.getSharedPreferences(PREFS_ATTENDANCE, Context.MODE_PRIVATE)
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
        lifecycleScope.launch(Dispatchers.Main) {
            setSuccessState()
            statusText.text = "Присутствие зафиксировано"
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
                        lifecycleScope.launch {
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

    private fun setSuccessState() {
        statusIcon.setImageResource(R.drawable.ic_check)
        statusIcon.setColorFilter(Color.parseColor("#10B981"))
        statusText.setTextColor(Color.parseColor("#10B981"))
    }

    private fun setNeutralState() {
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.colorFilter = null
        statusText.text = "Поднесите к чекеру"
        statusText.setTextColor(requireContext().getColorFromAttr(android.R.attr.textColorPrimary))
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(requireContext(), "Сканирование отменено", Toast.LENGTH_SHORT).show()
        } else {
            val scannedData = result.contents
            handleScannedUrl(scannedData)
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
        } catch (e: Exception) {
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

            // If token was not in query params, inspect the URL fragment (e.g. #/attendance/join?token=eyJ...)
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
            // Raw JWT invite token
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
            if (Math.abs(nowSec - ts) > 8) {
                Toast.makeText(requireContext(), "QR-код устарел! Наведите камеру на актуальный QR-код на экране", Toast.LENGTH_LONG).show()
                return
            }
        }

        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            try {
                val uri = Uri.parse(raw)
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                val scannedServerOrigin = "${uri.scheme}://${uri.host}$portStr"
                val currentServer = com.example.kotlinroomdatabase.config.ServerConfig.getBaseUrl(requireContext())
                if (!currentServer.equals(scannedServerOrigin, ignoreCase = true)) {
                    android.util.Log.d("User_Interface", "Auto-aligning server to QR origin: $scannedServerOrigin (was $currentServer)")
                    com.example.kotlinroomdatabase.config.ServerConfig.setCustomServerUrl(requireContext(), scannedServerOrigin)
                }
            } catch (e: Exception) {
                android.util.Log.w("User_Interface", "Failed to parse scanned origin: ${e.message}")
            }
        }

        if (!token.isNullOrBlank() || effectiveLessonId > 0) {
            markAttendance(effectiveLessonId, token, totpCode, ts, nonce)
        } else if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "QR-код не содержит данных о посещаемости", Toast.LENGTH_SHORT).show()
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
                        lifecycleScope.launch(Dispatchers.IO) {
                            val repository = com.example.kotlinroomdatabase.settings.RepositoryHTTPS.getStudentRepository(requireContext())
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
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), "Нет разрешения на геолокацию", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanning() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Наведите камеру на QR-код занятия")
        options.setBeepEnabled(true)
        options.setBarcodeImageEnabled(true)
        options.setOrientationLocked(true)
        barcodeLauncher.launch(options)
    }
}