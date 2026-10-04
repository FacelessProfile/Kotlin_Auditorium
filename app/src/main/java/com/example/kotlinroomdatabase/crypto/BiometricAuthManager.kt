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
import com.example.kotlinroomdatabase.config.ServerConfig

object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"
    private const val PREFS_NAME = "secure_auth_prefs"
    private const val KEY_SAVED_LOGIN = "saved_login"
    private const val KEY_SAVED_PASSWORD = "saved_password"
    private const val KEY_SAVED_ORIGIN = "saved_origin"
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
            val currentOrigin = ServerConfig.getBaseUrl(context)
            getEncryptedPrefs(context).edit().apply {
                putString(KEY_SAVED_LOGIN, login.trim())
                putString(KEY_SAVED_PASSWORD, passwordRaw)
                putString(KEY_SAVED_ORIGIN, currentOrigin)
                putBoolean(KEY_BIOMETRIC_ENABLED, true)
            }.apply()
            Log.d(TAG, "Credentials securely stored for user: $login on origin $currentOrigin")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save secure credentials", e)
        }
    }

    fun getSavedCredentials(context: Context): Pair<String, String>? {
        return try {
            val prefs = getEncryptedPrefs(context)
            val login = prefs.getString(KEY_SAVED_LOGIN, null)
            val pass = prefs.getString(KEY_SAVED_PASSWORD, null)
            val origin = prefs.getString(KEY_SAVED_ORIGIN, null)
            val enabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
            val currentOrigin = ServerConfig.getBaseUrl(context)

            // Validate origin matches current server (KA-05 & KA-11)
            if (enabled && !login.isNullOrBlank() && !pass.isNullOrBlank() && origin == currentOrigin) {
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
            val prefs = getEncryptedPrefs(context)
            val origin = prefs.getString(KEY_SAVED_ORIGIN, null)
            val currentOrigin = ServerConfig.getBaseUrl(context)
            if (origin == currentOrigin) {
                prefs.getString(KEY_SAVED_LOGIN, null)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasSavedCredentials(context: Context): Boolean {
        return try {
            val prefs = getEncryptedPrefs(context)
            val enabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
            val hasLogin = prefs.contains(KEY_SAVED_LOGIN)
            val hasPass = prefs.contains(KEY_SAVED_PASSWORD)
            val origin = prefs.getString(KEY_SAVED_ORIGIN, null)
            val currentOrigin = ServerConfig.getBaseUrl(context)
            enabled && hasLogin && hasPass && origin == currentOrigin
        } catch (e: Exception) {
            false
        }
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
        // KA-11: Do NOT decrypt credentials before biometric authentication succeeds
        if (!hasSavedCredentials(context)) {
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
                    // Retrieve and decrypt only upon successful biometric authentication
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
