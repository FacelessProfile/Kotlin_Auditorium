package com.example.kotlinroomdatabase.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"
    private const val PREFS_NAME = "secure_auth_prefs"
    private const val KEY_SAVED_LOGIN = "saved_login"
    private const val KEY_SAVED_PASSWORD = "saved_password"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveCredentials(context: Context, login: String, passwordRaw: String) {
        try {
            getEncryptedPrefs(context).edit().apply {
                putString(KEY_SAVED_LOGIN, login.trim())
                putString(KEY_SAVED_PASSWORD, passwordRaw)
                putBoolean(KEY_BIOMETRIC_ENABLED, true)
            }.apply()
            Log.d(TAG, "Credentials securely stored for user: $login")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save secure credentials", e)
        }
    }

    fun getSavedCredentials(context: Context): Pair<String, String>? {
        return try {
            val prefs = getEncryptedPrefs(context)
            val login = prefs.getString(KEY_SAVED_LOGIN, null)
            val pass = prefs.getString(KEY_SAVED_PASSWORD, null)
            val enabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

            if (enabled && !login.isNullOrBlank() && !pass.isNullOrBlank()) {
                Pair(login, pass)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve secure credentials", e)
            null
        }
    }

    fun getSavedLogin(context: Context): String? {
        return try {
            getEncryptedPrefs(context).getString(KEY_SAVED_LOGIN, null)
        } catch (e: Exception) {
            null
        }
    }

    fun hasSavedCredentials(context: Context): Boolean {
        return getSavedCredentials(context) != null
    }

    fun clearCredentials(context: Context) {
        try {
            getEncryptedPrefs(context).edit().clear().apply()
            Log.d(TAG, "Secure credentials cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear secure credentials", e)
        }
    }

    enum class QuickAuthType {
        NONE,
        FINGERPRINT,
        PIN
    }

    fun getAvailableAuthType(context: Context): QuickAuthType {
        if (!hasSavedCredentials(context)) return QuickAuthType.NONE
        return try {
            val biometricManager = BiometricManager.from(context)
            val canBiometric = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            if (canBiometric == BiometricManager.BIOMETRIC_SUCCESS) {
                QuickAuthType.FINGERPRINT
            } else if (isBiometricOrPinAvailable(context)) {
                QuickAuthType.PIN
            } else {
                QuickAuthType.NONE
            }
        } catch (e: Exception) {
            QuickAuthType.NONE
        }
    }

    fun isBiometricOrPinAvailable(context: Context): Boolean {
        return try {
            val biometricManager = BiometricManager.from(context)
            val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
            val canAuth = biometricManager.canAuthenticate(authenticators)
            canAuth == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "isBiometricOrPinAvailable check failed", e)
            false
        }
    }

    fun authenticate(
        fragment: Fragment,
        title: String = "Вход в СибГУТИ",
        subtitle: String = "Используйте отпечаток пальца, Face ID или PIN-код",
        onSuccess: (login: String, pass: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val context = fragment.requireContext()
        val creds = getSavedCredentials(context)
        if (creds == null) {
            onError("Сохранённые учётные данные отсутствуют. Выполните вход с паролем.")
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            promptBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            promptBuilder.setNegativeButtonText("Отмена")
        }

        val biometricPrompt = BiometricPrompt(
            fragment,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val currentCreds = getSavedCredentials(context)
                    if (currentCreds != null) {
                        onSuccess(currentCreds.first, currentCreds.second)
                    } else {
                        onError("Ошибка получения сохранённых данных")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "Biometric error ($errorCode): $errString")
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(TAG, "Biometric authentication failed")
                }
            }
        )

        try {
            biometricPrompt.authenticate(promptBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BiometricPrompt", e)
            onError("Не удалось запустить биометрическую аутентификацию")
        }
    }
}
