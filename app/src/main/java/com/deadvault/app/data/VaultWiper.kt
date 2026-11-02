package com.deadvault.app.data

import android.content.Context
import androidx.security.crypto.MasterKey
import com.deadvault.app.work.DeadlineEnforcer
import com.deadvault.app.work.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.KeyStore

/** Lets the UI learn about a wipe that happened in the background. */
object VaultEvents {
    private val _wipes = MutableStateFlow(0)
    val wipes: StateFlow<Int> = _wipes
    fun wiped() {
        _wipes.value += 1
    }
}

object VaultWiper {

    private val lock = Any()
    private const val ENCRYPTED_FILE_PREFS = "__androidx_security_crypto_encrypted_file_pref__"

    /**
     * Irreversibly destroys the vault.
     *
     * 1. Mark the wipe as pending (so a crash half-way through is finished on next launch).
     * 2. Crypto-erase: delete the Keystore master key and the wrapped file keyset. From this
     *    moment the encrypted files are unreadable even if the storage still holds the bytes.
     * 3. Delete the encrypted files and any decrypted playback copy.
     * 4. Reset all state, keeping only a note saying why.
     */
    fun wipe(context: Context, reason: String) = synchronized(lock) {
        val app = context.applicationContext
        val prefs = VaultPrefs(app)

        prefs.beginWipe(reason)

        var keyGone = false
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            keyGone = !keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        BiometricHelper.deleteKey()
        app.deleteSharedPreferences(ENCRYPTED_FILE_PREFS)
        File(app.dataDir, "shared_prefs").listFiles()
            ?.filter { it.name.contains("androidx_security_crypto") }
            ?.forEach { it.delete() }

        val vaultDir = File(app.filesDir, VaultStore.DIR_NAME)
        vaultDir.deleteRecursively()
        File(app.cacheDir, VaultStore.PLAY_DIR).deleteRecursively()

        // Only report success if the key and the files are really gone. Otherwise the wipe stays
        // pending and the next deadline check retries it.
        if (!keyGone || !vaultDir.listFiles().isNullOrEmpty()) return@synchronized

        prefs.finishWipe(reason, System.currentTimeMillis())

        DeadlineEnforcer.cancelAll(app)
        Notifications.postWiped(app)
        VaultEvents.wiped()
    }
}
