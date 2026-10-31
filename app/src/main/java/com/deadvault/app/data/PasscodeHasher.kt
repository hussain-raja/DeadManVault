package com.deadvault.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2-HMAC-SHA256. Only used to check the passcode, never to encrypt anything. */
object PasscodeHasher {

    const val ITERATIONS = 150_000
    const val MIN_LENGTH = 6

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(passcode: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(passcode: String, salt: ByteArray, expected: ByteArray, iterations: Int): Boolean =
        MessageDigest.isEqual(hash(passcode, salt, iterations), expected)
}
