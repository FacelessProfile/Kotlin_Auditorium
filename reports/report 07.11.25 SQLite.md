# Отчёт о проделанной работе
**Период:** 9 неделя проекта  
**Дата составления:** 7.11.2025  

## Описание проделанной работы

За восьмую неделю разработки были успешно выполнены следующие шаги:
- Был изучен материал по Dao/SQLite/Room
- Написан StudentRepository.kt
- Обновлены функции работающие с json
  
## Поэтапное описание

### Написан StudentRepository.kt
  За основу нового StudentRepository изучил пример из forked repository и имплементировал новый,подходящий для Room DB
```kotlin
package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.InternalSerializationApi

class StudentRepository(private val studentDao: StudentDao) {

    @OptIn(InternalSerializationApi::class)
    fun getAllStudents(): Flow<List<Student>> = studentDao.getAllStudents()

    @OptIn(InternalSerializationApi::class)
    fun getStudentsByGroup(group: String): Flow<List<Student>> = studentDao.getStudentsByGroup(group)

    fun getAllGroups(): Flow<List<String>> = studentDao.getAllGroups()

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNfc(nfcId: String): Student? = studentDao.getStudentByNfc(nfcId)

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentById(id: Int): Student? = studentDao.getStudentById(id)

    @OptIn(InternalSerializationApi::class)
    suspend fun insertStudent(student: Student) = studentDao.insertStudent(student)

    @OptIn(InternalSerializationApi::class)
    suspend fun updateStudent(student: Student) = studentDao.updateStudent(student)

    @OptIn(InternalSerializationApi::class)
    suspend fun deleteStudent(student: Student) = studentDao.deleteStudent(student)

    suspend fun deleteAllStudents() = studentDao.deleteAllStudents()

    suspend fun updateAttendance(id: Int, attendance: Boolean) = studentDao.updateAttendance(id, attendance)

    @OptIn(InternalSerializationApi::class)
    suspend fun migrateFromJson(jsonStudents: List<Student>) {
        jsonStudents.forEach { student ->
            insertStudent(student)
        }
    }
}
```
  

### Обновлены методы работающие с json
Теперь все они взаимодействуют с базой данных с помощью написаных Repository и DAO.
Фрагмент из 
```kotlin
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
```
<img width="1080" height="751" alt="image" src="https://github.com/user-attachments/assets/48d0276d-3ae7-4cfa-83fd-e4c3e1746469" />

<img width="1280" height="368" alt="image" src="https://github.com/user-attachments/assets/a2a746ea-54a9-470f-8f9f-c1b5bda5d27b" />


## Планы на неделю и issues

- Разобраться с методом аутентификации пользователя с помощью телефона
