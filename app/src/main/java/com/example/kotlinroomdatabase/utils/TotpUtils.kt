package com.example.kotlinroomdatabase.utils

import org.apache.commons.codec.binary.Base32
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpUtils {
    
    /**
     * Generates a TOTP code based on a base32 encoded secret.
     * @param base32Secret The shared secret encoded in Base32.
     * @param timeWindowSeconds The time step in seconds (e.g., 30 for standard 2FA, 5 for fast regenerating QR).
     * @param codeDigits The length of the generated code (typically 6).
     * @return The formatted TOTP code.
     */
    fun generateCurrentCode(base32Secret: String, timeWindowSeconds: Int = 30, codeDigits: Int = 6): String {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val timeWindow = currentTimeSeconds / timeWindowSeconds
        return generateCodeForTime(base32Secret, timeWindow, codeDigits)
    }

    private fun generateCodeForTime(base32Secret: String, timeWindow: Long, codeDigits: Int): String {
        val base32 = Base32()
        val keyBytes = base32.decode(base32Secret)
        
        val data = ByteBuffer.allocate(8).putLong(timeWindow).array()
        
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        
        val hash = mac.doFinal(data)
        
        // Dynamic truncation
        val offset = hash[hash.size - 1].toInt() and 0xF
        var binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
                
        val otp = binary % 10.0.pow(codeDigits.toDouble()).toInt()
        
        return otp.toString().padStart(codeDigits, '0')
    }

    /**
     * Calculates the remaining seconds for the current code before it regenerates.
     */
    fun getRemainingSeconds(timeWindowSeconds: Int): Int {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        return (timeWindowSeconds - (currentTimeSeconds % timeWindowSeconds)).toInt()
    }
}
