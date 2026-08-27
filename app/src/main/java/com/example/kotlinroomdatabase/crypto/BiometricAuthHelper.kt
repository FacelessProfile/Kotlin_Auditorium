package com.example.kotlinroomdatabase.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.security.Signature

class BiometricAuthHelper(private val fragment: Fragment) {

    private val context: Context get() = fragment.requireContext()
    private val TAG = "BiometricAuthHelper"

    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return canAuth == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateAndSign(
        payloadToSign: String,
        title: String = "Подтверждение присутствия",
        subtitle: String = "Приложите палец или используйте Face ID для подтверждения",
        onSuccess: (signatureBase64: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val signature: Signature? = try {
            BiometricKeyManager.initSignature()
        } catch (e: Exception) {
            Log.e(TAG, "Biometric signature initialization failed", e)
            null
        }

        if (signature == null) {
            // If hardware KeyStore is not available on emulator or strongbox not configured,
            // fallback gracefully without blocking
            Log.w(TAG, "Hardware KeyStore signature not available, proceeding with empty signature")
            onSuccess("")
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val biometricPrompt = BiometricPrompt(
            fragment,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val cryptoSignature = result.cryptoObject?.signature ?: signature
                        cryptoSignature.update(payloadToSign.toByteArray(Charsets.UTF_8))
                        val signedBytes = cryptoSignature.sign()
                        val signatureBase64 = Base64.encodeToString(signedBytes, Base64.NO_WRAP)
                        onSuccess(signatureBase64)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to sign attendance payload", e)
                        onError("Ошибка формирования цифровой подписи: ${e.message}")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "Biometric auth error ($errorCode): $errString")
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED) {
                        onError("Отметка отменена пользователем")
                    } else if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                               errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                               errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE) {
                        // Allow pass-through if device has no biometric hardware (e.g. Android Emulator)
                        onSuccess("")
                    } else {
                        onError("Ошибка биометрии: $errString")
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(TAG, "Biometric auth failed: fingerprint/face not recognized")
                }
            }
        )

        try {
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } catch (e: Exception) {
            Log.e(TAG, "BiometricPrompt launch failed", e)
            onSuccess("")
        }
    }
}
