package com.example.kotlinroomdatabase.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.data.ZmqSockets
import com.example.kotlinroomdatabase.repository.StudentRepository

object RepositoryZMQ {
    private var _studentRepository: StudentRepository? = null
    private var _sharedPreferences: SharedPreferences? = null
    fun initialize(context: Context) {
        if (_studentRepository == null) {
            val database = StudentDatabase.getInstance(context)
            val zeroMQSender = ZmqSockets("tcp://192.168.0.19:5555") // CHANGE ME
            _studentRepository = StudentRepository(database.studentDao(), zeroMQSender)
            _sharedPreferences = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getStudentRepository(context: Context): StudentRepository {
        initialize(context)
        return _studentRepository!!
    }

    fun getSharedPreferences(context: Context): SharedPreferences {
        initialize(context)
        return _sharedPreferences!!
    }

    fun getCurrentStudentId(context: Context): Int {
        return getSharedPreferences(context).getInt("current_student_id", -1)
    }
}