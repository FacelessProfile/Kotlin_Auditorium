package com.example.kotlinroomdatabase.fragments.add

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.databinding.FragmentAddBinding
import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AddFragment : Fragment() {

    private var _binding: FragmentAddBinding? = null
    private val binding get() = _binding!!
    private val jsonFileName = "students.json"
    private var nfcAdapter: NfcAdapter? = null
    private var isReadingMode = false
    private val nfcTimeoutHandler = Handler(Looper.getMainLooper())
    private val NFC_READ_TIMEOUT = 15000L // 15 sec timer for reading mode
    private var currentNfcId: String = ""
    private var editingStudentId: Int = 0

    @OptIn(InternalSerializationApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddBinding.inflate(inflater, container, false)
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        setupClickListeners()
        setupNfcReading()

        editingStudentId = arguments?.getInt("studentId", 0) ?: 0
        if (editingStudentId > 0) {
            val students = loadStudentList()
            val student = students.find { it.id == editingStudentId }
            if (student != null) {
                binding.addFirstNameEt.setText(student.studentName)
                binding.addLastNameEt.setText(student.studentGroup)
                binding.attendanceCb.isChecked = student.attendance
                currentNfcId = student.studentNFC
                binding.addBtn.text = "Update"
            }
        }

        return binding.root
    }

    private fun setupClickListeners() {
        binding.addBtn.setOnClickListener {
            saveStudent()
        }
        binding.btnReadNfc.setOnClickListener {
            startNfcReadingMode()
        }
    }

    private fun setupNfcReading() {
        if (nfcAdapter == null) {
            Toast.makeText(requireContext(), "NFC is not supported, use QR", Toast.LENGTH_LONG).show()
            binding.btnReadNfc.isEnabled = false
        }
    }

    private fun startNfcReadingMode() {
        if (nfcAdapter == null || isReadingMode) return
        isReadingMode = true
        binding.btnReadNfc.isEnabled = false
        binding.btnReadNfc.text = "Reading..."
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B
        nfcAdapter?.enableReaderMode(
            requireActivity(),
            nfcReaderCallback,
            flags,
            null
        )
        // auto stop reading after NFC_READ_TIMEOUT in milliseconds
        nfcTimeoutHandler.postDelayed({ stopNfcReadingModeByTimeout() }, NFC_READ_TIMEOUT)
        Toast.makeText(requireContext(), "Read is active for 15 sec.", Toast.LENGTH_SHORT).show()
    }

    private fun stopNfcReadingModeByTimeout() {
        if (!isReadingMode) return
        isReadingMode = false
        nfcAdapter?.disableReaderMode(requireActivity())
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
        binding.btnReadNfc.isEnabled = true
        binding.btnReadNfc.text = "Read NFC"
        Toast.makeText(requireContext(), "Reader mode expired", Toast.LENGTH_SHORT).show()
    }

    private fun stopNfcReadingModeAfterScan() {
        if (!isReadingMode) return
        isReadingMode = false
        nfcAdapter?.disableReaderMode(requireActivity())
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
        binding.btnReadNfc.isEnabled = true
        binding.btnReadNfc.text = "Read NFC"
    }

    private val nfcReaderCallback = NfcAdapter.ReaderCallback { tag ->
        val nfcId = tag.id?.joinToString("") { String.format("%02X", it) } ?: ""
        requireActivity().runOnUiThread {
            processNfcTag(nfcId)
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun processNfcTag(nfcId: String) {
        if (nfcId.isNotBlank()) {
            val students = loadStudentList()
            val existingStudent = students.find { it.studentNFC == nfcId && it.id != editingStudentId }
            if (existingStudent != null) {
                Toast.makeText(
                    requireContext(),
                    "TAG IS ALREADY USED!",
                    Toast.LENGTH_LONG
                ).show()
                stopNfcReadingModeAfterScan()
            } else {
                binding.attendanceCb.isChecked = true // если сканировали nfc, то студент был на паре (мини автоматизация)
                Toast.makeText(requireContext(), "NFC READED: $nfcId", Toast.LENGTH_LONG).show()
                stopNfcReadingModeAfterScan()
                currentNfcId = nfcId
            }
        } else {
            Toast.makeText(requireContext(), "NFC TAG reading err", Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudent() {
        val name = binding.addFirstNameEt.text.toString()
        val group = binding.addLastNameEt.text.toString()
        val attendance = binding.attendanceCb.isChecked

        // Formalize first
        val formalizedName = Student.formalizeName(name)
        val formalizedGroup = Student.formalizeGroup(group)

        val studentList = loadStudentList()

        if (currentNfcId != "") {
            val existingStudent = studentList.find { it.studentNFC == currentNfcId && it.id != editingStudentId }
            if (existingStudent != null) {
                Toast.makeText(
                    requireContext(),
                    "TAG IS ALREADY USED!",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val student = Student(
            id = editingStudentId,
            studentNFC = currentNfcId,
            studentName = formalizedName,
            studentGroup = formalizedGroup,
            attendance = attendance
        )

        if (student.isValid()) {
            if (editingStudentId > 0) {
                val index = studentList.indexOfFirst { it.id == editingStudentId }
                if (index != -1) {
                    studentList[index] = student.copy(id = editingStudentId)
                    Toast.makeText(requireContext(), "Student updated: ${student.studentName}", Toast.LENGTH_LONG).show()
                }
            } else {
                val newId = if (studentList.isEmpty()) 1 else (studentList.maxOf { it.id } + 1)
                studentList.add(student.copy(id = newId))
                Toast.makeText(requireContext(), "Student updated: ${student.studentName}", Toast.LENGTH_LONG).show()
            }

            saveStudentList(studentList)
            clearForm()
            currentNfcId = ""
            findNavController().popBackStack()
        } else {
            showValidationErrors(student)
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun showValidationErrors(student: Student) {
        val errors = mutableListOf<String>()
        if (!Student.validateName(student.studentName)) {
            errors.add("fio pattern missmatch")
        }
        if (!Student.validateGroup(student.studentGroup)) {
            errors.add("group pattern missmatch")
        }
        val errorMessage = "check:\n${errors.joinToString("\n")}"
        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
    }

    private fun clearForm() {
        binding.addFirstNameEt.text.clear()
        binding.addLastNameEt.text.clear()
        binding.attendanceCb.isChecked = false
    }

    @OptIn(InternalSerializationApi::class)
    private fun loadStudentList(): MutableList<Student> {
        return try {
            val jsonString = requireContext()
                .openFileInput(jsonFileName)
                .bufferedReader()
                .use { it.readText() }
            Json.decodeFromString<MutableList<Student>>(jsonString)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudentList(studentList: List<Student>) {
        val jsonString = Json.encodeToString(studentList)
        requireContext().openFileOutput(jsonFileName, Context.MODE_PRIVATE).use {
            it.write(jsonString.toByteArray())
        }
        Log.d("AddFragment", "students saved.. ${studentList.size} records")
    }

    override fun onPause() {
        super.onPause()
        // stop reading if user left add section
        if (isReadingMode) {
            stopNfcReadingModeAfterScan()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}