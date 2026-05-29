package com.example.kotlinroomdatabase

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.*
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.data.ZmqSockets
import com.example.kotlinroomdatabase.databinding.ActivityMainBinding
import com.example.kotlinroomdatabase.nfc.HCEservice
import com.example.kotlinroomdatabase.repository.StudentRepository
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var studentRepository: StudentRepository
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        studentRepository = RepositoryZMQ.getStudentRepository(this)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.userHomeFragment, R.id.listFragment, R.id.lessonFragment, R.id.profileFragment),
            binding.drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)

        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentId = prefs.getInt("current_student_id", -1)
        val userRole = prefs.getString("user_role", "student")

        lifecycleScope.launch {
            studentRepository.testConnection()
        }
        if (studentId != -1) {
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.loginFragment) {
                if (userRole == "admin" || userRole == "teacher") {
                    navController.navigate(R.id.action_loginFragment_to_lessonFragment)
                } else {
                    navController.navigate(R.id.userHomeFragment)
                }
            }
        }

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.logout -> {
                    val prefsLogout = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                    prefsLogout.edit().clear().apply()
                    navController.navigate(R.id.loginFragment)
                    binding.drawerLayout.closeDrawers()
                    true
                }
                else -> {
                    val handled = menuItem.onNavDestinationSelected(navController)
                    if (handled) binding.drawerLayout.closeDrawers()
                    handled
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    @OptIn(InternalSerializationApi::class)
    private fun restoreStudentSession() {
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentId = prefs.getInt("current_student_id", -1)

        if (studentId != -1) {
            lifecycleScope.launch {
                val student = studentRepository.getStudentById(studentId)
                //HCEservice.currentStudent = student
            }
        }
    }

}