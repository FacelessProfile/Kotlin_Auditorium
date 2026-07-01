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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var studentRepository: StudentRepository
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = appPrefs.getString("theme_mode", "system") ?: "system"
        val appCompatMode = when (themeMode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(appCompatMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val primaryColorHex = appPrefs.getString("navbar_color", "#C48E17")
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

        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student")
        val studentId = prefs.getInt("current_student_id", -1)

        updateUIForRole()

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
                    val authPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    authPrefs.edit().remove("avatar_url").apply()

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

    fun updateUIForRole() {
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student")
        
        val menu = binding.navView.menu
        if (userRole == "teacher" || userRole == "admin") {
            menu.findItem(R.id.userHomeFragment)?.isVisible = false
            menu.findItem(R.id.historyFragment)?.isVisible = true
            menu.findItem(R.id.lessonFragment)?.isVisible = true
            menu.findItem(R.id.listFragment)?.isVisible = true
        } else {
            menu.findItem(R.id.userHomeFragment)?.isVisible = true
            menu.findItem(R.id.historyFragment)?.isVisible = true
            menu.findItem(R.id.lessonFragment)?.isVisible = false
            menu.findItem(R.id.listFragment)?.isVisible = false
        }
        updateNavHeader()
    }

    fun updateNavHeader() {
        val headerView = binding.navView.getHeaderView(0)
        val tvName = headerView.findViewById<android.widget.TextView>(R.id.nav_header_name)
        val tvEmail = headerView.findViewById<android.widget.TextView>(R.id.nav_header_email)
        val ivAvatar = headerView.findViewById<android.widget.ImageView>(R.id.nav_header_avatar)
        
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student")
        val localizedRole = when(userRole) {
            "teacher" -> "Преподаватель"
            "admin" -> "Администратор"
            else -> "Студент"
        }
        val name = prefs.getString("student_name", localizedRole)
        
        tvName.text = name
        tvEmail.text = if (userRole == "teacher") localizedRole else prefs.getString("student_group", "Студент")

        val avatarPath = prefs.getString("avatar_path", null)
        val authPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val avatarUrl = authPrefs.getString("avatar_url", null)

        Log.d("MainActivity", "Updating header: path=$avatarPath, url=$avatarUrl")

        if (avatarPath != null) {
            val file = java.io.File(avatarPath)
            if (file.exists()) {
                ivAvatar.setImageURI(android.net.Uri.fromFile(file))
                ivAvatar.imageTintList = null
            } else {
                loadAvatarFromUrl(avatarUrl, ivAvatar)
            }
        } else {
            loadAvatarFromUrl(avatarUrl, ivAvatar)
        }

        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val primaryColorHex = appPrefs.getString("navbar_color", "#C48E17")
        primaryColorHex?.let {
            try {
                headerView.setBackgroundColor(android.graphics.Color.parseColor(it))
            } catch (e: Exception) {}
        }
    }

    private fun loadAvatarFromUrl(url: String?, imageView: android.widget.ImageView) {
        if (url.isNullOrBlank() || url == "null") {
            imageView.setImageResource(R.drawable.ic_person)
            imageView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Handle relative URLs
                val finalUrl = if (url.startsWith("http")) {
                    url
                } else {
                    "http://109.172.114.128:9000${if (url.startsWith("/")) "" else "/"}$url"
                }

                Log.d("MainActivity", "Loading avatar from: $finalUrl")
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(finalUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val bytes = response.body.bytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(bitmap)
                        imageView.imageTintList = null
                        Log.d("MainActivity", "Avatar loaded successfully")
                    }
                } else {
                    Log.e("MainActivity", "Failed to download avatar: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading avatar: ${e.message}")
                withContext(Dispatchers.Main) {
                    imageView.setImageResource(R.drawable.ic_person)
                    imageView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                }
            }
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