package com.deadvault.app.ui

import android.app.Application
import android.content.ContentUris
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deadvault.app.data.PasscodeHasher
import com.deadvault.app.data.VaultEvents
import com.deadvault.app.data.VaultItem
import com.deadvault.app.data.VaultPrefs
import com.deadvault.app.data.VaultStore
import com.deadvault.app.data.VaultWiper
import com.deadvault.app.work.DeadlineEnforcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How long the user may stay in a picker/dialog we opened before the vault re-locks on return. */
private const val EXTERNAL_GRACE_MS = 5 * 60 * 1000L

enum class Phase { Loading, Setup, Locked, Unlocked }

sealed interface Overlay {
    data object None : Overlay
    data class Viewer(val startIndex: Int) : Overlay
    data object Settings : Overlay
}

class VaultViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = VaultPrefs(app)
    private val store = VaultStore(app)

    var phase by mutableStateOf(Phase.Loading); private set
    var overlay by mutableStateOf<Overlay>(Overlay.None); private set
    val items = mutableStateListOf<VaultItem>()
    var selection by mutableStateOf<Set<String>>(emptySet()); private set

    var remainingMs by mutableStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var wipeNotice by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var maxAttempts by mutableStateOf(prefs.maxAttempts); private set
    var intervalMs by mutableStateOf(prefs.intervalMs); private set

    /** Set when the system "allow DeadVault to delete these originals?" dialog should be shown. */
    var deleteRequest by mutableStateOf<IntentSender?>(null)

    /** Auto-lock is skipped for a few seconds after we hand the screen to a picker / system dialog. */
    private var suppressUntil = 0L

    /** Time of a stop we skipped because a picker/dialog was opening; 0 = none pending. */
    private var suppressedStopAt = 0L
    private var enforcing = false
    private val thumbs = object : LruCache<String, Bitmap>(96) {}

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DeadlineEnforcer.check(app)
                store.clearPlayback()
            }
            refreshPhaseFromDisk()
        }
        viewModelScope.launch {
            VaultEvents.wipes.drop(1).collect { onWiped() }
        }
    }

    private fun refreshPhaseFromDisk() {
        wipeNotice = prefs.wipeNotice
        phase = if (prefs.isSetUp) Phase.Locked else Phase.Setup
        maxAttempts = prefs.maxAttempts
        intervalMs = prefs.intervalMs
    }

    private fun onWiped() {
        clearSession()
        refreshPhaseFromDisk()
    }

    // ------------------------------------------------------------- lifecycle

    /** Called by the activity right before it launches a picker / system dialog. */
    fun suppressLock() {
        suppressUntil = SystemClock.elapsedRealtime() + 10_000
    }

    fun onResumed() {
        expireIfStale()
        suppressedStopAt = 0L
        if (phase == Phase.Loading) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DeadlineEnforcer.check(app) }
            tick()
        }
    }

    fun onStopped() {
        val t = SystemClock.elapsedRealtime()
        if (t < suppressUntil) {
            suppressedStopAt = t
            return
        }
        if (phase == Phase.Unlocked) lock()
    }

    /**
     * If the user left the app while one of our pickers was open (so auto-lock was skipped) and
     * only comes back after a long time, the vault must not still be open.
     */
    private fun expireIfStale() {
        val t = suppressedStopAt
        if (t != 0L && SystemClock.elapsedRealtime() - t > EXTERNAL_GRACE_MS) lock()
    }

    private fun clearPlaybackAsync() {
        viewModelScope.launch(Dispatchers.IO) { store.clearPlayback() }
    }

    fun tick() {
        if (phase == Phase.Loading || phase == Phase.Setup) return
        val remaining = DeadlineEnforcer.remainingMs(app) ?: return
        remainingMs = remaining
        if (remaining <= 0 && !enforcing) {
            enforcing = true
            viewModelScope.launch {
                withContext(Dispatchers.IO) { DeadlineEnforcer.check(app) }
                enforcing = false
            }
        }
    }

    fun lock() {
        clearSession()
        if (prefs.isSetUp) phase = Phase.Locked
    }

    private fun clearSession() {
        overlay = Overlay.None
        selection = emptySet()
        items.clear()
        thumbs.evictAll()
        deleteRequest = null
        error = null
        suppressedStopAt = 0L
        clearPlaybackAsync()
    }

    // ------------------------------------------------------- setup / unlock

    fun setup(passcode: String, confirm: String, intervalMs: Long) {
        error = when {
            passcode.length < PasscodeHasher.MIN_LENGTH -> "Use at least ${PasscodeHasher.MIN_LENGTH} characters."
            passcode != confirm -> "The two passcodes don't match."
            else -> null
        }
        if (error != null) return
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val salt = PasscodeHasher.newSalt()
                val hash = PasscodeHasher.hash(passcode, salt)
                DeadlineEnforcer.atomically {
                    // "Set up" is switched on last, so a deadline check never sees a half-written vault.
                    prefs.saveCredential(salt, hash, PasscodeHasher.ITERATIONS, setUp = false)
                    prefs.intervalMs = intervalMs
                    DeadlineEnforcer.markUnlocked(app)
                    prefs.setSetUp(true)
                    DeadlineEnforcer.schedule(app)
                }
            }
            this@VaultViewModel.intervalMs = intervalMs
            maxAttempts = prefs.maxAttempts
            wipeNotice = null
            items.clear()
            busy = false
            tick()
            phase = Phase.Unlocked
        }
    }

    fun unlock(passcode: String) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { tryUnlock(passcode) }
            busy = false
            when (outcome) {
                is Unlock.Ok -> {
                    items.clear()
                    items.addAll(outcome.items)
                    tick()
                    phase = Phase.Unlocked
                }
                is Unlock.Wrong -> error = outcome.message
                Unlock.Wiped -> refreshPhaseFromDisk() // normally onWiped() has already done this
            }
        }
    }

    private sealed interface Unlock {
        data class Ok(val items: List<VaultItem>) : Unlock
        data class Wrong(val message: String) : Unlock
        data object Wiped : Unlock
    }

    private fun tryUnlock(passcode: String): Unlock {
        // Deadline first: a vault past its deadline must never open, even with the right passcode.
        if (DeadlineEnforcer.check(app)) return Unlock.Wiped

        val salt = prefs.salt
        val hash = prefs.hash
        if (salt == null || hash == null) return Unlock.Wrong("Vault is not set up.")

        if (PasscodeHasher.verify(passcode, salt, hash, prefs.iterations)) {
            DeadlineEnforcer.markUnlocked(app)
            store.purgeOrphans()
            return Unlock.Ok(store.listItems())
        }

        val failed = prefs.failedAttempts + 1
        prefs.failedAttempts = failed
        val limit = prefs.maxAttempts
        if (limit > 0 && failed >= limit) {
            VaultWiper.wipe(app, DeadlineEnforcer.REASON_ATTEMPTS)
            return Unlock.Wiped
        }
        return Unlock.Wrong(
            if (limit > 0) {
                val left = limit - failed
                "Incorrect passcode. $left ${if (left == 1) "attempt" else "attempts"} left before the vault is wiped."
            } else {
                "Incorrect passcode."
            },
        )
    }

    // ------------------------------------------------------------ browsing

    suspend fun thumb(id: String): Bitmap? {
        thumbs.get(id)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { store.thumbnail(id) }
        if (bmp != null) thumbs.put(id, bmp)
        return bmp
    }

    suspend fun fullImage(item: VaultItem): Bitmap? = withContext(Dispatchers.IO) { store.image(item.id) }

    suspend fun videoFile(item: VaultItem): File? = withContext(Dispatchers.IO) { store.decryptForPlayback(item) }

    fun discardVideoFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) { store.deletePlayback(file) }
    }

    fun openViewer(index: Int) {
        overlay = Overlay.Viewer(index)
    }

    fun openSettings() {
        overlay = Overlay.Settings
    }

    fun closeOverlay() {
        overlay = Overlay.None
        clearPlaybackAsync()
    }

    // ----------------------------------------------------------- selection

    fun toggleSelected(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    // --------------------------------------------------- import / restore

    fun importUris(uris: List<Uri>) {
        expireIfStale()
        if (uris.isEmpty() || phase != Phase.Unlocked) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val done = mutableListOf<VaultItem>()
                val sources = mutableListOf<Uri>()
                var failed = 0
                for (uri in uris) {
                    val item = store.import(uri)
                    if (item != null) {
                        done += item
                        sources += uri
                    } else {
                        failed++
                    }
                }
                Triple(done, sources, failed)
            }
            val (done, sources, failed) = result
            items.addAll(0, done)
            busy = false
            message = buildString {
                append("Added ${done.size} to the vault.")
                if (failed > 0) append(" $failed couldn't be imported.")
            }
            offerOriginalsDeletion(sources)
        }
    }

    /**
     * The originals are still in the gallery after import. Ask Android to delete them (the user
     * confirms in a system dialog). Only items chosen through the system Photos/Videos browser
     * can be mapped back to MediaStore; anything else is left alone.
     */
    private fun offerOriginalsDeletion(sources: List<Uri>) {
        val mediaUris = sources.mapNotNull { toMediaStoreUri(it) }
        if (mediaUris.isEmpty()) return
        runCatching {
            deleteRequest = MediaStore.createDeleteRequest(app.contentResolver, mediaUris).intentSender
        }
    }

    private fun toMediaStoreUri(uri: Uri): Uri? {
        if (uri.authority != "com.android.providers.media.documents") return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val (kind, idStr) = docId.split(":", limit = 2).let { if (it.size == 2) it[0] to it[1] else return null }
        val id = idStr.toLongOrNull() ?: return null
        val base = when (kind) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        return ContentUris.withAppendedId(base, id)
    }

    fun restoreSelected() = restore(selection.toList())

    fun restore(ids: List<String>) {
        val targets = items.filter { it.id in ids }
        if (targets.isEmpty()) return
        busy = true
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                targets.filter { item -> store.exportToGallery(item).also { ok -> if (ok) store.delete(item.id) } }
            }
            items.removeAll(restored.toSet())
            selection = emptySet()
            busy = false
            message = "Restored ${restored.size} to your gallery (Pictures / Movies › DeadVault Restored)."
        }
    }

    fun deleteSelected() {
        val ids = selection
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ids.forEach { store.delete(it) } }
            items.removeAll { it.id in ids }
            selection = emptySet()
            message = "Deleted ${ids.size} permanently."
        }
    }

    // ------------------------------------------------------------ settings

    fun changeInterval(ms: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DeadlineEnforcer.atomically {
                    prefs.intervalMs = ms
                    DeadlineEnforcer.markUnlocked(app) // new interval starts counting from now
                }
            }
            intervalMs = ms
            tick()
            message = "Check-in interval updated. The countdown restarted."
        }
    }

    fun changeMaxAttempts(n: Int) {
        maxAttempts = n
        viewModelScope.launch(Dispatchers.IO) { prefs.maxAttempts = n }
    }

    fun changePasscode(current: String, new: String, confirm: String, onDone: (String?) -> Unit) {
        if (new.length < PasscodeHasher.MIN_LENGTH) return onDone("Use at least ${PasscodeHasher.MIN_LENGTH} characters.")
        if (new != confirm) return onDone("The new passcodes don't match.")
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                val salt = prefs.salt
                val hash = prefs.hash
                if (salt == null || hash == null || !PasscodeHasher.verify(current, salt, hash, prefs.iterations)) {
                    "Current passcode is incorrect."
                } else {
                    val newSalt = PasscodeHasher.newSalt()
                    prefs.saveCredential(newSalt, PasscodeHasher.hash(new, newSalt), PasscodeHasher.ITERATIONS)
                    null
                }
            }
            if (err == null) message = "Passcode changed."
            onDone(err)
        }
    }

    /** Verifies the passcode, then destroys everything. */
    fun wipeNow(passcode: String, onWrong: () -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val salt = prefs.salt
                val hash = prefs.hash
                val good = salt != null && hash != null && PasscodeHasher.verify(passcode, salt, hash, prefs.iterations)
                if (good) VaultWiper.wipe(app, DeadlineEnforcer.REASON_MANUAL)
                good
            }
            if (!ok) onWrong()
        }
    }
}
