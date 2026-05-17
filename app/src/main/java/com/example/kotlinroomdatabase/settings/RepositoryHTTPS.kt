package com.example.kotlinroomdatabase.settings

import android.content.Context
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS

object RepositoryHTTPS {
    private var _studentRepository: StudentRepositoryHTTPS? = null

    fun initialize(context: Context) {
        if (_studentRepository == null) {
            val database = StudentDatabase.getInstance(context)
            _studentRepository = StudentRepositoryHTTPS(context, database.studentDao())
        }
    }

    fun getStudentRepository(context: Context): StudentRepositoryHTTPS {
        initialize(context)
        return _studentRepository!!
    }
}
