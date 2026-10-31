package com.deadvault.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Small, non-secret state: passcode hash (PBKDF2, salted), the check-in interval and the
 * timestamps the dead-man's-switch needs. Nothing here can decrypt the vault.
 */
class VaultPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val isSetUp: Boolean get() = sp.getBoolean(K_SETUP, false)

    val salt: ByteArray? get() = sp.getString(K_SALT, null)?.let { Base64Compat.decode(it) }
    val hash: ByteArray? get() = sp.getString(K_HASH, null)?.let { Base64Compat.decode(it) }
    val iterations: Int get() = sp.getInt(K_ITER, PasscodeHasher.ITERATIONS)

    var intervalMs: Long
        get() = sp.getLong(K_INTERVAL, DEFAULT_INTERVAL_MS)
        set(v) = sp.edit().putLong(K_INTERVAL, v).commit().let { }

    val lastUnlockWall: Long get() = sp.getLong(K_LAST_WALL, 0L)

    // Monotonic anchor: "during boot [anchorBoot], when elapsedRealtime was [anchorElapsed],
    // the effective time was [anchorNow]". Within one boot, time is anchorNow + elapsed delta,
    // so changing the phone's clock has no effect.
    val anchorBoot: Int get() = sp.getInt(K_ANCHOR_BOOT, -1)
    val anchorElapsed: Long get() = sp.getLong(K_ANCHOR_ELAPSED, 0L)
    val anchorNow: Long get() = sp.getLong(K_ANCHOR_NOW, 0L)

    fun setAnchor(boot: Int, elapsed: Long, now: Long) {
        sp.edit()
            .putInt(K_ANCHOR_BOOT, boot)
            .putLong(K_ANCHOR_ELAPSED, elapsed)
            .putLong(K_ANCHOR_NOW, now)
            .apply()
    }

    /** Latest effective time persisted (at most ~60 s stale). It is the floor used after a reboot. */
    var maxWallSeen: Long
        get() = sp.getLong(K_MAX_WALL, 0L)
        set(v) = sp.edit().putLong(K_MAX_WALL, v).apply()

    var failedAttempts: Int
        get() = sp.getInt(K_FAILED, 0)
        set(v) = sp.edit().putInt(K_FAILED, v).commit().let { }

    /** Wrong passcodes allowed in a row before a wipe. 0 = never wipe on wrong guesses. */
    var maxAttempts: Int
        get() = sp.getInt(K_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
        set(v) = sp.edit().putInt(K_MAX_ATTEMPTS, v).commit().let { }

    var warned: Boolean
        get() = sp.getBoolean(K_WARNED, false)
        set(v) = sp.edit().putBoolean(K_WARNED, v).apply()

    val pendingWipe: Boolean get() = sp.getBoolean(K_PENDING_WIPE, false)
    val pendingReason: String? get() = sp.getString(K_PENDING_REASON, null)

    val wipeNotice: String? get() = sp.getString(K_NOTICE, null)
    val wipeNoticeAt: Long get() = sp.getLong(K_NOTICE_AT, 0L)

    /** Flipped last during setup, so a half-written vault is never treated as live. */
    fun setSetUp(value: Boolean) {
        sp.edit().putBoolean(K_SETUP, value).commit()
    }

    fun clearWipeNotice() {
        sp.edit().remove(K_NOTICE).remove(K_NOTICE_AT).commit()
    }

    /** First-time setup or passcode change. */
    fun saveCredential(salt: ByteArray, hash: ByteArray, iterations: Int, setUp: Boolean = true) {
        sp.edit()
            .putString(K_SALT, Base64Compat.encode(salt))
            .putString(K_HASH, Base64Compat.encode(hash))
            .putInt(K_ITER, iterations)
            .putBoolean(K_SETUP, setUp)
            .remove(K_NOTICE)
            .remove(K_NOTICE_AT)
            .commit()
    }

    /** A successful passcode entry: restart the countdown and forgive earlier wrong guesses. */
    fun markUnlocked(nowMs: Long, elapsedMs: Long, boot: Int) {
        sp.edit()
            .putLong(K_LAST_WALL, nowMs)
            .putInt(K_ANCHOR_BOOT, boot)
            .putLong(K_ANCHOR_ELAPSED, elapsedMs)
            .putLong(K_ANCHOR_NOW, nowMs)
            .putLong(K_MAX_WALL, maxOf(nowMs, maxWallSeen))
            .putInt(K_FAILED, 0)
            .putBoolean(K_WARNED, false)
            .commit()
    }

    /** Written before any destructive step so an interrupted wipe is finished on next launch. */
    fun beginWipe(reason: String) {
        sp.edit().putBoolean(K_PENDING_WIPE, true).putString(K_PENDING_REASON, reason).commit()
    }

    /** Back to a blank slate, leaving only a note explaining what happened. */
    fun finishWipe(reason: String, atMs: Long) {
        sp.edit().clear()
            .putString(K_NOTICE, reason)
            .putLong(K_NOTICE_AT, atMs)
            .commit()
    }

    companion object {
        private const val NAME = "deadvault_state"
        private const val K_SETUP = "setup"
        private const val K_SALT = "salt"
        private const val K_HASH = "hash"
        private const val K_ITER = "iter"
        private const val K_INTERVAL = "interval_ms"
        private const val K_LAST_WALL = "last_unlock_wall"
        private const val K_ANCHOR_BOOT = "anchor_boot"
        private const val K_ANCHOR_ELAPSED = "anchor_elapsed"
        private const val K_ANCHOR_NOW = "anchor_now"
        private const val K_MAX_WALL = "max_wall_seen"
        private const val K_FAILED = "failed_attempts"
        private const val K_MAX_ATTEMPTS = "max_attempts"
        private const val K_WARNED = "warned"
        private const val K_PENDING_WIPE = "pending_wipe"
        private const val K_PENDING_REASON = "pending_reason"
        private const val K_NOTICE = "wipe_notice"
        private const val K_NOTICE_AT = "wipe_notice_at"

        const val DEFAULT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}

/** android.util.Base64 wrapper so the rest of the code stays tidy. */
internal object Base64Compat {
    fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun decode(s: String): ByteArray = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}
