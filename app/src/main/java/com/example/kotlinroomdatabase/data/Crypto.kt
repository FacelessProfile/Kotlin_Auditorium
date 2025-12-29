package com.example.kotlinroomdatabase.data

import android.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object Crypto {
    private const val SERVER_PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvNnAQd+SLd8B7beYvJBR4XgYWTgzWJ/cgrSFvSxMvDHXwNQneA6twQuuBpuNuG6wB176oFuyNNXjHJ04KZZdp/UdVuJ1X4nfWg3wZ8zRPqI8zy7eJpmZOShTisQnp7UuJrs0eek5x29YkXxDV4/lIB4LnYXzGnX9sFhJi2uv4of2eqxy7MctCwt0UwPJjr3c4OyJ++m+sJk/VmkognW2xmVfhqWG2juxhoqcygGGYYEZDiqVcRzuKIgmsg9qtjEKDXOisYwBjifwOVR9MgGCn+tSCo/WqbtdEhe9Lcm3ivOYGsFsvHFr7p6v4u4fi5lL6yt5avq+yDT7npWsQVRfFQIDAQAB"

    fun encryptPassword(password: String): String {
        return try {
            val keyBytes = Base64.decode(SERVER_PUBLIC_KEY_B64, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(spec)

            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)

            val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}