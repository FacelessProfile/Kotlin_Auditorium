package com.example.kotlinroomdatabase

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.*
import com.example.kotlinroomdatabase.databinding.ActivityMainBinding
import com.example.kotlinroomdatabase.repository.StudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.example.kotlinroomdatabase.util.JwtUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var studentRepository: com.example.kotlinroomdatabase.repository.IStudentRepository
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val logoutReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.kotlinroomdatabase.LOGOUT") {
                val reason = intent.getStringExtra("reason") ?: "Сессия завершена"
                performLogout(reason)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("com.example.kotlinroomdatabase.LOGOUT")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logoutReceiver, filter)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(logoutReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(logoutReceiver)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logoutReceiver)
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        checkSessionValidityOnResume()
    }

    private fun checkSessionValidityOnResume() {
        try {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as? NavHostFragment
            val navController = navHostFragment?.navController
            val currentDest = navController?.currentDestination?.id

            if (currentDest != null && currentDest != R.id.loginFragment) {
                if (!JwtUtils.isUserSessionValid(this)) {
                    performLogout("Срок действия сессии истёк. Пожалуйста, выполните вход повторно.")
                } else {
                    val authPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    val token = authPrefs.getString("auth_token", null)
                    if (JwtUtils.needsRefresh(token)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity)
                            val repo = StudentRepositoryHTTPS(this@MainActivity, db.studentDao())
                            repo.refreshSessionToken()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "checkSessionValidityOnResume error", e)
        }
    }

    fun performLogout(message: String? = null) {
        JwtUtils.clearAllSessionData(this)
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as? NavHostFragment
                val navController = navHostFragment?.navController
                navController?.navigate(R.id.loginFragment, null, navOptions {
                    popUpTo(R.id.my_nav) { inclusive = true }
                })
                binding.drawerLayout.closeDrawers()
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                if (!message.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "performLogout error", e)
            }
        }
    }

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

        // Handle window insets for edge-to-edge / status bar / navigation bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)
            findViewById<android.view.View>(R.id.fragment)?.setPadding(0, 0, 0, systemBars.bottom)
            insets
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
                R.id.scheduleFragment,
                R.id.historyFragment,
                R.id.settingsFragment,
                R.id.notificationsFragment,
                R.id.gradesFragment,
                R.id.totpFragment
            ),
            binding.drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)

        val isSessionValid = JwtUtils.isUserSessionValid(this)

        if (isSessionValid) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
            val userRole = prefs.getString("user_role", "student")
            val studentId = prefs.getInt("current_student_id", -1)

            updateUIForRole()

            lifecycleScope.launch(Dispatchers.IO) {
                studentRepository.testConnection()
                if (studentId != -1) {
                    Log.d("MainActivity", "Starting auto-sync on launch for studentId=$studentId")
                    studentRepository.syncAllStudents()
                }
                try {
                    val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val schedRes = studentRepository.getScheduleForDay(todayStr)
                    if (schedRes is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                        com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.scheduleAlarmsForDay(this@MainActivity, schedRes.data)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error scheduling reminders on launch", e)
                }
            }

            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.loginFragment) {
                val actionId = if (userRole == "admin" || userRole == "teacher") {
                    R.id.action_loginFragment_to_lessonFragment
                } else {
                    R.id.action_login_to_userHome
                }
                navController.navigate(actionId, null, navOptions {
                    popUpTo(R.id.my_nav) { inclusive = true }
                })
            }
            checkUserAgreement()
            registerDeviceToken()
        } else {
            JwtUtils.clearAllSessionData(this)
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        }

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.logout -> {
                    performLogout("Вы вышли из учетной записи")
                    true
                }
                else -> {
                    val handled = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(menuItem, navController)
                    if (!handled) {
                        try {
                            navController.navigate(menuItem.itemId)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    binding.drawerLayout.closeDrawers()
                    true
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    fun updateUIForRole() {
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student")
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)

        val menu = binding.navView.menu
        menu.findItem(R.id.scheduleFragment)?.isVisible = true
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
        val headerView = binding.navView.getHeaderView(0) ?: return
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
        val name = prefs.getString("student_name", localizedRole) ?: localizedRole
        tvName.text = name

        tvName.setOnLongClickListener {
            com.example.kotlinroomdatabase.config.ServerConfig.showServerSwitcherDialog(this) {
                updateNavHeader()
            }
            true
        }
        tvEmail.setOnLongClickListener {
            com.example.kotlinroomdatabase.config.ServerConfig.showServerSwitcherDialog(this) {
                updateNavHeader()
            }
            true
        }
        ivAvatar.setOnLongClickListener {
            com.example.kotlinroomdatabase.config.ServerConfig.showServerSwitcherDialog(this) {
                updateNavHeader()
            }
            true
        }

        val savedEmail = prefs.getString("user_email", null)?.takeIf { it.isNotBlank() }
            ?: getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("user_email", null)?.takeIf { it.isNotBlank() }
            ?: if (userRole == "teacher") "teacher@sibsutis.ru" else "student@sibsutis.ru"
        tvEmail.text = savedEmail

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
                val finalUrl = com.example.kotlinroomdatabase.config.ServerConfig.resolveMediaUrl(this@MainActivity, url)

                Log.d("MainActivity", "Loading avatar from: $finalUrl")
                val repo = StudentRepositoryHTTPS(this@MainActivity, com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity).studentDao())
                val client = repo.getUnsafeOkHttpClient()
                val request = okhttp3.Request.Builder().url(finalUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return@launch
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

    private fun checkUserAgreement() {
        val authPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val token = authPrefs.getString("auth_token", "") ?: ""
        if (token.isNotEmpty()) {
            lifecycleScope.launch {
                val httpsRepo = StudentRepositoryHTTPS(this@MainActivity, com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity).studentDao())
                val result = httpsRepo.getUserAgreementCurrent()
                if (result is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                    val status = result.data
                    if (!status.accepted) {
                        val dialog = com.example.kotlinroomdatabase.fragments.profile.UserAgreementDialogFragment.newInstance(status.version)
                        dialog.show(supportFragmentManager, "UserAgreementDialogFragment")
                    }
                }
            }
        }
    }

    private fun registerDeviceToken() {
        val authPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val token = authPrefs.getString("auth_token", "") ?: ""
        if (token.isNotEmpty()) {
            lifecycleScope.launch {
                val httpsRepo = StudentRepositoryHTTPS(this@MainActivity, com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity).studentDao())
                val dummyFcmToken = "fcm_device_" + android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                httpsRepo.registerDeviceToken(dummyFcmToken, "android")
            }
        }
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        com.example.kotlinroomdatabase.util.LocalNotificationHelper.createNotificationChannel(this)
        com.example.kotlinroomdatabase.service.NotificationForegroundService.startService(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}