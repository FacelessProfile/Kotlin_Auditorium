package com.example.kotlinroomdatabase.fragments.nfc

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.ItemNfcConveyorStudentBinding
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.util.SafeNdefManager
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
class NfcConveyorAdapter(
    private val onStudentSelected: (Student) -> Unit
) : RecyclerView.Adapter<NfcConveyorAdapter.ViewHolder>() {

    private val students = mutableListOf<Student>()
    private var activeStudentId: Int? = null

    fun setData(newStudents: List<Student>, currentActiveId: Int?) {
        students.clear()
        students.addAll(newStudents)
        activeStudentId = currentActiveId
        notifyDataSetChanged()
    }

    fun setActiveStudent(studentId: Int?) {
        activeStudentId = studentId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNfcConveyorStudentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = students.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(students[position], position + 1, students[position].id == activeStudentId)
    }

    inner class ViewHolder(private val binding: ItemNfcConveyorStudentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(student: Student, index: Int, isActive: Boolean) {
            val context = binding.root.context
            binding.tvConveyorIndex.text = index.toString()
            binding.tvConveyorStudentName.text = com.example.kotlinroomdatabase.util.RoleUtils.formatShortName(student.studentName)

            val hasNfc = !student.studentNFC.isNullOrBlank() && student.studentNFC != "null"
            val displayUid = if (hasNfc) SafeNdefManager.formatUidWithColons(student.studentNFC) else "Нет метки"

            val isUnregistered = student.role == "unregistered" || student.studentGroup == "Абитуриент" || student.studentGroup.contains("Приемная") || student.studentGroup == "Без группы"
            val groupDisplay = if (student.studentGroup.isBlank()) "Абитуриент" else student.studentGroup
            val statusExtra = if (isUnregistered && !hasNfc) " • Приемная комиссия" else ""
            binding.tvConveyorStudentDetails.text = "$groupDisplay • $displayUid$statusExtra"

            when {
                isActive -> {
                    binding.cardConveyorStudent.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.sib_blue_active_bg)
                    )
                    binding.cardConveyorStudent.strokeColor =
                        ContextCompat.getColor(context, R.color.sib_blue_primary)
                    binding.cardConveyorStudent.strokeWidth = 2
                    binding.chipConveyorStatus.text = "К записи"
                    binding.chipConveyorStatus.setBackgroundResource(R.drawable.bg_nfc_stat_blue)
                    binding.chipConveyorStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.sib_blue_primary)
                    )
                }
                hasNfc -> {
                    binding.cardConveyorStudent.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.sib_card_surface)
                    )
                    binding.cardConveyorStudent.strokeColor =
                        ContextCompat.getColor(context, R.color.sib_border)
                    binding.cardConveyorStudent.strokeWidth = 1
                    binding.chipConveyorStatus.text = "Записан"
                    binding.chipConveyorStatus.setBackgroundResource(R.drawable.bg_nfc_stat_green)
                    binding.chipConveyorStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.sib_success)
                    )
                }
                isUnregistered -> {
                    binding.cardConveyorStudent.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.sib_card_surface)
                    )
                    binding.cardConveyorStudent.strokeColor =
                        ContextCompat.getColor(context, R.color.sib_border)
                    binding.cardConveyorStudent.strokeWidth = 1
                    binding.chipConveyorStatus.text = "Предзапись"
                    binding.chipConveyorStatus.setBackgroundResource(R.drawable.bg_nfc_stat_yellow)
                    binding.chipConveyorStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.sib_warning)
                    )
                }
                else -> {
                    binding.cardConveyorStudent.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.sib_card_surface)
                    )
                    binding.cardConveyorStudent.strokeColor =
                        ContextCompat.getColor(context, R.color.sib_border)
                    binding.cardConveyorStudent.strokeWidth = 1
                    binding.chipConveyorStatus.text = "В очереди"
                    binding.chipConveyorStatus.setBackgroundResource(R.drawable.bg_nfc_section_header)
                    binding.chipConveyorStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.sib_text_muted)
                    )
                }
            }

            binding.root.setOnClickListener {
                onStudentSelected(student)
            }
        }
    }
}
