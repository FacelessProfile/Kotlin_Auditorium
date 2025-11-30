package com.example.kotlinroomdatabase

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.data.ZmqSockets
import com.example.kotlinroomdatabase.databinding.ActivityMainBinding
import com.example.kotlinroomdatabase.nfc.HCEservice
import com.example.kotlinroomdatabase.repository.StudentRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var studentRepository: StudentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = StudentDatabase.getInstance(this)
        studentRepository = StudentRepository(database.studentDao())
        restoreStudentSession()

        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentId = prefs.getInt("current_student_id", -1)

        val zeroMQSender = ZmqSockets("tcp://192.168.0.19:5555") // YOUR SERVER IP HERE
        StudentRepository(database.studentDao(), zeroMQSender)

        lifecycleScope.launch {
            val result = zeroMQSender.testConnection()
            Log.d("ZMQ_TEST", "Connection test: $result")
        }

        if (studentId != -1 && findNavController(R.id.fragment).currentDestination?.id == R.id.loginFragment) {
            findNavController(R.id.fragment).navigate(R.id.action_login_to_main)
        }
        setupActionBarWithNavController(findNavController(R.id.fragment))
    }

    // Enable back arrow button functionality in Add Fragment to return to List Fragment (main page of the app)
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.fragment)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    @OptIn(InternalSerializationApi::class)
    private fun restoreStudentSession() {
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentId = prefs.getInt("current_student_id", -1)

        if (studentId != -1) {
            lifecycleScope.launch {
                val student = studentRepository.getStudentById(studentId)
                HCEservice.currentStudent = student
            }
        }
    }

}