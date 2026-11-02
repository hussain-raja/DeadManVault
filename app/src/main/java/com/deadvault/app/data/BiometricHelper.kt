package com.deadvault.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

sealed interface BiometricResult {
    data object Success : BiometricResult

    /** The Keystore key is gone or was invalidated (a new fingerprint / face was enrolled on the phone). */
    data object KeyInvalid : BiometricResult

    /** The user pressed "Use passcode" / back, or the prompt was dismissed. */
    data object Cancelled : BiometricResult

    data class Error(val message: String) : BiometricResult
}

/**
 * Biometric unlock backed by a Keystore key that can only be used right after a successful
 * Class-3 ("strong") biometric check, and that is destroyed by Android whenever a new fingerprint
 * or face is enrolled. So adding someone else's finger to the phone does NOT give them the vault.
 */
object BiometricHelper {

    private const val ALIAS = "deadvault_biometric_key"
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun createKey() {
        deleteKey()
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    fun deleteKey() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    /** A cipher ready to be unlocked by the prompt, or null if the key is missing / invalidated. */
    private fun cipherOrNull(): Cipher? = try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey
        if (key == null) {
            null
        } else {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        null
    } catch (e: Exception) {
        null
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        negativeText: String,
        onResult: (BiometricResult) -> Unit,
    ) {
        val cipher = cipherOrNull()
        if (cipher == null) {
            onResult(BiometricResult.KeyInvalid)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Actually use the unlocked key once, so success means the hardware really approved it.
                    val unlocked = result.cryptoObject?.cipher
                    val ok = unlocked != null && runCatching { unlocked.doFinal(ByteArray(1)) }.isSuccess
                    onResult(if (ok) BiometricResult.Success else BiometricResult.KeyInvalid)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onResult(if (cancelled) BiometricResult.Cancelled else BiometricResult.Error(errString.toString()))
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
