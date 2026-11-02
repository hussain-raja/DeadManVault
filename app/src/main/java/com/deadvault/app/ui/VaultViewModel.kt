package com.deadvault.app.ui

import android.app.Application
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import com.deadvault.app.data.BiometricHelper
import com.deadvault.app.data.BiometricResult
import com.deadvault.app.data.OriginalDeleter
import com.deadvault.app.data.PasscodeHasher
import com.deadvault.app.data.VaultEvents
import com.deadvault.app.data.VaultFolder
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

/** Ignore automatic biometric prompts for this long after one ended, to avoid prompt loops. */
private const val BIOMETRIC_COOLDOWN_MS = 2_000L

enum class Phase { Loading, Setup, Locked, Unlocked }

sealed interface Overlay {
    data object None : Overlay
    data class Viewer(val startIndex: Int) : Overlay
    data object Settings : Overlay
}

private data class ImportResult(
    val added: List<VaultItem>,
    val leftoverOriginals: List<Uri>,
    val failed: Int,
    val deleted: Int,
)

class VaultViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = VaultPrefs(app)
    private val store = VaultStore(app)

    var phase by mutableStateOf(Phase.Loading); private set
    var overlay by mutableStateOf<Overlay>(Overlay.None); private set

    val items = mutableStateListOf<VaultItem>()
    val folders = mutableStateListOf<VaultFolder>()

    /** null = main library. */
    var currentFolderId by mutableStateOf<String?>(null); private set
    val currentFolder: VaultFolder? get() = folders.firstOrNull { it.id == currentFolderId }

    /** The items shown in the gallery right now (main library or the open folder). */
    val visibleItems: List<VaultItem> by derivedStateOf { items.filter { it.folderId == currentFolderId } }

    var selection by mutableStateOf<Set<String>>(emptySet()); private set

    var remainingMs by mutableStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var wipeNotice by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    /** A long import / restore is running. Unlike [busy] it never blocks unlocking. */
    var working by mutableStateOf(false); private set
    var maxAttempts by mutableStateOf(prefs.maxAttempts); private set
    var intervalMs by mutableStateOf(prefs.intervalMs); private set

    var biometricEnabled by mutableStateOf(prefs.biometricEnabled); private set

    /** Bumped when the lock screen should (re)start the biometric prompt on its own. */
    var biometricTrigger by mutableStateOf(0); private set

    var autoDelete by mutableStateOf(prefs.autoDeleteOriginals); private set
    var hasAllFilesAccess by mutableStateOf(Environment.isExternalStorageManager()); private set

    /** Set when the system "allow DeadVault to delete these originals?" dialog should be shown. */
    var deleteRequest by mutableStateOf<IntentSender?>(null)

    /** Auto-lock is skipped for a few seconds after we hand the screen to a picker / system dialog. */
    private var suppressUntil = 0L

    /** Time of a stop we skipped because a picker/dialog was opening; 0 = none pending. */
    private var suppressedStopAt = 0L
    private var enforcing = false

    private var biometricShowing = false
    private var lastBiometricEnd = 0L

    /** Set by "Lock now" so the lock screen doesn't immediately pop the fingerprint prompt again. */
    private var skipNextAutoPrompt = false

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
        biometricEnabled = prefs.biometricEnabled
        autoDelete = prefs.autoDeleteOriginals
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
        refreshPermissions()
        if (phase == Phase.Loading) return
        if (phase == Phase.Locked) biometricTrigger++
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

    fun refreshPermissions() {
        hasAllFilesAccess = Environment.isExternalStorageManager()
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

    /** The lock button: lock, and wait for the user to ask for biometrics again. */
    fun lockNow() {
        skipNextAutoPrompt = true
        lock()
    }

    private fun clearSession() {
        overlay = Overlay.None
        selection = emptySet()
        items.clear()
        folders.clear()
        currentFolderId = null
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
            biometricEnabled = false
            autoDelete = prefs.autoDeleteOriginals
            wipeNotice = null
            items.clear()
            folders.clear()
            currentFolderId = null
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
            handleUnlock(outcome)
        }
    }

    private fun handleUnlock(outcome: Unlock) {
        when (outcome) {
            is Unlock.Ok -> enter(outcome)
            is Unlock.Wrong -> error = outcome.message
            Unlock.Wiped -> refreshPhaseFromDisk() // normally onWiped() has already done this
        }
    }

    private fun enter(ok: Unlock.Ok) {
        items.clear()
        items.addAll(ok.items)
        folders.clear()
        folders.addAll(ok.folders)
        currentFolderId = null
        tick()
        phase = Phase.Unlocked
    }

    private sealed interface Unlock {
        data class Ok(val items: List<VaultItem>, val folders: List<VaultFolder>) : Unlock
        data class Wrong(val message: String) : Unlock
        data object Wiped : Unlock
    }

    private fun loadVault(): Unlock.Ok {
        store.purgeOrphans()
        val loadedFolders = store.listFolders()
        val folderIds = loadedFolders.map { it.id }.toSet()
        // An item whose folder record is missing is shown in the main library instead of vanishing.
        val loadedItems = store.listItems().map { item ->
            val fid = item.folderId
            if (fid != null && fid !in folderIds) item.copy(folderId = null) else item
        }
        return Unlock.Ok(loadedItems, loadedFolders)
    }

    private fun tryUnlock(passcode: String): Unlock {
        // Deadline first: a vault past its deadline must never open, even with the right passcode.
        if (DeadlineEnforcer.check(app)) return Unlock.Wiped

        val salt = prefs.salt
        val hash = prefs.hash
        if (salt == null || hash == null) return Unlock.Wrong("Vault is not set up.")

        if (PasscodeHasher.verify(passcode, salt, hash, prefs.iterations)) {
            DeadlineEnforcer.markUnlocked(app)
            return loadVault()
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

    // ----------------------------------------------------------- biometrics

    /** Shows the fingerprint / face prompt on the lock screen. */
    fun requestBiometric(activity: FragmentActivity, auto: Boolean) {
        if (phase != Phase.Locked || !biometricEnabled || biometricShowing || busy) return
        // BiometricPrompt silently does nothing (and never calls back) unless the activity is on screen.
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.supportFragmentManager.isStateSaved
        ) return
        if (auto && skipNextAutoPrompt) {
            skipNextAutoPrompt = false
            return
        }
        if (auto && SystemClock.elapsedRealtime() - lastBiometricEnd < BIOMETRIC_COOLDOWN_MS) return
        biometricShowing = true
        BiometricHelper.authenticate(activity, "Unlock DeadVault", "Use passcode") { result ->
            biometricShowing = false
            lastBiometricEnd = SystemClock.elapsedRealtime()
            when (result) {
                BiometricResult.Success -> unlockWithBiometric()
                BiometricResult.KeyInvalid -> disableBiometricBecauseInvalidated()
                BiometricResult.Cancelled -> Unit
                is BiometricResult.Error -> error = result.message
            }
        }
    }

    private fun unlockWithBiometric() {
        // A wipe may have happened while the prompt was open: never open a vault that no longer exists.
        if (busy || phase != Phase.Locked || !prefs.isSetUp) {
            refreshPhaseFromDisk()
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (DeadlineEnforcer.check(app) || !prefs.isSetUp) {
                    Unlock.Wiped
                } else {
                    DeadlineEnforcer.markUnlocked(app)
                    loadVault()
                }
            }
            busy = false
            handleUnlock(outcome)
        }
    }

    private fun disableBiometricBecauseInvalidated() {
        BiometricHelper.deleteKey()
        biometricEnabled = false
        viewModelScope.launch(Dispatchers.IO) { prefs.biometricEnabled = false }
        error = "Biometric unlock was turned off because the phone's fingerprints or face data changed. " +
            "Unlock with your passcode, then switch it on again in Settings."
    }

    /** Settings switch. Turning it on asks for one biometric check to prove it works. */
    fun setBiometric(enable: Boolean, activity: FragmentActivity?) {
        if (!enable) {
            BiometricHelper.deleteKey()
            biometricEnabled = false
            viewModelScope.launch(Dispatchers.IO) { prefs.biometricEnabled = false }
            return
        }
        if (activity == null) return
        if (!BiometricHelper.isAvailable(app)) {
            message = "No strong fingerprint or face unlock is set up on this phone. Add one in Android settings first."
            return
        }
        try {
            BiometricHelper.createKey()
        } catch (e: Exception) {
            message = "This phone couldn't create a biometric key."
            return
        }
        BiometricHelper.authenticate(activity, "Confirm to turn on biometric unlock", "Cancel") { result ->
            when (result) {
                BiometricResult.Success -> {
                    biometricEnabled = true
                    viewModelScope.launch(Dispatchers.IO) { prefs.biometricEnabled = true }
                    message = "Biometric unlock is on."
                }
                is BiometricResult.Error -> {
                    BiometricHelper.deleteKey()
                    message = result.message
                }
                else -> BiometricHelper.deleteKey()
            }
        }
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

    /** [index] is a position in [visibleItems]. */
    fun openViewer(index: Int) {
        overlay = Overlay.Viewer(index)
    }

    fun openSettings() {
        refreshPermissions()
        overlay = Overlay.Settings
    }

    fun closeOverlay() {
        overlay = Overlay.None
        clearPlaybackAsync()
    }

    fun folderItemCount(folderId: String): Int = items.count { it.folderId == folderId }

    // ----------------------------------------------------------- selection

    fun toggleSelected(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectAllVisible() {
        selection = visibleItems.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    // -------------------------------------------------------------- folders

    fun openFolder(id: String?) {
        selection = emptySet()
        currentFolderId = id
    }

    /** Creates a folder; with [moveSelection] the currently selected items are moved into it. */
    fun createFolder(name: String, moveSelection: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val folder = withContext(Dispatchers.IO) { runCatching { store.createFolder(clean) }.getOrNull() }
            if (folder == null) {
                message = "Couldn't create the folder."
                return@launch
            }
            folders.add(folder)
            folders.sortBy { it.name.lowercase() }
            if (moveSelection) moveSelected(folder.id) else message = "Folder \"${folder.name}\" created."
        }
    }

    fun renameFolder(id: String, name: String) {
        val clean = name.trim()
        val folder = folders.firstOrNull { it.id == id } ?: return
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { runCatching { store.renameFolder(folder, clean) }.getOrNull() }
            if (updated == null) {
                message = "Couldn't rename the folder."
                return@launch
            }
            val i = folders.indexOfFirst { it.id == id }
            if (i >= 0) folders[i] = updated
            folders.sortBy { it.name.lowercase() }
        }
    }

    /** Deleting a folder never deletes its photos: they go back to the main library. */
    fun deleteFolder(id: String) {
        val folder = folders.firstOrNull { it.id == id } ?: return
        val inside = items.filter { it.folderId == id }
        viewModelScope.launch {
            val moved = withContext(Dispatchers.IO) {
                runCatching {
                    val result = inside.map { it.copy(folderId = null).also { updated -> store.updateItem(updated) } }
                    store.deleteFolder(id)
                    result
                }.getOrNull()
            }
            if (moved == null) {
                message = "Couldn't delete the folder. Nothing was lost."
                return@launch
            }
            replaceItems(moved)
            folders.remove(folder)
            if (currentFolderId == id) currentFolderId = null
            message = if (moved.isEmpty()) {
                "Folder deleted."
            } else {
                "Folder deleted. Its ${moved.size} items are back in the main library."
            }
        }
    }

    /** Moves the selected items to [folderId] (null = main library). */
    fun moveSelected(folderId: String?) {
        val ids = selection
        val targets = items.filter { it.id in ids && it.folderId != folderId }
        if (targets.isEmpty()) {
            selection = emptySet()
            return
        }
        val label = folders.firstOrNull { it.id == folderId }?.name ?: "the main library"
        viewModelScope.launch {
            val moved = withContext(Dispatchers.IO) {
                // Per item, so one failure doesn't stop the rest; only items that really moved are updated.
                targets.mapNotNull { item ->
                    val updated = item.copy(folderId = folderId)
                    if (runCatching { store.updateItem(updated) }.isSuccess) updated else null
                }
            }
            replaceItems(moved)
            selection = emptySet()
            message = "Moved ${moved.size} to $label."
        }
    }

    private fun replaceItems(updated: List<VaultItem>) {
        updated.forEach { u ->
            val i = items.indexOfFirst { it.id == u.id }
            if (i >= 0) items[i] = u
        }
    }

    // --------------------------------------------------- import / restore

    fun importUris(uris: List<Uri>) {
        expireIfStale()
        if (uris.isEmpty() || phase != Phase.Unlocked) return
        val target = currentFolderId
        val silent = autoDelete && OriginalDeleter.canDeleteSilently()
        working = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<VaultItem>()
                val leftovers = mutableListOf<Uri>()
                var failed = 0
                var deleted = 0
                for (uri in uris) {
                    val item = store.import(uri, target)
                    if (item == null) {
                        failed++
                        continue
                    }
                    // Read the encrypted copy back before anything else happens to the original. A copy that
                    // doesn't check out is thrown away and the original is left exactly where it was.
                    if (!store.verify(item)) {
                        store.delete(item.id)
                        failed++
                        continue
                    }
                    added += item
                    if (silent && OriginalDeleter.delete(app, uri)) {
                        deleted++
                    } else {
                        leftovers += uri
                    }
                }
                ImportResult(added, leftovers, failed, deleted)
            }
            working = false
            // The vault may have been locked (or wiped) while this ran; the items are stored and will
            // appear at the next unlock, so there is nothing to show now.
            if (phase != Phase.Unlocked) return@launch

            items.addAll(0, result.added)
            message = buildString {
                append("Added ${result.added.size} to the vault.")
                if (result.deleted > 0) append(" Removed ${result.deleted} from your gallery.")
                if (silent && result.leftoverOriginals.isNotEmpty()) {
                    append(" ${result.leftoverOriginals.size} originals couldn't be removed automatically.")
                }
                if (result.failed > 0) append(" ${result.failed} couldn't be imported; those were not touched.")
            }
            // Anything that couldn't be removed silently falls back to Android's confirmation dialog
            // (only ever for items whose encrypted copy has been verified).
            offerOriginalsDeletion(result.leftoverOriginals)
        }
    }

    private fun offerOriginalsDeletion(sources: List<Uri>) {
        val mediaUris = sources.mapNotNull { OriginalDeleter.toMediaStoreUri(it) }
        if (mediaUris.isEmpty()) return
        runCatching {
            deleteRequest = MediaStore.createDeleteRequest(app.contentResolver, mediaUris).intentSender
        }
    }

    fun restoreSelected() = restore(selection.toList())

    fun restore(ids: List<String>) {
        val targets = items.filter { it.id in ids }
        if (targets.isEmpty()) return
        working = true
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                targets.filter { item -> store.exportToGallery(item).also { ok -> if (ok) store.delete(item.id) } }
            }
            working = false
            if (phase != Phase.Unlocked) return@launch
            items.removeAll(restored.toSet())
            selection = emptySet()
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

    fun setAutoDelete(enabled: Boolean) {
        autoDelete = enabled
        viewModelScope.launch(Dispatchers.IO) { prefs.autoDeleteOriginals = enabled }
    }

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
