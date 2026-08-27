package com.example.kotlinroomdatabase.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Manages asymmetric ECDSA keys within the hardware-backed Android KeyStore (TEE/StrongBox).
 * 
 * Cryptographic guarantee:
 * The private key never leaves the hardware security module and cannot be extracted.
 * It is unlocked on-device solely upon successful biometric authentication.
 * Server stores only the public key (Digital Signature / PEP), with zero biometric data stored server-side.
 */
object BiometricKeyManager {
    private const val TAG = "BiometricKeyManager"
    private const val KEY_ALIAS = "student_attendance_ecdsa_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun getOrCreatePublicKey(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKeyPair()
        }

        val certificate = keyStore.getCertificate(KEY_ALIAS)
        val publicKey = certificate.publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    private fun generateKeyPair() {
        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )

            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0, // Requires authentication for every single signature invocation
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            keyPairGenerator.initialize(builder.build())
            keyPairGenerator.generateKeyPair()
            Log.d(TAG, "New hardware-backed ECDSA keypair successfully generated in KeyStore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate KeyStore keypair: ${e.message}", e)
        }
    }

    fun initSignature(): Signature? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateKeyPair()
            }
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey ?: return null
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "initSignature failed: ${e.message}", e)
            null
        }
    }

    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteKey error: ${e.message}")
        }
    }
}
