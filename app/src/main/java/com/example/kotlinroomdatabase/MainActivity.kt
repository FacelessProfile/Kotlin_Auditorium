package com.example.kotlinroomdatabase

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.*
import com.example.kotlinroomdatabase.databinding.ActivityMainBinding
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

        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val primaryColorHex = appPrefs.getString("primary_color", "#C48E17")
        primaryColorHex?.let {
            try {
                val color = android.graphics.Color.parseColor(it)
                binding.toolbar.setBackgroundColor(color)
                window.statusBarColor = color
            } catch (e: Exception) {
                Log.e("MainActivity", "Invalid color hex: $it")
            }
        }

        studentRepository = RepositoryZMQ.getStudentRepository(this)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.userHomeFragment,
                R.id.listFragment,
                R.id.lessonFragment,
                R.id.profileFragment,
                R.id.historyFragment,
                R.id.settingsFragment
            ),
            binding.drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)

        updateNavHeader()

        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentId = prefs.getInt("current_student_id", -1)
        val userRole = prefs.getString("user_role", "student")

        lifecycleScope.launch {
            studentRepository.testConnection()
        }
        if (studentId != -1) {
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.loginFragment) {
                val actionId = if (userRole == "admin" || userRole == "teacher") {
                    R.id.action_loginFragment_to_lessonFragment
                } else {
                    R.id.action_login_to_userHome
                }
                // Очищаем историю переходов, чтобы нельзя было вернуться к логину
                navController.navigate(actionId, null, navOptions {
                    popUpTo(R.id.my_nav) { inclusive = true }
                })
            }
        }

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.logout -> {
                    val prefsLogout = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                    prefsLogout.edit().clear().apply()
                    // Очищаем стек при выходе
                    navController.navigate(R.id.loginFragment, null, navOptions {
                        popUpTo(R.id.my_nav) { inclusive = true }
                    })
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

    fun updateNavHeader() {
        val headerView = binding.navView.getHeaderView(0)
        val tvName = headerView.findViewById<android.widget.TextView>(R.id.nav_header_name)
        val tvEmail = headerView.findViewById<android.widget.TextView>(R.id.nav_header_email)
        val ivAvatar = headerView.findViewById<android.widget.ImageView>(R.id.nav_header_avatar)
        
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("student_name", "Студент")
        tvName.text = name
        tvEmail.text = "${name?.replace(" ", ".")?.lowercase()}@university.edu"

        val avatarPath = prefs.getString("avatar_path", null)
        if (avatarPath != null) {
            try {
                val file = java.io.File(avatarPath)
                if (file.exists()) {
                    ivAvatar.setImageURI(android.net.Uri.fromFile(file))
                    ivAvatar.imageTintList = null
                } else if (avatarPath.startsWith("content://")) {
                    val uri = android.net.Uri.parse(avatarPath)
                    contentResolver.openInputStream(uri)?.use {
                        ivAvatar.setImageURI(uri)
                        ivAvatar.imageTintList = null
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading avatar from $avatarPath: ${e.message}")
                ivAvatar.setImageResource(R.drawable.ic_person)
                prefs.edit().remove("avatar_path").apply()
            }
        }

        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val primaryColorHex = appPrefs.getString("primary_color", "#C48E17")
        primaryColorHex?.let {
            try {
                headerView.setBackgroundColor(android.graphics.Color.parseColor(it))
            } catch (e: Exception) {}
        }
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