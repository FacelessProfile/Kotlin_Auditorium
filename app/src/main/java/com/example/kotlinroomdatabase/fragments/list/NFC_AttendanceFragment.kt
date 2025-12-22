package com.example.kotlinroomdatabase.fragments.nfc

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.data.ZmqSockets
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.StudentRepository
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class NFC_AttendanceFragment : NFC_Tools() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnClose: Button
    private lateinit var studentRepository: StudentRepository
    private var TAG = "ZMQ_UPD_FRAG"

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
        studentRepository.getAllStudents()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_nfc_checker, container, false)

        rootLayout = view.findViewById(R.id.rootLayout)
        statusIcon = view.findViewById(R.id.statusIcon)
        statusText = view.findViewById(R.id.statusText)
        btnClose = view.findViewById(R.id.btnClose)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupNfcReading()
        startNfcReadingMode(true)
        syncDataOnStart()
    }

    @OptIn(InternalSerializationApi::class)
    private fun syncDataOnStart() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting data sync on fragment start...")
                val result = studentRepository.syncAllStudents()
                studentRepository.getAllStudents()
                when (result) {
                    is StudentRepository.SyncResult.Success -> {
                        Log.d(TAG, "Sync successful: ${result.message}")
                    }
                    is StudentRepository.SyncResult.Error -> {
                        Log.e(TAG, "Sync failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
            }
        }
    }

    private fun setupUI() {
        btnClose.setOnClickListener {
            stopNfcReadingMode()
            findNavController().navigateUp()
        }
        setNeutralState()
    }

    override fun showNfcNotSupportedMessage() {
        requireActivity().runOnUiThread {
            statusText.text = "NFC не поддерживается"
            setErrorState()
            Toast.makeText(requireContext(), "NFC не поддерживается устройством", Toast.LENGTH_LONG).show()
        }
    }

    override fun showNfcReadingStartedMessage() {
        requireActivity().runOnUiThread {
            statusText.text = "Поднесите NFC метку"
            setNeutralState()
            if (isInfiniteMode) {
                Toast.makeText(requireContext(), "NFC чтение активировано (вечный режим)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "NFC чтение активировано на 15 сек", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun showNfcReadingStoppedMessage() {
        requireActivity().runOnUiThread {
            statusText.text = "NFC чтение остановлено"
            Toast.makeText(requireContext(), "NFC чтение остановлено", Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        lifecycleScope.launch {
            try {
                val existingStudent = studentRepository.getStudentByNfc(nfcId)

                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "NFC TAG СЧИТАН: $nfcId", Toast.LENGTH_LONG).show()

                    if (existingStudent != null) {
                        if (existingStudent.attendance) {
                            setWarningState()
                            statusText.text = "${existingStudent.studentName}\nуже отмечен"
                        } else {
                            markStudentAttendance(existingStudent)
                            setSuccessState()
                            statusText.text = "${existingStudent.studentName}\nотмечен присутствующим"
                        }
                    } else {
                        setErrorState()
                        statusText.text = "Студент не найден"
                    }

                    rootLayout.postDelayed({
                        setNeutralState()
                        statusText.text = "Поднесите следующую метку"
                    }, 3000)
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    setErrorState()
                    statusText.text = "Ошибка базы данных"
                    rootLayout.postDelayed({
                        setNeutralState()
                        statusText.text = "Поднесите следующую метку"
                    }, 3000)
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun markStudentAttendance(student: Student) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Marking attendance for student: ${student.studentName}")
                studentRepository.updateAttendance(student.id, true)
                val syncResult = studentRepository.syncAllStudents()
                when (syncResult) {
                    is StudentRepository.SyncResult.Success -> {
                        Log.d(TAG, "Attendance sync successful")
                    }
                    is StudentRepository.SyncResult.Error -> {
                        Log.e(TAG, "Attendance sync failed: ${syncResult.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error marking attendance: ${e.message}")
            }
        }
    }

    private fun setNeutralState() {
        if (!isAdded || isDetached) return
        rootLayout.setBackgroundColor(Color.WHITE)
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.setColorFilter(resources.getColor(R.color.purple_500, null))
        statusText.setTextColor(Color.BLACK)
    }

    private fun setSuccessState() {
        if (!isAdded || isDetached) return
        rootLayout.setBackgroundColor(Color.GREEN)
        statusIcon.setImageResource(R.drawable.ic_check)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    private fun setErrorState() {
        if (!isAdded || isDetached) return
        rootLayout.setBackgroundColor(Color.RED)
        statusIcon.setImageResource(R.drawable.ic_cross)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    private fun setWarningState() {
        if (!isAdded || isDetached) return
        rootLayout.setBackgroundColor(Color.HSVToColor(floatArrayOf(35.0f,97.0f,78.0f)))
        statusIcon.setImageResource(R.drawable.ic_question)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    override fun onPause() {
        super.onPause()
        rootLayout.removeCallbacks(null)
        if (isReadingMode && !isInfiniteMode) {
            stopNfcReadingMode()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun readHceData() {
        lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("student_prefs", android.content.Context.MODE_PRIVATE)
            val studentId = prefs.getInt("current_student_id", -1)

            if (studentId != -1) {
                val student = studentRepository.getStudentById(studentId)
                student?.let {
                    requireActivity().runOnUiThread {
                        handleStudentFound(it)
                    }
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun handleStudentFound(student: Student) {
        if (student.attendance) {
            setWarningState()
            statusText.text = "${student.studentName}\nуже отмечен"
        } else {
            markStudentAttendance(student)
            setSuccessState()
            statusText.text = "${student.studentName}\nотмечен присутствующим"
        }

        rootLayout.postDelayed({
            setNeutralState()
            statusText.text = "Поднесите следующую метку"
        }, 3000)
    }
}