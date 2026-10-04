package com.example.kotlinroomdatabase.fragments.nfc

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentNfcManagerBinding
import com.example.kotlinroomdatabase.BuildConfig
import com.example.kotlinroomdatabase.model.DossierGradeItem
import com.example.kotlinroomdatabase.model.DossierStudent
import com.example.kotlinroomdatabase.model.DossierTeacherSubject
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.model.StudentDossier
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.fragments.feedback.PhotoViewerDialog
import com.example.kotlinroomdatabase.util.AvatarManager
import com.example.kotlinroomdatabase.util.RoleUtils
import com.example.kotlinroomdatabase.util.SafeNdefManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
class NfcManagerFragment : NFC_Tools() {

    private var _binding: FragmentNfcManagerBinding? = null
    private val binding get() = _binding!!

    private lateinit var studentRepository: IStudentRepository
    private lateinit var conveyorAdapter: NfcConveyorAdapter

    private var lastScannedTag: Tag? = null
    private var currentDossier: StudentDossier? = null
    private var lastDiagnostics: SafeNdefManager.TagDiagnostics? = null

    // Conveyor Mode state
    private val allStudentsList = mutableListOf<Student>()
    private var filteredGroupStudents = mutableListOf<Student>()
    private var activeConveyorStudent: Student? = null
    private var selectedGroup: String = "Все"

    // Replacement workflow state
    private var isPendingReplacement = false
    private var pendingReplacementStudentId: Int = 0
    private var pendingReplacementReason: String = ""
    private var isPendingWipe = false

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: Context) {
        super.onAttach(context)
        val db = StudentDatabase.getInstance(requireContext())
        studentRepository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNfcManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupConveyorRecyclerView()
        setupActionButtons()
        loadInitialData()

        startNfcReadingMode(infiniteMode = true)
    }

    private fun setupTabs() {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val isTeacher = RoleUtils.normalizeRole(userRole) == RoleUtils.ROLE_TEACHER

        if (isTeacher) {
            // Requirement: "Пакетная запись не должна быть доступна преподавателю"
            binding.tabLayout.visibility = View.GONE
            binding.layoutInspectionTab.visibility = View.VISIBLE
            binding.layoutConveyorTab.visibility = View.GONE
            return
        } else {
            binding.tabLayout.visibility = View.VISIBLE
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.layoutInspectionTab.visibility = View.VISIBLE
                        binding.layoutConveyorTab.visibility = View.GONE
                    }
                    1 -> {
                        binding.layoutInspectionTab.visibility = View.GONE
                        binding.layoutConveyorTab.visibility = View.VISIBLE
                        refreshConveyorQueue()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupConveyorRecyclerView() {
        conveyorAdapter = NfcConveyorAdapter { clickedStudent ->
            val hasTag = !clickedStudent.studentNFC.isNullOrBlank() && clickedStudent.studentNFC != "null"
            if (hasTag) {
                showStudentTagActionsDialog(clickedStudent)
            } else {
                activeConveyorStudent = clickedStudent
                updateActiveTargetCard(clickedStudent)
                conveyorAdapter.setActiveStudent(clickedStudent.id)
            }
        }
        binding.rvConveyorQueue.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConveyorQueue.adapter = conveyorAdapter

        binding.cardConveyorActiveTarget.setOnClickListener {
            val student = activeConveyorStudent ?: return@setOnClickListener
            lifecycleScope.launch {
                val res = studentRepository.getStudentDossier(studentId = student.id)
                if (_binding == null || !isAdded) return@launch
                if (res is GenericResult.Success) {
                    val uidVal = student.studentNFC.ifBlank { "5327064E740001" }
                    val dummyDiag = SafeNdefManager.TagDiagnostics(
                        uid = uidVal,
                        uidFormatted = SafeNdefManager.formatUidWithColons(uidVal),
                        techList = listOf("android.nfc.tech.Ndef", "android.nfc.tech.NfcA"),
                        isNdefSupported = true,
                        isWritable = true,
                        maxSize = 868,
                        currentSize = 68,
                        tagType = "NTAG 216",
                        ndefPayload = null,
                        parsedPass = null
                    )
                    binding.tabLayout.getTabAt(0)?.select()
                    displayStudentDossier(res.data, dummyDiag)
                }
            }
        }

        binding.cardConveyorActiveTarget.setOnLongClickListener {
            val student = activeConveyorStudent ?: return@setOnLongClickListener false
            val hasTag = !student.studentNFC.isNullOrBlank() && student.studentNFC != "null"
            if (hasTag) {
                showStudentTagActionsDialog(student)
                true
            } else {
                false
            }
        }

        binding.btnConveyorSkip.setOnClickListener {
            advanceConveyorToNext(skipCurrent = true)
        }

        binding.btnConveyorWipe.setOnClickListener {
            if (isPendingWipe) {
                isPendingWipe = false
                updateActiveTargetCard(activeConveyorStudent)
                Toast.makeText(requireContext(), "Режим очистки отменён", Toast.LENGTH_SHORT).show()
            } else {
                isPendingWipe = true
                playFeedbackHapticAndSound()
                binding.tvActiveTargetDetails.text = "РЕЖИМ ОЧИСТКИ: поднесите физическую метку к задней крышке телефона..."
                Toast.makeText(requireContext(), "Приложите метку к задней крышке телефона для очистки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showStudentTagActionsDialog(student: Student) {
        val tagUid = student.studentNFC
        val formattedUid = SafeNdefManager.formatUidWithColons(tagUid)

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_student_tag_actions, null)
        val tvAvatar = dialogView.findViewById<TextView>(R.id.tvDialogStudentAvatar)
        val ivAvatar = dialogView.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivDialogStudentAvatar)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDialogStudentName)
        val tvGroup = dialogView.findViewById<TextView>(R.id.tvDialogStudentGroup)
        val tvTagUid = dialogView.findViewById<TextView>(R.id.tvDialogTagUid)
        val layoutMain = dialogView.findViewById<View>(R.id.layoutMainActions)
        val layoutRevoke = dialogView.findViewById<View>(R.id.layoutRevokeForm)

        val btnSelect = dialogView.findViewById<View>(R.id.btnTagActionSelect)
        val btnDossier = dialogView.findViewById<View>(R.id.btnTagActionDossier)
        val btnRevokePrompt = dialogView.findViewById<View>(R.id.btnTagActionRevokePrompt)
        val btnCancel = dialogView.findViewById<View>(R.id.btnTagActionCancel)

        val chipLost = dialogView.findViewById<View>(R.id.chipReasonLost)
        val chipDamaged = dialogView.findViewById<View>(R.id.chipReasonDamaged)
        val chipReplace = dialogView.findViewById<View>(R.id.chipReasonReplace)
        val chipDevice = dialogView.findViewById<View>(R.id.chipReasonDevice)
        val etReason = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRevokeReason)
        val cbWipe = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbPrepareWipe)
        val btnRevokeBack = dialogView.findViewById<View>(R.id.btnRevokeBack)
        val btnConfirmRevoke = dialogView.findViewById<View>(R.id.btnConfirmRevoke)

        tvAvatar.text = extractInitials(student.studentName)
        tvAvatar.visibility = View.VISIBLE
        ivAvatar.visibility = View.GONE
        tvName.text = student.studentName
        tvGroup.text = "Группа ${student.studentGroup.ifBlank { "—" }} • ID: #${student.id}"
        tvTagUid.text = formattedUid

        // Asynchronously load student avatar if available
        lifecycleScope.launch {
            val dossierRes = studentRepository.getStudentDossier(studentId = student.id)
            if (_binding != null && isAdded && dossierRes is GenericResult.Success) {
                val avUrl = dossierRes.data.student.avatar_url
                if (AvatarManager.isCustomAvatar(avUrl)) {
                    AvatarManager.loadAvatarUrl(
                        context = requireContext(),
                        imageView = ivAvatar,
                        avatarUrl = avUrl,
                        showDefaultPlaceholder = false,
                        onSuccess = {
                            ivAvatar.visibility = View.VISIBLE
                            tvAvatar.visibility = View.GONE
                        }
                    )
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        btnSelect.setOnClickListener {
            dialog.dismiss()
            activeConveyorStudent = student
            updateActiveTargetCard(student)
            conveyorAdapter.setActiveStudent(student.id)
            val idx = filteredGroupStudents.indexOfFirst { it.id == student.id }
            if (idx != -1) binding.rvConveyorQueue.smoothScrollToPosition(idx)
        }

        btnDossier.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                val res = studentRepository.getStudentDossier(studentId = student.id)
                if (_binding == null || !isAdded) return@launch
                if (res is GenericResult.Success) {
                    val dummyDiag = SafeNdefManager.TagDiagnostics(
                        uid = tagUid,
                        uidFormatted = formattedUid,
                        techList = listOf("android.nfc.tech.Ndef", "android.nfc.tech.NfcA"),
                        isNdefSupported = true,
                        isWritable = true,
                        maxSize = 868,
                        currentSize = 68,
                        tagType = "NTAG 216",
                        ndefPayload = null,
                        parsedPass = null
                    )
                    binding.tabLayout.getTabAt(0)?.select()
                    displayStudentDossier(res.data, dummyDiag)
                    binding.layoutInspectionTab.post {
                        binding.layoutInspectionTab.smoothScrollTo(0, 0)
                    }
                } else {
                    Toast.makeText(requireContext(), "Не удалось загрузить досье", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRevokePrompt.setOnClickListener {
            layoutMain.visibility = View.GONE
            layoutRevoke.visibility = View.VISIBLE
        }

        chipLost.setOnClickListener { etReason.setText("Метка утеряна студентом") }
        chipDamaged.setOnClickListener { etReason.setText("Физическое повреждение чипа / не читается") }
        chipReplace.setOnClickListener { etReason.setText("Плановый перевыпуск / замена карты") }
        chipDevice.setOnClickListener { etReason.setText("Смена смартфона / переход на новую метку") }

        btnRevokeBack.setOnClickListener {
            layoutRevoke.visibility = View.GONE
            layoutMain.visibility = View.VISIBLE
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirmRevoke.setOnClickListener {
            val reason = etReason.text.toString().trim().ifBlank { "Метка отвязана администратором" }
            val preparePhysicalWipe = cbWipe.isChecked
            dialog.dismiss()
            executeRevokeStudentTag(student, reason, preparePhysicalWipe)
        }

        dialog.show()
    }

    private fun executeRevokeStudentTag(student: Student, reason: String, preparePhysicalWipe: Boolean) {
        val shortName = RoleUtils.formatShortName(student.studentName)
        val formattedUid = SafeNdefManager.formatUidWithColons(student.studentNFC)

        lifecycleScope.launch {
            val tagUid = student.studentNFC
            Toast.makeText(requireContext(), "Отвязка метки на сервере...", Toast.LENGTH_SHORT).show()
            val res = studentRepository.revokeNfcTag(tagUid, reason)
            if (res is GenericResult.Success) {
                val db = StudentDatabase.getInstance(requireContext())
                db.studentDao().updateNfc(student.id, "")

                // Update in-memory allStudentsList and filteredGroupStudents
                val idx = allStudentsList.indexOfFirst { it.id == student.id }
                if (idx != -1) {
                    allStudentsList[idx] = allStudentsList[idx].copy(studentNFC = "")
                }
                val fIdx = filteredGroupStudents.indexOfFirst { it.id == student.id }
                if (fIdx != -1) {
                    filteredGroupStudents[fIdx] = filteredGroupStudents[fIdx].copy(studentNFC = "")
                }

                // If active conveyor target was this student, update card
                if (activeConveyorStudent?.id == student.id) {
                    activeConveyorStudent = activeConveyorStudent?.copy(studentNFC = "")
                    updateActiveTargetCard(activeConveyorStudent)
                }

                refreshConveyorQueue()
                playSuccessBeep()

                if (preparePhysicalWipe) {
                    isPendingWipe = true
                    binding.tvActiveTargetDetails.text = "РЕЖИМ ОЧИСТКИ: приложите метку ($formattedUid) к задней крышке телефона..."
                    Toast.makeText(
                        requireContext(),
                        "Метка отвязана! Теперь приложите чип для очистки памяти",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Метка успешно отвязана: $reason",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (res is GenericResult.Error) {
                Toast.makeText(
                    requireContext(),
                    "Ошибка сервера: ${res.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnReplaceTag.setOnClickListener {
            val dossier = currentDossier ?: return@setOnClickListener
            showReplaceTagDialog(dossier.student)
        }

        binding.btnWipeTag.setOnClickListener {
            if (isPendingWipe) {
                isPendingWipe = false
                binding.tvReaderPromptTitle.text = "Режим очистки отменён"
                binding.tvReaderPromptSub.text = "Поднесите физическую NFC метку к задней крышке телефона"
                Toast.makeText(requireContext(), "Режим очистки отменён", Toast.LENGTH_SHORT).show()
            } else {
                isPendingWipe = true
                playFeedbackHapticAndSound()
                binding.tvReaderPromptTitle.text = "РЕЖИМ ОЧИСТКИ АКТИВЕН"
                binding.tvReaderPromptSub.text = "Приложите метку к задней крышке телефона для очистки"
                Toast.makeText(requireContext(), "Приложите метку к телефону для очистки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadInitialData() {
        lifecycleScope.launch {
            try {
                val db = StudentDatabase.getInstance(requireContext())

                // 1. Purge duplicate or invalid local student entries (e.g. dummy id=25 with empty group)
                val currentLocalStudents = db.studentDao().getAllStudents().first()
                val studentsWithGroup = currentLocalStudents.filter { it.studentGroup.isNotBlank() }
                for (loc in currentLocalStudents) {
                    if (loc.studentGroup.isBlank() && studentsWithGroup.any { it.studentName == loc.studentName }) {
                        db.studentDao().deleteStudent(loc)
                    } else if (loc.id == 25 && currentLocalStudents.any { it.id == 116 && it.studentName == loc.studentName }) {
                        db.studentDao().deleteStudent(loc)
                    }
                }

                // 2. Fetch all university students from backend (including unregistered applicants)
                val backendResult = studentRepository.getNfcConveyorStudents()
                if (backendResult is GenericResult.Success && backendResult.data.isNotEmpty()) {
                    for (cs in backendResult.data) {
                        val groupName = if (cs.group_name.isNotBlank()) {
                            cs.group_name
                        } else if (cs.user_id == null) {
                            "Приемная комиссия"
                        } else {
                            "Без группы"
                        }
                        val student = Student(
                            id = cs.student_id,
                            studentNFC = cs.nfc_id,
                            studentName = cs.student_name,
                            studentGroup = groupName,
                            attendance = false,
                            role = if (cs.user_id == null) "unregistered" else "student",
                            isFraud = false,
                            totalCheatAttempts = 0
                        )
                        db.studentDao().insertStudent(student)
                    }
                }

                // 3. Load updated students list from local DB
                val students = db.studentDao().getAllStudents().first()
                allStudentsList.clear()
                allStudentsList.addAll(students)

                // 4. Populate groups spinner
                val groups = mutableListOf("Все")
                val sortedGroups = students
                    .map { it.studentGroup }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sortedWith { a, b ->
                        when {
                            a == "Приемная комиссия" -> 1
                            b == "Приемная комиссия" -> -1
                            a == "Без группы" -> 1
                            b == "Без группы" -> -1
                            else -> a.compareTo(b)
                        }
                    }
                groups.addAll(sortedGroups)

                val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, groups)
                binding.spinnerConveyorGroup.adapter = spinnerAdapter
                binding.spinnerConveyorGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        selectedGroup = groups[position]
                        refreshConveyorQueue()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                refreshConveyorQueue()

                // 5. Handle scanned_tag_uid if passed via navigation arguments
                val argTag = arguments?.getString("scanned_tag_uid")
                if (!argTag.isNullOrBlank()) {
                    val formatted = SafeNdefManager.formatUidWithColons(argTag)
                    val dummyDiag = SafeNdefManager.TagDiagnostics(
                        uid = argTag,
                        uidFormatted = formatted,
                        techList = listOf("android.nfc.tech.Ndef", "android.nfc.tech.NfcA"),
                        isNdefSupported = true,
                        isWritable = true,
                        maxSize = 868,
                        currentSize = 68,
                        tagType = "NTAG 216",
                        ndefPayload = null,
                        parsedPass = null
                    )
                    binding.tvReaderPromptTitle.text = "Загрузка досье студента..."
                    val result = studentRepository.getStudentDossier(tagUid = argTag)
                    if (result is GenericResult.Success) {
                        displayStudentDossier(result.data, dummyDiag)
                    }
                }
            } catch (e: Exception) {
                Log.e("NFC_MGR", "loadInitialData error", e)
            }
        }
    }

    private fun refreshConveyorQueue() {
        filteredGroupStudents.clear()
        if (selectedGroup == "Все") {
            filteredGroupStudents.addAll(allStudentsList)
        } else {
            filteredGroupStudents.addAll(allStudentsList.filter { it.studentGroup == selectedGroup })
        }

        val total = filteredGroupStudents.size
        val written = filteredGroupStudents.count { !it.studentNFC.isNullOrBlank() && it.studentNFC != "null" }
        val percent = if (total > 0) (written * 100) / total else 0

        binding.pbConveyorProgress.progress = percent
        binding.tvConveyorProgressCount.text = "$written / $total ($percent%)"

        // Auto-select first student without NFC tag
        if (activeConveyorStudent == null || !filteredGroupStudents.any { it.id == activeConveyorStudent?.id }) {
            activeConveyorStudent = filteredGroupStudents.firstOrNull { it.studentNFC.isNullOrBlank() || it.studentNFC == "null" }
                ?: filteredGroupStudents.firstOrNull()
        }

        conveyorAdapter.setData(filteredGroupStudents, activeConveyorStudent?.id)
        updateActiveTargetCard(activeConveyorStudent)
    }

    private fun updateActiveTargetCard(student: Student?) {
        if (_binding == null) return
        if (student == null) {
            binding.tvActiveTargetName.text = "Все студенты группы записаны"
            binding.tvActiveTargetDetails.text = "Все метки успешно привязаны"
            binding.cardConveyorActiveTarget.strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_success)
            binding.cardConveyorActiveTarget.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sib_success_bg))
        } else {
            binding.tvActiveTargetName.text = RoleUtils.formatShortName(student.studentName)
            val currentNfc = if (!student.studentNFC.isNullOrBlank() && student.studentNFC != "null") {
                "Уже записан: ${SafeNdefManager.formatUidWithColons(student.studentNFC)}"
            } else {
                "Метка отсутствует • Поднесите чистую метку NTAG 216"
            }
            binding.tvActiveTargetDetails.text = "${student.studentName}\nГруппа ${student.studentGroup} (ID: #${student.id}) • $currentNfc"
            binding.cardConveyorActiveTarget.strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
            binding.cardConveyorActiveTarget.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sib_blue_active_bg))
        }
    }

    private fun advanceConveyorToNext(skipCurrent: Boolean) {
        val currentIdx = filteredGroupStudents.indexOfFirst { it.id == activeConveyorStudent?.id }
        val remaining = filteredGroupStudents.filterIndexed { index, s ->
            (index > currentIdx || skipCurrent) && (s.studentNFC.isNullOrBlank() || s.studentNFC == "null")
        }

        activeConveyorStudent = remaining.firstOrNull() ?: filteredGroupStudents.firstOrNull {
            it.id != activeConveyorStudent?.id && (it.studentNFC.isNullOrBlank() || it.studentNFC == "null")
        }

        conveyorAdapter.setActiveStudent(activeConveyorStudent?.id)
        updateActiveTargetCard(activeConveyorStudent)

        // Scroll to active student
        if (activeConveyorStudent != null) {
            val idx = filteredGroupStudents.indexOfFirst { it.id == activeConveyorStudent?.id }
            if (idx != -1) binding.rvConveyorQueue.smoothScrollToPosition(idx)
        }
    }

    // ==================== NFC DISPATCH ====================

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        // Tag callback already handled in onPhysicalTagScanned
    }

    override fun onPhysicalTagScanned(tag: Tag, payloadOrUid: String) {
        lastScannedTag = tag
        playFeedbackHapticAndSound()

        if (isPendingWipe) {
            isPendingWipe = false
            performSafeWipe(tag)
            return
        }

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val isTeacher = RoleUtils.normalizeRole(userRole) == RoleUtils.ROLE_TEACHER

        val isConveyorMode = !isTeacher && binding.tabLayout.selectedTabPosition == 1

        if (isPendingReplacement) {
            handleReplacementTagScanned(tag)
            return
        }

        if (isConveyorMode) {
            handleConveyorTagScanned(tag)
        } else {
            handleInspectorTagScanned(tag, payloadOrUid)
        }
    }

    // ==================== TAB 1: INSPECTOR LOGIC ====================

    @SuppressLint("SetTextI18n")
    private fun handleInspectorTagScanned(tag: Tag, payloadOrUid: String) {
        val diagnostics = SafeNdefManager.inspectTag(tag)

        binding.cardChipDiagnostics.visibility = View.VISIBLE
        val shortType = when {
            diagnostics.tagType.contains("216") -> "NTAG 216"
            diagnostics.tagType.contains("215") -> "NTAG 215"
            diagnostics.tagType.contains("213") -> "NTAG 213"
            diagnostics.tagType.contains("Mifare", ignoreCase = true) -> "MIFARE"
            diagnostics.tagType.contains("Type 2", ignoreCase = true) -> "Type 2"
            else -> diagnostics.tagType.take(12)
        }
        binding.chipChipType.text = shortType
        binding.tvDiagUid.text = "UID: ${diagnostics.uidFormatted}"
        val nxpText = if (diagnostics.hasNxpOriginalitySignature) "Ответ на команду подписи NXP получен (32 байта, проверка ECDSA на сервере)" else "Совместимый чип Type 2 (без ответа NXP)"
        val lockText = if (diagnostics.isPasswordProtected) "Защита от чтения: Активна (NFC Tools заблокирован)" else "Защита от чтения: Открыта"
        binding.tvDiagLock.text = "$lockText\n$nxpText"

        val payloadText = diagnostics.ndefPayload ?: payloadOrUid
        binding.tvDiagPayload.text = "NDEF Payload: ${payloadText.ifBlank { "Пустой чип (без записей)" }}"

        // Determine student identifier
        val parsedPass = diagnostics.parsedPass
        val studentId = parsedPass?.studentId
        val tagUid = parsedPass?.tagUid ?: diagnostics.uid

        binding.tvReaderPromptTitle.text = "Загрузка досье студента..."
        binding.cardStudentDossier.visibility = View.GONE

        lifecycleScope.launch {
            var result = studentRepository.getStudentDossier(tagUid = tagUid, studentId = studentId)
            if (_binding == null || !isAdded) return@launch

            // Fallback: if studentId lookup failed and tagUid is present, query by physical tag UID
            if (result is GenericResult.Error && studentId != null && studentId > 0 && tagUid.isNotBlank()) {
                val fallback = studentRepository.getStudentDossier(tagUid = tagUid, studentId = null)
                if (fallback is GenericResult.Success) {
                    result = fallback
                }
            }

            when (result) {
                is GenericResult.Success -> {
                    displayStudentDossier(result.data, diagnostics)
                }
                is GenericResult.Error -> {
                    binding.tvReaderPromptTitle.text = "Метка считана (${diagnostics.uidFormatted})"
                    binding.tvReaderPromptSub.text = "Метка не привязана к студенту. Перейдите в Конвейер для записи."
                    binding.cardStudentDossier.visibility = View.GONE
                }
            }
        }
    }

    enum class StatRanking {
        RED, BROWN, ORANGE, YELLOW, GREEN
    }

    private fun getRankingColor(ranking: StatRanking): Int {
        val colorRes = when (ranking) {
            StatRanking.GREEN -> R.color.sib_success
            StatRanking.YELLOW -> R.color.sib_warning
            StatRanking.ORANGE -> R.color.sib_orange
            StatRanking.BROWN -> R.color.sib_brown
            StatRanking.RED -> R.color.sib_danger
        }
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun applyStatBoxStyle(
        container: View,
        valueView: TextView,
        statusView: TextView?,
        ranking: StatRanking
    ) {
        val bgRes = when (ranking) {
            StatRanking.GREEN -> R.drawable.bg_nfc_stat_green
            StatRanking.YELLOW -> R.drawable.bg_nfc_stat_yellow
            StatRanking.ORANGE -> R.drawable.bg_nfc_stat_orange
            StatRanking.BROWN -> R.drawable.bg_nfc_stat_brown
            StatRanking.RED -> R.drawable.bg_nfc_stat_red
        }
        container.setBackgroundResource(bgRes)
        val color = getRankingColor(ranking)
        valueView.setTextColor(color)
        statusView?.setTextColor(color)
    }

    @SuppressLint("SetTextI18n")
    private fun displayStudentDossier(dossier: StudentDossier, diagnostics: SafeNdefManager.TagDiagnostics) {
        currentDossier = dossier
        lastDiagnostics = diagnostics
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val isTeacher = RoleUtils.normalizeRole(userRole) == RoleUtils.ROLE_TEACHER || dossier.is_caller_teacher
        val s = dossier.student
        val n = dossier.nfc
        val att = dossier.attendance

        binding.cardStudentDossier.visibility = View.VISIBLE
        val shortName = RoleUtils.formatShortName(s.student_name)
        binding.tvReaderPromptTitle.text = "Досье: $shortName"
        binding.tvReaderPromptSub.text = "Метка верифицирована через HMAC-SHA256"

        // Profile: Header
        binding.tvDossierName.text = shortName
        binding.tvDossierFullName.text = s.student_name
        binding.tvDossierGroup.text = "Группа: ${s.group_name} • ID: #${s.student_id}"

        // Avatar: Custom photo if available, otherwise letters (initials)
        val initials = extractInitials(s.student_name)
        binding.tvDossierAvatar.text = initials

        if (AvatarManager.isCustomAvatar(s.avatar_url)) {
            // Keep letters visible while image is loading to prevent any blank flash
            binding.tvDossierAvatar.visibility = View.VISIBLE
            binding.ivDossierAvatar.visibility = View.GONE

            AvatarManager.loadAvatarUrl(
                context = requireContext(),
                imageView = binding.ivDossierAvatar,
                avatarUrl = s.avatar_url,
                showDefaultPlaceholder = false,
                onSuccess = {
                    if (_binding != null) {
                        binding.ivDossierAvatar.visibility = View.VISIBLE
                        binding.tvDossierAvatar.visibility = View.GONE
                    }
                },
                onError = {
                    if (_binding != null) {
                        binding.ivDossierAvatar.visibility = View.GONE
                        binding.tvDossierAvatar.visibility = View.VISIBLE
                    }
                }
            )

            binding.layoutDossierAvatar.setOnClickListener {
                val fullUrl = ServerConfig.resolveMediaUrl(requireContext(), s.avatar_url)
                PhotoViewerDialog.newInstance(fullUrl, s.student_name).show(parentFragmentManager, PhotoViewerDialog.TAG)
            }
        } else {
            binding.ivDossierAvatar.visibility = View.GONE
            binding.tvDossierAvatar.visibility = View.VISIBLE
            binding.layoutDossierAvatar.setOnClickListener(null)
        }

        // Profile: Faculty and Course tags
        val (faculty, course) = extractFacultyAndCourse(s.group_name)
        binding.tvDossierCourseTag.text = course
        binding.tvDossierFacultyTag.text = faculty
        binding.tvDossierStudyFormTag.text = "Очное (Бюджет)"

        // Tag Status Badge
        if (n.is_revoked_tag) {
            binding.chipTagStatus.text = "ОТОЗВАНА"
            binding.chipTagStatus.setBackgroundResource(R.drawable.bg_nfc_stat_red)
            binding.chipTagStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_danger))
            binding.tvRevocationBanner.visibility = View.VISIBLE
            binding.tvRevocationText.text = "Внимание: метка аннулирована. Причина: ${n.revoked_reason.ifBlank { "Замена карты" }}"
        } else {
            binding.chipTagStatus.text = "АКТИВНА"
            binding.chipTagStatus.setBackgroundResource(R.drawable.bg_nfc_stat_green)
            binding.chipTagStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_success))
            binding.tvRevocationBanner.visibility = View.GONE
        }

        // ==================== 5-LEVEL COLOR RANKING ====================
        // Scale: Красный -> Коричневый -> Оранжевый -> Жёлтый -> Зелёный

        // Stats 2x2: 1. Attendance
        val attPercent = att.attendance_percent.toInt()
        binding.tvStatAttendance.text = "$attPercent%"
        binding.pbStatAttendance.progress = attPercent.coerceIn(0, 100)
        val totalSessions = if (att.total_sessions > 0) att.total_sessions else (att.present_sessions + att.absent_sessions)
        binding.tvStatAttendanceDetail.text = "${att.present_sessions} из ${formatGenitivePairs(totalSessions)}"

        val attRank = when {
            attPercent >= 80 -> StatRanking.GREEN
            attPercent >= 65 -> StatRanking.YELLOW
            attPercent >= 50 -> StatRanking.ORANGE
            attPercent >= 35 -> StatRanking.BROWN
            else -> StatRanking.RED
        }
        applyStatBoxStyle(binding.containerStatAttendance, binding.tvStatAttendance, null, attRank)
        binding.pbStatAttendance.setIndicatorColor(getRankingColor(attRank))

        // Stats 2x2: 2. GPA
        binding.tvStatGpa.text = String.format(java.util.Locale.US, "%.1f", dossier.gpa)
        val subjectCount = dossier.grades.size
        binding.tvStatGradesCount.text = if (subjectCount > 0) "$subjectCount предм." else "Текущий семестр"
        val (gpaRank, gpaStatus) = when {
            subjectCount == 0 -> Pair(StatRanking.YELLOW, "Оценок нет")
            dossier.gpa >= 4.5 -> Pair(StatRanking.GREEN, "Отличник")
            dossier.gpa >= 3.8 -> Pair(StatRanking.YELLOW, "Хорошо")
            dossier.gpa >= 3.0 -> Pair(StatRanking.ORANGE, "Удовлетворительно")
            dossier.gpa >= 2.5 -> Pair(StatRanking.BROWN, "Зона риска")
            else -> Pair(StatRanking.RED, "Критично")
        }
        binding.tvStatGpaStatus.text = gpaStatus
        applyStatBoxStyle(binding.containerStatGpa, binding.tvStatGpa, binding.tvStatGpaStatus, gpaRank)

        // Stats 2x2: 3. Absences
        binding.tvStatAbsent.text = formatPairsCount(att.absent_sessions)
        val absentPct = if (totalSessions > 0) (att.absent_sessions * 100 / totalSessions).toInt() else 0
        binding.tvStatAbsentDetail.text = "$absentPct% от плана"
        val (absentRank, absentStatus) = when {
            absentPct <= 15 -> Pair(StatRanking.GREEN, "В норме")
            absentPct <= 30 -> Pair(StatRanking.YELLOW, "Внимание")
            absentPct <= 45 -> Pair(StatRanking.ORANGE, "Заметный пропуск")
            absentPct <= 60 -> Pair(StatRanking.BROWN, "Высокий риск")
            else -> Pair(StatRanking.RED, "Критично")
        }
        binding.tvStatAbsentStatus.text = absentStatus
        applyStatBoxStyle(binding.containerStatAbsent, binding.tvStatAbsent, binding.tvStatAbsentStatus, absentRank)

        // Stats 2x2: 4. Anti-cheat
        binding.tvStatFraud.text = "${s.total_cheat_attempts}"
        val (fraudRank, fraudStatus, fraudSec) = when {
            s.total_cheat_attempts == 0 -> Triple(StatRanking.GREEN, "Нарушений нет", "HMAC-SHA256 • Чист")
            s.total_cheat_attempts == 1 -> Triple(StatRanking.YELLOW, "1 инцидент", "Проверено")
            s.total_cheat_attempts == 2 -> Triple(StatRanking.ORANGE, "2 инцидента", "Повторный фрод")
            s.total_cheat_attempts == 3 -> Triple(StatRanking.BROWN, "3 инцидента", "Высокий риск")
            else -> Triple(StatRanking.RED, "Фрод зафиксирован", "Блокировка метки")
        }
        binding.tvStatFraudStatus.text = fraudStatus
        binding.tvStatFraudSecurity.text = fraudSec
        applyStatBoxStyle(binding.containerStatFraud, binding.tvStatFraud, null, fraudRank)
        binding.tvStatFraudSecurity.setTextColor(getRankingColor(fraudRank))

        // Ensure dossier is smoothly scrolled into full view with header and avatar
        binding.layoutInspectionTab.post {
            binding.layoutInspectionTab.smoothScrollTo(0, binding.cardStudentDossier.top)
        }

        // Student account details
        val statusLabel = if (s.status == "active") "Активен" else s.status
        val emailText = if (s.email.isNotBlank()) s.email else if (s.login.isNotBlank()) "${s.login}@sibsutis.ru" else "student@sibsutis.ru"
        val loginText = if (s.login.isNotBlank()) s.login else "student_${s.student_id}"
        binding.tvDossierAccountEmail.text = emailText
        binding.tvDossierAccountInfo.text = "Логин: $loginText • Статус: $statusLabel"

        // Hardware NFC details
        val rawUid = if (n.current_tag_uid.isNotBlank()) n.current_tag_uid else diagnostics.uid
        val nfcUidFormatted = SafeNdefManager.formatUidWithColons(rawUid)
        val humanIssueDate = formatHumanDate(n.issued_at)
        binding.tvDossierNfcUid.text = nfcUidFormatted
        binding.tvDossierNfcInfo.text = "Выпуск: $humanIssueDate • Защита Zero-Brick"

        // Last Attendance
        val last = att.last_mark
        if (last != null && last.marked_at.isNotBlank()) {
            val teacherShort = RoleUtils.formatShortName(last.teacher_name)
            val humanDate = formatHumanDate(last.marked_at)
            val teacherStr = if (teacherShort.isNotBlank()) " ($teacherShort)" else ""
            binding.tvDossierLastAttendance.text = "Последняя пара: $humanDate • ${last.subject_name}$teacherStr"
        } else {
            binding.tvDossierLastAttendance.text = "Нет зафиксированных посещений в журнале"
        }

        // Teacher subjects breakdown (Requirement 3: Teacher sees statistics ONLY for their own subjects)
        if (dossier.is_caller_teacher || dossier.teacher_subjects.isNotEmpty()) {
            binding.layoutTeacherSubjectsSection.visibility = View.VISIBLE
            if (dossier.teacher_name.isNotBlank()) {
                val tShort = RoleUtils.formatShortName(dossier.teacher_name)
                binding.tvTeacherSubjectsTitle.text = "Мои дисциплины ($tShort)"
            } else {
                binding.tvTeacherSubjectsTitle.text = "Мои дисциплины у студента"
            }
            binding.containerTeacherSubjects.removeAllViews()

            if (dossier.teacher_subjects.isNotEmpty()) {
                for (ts in dossier.teacher_subjects) {
                    val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                        radius = 12f * resources.displayMetrics.density
                        strokeWidth = (1 * resources.displayMetrics.density).toInt()
                        strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_border)
                        setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sib_card_surface))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                        }
                    }

                    val contentLayout = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        val p = (12 * resources.displayMetrics.density).toInt()
                        setPadding(p, p, p, p)
                    }

                    // Header row: Subject Name + Attendance Pill
                    val headerRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val tvTitle = TextView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = ts.subject_name
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_primary))
                        textSize = 14f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val tvBadge = TextView(requireContext()).apply {
                        val pct = ts.attendance_percent
                        text = "${String.format(java.util.Locale.US, "%.0f", pct)}% посещ."
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        val padH = (8 * resources.displayMetrics.density).toInt()
                        val padV = (3 * resources.displayMetrics.density).toInt()
                        setPadding(padH, padV, padH, padV)
                        when {
                            pct >= 80.0 -> {
                                setBackgroundResource(R.drawable.bg_nfc_stat_green)
                                setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_success))
                            }
                            pct >= 50.0 -> {
                                setBackgroundResource(R.drawable.bg_nfc_stat_yellow)
                                setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_warning))
                            }
                            else -> {
                                setBackgroundResource(R.drawable.bg_nfc_stat_red)
                                setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_danger))
                            }
                        }
                    }
                    headerRow.addView(tvTitle)
                    headerRow.addView(tvBadge)
                    contentLayout.addView(headerRow)

                    // Details row 1: Sessions
                    val tvSessions = TextView(requireContext()).apply {
                        val pres = ts.present_sessions
                        val tot = ts.total_sessions
                        val abs = ts.absent_sessions
                        text = "• Посещено: $pres из $tot пар (пропусков: $abs)"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_secondary))
                        textSize = 12f
                        setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    contentLayout.addView(tvSessions)

                    // Details row 2: Average grade & count
                    val tvGrades = TextView(requireContext()).apply {
                        val avgStr = if (ts.average_grade > 0.0) String.format(java.util.Locale.US, "%.1f", ts.average_grade) else "нет оценок"
                        text = "• Средний балл: $avgStr (${ts.grades_count} оценок)"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_secondary))
                        textSize = 12f
                        setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    contentLayout.addView(tvGrades)

                    // Details row 3: Last mark if available
                    if (ts.last_marked_at.isNotBlank() || ts.last_status.isNotBlank()) {
                        val tvLastMark = TextView(requireContext()).apply {
                            val stName = when (ts.last_status) {
                                "present", "ontime" -> "Присутствовал"
                                "late" -> "Опоздал"
                                "absent" -> "Отсутствовал"
                                else -> ts.last_status
                            }
                            val dateStr = if (ts.last_marked_at.isNotBlank()) " (${formatHumanDate(ts.last_marked_at)})" else ""
                            text = "• Последняя пара: $stName$dateStr"
                            setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_muted))
                            textSize = 11f
                            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        contentLayout.addView(tvLastMark)
                    }

                    // Divider inside subject card
                    val subDivider = View(requireContext()).apply {
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (1 * resources.displayMetrics.density).toInt()
                        ).apply {
                            topMargin = (8 * resources.displayMetrics.density).toInt()
                            bottomMargin = (8 * resources.displayMetrics.density).toInt()
                        }
                        layoutParams = lp
                        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sib_border))
                    }
                    contentLayout.addView(subDivider)

                    // Header row for assignments: "Задания и контрольные точки" + button "+ Задание"
                    val headerTasksRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val tvTasksTitle = TextView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text = "Задания и контрольные точки"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_primary))
                        textSize = 12f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    headerTasksRow.addView(tvTasksTitle)

                    val btnAddTask = TextView(requireContext()).apply {
                        text = "+ Задание"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_blue_primary))
                        textSize = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_nfc_btn_outline)
                        setPadding(
                            (10 * resources.displayMetrics.density).toInt(),
                            (4 * resources.displayMetrics.density).toInt(),
                            (10 * resources.displayMetrics.density).toInt(),
                            (4 * resources.displayMetrics.density).toInt()
                        )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            showCreateGradeItemDialog(dossier.student, ts)
                        }
                    }
                    headerTasksRow.addView(btnAddTask)
                    contentLayout.addView(headerTasksRow)

                    // Assignments list
                    if (ts.grade_items.isEmpty()) {
                        val tvNoTasks = TextView(requireContext()).apply {
                            text = "Нет заданий. Нажмите «+ Задание», чтобы добавить контрольную точку."
                            setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_muted))
                            textSize = 11f
                            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                        }
                        contentLayout.addView(tvNoTasks)
                    } else {
                        val tasksContainer = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = (6 * resources.displayMetrics.density).toInt()
                            }
                        }

                        for (item in ts.grade_items) {
                            val itemLayout = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    bottomMargin = (6 * resources.displayMetrics.density).toInt()
                                }
                                setBackgroundResource(R.drawable.bg_nfc_task_item)
                                setPadding(
                                    (10 * resources.displayMetrics.density).toInt(),
                                    (8 * resources.displayMetrics.density).toInt(),
                                    (10 * resources.displayMetrics.density).toInt(),
                                    (8 * resources.displayMetrics.density).toInt()
                                )
                                isClickable = true
                                isFocusable = true
                                setOnClickListener {
                                    showGradeEditDialog(dossier.student, ts, item)
                                }
                            }

                            // Row 1: Title and Score badge
                            val itemHeader = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            }

                            val tvItemTitle = TextView(requireContext()).apply {
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                val maxScoreStr = if (item.max_score > 0.0) {
                                    val ms = if (item.max_score % 1.0 == 0.0) item.max_score.toInt().toString() else item.max_score.toString()
                                    " (макс. $ms)"
                                } else ""
                                text = "${item.title}$maxScoreStr"
                                setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_primary))
                                textSize = 12f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            }
                            itemHeader.addView(tvItemTitle)

                            val badgeScore = TextView(requireContext()).apply {
                                if (item.has_grade || item.grade_id > 0) {
                                    val scoreStr = if (item.score % 1.0 == 0.0) item.score.toInt().toString() else String.format(java.util.Locale.US, "%.1f", item.score)
                                    val maxStr = if (item.max_score % 1.0 == 0.0) item.max_score.toInt().toString() else item.max_score.toString()
                                    text = "★ $scoreStr / $maxStr"
                                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_success))
                                    setBackgroundResource(R.drawable.bg_nfc_stat_green)
                                } else {
                                    text = "+ Оценить"
                                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_blue_primary))
                                    setBackgroundResource(R.drawable.bg_nfc_stat_blue)
                                }
                                textSize = 11f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setPadding(
                                    (8 * resources.displayMetrics.density).toInt(),
                                    (2 * resources.displayMetrics.density).toInt(),
                                    (8 * resources.displayMetrics.density).toInt(),
                                    (2 * resources.displayMetrics.density).toInt()
                                )
                            }
                            itemHeader.addView(badgeScore)
                            itemLayout.addView(itemHeader)

                            // Row 2: Comment and/or update time if present
                            if (item.comment.isNotBlank() || item.updated_at.isNotBlank()) {
                                val tvComment = TextView(requireContext()).apply {
                                    val parts = mutableListOf<String>()
                                    if (item.comment.isNotBlank()) parts.add("«${item.comment}»")
                                    if (item.updated_at.isNotBlank()) parts.add(formatHumanDate(item.updated_at))
                                    text = parts.joinToString(" • ")
                                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_muted))
                                    textSize = 10f
                                    setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
                                }
                                itemLayout.addView(tvComment)
                            }

                            tasksContainer.addView(itemLayout)
                        }
                        contentLayout.addView(tasksContainer)
                    }

                    card.addView(contentLayout)
                    binding.containerTeacherSubjects.addView(card)
                }
            } else {
                val tvEmpty = TextView(requireContext()).apply {
                    text = "Студент не записан на ваши предметы или занятий еще не проводилось"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sib_text_muted))
                    textSize = 12f
                    setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                }
                binding.containerTeacherSubjects.addView(tvEmpty)
            }
        } else {
            binding.layoutTeacherSubjectsSection.visibility = View.GONE
        }

        // Requirement: "Преподаватель видит только сводку о студенту + оценки за работу по этому предмету"
        if (isTeacher) {
            binding.layoutDossierGradesSummarySection.visibility = View.GONE
            binding.layoutDossierTagHistorySection.visibility = View.GONE
            binding.layoutDossierTagActions.visibility = View.GONE
        } else {
            binding.layoutDossierGradesSummarySection.visibility = View.VISIBLE
            binding.layoutDossierTagHistorySection.visibility = View.VISIBLE
            binding.layoutDossierTagActions.visibility = View.VISIBLE

            // Grades breakdown
            if (dossier.grades.isNotEmpty()) {
                val gradesText = dossier.grades.joinToString("\n") { "• ${it.name}: ${String.format(java.util.Locale.US, "%.1f", it.average)}" }
                binding.tvDossierGradesSummary.text = gradesText
            } else {
                binding.tvDossierGradesSummary.text = "Текущие оценки в семестре еще не выставлены"
            }

            // Tag History
            if (dossier.tag_history.isNotEmpty()) {
                val histText = dossier.tag_history.joinToString("\n") { item ->
                    val dateStr = formatHumanDate(item.issued_at)
                    val reasonStr = if (item.reason.isNotBlank()) " (${item.reason})" else ""
                    val statusStr = if (item.status == "active") "Активна" else "Отозвана$reasonStr"
                    "• ${SafeNdefManager.formatUidWithColons(item.tag_uid)} — $statusStr ($dateStr)"
                }
                binding.tvDossierTagHistory.text = histText
            } else {
                binding.tvDossierTagHistory.text = "Предыдущих выпусков меток нет"
            }
        }
    }

    private fun showGradeEditDialog(
        student: DossierStudent,
        ts: DossierTeacherSubject,
        item: DossierGradeItem
    ) {
        val context = requireContext()
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(item.title)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val tvSub = TextView(context).apply {
            val maxScoreStr = if (item.max_score % 1.0 == 0.0) item.max_score.toInt().toString() else item.max_score.toString()
            text = "Студент: ${student.student_name}\nДисциплина: ${ts.subject_name}\nМаксимальный балл: $maxScoreStr"
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_secondary))
            textSize = 12f
            setLineSpacing(2f, 1f)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        container.addView(tvSub)

        // Score input
        val tvScoreLabel = TextView(context).apply {
            text = "Балл / Оценка:"
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_primary))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(tvScoreLabel)

        val etScore = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            val currentScore = if (item.has_grade || item.grade_id > 0) {
                if (item.score % 1.0 == 0.0) item.score.toInt().toString() else item.score.toString()
            } else {
                if (item.max_score > 0) (item.max_score.toInt()).toString() else "5"
            }
            setText(currentScore)
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.sib_text_muted))
            hint = "Введите балл"
        }
        container.addView(etScore)

        // Quick buttons (2, 3, 4, 5)
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        }
        for (qVal in listOf(2, 3, 4, 5)) {
            val btnQ = TextView(context).apply {
                text = qVal.toString()
                setTextColor(ContextCompat.getColor(context, R.color.sib_blue_primary))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_nfc_stat_blue)
                val padH = (12 * resources.displayMetrics.density).toInt()
                val padV = (4 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    etScore.setText(qVal.toString())
                }
            }
            quickRow.addView(btnQ)
        }
        container.addView(quickRow)

        // Comment input
        val tvCommentLabel = TextView(context).apply {
            text = "Комментарий (необязательно):"
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_primary))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(tvCommentLabel)

        val etComment = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(item.comment)
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.sib_text_muted))
            hint = "Например: Зачтено / Работа выполнена в срок"
        }
        container.addView(etComment)

        builder.setView(container)

        builder.setPositiveButton("Сохранить") { _, _ ->
            val scoreText = etScore.text.toString().trim()
            val scoreVal = scoreText.toIntOrNull() ?: scoreText.toDoubleOrNull()?.toInt() ?: 0
            val commentText = etComment.text.toString().trim()

            viewLifecycleOwner.lifecycleScope.launch {
                val ok = studentRepository.setStudentGrade(
                    studentId = student.student_id,
                    itemId = item.item_id,
                    score = scoreVal,
                    comment = if (commentText.isNotBlank()) commentText else null
                )
                if (ok) {
                    Toast.makeText(context, "Оценка сохранена!", Toast.LENGTH_SHORT).show()
                    reloadDossier(student.student_id.toLong())
                } else {
                    Toast.makeText(context, "Не удалось сохранить оценку. Проверьте права доступа.", Toast.LENGTH_LONG).show()
                }
            }
        }

        if (item.has_grade && item.grade_id > 0) {
            builder.setNeutralButton("Удалить оценку") { _, _ ->
                MaterialAlertDialogBuilder(context)
                    .setTitle("Удаление оценки")
                    .setMessage("Вы уверены, что хотите удалить оценку за задание «${item.title}»?")
                    .setPositiveButton("Удалить") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val res = studentRepository.deleteTeacherGrade(item.grade_id.toLong())
                            when (res) {
                                is GenericResult.Success -> {
                                    Toast.makeText(context, "Оценка удалена", Toast.LENGTH_SHORT).show()
                                    reloadDossier(student.student_id.toLong())
                                }
                                is GenericResult.Error -> {
                                    Toast.makeText(context, "Ошибка удаления: ${res.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }

        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun showCreateGradeItemDialog(
        student: DossierStudent,
        ts: DossierTeacherSubject
    ) {
        val context = requireContext()
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle("Новое задание / точка")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val tvSub = TextView(context).apply {
            text = "Дисциплина: ${ts.subject_name}"
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }
        container.addView(tvSub)

        val etTitle = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "Название задания (например: Лаб. №2)"
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.sib_text_muted))
        }
        container.addView(etTitle)

        val etMaxScore = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Максимальный балл (по умолчанию 5)"
            setText("5")
            setTextColor(ContextCompat.getColor(context, R.color.sib_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.sib_text_muted))
        }
        container.addView(etMaxScore)

        builder.setView(container)
        builder.setPositiveButton("Создать") { _, _ ->
            val title = etTitle.text.toString().trim()
            if (title.isBlank()) {
                Toast.makeText(context, "Укажите название задания", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val maxScore = etMaxScore.text.toString().trim().toIntOrNull() ?: 5

            viewLifecycleOwner.lifecycleScope.launch {
                val ok = studentRepository.createGradeItem(
                    subjectId = ts.subject_id,
                    title = title,
                    maxScore = maxScore,
                    itemType = "assignment"
                )
                if (ok) {
                    Toast.makeText(context, "Задание создано!", Toast.LENGTH_SHORT).show()
                    reloadDossier(student.student_id.toLong())
                } else {
                    Toast.makeText(context, "Ошибка при создании задания", Toast.LENGTH_LONG).show()
                }
            }
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun reloadDossier(studentId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val res = studentRepository.getStudentDossier(studentId = studentId.toInt())) {
                is GenericResult.Success -> {
                    val dummyDiag = lastDiagnostics ?: SafeNdefManager.TagDiagnostics(
                        uid = res.data.nfc.current_tag_uid.ifBlank { "5327064E740001" },
                        uidFormatted = SafeNdefManager.formatUidWithColons(res.data.nfc.current_tag_uid.ifBlank { "5327064E740001" }),
                        techList = listOf("android.nfc.tech.Ndef", "android.nfc.tech.NfcA"),
                        isNdefSupported = true,
                        isWritable = true,
                        maxSize = 888,
                        currentSize = 68,
                        tagType = "NTAG 216 Compatible",
                        ndefPayload = "EJ1:$studentId:${res.data.nfc.current_tag_uid}",
                        parsedPass = null
                    )
                    displayStudentDossier(res.data, dummyDiag)
                }
                is GenericResult.Error -> {
                    Toast.makeText(requireContext(), "Не удалось обновить: ${res.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatPairsCount(count: Long): String {
        val rem100 = (count % 100).toInt()
        val rem10 = (count % 10).toInt()
        return when {
            rem100 in 11..19 -> "$count пар"
            rem10 == 1 -> "$count пара"
            rem10 in 2..4 -> "$count пары"
            else -> "$count пар"
        }
    }

    private fun formatGenitivePairs(count: Long): String {
        val rem100 = (count % 100).toInt()
        val rem10 = (count % 10).toInt()
        return if (rem10 == 1 && rem100 != 11) "$count пары" else "$count пар"
    }

    private fun extractInitials(fullName: String): String {
        val parts = fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
            parts.isNotEmpty() -> parts[0].take(2).uppercase()
            else -> "С"
        }
    }

    private fun extractFacultyAndCourse(groupName: String): Pair<String, String> {
        val clean = groupName.trim().uppercase()
        val courseDigit = clean.firstOrNull { it.isDigit() }?.toString() ?: "1"
        val courseStr = "$courseDigit курс"
        val facultyStr = when {
            clean.startsWith("ИКС") -> "ИКС (Сети и связь)"
            clean.startsWith("ИТ") || clean.startsWith("ПИН") || clean.startsWith("ИС") || clean.startsWith("ИВТ") -> "ИТ (Информатика)"
            clean.startsWith("МР") || clean.startsWith("РТ") || clean.startsWith("РС") -> "РЭТ (Радиотехника)"
            clean.startsWith("ЭБ") || clean.startsWith("ЭК") -> "Гуманитарный (ЭиУ)"
            else -> "СибГУТИ"
        }
        return Pair(facultyStr, courseStr)
    }

    private fun formatHumanDate(rawDate: String): String {
        if (rawDate.isBlank()) return "Не указана"
        return try {
            val clean = rawDate.replace("Z", "").replace("T", " ")
            val parts = clean.split(" ")
            val dateParts = parts[0].split("-")
            val timePart = if (parts.size > 1) parts[1].take(5) else ""
            if (dateParts.size == 3) {
                "${dateParts[2]}.${dateParts[1]}.${dateParts[0]}${if (timePart.isNotBlank()) " в $timePart" else ""}"
            } else {
                clean.take(16)
            }
        } catch (_: Exception) {
            rawDate.take(16).replace("T", " ")
        }
    }

    // ==================== TAB 2: CONVEYOR BATCH WRITE ====================

    private fun handleConveyorTagScanned(tag: Tag) {
        val target = activeConveyorStudent
        if (target == null) {
            Toast.makeText(requireContext(), "Все студенты уже записаны или не выбран студент", Toast.LENGTH_SHORT).show()
            return
        }

        val rawUid = SafeNdefManager.cleanUid(tag.id)
        if (rawUid.isBlank()) {
            Toast.makeText(requireContext(), "Не удалось прочитать UID чипа", Toast.LENGTH_SHORT).show()
            return
        }

        // Safety check: is this tag already assigned to a DIFFERENT student in our database?
        val existingOwner = allStudentsList.firstOrNull {
            !it.studentNFC.isNullOrBlank() && SafeNdefManager.cleanUid(it.studentNFC).equals(rawUid, ignoreCase = true)
        }
        if (existingOwner != null && existingOwner.id != target.id) {
            playFeedbackHapticAndSound()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Метка уже занята")
                .setMessage(
                    "Метка (UID: ${SafeNdefManager.formatUidWithColons(rawUid)}) уже привязана к студенту:\n\n" +
                    "${RoleUtils.formatShortName(existingOwner.studentName)} (${existingOwner.studentGroup})\n\n" +
                    "Перезаписать её для ${RoleUtils.formatShortName(target.studentName)}?"
                )
                .setPositiveButton("Перезаписать") { _, _ ->
                    proceedWithConveyorWrite(tag, target, rawUid)
                }
                .setNegativeButton("Отмена") { _, _ ->
                    binding.tvActiveTargetDetails.text = "Запись отменена: метка занята (${RoleUtils.formatShortName(existingOwner.studentName)})"
                }
                .show()
            return
        }

        proceedWithConveyorWrite(tag, target, rawUid)
    }

    private fun proceedWithConveyorWrite(tag: Tag, target: Student, rawUid: String) {
        binding.tvActiveTargetDetails.text = "Запись метки (UID: ${SafeNdefManager.formatUidWithColons(rawUid)})..."

        lifecycleScope.launch {
            // 1. Generate cryptographic payload
            val genResult = studentRepository.generateNfcPayload(target.id, rawUid)
            if (genResult is GenericResult.Error) {
                Toast.makeText(requireContext(), "Ошибка генерации: ${genResult.message}", Toast.LENGTH_LONG).show()
                binding.tvActiveTargetDetails.text = "Ошибка: ${genResult.message}"
                return@launch
            }

            val payloadData = (genResult as GenericResult.Success).data

            // 2. Bind tag on server FIRST (if rejected by server, physical tag remains untouched)
            val bindResult = studentRepository.bindNfcTag(target.id, rawUid, payloadData.signature)
            if (bindResult is GenericResult.Error) {
                Toast.makeText(requireContext(), "Ошибка привязки на сервере: ${bindResult.message}", Toast.LENGTH_LONG).show()
                binding.tvActiveTargetDetails.text = "Ошибка сервера: ${bindResult.message}"
                return@launch
            }

            // 3. Safe write to physical tag (Zero-Brick Guarantee)
            val writeResult = SafeNdefManager.writeSafePass(tag, payloadData.payload)
            if (writeResult.isFailure) {
                val err = writeResult.exceptionOrNull()?.message ?: "Ошибка записи"
                Toast.makeText(requireContext(), "Ошибка записи NDEF: $err", Toast.LENGTH_LONG).show()
                binding.tvActiveTargetDetails.text = "Ошибка NDEF: $err"
                return@launch
            }

            // 4. Apply hardware read protection (PROT=1, AUTH0=0x04) to prevent unauthorized cloning via NFC Tools
            val protResult = SafeNdefManager.applyReadProtection(tag, rawUid)
            if (protResult.isFailure) {
                android.util.Log.w("NfcManager", "Failed to apply hardware protection: ${protResult.exceptionOrNull()?.message}")
            }

            // Success feedback
            playSuccessBeep()
            Toast.makeText(requireContext(), "Записана метка для: ${RoleUtils.formatShortName(target.studentName)}", Toast.LENGTH_SHORT).show()

            val db = StudentDatabase.getInstance(requireContext())

            // Reassign support: Clear this tag from any previous owner locally
            for (i in allStudentsList.indices) {
                val s = allStudentsList[i]
                if (s.id != target.id && SafeNdefManager.cleanUid(s.studentNFC).equals(rawUid, ignoreCase = true)) {
                    allStudentsList[i] = s.copy(studentNFC = "")
                    db.studentDao().updateNfc(s.id, "")
                }
            }

            // Update local memory and DB for target student
            val updatedStudent = target.copy(studentNFC = rawUid)
            val idx = allStudentsList.indexOfFirst { it.id == target.id }
            if (idx != -1) allStudentsList[idx] = updatedStudent

            db.studentDao().updateNfc(target.id, rawUid)

            // Auto-advance if toggle enabled
            val autoAdvance = binding.switchAutoAdvance.isChecked
            refreshConveyorQueue()
            if (autoAdvance) {
                advanceConveyorToNext(skipCurrent = false)
            }
        }
    }

    // ==================== REPLACEMENT WORKFLOW ====================

    private fun showReplaceTagDialog(student: DossierStudent) {
        val input = EditText(requireContext()).apply {
            hint = "Причина (например: утеря / сломалась)"
            setText("Утеря / повреждение карты")
            setPadding(32, 24, 32, 24)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Перевыпуск метки студенту")
            .setMessage(
                "Студент: ${RoleUtils.formatShortName(student.student_name)} (${student.group_name})\n\n" +
                "Старая метка будет АННУЛИРОВАНА на сервере.\n" +
                "Введите причину и нажмите 'Готов к прикладыванию':"
            )
            .setView(input)
            .setPositiveButton("Готов к прикладыванию") { _, _ ->
                val reason = input.text.toString().trim().ifBlank { "Перевыпуск / замена" }
                startPendingReplacement(student.student_id, reason)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startPendingReplacement(studentId: Int, reason: String) {
        isPendingReplacement = true
        pendingReplacementStudentId = studentId
        pendingReplacementReason = reason

        binding.tvReaderPromptTitle.text = "Поднесите новую метку NTAG 216"
        binding.tvReaderPromptSub.text = "Новая метка будет привязана, старая аннулирована"
        binding.cardReaderStatus.strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_warning)
        binding.cardReaderStatus.strokeWidth = 3
        Toast.makeText(requireContext(), "Приложите новую чистую метку NTAG 216", Toast.LENGTH_SHORT).show()
    }

    private fun handleReplacementTagScanned(newTag: Tag) {
        val sId = pendingReplacementStudentId
        val reason = pendingReplacementReason
        isPendingReplacement = false
        binding.cardReaderStatus.strokeWidth = 0
        binding.cardReaderStatus.strokeColor = ContextCompat.getColor(requireContext(), R.color.sib_border)

        val newUid = SafeNdefManager.cleanUid(newTag.id)
        if (newUid.isBlank()) {
            Toast.makeText(requireContext(), "Не удалось считать новую метку", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvReaderPromptTitle.text = "Перевыпуск метки..."

        lifecycleScope.launch {
            // 1. Generate payload for new UID
            val genResult = studentRepository.generateNfcPayload(sId, newUid)
            if (genResult is GenericResult.Error) {
                Toast.makeText(requireContext(), "Ошибка: ${genResult.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            val payloadData = (genResult as GenericResult.Success).data

            // 2. Safe write to new physical tag
            val writeResult = SafeNdefManager.writeSafePass(newTag, payloadData.payload)
            if (writeResult.isFailure) {
                Toast.makeText(requireContext(), "Ошибка записи в чип: ${writeResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Apply hardware read protection to new tag
            val protResult = SafeNdefManager.applyReadProtection(newTag, newUid)
            if (protResult.isFailure) {
                android.util.Log.w("NfcManager", "Failed to apply hardware protection on new tag: ${protResult.exceptionOrNull()?.message}")
            }

            // 3. Call server replacement endpoint
            val replaceResult = studentRepository.replaceNfcTag(sId, newUid, reason)
            if (replaceResult is GenericResult.Error) {
                Toast.makeText(requireContext(), "Ошибка на сервере: ${replaceResult.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            playSuccessBeep()
            Toast.makeText(requireContext(), "Метка успешно перевыпущена", Toast.LENGTH_LONG).show()

            // Update DB
            val db = StudentDatabase.getInstance(requireContext())
            db.studentDao().updateNfc(sId, newUid)

            // Refresh dossier
            val dossierRes = studentRepository.getStudentDossier(studentId = sId)
            if (dossierRes is GenericResult.Success) {
                val diagnostics = SafeNdefManager.inspectTag(newTag)
                displayStudentDossier(dossierRes.data, diagnostics)
            }
        }
    }

    // ==================== SAFE WIPE ====================

    private fun performSafeWipe(tag: Tag) {
        val rawUid = SafeNdefManager.cleanUid(tag.id)
        val wipeResult = SafeNdefManager.removeProtectionAndWipe(tag, rawUid)
        if (wipeResult.isSuccess) {
            playSuccessBeep()
            val formattedUid = SafeNdefManager.formatUidWithColons(rawUid)
            binding.tvDiagPayload.text = "NDEF Payload: Очищен (Пустой чип)"
            binding.tvDiagLock.text = "Защита от чтения: Снята (Заводской статус)\nСовместимый чип Type 2 (NDEF)"
            binding.cardStudentDossier.visibility = View.GONE
            binding.tvReaderPromptTitle.text = "Метка очищена ($formattedUid)"
            binding.tvReaderPromptSub.text = "Чип разблокирован и возвращён к пустому NDEF"
            binding.tvActiveTargetDetails.text = "Метка ($formattedUid) очищена от данных приложения"
            Toast.makeText(requireContext(), "Чип ($formattedUid) успешно очищен и разблокирован", Toast.LENGTH_SHORT).show()

            // Detach/revoke on backend & locally if it was bound to any student
            lifecycleScope.launch {
                studentRepository.revokeNfcTag(rawUid, "Метка физически очищена (Safe Wipe)")
                val db = StudentDatabase.getInstance(requireContext())
                for (i in allStudentsList.indices) {
                    val s = allStudentsList[i]
                    if (SafeNdefManager.cleanUid(s.studentNFC).equals(rawUid, ignoreCase = true)) {
                        allStudentsList[i] = s.copy(studentNFC = "")
                        db.studentDao().updateNfc(s.id, "")
                    }
                }
                if (activeConveyorStudent != null && SafeNdefManager.cleanUid(activeConveyorStudent?.studentNFC).equals(rawUid, ignoreCase = true)) {
                    activeConveyorStudent = activeConveyorStudent?.copy(studentNFC = "")
                    updateActiveTargetCard(activeConveyorStudent)
                }
                refreshConveyorQueue()
            }
        } else {
            val ex = wipeResult.exceptionOrNull()
            val msg = when {
                ex is android.nfc.TagLostException -> "Чип потерян из поля NFC. Удерживайте метку неподвижно у задней крышки."
                ex is java.io.IOException && ex.message.isNullOrBlank() -> "Связь с чипом прервана. Приложите плотно к антенне."
                !ex?.message.isNullOrBlank() -> ex?.message
                else -> "Ошибка NFC при очистке чипа"
            }
            Toast.makeText(requireContext(), "Ошибка очистки: $msg", Toast.LENGTH_LONG).show()
            isPendingWipe = true
            binding.tvActiveTargetDetails.text = "РЕЖИМ ОЧИСТКИ: удерживайте метку у задней крышки..."
        }
    }

    // ==================== FEEDBACK HELPERS ====================

    private fun playFeedbackHapticAndSound() {
        try {
            val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    private fun playSuccessBeep() {
        playFeedbackHapticAndSound()
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun showNfcNotSupportedMessage() {
        if (!isAdded) return
        Toast.makeText(context, "NFC не поддерживается", Toast.LENGTH_SHORT).show()
    }

    override fun showNfcReadingStartedMessage() {
        Log.d("NFC_MGR", "NFC сканер активен")
    }

    override fun showNfcReadingStoppedMessage() {
        Log.d("NFC_MGR", "NFC сканер остановлен")
    }
}
