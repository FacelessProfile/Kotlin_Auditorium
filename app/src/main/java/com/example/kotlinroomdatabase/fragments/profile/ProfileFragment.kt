package com.example.kotlinroomdatabase.fragments.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.MainActivity
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentProfileBinding
import com.example.kotlinroomdatabase.model.UserProfile
import com.example.kotlinroomdatabase.repository.AvatarResult
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.RoleUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: IStudentRepository
    private var cachedProfile: UserProfile? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { openCropDialog(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        setupStaticData()
        updateServerHostText()

        binding.swipeRefreshProfile.setOnRefreshListener {
            loadFullProfile(isSwipe = true)
        }

        binding.profileAvatar.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.cardProfileAvatar.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnChangeAvatarBadge.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnSwitchRole.setOnClickListener {
            showRoleSwitcherDialog()
        }

        binding.rowTotpSecurity.setOnClickListener {
            findNavController().navigate(R.id.totpFragment)
        }

        binding.rowUserAgreement.setOnClickListener {
            showUserAgreementDialog()
        }

        binding.rowServerSettings.setOnClickListener {
            ServerConfig.showServerSwitcherDialog(requireContext()) {
                updateServerHostText()
            }
        }

        binding.btnLogoutProfile.setOnClickListener {
            showLogoutConfirmDialog()
        }

        loadFullProfile()
    }

    private fun setupStaticData() {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val studentName = prefs.getString("student_name", "Студент") ?: "Студент"
        val groupName = prefs.getString("student_group", "СибГУТИ") ?: "СибГУТИ"
        val studentId = prefs.getInt("current_student_id", 1)
        val email = prefs.getString("user_email", null) ?: "student@sibsutis.ru"

        binding.profileName.text = RoleUtils.formatShortName(studentName)
        binding.profileRoleBadge.text = RoleUtils.getRoleLabel(userRole)
        binding.tvProfileEmail.text = email

        val isTeacher = RoleUtils.isTeacherOrHead(userRole)
        binding.tvLabelGroupOrDept.text = if (isTeacher) "Кафедра" else "Учебная группа"
        binding.tvProfileGroup.text = if (isTeacher) "Кафедра ПОВТ / Инфокоммуникации" else groupName

        binding.tvLabelIdNumber.text = if (isTeacher) "Табельный номер" else "Номер зачетной книжки"
        binding.tvProfileIdNumber.text = if (isTeacher) "ID-T$studentId" else "№ 2023-${groupName.take(4)}-$studentId"

        val storedRoles = try {
            prefs.getString("user_roles", null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
        } catch (_: Exception) {
            null
        }
        val hasMultipleRoles = (storedRoles?.size ?: 0) > 1
        binding.btnSwitchRole.visibility = if (hasMultipleRoles) View.VISIBLE else View.GONE

        loadAvatar()
    }

    private fun loadFullProfile(isSwipe: Boolean = false) {
        binding.swipeRefreshProfile.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.getFullUserProfile()
            val agreementResult = repository.getUserAgreementCurrent()

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                binding.swipeRefreshProfile.isRefreshing = false

                if (result is GenericResult.Success) {
                    val profile = result.data
                    cachedProfile = profile

                    // Immediately sync avatar from server if changed or on refresh
                    syncAvatarFromServer(profile.avatar, forceRefresh = isSwipe)

                    binding.profileName.text = RoleUtils.formatShortName(profile.effectiveDisplayName)
                    binding.profileRoleBadge.text = RoleUtils.getRoleLabel(profile.effectiveRole)
                    if (profile.email.isNotBlank()) binding.tvProfileEmail.text = profile.email

                    val isTeacher = RoleUtils.isTeacherOrHead(profile.effectiveRole)
                    binding.tvLabelGroupOrDept.text = if (isTeacher) "Кафедра" else "Учебная группа"
                    val groupOrDept = profile.effectiveGroupOrDepartment
                    binding.tvProfileGroup.text = if (groupOrDept.isNotBlank()) {
                        groupOrDept
                    } else if (isTeacher) {
                        "Кафедра ПОВТ"
                    } else {
                        "СибГУТИ"
                    }

                    val idVal = if (profile.user_id > 0) profile.user_id.toString() else "001"
                    binding.tvProfileIdNumber.text = if (isTeacher) "ID-T$idVal" else "№ 2023-$idVal"

                    // Available roles display & switch button visibility
                    val distinctRoles = (profile.availableRoles + profile.roles).filter { it.isNotBlank() }.distinct()
                    val rolesList = if (distinctRoles.isNotEmpty()) {
                        distinctRoles.joinToString(", ") { RoleUtils.getRoleLabel(it) }
                    } else {
                        RoleUtils.getRoleLabel(profile.effectiveRole)
                    }
                    binding.tvProfileAllRoles.text = rolesList

                    // Show switch button ONLY if user has more than 1 role
                    binding.btnSwitchRole.visibility = if (distinctRoles.size > 1) View.VISIBLE else View.GONE
                }

                if (agreementResult is GenericResult.Success) {
                    val isAccepted = agreementResult.data.accepted
                    binding.tvAgreementStatus.text = if (isAccepted) "Статус: принято" else "Статус: требуется подтверждение"
                    binding.tvAgreementStatus.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (isAccepted) R.color.sib_success else R.color.sib_warning
                        )
                    )
                }
            }
        }
    }

    private fun showRoleSwitcherDialog() {
        val availableRoles = cachedProfile?.roles?.filter { it.isNotBlank() }?.distinct()
            ?: cachedProfile?.availableRoles?.filter { it.isNotBlank() }?.distinct()
            ?: emptyList()

        if (availableRoles.size <= 1) {
            Toast.makeText(requireContext(), "У вас только одна роль", Toast.LENGTH_SHORT).show()
            return
        }

        val roleLabels = availableRoles.map { RoleUtils.getRoleLabel(it) }.toTypedArray()
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val currentRole = prefs.getString("user_role", "student") ?: "student"
        val selectedIndex = availableRoles.indexOf(currentRole).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Смена активной роли")
            .setSingleChoiceItems(roleLabels, selectedIndex) { dialog, which ->
                val newRole = availableRoles[which]
                dialog.dismiss()
                if (newRole != currentRole) {
                    performSwitchRole(newRole)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun performSwitchRole(targetRole: String) {
        binding.swipeRefreshProfile.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.switchRole(targetRole)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.swipeRefreshProfile.isRefreshing = false

                when (result) {
                    is GenericResult.Success -> {
                        val switched = result.data
                        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("user_role", switched.active_role).apply()

                        binding.profileRoleBadge.text = RoleUtils.getRoleLabel(switched.active_role)
                        Toast.makeText(
                            requireContext(),
                            "Активная роль переключена на: ${RoleUtils.getRoleLabel(switched.active_role)}",
                            Toast.LENGTH_SHORT
                        ).show()

                        val mainActivity = activity as? MainActivity
                        mainActivity?.updateUIForRole()

                        loadFullProfile()
                    }
                    is GenericResult.Error -> {
                        Toast.makeText(requireContext(), "Не удалось сменить роль: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUserAgreementDialog() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.getUserAgreementCurrent()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                when (result) {
                    is GenericResult.Success -> {
                        val status = result.data
                        val dialog = UserAgreementDialogFragment.newInstance(status.version)
                        dialog.show(parentFragmentManager, "UserAgreementDialogFragment")
                    }
                    is GenericResult.Error -> {
                        Toast.makeText(requireContext(), "Не удалось загрузить текст соглашения", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateServerHostText() {
        val baseUrl = ServerConfig.getBaseUrl(requireContext())
        val host = baseUrl.removePrefix("https://").removePrefix("http://")
        binding.tvCurrentServerHost.text = host
    }

    private fun showLogoutConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Выход из аккаунта")
            .setMessage("Вы действительно хотите выйти из своего профиля?")
            .setPositiveButton("Выйти") { _, _ ->
                (activity as? MainActivity)?.performLogout("Вы вышли из учетной записи")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openCropDialog(uri: Uri) {
        val cropDialog = AvatarCropDialogFragment.newInstance(uri)
        cropDialog.onCropConfirmed = { croppedUri ->
            saveAvatarLocally(croppedUri)
        }
        cropDialog.show(parentFragmentManager, "AvatarCropDialog")
    }

    private fun loadAvatar() {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val avatarPath = prefs.getString("avatar_path", null)
        val syncedUrl = prefs.getString("synced_avatar_url", null)
        val avatarUrl = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("avatar_url", null)

        if (avatarPath != null) {
            val file = File(avatarPath)
            if (file.exists() && (avatarUrl == null || avatarUrl == syncedUrl)) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    binding.profileAvatar.setPadding(0, 0, 0, 0)
                    binding.profileAvatar.setImageBitmap(bitmap)
                    binding.profileAvatar.imageTintList = null
                    return
                }
            }
        }
        loadAvatarFromUrl(avatarUrl)
    }

    private fun syncAvatarFromServer(avatarUrlFromBackend: String?, forceRefresh: Boolean = false) {
        val ctx = context ?: return
        val authPrefs = ctx.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val studentPrefs = ctx.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val localFile = File(ctx.filesDir, "current_avatar.jpg")

        if (avatarUrlFromBackend.isNullOrBlank() || avatarUrlFromBackend == "null") {
            // User has no avatar on server (e.g. removed on web)
            if (localFile.exists()) {
                try { localFile.delete() } catch (_: Exception) {}
            }
            studentPrefs.edit().remove("avatar_path").remove("synced_avatar_url").apply()
            authPrefs.edit().remove("avatar_url").apply()
            val defaultPadding = (20 * resources.displayMetrics.density).toInt()
            binding.profileAvatar.setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
            binding.profileAvatar.setImageResource(R.drawable.ic_person)
            binding.profileAvatar.imageTintList = ContextCompat.getColorStateList(ctx, R.color.sib_text_secondary)
            (activity as? MainActivity)?.updateNavHeader()
            return
        }

        val lastSyncedUrl = studentPrefs.getString("synced_avatar_url", null)
        val shouldDownload = forceRefresh || (avatarUrlFromBackend != lastSyncedUrl) || !localFile.exists()

        if (!shouldDownload && localFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
            if (bitmap != null) {
                binding.profileAvatar.setPadding(0, 0, 0, 0)
                binding.profileAvatar.setImageBitmap(bitmap)
                binding.profileAvatar.imageTintList = null
                return
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalUrl = ServerConfig.resolveMediaUrl(ctx, avatarUrlFromBackend)
                Log.d("ProfileFragment", "Syncing avatar from: $finalUrl (force=$forceRefresh, lastSynced=$lastSyncedUrl)")
                val repo = StudentRepositoryHTTPS(ctx, StudentDatabase.getInstance(ctx).studentDao())
                val client = repo.getUnsafeOkHttpClient()
                val token = authPrefs.getString("auth_token", "") ?: ""
                val requestBuilder = okhttp3.Request.Builder()
                    .url(finalUrl)
                    .header("Cache-Control", "no-cache")
                if (token.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                val response = client.newCall(requestBuilder.build()).execute()

                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val file = File(ctx.filesDir, "current_avatar.jpg")
                        FileOutputStream(file).use { it.write(bytes) }
                        studentPrefs.edit()
                            .putString("avatar_path", file.absolutePath)
                            .putString("synced_avatar_url", avatarUrlFromBackend)
                            .apply()
                        authPrefs.edit().putString("avatar_url", avatarUrlFromBackend).apply()

                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        withContext(Dispatchers.Main) {
                            if (_binding == null || !isAdded) return@withContext
                            if (bitmap != null) {
                                binding.profileAvatar.setPadding(0, 0, 0, 0)
                                binding.profileAvatar.setImageBitmap(bitmap)
                                binding.profileAvatar.imageTintList = null
                            }
                            (activity as? MainActivity)?.updateNavHeader()
                        }
                    }
                } else {
                    Log.e("ProfileFragment", "Failed to download avatar: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Failed to sync avatar from backend", e)
            }
        }
    }

    private fun saveAvatarLocally(uri: Uri) {
        val internalPath = if (uri.scheme == "file" && uri.path?.contains(requireContext().filesDir.absolutePath) == true) {
            uri.path!!
        } else {
            copyUriToInternalStorage(uri) ?: return
        }

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("avatar_path", internalPath).apply()

        binding.profileAvatar.setPadding(0, 0, 0, 0)
        binding.profileAvatar.setImageURI(null)
        val bitmap = BitmapFactory.decodeFile(internalPath)
        if (bitmap != null) {
            binding.profileAvatar.setImageBitmap(bitmap)
        } else {
            binding.profileAvatar.setImageURI(Uri.fromFile(File(internalPath)))
        }
        binding.profileAvatar.imageTintList = null

        (activity as? MainActivity)?.updateNavHeader()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = repository.uploadAvatar(internalPath)
                withContext(Dispatchers.Main) {
                    if (result is AvatarResult.Success) {
                        requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("synced_avatar_url", result.avatarUrl)
                            .apply()
                        requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                            .edit().putString("avatar_url", result.avatarUrl).apply()
                        Toast.makeText(requireContext(), "Аватар успешно сохранен и обновлен", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error uploading avatar", e)
            }
        }
    }

    private fun copyUriToInternalStorage(uri: Uri): String? {
        return try {
            val context = requireContext()
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.filesDir, "current_avatar.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Failed to copy avatar", e)
            null
        }
    }

    private fun loadAvatarFromUrl(url: String?) {
        val defaultPadding = (20 * resources.displayMetrics.density).toInt()
        if (url.isNullOrBlank() || url == "null") {
            binding.profileAvatar.setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
            binding.profileAvatar.setImageResource(R.drawable.ic_person)
            binding.profileAvatar.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.sib_text_secondary)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalUrl = ServerConfig.resolveMediaUrl(requireContext(), url)
                val repo = StudentRepositoryHTTPS(requireContext(), StudentDatabase.getInstance(requireContext()).studentDao())
                val client = repo.getUnsafeOkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url(finalUrl)
                    .header("Cache-Control", "no-cache")
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return@launch
                    val file = File(requireContext().filesDir, "current_avatar.jpg")
                    try {
                        FileOutputStream(file).use { it.write(bytes) }
                        requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("avatar_path", file.absolutePath)
                            .putString("synced_avatar_url", url)
                            .apply()
                    } catch (_: Exception) {}

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.profileAvatar.setPadding(0, 0, 0, 0)
                        binding.profileAvatar.setImageBitmap(bitmap)
                        binding.profileAvatar.imageTintList = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.profileAvatar.setPadding(defaultPadding, defaultPadding, defaultPadding, defaultPadding)
                    binding.profileAvatar.setImageResource(R.drawable.ic_person)
                    binding.profileAvatar.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.sib_text_secondary)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}