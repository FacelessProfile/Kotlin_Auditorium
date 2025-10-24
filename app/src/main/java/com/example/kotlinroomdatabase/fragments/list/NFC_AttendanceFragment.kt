package com.example.kotlinroomdatabase.fragments.nfc

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class NFC_AttendanceFragment : NFC_Tools() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnClose: Button

    @OptIn(InternalSerializationApi::class)
    private lateinit var studentList: MutableList<Student>
    private val jsonFileName = "students.json"

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

        loadStudents()
        setupUI()
        setupNfcReading()
        startNfcReadingMode(true)
    }

    @OptIn(InternalSerializationApi::class)
    private fun loadStudents() {
        try {
            val file = File(requireContext().filesDir, jsonFileName)
            if (!file.exists()) {
                studentList = mutableListOf()
                Toast.makeText(requireContext(), "База студентов пуста", Toast.LENGTH_SHORT).show()
                return
            }
            val jsonString = file.readText()
            val list = Json.decodeFromString<List<Student>>(jsonString)
            studentList = list.toMutableList()
            Toast.makeText(requireContext(), "Загружено ${studentList.size} студентов", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            studentList = mutableListOf()
            Toast.makeText(requireContext(), "Ошибка загрузки студентов: ${e.message}", Toast.LENGTH_LONG).show()
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
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "NFC TAG СЧИТАН: $nfcId", Toast.LENGTH_LONG).show()

            if (nfcId.isNotBlank()) {
                val existingStudent = studentList.find { it.studentNFC == nfcId }

                if (existingStudent != null) {
                    if (existingStudent.attendance) {
                        // Студент уже отмечен
                        setWarningState()
                        statusText.text = "${existingStudent.studentName}\nуже отмечен"
                    } else {
                        // Студент найден и не отмечен
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
            } else {
                setErrorState()
                statusText.text = "Ошибка чтения метки"
                rootLayout.postDelayed({
                    setNeutralState()
                    statusText.text = "Поднесите следующую метку"
                }, 3000)
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun markStudentAttendance(student: Student) {
        val studentIndex = studentList.indexOfFirst { it.id == student.id }
        if (studentIndex != -1) {
            studentList[studentIndex] = student.copy(attendance = true)
            saveStudents()
            Toast.makeText(requireContext(), "${student.studentName} отмечен!", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudents() {
        try {
            val jsonString = Json.encodeToString(studentList)
            requireContext().openFileOutput(jsonFileName, Context.MODE_PRIVATE).use {
                it.write(jsonString.toByteArray())
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setNeutralState() {
        rootLayout.setBackgroundColor(Color.WHITE)
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.setColorFilter(resources.getColor(R.color.purple_500, null))
        statusText.setTextColor(Color.BLACK)
    }

    private fun setSuccessState() {
        rootLayout.setBackgroundColor(Color.GREEN)
        statusIcon.setImageResource(R.drawable.ic_check)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    private fun setErrorState() {
        rootLayout.setBackgroundColor(Color.RED)
        statusIcon.setImageResource(R.drawable.ic_cross)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    private fun setWarningState() {
        rootLayout.setBackgroundColor(Color.HSVToColor(floatArrayOf(35.0f,97.0f,78.0f)))
        statusIcon.setImageResource(R.drawable.ic_question)
        statusIcon.setColorFilter(Color.WHITE)
        statusText.setTextColor(Color.WHITE)
    }

    override fun onPause() {
        super.onPause()
        if (isReadingMode && !isInfiniteMode) {
            stopNfcReadingMode()
        }
    }

}