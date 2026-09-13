package com.example.kotlinroomdatabase

import android.content.Context
import android.content.Intent
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
                    if (!token.isNullOrBlank()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity)
                            val repo = StudentRepositoryHTTPS(this@MainActivity, db.studentDao())
                            repo.refreshSessionToken()
                        }
                    }
                    com.example.kotlinroomdatabase.util.AvatarManager.syncAvatarFromServer(this@MainActivity) {
                        updateNavHeader()
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

        // Handle window insets for status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        studentRepository = RepositoryZMQ.getStudentRepository(this)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.userHomeFragment,
                R.id.gradesFragment,
                R.id.historyFragment,
                R.id.lessonFragment,
                R.id.analyticsFragment,
                R.id.profileFragment,
                R.id.scheduleFragment,
                R.id.listFragment,
                R.id.settingsFragment,
                R.id.notificationsFragment,
                R.id.totpFragment,
                R.id.devTasksFragment,
                R.id.devBugsFragment,
                R.id.devSprintFragment
            ),
            binding.drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.toolbar.setNavigationIconTint(android.graphics.Color.WHITE)
        binding.navView.setupWithNavController(navController)

        // Setup bottom navigation listener
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val userRole = getSharedPreferences("student_prefs", Context.MODE_PRIVATE).getString("user_role", "student") ?: "student"
            val isDev = com.example.kotlinroomdatabase.util.RoleUtils.isDeveloper(userRole)
            val rootDestId = if (isDev) R.id.devTasksFragment else R.id.userHomeFragment

            if (item.itemId == rootDestId) {
                val popped = navController.popBackStack(rootDestId, false)
                if (!popped && navController.currentDestination?.id != rootDestId) {
                    navController.navigate(rootDestId)
                }
                return@setOnItemSelectedListener true
            }

            val currentId = navController.currentDestination?.id
            if (currentId != item.itemId) {
                navController.navigate(item.itemId, null, navOptions {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(rootDestId) {
                        saveState = true
                    }
                })
            }
            true
        }

        binding.bottomNavigation.setOnItemReselectedListener { item ->
            val userRole = getSharedPreferences("student_prefs", Context.MODE_PRIVATE).getString("user_role", "student") ?: "student"
            val isDev = com.example.kotlinroomdatabase.util.RoleUtils.isDeveloper(userRole)
            val rootDestId = if (isDev) R.id.devTasksFragment else R.id.userHomeFragment

            if (item.itemId == rootDestId) {
                if (navController.currentDestination?.id != rootDestId) {
                    val popped = navController.popBackStack(rootDestId, false)
                    if (!popped) {
                        navController.navigate(rootDestId)
                    }
                } else {
                    val currentFrag = navHostFragment.childFragmentManager.fragments.firstOrNull()
                    if (currentFrag is com.example.kotlinroomdatabase.fragments.list.User_Interface) {
                        currentFrag.scrollToTop()
                    }
                }
            } else {
                navController.popBackStack(item.itemId, false)
            }
        }

        // Role badge & notification bell click handlers
        binding.tvRoleBadge.setOnClickListener {
            if (navController.currentDestination?.id != R.id.profileFragment) {
                navController.navigate(R.id.profileFragment)
            }
        }

        binding.frameNotifications.setOnClickListener {
            if (navController.currentDestination?.id != R.id.notificationsFragment) {
                navController.navigate(R.id.notificationsFragment)
            }
        }

        // Show/hide toolbar and bottom navigation based on destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.loginFragment) {
                binding.appBarLayout.visibility = android.view.View.GONE
                binding.bottomNavigation.visibility = android.view.View.GONE
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            } else {
                binding.appBarLayout.visibility = android.view.View.VISIBLE
                binding.bottomNavigation.visibility = android.view.View.VISIBLE
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                binding.toolbar.setNavigationIconTint(android.graphics.Color.WHITE)
                // Sync selected bottom nav item if destination matches
                val bottomMenu = binding.bottomNavigation.menu
                for (i in 0 until bottomMenu.size()) {
                    val menuItem = bottomMenu.getItem(i)
                    if (menuItem.itemId == destination.id) {
                        menuItem.isChecked = true
                        break
                    }
                }
            }
        }

        val isSessionValid = JwtUtils.isUserSessionValid(this)

        if (isSessionValid) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
            val studentId = prefs.getInt("current_student_id", -1)

            updateUIForRole()
            refreshNotificationBadge()

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
                navController.navigate(R.id.action_login_to_userHome, null, navOptions {
                    popUpTo(R.id.my_nav) { inclusive = true }
                })
            }
            checkUserAgreement()
            registerDeviceToken()
        } else {
            JwtUtils.clearAllSessionData(this)
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.appBarLayout.visibility = android.view.View.GONE
            binding.bottomNavigation.visibility = android.view.View.GONE
        }

        // Automatic in-app update scanner on launch
        com.example.kotlinroomdatabase.update.AppUpdateManager.checkForUpdatesOnLaunch(this)

        handleFcmIntentExtras(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFcmIntentExtras(intent)
    }

    private fun handleFcmIntentExtras(intent: Intent?) {
        val taskIdStr = intent?.getStringExtra("fcm_extra_task_id")
        val taskId = taskIdStr?.toIntOrNull()
        if (taskId != null && taskId > 0) {
            val sheet = com.example.kotlinroomdatabase.fragments.dev.DevTaskDetailBottomSheet.newInstance(taskId)
            sheet.show(supportFragmentManager, com.example.kotlinroomdatabase.fragments.dev.DevTaskDetailBottomSheet.TAG)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    fun refreshNotificationBadge() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val res = studentRepository.getUnreadNotificationsCount()
                if (res is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                    withContext(Dispatchers.Main) {
                        updateUnreadNotificationCount(res.data)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun updateUnreadNotificationCount(count: Int) {
        if (count > 0) {
            binding.tvUnreadBadge.visibility = android.view.View.VISIBLE
            binding.tvUnreadBadge.text = if (count > 99) "99+" else count.toString()
        } else {
            binding.tvUnreadBadge.visibility = android.view.View.GONE
        }
    }

    fun updateUIForRole() {
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val isTeacher = com.example.kotlinroomdatabase.util.RoleUtils.isTeacherOrHead(userRole)
        val isDev = com.example.kotlinroomdatabase.util.RoleUtils.isDeveloper(userRole)

        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        binding.tvRoleBadge.text = com.example.kotlinroomdatabase.util.RoleUtils.getRoleLabel(userRole)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment) as? NavHostFragment
        val navController = navHostFragment?.navController

        // Switch bottom navigation menu depending on role
        val currentMenuRes = when {
            isDev -> R.menu.bottom_nav_developer
            isTeacher -> R.menu.bottom_nav_teacher
            else -> R.menu.bottom_nav_student
        }
        if (binding.bottomNavigation.tag != currentMenuRes) {
            binding.bottomNavigation.menu.clear()
            binding.bottomNavigation.inflateMenu(currentMenuRes)
            binding.bottomNavigation.tag = currentMenuRes
            if (navController != null) {
                val currentDestId = navController.currentDestination?.id
                if (isDev && currentDestId != R.id.devTasksFragment && currentDestId != R.id.devBugsFragment && currentDestId != R.id.devSprintFragment && currentDestId != R.id.profileFragment) {
                    navController.navigate(R.id.devTasksFragment)
                } else if (!isDev && (currentDestId == R.id.devTasksFragment || currentDestId == R.id.devBugsFragment || currentDestId == R.id.devSprintFragment)) {
                    navController.navigate(R.id.userHomeFragment)
                } else if (currentDestId != null) {
                    val item = binding.bottomNavigation.menu.findItem(currentDestId)
                    item?.isChecked = true
                }
            }
        }

        val menu = binding.navView.menu
        menu.findItem(R.id.scheduleFragment)?.isVisible = true
        menu.findItem(R.id.listFragment)?.isVisible = isTeacher
        updateNavHeader()
        registerDeviceToken()
    }

    fun updateNavHeader() {
        val headerView = binding.navView.getHeaderView(0) ?: return
        val tvName = headerView.findViewById<android.widget.TextView>(R.id.nav_header_name)
        val tvEmail = headerView.findViewById<android.widget.TextView>(R.id.nav_header_email)
        val ivAvatar = headerView.findViewById<android.widget.ImageView>(R.id.nav_header_avatar)
        
        val prefs = getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val localizedRole = com.example.kotlinroomdatabase.util.RoleUtils.getRoleLabel(userRole)
        val rawName = prefs.getString("student_name", localizedRole) ?: localizedRole
        tvName.text = com.example.kotlinroomdatabase.util.RoleUtils.formatShortName(rawName)
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

        com.example.kotlinroomdatabase.util.AvatarManager.loadCachedAvatar(
            this,
            ivAvatar,
            defaultPadDp = 12,
            placeholderTintRes = R.color.white
        )
    }

    private fun loadAvatarFromUrl(url: String?, imageView: android.widget.ImageView) {
        val defaultNavPad = (12 * resources.displayMetrics.density).toInt()
        if (url.isNullOrBlank() || url == "null") {
            imageView.setPadding(defaultNavPad, defaultNavPad, defaultNavPad, defaultNavPad)
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
                val token = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", "") ?: ""
                val requestBuilder = okhttp3.Request.Builder()
                    .url(finalUrl)
                    .header("Cache-Control", "no-cache")
                if (token.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                val response = client.newCall(requestBuilder.build()).execute()
                
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return@launch
                    val file = java.io.File(filesDir, "current_avatar.jpg")
                    try {
                        java.io.FileOutputStream(file).use { it.write(bytes) }
                        getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("avatar_path", file.absolutePath)
                            .putString("synced_avatar_url", url)
                            .apply()
                    } catch (_: Exception) {}

                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        imageView.setPadding(0, 0, 0, 0)
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
        val authToken = authPrefs.getString("auth_token", "") ?: ""

        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val fcmToken = task.result
                    android.util.Log.d("MainActivity", "Fetched FCM token: $fcmToken")
                    getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                        .edit().putString("fcm_token", fcmToken).apply()

                    if (authToken.isNotEmpty()) {
                        lifecycleScope.launch {
                            val httpsRepo = StudentRepositoryHTTPS(this@MainActivity, com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity).studentDao())
                            httpsRepo.registerDeviceToken(fcmToken, "android")
                        }
                    }
                } else {
                    android.util.Log.w("MainActivity", "Fetching FCM token failed, falling back to device ID", task.exception)
                    if (authToken.isNotEmpty()) {
                        lifecycleScope.launch {
                            val httpsRepo = StudentRepositoryHTTPS(this@MainActivity, com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(this@MainActivity).studentDao())
                            val fallbackToken = "device_" + android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                            httpsRepo.registerDeviceToken(fallbackToken, "android")
                        }
                    }
                }
            }
        checkNotificationPermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.setNotificationsEnabled(this, isGranted)
    }

    private fun checkNotificationPermission() {
        com.example.kotlinroomdatabase.util.LocalNotificationHelper.createNotificationChannel(this)
        val mode = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.getNotificationMode(this)
        if (mode != com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_FCM_ONLY &&
            mode != com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_DISABLED &&
            com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isNotificationsEnabled(this)
        ) {
            com.example.kotlinroomdatabase.service.NotificationForegroundService.startService(this)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}