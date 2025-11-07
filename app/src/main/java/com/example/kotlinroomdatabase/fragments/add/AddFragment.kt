package com.example.kotlinroomdatabase.fragments.add

import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.databinding.FragmentAddBinding
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.fragments.nfc.NFC_Tools
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.StudentRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class AddFragment :  NFC_Tools() {
    private var _binding: FragmentAddBinding? = null
    private val binding get() = _binding!!
    private var currentNfcId: String = ""
    private var editingStudentId: Int = 0
    private lateinit var studentRepository: StudentRepository

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        val database = StudentDatabase.getInstance(requireContext())
        studentRepository = StudentRepository(database.studentDao())
    }

    private fun setupNfcButton() {              //настройка nfc button
        binding.btnReadNfc.setOnClickListener {
            setupNfcReading()
            startNfcReadingMode()
        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        lifecycleScope.launch {
            if (nfcId.isNotBlank()) {
                val existingStudent = studentRepository.getStudentByNfc(nfcId)
                requireActivity().runOnUiThread {
                    if (existingStudent != null && existingStudent.id != editingStudentId) {
                        Toast.makeText(requireContext(), "TAG IS ALREADY USED!", Toast.LENGTH_LONG).show()
                        stopNfcReadingMode()
                    } else {
                        binding.attendanceCb.isChecked = true // если сканировали nfc, то студент был на паре (мини автоматизация)
                        Toast.makeText(requireContext(), "NFC READED: $nfcId", Toast.LENGTH_LONG).show()
                        stopNfcReadingMode()
                        currentNfcId = nfcId
                    }
                }
            } else {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "NFC TAG reading err", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun showNfcNotSupportedMessage() {
        Toast.makeText(requireContext(), "NFC is not supported, use QR", Toast.LENGTH_LONG).show()
        binding.btnReadNfc.isEnabled = false
    }

    override fun showNfcReadingStartedMessage() {
        binding.btnReadNfc.isEnabled = false
        binding.btnReadNfc.text = "Reading..."
        Toast.makeText(requireContext(), "Read is active for 15 sec.", Toast.LENGTH_SHORT).show() //15 sec in NFC TOOLS
    }

    override fun showNfcReadingStoppedMessage() {
        binding.btnReadNfc.isEnabled = true
        binding.btnReadNfc.text = "Read NFC"
        Toast.makeText(requireContext(), "Reader mode expired", Toast.LENGTH_SHORT).show()
    }

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
            lifecycleScope.launch {
                val student = studentRepository.getStudentById(editingStudentId)
                student?.let {
                    requireActivity().runOnUiThread {
                        binding.addFirstNameEt.setText(it.studentName)
                        binding.addLastNameEt.setText(it.studentGroup)
                        binding.attendanceCb.isChecked = it.attendance
                        currentNfcId = it.studentNFC
                        binding.addBtn.text = "Update"
                    }
                }
            }
        }

        return binding.root
    }

    private fun setupClickListeners() {
        binding.addBtn.setOnClickListener {
            saveStudent()
        }
        setupNfcButton()
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudent() {
        lifecycleScope.launch {
            val name = binding.addFirstNameEt.text.toString()
            val group = binding.addLastNameEt.text.toString()
            val attendance = binding.attendanceCb.isChecked

            val formalizedName = Student.formalizeName(name)
            val formalizedGroup = Student.formalizeGroup(group)

            if (currentNfcId.isNotBlank()) {
                val existingStudent = studentRepository.getStudentByNfc(currentNfcId)
                if (existingStudent != null && existingStudent.id != editingStudentId) {
                    Toast.makeText(requireContext(), "TAG IS ALREADY USED!", Toast.LENGTH_LONG).show()
                    return@launch
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
                    studentRepository.updateStudent(student)
                    Toast.makeText(requireContext(), "Student updated: ${student.studentName}", Toast.LENGTH_LONG).show()
                } else {
                    studentRepository.insertStudent(student)
                    Toast.makeText(requireContext(), "Student added: ${student.studentName}", Toast.LENGTH_LONG).show()
                }
                clearForm()
                currentNfcId = ""
                findNavController().popBackStack()
            } else {
                showValidationErrors(student)
            }
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

    override fun onPause() {
        super.onPause()
        if (isReadingMode) {
            stopNfcReadingMode()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        nfcTimeoutHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}