package com.example.kotlinroomdatabase.fragments.add

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.databinding.FragmentAddBinding
import com.example.kotlinroomdatabase.fragments.nfc.NFC_Tools
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.nfc.HCEservice
import com.example.kotlinroomdatabase.repository.StudentRepository
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class AddFragment :  NFC_Tools() {
    private var _binding: FragmentAddBinding? = null
    private val binding get() = _binding!!
    private var currentnfcData: String = ""
    private var editingStudentId: Int = 0
    private lateinit var studentRepository: StudentRepository
    private val TAG_ZMQ = "ZeroMQ_AddFragment"

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddBinding.inflate(inflater, container, false)
        setupNfcReading()
        setupClickListeners()

        editingStudentId = arguments?.getInt("studentId", 0) ?: 0
        if (editingStudentId > 0) {
            loadStudentData(editingStudentId)
        }

        return binding.root
    }

    private fun setupClickListeners() {
        binding.addBtn.setOnClickListener { saveStudent() }
        binding.btnReadNfc.setOnClickListener {
            startNfcReadingMode()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun loadStudentData(id: Int) {
        lifecycleScope.launch {
            val student = studentRepository.getStudentById(id)
            student?.let {
                requireActivity().runOnUiThread {
                    binding.addFirstNameEt.setText(it.studentName)
                    binding.addLastNameEt.setText(it.studentGroup)
                    binding.attendanceCb.isChecked = it.attendance
                    currentnfcData = it.studentNFC
                    binding.addBtn.text = "Update"
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        //результат из NFCTools (Payload от HCE либо UID ntag'a)
        lifecycleScope.launch {
            val existingStudent = studentRepository.getStudentByNfc(nfcId)

            requireActivity().runOnUiThread {
                if (existingStudent != null && existingStudent.id != editingStudentId) {
                    Toast.makeText(requireContext(), "ЭТОТ ТЕГ УЖЕ ИСПОЛЬЗУЕТСЯ!", Toast.LENGTH_LONG).show()
                    stopNfcReadingMode()
                } else {
                    currentnfcData = nfcId
                    binding.attendanceCb.isChecked = true
                    Toast.makeText(requireContext(), "NFC считан: $nfcId", Toast.LENGTH_SHORT).show()
                    stopNfcReadingMode()
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudent() {
        val name = binding.addFirstNameEt.text.toString()
        val group = binding.addLastNameEt.text.toString()
        val attendance = binding.attendanceCb.isChecked

        if (name.isBlank() || group.isBlank()) {
            Toast.makeText(requireContext(), "Заполните ФИО и группу", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val studentToSave = Student(
                    id = editingStudentId,
                    studentName = Student.formalizeName(name),
                    studentGroup = Student.formalizeGroup(group),
                    studentNFC = currentnfcData,
                    attendance = attendance
                )

                if (editingStudentId > 0) {
                    studentRepository.updateStudent(studentToSave)
                    saveToHcePreferences(currentnfcData)
                } else {
                    val insertedIdLong: Long = studentRepository.insertStudent(studentToSave)
                    val newId: Int = insertedIdLong.toInt()

                    var finalNfc = currentnfcData
                    if (finalNfc.isBlank()) {
                        // генерируем STUD tag
                        finalNfc = "STUD" + newId.toString().padStart(8, '0')
                        val studentWithNfc = studentToSave.copy(
                            id = newId,
                            studentNFC = finalNfc
                        )
                        // Обновляем запись в бдшке
                        studentRepository.updateStudent(studentWithNfc)
                    }

                    // STUD TAG TO SHARED PREF
                    saveToHcePreferences(finalNfc)
                }

                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Успешно сохранено", Toast.LENGTH_SHORT).show()
                    findNavController().popBackStack()
                }
            } catch (e: Exception) {
                Log.e(TAG_ZMQ, "Save error: ${e.message}")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Ошибка при сохранении", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun saveToHcePreferences(payload: String) {
        if (payload.isBlank()) return
        val prefs = requireContext().getSharedPreferences(HCEservice.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(HCEservice.KEY_NFC_PAYLOAD, payload).apply()
        Log.d(TAG_ZMQ, "Payload saved for HCE: $payload")
    }
    override fun showNfcNotSupportedMessage() {
        Toast.makeText(requireContext(), "NFC не поддерживается", Toast.LENGTH_LONG).show()
        binding.btnReadNfc.isEnabled = false
    }

    override fun showNfcReadingStartedMessage() {
        binding.btnReadNfc.text = "Ожидание метки..."
        binding.btnReadNfc.isEnabled = false
    }

    override fun showNfcReadingStoppedMessage() {
        binding.btnReadNfc.text = "Считать NFC"
        binding.btnReadNfc.isEnabled = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}