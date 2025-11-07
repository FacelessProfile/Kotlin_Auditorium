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